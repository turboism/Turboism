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

final class PluginArtifactInspector {
    private final ZipFile zip;

    PluginArtifactInspector(ZipFile zip) { this.zip = zip; }

    Inspected inspect(JsonNode manifest) throws Exception {
        List<PlannedFile> files = new ArrayList<>();
        byte[] main = null;
        Set<String> allowed = new HashSet<>();
        allowed.add(PluginManifestReader.NAME.toLowerCase(Locale.ROOT));
        JsonNode declared = manifest.path("files");
        for (int index = 0; index < declared.size(); index++) {
            JsonNode node = declared.get(index);
            String path = node.path("path").textValue();
            byte[] bytes = verifiedBytes(node, path, index);
            String role = node.path("role").textValue();
            files.add(new PlannedFile(role, path, path, node.path("sha256").textValue(), bytes.length));
            allowed.add(path.toLowerCase(Locale.ROOT));
            if ("PLUGIN_JAR".equals(role)) main = bytes;
        }
        exactInventory(allowed);
        return new Inspected(List.copyOf(files), main);
    }

    private byte[] verifiedBytes(JsonNode node, String path, int index) throws Exception {
        ZipEntry entry = zip.getEntry(path);
        require(entry != null && !entry.isDirectory(), "ARTIFACT_MISSING", field(index, "path"));
        byte[] bytes = read(entry);
        require(node.path("sha256").textValue().equals(sha256(bytes)),
            "ARTIFACT_HASH_MISMATCH", field(index, "sha256"));
        require(bytes.length == node.path("size").longValue(),
            "ARTIFACT_SIZE_MISMATCH", field(index, "size"));
        return bytes;
    }

    private byte[] read(ZipEntry entry) throws Exception {
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] bytes = input.readNBytes((int) PluginArchiveLimits.ENTRY_MAX + 1);
            require(bytes.length <= PluginArchiveLimits.ENTRY_MAX,
                "ARCHIVE_ENTRY_TOO_LARGE", entry.getName());
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

    private static String field(int index, String name) { return "files[" + index + "]." + name; }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void require(boolean valid, String code, String path) throws Exception {
        if (!valid) throw ArchivePolicy.problem(code, "Invalid plugin artifact", path);
    }

    record Inspected(List<PlannedFile> files, byte[] mainJar) {}
}
