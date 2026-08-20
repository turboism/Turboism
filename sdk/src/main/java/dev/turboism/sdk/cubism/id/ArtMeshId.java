package dev.turboism.sdk.cubism.id;

import java.util.Objects;

/**
 * Editor-assigned identity of a single art mesh (drawable) inside a Cubism model.
 *
 * <p>The wrapped string is the host's own opaque handle: it is compared verbatim and is only
 * meaningful within the model it was read from. Holding an id does not keep the mesh alive, and
 * the id is not re-validated when the host mutates the model.
 *
 * @param value the host-issued art mesh identifier, never {@code null}
 */
public record ArtMeshId(String value) {
    public ArtMeshId {
        value = Objects.requireNonNull(value, "value");
    }
}
