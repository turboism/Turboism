package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.cubism.EditorExitResult;
import dev.turboism.sdk.cubism.EditorLifecycleSnapshot;
import dev.turboism.sdk.cubism.hook.EditorLifecycleHooks;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runtime-owned coordinator for editor startup and pre-shutdown lifecycle callbacks. */
public final class EditorLifecycleCoordinator implements AutoCloseable {

    public static final String OPERATION_ID = "cubism.editor.lifecycle";

    private final CopyOnWriteArrayList<PluginHooks> plugins = new CopyOnWriteArrayList<>();
    private final PluginWorkExecutorRegistry executors;
    private final CopyOnWriteArrayList<CompletionStage<?>> pending = new CopyOnWriteArrayList<>();
    private final AtomicBoolean startupPublished = new AtomicBoolean(false);
    private volatile EditorLifecycleSnapshot current;

    public EditorLifecycleCoordinator() {
        this(new PluginWorkExecutorRegistry(1, 64, ignored -> { }, Clock.systemUTC()));
    }

    public EditorLifecycleCoordinator(final PluginWorkExecutorRegistry executors) {
        this.executors = Objects.requireNonNull(executors, "executors");
    }

    public void register(final PluginHooks plugin) {
        final PluginHooks hooks = Objects.requireNonNull(plugin, "plugin");
        plugins.add(hooks);
        final EditorLifecycleSnapshot started = current;
        if (startupPublished.get() && started != null && hooks.observeAllowed()) {
            publishStartupTo(hooks, started);
        }
    }

    public void unregister(final String pluginId) {
        Objects.requireNonNull(pluginId, "pluginId");
        plugins.removeIf(plugin -> plugin.descriptor().id().equals(pluginId));
        executors.shutdown(pluginId);
    }

    /** Publishes startup exactly once after plugins are loaded and the verified host is ready. */
    public void publishStartup(final String hostVersion) {
        final EditorLifecycleSnapshot editor = new EditorLifecycleSnapshot(
            Objects.requireNonNull(hostVersion, "hostVersion"),
            Instant.now()
        );
        current = editor;
        if (!startupPublished.compareAndSet(false, true)) return;
        for (PluginHooks plugin : plugins) {
            if (plugin.observeAllowed()) publishStartupTo(plugin, editor);
        }
    }

    /** Synchronous before phase invoked at the beginning of Cubism's exit command. */
    public ExitInvocation beginExit(final String hostVersion) {
        final EditorLifecycleSnapshot editor = Optional.ofNullable(current).orElseGet(
            () -> new EditorLifecycleSnapshot(hostVersion, Instant.now())
        );
        for (PluginHooks plugin : plugins) {
            if (!plugin.observeAllowed()) continue;
            for (EditorLifecycleHooks hook : plugin.entrypoints()) {
                try {
                    hook.beforeEditorExit(editor);
                } catch (Throwable failure) {
                    logFailure(plugin, "beforeEditorExit", failure);
                }
            }
        }
        return new ExitInvocation(editor);
    }

    /**
     * Publishes on only for an accepted exit and after for all return/throw paths.
     *
     * <p>Exit completion is intentionally synchronous: Cubism may terminate immediately after
     * {@code command_exit()} returns, so queued callbacks would not be guaranteed to run before
     * process shutdown. Plugin failures remain isolated and cannot cancel the host exit.</p>
     */
    public void completeExit(
        final ExitInvocation invocation,
        final boolean accepted,
        final Throwable failure
    ) {
        final ExitInvocation currentExit = Objects.requireNonNull(invocation, "invocation");
        final EditorExitResult result = new EditorExitResult(
            currentExit.editor(),
            accepted,
            failure == null ? Optional.empty() : Optional.of(failure.getClass().getName())
        );
        for (PluginHooks plugin : plugins) {
            if (!plugin.observeAllowed()) continue;
            for (EditorLifecycleHooks hook : plugin.entrypoints()) {
                if (accepted) {
                    try {
                        hook.onEditorExiting(currentExit.editor());
                    } catch (Throwable callbackFailure) {
                        logFailure(plugin, "onEditorExiting", callbackFailure);
                    }
                }
                try {
                    hook.afterEditorExit(result);
                } catch (Throwable callbackFailure) {
                    logFailure(plugin, "afterEditorExit", callbackFailure);
                }
            }
        }
    }

    public void awaitIdle() {
        final CompletionStage<?>[] snapshot = pending.toArray(CompletionStage[]::new);
        try {
            CompletableFuture.allOf(Arrays.stream(snapshot)
                .map(CompletionStage::toCompletableFuture)
                .toArray(CompletableFuture[]::new))
                .get(2, TimeUnit.SECONDS);
            pending.removeAll(List.of(snapshot));
        } catch (Exception failure) {
            throw new IllegalStateException("Editor lifecycle callbacks did not quiesce.", failure);
        }
    }

    @Override
    public void close() {
        plugins.clear();
        executors.shutdownAll();
    }

    private void publishStartupTo(
        final PluginHooks plugin,
        final EditorLifecycleSnapshot editor
    ) {
        for (EditorLifecycleHooks hook : plugin.entrypoints()) {
            try {
                hook.beforeEditorStartup(editor);
            } catch (Throwable failure) {
                logFailure(plugin, "beforeEditorStartup", failure);
            }
        }
        submit(plugin, () -> {
            for (EditorLifecycleHooks hook : plugin.entrypoints()) {
                try {
                    hook.onEditorStarted(editor);
                } catch (Throwable failure) {
                    logFailure(plugin, "onEditorStarted", failure);
                }
                try {
                    hook.afterEditorStartup(editor);
                } catch (Throwable failure) {
                    logFailure(plugin, "afterEditorStartup", failure);
                }
            }
        });
    }

    private void submit(final PluginHooks plugin, final Runnable callback) {
        final var submission = executors.get(plugin.descriptor().id())
            .submit(new PluginTask("event.subscribe", plugin.descriptor().id(), OPERATION_ID, "none"), callback);
        if (submission.accepted()) pending.add(submission.completion());
    }

    private static void logFailure(
        final PluginHooks plugin,
        final String phase,
        final Throwable failure
    ) {
        try {
            plugin.logger().error("Cubism editor lifecycle hook failed safely: " + phase, failure);
        } catch (Throwable ignored) {
            // Hook and diagnostic failures must not escape into Cubism.
        }
    }

    public record ExitInvocation(EditorLifecycleSnapshot editor) {
        public ExitInvocation {
            editor = Objects.requireNonNull(editor, "editor");
        }
    }

    public record PluginHooks(
        PluginDescriptor descriptor,
        List<? extends EditorLifecycleHooks> entrypoints,
        PluginLogger logger,
        boolean observeAllowed
    ) {
        public PluginHooks {
            descriptor = Objects.requireNonNull(descriptor, "descriptor");
            entrypoints = List.copyOf(Objects.requireNonNull(entrypoints, "entrypoints"));
            logger = Objects.requireNonNull(logger, "logger");
        }
    }
}
