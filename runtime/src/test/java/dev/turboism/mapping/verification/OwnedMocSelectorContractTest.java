package dev.turboism.mapping.verification;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the owned-Moc additive selector contract to the reviewed evidence sets.
 *
 * <p>All lifecycle selectors exist in both reviewed Core artifacts (5.2.03 and
 * 5.3.02) per the exported public-class observations, so one shared alias set is
 * required; the 5.2 profile never fails closed on version reads because the loader
 * uses byte-level {@code Live2DCubismCore.getMocVersion}.</p>
 */
class OwnedMocSelectorContractTest {

    @Test
    void ownedMocAliasesAreExactlyTheReviewedLifecycleSurface() {
        assertEquals(
            Set.of(
                "cubism.core.moc.class",
                "cubism.core.moc.instantiate",
                "cubism.core.moc.instantiate-model",
                "cubism.core.moc.get-native-handle",
                "cubism.core.moc.close",
                "cubism.core.model.get-native-handle",
                "cubism.core.model.update",
                "cubism.core.model.close"
            ),
            OwnedMocSelectorContract.REQUIRED_ALIASES
        );
        assertEquals(8, OwnedMocSelectorContract.REQUIRED_ALIASES.size());
        assertEquals(
            "adapter.core-model.readonly",
            OwnedMocSelectorContract.ADAPTER_SLICE_ID
        );
        assertEquals("cubism.core.owned-moc.read", OwnedMocSelectorContract.CAPABILITY_ID);
    }

    @Test
    void ownedMocAliasesAreSharedByBothReviewedProfiles() {
        final Set<String> aliases = OwnedMocSelectorContract.REQUIRED_ALIASES;
        assertFalse(aliases.contains("cubism.core.moc.get-moc-version"),
            "byte-level version reads must not require the 5.3.02-only CubismMoc.getMocVersion");
        assertFalse(aliases.contains("cubism.core.drawables.blend-modes"),
            "5.2 blend derivation must not require the 5.3.02-only blend-modes getter");
        assertTrue(aliases.contains(OwnedMocSelectorContract.MOC_INSTANTIATE));
        assertTrue(aliases.contains(OwnedMocSelectorContract.MODEL_UPDATE));
    }

    @Test
    void ownedMocSliceIsNotPartOfTheGeneratedRoster() {
        final Set<String> generated = CorePublicApiSelectorContract.REQUIRED_ALIASES_5_2;
        final Set<String> generated53 = CorePublicApiSelectorContract.REQUIRED_ALIASES_5_3_02;
        assertTrue(java.util.Collections.disjoint(
            OwnedMocSelectorContract.REQUIRED_ALIASES,
            generated
        ));
        assertTrue(java.util.Collections.disjoint(
            OwnedMocSelectorContract.REQUIRED_ALIASES,
            generated53
        ));
    }
}
