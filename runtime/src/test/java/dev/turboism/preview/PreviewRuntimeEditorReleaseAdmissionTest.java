package dev.turboism.preview;

import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PreviewRuntimeEditorReleaseAdmissionTest {

    @TempDir
    Path tempDir;

    @Test
    void exactArtifactAndDeclarationMustAgreeBeforeRuntimeStartup() throws Exception {
        final Path declared5303 = editorJar("5.3.03", 503030001);

        final IllegalStateException unreviewedArtifact = assertThrows(
            IllegalStateException.class,
            () -> PreviewRuntime.requireReviewedEditorRelease(declared5303)
        );
        assertEquals(
            "Cubism host artifact is not an exact reviewed identity; admission failed closed",
            unreviewedArtifact.getMessage()
        );
    }

    @Test
    void releaseMismatchFailsClosedEvenWhenDigestLookupIsReviewed() {
        final String reviewed = ReviewedHostArtifacts.cubismVersionOf(
            ReviewedHostArtifacts.CUBISM_5_3_03
        ).orElseThrow();
        final String declared = "5.3.02";

        final IllegalStateException mismatch = assertThrows(
            IllegalStateException.class,
            () -> PreviewRuntime.requireReleaseAgreement(reviewed, declared)
        );
        assertEquals(
            "Cubism host release/artifact mismatch; admission failed closed",
            mismatch.getMessage()
        );
    }

    @Test
    void exactReviewed5303AgreementOpensFullRuntimeAdmission() {
        assertEquals(
            "5.3.03",
            PreviewRuntime.requireReleaseAgreement("5.3.03", "5.3.03")
        );
        assertTrue(ReviewedHostArtifacts.admitsFullRuntime("5.3.03"));
    }

    private Path editorJar(final String version, final int build) throws Exception {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(
            Opcodes.V17,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
            "com/live2d/cubism/h",
            null,
            "java/lang/Object",
            null
        );
        addString(writer, "PRODUCT", "Live2D Cubism Editor");
        addString(writer, "VERSION", version);
        addString(writer, "DATE", "2026/06/16");
        writer.visitField(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            "BUILD",
            "I",
            null,
            build
        ).visitEnd();
        writer.visitEnd();

        final Path jar = Files.createTempFile(tempDir, "editor-release-", ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("com/live2d/cubism/h.class"));
            output.write(writer.toByteArray());
            output.closeEntry();
        }
        return jar;
    }

    private static void addString(
        final ClassWriter writer,
        final String name,
        final String value
    ) {
        writer.visitField(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            name,
            "Ljava/lang/String;",
            null,
            value
        ).visitEnd();
    }
}
