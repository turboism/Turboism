package dev.turboism.sdk.ui.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** visibleWhen predicate storage semantics on the SDK contribution record. */
class ContextMenuRegistryVisibleWhenTest {

    private static final Set<ContextMenuRegistry.ObjectKind> KINDS = Set.of(
        ContextMenuRegistry.ObjectKind.ART_MESH,
        ContextMenuRegistry.ObjectKind.WARP_DEFORMER,
        ContextMenuRegistry.ObjectKind.ROTATION_DEFORMER
    );

    private static ContextMenuSelection single() {
        return new ContextMenuSelection(
            1L,
            "document-a",
            ContextMenuRegistry.Location.DEFORMER_TAB,
            List.of(new ContextMenuSelection.Item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1"))
        );
    }

    private static ContextMenuSelection multiple() {
        return new ContextMenuSelection(
            1L,
            "document-a",
            ContextMenuRegistry.Location.DEFORMER_TAB,
            List.of(
                new ContextMenuSelection.Item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1"),
                new ContextMenuSelection.Item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-2")
            )
        );
    }

    @Test
    void newConstructorStoresThePredicate() {
        Predicate<ContextMenuSelection> visibleWhen = selection -> selection.items().size() == 1;

        ContextMenuRegistry.ContextMenuContribution contribution =
            new ContextMenuRegistry.ContextMenuContribution(
                "contribution-a", "action-a", "Label A", null,
                ContextMenuRegistry.Location.DEFORMER_TAB, KINDS, 100, visibleWhen
            );

        assertSame(visibleWhen, contribution.visibleWhen());
    }

    @Test
    void legacyConstructorsDefaultVisibleWhenToNull() {
        ContextMenuRegistry.ContextMenuContribution byLocation =
            new ContextMenuRegistry.ContextMenuContribution(
                "contribution-a", "action-a", "Label A", null,
                ContextMenuRegistry.Location.DEFORMER_TAB, KINDS, 100
            );
        ContextMenuRegistry.ContextMenuContribution byEntry =
            new ContextMenuRegistry.ContextMenuContribution(
                "contribution-b", ContextMenuRegistry.Location.DEFORMER_TAB, KINDS, 100,
                ContextMenuRegistry.ContextMenuEntry.item("contribution-b", "Label B", "action-b")
            );
        ContextMenuRegistry.ContextMenuContribution byContextString =
            new ContextMenuRegistry.ContextMenuContribution(
                "contribution-c", "Label C", null, "deformer", 100
            );
        ContextMenuRegistry.ContextMenuContribution byPanelTab =
            new ContextMenuRegistry.ContextMenuContribution(
                "contribution-d", "Label D", null, "deformer", 100,
                ContextMenuRegistry.Target.SELECTION, ContextMenuRegistry.Operation.ACTION
            );
        ContextMenuRegistry.ContextMenuContribution byFullShape =
            new ContextMenuRegistry.ContextMenuContribution(
                "contribution-e", "action-e", "Label E", null, "deformer",
                ContextMenuRegistry.Location.DEFORMER_TAB, KINDS, 100,
                ContextMenuRegistry.Target.SELECTION, ContextMenuRegistry.Operation.ACTION,
                ContextMenuRegistry.ContextMenuEntry.item("contribution-e", "Label E", "action-e"),
                ContextMenuRegistry.Placement.last()
            );

        assertNull(byLocation.visibleWhen());
        assertNull(byEntry.visibleWhen());
        assertNull(byContextString.visibleWhen());
        assertNull(byPanelTab.visibleWhen());
        assertNull(byFullShape.visibleWhen());
    }

    @Test
    void canonicalConstructorAllowsNullPredicate() {
        ContextMenuRegistry.ContextMenuContribution contribution =
            new ContextMenuRegistry.ContextMenuContribution(
                "contribution-a", "action-a", "Label A", null, "deformer",
                ContextMenuRegistry.Location.DEFORMER_TAB, KINDS, 100,
                ContextMenuRegistry.Target.SELECTION, ContextMenuRegistry.Operation.ACTION,
                ContextMenuRegistry.ContextMenuEntry.item("contribution-a", "Label A", "action-a"),
                ContextMenuRegistry.Placement.last(),
                null
            );

        assertNull(contribution.visibleWhen());
    }

    @Test
    void predicateIsInvokedWithTheSelection() {
        final boolean[] invoked = {false};
        ContextMenuRegistry.ContextMenuContribution contribution =
            new ContextMenuRegistry.ContextMenuContribution(
                "contribution-a", "action-a", "Label A", null,
                ContextMenuRegistry.Location.DEFORMER_TAB, KINDS, 100,
                selection -> {
                    invoked[0] = true;
                    return true;
                }
            );

        assertTrue(contribution.visibleWhen().test(single()));
        assertTrue(invoked[0]);
    }

    @Test
    void predicatesEvaluatedAsSpecifiedForSingleAndMultipleSelections() {
        Predicate<ContextMenuSelection> visibleWhen = selection -> selection.items().size() == 1;
        ContextMenuRegistry.ContextMenuContribution contribution =
            new ContextMenuRegistry.ContextMenuContribution(
                "contribution-a", "action-a", "Label A", null,
                ContextMenuRegistry.Location.DEFORMER_TAB, KINDS, 100, visibleWhen
            );

        assertTrue(contribution.visibleWhen().test(single()));
        assertFalse(contribution.visibleWhen().test(multiple()));
    }

    @Test
    void visibleWhenDoesNotAffectLegacyValidation() {
        ContextMenuRegistry.ContextMenuContribution contribution =
            new ContextMenuRegistry.ContextMenuContribution(
                "contribution-a", "action-a", "Label A", null,
                ContextMenuRegistry.Location.DEFORMER_TAB, KINDS, 100,
                selection -> false
            );

        assertNotNull(contribution);
        assertEquals("contribution-a", contribution.id());
        assertEquals("action-a", contribution.actionId());
        assertEquals(ContextMenuRegistry.Location.DEFORMER_TAB, contribution.location());
        assertEquals(KINDS, contribution.objectKinds());
    }
}
