package dev.turboism.adapter.cubism.core;

import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Color;

import java.util.List;
import java.util.Objects;

record CoreDrawableDefinition(
    String id,
    byte constantFlag,
    byte dynamicFlag,
    BlendMode blendMode,
    int textureIndex,
    int drawOrder,
    int renderOrder,
    float opacity,
    List<Integer> masks,
    List<Float> vertexPositions,
    List<Float> vertexUvs,
    List<Integer> indices,
    Color multiplyColor,
    Color screenColor,
    int parentPartIndex,
    int parentDeformerIndex,
    List<Integer> parameters
) {
    CoreDrawableDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(blendMode, "blendMode");
        Objects.requireNonNull(multiplyColor, "multiplyColor");
        Objects.requireNonNull(screenColor, "screenColor");
        masks = List.copyOf(masks);
        vertexPositions = List.copyOf(vertexPositions);
        vertexUvs = List.copyOf(vertexUvs);
        indices = List.copyOf(indices);
        parameters = List.copyOf(parameters);
        if (id.isBlank() || textureIndex < 0 || !Float.isFinite(opacity)
            || parentPartIndex < -1 || parentDeformerIndex < -1) {
            throw new IllegalArgumentException("invalid Core Drawable definition");
        }
    }
}
