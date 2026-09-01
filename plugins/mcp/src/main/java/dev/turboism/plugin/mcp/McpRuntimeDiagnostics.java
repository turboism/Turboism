package dev.turboism.plugin.mcp;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/** Thread-safe bounded evidence buffer for noteworthy MCP runtime outcomes. */
final class McpRuntimeDiagnostics {

    static final int DEFAULT_CAPACITY = 128;
    static final int MAX_MESSAGE_CHARS = 512;

    private static final Pattern TOKEN = Pattern.compile(
        "(?i)\\b(authorization|api[_-]?key|access[_-]?token|auth[_-]?token|token)\\b"
            + "\\s*(?:[:=]\\s*|\\s+)(?:bearer\\s+)?(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;]+)"
    );
    private static final Pattern BEARER = Pattern.compile(
        "(?i)\\bbearer\\s+[^\\s,;]+"
    );
    private static final Pattern SESSION = Pattern.compile(
        "(?i)\\b(mcp[-_]?session[-_]?id|session[-_]?id)\\b"
            + "\\s*(?:[:=]\\s*|\\s+)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;]+)"
    );
    private static final Pattern FILE_URI = Pattern.compile("(?i)file:(?://)?[^\\s]+");
    private static final Pattern WINDOWS_PATH = Pattern.compile("(?i)(?:[a-z]:\\\\|\\\\\\\\)[^\\s]+");
    private static final Pattern UNIX_PATH = Pattern.compile(
        "(?<![A-Za-z0-9_.-])/(?:[^\\s/]+/)*[^\\s]+"
    );

    private final Object lock = new Object();
    private final int capacity;
    private final Clock clock;
    private final ArrayDeque<Event> events;
    private long dropped;

    McpRuntimeDiagnostics() {
        this(DEFAULT_CAPACITY, Clock.systemUTC());
    }

    McpRuntimeDiagnostics(final int capacity, final Clock clock) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = new ArrayDeque<>(capacity);
    }

    McpToolCatalog observe(final McpToolCatalog delegate) {
        final McpToolCatalog checked = Objects.requireNonNull(delegate, "delegate");
        return new McpToolCatalog(checked.definitions(), (name, arguments) -> {
            try {
                final Map<String, Object> envelope = checked.call(name, arguments);
                recordStructuredOutcomes(name, envelope.get("structuredContent"));
                return envelope;
            } catch (RuntimeException failure) {
                recordFailure(name, failure);
                throw failure;
            }
        });
    }

    McpResourceCatalog observe(final McpResourceCatalog delegate) {
        final McpResourceCatalog checked = Objects.requireNonNull(delegate, "delegate");
        return new McpResourceCatalog(checked.resources(), checked.templates(), uri -> {
            try {
                return checked.read(uri);
            } catch (RuntimeException failure) {
                recordFailure(uri, failure);
                throw failure;
            }
        });
    }

    Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(clock.instant(), List.copyOf(events), dropped);
        }
    }

    private void recordFailure(final String provider, final RuntimeException failure) {
        final String kind;
        if (rollbackFailure(failure)) {
            kind = "ROLLBACK_FAILURE";
        } else if (timeout(failure)) {
            kind = "TIMEOUT";
        } else {
            kind = "RUNTIME_EXCEPTION";
        }
        append(
            kind,
            provider,
            null,
            null,
            null,
            kind,
            failure.getClass().getSimpleName(),
            failureMessage(failure)
        );
    }

    private void recordStructuredOutcomes(final String provider, final Object structured) {
        final LinkedHashSet<String> kinds = new LinkedHashSet<>();
        collectKinds(structured, kinds, 0);
        for (String kind : kinds) {
            append(
                kind,
                provider,
                findString(structured, "diagnosticId", 0),
                findString(structured, "operation", 0),
                kind.equals("OUTCOME_UNKNOWN") || kind.equals("APPLIED_WITH_READBACK_WARNING")
                    ? kind : null,
                findString(structured, "code", 0),
                null,
                structuredMessage(structured, kind)
            );
        }
    }

    private static void collectKinds(
        final Object value,
        final Set<String> kinds,
        final int depth
    ) {
        if (value == null || depth > 32) return;
        if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) collectKinds(item, kinds, depth + 1);
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) collectKinds(item, kinds, depth + 1);
            return;
        }
        if (!(value instanceof String text)) return;
        final String normalized = text.strip().toUpperCase(Locale.ROOT);
        if (normalized.equals("OUTCOME_UNKNOWN")) {
            kinds.add("OUTCOME_UNKNOWN");
        } else if (normalized.equals("APPLIED_WITH_READBACK_WARNING")) {
            kinds.add("APPLIED_WITH_READBACK_WARNING");
        } else if (normalized.contains("ROLLBACK") && normalized.contains("FAIL")) {
            kinds.add("ROLLBACK_FAILURE");
        } else if (normalized.equals("TIMEOUT") || normalized.equals("TIMED_OUT")
            || normalized.contains("TIMED OUT")) {
            kinds.add("TIMEOUT");
        } else if (normalized.equals("INVALID_ARGUMENT")) {
            kinds.add("INVALID_REQUEST");
        } else if (normalized.equals("FAILED") || normalized.equals("INTERNAL_OUTPUT_INVALID")) {
            kinds.add("RUNTIME_EXCEPTION");
        }
    }

    private void append(
        final String kind,
        final String provider,
        final String diagnosticId,
        final String operation,
        final String outcome,
        final String errorCode,
        final String exceptionType,
        final String message
    ) {
        final Event event = new Event(
            clock.instant(),
            diagnosticId == null
                ? java.util.UUID.randomUUID().toString()
                : optionalText(diagnosticId, 128),
            requireText(kind, "kind", 64),
            sanitizedProvider(provider),
            optionalText(operation, 64),
            optionalText(outcome, 64),
            optionalText(errorCode, 64),
            optionalText(exceptionType, 128),
            sanitized(message, "message", MAX_MESSAGE_CHARS)
        );
        synchronized (lock) {
            if (events.size() == capacity) {
                events.removeFirst();
                dropped = saturatingIncrement(dropped);
            }
            events.addLast(event);
        }
    }

    private static String structuredMessage(final Object structured, final String kind) {
        final String message = findString(structured, "message", 0);
        if (message != null) return message;
        return switch (kind) {
            case "OUTCOME_UNKNOWN" -> "Operation outcome could not be determined.";
            case "APPLIED_WITH_READBACK_WARNING" ->
                "Operation applied, but post-write readback could not fully verify the result.";
            case "ROLLBACK_FAILURE" -> "Operation reported a rollback failure.";
            case "TIMEOUT" -> "Operation reported a timeout.";
            case "RUNTIME_EXCEPTION" -> "Operation reported an unexpected runtime failure.";
            case "INVALID_REQUEST" -> "Operation rejected an invalid request.";
            default -> "Operation reported noteworthy runtime evidence.";
        };
    }

    private static String findString(final Object value, final String key, final int depth) {
        if (value == null || depth > 32) return null;
        if (value instanceof Map<?, ?> map) {
            final Object direct = map.get(key);
            if (direct instanceof String text && !text.isBlank()) return text;
            for (Object nested : map.values()) {
                final String found = findString(nested, key, depth + 1);
                if (found != null) return found;
            }
        } else if (value instanceof Iterable<?> iterable) {
            for (Object nested : iterable) {
                final String found = findString(nested, key, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String optionalText(final String value, final int maximum) {
        if (value == null || value.isBlank()) return null;
        return sanitized(value, "optional diagnostic field", maximum);
    }

    private static boolean rollbackFailure(final Throwable failure) {
        for (Throwable current : chain(failure)) {
            final String evidence = (current.getClass().getSimpleName() + " "
                + Objects.toString(current.getMessage(), "")).toUpperCase(Locale.ROOT);
            if (evidence.contains("ROLLBACK") && evidence.contains("FAIL")) return true;
        }
        return false;
    }

    private static boolean timeout(final Throwable failure) {
        for (Throwable current : chain(failure)) {
            if (current instanceof TimeoutException) return true;
            if (current instanceof McpResourceCatalog.ResourceFailure resourceFailure
                && resourceFailure.kind() == McpResourceCatalog.ResourceFailure.Kind.TIMEOUT) {
                return true;
            }
            final String evidence = (current.getClass().getSimpleName() + " "
                + Objects.toString(current.getMessage(), "")).toLowerCase(Locale.ROOT);
            if (evidence.contains("timeout") || evidence.contains("timed out")) return true;
        }
        return false;
    }

    private static List<Throwable> chain(final Throwable failure) {
        final ArrayList<Throwable> chain = new ArrayList<>();
        final Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && chain.size() < 16 && seen.add(current)) {
            chain.add(current);
            current = current.getCause();
        }
        return chain;
    }

    private static String failureMessage(final RuntimeException failure) {
        final String message = failure.getMessage();
        return message == null || message.isBlank()
            ? failure.getClass().getSimpleName()
            : failure.getClass().getSimpleName() + ": " + message;
    }

    private static String sanitizedProvider(final String value) {
        String text = requireText(value, "provider", Integer.MAX_VALUE)
            .replaceAll("[\\p{Cc}\\p{Cf}]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
        text = TOKEN.matcher(text).replaceAll("$1=[redacted-token]");
        text = BEARER.matcher(text).replaceAll("Bearer [redacted-token]");
        text = SESSION.matcher(text).replaceAll("$1=[redacted-session]");
        return truncate(text, 256);
    }

    static String sanitized(final String value, final String label, final int maximum) {
        String text = requireText(value, label, Integer.MAX_VALUE)
            .replaceAll("[\\p{Cc}\\p{Cf}]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
        text = TOKEN.matcher(text).replaceAll("$1=[redacted-token]");
        text = BEARER.matcher(text).replaceAll("Bearer [redacted-token]");
        text = SESSION.matcher(text).replaceAll("$1=[redacted-session]");
        text = FILE_URI.matcher(text).replaceAll("[redacted-path]");
        text = WINDOWS_PATH.matcher(text).replaceAll("[redacted-path]");
        text = UNIX_PATH.matcher(text).replaceAll("[redacted-path]");
        return truncate(text, maximum);
    }

    private static String truncate(final String value, final int maximum) {
        return value.length() > maximum
            ? value.substring(0, maximum - 1) + "…"
            : value;
    }

    private static String requireText(final String value, final String label, final int maximum) {
        Objects.requireNonNull(value, label);
        if (value.isBlank()) throw new IllegalArgumentException(label + " must not be blank");
        if (value.length() > maximum) throw new IllegalArgumentException(label + " is too long");
        return value;
    }

    private static long saturatingIncrement(final long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1;
    }

    record Event(
        Instant observedAt,
        String diagnosticId,
        String kind,
        String provider,
        String operation,
        String outcome,
        String errorCode,
        String exceptionType,
        String message
    ) {
        Event {
            Objects.requireNonNull(observedAt, "observedAt");
            diagnosticId = requireText(diagnosticId, "diagnosticId", 128);
            kind = requireText(kind, "kind", 64);
            provider = requireText(provider, "provider", 256);
            message = requireText(message, "message", MAX_MESSAGE_CHARS);
        }
    }

    record Snapshot(Instant asOf, List<Event> events, long dropped) {
        Snapshot {
            Objects.requireNonNull(asOf, "asOf");
            events = List.copyOf(Objects.requireNonNull(events, "events"));
            if (dropped < 0) throw new IllegalArgumentException("dropped must not be negative");
        }
    }
}
