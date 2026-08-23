package dev.turboism.sdk.cubism.textureatlas;


/** Outcome of a texture-atlas layout apply that reached the host without failing. */
public enum TextureAtlasLayoutApplyStatus {
    /** The host layout differed from the request and was updated. */
    APPLIED,
    /** The host layout already matched the request; nothing was written. */
    NO_CHANGE
}
