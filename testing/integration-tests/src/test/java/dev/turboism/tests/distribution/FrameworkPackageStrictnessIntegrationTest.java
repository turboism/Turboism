package dev.turboism.tests.distribution;

import dev.turboism.distribution.FrameworkPackageInspector;
import dev.turboism.distribution.LocalFrameworkPackageInspector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class FrameworkPackageStrictnessIntegrationTest {
    @TempDir Path tempDir;

    @Test void rejectsDuplicateJsonField() throws Exception {
        assertRejected(manifest("\"id\":\"duplicate\","), "MANIFEST_JSON_INVALID");
    }

    @Test void rejectsUnknownArtifactField() throws Exception {
        assertRejected(manifest("", ",\"extra\":true"), "ARTIFACT_UNKNOWN_FIELD");
    }

    @Test void rejectsBom() throws Exception {
        byte[] plain = manifest("");
        byte[] bom = new byte[plain.length + 3];
        bom[0] = (byte) 0xef; bom[1] = (byte) 0xbb; bom[2] = (byte) 0xbf;
        System.arraycopy(plain, 0, bom, 3, plain.length);
        assertRejected(bom, "MANIFEST_BOM");
    }

    @Test void rejectsTrailingJsonToken() throws Exception {
        byte[] plain = manifest("");
        byte[] token = " true".getBytes(StandardCharsets.UTF_8);
        byte[] trailing = java.util.Arrays.copyOf(plain, plain.length + token.length);
        System.arraycopy(token, 0, trailing, plain.length, token.length);
        assertRejected(trailing, "MANIFEST_JSON_INVALID");
    }

    @Test void rejectsSdkRuntimeContamination() throws Exception {
        byte[] runtime = FrameworkPackageFixtures.jar("dev/turboism/bootstrap/Agent.class", "runtime");
        byte[] sdk = FrameworkPackageFixtures.jar("dev/turboism/core/Manager.class", "core");
        Path input = tempDir.resolve("sdk-contamination.zip");
        Files.write(input, FrameworkPackageFixtures.frameworkZip(runtime, sdk));
        assertCode(input, "FRAMEWORK_CONTENT_CONTAMINATION");
    }

    private void assertRejected(byte[] manifest, String code) throws Exception {
        byte[] runtime = FrameworkPackageFixtures.jar("dev/turboism/bootstrap/Agent.class", "runtime");
        byte[] sdk = FrameworkPackageFixtures.jar("dev/turboism/sdk/Plugin.class", "sdk");
        Path input = tempDir.resolve("strict-" + System.nanoTime() + ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(input))) {
            add(zip, "META-INF/turboism/package.json", manifest);
            add(zip, "payload/runtime.jar", runtime);
            add(zip, "payload/sdk.jar", sdk);
        }
        assertCode(input, code);
    }

    private void assertCode(Path input, String code) {
        FrameworkPackageInspector.Rejected rejected = assertInstanceOf(
            FrameworkPackageInspector.Rejected.class, new LocalFrameworkPackageInspector().inspect(input));
        assertEquals(code, rejected.problems().get(0).code());
    }

    private static byte[] manifest(String top) throws Exception { return manifest(top, ""); }

    private static byte[] manifest(String top, String artifact) throws Exception {
        byte[] runtime = FrameworkPackageFixtures.jar("dev/turboism/bootstrap/Agent.class", "runtime");
        byte[] sdk = FrameworkPackageFixtures.jar("dev/turboism/sdk/Plugin.class", "sdk");
        return ("{" + top + "\"format\":\"turboism.framework.package\",\"schemaVersion\":1,"
            + "\"kind\":\"framework\",\"id\":\"dev.turboism.framework\",\"version\":\"0.1.0\","
            + "\"apiVersion\":\"0.1.0\",\"javaVersion\":17,\"artifacts\":["
            + artifact("runtime", "payload/runtime.jar", runtime, artifact) + ","
            + artifact("sdk", "payload/sdk.jar", sdk, "") + "]}").getBytes(StandardCharsets.UTF_8);
    }

    private static String artifact(String role, String path, byte[] bytes, String extra) throws Exception {
        return "{\"role\":\"" + role + "\",\"path\":\"" + path + "\",\"installPath\":\"lib/"
            + role + ".jar\",\"sha256\":\"" + FrameworkPackageFixtures.sha256(bytes)
            + "\",\"size\":" + bytes.length + extra + "}";
    }

    private static void add(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(name)); zip.write(bytes); zip.closeEntry();
    }
}
