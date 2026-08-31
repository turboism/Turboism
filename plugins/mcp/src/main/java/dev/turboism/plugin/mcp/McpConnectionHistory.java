package dev.turboism.plugin.mcp;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;

/** Bounded process-local history of authenticated MCP session and request activity. */
final class McpConnectionHistory {

    static final int MAX_ENTRIES = 200;

    private final ArrayDeque<Entry> entries = new ArrayDeque<>();

    synchronized void record(final Event event, final String client, final String detail) {
        entries.addLast(new Entry(
            Instant.now(),
            Objects.requireNonNull(event, "event"),
            bounded(client),
            bounded(detail)
        ));
        while (entries.size() > MAX_ENTRIES) entries.removeFirst();
    }

    synchronized List<Entry> snapshot() {
        return List.copyOf(entries);
    }

    enum Event {
        SESSION_CREATED,
        SESSION_INITIALIZED,
        REQUEST,
        SESSION_CLOSED,
        SESSION_EXPIRED
    }

    record Entry(Instant timestamp, Event event, String client, String detail) {
        Entry {
            timestamp = Objects.requireNonNull(timestamp, "timestamp");
            event = Objects.requireNonNull(event, "event");
            client = Objects.requireNonNull(client, "client");
            detail = Objects.requireNonNull(detail, "detail");
        }
    }

    private static String bounded(final String value) {
        final String text = Objects.requireNonNullElse(value, "").strip()
            .replace('\r', ' ')
            .replace('\n', ' ');
        return text.length() <= 160 ? text : text.substring(0, 160);
    }
}
