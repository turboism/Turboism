package dev.turboism.sdk.ui;

/**
 * How long a user file grant survives.
 *
 * <p>{@code ONE_OPERATION} expires after a single read or write;
 * {@code UNTIL_DISABLE} lasts until the plugin is disabled or the handle is
 * closed or revoked.</p>
 */
public enum UserFileLifetime {
    ONE_OPERATION,
    UNTIL_DISABLE
}
