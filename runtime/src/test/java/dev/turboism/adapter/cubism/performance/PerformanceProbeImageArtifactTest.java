package dev.turboism.adapter.cubism.performance;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in static check; reads exact class bytes but never starts or initializes Cubism. */
class PerformanceProbeImageArtifactTest {
    @Test
    void transformsEverySelectorOfTheExact5302ImageProfile() throws Exception {
        final String configured = System.getProperty("turboism.test.cubism5302Jar");
        assumeTrue(configured != null, "exact 5.3.02 artifact path was not provided");
        final Path artifact = Path.of(configured).toAbsolutePath();
        org.junit.jupiter.api.Assertions.assertTrue(Files.isRegularFile(artifact));
        assertEquals(ReviewedHostArtifacts.CUBISM_5_3_02, HostArtifactDigest.from(artifact));
        final var url = artifact.toUri().toURL();
        try (var loader = new URLClassLoader(new java.net.URL[]{url}, getClass().getClassLoader());
             var jar = new JarFile(artifact.toFile())) {
            final var targets = PerformanceProbeTargets.cubism5302Images();
            final var transformer = new PerformanceProbeMethodTransformer(loader, artifact, targets);
            final var domain = new ProtectionDomain(new CodeSource(url, (Certificate[]) null), null, loader, null);
            for (String owner : targets.stream().map(PerformanceProbeMethodTransformer.Target::ownerInternalName)
                .distinct().toList()) {
                final var entry = jar.getJarEntry(owner + ".class");
                assertNotNull(entry, owner);
                try (var input = jar.getInputStream(entry)) {
                    final byte[] transformed = transformer.transform(null, loader, owner, null, domain, input.readAllBytes());
                    assertNotNull(transformed, owner);
                    assertEquals(owner, new ClassReader(transformed).getClassName());
                }
            }
            assertEquals(12, transformer.matchCounts().size());
            transformer.matchCounts().forEach((target, count) -> assertEquals(1, count, target.toString()));
        }
    }
}
