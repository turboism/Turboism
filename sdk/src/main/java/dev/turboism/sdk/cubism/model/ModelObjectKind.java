package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;

/** Stable model-object families exposed to automation and plugins. */
@PreviewApi
public enum ModelObjectKind {
    PART,
    ART_MESH,
    WARP_DEFORMER,
    ROTATION_DEFORMER
}
