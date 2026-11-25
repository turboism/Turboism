package dev.turboism.adapter.cubism.temporarydeform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class TemporaryDeformNativeTransformerTest {

    private static final String ENV_CUBISM_REF = "TURBOISM_CUBISM_REF";
    private static final String ARTIFACTS_DIR_NAME = "cubism-host-artifacts";
    private static final String HOST_JAR_NAME = "Live2D_Cubism.jar";

    static Path resolveCubismArtifactsRoot() {
        final String refEnv = System.getenv(ENV_CUBISM_REF);
        if (refEnv != null && !refEnv.isBlank()) {
            return Path.of(refEnv);
        }
        return Path.of(System.getProperty("user.home"), ARTIFACTS_DIR_NAME);
    }

    static Path resolveFirstAvailableCubismJar(final String... versions) {
        final Path root = resolveCubismArtifactsRoot();
        for (final String version : versions) {
            final Path candidate = root.resolve(version).resolve(HOST_JAR_NAME);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return root.resolve(versions[0]).resolve(HOST_JAR_NAME);
    }

    @Test
    void transformsRealCubismHostJarsAcrossSupportedVersionsWithZeroTurboismReferences() throws Exception {
        final List<String> sampleVersions = List.of("5.0.00", "5.2.03", "5.3.00", "5.3.02", "5.3.04");
        final Path baseDir = resolveCubismArtifactsRoot();
        assumeTrue(Files.isDirectory(baseDir), "local Cubism host artifacts directory is absent");

        for (final String version : sampleVersions) {
            final Path jarPath = baseDir.resolve(version).resolve(HOST_JAR_NAME);
            assumeTrue(Files.isRegularFile(jarPath), "missing artifact " + jarPath);
            final TemporaryDeformHostProfile profile =
                    TemporaryDeformHostProfile.forVersion(version).orElseThrow();
            try (URLClassLoader loader =
                            new URLClassLoader(new URL[] {jarPath.toUri().toURL()}, null);
                    JarFile jar = new JarFile(jarPath.toFile())) {
                final ProtectionDomain domain =
                        new ProtectionDomain(new CodeSource(jarPath.toUri().toURL(), (Certificate[]) null), null);
                final TemporaryDeformNativeTransformer transformer =
                        new TemporaryDeformNativeTransformer(profile, loader, jarPath, ignored -> {});

                for (final String target : transformer.targetClassNames()) {
                    final JarEntry entry = jar.getJarEntry(target + ".class");
                    assertNotNull(entry, "missing entry " + target + " in " + version);
                    final byte[] original;
                    try (InputStream in = jar.getInputStream(entry)) {
                        original = in.readAllBytes();
                    }
                    final byte[] transformed = transformer.transform(null, loader, target, null, domain, original);
                    assertNotNull(transformed, "failed transforming " + target + " in " + version);
                    final String bytesAsAscii = new String(transformed, java.nio.charset.StandardCharsets.ISO_8859_1);
                    assertTrue(
                            !bytesAsAscii.contains("dev/turboism/"),
                            "emitted bytecode must remain loader-neutral in " + version);
                    assertTrue(
                            bytesAsAscii.contains("java/util/function/BiConsumer"),
                            "expected BiConsumer bridge lookup in " + version);
                }
                assertEquals(2, transformer.transformedCount(), "expected both targets transformed for " + version);
                assertEquals(TemporaryDeformNativeTransformer.Outcome.TARGET_TRANSFORMED, transformer.outcome());
            }
        }
    }

    @Test
    void rejectsMismatchedLoaderAndMissingMethodShapeFailClosed() throws Exception {
        final TemporaryDeformHostProfile profile =
                TemporaryDeformHostProfile.forVersion("5.3.04").orElseThrow();
        final Path expectedJar =
                Path.of("/tmp/fake-cubism.jar").toAbsolutePath().normalize();
        final ClassLoader loader = TemporaryDeformNativeTransformerTest.class.getClassLoader();
        final TemporaryDeformNativeTransformer transformer =
                new TemporaryDeformNativeTransformer(profile, loader, expectedJar, ignored -> {});
        final ProtectionDomain wrongDomain = new ProtectionDomain(
                new CodeSource(Path.of("/tmp/other.jar").toUri().toURL(), (Certificate[]) null), null);

        final byte[] minimalClass = emptyClassBytes(profile.managerInternalName());
        assertNull(transformer.transform(null, null, profile.managerInternalName(), null, wrongDomain, minimalClass));
        assertEquals(TemporaryDeformNativeTransformer.Outcome.LOADER_MISMATCH, transformer.outcome());

        final ProtectionDomain rightDomain =
                new ProtectionDomain(new CodeSource(expectedJar.toUri().toURL(), (Certificate[]) null), null);
        assertNull(transformer.transform(null, loader, profile.managerInternalName(), null, rightDomain, minimalClass));
        assertEquals(TemporaryDeformNativeTransformer.Outcome.METHOD_SHAPE_MISMATCH, transformer.outcome());
    }

    private static byte[] emptyClassBytes(final String internalName) throws IOException {
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        final MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        return new ClassReader(cw.toByteArray()).b;
    }
}
