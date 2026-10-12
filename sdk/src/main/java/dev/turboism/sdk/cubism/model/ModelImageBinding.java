package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.RawImageId;
import java.util.Objects;
import java.util.Optional;

/**
 * Result handle for one model-image registration or texture binding.
 *
 * <p>The handle is a detached value snapshot of the operation outcome: it records
 * the authoring scope, the model identity and generation the members were created
 * under, the registered raw/model image identities, and the bound ArtMesh when the
 * operation bound one. Group membership is expressed through the member identity
 * and the model generation, not through a group name or GUID. A handle captured
 * from a stale generation does not resurrect by GUID equality alone — every use is
 * re-checked against the active generation and member ownership.</p>
 *
 * @param scope authoring scope identity that owns the operation
 * @param modelId owning model identity
 * @param modelGeneration model generation the members belong to
 * @param rawImageId registered raw layered image identity
 * @param modelImageId registered model image identity
 * @param artMeshId bound ArtMesh identity, empty for a registration-only result
 */
@CubismEditor(from = "5.0.00")
public record ModelImageBinding(
        String scope,
        ModelId modelId,
        long modelGeneration,
        RawImageId rawImageId,
        ModelImageId modelImageId,
        Optional<ArtMeshId> artMeshId) {

    /** Validates one detached result handle. */
    public ModelImageBinding {
        scope = Objects.requireNonNull(scope, "scope").strip();
        if (scope.isEmpty()) {
            throw new IllegalArgumentException("scope must not be blank");
        }
        if (scope.length() > 256) {
            throw new IllegalArgumentException("scope must not exceed 256 characters");
        }
        modelId = Objects.requireNonNull(modelId, "modelId");
        if (modelGeneration <= 0) {
            throw new IllegalArgumentException("modelGeneration must be positive");
        }
        rawImageId = Objects.requireNonNull(rawImageId, "rawImageId");
        modelImageId = Objects.requireNonNull(modelImageId, "modelImageId");
        artMeshId = Objects.requireNonNull(artMeshId, "artMeshId");
    }

    /** Whether this handle carries a bound ArtMesh. */
    public boolean bound() {
        return artMeshId.isPresent();
    }
}
