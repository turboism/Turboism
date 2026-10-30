package dev.turboism.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Private resource cache. POSIX uses owner-only permission bits; Windows uses an owner/SYSTEM/Administrators-only
 * ACL. Both are static checks, not a sandbox against a hostile process running as the same user. Content is always
 * re-hashed before use, and cache files are replaced (never rewritten in place), so a pre-planted link cannot
 * change what is verified or installed.
 */
final class FrameworkUpdateCache implements AutoCloseable {
    private static final byte[] MARKER =
            "turboism-framework-resource-cache-v1\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    static final long DEFAULT_QUOTA = 2L * 1024 * 1024 * 1024;

    @FunctionalInterface
    interface Faults {
        void hit(String point) throws IOException;
    }

    private final long quota;
    private final Faults faults;
    private final Set<String> sessionPins = new HashSet<>();
    private final Path root;
    private final boolean posix;
    private final FileChannel lockChannel;
    private final FileLock lock;

    FrameworkUpdateCache(final Path requested) throws IOException {
        this(requested, DEFAULT_QUOTA, ignored -> {});
    }

    FrameworkUpdateCache(final Path requested, final long quota, final Faults faults) throws IOException {
        if (quota <= 0 || quota > 4L * 1024 * 1024 * 1024) throw new IllegalArgumentException("Invalid cache quota");
        this.quota = quota;
        this.faults = faults;
        root = requested.toAbsolutePath().normalize();
        directoryChain(root.getParent());
        posix = Files.getFileStore(root.getParent()).supportsFileAttributeView("posix");
        if (!posix && !Files.getFileStore(root.getParent()).supportsFileAttributeView("acl")) {
            throw new IOException("Private cache requires POSIX permissions or ACL support");
        }
        final BasicFileAttributes existing = attributes(root);
        if (existing == null) {
            if (posix) {
                Files.createDirectory(
                        root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } else {
                Files.createDirectory(root);
                restrictAcl(root);
            }
            create(root.resolve("owner"));
            Files.write(root.resolve("owner"), MARKER, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        }
        directoryChain(root);
        privatePermissions(root, true);
        file(root.resolve("owner"));
        if (Files.size(root.resolve("owner")) != MARKER.length
                || !MessageDigest.isEqual(readMarker(root.resolve("owner")), MARKER)) {
            throw new IOException("Unrecognized cache ownership");
        }
        if (existing == null) {
            forceFile(root.resolve("owner"));
            forceDirectory();
            forceDirectory(root.getParent());
        }
        final Path lockPath = root.resolve("writer.lock");
        if (attributes(lockPath) == null) create(lockPath);
        file(lockPath);
        lockChannel = FileChannel.open(lockPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        FileLock acquired = null;
        try {
            acquired = lockChannel.tryLock();
            if (acquired == null) throw new IOException("Cache writer already active");
        } catch (IOException | OverlappingFileLockException failure) {
            lockChannel.close();
            throw new IOException("Cache writer already active", failure);
        }
        lock = acquired;
    }

    private static byte[] readMarker(final Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            return input.readNBytes(MARKER.length + 1);
        }
    }

    static void installationAncestors(final Path path) throws IOException {
        Path current = path.getRoot();
        for (final Path component : path) {
            current = current.resolve(component);
            final BasicFileAttributes a = attributes(current);
            if (a == null) return;
            if (!a.isDirectory() || a.isSymbolicLink() || a.isOther()) {
                throw new IOException("Unsafe installation ancestor");
            }
        }
    }

    Path path(final String kind, final String hash, final String suffix) {
        if (!(kind.equals("document") || kind.equals("object"))
                || !hash.matches("[a-f0-9]{64}")
                || !Set.of("part", "etag", "verified").contains(suffix)) {
            throw new IllegalArgumentException("Invalid resource cache key");
        }
        return root.resolve(kind + "-" + hash + "." + suffix);
    }

    Path ownedPath(final String name) {
        if (!name.matches("task-[a-f0-9]{64}\\.(json|writing)"))
            throw new IllegalArgumentException("Invalid task path");
        return root.resolve(name);
    }

    void pin(final String kind, final String hash) {
        sessionPins.add(kind + "-" + hash);
    }

    List<Path> files() throws IOException {
        final List<Path> paths = new ArrayList<>();
        try (var directory = Files.newDirectoryStream(root)) {
            for (final Path path : directory) {
                if (paths.size() >= 4096) throw new IOException("Too many cache entries");
                file(path); // Reject aliases, links and directories, including unknown entries.
                paths.add(path);
            }
        }
        return paths;
    }

    byte[] read(final Path path, final int limit) throws IOException {
        if (!present(path) || Files.size(path) > limit) throw new IOException("Cache record missing or oversized");
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            final byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("Cache record oversized");
            return bytes;
        }
    }

    void preflight(final long bytes) throws IOException {
        if (bytes < 0 || bytes > 1024L * 1024 * 1024) throw new IOException("Payload budget exceeded");
        final Set<String> pins = FrameworkUpdateTaskStore.pins(this);
        pins.addAll(sessionPins);
        final List<Path> paths = files();
        long used = 0;
        for (final Path path : paths) used = Math.addExact(used, Files.size(path));
        paths.sort(Comparator.comparingLong((Path path) -> {
                    try {
                        return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS)
                                .toMillis();
                    } catch (IOException failure) {
                        return Long.MAX_VALUE;
                    }
                })
                .thenComparing(path -> path.getFileName().toString()));
        for (final Path path : paths) {
            if (used <= quota - bytes) break;
            final String name = path.getFileName().toString();
            if (!name.matches("(document|object)-[a-f0-9]{64}\\.(part|etag|verified)")) continue;
            final String resource = name.substring(0, name.lastIndexOf('.'));
            if (pins.contains(resource)) continue;
            final long size = Files.size(path);
            remove(path);
            used -= size;
        }
        if (used > quota - bytes || Files.getFileStore(root).getUsableSpace() < bytes + 1024 * 1024) {
            throw new IOException("Cache quota or disk space exhausted; pinned/unknown data preserved");
        }
    }

    void forceDirectory() throws IOException {
        faults.hit("before-directory-force");
        forceDirectory(root);
        faults.hit("after-directory-force");
    }

    /**
     * Flush a directory entry. Windows cannot open a directory as a FileChannel; NTFS commits renames
     * (MoveFileEx) through the journal, and each file is force()d before it is renamed, so this is a no-op there.
     */
    private void forceDirectory(final Path directory) throws IOException {
        if (!posix) return;
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    void forceFile(final Path path) throws IOException {
        file(path);
        faults.hit("before-force:" + path.getFileName());
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            channel.force(true);
        }
        faults.hit("after-force:" + path.getFileName());
    }

    void writeForced(final Path path, final byte[] bytes) throws IOException {
        remove(path);
        preflight(bytes.length);
        faults.hit("before-write:" + path.getFileName());
        create(path);
        Files.write(path, bytes, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        faults.hit("after-write:" + path.getFileName());
        forceFile(path);
    }

    void atomicRecord(final Path temporary, final Path target, final byte[] bytes) throws IOException {
        writeForced(temporary, bytes);
        replace(temporary, target);
    }

    boolean present(final Path path) throws IOException {
        if (attributes(path) == null) return false;
        file(path);
        return true;
    }

    void create(final Path path) throws IOException {
        requireOwnedPath(path);
        if (files().size() >= 4096) throw new IOException("Cache entry limit reached");
        if (posix) {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else {
            Files.createFile(path);
            restrictAcl(path);
        }
    }

    void remove(final Path path) throws IOException {
        if (present(path)) Files.delete(path);
    }

    void replace(final Path from, final Path to) throws IOException {
        replace(from, to, () -> {});
    }

    void replace(final Path from, final Path to, final Runnable published) throws IOException {
        file(from);
        present(to);
        forceFile(from);
        faults.hit("before-rename:" + to.getFileName());
        // No cross-filesystem or non-atomic fallback.
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        published.run();
        faults.hit("after-rename:" + to.getFileName());
        forceDirectory();
    }

    void etag(final Path path, final String value) throws IOException {
        final Path temporary = root.resolve("etag-writing");
        writeForced(temporary, value.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        replace(temporary, path);
    }

    String etag(final Path path) throws IOException {
        if (!present(path)) return null;
        if (Files.size(path) > 512) throw new IOException("Oversized cache validator");
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            final byte[] bytes = input.readNBytes(513);
            if (bytes.length > 512) throw new IOException("Oversized cache validator");
            return UpdateManifest.strictUtf8(bytes);
        }
    }

    boolean verified(final Path path, final long size, final String hash) throws IOException, InterruptedException {
        if (!present(path)) return false;
        if (Files.size(path) != size) return false;
        return hash(path, size).equals(hash);
    }

    static String hash(final Path path, final long limit) throws IOException, InterruptedException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            final byte[] bytes = new byte[32768];
            long total = 0;
            for (int n; (n = input.read(bytes)) != -1; ) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Cache hashing cancelled");
                total += n;
                if (total > limit) throw new IOException("Cache file grew beyond expected length");
                digest.update(bytes, 0, n);
            }
            if (total != limit) throw new IOException("Cache file length changed during hash");
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * True unless the file has more than one hard link. Where the provider has no link count (Windows via NIO),
     * this cannot be established and returns true: callers always re-hash content, files are replaced by
     * rename rather than rewritten in place, and the cache directory is ACL-private, so an alias cannot alter
     * the bytes that are verified or staged.
     */
    static boolean singleLink(final Path path) throws IOException {
        if (!Files.getFileStore(path).supportsFileAttributeView("unix")) return true;
        return ((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() == 1;
    }

    static BasicFileAttributes attributes(final Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return null;
        }
    }

    private static void directoryChain(final Path path) throws IOException {
        if (path == null) throw new IOException("Cache parent missing");
        Path current = path.getRoot();
        for (final Path component : path) {
            current = current.resolve(component);
            final BasicFileAttributes a = attributes(current);
            if (a == null || !a.isDirectory() || a.isSymbolicLink() || a.isOther()) {
                throw new IOException("Unsafe cache ancestor");
            }
        }
    }

    private void requireOwnedPath(final Path path) throws IOException {
        final Path normalized = path.toAbsolutePath().normalize();
        if (!path.equals(normalized) || !root.equals(normalized.getParent())) {
            throw new IOException("Path is not a generated direct cache child");
        }
    }

    private void file(final Path path) throws IOException {
        requireOwnedPath(path);
        directoryChain(root);
        final BasicFileAttributes a = attributes(path);
        if (a == null || !a.isRegularFile() || a.isSymbolicLink() || a.isOther())
            throw new IOException("Unsafe cache file");
        if (!singleLink(path)) throw new IOException("Hard-linked cache file");
        privatePermissions(path, false);
        if (!Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).equals(Files.getOwner(root, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Cache file owner mismatch");
        }
    }

    /** Replace the DACL with owner-only access (plus SYSTEM and Administrators if resolvable). */
    private static void restrictAcl(final Path path) throws IOException {
        final AclFileAttributeView view =
                Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        final UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
        final var lookup = path.getFileSystem().getUserPrincipalLookupService();
        final List<AclEntry> entries = new ArrayList<>();
        final List<UserPrincipal> principals = new ArrayList<>();
        principals.add(owner);
        for (final String name : new String[] {"SYSTEM", "Administrators"}) {
            try {
                principals.add(lookup.lookupPrincipalByName(name));
            } catch (IOException | UnsupportedOperationException unavailable) {
                // Owner-only is stricter; absence of a well-known account is not an error.
            }
        }
        final boolean directory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
        for (final UserPrincipal principal : principals) {
            entries.add(AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(principal)
                    .setPermissions(java.util.EnumSet.allOf(AclEntryPermission.class))
                    .setFlags(
                            directory
                                    ? java.util.EnumSet.of(
                                            java.nio.file.attribute.AclEntryFlag.FILE_INHERIT,
                                            java.nio.file.attribute.AclEntryFlag.DIRECTORY_INHERIT)
                                    : java.util.EnumSet.noneOf(java.nio.file.attribute.AclEntryFlag.class))
                    .build());
        }
        view.setAcl(entries);
    }

    private static boolean trustedAclPrincipal(final UserPrincipal principal, final UserPrincipal owner) {
        if (principal.equals(owner)) return true;
        final String name = principal.getName().toUpperCase(java.util.Locale.ROOT);
        return name.endsWith("\\SYSTEM") || name.endsWith("\\ADMINISTRATORS");
    }

    private void privatePermissions(final Path path, final boolean directory) throws IOException {
        if (!posix) {
            final var view =
                    Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            final UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
            for (final AclEntry entry : view.getAcl()) {
                // Any ALLOW for an untrusted principal (Everyone, Users, ...) makes the cache non-private.
                if (entry.type() == AclEntryType.ALLOW && !trustedAclPrincipal(entry.principal(), owner)) {
                    throw new IOException("Cache is not private");
                }
            }
            return;
        }
        final var expected = PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------");
        if (!Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(expected)) {
            throw new IOException("Cache is not private");
        }
    }

    @Override
    public void close() throws IOException {
        try {
            lock.close();
        } finally {
            lockChannel.close();
        }
    }
}
