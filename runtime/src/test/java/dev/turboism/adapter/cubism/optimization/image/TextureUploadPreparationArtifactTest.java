package dev.turboism.adapter.cubism.optimization.image;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.adapter.cubism.optimization.ClassPinTable;
import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class TextureUploadPreparationArtifactTest {
    private static final String OTHER_DESCRIPTOR =
            "(Lcom/live2d/graphics3d/a;Lcom/live2d/graphics/CWritableImage;ZZLjava/lang/String;)I";

    @Test
    void exactFactoryTransformsAndOtherOverloadRemainsUnchanged() throws Exception {
        exercise(ReviewedHostArtifacts.CUBISM_5_3_02, "5.3.02");
    }

    @Test
    void exact5203FactoryOnShaderYTransformsWithoutChangingTheJar() throws Exception {
        exercise(ReviewedHostArtifacts.CUBISM_5_2_03, "5.2.03");
    }

    @Test
    void exact5303FactoryTransformsWithoutChangingTheJar() throws Exception {
        exercise(ReviewedHostArtifacts.CUBISM_5_3_03, "5.3.03");
    }

    /**
     * Covers every reviewed generation a configured reference corpus can provide. The
     * three explicit tests above keep the historical property-based entry points working
     * on machines that only stage one exact JAR.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("reviewedArtifacts")
    void everyAvailableReviewedFactoryTransforms(final String version, final HostArtifactDigest expected)
            throws Exception {
        exercise(expected, version);
    }

    static Stream<Arguments> reviewedArtifacts() {
        return ReviewedHostArtifacts.all().stream()
                .map(artifact -> Arguments.of(
                        ReviewedHostArtifacts.cubismVersionOf(artifact).orElseThrow(), artifact));
    }

    private static void exercise(final HostArtifactDigest expected, final String version) throws Exception {
        Path artifact = artifact(version);
        assumeTrue(Files.isRegularFile(artifact), "exact " + version + " artifact not staged");
        assertEquals(expected, HostArtifactDigest.from(artifact));
        final String owner = TextureUploadPreparationTransformer.ownerForVersion(version);
        URL[] urls;
        try (var files = Files.walk(artifact.getParent())) {
            urls = files.filter(path -> path.toString().endsWith(".jar"))
                    .map(path -> {
                        try {
                            return path.toUri().toURL();
                        } catch (Exception failure) {
                            throw new IllegalStateException(failure);
                        }
                    })
                    .toArray(URL[]::new);
        }
        try (var loader = new URLClassLoader(urls, TextureUploadPreparationArtifactTest.class.getClassLoader());
                var jar = new JarFile(artifact.toFile())) {
            byte[] bytes;
            try (var input = jar.getInputStream(jar.getJarEntry(owner + ".class"))) {
                bytes = input.readAllBytes();
            }
            assertEquals(
                    ClassPinTable.singleSha256("texture-upload-preparation", version, owner),
                    HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                    version + " factory class must match its reviewed pin");
            Path jogl = artifact.getParent().resolve("jogl/jogl-all.jar");
            if (Files.isRegularFile(jogl))
                assertEquals(
                        ReviewedHostArtifacts.CUBISM_5_3_03_JOGL,
                        HostArtifactDigest.from(jogl),
                        version + " JOGL artifact must match the reviewed gate");
            var transformer = new TextureUploadPreparationTransformer(loader, artifact, bytes, owner);
            var domain = new ProtectionDomain(
                    new CodeSource(artifact.toUri().toURL(), (java.security.cert.Certificate[]) null), null);
            byte[] transformed = transformer.transform(null, loader, owner, null, domain, bytes);
            assertNotNull(transformed, transformer.failure());
            assertEquals(1, transformer.matches());
            var otherShape = ReviewedMethodShape.read(bytes, owner, "a", OTHER_DESCRIPTOR);
            assertNotNull(otherShape, "the unmodified overload must exist in the reviewed artifact");
            assertEquals(otherShape, ReviewedMethodShape.read(transformed, owner, "a", OTHER_DESCRIPTOR));
            ClassWriter changed = new ClassWriter(0);
            new ClassReader(bytes)
                    .accept(
                            new ClassVisitor(Opcodes.ASM9, changed) {
                                @Override
                                public MethodVisitor visitMethod(
                                        int access, String name, String desc, String sig, String[] exceptions) {
                                    var original = super.visitMethod(access, name, desc, sig, exceptions);
                                    if (!name.equals("a")
                                            || !desc.equals(TextureUploadPreparationTransformer.METHOD_DESCRIPTOR))
                                        return original;
                                    return new MethodVisitor(Opcodes.ASM9, original) {
                                        @Override
                                        public void visitCode() {
                                            super.visitCode();
                                            super.visitInsn(Opcodes.NOP);
                                        }
                                    };
                                }
                            },
                            0);
            assertNull(transformer.transform(null, loader, owner, null, domain, changed.toByteArray()));
            assertNotNull(transformer.failure());
        }
        assertEquals(expected, HostArtifactDigest.from(artifact));
    }

    private static Path artifact(final String version) {
        final String configured = System.getProperty("turboism.test.cubism" + version.replace(".", "") + "Jar", "");
        if (!configured.isBlank()) return Path.of(configured);
        final String ref = System.getProperty(
                "turboism.test.cubismRefDir", System.getenv().getOrDefault("TURBOISM_CUBISM_REF", ""));
        assumeTrue(!ref.isBlank(), "exact " + version + " artifact not configured");
        return Path.of(ref).resolve(version).resolve("Live2D_Cubism.jar");
    }
}
