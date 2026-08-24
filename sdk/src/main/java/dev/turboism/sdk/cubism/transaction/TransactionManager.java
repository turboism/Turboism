package dev.turboism.sdk.cubism.transaction;

import dev.turboism.sdk.cubism.id.DocumentId;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.plugin.PluginContext;

/**
 * Manages write transactions for plugins.
 * Each transaction is scoped to one plugin identity and one document.
 */
public interface TransactionManager {

    /**
     * Opens a new write transaction for the given plugin context and document.
     * @throws TransactionException if a transaction is already open for this plugin+document,
     *         or if permission is denied.
     * @throws CubismPermissionException if the plugin lacks the required write permission.
     */
    ModelTransaction openTransaction(PluginContext ctx, DocumentId docId)
            throws TransactionException, CubismPermissionException;
}
