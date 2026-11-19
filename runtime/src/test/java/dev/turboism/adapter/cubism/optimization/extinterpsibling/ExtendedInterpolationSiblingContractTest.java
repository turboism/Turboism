package dev.turboism.adapter.cubism.optimization.extinterpsibling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.config.optimization.Optimization;
import dev.turboism.config.optimization.OptimizationCatalog;
import dev.turboism.config.optimization.OptimizationPreset;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Offline contract tests for {@link ExtendedInterpolationSiblingTarget}.
 */
final class ExtendedInterpolationSiblingContractTest {

    private static final int EXPECTED_TOTAL_TARGETS = 21;
    private static final int EXPECTED_METHOD_DEPENDENCIES = 9;
    private static final int SYNTHETIC_SIZE_BYTES = 1024;
    private static final int SHA256_HEX_LENGTH = 64;
    private static final HostArtifactDigest UNREVIEWED_DIGEST =
            new HostArtifactDigest(SYNTHETIC_SIZE_BYTES, "0".repeat(SHA256_HEX_LENGTH));

    @Test
    void targetsCoverAllReviewedReleasesAndRefuseUnreviewedDigest() {
        final List<ExtendedInterpolationSiblingTarget> targets = ExtendedInterpolationSiblingTarget.all();
        assertEquals(EXPECTED_TOTAL_TARGETS, targets.size());
        assertTrue(ExtendedInterpolationSiblingTarget.of(UNREVIEWED_DIGEST).isEmpty());

        for (final ExtendedInterpolationSiblingTarget target : targets) {
            assertEquals(
                    EXPECTED_METHOD_DEPENDENCIES, target.methodDependencies().size());
        }
    }

    @Test
    void catalogEntryPersistsInHotpathSectionWithReferenceLinkAcrossPresets() {
        final Optimization entry = OptimizationCatalog.EXTENDED_INTERPOLATION_SIBLING_ELISION;
        assertTrue(entry.persisted());
        assertEquals(Optimization.Access.PERSISTED_LAUNCHER, entry.access());
        assertEquals("extendedInterpolationSiblingElision", entry.key());
        assertEquals("extended-interpolation-sibling-elision", entry.contributionId());
        assertEquals(ExtendedInterpolationSiblingTarget.ENABLE_PROPERTY, entry.property());
        assertEquals(List.of("TURBOISM_EXTENDED_INTERPOLATION_SIBLING_ELISION"), entry.protocols());
        assertTrue(entry.relations().isEmpty());
        assertTrue(entry.referenceLink().isPresent());
        assertEquals(
                OptimizationCatalog.EXTENDED_INTERPOLATION_SIBLING_REFERENCE_URI,
                entry.referenceLink().orElseThrow().uri());

        assertTrue(
                OptimizationPreset.BALANCED.targetFlag(entry).orElseThrow(),
                "Balanced preset must activate sibling extended-interpolation elision");
        assertTrue(
                OptimizationPreset.HEAVY_EDITING.targetFlag(entry).orElseThrow(),
                "Heavy Editing preset must activate sibling extended-interpolation elision");
        assertFalse(
                OptimizationPreset.OFF.targetFlag(entry).orElseThrow(),
                "Off preset must keep sibling extended-interpolation elision disabled");
    }
}
