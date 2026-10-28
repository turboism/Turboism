package dev.turboism.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.mapping.verification.VerifiedAccessException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreviewLogTest {

    @TempDir
    Path temporary;

    @Test
    void createsOneLogFilePerSessionUnderTheUtcDate() throws Exception {
        final Clock clock = Clock.fixed(Instant.parse("2026-01-02T03:04:05.006Z"), ZoneOffset.UTC);

        try (PreviewLog log =
                PreviewLog.openSession(temporary, clock, 42L, (level, component, message, failure) -> {})) {
            final Path file = log.snapshot().currentFile().orElseThrow();
            assertEquals(temporary.resolve("2026-01-02"), file.getParent());
            assertTrue(file.getFileName().toString().matches("turboism-03-04-05\\.006-p42-.*\\.log"));
            assertEquals(temporary, log.snapshot().directory().orElseThrow());
        }
    }

    @Test
    void removesOldestCompletedSessionsBeyondTheConfiguredStorageLimit() throws Exception {
        final Path oldest = temporary.resolve("2025-12-30/oldest.log");
        final Path newer = temporary.resolve("2025-12-31/newer.log");
        Files.createDirectories(oldest.getParent());
        Files.createDirectories(newer.getParent());
        Files.write(oldest, new byte[700 * 1024]);
        Files.write(newer, new byte[700 * 1024]);
        Files.setLastModifiedTime(oldest, FileTime.from(Instant.EPOCH));
        Files.setLastModifiedTime(newer, FileTime.from(Instant.EPOCH.plusSeconds(1)));

        try (PreviewLog log = PreviewLog.openSession(
                temporary,
                Clock.fixed(Instant.parse("2026-01-02T03:04:05.006Z"), ZoneOffset.UTC),
                42L,
                (level, component, message, failure) -> {})) {
            final Path current = log.snapshot().currentFile().orElseThrow();
            log.setMaxStorageMiB(1);
            log.drain();

            assertFalse(Files.exists(oldest));
            assertTrue(Files.exists(newer));
            assertTrue(Files.exists(current));
        }
    }

    @Test
    void forwardsStructuredCubismRecordsWithoutSessionFilePrefixes() throws Exception {
        final List<PreviewLog.Level> levels = new ArrayList<>();
        final List<String> hostRecords = new ArrayList<>();
        final PreviewLog.Sink sink = (level, component, message, failure) -> {
            levels.add(level);
            hostRecords.add("[" + component + "] " + message);
        };

        try (PreviewLog log =
                new PreviewLog(temporary.resolve("levels.log"), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), sink)) {
            log.setMinimumLevel("TRACE");
            log.trace("probe", "trace");
            log.debug("probe", "debug");
            log.info("probe", "info");
            log.warn("probe", "warn");
            log.error("probe", "error", null);
            log.fatal("probe", "fatal", null);
        }

        assertEquals(
                List.of(
                        PreviewLog.Level.TRACE,
                        PreviewLog.Level.DEBUG,
                        PreviewLog.Level.INFO,
                        PreviewLog.Level.WARN,
                        PreviewLog.Level.ERROR,
                        PreviewLog.Level.FATAL),
                levels);
        assertEquals(
                List.of(
                        "[probe] trace",
                        "[probe] debug",
                        "[probe] info",
                        "[probe] warn",
                        "[probe] error",
                        "[probe] fatal"),
                hostRecords);
    }

    @Test
    void appliesTheConfiguredMinimumLevelToTheHostAndFile() throws Exception {
        final Path path = temporary.resolve("filtered.log");
        final List<PreviewLog.Level> levels = new ArrayList<>();

        try (PreviewLog log = new PreviewLog(
                path,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                (level, component, message, failure) -> levels.add(level))) {
            log.setMinimumLevel("WARN");
            log.debug("probe", "hidden");
            log.warn("probe", "visible");
        }

        assertEquals(List.of(PreviewLog.Level.WARN), levels);
        assertEquals("1970-01-01T00:00:00Z [WARN] [probe] visible\n", Files.readString(path));
    }

    @Test
    void keepsOnlyTheLatestFiveThousandLinesForTheCoreLogWindow() throws Exception {
        try (PreviewLog log = new PreviewLog(
                temporary.resolve("recent.log"),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                (level, component, message, failure) -> {})) {
            for (int index = 0; index <= 5_000; index++) {
                log.info("probe", Integer.toString(index));
                // Exercise ring eviction without queue-overflow summaries adding extra lines.
                if (index % 128 == 127) {
                    log.drain();
                }
            }
            log.drain();

            final List<String> lines = log.snapshot().lines();
            assertEquals(5_000, lines.size());
            assertTrue(lines.get(0).endsWith(" 1"));
            assertTrue(lines.get(4_999).endsWith(" 5000"));
        }
    }

    @Test
    void frameworkOnlySinkKeepsOrdinaryRecordsOutOfNativeConsoleStreams() throws Exception {
        final PrintStream originalOut = System.out;
        final PrintStream originalErr = System.err;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final ByteArrayOutputStream error = new ByteArrayOutputStream();
        System.setOut(new PrintStream(output));
        System.setErr(new PrintStream(error));
        try (PreviewLog log = new PreviewLog(
                temporary.resolve("quiet.log"), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), PreviewLog.Sink.NONE)) {
            log.info("runtime", "normal startup record");
            assertTrue(log.snapshot().lines().get(0).endsWith("normal startup record"));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        assertEquals("", output.toString());
        assertEquals("", error.toString());
    }

    @Test
    void multilineBannerIsOneHostRecordAndKeepsItsFileLayout() throws Exception {
        final List<String> hostRecords = new ArrayList<>();
        final Path path = temporary.resolve("banner.log");
        try (PreviewLog log = new PreviewLog(
                path,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                (level, component, message, failure) -> hostRecords.add(message))) {
            log.banner("line one\nline two");
        }

        assertEquals(List.of("line one\nline two"), hostRecords);
        assertEquals("line one\nline two\n", Files.readString(path));
    }

    @Test
    void errorLogIncludesVerifiedSelectorDiagnosticsAndCauseChain() throws Exception {
        final Path path = temporary.resolve("turboism.log");
        final VerifiedAccessException failure = new VerifiedAccessException(
                "cubism.editor-model.parameter-group.add",
                VerifiedAccessException.FailureKind.INVOCATION,
                "Verified host selector invocation failed safely.",
                new IllegalAccessException("fixture"));

        try (PreviewLog log = new PreviewLog(path)) {
            log.error("probe", "Combined action failed", failure);
        }

        final String content = Files.readString(path);
        assertTrue(content.contains("dev.turboism.mapping.verification.VerifiedAccessException: "
                + "Verified host selector invocation failed safely. "
                + "[alias=cubism.editor-model.parameter-group.add, failureKind=INVOCATION]"));
        assertTrue(content.contains("caused by java.lang.IllegalAccessException: fixture"));
        assertTrue(content.contains("\tat dev.turboism.preview.PreviewLogTest."));
    }

    @Test
    void slowSinkDoesNotBlockProducerAndSnapshotIsImmediatelyVisible() throws Exception {
        final CountDownLatch sinkEntered = new CountDownLatch(1);
        final CountDownLatch sinkUnblock = new CountDownLatch(1);
        final PreviewLog.Sink slowSink = (level, component, message, failure) -> {
            sinkEntered.countDown();
            try {
                sinkUnblock.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        final Path path = temporary.resolve("slow-sink.log");
        try (PreviewLog log = new PreviewLog(path, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), slowSink)) {
            log.info("probe", "first record blocks sink");
            assertTrue(sinkEntered.await(2, TimeUnit.SECONDS), "worker should enter sink");

            final long start = System.nanoTime();
            log.info("probe", "second record fast");
            final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsedMs < 1000, "producer should not block on slow sink, took: " + elapsedMs + "ms");

            final List<String> snapshot = log.snapshot().lines();
            assertTrue(
                    snapshot.stream().anyMatch(l -> l.contains("second record fast")),
                    "snapshot must reflect second record immediately without waiting for sink drain");

            sinkUnblock.countDown();
        }
    }

    @Test
    void rotatesActiveSegmentAndEnforcesDiskBudget() throws Exception {
        final Path dir = temporary.resolve("budget-test");
        Files.createDirectories(dir);
        final Path file = dir.resolve("active.log");

        try (PreviewLog log = new PreviewLog(file, Clock.systemUTC(), PreviewLog.Sink.NONE)) {
            log.setMaxStorageMiB(1);
            final String chunk = "x".repeat(8192);
            for (int i = 0; i < 256; i++) {
                log.info("audit", chunk);
                if ((i + 1) % 16 == 0) {
                    log.drain();
                }
            }
            log.drain();

            final long activeSize = Files.size(file);
            assertTrue(
                    activeSize <= 256 * 1024L + 8192L,
                    "active file size must be bounded by segment cap, was: " + activeSize);

            List<Path> logFiles;
            try (Stream<Path> paths = Files.list(dir)) {
                logFiles = paths.filter(p -> p.getFileName().toString().endsWith(".log"))
                        .toList();
            }
            assertTrue(logFiles.size() > 1, "Must have rotated into multiple segments, found: " + logFiles.size());

            long totalBytes = 0L;
            for (Path p : logFiles) {
                totalBytes += Files.size(p);
            }
            assertTrue(
                    totalBytes <= 1024L * 1024L,
                    "total log directory size must not exceed 1 MiB budget, was: " + totalBytes);
            assertTrue(
                    totalBytes > 500 * 1024L,
                    "total bytes should be close to budget after writing 2 MiB, was: " + totalBytes);
        }
    }

    @Test
    void closeIsIdempotentAndDrainsSafely() throws Exception {
        final Path path = temporary.resolve("idempotent-close.log");
        final PreviewLog log = new PreviewLog(path);
        log.info("probe", "msg1");
        log.info("probe", "msg2");
        log.close();

        log.close();
        log.close();

        final String content = Files.readString(path);
        assertTrue(content.contains("msg1"));
        assertTrue(content.contains("msg2"));
    }

    @Test
    void closeWithSlowSinkDoesNotDeadlockOrRace() throws Exception {
        final CountDownLatch sinkEntered = new CountDownLatch(1);
        final CountDownLatch unblockSink = new CountDownLatch(1);
        final PreviewLog.Sink stuckSink = (level, component, message, failure) -> {
            sinkEntered.countDown();
            try {
                unblockSink.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        final Path path = temporary.resolve("slow-close.log");
        final PreviewLog log = new PreviewLog(path, Clock.systemUTC(), stuckSink);
        log.info("probe", "entering slow sink");
        assertTrue(sinkEntered.await(2, TimeUnit.SECONDS));

        final Thread closer = new Thread(() -> {
            try {
                log.close();
            } catch (IOException ignored) {
            }
        });
        closer.start();
        closer.join(4000);
        assertFalse(closer.isAlive(), "close() should return within bounded timeout even if sink is stuck");
        unblockSink.countDown();
    }

    @Test
    void queueOverflowAggregatesDroppedSummaryWithoutBlocking() throws Exception {
        final CountDownLatch sinkHold = new CountDownLatch(1);
        final PreviewLog.Sink pauseSink = (level, component, message, failure) -> {
            try {
                sinkHold.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        final Path path = temporary.resolve("overflow.log");
        try (PreviewLog log = new PreviewLog(path, Clock.systemUTC(), pauseSink)) {
            final String payload = "payload-" + "y".repeat(1024);
            for (int i = 0; i < 2500; i++) {
                log.info("probe", payload);
            }

            sinkHold.countDown();
            log.drain();

            final String content = Files.readString(path);
            assertTrue(
                    content.contains("Dropped") && content.contains("log entries due to queue capacity overflow"),
                    "file must contain aggregated dropped warning line");
        }
    }

    @Test
    void truncatesOversizedMessagesAndBoundsRecentRingBytes() throws Exception {
        final Path path = temporary.resolve("truncation.log");
        try (PreviewLog log = new PreviewLog(path)) {
            final String huge = "z".repeat(50_000);
            log.info("oversize", huge);
            log.drain();

            final String content = Files.readString(path);
            assertTrue(content.contains("... [truncated]"));
            assertFalse(content.contains("z".repeat(20_000)));

            final String block = "a".repeat(16_000);
            for (int i = 0; i < 300; i++) {
                log.info("ring", "block-" + i + ": " + block);
            }

            final List<String> snapshot = log.snapshot().lines();
            long snapshotBytes = 0L;
            for (String line : snapshot) {
                snapshotBytes += line.length() * 2L + 40L;
            }
            assertTrue(
                    snapshotBytes <= 2 * 1024 * 1024L + 40_000L,
                    "Recent ring memory bytes must be bounded by RECENT_BYTES_LIMIT, was: " + snapshotBytes);
            assertTrue(
                    snapshot.size() < 300,
                    "Earlier lines must have been evicted due to byte limit even though size < 5000");
            assertTrue(
                    snapshot.get(snapshot.size() - 1).contains("block-299"), "Latest line must be present in snapshot");
        }
    }

    @Test
    void sinkReentrancyDoesNotCauseLoopback() throws Exception {
        final Path path = temporary.resolve("reentrancy.log");
        final AtomicInteger sinkCalls = new AtomicInteger(0);
        final PreviewLog[] logRef = new PreviewLog[1];

        final PreviewLog.Sink reentrantSink = (level, component, message, failure) -> {
            sinkCalls.incrementAndGet();
            if (logRef[0] != null && "first".equals(message)) {
                logRef[0].info("reentrant", "nested-call");
            }
        };

        try (PreviewLog log = new PreviewLog(path, Clock.systemUTC(), reentrantSink)) {
            logRef[0] = log;
            log.info("probe", "first");
            log.drain();
        }

        final String content = Files.readString(path);
        assertTrue(content.contains("first"));
        assertTrue(content.contains("nested-call"));
        assertEquals(1, sinkCalls.get(), "reentrant log call must not re-invoke sink");
    }

    @Test
    void fatalErrorsRethrownAccordingToRepoStandards() throws Exception {
        assertTrue(FatalErrors.isFatal(new OutOfMemoryError("check")));
        final CountDownLatch fatalThrown = new CountDownLatch(1);
        final PreviewLog.Sink fatalSink = (level, component, message, failure) -> {
            fatalThrown.countDown();
            throw new OutOfMemoryError("simulated fatal error");
        };

        final Path path = temporary.resolve("fatal.log");
        try (PreviewLog log = new PreviewLog(path, Clock.systemUTC(), fatalSink)) {
            log.info("probe", "trigger fatal");
            assertTrue(fatalThrown.await(2, TimeUnit.SECONDS));
            log.drain();
        }
    }

    @Test
    void drainWaitsForBatchesBeyondQueueChunkLimit() throws Exception {
        final Path path = temporary.resolve("drain-chunk.log");
        final CountDownLatch itemInSecondBatchEntered = new CountDownLatch(1);
        final CountDownLatch allowSecondBatchToFinish = new CountDownLatch(1);

        final PreviewLog.Sink blockingSink = (level, component, message, failure) -> {
            if ("record-130".equals(message)) {
                itemInSecondBatchEntered.countDown();
                try {
                    allowSecondBatchToFinish.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        try (PreviewLog log = new PreviewLog(path, Clock.systemUTC(), blockingSink)) {
            for (int i = 0; i < 200; i++) {
                log.info("probe", "record-" + i);
            }

            assertTrue(itemInSecondBatchEntered.await(2, TimeUnit.SECONDS), "Worker should reach 2nd batch");

            final AtomicBoolean drainFinished = new AtomicBoolean(false);
            final Thread drainer = new Thread(() -> {
                log.drain();
                drainFinished.set(true);
            });
            drainer.start();

            assertFalse(log.drain(100, TimeUnit.MILLISECONDS), "Drain must not return while 2nd batch is blocked");
            assertFalse(drainFinished.get(), "Drainer thread must not be finished yet");

            allowSecondBatchToFinish.countDown();
            drainer.join(3000);
            assertTrue(drainFinished.get(), "Drainer must finish after 2nd batch unblocks");

            final String content = Files.readString(path);
            assertTrue(content.contains("record-199"), "File must contain the last record");
        }
    }

    @Test
    void rotationFailurePreservesExistingDataWithoutTruncation() throws Exception {
        final Path dir = temporary.resolve("ro-test");
        Files.createDirectories(dir);
        final Path file = dir.resolve("active.log");

        try (PreviewLog log = new PreviewLog(file, Clock.systemUTC(), PreviewLog.Sink.NONE)) {
            log.setMaxStorageMiB(1);
            log.info("probe", "important prior data");
            log.drain();

            final String initialContent = Files.readString(file);
            assertTrue(initialContent.contains("important prior data"));

            // Inject simulated move failure via package-private seam
            log.setFileMoverForTesting((src, target, opts) -> {
                throw new IOException("Simulated file move failure");
            });

            final String chunk = "x".repeat(8192);
            for (int i = 0; i < 40; i++) {
                log.info("audit", chunk);
            }
            log.drain();

            final String afterContent = Files.readString(file);
            assertTrue(
                    afterContent.contains("important prior data"), "prior data must never be lost on rotation failure");
        }
    }

    @Test
    void unicodeAndEmojiTrackUtf8BytesAndEnforcesBudget() throws Exception {
        final Path dir = temporary.resolve("unicode-budget");
        Files.createDirectories(dir);
        final Path file = dir.resolve("active.log");

        try (PreviewLog log = new PreviewLog(file, Clock.systemUTC(), PreviewLog.Sink.NONE)) {
            log.setMaxStorageMiB(1);
            final String multiByte = "中文测试 日志 🐱‍👤 日本語テスト \uD83D\uDE00 ".repeat(200);
            for (int i = 0; i < 200; i++) {
                log.info("i18n", "msg-" + i + ": " + multiByte);
                if ((i + 1) % 16 == 0) {
                    log.drain();
                }
            }
            log.drain();

            final long activeSize = Files.size(file);
            assertTrue(
                    activeSize <= 256 * 1024L + 32 * 1024L,
                    "Active file size must be bounded by segment cap, was: " + activeSize);

            List<Path> logFiles;
            try (Stream<Path> paths = Files.list(dir)) {
                logFiles = paths.filter(p -> p.getFileName().toString().endsWith(".log"))
                        .toList();
            }
            assertTrue(logFiles.size() > 1, "Must have rotated into multiple segments, found: " + logFiles.size());

            long totalBytes = 0L;
            for (Path p : logFiles) {
                totalBytes += Files.size(p);
            }
            assertTrue(
                    totalBytes <= 1024L * 1024L,
                    "Total log directory size with multi-byte text must not exceed 1 MiB, was: " + totalBytes);

            final String content = Files.readString(file);
            assertTrue(content.contains("中文测试 日志 🐱‍👤 日本語テスト \uD83D\uDE00"));
        }
    }

    @Test
    void preExistingLargeActiveFileConvergesOnOpen() throws Exception {
        final Path dir = temporary.resolve("preexisting");
        Files.createDirectories(dir);
        final Path file = dir.resolve("active.log");
        Files.write(file, new byte[500 * 1024]);

        try (PreviewLog log = new PreviewLog(file, Clock.systemUTC(), PreviewLog.Sink.NONE)) {
            log.setMaxStorageMiB(1);
            log.info("probe", "after open");
            log.drain();

            assertTrue(
                    Files.size(file) <= 256 * 1024L,
                    "active file must converge to segment cap, was: " + Files.size(file));
        }
    }

    @Test
    void multiProducerConcurrencyUnderAdmissionLock() throws Exception {
        final Path path = temporary.resolve("concurrency.log");
        final int threadCount = 10;
        final int recordsPerThread = 500;
        final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(threadCount);

        try (PreviewLog log = new PreviewLog(path, Clock.systemUTC(), PreviewLog.Sink.NONE)) {
            final String payload = "payload-" + "x".repeat(1024);
            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.execute(() -> {
                    try {
                        startLatch.await();
                        for (int r = 0; r < recordsPerThread; r++) {
                            if (r % 10 == 0) {
                                log.error("t" + threadId, "err-" + r, new RuntimeException("concurrency error"));
                            } else {
                                log.info("t" + threadId, "msg-" + r + ": " + payload);
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "All producers should finish");
            log.drain();

            final String content = Files.readString(path);
            assertTrue(
                    content.contains("Dropped") || content.contains("concurrency error"),
                    "File must contain log records and/or aggregated dropped summary");
        } finally {
            executor.shutdownNow();
        }
    }
}
