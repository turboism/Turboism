package dev.turboism.compatibility.policy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** Pins the pure selector admission predicate and its fail-closed evidence handling. */
class SelectorAdmissionPolicyTest {

    private static final Set<String> SUPPORTED_VERSIONS = Set.of("5.2.03", "5.3.02", "5.3.03", "5.3.04");
    private static final String CAPABILITY = "cubism.mesh-tool.session";
    private static final Set<String> REQUIRED_ALIASES =
            Set.of("cubism.editor-model.app-controller.instance", "cubism.editor-model.modeling-view.class");

    private static boolean admits(final String version, final Set<String> capabilities, final Set<String> aliases) {
        return SelectorAdmissionPolicy.admits(
                SUPPORTED_VERSIONS, CAPABILITY, REQUIRED_ALIASES, version, capabilities, aliases);
    }

    @Test
    void admitsDeepAdaptationTargetsWithCompleteEvidence() {
        assertTrue(admits("5.2.03", Set.of(CAPABILITY), REQUIRED_ALIASES));
        assertTrue(admits("5.3.02", Set.of(CAPABILITY), REQUIRED_ALIASES));
    }

    @Test
    void rejectsUnsupportedOrUnknownFutureVersion() {
        assertFalse(admits("5.2.02", Set.of(CAPABILITY), REQUIRED_ALIASES));
        assertFalse(admits("5.9.99", Set.of(CAPABILITY), REQUIRED_ALIASES));
        assertFalse(admits(null, Set.of(CAPABILITY), REQUIRED_ALIASES));
    }

    @Test
    void rejectsMissingOrInconsistentCapabilityEvidence() {
        assertFalse(admits("5.3.02", Set.of(), REQUIRED_ALIASES));
        assertFalse(admits("5.3.02", Set.of("cubism.editor-model.read"), REQUIRED_ALIASES));
        assertFalse(admits("5.3.02", null, REQUIRED_ALIASES));
    }

    @Test
    void rejectsPartiallyVerifiedAliasEvidence() {
        assertFalse(admits("5.3.02", Set.of(CAPABILITY), Set.of("cubism.editor-model.app-controller.instance")));
        assertFalse(admits("5.3.02", Set.of(CAPABILITY), Set.of()));
        assertFalse(admits("5.3.02", Set.of(CAPABILITY), null));
    }

    @Test
    void requiredRequirementDataMustBePresent() {
        assertThrows(
                NullPointerException.class,
                () -> SelectorAdmissionPolicy.admits(
                        null, CAPABILITY, REQUIRED_ALIASES, "5.3.02", Set.of(CAPABILITY), REQUIRED_ALIASES));
        assertThrows(
                NullPointerException.class,
                () -> SelectorAdmissionPolicy.admits(
                        SUPPORTED_VERSIONS, null, REQUIRED_ALIASES, "5.3.02", Set.of(CAPABILITY), REQUIRED_ALIASES));
        assertThrows(
                NullPointerException.class,
                () -> SelectorAdmissionPolicy.admits(
                        SUPPORTED_VERSIONS, CAPABILITY, null, "5.3.02", Set.of(CAPABILITY), REQUIRED_ALIASES));
    }
}
