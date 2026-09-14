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

    /** Returns the handle's opaque runtime identifier. */
    String id();

    /** Returns the user-visible name of the chosen file. */
    String displayName();

    /** Returns the access mode the user granted. */
    UserFileMode mode();

    /** Returns the lifetime scope the user granted. */
    UserFileLifetime lifetime();

    /** Returns the handle's current state (active, closed, revoked, or expired). */
    UserFileHandleState state();

    /** Revokes the handle early; equivalent to closing it before its lifetime elapses. */
    void revoke();

    @Override
    void close();
}
