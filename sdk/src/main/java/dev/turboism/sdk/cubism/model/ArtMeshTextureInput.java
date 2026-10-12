package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.TextureAtlasId;
import java.util.Objects;
import java.util.Optional;

/**
 * Detached observation of the native texture input currently bound to one ArtMesh.
 *
 * <p>The value reports which authored resource the Editor's texture-input extension
 * resolves to for the target mesh at read time: either a model image or a texture
 * atlas region — exactly one resource identity, keyed by {@link #kind()}. It carries
 * the model identity and generation it was read under so a stale observation cannot
 * be confused with the active document. It is not a write token, and it is not a
 * render texture index, an atlas page position, or proof of GPU sampling.</p>
 *
 * @param artMeshId the mesh the input belongs to
 * @param modelId owning model identity the observation was read under
 * @param modelGeneration model generation the observation belongs to
 * @param kind which resource kind is bound
 * @param modelImageId bound model image identity, present only for {@link Kind#MODEL_IMAGE}
 * @param textureAtlasId bound texture atlas identity, present only for
 *     {@link Kind#TEXTURE_ATLAS_REGION}
 */
@CubismEditor(from = "5.2.00")
public record ArtMeshTextureInput(
        ArtMeshId artMeshId,
        ModelId modelId,
        long modelGeneration,
        Kind kind,
        Optional<ModelImageId> modelImageId,
        Optional<TextureAtlasId> textureAtlasId) {

    /** Which authored resource kind the observed input resolves to. */
    public enum Kind {
        MODEL_IMAGE,
        TEXTURE_ATLAS_REGION
    }

    /** Validates one detached observation. */
    public ArtMeshTextureInput {
        artMeshId = Objects.requireNonNull(artMeshId, "artMeshId");
        if (artMeshId.value().isBlank()) {
            throw new IllegalArgumentException("artMeshId must not be blank");
        }
        modelId = Objects.requireNonNull(modelId, "modelId");
        if (modelId.value().isBlank()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }
        if (modelGeneration <= 0) {
            throw new IllegalArgumentException("modelGeneration must be positive");
        }
        kind = Objects.requireNonNull(kind, "kind");
        modelImageId = Objects.requireNonNull(modelImageId, "modelImageId");
        textureAtlasId = Objects.requireNonNull(textureAtlasId, "textureAtlasId");
        switch (kind) {
            case MODEL_IMAGE -> {
                if (modelImageId.isEmpty() || textureAtlasId.isPresent()) {
                    throw new IllegalArgumentException("MODEL_IMAGE input requires a model image id and no atlas id");
                }
            }
            case TEXTURE_ATLAS_REGION -> {
                if (textureAtlasId.isEmpty() || modelImageId.isPresent()) {
                    throw new IllegalArgumentException(
                            "TEXTURE_ATLAS_REGION input requires a texture atlas id and no model image id");
                }
            }
        }
    }
}
