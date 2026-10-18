package dev.turboism.test.plugin;

import dev.turboism.sdk.plugin.PluginLogger;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Recording test fake for {@link PluginLogger}.
 */
public class RecordingPluginLogger implements PluginLogger {

    public record LogRecord(String level, String message, Throwable throwable) {
        public LogRecord {
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(message, "message");
        }
    }

    private final List<LogRecord> records = new CopyOnWriteArrayList<>();

    @Override
    public void debug(final String message) {
        records.add(new LogRecord("DEBUG", message, null));
    }

    @Override
    public void info(final String message) {
        records.add(new LogRecord("INFO", message, null));
    }

    @Override
    public void warn(final String message) {
        records.add(new LogRecord("WARN", message, null));
    }

    @Override
    public void error(final String message) {
        records.add(new LogRecord("ERROR", message, null));
    }

    @Override
    public void error(final String message, final Throwable throwable) {
        records.add(new LogRecord("ERROR", message, throwable));
    }

    public List<LogRecord> records() {
        return List.copyOf(records);
    }

    public List<String> messages() {
        return records.stream().map(LogRecord::message).toList();
    }

    public void clear() {
        records.clear();
    }
}
