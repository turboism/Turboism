package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.command.EditorCommand;
import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.cubism.command.EditorCommandService;
import dev.turboism.sdk.cubism.command.EditorFileCommandRequest;
import dev.turboism.sdk.cubism.command.EditorParameterizedRequest;
import dev.turboism.sdk.cubism.history.CubismHistory;
import dev.turboism.sdk.cubism.history.HistoryEntry;
import dev.turboism.sdk.cubism.history.HistoryMoveResult;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class McpHistoryCommandDomainTest {

    @Test
    void readsImmutableHistoryAndCommandInventories() {
        final FakeHistory history = new FakeHistory(snapshot(7, 11, 1, 3));
        final FakeCommands commands = new FakeCommands(Set.of(EditorCommand.SAVE, EditorCommand.UNDO));
        final McpHistoryCommandDomain domain = new McpHistoryCommandDomain(history, commands);

        final McpHistoryCommandDomain.ResourceReadResult historyResult = domain.read(
            McpHistoryCommandDomain.HISTORY_RESOURCE
        );
        assertEquals("AVAILABLE", historyResult.content().get("availability"));
        assertEquals(7L, historyResult.content().get("generation"));
        assertEquals(11L, historyResult.content().get("revision"));
        assertEquals(3, ((List<?>) historyResult.content().get("entries")).size());
        assertThrows(UnsupportedOperationException.class, () -> historyResult.content().put("x", "y"));

        final McpHistoryCommandDomain.ResourceReadResult commandResult = domain.read(
            McpHistoryCommandDomain.EDITOR_COMMANDS_RESOURCE
        );
        assertEquals(
            List.of(EditorCommand.SAVE.id(), EditorCommand.UNDO.id()),
            commandResult.content().get("availableDirectCommands")
        );
        final List<?> contracts = (List<?>) commandResult.content().get("typedContracts");
        assertEquals(5, contracts.size());
        assertFalse(Json.stringify(commandResult.content()).contains("EditorFileCommandRequest"));
        assertFalse(Json.stringify(commandResult.content()).contains("path"));
    }

    @Test
    void rejectsStaleHistoryBeforeCallingHostAndTranslatesUndoRedoFromCurrentSnapshot() {
        final FakeHistory history = new FakeHistory(snapshot(3, 4, 2, 5));
        final McpHistoryCommandDomain domain = new McpHistoryCommandDomain(history, EditorCommandService.unavailable());

        final McpHistoryCommandDomain.ToolCallResult stale = domain.call(
            McpHistoryCommandDomain.HISTORY_MOVE,
            Map.of("operation", "move_to", "expectedGeneration", 2L, "expectedRevision", 4L, "position", 1)
        );
        assertFalse(stale.isError());
        assertEquals(Boolean.FALSE, stale.structuredContent().get("ok"));
        assertEquals("REJECTED_STALE", stale.structuredContent().get("outcome"));
        assertEquals(0, history.moves.size());

        final McpHistoryCommandDomain.ToolCallResult undo = domain.call(
            McpHistoryCommandDomain.HISTORY_MOVE,
            Map.of("operation", "undo", "expectedGeneration", 3L, "expectedRevision", 4L, "steps", 2)
        );
        assertFalse(undo.isError());
        assertEquals(new Move(3, 4, 0), history.moves.get(0));

        history.current = snapshot(3, 5, 1, 5);
        final McpHistoryCommandDomain.ToolCallResult redo = domain.call(
            McpHistoryCommandDomain.HISTORY_MOVE,
            Map.of("operation", "redo", "expectedGeneration", 3L, "expectedRevision", 5L, "steps", 9)
        );
        assertFalse(redo.isError());
        assertEquals(new Move(3, 5, 5), history.moves.get(1));
    }

    @Test
    void requiresPreconditionsForEveryHistoryOperation() {
        final FakeHistory history = new FakeHistory(snapshot(3, 4, 2, 5));
        final McpHistoryCommandDomain domain = new McpHistoryCommandDomain(history, EditorCommandService.unavailable());

        final McpHistoryCommandDomain.ToolCallResult result = domain.call(
            McpHistoryCommandDomain.HISTORY_MOVE,
            Map.of("operation", "undo", "expectedGeneration", 3L, "steps", 1)
        );

        assertTrue(result.isError());
        assertEquals("INVALID_ARGUMENT", errorCode(result));
        assertEquals(0, history.moves.size());
    }

    @Test
    void rechecksDirectAvailabilityAndExecutesExactTypedRequests() {
        final FakeCommands commands = new FakeCommands(Set.of(EditorCommand.SAVE));
        final McpHistoryCommandDomain domain = new McpHistoryCommandDomain(CubismHistory.unavailable(), commands);

        final McpHistoryCommandDomain.ToolCallResult unavailable = domain.call(
            McpHistoryCommandDomain.EDITOR_COMMANDS_EXECUTE,
            Map.of("kind", "direct", "commandId", EditorCommand.UNDO.id())
        );
        assertFalse(unavailable.isError());
        assertEquals(Boolean.FALSE, unavailable.structuredContent().get("ok"));
        assertEquals("UNAVAILABLE", unavailable.structuredContent().get("status"));
        assertEquals(0, commands.directExecutions.size());

        final McpHistoryCommandDomain.ToolCallResult direct = domain.call(
            McpHistoryCommandDomain.EDITOR_COMMANDS_EXECUTE,
            Map.of("kind", "direct", "commandId", EditorCommand.SAVE.id())
        );
        assertFalse(direct.isError());
        assertEquals("EXECUTED", direct.structuredContent().get("status"));
        assertEquals(List.of(EditorCommand.SAVE), commands.directExecutions);

        final McpHistoryCommandDomain.ToolCallResult grid = domain.call(
            McpHistoryCommandDomain.EDITOR_COMMANDS_EXECUTE,
            Map.of(
                "kind", "grid_settings",
                "spacingPixels", 16,
                "color", Map.of("red", 0.2, "green", 0.3, "blue", 0.4, "alpha", 1.0)
            )
        );
        assertFalse(grid.isError());
        assertEquals("EXECUTED", grid.structuredContent().get("status"));
        assertEquals("grid.setting", commands.lastParameterized.commandId());

        final McpHistoryCommandDomain.ToolCallResult rawPath = domain.call(
            McpHistoryCommandDomain.EDITOR_COMMANDS_EXECUTE,
            Map.of("kind", "direct", "commandId", EditorCommand.SAVE.id(), "path", "/tmp/forbidden")
        );
        assertTrue(rawPath.isError());
        assertEquals("INVALID_ARGUMENT", errorCode(rawPath));
    }

    @Test
    void exposesOnlyExactMcpDefinitions() {
        final McpHistoryCommandDomain domain = new McpHistoryCommandDomain(
            CubismHistory.unavailable(),
            EditorCommandService.unavailable()
        );

        assertEquals(
            List.of(McpHistoryCommandDomain.HISTORY_RESOURCE, McpHistoryCommandDomain.EDITOR_COMMANDS_RESOURCE),
            domain.resourceDefinitions().stream().map(McpHistoryCommandDomain.ResourceDefinition::uri).toList()
        );
        assertEquals(
            List.of(McpHistoryCommandDomain.HISTORY_MOVE, McpHistoryCommandDomain.EDITOR_COMMANDS_EXECUTE),
            domain.toolDefinitions().stream().map(McpHistoryCommandDomain.ToolDefinition::name).toList()
        );
    }

    private static String errorCode(final McpHistoryCommandDomain.ToolCallResult result) {
        @SuppressWarnings("unchecked")
        final Map<String, Object> error = (Map<String, Object>) result.structuredContent().get("error");
        return (String) error.get("code");
    }

    private static HistorySnapshot snapshot(
        final long generation,
        final long revision,
        final int position,
        final int size
    ) {
        final List<HistoryEntry> entries = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            entries.add(new HistoryEntry(index, "Action " + index, true));
        }
        return new HistorySnapshot(
            HistorySnapshot.Availability.AVAILABLE,
            generation,
            revision,
            position,
            entries,
            position > 0,
            position < size
        );
    }

    private record Move(long generation, long revision, int position) {
    }

    private static final class FakeHistory implements CubismHistory {
        private HistorySnapshot current;
        private final List<Move> moves = new ArrayList<>();

        private FakeHistory(final HistorySnapshot initial) {
            current = initial;
        }

        @Override public HistorySnapshot snapshot() {
            return current;
        }

        @Override public HistoryMoveResult moveTo(
            final long expectedGeneration,
            final long expectedRevision,
            final int position
        ) {
            moves.add(new Move(expectedGeneration, expectedRevision, position));
            if (expectedGeneration != current.generation() || expectedRevision != current.revision()) {
                return new HistoryMoveResult(
                    HistoryMoveResult.Outcome.REJECTED_STALE,
                    current,
                    Optional.of("fake.stale")
                );
            }
            current = new HistorySnapshot(
                current.availability(), current.generation(), current.revision() + 1,
                position, current.entries(), position > 0, position < current.entries().size()
            );
            return new HistoryMoveResult(HistoryMoveResult.Outcome.MOVED, current, Optional.empty());
        }
    }

    private static final class FakeCommands implements EditorCommandService {
        private final Set<EditorCommand> available;
        private final List<EditorCommand> directExecutions = new ArrayList<>();
        private EditorParameterizedRequest lastParameterized;

        private FakeCommands(final Set<EditorCommand> available) {
            this.available = available;
        }

        @Override public Set<EditorCommand> available() {
            return available;
        }

        @Override public EditorCommandResult execute(final EditorCommand command) {
            directExecutions.add(command);
            return new EditorCommandResult(EditorCommandResult.Status.EXECUTED, command.id());
        }

        @Override public EditorCommandResult execute(final EditorFileCommandRequest request) {
            return new EditorCommandResult(EditorCommandResult.Status.REJECTED, request.commandId());
        }

        @Override public EditorCommandResult execute(final EditorParameterizedRequest request) {
            lastParameterized = request;
            return new EditorCommandResult(EditorCommandResult.Status.EXECUTED, request.commandId());
        }
    }
}
