package dev.turboism.sdk.cubism.core;

import dev.turboism.sdk.PreviewApi;

import java.util.List;
import java.util.Objects;

/** Immutable adapter-owned projection of one evaluated Core glue. */
@PreviewApi
public record OwnedGlue(
    String id,
    int drawableA,
    int drawableB,
    List<Integer> parameters
) {

    public OwnedGlue {
        Objects.requireNonNull(id, "id");
        parameters = List.copyOf(parameters);
        if (id.isBlank() || drawableA < 0 || drawableB < 0) {
            throw new IllegalArgumentException("invalid OwnedGlue definition");
        }
    }
}
