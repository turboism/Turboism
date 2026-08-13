package dev.turboism.sdk.cubism.textureatlas;

import dev.turboism.sdk.PreviewApi;

@PreviewApi
public enum TextureAtlasLayoutFailureCode {
    PERMISSION_DENIED,
    CAPABILITY_UNAVAILABLE,
    TARGET_STALE,
    PLAN_INVALID,
    PROVIDER_REJECTED,
    PROVIDER_FAILED,
    RUNTIME_CLOSED
}
