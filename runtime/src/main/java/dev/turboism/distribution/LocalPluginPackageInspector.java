package dev.turboism.distribution;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Filesystem-backed {@link PluginPackageInspector} for plugin packages held on local disk.
 *
 * <p>Every operation copies the package into a private temporary snapshot (owner-only permissions
 * where the platform supports them) and validates only that copy, so a source file mutated
 * concurrently cannot change what was inspected. The source's attributes - size, last-modified time,
 * and file key - are re-checked after hashing and again after inspection; any drift rejects the
 * package. Archive expansion is bounded by {@code PluginArchiveLimits}. Snapshots are always
 * deleted, including on failure.
 */
public final class LocalPluginPackageInspector implements PluginPackageInspector {
    private static final StrictZipArchive.Limits LIMITS = new StrictZipArchive.Limits(
        PluginArchiveLimits.RAW_MAX, PluginArchiveLimits.ENTRY_MAX, PluginArchiveLimits.TOTAL_MAX,
        PluginArchiveLimits.ENTRY_COUNT_MAX, PluginArchiveLimits.RATIO_MAX);
    private final PackageAccess access;

    public LocalPluginPackageInspector() { this(PackageAccess.FILE_SYSTEM); }

    LocalPluginPackageInspector(PackageAccess access) {
        this.access = Objects.requireNonNull(access, "access");
    }

    @Override public Result inspect(Path packagePath) {
        Objects.requireNonNull(packagePath, "packagePath");
        Path snapshot = null;
        try {
            BasicFileAttributes initial = access.attributes(packagePath);
            ArchivePolicy.validatePackagePath(packagePath, initial);
            require(initial.size() <= PluginArchiveLimits.RAW_MAX,
                "PACKAGE_TOO_LARGE", packagePath.toString());
            snapshot = privateSnapshot();
            RawObservation raw = snapshot(packagePath, snapshot);
            access.afterInitialHash(packagePath);
            unchanged(packagePath, initial);
            PluginInstallPlan plan = inspectArchive(snapshot, raw);
            access.afterInspection(packagePath);
            unchanged(packagePath, initial);
            return new Accepted(plan);
        } catch (DistributionValidationException exception) {
            return rejected(exception.code(), exception.getMessage(), exception.problemPath());
        } catch (IOException exception) {
            return rejected(DistributionErrors.PACKAGE_IO, "Package I/O failed", packagePath.toString());
        } catch (Exception exception) {
            return rejected("PACKAGE_INVALID", "Package validation failed", packagePath.toString());
        } finally {
            if (snapshot != null) try { Files.deleteIfExists(snapshot); } catch (IOException ignored) { }
        }
    }

    /**
     * Inspects a package and, if it is valid, extracts its plugin JAR into a staging directory.
     *
     * <p>Performs the same validation as {@link #inspect(Path)}, then writes the planned
     * {@code PLUGIN_JAR} entry to a confined file inside {@code stagingDirectory} named
     * {@code <pluginId>-<rawArchiveSha256>.jar}, verifying the extracted size and digest against the
     * plan before publishing it. A staged file that fails either check is cleaned up and never
     * published.
     *
     * <p>Never throws: failures surface as {@link PreparationRejected}, carrying the validation code
     * when one is available and {@code PLUGIN_STAGE_FAILED} otherwise.
     *
     * @param packagePath the package archive to inspect; must not be {@code null}
     * @param stagingDirectory directory the JAR is staged into; must not be {@code null}
     * @return {@link Prepared} with the plan and the staged JAR path, or {@link PreparationRejected}
     * @throws NullPointerException if either argument is {@code null}
     */
    public Preparation prepare(final Path packagePath, final Path stagingDirectory) {
        Objects.requireNonNull(packagePath, "packagePath");
        Objects.requireNonNull(stagingDirectory, "stagingDirectory");
        Path snapshot = null;
        try {
            final BasicFileAttributes initial = access.attributes(packagePath);
            ArchivePolicy.validatePackagePath(packagePath, initial);
            require(initial.size() <= PluginArchiveLimits.RAW_MAX,
                "PACKAGE_TOO_LARGE", packagePath.toString());
            snapshot = privateSnapshot();
            final RawObservation raw = snapshot(packagePath, snapshot);
            access.afterInitialHash(packagePath);
            unchanged(packagePath, initial);
            final PluginInstallPlan plan = inspectArchive(snapshot, raw);
            access.afterInspection(packagePath);
            unchanged(packagePath, initial);
            final Path staged = stagePluginJar(snapshot, stagingDirectory, plan);
            return new Prepared(new PreparedPluginPackage(plan, staged));
        } catch (DistributionValidationException exception) {
            return new PreparationRejected(exception.code());
        } catch (Exception exception) {
            return new PreparationRejected("PLUGIN_STAGE_FAILED");
        } finally {
            if (snapshot != null) try { Files.deleteIfExists(snapshot); } catch (IOException ignored) { }
        }
    }

    private PluginInstallPlan inspectArchive(Path snapshot, RawObservation raw) throws Exception {
        try (StrictZipArchive archive = StrictZipArchive.open(snapshot, LIMITS)) {
            StrictZipArchive.Entry manifestEntry = archive.entry(PluginManifestReader.NAME);
            require(manifestEntry != null && !manifestEntry.directory(),
                "MANIFEST_MISSING", PluginManifestReader.NAME);
            JsonNode manifest;
            try (InputStream input = archive.stream(manifestEntry)) {
                manifest = PluginManifestReader.read(input);
            }
            archive.consume(manifestEntry, null);
            PluginArtifactInspector.Inspected artifacts = new PluginArtifactInspector(archive).inspect(manifest);
            PluginJarInspector.Inspected plugin = artifacts.plugin();
            verifyManifest(manifest, plugin);
            PluginPackageIdentity identity = identity(manifest, raw);
            return new PluginInstallPlan(identity, plugin.descriptor(), plugin.descriptorSha256(),
                artifacts.files(), PluginInstallPlan.Requirement.INSPECTION_PREFLIGHT_REVALIDATION_REQUIRED);
        }
    }

    private static void verifyManifest(JsonNode manifest, PluginJarInspector.Inspected plugin) throws Exception {
        PluginDescriptorSnapshot descriptor = plugin.descriptor();
        require(manifest.path("packageId").textValue().equals(descriptor.id())
            && manifest.path("version").textValue().equals(descriptor.version()),
            "PLUGIN_IDENTITY_MISMATCH", "packageId/version");
        require(manifest.path("pluginDescriptorSha256").textValue().equals(plugin.descriptorSha256()),
            "PLUGIN_DESCRIPTOR_HASH_MISMATCH", "pluginDescriptorSha256");
        require(strictApi(descriptor.turboismApi()), "PLUGIN_META_BAD_VERSION_RANGE", "turboismApi");
    }

    private static PluginPackageIdentity identity(JsonNode manifest, RawObservation raw) {
        return new PluginPackageIdentity(manifest.path("packageHash").textValue(),
            manifest.path("packageId").textValue(), manifest.path("version").textValue(),
            raw.sha256(), raw.size());
    }

    private RawObservation snapshot(Path source, Path target) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long size = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = access.open(source); OutputStream output = Files.newOutputStream(target)) {
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read == 0) continue;
                require(size <= PluginArchiveLimits.RAW_MAX - read, "PACKAGE_TOO_LARGE", source.toString());
                size += read;
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        return new RawObservation(HexFormat.of().formatHex(digest.digest()), size);
    }

    private void unchanged(Path path, BasicFileAttributes initial) throws Exception {
        BasicFileAttributes current = access.attributes(path);
        require(current.isRegularFile()
            && initial.size() == current.size()
            && initial.lastModifiedTime().equals(current.lastModifiedTime())
            && Objects.equals(initial.fileKey(), current.fileKey()),
            DistributionErrors.PACKAGE_CHANGED, path.toString());
    }

    private static Path privateSnapshot() throws IOException {
        try {
            return Files.createTempFile("turboism-plugin-inspection-", ".zip",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException exception) {
            return Files.createTempFile("turboism-plugin-inspection-", ".zip");
        }
    }

    private static Path stagePluginJar(
        final Path snapshot,
        final Path requestedDirectory,
        final PluginInstallPlan plan
    ) throws Exception {
        final PlannedFile planned = plan.files().stream()
            .filter(file -> file.role().equals("PLUGIN_JAR"))
            .findFirst().orElseThrow();
        final String targetName = plan.descriptor().id() + "-"
            + plan.packageIdentity().rawArchiveSha256() + ".jar";
        final ConfinedStagingFiles.Target staged = ConfinedStagingFiles.create(requestedDirectory, targetName);
        try (StrictZipArchive archive = StrictZipArchive.open(snapshot, LIMITS)) {
            final StrictZipArchive.Entry entry = archive.entry(planned.archivePath());
            require(entry != null && !entry.directory(), "ARTIFACT_MISSING", planned.archivePath());
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (OutputStream output = new DigestingOutputStream(staged.output(), digest)) {
                final StrictZipArchive.Observation observation = archive.consume(entry, output);
                require(observation.size() == planned.size(), "ARTIFACT_SIZE_MISMATCH", planned.archivePath());
            }
            require(HexFormat.of().formatHex(digest.digest()).equals(planned.sha256()),
                "ARTIFACT_HASH_MISMATCH", planned.archivePath());
            staged.publish();
            return staged.target();
        } catch (Exception failure) {
            staged.cleanup();
            throw failure;
        }
    }

    /** Outcome of {@link #prepare(Path, Path)}: either a staged package or a rejection code. */
    public sealed interface Preparation permits Prepared, PreparationRejected { }

    /**
     * A package that passed inspection and whose plugin JAR is staged on disk.
     *
     * @param value the validated plan paired with the path of the published staged JAR
     */
    public record Prepared(PreparedPluginPackage value) implements Preparation { }

    /**
     * A package that could not be prepared; nothing was staged.
     *
     * @param code the validation code that caused the rejection, or {@code PLUGIN_STAGE_FAILED} for
     *             an unexpected failure
     */
    public record PreparationRejected(String code) implements Preparation { }

    private static final class DigestingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final MessageDigest digest;
        private DigestingOutputStream(final OutputStream delegate, final MessageDigest digest) {
            this.delegate = delegate;
            this.digest = digest;
        }
        @Override public void write(final int value) throws IOException {
            delegate.write(value);
            digest.update((byte) value);
        }
        @Override public void write(final byte[] bytes, final int offset, final int length) throws IOException {
            delegate.write(bytes, offset, length);
            digest.update(bytes, offset, length);
        }
        @Override public void close() throws IOException { delegate.close(); }
    }

    private static boolean strictApi(String value) {
        String version = "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)";
        return value != null && (value.matches(version)
            || value.matches("\\[" + version + "," + version + "\\)"));
    }

    private static void require(boolean valid, String code, String path) throws Exception {
        if (!valid) throw ArchivePolicy.problem(code, "Invalid plugin package", path);
    }

    private static Rejected rejected(String code, String message, String path) {
        return new Rejected(List.of(new DistributionProblem(code, message, path)));
    }

    private record RawObservation(String sha256, long size) {}
}
