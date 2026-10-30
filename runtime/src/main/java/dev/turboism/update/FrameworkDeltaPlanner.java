package dev.turboism.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Offline, read-only delta selection. Existing eligible files require trusted installation hashes.
 *
 * <p>Checks static links, non-directory ancestors and reported special files. BasicFileAttributes
 * does not guarantee detection of every Windows reparse type. NOFOLLOW on the final file does not
 * prevent parent-directory races. These decisions authorize neither apply nor download; the future
 * helper must revalidate platform-specific paths, ownership and bytes at application time.
 */
public final class FrameworkDeltaPlanner {
    private FrameworkDeltaPlanner() {}

    public enum Action {
        REUSE,
        DOWNLOAD
    }

    public record Decision(UpdateManifestEntry entry, Action action) {
        public Decision {
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(action, "action");
        }
    }

    public record Plan(List<Decision> decisions, long bytesToDownload) {
        public Plan {
            decisions = List.copyOf(decisions);
        }
    }

    /**
     * Compares only authenticated manifest entries; never scans or modifies user files.
     * @throws IOException on unsafe paths, missing ownership evidence, conflicts or read errors
     * @throws InterruptedException on cancellation, preserving the interrupt flag
     */
    public static Plan plan(final Path installationRoot, final TrustedUpdateManifest trustedManifest)
            throws IOException, InterruptedException {
        Objects.requireNonNull(installationRoot, "installationRoot");
        Objects.requireNonNull(trustedManifest, "trustedManifest");
        checkInterrupted();
        final Path root = installationRoot.toAbsolutePath().normalize();
        validateSafeRootAndAncestors(root);
        final List<Decision> decisions = new ArrayList<>();
        long bytesToDownload = 0;
        for (final UpdateManifestEntry entry : trustedManifest.entries()) {
            checkInterrupted();
            final Path target = root.resolve(entry.relativePath());
            final BasicFileAttributes attrs = targetAttributes(root, target);
            boolean unchanged = false;
            if (attrs != null) {
                if (!attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.isOther()) {
                    throw new IOException("Installed target is not a regular file: " + target);
                }
                final String installedHash =
                        trustedManifest.installedFrameworkHashes().get(entry.relativePath());
                if (installedHash == null || attrs.size() > UpdateManifest.MAX_FILE_BYTES) {
                    throw new IOException("FRAMEWORK_OWNERSHIP_VIOLATION: no bounded installation evidence: " + target);
                }
                final String actualHash = sha256(target);
                if (!actualHash.equals(installedHash)) {
                    throw new IOException(
                            "FRAMEWORK_OWNERSHIP_VIOLATION: local file conflicts with installation receipt: " + target);
                }
                unchanged = attrs.size() == entry.bytes() && actualHash.equals(entry.sha256());
            }
            checkInterrupted();
            final Action action = unchanged ? Action.REUSE : Action.DOWNLOAD;
            decisions.add(new Decision(entry, action));
            if (action == Action.DOWNLOAD) bytesToDownload += entry.bytes();
        }
        checkInterrupted();
        return new Plan(decisions, bytesToDownload);
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Framework delta planning cancelled");
        }
    }

    private static BasicFileAttributes attributesIfPresent(final Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return null;
        }
    }

    private static void requireDirectory(final Path path, final BasicFileAttributes attrs) throws IOException {
        if (!attrs.isDirectory() || attrs.isSymbolicLink() || attrs.isOther()) {
            throw new IOException("Unsafe or non-directory ancestor: " + path);
        }
    }

    private static void validateSafeRootAndAncestors(final Path root) throws IOException, InterruptedException {
        Path current = root.getRoot();
        final BasicFileAttributes filesystemRoot = attributesIfPresent(current);
        if (filesystemRoot == null) throw new IOException("Filesystem root unavailable: " + current);
        requireDirectory(current, filesystemRoot);
        for (final Path component : root) {
            checkInterrupted();
            current = current.resolve(component);
            final BasicFileAttributes attrs = attributesIfPresent(current);
            if (attrs == null) return; // Remaining descendants are statically absent.
            requireDirectory(current, attrs);
        }
    }

    private static BasicFileAttributes targetAttributes(final Path root, final Path target)
            throws IOException, InterruptedException {
        Path current = root;
        for (final Path component : root.relativize(target)) {
            checkInterrupted();
            current = current.resolve(component);
            final BasicFileAttributes attrs = attributesIfPresent(current);
            if (attrs == null) return null;
            if (current.equals(target)) return attrs;
            requireDirectory(current, attrs);
        }
        throw new IOException("Empty target path");
    }

    private static String sha256(final Path path) throws IOException, InterruptedException {
        checkInterrupted();
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            final byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                checkInterrupted();
                total += read;
                if (total > UpdateManifest.MAX_FILE_BYTES) throw new IOException("Installed file grew beyond limit");
                digest.update(buffer, 0, read);
            }
        }
        checkInterrupted();
        return HexFormat.of().formatHex(digest.digest());
    }
}
