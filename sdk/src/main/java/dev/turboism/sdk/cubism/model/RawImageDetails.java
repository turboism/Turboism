package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.RawImageId;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable details for one raw layered image and its verified layer tree.
 *
 * <p>{@link SourceKind#PSD} is emitted only when the verified typed PSD-document selector returns
 * a non-null document. An ordinary image may still carry a non-null source-file field, and a
 * reopened project may have no PSD document object; both cases remain {@link SourceKind#UNKNOWN}.
 * Classification does not read source-file contents.</p>
 */
public record RawImageDetails(
    RawTexture rawImage,
    SourceKind sourceKind,
    List<RawLayerDetails> layers,
    boolean replaced,
    Optional<String> importedAt,
    Optional<String> sourceModifiedAt,
    Optional<Boolean> projectTreeVisible
) {
    public RawImageDetails {
        rawImage = Objects.requireNonNull(rawImage, "rawImage");
        sourceKind = Objects.requireNonNull(sourceKind, "sourceKind");
        layers = List.copyOf(Objects.requireNonNull(layers, "layers"));
        importedAt = textOptional(importedAt, "importedAt");
        sourceModifiedAt = textOptional(sourceModifiedAt, "sourceModifiedAt");
        projectTreeVisible = Objects.requireNonNull(projectTreeVisible, "projectTreeVisible");
    }

    /** Returns the owning raw-image identity, not a layer or model-image ID. */
    public RawImageId id() {
        return rawImage.id();
    }

    /** Returns captured raw-image metadata; alias for {@link #rawImage()}. */
    public RawTexture rawTexture() {
        return rawImage;
    }

    /** Returns the host replacement flag captured by this snapshot. */
    public boolean isReplaced() {
        return replaced;
    }

    public enum SourceKind {
        PSD,
        UNKNOWN
    }

    private static Optional<String> textOptional(final Optional<String> value, final String name) {
        Objects.requireNonNull(value, name);
        return value.map(text -> Objects.requireNonNull(text, name));
    }
}
