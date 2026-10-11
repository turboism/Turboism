package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;

/** Stable model-object families exposed to automation and plugins. */
@CubismEditor
public enum ModelObjectKind {
    PART,
    ART_MESH,
    WARP_DEFORMER,
    ROTATION_DEFORMER
}
