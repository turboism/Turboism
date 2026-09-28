package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.id.RawLayerId;
import java.util.Objects;

/** A model-image input's ordered connection to one raw layer entry. */
public record RawLayerBinding(
        RawImageId rawImageId,
        RawLayerId rawLayerId,
        int inputOrder,
        DetailAvailability transformAvailability,
        DetailAvailability clippingAvailability) {
    public RawLayerBinding {
        rawImageId = Objects.requireNonNull(rawImageId, "rawImageId");
        rawLayerId = Objects.requireNonNull(rawLayerId, "rawLayerId");
        if (inputOrder < 0) throw new IllegalArgumentException("inputOrder must not be negative");
        transformAvailability = Objects.requireNonNull(transformAvailability, "transformAvailability");
        clippingAvailability = Objects.requireNonNull(clippingAvailability, "clippingAvailability");
    }

    /** Returns the bound raw-layer identity, not the owning raw-image ID. */
    public RawLayerId id() {
        return rawLayerId;
    }

    /** Whether the adapter observed the associated transform or clipping detail. */
    public enum DetailAvailability {
        AVAILABLE,
        UNKNOWN,
        UNAVAILABLE
    }
}
