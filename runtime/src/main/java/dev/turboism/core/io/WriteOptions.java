package dev.turboism.core.io;

import java.util.Objects;

/**
 * Immutable policy under which a {@link DurableFile} publishes its bytes.
 *
 * <p>The defaults are the behaviour of the call sites this module replaces: an atomic
 * replacement is required, the file system's own permission model is inherited, and only the
 * published file itself is forced. Callers opt into something else explicitly, so a slice that
 * migrates a site keeps its previous durability and permission semantics unless it says
 * otherwise.
 *
 * @param atomicity whether a non-atomic replacement is an acceptable outcome
 * @param permissions which permission model the staged file must carry before it holds bytes
 * @param durability how much of the parent directory entry chain is forced before returning
 * @throws NullPointerException when any component is {@code null}
 */
public record WriteOptions(Atomicity atomicity, Permissions permissions, Durability durability) {

    /** Atomic replacement required, permission model inherited, file forced before publication. */
    public static final WriteOptions DEFAULT =
            new WriteOptions(Atomicity.REQUIRED, Permissions.INHERIT, Durability.FILE);

    public WriteOptions {
        atomicity = Objects.requireNonNull(atomicity, "atomicity");
        permissions = Objects.requireNonNull(permissions, "permissions");
        durability = Objects.requireNonNull(durability, "durability");
    }

    /**
     * @param value the replacement policy to use instead
     * @return a copy of these options with only the atomicity changed
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public WriteOptions withAtomicity(final Atomicity value) {
        return new WriteOptions(value, permissions, durability);
    }

    /**
     * @param value the permission model to use instead
     * @return a copy of these options with only the permission model changed
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public WriteOptions withPermissions(final Permissions value) {
        return new WriteOptions(atomicity, value, durability);
    }

    /**
     * @param value the durability policy to use instead
     * @return a copy of these options with only the durability changed
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public WriteOptions withDurability(final Durability value) {
        return new WriteOptions(atomicity, permissions, value);
    }

    /** What a caller accepts when the file system cannot move a staged file atomically. */
    public enum Atomicity {
        /** A non-atomic replacement is not an acceptable outcome; fail with {@code ATOMIC_REPLACE_UNAVAILABLE}. */
        REQUIRED,
        /** Fall back to a plain replacing move when the file system refuses {@code ATOMIC_MOVE}. */
        PREFERRED
    }

    /** Which permission model the staged file carries before the first payload byte is written. */
    public enum Permissions {
        /** Keep the file system's default model for a newly created file (the caller's umask or parent DACL). */
        INHERIT,
        /**
         * POSIX {@code rw-------}, else a single owner-only ACL entry. A file system exposing
         * neither model fails closed with {@code OWNER_ONLY_UNAVAILABLE} instead of publishing
         * bytes under permissions the caller did not ask for.
         */
        OWNER_ONLY
    }

    /** How much of the publication is forced to stable storage before the call returns. */
    public enum Durability {
        /** Only the published file is forced before it replaces the target. */
        FILE,
        /**
         * The containing directory is also forced after the replacement. POSIX file systems
         * need this for the rename itself to survive a power loss; on Windows a directory cannot
         * be opened as a channel, NTFS journals the rename, so the step is a no-op there.
         */
        FILE_AND_DIRECTORY
    }
}
