package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.cubism.ProjectContentKind;
import dev.turboism.sdk.cubism.ProjectContentSnapshot;
import dev.turboism.sdk.cubism.ProjectFileOperation;
import dev.turboism.sdk.cubism.ProjectFileOperationResult;
import dev.turboism.sdk.cubism.ProjectFileOperationType;
import dev.turboism.sdk.cubism.hook.AnimationFileHooks;
import dev.turboism.sdk.cubism.hook.ModelFileHooks;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Runtime-owned before/on/after coordinator for model and animation file content. */
public final class ProjectFileLifecycleCoordinator implements AutoCloseable {

    public static final String OPERATION_PREFIX = "cubism.project-content.";

    private final CopyOnWriteArrayList<PluginHooks> plugins = new CopyOnWriteArrayList<>();
    private final PluginWorkExecutorRegistry executors;
    private final CopyOnWriteArrayList<CompletionStage<?>> pending = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<ProjectFileOperationResult>> completionListeners =
        new CopyOnWriteArrayList<>();

    public ProjectFileLifecycleCoordinator() {
        this(new PluginWorkExecutorRegistry(1, 64, ignored -> { }, Clock.systemUTC()));
    }

    public ProjectFileLifecycleCoordinator(final PluginWorkExecutorRegistry executors) {
        this.executors = Objects.requireNonNull(executors, "executors");
    }

    public void register(final PluginHooks plugin) {
        plugins.add(Objects.requireNonNull(plugin, "plugin"));
    }

    /** Registers a runtime-owned synchronous completion listener. */
    public void registerCompletionListener(final Consumer<ProjectFileOperationResult> listener) {
        completionListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void unregister(final String pluginId) {
        Objects.requireNonNull(pluginId, "pluginId");
        plugins.removeIf(plugin -> plugin.descriptor().id().equals(pluginId));
        executors.shutdown(pluginId);
    }

    /** Executes the synchronous before phase and returns a correlation object for completion. */
    public Invocation begin(final ProjectFileOperation operation) {
        final ProjectFileOperation request = Objects.requireNonNull(operation, "operation");
        System.out.println("LIFECYCLE-COORD:method=begin kind=" + request.kind()
            + " op=" + request.operation() + " hooks=" + plugins.size());
        for (PluginHooks plugin : plugins) {
            if (!plugin.observeAllowed()) continue;
            if (request.kind() == ProjectContentKind.MODEL) {
                for (ModelFileHooks hook : plugin.modelHooks()) {
                    invokeBeforeModel(plugin, hook, request);
                }
            } else if (request.kind() == ProjectContentKind.ANIMATION) {
                for (AnimationFileHooks hook : plugin.animationHooks()) {
                    invokeBeforeAnimation(plugin, hook, request);
                }
            }
        }
        return new Invocation(request);
    }

    /** Publishes a successful on phase when applicable, followed by after in all cases. */
    public void complete(
        final Invocation invocation,
        final ProjectContentSnapshot content,
        final boolean succeeded,
        final Throwable failure
    ) {
        final Invocation current = Objects.requireNonNull(invocation, "invocation");
        System.out.println("LIFECYCLE-COORD:method=complete kind=" + current.operation().kind()
            + " op=" + current.operation().operation() + " hooks=" + plugins.size());
        final ProjectContentSnapshot immutableContent = content;
        final ProjectFileOperationResult result = failure != null
            ? ProjectFileOperationResult.failed(current.operation(), immutableContent, failure)
            : succeeded
                ? ProjectFileOperationResult.succeeded(
                    current.operation(),
                    Objects.requireNonNull(immutableContent, "content")
                )
                : ProjectFileOperationResult.rejected(current.operation(), immutableContent);
        for (Consumer<ProjectFileOperationResult> listener : completionListeners) {
            try {
                listener.accept(result);
            } catch (Throwable ignored) {
                // Runtime cleanup listeners fail open and must not block plugin callbacks.
            }
        }
        for (PluginHooks plugin : plugins) {
            if (!plugin.observeAllowed()) continue;
            submit(plugin, () -> {
                if (current.operation().kind() == ProjectContentKind.MODEL) {
                    for (ModelFileHooks hook : plugin.modelHooks()) {
                        if (succeeded) invokeOnModel(plugin, hook, current.operation(), immutableContent);
                        invokeAfterModel(plugin, hook, current.operation(), result);
                    }
                } else if (current.operation().kind() == ProjectContentKind.ANIMATION) {
                    for (AnimationFileHooks hook : plugin.animationHooks()) {
                        if (succeeded) {
                            invokeOnAnimation(plugin, hook, current.operation(), immutableContent);
                        }
                        invokeAfterAnimation(plugin, hook, current.operation(), result);
                    }
                }
            });
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
            throw new IllegalStateException("Project-file lifecycle callbacks did not quiesce.", failure);
        }
    }

    @Override
    public void close() {
        plugins.clear();
        completionListeners.clear();
        executors.shutdownAll();
    }

    private static void invokeBeforeModel(
        final PluginHooks plugin,
        final ModelFileHooks hook,
        final ProjectFileOperation operation
    ) {
        try {
            switch (operation.operation()) {
                case CREATE -> hook.beforeCreateModel(operation);
                case OPEN -> hook.beforeOpenModel(operation);
                case SAVE -> hook.beforeSaveModel(operation);
                case CLOSE -> hook.beforeCloseModel(operation);
            }
        } catch (Throwable failure) {
            logFailure(plugin, "before" + phaseName(operation) + "Model", failure);
        }
    }

    private static void invokeBeforeAnimation(
        final PluginHooks plugin,
        final AnimationFileHooks hook,
        final ProjectFileOperation operation
    ) {
        try {
            switch (operation.operation()) {
                case CREATE -> hook.beforeCreateAnimation(operation);
                case OPEN -> hook.beforeOpenAnimation(operation);
                case SAVE -> hook.beforeSaveAnimation(operation);
                case CLOSE -> hook.beforeCloseAnimation(operation);
            }
        } catch (Throwable failure) {
            logFailure(plugin, "before" + phaseName(operation) + "Animation", failure);
        }
    }

    private static void invokeOnModel(
        final PluginHooks plugin,
        final ModelFileHooks hook,
        final ProjectFileOperation operation,
        final ProjectContentSnapshot content
    ) {
        try {
            switch (operation.operation()) {
                case CREATE -> hook.onModelCreated(content);
                case OPEN -> hook.onModelOpened(content);
                case SAVE -> hook.onModelSaved(content);
                case CLOSE -> hook.onModelClosed(content);
            }
        } catch (Throwable failure) {
            logFailure(plugin, "onModel" + pastParticiple(operation), failure);
        }
    }

    private static void invokeOnAnimation(
        final PluginHooks plugin,
        final AnimationFileHooks hook,
        final ProjectFileOperation operation,
        final ProjectContentSnapshot content
    ) {
        try {
            switch (operation.operation()) {
                case CREATE -> hook.onAnimationCreated(content);
                case OPEN -> hook.onAnimationOpened(content);
                case SAVE -> hook.onAnimationSaved(content);
                case CLOSE -> hook.onAnimationClosed(content);
            }
        } catch (Throwable failure) {
            logFailure(plugin, "onAnimation" + pastParticiple(operation), failure);
        }
    }

    private static void invokeAfterModel(
        final PluginHooks plugin,
        final ModelFileHooks hook,
        final ProjectFileOperation operation,
        final ProjectFileOperationResult result
    ) {
        try {
            switch (operation.operation()) {
                case CREATE -> hook.afterCreateModel(result);
                case OPEN -> hook.afterOpenModel(result);
                case SAVE -> hook.afterSaveModel(result);
                case CLOSE -> hook.afterCloseModel(result);
            }
        } catch (Throwable failure) {
            logFailure(plugin, "after" + phaseName(operation) + "Model", failure);
        }
    }

    private static void invokeAfterAnimation(
        final PluginHooks plugin,
        final AnimationFileHooks hook,
        final ProjectFileOperation operation,
        final ProjectFileOperationResult result
    ) {
        try {
            switch (operation.operation()) {
                case CREATE -> hook.afterCreateAnimation(result);
                case OPEN -> hook.afterOpenAnimation(result);
                case SAVE -> hook.afterSaveAnimation(result);
                case CLOSE -> hook.afterCloseAnimation(result);
            }
        } catch (Throwable failure) {
            logFailure(plugin, "after" + phaseName(operation) + "Animation", failure);
        }
    }

    private void submit(final PluginHooks plugin, final Runnable callback) {
        final var submission = executors.get(plugin.descriptor().id())
            .submit(task(plugin.descriptor().id()), callback);
        if (submission.accepted()) pending.add(submission.completion());
    }

    private static PluginTask task(final String pluginId) {
        return new PluginTask("event.subscribe", pluginId, OPERATION_PREFIX + "lifecycle", "none");
    }

    private static String phaseName(final ProjectFileOperation operation) {
        final String lower = operation.operation().name().toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static String pastParticiple(final ProjectFileOperation operation) {
        return switch (operation.operation()) {
            case CREATE -> "Created";
            case OPEN -> "Opened";
            case SAVE -> "Saved";
            case CLOSE -> "Closed";
        };
    }

    private static void logFailure(
        final PluginHooks plugin,
        final String phase,
        final Throwable failure
    ) {
        try {
            plugin.logger().error("Cubism project-file hook failed safely: " + phase, failure);
        } catch (Throwable ignored) {
            // Hook and diagnostic failures must not escape into Cubism.
        }
    }

    public record Invocation(ProjectFileOperation operation) {
        public Invocation {
            operation = Objects.requireNonNull(operation, "operation");
        }
    }

    public record PluginHooks(
        PluginDescriptor descriptor,
        List<? extends ModelFileHooks> modelHooks,
        List<? extends AnimationFileHooks> animationHooks,
        PluginLogger logger,
        boolean observeAllowed
    ) {
        public PluginHooks {
            descriptor = Objects.requireNonNull(descriptor, "descriptor");
            modelHooks = List.copyOf(Objects.requireNonNull(modelHooks, "modelHooks"));
            animationHooks = List.copyOf(Objects.requireNonNull(animationHooks, "animationHooks"));
            logger = Objects.requireNonNull(logger, "logger");
        }
    }
}
