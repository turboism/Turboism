package dev.turboism.sdk.cubism.temporarydeform;

import dev.turboism.sdk.CubismEditor;

/** Cursor shapes that a custom temporary deform tool may request on pointer hover. */
@CubismEditor(from = "5.0.00")
public enum TemporaryDeformCursor {
    DEFAULT,
    MOVE,
    HAND
}
