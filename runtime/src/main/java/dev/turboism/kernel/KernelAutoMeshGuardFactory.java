package dev.turboism.kernel;

import dev.turboism.adapter.cubism.mesh.EditorAutoMeshEditableWrite;
import dev.turboism.adapter.cubism.mesh.EditorAutoMeshTargetResolver;
import dev.turboism.adapter.cubism.mesh.EditorAutoMeshUndoGroupProbe;
import dev.turboism.automesh.AutoMeshDispatch;
import dev.turboism.automesh.AutoMeshExecutor;
import dev.turboism.automesh.AutoMeshRequestBuilder;
import dev.turboism.automesh.AutoMeshSelectionState;
import dev.turboism.automesh.host.AutoMeshGuardHandler;
import dev.turboism.automesh.host.AutoMeshMessages;
import dev.turboism.automesh.host.AutoMeshNotice;
import dev.turboism.automesh.host.AutoMeshNoticeSink;
import dev.turboism.automesh.host.AutoMeshResolvedTarget;
import dev.turboism.automesh.host.NativeAutoMeshBridge;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.contract.PluginTask;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.internal.core.CorePluginManagement;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.ui.host.EdtDispatch;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Composes the Auto Mesh guard policy from the runtime services the Editor already runs.
 *
 * <p>The factory binds the session selection to the session algorithm catalog, resolves host
 * selection elements through the verified Editor-model resolver, computes on the runtime's plugin
 * work lane, commits through the runtime authoring-transaction service, and reports through the
 * Editor status surface. Every collaborator is supplied by the caller, so the guard never creates a
 * thread, a resolver or a dialog of its own.</p>
 *
 * <p>The returned handler is inert while the session selects the Editor's own generator, which is
 * the state of a runtime without a registered custom algorithm.</p>
 */
final class KernelAutoMeshGuardFactory {

    /** Plugin identity the guard's compute runs are attributed to in work-budget diagnostics. */
    private static final String GUARD_OWNER = CorePluginManagement.CORE_PLUGIN_ID;

    /** Work-budget task type that routes one compute run onto the plugin long lane. */
    private static final String COMPUTE_TASK_TYPE = "plugin.long.normal";

    private static final String COMPUTE_PAYLOAD = "automesh.compute";
    private static final String COMPUTE_CAPABILITY = "none";
    private static final String STATUS_NOTIFICATION_ID = "turboism.automesh";
    private static final String SEVERITY_INFO = "INFO";
    private static final String SEVERITY_WARNING = "WARNING";
    private static final String SEVERITY_ERROR = "ERROR";
    private static final String UI_POST_LABEL = "auto mesh guard UI work";
    private static final String LANE_REFUSED = "auto mesh compute lane refused the run";

    private KernelAutoMeshGuardFactory() {}

    /**
     * Builds the guard policy of one started runtime.
     *
     * @param selection the session selection bound to the session algorithm catalog
     * @param resolver the verified Editor-model resolver of the active session
     * @param modelAccess the live model access the target projection reads
     * @param statusSink posts one status notification through the Editor status surface
     * @param scheduler the runtime scheduler that owns the compute lane
     * @param locale the locale the notices are rendered in
     * @param diagnostics receives every swallowed failure; it must not throw
     * @return the guard policy the transformed funnels report to
     */
    static NativeAutoMeshBridge.Handler create(
            final AutoMeshSelectionState selection,
            final VerifiedMemberResolver resolver,
            final CubismModelAccess modelAccess,
            final Consumer<StatusNotification> statusSink,
            final RuntimeScheduler scheduler,
            final Locale locale,
            final Consumer<String> diagnostics) {
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(modelAccess, "modelAccess");
        Objects.requireNonNull(statusSink, "statusSink");
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(diagnostics, "diagnostics");
        final AutoMeshDispatch<AutoMeshResolvedTarget> dispatch = new AutoMeshDispatch<>(
                new AutoMeshRequestBuilder(),
                new AutoMeshExecutor(computeLane(scheduler)),
                new EditorAutoMeshEditableWrite(resolver, diagnostics),
                new EditorAutoMeshUndoGroupProbe(resolver, diagnostics),
                uiThread());
        return new AutoMeshGuardHandler(
                selection,
                new EditorAutoMeshTargetResolver(resolver, modelAccess),
                dispatch,
                noticeSink(statusSink),
                diagnostics,
                AutoMeshMessages.forLocale(locale));
    }

    /**
     * Returns the off-UI-thread lane one auto-mesh compute run uses.
     *
     * <p>The lane is the runtime's own plugin long lane, so a compute run shares the bounded worker,
     * queue and diagnostics the rest of the runtime already accounts for. A refused admission is
     * reported as a rejected execution, which the executor turns into a rejected run.</p>
     */
    private static java.util.concurrent.Executor computeLane(final RuntimeScheduler scheduler) {
        return runnable -> {
            final boolean accepted = scheduler.dispatch(
                    new PluginTask(COMPUTE_TASK_TYPE, GUARD_OWNER, COMPUTE_PAYLOAD, COMPUTE_CAPABILITY), runnable);
            if (!accepted) {
                throw new IllegalStateException(LANE_REFUSED);
            }
        };
    }

    /**
     * Returns the Editor UI thread executor the write and the completion callback return to.
     */
    private static java.util.concurrent.Executor uiThread() {
        return runnable -> EdtDispatch.post(UI_POST_LABEL, runnable);
    }

    private static AutoMeshNoticeSink noticeSink(final Consumer<StatusNotification> statusSink) {
        return notice -> {
            final StatusNotification notification =
                    new StatusNotification(STATUS_NOTIFICATION_ID, severityOf(notice), notice.message());
            try {
                statusSink.accept(notification);
            } catch (RuntimeException | LinkageError failure) {
                FatalErrors.rethrowIfFatal(failure);
                // A missing status surface must not change the guard's answer to the host.
            }
        };
    }

    private static String severityOf(final AutoMeshNotice notice) {
        return switch (notice.severity()) {
            case INFO -> SEVERITY_INFO;
            case WARNING -> SEVERITY_WARNING;
            case ERROR -> SEVERITY_ERROR;
        };
    }
}
