package dev.turboism.mapping.verification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CubismEditorReleaseDetectorTest {

    @TempDir
    Path tempDir;

    @Test
    void detectsExactReleaseFromSelfContainedEditorJarWithoutLoadingHostClasses() throws Exception {
        final Path editorJar = jarWithDeclaration(declarationClassBytes());

        final CubismEditorReleaseDeclaration declaration = CubismEditorReleaseDetector
            .detect(editorJar)
            .orElseThrow();

        assertEquals("Live2D Cubism Editor", declaration.product());
        assertEquals("5.3.03", declaration.version());
        assertEquals("2026/06/16", declaration.date());
        assertEquals(503030001, declaration.build());
    }

    @Test
    void declarationRejectsNullFieldsAndNonPositiveBuilds() {
        assertThrows(
            NullPointerException.class,
            () -> new CubismEditorReleaseDeclaration(null, "5.3.03", "2026/06/16", 503030001)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new CubismEditorReleaseDeclaration(
                "Live2D Cubism Editor", "5.3.03", "2026/06/16", 0
            )
        );
    }

    @Test
    void rejectsMissingMalformedAndTruncatedDeclarations() throws Exception {
        assertTrue(CubismEditorReleaseDetector.detect(tempDir.resolve("missing.jar")).isEmpty());
        assertTrue(CubismEditorReleaseDetector.detect(jarWithoutDeclaration()).isEmpty());
        assertTrue(CubismEditorReleaseDetector.parse(new byte[] {0, 1, 2, 3}).isEmpty());
        assertTrue(CubismEditorReleaseDetector.parse(new byte[] {
            (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 0, 0, 2
        }).isEmpty());
    }

    @Test
    void rejectsAmbiguousOrMismatchedVersionAndBuildConstants() {
        assertTrue(CubismEditorReleaseDetector.parse(
            classBytesWithConstants(
                "5.3.03", "5.3.02", "2026/06/16", "Live2D Cubism Editor",
                503030001, 503020001
            )
        ).isEmpty());
        assertTrue(CubismEditorReleaseDetector.parse(
            classBytesWithConstants(
                "5.3.03", "5.3.03", "2026/06/16", "Live2D Cubism Editor",
                503020001, 0
            )
        ).isEmpty());
    }

    @Test
    void rejectsFutureDeclaredVersion() {
        assertTrue(CubismEditorReleaseDetector.parse(
            classBytesWithConstants(
                "5.4.00", "5.4.00", "2026/06/16", "Live2D Cubism Editor",
                504000001, 0
            )
        ).isEmpty());
    }

    @Test
    void rejectsOversizedDeclarationEntryBeforeParsing() throws Exception {
        final byte[] oversized = new byte[CubismEditorReleaseDetector.MAX_DECLARATION_CLASS_BYTES + 1];
        System.arraycopy(declarationClassBytes(), 0, oversized, 0, declarationClassBytes().length);

        assertTrue(CubismEditorReleaseDetector.detect(jarWithDeclaration(oversized)).isEmpty());
    }

    private Path jarWithDeclaration(final byte[] classBytes) throws IOException {
        final Path jar = Files.createTempFile(tempDir, "cubism-editor-release-", ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(CubismEditorReleaseDetector.DECLARATION_CLASS));
            output.write(classBytes);
            output.closeEntry();
        }
        return jar;
    }

    private Path jarWithoutDeclaration() throws IOException {
        final Path jar = Files.createTempFile(tempDir, "cubism-editor-no-release-", ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("other/Type.class"));
            output.write(declarationClassBytes());
            output.closeEntry();
        }
        return jar;
    }


    private static byte[] declarationClassBytes() {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
            "com/live2d/cubism/h", null, "java/lang/Object", null);
        addString(writer, "PRODUCT", "Live2D Cubism Editor");
        addString(writer, "VERSION", "5.3.03");
        addString(writer, "DATE", "2026/06/16");
        addInt(writer, "BUILD", 503030001);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void addString(final ClassWriter writer, final String name, final String value) {
        writer.visitField(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            name,
            "Ljava/lang/String;",
            null,
            value
        ).visitEnd();
    }

    private static void addInt(final ClassWriter writer, final String name, final int value) {
        writer.visitField(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            name,
            "I",
            null,
            value
        ).visitEnd();
    }

    private static byte[] classBytesWithConstants(
        final String version,
        final String otherVersion,
        final String date,
        final String product,
        final int build,
        final int otherBuild
    ) {
        try {
            final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            final java.io.DataOutputStream output = new java.io.DataOutputStream(bytes);
            output.writeInt(0xCAFEBABE);
            output.writeShort(0);
            output.writeShort(7);
            output.writeShort(8);
            for (String value : new String[] {version, otherVersion, date, product}) {
                output.writeByte(1);
                output.writeUTF(value);
            }
            output.writeByte(3);
            output.writeInt(build);
            output.writeByte(3);
            output.writeInt(otherBuild);
            output.flush();
            return bytes.toByteArray();
        } catch (java.io.IOException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
