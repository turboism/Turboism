package dev.turboism.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class PreviewRuntimeEditorReleaseAdmissionTest {

    @TempDir
    Path tempDir;

    @Test
    void exactArtifactAndDeclarationMustAgreeBeforeRuntimeStartup() throws Exception {
        final Path declared5303 = editorJar("5.3.03", 503030001);

        final IllegalStateException unreviewedArtifact = assertThrows(
                IllegalStateException.class, () -> PreviewRuntime.requireReviewedEditorRelease(declared5303));
        assertEquals(
                "Cubism host artifact is not an exact reviewed identity; admission failed closed",
                unreviewedArtifact.getMessage());
    }

    @Test
    void releaseMismatchFailsClosedEvenWhenDigestLookupIsReviewed() {
        final String reviewed = ReviewedHostArtifacts.cubismVersionOf(ReviewedHostArtifacts.CUBISM_5_3_03)
                .orElseThrow();
        final String declared = "5.3.02";

        final IllegalStateException mismatch = assertThrows(
                IllegalStateException.class, () -> PreviewRuntime.requireReleaseAgreement(reviewed, declared));
        assertEquals("Cubism host release/artifact mismatch; admission failed closed", mismatch.getMessage());
    }

    @Test
    void exactReviewed5303AgreementOpensFullRuntimeAdmission() {
        assertEquals("5.3.03", PreviewRuntime.requireReleaseAgreement("5.3.03", "5.3.03"));
        assertTrue(ReviewedHostArtifacts.admitsFullRuntime("5.3.03"));
    }

    @Test
    void compatibilityAdmissionRejectsAnArtifactChangedSinceIdentityProbe() throws Exception {
        final Path artifact = editorJar("5.3.02", 503020001);
        final var identity = new dev.turboism.mapping.verification.CubismHostIdentity(
                "Live2D Cubism Editor",
                "5.3.02",
                java.util.Optional.empty(),
                503020001,
                "com/live2d/cubism/h",
                dev.turboism.mapping.verification.HostArtifactDigest.from(artifact));
        final var resolution = dev.turboism.mapping.verification.CompatibilityResolution.of(
                dev.turboism.mapping.verification.CompatibilityResolution.Mode.COMPATIBLE,
                new dev.turboism.mapping.verification.HostIdentityProbe(
                        dev.turboism.mapping.verification.HostIdentityProbe.Status.DECLARED,
                        java.util.Optional.of(identity),
                        "fixture identity"),
                java.util.Map.of(),
                true,
                "fixture admitted");
        assertEquals("5.3.02", PreviewRuntime.requireAdmittedEditorRelease(artifact, resolution));

        Files.copy(editorJar("5.3.99", 503990001), artifact, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        assertThrows(
                IllegalStateException.class,
                () -> PreviewRuntime.requireAdmittedEditorRelease(artifact, resolution),
                "a previously admitted version cannot authorize a replaced artifact");
    }

    @Test
    void exactModeStillRequiresBaseCapabilityAndTheObservedArtifactSnapshot() throws Exception {
        final Path artifact = editorJar("5.3.02", 503020001);
        final var identity = new dev.turboism.mapping.verification.CubismHostIdentity(
                "Live2D Cubism Editor",
                "5.3.02",
                java.util.Optional.empty(),
                503020001,
                "com/live2d/cubism/h",
                dev.turboism.mapping.verification.HostArtifactDigest.from(artifact));
        final var probe = new dev.turboism.mapping.verification.HostIdentityProbe(
                dev.turboism.mapping.verification.HostIdentityProbe.Status.DECLARED,
                java.util.Optional.of(identity),
                "fixture identity");
        final var missingBase = dev.turboism.mapping.verification.CompatibilityResolution.of(
                dev.turboism.mapping.verification.CompatibilityResolution.Mode.VERIFIED,
                probe,
                java.util.Map.of(),
                false,
                "missing records");

        assertEquals(
                "Cubism admission resolved no base runtime capability",
                assertThrows(
                                IllegalStateException.class,
                                () -> PreviewRuntime.requireAdmittedEditorRelease(artifact, missingBase))
                        .getMessage());

        final var admitted = dev.turboism.mapping.verification.CompatibilityResolution.of(
                dev.turboism.mapping.verification.CompatibilityResolution.Mode.VERIFIED,
                probe,
                java.util.Map.of(),
                true,
                "fixture admitted");
        Files.copy(editorJar("5.3.99", 503990001), artifact, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertEquals(
                "Cubism host artifact changed since compatibility probing",
                assertThrows(
                                IllegalStateException.class,
                                () -> PreviewRuntime.requireAdmittedEditorRelease(artifact, admitted))
                        .getMessage());
    }

    private Path editorJar(final String version, final int build) throws Exception {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                "com/live2d/cubism/h",
                null,
                "java/lang/Object",
                null);
        addString(writer, "PRODUCT", "Live2D Cubism Editor");
        addString(writer, "VERSION", version);
        addString(writer, "DATE", "2026/06/16");
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "BUILD", "I", null, build)
                .visitEnd();
        writer.visitEnd();

        final Path jar = Files.createTempFile(tempDir, "editor-release-", ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("com/live2d/cubism/h.class"));
            output.write(writer.toByteArray());
            output.closeEntry();
        }
        return jar;
    }

    private static void addString(final ClassWriter writer, final String name, final String value) {
        writer.visitField(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                        name,
                        "Ljava/lang/String;",
                        null,
                        value)
                .visitEnd();
    }
}
