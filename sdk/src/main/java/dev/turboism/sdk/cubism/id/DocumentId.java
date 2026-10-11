package dev.turboism.sdk.cubism.id;

import dev.turboism.sdk.CubismEditor;
import java.util.Objects;

/** Opaque identity of one Cubism Editor document. */
@CubismEditor
public record DocumentId(String value) {
    public DocumentId {
        value = Objects.requireNonNull(value, "value");
    }
}
