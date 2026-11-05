package dev.turboism.kernel;

import dev.turboism.adapter.cubism.lifecycle.EditorObjectHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.ParameterHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.PartHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.ProjectLifecycleHookRegistry;
import dev.turboism.core.event.PluginEventOwnerKey;
import dev.turboism.core.runtime.UncheckedThrowableException;
import dev.turboism.core.runtime.work.FatalErrors;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The plugin kind's close content: one already-loaded generation whose ordered teardown is the
 * only work left.
 *
 * <p>Load-time collaborators (the candidate JAR, the context factory) are gone by the time a
 * plugin closes, and close-time collaborators (the staged disable/shutdown/unload bodies and the
 * close hook) do not exist while it loads, so the plugin kind supplies one content object per
 * phase over the same {@link AdmittedGeneration} module.</p>
 *
 * <p>This adapter owns the plugin-visible verdict data — the per-stage states and failure codes an
 * operator report shows — while {@link AdmittedGeneration} owns the stage order, the one-shot
 * semantics and the deferral gates. {@link #recordDeferral} turns a gate into this kind's failure
 * code, so the module stays neutral about verdicts.</p>
 */
final class PluginCloseContent implements AdmittedGeneration.Content<Void> {

    private final LocalPluginRuntime.LoadedPlugin loadedPlugin;
    private final KernelPluginShutdownStages stages;
    private final ParameterHookRegistry parameterHookRegistry;
    private final PartHookRegistry partHookRegistry;
    private final EditorObjectHookRegistry editorObjectHookRegistry;
    private final ProjectLifecycleHookRegistry projectLifecycleHookRegistry;
    private final LocalPluginRuntime.PluginCloseHook closeHook;
    private final KernelLog log;
    private final KernelPluginShutdownStages.CloseReport report = new KernelPluginShutdownStages.CloseReport();
    /**
     * Stage failures recorded since the last teardown body started. The per-stage states above stay
     * sticky across re-drives, so a later pass never contradicts an earlier one.
     */
    private List<LocalPluginRuntime.PluginSummaryFailure> failures = new ArrayList<>();

    PluginCloseContent(
            final LocalPluginRuntime.LoadedPlugin loadedPlugin,
            final KernelPluginShutdownStages stages,
            final ParameterHookRegistry parameterHookRegistry,
            final PartHookRegistry partHookRegistry,
            final EditorObjectHookRegistry editorObjectHookRegistry,
            final ProjectLifecycleHookRegistry projectLifecycleHookRegistry,
            final LocalPluginRuntime.PluginCloseHook closeHook,
            final KernelLog log) {
        this.loadedPlugin = Objects.requireNonNull(loadedPlugin, "loadedPlugin");
        this.stages = Objects.requireNonNull(stages, "stages");
        this.parameterHookRegistry = Objects.requireNonNull(parameterHookRegistry, "parameterHookRegistry");
        this.partHookRegistry = Objects.requireNonNull(partHookRegistry, "partHookRegistry");
        this.editorObjectHookRegistry = Objects.requireNonNull(editorObjectHookRegistry, "editorObjectHookRegistry");
        this.projectLifecycleHookRegistry =
                Objects.requireNonNull(projectLifecycleHookRegistry, "projectLifecycleHookRegistry");
        this.closeHook = Objects.requireNonNull(closeHook, "closeHook");
        this.log = Objects.requireNonNull(log, "log");
    }

    @Override
    public String id() {
        return loadedPlugin.runtime().id();
    }

    @Override
    public boolean claimsExecutors() {
        return true;
    }

    /** A close generation is already admitted: its handles come from the loaded plugin. */
    @Override
    public void bind(final AdmittedGeneration.GenerationState shared) {
        shared.guard = loadedPlugin.guard();
        shared.eventOwner = loadedPlugin.eventOwner();
        shared.scope = loadedPlugin.scope();
    }

    @Override
    public Void start(final AdmittedGeneration.GenerationState shared, final PluginLifecycleLease lease) {
        throw new IllegalStateException("a close generation has no admission body: " + id());
    }

    @Override
    public void claimExecutors(final AdmittedGeneration.GenerationState shared, final AdmittedGeneration.Cause cause) {
        // Capture the executor set while admission is fenced: at this point the registry entry
        // still belongs to this generation, so the terminal close tail can release exactly the
        // set this generation used — never a replacement claimed by a newer generation.
        try {
            loadedPlugin.eventOwner().claimExecutors();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            log.error(id(), "Plugin executor claim failed safely", failure);
        }
    }

    @Override
    public void unregisterHooks(final AdmittedGeneration.GenerationState shared) {
        final PluginEventOwnerKey key = loadedPlugin.eventOwner().key();
        projectLifecycleHookRegistry.unregister(key);
        editorObjectHookRegistry.unregister(key);
        partHookRegistry.unregister(key);
        parameterHookRegistry.unregister(key);
    }

    @Override
    public boolean quiesceBackup(final AdmittedGeneration.GenerationState shared) {
        return stages.quiesceBackup(loadedPlugin);
    }

    /**
     * The plugin's own teardown body: the hook that lets a composition observe "this close
     * started", then disable and shutdown in reverse load order. A throw escapes and fails the
     * pass; the one-shot flags the module already set keep the retained re-drive from repeating
     * any stage.
     */
    @Override
    public void closeBody(final AdmittedGeneration.GenerationState shared, final AdmittedGeneration.Cause cause) {
        failures = new ArrayList<>();
        runCloseHook();
        log.info(id(), "Plugin lifecycle: close started");
        report.disableState = stages.disable(loadedPlugin, failures);
        report.shutdownState = stages.shutdown(loadedPlugin, failures);
    }

    @Override
    public AdmittedGeneration.StageOutcome closeScope(final AdmittedGeneration.GenerationState shared) {
        final KernelPluginShutdownStages.ScopeResult scope = stages.closeScope(loadedPlugin, failures);
        report.scopeState = scope.state();
        return scope.closed() ? AdmittedGeneration.StageOutcome.succeeded() : AdmittedGeneration.StageOutcome.failed();
    }

    /**
     * The classloader is released last and only after the scope verifiably closed; an unproven
     * scope keeps it open and records the retention failure this kind has always reported.
     */
    @Override
    public AdmittedGeneration.StageOutcome closeResources(final AdmittedGeneration.GenerationState shared) {
        report.classloaderState =
                stages.closeClassLoader(loadedPlugin, shared.scopeClosed, shared.eventOwnerQuiesced, failures);
        return "SUCCEEDED".equals(report.classloaderState)
                ? AdmittedGeneration.StageOutcome.succeeded()
                : AdmittedGeneration.StageOutcome.failed();
    }

    @Override
    public void finishDisposal(final AdmittedGeneration.GenerationState shared) {
        report.unloadState =
                stages.unload(loadedPlugin, shared.scopeClosed, shared.eventOwnerQuiesced, report.classloaderState);
        log.info(
                id(),
                "Plugin lifecycle: close complete disable=" + orNotStarted(report.disableState)
                        + " shutdown=" + orNotStarted(report.shutdownState)
                        + " unload=" + orNotStarted(report.unloadState));
        log.info(id(), "Plugin unloaded with state " + loadedPlugin.runtime().state());
    }

    @Override
    public void releaseExecutors(
            final AdmittedGeneration.GenerationState shared, final AdmittedGeneration.Cause cause) {
        try {
            loadedPlugin.eventOwner().releaseExecutors();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            log.error(id(), "Plugin executor release failed safely", failure);
        }
    }

    /** The close path already reports incompleteness through its stage states and failures. */
    @Override
    public String incompleteMessage() {
        return null;
    }

    @Override
    public String retainedMessage() {
        return "Plugin close deferred: generation retained until lifecycle work quiesces";
    }

    /**
     * Turns a gate that ended a deferred pass into this kind's stable failure code and log line,
     * so a deferred close reports the same summary it always has.
     *
     * @return the deferral failures of this pass, for the caller's summary
     */
    List<LocalPluginRuntime.PluginSummaryFailure> recordDeferral(final AdmittedGeneration.Gate gate) {
        final List<LocalPluginRuntime.PluginSummaryFailure> deferral = new ArrayList<>(1);
        switch (gate) {
            case EVENT_QUIESCENCE -> {
                deferral.add(failure(
                        "PLUGIN_EVENT_QUIESCENCE_FAILED",
                        "event-quiescence",
                        "Plugin event callbacks did not quiesce before cleanup."));
                logFailure("PLUGIN_EVENT_QUIESCENCE_FAILED");
                log.warn(id(), "Plugin lifecycle: close deferred until event callbacks quiesce");
            }
            case BACKUP_QUIESCENCE -> {
                deferral.add(failure(
                        "PLUGIN_BACKUP_QUIESCENCE_FAILED",
                        "backup-quiescence",
                        "Plugin backup work did not quiesce before lifecycle shutdown."));
                logFailure("PLUGIN_BACKUP_QUIESCENCE_FAILED");
                log.warn(id(), "Plugin lifecycle: close deferred until backup work quiesces");
            }
            case SDK_DRAIN -> {
                deferral.add(failure(
                        "PLUGIN_SDK_DRAIN_FAILED",
                        "sdk-drain",
                        "Plugin SDK calls admitted before fencing did not drain before teardown."));
                logFailure("PLUGIN_SDK_DRAIN_FAILED");
                log.warn(id(), "Plugin lifecycle: close deferred until admitted SDK calls drain");
            }
            default -> {
                // No other gate exists for a plugin close: the "other generations inert" barrier
                // is the runtime-owned shell's caller-supplied stage.
            }
        }
        return List.copyOf(deferral);
    }

    /** Verdict data of the pass that just returned, for the caller's summary. */
    KernelPluginShutdownStages.CloseReport report() {
        return report;
    }

    /** Stage failures recorded by the pass that just returned. */
    List<LocalPluginRuntime.PluginSummaryFailure> failures() {
        return List.copyOf(failures);
    }

    private void runCloseHook() {
        try {
            closeHook.run(id(), "close");
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            // closeHook.run declares Throwable; Errors pass through, the rest wrap.
            if (failure instanceof Error error) {
                throw error;
            }
            throw new UncheckedThrowableException(failure);
        }
    }

    private static String orNotStarted(final String state) {
        return state == null ? "NOT_STARTED" : state;
    }

    private static LocalPluginRuntime.PluginSummaryFailure failure(
            final String code, final String phase, final String message) {
        return new LocalPluginRuntime.PluginSummaryFailure(code, phase, message);
    }

    private void logFailure(final String code) {
        log.error(id(), "Plugin lifecycle stage failed safely: " + code, new IllegalStateException(code));
    }
}
