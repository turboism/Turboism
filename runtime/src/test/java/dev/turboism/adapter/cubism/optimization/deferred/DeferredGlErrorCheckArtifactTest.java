package dev.turboism.adapter.cubism.optimization.deferred;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/**
 * Exact-artifact admission for the deferred GL error check across every reviewed
 * Cubism version.
 *
 * <p>The transformer verifies the shader error helper and the frame-boundary
 * reporter against each artifact's own reference bytes. This test feeds those
 * bytes for every reviewed generation, so a shape drift in any stable version
 * fails here instead of surfacing as {@code deferred=INACTIVE} on a real host.</p>
 */
class DeferredGlErrorCheckArtifactTest {

    @Test
    void everyConfiguredReviewedArtifactRewritesErrorCheckAndFrameBoundary() throws Exception {
        final Path root = referenceRoot();
        int rewritten = 0;
        for (final String version : ReviewedHostArtifacts.reviewedCubismVersions()) {
            final Path artifact = root.resolve(version).resolve("Live2D_Cubism.jar");
            if (!Files.isRegularFile(artifact)) continue;
            final HostArtifactDigest digest = HostArtifactDigest.from(artifact);
            final DeferredGlErrorCheckTarget target = DeferredGlErrorCheckTarget.of(digest)
                    .orElseThrow(() -> new AssertionError("reviewed artifact refused: " + version));
            final byte[] errorReference = bytes(artifact, target.errorOwner());
            final byte[] frameReference = bytes(artifact, target.frameOwner());
            try (URLClassLoader loader = loader(artifact.getParent())) {
                final DeferredGlErrorCheckTransformer transformer =
                        new DeferredGlErrorCheckTransformer(loader, artifact, errorReference, frameReference, target);
                final ProtectionDomain domain = new ProtectionDomain(
                        new CodeSource(artifact.toUri().toURL(), (java.security.cert.Certificate[]) null), null);
                final byte[] error =
                        transformer.transform(null, loader, target.errorOwner(), null, domain, errorReference);
                assertNotNull(error, version + ": " + transformer.failure());
                assertNull(transformer.failure(), version);
                final byte[] frame =
                        transformer.transform(null, loader, target.frameOwner(), null, domain, frameReference);
                assertNotNull(frame, version + ": " + transformer.failure());
                assertNull(transformer.failure(), version);
                assertEquals(2, transformer.matches(), version);
            }
            rewritten++;
            System.out.println("DEFERRED_GL artifact transform PASS version=" + version);
        }
        assertTrue(rewritten > 0, "no reviewed exact artifact found under " + root);
    }

    private static URLClassLoader loader(final Path directory) throws Exception {
        final List<URL> urls = new ArrayList<>();
        try (var files = Files.walk(directory, 2)) {
            for (final Path path :
                    files.filter(path -> path.toString().endsWith(".jar")).toList()) {
                urls.add(path.toUri().toURL());
            }
        }
        return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }

    private static byte[] bytes(final Path artifact, final String owner) throws Exception {
        try (JarFile jar = new JarFile(artifact.toFile())) {
            final var entry = jar.getJarEntry(owner + ".class");
            assertNotNull(entry, owner + " absent from " + artifact);
            try (var input = jar.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    private static Path referenceRoot() {
        final String configured = System.getProperty(
                "turboism.test.cubismRefDir", System.getenv().getOrDefault("TURBOISM_CUBISM_REF", ""));
        assumeTrue(!configured.isBlank(), "exact Cubism reference directory not configured");
        final Path root = Path.of(configured);
        assumeTrue(Files.isDirectory(root), "reference directory absent: " + root);
        return root;
    }
}
