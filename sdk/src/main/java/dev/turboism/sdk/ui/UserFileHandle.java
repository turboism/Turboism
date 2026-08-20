package dev.turboism.sdk.ui;

/**
 * A capability over one user-chosen file, valid only for the mode and lifetime
 * the user granted.
 *
 * <p>The handle deliberately does not expose the underlying path: it is the
 * token the {@link UserFileAccessService} read and write methods accept. It
 * stops being usable once it is closed or revoked, or once its
 * {@link UserFileLifetime} elapses; operations on a spent handle fail with a
 * {@link UserFileError} rather than a throw. Closing is idempotent.</p>
 */
public interface UserFileHandle extends AutoCloseable {

    String id();

    String displayName();

    UserFileMode mode();

    UserFileLifetime lifetime();

    UserFileHandleState state();

    void revoke();

    @Override
    void close();
}
