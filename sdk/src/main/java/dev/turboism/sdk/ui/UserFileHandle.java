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

    /** Returns the handle's current state; see {@link UserFileHandleState} for the full set. */
    UserFileHandleState state();

    /**
     * Withdraws the grant early, moving the handle to {@link UserFileHandleState#REVOKED};
     * the state stays distinguishable from a holder-initiated {@link #close()}.
     */
    void revoke();

    @Override
    void close();
}
