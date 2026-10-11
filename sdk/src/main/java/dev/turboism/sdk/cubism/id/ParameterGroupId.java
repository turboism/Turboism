package dev.turboism.sdk.cubism.id;

import dev.turboism.sdk.CubismEditor;
import java.util.Objects;

/** Stable Editor parameter-group identifier. */
@CubismEditor
public record ParameterGroupId(String value) {
    public ParameterGroupId {
        value = Objects.requireNonNull(value, "value");
    }
}
