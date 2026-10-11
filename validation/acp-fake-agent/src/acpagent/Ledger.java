package acpagent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Assertion ledger and terminal result-file writer, mirroring the MCP probe contract. */
final class Ledger {

    private final LinkedHashMap<String, String> rows = new LinkedHashMap<>();

    void pass(final String assertion) {
        rows.put("assertion." + assertion + ".status", "PASS");
        rows.remove("assertion." + assertion + ".reason");
    }

    void fail(final String assertion, final String reason) {
        rows.put("assertion." + assertion + ".status", "FAIL");
        rows.put("assertion." + assertion + ".reason", reason.replaceAll("\\s+", "_"));
    }

    /** Records a non-judging observation the probe cross-reads against its own SDK view. */
    void data(final String key, final String value) {
        rows.put(key, value);
    }

    boolean allPassed() {
        return rows.entrySet().stream()
                .filter(entry -> entry.getKey().endsWith(".status"))
                .allMatch(entry -> "PASS".equals(entry.getValue()) || "NOT_APPLICABLE".equals(entry.getValue()));
    }

    boolean anyFailed() {
        return !allPassed();
    }

    /** Writes the terminal result file with the probe contract: runId binding plus a final status. */
    void write(final Path file, final String runId) throws IOException {
        final LinkedHashMap<String, String> document = new LinkedHashMap<>();
        document.put("schemaVersion", "1");
        document.put("runId", runId);
        document.put("client", "acp-fake-agent");
        document.putAll(rows);
        document.put("status", allPassed() ? "PASS" : "FAIL");
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        final StringBuilder out = new StringBuilder();
        document.forEach((key, value) -> out.append(key).append('=').append(value).append('\n'));
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
    }

    Map<String, String> snapshot() {
        return new LinkedHashMap<>(rows);
    }
}
