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
        Sink STDERR = (level, line, failure) -> System.err.println(line);

        void write(Level level, String line, Throwable failure);

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

    public void setMinimumLevel(final String level) {
        minimumLevel = Level.valueOf(Objects.requireNonNull(level, "level"));
    }

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

    public void trace(final String component, final String message) {
        write(Level.TRACE, component, message, null);
    }

    public void debug(final String component, final String message) {
        write(Level.DEBUG, component, message, null);
    }

    public void info(final String component, final String message) {
        write(Level.INFO, component, message, null);
    }

    public void warn(final String component, final String message) {
        write(Level.WARN, component, message, null);
    }

    public void error(final String component, final String message, final Throwable failure) {
        write(Level.ERROR, component, message, failure);
    }

    public void fatal(final String component, final String message, final Throwable failure) {
        write(Level.FATAL, component, message, failure);
    }

    private synchronized void write(
        final Level level,
        final String component,
        final String message,
        final Throwable failure
    ) {
        if (level.ordinal() < minimumLevel.ordinal()) return;
        final String line = Instant.now(clock) + " [" + level + "] [" + safe(component) + "] " + safe(message);
        remember(line);
        try {
            sink.write(level, line, failure);
        } catch (RuntimeException exception) {
            System.out.println("Turboism preview host log write failed: " + exception.getMessage());
        }
        System.out.println(line);
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
            System.out.println("Turboism preview log write failed: " + exception.getMessage());
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
