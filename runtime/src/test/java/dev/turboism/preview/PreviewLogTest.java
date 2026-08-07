package dev.turboism.preview;

import dev.turboism.mapping.verification.VerifiedAccessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewLogTest {

    @TempDir
    Path temporary;

    @Test
    void createsOneLogFilePerSessionUnderTheUtcDate() throws Exception {
        final Clock clock = Clock.fixed(Instant.parse("2026-01-02T03:04:05.006Z"), ZoneOffset.UTC);

        try (PreviewLog log = PreviewLog.openSession(temporary, clock, 42L, (level, line, failure) -> {})) {
            final Path file = log.snapshot().currentFile().orElseThrow();
            assertEquals(temporary.resolve("2026-01-02"), file.getParent());
            assertTrue(file.getFileName().toString().matches(
                "turboism-03-04-05\\.006-p42-.*\\.log"
            ));
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
            (level, line, failure) -> {}
        )) {
            final Path current = log.snapshot().currentFile().orElseThrow();
            log.setMaxStorageMiB(1);

            assertFalse(Files.exists(oldest));
            assertTrue(Files.exists(newer));
            assertTrue(Files.exists(current));
        }
    }

    @Test
    void forwardsEveryCubismLevelWithoutCollapsingToError() throws Exception {
        final List<PreviewLog.Level> levels = new ArrayList<>();
        final PreviewLog.Sink sink = (level, line, failure) -> levels.add(level);

        try (PreviewLog log = new PreviewLog(
            temporary.resolve("levels.log"),
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
            sink
        )) {
            log.setMinimumLevel("TRACE");
            log.trace("probe", "trace");
            log.debug("probe", "debug");
            log.info("probe", "info");
            log.warn("probe", "warn");
            log.error("probe", "error", null);
            log.fatal("probe", "fatal", null);
        }

        assertEquals(List.of(
            PreviewLog.Level.TRACE,
            PreviewLog.Level.DEBUG,
            PreviewLog.Level.INFO,
            PreviewLog.Level.WARN,
            PreviewLog.Level.ERROR,
            PreviewLog.Level.FATAL
        ), levels);
    }

    @Test
    void appliesTheConfiguredMinimumLevelToTheHostAndFile() throws Exception {
        final Path path = temporary.resolve("filtered.log");
        final List<PreviewLog.Level> levels = new ArrayList<>();

        try (PreviewLog log = new PreviewLog(
            path,
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
            (level, line, failure) -> levels.add(level)
        )) {
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
            (level, line, failure) -> {}
        )) {
            for (int index = 0; index <= 5_000; index++) {
                log.info("probe", Integer.toString(index));
            }

            assertEquals(5_000, log.snapshot().lines().size());
            assertTrue(log.snapshot().lines().get(0).endsWith(" 1"));
            assertTrue(log.snapshot().lines().get(4_999).endsWith(" 5000"));
        }
    }

    @Test
    void errorLogIncludesVerifiedSelectorDiagnosticsAndCauseChain() throws Exception {
        final Path path = temporary.resolve("turboism.log");
        final VerifiedAccessException failure = new VerifiedAccessException(
            "cubism.editor-model.parameter-group.add",
            VerifiedAccessException.FailureKind.INVOCATION,
            "Verified host selector invocation failed safely.",
            new IllegalAccessException("fixture")
        );

        try (PreviewLog log = new PreviewLog(path)) {
            log.error("probe", "Combined action failed", failure);
        }

        final String content = Files.readString(path);
        assertTrue(content.contains(
            "dev.turboism.mapping.verification.VerifiedAccessException: "
                + "Verified host selector invocation failed safely. "
                + "[alias=cubism.editor-model.parameter-group.add, failureKind=INVOCATION]"
        ));
        assertTrue(content.contains("caused by java.lang.IllegalAccessException: fixture"));
    }
}
