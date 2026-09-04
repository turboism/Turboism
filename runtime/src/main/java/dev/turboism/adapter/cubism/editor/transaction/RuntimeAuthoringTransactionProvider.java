package dev.turboism.adapter.cubism.editor.transaction;

import dev.turboism.sdk.cubism.transaction.AuthoringTransactionService;

/** Runtime-internal provider that binds authoring transactions to one owning plugin identity. */
public interface RuntimeAuthoringTransactionProvider {

    /**
     * Creates a stable service view for the owning plugin.
     *
     * @param pluginId nonblank owning plugin identity
     * @return service that resolves the current host binding on each root invocation
     */
    AuthoringTransactionService authoringTransactions(String pluginId);
}
