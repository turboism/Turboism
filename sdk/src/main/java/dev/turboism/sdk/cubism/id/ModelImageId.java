package dev.turboism.sdk.cubism.id;

import java.util.Objects;

/**
 * Editor-assigned identity of one model image (texture slot) inside a model image group.
 *
 * <p>Distinct from {@link RawImageId}, which names the imported source artwork rather than the
 * texture slot the model draws from. The wrapped string is the host's own opaque handle and is
 * compared verbatim.
 *
 * @param value the host-issued model image identifier, never {@code null}
 */
public record ModelImageId(String value) {
    public ModelImageId {
        value = Objects.requireNonNull(value, "value");
    }
}
