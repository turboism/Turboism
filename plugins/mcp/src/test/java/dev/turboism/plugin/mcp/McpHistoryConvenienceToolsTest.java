package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.command.EditorCommandService;
import dev.turboism.sdk.cubism.history.CubismHistory;
import dev.turboism.sdk.cubism.history.HistoryEntry;
import dev.turboism.sdk.cubism.history.HistoryMoveResult;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class McpHistoryConvenienceToolsTest {

    @Test
    void catalogPublishesExplicitGuardedUndoAndRedoTools() {
        final McpHistoryCommandDomain domain = new McpHistoryCommandDomain(
            CubismHistory.unavailable(),
            EditorCommandService.unavailable()
        );

        final Map<String, Map<String, Object>> definitions = domain.tools().definitions().stream()
            .collect(java.util.stream.Collectors.toMap(
                definition -> (String) definition.get("name"),
                definition -> definition
            ));

        assertTrue(definitions.containsKey(McpHistoryCommandDomain.HISTORY_READ));
        assertTrue(definitions.containsKey(McpHistoryCommandDomain.HISTORY_UNDO));
        assertTrue(definitions.containsKey(McpHistoryCommandDomain.HISTORY_REDO));
        assertEquals(
            McpOutputSchemas.historyMove(),
            definitions.get(McpHistoryCommandDomain.HISTORY_UNDO).get("outputSchema")
        );
        assertEquals(
            McpOutputSchemas.historyMove(),
            definitions.get(McpHistoryCommandDomain.HISTORY_REDO).get("outputSchema")
        );
        assertEquals(false, object(definitions.get(
            McpHistoryCommandDomain.HISTORY_UNDO
        ).get("annotations")).get("readOnlyHint"));
        assertEquals(
            McpOperationEffect.READ,
            domain.tools().registration(McpHistoryCommandDomain.HISTORY_READ).effect()
        );
        assertEquals(
            McpOperationEffect.HISTORY_CONTROL,
            domain.tools().registration(McpHistoryCommandDomain.HISTORY_UNDO).effect()
        );
        assertFalse(domain.tools().registration(
            McpHistoryCommandDomain.HISTORY_READ
        ).transactionEligible());
        assertFalse(domain.tools().registration(
            McpHistoryCommandDomain.HISTORY_UNDO
        ).transactionEligible());
    }

    @Test
    void historyReadSchemaIncludesClosedRecursiveSemanticDetail() {
        final Map<String, Object> root = McpOutputSchemas.historyRead();
        final Map<String, Object> success = object(((List<?>) root.get("oneOf")).get(0));
        final Map<String, Object> successProperties = object(success.get("properties"));
        final Map<String, Object> snapshot = object(successProperties.get("snapshot"));
        final Map<String, Object> snapshotProperties = object(snapshot.get("properties"));
        final Map<String, Object> entries = object(snapshotProperties.get("entries"));
        final Map<String, Object> entrySchema = object(entries.get("items"));
        final Map<String, Object> entryProperties = object(entrySchema.get("properties"));
        final Map<String, Object> detail = object(entryProperties.get("detail"));
        final Map<String, Object> detailProperties = object(detail.get("properties"));

        assertEquals(false, detail.get("additionalProperties"));
        assertTrue(((List<?>) detail.get("required")).containsAll(List.of(
            "summary", "detailLevel", "origin", "targets", "changes", "group", "degradationCode"
        )));
        assertEquals(
            List.of("FULL", "PARTIAL", "LABEL_ONLY"),
            object(detailProperties.get("detailLevel")).get("enum")
        );
        final Map<String, Object> group = object(detailProperties.get("group"));
        assertEquals(List.of("object", "null"), group.get("type"));
        final Map<String, Object> groupChildren = object(object(group.get("properties")).get("children"));
        final Map<String, Object> childDetail = object(groupChildren.get("items"));
        assertTrue(object(childDetail.get("properties")).containsKey("group"));
    }

    @Test
    void standaloneHistoryToolsUseTheUiThreadAndCannotBecomeTransactionChildren() {
        final AtomicInteger dispatches = new AtomicInteger();
        final McpExecutionBridge execution = new McpExecutionBridge(new UiScheduler() {
            @Override
            public Registration runOnUiThread(final Runnable work) {
                dispatches.incrementAndGet();
                work.run();
                return () -> { };
            }

            @Override
            public Registration runOnUiThreadLater(final Runnable work, final Duration delay) {
                throw new UnsupportedOperationException();
            }
        });
        final McpToolCatalog tools = new McpHistoryCommandDomain(
            CubismHistory.unavailable(),
            EditorCommandService.unavailable(),
            execution
        ).tools();

        assertEquals(
            McpExecutionAffinity.UI_THREAD,
            tools.registration(McpHistoryCommandDomain.HISTORY_READ).affinity()
        );
        assertEquals(
            McpExecutionAffinity.UI_THREAD,
            tools.registration(McpHistoryCommandDomain.HISTORY_UNDO).affinity()
        );
        tools.call(McpHistoryCommandDomain.HISTORY_READ, Map.of());
        assertEquals(1, dispatches.get());
        assertThrows(
            IllegalArgumentException.class,
            () -> tools.callRaw(McpHistoryCommandDomain.HISTORY_READ, Map.of())
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> tools.callRaw(McpHistoryCommandDomain.HISTORY_UNDO, Map.of(
                "expectedGeneration", 0,
                "expectedRevision", 0,
                "steps", 1
            ))
        );
    }

    @Test
    void explicitUndoAndRedoTranslateToOneGuardedMoveFromFreshSnapshots() {
        final AtomicReference<HistorySnapshot> current = new AtomicReference<>(snapshot(7, 11, 3));
        final AtomicInteger moves = new AtomicInteger();
        final AtomicReference<List<Long>> preconditions = new AtomicReference<>();
        final AtomicInteger target = new AtomicInteger(-1);
        final CubismHistory history = history(current, moves, preconditions, target);
        final McpToolCatalog tools = new McpHistoryCommandDomain(
            history,
            EditorCommandService.unavailable()
        ).tools();

        final Map<String, Object> undo = output(tools.call(
            McpHistoryCommandDomain.HISTORY_UNDO,
            Map.of(
                "expectedGeneration", 7,
                "expectedRevision", 11,
                "steps", 2
            )
        ));
        assertEquals("MOVED", undo.get("outcome"));
        assertEquals(1, target.get());
        assertEquals(List.of(7L, 11L), preconditions.get());

        current.set(snapshot(7, 12, 1));
        final Map<String, Object> redo = output(tools.call(
            McpHistoryCommandDomain.HISTORY_REDO,
            Map.of(
                "expectedGeneration", 7,
                "expectedRevision", 12,
                "steps", 10
            )
        ));
        assertEquals("MOVED", redo.get("outcome"));
        assertEquals(4, target.get());
        assertEquals(2, moves.get());
    }

    @Test
    void explicitUndoRejectsStalePreconditionsWithoutMovingHistory() {
        final AtomicReference<HistorySnapshot> current = new AtomicReference<>(snapshot(7, 11, 3));
        final AtomicInteger moves = new AtomicInteger();
        final McpToolCatalog tools = new McpHistoryCommandDomain(
            history(current, moves, new AtomicReference<>(), new AtomicInteger()),
            EditorCommandService.unavailable()
        ).tools();

        final Map<String, Object> output = output(tools.call(
            McpHistoryCommandDomain.HISTORY_UNDO,
            Map.of(
                "expectedGeneration", 7,
                "expectedRevision", 10,
                "steps", 1
            )
        ));

        assertFalse((Boolean) output.get("ok"));
        assertEquals("REJECTED_STALE", output.get("outcome"));
        assertEquals(0, moves.get());
    }

    private static CubismHistory history(
        final AtomicReference<HistorySnapshot> current,
        final AtomicInteger moves,
        final AtomicReference<List<Long>> preconditions,
        final AtomicInteger target
    ) {
        return (CubismHistory) Proxy.newProxyInstance(
            CubismHistory.class.getClassLoader(),
            new Class<?>[] {CubismHistory.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "snapshot" -> current.get();
                case "moveTo" -> {
                    moves.incrementAndGet();
                    preconditions.set(List.of(
                        ((Number) arguments[0]).longValue(),
                        ((Number) arguments[1]).longValue()
                    ));
                    target.set(((Number) arguments[2]).intValue());
                    final HistorySnapshot moved = snapshot(
                        current.get().generation(),
                        current.get().revision() + 1,
                        target.get()
                    );
                    current.set(moved);
                    yield new HistoryMoveResult(
                        HistoryMoveResult.Outcome.MOVED,
                        moved,
                        Optional.empty()
                    );
                }
                case "toString" -> "FakeCubismHistory";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> throw new UnsupportedOperationException(method.getName());
            }
        );
    }

    private static HistorySnapshot snapshot(
        final long generation,
        final long revision,
        final int position
    ) {
        final List<HistoryEntry> entries = java.util.stream.IntStream.range(0, 4)
            .mapToObj(index -> new HistoryEntry(index, "Entry " + index, true))
            .toList();
        return new HistorySnapshot(
            HistorySnapshot.Availability.AVAILABLE,
            generation,
            revision,
            position,
            entries,
            position > 0,
            position < entries.size()
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> output(final Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("structuredContent");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(final Object value) {
        return (Map<String, Object>) value;
    }
}
