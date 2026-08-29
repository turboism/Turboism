package dev.turboism.config;

import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.storage.StorageRoot;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.AtomicMoveNotSupportedException;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Deterministic atomic persistence for one plugin's typed config documents. */
final class TypedConfigDocumentStore {

    private static final String HEADER = "turboism-typed-config-v1";
    private static final int MAX_DOCUMENT_BYTES = 1024 * 1024;

    private final Path root;

    TypedConfigDocumentStore(final Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root);
        if (Files.isSymbolicLink(this.root)) {
            throw new IOException("typed config root must not be a symbolic link");
        }
        enforceOwnerOnly(this.root, true);
    }

    Optional<StoredDocument> read(final String relativePath) throws IOException {
        final Path path = resolve(relativePath, false);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("typed config path is not a regular file");
        }
        final long size = Files.size(path);
        if (size > MAX_DOCUMENT_BYTES) {
            throw new IOException("typed config document exceeds size limit");
        }
        tightenTree(path);
        final byte[] bytes = Files.readAllBytes(path);
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF
            && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) {
            throw new IOException("typed config document must not contain a BOM");
        }
        try {
            final String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
            return Optional.of(parse(text));
        } catch (CharacterCodingException exception) {
            throw new IOException("typed config document is not valid UTF-8", exception);
        }
    }

    void writeAtomic(
        final String relativePath,
        final StoredDocument document
    ) throws IOException {
        final Path target = resolve(relativePath, true);
        final byte[] bytes = encode(document).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_DOCUMENT_BYTES) {
            throw new IOException("typed config document exceeds size limit");
        }
        final Path temporary = target.resolveSibling(
            "." + target.getFileName() + ".turboism-config-" + UUID.randomUUID() + ".tmp"
        );
        try {
            // Secure the empty temporary path (owner-only) before any document
            // bytes are written to it; the file is only then opened for writing.
            createSecuredTemporary(temporary);
            try (FileChannel channel = FileChannel.open(
                temporary,
                StandardOpenOption.WRITE
            )) {
                final ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            verifyParent(target);
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
            tightenTree(target);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("typed config atomic replacement unavailable", exception);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path resolve(
        final String relativePath,
        final boolean createParents
    ) throws IOException {
        final StoragePath validated;
        try {
            validated = new StoragePath(StorageRoot.DATA, relativePath);
        } catch (RuntimeException exception) {
            throw new IOException("typed config path is invalid", exception);
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
                return current.resolve(String.join("/", java.util.Arrays.copyOfRange(
                    segments,
                    index + 1,
                    segments.length
                )));
            }
        }
        verifyParent(current);
        return current;
    }

    private void verifyParent(final Path target) throws IOException {
        final Path parent = target.getParent();
        if (parent == null || Files.isSymbolicLink(parent)
            || !parent.toRealPath().startsWith(root.toRealPath())) {
            throw new IOException("typed config path escapes its root");
        }
    }

    private static void verifyExisting(
        final Path path,
        final Path rootReal
    ) throws IOException {
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
            final AclFileAttributeView acl = Files.getFileAttributeView(
                temporary,
                AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS
            );
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
            throw new IOException(
                "typed config cannot create an owner-only temporary file",
                failure
            );
        }
    }

    /**
     * Enforces owner-only access on a typed config path using the platform model.
     * POSIX targets are directory 0700 and file 0600; the ACL model replaces
     * inherited ACEs with one owner-only entry. A file system exposing neither
     * model fails closed for persistence rather than pretending to be secure.
     */
    private static void enforceOwnerOnly(final Path path, final boolean directory)
        throws IOException {
        try {
            final PosixFileAttributeView posix = Files.getFileAttributeView(
                path,
                PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS
            );
            if (posix != null) {
                posix.setPermissions(directory ? DIR_OWNER_ONLY : FILE_OWNER_ONLY);
                return;
            }
            final AclFileAttributeView acl = Files.getFileAttributeView(
                path,
                AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS
            );
            if (acl != null) {
                acl.setAcl(List.of(ownerOnlyEntry(acl.getOwner(), directory)));
                return;
            }
        } catch (UnsupportedOperationException unsupported) {
            throw new IOException(
                "typed config owner-only permissions unavailable: " + path,
                unsupported
            );
        }
        throw new IOException(
            "typed config cannot enforce owner-only permissions on this file system: " + path
        );
    }

    static AclEntry ownerOnlyEntry(
        final java.nio.file.attribute.UserPrincipal owner,
        final boolean directory
    ) {
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

    private static final Set<PosixFilePermission> FILE_OWNER_ONLY =
        PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> DIR_OWNER_ONLY =
        PosixFilePermissions.fromString("rwx------");

    private static String encode(final StoredDocument document) {
        final List<String> keys = new ArrayList<>(document.encodedValues().keySet());
        keys.sort(String::compareTo);
        final StringBuilder output = new StringBuilder();
        output.append(HEADER).append('\n');
        output.append("schemaVersion=").append(document.schemaVersion()).append('\n');
        output.append("revision=").append(document.revision()).append('\n');
        output.append("count=").append(keys.size()).append('\n');
        final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        for (String key : keys) {
            output.append(encoder.encodeToString(key.getBytes(StandardCharsets.UTF_8)))
                .append(':')
                .append(encoder.encodeToString(
                    document.encodedValues().get(key).getBytes(StandardCharsets.UTF_8)
                ))
                .append('\n');
        }
        return output.toString();
    }

    private static StoredDocument parse(final String text) throws IOException {
        final String[] lines = text.split("\\n", -1);
        if (lines.length < 5 || !lines[0].equals(HEADER)
            || !lines[lines.length - 1].isEmpty()) {
            throw new IOException("typed config document header is invalid");
        }
        final int version = parseInt(lines[1], "schemaVersion=");
        final long revision = parseLong(lines[2], "revision=");
        final int count = parseInt(lines[3], "count=");
        if (version < 1 || revision < 0 || count < 0 || lines.length != count + 5) {
            throw new IOException("typed config document metadata is invalid");
        }
        final Base64.Decoder decoder = Base64.getUrlDecoder();
        final Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            final String line = lines[index + 4];
            final int separator = line.indexOf(':');
            if (separator <= 0 || separator != line.lastIndexOf(':')) {
                throw new IOException("typed config entry is invalid");
            }
            try {
                final String key = decodeUtf8(
                    decoder.decode(line.substring(0, separator))
                );
                final String value = decodeUtf8(
                    decoder.decode(line.substring(separator + 1))
                );
                if (key.isBlank() || values.putIfAbsent(key, value) != null) {
                    throw new IOException("typed config entry key is invalid");
                }
            } catch (IllegalArgumentException exception) {
                throw new IOException("typed config entry encoding is invalid", exception);
            }
        }
        final StoredDocument document = new StoredDocument(version, revision, values);
        if (!encode(document).equals(text)) {
            throw new IOException("typed config document is noncanonical");
        }
        return document;
    }

    private static String decodeUtf8(final byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("typed config entry is not valid UTF-8", exception);
        }
    }

    private static int parseInt(final String line, final String prefix) throws IOException {
        final long value = parseLong(line, prefix);
        if (value > Integer.MAX_VALUE) {
            throw new IOException("typed config integer metadata is out of range");
        }
        return (int) value;
    }

    private static long parseLong(final String line, final String prefix) throws IOException {
        if (!line.startsWith(prefix)) {
            throw new IOException("typed config metadata is missing");
        }
        final String raw = line.substring(prefix.length());
        if (!raw.matches("0|[1-9][0-9]*")) {
            throw new IOException("typed config metadata is noncanonical");
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException exception) {
            throw new IOException("typed config metadata is out of range", exception);
        }
    }

    record StoredDocument(
        int schemaVersion,
        long revision,
        Map<String, String> encodedValues
    ) {
        StoredDocument {
            if (schemaVersion < 1 || revision < 0) {
                throw new IllegalArgumentException("typed config document metadata is invalid");
            }
            encodedValues = Map.copyOf(encodedValues);
        }
    }
}
