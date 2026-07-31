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
                action.accept(contribution.actionId());
            }
        );

        host.addItem(descriptor("later", 20, ContextMenuRegistry.ObjectKind.WARP_DEFORMER),
            (actual, actionId) -> actions.add("later:" + actual.items().get(0).id()));
        host.addItem(descriptor("ignored", 5, ContextMenuRegistry.ObjectKind.ART_MESH),
            (actual, actionId) -> actions.add("ignored"));
        host.addItem(descriptor("first", 10, ContextMenuRegistry.ObjectKind.WARP_DEFORMER),
            (actual, actionId) -> actions.add("first:" + actual.items().get(0).id()));

        assertSame(menu, host.augment(menu, ContextMenuRegistry.Location.DEFORMER_TAB, source));
        assertEquals(List.of("first", "later"), appended);
        assertEquals(List.of("first:warp-a", "later:warp-a"), actions);
    }

    @Test
    void rendersSeparatorAndNestedSubmenuInPriorityOrder() {
        final List<String> rendered = new ArrayList<>();
        final VerifiedObjectContextMenuHostOperations host = new VerifiedObjectContextMenuHostOperations(
            (location, source) -> selection(ContextMenuRegistry.ObjectKind.WARP_DEFORMER),
            (menu, contribution, action) -> rendered.add(
                contribution.entry().kind() + ":" + contribution.entry().id()
            )
        );

        host.addItem(descriptor(
            "separator", 20, ContextMenuRegistry.ObjectKind.WARP_DEFORMER,
            ContextMenuRegistry.ContextMenuEntry.separator("separator")
        ), (selection, actionId) -> {});
        host.addItem(descriptor(
            "tools", 30, ContextMenuRegistry.ObjectKind.WARP_DEFORMER,
            ContextMenuRegistry.ContextMenuEntry.submenu(
                "tools", "Tools",
                List.of(ContextMenuRegistry.ContextMenuEntry.item("child", "Child", "action.child"))
            )
        ), (selection, actionId) -> {});

        host.augment(new Object(), ContextMenuRegistry.Location.DEFORMER_TAB, new Object());

        assertEquals(List.of("SEPARATOR:separator", "SUBMENU:tools"), rendered);
    }

    @Test
    void registrationRemovalAndResolverFailureKeepTheOriginalMenu() {
        final List<String> appended = new ArrayList<>();
        final VerifiedObjectContextMenuHostOperations host = new VerifiedObjectContextMenuHostOperations(
            (location, source) -> { throw new IllegalStateException("unavailable"); },
            (menu, contribution, action) -> appended.add(contribution.contributionId())
        );
        final Registration registration = host.addItem(
            descriptor("warp", 10, ContextMenuRegistry.ObjectKind.WARP_DEFORMER),
            (selection, actionId) -> {}
        );
        final Object menu = new Object();

        assertSame(menu, host.augment(menu, ContextMenuRegistry.Location.DEFORMER_TAB, new Object()));
        assertEquals(List.of(), appended);
        registration.close();
        assertSame(menu, host.augment(menu, ContextMenuRegistry.Location.DEFORMER_TAB, new Object()));
        assertEquals(List.of(), appended);
    }

    @Test
    void persistentParameterPointMenusResolveCurrentGuidAndRemoveItemsOnUnregister() {
        final List<String> appended = new ArrayList<>();
        final List<String> removed = new ArrayList<>();
        final List<String> actions = new ArrayList<>();
        final java.util.concurrent.atomic.AtomicReference<ContextMenuSelection> current =
            new java.util.concurrent.atomic.AtomicReference<>(parameterSelection("parameter-a"));
        final VerifiedObjectContextMenuHostOperations host = new VerifiedObjectContextMenuHostOperations(
            (location, source) -> { throw new AssertionError("ordinary resolver must not be used"); },
            (menu, contribution, action) -> {},
            (menu, contribution, action) -> {
                appended.add(contribution.contributionId());
                action.accept(contribution.actionId());
                return () -> removed.add(contribution.contributionId());
            }
        );
        final Registration registration = host.addItem(
            parameterDescriptor("point"),
            (selection, actionId) -> actions.add(selection.items().get(0).id())
        );

        host.installPersistent(new Object(), ContextMenuRegistry.Location.PARAMETER_TAB, current::get);
        current.set(parameterSelection("parameter-b"));
        host.installPersistent(new Object(), ContextMenuRegistry.Location.PARAMETER_TAB, current::get);

        assertEquals(List.of("point", "point"), appended);
        assertEquals(List.of("parameter-a", "parameter-b"), actions);
        registration.close();
        assertEquals(List.of("point", "point"), removed);
    }

    private static ContextMenuContributionDescriptor parameterDescriptor(final String id) {
        return new ContextMenuContributionDescriptor(
            "plugin-a", id, "action." + id, id, null,
            ContextMenuRegistry.Location.PARAMETER_TAB,
            Set.of(ContextMenuRegistry.ObjectKind.PARAMETER), 10
        );
    }

    private static ContextMenuSelection parameterSelection(final String id) {
        return new ContextMenuSelection(
            7, "document-a", ContextMenuRegistry.Location.PARAMETER_TAB,
            List.of(new ContextMenuSelection.Item(ContextMenuRegistry.ObjectKind.PARAMETER, id))
        );
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

    private static ContextMenuContributionDescriptor descriptor(
        final String id,
        final int priority,
        final ContextMenuRegistry.ObjectKind kind,
        final ContextMenuRegistry.ContextMenuEntry entry
    ) {
        return new ContextMenuContributionDescriptor(
            "plugin-a", id, "action." + id, id, null,
            ContextMenuRegistry.Location.DEFORMER_TAB, Set.of(kind), priority,
            entry, ContextMenuRegistry.Placement.last()
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
