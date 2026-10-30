package dev.turboism.update;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;

/**
 * Turns a verified {@link FrameworkUpdateDownloader.Downloaded} into the directory layout the external helper
 * consumes:
 *
 * <pre>
 * stage/
 *   manifest.json        exact signed bytes
 *   manifest.json.sig    exact signature envelope
 *   files/&lt;relative path&gt;  every file the update changes, by manifest path
 * </pre>
 *
 * <p>Files the planner marked {@code REUSE} are <em>not</em> copied: the helper leaves them in place. Content comes
 * from the content-addressed cache objects; each is re-hashed against the manifest entry while copying, so the stage
 * never contains bytes that were not authenticated, even if the cache changed after download.
 *
 * <p>The directory is built under a temporary name and renamed into place, so the helper never sees a half-built
 * stage. This class never touches the installation.
 */
final class FrameworkUpdateStage {
    private FrameworkUpdateStage() {}

    /**
     * @param target stage directory to create; must not exist
     * @return {@code target}
     */
    static Path materialize(final FrameworkUpdateDownloader.Downloaded downloaded, final Path target)
            throws IOException, InterruptedException {
        Objects.requireNonNull(downloaded, "downloaded");
        final Path destination = target.toAbsolutePath().normalize();
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Stage already exists");
        final Path parent = destination.getParent();
        if (parent == null) throw new IOException("Stage needs a parent directory");
        Files.createDirectories(parent);
        final Path building = parent.resolve(destination.getFileName() + ".building");
        deleteTree(building);
        Files.createDirectory(building);
        try {
            copyDocument(downloaded.manifest(), building.resolve("manifest.json"), UpdateManifest.MAX_MANIFEST_BYTES);
            copyDocument(
                    downloaded.envelope(), building.resolve("manifest.json.sig"), UpdateManifestVerifier.MAX_ENVELOPE_BYTES);
            for (final FrameworkDeltaPlanner.Decision decision : downloaded.plan().decisions()) {
                if (decision.action() != FrameworkDeltaPlanner.Action.DOWNLOAD) continue;
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Staging cancelled");
                final UpdateManifestEntry entry = decision.entry();
                final Path object = downloaded.objects().get(entry.sha256());
                if (object == null) throw new IOException("Downloaded object missing for " + entry.relativePath());
                final Path file = building.resolve("files").resolve(entry.relativePath()).normalize();
                if (!file.startsWith(building.resolve("files"))) throw new IOException("Path escapes stage");
                Files.createDirectories(file.getParent());
                Files.copy(object, file);
                if (!FrameworkUpdateCache.hash(file, entry.bytes()).equals(entry.sha256())) {
                    throw new IOException("Cache object no longer matches manifest: " + entry.relativePath());
                }
            }
            try {
                Files.move(building, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Atomic stage publication unavailable", unsupported);
            }
        } catch (IOException | InterruptedException | RuntimeException failure) {
            deleteTree(building);
            throw failure;
        }
        return destination;
    }

    /** Every file the helper will install, keyed by manifest path, with its signed hash. */
    static Map<String, String> expectedFiles(final FrameworkUpdateDownloader.Downloaded downloaded) {
        final var result = new java.util.TreeMap<String, String>();
        for (final var decision : downloaded.plan().decisions()) {
            if (decision.action() == FrameworkDeltaPlanner.Action.DOWNLOAD) {
                result.put(decision.entry().relativePath(), decision.entry().sha256());
            }
        }
        return result;
    }

    private static void copyDocument(final Path source, final Path target, final int limit) throws IOException {
        final BasicFileAttributes a = FrameworkUpdateCache.attributes(source);
        if (a == null || !a.isRegularFile() || a.isSymbolicLink() || a.size() > limit) {
            throw new IOException("Signed document missing or invalid");
        }
        Files.copy(source, target);
    }

    private static void deleteTree(final Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (var stream = Files.walk(directory)) {
            for (final Path p : stream.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
