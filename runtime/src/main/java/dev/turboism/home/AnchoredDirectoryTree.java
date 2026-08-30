package dev.turboism.home;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/** Anchors and lazily creates one missing directory below an existing parent. */
public final class AnchoredDirectoryTree {

    private AnchoredDirectoryTree() {
    }

    /**
     * Canonicalizes an existing directory or its existing parent. The returned
     * path is independent of aliases in the configured path, while the directory
     * leaf itself remains absent until first use.
     *
     * @param directory configured directory path
     * @return the canonical directory path, which may not exist yet
     * @throws IOException when the directory or its immediate parent is unsafe
     */
    public static Path anchor(final Path directory) throws IOException {
        final Path target = normalize(directory);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(target)
                || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new UnsafeDirectoryTreeException();
            }
            return target.toRealPath();
        }
        final Path parent = target.getParent();
        final Path name = target.getFileName();
        if (parent == null || name == null
            || !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("lazy directory parent must already exist");
        }
        final Path parentReal = parent.toRealPath();
        verifyCanonicalDirectory(parentReal);
        return parentReal.resolve(name).normalize();
    }

    /**
     * Creates an anchored directory leaf without recursively materializing or
     * following a substituted parent path.
     *
     * @param directory anchored directory path
     * @throws IOException when creation fails or the path is unsafe
     */
    public static void materialize(final Path directory) throws IOException {
        final Path target = normalize(directory);
        final Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("lazy directory has no parent");
        }
        verifyCanonicalDirectory(parent);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            verifyCanonicalDirectory(target);
            return;
        }
        try {
            Files.createDirectory(target);
        } catch (FileAlreadyExistsException ignored) {
            // A concurrent creator is allowed only after validation below.
        }
        verifyCanonicalDirectory(parent);
        verifyCanonicalDirectory(target);
    }

    private static Path normalize(final Path directory) {
        return Objects.requireNonNull(directory, "directory")
            .toAbsolutePath()
            .normalize();
    }

    private static void verifyCanonicalDirectory(final Path directory) throws IOException {
        if (Files.isSymbolicLink(directory)
            || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
            || !directory.toRealPath().equals(directory)) {
            throw new UnsafeDirectoryTreeException();
        }
    }

    /** Identifies a directory path that is linked, aliased, or not a directory. */
    public static final class UnsafeDirectoryTreeException extends IOException {
        private UnsafeDirectoryTreeException() {
            super("directory path is not a canonical, unlinked directory");
        }
    }
}
