package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.id.RawLayerId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable layer-entry projection inside one owning raw image. */
public record RawLayerDetails(
        RawLayerId id,
        RawImageId ownerRawImageId,
        EntryKind entryKind,
        String name,
        Optional<String> psdLayerId,
        List<RawLayerDetails> children) {
    public RawLayerDetails {
        id = Objects.requireNonNull(id, "id");
        ownerRawImageId = Objects.requireNonNull(ownerRawImageId, "ownerRawImageId");
        entryKind = Objects.requireNonNull(entryKind, "entryKind");
        name = Objects.requireNonNull(name, "name");
        psdLayerId = Objects.requireNonNull(psdLayerId, "psdLayerId")
                .map(value -> Objects.requireNonNull(value, "psdLayerId"));
        children = List.copyOf(Objects.requireNonNull(children, "children"));
        for (final RawLayerDetails child : children) {
            if (!ownerRawImageId.equals(child.ownerRawImageId())) {
                throw new IllegalArgumentException("layer children must remain in the owning raw image identity space");
            }
        }
        if (entryKind == EntryKind.PIXEL && !children.isEmpty()) {
            throw new IllegalArgumentException("pixel layers cannot have children");
        }
    }

    /** Returns the host layer-entry identity, distinct from the numeric PSD layer ID. */
    public RawLayerId rawLayerId() {
        return id;
    }

    /** Distinguishes a pixel layer from a nested layer group. */
    public enum EntryKind {
        PIXEL,
        GROUP
    }
}
