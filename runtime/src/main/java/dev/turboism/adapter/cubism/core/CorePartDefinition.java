package dev.turboism.adapter.cubism.core;

import java.util.Objects;

record CorePartDefinition(String id, float opacity, int parentIndex) {
    CorePartDefinition {
        Objects.requireNonNull(id, "id");
        if (id.isBlank() || !Float.isFinite(opacity) || parentIndex < -1) {
            throw new IllegalArgumentException("invalid Core Part definition");
        }
    }
}
