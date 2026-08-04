package dev.turboism.sdk.runtime;

import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Framework access to Cubism's own log stream.
 *
 * <p>Subscribers receive every log entry produced by Cubism (the same stream
 * the editor's log palette renders). {@link #setFilter(LogFilter)} publishes
 * the framework's pre-render filter so host adapters can stop non-matching
 * entries before they reach the log palette, while subscribers always see the
 * unfiltered stream (they apply their own matching when needed).</p>
 */
public interface CubismLogService {

    /** Subscribes to Cubism log entries. The stream is unfiltered. */
    Registration subscribe(Consumer<LogEntry> listener);

    /** Publishes the pre-render filter for the Cubism log palette. */
    void setFilter(LogFilter filter);

    /** Returns the currently published filter. */
    LogFilter filter();

    enum LogLevel {
        TRACE, DEBUG, INFO, WARN, ERROR, FATAL
    }

    /** One Cubism log entry. */
    record LogEntry(LogLevel level, String message, long timestampNanos) {
        public LogEntry {
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(message, "message");
        }
    }

    /** Pre-render filter for the Cubism log palette (level visibility + keyword). */
    record LogFilter(boolean showInfo, boolean showWarn, boolean showError, String keyword) {

        public LogFilter {
            keyword = keyword == null ? "" : keyword;
        }

        public static LogFilter all() {
            return new LogFilter(true, true, true, "");
        }

        public boolean matches(final LogEntry entry) {
            final boolean levelVisible = switch (entry.level()) {
                case INFO, DEBUG, TRACE -> showInfo;
                case WARN -> showWarn;
                case ERROR, FATAL -> showError;
            };
            if (!levelVisible) {
                return false;
            }
            final String normalized = keyword.trim().toLowerCase(java.util.Locale.ROOT);
            return normalized.isEmpty()
                || entry.message().toLowerCase(java.util.Locale.ROOT).contains(normalized);
        }
    }

    static CubismLogService unavailable() {
        return new CubismLogService() {
            private LogFilter filter = LogFilter.all();

            @Override
            public Registration subscribe(final Consumer<LogEntry> listener) {
                Objects.requireNonNull(listener, "listener");
                return () -> { };
            }

            @Override
            public void setFilter(final LogFilter filter) {
                this.filter = Objects.requireNonNull(filter, "filter");
            }

            @Override
            public LogFilter filter() {
                return filter;
            }
        };
    }
}
