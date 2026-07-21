package dev.turboism.adapter.cubism.core;

import java.util.List;
import java.util.Objects;

/** Immutable, adapter-owned structural snapshot of one leased Cubism Core model generation. */
record CoreStructuralSnapshot(
    long generation,
    String modelIdentity,
    String providerId,
    String artifactProfile,
    CoreCanvasSnapshot canvas,
    List<CoreParameterDefinition> parameters
) {

    CoreStructuralSnapshot {
        if (generation < 0) {
            throw new IllegalArgumentException("generation must not be negative");
        }
        modelIdentity = requireText(modelIdentity, "modelIdentity");
        providerId = requireText(providerId, "providerId");
        artifactProfile = requireText(artifactProfile, "artifactProfile");
        canvas = Objects.requireNonNull(canvas, "canvas");
        parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
