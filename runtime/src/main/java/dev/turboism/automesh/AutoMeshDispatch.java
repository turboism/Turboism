package dev.turboism.automesh;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshRequest;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshResult;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionService;
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
import java.util.concurrent.atomic.AtomicReference;

/**
 * Routes one native Auto Mesh Generate confirmation to the Editor's generator or to a registered
 * algorithm, and applies a custom result as one Undo entry.
 *
 * <p>The native path is the priority: a native selection returns {@link DispatchOutcome#UNHANDLED}
 * after the selection check alone, so the host body keeps its geometry, its Undo group and its
 * diagnostics untouched.</p>
 *
 * <p>A custom run reads its targets on the UI thread, computes off it, and returns to the UI thread
 * for one {@link AuthoringTransactionService#execute} that replaces every target. All-or-nothing
 * holds in both directions: one rejected or missing result writes nothing, and a refused
 * transaction leaves every target untouched.</p>
 *
 * <p>The plugin returns one layout. A target that stores ordinary keyforms receives that layout in
 * every form: the write installs the new source topology and then submits the positions derived
 * for each form in one compare-and-set batch, so the result equals the Editor's own generator and
 * one Undo reverts it. A form the batch cannot address, or a stored bank that the topology write
 * does not keep, refuses the write before or inside the transaction.</p>
 *
 * <p>Two preconditions are checked, once before the compute and once before the write: no native
 * Undo group may be open, and the targets must be readable. The host binds the Undo precondition at
 * the milestone that injects the native entry, so the framework never nests its transaction inside
 * the host's own edit group.</p>
 */
public final class AutoMeshDispatch {

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

    /** Undo label of the one transaction that commits a whole selection. */
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
    private static final String MESSAGE_TRANSACTION_REFUSED = "The Editor did not commit the mesh update";
    private static final String MESSAGE_UI_REJECTED = "The Editor refused the auto-mesh result.";
    private static final String DIAGNOSTIC_PREFIX = " (";
    private static final String DIAGNOSTIC_SUFFIX = ")";

    /** Keyform count of a target that stores its neutral form only. */
    private static final int SINGLE_KEYFORM_COUNT = 1;

    private final AutoMeshRequestBuilder requestBuilder;
    private final AutoMeshExecutor executor;
    private final AuthoringTransactionService transactions;
    private final AutoMeshUndoGroupProbe undoGroup;
    private final Executor uiThread;

    /**
     * Creates the dispatcher.
     *
     * @param requestBuilder reads selected targets on the UI thread
     * @param executor runs the algorithm off the UI thread
     * @param transactions commits the whole selection as one Undo entry
     * @param undoGroup reports whether the host holds an open native Undo group
     * @param uiThread the Editor UI thread the write returns to
     */
    public AutoMeshDispatch(
            final AutoMeshRequestBuilder requestBuilder,
            final AutoMeshExecutor executor,
            final AuthoringTransactionService transactions,
            final AutoMeshUndoGroupProbe undoGroup,
            final Executor uiThread) {
        this.requestBuilder = Objects.requireNonNull(requestBuilder, "requestBuilder");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.undoGroup = Objects.requireNonNull(undoGroup, "undoGroup");
        this.uiThread = Objects.requireNonNull(uiThread, "uiThread");
    }

    /**
     * Routes one confirmation of the native Auto Mesh Generate entry.
     *
     * @param selection the current session selection and its parameter values
     * @param targets the selected ArtMeshes, in selection order
     * @return the native hand-off, a refusal, or a started run
     */
    public DispatchOutcome dispatch(final AutoMeshSelectionState selection, final List<Drawable> targets) {
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
            final AutoMeshSelectionState selection, final AutoMeshAlgorithm algorithm, final List<Drawable> targets) {
        final Map<String, String> parameterValues = selection.parameterValues(algorithm);
        final List<AutoMeshRequest> requests = new ArrayList<>(targets.size());
        for (final Drawable target : targets) {
            requests.add(requestBuilder.build(target, parameterValues));
        }
        return List.copyOf(requests);
    }

    /**
     * Refuses a keyformed target whose stored forms the host cannot read.
     *
     * <p>The keyform-complete write needs the stored bank to derive each form's positions. A host
     * without that read can only replace the form the Editor evaluates, so the run must not start
     * and silently drop the other forms.</p>
     */
    private static DispatchOutcome refuseUnreadableKeyforms(
            final List<AutoMeshRequest> requests, final List<Drawable> targets) {
        for (int index = 0; index < requests.size(); index++) {
            final AutoMeshRequest request = requests.get(index);
            if (request.keyformCount() <= SINGLE_KEYFORM_COUNT) {
                continue;
            }
            try {
                targets.get(index).authoringSnapshot();
            } catch (RuntimeException unavailable) {
                FatalErrors.rethrowIfFatal(unavailable);
                return new DispatchOutcome.Refused(
                        REASON_KEYFORM_UNREADABLE, MESSAGE_KEYFORM_UNREADABLE + request.targetId() + MESSAGE_SUFFIX);
            }
        }
        return null;
    }

    private DispatchOutcome start(
            final AutoMeshAlgorithm algorithm, final List<AutoMeshRequest> requests, final List<Drawable> targets) {
        final AutoMeshExecutor.Handle handle = executor.submit(algorithm, requests);
        final RuntimeRun run = new RuntimeRun(handle, targets);
        handle.completion().whenComplete((results, failure) -> postToUiThread(run, results, failure));
        return new DispatchOutcome.Started(run);
    }

    private void postToUiThread(final RuntimeRun run, final List<AutoMeshResult> results, final Throwable failure) {
        try {
            uiThread.execute(() -> settle(run, results, failure));
        } catch (RuntimeException rejected) {
            FatalErrors.rethrowIfFatal(rejected);
            run.settle(new DispatchResult(DispatchResult.Status.FAILED, MESSAGE_UI_REJECTED, List.of()));
        }
    }

    private void settle(final RuntimeRun run, final List<AutoMeshResult> results, final Throwable failure) {
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

    private void write(final RuntimeRun run, final List<ArtMeshGeometry> geometries, final String summary) {
        final List<Drawable> targets = run.targets();
        // Derive before the transaction opens, so a form the batch cannot address leaves no Undo entry.
        final List<Optional<AutoMeshKeyformLayout>> keyforms = new ArrayList<>(targets.size());
        try {
            for (int index = 0; index < targets.size(); index++) {
                keyforms.add(AutoMeshKeyformLayout.of(targets.get(index), geometries.get(index)));
            }
        } catch (AutoMeshKeyformLayout.Refusal refused) {
            run.settle(keyformRejection(refused));
            return;
        } catch (RuntimeException failed) {
            FatalErrors.rethrowIfFatal(failed);
            run.settle(new DispatchResult(DispatchResult.Status.FAILED, AutoMeshFailures.describe(failed), List.of()));
            return;
        }
        final AtomicReference<AutoMeshKeyformLayout.Refusal> refusal = new AtomicReference<>();
        final AuthoringTransactionResult<List<ArtMeshId>> transaction;
        try {
            transaction = transactions.execute(AuthoringTransactionOptions.of(UNDO_LABEL), () -> {
                final List<ArtMeshId> written = new ArrayList<>(targets.size());
                for (int index = 0; index < targets.size(); index++) {
                    final Drawable target = targets.get(index);
                    target.replaceGeometry(geometries.get(index));
                    commitKeyforms(target, geometries.get(index), keyforms.get(index), refusal);
                    written.add(target.id());
                }
                return List.copyOf(written);
            });
        } catch (RuntimeException failed) {
            FatalErrors.rethrowIfFatal(failed);
            final AutoMeshKeyformLayout.Refusal rejected = keyformRefusal(failed, refusal.get());
            if (rejected != null) {
                run.settle(keyformRejection(rejected));
                return;
            }
            run.settle(new DispatchResult(DispatchResult.Status.FAILED, AutoMeshFailures.describe(failed), List.of()));
            return;
        }
        final List<ArtMeshId> written = transaction.value().orElse(null);
        if (written == null) {
            final AutoMeshKeyformLayout.Refusal rejected = refusal.get();
            if (rejected != null) {
                run.settle(keyformRejection(rejected));
                return;
            }
            run.settle(new DispatchResult(DispatchResult.Status.REJECTED, transactionRefused(transaction), List.of()));
            return;
        }
        run.settle(new DispatchResult(DispatchResult.Status.APPLIED, summary, written));
    }

    /**
     * Applies one derived keyform batch after its topology write and aborts on a refusal.
     *
     * <p>The transaction must roll back a refusal, so the signal leaves the callback. The recorded
     * reference survives services that convert the callback failure into a rolled-back result.</p>
     */
    private static void commitKeyforms(
            final Drawable target,
            final ArtMeshGeometry layout,
            final Optional<AutoMeshKeyformLayout> keyforms,
            final AtomicReference<AutoMeshKeyformLayout.Refusal> refusal) {
        try {
            keyforms.ifPresent(value -> value.apply(target, layout));
        } catch (AutoMeshKeyformLayout.Refusal rejected) {
            refusal.set(rejected);
            throw rejected;
        }
    }

    private static DispatchResult keyformRejection(final AutoMeshKeyformLayout.Refusal refused) {
        return new DispatchResult(DispatchResult.Status.REJECTED, refused.getMessage(), List.of());
    }

    private static AutoMeshKeyformLayout.Refusal keyformRefusal(
            final Throwable failure, final AutoMeshKeyformLayout.Refusal recorded) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof AutoMeshKeyformLayout.Refusal rejected) {
                return rejected;
            }
            current = current.getCause();
        }
        return recorded;
    }

    private static DispatchResult unsettledFailure(final Throwable failure) {
        final Throwable cause =
                failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        if (cause instanceof TimeoutException) {
            return new DispatchResult(DispatchResult.Status.REJECTED, MESSAGE_TIMEOUT, List.of());
        }
        return new DispatchResult(DispatchResult.Status.FAILED, AutoMeshFailures.describe(cause), List.of());
    }

    private static String transactionRefused(final AuthoringTransactionResult<List<ArtMeshId>> transaction) {
        final String diagnostic = transaction
                .diagnosticId()
                .map(id -> DIAGNOSTIC_PREFIX + id + DIAGNOSTIC_SUFFIX)
                .orElse("");
        return MESSAGE_TRANSACTION_REFUSED + " (" + transaction.outcome() + ")" + diagnostic + ".";
    }

    private static DispatchResult cancelled() {
        return new DispatchResult(DispatchResult.Status.CANCELLED, "", List.of());
    }

    /** One started run: the executor handle, its targets, and the terminal result it settles once. */
    private static final class RuntimeRun implements DispatchOutcome.Run {

        private final AutoMeshExecutor.Handle handle;
        private final List<Drawable> targets;
        private final CompletableFuture<DispatchResult> settled = new CompletableFuture<>();
        private volatile boolean cancelled;

        private RuntimeRun(final AutoMeshExecutor.Handle handle, final List<Drawable> targets) {
            this.handle = handle;
            this.targets = List.copyOf(targets);
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

        private List<Drawable> targets() {
            return targets;
        }
    }
}
