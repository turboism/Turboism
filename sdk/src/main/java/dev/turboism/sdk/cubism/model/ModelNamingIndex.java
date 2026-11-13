package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ParameterId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Detached naming view of one model, read in one pass.
 *
 * <p>Every member is a value, not a live handle: the index records names and parent links as they
 * stood when it was read and does not track later Editor edits.</p>
 *
 * <p>The index exists because a caller that visits every deformer pays a full re-validation per
 * property when it reads live {@link Deformer} handles. A batch that derives names from the object
 * graph therefore reads one index and derives everything from it, instead of asking the host once
 * per object and property.</p>
 *
 * <p>Deformers are reported with their kind, because the unified {@link Deformers} collection
 * exposes generic handles that cannot be classified by type alone. Art meshes report the deformer
 * and the Part that own them, so a caller can walk the tree without another read.</p>
 *
 * @param parameters every parameter, in host order
 * @param parts every Part, in host order
 * @param deformers every Warp and Rotation Deformer, in host order
 * @param artMeshes every ArtMesh, in host order
 */
@CubismEditor
public record ModelNamingIndex(
        List<ParameterEntry> parameters,
        List<PartEntry> parts,
        List<DeformerEntry> deformers,
        List<ArtMeshEntry> artMeshes) {

    public ModelNamingIndex {
        parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
        parts = List.copyOf(Objects.requireNonNull(parts, "parts"));
        deformers = List.copyOf(Objects.requireNonNull(deformers, "deformers"));
        artMeshes = List.copyOf(Objects.requireNonNull(artMeshes, "artMeshes"));
    }

    /** Returns an empty index, used when no model is open. */
    public static ModelNamingIndex empty() {
        return new ModelNamingIndex(List.of(), List.of(), List.of(), List.of());
    }

    /** Returns whether the model contains no parameter, Part, Deformer or ArtMesh. */
    public boolean isEmpty() {
        return parameters.isEmpty() && parts.isEmpty() && deformers.isEmpty() && artMeshes.isEmpty();
    }

    /** One parameter and its display name. */
    @CubismEditor
    public record ParameterEntry(ParameterId id, String name) {
        public ParameterEntry {
            id = Objects.requireNonNull(id, "id");
            name = requireName(name, id.value());
        }
    }

    /** One Part and its display name. */
    @CubismEditor
    public record PartEntry(PartId id, String name) {
        public PartEntry {
            id = Objects.requireNonNull(id, "id");
            name = requireName(name, id.value());
        }
    }

    /**
     * One Warp or Rotation Deformer with the links a naming batch needs.
     *
     * @param id stable identifier
     * @param name display name, or the identifier text when no authoring name is available
     * @param rotation whether this is a Rotation Deformer rather than a Warp Deformer
     * @param parentDeformerId the enclosing Deformer, empty at the model root
     * @param parameterIds the parameters bound to this deformer, in host order
     */
    @CubismEditor
    public record DeformerEntry(
            DeformerId id,
            String name,
            boolean rotation,
            Optional<DeformerId> parentDeformerId,
            List<ParameterId> parameterIds) {
        public DeformerEntry {
            id = Objects.requireNonNull(id, "id");
            name = requireName(name, id.value());
            parentDeformerId = Objects.requireNonNull(parentDeformerId, "parentDeformerId");
            parameterIds = List.copyOf(Objects.requireNonNull(parameterIds, "parameterIds"));
        }
    }

    /**
     * One ArtMesh with the deformer and Part that own it.
     *
     * @param id stable identifier
     * @param name display name, or the identifier text when no authoring name is available
     * @param parentDeformerId the Deformer this mesh belongs to, empty when it has none
     * @param parentPartId the Part this mesh belongs to, empty when it has none
     */
    @CubismEditor
    public record ArtMeshEntry(
            ArtMeshId id, String name, Optional<DeformerId> parentDeformerId, Optional<PartId> parentPartId) {
        public ArtMeshEntry {
            id = Objects.requireNonNull(id, "id");
            name = requireName(name, id.value());
            parentDeformerId = Objects.requireNonNull(parentDeformerId, "parentDeformerId");
            parentPartId = Objects.requireNonNull(parentPartId, "parentPartId");
        }
    }

    private static String requireName(final String name, final String fallback) {
        Objects.requireNonNull(name, "name");
        return name.isBlank() ? fallback : name;
    }
}
