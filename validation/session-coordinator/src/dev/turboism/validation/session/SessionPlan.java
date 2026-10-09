package dev.turboism.validation.session;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parsed {@code state/session/plan.json}: an ordered list of probe steps for one
 * multi-probe session. The parser is a minimal strict JSON reader (no third-party
 * library is available inside the host JVM).
 */
public final class SessionPlan {
    public static final String FORMAT = "turboism.validation.session-plan";
    public static final long DEFAULT_STARTUP_TIMEOUT_SECONDS = 900L;
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern HOME_RELATIVE = Pattern.compile("[A-Za-z0-9._-][A-Za-z0-9._/-]*");

    public record Step(String name, String spec, String trigger, String result,
                       long timeoutSeconds, Map<String, String> triggerProperties) {
        public Step {
            if (name == null || !SAFE_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("invalid step name: " + name);
            }
            if (spec == null || spec.isBlank()) {
                throw new IllegalArgumentException("step " + name + " has no spec");
            }
            requireHomePath(trigger, "trigger");
            requireHomePath(result, "result");
            if (timeoutSeconds < 1) {
                throw new IllegalArgumentException("step " + name + " has no positive timeout");
            }
            triggerProperties = triggerProperties == null ? Map.of() : Map.copyOf(triggerProperties);
        }

        private static void requireHomePath(final String value, final String field) {
            if (value == null || !HOME_RELATIVE.matcher(value).matches()
                    || value.contains("//") || !value.startsWith("state/")) {
                throw new IllegalArgumentException("step path field " + field
                        + " must be a normalized state/-relative path: " + value);
            }
        }
    }

    public record Plan(String session, String hostVersion, long startupTimeoutSeconds,
                       List<Step> steps) {
        public Plan {
            if (steps == null || steps.isEmpty()) {
                throw new IllegalArgumentException("session plan has no steps");
            }
            final var seen = new java.util.HashSet<String>();
            for (final Step step : steps) {
                if (!seen.add(step.name())) {
                    throw new IllegalArgumentException("duplicate step name: " + step.name());
                }
            }
            steps = List.copyOf(steps);
        }
    }

    public static Plan parse(final String json) {
        final Object root = new Json(json).read();
        if (!(root instanceof Map)) {
            throw new IllegalArgumentException("session plan must be a JSON object");
        }
        final Map<?, ?> document = (Map<?, ?>) root;
        if (!FORMAT.equals(document.get("format"))) {
            throw new IllegalArgumentException("unexpected plan format: " + document.get("format"));
        }
        if (!Long.valueOf(1L).equals(number(document.get("schemaVersion"), "schemaVersion"))) {
            throw new IllegalArgumentException("unsupported plan schemaVersion");
        }
        final Object rawSteps = document.get("steps");
        if (!(rawSteps instanceof List) || ((List<?>) rawSteps).isEmpty()) {
            throw new IllegalArgumentException("session plan steps must be a non-empty array");
        }
        final List<Step> steps = new ArrayList<>();
        for (final Object entry : (List<?>) rawSteps) {
            if (!(entry instanceof Map)) {
                throw new IllegalArgumentException("session step must be an object");
            }
            steps.add(step((Map<?, ?>) entry));
        }
        return new Plan(
                string(document.get("session"), "session"),
                string(document.get("hostVersion"), "hostVersion"),
                numberOr(document.get("startupTimeoutSeconds"), DEFAULT_STARTUP_TIMEOUT_SECONDS),
                steps);
    }

    private static Step step(final Map<?, ?> entry) {
        final Map<String, String> properties = new LinkedHashMap<>();
        final Object rawProperties = entry.get("triggerProperties");
        if (rawProperties != null) {
            if (!(rawProperties instanceof Map)) {
                throw new IllegalArgumentException("triggerProperties must be an object");
            }
            for (final Map.Entry<?, ?> property : ((Map<?, ?>) rawProperties).entrySet()) {
                properties.put(String.valueOf(property.getKey()), String.valueOf(property.getValue()));
            }
        }
        return new Step(
                string(entry.get("name"), "step.name"),
                string(entry.get("spec"), "step.spec"),
                string(entry.get("trigger"), "step.trigger"),
                string(entry.get("result"), "step.result"),
                number(entry.get("timeoutSeconds"), "step.timeoutSeconds"),
                properties);
    }

    private static String string(final Object value, final String field) {
        if (!(value instanceof String) || ((String) value).isBlank()) {
            throw new IllegalArgumentException("session plan field " + field + " must be a non-empty string");
        }
        return (String) value;
    }

    private static long number(final Object value, final String field) {
        if (!(value instanceof Long)) {
            throw new IllegalArgumentException("session plan field " + field + " must be an integer");
        }
        return (Long) value;
    }

    private static long numberOr(final Object value, final long fallback) {
        return value instanceof Long ? (Long) value : fallback;
    }

    /** Minimal strict JSON reader: objects, arrays, strings, integers, booleans, null. */
    private static final class Json {
        private final String text;
        private int position;

        Json(final String text) {
            this.text = text == null ? "" : text;
        }

        Object read() {
            final Object value = value();
            skipWhitespace();
            if (position != text.length()) {
                throw new IllegalArgumentException("trailing content after JSON document");
            }
            return value;
        }

        private Object value() {
            skipWhitespace();
            if (position >= text.length()) {
                throw new IllegalArgumentException("unexpected end of JSON input");
            }
            return switch (text.charAt(position)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            expect('{');
            final Map<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                position++;
                return result;
            }
            while (true) {
                skipWhitespace();
                final String key = string();
                skipWhitespace();
                expect(':');
                result.put(key, value());
                skipWhitespace();
                if (peek(',')) {
                    position++;
                    continue;
                }
                expect('}');
                return result;
            }
        }

        private List<Object> array() {
            expect('[');
            final List<Object> result = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                position++;
                return result;
            }
            while (true) {
                result.add(value());
                skipWhitespace();
                if (peek(',')) {
                    position++;
                    continue;
                }
                expect(']');
                return result;
            }
        }

        private String string() {
            expect('"');
            final StringBuilder decoded = new StringBuilder();
            while (position < text.length()) {
                final char current = text.charAt(position++);
                if (current == '"') {
                    return decoded.toString();
                }
                if (current == '\\') {
                    if (position >= text.length()) break;
                    final char escape = text.charAt(position++);
                    switch (escape) {
                        case '"' -> decoded.append('"');
                        case '\\' -> decoded.append('\\');
                        case '/' -> decoded.append('/');
                        case 'b' -> decoded.append('\b');
                        case 'f' -> decoded.append('\f');
                        case 'n' -> decoded.append('\n');
                        case 'r' -> decoded.append('\r');
                        case 't' -> decoded.append('\t');
                        case 'u' -> {
                            if (position + 4 > text.length()) {
                                throw new IllegalArgumentException("truncated \\u escape");
                            }
                            decoded.append((char) Integer.parseInt(
                                    text.substring(position, position + 4), 16));
                            position += 4;
                        }
                        default -> throw new IllegalArgumentException("unsupported escape: \\" + escape);
                    }
                } else {
                    decoded.append(current);
                }
            }
            throw new IllegalArgumentException("unterminated JSON string");
        }

        private Object number() {
            final int start = position;
            if (peek('-')) position++;
            while (position < text.length() && Character.isDigit(text.charAt(position))) position++;
            if (position < text.length() && text.charAt(position) == '.') {
                throw new IllegalArgumentException("non-integer numbers are not used in session plans");
            }
            if (position == start || (position == start + 1 && text.charAt(start) == '-')) {
                throw new IllegalArgumentException("expected a JSON value at offset " + start);
            }
            return Long.parseLong(text.substring(start, position));
        }

        private Object literal(final String word, final Object value) {
            if (!text.startsWith(word, position)) {
                throw new IllegalArgumentException("expected '" + word + "' at offset " + position);
            }
            position += word.length();
            return value;
        }

        private void expect(final char expected) {
            if (position >= text.length() || text.charAt(position) != expected) {
                throw new IllegalArgumentException(
                        "expected '" + expected + "' at offset " + position);
            }
            position++;
        }

        private boolean peek(final char candidate) {
            return position < text.length() && text.charAt(position) == candidate;
        }

        private void skipWhitespace() {
            while (position < text.length()) {
                final char current = text.charAt(position);
                if (current != ' ' && current != '\t' && current != '\n' && current != '\r') return;
                position++;
            }
        }
    }
}
