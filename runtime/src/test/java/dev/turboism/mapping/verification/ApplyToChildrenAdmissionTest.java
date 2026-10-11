package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.selector.EditorObjectHierarchyEditSelectorContract;
import org.junit.jupiter.api.Test;

/** The apply-to-children command verifies on every reviewed 5.2/5.3 record, so its
 * capability and command alias must stay inside every admitted roster. */
class ApplyToChildrenAdmissionTest {
    @Test
    void applyToChildrenIsAProductionCapability() {
        assertTrue(EditorModelVerificationManifest.CAPABILITY_IDS.contains(
                EditorObjectHierarchyEditSelectorContract.APPLY_TO_CHILDREN_CAPABILITY_ID));
        assertTrue(EditorModelVerificationManifest.cubism52Capabilities().contains(
                EditorObjectHierarchyEditSelectorContract.APPLY_TO_CHILDREN_CAPABILITY_ID));
        assertTrue(EditorModelVerificationManifest.cubism5303Capabilities().contains(
                EditorObjectHierarchyEditSelectorContract.APPLY_TO_CHILDREN_CAPABILITY_ID));
    }

    @Test
    void commandAliasStaysInsideEveryVersionRoster() {
        for (String alias : EditorObjectHierarchyEditSelectorContract.APPLY_TO_CHILDREN_REQUIRED_ALIASES) {
            assertTrue(EditorModelVerificationManifest.REQUIRED_ALIASES.contains(alias));
            assertTrue(EditorModelVerificationManifest.cubism52Aliases().contains(alias));
            assertTrue(
                    EditorModelVerificationManifest.cubism5303StaticAliases().contains(alias));
        }
    }
}
