package dev.turboism.mapping.verification;

import dev.turboism.mapping.verification.selector.EditorObjectHierarchyEditSelectorContract;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Static candidate records must not grant unverified production mutation authority. */
class ApplyToChildrenCandidateAdmissionTest {
    @Test
    void candidateIsNotAProductionCapability() {
        assertFalse(EditorModelVerificationManifest.CAPABILITY_IDS.contains(
            EditorObjectHierarchyEditSelectorContract.APPLY_TO_CHILDREN_CAPABILITY_ID
        ));
    }

    @Test
    void exact5302CandidateAliasDoesNotLeakIntoOtherVersionRosters() {
        for (String alias : EditorObjectHierarchyEditSelectorContract.APPLY_TO_CHILDREN_5302_ONLY_ALIASES) {
            assertTrue(EditorModelVerificationManifest.REQUIRED_ALIASES.contains(alias));
            assertFalse(EditorModelVerificationManifest.cubism52Aliases().contains(alias));
            assertFalse(EditorModelVerificationManifest.cubism5303StaticAliases().contains(alias));
        }
    }
}
