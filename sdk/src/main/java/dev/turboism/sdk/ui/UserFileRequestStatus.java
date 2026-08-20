package dev.turboism.sdk.ui;

/**
 * How a {@link UserFileRequest} resolved.
 *
 * <p>{@code GRANTED} yields a handle; {@code CANCELED} means the user dismissed
 * the chooser; {@code DENIED} means policy refused the plugin; and
 * {@code UNAVAILABLE} means no runtime file surface was present.</p>
 */
public enum UserFileRequestStatus {
    GRANTED,
    CANCELED,
    DENIED,
    UNAVAILABLE
}
