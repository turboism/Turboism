package dev.turboism.distribution;

import com.fasterxml.jackson.databind.JsonNode;
import dev.turboism.sdk.plugin.PluginDescriptor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class LocalPluginPackageInspector implements PluginPackageInspector {
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
            if (initial.size() > ArchivePolicy.PACKAGE_MAX) fail("PACKAGE_TOO_LARGE", packagePath.toString());
            byte[] bytes = readBounded(packagePath);
            String expectedHash = sha256(bytes);
            access.afterInitialHash(packagePath);
            unchanged(packagePath, initial, expectedHash);
            snapshot = createPrivateSnapshot(bytes);
            PluginInstallPlan plan = inspectArchive(snapshot, bytes);
            access.afterInspection(packagePath);
            unchanged(packagePath, initial, expectedHash);
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

    private PluginInstallPlan inspectArchive(Path snapshot, byte[] packageBytes) throws Exception {
        try (ZipFile zip = new ZipFile(snapshot.toFile())) {
            ArchivePolicy.validateArchive(zip);
            JsonNode manifest = manifest(zip);
            PluginArtifactInspector.Inspected artifact = new PluginArtifactInspector(zip).inspect(manifest);
            PluginDescriptor descriptor = new PluginJarInspector().inspect(artifact.bytes(), artifact.file().archivePath());
            require(manifest.path("id").textValue().equals(descriptor.id())
                && manifest.path("version").textValue().equals(descriptor.version()),
                "PLUGIN_IDENTITY_MISMATCH", "id/version");
            require(strictApi(descriptor.turboismApi()), "PLUGIN_META_BAD_VERSION_RANGE", "turboismApi");
            PackageIdentity identity = new PackageIdentity(sha256(packageBytes), packageBytes.length,
                descriptor.id(), descriptor.version(), descriptor.turboismApi(), 17);
            return new PluginInstallPlan(identity, descriptor, List.of(artifact.file()),
                PluginInstallPlan.Requirement.INSPECTION_PREFLIGHT_REVALIDATION_REQUIRED);
        }
    }

    private byte[] readBounded(Path path) throws Exception {
        try (InputStream input = access.open(path)) {
            byte[] bytes = input.readNBytes((int) ArchivePolicy.PACKAGE_MAX + 1);
            if (bytes.length > ArchivePolicy.PACKAGE_MAX) fail("PACKAGE_TOO_LARGE", path.toString());
            return bytes;
        }
    }

    private void unchanged(Path path, BasicFileAttributes initial, String expectedHash) throws Exception {
        BasicFileAttributes current = access.attributes(path);
        require(initial.size() == current.size()
            && initial.lastModifiedTime().equals(current.lastModifiedTime())
            && Objects.equals(initial.fileKey(), current.fileKey())
            && expectedHash.equals(sha256(readBounded(path))),
            DistributionErrors.PACKAGE_CHANGED, path.toString());
    }

    private static Path createPrivateSnapshot(byte[] bytes) throws IOException {
        Path path;
        try {
            path = Files.createTempFile("turboism-plugin-inspection-", ".zip",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException exception) {
            path = Files.createTempFile("turboism-plugin-inspection-", ".zip");
        }
        boolean complete = false;
        try {
            Files.write(path, bytes);
            complete = true;
            return path;
        } finally {
            if (!complete) Files.deleteIfExists(path);
        }
    }

    private static JsonNode manifest(ZipFile zip) throws Exception {
        ZipEntry entry = zip.getEntry(PluginManifestReader.NAME);
        require(entry != null && !entry.isDirectory(), "MANIFEST_MISSING", PluginManifestReader.NAME);
        try (InputStream input = zip.getInputStream(entry)) { return PluginManifestReader.read(input); }
    }

    private static boolean strictApi(String value) {
        String v = "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)";
        return value != null && (value.matches(v) || value.matches("\\[" + v + "," + v + "\\)"));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void require(boolean valid, String code, String path) throws Exception {
        if (!valid) fail(code, path);
    }

    private static void fail(String code, String path) throws DistributionValidationException {
        throw ArchivePolicy.problem(code, "Invalid plugin package", path);
    }

    private static Rejected rejected(String code, String message, String path) {
        return new Rejected(List.of(new DistributionProblem(code, message, path)));
    }
}
