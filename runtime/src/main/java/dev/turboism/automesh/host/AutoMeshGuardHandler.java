package dev.turboism.automesh.host;

import dev.turboism.automesh.AutoMeshDispatch;
import dev.turboism.automesh.AutoMeshSelectionState;
import dev.turboism.automesh.DispatchOutcome;
import dev.turboism.automesh.DispatchResult;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm;
import dev.turboism.sdk.cubism.model.Drawable;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Binds the guarded Auto Mesh Generate funnels to the runtime's dispatch, targets and status
 * surface.
 *
 * <p>The handler answers one confirmation of the native entry. A native selection and an
 * unhandled dispatch both answer "not handled", so the Editor's own generator keeps its geometry,
 * its Undo group and its diagnostics untouched. A refusal answers "handled" and reports the
 * localized reason, because the user asked for a custom algorithm and running the native one
 * instead would silently ignore that choice. A started run answers "handled" and reports its
 * terminal result through the same status surface.</p>
 *
 * <p>Every other failure answers "not handled" and reaches the diagnostic sink, so a broken
 * resolver, a throwing algorithm or an unreachable status surface never denies the user the
 * Editor's own Auto Mesh Generate.</p>
 *
 * <p>The guard is inert while the session selects the Editor's own generator: that path reads the
 * selection alone and resolves no target, no algorithm and no host member.</p>
 */
public final class AutoMeshGuardHandler implements NativeAutoMeshBridge.Handler {

    private static final String NATIVE_ID = AutoMeshAlgorithm.NATIVE_ALGORITHM_ID;

    private final AutoMeshSelectionState selection;
    private final AutoMeshTargetResolver targets;
    private final AutoMeshDispatch dispatch;
    private static final String ROUTE_TRACE = "auto mesh guard: route algorithm=%s outcome=%s";
    private static final String RESULT_TRACE = "auto mesh guard: run settled status=%s written=%d message=%s";
    private static final String OUTCOME_UNHANDLED = "unhandled";
    private static final String OUTCOME_REFUSED = "refused:";
    private static final String OUTCOME_STARTED = "started";
    private static final String UNRESOLVED_TRACE =
            "auto mesh guard: route algorithm=%s outcome=unresolvable-selection, the native algorithm runs";

    private final AutoMeshNoticeSink notices;
    private final Consumer<String> diagnostics;
    private final AutoMeshMessages messages;

    /**
     * Creates the handler.
     *
     * @param selection the session selection the native entry shows
     * @param targets resolves the confirmed host selection to SDK ArtMeshes
     * @param dispatch routes one confirmation to the selected algorithm
     * @param notices reports refusals and terminal results to the user
     * @param diagnostics receives every swallowed failure; it must not throw
     * @param messages supplies the localized texts of every notice
     */
    public AutoMeshGuardHandler(
            final AutoMeshSelectionState selection,
            final AutoMeshTargetResolver targets,
            final AutoMeshDispatch dispatch,
            final AutoMeshNoticeSink notices,
            final Consumer<String> diagnostics,
            final AutoMeshMessages messages) {
        this.selection = Objects.requireNonNull(selection, "selection");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.dispatch = Objects.requireNonNull(dispatch, "dispatch");
        this.notices = Objects.requireNonNull(notices, "notices");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    @Override
    public Boolean handle(final AutoMeshFunnel funnel, final Object[] hostArguments) {
        try {
            return route(funnel, hostArguments);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            diagnostic("auto mesh guard: confirmation failed and the native algorithm runs: "
                    + failure.getClass().getName() + ": " + failure.getMessage());
            return Boolean.FALSE;
        }
    }

    private Boolean route(final AutoMeshFunnel funnel, final Object[] hostArguments) {
        final Optional<String> selectedId = selection.selectedAlgorithmId();
        if (selectedId.isEmpty() || NATIVE_ID.equals(selectedId.orElseThrow())) {
            return Boolean.FALSE;
        }
        final Optional<List<Drawable>> resolved = targets.resolve(funnel, hostArguments);
        if (resolved.isEmpty()) {
            diagnostic(UNRESOLVED_TRACE.formatted(selectedId.orElseThrow()));
            return Boolean.FALSE;
        }
        final DispatchOutcome outcome = dispatch.dispatch(selection, resolved.orElseThrow());
        if (outcome instanceof DispatchOutcome.Unhandled) {
            diagnostic(ROUTE_TRACE.formatted(selectedId.orElseThrow(), OUTCOME_UNHANDLED));
            return Boolean.FALSE;
        }
        if (outcome instanceof DispatchOutcome.Refused refused) {
            diagnostic(ROUTE_TRACE.formatted(selectedId.orElseThrow(), OUTCOME_REFUSED + refused.reasonCode()));
            notice(new AutoMeshNotice(AutoMeshNotice.Severity.WARNING, refused.message()));
            return Boolean.TRUE;
        }
        final DispatchOutcome.Started started = (DispatchOutcome.Started) outcome;
        diagnostic(ROUTE_TRACE.formatted(selectedId.orElseThrow(), OUTCOME_STARTED));
        started.run().completion().whenComplete((result, failure) -> report(result, failure));
        return Boolean.TRUE;
    }

    /**
     * Reports the terminal result of one started run. The completion stage settles on the Editor
     * UI thread, so the notice is rendered on the thread the status surface requires.
     */
    private void report(final DispatchResult result, final Throwable failure) {
        try {
            if (failure != null) {
                diagnostic("auto mesh guard: run completion failed: "
                        + failure.getClass().getName());
                notice(new AutoMeshNotice(AutoMeshNotice.Severity.ERROR, messages.runFailed()));
                return;
            }
            diagnostic(RESULT_TRACE.formatted(
                    result.status(), result.writtenTargets().size(), result.message()));
            notice(noticeOf(result));
        } catch (Throwable reporting) {
            FatalErrors.rethrowIfFatal(reporting);
            diagnostic("auto mesh guard: run result could not be reported: "
                    + reporting.getClass().getName());
        }
    }

    /**
     * Shows one notice without letting the status surface change the answer already given.
     *
     * <p>A missing or failing surface is a reporting fault, not a reason to run a different
     * algorithm than the user selected.</p>
     */
    private void notice(final AutoMeshNotice notice) {
        try {
            notices.show(notice);
        } catch (RuntimeException | LinkageError failure) {
            FatalErrors.rethrowIfFatal(failure);
            diagnostic("auto mesh guard: notice could not be shown: "
                    + failure.getClass().getName());
        }
    }

    private AutoMeshNotice noticeOf(final DispatchResult result) {
        return switch (result.status()) {
            case APPLIED ->
                new AutoMeshNotice(
                        AutoMeshNotice.Severity.INFO,
                        messages.applied(result.writtenTargets().size()));
            case REJECTED -> new AutoMeshNotice(AutoMeshNotice.Severity.WARNING, messages.rejected(result.message()));
            case CANCELLED -> new AutoMeshNotice(AutoMeshNotice.Severity.INFO, messages.cancelled());
            case FAILED -> new AutoMeshNotice(AutoMeshNotice.Severity.ERROR, messages.failed(result.message()));
        };
    }

    private void diagnostic(final String message) {
        try {
            diagnostics.accept(message);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            // A diagnostic sink must never change whether the native algorithm runs.
        }
    }
}
