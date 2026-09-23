package dev.turboism.adapter.cubism.optimization.image;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import org.junit.jupiter.api.Test;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.jar.JarFile;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ImageArchiveReuseArtifactTest {
    @Test void exact5302ArchiveAndDecodeCallSitesTransformWithoutChangingTheJar() throws Exception {
        String configured = System.getProperty("turboism.test.cubism5302Jar", "");
        assumeTrue(!configured.isBlank(), "exact local artifact path not configured");
        Path artifact = Path.of(configured);
        assumeTrue(Files.isRegularFile(artifact), "exact local artifact missing");
        assertEquals(ReviewedHostArtifacts.CUBISM_5_3_02,HostArtifactDigest.from(artifact));
        try (var jar = new JarFile(artifact.toFile());
             var loader = new URLClassLoader(new java.net.URL[]{artifact.toUri().toURL()},getClass().getClassLoader())) {
            byte[] bytes;
            try (var in = jar.getInputStream(jar.getJarEntry("com/live2d/graphics/CImageResource.class"))) { bytes=in.readAllBytes(); }
            var transformer = new ImageArchiveReuseTransformer(loader,artifact);
            var domain = new ProtectionDomain(new CodeSource(artifact.toUri().toURL(),(java.security.cert.Certificate[])null),null);
            byte[] result = transformer.transform(null,loader,"com/live2d/graphics/CImageResource",null,domain,bytes);
            assertNotNull(result,transformer.failure());assertEquals(1,transformer.matches());
            assertFalse(java.util.Arrays.equals(bytes,result));assertNotNull(transformer.beforeSha256());
        }
        assertEquals(ReviewedHostArtifacts.CUBISM_5_3_02,HostArtifactDigest.from(artifact));
    }
}
