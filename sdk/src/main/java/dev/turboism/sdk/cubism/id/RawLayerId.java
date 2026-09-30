package dev.turboism.sdk.cubism.id;

import java.util.Objects;

/**
 * Editor-assigned identity of one layer entry inside a raw layered image.
 *
 * <p>This is a separate identity space from {@link RawImageId}: the value is
 * projected from the native {@code ACLayerEntry.getGuid()} and never from the
 * owning layered-image wrapper or a PSD numeric layer id.</p>
 *
 * @param value the host-issued raw layer identifier, never {@code null}
 */
public record RawLayerId(String value) {
    public RawLayerId {
        value = Objects.requireNonNull(value, "value");
    }
}
