package dev.turboism.distribution;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class PluginArtifactInspector {
    private final ZipFile zip;

    PluginArtifactInspector(ZipFile zip) { this.zip = zip; }

    Inspected inspect(JsonNode manifest) throws Exception {
        JsonNode node = manifest.path("artifacts").get(0);
        String role = text(node, "role");
        require("plugin".equals(role), "ARTIFACT_ROLES_INVALID", "artifacts[0].role");
        String archive = text(node, "path");
        String install = text(node, "installPath");
        ArchivePolicy.safeRelative(archive, "ARCHIVE_PATH_UNSAFE", "artifacts[0].path");
        ArchivePolicy.safeRelative(install, "INSTALL_PATH_UNSAFE", "artifacts[0].installPath");
        require("payload/plugin.jar".equals(archive), "ARTIFACT_PATH_INVALID", "artifacts[0].path");
        require("plugin.jar".equals(install), "INSTALL_PATH_INVALID", "artifacts[0].installPath");
        String expected = text(node, "sha256");
        require(expected.matches("[0-9a-f]{64}"), "ARTIFACT_HASH_INVALID", "artifacts[0].sha256");
        require(node.path("size").isIntegralNumber() && node.path("size").longValue() >= 0,
            "ARTIFACT_SIZE_INVALID", "artifacts[0].size");
        ZipEntry entry = zip.getEntry(archive);
        require(entry != null && !entry.isDirectory(), "ARTIFACT_MISSING", "artifacts[0].path");
        byte[] bytes = read(entry);
        require(expected.equals(sha256(bytes)), "ARTIFACT_HASH_MISMATCH", "artifacts[0].sha256");
        require(bytes.length == node.path("size").longValue(), "ARTIFACT_SIZE_MISMATCH", "artifacts[0].size");
        exactInventory(Set.of(PluginManifestReader.NAME.toLowerCase(Locale.ROOT), archive));
        return new Inspected(new PlannedFile(role, archive, install, expected, bytes.length), bytes);
    }

    private byte[] read(ZipEntry entry) throws Exception {
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] bytes = input.readNBytes((int) ArchivePolicy.ENTRY_MAX + 1);
            require(bytes.length <= ArchivePolicy.ENTRY_MAX, "ARCHIVE_ENTRY_TOO_LARGE", entry.getName());
            return bytes;
        }
    }

    private void exactInventory(Set<String> allowed) throws Exception {
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!entry.isDirectory()) require(allowed.contains(entry.getName().toLowerCase(Locale.ROOT)),
                "ARCHIVE_UNDECLARED_FILE", entry.getName());
        }
    }

    private static String text(JsonNode node, String field) throws Exception {
        require(node.path(field).isTextual() && !node.path(field).textValue().isBlank(),
            "ARTIFACT_FIELD_INVALID", "artifacts[0]." + field);
        return node.path(field).textValue();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void require(boolean valid, String code, String path) throws Exception {
        if (!valid) throw ArchivePolicy.problem(code, "Invalid plugin artifact", path);
    }

    record Inspected(PlannedFile file, byte[] bytes) {}
}
