package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.TextureAtlasId;

import java.util.Objects;
import java.util.Optional;

/** One ordered ArtMesh texture input, retaining model-image, atlas, and unknown kinds. */
public record TextureInputBinding(
    Kind kind,
    Optional<ModelImageId> modelImageId,
    Optional<TextureAtlasId> textureAtlasId,
    ResolutionState resolutionState
) {
    public TextureInputBinding {
        kind = Objects.requireNonNull(kind, "kind");
        modelImageId = Objects.requireNonNull(modelImageId, "modelImageId");
        textureAtlasId = Objects.requireNonNull(textureAtlasId, "textureAtlasId");
        resolutionState = Objects.requireNonNull(resolutionState, "resolutionState");
        if (kind == Kind.MODEL_IMAGE && textureAtlasId.isPresent()) {
            throw new IllegalArgumentException("model-image input cannot carry a texture-atlas id");
        }
        if (kind == Kind.ATLAS && modelImageId.isPresent()) {
            throw new IllegalArgumentException("atlas input cannot carry a model-image id");
        }
        if (kind == Kind.UNKNOWN && (modelImageId.isPresent() || textureAtlasId.isPresent())) {
            throw new IllegalArgumentException("unknown input cannot carry a typed id");
        }
        if (resolutionState == ResolutionState.RESOLVED
            && ((kind == Kind.MODEL_IMAGE && modelImageId.isEmpty())
                || (kind == Kind.ATLAS && textureAtlasId.isEmpty())
                || kind == Kind.UNKNOWN)) {
            throw new IllegalArgumentException("resolved input must carry a known typed id");
        }
    }

    public static TextureInputBinding modelImage(final ModelImageId id) {
        return modelImage(id, ResolutionState.RESOLVED);
    }

    public static TextureInputBinding modelImage(
        final ModelImageId id,
        final ResolutionState resolutionState
    ) {
        return new TextureInputBinding(
            Kind.MODEL_IMAGE,
            Optional.ofNullable(id),
            Optional.empty(),
            resolutionState
        );
    }

    public static TextureInputBinding atlas(final TextureAtlasId id) {
        return atlas(id, ResolutionState.RESOLVED);
    }

    public static TextureInputBinding atlas(
        final TextureAtlasId id,
        final ResolutionState resolutionState
    ) {
        return new TextureInputBinding(
            Kind.ATLAS,
            Optional.empty(),
            Optional.ofNullable(id),
            resolutionState
        );
    }

    public static TextureInputBinding unknown() {
        return new TextureInputBinding(
            Kind.UNKNOWN,
            Optional.empty(),
            Optional.empty(),
            ResolutionState.UNKNOWN
        );
    }

    public static TextureInputBinding unavailable() {
        return new TextureInputBinding(
            Kind.UNKNOWN,
            Optional.empty(),
            Optional.empty(),
            ResolutionState.UNAVAILABLE
        );
    }

    public boolean isResolved() {
        return resolutionState == ResolutionState.RESOLVED;
    }

    public enum Kind {
        MODEL_IMAGE,
        ATLAS,
        UNKNOWN
    }

    public enum ResolutionState {
        RESOLVED,
        UNKNOWN,
        UNAVAILABLE
    }
}
