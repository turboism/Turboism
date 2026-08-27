package dev.turboism.sdk.cubism.command;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorParameterizedCommandCatalogTest {
    @Test
    void accountsForEveryRemainingTypedCommandWithoutNativeIdentifiers() {
        assertEquals(67, EditorParameterizedCommand.values().length);
        assertTrue(Set.of(EditorParameterizedCommand.values()).contains(EditorParameterizedCommand.CREATE_WARP_DEFORMER));
        assertTrue(Set.of(EditorParameterizedCommand.values()).contains(EditorParameterizedCommand.ADD_TIMELINE_MARKER));
        assertTrue(Set.of(EditorParameterizedCommand.values()).contains(EditorParameterizedCommand.EXPORT_SCENE_AS_VIDEO));
        assertFalse(java.util.Arrays.stream(EditorParameterizedCommand.values())
            .anyMatch(command -> command.id().startsWith("CMD_")));
        assertFalse(EditorParameterizedCommand.FADE_SETTING.supports("5.2.03"));
        Set<EditorParameterizedCommand> verified = Set.of(
            EditorParameterizedCommand.EXTERNAL_APP_SETTING,
            EditorParameterizedCommand.GRID_SETTING,
            EditorParameterizedCommand.MODEL_SETTING,
            EditorParameterizedCommand.RESIZE_MODEL_DOCUMENT
        );
        assertTrue(java.util.Arrays.stream(EditorParameterizedCommand.values())
            .filter(command -> !verified.contains(command))
            .allMatch(command -> command.availability() == EditorParameterizedCommand.Availability.EVIDENCE_REQUIRED));
        assertTrue(verified.stream().allMatch(
            command -> command.availability() == EditorParameterizedCommand.Availability.TYPED_CONTRACT_VERIFIED
        ));
    }
}
