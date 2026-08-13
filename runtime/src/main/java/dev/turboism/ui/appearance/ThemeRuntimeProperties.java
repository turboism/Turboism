package dev.turboism.ui.appearance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Shared FlatLaf custom-defaults properties file.
 *
 * <p>The legacy hook agent wrote theme overrides to a runtime properties file
 * and restored the native look by deleting it and calling
 * {@code FlatLaf.updateUI()} (missing sources are skipped). Turboism uses one
 * fixed file in the JVM temp directory so both the early bootstrap injection
 * and the runtime appearance provider share the same source: applying writes
 * it, restoring deletes it. Wine isolates each Cubism prefix's temp directory,
 * so 5.2 and 5.3 sessions never share the file.</p>
 */
public final class ThemeRuntimeProperties {

    private static final String FILE_NAME = "turboism-theme-runtime.properties";

    private ThemeRuntimeProperties() {
    }

    public static Path path() {
        return Path.of(System.getProperty("java.io.tmpdir"), FILE_NAME);
    }

    public static void write(final Map<String, String> values) throws IOException {
        final StringBuilder content = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            content.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        Files.writeString(path(), content.toString(), StandardCharsets.UTF_8);
    }

    public static void delete() {
        try {
            Files.deleteIfExists(path());
        } catch (IOException ignored) {
            // Best-effort; FlatLaf skips missing sources, and a stale file only
            // re-applies values that updateUI would recompute anyway.
        }
    }
}
