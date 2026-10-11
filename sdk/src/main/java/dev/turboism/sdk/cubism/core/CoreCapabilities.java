package dev.turboism.sdk.cubism.core;

import dev.turboism.sdk.CubismEditor;

/** Version-normalized Cubism Core public capabilities. */
@CubismEditor
public record CoreCapabilities(boolean parameterRepeat, boolean drawableTypedFlags, boolean mocInspection) {}
