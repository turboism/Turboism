package dev.turboism.sdk.cubism;

import dev.turboism.sdk.cubism.transaction.TransactionManager;
import java.util.Optional;

/**
 * View of the Cubism host exposed to plugins.
 * Read-only by default; write operations require an open transaction.
 */
public interface CubismFacade {

    CubismRuntimeSnapshot runtime();

    Optional<ProjectSnapshot> activeProject();

    Optional<DocumentSnapshot> activeDocument();

    Optional<ModelSnapshot> activeModel();

    boolean isHostPresent();

    /** Returns the transaction manager for write operations. */
    TransactionManager transactionManager();

    default boolean hasActiveProject() {
        return activeProject().isPresent();
    }

    default boolean hasActiveDocument() {
        return activeDocument().isPresent();
    }

    default boolean hasActiveModel() {
        return activeModel().isPresent();
    }
}
