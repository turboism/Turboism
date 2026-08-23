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

    HostSnapshot capture(DocumentId documentId) throws TransactionException;

    void apply(DocumentId documentId, List<CubismWriteCommand> commands) throws TransactionException;

    void restore(HostSnapshot snapshot) throws TransactionException;

    long version();

    interface HostSnapshot {
        DocumentId documentId();

        long version();
    }
}
