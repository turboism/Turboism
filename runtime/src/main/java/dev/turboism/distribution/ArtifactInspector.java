package dev.turboism.distribution;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ArtifactInspector {
    private static final Set<String> ROLES = Set.of("runtime", "sdk");
    private final ZipFile zip;

    ArtifactInspector(ZipFile zip) { this.zip = zip; }

    List<PlannedFile> inspect(JsonNode manifest) throws Exception {
        List<PlannedFile> files = new ArrayList<>();
        Set<String> roles = new HashSet<>();
        Set<String> archivePaths = new HashSet<>();
        Set<String> installPaths = new HashSet<>();
        for (int i = 0; i < manifest.path("artifacts").size(); i++) {
            JsonNode node = manifest.path("artifacts").get(i);
            files.add(inspectOne(node, i, roles, archivePaths, installPaths));
        }
        require(files.size() == 2 && roles.equals(ROLES), "ARTIFACT_ROLES_INVALID",
            "Framework package requires exactly runtime and sdk roles", "artifacts");
        exactInventory(archivePaths);
        return files;
    }

    private PlannedFile inspectOne(JsonNode node, int index, Set<String> roles,
                                   Set<String> archives, Set<String> installs) throws Exception {
        String base = "artifacts[" + index + "]";
        String role = requiredText(node, "role", base);
        require(ROLES.contains(role) && roles.add(role), "ARTIFACT_ROLES_INVALID", "Invalid or duplicate role", base + ".role");
        String archive = requiredText(node, "path", base);
        String install = requiredText(node, "installPath", base);
        ArchivePolicy.safeRelative(archive, "ARCHIVE_PATH_UNSAFE", base + ".path");
        ArchivePolicy.safeRelative(install, "INSTALL_PATH_UNSAFE", base + ".installPath");
        require(archives.add(archive.toLowerCase(Locale.ROOT)), "ARCHIVE_PATH_COLLISION", "Artifact archive path collision", base + ".path");
        require(installs.add(install.toLowerCase(Locale.ROOT)), "INSTALL_PATH_COLLISION", "Artifact install path collision", base + ".installPath");
        String expectedHash = requiredText(node, "sha256", base);
        require(expectedHash.matches("[0-9a-f]{64}"), "ARTIFACT_HASH_INVALID", "sha256 must be lowercase hex", base + ".sha256");
        require(node.path("size").isIntegralNumber() && node.path("size").longValue() >= 0,
            "ARTIFACT_SIZE_INVALID", "size must be non-negative integer", base + ".size");
        ZipEntry entry = zip.getEntry(archive);
        require(entry != null && !entry.isDirectory(), "ARTIFACT_MISSING", "Declared artifact is missing", base + ".path");
        byte[] bytes = readBounded(entry, base);
        require(sha256(bytes).equals(expectedHash), "ARTIFACT_HASH_MISMATCH", "Artifact hash mismatch", base + ".sha256");
        require(bytes.length == node.path("size").longValue(), "ARTIFACT_SIZE_MISMATCH", "Artifact size mismatch", base + ".size");
        new NestedJarInspector().inspect(role, bytes, base);
        return new PlannedFile(role, archive, install, expectedHash, bytes.length);
    }

    private byte[] readBounded(ZipEntry entry, String base) throws Exception {
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] bytes = input.readNBytes((int) ArchivePolicy.ENTRY_MAX + 1);
            require(bytes.length <= ArchivePolicy.ENTRY_MAX, "ARCHIVE_ENTRY_TOO_LARGE", "Artifact exceeds limit", base + ".path");
            return bytes;
        }
    }

    private void exactInventory(Set<String> declared) throws DistributionValidationException {
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!entry.isDirectory() && !entry.getName().equals(ManifestReader.NAME)) {
                require(declared.contains(entry.getName().toLowerCase(Locale.ROOT)), "ARCHIVE_UNDECLARED_FILE",
                    "Archive contains undeclared regular file", entry.getName());
            }
        }
    }

    private static String requiredText(JsonNode node, String field, String base) throws DistributionValidationException {
        require(node.path(field).isTextual() && !node.path(field).textValue().isBlank(),
            "ARTIFACT_FIELD_INVALID", field + " must be non-empty text", base + "." + field);
        return node.path(field).textValue();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void require(boolean valid, String code, String message, String path)
        throws DistributionValidationException {
        if (!valid) throw ArchivePolicy.problem(code, message, path);
    }
}
