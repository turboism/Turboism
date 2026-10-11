package dev.turboism.adapter.cubism.optimization.serialization;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

class FloatArrayParseArtifactTest {
    @Test
    void exact5302MethodIsAdmittedEvenWhenConstantPoolIsRebuilt() throws Exception {
        exercise(ReviewedHostArtifacts.CUBISM_5_3_02, "5.3.02");
    }

    @Test
    void exact5203MethodIsAdmittedEvenWhenConstantPoolIsRebuilt() throws Exception {
        exercise(ReviewedHostArtifacts.CUBISM_5_2_03, "5.2.03");
    }

    @Test
    void exact5303MethodIsAdmittedEvenWhenConstantPoolIsRebuilt() throws Exception {
        exercise(ReviewedHostArtifacts.CUBISM_5_3_03, "5.3.03");
    }

    private static void exercise(final HostArtifactDigest expected, final String version) throws Exception {
        String configured = System.getProperty("turboism.test.cubism" + version.replace(".", "") + "Jar", "");
        if (configured.isBlank()) {
            final String ref = System.getProperty(
                    "turboism.test.cubismRefDir", System.getenv().getOrDefault("TURBOISM_CUBISM_REF", ""));
            assumeTrue(!ref.isBlank(), "exact " + version + " artifact not configured");
            configured =
                    Path.of(ref).resolve(version).resolve("Live2D_Cubism.jar").toString();
        }
        Path artifact = Path.of(configured);
        assertTrue(Files.isRegularFile(artifact), "configured artifact must exist");
        assertEquals(expected, HostArtifactDigest.from(artifact));
        try (var jar = new JarFile(artifact.toFile());
                var loader = new URLClassLoader(
                        new java.net.URL[] {artifact.toUri().toURL()},
                        FloatArrayParseArtifactTest.class.getClassLoader())) {
            byte[] original;
            try (var input = jar.getInputStream(jar.getJarEntry(FloatArrayParseTransformer.TARGET + ".class"))) {
                original = input.readAllBytes();
            }
            var rewritten = new ClassWriter(0);
            new ClassReader(original).accept(rewritten, 0);
            var transformer = new FloatArrayParseTransformer(loader, artifact, original);
            var domain = new ProtectionDomain(
                    new CodeSource(artifact.toUri().toURL(), (java.security.cert.Certificate[]) null), null);
            assertNotNull(
                    transformer.transform(
                            null, loader, FloatArrayParseTransformer.TARGET, null, domain, rewritten.toByteArray()),
                    transformer.failure());
            assertEquals(1, transformer.matches());
            assertNotNull(transformer.beforeSha256());
        }
        assertEquals(expected, HostArtifactDigest.from(artifact));
    }
}
