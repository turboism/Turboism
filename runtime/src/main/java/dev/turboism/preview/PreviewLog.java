package dev.turboism.preview;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.sdk.runtime.RuntimeLogReader;
import dev.turboism.sdk.runtime.RuntimeSettings;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Small preview-owned logger that writes to a framework sink and relocatable session files.
 *
 * <p>The constructor synchronously ensures directory existence and opens the active session file.
 * Subsequent file writes, log rotation, secondary sink dispatch, and directory maintenance are
 * performed asynchronously on a single background worker thread, while the in-memory recent lines
 * ring buffer remains immediately visible and bounded on calling threads.</p>
 */
public final class PreviewLog implements AutoCloseable, RuntimeLogReader {

    private static final int RECENT_LINE_LIMIT = 5_000;
    private static final long RECENT_BYTES_LIMIT = 2 * 1024 * 1024L;
    private static final int PRUNE_INTERVAL_WRITES = 256;

    private static final int MAX_QUEUE_ENTRIES = 2_048;
    private static final long MAX_QUEUE_BYTES = 2 * 1024 * 1024L;
    private static final long CLOSE_DRAIN_TIMEOUT_MS = 2_000L;

    private static final long DEFAULT_MAX_SEGMENT_BYTES = 10 * 1024 * 1024L;
    private static final long MIN_SEGMENT_BYTES = 128 * 1024L;

    private static final DateTimeFormatter SESSION_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter SESSION_TIME =
            DateTimeFormatter.ofPattern("HH-mm-ss.SSS").withZone(ZoneOffset.UTC);

    /** Severity levels supported by the preview logging subsystem. */
    public enum Level {
        TRACE,
        DEBUG,
        INFO,
        WARN,
        ERROR,
        FATAL
    }

    /** Destination sink for structured log records dispatched by the preview logger. */
    @FunctionalInterface
    public interface Sink {
        Sink NONE = (level, component, message, failure) -> {};
        Sink STDERR = (level, component, message, failure) ->
                System.err.println("[" + level + "][" + component + "] " + message);

        /** Writes one structured record to an optional secondary sink. */
        void write(Level level, String component, String message, Throwable failure);

        /** Closes this sink and releases any underlying resources. */
        default void close() {}
    }

    @FunctionalInterface
    interface FileMover {
        void move(Path source, Path target, CopyOption... options) throws IOException;
    }

    private final Clock clock;
    private final Path logDirectory;
    private final Path baseLogFile;
    private final Sink sink;

    // Snapshot state: immediately visible on calling threads
    private final Object snapshotLock = new Object();
    private final ArrayDeque<String> recentLines = new ArrayDeque<>(RECENT_LINE_LIMIT);
    private long recentLinesBytes = 0L;
    private volatile Path currentActiveFile;

    private volatile Level minimumLevel = Level.INFO;

    // Admission lock guarding queue state, sequence numbers, and storage budget settings
    private final Object admissionLock = new Object();
    private final ArrayDeque<QueueEntry> recordQueue = new ArrayDeque<>();
    private long queueBytes = 0L;
    private long lastEnqueuedEntrySeq = 0L;
    private long flushedSeq = 0L;
    private long pendingPruneSeq = 0L;
    private long completedPruneSeq = 0L;
    private boolean closed = false;
    private int droppedCount = 0;

    private volatile long maxStorageBytes = RuntimeSettings.DEFAULT_MAX_LOG_STORAGE_MIB * 1024L * 1024L;
    private volatile long maxSegmentBytes = Math.min(
            DEFAULT_MAX_SEGMENT_BYTES,
            Math.max(MIN_SEGMENT_BYTES, RuntimeSettings.DEFAULT_MAX_LOG_STORAGE_MIB * 1024L * 1024L / 4));

    // Sink loopback prevention
    private final ThreadLocal<Boolean> inSink = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final CountDownLatch workerTerminated = new CountDownLatch(1);
    private final Thread workerThread;

    // Resources owned exclusively by the background worker thread:
    private BufferedWriter writer;
    private long currentSegmentBytes = 0L;
    private int writesUntilPrune = PRUNE_INTERVAL_WRITES;
    private int segmentSequence = 1;
    private FileMover fileMover = Files::move;

    public PreviewLog(final Path logFile) throws IOException {
        this(logFile, Clock.systemUTC(), Sink.STDERR);
    }

    PreviewLog(final Path logFile, final Clock clock) throws IOException {
        this(logFile, clock, Sink.STDERR);
    }

    PreviewLog(final Path logFile, final Clock clock, final Sink sink) throws IOException {
        this(normalizedParent(logFile), logFile, clock, sink);
    }

    private PreviewLog(final Path logDirectory, final Path logFile, final Clock clock, final Sink sink)
            throws IOException {
        this.logDirectory = Objects.requireNonNull(logDirectory, "logDirectory")
                .toAbsolutePath()
                .normalize();
        this.baseLogFile =
                Objects.requireNonNull(logFile, "logFile").toAbsolutePath().normalize();
        if (!this.baseLogFile.startsWith(this.logDirectory)) {
            throw new IllegalArgumentException("log file escapes log directory");
        }
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sink = Objects.requireNonNull(sink, "sink");

        Files.createDirectories(this.baseLogFile.getParent());
        this.currentActiveFile = this.baseLogFile;
        this.currentSegmentBytes = Files.exists(this.baseLogFile) ? Files.size(this.baseLogFile) : 0L;
        this.writer = Files.newBufferedWriter(
                this.baseLogFile,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE);

        this.workerThread = new Thread(this::runWorkerLoop, "Turboism-PreviewLog-Writer");
        this.workerThread.setDaemon(true);
        this.workerThread.start();
    }

    static PreviewLog openSession(
            final Path requestedDirectory, final Clock clock, final long processId, final Sink sink)
            throws IOException {
        if (processId < 0) throw new IllegalArgumentException("processId must not be negative");
        final Path directory = Objects.requireNonNull(requestedDirectory, "requestedDirectory")
                .toAbsolutePath()
                .normalize();
        final Instant startedAt = Instant.now(Objects.requireNonNull(clock, "clock"));
        final Path dateDirectory = directory.resolve(SESSION_DATE.format(startedAt));
        Files.createDirectories(dateDirectory);
        final Path file = Files.createTempFile(
                dateDirectory, "turboism-" + SESSION_TIME.format(startedAt) + "-p" + processId + "-", ".log");
        return new PreviewLog(directory, file, clock, sink);
    }

    void setFileMoverForTesting(final FileMover fileMover) {
        this.fileMover = Objects.requireNonNull(fileMover, "fileMover");
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
     * Sets the total budget for retained session log files and schedules an asynchronous prune.
     * The current session's file is never what the budget forces away first — pruning works
     * from the oldest sessions and completed segments.
     *
     * @param value budget in MiB, within {@code RuntimeSettings}' supported min/max
     * @throws IllegalArgumentException if {@code value} is outside the supported range; the budget
     *     is left unchanged and nothing is pruned
     */
    public void setMaxStorageMiB(final int value) {
        if (value < RuntimeSettings.MIN_MAX_LOG_STORAGE_MIB || value > RuntimeSettings.MAX_MAX_LOG_STORAGE_MIB) {
            throw new IllegalArgumentException("unsupported maxLogStorageMiB: " + value);
        }
        synchronized (admissionLock) {
            this.maxStorageBytes = value * 1024L * 1024L;
            this.maxSegmentBytes =
                    Math.min(DEFAULT_MAX_SEGMENT_BYTES, Math.max(MIN_SEGMENT_BYTES, this.maxStorageBytes / 4));
            this.pendingPruneSeq++;
            admissionLock.notifyAll();
        }
    }

    @Override
    public RuntimeLogReader.Snapshot snapshot() {
        synchronized (snapshotLock) {
            return new RuntimeLogReader.Snapshot(
                    Optional.of(logDirectory), Optional.of(currentActiveFile), List.copyOf(recentLines));
        }
    }

    /**
     * Records a TRACE-level line. Dropped without reaching the file when the minimum level is
     * above TRACE.
     *
     * @param component short subsystem tag included in the line
     * @param message the text; sanitized before it reaches the framework file and optional sink
     */
    public void trace(final String component, final String message) {
        write(Level.TRACE, component, message, null);
    }

    /**
     * Records a DEBUG-level line. Dropped when the minimum level is above DEBUG.
     *
     * @param component short subsystem tag included in the line
     * @param message the text; sanitized before it reaches the framework file and optional sink
     */
    public void debug(final String component, final String message) {
        write(Level.DEBUG, component, message, null);
    }

    /**
     * Records an INFO-level line, which is the default threshold.
     *
     * @param component short subsystem tag included in the line
     * @param message the text; sanitized before it reaches the framework file and optional sink
     */
    public void info(final String component, final String message) {
        write(Level.INFO, component, message, null);
    }

    /**
     * Records a WARN-level line.
     *
     * @param component short subsystem tag included in the line
     * @param message the text; sanitized before it reaches the framework file and optional sink
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
     * @param message the text; sanitized before it reaches the framework file and optional sink
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
     * @param message the text; sanitized before it reaches the framework file and optional sink
     * @param failure throwable to attach, or null to write the line alone
     */
    public void fatal(final String component, final String message, final Throwable failure) {
        write(Level.FATAL, component, message, failure);
    }

    /** Publishes one intentional multiline startup record without console duplication. */
    synchronized void banner(final String message) {
        final String safeMessage =
                PreviewLogFormatter.safeMultiline(message, PreviewLogFormatter.MAX_MESSAGE_CHARS * 4);
        final String[] lines = safeMessage.split("\\n", -1);
        final List<String> lineList = List.of(lines);
        long linesBytes = 0L;
        for (String line : lineList) {
            remember(line);
            linesBytes += line.length() * 2L + 32L;
        }
        final long entryBytes = safeMessage.length() * 2L + linesBytes + 128L;
        enqueue(new QueueEntry(0L, Level.INFO, "startup", safeMessage, null, null, lineList, true, false, entryBytes));
    }

    private void write(final Level level, final String component, final String message, final Throwable failure) {
        if (level.ordinal() < minimumLevel.ordinal()) return;
        final String safeComponent = PreviewLogFormatter.safe(component, PreviewLogFormatter.MAX_COMPONENT_CHARS);
        final String safeMessage = PreviewLogFormatter.safe(message, PreviewLogFormatter.MAX_MESSAGE_CHARS);
        final String line = Instant.now(clock) + " [" + level + "] [" + safeComponent + "] " + safeMessage;

        final PreviewLogFormatter.FailureSnapshot snapshot = PreviewLogFormatter.capture(failure);

        remember(line);
        for (String fLine : snapshot.formattedLines()) {
            remember(fLine);
        }

        final boolean skipSink = inSink.get();
        final long entryBytes = line.length() * 2L
                + safeComponent.length() * 2L
                + safeMessage.length() * 2L
                + snapshot.estimatedMemoryBytes()
                + 128L;

        enqueue(new QueueEntry(
                0L,
                level,
                safeComponent,
                safeMessage,
                snapshot.cleanThrowable(),
                line,
                snapshot.formattedLines(),
                false,
                skipSink,
                entryBytes));
    }

    private void enqueue(final QueueEntry entry) {
        synchronized (admissionLock) {
            if (closed) {
                return;
            }
            final long entryBytes = entry.estimatedBytes();
            final Level level = entry.level();

            if (recordQueue.size() >= MAX_QUEUE_ENTRIES || queueBytes + entryBytes > MAX_QUEUE_BYTES) {
                if (level.ordinal() <= Level.WARN.ordinal()) {
                    droppedCount++;
                    return;
                }
                while (!recordQueue.isEmpty()
                        && (recordQueue.size() >= MAX_QUEUE_ENTRIES || queueBytes + entryBytes > MAX_QUEUE_BYTES)) {
                    final QueueEntry head = recordQueue.peekFirst();
                    if (head != null && head.level().ordinal() <= Level.WARN.ordinal()) {
                        recordQueue.removeFirst();
                        queueBytes -= head.estimatedBytes();
                        droppedCount++;
                    } else {
                        break;
                    }
                }
                if (recordQueue.size() >= MAX_QUEUE_ENTRIES || queueBytes + entryBytes > MAX_QUEUE_BYTES) {
                    droppedCount++;
                    return;
                }
            }

            final long seq = ++lastEnqueuedEntrySeq;
            recordQueue.addLast(entry.withSequence(seq));
            queueBytes += entryBytes;
            admissionLock.notifyAll();
        }
    }

    private void remember(final String line) {
        final long lineBytes = line == null ? 0L : (line.length() * 2L + 40L);
        synchronized (snapshotLock) {
            while (!recentLines.isEmpty()
                    && (recentLines.size() >= RECENT_LINE_LIMIT || recentLinesBytes + lineBytes > RECENT_BYTES_LIMIT)) {
                final String removed = recentLines.removeFirst();
                recentLinesBytes -= (removed.length() * 2L + 40L);
            }
            recentLines.addLast(line);
            recentLinesBytes += lineBytes;
        }
    }

    private void runWorkerLoop() {
        try {
            if (currentSegmentBytes >= maxSegmentBytes) {
                rotateSegment();
            }

            while (true) {
                final List<QueueEntry> batch = new ArrayList<>(128);
                boolean shouldPrune = false;
                long targetPruneSeq = 0L;
                int droppedToReport = 0;

                synchronized (admissionLock) {
                    while (recordQueue.isEmpty() && pendingPruneSeq <= completedPruneSeq && !closed) {
                        admissionLock.wait(100);
                    }

                    while (!recordQueue.isEmpty() && batch.size() < 128) {
                        final QueueEntry entry = recordQueue.removeFirst();
                        queueBytes -= entry.estimatedBytes();
                        batch.add(entry);
                    }

                    if (pendingPruneSeq > completedPruneSeq) {
                        shouldPrune = true;
                        targetPruneSeq = pendingPruneSeq;
                    }

                    if (droppedCount > 0) {
                        droppedToReport = droppedCount;
                        droppedCount = 0;
                    }

                    if (batch.isEmpty() && !shouldPrune && droppedToReport == 0 && closed) {
                        break;
                    }
                }

                if (droppedToReport > 0) {
                    emitDroppedSummary(droppedToReport);
                }

                boolean wroteLines = false;
                if (!batch.isEmpty()) {
                    wroteLines = processBatch(batch);
                }

                if (shouldPrune && currentSegmentBytes >= maxSegmentBytes) {
                    rotateSegment();
                }

                flushWriter();

                if (shouldPrune || (wroteLines && --writesUntilPrune <= 0)) {
                    writesUntilPrune = PRUNE_INTERVAL_WRITES;
                    pruneOldSessions();
                }

                final long batchMaxSeq = batch.isEmpty()
                        ? flushedSeq
                        : batch.get(batch.size() - 1).sequence();

                synchronized (admissionLock) {
                    if (batchMaxSeq > flushedSeq) {
                        flushedSeq = batchMaxSeq;
                    }
                    if (shouldPrune && targetPruneSeq > completedPruneSeq) {
                        completedPruneSeq = targetPruneSeq;
                    }
                    admissionLock.notifyAll();
                }
            }

            drainRemainingOnShutdown();
        } catch (Throwable t) {
            FatalErrors.rethrowIfFatal(t);
            System.err.println("Turboism preview log worker terminated unexpectedly: " + t.getMessage());
        } finally {
            try {
                closeResources();
            } finally {
                workerTerminated.countDown();
            }
        }
    }

    private void drainRemainingOnShutdown() {
        final List<QueueEntry> remaining = new ArrayList<>();
        int droppedToReport = 0;
        long remainingMaxSeq = 0L;
        long targetPruneSeq = 0L;

        synchronized (admissionLock) {
            while (!recordQueue.isEmpty()) {
                final QueueEntry entry = recordQueue.removeFirst();
                queueBytes -= entry.estimatedBytes();
                remaining.add(entry);
                remainingMaxSeq = entry.sequence();
            }
            droppedToReport = droppedCount;
            droppedCount = 0;
            targetPruneSeq = pendingPruneSeq;
        }

        if (droppedToReport > 0) {
            emitDroppedSummary(droppedToReport);
        }
        if (!remaining.isEmpty()) {
            processBatch(remaining);
        }

        if (currentSegmentBytes >= maxSegmentBytes) {
            rotateSegment();
        }
        flushWriter();
        pruneOldSessions();

        synchronized (admissionLock) {
            if (remainingMaxSeq > flushedSeq) {
                flushedSeq = remainingMaxSeq;
            }
            if (targetPruneSeq > completedPruneSeq) {
                completedPruneSeq = targetPruneSeq;
            }
            admissionLock.notifyAll();
        }
    }

    private boolean processBatch(final List<QueueEntry> batch) {
        boolean wroteLines = false;
        for (QueueEntry entry : batch) {
            if (entry.isBanner()) {
                deliverToSink(Level.INFO, "startup", entry.message(), null);
                for (String line : entry.failureLines()) {
                    writeRawLineToFile(line);
                }
                wroteLines = true;
            } else {
                if (!entry.skipSink()) {
                    deliverToSink(entry.level(), entry.component(), entry.message(), entry.failure());
                }
                writeRawLineToFile(entry.line());
                for (String fLine : entry.failureLines()) {
                    writeRawLineToFile(fLine);
                }
                wroteLines = true;
            }
        }
        return wroteLines;
    }

    private void deliverToSink(
            final Level level, final String component, final String message, final Throwable failure) {
        if (inSink.get()) return;
        inSink.set(Boolean.TRUE);
        try {
            sink.write(level, component, message, failure);
        } catch (Throwable failureException) {
            FatalErrors.rethrowIfFatal(failureException);
            System.err.println("Turboism host log write failed safely");
        } finally {
            inSink.set(Boolean.FALSE);
        }
    }

    private void writeRawLineToFile(final String line) {
        if (writer == null) return;
        final int utf8Bytes = line.getBytes(StandardCharsets.UTF_8).length + 1;
        if (currentSegmentBytes + utf8Bytes >= maxSegmentBytes) {
            rotateSegment();
        }
        try {
            writer.write(line);
            writer.write('\n');
            currentSegmentBytes += utf8Bytes;
        } catch (IOException e) {
            System.err.println("Turboism runtime log write failed safely");
        }
    }

    private void flushWriter() {
        if (writer != null) {
            try {
                writer.flush();
            } catch (IOException e) {
                System.err.println("Turboism runtime log write failed safely");
            }
        }
    }

    private void emitDroppedSummary(final int dropped) {
        final String warning = Instant.now(clock) + " [WARN] [PreviewLog] Dropped " + dropped
                + " log entries due to queue capacity overflow";
        remember(warning);
        writeRawLineToFile(warning);
        if (!inSink.get()) {
            inSink.set(Boolean.TRUE);
            try {
                sink.write(
                        Level.WARN,
                        "PreviewLog",
                        "Dropped " + dropped + " log entries due to queue capacity overflow",
                        null);
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                System.err.println("Turboism host log write failed safely");
            } finally {
                inSink.set(Boolean.FALSE);
            }
        }
    }

    private void rotateSegment() {
        if (writer != null) {
            try {
                writer.flush();
                writer.close();
            } catch (IOException e) {
                System.err.println("Turboism runtime log write failed safely");
            }
        }

        final Path archive = resolveArchiveSegmentPath();
        boolean moved = false;
        try {
            fileMover.move(baseLogFile, archive, StandardCopyOption.REPLACE_EXISTING);
            moved = true;
        } catch (IOException e) {
            System.err.println("Turboism preview log rotation move failed safely: " + e.getMessage());
        }

        try {
            writer = Files.newBufferedWriter(
                    baseLogFile,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE);
            currentSegmentBytes = moved ? 0L : (Files.exists(baseLogFile) ? Files.size(baseLogFile) : 0L);
        } catch (IOException e) {
            System.err.println("Turboism runtime log write failed safely");
        }

        if (moved) {
            pruneOldSessions();
        }
    }

    private Path resolveArchiveSegmentPath() {
        final String fileName = baseLogFile.getFileName().toString();
        final String baseName = fileName.endsWith(".log") ? fileName.substring(0, fileName.length() - 4) : fileName;
        final Path parent = baseLogFile.getParent();
        Path candidate;
        do {
            candidate = parent.resolve(baseName + "." + segmentSequence + ".log");
            segmentSequence++;
        } while (Files.exists(candidate));
        return candidate;
    }

    private void pruneOldSessions() {
        try {
            final List<StoredLog> files = new ArrayList<>();
            long activeFileSize = 0L;
            try (Stream<Path> paths = Files.walk(logDirectory)) {
                for (Path path : paths.filter(PreviewLog::isStoredLog).toList()) {
                    final Path normalized = path.toAbsolutePath().normalize();
                    final long size = Files.size(normalized);
                    if (normalized.equals(baseLogFile)) {
                        activeFileSize = size;
                    }
                    files.add(new StoredLog(
                            normalized, size, Files.getLastModifiedTime(normalized, LinkOption.NOFOLLOW_LINKS)));
                }
            }
            files.sort(Comparator.comparing(StoredLog::modified)
                    .thenComparing(value -> value.path().toString()));
            final long reservedForActive = Math.max(0L, maxSegmentBytes - activeFileSize);
            long total = files.stream().mapToLong(StoredLog::size).sum() + reservedForActive;
            int remaining = files.size();
            for (StoredLog file : files) {
                if (total <= maxStorageBytes || remaining <= 1) break;
                if (file.path().equals(baseLogFile)) continue;
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
        final Path file = Objects.requireNonNull(requestedFile, "logFile")
                .toAbsolutePath()
                .normalize();
        return file.getParent();
    }

    /** Package-private seam for deterministic testing without Thread.sleep. */
    boolean drain(final long timeout, final TimeUnit unit) throws InterruptedException {
        final long deadline = System.nanoTime() + unit.toNanos(timeout);
        final long targetSeq;
        final long targetPrune;
        synchronized (admissionLock) {
            if (workerTerminated.getCount() == 0) {
                return true;
            }
            this.pendingPruneSeq++;
            targetPrune = pendingPruneSeq;
            targetSeq = lastEnqueuedEntrySeq;
            admissionLock.notifyAll();
        }

        synchronized (admissionLock) {
            while (flushedSeq < targetSeq || completedPruneSeq < targetPrune) {
                if (workerTerminated.getCount() == 0) {
                    return true;
                }
                final long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    return false;
                }
                final long waitMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
                admissionLock.wait(Math.max(1, waitMillis));
            }
            return true;
        }
    }

    /** Package-private convenience drain seam with default timeout; throws if timed out. */
    void drain() {
        try {
            if (!drain(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for PreviewLog drain");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for PreviewLog drain", e);
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (admissionLock) {
            if (closed) {
                return;
            }
            closed = true;
            admissionLock.notifyAll();
        }

        try {
            final boolean finished = workerTerminated.await(CLOSE_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                workerThread.interrupt();
                workerTerminated.await(500, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            workerThread.interrupt();
        }
    }

    private void closeResources() {
        try {
            if (writer != null) {
                try {
                    writer.flush();
                } finally {
                    writer.close();
                }
            }
        } catch (Throwable e) {
            FatalErrors.rethrowIfFatal(e);
            System.err.println("Turboism runtime log close failed safely");
        } finally {
            try {
                sink.close();
            } catch (Throwable e) {
                FatalErrors.rethrowIfFatal(e);
                System.err.println("Turboism host log close failed safely");
            }
        }
    }

    private record StoredLog(Path path, long size, FileTime modified) {}

    private record QueueEntry(
            long sequence,
            Level level,
            String component,
            String message,
            Throwable failure,
            String line,
            List<String> failureLines,
            boolean isBanner,
            boolean skipSink,
            long estimatedBytes) {
        QueueEntry withSequence(final long seq) {
            return new QueueEntry(
                    seq, level, component, message, failure, line, failureLines, isBanner, skipSink, estimatedBytes);
        }
    }
}
