package dev.turboism.sdk.cubism.id;

import java.util.Objects;

/**
 * Editor-assigned identity of a single deformer (warp or rotation) inside a Cubism model.
 *
 * <p>The wrapped string is the host's own opaque handle: it is compared verbatim and is only
 * meaningful within the model it was read from. Holding an id does not keep the deformer alive,
 * and the id is not re-validated when the host mutates the model.
 *
 * @param value the host-issued deformer identifier, never {@code null}
 */
public record DeformerId(String value) {
    public DeformerId {
        value = Objects.requireNonNull(value, "value");
    }
}
