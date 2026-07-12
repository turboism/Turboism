package dev.turboism.distribution;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class PackageTestFixtures {
    private PackageTestFixtures() {}

    static byte[] framework(String value) throws Exception {
        return framework(
            jarBytes("dev/turboism/bootstrap/Agent.class", value),
            jarBytes("dev/turboism/sdk/Plugin.class", value));
    }

    static byte[] framework(byte[] runtime, byte[] sdk) throws Exception {
        String manifest = "{\"format\":\"turboism.framework.package\",\"schemaVersion\":1,"
            + "\"kind\":\"framework\",\"id\":\"dev.turboism.framework\",\"version\":\"0.1.0\","
            + "\"apiVersion\":\"0.1.0\",\"javaVersion\":17,\"artifacts\":["
            + artifact("runtime", "payload/runtime.jar", runtime) + ","
            + artifact("sdk", "payload/sdk.jar", sdk) + "]}";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            add(zip, ManifestReader.NAME, manifest.getBytes(StandardCharsets.UTF_8));
            add(zip, "payload/runtime.jar", runtime);
            add(zip, "payload/sdk.jar", sdk);
        }
        return output.toByteArray();
    }

    static byte[] jarBytes(String name, String value) throws Exception {
        return jarBytes(name, value.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] jarBytes(String name, byte[] value) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(output)) {
            jar.putNextEntry(new JarEntry(name));
            jar.write(value);
            jar.closeEntry();
        }
        return output.toByteArray();
    }

    private static String artifact(String role, String path, byte[] bytes) throws Exception {
        return "{\"role\":\"" + role + "\",\"path\":\"" + path + "\",\"installPath\":\"lib/"
            + role + ".jar\",\"sha256\":\"" + sha256(bytes) + "\",\"size\":" + bytes.length + "}";
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void add(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }
}
