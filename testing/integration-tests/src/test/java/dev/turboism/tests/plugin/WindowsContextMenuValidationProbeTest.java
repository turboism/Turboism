package dev.turboism.tests.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verdict-seam tests for {@link WindowsContextMenuValidationProbe}'s contributed menu tree. */
final class WindowsContextMenuValidationProbeTest {

    @Test
    void probeEntryBuildsExpectedMenuTree() {
        final ContextMenuRegistry.ContextMenuEntry entry = WindowsContextMenuValidationProbe.probeEntry("parameter");
        assertEquals("turboism-validation-parameter", entry.id());
        assertEquals("Turboism Validation parameter", entry.label());
        assertEquals(ContextMenuRegistry.Placement.first(), entry.placement());

        final List<ContextMenuRegistry.ContextMenuEntry> children = entry.children();
        assertEquals(6, children.size());
        assertEquals("anchor", children.get(0).id());
        assertEquals(
                ContextMenuRegistry.Placement.before("Anchor"), children.get(1).placement());
        assertEquals(
                ContextMenuRegistry.Placement.after("Anchor"), children.get(2).placement());
        assertEquals(ContextMenuRegistry.Placement.first(), children.get(3).placement());
        assertEquals(ContextMenuRegistry.EntryKind.SEPARATOR, children.get(4).kind());
        assertEquals(ContextMenuRegistry.EntryKind.SUBMENU, children.get(5).kind());

        final ContextMenuRegistry.ContextMenuEntry levelTwo = children.get(5);
        assertEquals("level-two", levelTwo.id());
        final ContextMenuRegistry.ContextMenuEntry levelThree =
                levelTwo.children().get(0);
        assertEquals("level-three", levelThree.id());
        assertEquals("deep", levelThree.children().get(0).id());
        assertEquals("context-menu.deep", levelThree.children().get(0).actionId());
    }

    @Test
    void probeEntryIdsArePrefixedPerContribution() {
        assertTrue(WindowsContextMenuValidationProbe.probeEntry("objects").id().startsWith("turboism-validation-"));
    }
}
