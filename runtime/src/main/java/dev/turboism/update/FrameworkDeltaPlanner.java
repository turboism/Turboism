package dev.turboism.update;

import dev.turboism.core.io.Sha256;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Offline, read-only delta selection. Existing eligible files require trusted installation hashes.
 *
 * <p>Checks static links, non-directory ancestors and reported special files. BasicFileAttributes
 * does not guarantee detection of every Windows reparse type. NOFOLLOW on the final file does not
 * prevent parent-directory races. These decisions authorize neither apply nor download; the future
 * helper must revalidate platform-specific paths, ownership and bytes at application time.
 */
public final class FrameworkDeltaPlanner {
    private static final int HASH_BUFFER_BYTES = 8192;

    private FrameworkDeltaPlanner() {}

    /**
     * What an apply step must do with one manifest entry: {@code REUSE} keeps the verified installed
     * bytes at the same path, {@code COPY_LOCAL} copies verified bytes from another authenticated
     * installed path, and {@code DOWNLOAD} fetches the entry from the authenticated source.
     */
    public enum Action {
        REUSE,
        COPY_LOCAL,
        DOWNLOAD
    }

    /**
     * One planned entry and the action selected for it.
     *
     * @param entry authenticated manifest entry the decision belongs to
     * @param action whether apply reuses the installed bytes, copies another installed file, or downloads
     * @param sourceRelativePath authenticated local source path when {@code action} is {@link Action#COPY_LOCAL}
     */
    public record Decision(UpdateManifestEntry entry, Action action, Optional<String> sourceRelativePath) {
        public Decision(final UpdateManifestEntry entry, final Action action) {
            this(entry, action, Optional.empty());
        }

        public Decision {
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(sourceRelativePath, "sourceRelativePath");
            if (action == Action.COPY_LOCAL
                    && sourceRelativePath.filter(p -> !p.isBlank()).isEmpty()) {
                throw new IllegalArgumentException("COPY_LOCAL decision requires a source relative path");
            }
            if (action != Action.COPY_LOCAL && sourceRelativePath.isPresent()) {
                throw new IllegalArgumentException("Only COPY_LOCAL decisions may specify a source relative path");
            }
        }
    }

    /**
     * Complete offline delta decision for one trusted manifest.
     *
     * @param decisions one immutable decision per manifest entry, in manifest order
     * @param bytesToDownload sum of entry sizes that still have to be downloaded
     */
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
        final Map<String, List<String>> installedPathsByHash =
                indexInstalledPathsByHash(trustedManifest.installedFrameworkHashes());
        final Map<String, String> verifiedLocalHashes = new HashMap<>();
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
                verifiedLocalHashes.put(entry.relativePath(), actualHash);
                unchanged = attrs.size() == entry.bytes() && actualHash.equals(entry.sha256());
            }
            checkInterrupted();
            if (unchanged) {
                decisions.add(new Decision(entry, Action.REUSE));
                continue;
            }
            final Optional<String> localSource =
                    findReusableLocalSource(root, entry, installedPathsByHash, verifiedLocalHashes);
            checkInterrupted();
            if (localSource.isPresent()) {
                decisions.add(new Decision(entry, Action.COPY_LOCAL, localSource));
            } else {
                decisions.add(new Decision(entry, Action.DOWNLOAD));
                bytesToDownload += entry.bytes();
            }
        }
        checkInterrupted();
        return new Plan(decisions, bytesToDownload);
    }

    /** Indexes authenticated receipt paths in deterministic sorted order for cross-path reuse. */
    private static Map<String, List<String>> indexInstalledPathsByHash(final Map<String, String> installedHashes) {
        final Map<String, List<String>> byHash = new HashMap<>();
        for (final var item : new TreeMap<>(installedHashes).entrySet()) {
            byHash.computeIfAbsent(item.getValue(), ignored -> new ArrayList<>())
                    .add(item.getKey());
        }
        return byHash;
    }

    /** Finds a verified local receipt file with identical bytes to avoid redownloading renamed files. */
    private static Optional<String> findReusableLocalSource(
            final Path root,
            final UpdateManifestEntry entry,
            final Map<String, List<String>> installedPathsByHash,
            final Map<String, String> verifiedLocalHashes)
            throws IOException, InterruptedException {
        final List<String> candidates = installedPathsByHash.get(entry.sha256());
        if (candidates == null) return Optional.empty();
        for (final String candidatePath : candidates) {
            checkInterrupted();
            if (candidatePath.equals(entry.relativePath())) continue;
            final Path source = root.resolve(candidatePath);
            final BasicFileAttributes sourceAttrs = targetAttributes(root, source);
            if (sourceAttrs == null
                    || !sourceAttrs.isRegularFile()
                    || sourceAttrs.isSymbolicLink()
                    || sourceAttrs.isOther()
                    || sourceAttrs.size() != entry.bytes()
                    || sourceAttrs.size() > UpdateManifest.MAX_FILE_BYTES) {
                continue;
            }
            final String cachedHash = verifiedLocalHashes.get(candidatePath);
            final String actualHash = cachedHash != null ? cachedHash : sha256(source);
            verifiedLocalHashes.putIfAbsent(candidatePath, actualHash);
            if (actualHash.equals(entry.sha256())) {
                return Optional.of(candidatePath);
            }
        }
        return Optional.empty();
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
        final BasicFileAttributes filesystemRoot = UpdateFileSupport.ancestorAttributesIfPresent(current);
        if (filesystemRoot == null) throw new IOException("Filesystem root unavailable: " + current);
        requireDirectory(current, filesystemRoot);
        for (final Path component : root) {
            checkInterrupted();
            current = current.resolve(component);
            final BasicFileAttributes attrs = UpdateFileSupport.ancestorAttributesIfPresent(current);
            if (attrs == null) return;
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
        final MessageDigest digest = Sha256.digest();
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            final byte[] buffer = new byte[HASH_BUFFER_BYTES];
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
        return Sha256.hex(digest);
    }
}
