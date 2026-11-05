package dev.turboism.kernel;

import dev.turboism.adapter.cubism.lifecycle.EditorObjectHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.ParameterHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.PartHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.ProjectLifecycleHookRegistry;
import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.core.plugin.PluginRuntime;
import dev.turboism.sdk.plugin.PluginDescriptor;
import java.net.URLClassLoader;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Loads one plugin JAR and all of its ordered entrypoints atomically.
 *
 * <p>The whole construct/init/enable sequence is one task on the shared bounded
 * {@link PluginLifecycleLane}, submitted through {@link AdmittedGeneration}: the caller waits with
 * {@link PluginLifecyclePolicy#loadTimeout()} measured from the worker's own start, and on
 * timeout the generation is fenced — admission closed through the guard, the event owner and a
 * sealed scope — and retained until the worker actually exits. A commit that already won the lease
 * race reports success; a late commit attempt hits the fence and unwinds through the module's
 * ordered teardown.</p>
 *
 * <p>This type keeps discovery-facing concerns — the failure list, the load report, the
 * operator-visible log lines and the lifecycle events of one attempt — while the plugin content
 * ({@link PluginGenerationContent}) owns the JAR, the entrypoints and the rollback bodies.</p>
 */
final class PluginClassLoader {

    private final KernelPluginContextFactory contextFactory;
    private final KernelLog log;
    private final List<LocalPluginRuntime.LoadedPlugin> loaded;
    private final ParameterHookRegistry parameterHookRegistry;
    private final PartHookRegistry partHookRegistry;
    private final EditorObjectHookRegistry editorObjectHookRegistry;
    private final ProjectLifecycleHookRegistry projectLifecycleHookRegistry;
    private final PluginLifecyclePolicy policy;
    private final PluginLifecycleEvents lifecycleEvents;
    private final AdmittedGeneration.Admission admission;

    PluginClassLoader(
            final KernelPluginContextFactory contextFactory,
            final KernelLog log,
            final List<LocalPluginRuntime.LoadedPlugin> loaded,
            final ParameterHookRegistry parameterHookRegistry,
            final PartHookRegistry partHookRegistry,
            final EditorObjectHookRegistry editorObjectHookRegistry,
            final ProjectLifecycleHookRegistry projectLifecycleHookRegistry,
            final PluginLifecycleLane lane,
            final PluginLifecyclePolicy policy,
            final RetainedPluginGenerations retention) {
        this.contextFactory = contextFactory;
        this.log = log;
        this.loaded = loaded;
        this.parameterHookRegistry = java.util.Objects.requireNonNull(parameterHookRegistry, "parameterHookRegistry");
        this.partHookRegistry = java.util.Objects.requireNonNull(partHookRegistry, "partHookRegistry");
        this.editorObjectHookRegistry =
                java.util.Objects.requireNonNull(editorObjectHookRegistry, "editorObjectHookRegistry");
        this.projectLifecycleHookRegistry =
                java.util.Objects.requireNonNull(projectLifecycleHookRegistry, "projectLifecycleHookRegistry");
        this.policy = java.util.Objects.requireNonNull(policy, "policy");
        this.lifecycleEvents = new PluginLifecycleEvents(contextFactory.eventBroker(), log);
        this.admission = new AdmittedGeneration.Admission(
                this.policy,
                java.util.Objects.requireNonNull(lane, "lane"),
                java.util.Objects.requireNonNull(retention, "retention"),
                log);
    }

    LocalPluginRuntime.LoadedPluginSummary load(
            final PreviewPluginCandidate candidate, final List<LocalPluginRuntime.PluginFailure> failures) {
        return finishLoad(beginLoad(candidate), failures);
    }

    PendingLoad beginLoad(final PreviewPluginCandidate candidate) {
        return submitLoad(candidate);
    }

    PendingLoad submitLoad(final PreviewPluginCandidate candidate) {
        final PluginDescriptor descriptor = candidate.descriptor();
        log.info(
                descriptor.id(),
                "Plugin lifecycle: load started name=" + descriptor.name() + " version=" + descriptor.version());
        final PluginRuntime runtime = new PluginRuntime(descriptor.id(), descriptor);
        runtime.transitionTo(PluginLifecycleState.RESOLVED);
        final PluginGenerationContent content = new PluginGenerationContent(
                candidate,
                runtime,
                contextFactory,
                log,
                parameterHookRegistry,
                partHookRegistry,
                editorObjectHookRegistry,
                projectLifecycleHookRegistry);
        return new PendingLoad(
                candidate, runtime, content, AdmittedGeneration.submit(content, admission, policy.loadTimeout()));
    }

    LocalPluginRuntime.LoadedPluginSummary finishLoad(
            final PendingLoad pending, final List<LocalPluginRuntime.PluginFailure> failures) {
        final PreviewPluginCandidate candidate = pending.candidate();
        final PluginDescriptor descriptor = candidate.descriptor();
        final PluginRuntime runtime = pending.runtime();
        final PluginGenerationContent content = pending.content();
        final AdmittedGeneration.Outcome<LocalPluginRuntime.LoadedPlugin> outcome =
                pending.submission().await();
        final long elapsedMillis = outcome.elapsedMillis();
        if (elapsedMillis > 5_000L) {
            log.warn(descriptor.id(), "Plugin lifecycle: slow load detected elapsedMs=" + elapsedMillis);
        }
        return switch (outcome.kind()) {
            case SUCCEEDED -> {
                final LocalPluginRuntime.LoadedPlugin value = outcome.value();
                loaded.add(value);
                log.info(
                        descriptor.id(),
                        "Plugin lifecycle: load succeeded name=" + descriptor.name()
                                + " version=" + descriptor.version()
                                + " entrypoints=" + content.entrypointCount()
                                + " elapsedMs=" + elapsedMillis);
                lifecycleEvents.loaded(descriptor.id(), value.eventOwner().key().generation());
                yield KernelPluginSummaryFactory.active(value);
            }
            case FAILED -> {
                recordFailure(candidate, runtime, content.classLoader(), failures, outcome.failure());
                lifecycleEvents.loadFailed(descriptor.id(), content.admittedGeneration());
                yield null;
            }
            case REJECTED -> {
                // The task never ran: nothing was constructed, so there is nothing to clean up.
                // The module already fenced the pre-created guard so no reference to it can
                // admit work later.
                recordFailure(
                        candidate,
                        runtime,
                        content.classLoader(),
                        failures,
                        new IllegalStateException("Plugin lifecycle lane is saturated or closed: " + descriptor.id()));
                lifecycleEvents.loadFailed(
                        descriptor.id(), dev.turboism.sdk.runtime.PluginLifecycleEvent.NO_ADMITTED_GENERATION);
                yield null;
            }
            case TIMED_OUT -> {
                recordFailure(
                        candidate,
                        runtime,
                        content.classLoader(),
                        failures,
                        new TimeoutException("Plugin load exceeded " + policy.loadTimeout() + ": " + descriptor.id()));
                lifecycleEvents.loadTimedOut(descriptor.id(), content.admittedGeneration());
                yield null;
            }
        };
    }

    record PendingLoad(
            PreviewPluginCandidate candidate,
            PluginRuntime runtime,
            PluginGenerationContent content,
            AdmittedGeneration.Submission<LocalPluginRuntime.LoadedPlugin> submission) {}

    private void recordFailure(
            final PreviewPluginCandidate candidate,
            final PluginRuntime runtime,
            final URLClassLoader classLoader,
            final List<LocalPluginRuntime.PluginFailure> failures,
            final Throwable failure) {
        if (runtime.state() != PluginLifecycleState.ENABLE_FAILED) {
            runtime.transitionTo(
                    classLoader == null ? PluginLifecycleState.CLASSLOADER_FAILED : PluginLifecycleState.LOAD_FAILED);
        }
        failures.add(new LocalPluginRuntime.PluginFailure(
                candidate.descriptor().id(), candidate.jar(), runtime.state().name(), safeMessage(failure)));
        log.error(candidate.descriptor().id(), "Plugin lifecycle: load failed state=" + runtime.state(), failure);
    }

    /**
     * Package-private parent-selection seam. When the SDK is bootstrap-loaded
     * by the agent Boot-Class-Path, {@code TurboismPlugin.class.getClassLoader()}
     * is null and the platform loader is required so plugin JARs stay visible to
     * JDK platform modules (for example {@code jdk.httpserver}). The chosen parent is
     * always wrapped by {@link PluginParentBoundary} so external plugins cannot link
     * implementation namespaces (internal management contracts, core UI classes,
     * relocated agent libraries) that the parent chain can see.
     */
    static ClassLoader resolvePluginParent(final ClassLoader sdkClassLoader) {
        return PluginParentBoundary.denyImplementationNamespaces(
                sdkClassLoader != null ? sdkClassLoader : ClassLoader.getPlatformClassLoader());
    }

    private static String safeMessage(final Throwable failure) {
        if (failure == null) {
            return "unknown";
        }
        final String message = failure.getMessage();
        return message == null ? failure.getClass().getSimpleName() : message;
    }
}
