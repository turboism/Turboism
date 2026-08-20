package dev.turboism.sdk.cubism.id;

import java.util.Objects;

/**
 * Editor-assigned identity of one raw layered source image registered on a model.
 *
 * <p>Names the imported source artwork, as opposed to {@link ModelImageId}, which names the
 * texture slot the model renders from. The wrapped string is the host's own opaque handle and is
 * compared verbatim.
 *
 * @param value the host-issued raw image identifier, never {@code null}
 */
public record RawImageId(String value) {
    public RawImageId {
        value = Objects.requireNonNull(value, "value");
    }
}
