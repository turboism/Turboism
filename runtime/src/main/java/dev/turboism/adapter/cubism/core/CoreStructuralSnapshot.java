package dev.turboism.adapter.cubism.core;

import dev.turboism.internal.Requirements;
import java.util.List;
import java.util.Objects;

/** Immutable, adapter-owned structural snapshot of one leased Cubism Core model generation. */
record CoreStructuralSnapshot(
        long generation,
        String modelIdentity,
        String providerId,
        String artifactProfile,
        CoreCanvasSnapshot canvas,
        List<CoreParameterDefinition> parameters,
        List<CorePartDefinition> parts,
        List<CoreDrawableDefinition> drawables,
        List<CoreDeformerDefinition> deformers,
        List<CoreGlueDefinition> glues) {

    CoreStructuralSnapshot {
        if (generation < 0) {
            throw new IllegalArgumentException("generation must not be negative");
        }
        modelIdentity = Requirements.text(modelIdentity, "modelIdentity");
        providerId = Requirements.text(providerId, "providerId");
        artifactProfile = Requirements.text(artifactProfile, "artifactProfile");
        canvas = Objects.requireNonNull(canvas, "canvas");
        parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
        parts = List.copyOf(Objects.requireNonNull(parts, "parts"));
        drawables = List.copyOf(Objects.requireNonNull(drawables, "drawables"));
        deformers = List.copyOf(Objects.requireNonNull(deformers, "deformers"));
        glues = List.copyOf(Objects.requireNonNull(glues, "glues"));
    }
}
