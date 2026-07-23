package dev.turboism.adapter.cubism.core;

import java.util.List;
import java.util.Objects;

record CoreDeformerDefinition(
    String id,
    int parentDeformerIndex,
    List<Integer> parameters
) {
    CoreDeformerDefinition {
        Objects.requireNonNull(id, "id");
        parameters = List.copyOf(parameters);
        if (id.isBlank() || parentDeformerIndex < -1) {
            throw new IllegalArgumentException("invalid Core Deformer definition");
        }
    }
}
