package dev.turboism.tests.distribution;

import dev.turboism.distribution.FrameworkInstallPlan;
import dev.turboism.distribution.FrameworkPackageInspector;
import dev.turboism.distribution.LocalFrameworkPackageInspector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameworkPackageInspectionIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void inspectsValidLocalFrameworkPackageWithoutMutation() throws Exception {
        byte[] runtime = jar("dev/turboism/bootstrap/Agent.class", "runtime");
        byte[] sdk = jar("dev/turboism/sdk/Plugin.class", "sdk");
        byte[] archive = frameworkZip(runtime, sdk, "");
        Path inputDirectory = tempDir.resolve("input");
        Files.createDirectories(inputDirectory);
        Path packagePath = inputDirectory.resolve("turboism.zip");
        Files.write(packagePath, archive);
        Path target = tempDir.resolve("target");
        Files.createDirectories(target);
        Path sentinel = target.resolve("keep.txt");
        Files.writeString(sentinel, "unchanged");
        byte[] before = Files.readAllBytes(packagePath);

        FrameworkPackageInspector.Result result = new LocalFrameworkPackageInspector().inspect(packagePath);

        FrameworkPackageInspector.Accepted accepted = assertInstanceOf(
            FrameworkPackageInspector.Accepted.class,
            result
        );
        FrameworkInstallPlan plan = accepted.plan();
        assertPlan(plan, archive, runtime, sdk);
        assertArrayEquals(before, Files.readAllBytes(packagePath));
        assertEquals("unchanged", Files.readString(sentinel));
        assertFalse(Files.exists(target.resolve("lib")));
        assertTrue(Files.list(inputDirectory).map(Path::getFileName).toList().contains(packagePath.getFileName()));
    }

    private static void assertPlan(FrameworkInstallPlan plan, byte[] archive,
                                   byte[] runtime, byte[] sdk) throws Exception {
        assertEquals(sha256(archive), plan.packageIdentity().sha256());
        assertEquals(archive.length, plan.packageIdentity().size());
        assertEquals("dev.turboism.framework", plan.packageIdentity().id());
        assertEquals("0.1.0", plan.packageIdentity().version());
        assertEquals(2, plan.files().size());
        assertEquals("runtime", plan.files().get(0).role());
        assertEquals("payload/runtime.jar", plan.files().get(0).archivePath());
        assertEquals("lib/runtime.jar", plan.files().get(0).installPath());
        assertEquals(sha256(runtime), plan.files().get(0).sha256());
        assertEquals(runtime.length, plan.files().get(0).size());
        assertEquals("sdk", plan.files().get(1).role());
        assertEquals("payload/sdk.jar", plan.files().get(1).archivePath());
        assertEquals(sha256(sdk), plan.files().get(1).sha256());
        assertEquals(sdk.length, plan.files().get(1).size());
        assertEquals("lib/sdk.jar", plan.files().get(1).installPath());
        assertEquals(FrameworkInstallPlan.Requirement.PREFLIGHT_REVALIDATION_REQUIRED,
            plan.requirement());
    }

    @Test
    void rejectsArtifactHashMismatch() throws Exception {
        byte[] runtime = jar("dev/turboism/bootstrap/Agent.class", "runtime");
        byte[] sdk = jar("dev/turboism/sdk/Plugin.class", "sdk");
        byte[] archive = frameworkZip(runtime, sdk, "");
        archive = replaceZipEntry(archive, "payload/runtime.jar", jar(
            "dev/turboism/bootstrap/Agent.class", "tampered"
        ));
        Path packagePath = tempDir.resolve("hash-mismatch.zip");
        Files.write(packagePath, archive);

        FrameworkPackageInspector.Rejected rejected = assertInstanceOf(
            FrameworkPackageInspector.Rejected.class,
            new LocalFrameworkPackageInspector().inspect(packagePath)
        );

        assertEquals("ARTIFACT_HASH_MISMATCH", rejected.problems().get(0).code());
        assertEquals("artifacts[0].sha256", rejected.problems().get(0).path());
    }

    @Test
    void rejectsUnknownManifestField() throws Exception {
        byte[] runtime = jar("dev/turboism/bootstrap/Agent.class", "runtime");
        byte[] sdk = jar("dev/turboism/sdk/Plugin.class", "sdk");
        Path packagePath = tempDir.resolve("unknown-field.zip");
        Files.write(packagePath, frameworkZip(runtime, sdk, ",\"unexpected\":true"));

        FrameworkPackageInspector.Rejected rejected = assertInstanceOf(
            FrameworkPackageInspector.Rejected.class,
            new LocalFrameworkPackageInspector().inspect(packagePath)
        );

        assertEquals("MANIFEST_UNKNOWN_FIELD", rejected.problems().get(0).code());
        assertEquals("unexpected", rejected.problems().get(0).path());
    }

    private static byte[] frameworkZip(byte[] runtime, byte[] sdk, String extraManifestField) throws Exception {
        String manifest = """
            {"format":"turboism.framework.package","schemaVersion":1,"kind":"framework",
             "id":"dev.turboism.framework","version":"0.1.0","apiVersion":"0.1.0","javaVersion":17,
             "artifacts":[
               {"role":"runtime","path":"payload/runtime.jar","installPath":"lib/runtime.jar","sha256":"%s","size":%d},
               {"role":"sdk","path":"payload/sdk.jar","installPath":"lib/sdk.jar","sha256":"%s","size":%d}
             ]%s}
            """.formatted(sha256(runtime), runtime.length, sha256(sdk), sdk.length, extraManifestField);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            add(zip, "META-INF/turboism/package.json", manifest.getBytes(StandardCharsets.UTF_8));
            add(zip, "payload/runtime.jar", runtime);
            add(zip, "payload/sdk.jar", sdk);
        }
        return output.toByteArray();
    }

    private static byte[] replaceZipEntry(byte[] archive, String name, byte[] replacement) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var input = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(archive));
             var zip = new ZipOutputStream(output)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                add(zip, entry.getName(), entry.getName().equals(name) ? replacement : input.readAllBytes());
            }
        }
        return output.toByteArray();
    }

    private static byte[] jar(String entryName, String content) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(output)) {
            JarEntry entry = new JarEntry(entryName);
            jar.putNextEntry(entry);
            jar.write(content.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return output.toByteArray();
    }

    private static void add(ZipOutputStream zip, String name, byte[] content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
