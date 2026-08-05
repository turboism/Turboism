package dev.turboism.adapter.host;

import dev.turboism.adapter.cubism.command.EditorCommandAdapter;
import dev.turboism.sdk.cubism.command.EditorCommand;
import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.cubism.command.EditorParameterizedRequest;
import dev.turboism.sdk.cubism.command.EditorResizeModelRequest;
import dev.turboism.adapter.cubism.command.ResolvedEditorFileCommand;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DynamicEditorCommandAdapterTest {
    @Test
    void retainedViewFollowsReplacementAndFailsClosedAfterDeactivation() {
        DynamicEditorCommandAdapter dynamic = new DynamicEditorCommandAdapter();
        EditorCommandAdapter retained = dynamic;

        assertEquals(EditorCommandResult.Status.UNAVAILABLE, retained.execute(EditorCommand.NEXT_FRAME).status());
        dynamic.connect(adapter(EditorCommand.NEXT_FRAME));
        assertEquals(Set.of(EditorCommand.NEXT_FRAME), retained.available());
        assertEquals(EditorCommandResult.Status.EXECUTED, retained.execute(EditorCommand.NEXT_FRAME).status());

        dynamic.connect(adapter(EditorCommand.PREV_FRAME));
        assertEquals(Set.of(EditorCommand.PREV_FRAME), retained.available());
        assertEquals(EditorCommandResult.Status.UNAVAILABLE, retained.execute(EditorCommand.NEXT_FRAME).status());

        dynamic.deactivate();
        assertEquals(Set.of(), retained.available());
        assertEquals(EditorCommandResult.Status.UNAVAILABLE, retained.execute(EditorCommand.PREV_FRAME).status());
        assertEquals(EditorCommandResult.Status.UNAVAILABLE, retained.execute(new EditorResizeModelRequest(100)).status());
    }

    private static EditorCommandAdapter adapter(final EditorCommand available) {
        return new EditorCommandAdapter() {
            @Override
            public Set<EditorCommand> available() {
                return Set.of(available);
            }

            @Override
            public EditorCommandResult execute(final EditorCommand command) {
                return new EditorCommandResult(
                    command == available ? EditorCommandResult.Status.EXECUTED : EditorCommandResult.Status.UNAVAILABLE,
                    command.id()
                );
            }

            @Override
            public EditorCommandResult execute(final ResolvedEditorFileCommand command) {
                return new EditorCommandResult(EditorCommandResult.Status.UNAVAILABLE, command.commandId());
            }

            @Override
            public EditorCommandResult execute(final EditorParameterizedRequest command) {
                return new EditorCommandResult(EditorCommandResult.Status.EXECUTED, command.commandId());
            }
        };
    }
}
