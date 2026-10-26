package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.id.ParameterId;
import java.util.Objects;

/** One direct entry in a parameter folder's mixed native order; IDs are not native GUIDs. */
@CubismEditor
public record ParameterGroupChild(Kind kind, String id) {
    /** Identifies whether a folder entry is a group or a parameter. */
    public enum Kind {
        GROUP,
        PARAMETER
    }

    public ParameterGroupChild {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) throw new IllegalArgumentException("id must not be blank");
    }

    /** Creates a folder entry from a parameter group display ID. */
    public static ParameterGroupChild group(final ParameterGroupId id) {
        return new ParameterGroupChild(
                Kind.GROUP, Objects.requireNonNull(id, "id").value());
    }

    /** Creates a parameter entry from a parameter display ID. */
    public static ParameterGroupChild parameter(final ParameterId id) {
        return new ParameterGroupChild(
                Kind.PARAMETER, Objects.requireNonNull(id, "id").value());
    }
}
