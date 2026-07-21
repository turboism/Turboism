package dev.turboism.sdk.cubism;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.callback.CubismCallbacks;
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

    /**
     * Legacy Preview compatibility stub.
     *
     * @deprecated Implement {@link CubismPlugin} and override lifecycle hooks.
     * This method no longer exposes callback registration semantics.
     */
    @Deprecated(forRemoval = true)
    @SuppressWarnings("removal")
    @PreviewApi
    default CubismCallbacks callbacks() {
        throw new UnsupportedOperationException(
            "Callback registration was replaced by CubismPlugin overrides"
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
