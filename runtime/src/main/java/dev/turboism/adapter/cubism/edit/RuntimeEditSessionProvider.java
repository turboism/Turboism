package dev.turboism.adapter.cubism.edit;

import dev.turboism.sdk.cubism.edit.EditSessionService;
import dev.turboism.sdk.cubism.id.DocumentId;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Implemented by runtime model-access objects that can back the editing-session surface.
 *
 * <p>Mirrors {@code RuntimeAuthoringTransactionProvider}: the services factory asks the bound
 * model access for a plugin-scoped service, which fails closed when the connected host lacks
 * verified session bindings.</p>
 */
public interface RuntimeEditSessionProvider {

    /**
     * Creates a stable service view for the owning plugin.
     *
     * @param pluginId nonblank owning plugin identity
     * @param activeDocumentId supplies the host's active-document identity per call; empty means
     *     no document is active, so {@code open} fails closed
     * @return service that resolves the current host binding on each invocation
     */
    EditSessionService editSessions(
        String pluginId,
        Supplier<Optional<DocumentId>> activeDocumentId
    );
}
