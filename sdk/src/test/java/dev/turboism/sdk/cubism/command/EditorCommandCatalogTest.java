package dev.turboism.sdk.cubism.command;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorCommandCatalogTest {
    @Test
    void exposesTheCompleteDirectSafeCatalogWithoutExcludedCommands() {
        assertEquals(197, EditorCommand.values().length);
        assertTrue(Set.of(EditorCommand.values()).contains(EditorCommand.SAVE));
        assertTrue(Set.of(EditorCommand.values()).contains(EditorCommand.UNDO));
        assertTrue(Set.of(EditorCommand.values()).contains(EditorCommand.NEXT_FRAME));
        assertTrue(Set.of(EditorCommand.values()).contains(EditorCommand.SHOW_FULL_WORKSPACE));
        assertTrue(Set.of(EditorCommand.values()).contains(EditorCommand.SHOW_PARAMETER_PALETTE));
        assertTrue(Set.of(EditorCommand.values()).contains(EditorCommand.OPEN_MANUAL_PAGE));
        assertTrue(Set.of(EditorCommand.values()).contains(EditorCommand.OPEN_LOG_FILE));
        assertTrue(EditorCommand.SHOW_FULL_SCENE.supports("5.3.02"));
        assertFalse(EditorCommand.SHOW_FULL_SCENE.supports("5.2.03"));
        assertFalse(java.util.Arrays.stream(EditorCommand.values()).anyMatch(command -> command.name().equals("EXIT")));
        assertFalse(java.util.Arrays.stream(EditorCommand.values()).anyMatch(command -> command.name().equals("CLOSE_ALL")));
        assertFalse(java.util.Arrays.stream(EditorCommand.values()).anyMatch(command -> command.name().contains("LICENSE")));
    }

    @Test
    void recordsExactVersionOnlyCommandsWithoutNativeIds() {
        assertTrue(EditorCommand.EXPAND_WARPDEFORMER.supports("5.3.02"));
        assertFalse(EditorCommand.EXPAND_WARPDEFORMER.supports("5.2.03"));
        assertEquals("next.frame", EditorCommand.NEXT_FRAME.id());
        assertFalse(EditorCommand.NEXT_FRAME.id().startsWith("CMD_"));
    }

    @Test
    void unavailableServiceFailsClosed() {
        EditorCommandService service = EditorCommandService.unavailable();
        assertTrue(service.available().isEmpty());
        assertEquals(
            EditorCommandResult.Status.UNAVAILABLE,
            service.execute(EditorCommand.NEXT_FRAME).status()
        );
    }
}
