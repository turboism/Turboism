package dev.turboism.tests.distribution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class PluginPackageFixtures {
    static final String ID = "dev.turboism.plugin.sample";
    static final String VERSION = "0.1.0";
    static final String ENTRYPOINT = "dev.turboism.plugin.sample.SamplePlugin";
    private static final ObjectMapper CANONICAL = new ObjectMapper()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private PluginPackageFixtures() {}

    static byte[] valid() throws Exception {
        return packageWith(jar(descriptor(ID, VERSION, "[0.1.0,0.2.0)"),
            ENTRYPOINT.replace('.', '/') + ".class", "class"), ID, VERSION, List.of());
    }

    static byte[] withLibraries(byte[] main, Map<String, byte[]> libraries) throws Exception {
        return packageWith(main, ID, VERSION, new ArrayList<>(libraries.entrySet()));
    }

    static byte[] packageWith(byte[] main, String id, String version) throws Exception {
        return packageWith(main, id, version, List.of());
    }

    static byte[] packageWith(byte[] main, String id, String version,
                              List<Map.Entry<String, byte[]>> libraries) throws Exception {
        List<FileSpec> files = new ArrayList<>();
        files.add(new FileSpec("plugin/plugin.jar", main, "PLUGIN_JAR"));
        libraries.stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
            files.add(new FileSpec("plugin/lib/" + entry.getKey(), entry.getValue(), "PLUGIN_LIBRARY")));
        Map<String, Object> manifest = manifest(id, version, files);
        manifest.put("packageHash", sha256(CANONICAL.writeValueAsBytes(manifest)));
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/turboism/package.json", CANONICAL.writeValueAsBytes(manifest));
        files.forEach(file -> entries.put(file.path(), file.bytes()));
        return zip(entries);
    }

    static byte[] legacy() throws Exception {
        byte[] main = jar(descriptor(ID, VERSION, "0.1.0"));
        String json = "{\"format\":\"turboism.plugin.package\",\"schemaVersion\":1,"
            + "\"kind\":\"plugin\",\"id\":\"" + ID + "\",\"version\":\"" + VERSION + "\","
            + "\"artifacts\":[{\"role\":\"plugin\",\"path\":\"payload/plugin.jar\","
            + "\"installPath\":\"plugin.jar\",\"sha256\":\"" + sha256(main)
            + "\",\"size\":" + main.length + "}]}";
        return zip(Map.of("META-INF/turboism/package.json", json.getBytes(StandardCharsets.UTF_8),
            "payload/plugin.jar", main));
    }

    static byte[] jar(String descriptor, String... nameContentPairs) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(output)) {
            add(jar, "META-INF/turboism/plugin.json", descriptor.getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < nameContentPairs.length; i += 2) {
                add(jar, nameContentPairs[i], nameContentPairs[i + 1].getBytes(StandardCharsets.UTF_8));
            }
        }
        return output.toByteArray();
    }

    static byte[] jarEntries(String... nameContentPairs) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(output)) {
            for (int i = 0; i < nameContentPairs.length; i += 2) {
                add(jar, nameContentPairs[i], nameContentPairs[i + 1].getBytes(StandardCharsets.UTF_8));
            }
        }
        return output.toByteArray();
    }

    static String descriptor(String id, String version, String api) {
        return descriptor(id, version, api, "");
    }

    static String descriptorWithEntrypoint(String id, String version, String api, String entrypoint) {
        return descriptor(id, version, api, entrypoint, "");
    }

    static String descriptor(String id, String version, String api, String extra) {
        return descriptor(id, version, api, ENTRYPOINT, extra);
    }

    private static String descriptor(String id, String version, String api, String entrypoint, String extra) {
        return "{\"format\":\"turboism.plugin.meta\",\"schemaVersion\":2,\"id\":\"" + id
            + "\",\"name\":\"Sample\",\"version\":\"" + version + "\",\"entrypoints\":[\""
            + entrypoint + "\"],\"turboismApi\":\"" + api
            + "\",\"authors\":[{\"name\":\"Turboism Contributors\"}]"
            + ",\"website\":\"https://turboism.dev\",\"resources\":[]"
            + ",\"i18n\":{\"baseName\":\"META-INF/turboism/i18n/messages\",\"locales\":[]}" + extra + "}";
    }

    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Map<String, Object> manifest(String id, String version, List<FileSpec> files) throws Exception {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("createdAt", Instant.parse("2026-07-12T18:00:00Z").toString());
        List<Map<String, Object>> records = new ArrayList<>();
        files.forEach(file -> {
            try {
                records.add(Map.of("path", file.path(), "role", file.role(),
                    "sha256", sha256(file.bytes()), "size", file.bytes().length));
            } catch (Exception exception) { throw new IllegalStateException(exception); }
        });
        manifest.put("files", records);
        manifest.put("format", "turboism.distribution.plugin-package");
        manifest.put("packageId", id);
        manifest.put("packageKind", "PLUGIN");
        manifest.put("pluginDescriptorPath", "plugin/plugin.jar!/META-INF/turboism/plugin.json");
        byte[] descriptor = descriptorBytes(files.get(0).bytes());
        manifest.put("pluginDescriptorSha256", sha256(descriptor));
        manifest.put("schemaVersion", 1);
        manifest.put("version", version);
        return manifest;
    }

    private static byte[] descriptorBytes(byte[] jarBytes) throws Exception {
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(jarBytes))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (entry.getName().equals("META-INF/turboism/plugin.json")) return zip.readAllBytes();
            }
        }
        throw new IllegalArgumentException("descriptor missing");
    }

    private static byte[] zip(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (var entry : entries.entrySet()) add(zip, entry.getKey(), entry.getValue());
        }
        return output.toByteArray();
    }

    static byte[] zipEntries(Map<String, byte[]> entries) throws Exception { return zip(entries); }

    static byte[] clearUtf8Flags(byte[] source) { return rewriteFlags(source, flags -> flags & ~0x0800); }

    static byte[] addFlag(byte[] source, int flag) { return rewriteFlags(source, flags -> flags | flag); }

    private static byte[] rewriteFlags(byte[] source, java.util.function.IntUnaryOperator change) {
        final byte[] bytes = source.clone();
        for (int at = 0; at <= bytes.length - 4; at++) {
            final boolean local = uint(bytes, at) == 0x04034b50L;
            final boolean central = uint(bytes, at) == 0x02014b50L;
            if (local || central) {
                final int flagAt = at + (local ? 6 : 8);
                putShort(bytes, flagAt, change.applyAsInt(ushort(bytes, flagAt)));
            }
        }
        return bytes;
    }

    private static int ushort(byte[] bytes, int at) {
        return (bytes[at] & 255) | (bytes[at + 1] & 255) << 8;
    }

    private static long uint(byte[] bytes, int at) {
        return (bytes[at] & 255L) | (bytes[at + 1] & 255L) << 8
            | (bytes[at + 2] & 255L) << 16 | (bytes[at + 3] & 255L) << 24;
    }

    private static void putShort(byte[] bytes, int at, int value) {
        bytes[at] = (byte) value;
        bytes[at + 1] = (byte) (value >>> 8);
    }

    private static void add(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
        ZipEntry entry = new ZipEntry(name);
        entry.setComment(null);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private record FileSpec(String path, byte[] bytes, String role) {}
}
