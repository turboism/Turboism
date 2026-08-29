package dev.turboism.preview;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import dev.turboism.mapping.verification.VerifiedAccessException;
import dev.turboism.sdk.runtime.RuntimeLogReader;
import dev.turboism.sdk.runtime.RuntimeSettings;

/** Small preview-owned logger that writes to a framework sink and relocatable session files. */
public final class PreviewLog implements AutoCloseable, RuntimeLogReader {

    private static final int RECENT_LINE_LIMIT = 5_000;
    private static final int PRUNE_INTERVAL_WRITES = 256;

    /** Stack frames written per throwable in a cause chain, bounded so a log cannot run away. */
    private static final int MAX_LOGGED_FRAMES = 24;
    private static final DateTimeFormatter SESSION_DATE = DateTimeFormatter
        .ofPattern("yyyy-MM-dd")
        .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter SESSION_TIME = DateTimeFormatter
        .ofPattern("HH-mm-ss.SSS")
        .withZone(ZoneOffset.UTC);

    enum Level {
        TRACE,
        DEBUG,
        INFO,
        WARN,
        ERROR,
        FATAL
    }

    @FunctionalInterface
    interface Sink {
        Sink STDERR = (level, component, message, failure) ->
            System.err.println("[" + level + "][" + component + "] " + message);

        /**
         * Writes one record to the host logger. The sink receives structured fields rather than the
         * session-file line so the host's own appender remains the sole owner of timestamps and
         * level prefixes.
         */
        void write(Level level, String component, String message, Throwable failure);

        default void close() {}
    }

    private final Clock clock;
    private final BufferedWriter writer;
    private final Sink sink;
    private final Path logDirectory;
    private final Path logFile;
    private final ArrayDeque<String> recentLines = new ArrayDeque<>(RECENT_LINE_LIMIT);
    private volatile Level minimumLevel = Level.INFO;
    private long maxStorageBytes = RuntimeSettings.DEFAULT_MAX_LOG_STORAGE_MIB * 1024L * 1024L;
    private int writesUntilPrune = PRUNE_INTERVAL_WRITES;

    public PreviewLog(final Path logFile) throws IOException {
        this(logFile, Clock.systemUTC(), Sink.STDERR);
    }

    PreviewLog(final Path logFile, final Clock clock) throws IOException {
        this(logFile, clock, Sink.STDERR);
    }

    PreviewLog(final Path logFile, final Clock clock, final Sink sink) throws IOException {
        this(normalizedParent(logFile), logFile, clock, sink);
    }

    private PreviewLog(
        final Path logDirectory,
        final Path logFile,
        final Clock clock,
        final Sink sink
    ) throws IOException {
        this.logDirectory = Objects.requireNonNull(logDirectory, "logDirectory")
            .toAbsolutePath().normalize();
        this.logFile = Objects.requireNonNull(logFile, "logFile").toAbsolutePath().normalize();
        if (!this.logFile.startsWith(this.logDirectory)) {
            throw new IllegalArgumentException("log file escapes log directory");
        }
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sink = Objects.requireNonNull(sink, "sink");
        Files.createDirectories(this.logFile.getParent());
        writer = Files.newBufferedWriter(
            this.logFile,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
            StandardOpenOption.WRITE
        );
    }

    static PreviewLog openSession(
        final Path requestedDirectory,
        final Clock clock,
        final long processId,
        final Sink sink
    ) throws IOException {
        if (processId < 0) throw new IllegalArgumentException("processId must not be negative");
        final Path directory = Objects.requireNonNull(requestedDirectory, "requestedDirectory")
            .toAbsolutePath().normalize();
        final Instant startedAt = Instant.now(Objects.requireNonNull(clock, "clock"));
        final Path dateDirectory = directory.resolve(SESSION_DATE.format(startedAt));
        Files.createDirectories(dateDirectory);
        final Path file = Files.createTempFile(
            dateDirectory,
            "turboism-" + SESSION_TIME.format(startedAt) + "-p" + processId + "-",
            ".log"
        );
        return new PreviewLog(directory, file, clock, sink);
    }

    /**
     * Raises or lowers the threshold below which messages are dropped. Takes effect immediately
     * for all threads; the field is volatile and this call needs no lock.
     *
     * @param level name of a {@code Level} constant, case-sensitive
     * @throws NullPointerException if {@code level} is null
     * @throws IllegalArgumentException if {@code level} does not name a known level
     */
    public void setMinimumLevel(final String level) {
        minimumLevel = Level.valueOf(Objects.requireNonNull(level, "level"));
    }

    /**
     * Sets the total budget for retained session log files and immediately prunes older sessions
     * down to it. The current session's file is never what the budget forces away first — pruning
     * works from the oldest sessions.
     *
     * @param value budget in MiB, within {@code RuntimeSettings}' supported min/max
     * @throws IllegalArgumentException if {@code value} is outside the supported range; the budget
     *     is left unchanged and nothing is pruned
     */
    public synchronized void setMaxStorageMiB(final int value) {
        if (value < RuntimeSettings.MIN_MAX_LOG_STORAGE_MIB
            || value > RuntimeSettings.MAX_MAX_LOG_STORAGE_MIB) {
            throw new IllegalArgumentException("unsupported maxLogStorageMiB: " + value);
        }
        maxStorageBytes = value * 1024L * 1024L;
        pruneOldSessions();
    }

    @Override
    public synchronized RuntimeLogReader.Snapshot snapshot() {
        return new RuntimeLogReader.Snapshot(
            Optional.of(logDirectory),
            Optional.of(logFile),
            List.copyOf(recentLines)
        );
    }

    /**
     * Records a TRACE-level line. Dropped without reaching the file when the minimum level is
     * above TRACE.
     *
     * @param component short subsystem tag included in the line
     * @param message the text; sanitized before it reaches the file, the sink and stdout
     */
    public void trace(final String component, final String message) {
        write(Level.TRACE, component, message, null);
    }

    /**
     * Records a DEBUG-level line. Dropped when the minimum level is above DEBUG.
     *
     * @param component short subsystem tag included in the line
     * @param message the text; sanitized before it reaches the file, the sink and stdout
     */
    public void debug(final String component, final String message) {
        write(Level.DEBUG, component, message, null);
    }

    /**
     * Records an INFO-level line, which is the default threshold.
     *
     * @param component short subsystem tag included in the line
     * @param message the text; sanitized before it reaches the file, the sink and stdout
     */
    public void info(final String component, final String message) {
        write(Level.INFO, component, message, null);
    }

    /**
     * Records a WARN-level line.
     *
     * @param component short subsystem tag included in the line
     * @param message the text; sanitized before it reaches the file, the sink and stdout
     */
    public void warn(final String component, final String message) {
        write(Level.WARN, component, message, null);
    }

    /**
     * Records an ERROR-level line together with a throwable. The cause chain is written to the log
     * file up to eight levels deep as class name plus sanitized message, followed by at most 24
     * stack frames per cause. A failing sink or a failing file write is swallowed — logging never
     * propagates an exception to its caller.
     *
     * @param component short subsystem tag included in the line
     * @param message the text; sanitized before it reaches the file, the sink and stdout
     * @param failure throwable to attach, or null to write the line alone
     */
    public void error(final String component, final String message, final Throwable failure) {
        write(Level.ERROR, component, message, failure);
    }

    /**
     * Records a FATAL-level line, the highest level, which no minimum-level setting suppresses.
     * The cause chain is handled exactly as in {@link #error}.
     *
     * @param component short subsystem tag included in the line
     * @param message the text; sanitized before it reaches the file, the sink and stdout
     * @param failure throwable to attach, or null to write the line alone
     */
    public void fatal(final String component, final String message, final Throwable failure) {
        write(Level.FATAL, component, message, failure);
    }

    /** Publishes one intentional multiline startup record without console duplication. */
    synchronized void banner(final String message) {
        final String safeMessage = safeMultiline(message);
        try {
            sink.write(Level.INFO, "startup", safeMessage, null);
        } catch (RuntimeException exception) {
            System.err.println("Turboism host log write failed safely");
        }
        try {
            for (String line : safeMessage.split("\\n", -1)) {
                remember(line);
                writer.write(line);
                writer.newLine();
            }
            writer.flush();
        } catch (IOException exception) {
            System.err.println("Turboism runtime log write failed safely");
        }
    }

    private synchronized void write(
        final Level level,
        final String component,
        final String message,
        final Throwable failure
    ) {
        if (level.ordinal() < minimumLevel.ordinal()) return;
        final String safeComponent = safe(component);
        final String safeMessage = safe(message);
        final String line = Instant.now(clock) + " [" + level + "] [" + safeComponent + "] " + safeMessage;
        remember(line);
        try {
            sink.write(level, safeComponent, safeMessage, failure);
        } catch (RuntimeException exception) {
            System.err.println("Turboism host log write failed safely");
        }
        try {
            writer.write(line);
            writer.newLine();
            if (failure != null) {
                writeFailure(failure);
            }
            writer.flush();
            if (--writesUntilPrune == 0) {
                writesUntilPrune = PRUNE_INTERVAL_WRITES;
                pruneOldSessions();
            }
        } catch (IOException exception) {
            System.err.println("Turboism runtime log write failed safely");
        }
    }

    private void writeFailure(final Throwable failure) throws IOException {
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth < 8) {
            final StringBuilder line = new StringBuilder(depth > 0 ? "caused by " : "")
                .append(current.getClass().getName()).append(": ").append(safe(current.getMessage()));
            if (current instanceof VerifiedAccessException verified) {
                line.append(" [alias=").append(safe(verified.alias()))
                    .append(", failureKind=").append(verified.failureKind()).append(']');
            }
            remember(line.toString());
            writer.write(line.toString());
            writer.newLine();
            // Frames, bounded. Without them a failure that carries no message -- a
            // NoSuchElementException out of a static initializer, say -- reduces to two lines
            // that name the exception type and nothing about where it came from, which is not
            // enough to diagnose an exact-host run.
            final StackTraceElement[] frames = current.getStackTrace();
            for (int index = 0; index < Math.min(frames.length, MAX_LOGGED_FRAMES); index++) {
                final String frame = "\tat " + safe(frames[index].toString());
                remember(frame);
                writer.write(frame);
                writer.newLine();
            }
            if (frames.length > MAX_LOGGED_FRAMES) {
                final String elided = "\t... " + (frames.length - MAX_LOGGED_FRAMES) + " more";
                remember(elided);
                writer.write(elided);
                writer.newLine();
            }
            current = current.getCause();
            depth++;
        }
    }

    private void pruneOldSessions() {
        try {
            final List<StoredLog> files = new ArrayList<>();
            try (Stream<Path> paths = Files.walk(logDirectory)) {
                for (Path path : paths.filter(PreviewLog::isStoredLog).toList()) {
                    files.add(new StoredLog(
                        path.toAbsolutePath().normalize(),
                        Files.size(path),
                        Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS)
                    ));
                }
            }
            files.sort(Comparator.comparing(StoredLog::modified).thenComparing(value -> value.path().toString()));
            long total = files.stream().mapToLong(StoredLog::size).sum();
            int remaining = files.size();
            for (StoredLog file : files) {
                if (total <= maxStorageBytes || remaining <= 1) break;
                // ponytail: keep the active session intact; add size segments only if one real session exceeds the cap.
                if (file.path().equals(logFile)) continue;
                if (Files.deleteIfExists(file.path())) {
                    total -= file.size();
                    remaining--;
                }
            }
        } catch (IOException exception) {
            System.err.println("Turboism preview log cleanup failed: " + exception.getMessage());
        }
    }

    private static boolean isStoredLog(final Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            && path.getFileName().toString().endsWith(".log");
    }

    private static Path normalizedParent(final Path requestedFile) {
        final Path file = Objects.requireNonNull(requestedFile, "logFile").toAbsolutePath().normalize();
        return file.getParent();
    }

    private void remember(final String line) {
        if (recentLines.size() == RECENT_LINE_LIMIT) recentLines.removeFirst();
        recentLines.addLast(line);
    }

    private static String safe(final String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ');
    }

    private static String safeMultiline(final String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n');
    }

    @Override
    public synchronized void close() throws IOException {
        try {
            writer.close();
        } finally {
            sink.close();
        }
    }

    private record StoredLog(Path path, long size, FileTime modified) {}
}
