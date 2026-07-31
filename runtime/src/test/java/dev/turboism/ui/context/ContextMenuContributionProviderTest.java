package dev.turboism.ui.context;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.context.ContextMenuSelection;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.contribution.EditorUiContributionIdentity;
import dev.turboism.ui.contribution.EditorUiProviderAdmission;
import dev.turboism.ui.host.EditorUiFamily;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextMenuContributionProviderTest {

    @Test
    void filtersByLocationAndRequiresEverySelectedObjectToMatch() {
        ContextMenuContributionDescriptor warpOnly = descriptor(
            ContextMenuRegistry.Location.DEFORMER_TAB,
            Set.of(ContextMenuRegistry.ObjectKind.WARP_DEFORMER)
        );

        assertTrue(warpOnly.matches(selection(
            ContextMenuRegistry.Location.DEFORMER_TAB,
            ContextMenuRegistry.ObjectKind.WARP_DEFORMER,
            ContextMenuRegistry.ObjectKind.WARP_DEFORMER
        )));
        assertFalse(warpOnly.matches(selection(
            ContextMenuRegistry.Location.DEFORMER_TAB,
            ContextMenuRegistry.ObjectKind.WARP_DEFORMER,
            ContextMenuRegistry.ObjectKind.ART_MESH
        )));
        assertFalse(warpOnly.matches(selection(
            ContextMenuRegistry.Location.PART_TAB,
            ContextMenuRegistry.ObjectKind.WARP_DEFORMER
        )));
        assertFalse(warpOnly.matches(selection(ContextMenuRegistry.Location.DEFORMER_TAB)));
    }

    @Test
    void rejectsObjectKindsThatCannotAppearAtTheDeclaredLocation() {
        assertThrows(IllegalArgumentException.class, () -> contribution(
            ContextMenuRegistry.Location.PARAMETER_TAB,
            Set.of(ContextMenuRegistry.ObjectKind.GLUE)
        ));
        assertThrows(IllegalArgumentException.class, () -> contribution(
            ContextMenuRegistry.Location.DEFORMER_TAB,
            Set.of(ContextMenuRegistry.ObjectKind.PARAMETER_FOLDER)
        ));
    }

    @Test
    void installsContributionRoutesTypedSelectionAndCleansUp() {
        RecordingHost host = new RecordingHost();
        List<String> actions = new ArrayList<>();
        ContextMenuContributionProvider provider = new ContextMenuContributionProvider(
            admission(7),
            host,
            (pluginId, actionId, context) -> actions.add(
                pluginId + ":" + actionId + ":" + context.contextMenuSelection().orElseThrow().items().get(0).kind()
            )
        );

        Registration registration = provider.apply(7, List.of(new EditorUiContribution<>(
            new EditorUiContributionIdentity("plugin-a", EditorUiFamily.CONTEXT_MENU, "warp"),
            10,
            contribution(
                ContextMenuRegistry.Location.DEFORMER_TAB,
                Set.of(ContextMenuRegistry.ObjectKind.WARP_DEFORMER)
            )
        )));

        host.action.run(selection(
            ContextMenuRegistry.Location.DEFORMER_TAB,
            ContextMenuRegistry.ObjectKind.WARP_DEFORMER
        ), "action.warp");
        assertEquals(List.of("plugin-a:action.warp:WARP_DEFORMER"), actions);

        registration.close();
        assertEquals(List.of("warp"), host.closedIds);
    }

    private static ContextMenuRegistry.ContextMenuContribution contribution(
        final ContextMenuRegistry.Location location,
        final Set<ContextMenuRegistry.ObjectKind> kinds
    ) {
        return new ContextMenuRegistry.ContextMenuContribution(
            "warp",
            "action.warp",
            "Warp action",
            null,
            location,
            kinds,
            10
        );
    }

    private static ContextMenuContributionDescriptor descriptor(
        final ContextMenuRegistry.Location location,
        final Set<ContextMenuRegistry.ObjectKind> kinds
    ) {
        return ContextMenuContributionDescriptor.from(new EditorUiContribution<>(
            new EditorUiContributionIdentity("plugin-a", EditorUiFamily.CONTEXT_MENU, "warp"),
            10,
            contribution(location, kinds)
        ));
    }

    private static ContextMenuSelection selection(
        final ContextMenuRegistry.Location location,
        final ContextMenuRegistry.ObjectKind... kinds
    ) {
        List<ContextMenuSelection.Item> items = new ArrayList<>();
        for (int index = 0; index < kinds.length; index++) {
            items.add(new ContextMenuSelection.Item(kinds[index], "id-" + index));
        }
        return new ContextMenuSelection(7, "document-a", location, items);
    }

    private static EditorUiProviderAdmission admission(final long generation) {
        return EditorUiProviderAdmission.admitted(
            EditorUiFamily.CONTEXT_MENU,
            generation,
            new EditorUiProviderAdmission.VerificationEvidence(
                "5.3.02",
                42,
                "a".repeat(64),
                "adapter.editor-ui.object-context-menu",
                "b".repeat(64)
            )
        );
    }

    private static final class RecordingHost implements ContextMenuHostOperations {
        private final List<String> closedIds = new ArrayList<>();
        private ContextMenuHostOperations.MenuAction action;

        @Override
        public Registration addItem(
            final ContextMenuContributionDescriptor contribution,
            final ContextMenuHostOperations.MenuAction action
        ) {
            this.action = action;
            return () -> closedIds.add(contribution.contributionId());
        }
    }
}
