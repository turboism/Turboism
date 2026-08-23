package dev.turboism.sdk.cubism.model;


/** One immutable Cubism Core drawable-evaluation snapshot. */
public record DrawableEvaluationState(
    boolean evaluatedVisible,
    boolean visibilityChanged,
    boolean opacityChanged,
    boolean drawOrderChanged,
    boolean renderOrderChanged,
    boolean vertexPositionsChanged,
    boolean blendColorChanged
) { }
