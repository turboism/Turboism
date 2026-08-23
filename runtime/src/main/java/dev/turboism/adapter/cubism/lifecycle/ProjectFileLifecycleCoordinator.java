package dev.turboism.adapter.cubism.lifecycle;

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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Runtime-owned before/on/after coordinator for model and animation file content. */
public final class ProjectFileLifecycleCoordinator implements AutoCloseable {

    public static final String OPERATION_PREFIX = "cubism.project-content.";

    private final CopyOnWriteArrayList<Registration> plugins = new CopyOnWriteArrayList<>();
    private final LifecycleCallbackExecutor callbacks;
    private final Object registrationLock = new Object();
    private final CopyOnWriteArrayList<Consumer<ProjectFileOperationResult>> completionListeners =
        new CopyOnWriteArrayList<>();

    public ProjectFileLifecycleCoordinator() {
        this(new PluginWorkExecutorRegistry(1, 64, ignored -> { }, Clock.systemUTC()));
    }

    public ProjectFileLifecycleCoordinator(final PluginWorkExecutorRegistry executors) {
        this.callbacks = new LifecycleCallbackExecutor("Project-file", executors);
    }

    /**
     * Registers a plugin's model and animation file hooks, replacing any earlier registration under the
     * same plugin id and shutting down that plugin's pending callback queue first.
     *
     * @param plugin descriptor, hook lists and logger for the registering plugin
     * @throws NullPointerException when {@code plugin} is null
     */
    public void register(final PluginHooks plugin) {
        final PluginHooks value = Objects.requireNonNull(plugin, "plugin");
        final Object token = new Object();
        synchronized (registrationLock) {
            plugins.removeIf(registration -> registration.plugin().descriptor().id().equals(value.descriptor().id()));
            callbacks.shutdown(value.descriptor().id());
            plugins.add(new Registration(token, value));
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

    /** Registers a runtime-owned synchronous completion listener. */
    public void registerCompletionListener(final Consumer<ProjectFileOperationResult> listener) {
        completionListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Removes every registration owned by the given plugin id and shuts down its callback queue.
     * Runtime completion listeners are unaffected. Unknown ids are ignored.
     *
     * @param pluginId id of the plugin to detach
     * @throws NullPointerException when {@code pluginId} is null
     */
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

    /** Executes the synchronous before phase and returns a correlation object for completion. */
    public Invocation begin(final ProjectFileOperation operation) {
        final ProjectFileOperation request = Objects.requireNonNull(operation, "operation");
        System.out.println("LIFECYCLE-COORD:method=begin kind=" + request.kind()
            + " op=" + request.operation() + " hooks=" + plugins.size());
        for (Registration registration : plugins) {
            final PluginHooks plugin = registration.plugin();
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
        for (Registration registration : plugins) {
            final PluginHooks plugin = registration.plugin();
            if (!plugin.observeAllowed()) continue;
            submit(registration, () -> {
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

    /**
     * Blocks until every observer callback queued so far has finished. The before phase and the runtime
     * completion listeners are synchronous and so are already complete when their calls return.
     */
    public void awaitIdle() {
        callbacks.awaitIdle();
    }

    @Override
    public void close() {
        synchronized (registrationLock) {
            plugins.clear();
            completionListeners.clear();
            callbacks.close();
        }
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

    private void submit(final Registration registration, final Runnable callback) {
        synchronized (registrationLock) {
            if (!plugins.contains(registration)) {
                return;
            }
            callbacks.submit(
                registration.plugin().descriptor().id(),
                OPERATION_PREFIX + "lifecycle",
                callback
            );
        }
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

    private record Registration(Object token, PluginHooks plugin) { }

    /**
     * Correlation token linking a {@code begin} call to its {@code complete}, so the completion phase
     * reports the same operation the before phase saw.
     *
     * @param operation the requested project-file operation; never null
     */
    public record Invocation(ProjectFileOperation operation) {
        public Invocation {
            operation = Objects.requireNonNull(operation, "operation");
        }
    }

    /**
     * One plugin's participation in the project-file lifecycle. Model and animation hooks are held
     * separately because a content kind only dispatches to its own list.
     *
     * @param descriptor identity of the owning plugin, used as the registration key
     * @param modelHooks the plugin's model file hooks, defensively copied and immutable
     * @param animationHooks the plugin's animation file hooks, defensively copied and immutable
     * @param logger sink for hook failures raised by this plugin
     * @param observeAllowed whether this plugin receives project-file callbacks at all
     */
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
