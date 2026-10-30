package dev.turboism.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.Set;

/** Read-only installation file boundary. Windows requires a separate native handle implementation. */
interface FrameworkInstallationFiles extends AutoCloseable {
    @FunctionalInterface
    interface Factory {
        FrameworkInstallationFiles open(Path root) throws IOException;
    }

    @Override
    default void close() throws IOException {}

    String rootIdentity() throws IOException;

    byte[] document(Path path, int limit) throws IOException, InterruptedException;

    void verify(UpdateManifestEntry entry) throws IOException, InterruptedException;

    /** Static POSIX admission, not a sandbox against concurrent same-UID directory replacement. */
    final class Posix implements FrameworkInstallationFiles {
        private final Path root;
        private final UserPrincipal owner;

        Posix(final Path root) throws IOException {
            this.root = root;
            chain(root);
            if (!Files.getFileStore(root).supportsFileAttributeView("posix")
                    || !Files.getFileStore(root).supportsFileAttributeView("unix")) {
                throw new IOException("Receipt loader requires POSIX; Windows native file safety is pending");
            }
            owner = Files.getOwner(root, LinkOption.NOFOLLOW_LINKS);
            permissions(root, false, true);
        }

        @Override
        public String rootIdentity() throws IOException {
            chain(root);
            permissions(root, false, true);
            final var attrs = Files.readAttributes(root, "unix:dev,ino", LinkOption.NOFOLLOW_LINKS);
            return FrameworkUpdateTaskStore.hash((root + "\n" + attrs.get("dev") + ":" + attrs.get("ino"))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
                    || ((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1) {
                throw new IOException("Unsafe installation file or alias");
            }
            permissions(path, receipt, false);
            return attributes;
        }

        private void permissions(final Path path, final boolean privateState, final boolean directory)
                throws IOException {
            if (!owner.equals(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS)))
                throw new IOException("Installation owner mismatch");
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
            if (before.fileKey() == null
                    || !before.fileKey().equals(after.fileKey())
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
