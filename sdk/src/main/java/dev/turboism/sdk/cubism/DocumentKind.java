package dev.turboism.sdk.cubism;

/** Semantic kind of a Cubism editor {@code IDocument}. */
public enum DocumentKind {
    /** A modeling document backed by {@code CModelingDocument}. */
    MODEL,
    /** One scene view backed by {@code CSceneDocument}. */
    ANIMATION_SCENE,
    /** A layered image/PSD editor document backed by Cubism's resource document type. */
    IMAGE,
    /** A game-data editing document. */
    GAME_DATA,
    /** A physics-settings child document. */
    PHYSICS_SETTINGS,
    /** A reviewed host document kind that is not yet modeled by the SDK. */
    OTHER
}
