package dev.turboism.core.descriptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The retired capability vocabulary: each synonym maps to one canonical id, and the map stays
 * closed so an unrecognised id is never silently reassigned to a capability it does not mean.
 */
final class CapabilityAliasesTest {

    @Test
    void parameterSynonymsCollapseToTheSingularIds() {
        assertEquals(CapabilityAliases.PARAMETER_READ, CapabilityAliases.canonical("cubism.parameters.read"));
        assertEquals(CapabilityAliases.PARAMETER_WRITE, CapabilityAliases.canonical("cubism.parameters.write"));
    }

    @Test
    void historySynonymsCollapseToTheEditorHistoryIds() {
        assertEquals(CapabilityAliases.HISTORY_READ, CapabilityAliases.canonical("cubism.history.read"));
        assertEquals(
                CapabilityAliases.HISTORY_READ, CapabilityAliases.canonical("cubism.editor-history.semantic-read"));
        assertEquals(CapabilityAliases.HISTORY_MOVE, CapabilityAliases.canonical("cubism.history.write"));
    }

    @Test
    void canonicalIdsAndUnrelatedIdsAreReturnedUnchanged() {
        for (final String id : List.of(
                CapabilityAliases.PARAMETER_READ,
                CapabilityAliases.PARAMETER_WRITE,
                CapabilityAliases.HISTORY_READ,
                CapabilityAliases.HISTORY_MOVE,
                "cubism.model.read",
                "ui.status.notify")) {
            assertEquals(id, CapabilityAliases.canonical(id));
        }
    }

    @Test
    void canonicalizeKeepsFirstDeclarationOrderAndDropsMigrationDuplicates() {
        assertEquals(
                List.of(CapabilityAliases.PARAMETER_READ, CapabilityAliases.HISTORY_READ),
                CapabilityAliases.canonicalize(
                        List.of("cubism.parameters.read", "cubism.parameter.read", "cubism.history.read")));
    }

    @Test
    void everyRetiredIdHasAReplacementAndNoReplacementIsItselfRetired() {
        assertFalse(CapabilityAliases.retiredIds().isEmpty());
        for (final String retired : CapabilityAliases.retiredIds()) {
            final String canonical = CapabilityAliases.replacementFor(retired).orElseThrow();
            assertFalse(
                    CapabilityAliases.retiredIds().contains(canonical),
                    "a canonical id must not itself be retired: " + canonical);
            assertTrue(CapabilityAliases.retiredIdsFor(canonical).contains(retired));
        }
    }
}
