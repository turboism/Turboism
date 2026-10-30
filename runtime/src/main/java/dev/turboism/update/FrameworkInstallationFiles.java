package dev.turboism.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.Set;

/**
 * Read-only installation file boundary. {@link #system(Path)} selects the portable implementation for the running
 * platform; the W2 native-handle implementation remains a stricter, validation-only alternative.
 */
interface FrameworkInstallationFiles extends AutoCloseable {
    @FunctionalInterface
    interface Factory {
        FrameworkInstallationFiles open(Path root) throws IOException;
    }

    @Override
    default void close() throws IOException {}

    /** Portable default: POSIX permission bits, or ACL checks where the file store has no POSIX view. */
    static FrameworkInstallationFiles system(final Path root) throws IOException {
        return new Local(root);
    }

    String rootIdentity() throws IOException;

    byte[] document(Path path, int limit) throws IOException, InterruptedException;

    void verify(UpdateManifestEntry entry) throws IOException, InterruptedException;

    /**
     * Static admission, not a sandbox against concurrent same-user directory replacement. Every read re-hashes
     * bytes against the signed receipt, so the checks here are defence in depth, not the source of authority.
     *
     * <p>Windows: installation files are checked for ownership, regular-file/non-reparse shape and identity
     * stability only. Cubism installations commonly grant Authenticated Users modify rights, so rejecting
     * writable payload would make updates impossible; the receipt state itself must still be private.
     */
    final class Local implements FrameworkInstallationFiles {
        private final Path root;
        private final UserPrincipal owner;
        private final boolean posix;

        Local(final Path root) throws IOException {
            this.root = root;
            chain(root);
            final var store = Files.getFileStore(root);
            posix = store.supportsFileAttributeView("posix") && store.supportsFileAttributeView("unix");
            if (!posix && !store.supportsFileAttributeView("acl")) {
                throw new IOException("Receipt loader requires POSIX permissions or ACL support");
            }
            owner = Files.getOwner(root, LinkOption.NOFOLLOW_LINKS);
            permissions(root, false, true);
        }

        @Override
        public String rootIdentity() throws IOException {
            chain(root);
            permissions(root, false, true);
            final String identity;
            if (posix) {
                final var attrs = Files.readAttributes(root, "unix:dev,ino", LinkOption.NOFOLLOW_LINKS);
                identity = attrs.get("dev") + ":" + attrs.get("ino");
            } else {
                // No inode via NIO on Windows: volume serial + directory creation time identify this root.
                final var attrs = Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                identity = Files.getFileStore(root).getAttribute("volume:vsn") + ":" + attrs.creationTime();
            }
            return FrameworkUpdateTaskStore.hash(
                    (root + "\n" + identity).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public byte[] document(final Path path, final int limit) throws IOException, InterruptedException {
            interrupted();
            final BasicFileAttributes before = file(path, true);
            if (before.size() == 0 || before.size() > limit) throw new IOException("Receipt document size invalid");
            final byte[] bytes;
            try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(limit + 1);
            }
            stable(before, file(path, true));
            if (bytes.length != before.size()) throw new IOException("Receipt changed during read");
            interrupted();
            return bytes;
        }

        @Override
        public void verify(final UpdateManifestEntry entry) throws IOException, InterruptedException {
            interrupted();
            final Path path = root.resolve(entry.relativePath());
            final BasicFileAttributes before = file(path, false);
            if (before.size() != entry.bytes()
                    || !FrameworkUpdateCache.hash(path, entry.bytes()).equals(entry.sha256())) {
                throw new IOException("Installed bytes conflict with signed receipt");
            }
            stable(before, file(path, false));
            interrupted();
        }

        private BasicFileAttributes file(final Path path, final boolean receipt) throws IOException {
            if (!path.startsWith(root) || path.equals(root)) throw new IOException("Receipt path outside installation");
            chain(path.getParent());
            Path directory = path.getParent();
            while (directory.startsWith(root)) {
                permissions(directory, receipt && !directory.equals(root), true);
                if (directory.equals(root)) break;
                directory = directory.getParent();
            }
            final BasicFileAttributes attributes =
                    Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()
                    || attributes.isSymbolicLink()
                    || attributes.isOther()
                    || !FrameworkUpdateCache.singleLink(path)) {
                throw new IOException("Unsafe installation file or alias");
            }
            permissions(path, receipt, false);
            return attributes;
        }

        private void permissions(final Path path, final boolean privateState, final boolean directory)
                throws IOException {
            if (!owner.equals(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS)))
                throw new IOException("Installation owner mismatch");
            if (!posix) {
                if (privateState) {
                    for (final AclEntry entry : Files.getFileAttributeView(
                                    path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                            .getAcl()) {
                        if (entry.type() == AclEntryType.ALLOW && !trusted(entry)) {
                            throw new IOException("Receipt state is not private");
                        }
                    }
                }
                return;
            }
            final Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE)
                    || !permissions.contains(PosixFilePermission.OWNER_READ)
                    || directory && !permissions.contains(PosixFilePermission.OWNER_EXECUTE)) {
                throw new IOException("Unsafe installation permissions");
            }
            if (privateState
                    && !permissions.equals(java.nio.file.attribute.PosixFilePermissions.fromString(
                            directory ? "rwx------" : "rw-------"))) {
                throw new IOException("Receipt state is not private");
            }
        }

        private boolean trusted(final AclEntry entry) {
            if (entry.principal().equals(owner)) return true;
            final String name = entry.principal().getName().toUpperCase(java.util.Locale.ROOT);
            return name.endsWith("\\SYSTEM") || name.endsWith("\\ADMINISTRATORS");
        }

        private static void chain(final Path path) throws IOException {
            Path current = path.getRoot();
            directory(current);
            for (final Path component : path) {
                current = current.resolve(component);
                directory(current);
            }
        }

        private static void directory(final Path path) throws IOException {
            final BasicFileAttributes attrs =
                    Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isDirectory() || attrs.isSymbolicLink() || attrs.isOther())
                throw new IOException("Unsafe installation ancestor");
        }

        private static void stable(final BasicFileAttributes before, final BasicFileAttributes after)
                throws IOException {
            // Windows NIO exposes no fileKey; creation time then stands in for identity.
            final boolean sameIdentity = before.fileKey() != null
                    ? before.fileKey().equals(after.fileKey())
                    : after.fileKey() == null && before.creationTime().equals(after.creationTime());
            if (!sameIdentity
                    || before.size() != after.size()
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())) {
                throw new IOException("Installation file identity changed during verification");
            }
        }

        private static void interrupted() throws InterruptedException {
            if (Thread.currentThread().isInterrupted())
                throw new InterruptedException("Receipt verification cancelled");
        }
    }
}
