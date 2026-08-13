package dev.turboism.ui.context;

import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.context.ContextMenuSelection;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.contribution.EditorUiContributionIdentity;
import dev.turboism.ui.host.EditorUiFamily;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** visibleWhen evaluation semantics on the runtime descriptor. */
class ContextMenuContributionDescriptorVisibleWhenTest {

    private static final Set<ContextMenuRegistry.ObjectKind> KINDS = Set.of(
        ContextMenuRegistry.ObjectKind.ART_MESH,
        ContextMenuRegistry.ObjectKind.WARP_DEFORMER,
        ContextMenuRegistry.ObjectKind.ROTATION_DEFORMER
    );

    private static ContextMenuSelection selection(final int itemCount) {
        final java.util.ArrayList<ContextMenuSelection.Item> items = new java.util.ArrayList<>();
        for (int index = 0; index < itemCount; index++) {
            items.add(new ContextMenuSelection.Item(
                ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-" + index
            ));
        }
        return new ContextMenuSelection(
            1L, "document-a", ContextMenuRegistry.Location.DEFORMER_TAB, items
        );
    }

    private static ContextMenuContributionDescriptor descriptor(
        final Predicate<ContextMenuSelection> visibleWhen
    ) {
        return new ContextMenuContributionDescriptor(
            "plugin-a", "contribution-a", "action-a", "Label A", null,
            ContextMenuRegistry.Location.DEFORMER_TAB, KINDS, 100, visibleWhen
        );
    }

    @Test
    void nullPredicateKeepsLegacyMatchingBehavior() {
        final ContextMenuContributionDescriptor descriptor = descriptor(null);

        assertTrue(descriptor.matches(selection(1)));
        assertTrue(descriptor.matches(selection(2)));
        assertFalse(descriptor.matches(new ContextMenuSelection(
            1L, "document-a", ContextMenuRegistry.Location.PART_TAB, List.of(
                new ContextMenuSelection.Item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-0")
            )
        )));
    }

    @Test
    void singleItemSelectionMatches() {
        final ContextMenuContributionDescriptor descriptor = descriptor(
            value -> value.items().size() == 1
        );

        assertTrue(descriptor.matches(selection(1)));
    }

    @Test
    void multiItemSelectionDoesNotMatch() {
        final ContextMenuContributionDescriptor descriptor = descriptor(
            value -> value.items().size() == 1
        );

        assertFalse(descriptor.matches(selection(2)));
    }

    @Test
    void predicateReceivesTheSelectionBeingMatched() {
        final ContextMenuSelection expected = selection(1);
        final ContextMenuSelection[] received = new ContextMenuSelection[1];
        final ContextMenuContributionDescriptor descriptor = descriptor(value -> {
            received[0] = value;
            return true;
        });

        assertTrue(descriptor.matches(expected));
        assertSame(expected, received[0]);
    }

    @Test
    void kindOrLocationMismatchShortCircuitsBeforePredicate() {
        final boolean[] invoked = {false};
        final ContextMenuContributionDescriptor descriptor = new ContextMenuContributionDescriptor(
            "plugin-a", "contribution-a", "action-a", "Label A", null,
            ContextMenuRegistry.Location.DEFORMER_TAB, KINDS, 100,
            value -> {
                invoked[0] = true;
                return true;
            }
        );

        assertFalse(descriptor.matches(new ContextMenuSelection(
            1L, "document-a", ContextMenuRegistry.Location.PART_TAB, List.of(
                new ContextMenuSelection.Item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-0")
            )
        )));
        assertFalse(invoked[0]);
    }

    @Test
    void fromPassthroughCarriesThePredicate() {
        final Predicate<ContextMenuSelection> visibleWhen = value -> value.items().size() == 1;
        final ContextMenuRegistry.ContextMenuContribution contribution =
            new ContextMenuRegistry.ContextMenuContribution(
                "contribution-a", "action-a", "Label A", null,
                ContextMenuRegistry.Location.DEFORMER_TAB, KINDS, 100, visibleWhen
            );

        final ContextMenuContributionDescriptor descriptor =
            ContextMenuContributionDescriptor.from(new EditorUiContribution<>(
                new EditorUiContributionIdentity("plugin-a", EditorUiFamily.CONTEXT_MENU, "contribution-a"),
                100,
                contribution
            ));

        assertSame(visibleWhen, descriptor.visibleWhen());
        assertTrue(descriptor.matches(selection(1)));
        assertFalse(descriptor.matches(selection(2)));
    }

    @Test
    void legacyConstructorsDefaultVisibleWhenToNull() {
        final ContextMenuContributionDescriptor shortForm = new ContextMenuContributionDescriptor(
            "plugin-a", "contribution-a", "action-a", "Label A", null,
            ContextMenuRegistry.Location.DEFORMER_TAB, KINDS, 100
        );
        final ContextMenuContributionDescriptor fullForm = new ContextMenuContributionDescriptor(
            "plugin-a", "contribution-a", "action-a", "Label A", null,
            ContextMenuRegistry.Location.DEFORMER_TAB, KINDS, 100,
            ContextMenuRegistry.ContextMenuEntry.item("contribution-a", "Label A", "action-a"),
            ContextMenuRegistry.Placement.last()
        );

        assertNull(shortForm.visibleWhen());
        assertNull(fullForm.visibleWhen());
        assertTrue(shortForm.matches(selection(2)));
    }
}
