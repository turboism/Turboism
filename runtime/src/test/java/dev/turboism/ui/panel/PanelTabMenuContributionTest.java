package dev.turboism.ui.panel;

import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PanelTabMenuContributionTest {

    @Test
    void rejectsNonPanelTabContributionsAtTheRuntimeBoundary() {
        ContextMenuRegistry.ContextMenuContribution selection = new ContextMenuRegistry.ContextMenuContribution(
                "selection",
                "selection.action",
                "Selection",
                null,
                ContextMenuRegistry.Location.PARAMETER_TAB,
                Set.of(ContextMenuRegistry.ObjectKind.PARAMETER),
                10);

        assertThrows(IllegalArgumentException.class, () -> new PanelTabMenuContribution(7, "plugin", selection));
    }

    @Test
    void acceptsPanelTabContributionsWithoutNativeObjects() {
        new PanelTabMenuContribution(
                7,
                "plugin",
                new ContextMenuRegistry.ContextMenuContribution(
                        "float",
                        "panel.toggle",
                        "Float",
                        null,
                        "panel.docked",
                        ContextMenuRegistry.Location.WORKSPACE_OBJECT,
                        Set.of(),
                        100,
                        ContextMenuRegistry.Target.PANEL_TAB,
                        ContextMenuRegistry.Operation.TOGGLE_PANEL_FLOATING,
                        ContextMenuRegistry.ContextMenuEntry.item("float", "Float", "panel.toggle"),
                        ContextMenuRegistry.Placement.last()));
    }
}
