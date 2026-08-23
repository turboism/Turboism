package dev.turboism.sdk.ui;

/**
 * Whether a {@link UserFileHandle} may still be used.
 *
 * <p>{@code ACTIVE} handles accept operations; {@code CLOSED} was ended by the
 * holder and {@code REVOKED} was withdrawn. Neither terminal state ever
 * returns to {@code ACTIVE}.</p>
 */
public enum UserFileHandleState {
    ACTIVE,
    CLOSED,
    REVOKED
}
