package dev.turboism.adapter.cubism.optimization.image;

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

class ImageArchiveReuseArtifactTest {
    @Test
    void exact5302ArchiveAndDecodeCallSitesTransformWithoutChangingTheJar() throws Exception {
        exercise(ReviewedHostArtifacts.CUBISM_5_3_02, "5.3.02");
    }

    @Test
    void exact5203ArchiveAndDecodeCallSitesTransformWithoutChangingTheJar() throws Exception {
        exercise(ReviewedHostArtifacts.CUBISM_5_2_03, "5.2.03");
    }

    @Test
    void exact5303ArchiveAndDecodeCallSitesTransformWithoutChangingTheJar() throws Exception {
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
        assumeTrue(Files.isRegularFile(artifact), "exact local artifact missing");
        assertEquals(expected, HostArtifactDigest.from(artifact));
        try (var jar = new JarFile(artifact.toFile());
                var loader = new URLClassLoader(
                        new java.net.URL[] {artifact.toUri().toURL()},
                        ImageArchiveReuseArtifactTest.class.getClassLoader())) {
            byte[] bytes;
            try (var in = jar.getInputStream(jar.getJarEntry("com/live2d/graphics/CImageResource.class"))) {
                bytes = in.readAllBytes();
            }
            var transformer = new ImageArchiveReuseTransformer(loader, artifact);
            var domain = new ProtectionDomain(
                    new CodeSource(artifact.toUri().toURL(), (java.security.cert.Certificate[]) null), null);
            byte[] result =
                    transformer.transform(null, loader, "com/live2d/graphics/CImageResource", null, domain, bytes);
            assertNotNull(result, transformer.failure());
            assertEquals(1, transformer.matches());
            assertFalse(java.util.Arrays.equals(bytes, result));
            assertNotNull(transformer.beforeSha256());
        }
        assertEquals(expected, HostArtifactDigest.from(artifact));
    }
}
