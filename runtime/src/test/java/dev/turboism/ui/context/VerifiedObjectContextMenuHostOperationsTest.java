package dev.turboism.ui.context;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.context.ContextMenuSelection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class VerifiedObjectContextMenuHostOperationsTest {

    @Test
    void appendsOnlyMatchingItemsInPriorityOrderAndRoutesTheCapturedSelection() {
        final List<String> appended = new ArrayList<>();
        final List<String> actions = new ArrayList<>();
        final Object menu = new Object();
        final Object source = new Object();
        final ContextMenuSelection selection = selection(ContextMenuRegistry.ObjectKind.WARP_DEFORMER);
        final VerifiedObjectContextMenuHostOperations host = new VerifiedObjectContextMenuHostOperations(
            (location, actualSource) -> {
                assertEquals(ContextMenuRegistry.Location.DEFORMER_TAB, location);
                assertSame(source, actualSource);
                return selection;
            },
            (actualMenu, contribution, action) -> {
                assertSame(menu, actualMenu);
                appended.add(contribution.contributionId());
                action.run();
            }
        );

        host.addItem(descriptor("later", 20, ContextMenuRegistry.ObjectKind.WARP_DEFORMER),
            actual -> actions.add("later:" + actual.items().get(0).id()));
        host.addItem(descriptor("ignored", 5, ContextMenuRegistry.ObjectKind.ART_MESH),
            actual -> actions.add("ignored"));
        host.addItem(descriptor("first", 10, ContextMenuRegistry.ObjectKind.WARP_DEFORMER),
            actual -> actions.add("first:" + actual.items().get(0).id()));

        assertSame(menu, host.augment(menu, ContextMenuRegistry.Location.DEFORMER_TAB, source));
        assertEquals(List.of("first", "later"), appended);
        assertEquals(List.of("first:warp-a", "later:warp-a"), actions);
    }

    @Test
    void registrationRemovalAndResolverFailureKeepTheOriginalMenu() {
        final List<String> appended = new ArrayList<>();
        final VerifiedObjectContextMenuHostOperations host = new VerifiedObjectContextMenuHostOperations(
            (location, source) -> { throw new IllegalStateException("unavailable"); },
            (menu, contribution, action) -> appended.add(contribution.contributionId())
        );
        final Registration registration = host.addItem(
            descriptor("warp", 10, ContextMenuRegistry.ObjectKind.WARP_DEFORMER), selection -> {}
        );
        final Object menu = new Object();

        assertSame(menu, host.augment(menu, ContextMenuRegistry.Location.DEFORMER_TAB, new Object()));
        assertEquals(List.of(), appended);
        registration.close();
        assertSame(menu, host.augment(menu, ContextMenuRegistry.Location.DEFORMER_TAB, new Object()));
        assertEquals(List.of(), appended);
    }

    private static ContextMenuContributionDescriptor descriptor(
        final String id,
        final int priority,
        final ContextMenuRegistry.ObjectKind kind
    ) {
        return new ContextMenuContributionDescriptor(
            "plugin-a", id, "action." + id, id, null,
            ContextMenuRegistry.Location.DEFORMER_TAB, Set.of(kind), priority
        );
    }

    private static ContextMenuSelection selection(final ContextMenuRegistry.ObjectKind kind) {
        return new ContextMenuSelection(
            7,
            "document-a",
            ContextMenuRegistry.Location.DEFORMER_TAB,
            List.of(new ContextMenuSelection.Item(kind, "warp-a"))
        );
    }
}
