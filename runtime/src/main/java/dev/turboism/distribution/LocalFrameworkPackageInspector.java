package dev.turboism.distribution;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class LocalFrameworkPackageInspector implements FrameworkPackageInspector {
    private final PackageAccess access;

    public LocalFrameworkPackageInspector() {
        this(PackageAccess.FILE_SYSTEM);
    }

    LocalFrameworkPackageInspector(PackageAccess access) {
        this.access = Objects.requireNonNull(access, "access");
    }

    @Override
    public Result inspect(Path packagePath) {
        Objects.requireNonNull(packagePath, "packagePath");
        Path privateSnapshot = null;
        try {
            BasicFileAttributes initial = access.attributes(packagePath);
            ArchivePolicy.validatePackagePath(packagePath, initial);
            if (initial.size() > ArchivePolicy.PACKAGE_MAX) {
                throw ArchivePolicy.problem("PACKAGE_TOO_LARGE", "Package exceeds 64 MiB", packagePath.toString());
            }
            byte[] bytes = readBounded(packagePath);
            access.afterInitialHash(packagePath);
            requireUnchangedAttributes(packagePath, initial);
            privateSnapshot = createPrivateSnapshot(bytes);
            Snapshot snapshot = new Snapshot(initial, sha256(privateSnapshot), bytes.length);
            FrameworkInstallPlan plan = inspectArchive(privateSnapshot, snapshot);
            access.afterInspection(packagePath);
            requireUnchangedAttributes(packagePath, initial);
            return new Accepted(plan);
        } catch (DistributionValidationException exception) {
            return rejected(exception.code(), exception.getMessage(), exception.problemPath());
        } catch (IOException exception) {
            return rejected(DistributionErrors.PACKAGE_IO, "Package I/O failed", packagePath.toString());
        } catch (Exception exception) {
            return rejected("PACKAGE_INVALID", "Package validation failed", packagePath.toString());
        } finally {
            if (privateSnapshot != null) {
                try { Files.deleteIfExists(privateSnapshot); } catch (IOException ignored) { }
            }
        }
    }

    private byte[] readBounded(Path path) throws Exception {
        try (InputStream input = access.open(path)) {
            byte[] bytes = input.readNBytes((int) ArchivePolicy.PACKAGE_MAX + 1);
            if (bytes.length > ArchivePolicy.PACKAGE_MAX) {
                throw ArchivePolicy.problem("PACKAGE_TOO_LARGE", "Package exceeds 64 MiB", path.toString());
            }
            return bytes;
        }
    }

    private static Path createPrivateSnapshot(byte[] bytes) throws IOException {
        Path path;
        try {
            path = Files.createTempFile("turboism-package-inspection-", ".zip",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException exception) {
            path = Files.createTempFile("turboism-package-inspection-", ".zip");
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

    private FrameworkInstallPlan inspectArchive(Path snapshotPath, Snapshot snapshot) throws Exception {
        try (ZipFile zip = new ZipFile(snapshotPath.toFile())) {
            ArchivePolicy.validateArchive(zip);
            JsonNode manifest = manifest(zip);
            List<PlannedFile> files = new ArtifactInspector(zip).inspect(manifest);
            PackageIdentity identity = identity(snapshot, manifest);
            return new FrameworkInstallPlan(identity, files,
                FrameworkInstallPlan.Requirement.PREFLIGHT_REVALIDATION_REQUIRED);
        }
    }

    private static PackageIdentity identity(Snapshot snapshot, JsonNode manifest) {
        return new PackageIdentity(snapshot.hash(), snapshot.size(),
            manifest.path("id").textValue(), manifest.path("version").textValue(),
            manifest.path("apiVersion").textValue(), manifest.path("javaVersion").intValue());
    }

    private void requireUnchangedAttributes(Path path, BasicFileAttributes initial) throws Exception {
        BasicFileAttributes current = access.attributes(path);
        if (initial.size() != current.size()
            || !initial.lastModifiedTime().equals(current.lastModifiedTime())
            || !Objects.equals(initial.fileKey(), current.fileKey())) {
            throw ArchivePolicy.problem(DistributionErrors.PACKAGE_CHANGED,
                "Package changed during inspection", path.toString());
        }
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            input.transferTo(new java.io.OutputStream() {
                @Override public void write(int value) { digest.update((byte) value); }
                @Override public void write(byte[] bytes, int offset, int length) {
                    digest.update(bytes, offset, length);
                }
            });
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static JsonNode manifest(ZipFile zip) throws Exception {
        ZipEntry entry = zip.getEntry(ManifestReader.NAME);
        if (entry == null || entry.isDirectory()) {
            throw ArchivePolicy.problem("MANIFEST_MISSING", "Missing framework package manifest", ManifestReader.NAME);
        }
        try (InputStream input = zip.getInputStream(entry)) {
            return ManifestReader.read(input);
        }
    }

    private static Rejected rejected(String code, String message, String path) {
        return new Rejected(List.of(new DistributionProblem(code, message, path)));
    }

    private record Snapshot(BasicFileAttributes attributes, String hash, long size) {}
}
