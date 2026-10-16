package dev.turboism.sdk.cubism.transaction;

import dev.turboism.sdk.CubismEditor;
import java.util.Objects;

/**
 * Executes one synchronous callback inside a Runtime-owned Editor authoring transaction.
 *
 * <p>The implementation owns host-thread admission, active document/model binding, native Undo
 * grouping, abort and recovery, and transaction diagnostics. The callback receives no transaction
 * handle and may interact only through normal SDK services available to the plugin.</p>
 *
 * <p>This is the one recommended transaction entry for plugin writes. Use it when several
 * writes must commit or roll back together or share a single Undo entry; a lone {@code
 * Parameter.setValue} call needs no transaction wrapper.
 *
 * <p>The callback runs on the host's UI thread (the Cubism Editor Swing event dispatch thread).
 * It must be brief and must not block or wait for other UI-thread tasks — doing so deadlocks the
 * host. SDK writes inside the callback that produce an admissible native Undo object — both
 * coordinator-backed writes such as {@code Parameter.setValue} and the migrated hand-written
 * Undo envelopes — join the ambient transaction's single Undo group and commit or roll back
 * with it. SDK writes whose native Undo cannot be admitted to the ambient edit (animation
 * timeline writes, parameter-definition updates, writes that explicitly bypass history) reject
 * with a typed failure instead of creating detached Undo state.</p>
 */
@CubismEditor(from = "5.0.00")
public interface AuthoringTransactionService {

    /**
     * Executes authoring work synchronously on the host UI thread.
     *
     * @param options validated transaction options
     * @param work synchronous callback run on the host UI thread; never invoked when the service
     *     is unavailable or preflight rejects the request, and must not block or wait for other
     *     UI-thread tasks
     * @param <T> callback result type
     * @return typed terminal result and immutable history evidence
     */
    <T> AuthoringTransactionResult<T> execute(AuthoringTransactionOptions options, AuthoringTransactionWork<T> work);

    /**
     * Groups stored-source writes behind one synchronous instance-evaluation barrier.
     *
     * <p>Call only within an active authoring transaction, for parameter bindings and
     * ordinary ArtMesh keyform batches and stored ArtMesh draw-order scalars (including
     * the default form). The callback may read stored authoring snapshots;
     * it must not reparent objects, access evaluated geometry, change parameter values,
     * or perform texture operations. Instances are current again before this method returns,
     * including when the callback throws. Native Undo and readback remain per write.
     * Implementations without this optimization execute the callback normally.</p>
     *
     * @param work synchronous stored-source writes; must not start a nested batch
     */
    default void batchStoredSourceWrites(final Runnable work) {
        Objects.requireNonNull(work, "work").run();
    }

    /**
     * Returns the fail-closed implementation used when Runtime has no verified transaction backend.
     *
     * @return shared unavailable service
     */
    static AuthoringTransactionService unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Fail-closed implementation that never invokes authoring work. */
    enum Unavailable implements AuthoringTransactionService {
        INSTANCE;

        @Override
        public void batchStoredSourceWrites(final Runnable work) {
            Objects.requireNonNull(work, "work");
            throw new UnsupportedOperationException("Authoring transactions unavailable");
        }

        @Override
        public <T> AuthoringTransactionResult<T> execute(
                final AuthoringTransactionOptions options, final AuthoringTransactionWork<T> work) {
            Objects.requireNonNull(options, "options");
            Objects.requireNonNull(work, "work");
            return AuthoringTransactionResult.unavailable("cubism.authoring.transactions.unavailable");
        }
    }
}
