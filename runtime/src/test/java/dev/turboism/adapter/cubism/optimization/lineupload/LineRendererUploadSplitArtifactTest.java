package dev.turboism.adapter.cubism.optimization.lineupload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.adapter.cubism.optimization.HostArtifactTestLoader;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/**
 * Exact-artifact admission and content-split behavior for the line-renderer dirty-buffer
 * hook across every reviewed Cubism version.
 *
 * <p>The renderer's buffer accessors declare JOGL types, so the loader needs the reviewed JOGL
 * jar. Without it the bridge cannot resolve a single member and the test could not report the
 * behavior it exists to check.</p>
 */
class LineRendererUploadSplitArtifactTest {

    @Test
    void everyConfiguredReviewedArtifactRewritesAndResolvesTheBridge() throws Exception {
        final Path root = referenceRoot();
        final Path jogl = HostArtifactTestLoader.reviewedJogl(root)
                .orElseThrow(() -> new AssertionError(
                        "reviewed JOGL jar absent; set " + HostArtifactTestLoader.JOGL_ENVIRONMENT_VARIABLE
                                + " or stage jogl-all.jar in the evidence root"));
        int rewritten = 0;
        for (final String version : ReviewedHostArtifacts.reviewedCubismVersions()) {
            final Path artifact = root.resolve(version).resolve("Live2D_Cubism.jar");
            if (!Files.isRegularFile(artifact)) continue;
            assertTrue(
                    ReviewedHostArtifacts.cubismVersionOf(HostArtifactDigest.from(artifact))
                            .isPresent(),
                    version + " is not a reviewed artifact");
            try (HostArtifactTestLoader loader = HostArtifactTestLoader.open(artifact.getParent(), jogl)) {
                final byte[] reference = bytes(artifact, LineRendererUploadSplitTarget.OWNER);
                final LineRendererUploadSplitTransformer transformer =
                        new LineRendererUploadSplitTransformer(loader, artifact, reference);
                final ProtectionDomain domain = domain(artifact);
                final byte[] transformed = transformer.transform(
                        null, loader, LineRendererUploadSplitTarget.OWNER, null, domain, reference);
                assertNotNull(transformed, version + ": " + transformer.failure());
                assertEquals(1, transformer.matches(), version);
                assertNull(transformer.failure(), version);
                try (LineRendererUploadSplitBridge bridge = new LineRendererUploadSplitBridge(loader)) {
                    assertNotNull(bridge.snapshot(), version);
                }
            }
            rewritten++;
            System.out.println("LINE_RENDERER_UPLOAD artifact PASS version=" + version);
        }
        assertTrue(rewritten > 0, "no reviewed exact artifact found under " + root);
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

    private static ProtectionDomain domain(final Path artifact) throws Exception {
        return new ProtectionDomain(
                new CodeSource(artifact.toUri().toURL(), (java.security.cert.Certificate[]) null), null);
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
