package dev.turboism.sdk.cubism;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.core.CoreRuntimeInfo;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.transaction.TransactionManager;
import java.util.Optional;

/** View of the Cubism host exposed to plugins. */
public interface CubismFacade {

    CubismRuntimeSnapshot runtime();

    Optional<ProjectSnapshot> activeProject();

    Optional<DocumentSnapshot> activeDocument();

    Optional<ModelSnapshot> activeModel();

    boolean isHostPresent();

    /** Returns permission-checked Cubism Core metadata and MOC inspection. */
    @PreviewApi
    default CoreRuntimeInfo coreRuntime() {
        throw new UnsupportedOperationException(
            "Cubism Core runtime metadata is unavailable."
        );
    }

    /**
     * Returns the unified model object API.
     *
     * <p>The default keeps existing implementations source-compatible until a
     * Runtime backend is installed.</p>
     */
    @PreviewApi
    default CubismModelAccess model() {
        throw new UnsupportedOperationException(
            "Unified Cubism model access is unavailable"
        );
    }

    /** Returns the legacy transaction manager for Preview compatibility. */
    @PreviewApi
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
