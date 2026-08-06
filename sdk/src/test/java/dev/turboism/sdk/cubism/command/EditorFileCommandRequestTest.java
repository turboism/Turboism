package dev.turboism.sdk.cubism.command;

import dev.turboism.sdk.ui.UserFileHandle;
import dev.turboism.sdk.ui.UserFileHandleState;
import dev.turboism.sdk.ui.UserFileLifetime;
import dev.turboism.sdk.ui.UserFileMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EditorFileCommandRequestTest {
    @Test
    void openRequiresAnActiveReadableOpaqueGrant() {
        UserFileHandle readable = handle(UserFileMode.READ, UserFileHandleState.ACTIVE);

        EditorFileCommandRequest request = new EditorFileCommandRequest(
            EditorFileCommand.OPEN,
            readable,
            EditorOverwritePolicy.REJECT_EXISTING
        );

        assertEquals("open", request.commandId());
        assertEquals(readable, request.file());
        assertThrows(IllegalArgumentException.class, () -> new EditorFileCommandRequest(
            EditorFileCommand.OPEN,
            handle(UserFileMode.WRITE, UserFileHandleState.ACTIVE),
            EditorOverwritePolicy.REJECT_EXISTING
        ));
        assertThrows(IllegalArgumentException.class, () -> new EditorFileCommandRequest(
            EditorFileCommand.OPEN,
            handle(UserFileMode.READ, UserFileHandleState.REVOKED),
            EditorOverwritePolicy.REJECT_EXISTING
        ));
    }

    @Test
    void writeCommandsRequireWritableGrantsAndExplicitOverwritePolicy() {
        UserFileHandle writable = handle(UserFileMode.WRITE, UserFileHandleState.ACTIVE);

        EditorFileCommandRequest request = new EditorFileCommandRequest(
            EditorFileCommand.SAVE_AS,
            writable,
            EditorOverwritePolicy.REPLACE_EXISTING
        );

        assertEquals("save.as", request.commandId());
        assertThrows(IllegalArgumentException.class, () -> new EditorFileCommandRequest(
            EditorFileCommand.SAVE_AS,
            handle(UserFileMode.READ, UserFileHandleState.ACTIVE),
            EditorOverwritePolicy.REPLACE_EXISTING
        ));
        assertThrows(NullPointerException.class, () -> new EditorFileCommandRequest(
            EditorFileCommand.SAVE_AS,
            writable,
            null
        ));

        assertEquals(UserFileMode.READ, EditorFileCommand.CSV_IMPORT_MODEL_IDS.mode());
        assertEquals(UserFileMode.WRITE, EditorFileCommand.CSV_EXPORT_MODEL_IDS.mode());
        assertEquals(UserFileMode.READ, EditorFileCommand.IMPORT_SCENE_FROM_ANIMATION.mode());
        assertEquals(UserFileMode.WRITE, EditorFileCommand.EXPORT_PHYSICS_SETTINGS.mode());
        assertEquals(14, EditorFileCommand.values().length);
    }

    private static UserFileHandle handle(final UserFileMode mode, final UserFileHandleState state) {
        return new UserFileHandle() {
            @Override public String id() { return "grant"; }
            @Override public String displayName() { return "fixture.cmo3"; }
            @Override public UserFileMode mode() { return mode; }
            @Override public UserFileLifetime lifetime() { return UserFileLifetime.UNTIL_DISABLE; }
            @Override public UserFileHandleState state() { return state; }
            @Override public void revoke() { }
            @Override public void close() { }
        };
    }
}
