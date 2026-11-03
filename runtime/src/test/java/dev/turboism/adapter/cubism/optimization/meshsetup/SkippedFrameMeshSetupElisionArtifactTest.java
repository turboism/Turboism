package dev.turboism.adapter.cubism.optimization.meshsetup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Exact-artifact admission for the skipped-frame {@code GMesh2.setup} elision.
 * Versions without a configured official jar are skipped, not failed.
 */
class SkippedFrameMeshSetupElisionArtifactTest {

    @Test
    void cubism5302SetupTransformsFromOfficialBytes() throws Exception {
        transform(artifact("turboism.test.cubism5302Jar", "5.3.02"));
    }

    @Test
    void cubism5304SetupTransformsFromOfficialBytes() throws Exception {
        transform(artifact("turboism.test.cubism5304Jar", "5.3.04"));
    }

    @Test
    void everyConfiguredReviewedArtifactTransforms() throws Exception {
        final String ref = System.getProperty(
                "turboism.test.cubismRefDir", System.getenv().getOrDefault("TURBOISM_CUBISM_REF", ""));
        assumeTrue(!ref.isBlank(), "exact Cubism reference directory not configured");
        final Path root = Path.of(ref);
        int transformed = 0;
        for (final String version : ReviewedHostArtifacts.reviewedCubismVersions()) {
            final Path artifact = root.resolve(version).resolve("Live2D_Cubism.jar");
            if (!Files.isRegularFile(artifact)) continue;
            final HostArtifactDigest digest = HostArtifactDigest.from(artifact);
            if (SkippedFrameMeshSetupElisionTarget.of(digest).isEmpty()) continue;
            transform(artifact);
            transformed++;
            System.out.println("MESH_SETUP_ELISION artifact transform PASS version=" + version);
        }
        assertTrue(transformed > 0, "no reviewed exact artifact found under " + root);
    }

    private static void transform(final Path artifact) throws Exception {
        try (var loader = new URLClassLoader(
                        new java.net.URL[] {artifact.toUri().toURL()},
                        SkippedFrameMeshSetupElisionArtifactTest.class.getClassLoader());
                var jar = new JarFile(artifact.toFile())) {
            final var entry = jar.getJarEntry(SkippedFrameMeshSetupElisionTarget.OWNER + ".class");
            assertNotNull(entry, "GMesh2 absent from " + artifact);
            final byte[] reference;
            try (var input = jar.getInputStream(entry)) {
                reference = input.readAllBytes();
            }
            final java.util.List<String> shape = ReviewedMethodShape.read(
                    reference,
                    SkippedFrameMeshSetupElisionTarget.OWNER,
                    SkippedFrameMeshSetupElisionTarget.METHOD,
                    SkippedFrameMeshSetupElisionTarget.DESCRIPTOR);
            assertNotNull(shape, "reviewed setup missing from " + artifact);
            assertTrue(
                    SkippedFrameMeshSetupElisionTarget.hasReviewedSetupShape(shape),
                    "setup body is not the reviewed version-compatible shape in " + artifact);
            assertDependencySignatures(artifact, jar);
            final var transformer = new SkippedFrameMeshSetupElisionTransformer(loader, artifact, reference);
            final ProtectionDomain domain = new ProtectionDomain(
                    new CodeSource(artifact.toUri().toURL(), (java.security.cert.Certificate[]) null), null);
            final byte[] output = transformer.transform(
                    null, loader, SkippedFrameMeshSetupElisionTarget.OWNER, null, domain, reference);
            assertNotNull(output, transformer.failure());
            assertEquals(1, transformer.matches(), artifact.toString());
            assertEquals(1, transformer.guarded(), artifact.toString());
        }
    }

    private static void assertDependencySignatures(final Path artifact, final JarFile jar) throws Exception {
        final Map<String, String> bufferGetters = Map.ofEntries(
                Map.entry("getPositionsBuffer", "()Lcom/live2d/graphics3d/mesh/a/b;"),
                Map.entry("getNormalsBuffer", "()Lcom/live2d/graphics3d/mesh/a/b;"),
                Map.entry("getUvsBuffer", "()Lcom/live2d/graphics3d/mesh/a/b;"),
                Map.entry("getUvs2Buffer", "()Lcom/live2d/graphics3d/mesh/a/b;"),
                Map.entry("getUvs3Buffer", "()Lcom/live2d/graphics3d/mesh/a/b;"),
                Map.entry("getColorsBuffer", "()Lcom/live2d/graphics3d/mesh/a/b;"),
                Map.entry("getColors32Buffer", "()Lcom/live2d/graphics3d/mesh/a/c;"),
                Map.entry("getAlphaBuffer", "()Lcom/live2d/graphics3d/mesh/a/b;"),
                Map.entry("getIndicesBuffer", "()Lcom/live2d/graphics3d/mesh/a/c;"));
        final Set<String> foundBuffers = new HashSet<>();
        accept(jar, SkippedFrameMeshSetupElisionTarget.OWNER, (access, name, descriptor) -> {
            final String expected = bufferGetters.get(name);
            if (expected != null) {
                assertEquals(expected, descriptor, artifact + " " + name);
                foundBuffers.add(name);
            }
        });
        assertEquals(bufferGetters.keySet(), foundBuffers, "buffer getter surface changed in " + artifact);

        final Set<String> foundBase = new HashSet<>();
        accept(jar, SkippedFrameMeshSetupElisionTarget.BASE_OWNER, (access, name, descriptor) -> {
            if ("i".equals(name)) {
                assertEquals("()Ljava/nio/IntBuffer;", descriptor, artifact.toString());
                foundBase.add(name);
            } else if ("k".equals(name)) {
                assertEquals("()Z", descriptor, artifact.toString());
                foundBase.add(name);
            }
        });
        assertEquals(Set.of("i", "k"), foundBase, "buffer base accessors changed in " + artifact);

        final Set<String> foundFrame = new HashSet<>();
        accept(jar, SkippedFrameMeshSetupElisionTarget.FRAME_OWNER, (access, name, descriptor) -> {
            if ("a".equals(name) && descriptor.endsWith(")Lcom/jogamp/opengl/GL3;")) {
                foundFrame.add(name);
            }
        });
        assertEquals(Set.of("a"), foundFrame, "frame GL accessor changed in " + artifact);
    }

    @FunctionalInterface
    private interface MethodSink {
        void accept(int access, String name, String descriptor);
    }

    private static void accept(final JarFile jar, final String owner, final MethodSink sink) throws Exception {
        final var entry = jar.getJarEntry(owner + ".class");
        assertNotNull(entry, owner + " absent from artifact");
        final byte[] bytes;
        try (var input = jar.getInputStream(entry)) {
            bytes = input.readAllBytes();
        }
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public org.objectweb.asm.MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String descriptor,
                                    final String signature,
                                    final String[] exceptions) {
                                sink.accept(access, name, descriptor);
                                return null;
                            }
                        },
                        ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    private static Path artifact(final String property, final String version) {
        String configured = System.getProperty(property, "");
        if (configured.isBlank()) {
            final String ref = System.getProperty(
                    "turboism.test.cubismRefDir", System.getenv().getOrDefault("TURBOISM_CUBISM_REF", ""));
            assumeTrue(!ref.isBlank(), "exact " + version + " artifact not configured");
            configured =
                    Path.of(ref).resolve(version).resolve("Live2D_Cubism.jar").toString();
        }
        final Path artifact = Path.of(configured);
        assumeTrue(Files.isRegularFile(artifact), "artifact absent: " + artifact);
        return artifact;
    }
}
