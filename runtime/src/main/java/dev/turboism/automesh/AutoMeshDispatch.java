package dev.turboism.automesh;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshRequest;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshResult;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;

/**
 * Routes one native Auto Mesh Generate confirmation to the Editor's generator or to a registered
 * algorithm, and applies a custom result the way the Editor's own generator applies its own.
 *
 * <p>The native path is the priority: a native selection returns {@link DispatchOutcome#UNHANDLED}
 * after the selection check alone, so the host body keeps its geometry, its Undo group and its
 * diagnostics untouched.</p>
 *
 * <p>A custom run reads its targets on the UI thread, computes off it, and returns to the UI thread
 * for one {@link AutoMeshEditableWrite} that replaces the editable mesh of every target. All-or-
 * nothing holds in both directions: one rejected or missing result writes nothing, and a refused
 * write leaves every target untouched.</p>
 *
 * <p>The write owns the whole Editor contract: canvas coordinates, the target's own editable mesh,
 * the Undo envelope of the funnel, and the topology notifications. The plugin therefore returns one
 * layout and never touches source geometry, stored keyforms or the Undo history itself, because the
 * Editor derives all three from the editable mesh exactly as it does after its own run.</p>
 *
 * <p>The Undo precondition is checked once before the compute and once before the write. The host
 * binds it at the milestone that injects the native entry, so the framework never nests its own
 * envelope inside the host's open edit group.</p>
 */
public final class AutoMeshDispatch<T extends AutoMeshTarget> {

    /** Reason code of a confirmation that selected no target. */
    public static final String REASON_NO_TARGETS = "no-targets";

    /** Reason code of a target whose SDK reads failed before the run started. */
    public static final String REASON_TARGET_UNREADABLE = "target-unreadable";

    /** Reason code of a keyformed target whose stored forms the host cannot expose. */
    public static final String REASON_KEYFORM_UNREADABLE = "keyform-unreadable";

    /** Reason code of a selection whose algorithm disappeared before the run started. */
    public static final String REASON_ALGORITHM_UNAVAILABLE = "algorithm-unavailable";

    /** Reason code of a confirmation that arrived while a native Undo group was open. */
    public static final String REASON_NATIVE_UNDO_OPEN = "native-undo-open";

    /** Undo label of the one Editor operation that commits a whole selection. */
    public static final String UNDO_LABEL = "Auto Mesh";

    private static final String MESSAGE_NO_TARGETS = "Select at least one ArtMesh.";
    private static final String MESSAGE_TARGET_UNREADABLE =
            "A selected ArtMesh cannot be read, so the run did not start.";
    private static final String MESSAGE_KEYFORM_UNREADABLE = "The Editor cannot read the keyforms of ";
    private static final String MESSAGE_SUFFIX = ".";
    private static final String MESSAGE_ALGORITHM_UNAVAILABLE = "The selected algorithm is no longer available.";
    private static final String MESSAGE_NATIVE_UNDO_OPEN =
            "Finish the current Editor operation before generating a mesh.";
    private static final String MESSAGE_GEOMETRY_EMPTY = "The algorithm produced a mesh without vertices.";
    private static final String MESSAGE_TIMEOUT = "The algorithm exceeded its time limit.";
    private static final String MESSAGE_RESULT_MISMATCH = "The algorithm returned an unexpected number of results.";
    private static final String MESSAGE_UI_REJECTED = "The Editor refused the auto-mesh result.";

    /** Keyform count of a target that stores its neutral form only. */
    private static final int SINGLE_KEYFORM_COUNT = 1;

    private final AutoMeshRequestBuilder requestBuilder;
    private final AutoMeshExecutor executor;
    private final AutoMeshEditableWrite<T> editableWrite;
    private final AutoMeshUndoGroupProbe undoGroup;
    private final Executor uiThread;

    /**
     * Creates the dispatcher.
     *
     * @param requestBuilder reads selected targets on the UI thread
     * @param executor runs the algorithm off the UI thread
     * @param editableWrite replaces the editable mesh of every target as one Editor operation
     * @param undoGroup reports whether the host holds an open native Undo group
     * @param uiThread the Editor UI thread the write returns to
     */
    public AutoMeshDispatch(
            final AutoMeshRequestBuilder requestBuilder,
            final AutoMeshExecutor executor,
            final AutoMeshEditableWrite<T> editableWrite,
            final AutoMeshUndoGroupProbe undoGroup,
            final Executor uiThread) {
        this.requestBuilder = Objects.requireNonNull(requestBuilder, "requestBuilder");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.editableWrite = Objects.requireNonNull(editableWrite, "editableWrite");
        this.undoGroup = Objects.requireNonNull(undoGroup, "undoGroup");
        this.uiThread = Objects.requireNonNull(uiThread, "uiThread");
    }

    /**
     * Routes one confirmation of the native Auto Mesh Generate entry.
     *
     * @param selection the current session selection and its parameter values
     * @param targets the resolved targets, in selection order
     * @return the native hand-off, a refusal, or a started run
     */
    public DispatchOutcome dispatch(final AutoMeshSelectionState selection, final List<T> targets) {
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(targets, "targets");
        final Optional<String> selectedId = selection.selectedAlgorithmId();
        if (selectedId.isEmpty() || AutoMeshAlgorithm.NATIVE_ALGORITHM_ID.equals(selectedId.get())) {
            return DispatchOutcome.UNHANDLED;
        }
        if (targets.isEmpty()) {
            return new DispatchOutcome.Refused(REASON_NO_TARGETS, MESSAGE_NO_TARGETS);
        }
        if (undoGroup.undoGroupOpen()) {
            return new DispatchOutcome.Refused(REASON_NATIVE_UNDO_OPEN, MESSAGE_NATIVE_UNDO_OPEN);
        }
        final AutoMeshAlgorithm algorithm = selection.find(selectedId.get()).orElse(null);
        if (algorithm == null || algorithm.isNative()) {
            return new DispatchOutcome.Refused(REASON_ALGORITHM_UNAVAILABLE, MESSAGE_ALGORITHM_UNAVAILABLE);
        }
        final List<AutoMeshRequest> requests;
        try {
            requests = buildRequests(selection, algorithm, targets);
        } catch (RuntimeException unreadable) {
            FatalErrors.rethrowIfFatal(unreadable);
            return new DispatchOutcome.Refused(REASON_TARGET_UNREADABLE, MESSAGE_TARGET_UNREADABLE);
        }
        final DispatchOutcome unreadableKeyforms = refuseUnreadableKeyforms(requests, targets);
        if (unreadableKeyforms != null) {
            return unreadableKeyforms;
        }
        return start(algorithm, requests, targets);
    }

    private List<AutoMeshRequest> buildRequests(
            final AutoMeshSelectionState selection, final AutoMeshAlgorithm algorithm, final List<T> targets) {
        final Map<String, String> parameterValues = selection.parameterValues(algorithm);
        final List<AutoMeshRequest> requests = new ArrayList<>(targets.size());
        for (final T target : targets) {
            requests.add(requestBuilder.build(target.drawable(), parameterValues));
        }
        return List.copyOf(requests);
    }

    /**
     * Refuses a keyformed target whose stored forms the host cannot read.
     *
     * <p>The Editor derives every stored form from the editable mesh after the topology change, so
     * the run only starts when the host can expose those forms at all. A host without that read
     * hides whether the topology write kept them, and the run would report a success the user
     * cannot verify.</p>
     */
    private static DispatchOutcome refuseUnreadableKeyforms(
            final List<AutoMeshRequest> requests, final List<? extends AutoMeshTarget> targets) {
        for (int index = 0; index < requests.size(); index++) {
            final AutoMeshRequest request = requests.get(index);
            if (request.keyformCount() <= SINGLE_KEYFORM_COUNT) {
                continue;
            }
            try {
                targets.get(index).drawable().authoringSnapshot();
            } catch (RuntimeException unavailable) {
                FatalErrors.rethrowIfFatal(unavailable);
                return new DispatchOutcome.Refused(
                        REASON_KEYFORM_UNREADABLE, MESSAGE_KEYFORM_UNREADABLE + request.targetId() + MESSAGE_SUFFIX);
            }
        }
        return null;
    }

    private DispatchOutcome start(
            final AutoMeshAlgorithm algorithm, final List<AutoMeshRequest> requests, final List<T> targets) {
        final AutoMeshExecutor.Handle handle = executor.submit(algorithm, requests);
        final RuntimeRun<T> run = new RuntimeRun<>(handle, targets, requests);
        handle.completion().whenComplete((results, failure) -> postToUiThread(run, results, failure));
        return new DispatchOutcome.Started(run);
    }

    private void postToUiThread(final RuntimeRun<T> run, final List<AutoMeshResult> results, final Throwable failure) {
        try {
            uiThread.execute(() -> settle(run, results, failure));
        } catch (RuntimeException rejected) {
            FatalErrors.rethrowIfFatal(rejected);
            run.settle(new DispatchResult(DispatchResult.Status.FAILED, MESSAGE_UI_REJECTED, List.of()));
        }
    }

    private void settle(final RuntimeRun<T> run, final List<AutoMeshResult> results, final Throwable failure) {
        if (run.cancelled()) {
            run.settle(cancelled());
            return;
        }
        if (failure != null) {
            run.settle(unsettledFailure(failure));
            return;
        }
        final List<ArtMeshGeometry> geometries = new ArrayList<>(results.size());
        String summary = "";
        for (final AutoMeshResult result : results) {
            if (result instanceof AutoMeshResult.Cancelled) {
                run.settle(cancelled());
                return;
            }
            if (result instanceof AutoMeshResult.Rejected rejected) {
                run.settle(new DispatchResult(DispatchResult.Status.REJECTED, rejected.reason(), List.of()));
                return;
            }
            final AutoMeshResult.Success success = (AutoMeshResult.Success) result;
            if (success.geometry().positions().isEmpty()) {
                run.settle(new DispatchResult(DispatchResult.Status.REJECTED, MESSAGE_GEOMETRY_EMPTY, List.of()));
                return;
            }
            geometries.add(success.geometry());
            if (summary.isEmpty()) {
                summary = success.diagnosticSummary();
            }
        }
        if (geometries.size() != run.targets().size()) {
            run.settle(new DispatchResult(DispatchResult.Status.FAILED, MESSAGE_RESULT_MISMATCH, List.of()));
            return;
        }
        if (undoGroup.undoGroupOpen()) {
            run.settle(new DispatchResult(DispatchResult.Status.REJECTED, MESSAGE_NATIVE_UNDO_OPEN, List.of()));
            return;
        }
        write(run, geometries, summary);
    }

    private void write(final RuntimeRun<T> run, final List<ArtMeshGeometry> geometries, final String summary) {
        final List<AutoMeshWriteLayout> layouts = run.layouts(geometries);
        final List<ArtMeshId> written;
        try {
            written = editableWrite.write(new AutoMeshWrite<>(UNDO_LABEL, run.targets(), layouts));
        } catch (AutoMeshWriteRefusal refused) {
            run.settle(new DispatchResult(DispatchResult.Status.REJECTED, refused.getMessage(), List.of()));
            return;
        } catch (RuntimeException failed) {
            FatalErrors.rethrowIfFatal(failed);
            run.settle(new DispatchResult(DispatchResult.Status.FAILED, AutoMeshFailures.describe(failed), List.of()));
            return;
        }
        run.settle(new DispatchResult(DispatchResult.Status.APPLIED, summary, written));
    }

    private static DispatchResult unsettledFailure(final Throwable failure) {
        final Throwable cause =
                failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        if (cause instanceof TimeoutException) {
            return new DispatchResult(DispatchResult.Status.REJECTED, MESSAGE_TIMEOUT, List.of());
        }
        return new DispatchResult(DispatchResult.Status.FAILED, AutoMeshFailures.describe(cause), List.of());
    }

    private static DispatchResult cancelled() {
        return new DispatchResult(DispatchResult.Status.CANCELLED, "", List.of());
    }

    /** One started run: the executor handle, its targets, its requests, and its terminal result. */
    private static final class RuntimeRun<T extends AutoMeshTarget> implements DispatchOutcome.Run {

        private final AutoMeshExecutor.Handle handle;
        private final List<T> targets;
        private final List<AutoMeshRequest> requests;
        private final CompletableFuture<DispatchResult> settled = new CompletableFuture<>();
        private volatile boolean cancelled;

        private RuntimeRun(
                final AutoMeshExecutor.Handle handle, final List<T> targets, final List<AutoMeshRequest> requests) {
            this.handle = handle;
            this.targets = List.copyOf(targets);
            this.requests = List.copyOf(requests);
        }

        @Override
        public CompletionStage<DispatchResult> completion() {
            return settled;
        }

        @Override
        public boolean cancel() {
            cancelled = true;
            handle.cancel();
            return !settled.isDone();
        }

        private void settle(final DispatchResult result) {
            settled.complete(result);
        }

        private boolean cancelled() {
            return cancelled;
        }

        private List<T> targets() {
            return targets;
        }

        /** Pairs every returned layout with the texture its own request carried. */
        private List<AutoMeshWriteLayout> layouts(final List<ArtMeshGeometry> geometries) {
            final List<AutoMeshWriteLayout> layouts = new ArrayList<>(geometries.size());
            for (int index = 0; index < geometries.size(); index++) {
                layouts.add(new AutoMeshWriteLayout(
                        geometries.get(index), requests.get(index).texture()));
            }
            return List.copyOf(layouts);
        }
    }
}
