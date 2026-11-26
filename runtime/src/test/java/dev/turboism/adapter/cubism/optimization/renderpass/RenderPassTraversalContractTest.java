package dev.turboism.adapter.cubism.optimization.renderpass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.config.optimization.Optimization;
import dev.turboism.config.optimization.OptimizationCatalog;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiFunction;
import kotlin.Pair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Offline contract tests for {@link RenderPassTraversalTarget} and {@link RenderPassTraversalBridge}.
 */
final class RenderPassTraversalContractTest {

    private static final int EXPECTED_TOTAL_TARGETS = 21;
    private static final int EXPECTED_PRE_53_TARGETS = 15;
    private static final int EXPECTED_53_TARGETS = 6;
    private static final HostArtifactDigest UNREVIEWED_DIGEST = new HostArtifactDigest(1024, "0".repeat(64));

    @Test
    void targetsCoverAllReviewedReleasesWithVersionRoutedDescriptors() {
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
    @SuppressWarnings("unchecked")
    void bridgeCompilesCanonicalUniformPairResolverAndRestoresPropertyOnClose(@TempDir final Path tempDir)
            throws Exception {
        final String pairKey = RenderPassTraversalBridge.UNIFORM_PAIR_PROPERTY;
        final Object prevPair = System.getProperties().get(pairKey);
        final URL kotlinJar = Pair.class.getProtectionDomain().getCodeSource().getLocation();
        assertNotNull(kotlinJar);
        try (URLClassLoader hostLoader = new URLClassLoader(
                        new URL[] {tempDir.toUri().toURL(), kotlinJar}, ClassLoader.getPlatformClassLoader());
                RenderPassTraversalBridge bridge = new RenderPassTraversalBridge(hostLoader)) {
            assertTrue(bridge.hasUniformPairResolver());
            bridge.install();

            final Object published = System.getProperties().get(pairKey);
            assertTrue(published instanceof BiFunction<?, ?, ?>);
            final BiFunction<Object, Object, Object> resolver = (BiFunction<Object, Object, Object>) published;

            final Object base1 = resolver.apply("baseColor", null);
            final Object base2 = resolver.apply(new String("baseColor"), null);
            final Object mult1 = resolver.apply("multiplyColor", null);
            final Object screen1 = resolver.apply("screenColor", null);
            final Object custom1 = resolver.apply("pointSteps", 7);
            final Object custom2 = resolver.apply("pointSteps", 7);
            assertTrue(base1.getClass().getClassLoader() == hostLoader || base1 instanceof Pair);
            assertSame(base1, base2);
            assertSame(mult1, resolver.apply(new String("multiplyColor"), null));
            assertSame(screen1, resolver.apply(new String("screenColor"), null));
            assertSame(custom1, custom2);

            bridge.close();
            if (prevPair == null) {
                assertNull(System.getProperties().get(pairKey));
            } else {
                assertSame(prevPair, System.getProperties().get(pairKey));
            }
        } finally {
            if (prevPair == null) System.getProperties().remove(pairKey);
            else System.getProperties().put(pairKey, prevPair);
        }
    }

    @Test
    void catalogEntryPersistsAsHotpathLauncherOptimization() {
        final Optimization entry = OptimizationCatalog.RENDER_PASS_TRAVERSAL;
        assertTrue(entry.persisted());
        assertEquals(Optimization.Access.PERSISTED_LAUNCHER, entry.access());
        assertEquals("renderPassTraversal", entry.key());
        assertEquals("render-pass-traversal", entry.contributionId());
        assertEquals("turboism.optimization.renderPassTraversal", entry.property());
        assertEquals(List.of("TURBOISM_RENDER_PASS_TRAVERSAL"), entry.protocols());
        assertTrue(entry.relations().isEmpty());
    }
}
