package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.RawImageId;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable relation projection for one verified live Cubism model generation.
 *
 * <p>{@link #revision()} is an observation revision owned by the runtime. It
 * is not an Undo revision, save count, or permanent host version. An
 * unavailable snapshot is deliberately not represented by an available empty
 * graph.</p>
 */
public record TextureRelationsSnapshot(
    Availability availability,
    String binding,
    long generation,
    long revision,
    List<RawImageDetails> rawImages,
    List<ModelImageRelation> modelImages,
    List<ModelImageGroupRelation> groups,
    List<ArtMeshTextureInputs> artMeshInputs
) {
    public TextureRelationsSnapshot {
        availability = Objects.requireNonNull(availability, "availability");
        binding = Objects.requireNonNull(binding, "binding");
        if (generation < 0) throw new IllegalArgumentException("generation must not be negative");
        if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
        rawImages = List.copyOf(Objects.requireNonNull(rawImages, "rawImages"));
        modelImages = List.copyOf(Objects.requireNonNull(modelImages, "modelImages"));
        groups = List.copyOf(Objects.requireNonNull(groups, "groups"));
        artMeshInputs = List.copyOf(Objects.requireNonNull(artMeshInputs, "artMeshInputs"));
        if (availability == Availability.UNAVAILABLE
            && (generation != 0
                || revision != 0
                || !binding.isEmpty()
                || !rawImages.isEmpty()
                || !modelImages.isEmpty()
                || !groups.isEmpty()
                || !artMeshInputs.isEmpty())) {
            throw new IllegalArgumentException("unavailable relation snapshot must be empty");
        }
    }

    /** The typed fail-closed value used by unsupported/default implementations. */
    public static TextureRelationsSnapshot unavailable() {
        return new TextureRelationsSnapshot(
            Availability.UNAVAILABLE,
            "",
            0,
            0,
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
    }

    /** Returns whether the adapter supplied a relation projection. */
    public boolean isAvailable() {
        return availability == Availability.AVAILABLE;
    }

    /** Alias matching the existing texture-library terminology. */
    public List<ModelImageGroupRelation> modelImageGroups() {
        return groups;
    }

    /** Alias for callers that refer to ArtMeshes as drawables. */
    public List<ArtMeshTextureInputs> artMeshes() {
        return artMeshInputs;
    }

    /** Finds the first raw image with this non-null identity in this snapshot. */
    public Optional<RawImageDetails> rawImage(final RawImageId id) {
        Objects.requireNonNull(id, "id");
        return rawImages.stream().filter(value -> value.id().equals(id)).findFirst();
    }

    /** Finds the first model image with this non-null identity in this snapshot. */
    public Optional<ModelImageRelation> modelImage(final ModelImageId id) {
        Objects.requireNonNull(id, "id");
        return modelImages.stream().filter(value -> value.id().equals(id)).findFirst();
    }

    public enum Availability {
        AVAILABLE,
        UNAVAILABLE
    }
}
