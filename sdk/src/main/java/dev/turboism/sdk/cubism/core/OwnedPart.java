package dev.turboism.sdk.cubism.core;

import dev.turboism.sdk.CubismEditor;
import java.util.Objects;

/** Immutable adapter-owned projection of one evaluated Core part. */
@CubismEditor
public record OwnedPart(String id, float opacity, int parentIndex) {

    public OwnedPart {
        Objects.requireNonNull(id, "id");
        if (id.isBlank() || !Float.isFinite(opacity) || parentIndex < -1) {
            throw new IllegalArgumentException("invalid OwnedPart definition");
        }
    }
}
