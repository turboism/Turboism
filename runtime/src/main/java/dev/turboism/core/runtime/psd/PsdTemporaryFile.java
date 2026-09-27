package dev.turboism.core.runtime.psd;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/**
 * Runtime-private allocation for a native raw-image export, never a user-supplied path.
 *
 * <p>There is intentionally no delete/close/deleteOnExit behavior. The system owns temp
 * cleanup. This is not a public file grant: a registry must enforce owner, permissions,
 * generation and quotas before allocation/use. Validation is point-in-time; snapshot
 * reads must additionally use NOFOLLOW_LINKS and validate identity around the read.
 */
final class PsdTemporaryFile {
    private final Path root;
    private final Path directory;
    private Path file;
    private final Object rootKey;
    private final Object directoryKey;

    private PsdTemporaryFile(final Path root, final Path directory) throws IOException {
        this.root = root;
        this.directory = directory;
        rootKey = attributes(root).fileKey();
        directoryKey = attributes(directory).fileKey();
        file = Files.createFile(directory.resolve("external-edit.psd"));
    }

    static PsdTemporaryFile create() throws IOException {
        return createIn(Path.of(System.getProperty("java.io.tmpdir")));
    }

    /** Runtime/test configuration only; never accept this root from a plugin request. */
    static PsdTemporaryFile createIn(final Path temporaryRoot) throws IOException {
        final Path root = Objects.requireNonNull(temporaryRoot, "temporaryRoot").toRealPath();
        if (!attributes(root).isDirectory()) {
            throw new IOException("PSD temporary root is not a directory");
        }
        final Path directory = Files.createTempDirectory(root, "turboism-psd-");
        return new PsdTemporaryFile(root, directory);
    }

    /**
     * Resolves only this allocation after checking its current type and parent identity.
     * Atomic regular-file replacement by a PSD editor is permitted; links are not.
     */
    Path validatedPath() throws IOException {
        validateDirectory(root, rootKey);
        validateDirectory(directory, directoryKey);
        if (!attributes(file).isRegularFile()) {
            throw new IOException("PSD temporary target is not a regular file");
        }
        return file;
    }

    /** Called only before handle publication/watcher creation; never overwrites another file. */
    void useSourceName(final String sourceName) throws IOException {
        final String name = safePsdName(sourceName);
        Path current = validatedPath();
        final Path named = directory.resolve(name);
        if (!fileName().equals(name)) {
            if (current.equals(named)) {
                // Windows Path equality ignores case; use a distinct intermediate for case-only names.
                final Path intermediate = directory.resolve("rename-" + java.util.UUID.randomUUID() + ".psd");
                Files.move(current, intermediate);
                file = intermediate;
                current = validatedPath();
            }
            // No REPLACE_EXISTING: even an unexpected file/link in our private directory is retained.
            Files.move(current, named);
            file = named;
            validatedPath();
        }
    }

    private static String safePsdName(final String sourceName) {
        final String fallback = "external-edit.psd";
        if (sourceName == null || sourceName.isBlank() || sourceName.endsWith(".")
            || sourceName.endsWith(" ") || sourceName.codePoints().anyMatch(c ->
                c < 32 || "<>:\"/\\\\|?*".indexOf(c) >= 0)) return fallback;
        final String stem = sourceName.split("\\.", 2)[0].stripTrailing();
        if (stem.matches("(?iu)(CON|PRN|AUX|NUL|CONIN\\$|CONOUT\\$|COM[1-9¹²³]|LPT[1-9¹²³])")) {
            return fallback;
        }
        final String name = sourceName.toLowerCase(java.util.Locale.ROOT).endsWith(".psd")
            ? sourceName : sourceName + ".psd";
        return name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 255 ? fallback : name;
    }

    /** Display metadata frozen before handle publication; grants no path access. */
    String fileName() {
        return file.getFileName().toString();
    }

    private static void validateDirectory(final Path path, final Object expectedKey)
        throws IOException {
        final BasicFileAttributes current = attributes(path);
        if (!current.isDirectory() || !path.toRealPath().equals(path)
            || (expectedKey != null && !expectedKey.equals(current.fileKey()))) {
            throw new IOException("PSD temporary directory identity changed");
        }
    }

    private static BasicFileAttributes attributes(final Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }
}
