package dev.turboism.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.turboism.core.io.DurableFile;
import dev.turboism.core.io.PathLocks;
import dev.turboism.core.io.WriteOptions;
import dev.turboism.runtime.json.RuntimeJson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.function.Consumer;

/** Atomic file replacement and bounded, symlink-rejecting update state IO. */
public final class UpdateFileSupport {
    static final ObjectMapper JSON = RuntimeJson.strict();
    private static final long MAX_FILE_BYTES = 1024L * 1024L;

    /**
     * Update state keeps the pre-migration tolerance for file systems that refuse
     * {@code ATOMIC_MOVE} (removable and network Turboism homes) and inherits the home directory's
     * permission model.
     */
    private static final WriteOptions STATE_WRITE =
            WriteOptions.DEFAULT.withAtomicity(WriteOptions.Atomicity.PREFERRED);

    /**
     * Suffix of the single bounded recovery image kept when a document can no longer be read as the
     * current schema. Named after the whole file ({@code update-state.json.corrupt.bak}) so a user
     * can find it next to the document it preserves, and rewritten rather than appended so the
     * retention is exactly one image.
     */
    static final String UNREADABLE_SUFFIX = ".corrupt.bak";

    /**
     * Win32 {@code ERROR_NOT_A_REPARSE_POINT} ({@code 0x1126}) reason emitted when Wine marks a
     * Unix mount boundary as a reparse point and rejects {@code FSCTL_GET_REPARSE_POINT}.
     */
    static final String WINE_MOUNT_POINT_REASON = "Unknown error: 0x1126";

    interface AncestorProbe {
        BasicFileAttributes readNoFollow(Path path) throws IOException;

        BasicFileAttributes readFollowing(Path path) throws IOException;

        Path toRealPath(Path path) throws IOException;
    }

    private static final AncestorProbe SYSTEM_ANCESTOR_PROBE = new AncestorProbe() {
        @Override
        public BasicFileAttributes readNoFollow(final Path path) throws IOException {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        }

        @Override
        public BasicFileAttributes readFollowing(final Path path) throws IOException {
            return Files.readAttributes(path, BasicFileAttributes.class);
        }

        @Override
        public Path toRealPath(final Path path) throws IOException {
            return path.toRealPath();
        }
    };

    private UpdateFileSupport() {}

    static Path normalizeHome(final Path requestedHome) {
        return Objects.requireNonNull(requestedHome, "home").toAbsolutePath().normalize();
    }

    static Object lockFor(final Path path) {
        return PathLocks.forPath(path);
    }

    /**
     * Reads ancestor attributes without following links, accepting non-redirecting Unix mount
     * boundaries under Wine where {@code NOFOLLOW_LINKS} fails with {@code 0x1126}.
     */
    static BasicFileAttributes ancestorAttributesIfPresent(final Path path) throws IOException {
        return ancestorAttributesIfPresent(path, SYSTEM_ANCESTOR_PROBE);
    }

    static BasicFileAttributes ancestorAttributesIfPresent(final Path path, final AncestorProbe probe)
            throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(probe, "probe");
        try {
            return probe.readNoFollow(path);
        } catch (NoSuchFileException missing) {
            return null;
        } catch (FileSystemException failure) {
            if (!WINE_MOUNT_POINT_REASON.equals(failure.getReason())) {
                throw failure;
            }
            return verifyMountBoundaryDirectory(path, probe, failure);
        }
    }

    private static BasicFileAttributes verifyMountBoundaryDirectory(
            final Path path, final AncestorProbe probe, final FileSystemException originalFailure) throws IOException {
        final BasicFileAttributes resolved;
        final Path real;
        try {
            resolved = probe.readFollowing(path);
            real = probe.toRealPath(path);
        } catch (IOException secondary) {
            originalFailure.addSuppressed(secondary);
            throw originalFailure;
        }
        final Path expected = path.toAbsolutePath().normalize();
        if (resolved == null
                || !resolved.isDirectory()
                || resolved.isSymbolicLink()
                || resolved.isOther()
                || !expected.equals(real)) {
            throw originalFailure;
        }
        return resolved;
    }

    static ObjectNode readObject(final Path home, final Path path) throws IOException {
        rejectSymlinkChain(home, path);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_FILE_BYTES) {
            throw new IOException("update state file rejected");
        }
        final byte[] bytes = Files.readAllBytes(path);
        if (bytes.length > MAX_FILE_BYTES) throw new IOException("update state file rejected");
        final JsonNode parsed = JSON.readTree(bytes);
        if (!(parsed instanceof ObjectNode object)) throw new IOException("update state is not an object");
        return object;
    }

    static void writeAtomic(final Path home, final Path path, final ObjectNode object) throws IOException {
        Objects.requireNonNull(object, "object");
        final byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(object);
        if (bytes.length > MAX_FILE_BYTES) throw new IOException("update state is too large");
        writeAtomic(home, path, bytes);
    }

    private static void writeAtomic(final Path home, final Path path, final byte[] bytes) throws IOException {
        final Path parent = path.getParent();
        if (parent == null || !path.startsWith(home)) throw new IOException("update path escaped home");
        rejectSymlinkChain(home, parent);
        Files.createDirectories(parent);
        rejectSymlinkChain(home, parent);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
            throw new IOException("update state file is symbolic link");
        }
        // The chain is re-checked while the previous state file is still visible: a directory that
        // turned into a link during the write must not receive the new bytes.
        DurableFile.at(path)
                .withOptions(STATE_WRITE)
                .guardedBy((staged, target) -> rejectSymlinkChain(home, target))
                .replace(bytes);
    }

    /** Returns the bounded recovery image path for {@code path}. */
    static Path unreadableSibling(final Path path) {
        return path.resolveSibling(path.getFileName() + UNREADABLE_SUFFIX);
    }

    /**
     * Keeps one copy of a stored document that the current reader cannot accept, before the caller
     * replaces it.
     *
     * <p>Only corrupt bytes, unknown fields and future {@code schemaVersion} documents reach this
     * path: a document this build reads normally is overwritten with no extra copy, so a healthy
     * installation never accumulates backups. The copy is written through the same atomic
     * temporary-and-move path as the document itself, replaces any earlier image (retention is
     * exactly one) and is best effort — a failed recovery copy must never block the write that
     * follows or turn a save into data loss.</p>
     */
    static void preserveUnreadable(final Path home, final Path path) {
        try {
            rejectSymlinkChain(home, path);
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return;
            if (Files.size(path) > MAX_FILE_BYTES) return;
            final byte[] bytes = Files.readAllBytes(path);
            writeAtomic(home, unreadableSibling(path), bytes);
        } catch (IOException | RuntimeException unavailable) {
            // Best effort by contract: the caller's write still proceeds.
        }
    }

    /** Copies a file via a temporary sibling, preserving the source for recovery. */
    public static void copyAtomic(final Path source, final Path target) throws IOException {
        try (InputStream input = Files.newInputStream(source)) {
            writeAtomic(target, input);
        }
    }

    /** Replaces a file from a stream via a temporary sibling; does not close the stream. */
    public static void writeAtomic(final Path target, final InputStream input) throws IOException {
        DurableFile.at(target).withOptions(STATE_WRITE).replaceStreaming(out -> input.transferTo(out));
    }

    static void rejectSymlinkChain(final Path home, final Path requested) throws IOException {
        final Path normalizedHome = normalizeHome(home);
        final Path normalized = requested.toAbsolutePath().normalize();
        if (!normalized.startsWith(normalizedHome)) throw new IOException("update path escaped home");
        Path current = normalized;
        while (current != null && current.startsWith(normalizedHome)) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new IOException("symbolic link in update path");
            }
            if (current.equals(normalizedHome)) break;
            current = current.getParent();
        }
        if (current == null || !current.equals(normalizedHome)) {
            throw new IOException("update path escaped home");
        }
        if (Files.exists(normalizedHome, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(normalizedHome)) {
            throw new IOException("update home is symbolic link");
        }
    }

    static void report(final Consumer<String> diagnostic, final String code) {
        try {
            diagnostic.accept(code);
        } catch (RuntimeException ignored) {
            // Diagnostics are best effort and never change fail-closed state handling.
        }
    }
}
