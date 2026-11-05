package dev.turboism.core.io;

import dev.turboism.core.io.DurableWriteException.Failure;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The filesystem steps behind {@link DurableFile}: stage a sibling, write and force it, rename it
 * over the target, force the directory, clean up.
 *
 * <p>Package-private on purpose. Callers name a target and a policy; they never name a step, so a
 * durability regression is fixed here once instead of at every call site.
 */
final class DurableFiles {

    /** POSIX mode a staged file carries before it holds a byte when owner-only is requested. */
    private static final Set<PosixFilePermission> FILE_OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private static final String STAGED_SUFFIX = ".tmp";

    private DurableFiles() {}

    /**
     * @return the directory a staged sibling must be created in
     * @throws DurableWriteException with {@code IO_FAILURE} when the target names no directory: a
     *     staged file outside the target's own directory could land on another volume and would
     *     not inherit the target's permission model
     */
    static Path requireParent(final Path target) throws DurableWriteException {
        final Path parent = target.getParent();
        if (parent == null) {
            throw new DurableWriteException(Failure.IO_FAILURE, "durable target has no parent directory: " + target);
        }
        return parent;
    }

    /**
     * @param target the create-once target
     * @throws DurableWriteException with {@code ALREADY_EXISTS} when the target exists
     */
    static void requireAbsent(final Path target) throws DurableWriteException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new DurableWriteException(Failure.ALREADY_EXISTS, "create-once target already exists: " + target);
        }
    }

    /**
     * Creates an empty temporary file in the target's own directory under a unique name.
     * Uniqueness matters: two writers publishing the same target must not share a staging path,
     * because one of them would delete the other's half-written file.
     *
     * <p>With {@link WriteOptions.Permissions#OWNER_ONLY} the mode is requested as closely to
     * creation as the file system allows and verified afterwards, so no payload byte is ever
     * written to a file another local user can read. A file system that exposes neither a POSIX
     * permission model nor an ACL fails closed and leaves nothing behind.
     *
     * @param target the file that will be replaced
     * @param permissions the permission model the staged file must carry
     * @return the staged file, empty and already restricted when owner-only was requested
     * @throws IOException a {@link DurableWriteException} for every staged-file failure
     */
    static Path createStaged(final Path target, final WriteOptions.Permissions permissions) throws IOException {
        final Path parent = requireParent(target);
        final Path staged =
                parent.resolve("." + target.getFileName() + ".turboism-" + UUID.randomUUID() + STAGED_SUFFIX);
        try {
            createEmpty(staged, permissions);
        } catch (IOException failure) {
            throw asWriteFailure(failure, "cannot stage a sibling of " + target);
        }
        return staged;
    }

    /**
     * Creates the empty staged file, with the owner-only mode already applied when requested and
     * the staged path removed again when that mode cannot be enforced.
     */
    private static void createEmpty(final Path staged, final WriteOptions.Permissions permissions) throws IOException {
        if (permissions != WriteOptions.Permissions.OWNER_ONLY) {
            Files.createFile(staged);
            return;
        }
        try {
            Files.createFile(staged, PosixFilePermissions.asFileAttribute(FILE_OWNER_ONLY));
        } catch (UnsupportedOperationException noPosixCreationAttributes) {
            // A file system without POSIX creation attributes: create, then tighten before any
            // payload byte is written. A file system with neither model fails closed below.
            Files.createFile(staged);
        }
        try {
            enforceOwnerOnly(staged);
        } catch (IOException | RuntimeException failure) {
            deleteQuietly(staged);
            throw failure;
        }
    }

    /**
     * Replaces the staged file's permission model with owner-only: POSIX {@code rw-------}, else a
     * single owner-only ACL entry. A file system exposing neither fails closed.
     *
     * @param path the staged file, still empty
     * @throws DurableWriteException with {@code OWNER_ONLY_UNAVAILABLE} when neither model exists
     *     or the one that exists cannot be applied
     */
    static void enforceOwnerOnly(final Path path) throws IOException {
        try {
            final PosixFileAttributeView posix =
                    Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (posix != null) {
                if (!FILE_OWNER_ONLY.equals(posix.readAttributes().permissions())) {
                    posix.setPermissions(FILE_OWNER_ONLY);
                }
                return;
            }
            final AclFileAttributeView acl =
                    Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (acl == null) {
                throw new DurableWriteException(
                        Failure.OWNER_ONLY_UNAVAILABLE,
                        "file system exposes neither POSIX permissions nor an ACL: " + path);
            }
            try {
                acl.setAcl(List.of(ownerOnlyEntry(acl.getOwner())));
            } catch (IOException | UnsupportedOperationException failure) {
                throw new DurableWriteException(
                        Failure.OWNER_ONLY_UNAVAILABLE, "cannot apply an owner-only ACL to " + path, failure);
            }
        } catch (UnsupportedOperationException unsupported) {
            throw new DurableWriteException(
                    Failure.OWNER_ONLY_UNAVAILABLE,
                    "file system cannot carry an owner-only permission model: " + path,
                    unsupported);
        }
    }

    /** The single ACE an owner-only file carries on an ACL file system. */
    private static AclEntry ownerOnlyEntry(final UserPrincipal owner) {
        final Set<AclEntryPermission> permissions = EnumSet.noneOf(AclEntryPermission.class);
        permissions.add(AclEntryPermission.READ_DATA);
        permissions.add(AclEntryPermission.WRITE_DATA);
        permissions.add(AclEntryPermission.APPEND_DATA);
        permissions.add(AclEntryPermission.READ_ATTRIBUTES);
        permissions.add(AclEntryPermission.WRITE_ATTRIBUTES);
        permissions.add(AclEntryPermission.READ_NAMED_ATTRS);
        permissions.add(AclEntryPermission.WRITE_NAMED_ATTRS);
        permissions.add(AclEntryPermission.SYNCHRONIZE);
        return AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(permissions)
                .build();
    }

    /**
     * Writes the payload into the staged file and forces it. The force happens while the channel
     * is still open, so the rename can never commit bytes that are not on stable storage.
     *
     * @param staged the empty staged file
     * @param bytes the payload
     * @param cancellable whether an interrupt may abort the write between chunks
     */
    static void writeBytes(final Path staged, final byte[] bytes, final boolean cancellable) throws IOException {
        try (FileChannel channel = openForWrite(staged)) {
            final ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                if (cancellable) {
                    checkInterrupted();
                }
                channel.write(buffer);
            }
            channel.force(true);
        } catch (ClosedByInterruptException interrupted) {
            if (cancellable) {
                throw new InterruptPending();
            }
            throw asWriteFailure(interrupted, "cannot write " + staged);
        } catch (IOException failure) {
            throw asWriteFailure(failure, "cannot write " + staged);
        }
    }

    /**
     * Streams the payload produced by {@code producer} into the staged file and forces it. The
     * module owns the sink: the producer writes, the module forces and closes.
     *
     * @param staged the empty staged file
     * @param producer writes the payload; it must not close the stream
     */
    static void writeStreaming(final Path staged, final DurableFile.OutputStreamConsumer producer) throws IOException {
        try (FileChannel channel = openForWrite(staged)) {
            producer.accept(new ChannelSink(channel));
            channel.force(true);
        } catch (IOException failure) {
            throw asWriteFailure(failure, "cannot stream " + staged);
        }
    }

    /** Forces a staged file another component produced before it is published. */
    static void forceFile(final Path staged) throws IOException {
        // No NOFOLLOW_LINKS here: a staged file is produced by the caller and a provider that
        // refuses link options in a channel open (zipfs) must still be publishable. The caller's
        // PublishGuard is where confinement of that path is decided.
        try (FileChannel channel = FileChannel.open(staged, StandardOpenOption.WRITE)) {
            channel.force(true);
        } catch (IOException failure) {
            throw asWriteFailure(failure, "cannot force " + staged);
        }
    }

    /**
     * Publishes the staged file at the target. {@code ATOMIC_MOVE} is tried first; when the file
     * system refuses it the caller's {@link WriteOptions.Atomicity} decides between failing closed
     * with {@code ATOMIC_REPLACE_UNAVAILABLE} and a plain replacing move.
     *
     * @param staged the durable staged file
     * @param target the path to publish at
     * @param options the caller's policy
     * @param createOnce whether an existing target must be reported instead of replaced
     */
    static void move(final Path staged, final Path target, final WriteOptions options, final boolean createOnce)
            throws IOException {
        try {
            if (createOnce) {
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
            } else {
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (AtomicMoveNotSupportedException unsupported) {
            if (options.atomicity() == WriteOptions.Atomicity.REQUIRED) {
                throw new DurableWriteException(
                        Failure.ATOMIC_REPLACE_UNAVAILABLE,
                        "file system cannot replace " + target + " atomically",
                        unsupported);
            }
            moveWithoutAtomicity(staged, target, createOnce);
        } catch (FileAlreadyExistsException exists) {
            throw alreadyExists(target, exists);
        } catch (IOException failure) {
            throw asWriteFailure(failure, "cannot publish " + target);
        }
    }

    /** The {@code Atomicity.PREFERRED} path: a plain replacing move, never a silent no-op. */
    private static void moveWithoutAtomicity(final Path staged, final Path target, final boolean createOnce)
            throws IOException {
        try {
            if (createOnce) {
                Files.move(staged, target);
            } else {
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (FileAlreadyExistsException exists) {
            throw alreadyExists(target, exists);
        } catch (IOException failure) {
            throw asWriteFailure(failure, "cannot publish " + target);
        }
    }

    /**
     * Forces the containing directory so the rename itself survives a power loss. POSIX only:
     * Windows cannot open a directory as a channel and NTFS journals the rename, so the step is a
     * documented no-op there.
     *
     * @param directory the directory whose entry changed
     */
    static void forceDirectory(final Path directory) throws IOException {
        try {
            if (!Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                return;
            }
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
                channel.force(true);
            }
        } catch (IOException failure) {
            throw asWriteFailure(failure, "cannot force directory " + directory);
        }
    }

    /** Appends the payload to an existing file and forces it; no staging, no atomicity claim. */
    static void append(final Path target, final byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(
                target, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            final ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        } catch (IOException failure) {
            throw asWriteFailure(failure, "cannot append to " + target);
        }
    }

    /** @throws InterruptPending when the current thread was interrupted before publication */
    static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptPending();
        }
    }

    /** Best-effort removal of a staged file; a failure here must not mask the original failure. */
    static void deleteQuietly(final Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The staged path stays behind; the target is still untouched, so the write failed closed.
        }
    }

    private static FileChannel openForWrite(final Path staged) throws IOException {
        return FileChannel.open(
                staged, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
    }

    private static DurableWriteException alreadyExists(final Path target, final FileAlreadyExistsException exists) {
        return new DurableWriteException(
                Failure.ALREADY_EXISTS, "create-once target already exists: " + target, exists);
    }

    /**
     * Keeps a failure that already carries a closed reason and folds every other filesystem
     * failure into {@code IO_FAILURE}.
     */
    private static DurableWriteException asWriteFailure(final IOException failure, final String message) {
        if (failure instanceof DurableWriteException durable) {
            return durable;
        }
        return new DurableWriteException(Failure.IO_FAILURE, message, failure);
    }

    /**
     * Internal signal for "the caller's thread was interrupted": the shared steps declare only
     * {@link IOException}, so {@link DurableFile#replaceCancellable} maps this onto
     * {@link InterruptedException} at the one entry point that accepts an interrupt.
     */
    static final class InterruptPending extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private InterruptPending() {
            super("interrupted before publication", null, false, false);
        }
    }

    /** Writes straight into the staged file's channel so a streamed payload is forced the same way. */
    private static final class ChannelSink extends OutputStream {

        private final FileChannel channel;

        private ChannelSink(final FileChannel channel) {
            this.channel = channel;
        }

        @Override
        public void write(final int value) throws IOException {
            write(new byte[] {(byte) value}, 0, 1);
        }

        @Override
        public void write(final byte[] bytes, final int offset, final int length) throws IOException {
            final ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, length);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        }

        @Override
        public void close() {
            // The module closes the channel; a producer that closes early must not skip the force.
        }
    }
}
