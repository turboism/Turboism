package dev.turboism.adapter.cubism.write;

import dev.turboism.sdk.cubism.id.DocumentId;
import dev.turboism.sdk.cubism.transaction.TransactionException;
import dev.turboism.sdk.cubism.write.CubismWriteCommand;

import java.util.List;

/**
 * The single seam through which the runtime mutates live Editor model
 * state. Implementations wrap the version-specific Cubism host, so all
 * host-version knowledge stays behind this interface.
 *
 * <p>Implementations are expected to be called on the host thread by way
 * of the runtime scheduler; they do not schedule work themselves.</p>
 */
public interface HostWriteAdapter {

    /**
     * Captures the mutable state a later {@link #restore} can roll back to.
     *
     * @param documentId the document whose state is captured
     * @return the captured snapshot
     * @throws TransactionException when the document cannot be captured
     */
    HostSnapshot capture(DocumentId documentId) throws TransactionException;

    /**
     * Applies a group of write commands to one document.
     *
     * @param documentId the target document
     * @param commands the ordered commands to apply
     * @throws TransactionException when the group cannot be applied
     */
    void apply(DocumentId documentId, List<CubismWriteCommand> commands) throws TransactionException;

    /**
     * Rolls the document back to a previously captured snapshot.
     *
     * @param snapshot a snapshot this adapter returned from {@link #capture}
     * @throws TransactionException when the state cannot be restored
     */
    void restore(HostSnapshot snapshot) throws TransactionException;

    /**
     * @return the adapter's current write-state version; callers compare it across a
     *         capture/apply/restore sequence to detect concurrent mutation
     */
    long version();

    /** An opaque captured write state owned by the adapter that produced it. */
    interface HostSnapshot {
        /**
         * @return the document the snapshot belongs to
         */
        DocumentId documentId();

        /**
         * @return the write-state version the snapshot was taken at
         */
        long version();
    }
}
