package dev.turboism.tests.plugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ordered {@code key=value} records of one probe run, published as one properties file.
 *
 * <p>The host-validation runner reads the result file by exact line, so a record must never contain
 * a newline or a carriage return. Every value therefore collapses to one line and every control
 * character becomes a space: a host message with a line break cannot break the terminal verdict.</p>
 *
 * <p>Records keep their recording order. The runner reads the counter block as soon as it sees the
 * pass line, so the caller appends the counters and the terminal line last.</p>
 */
final class AutoMeshProbeResult {

    /** Separator between a record key and its value. */
    static final char KEY_SEPARATOR = '=';

    private static final char LINE_SEPARATOR = '\n';
    private static final String VALUE_REPLACEMENT = " ";

    private final Map<String, String> records = new LinkedHashMap<>();

    /**
     * Appends one record.
     *
     * <p>A repeated key keeps its first position and receives the new value, which matches the
     * properties semantics the runner's readers assume.</p>
     *
     * @param key the record key; never {@code null} or blank
     * @param value the record value; {@code null} becomes an empty value
     */
    void put(final String key, final Object value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("result record key must not be blank");
        }
        records.put(singleLine(key), value == null ? "" : singleLine(String.valueOf(value)));
    }

    /**
     * Appends one record with a boolean value.
     *
     * @param key the record key
     * @param value the recorded flag
     */
    void put(final String key, final boolean value) {
        put(key, Boolean.toString(value));
    }

    /**
     * Appends one record with an integer value.
     *
     * @param key the record key
     * @param value the recorded number
     */
    void put(final String key, final long value) {
        put(key, Long.toString(value));
    }

    /** @return the recorded keys in recording order */
    List<String> keys() {
        return List.copyOf(records.keySet());
    }

    /**
     * Returns one recorded value.
     *
     * @param key the record key
     * @return the value, or {@code null} when the key was never recorded
     */
    String get(final String key) {
        return records.get(key);
    }

    /** @return the complete file content, newline-terminated */
    String render() {
        final StringBuilder text = new StringBuilder();
        for (final Map.Entry<String, String> record : records.entrySet()) {
            text.append(record.getKey())
                    .append(KEY_SEPARATOR)
                    .append(record.getValue())
                    .append(LINE_SEPARATOR);
        }
        return text.toString();
    }

    /**
     * Writes the records to {@code file}, creating the parent directory.
     *
     * @param file the destination file
     * @throws IOException when the file cannot be written
     */
    void writeTo(final Path file) throws IOException {
        final Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, render(), StandardCharsets.UTF_8);
    }

    /**
     * Returns the assertion counter block of a run.
     *
     * <p>The runner's first screen reads {@code assertions.verdict} before {@code assertions}, so an
     * evidence-only run that decided nothing must publish a zero verdict count instead of a
     * non-zero total.</p>
     *
     * @param statuses the recorded assertion statuses in recording order
     * @return the counter records in the order the consumers read them
     */
    static Map<String, String> counters(final List<AutoMeshProbeStatus> statuses) {
        final Map<String, String> counts = new LinkedHashMap<>();
        counts.put("assertions", Integer.toString(statuses.size()));
        counts.put("assertions.verdict", Integer.toString(verdictCount(statuses)));
        for (final AutoMeshProbeStatus status : AutoMeshProbeStatus.values()) {
            counts.put("assertions." + status.token(), Integer.toString(count(statuses, status)));
        }
        return counts;
    }

    /**
     * Returns the terminal verdict of the recorded statuses.
     *
     * <p>Rule order: any {@code FAIL} fails the run; otherwise any {@code BLOCKED} blocks it;
     * otherwise a run with no verdict-bearing assertion is {@code BLOCKED}, because a run that
     * asserted nothing proved nothing; otherwise any {@code SKIPPED} skips it; otherwise it
     * passes.</p>
     *
     * @param statuses the recorded assertion statuses in recording order
     * @return the terminal status
     */
    static AutoMeshProbeStatus terminal(final List<AutoMeshProbeStatus> statuses) {
        boolean blocked = false;
        boolean skipped = false;
        int verdicts = 0;
        for (final AutoMeshProbeStatus status : statuses) {
            if (!status.verdictBearing()) {
                continue;
            }
            if (status == AutoMeshProbeStatus.FAIL) {
                return AutoMeshProbeStatus.FAIL;
            }
            if (status == AutoMeshProbeStatus.BLOCKED) {
                blocked = true;
            } else if (status == AutoMeshProbeStatus.SKIPPED) {
                skipped = true;
            }
            verdicts++;
        }
        if (blocked) {
            return AutoMeshProbeStatus.BLOCKED;
        }
        if (verdicts == 0) {
            return AutoMeshProbeStatus.BLOCKED;
        }
        return skipped ? AutoMeshProbeStatus.SKIPPED : AutoMeshProbeStatus.PASS;
    }

    /**
     * Collapses one value to a single line without control characters.
     *
     * @param value the raw value
     * @return the value the result file can carry
     */
    static String singleLine(final String value) {
        final StringBuilder text = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (Character.isISOControl(character)) {
                text.append(VALUE_REPLACEMENT);
            } else {
                text.append(character);
            }
        }
        return text.toString();
    }

    /**
     * Renders a double without exponent notation, so a record stays readable.
     *
     * @param value the value to render
     * @return the plain decimal form
     */
    static String decimal(final double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static int verdictCount(final List<AutoMeshProbeStatus> statuses) {
        int verdicts = 0;
        for (final AutoMeshProbeStatus status : statuses) {
            if (status.verdictBearing()) {
                verdicts++;
            }
        }
        return verdicts;
    }

    private static int count(final List<AutoMeshProbeStatus> statuses, final AutoMeshProbeStatus wanted) {
        int matches = 0;
        for (final AutoMeshProbeStatus status : statuses) {
            if (status == wanted) {
                matches++;
            }
        }
        return matches;
    }

    /** @return the recorded keys as a list, for callers that inspect the publication order */
    List<String> recordedKeys() {
        return new ArrayList<>(records.keySet());
    }
}
