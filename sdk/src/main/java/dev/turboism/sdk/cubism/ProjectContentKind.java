package dev.turboism.sdk.cubism;

import dev.turboism.sdk.CubismEditor;

/** Semantic kind of a file-backed entry owned by the current Cubism project. */
@CubismEditor
public enum ProjectContentKind {
    MODEL,
    ANIMATION,
    IMAGE,
    GAME_DATA,
    OTHER
}
