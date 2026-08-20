package dev.turboism.sdk.ui;

/**
 * The single access direction a user file grant permits.
 *
 * <p>A grant is never both: using a {@code READ} handle to write, or the
 * reverse, fails with {@link UserFileErrorCode#MODE_MISMATCH}.</p>
 */
public enum UserFileMode {
    READ,
    WRITE
}
