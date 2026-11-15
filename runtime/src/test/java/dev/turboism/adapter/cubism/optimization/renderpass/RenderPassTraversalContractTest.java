package dev.turboism.adapter.cubism.optimization.renderpass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.config.optimization.Optimization;
import dev.turboism.config.optimization.OptimizationCatalog;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Offline contract tests for {@link RenderPassTraversalTarget} and {@link RenderPassTraversalBridge}.
 */
final class RenderPassTraversalContractTest {

    private static final int EXPECTED_TOTAL_TARGETS = 20;
    private static final int EXPECTED_PRE_53_TARGETS = 15;
    private static final int EXPECTED_53_TARGETS = 5;
    private static final HostArtifactDigest UNREVIEWED_DIGEST = new HostArtifactDigest(1024, "0".repeat(64));

    @Test
    void targetsCoverAllTwentyReviewedReleasesWithVersionRoutedDescriptors() {
        final List<RenderPassTraversalTarget> targets = RenderPassTraversalTarget.all();
        assertEquals(EXPECTED_TOTAL_TARGETS, targets.size());
        assertTrue(RenderPassTraversalTarget.of(UNREVIEWED_DIGEST).isEmpty());

        final long pre53 =
                targets.stream().filter(t -> !t.hasRecursiveTraverseMethod()).count();
        final long v53 = targets.stream()
                .filter(RenderPassTraversalTarget::hasRecursiveTraverseMethod)
                .count();
        assertEquals(EXPECTED_PRE_53_TARGETS, pre53);
        assertEquals(EXPECTED_53_TARGETS, v53);

        for (final RenderPassTraversalTarget target : targets) {
            if (target.version().startsWith("5.3.")) {
                assertEquals(RenderPassTraversalTarget.RENDER_SYSTEM_53, target.renderSystemOwner());
                assertEquals(RenderPassTraversalTarget.RENDER_IMPL_DESCRIPTOR_53, target.renderImplDescriptor());
                assertEquals(RenderPassTraversalTarget.SHADER_HELPER_53, target.shaderHelperOwner());
                assertEquals(RenderPassTraversalTarget.EXPECTED_SITES_53, target.expectedSites());
            } else {
                assertEquals(RenderPassTraversalTarget.RENDER_SYSTEM_50, target.renderSystemOwner());
                assertEquals(RenderPassTraversalTarget.RENDER_IMPL_DESCRIPTOR_50, target.renderImplDescriptor());
                assertEquals(
                        target.version().startsWith("5.2.")
                                ? RenderPassTraversalTarget.SHADER_HELPER_52
                                : RenderPassTraversalTarget.SHADER_HELPER_50,
                        target.shaderHelperOwner());
                assertEquals(RenderPassTraversalTarget.EXPECTED_SITES_50, target.expectedSites());
            }
            assertFalse(target.dependencies().isEmpty());
        }
    }

    @Test
    void bridgePublishesAndRestoresPreCachedLabelsAcrossBounds() {
        final String preKey = RenderPassTraversalBridge.PRE_DRAW_LABELS_PROPERTY;
        final String postKey = RenderPassTraversalBridge.POST_DRAW_LABELS_PROPERTY;
        final Object prevPre = System.getProperties().get(preKey);
        final Object prevPost = System.getProperties().get(postKey);
        try (RenderPassTraversalBridge bridge = new RenderPassTraversalBridge()) {
            assertFalse(bridge.active());
            bridge.install();
            bridge.install();
            assertTrue(bridge.active());

            final String[] preTable = (String[]) System.getProperties().get(preKey);
            final String[] postTable = (String[]) System.getProperties().get(postKey);
            assertEquals(RenderPassTraversalBridge.LABEL_CACHE_SIZE, preTable.length);
            assertEquals(RenderPassTraversalBridge.LABEL_CACHE_SIZE, postTable.length);
            assertEquals("pre draw[0]", preTable[0]);
            assertEquals("post draw[4095]", postTable[RenderPassTraversalBridge.LABEL_CACHE_SIZE - 1]);
            assertEquals("pre draw[-1]", RenderPassTraversalBridge.preDrawLabel(-1));
            assertEquals("post draw[4096]", RenderPassTraversalBridge.postDrawLabel(4096));

            bridge.close();
            assertFalse(bridge.active());
            assertEquals(prevPre, System.getProperties().get(preKey));
            assertEquals(prevPost, System.getProperties().get(postKey));
        } finally {
            if (prevPre == null) System.getProperties().remove(preKey);
            else System.getProperties().put(preKey, prevPre);
            if (prevPost == null) System.getProperties().remove(postKey);
            else System.getProperties().put(postKey, prevPost);
        }
    }

    @Test
    void catalogEntryIsDiagnosticOnlyAndDerivesFromMatrixScratch() {
        final Optimization entry = OptimizationCatalog.RENDER_PASS_TRAVERSAL;
        assertFalse(entry.persisted());
        assertEquals("turboism.optimization.renderPassTraversal", entry.property());
        assertEquals(List.of("TURBOISM_RENDER_PASS_TRAVERSAL"), entry.protocols());
        assertEquals(1, entry.relations().size());
        assertEquals(
                Optimization.Relation.Kind.DERIVED_FROM,
                entry.relations().get(0).kind());
        assertEquals(
                OptimizationCatalog.MATRIX_SCRATCH, entry.relations().get(0).target());
    }
}
