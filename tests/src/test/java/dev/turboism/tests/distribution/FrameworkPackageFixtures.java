package dev.turboism.tests.distribution;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class FrameworkPackageFixtures {
    private FrameworkPackageFixtures() {}

    static byte[] frameworkZip(byte[] runtime, byte[] sdk) throws Exception {
        return frameworkZip(runtime, sdk, "payload/runtime.jar", "lib/runtime.jar", "");
    }

    static byte[] frameworkZip(byte[] runtime, byte[] sdk, String runtimePath,
                               String runtimeInstallPath, String extra) throws Exception {
        String manifest = """
            {"format":"turboism.framework.package","schemaVersion":1,"kind":"framework",
             "id":"dev.turboism.framework","version":"0.1.0","apiVersion":"0.1.0","javaVersion":17,
             "artifacts":[
               {"role":"runtime","path":%s,"installPath":%s,"sha256":"%s","size":%d},
               {"role":"sdk","path":"payload/sdk.jar","installPath":"lib/sdk.jar","sha256":"%s","size":%d}
             ]%s}
            """.formatted(json(runtimePath), json(runtimeInstallPath), sha256(runtime), runtime.length,
                sha256(sdk), sdk.length, extra);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            add(zip, "META-INF/turboism/package.json", manifest.getBytes(StandardCharsets.UTF_8));
            add(zip, runtimePath, runtime);
            add(zip, "payload/sdk.jar", sdk);
        }
        return output.toByteArray();
    }

    static byte[] jar(String... nameContentPairs) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(output)) {
            for (int i = 0; i < nameContentPairs.length; i += 2) {
                jar.putNextEntry(new JarEntry(nameContentPairs[i]));
                jar.write(nameContentPairs[i + 1].getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static String json(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '\\' || character == '\"') result.append('\\').append(character);
            else if (character < 0x20) result.append("\\u%04x".formatted((int) character));
            else result.append(character);
        }
        return result.append('\"').toString();
    }

    static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private static void add(ZipOutputStream zip, String name, byte[] content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }
}
