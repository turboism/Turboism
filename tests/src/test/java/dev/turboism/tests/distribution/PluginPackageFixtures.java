package dev.turboism.tests.distribution;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class PluginPackageFixtures {
    static final String ID = "dev.turboism.plugin.sample";
    static final String VERSION = "0.1.0";
    static final String ENTRYPOINT = "dev.turboism.plugin.sample.SamplePlugin";

    private PluginPackageFixtures() {}

    static byte[] valid() throws Exception {
        return packageWith(jar(descriptor(ID, VERSION, "[0.1.0,0.2.0)"),
            ENTRYPOINT.replace('.', '/') + ".class", "class"), ID, VERSION, "");
    }

    static byte[] packageWith(byte[] jar, String id, String version, String extra) throws Exception {
        String manifest = "{\"format\":\"turboism.plugin.package\",\"schemaVersion\":1,"
            + "\"kind\":\"plugin\",\"id\":\"" + id + "\",\"version\":\"" + version + "\","
            + "\"artifacts\":[{\"role\":\"plugin\",\"path\":\"payload/plugin.jar\","
            + "\"installPath\":\"plugin.jar\",\"sha256\":\"" + sha256(jar)
            + "\",\"size\":" + jar.length + "}]" + extra + "}";
        return zip(new String[][]{
            {"META-INF/turboism/package.json", manifest},
            {"payload/plugin.jar", HexFormat.of().formatHex(jar)}
        }, true);
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
        return "{\"format\":\"turboism.plugin.meta\",\"schemaVersion\":1,\"id\":\"" + id
            + "\",\"name\":\"Sample\",\"version\":\"" + version + "\",\"entrypoints\":{\"plugin\":\""
            + ENTRYPOINT + "\"},\"turboismApi\":\"" + api + "\"}";
    }

    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static byte[] zip(String[][] entries, boolean hexPayload) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (String[] entry : entries) {
                byte[] bytes = hexPayload && entry[0].equals("payload/plugin.jar")
                    ? HexFormat.of().parseHex(entry[1]) : entry[1].getBytes(StandardCharsets.UTF_8);
                add(zip, entry[0], bytes);
            }
        }
        return output.toByteArray();
    }

    private static void add(java.util.zip.ZipOutputStream zip, String name, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }
}
