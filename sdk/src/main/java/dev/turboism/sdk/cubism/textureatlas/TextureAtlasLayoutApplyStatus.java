package dev.turboism.sdk.cubism.textureatlas;

import dev.turboism.sdk.PreviewApi;

/** Outcome of a texture-atlas layout apply that reached the host without failing. */
@PreviewApi
public enum TextureAtlasLayoutApplyStatus {
    /** The host layout differed from the request and was updated. */
    APPLIED,
    /** The host layout already matched the request; nothing was written. */
    NO_CHANGE
}
