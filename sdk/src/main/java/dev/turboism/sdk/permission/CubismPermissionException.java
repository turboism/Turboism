package dev.turboism.sdk.permission;

public final class CubismPermissionException extends RuntimeException {

    public CubismPermissionException(final String message) {
        super(message);
    }

    public CubismPermissionException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
