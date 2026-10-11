package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;

/** One immutable Cubism Core drawable-evaluation snapshot. */
@CubismEditor
public record DrawableEvaluationState(
        boolean evaluatedVisible,
        boolean visibilityChanged,
        boolean opacityChanged,
        boolean drawOrderChanged,
        boolean renderOrderChanged,
        boolean vertexPositionsChanged,
        boolean blendColorChanged) {}
