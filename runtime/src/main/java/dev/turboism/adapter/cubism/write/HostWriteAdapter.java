package dev.turboism.adapter.cubism.write;

import dev.turboism.sdk.cubism.transaction.DocumentId;
import dev.turboism.sdk.cubism.transaction.TransactionException;
import dev.turboism.sdk.cubism.write.WriteParameterCommand;

import java.util.List;

public interface HostWriteAdapter {

    HostSnapshot capture(DocumentId documentId) throws TransactionException;

    void apply(DocumentId documentId, List<WriteParameterCommand> commands) throws TransactionException;

    void restore(HostSnapshot snapshot) throws TransactionException;

    long version();

    interface HostSnapshot {
        DocumentId documentId();

        long version();
    }
}
