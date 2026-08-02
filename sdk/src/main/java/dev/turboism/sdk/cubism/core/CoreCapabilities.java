package dev.turboism.sdk.cubism.core;

import dev.turboism.sdk.PreviewApi;

/** Version-normalized Cubism Core public capabilities. */
@PreviewApi
public record CoreCapabilities(
    boolean parameterRepeat,
    boolean drawableTypedFlags,
    boolean mocInspection
) { }
