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
