package dev.turboism.adapter.cubism.optimization.clipmask;

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
import java.util.Map;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/**
 * Exact-artifact admission for both clipping-mask bridges across every reviewed
 * Cubism family. The bridges resolve their host members at construction, so a
 * member that moved or changed shape fails before the Editor can render a frame.
 *
 * <p>Both bridges need the reviewed JOGL jar: the dedupe bridge reaches the render
 * backend's GL members and the scissor bridge declares GL types on its host
 * signatures. Without that jar neither bridge can resolve, so the test reports the
 * missing evidence instead of passing on a skipped half.</p>
 */
class ClipMaskBridgeArtifactTest {

    @Test
    void dedupeBridgeResolvesEveryConfiguredReviewedArtifact() throws Exception {
        final Path root = referenceRoot();
        final Path jogl = joglArtifact(root);
        int resolved = 0;
        for (final String version : ReviewedHostArtifacts.reviewedCubismVersions()) {
            final Path artifact = resolveArtifact(root, version);
            if (!Files.isRegularFile(artifact)) continue;
            assertTrue(
                    ReviewedHostArtifacts.cubismVersionOf(HostArtifactDigest.from(artifact))
                            .isPresent(),
                    version + " is not a reviewed artifact");
            try (HostArtifactTestLoader loader = HostArtifactTestLoader.open(artifact.getParent(), jogl)) {
                try (ClipMaskDeduplicationBridge dedupe = new ClipMaskDeduplicationBridge(loader, version)) {
                    assertNotNull(dedupe.snapshot(), version);
                }
            }
            resolved++;
            System.out.println("CLIP_MASK dedupe bridge PASS version=" + version);
        }
        assertTrue(resolved > 0, "no reviewed exact artifact found under " + root);
    }

    @Test
    void everyConfiguredReviewedArtifactResolvesTheScissorBridgeAndWeavesTheRenderSystem() throws Exception {
        final Path root = referenceRoot();
        final Path jogl = joglArtifact(root);
        int dedupeVersions = 0;
        int scissorVersions = 0;
        for (final String version : ReviewedHostArtifacts.reviewedCubismVersions()) {
            final Path artifact = resolveArtifact(root, version);
            if (!Files.isRegularFile(artifact)) continue;
            final String owner = ClipMaskDeduplicationTarget.renderSystemOwner(version);
            final String descriptor = ClipMaskDeduplicationTarget.renderMethodDescriptor(version);
            try (HostArtifactTestLoader loader = HostArtifactTestLoader.open(artifact.getParent(), jogl)) {
                try (ClipMaskDeduplicationBridge dedupe = new ClipMaskDeduplicationBridge(loader, version)) {
                    assertNotNull(dedupe.snapshot(), version);
                }
                dedupeVersions++;
                try (ClipMaskScissorBridge scissor = new ClipMaskScissorBridge(loader, version)) {
                    assertNotNull(scissor.snapshot(), version);
                }
                scissorVersions++;

                final byte[] reference = bytes(artifact, owner);
                final ClipMaskDeduplicationTransformer transformer = new ClipMaskDeduplicationTransformer(
                        loader, artifact, owner, descriptor, Map.of(owner, reference));
                final ProtectionDomain domain = new ProtectionDomain(
                        new CodeSource(artifact.toUri().toURL(), (java.security.cert.Certificate[]) null), null);
                final byte[] transformed = transformer.transform(null, loader, owner, null, domain, reference);
                assertNotNull(transformed, version + ": " + transformer.failure());
                assertEquals(3, transformer.sites(), version);
                assertNull(transformer.failure(), version);
            }
            System.out.println("CLIP_MASK scissor/transform PASS version=" + version);
        }
        assertTrue(dedupeVersions > 0, "no reviewed exact artifact found under " + root);
        assertEquals(dedupeVersions, scissorVersions, "a version was skipped");
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

    private static Path resolveArtifact(final Path root, final String version) {
        final String override = System.getProperty("turboism.test.cubism" + version.replace(".", "") + "Jar", "");
        if (!override.isBlank()) {
            return Path.of(override);
        }
        return root.resolve(version).resolve("Live2D_Cubism.jar");
    }

    private static Path referenceRoot() {
        final String configured = System.getProperty(
                "turboism.test.cubismRefDir", System.getenv().getOrDefault("TURBOISM_CUBISM_REF", ""));
        assumeTrue(!configured.isBlank(), "exact Cubism reference directory not configured");
        final Path root = Path.of(configured);
        assumeTrue(Files.isDirectory(root), "reference directory absent: " + root);
        return root;
    }

    private static Path joglArtifact(final Path root) {
        return HostArtifactTestLoader.reviewedJogl(root)
                .orElseThrow(() -> new AssertionError(
                        "reviewed JOGL jar absent; set " + HostArtifactTestLoader.JOGL_ENVIRONMENT_VARIABLE
                                + " or stage jogl-all.jar in the evidence root"));
    }
}
