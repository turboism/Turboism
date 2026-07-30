package dev.turboism.pluginmanagement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class PluginManagementPackageFixture {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private PluginManagementPackageFixture() { }

    static byte[] packageBytes(final String id, final String version) throws Exception {
        final String entrypoint = "example.Plugin";
        final byte[] jar = jar(descriptor(id, version, entrypoint), entrypoint.replace('.', '/') + ".class");
        final String jarHash = sha256(jar);
        final String descriptorHash = descriptorHash(jar);
        final Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("createdAt", Instant.parse("2026-07-30T00:00:00Z").toString());
        manifest.put("files", List.of(Map.of("path", "plugin/plugin.jar", "role", "PLUGIN_JAR",
            "sha256", jarHash, "size", jar.length)));
        manifest.put("format", "turboism.distribution.plugin-package");
        manifest.put("packageId", id);
        manifest.put("packageKind", "PLUGIN");
        manifest.put("pluginDescriptorPath", "plugin/plugin.jar!/META-INF/turboism/plugin.json");
        manifest.put("pluginDescriptorSha256", descriptorHash);
        manifest.put("schemaVersion", 1);
        manifest.put("version", version);
        manifest.put("packageHash", sha256(JSON.writeValueAsBytes(manifest)));
        return zip(Map.of(
            "META-INF/turboism/package.json", JSON.writeValueAsBytes(manifest),
            "plugin/plugin.jar", jar
        ));
    }

    static byte[] pluginJarBytes(final String id, final String version) throws Exception {
        final String entrypoint = "example.Plugin";
        return jar(descriptor(id, version, entrypoint), entrypoint.replace('.', '/') + ".class");
    }

    private static String descriptor(final String id, final String version, final String entrypoint) {
        return "{\"format\":\"turboism.plugin.meta\",\"schemaVersion\":2,\"id\":\"" + id
            + "\",\"name\":\"Example\",\"version\":\"" + version + "\",\"description\":\"Example\","
            + "\"entrypoints\":[\"" + entrypoint + "\"],\"turboismApi\":\"[0.1.0,0.2.0)\","
            + "\"authors\":[{\"name\":\"Test\"}],\"license\":\"Test\",\"website\":\"https://example.test\",\"resources\":[],"
            + "\"i18n\":{\"baseName\":\"META-INF/turboism/i18n/messages\",\"locales\":[]},"
            + "\"dependencies\":[],\"permissions\":[],\"capabilities\":[],"
            + "\"environment\":{\"requiresCubism\":false,\"ui\":\"none\"}}";
    }

    private static byte[] jar(final String descriptor, final String entrypointPath) throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(output)) {
            add(jar, "META-INF/turboism/plugin.json", descriptor.getBytes(StandardCharsets.UTF_8));
            add(jar, entrypointPath, new byte[]{0});
        }
        return output.toByteArray();
    }

    private static String descriptorHash(final byte[] jarBytes) throws Exception {
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(jarBytes))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (entry.getName().equals("META-INF/turboism/plugin.json")) return sha256(zip.readAllBytes());
            }
        }
        throw new IllegalStateException();
    }

    private static byte[] zip(final Map<String, byte[]> entries) throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (var entry : entries.entrySet()) add(zip, entry.getKey(), entry.getValue());
        }
        return output.toByteArray();
    }

    private static void add(final java.util.zip.ZipOutputStream zip, final String name, final byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static String sha256(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
