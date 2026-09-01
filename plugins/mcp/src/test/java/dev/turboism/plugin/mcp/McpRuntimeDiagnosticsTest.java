package dev.turboism.plugin.mcp;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class McpRuntimeDiagnosticsTest {

    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-09-01T12:00:00Z"), ZoneOffset.UTC
    );

    @Test
    void boundedBufferRetainsNewestEventsAndSanitizesSensitiveFailureEvidence() {
        final McpRuntimeDiagnostics diagnostics = new McpRuntimeDiagnostics(2, CLOCK);
        final AtomicReference<RuntimeException> next = new AtomicReference<>();
        final McpToolCatalog observed = diagnostics.observe(new McpToolCatalog(
            List.of(Map.of("name", "turboism.test")),
            (name, arguments) -> { throw next.get(); }
        ));

        next.set(new RuntimeException(
            "token=secret sessionId=session-123 /home/private/model.cmo3\nmessage"
        ));
        assertThrows(RuntimeException.class, () -> observed.call("turboism.test", Map.of()));
        next.set(new RuntimeException(
            "native rollback failed token=secret sessionId=session-123 for C:\\Users\\private\\model.cmo3"
        ));
        assertThrows(RuntimeException.class, () -> observed.call("turboism.test", Map.of()));
        next.set(new McpExecutionBridge.ExecutionFailure(
            "MCP operation timed out",
            new TimeoutException("Bearer another-secret")
        ));
        assertThrows(RuntimeException.class, () -> observed.call("turboism.test", Map.of()));

        final McpRuntimeDiagnostics.Snapshot snapshot = diagnostics.snapshot();
        assertEquals(Instant.parse("2026-09-01T12:00:00Z"), snapshot.asOf());
        assertEquals(1, snapshot.dropped());
        assertEquals(List.of("ROLLBACK_FAILURE", "TIMEOUT"), snapshot.events().stream()
            .map(McpRuntimeDiagnostics.Event::kind).toList());
        assertEquals(List.of("turboism.test", "turboism.test"), snapshot.events().stream()
            .map(McpRuntimeDiagnostics.Event::provider).toList());
        assertTrue(snapshot.events().stream().allMatch(event ->
            !event.message().contains("secret")
                && !event.message().contains("session-123")
                && !event.message().contains("C:\\Users")
                && !event.message().contains("/home/private")
                && !event.message().contains("\n")
        ));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.events().clear());
    }

    @Test
    void observesUnknownReadbackWarningRollbackAndTimeoutOutcomesWithoutPayloadSecrets() {
        final McpRuntimeDiagnostics diagnostics = new McpRuntimeDiagnostics(8, CLOCK);
        final McpToolCatalog observed = diagnostics.observe(new McpToolCatalog(
            List.of(Map.of("name", "turboism.write")),
            (name, arguments) -> envelope(Map.of(
                "ok", true,
                "results", List.of(
                    Map.of("outcome", "OUTCOME_UNKNOWN", "message", "token=must-not-leak"),
                    Map.of("outcome", "APPLIED_WITH_READBACK_WARNING"),
                    Map.of("error", Map.of("code", "ROLLBACK_FAILED")),
                    Map.of("error", Map.of("code", "TIMEOUT")),
                    Map.of("error", Map.of("code", "FAILED"))
                )
            ))
        ));

        observed.call("turboism.write", Map.of());

        final List<McpRuntimeDiagnostics.Event> events = diagnostics.snapshot().events();
        assertEquals(List.of(
            "OUTCOME_UNKNOWN",
            "APPLIED_WITH_READBACK_WARNING",
            "ROLLBACK_FAILURE",
            "TIMEOUT",
            "RUNTIME_EXCEPTION"
        ), events.stream().map(McpRuntimeDiagnostics.Event::kind).toList());
        assertTrue(events.stream().allMatch(event -> event.provider().equals("turboism.write")));
        assertTrue(events.stream().noneMatch(event -> event.message().contains("must-not-leak")));
    }

    @Test
    void resourceTimeoutsAreRecordedWithTheResourceUriAsProvider() {
        final McpRuntimeDiagnostics diagnostics = new McpRuntimeDiagnostics(4, CLOCK);
        final McpResourceCatalog observed = diagnostics.observe(new McpResourceCatalog(
            List.of(Map.of("uri", "turboism://test/resource")),
            List.of(),
            uri -> { throw new McpResourceCatalog.ResourceFailure(
                McpResourceCatalog.ResourceFailure.Kind.TIMEOUT,
                "resource read timed out token=secret",
                null
            ); }
        ));

        assertThrows(
            McpResourceCatalog.ResourceFailure.class,
            () -> observed.read("turboism://test/resource")
        );

        final McpRuntimeDiagnostics.Event event = diagnostics.snapshot().events().get(0);
        assertEquals("TIMEOUT", event.kind());
        assertEquals("turboism://test/resource", event.provider());
        assertFalse(event.message().contains("secret"));
        assertTrue(event.message().contains("[redacted-token]"));
    }

    private static Map<String, Object> envelope(final Map<String, Object> structured) {
        return Map.of(
            "content", List.of(Map.of(
                "type", "text",
                "text", Json.stringify(structured)
            )),
            "structuredContent", structured,
            "isError", false
        );
    }
}
