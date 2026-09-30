package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.RawImageId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Lightweight source identities for a {@link TextureSourceQuery}. Contains no layer trees,
 * pixel data, selector maps or global usage/group lists. Missing entries are unresolved, not
 * permission to guess a source. The revision is an observation sequence, not a model revision.
 */
public record TextureSourcesSnapshot(
        Availability availability,
        String binding,
        long generation,
        long revision,
        List<RawTexture> rawImages,
        List<ModelImageSource> modelImages,
        List<ArtMeshTextureInputs> artMeshInputs) {
    /** Whether the adapter admitted the scoped query for this host. */
    public enum Availability {
        AVAILABLE,
        UNAVAILABLE
    }

    /** Current source only; candidate sources must not be substituted for a missing current source. */
    public record ModelImageSource(ModelImageId id, Optional<RawImageId> currentRawImageId) {
        public ModelImageSource {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(currentRawImageId, "currentRawImageId");
        }
    }

    public TextureSourcesSnapshot {
        Objects.requireNonNull(availability, "availability");
        Objects.requireNonNull(binding, "binding");
        rawImages = List.copyOf(rawImages);
        modelImages = List.copyOf(modelImages);
        artMeshInputs = List.copyOf(artMeshInputs);
        if (availability == Availability.AVAILABLE && binding.isBlank()) {
            throw new IllegalArgumentException("available source snapshot requires a binding");
        }
        if (generation < 0 || revision < 0) throw new IllegalArgumentException("negative source epoch");
    }

    /** Returns whether this snapshot represents an admitted observation. */
    public boolean isAvailable() {
        return availability == Availability.AVAILABLE;
    }

    /** Finds raw metadata within this query, not across the whole model. */
    public Optional<RawTexture> rawImage(final RawImageId id) {
        Objects.requireNonNull(id, "id");
        return rawImages.stream().filter(raw -> raw.id().equals(id)).findFirst();
    }

    /** Finds a requested model-image source; absence is unresolved. */
    public Optional<ModelImageSource> modelImage(final ModelImageId id) {
        Objects.requireNonNull(id, "id");
        return modelImages.stream().filter(image -> image.id().equals(id)).findFirst();
    }

    /** Creates the empty unavailable result without a document binding. */
    public static TextureSourcesSnapshot unavailable() {
        return new TextureSourcesSnapshot(Availability.UNAVAILABLE, "", 0, 0, List.of(), List.of(), List.of());
    }
}
