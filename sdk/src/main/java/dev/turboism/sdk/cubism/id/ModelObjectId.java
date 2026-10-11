package dev.turboism.sdk.cubism.id;

import dev.turboism.sdk.CubismEditor;
import java.util.Objects;

/**
 * Editor-assigned identity of an addressable object in the model tree, used where the concrete
 * kind (part, deformer, drawable, parameter) is not known statically.
 *
 * <p>The wrapped string is the host's own opaque handle, compared verbatim. This type carries no
 * information about which kind of object it names; callers must take that from the query that
 * produced the id.
 *
 * @param value the host-issued object identifier, never {@code null}
 */
@CubismEditor
public record ModelObjectId(String value) {
    public ModelObjectId {
        value = Objects.requireNonNull(value, "value");
    }
}
