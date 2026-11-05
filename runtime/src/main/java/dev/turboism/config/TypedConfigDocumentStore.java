package dev.turboism.config;

import dev.turboism.home.AnchoredDirectoryTree;
import dev.turboism.persistence.config.StoredDocument;
import dev.turboism.persistence.config.TypedConfigDocumentCodec;
import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.storage.StorageRoot;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic atomic persistence for one plugin's typed config documents. */
final class TypedConfigDocumentStore {

    private static final int MAX_DOCUMENT_BYTES = 1024 * 1024;

    /**
     * Recovery points retained per document. The newest point is the image the last migration
     * replaced; the older ones keep the parent images of a chained upgrade. The bound exists so a
     * crash loop that keeps failing to replace a document cannot fill the disk.
     */
    static final int RECOVERY_POINT_RETENTION = 3;

    private static final String RECOVERY_ROOT_NAME = ".recovery-points";
    private static final String RECOVERY_DIRECTORY_SUFFIX = ".recovery";
    private static final Pattern RECOVERY_POINT_NAME =
            Pattern.compile("([1-9][0-9]{0,9})-r(0|[1-9][0-9]{0,18})-([0-9a-f]{64})\\.bak");
    private static final Comparator<RecoveryPointFile> RECOVERY_POINT_ORDER = Comparator.comparingInt(
                    RecoveryPointFile::schemaVersion)
            .thenComparingLong(RecoveryPointFile::revision)
            .thenComparing(RecoveryPointFile::sha256);

    private final Path root;

    TypedConfigDocumentStore(final Path root) throws IOException {
        this.root = AnchoredDirectoryTree.anchor(root);
        if (Files.exists(this.root, LinkOption.NOFOLLOW_LINKS)) {
            verifyExistingRoot();
        }
    }

    Optional<StoredDocument> read(final String relativePath) throws IOException {
        final Path path = resolve(relativePath, false);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        final byte[] bytes = readDocumentBytes(path);
        try {
            final String text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            return Optional.of(TypedConfigDocumentCodec.decode(text));
        } catch (CharacterCodingException exception) {
            throw new IOException("typed config document is not valid UTF-8", exception);
        }
    }

    void writeAtomic(final String relativePath, final StoredDocument document) throws IOException {
        final Path target = resolve(relativePath, true);
        final byte[] bytes = TypedConfigDocumentCodec.encode(document).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_DOCUMENT_BYTES) {
            throw new IOException("typed config document exceeds size limit");
        }
        writeFileAtomic(target, bytes);
    }

    /**
     * Captures the bytes a document holds on disk right now as the recovery point that must exist
     * before a read-time or write-time migration replaces them.
     *
     * <p>This is deliberately fail-closed: after an exception the caller must not migrate, because
     * the pre-migration image is not durably stored. The captured copy is written through the same
     * secured temporary file and atomic rename as a document, so a recovery point is either absent
     * or byte-identical, never partial. Points live under
     * {@code <root>/.recovery-points/<document path>.recovery/} as
     * {@code <schemaVersion>-r<revision>-<sha256>.bak}, capped at {@link #RECOVERY_POINT_RETENTION}
     * files per document; pruning is best effort because an extra retained copy cannot lose bytes,
     * while a failed prune must never turn a durable capture into a failed migration.
     *
     * <p>Only a migration consults the recovery area, never {@link #read}, so a damaged or
     * unwritable recovery area takes affected documents into a reported failure instead of offline
     * data loss; the recovery point itself is not a new single point of failure.
     *
     * @param relativePath sealed document path inside the store root
     * @param expected the document exactly as the caller read it; the bytes on disk must still match
     *     it, otherwise a concurrent writer changed the document and nothing is captured
     * @return the recovery point file holding the pre-migration bytes
     * @throws IOException when the document changed, is unreadable, or the point cannot be stored
     */
    Path recordRecoveryPoint(final String relativePath, final StoredDocument expected) throws IOException {
        final Path document = resolve(relativePath, false);
        if (!Files.exists(document, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("typed config recovery point source is missing");
        }
        final byte[] bytes = readDocumentBytes(document);
        if (!Arrays.equals(bytes, TypedConfigDocumentCodec.encode(expected).getBytes(StandardCharsets.UTF_8))) {
            throw new IOException("typed config document changed before its recovery point was captured");
        }
        final Path directory = recoveryDirectory(relativePath, true);
        final Path point = directory.resolve(recoveryPointName(expected, bytes));
        if (holdsExactImage(point, bytes)) {
            // A retried migration after a crash between capture and replacement must not accumulate
            // duplicate copies of the same pre-migration image.
            tightenTree(point);
            return point;
        }
        // A same-named point whose bytes no longer match is repaired rather than trusted, so a
        // damaged recovery area cannot make a migration succeed with an unusable image behind it.
        try {
            writeFileAtomic(point, bytes);
        } catch (java.nio.file.AccessDeniedException concurrentCapture) {
            if (holdsExactImage(point, bytes)) {
                tightenTree(point);
                return point;
            }
            throw concurrentCapture;
        }
        pruneRecoveryPoints(directory, point);
        return point;
    }

    /**
     * Lists the retained recovery points for a document, newest first. The recovery area is never
     * created by this query, so it is safe to ask for a document that never migrated.
     *
     * @param relativePath sealed document path inside the store root
     * @return immutable list of recovery point files; empty when none were captured
     * @throws IOException when the recovery area exists but is not a sealed directory
     */
    List<Path> recoveryPoints(final String relativePath) throws IOException {
        final Path directory = recoveryDirectory(relativePath, false);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        verifyExisting(directory, root.toRealPath());
        final List<RecoveryPointFile> points = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (final Path entry : entries) {
                recoveryPointFile(entry)
                        .filter(point -> Files.isRegularFile(point.path(), LinkOption.NOFOLLOW_LINKS))
                        .ifPresent(points::add);
            }
        }
        points.sort(RECOVERY_POINT_ORDER.reversed());
        return points.stream().map(RecoveryPointFile::path).toList();
    }

    /**
     * Writes bytes to a document or recovery point target through one temporary file that is
     * owner-only before the first byte is written, then atomically renamed over the target.
     */
    private void writeFileAtomic(final Path target, final byte[] bytes) throws IOException {
        final Path temporary =
                target.resolveSibling("." + target.getFileName() + ".turboism-config-" + UUID.randomUUID() + ".tmp");
        try {
            // Secure the empty temporary path (owner-only) before any document
            // bytes are written to it; the file is only then opened for writing.
            createSecuredTemporary(temporary);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                final ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            verifyParent(target);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            tightenTree(target);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("typed config atomic replacement unavailable", exception);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Reads one document leaf with the bounded, BOM-rejecting rules of {@link #read}, after
     * tightening the owner-only permissions of the leaf and every parent up to the root.
     */
    private byte[] readDocumentBytes(final Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("typed config path is not a regular file");
        }
        if (Files.size(path) > MAX_DOCUMENT_BYTES) {
            throw new IOException("typed config document exceeds size limit");
        }
        tightenTree(path);
        final byte[] bytes = Files.readAllBytes(path);
        if (bytes.length > MAX_DOCUMENT_BYTES) {
            throw new IOException("typed config document exceeds size limit");
        }
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) {
            throw new IOException("typed config document must not contain a BOM");
        }
        return bytes;
    }

    /**
     * Resolves the recovery directory of one document, optionally creating it with the same
     * owner-only, link-rejecting rules as a document directory.
     */
    private Path recoveryDirectory(final String relativePath, final boolean create) throws IOException {
        final StoragePath validated;
        try {
            validated = new StoragePath(StorageRoot.DATA, relativePath);
        } catch (RuntimeException exception) {
            throw new IOException("typed config path is invalid", exception);
        }
        final List<String> segments =
                new ArrayList<>(Arrays.asList(validated.relativePath().split("/")));
        segments.add(0, RECOVERY_ROOT_NAME);
        final int leaf = segments.size() - 1;
        segments.set(leaf, segments.get(leaf) + RECOVERY_DIRECTORY_SUFFIX);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            if (!create) {
                return root.resolve(String.join("/", segments));
            }
            ensureRoot();
        } else {
            verifyExistingRoot();
        }
        final Path rootReal = root.toRealPath();
        Path directory = root;
        for (final String segment : segments) {
            directory = directory.resolve(segment);
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                if (!create) {
                    return directory;
                }
                try {
                    Files.createDirectory(directory);
                } catch (FileAlreadyExistsException concurrentCreator) {
                    // A concurrent capture won the race for this directory; fall through to the same
                    // verification as any other existing path instead of failing the capture.
                }
            }
            verifyExisting(directory, rootReal);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("typed config recovery path is not a directory");
            }
            enforceOwnerOnly(directory, true);
        }
        return directory;
    }

    /**
     * Keeps at most {@link #RECOVERY_POINT_RETENTION} points per document, dropping the oldest
     * document states first and never the point that was just captured. Pruning runs after a
     * durable capture and therefore never fails a migration.
     */
    private void pruneRecoveryPoints(final Path directory, final Path captured) {
        try {
            final List<RecoveryPointFile> points = new ArrayList<>();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
                for (final Path entry : entries) {
                    recoveryPointFile(entry)
                            .filter(point -> Files.isRegularFile(point.path(), LinkOption.NOFOLLOW_LINKS))
                            .ifPresent(points::add);
                }
            }
            points.sort(RECOVERY_POINT_ORDER);
            int excess = points.size() - RECOVERY_POINT_RETENTION;
            for (final RecoveryPointFile point : points) {
                if (excess <= 0) {
                    return;
                }
                if (point.path().equals(captured)) {
                    continue;
                }
                Files.deleteIfExists(point.path());
                excess--;
            }
        } catch (IOException ignored) {
            // Best effort: an unpruned older copy cannot lose bytes, and the newly captured image is
            // already durable at this point.
        }
    }

    private static boolean holdsExactImage(final Path point, final byte[] bytes) {
        try {
            return Files.isRegularFile(point, LinkOption.NOFOLLOW_LINKS)
                    && Files.size(point) == bytes.length
                    && Arrays.equals(Files.readAllBytes(point), bytes);
        } catch (IOException exception) {
            return false;
        }
    }

    private static String recoveryPointName(final StoredDocument document, final byte[] bytes) {
        return document.schemaVersion() + "-r" + document.revision() + "-" + sha256(bytes) + ".bak";
    }

    private static Optional<RecoveryPointFile> recoveryPointFile(final Path path) {
        final Path name = path.getFileName();
        if (name == null) {
            return Optional.empty();
        }
        final Matcher matcher = RECOVERY_POINT_NAME.matcher(name.toString());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new RecoveryPointFile(
                    path, Integer.parseInt(matcher.group(1)), Long.parseLong(matcher.group(2)), matcher.group(3)));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private static String sha256(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is unavailable", exception);
        }
    }

    /** One retained pre-migration image, ordered by the document state it holds. */
    private record RecoveryPointFile(Path path, int schemaVersion, long revision, String sha256) {}

    private Path resolve(final String relativePath, final boolean createParents) throws IOException {
        final StoragePath validated;
        try {
            validated = new StoragePath(StorageRoot.DATA, relativePath);
        } catch (RuntimeException exception) {
            throw new IOException("typed config path is invalid", exception);
        }
        // The recovery namespace is owned by this store; a document declared there could otherwise
        // read or overwrite another document's pre-migration image.
        if (validated.relativePath().equals(RECOVERY_ROOT_NAME)
                || validated.relativePath().startsWith(RECOVERY_ROOT_NAME + "/")) {
            throw new IOException("typed config path uses the reserved recovery namespace");
        }
        if (createParents) {
            ensureRoot();
        } else if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return root.resolve(validated.relativePath());
        } else {
            verifyExistingRoot();
        }
        final Path rootReal = root.toRealPath();
        Path current = root;
        final String[] segments = validated.relativePath().split("/");
        for (int index = 0; index < segments.length; index++) {
            current = current.resolve(segments[index]);
            final boolean last = index == segments.length - 1;
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                verifyExisting(current, rootReal);
                if (!last && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("typed config parent is not a directory");
                }
            } else if (!last && createParents) {
                Files.createDirectory(current);
                enforceOwnerOnly(current, true);
                verifyExisting(current, rootReal);
            } else if (!last) {
                return current.resolve(
                        String.join("/", java.util.Arrays.copyOfRange(segments, index + 1, segments.length)));
            }
        }
        verifyParent(current);
        return current;
    }

    private void ensureRoot() throws IOException {
        AnchoredDirectoryTree.materialize(root);
        verifyExistingRoot();
    }

    private void verifyExistingRoot() throws IOException {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("typed config root must be a directory and not a symbolic link");
        }
        enforceOwnerOnly(root, true);
    }

    private void verifyParent(final Path target) throws IOException {
        final Path parent = target.getParent();
        if (parent == null
                || Files.isSymbolicLink(parent)
                || !parent.toRealPath().startsWith(root.toRealPath())) {
            throw new IOException("typed config path escapes its root");
        }
    }

    private static void verifyExisting(final Path path, final Path rootReal) throws IOException {
        if (Files.isSymbolicLink(path) || !path.toRealPath().startsWith(rootReal)) {
            throw new IOException("typed config path traverses a link");
        }
    }

    /** Tightens the given document leaf and every parent up to and including the root. */
    private void tightenTree(final Path leaf) throws IOException {
        final Path rootAbs = root.toAbsolutePath().normalize();
        final List<Path> directories = new ArrayList<>();
        Path cursor = leaf.toAbsolutePath().getParent();
        while (cursor != null && cursor.startsWith(rootAbs) && !cursor.equals(rootAbs)) {
            directories.add(cursor);
            cursor = cursor.getParent();
        }
        directories.add(rootAbs);
        Collections.reverse(directories);
        for (final Path directory : directories) {
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                enforceOwnerOnly(directory, true);
            }
        }
        if (Files.exists(leaf, LinkOption.NOFOLLOW_LINKS)) {
            enforceOwnerOnly(leaf, false);
        }
    }

    /**
     * Creates an empty temporary config file that is owner-only before the first
     * document byte is written. On POSIX file systems the owner read/write (0600)
     * permission is requested atomically at creation; on ACL file systems the
     * empty file is created, tightened to a single owner-only ACE, and only then
     * opened for writing. A file system exposing neither model fails closed and
     * deletes the still-empty temporary path.
     */
    private static void createSecuredTemporary(final Path temporary) throws IOException {
        try {
            Files.createFile(temporary, PosixFilePermissions.asFileAttribute(FILE_OWNER_ONLY));
            return;
        } catch (UnsupportedOperationException noPosix) {
            // Non-POSIX file system; fall through to the ACL model below.
        } catch (java.nio.file.FileAlreadyExistsException collision) {
            throw new IOException("typed config temporary already exists", collision);
        }
        boolean created = false;
        try {
            Files.createFile(temporary);
            created = true;
            final AclFileAttributeView acl =
                    Files.getFileAttributeView(temporary, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (acl == null) {
                throw new IOException("typed config ACL view is unavailable");
            }
            acl.setAcl(List.of(ownerOnlyEntry(acl.getOwner(), false)));
        } catch (IOException | UnsupportedOperationException failure) {
            if (created) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Best-effort cleanup of an empty temporary path.
                }
            }
            throw new IOException("typed config cannot create an owner-only temporary file", failure);
        }
    }

    /**
     * Enforces owner-only access on a typed config path using the platform model.
     * POSIX targets are directory 0700 and file 0600; the ACL model replaces
     * inherited ACEs with one owner-only entry. A file system exposing neither
     * model fails closed for persistence rather than pretending to be secure.
     */
    private static void enforceOwnerOnly(final Path path, final boolean directory) throws IOException {
        try {
            final PosixFileAttributeView posix =
                    Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (posix != null) {
                final Set<PosixFilePermission> expected = directory ? DIR_OWNER_ONLY : FILE_OWNER_ONLY;
                if (expected.equals(posix.readAttributes().permissions())) {
                    return;
                }
                posix.setPermissions(expected);
                return;
            }
            final AclFileAttributeView acl =
                    Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (acl != null) {
                final List<AclEntry> expected = List.of(ownerOnlyEntry(acl.getOwner(), directory));
                if (expected.equals(acl.getAcl())) {
                    return;
                }
                acl.setAcl(expected);
                return;
            }
        } catch (UnsupportedOperationException unsupported) {
            throw new IOException("typed config owner-only permissions unavailable: " + path, unsupported);
        }
        throw new IOException("typed config cannot enforce owner-only permissions on this file system: " + path);
    }

    static AclEntry ownerOnlyEntry(final java.nio.file.attribute.UserPrincipal owner, final boolean directory) {
        final Set<AclEntryPermission> permissions = EnumSet.noneOf(AclEntryPermission.class);
        permissions.add(AclEntryPermission.READ_DATA);
        permissions.add(AclEntryPermission.WRITE_DATA);
        permissions.add(AclEntryPermission.APPEND_DATA);
        permissions.add(AclEntryPermission.READ_ATTRIBUTES);
        permissions.add(AclEntryPermission.WRITE_ATTRIBUTES);
        permissions.add(AclEntryPermission.READ_NAMED_ATTRS);
        permissions.add(AclEntryPermission.WRITE_NAMED_ATTRS);
        permissions.add(AclEntryPermission.SYNCHRONIZE);
        if (directory) {
            permissions.add(AclEntryPermission.EXECUTE);
            permissions.add(AclEntryPermission.DELETE_CHILD);
        }
        return AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(permissions)
                .build();
    }

    private static final Set<PosixFilePermission> FILE_OWNER_ONLY = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> DIR_OWNER_ONLY = PosixFilePermissions.fromString("rwx------");
}
