package dev.turboism.mapping.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CubismHostProbeCliTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void windowsConsumerSchemaMatchesTheEmittedWireContract() throws Exception {
        final ObjectNode report = CubismHostProbeCli.probe(temporaryDirectory.resolve("missing.jar"), null);
        Path root = Path.of("").toAbsolutePath().normalize();
        while (root != null && !Files.isRegularFile(root.resolve("settings.gradle.kts"))) {
            root = root.getParent();
        }
        assertNotNull(root, "the CLI consumer contract is checked against this source tree");
        final String consumer = Files.readString(root
            .resolve("packaging/windows-installer/cubism-launch-common.ps1"));
        final var schema = java.util.regex.Pattern.compile(
            "(?m)^\\$script:CubismProbeSchemaVersion\\s*=\\s*(\\d+)\\s*$").matcher(consumer);

        assertTrue(schema.find(), "Windows discovery must declare the probe schema it consumes");
        assertEquals(report.path("schemaVersion").asInt(), Integer.parseInt(schema.group(1)),
            "Windows discovery must not discard every current CLI result due to protocol drift");
    }

    @Test
    void reportsRejectedIdentityWithoutFabricatedVersion() throws Exception {
        final Path artifact = temporaryDirectory.resolve("Live2D_Cubism.jar");
        Files.write(artifact, new byte[] {1, 2, 3, 4});

        final ObjectNode report = CubismHostProbeCli.probe(artifact, null);

        assertEquals(CubismHostProbeCli.SCHEMA_VERSION, report.get("schemaVersion").asInt());
        assertEquals("cubism-host-compatibility", report.get("probe").asText());
        assertEquals("STATIC_PREFLIGHT", report.path("evidenceStage").asText());
        assertFalse(report.path("runtimeHooksVerified").asBoolean(true));
        assertEquals("REJECTED", report.get("status").asText());
        assertFalse(report.get("reason").asText().isBlank());
        assertFalse(report.get("runtimeAdmitted").asBoolean());
        assertTrue(report.has("probeStatus"));
        assertEquals("UNREADABLE", report.get("probeStatus").asText());
        // No identity fields are fabricated for a rejected host.
        assertEquals(0, report.get("identity").size());
        assertEquals(0, report.get("admittedCapabilities").size());
    }

    @Test
    void reportsDeclaredIdentityAndAdmissionForCoherentHost() throws Exception {
        // The embedded records pin real Cubism classes, so a synthetic jar
        // admits structurally as COMPATIBLE slices only where contracts match;
        // what this test pins is the JSON contract, not the verdict.
        final Path artifact = syntheticJar();
        final ObjectNode report = CubismHostProbeCli.probe(artifact, null);

        assertEquals("COMPATIBLE", report.get("status").asText());
        assertEquals("STATIC_PREFLIGHT", report.path("evidenceStage").asText());
        assertFalse(report.path("runtimeHooksVerified").asBoolean(true));
        assertFalse(report.path("runtimeAdmitted").asBoolean());
        assertEquals("5.3.99", report.get("identity").get("version").asText());
        assertEquals(503990001, report.get("identity").get("build").asInt());
        assertFalse(report.get("identity").get("artifactReviewed").asBoolean());
        assertFalse(report.get("identity").get("releaseReviewed").asBoolean());
        assertEquals(64, report.get("identity").get("artifact").get("sha256").asText().length());
        assertTrue(report.has("slices"));
        assertTrue(report.get("slices").size() > 0);
        for (final JsonNode slice : report.get("slices")) {
            assertTrue(slice.has("sliceId"));
            assertTrue(slice.has("status"));
            assertTrue(slice.has("reason"));
            assertTrue(slice.has("matchedCandidates"));
            if (slice.has("contract")) {
                assertTrue(slice.get("contract").has("sourceVersion"));
                assertTrue(slice.get("contract").get("compatible").asBoolean());
                assertTrue(slice.get("contract").has("capabilities"));
            }
        }
        // This fixture has no Cubism implementation classes to satisfy the
        // embedded selectors; it cannot prove any runtime capability.
        assertEquals(0, report.get("admittedCapabilities").size());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {503020001, 503020002})
    void releaseReviewDoesNotImplyArchiveReview(final int build) {
        final CubismHostIdentity identity = new CubismHostIdentity(
            "Live2D Cubism Editor", "5.3.02", java.util.Optional.empty(), build,
            "com/live2d/cubism/h", new HostArtifactDigest(1, "a".repeat(64))
        );
        final ObjectNode report = CubismHostProbeCli.render(CompatibilityResolution.of(
            CompatibilityResolution.Mode.COMPATIBLE, HostIdentityProbe.declared(identity),
            java.util.Map.of(), false, "no fixture selectors"
        ));

        assertEquals("5.3.02", report.path("identity").path("version").asText());
        assertEquals(build, report.path("identity").path("build").asInt());
        assertEquals(build == 503020001, report.path("identity").path("releaseReviewed").asBoolean());
        assertFalse(report.path("identity").path("artifactReviewed").asBoolean());
    }

    private Path syntheticJar() throws Exception {
        final Path classes = Files.createDirectories(temporaryDirectory.resolve("classes"));
        final Path sources = Files.createDirectories(temporaryDirectory.resolve("src/synthetic/host"));
        final Path source = sources.resolve("SyntheticHost.java");
        Files.writeString(source, """
            package synthetic.host;
            public final class SyntheticHost {
                public String value() { return "x"; }
            }
            """);
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-d", classes.toString(), source.toString()));

        final Path artifact = temporaryDirectory.resolve("synthetic-host.jar");
        try (java.util.jar.JarOutputStream output =
                 new java.util.jar.JarOutputStream(Files.newOutputStream(artifact))) {
            writeEntry(output, "com/live2d/cubism/CEAppCtrl.class", anchorClass());
            writeEntry(output, "com/live2d/cubism/h.class",
                declarationClass("com/live2d/cubism/h"));
            writeEntry(output, "synthetic/host/SyntheticHost.class",
                Files.readAllBytes(classes.resolve("synthetic/host/SyntheticHost.class")));
        }
        return artifact;
    }

    private static void writeEntry(
        final java.util.jar.JarOutputStream output,
        final String name,
        final byte[] bytes
    ) throws Exception {
        output.putNextEntry(new java.util.jar.JarEntry(name));
        output.write(bytes);
        output.closeEntry();
    }

    private static byte[] anchorClass() {
        final org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(org.objectweb.asm.Opcodes.V17,
            org.objectweb.asm.Opcodes.ACC_FINAL | org.objectweb.asm.Opcodes.ACC_SUPER,
            "com/live2d/cubism/CEAppCtrl", null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    /**
     * Minimal class assigning product/version/date/build to its own static
     * fields through ConstantValue attributes — the shape the release
     * detector reads without loading the class.
     */
    private static byte[] declarationClass(final String internalName) {
        final org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(org.objectweb.asm.Opcodes.V17,
            org.objectweb.asm.Opcodes.ACC_FINAL | org.objectweb.asm.Opcodes.ACC_SUPER,
            internalName, null, "java/lang/Object", null);
        writer.visitField(org.objectweb.asm.Opcodes.ACC_PRIVATE
                | org.objectweb.asm.Opcodes.ACC_STATIC | org.objectweb.asm.Opcodes.ACC_FINAL,
            "product", "Ljava/lang/String;", null, "Live2D Cubism Editor").visitEnd();
        writer.visitField(org.objectweb.asm.Opcodes.ACC_PRIVATE
                | org.objectweb.asm.Opcodes.ACC_STATIC | org.objectweb.asm.Opcodes.ACC_FINAL,
            "version", "Ljava/lang/String;", null, "5.3.99").visitEnd();
        writer.visitField(org.objectweb.asm.Opcodes.ACC_PRIVATE
                | org.objectweb.asm.Opcodes.ACC_STATIC | org.objectweb.asm.Opcodes.ACC_FINAL,
            "date", "Ljava/lang/String;", null, "2026/12/31").visitEnd();
        writer.visitField(org.objectweb.asm.Opcodes.ACC_PRIVATE
                | org.objectweb.asm.Opcodes.ACC_STATIC | org.objectweb.asm.Opcodes.ACC_FINAL,
            "build", "I", null, 503990001).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
