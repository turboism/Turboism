package dev.turboism.adapter.cubism.core;

import java.util.List;
import java.util.Objects;

record CoreGlueDefinition(
    String id,
    int drawableA,
    int drawableB,
    List<Integer> parameters
) {
    CoreGlueDefinition {
        Objects.requireNonNull(id, "id");
        parameters = List.copyOf(parameters);
        if (id.isBlank() || drawableA < 0 || drawableB < 0) {
            throw new IllegalArgumentException("invalid Core Glue definition");
        }
    }
}
