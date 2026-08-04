package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;

/** One immutable Cubism Core drawable-evaluation snapshot. */
@PreviewApi
public record DrawableEvaluationState(
    boolean evaluatedVisible,
    boolean visibilityChanged,
    boolean opacityChanged,
    boolean drawOrderChanged,
    boolean renderOrderChanged,
    boolean vertexPositionsChanged,
    boolean blendColorChanged
) { }
