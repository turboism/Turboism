package dev.turboism.sdk.permission;

/**
 * Thrown when a plugin invokes a Cubism-facing operation it has not been granted permission
 * for. Unchecked, so it propagates out of SDK calls without appearing in their signatures;
 * the guarded operation never runs.
 */
public final class CubismPermissionException extends RuntimeException {

    public CubismPermissionException(final String message) {
        super(message);
    }

    public CubismPermissionException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
