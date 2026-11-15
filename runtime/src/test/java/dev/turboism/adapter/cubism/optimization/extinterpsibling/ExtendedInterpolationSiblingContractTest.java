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

    private static final int EXPECTED_TOTAL_TARGETS = 20;
    private static final int EXPECTED_METHOD_DEPENDENCIES = 9;
    private static final int SYNTHETIC_SIZE_BYTES = 1024;
    private static final int SHA256_HEX_LENGTH = 64;
    private static final HostArtifactDigest UNREVIEWED_DIGEST =
            new HostArtifactDigest(SYNTHETIC_SIZE_BYTES, "0".repeat(SHA256_HEX_LENGTH));

    @Test
    void targetsCoverAllTwentyReviewedReleasesAndRefuseUnreviewedDigest() {
        final List<ExtendedInterpolationSiblingTarget> targets = ExtendedInterpolationSiblingTarget.all();
        assertEquals(EXPECTED_TOTAL_TARGETS, targets.size());
        assertTrue(ExtendedInterpolationSiblingTarget.of(UNREVIEWED_DIGEST).isEmpty());

        for (final ExtendedInterpolationSiblingTarget target : targets) {
            assertEquals(EXPECTED_METHOD_DEPENDENCIES, target.methodDependencies().size());
        }
    }

    @Test
    void catalogEntryDerivesFromExtendedInterpolationIncrementalSetupAcrossPresets() {
        final Optimization entry = OptimizationCatalog.EXTENDED_INTERPOLATION_SIBLING_ELISION;
        assertFalse(entry.persisted());
        assertEquals(ExtendedInterpolationSiblingTarget.ENABLE_PROPERTY, entry.property());
        assertEquals(List.of("TURBOISM_EXTENDED_INTERPOLATION_SIBLING_ELISION"), entry.protocols());
        assertEquals(1, entry.relations().size());
        assertEquals(Optimization.Relation.Kind.DERIVED_FROM, entry.relations().get(0).kind());
        assertEquals(
                OptimizationCatalog.EXTENDED_INTERPOLATION_INCREMENTAL_SETUP,
                entry.relations().get(0).target());

        assertTrue(
                OptimizationPreset.BALANCED
                        .targetFlag(OptimizationCatalog.EXTENDED_INTERPOLATION_INCREMENTAL_SETUP)
                        .orElseThrow(),
                "Balanced preset must activate extended-interpolation incremental setup");
        assertTrue(
                OptimizationPreset.HEAVY_EDITING
                        .targetFlag(OptimizationCatalog.EXTENDED_INTERPOLATION_INCREMENTAL_SETUP)
                        .orElseThrow(),
                "Heavy Editing preset must activate extended-interpolation incremental setup");
        assertFalse(
                OptimizationPreset.OFF
                        .targetFlag(OptimizationCatalog.EXTENDED_INTERPOLATION_INCREMENTAL_SETUP)
                        .orElseThrow(),
                "Off preset must keep extended-interpolation incremental setup disabled");
    }
}
