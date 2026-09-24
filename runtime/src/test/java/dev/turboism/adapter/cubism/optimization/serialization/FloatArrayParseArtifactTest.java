package dev.turboism.adapter.cubism.optimization.serialization;

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
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FloatArrayParseArtifactTest {
    @Test void exact5302MethodIsAdmittedEvenWhenConstantPoolIsRebuilt() throws Exception {
        String configured = System.getProperty("turboism.test.cubism5302Jar", "");
        assumeTrue(!configured.isBlank(), "exact artifact path not configured");
        Path artifact = Path.of(configured);
        assertTrue(Files.isRegularFile(artifact), "configured artifact must exist");
        assertEquals(ReviewedHostArtifacts.CUBISM_5_3_02, HostArtifactDigest.from(artifact));
        try (var jar = new JarFile(artifact.toFile());
             var loader = new URLClassLoader(new java.net.URL[]{artifact.toUri().toURL()}, getClass().getClassLoader())) {
            byte[] original;
            try (var input = jar.getInputStream(jar.getJarEntry(FloatArrayParseTransformer.TARGET + ".class"))) {
                original = input.readAllBytes();
            }
            var rewritten = new ClassWriter(0);
            new ClassReader(original).accept(rewritten, 0);
            var transformer = new FloatArrayParseTransformer(loader, artifact, original);
            var domain = new ProtectionDomain(new CodeSource(artifact.toUri().toURL(), (java.security.cert.Certificate[]) null), null);
            assertNotNull(transformer.transform(null, loader, FloatArrayParseTransformer.TARGET, null, domain, rewritten.toByteArray()), transformer.failure());
            assertEquals(1, transformer.matches());
            assertNotNull(transformer.beforeSha256());
        }
        assertEquals(ReviewedHostArtifacts.CUBISM_5_3_02, HostArtifactDigest.from(artifact));
    }
}
