package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.cubism.EditorExitResult;
import dev.turboism.sdk.cubism.EditorLifecycleSnapshot;
import dev.turboism.sdk.cubism.hook.EditorLifecycleHooks;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runtime-owned coordinator for editor startup and pre-shutdown lifecycle callbacks. */
public final class EditorLifecycleCoordinator implements AutoCloseable {

    public static final String OPERATION_ID = "cubism.editor.lifecycle";

    private final CopyOnWriteArrayList<Registration> plugins = new CopyOnWriteArrayList<>();
    private final LifecycleCallbackExecutor callbacks;
    private final Object registrationLock = new Object();
    private final AtomicBoolean startupPublished = new AtomicBoolean(false);
    private volatile EditorLifecycleSnapshot current;

    public EditorLifecycleCoordinator() {
        this(new PluginWorkExecutorRegistry(1, 64, ignored -> { }, Clock.systemUTC()));
    }

    public EditorLifecycleCoordinator(final PluginWorkExecutorRegistry executors) {
        this.callbacks = new LifecycleCallbackExecutor("Editor", executors);
    }

    public void register(final PluginHooks plugin) {
        final PluginHooks hooks = Objects.requireNonNull(plugin, "plugin");
        final Registration registration = new Registration(new Object(), hooks);
        synchronized (registrationLock) {
            plugins.removeIf(existing -> existing.plugin().descriptor().id().equals(hooks.descriptor().id()));
            callbacks.shutdown(hooks.descriptor().id());
            plugins.add(registration);
            final EditorLifecycleSnapshot started = current;
            if (startupPublished.get() && started != null && hooks.observeAllowed()) {
                publishStartupTo(registration, started);
            }
        }
    }

    void register(final Object token, final PluginHooks plugin) {
        synchronized (registrationLock) {
            plugins.add(new Registration(
                Objects.requireNonNull(token, "token"),
                Objects.requireNonNull(plugin, "plugin")
            ));
        }
    }

    public void unregister(final String pluginId) {
        final String id = Objects.requireNonNull(pluginId, "pluginId");
        synchronized (registrationLock) {
            plugins.removeIf(registration -> registration.plugin().descriptor().id().equals(id));
            callbacks.shutdown(id);
        }
    }

    void unregister(final String pluginId, final Object token) {
        final String id = Objects.requireNonNull(pluginId, "pluginId");
        final Object generation = Objects.requireNonNull(token, "token");
        synchronized (registrationLock) {
            final boolean removed = plugins.removeIf(registration ->
                registration.token() == generation
                    && registration.plugin().descriptor().id().equals(id)
            );
            if (removed && plugins.stream().noneMatch(registration ->
                registration.plugin().descriptor().id().equals(id)
            )) {
                callbacks.shutdown(id);
            }
        }
    }

    /** Publishes startup exactly once after plugins are loaded and the verified host is ready. */
    public void publishStartup(final String hostVersion) {
        final EditorLifecycleSnapshot editor = new EditorLifecycleSnapshot(
            Objects.requireNonNull(hostVersion, "hostVersion"),
            Instant.now()
        );
        current = editor;
        if (!startupPublished.compareAndSet(false, true)) return;
        for (Registration registration : plugins) {
            final PluginHooks plugin = registration.plugin();
            if (plugin.observeAllowed()) publishStartupTo(registration, editor);
        }
    }

    /** Synchronous before phase invoked at the beginning of Cubism's exit command. */
    public ExitInvocation beginExit(final String hostVersion) {
        final EditorLifecycleSnapshot editor = Optional.ofNullable(current).orElseGet(
            () -> new EditorLifecycleSnapshot(hostVersion, Instant.now())
        );
        for (Registration registration : plugins) {
            final PluginHooks plugin = registration.plugin();
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
        for (Registration registration : plugins) {
            final PluginHooks plugin = registration.plugin();
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
        callbacks.awaitIdle();
    }

    @Override
    public void close() {
        synchronized (registrationLock) {
            plugins.clear();
            callbacks.close();
        }
    }

    private void publishStartupTo(
        final Registration registration,
        final EditorLifecycleSnapshot editor
    ) {
        final PluginHooks plugin = registration.plugin();
        for (EditorLifecycleHooks hook : plugin.entrypoints()) {
            try {
                hook.beforeEditorStartup(editor);
            } catch (Throwable failure) {
                logFailure(plugin, "beforeEditorStartup", failure);
            }
        }
        submit(registration, () -> {
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

    private void submit(final Registration registration, final Runnable callback) {
        synchronized (registrationLock) {
            if (!plugins.contains(registration)) {
                return;
            }
            callbacks.submit(
                registration.plugin().descriptor().id(),
                OPERATION_ID,
                callback
            );
        }
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

    private record Registration(Object token, PluginHooks plugin) { }

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
