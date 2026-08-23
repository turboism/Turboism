package dev.turboism.sdk.cubism.textureatlas;


/**
 * Machine-readable reason a texture-atlas layout apply did not take effect.
 *
 * <p>Codes are the stable part of a failure; the accompanying message is diagnostic text and must
 * not be parsed. Callers should branch on the code alone.</p>
 */
public enum TextureAtlasLayoutFailureCode {
    /** The calling plugin does not hold the permission required to modify the atlas layout. */
    PERMISSION_DENIED,
    /** The host build does not expose the texture-atlas layout capability at all. */
    CAPABILITY_UNAVAILABLE,
    /** The atlas the plan targets has changed or disappeared since the plan was built. */
    TARGET_STALE,
    /** The submitted plan is internally inconsistent and was rejected before reaching the host. */
    PLAN_INVALID,
    /** The host received a well-formed plan and declined to apply it. */
    PROVIDER_REJECTED,
    /** The host attempted the apply and errored partway. */
    PROVIDER_FAILED,
    /** The runtime was shutting down or already closed when the apply was requested. */
    RUNTIME_CLOSED
}
