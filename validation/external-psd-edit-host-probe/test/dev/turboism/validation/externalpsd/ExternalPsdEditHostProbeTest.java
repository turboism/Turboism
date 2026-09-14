package dev.turboism.validation.externalpsd;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;

import dev.turboism.sdk.cubism.history.HistoryMoveResult;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.psd.PsdEditFile;
import dev.turboism.sdk.cubism.psd.PsdFileOperationResult;
import dev.turboism.sdk.cubism.psd.PsdFileRevision;
import dev.turboism.sdk.plugin.Registration;

/** Offline unit coverage for PSD mutation, GUI dispatch, and persistence evidence gates. */
public final class ExternalPsdEditHostProbeTest {
    public static void main(final String[] args) throws Exception {
        testPopupTriggerDispatch();
        testSyntheticTargetDiagnostics();
        testRendererPreparationFollowsDispatch();
        testRightClickDispatchFailure();
        testReplacedTargetAfterSelection();
        testUnrelocatableTargetIsRejected();
        testExactRowResolverAndDispatchGuards();
        testExactTargetFamilySelection();
        testActiveRowResolver();
        testStableRowKeySafety();
        testBoundedRowDispatches();
        testPopupMarker();
        testPopupAttemptAssociation();
        testAutoImportEvidence();
        testPersistEvidenceGate();
        testNativeFingerprintGates();
        testHistoryMovesRequireMoved();
        testTempCandidateBinding();
        testQuarantineMovesAllTrackedDirectories();
        testQuarantineRejectsConflictAndSymlink();
        testQuarantinePartialMoveEvidence();
        testTrackerStopAggregation();
        testFinalCycleSelection();
        testSaveCycleBytes();

        final byte[] psd = syntheticPsd("LayerA", "B2");
        final List<int[]> names = ExternalPsdEditHostProbe.layerNameRanges(psd);
        assertEquals(2, names.size(), "two layer names parsed");
        assertEquals("LayerA", slice(psd, names.get(0)), "first name");
        assertEquals("B2", slice(psd, names.get(1)), "second name");

        final Optional<byte[]> first = ExternalPsdEditHostProbe.mutateLayerName(psd, 1);
        assertTrue(first.isPresent(), "mutation present");
        final byte[] mutated = first.orElseThrow();
        assertEquals(psd.length, mutated.length, "mutation preserves length");
        int diffs = 0;
        for (int i = 0; i < psd.length; i++) if (psd[i] != mutated[i]) diffs++;
        assertEquals(1, diffs, "exactly one byte differs");
        assertTrue(!Arrays.equals(psd, mutated), "digest changes");

        final Optional<byte[]> second = ExternalPsdEditHostProbe.mutateLayerName(psd, 2);
        assertTrue(second.isPresent(), "cycle 2 mutates second layer");
        assertTrue(!Arrays.equals(mutated, second.orElseThrow()), "distinct content per cycle");

        assertTrue(ExternalPsdEditHostProbe.layerNameRanges(new byte[10]).isEmpty(),
            "truncated input yields no ranges");
        assertTrue(ExternalPsdEditHostProbe.layerNameRanges(
            "not a psd".getBytes(StandardCharsets.UTF_8)).isEmpty(),
            "non-PSD input yields no ranges");
        assertTrue(ExternalPsdEditHostProbe.mutateLayerName(
            "not a psd".getBytes(StandardCharsets.UTF_8), 1).isEmpty(),
            "non-PSD input cannot mutate");

        // A file whose first name byte already equals the replacement still differs.
        final byte[] startsWithA = syntheticPsd("apple", "B2");
        final byte[] changed = ExternalPsdEditHostProbe.mutateLayerName(startsWithA, 1)
            .orElseThrow();
        assertTrue(!Arrays.equals(startsWithA, changed), "same-letter cycle still differs");

        // mutationFor coordinates must describe exactly the byte applyMutation writes,
        // so a reopen stage can re-verify the persisted marker by coordinates alone.
        final var marker = ExternalPsdEditHostProbe.mutationFor(psd, 1).orElseThrow();
        final int[] markedRange = names.get(marker.layer());
        assertEquals(marker.letter(),
            (char) first.orElseThrow()[markedRange[0] + marker.nameOffset()],
            "marker coordinates identify the mutated byte");
        assertEquals(marker.letter(), (char) mutated[markedRange[0] + marker.nameOffset()],
            "persisted byte equals the recorded marker letter");
        System.out.println("PASS: ExternalPsdEditHostProbeTest");
    }

    private static void testPersistEvidenceGate() {
        final Properties valid = validPersistEvidence();
        ExternalPsdEditHostProbe.requirePersistEvidence(valid);

        final Properties missing = copy(valid);
        missing.remove("persist.postEditTargetRgbSha256");
        expectIllegalState(() -> ExternalPsdEditHostProbe.requirePersistEvidence(missing),
            "missing target RGB evidence is rejected");

        final Properties sameContent = copy(valid);
        sameContent.setProperty("persist.postEditTargetRgbSha256",
            sameContent.getProperty("persist.baselineTargetRgbSha256"));
        expectIllegalState(() -> ExternalPsdEditHostProbe.requirePersistEvidence(sameContent),
            "unchanged target RGB evidence is rejected");

        final Properties unstableBaseline = copy(valid);
        unstableBaseline.setProperty("persist.baselineSecondTargetRgbSha256", "c".repeat(64));
        expectIllegalState(() -> ExternalPsdEditHostProbe.requirePersistEvidence(unstableBaseline),
            "unstable baseline evidence is rejected");

        final Properties incompleteQuarantine = copy(valid);
        incompleteQuarantine.setProperty("persist.tempQuarantine.sourceMissing", "false");
        expectIllegalState(() -> ExternalPsdEditHostProbe.requirePersistEvidence(
            incompleteQuarantine), "incomplete quarantine evidence is rejected");

        final Properties uppercase = copy(valid);
        uppercase.setProperty("persist.baselineTargetRgbSha256", "A".repeat(64));
        expectIllegalState(() -> ExternalPsdEditHostProbe.requirePersistEvidence(uppercase),
            "non-canonical hash evidence is rejected");
    }

    private static void testNativeFingerprintGates() {
        final PsdValidationContent.Bounds bounds = new PsdValidationContent.Bounds(
            450, 450, 550, 550);
        final PsdValidationContent.Fingerprint baseline = new PsdValidationContent.Fingerprint(
            "a".repeat(64), bounds, 100, 100, List.of(0, 1, 2));
        final PsdValidationContent.Fingerprint same = new PsdValidationContent.Fingerprint(
            "a".repeat(64), bounds, 100, 100, List.of(0, 1, 2));
        final PsdValidationContent.Fingerprint changed = new PsdValidationContent.Fingerprint(
            "b".repeat(64), bounds, 100, 100, List.of(0, 1, 2));
        final PsdValidationContent.Fingerprint unstable = new PsdValidationContent.Fingerprint(
            "a".repeat(64), new PsdValidationContent.Bounds(451, 450, 551, 550),
            100, 100, List.of(0, 1, 2));

        ExternalPsdEditHostProbe.requireStableBaseline(baseline, same);
        expectIllegalState(() -> ExternalPsdEditHostProbe.requireStableBaseline(
            baseline, unstable), "baseline bounds instability is rejected");
        ExternalPsdEditHostProbe.requireChangedPost(baseline, changed);
        expectIllegalState(() -> ExternalPsdEditHostProbe.requireChangedPost(baseline, same),
            "unchanged native post fingerprint is rejected");
        ExternalPsdEditHostProbe.requireReopenTarget("b".repeat(64), changed);
        expectIllegalState(() -> ExternalPsdEditHostProbe.requireReopenTarget(
            "a".repeat(64), changed), "reopen digest mismatch is rejected");
        expectIllegalState(() -> ExternalPsdEditHostProbe.requireReopenTarget(
            "B".repeat(64), changed), "reopen expected digest must be lowercase");
    }

    private static void testHistoryMovesRequireMoved() {
        final HistorySnapshot unavailable = HistorySnapshot.unavailable();
        final HistoryMoveResult moved = new HistoryMoveResult(
            HistoryMoveResult.Outcome.MOVED, unavailable, Optional.empty());
        ExternalPsdEditHostProbe.requireHistoryMoved(moved, "undo");

        final HistoryMoveResult unchanged = new HistoryMoveResult(
            HistoryMoveResult.Outcome.NO_CHANGE, unavailable, Optional.empty());
        expectIllegalState(() -> ExternalPsdEditHostProbe.requireHistoryMoved(unchanged, "redo"),
            "redo without MOVED is rejected");
    }

    private static void testTempCandidateBinding() throws Exception {
        final Path oldCandidate = Path.of("/tmp/external PSD old/turboism-psd-old");
        final Path newCandidate = Path.of("/tmp/external PSD new/turboism-psd-new");
        assertEquals(newCandidate, ExternalPsdEditHostProbe.uniqueNewTempCandidate(
            Set.of(oldCandidate), Set.of(oldCandidate, newCandidate)),
            "exactly one new candidate is bound");
        expectIllegalState(() -> ExternalPsdEditHostProbe.uniqueNewTempCandidate(
            Set.of(oldCandidate), Set.of(oldCandidate)), "missing candidate is rejected");
        expectIllegalState(() -> ExternalPsdEditHostProbe.uniqueNewTempCandidate(
            Set.of(oldCandidate), Set.of(oldCandidate, newCandidate,
                Path.of("/tmp/external PSD another/turboism-psd-another"))),
            "ambiguous candidates are rejected");

        final Path root = Files.createTempDirectory("external PSD candidate root ");
        try {
            final Path directory = Files.createDirectory(root.resolve("turboism-psd-valid"));
            expectIllegalStateChecked(() -> ExternalPsdEditHostProbe.requireTrackedTempFile(
                directory, root), "candidate without the runtime PSD is rejected");
            final Path outside = Files.createTempFile("external PSD candidate outside ", ".psd");
            try {
                Files.createSymbolicLink(directory.resolve("external-edit.psd"), outside);
                expectIllegalStateChecked(() -> ExternalPsdEditHostProbe.requireTrackedTempFile(
                    directory, root), "candidate PSD symlink is rejected");
                Files.delete(directory.resolve("external-edit.psd"));
            } finally {
                Files.deleteIfExists(outside);
            }
            Files.write(directory.resolve("external-edit.psd"), new byte[]{1});
            assertEquals(directory.resolve("external-edit.psd").toRealPath(),
                ExternalPsdEditHostProbe.requireTrackedTempFile(directory, root),
                "valid candidate PSD is bound without mtime guessing");
        } finally {
            deleteTree(root);
        }
    }

    private static void testQuarantineMovesAllTrackedDirectories() throws Exception {
        final Path tempRoot = Files.createTempDirectory("external PSD temp root ");
        final Path taskRoot = Files.createTempDirectory("external PSD task root ");
        try {
            final Path first = Files.createDirectory(tempRoot.resolve("turboism-psd-first"));
            final Path second = Files.createDirectory(tempRoot.resolve("turboism-psd-second"));
            Files.write(first.resolve("external-edit.psd"), new byte[]{1, 2, 3});
            Files.write(second.resolve("external-edit.psd"), new byte[]{4, 5, 6});
            final Path destination = taskRoot.resolve("quarantine with spaces");

            final ExternalPsdEditHostProbe.QuarantineReport report =
                ExternalPsdEditHostProbe.moveTrackedTempDirectories(
                    List.of(first, second), tempRoot, taskRoot, destination);
            assertEquals("MOVED", report.status(), "all tracked directories are moved atomically");
            assertTrue(report.taskOwned(), "quarantine remains under the task root");
            assertTrue(report.sourceMissing(), "all tracked sources are absent after move");
            assertEquals(2, report.entries().size(), "every export directory is recorded");
            assertTrue(!Files.exists(first, LinkOption.NOFOLLOW_LINKS),
                "first source is not left behind");
            assertTrue(!Files.exists(second, LinkOption.NOFOLLOW_LINKS),
                "second source is not left behind");
            assertTrue(Files.isRegularFile(destination.resolve(first.getFileName())
                .resolve("external-edit.psd"), LinkOption.NOFOLLOW_LINKS),
                "first PSD is retained in quarantine");
            assertTrue(Files.isRegularFile(destination.resolve(second.getFileName())
                .resolve("external-edit.psd"), LinkOption.NOFOLLOW_LINKS),
                "second PSD is retained in quarantine");
        } finally {
            deleteTree(taskRoot);
            deleteTree(tempRoot);
        }
    }

    private static void testQuarantineRejectsConflictAndSymlink() throws Exception {
        final Path conflictTemp = Files.createTempDirectory("external PSD conflict temp ");
        final Path conflictTask = Files.createTempDirectory("external PSD conflict task ");
        try {
            final Path source = Files.createDirectory(conflictTemp.resolve("turboism-psd-source"));
            Files.write(source.resolve("external-edit.psd"), new byte[]{7});
            final Path destination = conflictTask.resolve("quarantine");
            Files.createDirectory(destination);
            expectIllegalStateChecked(() -> ExternalPsdEditHostProbe.moveTrackedTempDirectories(
                List.of(source), conflictTemp, conflictTask, destination),
                "existing quarantine destination is rejected");
            assertTrue(Files.exists(source, LinkOption.NOFOLLOW_LINKS),
                "destination conflict does not move the source");
        } finally {
            deleteTree(conflictTask);
            deleteTree(conflictTemp);
        }

        final Path symlinkTemp = Files.createTempDirectory("external PSD symlink temp ");
        final Path symlinkTask = Files.createTempDirectory("external PSD symlink task ");
        final Path outside = Files.createTempDirectory("external PSD outside ");
        try {
            final Path source = Files.createDirectory(symlinkTemp.resolve("turboism-psd-source"));
            Files.write(source.resolve("external-edit.psd"), new byte[]{8});
            Files.createSymbolicLink(source.resolve("escape"), outside);
            expectIllegalStateChecked(() -> ExternalPsdEditHostProbe.moveTrackedTempDirectories(
                List.of(source), symlinkTemp, symlinkTask, symlinkTask.resolve("quarantine")),
                "source child symlink is rejected");
            assertTrue(Files.exists(source, LinkOption.NOFOLLOW_LINKS),
                "symlink rejection does not move the source");
            assertTrue(!Files.exists(symlinkTask.resolve("quarantine"), LinkOption.NOFOLLOW_LINKS),
                "symlink rejection does not create quarantine");
        } finally {
            deleteTree(symlinkTask);
            deleteTree(symlinkTemp);
            deleteTree(outside);
        }
    }

    private static void testQuarantinePartialMoveEvidence() throws Exception {
        final Path tempRoot = Files.createTempDirectory("external PSD partial temp ");
        final Path taskRoot = Files.createTempDirectory("external PSD partial task ");
        try {
            final Path first = Files.createDirectory(tempRoot.resolve("turboism-psd-first"));
            final Path second = Files.createDirectory(tempRoot.resolve("turboism-psd-second"));
            Files.write(first.resolve("external-edit.psd"), new byte[]{1});
            Files.write(second.resolve("external-edit.psd"), new byte[]{2});
            final Path destination = taskRoot.resolve("quarantine with spaces");
            final AtomicInteger calls = new AtomicInteger();
            final ExternalPsdEditHostProbe.QuarantineReport report =
                ExternalPsdEditHostProbe.moveTrackedTempDirectories(
                    List.of(first, second), tempRoot, taskRoot, destination,
                    (source, target) -> {
                        if (calls.incrementAndGet() == 2) {
                            throw new IOException("injected second move failure");
                        }
                        Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                    });
            assertEquals("FAILED", report.status(), "partial quarantine is failed");
            assertTrue(!report.taskOwned(), "partial quarantine cannot claim task ownership");
            assertTrue(!report.sourceMissing(), "partial quarantine cannot claim source missing");
            assertEquals(2, report.entries().size(), "partial report retains every item");
            assertTrue(report.entries().get(0).moved(), "first move is recorded as moved");
            assertTrue(!report.entries().get(1).moved(), "failed move is recorded as unmoved");
            assertTrue(Files.exists(destination.resolve(first.getFileName()),
                LinkOption.NOFOLLOW_LINKS), "first destination is retained");
            assertTrue(Files.exists(second, LinkOption.NOFOLLOW_LINKS),
                "second source is retained after failure");
            assertContains(report.diagnostic(), "injected second move failure",
                "partial failure keeps a diagnostic");
        } finally {
            deleteTree(taskRoot);
            deleteTree(tempRoot);
        }
    }

    /** Cleanup failures fail the phase, but every handle is still attempted. */
    private static void testTrackerStopAggregation() throws Exception {
        final ExternalPsdEditHostProbe.TempTracker tracker =
            new ExternalPsdEditHostProbe.TempTracker();
        final AtomicInteger laterStops = new AtomicInteger();
        tracker.register("failed", stoppingFile(new IllegalStateException(
            "injected stop failure"), new AtomicInteger()), Path.of("/tmp"));
        tracker.register("later", stoppingFile(null, laterStops), Path.of("/tmp"));
        try {
            tracker.stopAll(new Properties());
            throw new AssertionError("cleanup failure was silently converted into success");
        } catch (Exception failure) {
            assertContains(failure.toString(), "injected stop failure",
                "cleanup failure must be propagated as an exception");
        }
        assertEquals(1, laterStops.get(), "later handles are still stopped after a failure");
        assertTrue(!tracker.allStopped(), "a stop failure prevents allStopped success");

        final ExternalPsdEditHostProbe.TempTracker aggregateTracker =
            new ExternalPsdEditHostProbe.TempTracker();
        aggregateTracker.register("first-failure", stoppingFile(new IllegalStateException(
            "first cleanup failure"), new AtomicInteger()), Path.of("/tmp"));
        aggregateTracker.register("second-failure", stoppingFile(new IllegalStateException(
            "second cleanup failure"), new AtomicInteger()), Path.of("/tmp"));
        try {
            aggregateTracker.stopAll(new Properties());
            throw new AssertionError("multiple cleanup failures were silently ignored");
        } catch (Exception failure) {
            assertTrue(failure.getSuppressed().length == 1,
                "all cleanup failures are aggregated on the primary cleanup failure");
            assertContains(failure.getSuppressed()[0].toString(), "second cleanup failure",
                "the later cleanup failure is retained");
        }

        final AtomicInteger afterErrorStops = new AtomicInteger();
        final ExternalPsdEditHostProbe.TempTracker errorTracker =
            new ExternalPsdEditHostProbe.TempTracker();
        errorTracker.register("error", stoppingFile(new AssertionError("injected error"),
            new AtomicInteger()), Path.of("/tmp"));
        errorTracker.register("after-error", stoppingFile(null, afterErrorStops), Path.of("/tmp"));
        try {
            errorTracker.stopAll(new Properties());
            throw new AssertionError("Error from stop was swallowed");
        } catch (AssertionError expected) {
            // Error remains the phase failure after later handles have been attempted.
        }
        assertEquals(1, afterErrorStops.get(), "Error cleanup still attempts later handles");

        final ExternalPsdEditHostProbe.TempTracker primaryTracker =
            new ExternalPsdEditHostProbe.TempTracker();
        primaryTracker.register("cleanup", stoppingFile(new IllegalStateException(
            "cleanup root"), new AtomicInteger()), Path.of("/tmp"));
        final IllegalStateException primary = new IllegalStateException("phase root");
        ExternalPsdEditHostProbe.stopAllPreservingPrimary(
            primaryTracker, new Properties(), primary);
        assertTrue(primary.getSuppressed().length == 1,
            "cleanup failure is suppressed on the phase's primary failure");
    }

    private static PsdEditFile stoppingFile(final Throwable failure, final AtomicInteger stops) {
        return new PsdEditFile() {
            @Override public CompletionStage<PsdFileOperationResult> openInDefaultApplication() {
                return CompletableFuture.completedFuture(new PsdFileOperationResult(
                    PsdFileOperationResult.Status.OPENED, "test"));
            }

            @Override public Registration observeSaves(final Consumer<PsdFileRevision> listener) {
                return () -> { };
            }

            @Override public CompletionStage<PsdFileOperationResult> stop() {
                stops.incrementAndGet();
                if (failure instanceof Error error) throw error;
                if (failure != null) return CompletableFuture.failedFuture(failure);
                return CompletableFuture.completedFuture(new PsdFileOperationResult(
                    PsdFileOperationResult.Status.STOPPED, "test"));
            }
        };
    }

    private static void testFinalCycleSelection() {
        assertTrue(!ExternalPsdEditHostProbe.isFinalRgbMutationCycle(1, 3),
            "early cycles do not invert RGB content");
        assertTrue(!ExternalPsdEditHostProbe.isFinalRgbMutationCycle(2, 3),
            "middle cycles do not invert RGB content");
        assertTrue(ExternalPsdEditHostProbe.isFinalRgbMutationCycle(3, 3),
            "only the final cycle inverts RGB content");
        assertTrue(ExternalPsdEditHostProbe.isFinalRgbMutationCycle(1, 1),
            "a one-cycle run still has one final inversion");
        expectIllegalArgument(() -> ExternalPsdEditHostProbe.isFinalRgbMutationCycle(0, 3),
            "zero cycle is rejected");
        expectIllegalArgument(() -> ExternalPsdEditHostProbe.isFinalRgbMutationCycle(4, 3),
            "cycle beyond requested count is rejected");
    }

    private static void testSaveCycleBytes() {
        for (int cycles = 1; cycles <= 3; cycles++) {
            final byte[] baselineBytes = validationPsd();
            final String baseline = targetFingerprint(baselineBytes);
            byte[] current = baselineBytes;
            for (int cycle = 1; cycle <= cycles; cycle++) {
                final ExternalPsdEditHostProbe.CycleWritePlan plan =
                    ExternalPsdEditHostProbe.prepareSaveCycleBytes(
                        current, cycle, cycles, true);
                final String first = targetFingerprint(plan.firstWrite());
                if (cycle < cycles) {
                    assertEquals(baseline, first,
                        "cycle " + cycle + " of " + cycles + " leaves target RGB unchanged");
                } else {
                    assertTrue(!baseline.equals(first),
                        "last cycle of " + cycles + " changes decoded target RGB");
                }
                if (cycle == 3) {
                    assertEquals(first, targetFingerprint(plan.overlapFinalWrite()),
                        "overlap second write retains exactly one RGB inversion");
                }
                current = plan.overlapFinalWrite();
            }
            assertTrue(!baseline.equals(targetFingerprint(current)),
                "saved final bytes for " + cycles + " cycles contain the final content change");
        }
    }

    private static String targetFingerprint(final byte[] psd) {
        return PsdValidationContent.targetLayerRgbFingerprint(psd).sha256();
    }

    /** Valid RLE1 fixture used only to prove the production cycle-byte seam. */
    private static byte[] validationPsd() {
        final List<ValidationLayer> layers = List.of(
            ValidationLayer.create(0, 0, 1000, 1000, "layer0"),
            ValidationLayer.create(0, 0, 100, 100, "layer1"),
            ValidationLayer.create(100, 100, 200, 200, "layer2"),
            ValidationLayer.create(200, 200, 300, 300, "layer3"),
            ValidationLayer.create(250, 250, 350, 350, "layer4"),
            ValidationLayer.create(350, 350, 450, 450, "layer5"),
            ValidationLayer.create(450, 450, 550, 550, "layer6"));
        final PsdBytes layerInfo = new PsdBytes();
        layerInfo.u16(layers.size());
        for (final ValidationLayer layer : layers) layerInfo.bytes(layer.record());
        for (final ValidationLayer layer : layers) {
            for (final byte[] channel : layer.channels()) layerInfo.bytes(channel);
        }
        final PsdBytes layerMask = new PsdBytes();
        layerMask.u32(layerInfo.size());
        layerMask.bytes(layerInfo.toByteArray());
        layerMask.u32(0);

        final PsdBytes composite = new PsdBytes();
        composite.u16(1);
        final int compositeRows = 4 * 1000;
        final int compositeRowTable = composite.size();
        composite.zeros(compositeRows * 2);
        for (int channel = 0; channel < 4; channel++) {
            for (int row = 0; row < 1000; row++) {
                final int start = composite.size();
                repeatChunks(composite, 1000, 1 + channel);
                composite.patchU16(compositeRowTable + (channel * 1000 + row) * 2,
                    composite.size() - start);
            }
        }

        final PsdBytes file = new PsdBytes();
        file.ascii("8BPS");
        file.u16(1);
        file.zeros(6);
        file.u16(4);
        file.u32(1000);
        file.u32(1000);
        file.u16(8);
        file.u16(3);
        file.u32(0);
        file.u32(0);
        file.u32(layerMask.size());
        file.bytes(layerMask.toByteArray());
        file.bytes(composite.toByteArray());
        return file.toByteArray();
    }

    private record ValidationLayer(byte[] record, List<byte[]> channels) {
        private static ValidationLayer create(final int top, final int left,
            final int bottom, final int right, final String name) {
            final int width = right - left;
            final int height = bottom - top;
            final List<byte[]> channels = List.of(
                validationChannel(width, height, 0, top == 450 && left == 450),
                validationChannel(width, height, 1, top == 450 && left == 450),
                validationChannel(width, height, 2, top == 450 && left == 450),
                validationChannel(width, height, 3, top == 450 && left == 450));
            final PsdBytes record = new PsdBytes();
            record.u32(top);
            record.u32(left);
            record.u32(bottom);
            record.u32(right);
            record.u16(channels.size());
            for (int channel = 0; channel < channels.size(); channel++) {
                record.u16(channel == 3 ? 0xffff : channel);
                record.u32(channels.get(channel).length);
            }
            record.ascii("8BIM");
            record.ascii("norm");
            record.u8(255);
            record.u8(0);
            record.u8(0);
            record.u8(0);
            final PsdBytes extra = new PsdBytes();
            extra.u32(0);
            extra.u32(0);
            final byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
            extra.u8(nameBytes.length);
            extra.bytes(nameBytes);
            final int padded = (nameBytes.length + 1 + 3) & ~3;
            extra.zeros(padded - nameBytes.length - 1);
            record.u32(extra.size());
            record.bytes(extra.toByteArray());
            return new ValidationLayer(record.toByteArray(), channels);
        }
    }

    private static byte[] validationChannel(final int width, final int height,
        final int channel, final boolean target) {
        final PsdBytes data = new PsdBytes();
        data.u16(1);
        final int rows = data.size();
        data.zeros(height * 2);
        for (int row = 0; row < height; row++) {
            final int start = data.size();
            final int value = target ? 0x20 + channel + (row & 0x0f) : 0x50 + channel;
            repeatChunks(data, width, value);
            data.patchU16(rows + row * 2, data.size() - start);
        }
        return data.toByteArray();
    }

    private static void repeatChunks(final PsdBytes data, final int width, final int value) {
        int remaining = width;
        while (remaining > 0) {
            final int count = Math.min(128, remaining);
            if (count == 1) {
                data.u8(0);
                data.u8(value);
            } else {
                data.u8(257 - count);
                data.u8(value);
            }
            remaining -= count;
        }
    }

    private static final class PsdBytes {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        private int size() { return output.size(); }
        private void u8(final int value) { output.write(value & 0xff); }
        private void u16(final int value) {
            output.write((value >>> 8) & 0xff);
            output.write(value & 0xff);
        }
        private void u32(final long value) {
            output.write((int) (value >>> 24) & 0xff);
            output.write((int) (value >>> 16) & 0xff);
            output.write((int) (value >>> 8) & 0xff);
            output.write((int) value & 0xff);
        }
        private void ascii(final String value) {
            bytes(value.getBytes(StandardCharsets.US_ASCII));
        }
        private void zeros(final int count) {
            for (int index = 0; index < count; index++) output.write(0);
        }
        private void bytes(final byte[] value) { output.write(value, 0, value.length); }
        private void patchU16(final int offset, final int value) {
            final byte[] current = output.toByteArray();
            current[offset] = (byte) (value >>> 8);
            current[offset + 1] = (byte) value;
            output.reset();
            output.write(current, 0, current.length);
        }
        private byte[] toByteArray() { return output.toByteArray(); }
    }

    private static Properties validPersistEvidence() {
        final Properties result = new Properties();
        result.setProperty("persist.baselineTargetRgbSha256", "a".repeat(64));
        result.setProperty("persist.baselineSecondTargetRgbSha256", "a".repeat(64));
        result.setProperty("persist.postEditTargetRgbSha256", "b".repeat(64));
        result.setProperty("persist.targetContentChanged", "true");
        result.setProperty("persist.saveSucceeded", "true");
        result.setProperty("persist.tempQuarantine.status", "MOVED");
        result.setProperty("persist.tempQuarantine.taskOwned", "true");
        result.setProperty("persist.tempQuarantine.sourceMissing", "true");
        return result;
    }

    private static Properties copy(final Properties source) {
        final Properties copy = new Properties();
        copy.putAll(source);
        return copy;
    }

    private static void expectIllegalState(final Runnable action, final String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalStateException expected) {
            // expected
        }
    }

    private static void expectIllegalArgument(final Runnable action, final String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void expectIllegalStateChecked(final ThrowingAction action,
        final String message) throws Exception {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalStateException expected) {
            // expected
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static void deleteTree(final Path root) throws IOException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException failure) {
                    throw new RuntimeException(failure);
                }
            });
        }
    }

    private static void testPopupTriggerDispatch() {
        assertTrue(!ExternalPsdEditHostProbe.popupTriggerOnPress("Windows 11"),
            "Windows popup trigger is on release");
        assertTrue(ExternalPsdEditHostProbe.popupTriggerOnPress("Linux"),
            "non-Windows popup trigger is on press");

        assertSinglePopupTrigger(true, MouseEvent.MOUSE_PRESSED);
        assertSinglePopupTrigger(false, MouseEvent.MOUSE_RELEASED);
    }

    private static void assertSinglePopupTrigger(final boolean triggerOnPress,
        final int expectedTriggerEvent) {
        final RecordingComponent component = new RecordingComponent();
        final var dispatch = ExternalPsdEditHostProbe.dispatchRightClick(
            component, 10, 12, triggerOnPress);
        assertContains(dispatch.diagnostic(), "synthetic=",
            "synthetic event coordinates are recorded");
        assertContains(dispatch.diagnostic(), "pointer=",
            "real pointer observation is recorded");
        final List<MouseEvent> right = component.events().stream()
            .filter(event -> event.getButton() == MouseEvent.BUTTON3)
            .toList();
        assertEquals(2, right.size(), "right click dispatches press and release");
        assertEquals(expectedTriggerEvent,
            right.stream().filter(MouseEvent::isPopupTrigger).findFirst().orElseThrow().getID(),
            "exactly one right-click event is the popup trigger");
        assertEquals(1L, right.stream().filter(MouseEvent::isPopupTrigger).count(),
            "one popup trigger per right click");
    }

    private static void testRightClickDispatchFailure() {
        final ThrowingComponent component = new ThrowingComponent();
        try {
            ExternalPsdEditHostProbe.dispatchRightClick(component, 10, 12, true);
            throw new AssertionError("right-click handler failures must be exposed");
        } catch (ExternalPsdEditHostProbe.RightClickDispatchException failure) {
            assertEquals(2L, component.events().stream()
                .filter(event -> event.getButton() == MouseEvent.BUTTON3).count(),
                "release is attempted after press failure");
            final java.io.StringWriter trace = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(trace));
            final String text = trace.toString();
            assertContains(text, "MOUSE_PRESSED", "press phase is in failure trace");
            assertContains(text, "pressed-failure", "press cause is in failure trace");
            assertContains(text, "MOUSE_RELEASED", "release phase is in failure trace");
            assertContains(text, "released-failure", "release cause is in failure trace");
            assertTrue(failure.getCause() != null, "first dispatch failure remains the cause");
            assertEquals("pressed-failure", failure.getCause().getCause().getMessage(),
                "first original cause is retained");
            assertEquals(1, failure.getSuppressed().length,
                "second dispatch failure remains suppressed");
            assertEquals("released-failure", failure.getSuppressed()[0].getCause().getMessage(),
                "second original cause is retained");
        }
    }

    private static void testSyntheticTargetDiagnostics() {
        final javax.swing.JTable table = new javax.swing.JTable(
            new Object[][]{{"value"}}, new Object[]{"column"}) {
            @Override protected void processMouseEvent(final MouseEvent event) {
                // Keep this renderer-diagnostic test independent of BasicTableUI's headful
                // menu-shortcut lookup; the production probe still dispatches normally.
            }
        };
        final var dispatch = ExternalPsdEditHostProbe.dispatchRightClick(table, 2, 2, true);
        assertContains(dispatch.diagnostic(), "target=",
            "dispatch target identity is recorded");
        assertContains(dispatch.diagnostic(), "renderer=",
            "table renderer candidate is recorded");
        assertContains(dispatch.diagnostic(), "prepared=",
            "prepared renderer state is recorded");
        assertContains(dispatch.diagnostic(), "skipped renderer preparation",
            "detached diagnostic does not prepare a renderer");
        assertContains(dispatch.diagnostic(), "screen=(",
            "synthetic screen coordinates are recorded");
    }

    private static void testRendererPreparationFollowsDispatch() {
        final OrderedTable table = new OrderedTable();
        ExternalPsdEditHostProbe.dispatchRightClick(table, 2, 2, true);
        assertTrue(table.rightClickSeen(), "right-click events run before renderer preparation");
        assertTrue(table.rendererPrepared(), "live renderer diagnostic is still recorded");
    }

    private static void testReplacedTargetAfterSelection() {
        final ReplacingComponent captured = new ReplacingComponent();
        final RecordingComponent replacement = new RecordingComponent();
        final var dispatch = ExternalPsdEditHostProbe.dispatchRightClickAfterSelection(
            captured, 10, 12, true,
            () -> new ExternalPsdEditHostProbe.DispatchTarget(replacement, 20, 22));

        assertTrue(captured.detached(), "selection phase records the captured component as detached");
        assertEquals(0L, captured.events().stream()
            .filter(event -> event.getButton() == MouseEvent.BUTTON3).count(),
            "detached captured component receives no right-click events");
        final List<MouseEvent> right = replacement.events().stream()
            .filter(event -> event.getButton() == MouseEvent.BUTTON3)
            .toList();
        assertEquals(2, right.size(), "replacement receives the right-click press and release");
        assertEquals(1L, right.stream().filter(MouseEvent::isPopupTrigger).count(),
            "replacement receives one popup trigger");
        assertContains(dispatch.diagnostic(), "source=",
            "replacement dispatch records its event source");
    }

    private static void testUnrelocatableTargetIsRejected() {
        final ReplacingComponent captured = new ReplacingComponent();
        try {
            ExternalPsdEditHostProbe.dispatchRightClickAfterSelection(
                captured, 10, 12, true, () -> null);
            throw new AssertionError("missing replacement target must be rejected");
        } catch (RuntimeException failure) {
            assertContains(failure.getMessage(), "could not be revalidated",
                "missing replacement target explains the rejection");
        }
        assertEquals(0L, captured.events().stream()
            .filter(event -> event.getButton() == MouseEvent.BUTTON3).count(),
            "unrelocatable target receives no right-click events");
        assertEquals(3, ExternalPsdEditHostProbe.rowDispatchAttemptLimit(),
            "row relocation retries are bounded");
    }

    private static void testExactRowResolverAndDispatchGuards() throws Exception {
        final String sourceClass = ExactHostRowTarget.ART_MESH_SOURCE_CLASS_NAME;
        final ExactHostRowTarget.Identity capturedIdentity = new ExactHostRowTarget.Identity(
            ExactHostRowTarget.RowFamily.PARTS, sourceClass, "ArtMesh4");
        final ExternalPsdEditHostProbe.ExactCapturedRow captured = exactCapturedRow(
            capturedIdentity, "capture-table", "capture-window", "capture-model", 2,
            true, false);
        final LiveComponent rebuiltComponent = new LiveComponent();
        final ExternalPsdEditHostProbe.ExactCurrentRow rebuilt = exactCurrentRow(
            capturedIdentity, "replacement-table", "capture-window", "replacement-model", 8,
            true, false, rebuiltComponent);
        final var relocated = ExternalPsdEditHostProbe.resolveExactActiveRowForTest(
            captured, List.of(rebuilt));
        assertTrue(relocated.available(), "same family/source/domain identity relocates after rebuild");
        assertSame(rebuiltComponent, relocated.row().component(),
            "same domain ID selects the rebuilt component");
        assertEquals(8, relocated.row().descriptor().viewRow(),
            "rebuild relocation uses the fresh row, not the captured row index");

        final var differentId = ExternalPsdEditHostProbe.resolveExactActiveRowForTest(
            captured, List.of(exactCurrentRow(
                new ExactHostRowTarget.Identity(ExactHostRowTarget.RowFamily.PARTS,
                    sourceClass, "ArtMesh5"), "other-table", "capture-window", "other-model", 8,
                true, false, new LiveComponent())));
        assertTrue(!differentId.available(), "a different captured ArtMesh ID is not a candidate");
        final var differentFamily = ExternalPsdEditHostProbe.resolveExactActiveRowForTest(
            captured, List.of(exactCurrentRow(
                new ExactHostRowTarget.Identity(ExactHostRowTarget.RowFamily.DEFORMER,
                    sourceClass, "ArtMesh4"), "other-table", "capture-window", "other-model", 8,
                true, false, new LiveComponent())));
        assertTrue(!differentFamily.available(), "a different row family is not a candidate");
        final LiveComponent mismatchComponent = new LiveComponent();
        final var mismatchDispatch = onEdt(() ->
            ExternalPsdEditHostProbe.dispatchExactResolvedRowForTest(
                captured, exactCurrentRow(
                    new ExactHostRowTarget.Identity(ExactHostRowTarget.RowFamily.PARTS,
                        sourceClass, "ArtMesh5"), "other-table", "capture-window", "other-model", 2,
                    true, false, mismatchComponent), rebuilt));
        assertContains(mismatchDispatch.diagnostic(), "not-dispatched",
            "different captured domain ID is rejected before any selection event");
        assertEquals(0, mismatchComponent.mouseEvents().size(),
            "different target receives no left or right click");

        final var otherWindow = ExternalPsdEditHostProbe.resolveExactActiveRowForTest(
            captured, List.of(exactCurrentRow(capturedIdentity, "other-table", "other-window",
                "other-model", 8, true, false, new LiveComponent())));
        assertTrue(!otherWindow.available(), "same ID in another window is rejected");
        assertContains(otherWindow.reason(), "another window",
            "cross-window exact target rejection is explicit");

        final var ambiguous = ExternalPsdEditHostProbe.resolveExactActiveRowForTest(
            captured, List.of(
                rebuilt,
                exactCurrentRow(capturedIdentity, "second-table", "capture-window", "second-model",
                    9, true, false, new LiveComponent())));
        assertTrue(!ambiguous.available(), "duplicate same-window exact IDs are rejected");
        assertContains(ambiguous.reason(), "ambiguous",
            "same-window exact ambiguity is explicit");

        final var changedState = exactCurrentRow(capturedIdentity, "replacement-table",
            "capture-window", "replacement-model", 8, false, false, rebuiltComponent);
        assertTrue(!ExternalPsdEditHostProbe.sameExactStateForTest(captured, changedState),
            "visibility change is not treated as the same exact target state");
        final var stateDispatch = onEdt(() ->
            ExternalPsdEditHostProbe.dispatchExactResolvedRowForTest(
                captured, changedState, changedState));
        assertContains(stateDispatch.diagnostic(), "not-dispatched",
            "state change is rejected before selection and right-click");
        assertEquals(0, rebuiltComponent.mouseEvents().size(),
            "state change does not click or auto-restore the row");

        final NameRecordingTable nameTable = onEdt(NameRecordingTable::new);
        final ExactHostRowTarget.NameCell nameCell = onEdt(() ->
            ExactHostRowTarget.nameCellForTest(nameTable, 0).orElseThrow());
        final ExactHostRowTarget.Identity nameIdentity = new ExactHostRowTarget.Identity(
            ExactHostRowTarget.RowFamily.DEFORMER, sourceClass, "ArtMesh4");
        final ExternalPsdEditHostProbe.ExactCapturedRow nameCaptured =
            new ExternalPsdEditHostProbe.ExactCapturedRow(nameIdentity, "name-table",
                "name-window", "name-model", nameCell.viewRow(), nameCell.modelRow(), nameCell,
                new ExactHostRowTarget.VisibilityLockState(true, false));
        final ExternalPsdEditHostProbe.ExactCurrentRow nameCurrent =
            new ExternalPsdEditHostProbe.ExactCurrentRow(nameCaptured, nameTable);
        final var offEdtDispatch =
            ExternalPsdEditHostProbe.dispatchExactResolvedRowForTest(
                nameCaptured, nameCurrent, nameCurrent);
        assertContains(offEdtDispatch.diagnostic(), "must run on EDT",
            "exact Swing dispatch seam rejects off-EDT access");
        assertEquals(0, nameTable.mouseEvents().size(),
            "off-EDT exact dispatch does not touch the table");
        final Object drawBefore = nameTable.getModel().getValueAt(0, 0);
        final Object lockBefore = nameTable.getModel().getValueAt(0, 1);
        final var nameDispatch = onEdt(() ->
            ExternalPsdEditHostProbe.dispatchExactResolvedRowForTest(
                nameCaptured, nameCurrent, nameCurrent));
        assertContains(nameDispatch.diagnostic(), "afterLeft=",
            "exact dispatch records the refreshed row identity and state");
        for (final MouseEvent event : nameTable.mouseEvents()) {
            final int viewColumn = nameTable.columnAtPoint(new Point(event.getX(), event.getY()));
            assertEquals(ExactHostRowTarget.NAME_MODEL_COLUMN,
                nameTable.convertColumnIndexToModel(viewColumn),
                "left/right events use the converted name column, never Draw or Lock");
        }
        assertEquals(drawBefore, nameTable.getModel().getValueAt(0, 0),
            "name-column events do not change Draw state");
        assertEquals(lockBefore, nameTable.getModel().getValueAt(0, 1),
            "name-column events do not change Lock state");
    }

    private static ExternalPsdEditHostProbe.ExactCapturedRow exactCapturedRow(
        final ExactHostRowTarget.Identity identity, final String widget, final String window,
        final String model, final int row, final boolean visible, final boolean locked) {
        final ExactHostRowTarget.NameCell cell = new ExactHostRowTarget.NameCell(
            row, row, 2, ExactHostRowTarget.NAME_MODEL_COLUMN,
            new Rectangle(0, row * 20, 120, 20), new Point(60, row * 20 + 10));
        return new ExternalPsdEditHostProbe.ExactCapturedRow(identity, widget, window, model,
            row, row, cell, new ExactHostRowTarget.VisibilityLockState(visible, locked));
    }

    private static ExternalPsdEditHostProbe.ExactCurrentRow exactCurrentRow(
        final ExactHostRowTarget.Identity identity, final String widget, final String window,
        final String model, final int row, final boolean visible, final boolean locked,
        final Component component) {
        return new ExternalPsdEditHostProbe.ExactCurrentRow(
            exactCapturedRow(identity, widget, window, model, row, visible, locked), component);
    }

    private static void testActiveRowResolver() {
        final Component original = new RecordingComponent();
        final Component replacement = new RecordingComponent();
        final var captured = capturedRow("widget-a", "window-a", 2, "mesh-a", true);

        final var sameIdentity = ExternalPsdEditHostProbe.resolveActiveRowForTest(
            captured, List.of(currentRow("widget-a", "window-a", 2, "mesh-a", true, original)));
        assertTrue(sameIdentity.available(), "same widget and row identity resolves");
        assertSame(original, sameIdentity.row().component(), "same identity keeps target");

        final var sameIdentityWithDuplicate = ExternalPsdEditHostProbe.resolveActiveRowForTest(
            captured, List.of(
                currentRow("widget-a", "window-a", 2, "mesh-a", true, original),
                currentRow("widget-b", "window-a", 8, "mesh-a", true, replacement)));
        assertTrue(sameIdentityWithDuplicate.available(),
            "same identity wins over another same-key row");
        assertSame(original, sameIdentityWithDuplicate.row().component(),
            "same identity branch preserves the captured target constraint");

        final var moved = ExternalPsdEditHostProbe.resolveActiveRowForTest(
            captured, List.of(currentRow("widget-a", "window-a", 7, "mesh-a", true, original)));
        assertTrue(moved.available(), "a uniquely keyed row can move within its widget");
        assertEquals(7, moved.row().row(), "row movement resolves the new row index");

        final var replaced = ExternalPsdEditHostProbe.resolveActiveRowForTest(
            captured, List.of(currentRow("widget-b", "window-a", 8, "mesh-a", true, replacement)));
        assertTrue(replaced.available(), "a replacement component can be relocated");
        assertSame(replacement, replaced.row().component(),
            "replacement component is the resolved target");
        assertEquals(8, replaced.row().row(), "replacement row is used instead of captured index");

        final var otherWindow = ExternalPsdEditHostProbe.resolveActiveRowForTest(
            captured, List.of(currentRow("widget-b", "window-b", 8, "mesh-a", true, replacement)));
        assertTrue(!otherWindow.available(), "matching row in another window is rejected");
        assertContains(otherWindow.reason(), "captured window",
            "cross-window rejection identifies the captured window constraint");

        final var ambiguous = ExternalPsdEditHostProbe.resolveActiveRowForTest(
            captured, List.of(
                currentRow("widget-b", "window-a", 8, "mesh-a", true, replacement),
                currentRow("widget-c", "window-a", 9, "mesh-a", true, new RecordingComponent())));
        assertTrue(!ambiguous.available(), "duplicate same-window keys are rejected");
        assertContains(ambiguous.reason(), "ambiguous", "ambiguous row rejection is explicit");

        final var unavailable = ExternalPsdEditHostProbe.resolveActiveRowForTest(
            capturedRow("widget-a", "window-a", 2, "", false),
            List.of(currentRow("widget-a", "window-a", 2, "mesh-a", true, original)));
        assertTrue(!unavailable.available(), "an unavailable capture key is rejected");
        assertContains(unavailable.reason(), "no stable key",
            "unavailable key rejection explains why index reuse is unsafe");
    }

    private static void testExactTargetFamilySelection() {
        final String sourceClass = ExactHostRowTarget.ART_MESH_SOURCE_CLASS_NAME;
        final String expectedDomainId = "ArtMesh4";
        final ExactHostRowTarget.Identity partsIdentity = new ExactHostRowTarget.Identity(
            ExactHostRowTarget.RowFamily.PARTS, sourceClass, expectedDomainId);
        final ExactHostRowTarget.Identity deformerIdentity = new ExactHostRowTarget.Identity(
            ExactHostRowTarget.RowFamily.DEFORMER, sourceClass, expectedDomainId);
        final ExternalPsdEditHostProbe.ExactCapturedRow parts = exactCapturedRow(
            partsIdentity, "parts-table", "active-window", "parts-model", 4, true, false);
        final ExternalPsdEditHostProbe.ExactCapturedRow deformer = exactCapturedRow(
            deformerIdentity, "deformer-table", "active-window", "deformer-model", 2,
            true, false);

        final var bothFamilies = ExternalPsdEditHostProbe.selectExactTargetRowsForTest(
            List.of(parts, deformer), "active-window", expectedDomainId);
        assertTrue(bothFamilies.available(),
            "one Parts and one Deformer row for the same ArtMesh are legal entrances");
        assertEquals(2, bothFamilies.rows().size(),
            "both legal row families remain available for ordered attempts");
        assertEquals(ExactHostRowTarget.RowFamily.DEFORMER,
            bothFamilies.rows().get(0).identity().rowFamily(),
            "Deformer is the deterministic first entrance");
        assertEquals(ExactHostRowTarget.RowFamily.PARTS,
            bothFamilies.rows().get(1).identity().rowFamily(),
            "Parts is the deterministic fallback entrance");

        final var duplicateFamily = ExternalPsdEditHostProbe.selectExactTargetRowsForTest(
            List.of(parts, exactCapturedRow(partsIdentity, "parts-table-2", "active-window",
                "parts-model-2", 9, true, false)), "active-window", expectedDomainId);
        assertTrue(!duplicateFamily.available(),
            "multiple active candidates in one row family are rejected");
        assertEquals(ExternalPsdEditHostProbe.ExactTargetSelectionStatus.AMBIGUOUS,
            duplicateFamily.status(), "same-family duplication is classified as ambiguous");
        assertContains(duplicateFamily.reason(), "same row family",
            "same-family ambiguity explains the target constraint");

        final var activeAndOtherWindow = ExternalPsdEditHostProbe.selectExactTargetRowsForTest(
            List.of(parts, exactCapturedRow(deformerIdentity, "other-window-table",
                "other-window", "other-model", 1, true, false)),
            "active-window", expectedDomainId);
        assertTrue(activeAndOtherWindow.available(),
            "a proven active-window target is not made ambiguous by another window");
        assertEquals(1, activeAndOtherWindow.rows().size(),
            "the other window is not an entrance candidate");
        assertEquals("active-window",
            activeAndOtherWindow.rows().get(0).windowIdentity(),
            "selection cannot fall back to another window");

        final var onlyOtherWindow = ExternalPsdEditHostProbe.selectExactTargetRowsForTest(
            List.of(exactCapturedRow(deformerIdentity, "other-window-table", "other-window",
                "other-model", 1, true, false)), "active-window", expectedDomainId);
        assertTrue(!onlyOtherWindow.available(),
            "a matching row only in another window is rejected");
        assertEquals(ExternalPsdEditHostProbe.ExactTargetSelectionStatus.REJECTED,
            onlyOtherWindow.status(), "cross-window fallback is a hard rejection");
        assertContains(onlyOtherWindow.reason(), "another window",
            "cross-window rejection is explicit");

        final var unknownWindow = ExternalPsdEditHostProbe.selectExactTargetRowsForTest(
            List.of(parts, deformer), "", expectedDomainId);
        assertTrue(!unknownWindow.available(),
            "an unproven active window cannot select any target");
        assertEquals(ExternalPsdEditHostProbe.ExactTargetSelectionStatus.REJECTED,
            unknownWindow.status(), "unknown active window is fail-closed");

        final var differentId = ExternalPsdEditHostProbe.selectExactTargetRowsForTest(
            List.of(exactCapturedRow(new ExactHostRowTarget.Identity(
                ExactHostRowTarget.RowFamily.PARTS, sourceClass, "ArtMesh5"),
                "different-id-table", "active-window", "different-id-model", 3,
                true, false)), "active-window", expectedDomainId);
        assertTrue(!differentId.available(),
            "a different ArtMesh domain ID cannot be selected");
        assertEquals(ExternalPsdEditHostProbe.ExactTargetSelectionStatus.NOT_FOUND,
            differentId.status(), "different domain IDs are not treated as ambiguity");
        assertEquals(0, differentId.rows().size(),
            "a different domain ID produces no click candidate");
    }

    private static void testStableRowKeySafety() {
        final JTable collision = new JTable(new Object[][]{
            {"a", "b|String:c"},
            {"a|String:b", "c"}
        }, new Object[]{"one", "two"});
        final var first = ExternalPsdEditHostProbe.rowKeyForTest(
            collision, ExternalPsdEditHostProbe.RowKind.TABLE, 0);
        final var second = ExternalPsdEditHostProbe.rowKeyForTest(
            collision, ExternalPsdEditHostProbe.RowKind.TABLE, 1);
        assertTrue(first.available() && second.available(),
            "ordinary table values have stable keys");
        assertTrue(!first.value().equals(second.value()),
            "delimiters in values cannot collide in a row key");

        final String prefix = "x".repeat(256);
        final JTable longValues = new JTable(new Object[][]{
            {prefix + "a"}, {prefix + "b"}
        }, new Object[]{"one"});
        final var longFirst = ExternalPsdEditHostProbe.rowKeyForTest(
            longValues, ExternalPsdEditHostProbe.RowKind.TABLE, 0);
        final var longSecond = ExternalPsdEditHostProbe.rowKeyForTest(
            longValues, ExternalPsdEditHostProbe.RowKind.TABLE, 1);
        assertTrue(!longFirst.value().equals(longSecond.value()),
            "full values, not a 256-character preview, identify rows");

        final JTable throwing = new JTable(new Object[][]{{new ThrowingValue()}},
            new Object[]{"one"});
        assertTrue(!ExternalPsdEditHostProbe.rowKeyForTest(
            throwing, ExternalPsdEditHostProbe.RowKind.TABLE, 0).available(),
            "toString failure makes the row key unavailable");

        final Object identityOnly = new Object();
        final JTable defaultString = new JTable(new Object[][]{{identityOnly}},
            new Object[]{"one"});
        assertTrue(!ExternalPsdEditHostProbe.rowKeyForTest(
            defaultString, ExternalPsdEditHostProbe.RowKind.TABLE, 0).available(),
            "Object class-at-identity text is not a stable cross-component key");
    }

    private static void testBoundedRowDispatches() throws Exception {
        final int[] calls = {0};
        final int exhausted = ExternalPsdEditHostProbe.runBoundedRowDispatches(attempt -> {
            calls[0]++;
            assertEquals(calls[0], attempt, "retry attempt numbers are sequential");
            return true;
        });
        assertEquals(ExternalPsdEditHostProbe.rowDispatchAttemptLimit(), exhausted,
            "retry loop stops at its configured upper bound");
        assertEquals(ExternalPsdEditHostProbe.rowDispatchAttemptLimit(), calls[0],
            "retry operation is invoked only through the upper bound");

        calls[0] = 0;
        final int stopped = ExternalPsdEditHostProbe.runBoundedRowDispatches(attempt -> {
            calls[0]++;
            return attempt < 2;
        });
        assertEquals(2, stopped, "retry loop stops when the operation succeeds");
        assertEquals(2, calls[0], "successful attempt prevents another retry");
    }

    private static ExternalPsdEditHostProbe.CapturedRow capturedRow(
        final String widget, final String window, final int row, final String key,
        final boolean keyAvailable) {
        return new ExternalPsdEditHostProbe.CapturedRow(
            ExternalPsdEditHostProbe.RowKind.TABLE, "HostTable", widget, window, row,
            key, keyAvailable, "(0,0,10,10)");
    }

    private static ExternalPsdEditHostProbe.CurrentRow currentRow(
        final String widget, final String window, final int row, final String key,
        final boolean keyAvailable, final Component component) {
        return new ExternalPsdEditHostProbe.CurrentRow(
            capturedRow(widget, window, row, key, keyAvailable), component,
            new Rectangle(0, row * 10, 100, 10));
    }

    private static void testPopupMarker() {
        final JPopupMenu popup = new JPopupMenu();
        final javax.swing.JMenuItem item = new javax.swing.JMenuItem("Edit PSD Externally");
        popup.add(item);
        final String marker = ExternalPsdEditHostProbe.popupMarker(popup);
        assertContains(marker, "javax.swing.JPopupMenu@", "popup class and identity are recorded");
        assertContains(marker, "javax.swing.JMenuItem@", "menu child class and identity are recorded");
        assertContains(marker, "menuItem=true", "menu item subtype is identified");
        assertContains(marker, "text='Edit PSD Externally'", "menu text is recorded");
        assertContains(marker, "visible=false", "popup visibility is recorded");
        assertContains(marker, "showing=false", "popup showing state is recorded");
    }

    private static void testPopupAttemptAssociation() {
        final JPopupMenu old = new JPopupMenu();
        final JPopupMenu fresh = new JPopupMenu();
        assertSame(old, ExternalPsdEditHostProbe.popupForAttempt(
            List.of(old), List.of(old), List.of(old)),
            "a successfully dismissed popup may be reused for this attempt");
        assertSame(fresh, ExternalPsdEditHostProbe.popupForAttempt(
            List.of(old), List.of(), List.of(old, fresh)),
            "a new visible popup is associated with this attempt");
        assertNull(ExternalPsdEditHostProbe.popupForAttempt(
            List.of(old), List.of(), List.of(old)),
            "an old popup that was not dismissed is rejected as stale");
    }

    private static void testAutoImportEvidence() {
        final var before = new ExternalPsdEditHostProbe.GuiTargetState(
            true, "binding-a", 7L, "raw-a", false, "");
        final var applied = new ExternalPsdEditHostProbe.GuiTargetState(
            true, "binding-a", 7L, "raw-a", true, "");
        assertTrue(ExternalPsdEditHostProbe.acceptsAutoImport(before, applied, "raw-a"),
            "same target false-to-true replacement is accepted");
        assertTrue(!ExternalPsdEditHostProbe.acceptsAutoImport(before,
            new ExternalPsdEditHostProbe.GuiTargetState(true, "binding-b", 7L, "raw-a", true, ""),
            "raw-a"), "binding change is not replacement evidence");
        assertTrue(!ExternalPsdEditHostProbe.acceptsAutoImport(before,
            new ExternalPsdEditHostProbe.GuiTargetState(true, "binding-a", 8L, "raw-a", true, ""),
            "raw-a"), "generation change is not replacement evidence");
        assertTrue(!ExternalPsdEditHostProbe.acceptsAutoImport(before,
            new ExternalPsdEditHostProbe.GuiTargetState(true, "binding-a", 7L, "raw-b", true, ""),
            "raw-a"), "raw target change is not replacement evidence");
        assertTrue(!ExternalPsdEditHostProbe.acceptsAutoImport(
            new ExternalPsdEditHostProbe.GuiTargetState(true, "binding-a", 7L, "raw-a", true, ""),
            applied, "raw-a"), "initial isReplaced=true is not new replacement evidence");
    }

    private static String slice(final byte[] psd, final int[] range) {
        return new String(psd, range[0], range[1], StandardCharsets.US_ASCII);
    }

    /** Minimal valid-structured PSD: header, empty color mode, empty resources, layer info. */
    private static byte[] syntheticPsd(final String... layerNames)
        throws java.io.IOException {
        final ByteArrayOutputStream layerRecords = new ByteArrayOutputStream();
        final ByteArrayOutputStream channelData = new ByteArrayOutputStream();
        for (final String name : layerNames) {
            final byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
            final int padded = (nameBytes.length + 1 + 3) & ~3;
            final ByteArrayOutputStream extra = new ByteArrayOutputStream();
            extra.write(int32(0));            // mask length
            extra.write(int32(0));            // blending ranges length
            extra.write(nameBytes.length);    // pascal length
            extra.write(nameBytes, 0, nameBytes.length);
            for (int i = nameBytes.length + 1; i < padded; i++) extra.write(0);
            final byte[] extraBytes = extra.toByteArray();

            layerRecords.write(int32(0)); layerRecords.write(int32(0));
            layerRecords.write(int32(4)); layerRecords.write(int32(4)); // rect
            layerRecords.write(int16(1));                                // channel count
            layerRecords.write(int16(0)); layerRecords.write(int32(4));  // ch id + len
            layerRecords.write("8BIM".getBytes(StandardCharsets.US_ASCII), 0, 4);
            layerRecords.write("norm".getBytes(StandardCharsets.US_ASCII), 0, 4);
            layerRecords.write(255); layerRecords.write(0);
            layerRecords.write(8); layerRecords.write(0);                // opacity..filler
            layerRecords.write(int32(extraBytes.length));
            layerRecords.write(extraBytes, 0, extraBytes.length);
            channelData.write(int32(0xDEADBEEF));                        // 4 bytes channel data
        }
        final ByteArrayOutputStream layerInfo = new ByteArrayOutputStream();
        layerInfo.write(int16(layerNames.length));
        layerInfo.write(layerRecords.toByteArray(), 0, layerRecords.size());
        layerInfo.write(channelData.toByteArray(), 0, channelData.size());
        final ByteArrayOutputStream layerMask = new ByteArrayOutputStream();
        layerMask.write(int32(layerInfo.size()));
        layerMask.write(layerInfo.toByteArray(), 0, layerInfo.size());

        final ByteArrayOutputStream psd = new ByteArrayOutputStream();
        psd.write("8BPS".getBytes(StandardCharsets.US_ASCII), 0, 4);
        psd.write(int16(1));                 // version
        psd.write(new byte[6], 0, 6);        // reserved
        psd.write(int16(3));                 // channels
        psd.write(int32(4)); psd.write(int32(4));  // height, width
        psd.write(int16(8)); psd.write(int16(3));  // depth, color mode
        psd.write(int32(0));                 // color mode data
        psd.write(int32(0));                 // image resources
        psd.write(int32(layerMask.size()));
        psd.write(layerMask.toByteArray(), 0, layerMask.size());
        psd.write(int16(0));                 // compression
        psd.write(new byte[12], 0, 12);      // image data
        return psd.toByteArray();
    }

    private static byte[] int32(final int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array();
    }

    private static byte[] int16(final int value) {
        return ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort((short) value).array();
    }

    private static void assertTrue(final boolean condition, final String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertContains(final String text, final String expected,
        final String message) {
        assertTrue(text.contains(expected), message + " expected=" + expected + " text=" + text);
    }

    private static void assertSame(final Object expected, final Object actual,
        final String message) {
        if (expected != actual) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertNull(final Object actual, final String message) {
        if (actual != null) throw new AssertionError(message + " actual=" + actual);
    }

    private static void assertEquals(final Object expected, final Object actual,
        final String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }

    private static <T> T onEdt(final Callable<T> operation) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return operation.call();
        final AtomicReference<T> value = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                value.set(operation.call());
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        if (failure.get() != null) {
            final Throwable error = failure.get();
            if (error instanceof Exception exception) throw exception;
            if (error instanceof Error exception) throw exception;
            throw new java.lang.reflect.InvocationTargetException(error);
        }
        return value.get();
    }

    private static final class RecordingComponent extends Component {
        private final List<MouseEvent> events = new ArrayList<>();

        private RecordingComponent() {
            enableEvents(AWTEvent.MOUSE_EVENT_MASK);
        }

        @Override protected void processMouseEvent(final MouseEvent event) {
            events.add(event);
        }

        private List<MouseEvent> events() { return events; }
    }

    private static final class ThrowingComponent extends Component {
        private final List<MouseEvent> events = new ArrayList<>();

        private ThrowingComponent() {
            enableEvents(AWTEvent.MOUSE_EVENT_MASK);
        }

        @Override protected void processMouseEvent(final MouseEvent event) {
            events.add(event);
            if (event.getButton() == MouseEvent.BUTTON3) {
                throw new IllegalStateException(event.getID() == MouseEvent.MOUSE_PRESSED
                    ? "pressed-failure" : "released-failure");
            }
        }

        private List<MouseEvent> events() { return events; }
    }

    private static final class LiveComponent extends Component {
        private final Container parent = new Container();
        private final List<MouseEvent> mouseEvents = new ArrayList<>();

        private LiveComponent() {
            enableEvents(AWTEvent.MOUSE_EVENT_MASK);
        }

        @Override public boolean isShowing() { return true; }
        @Override public boolean isDisplayable() { return true; }
        @Override public Container getParent() { return parent; }

        @Override protected void processMouseEvent(final MouseEvent event) {
            mouseEvents.add(event);
        }

        private List<MouseEvent> mouseEvents() { return mouseEvents; }
    }

    private static final class NameRecordingTable extends JTable {
        private final Container parent = new Container();
        private final List<MouseEvent> mouseEvents = new ArrayList<>();

        private NameRecordingTable() {
            super(new DefaultTableModel(
                new Object[][]{{Boolean.TRUE, Boolean.FALSE, "mesh-name", "overlap"}},
                new Object[]{"Draw", "Lock", "Name", "Overlap"}));
            setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
            setRowHeight(24);
            for (int column = 0; column < getColumnCount(); column++) {
                getColumnModel().getColumn(column).setPreferredWidth(80);
            }
            final var columns = getColumnModel();
            final var draw = columns.getColumn(0);
            columns.removeColumn(draw);
            columns.addColumn(draw);
            final var lock = columns.getColumn(0);
            columns.removeColumn(lock);
            columns.addColumn(lock);
            setSize(320, 24);
            doLayout();
            enableEvents(AWTEvent.MOUSE_EVENT_MASK);
        }

        @Override public boolean isShowing() { return true; }
        @Override public boolean isDisplayable() { return true; }
        @Override public Container getParent() { return parent; }
        @Override public Rectangle getVisibleRect() {
            return new Rectangle(0, 0, getWidth(), getHeight());
        }

        @Override protected void processMouseEvent(final MouseEvent event) {
            mouseEvents.add(event);
        }

        private List<MouseEvent> mouseEvents() { return mouseEvents; }
    }

    private static final class ThrowingValue {
        @Override public String toString() {
            throw new IllegalStateException("row-value-failure");
        }
    }

    private static final class ReplacingComponent extends Component {
        private final List<MouseEvent> events = new ArrayList<>();
        private boolean detached;

        private ReplacingComponent() {
            enableEvents(AWTEvent.MOUSE_EVENT_MASK);
        }

        @Override protected void processMouseEvent(final MouseEvent event) {
            events.add(event);
            if (event.getButton() == MouseEvent.BUTTON1
                && event.getID() == MouseEvent.MOUSE_RELEASED) {
                detached = true;
            }
        }

        private boolean detached() { return detached; }
        private List<MouseEvent> events() { return events; }
    }

    private static final class OrderedTable extends javax.swing.JTable {
        private final Container parent = new Container();
        private boolean rightClickSeen;
        private boolean rendererPrepared;

        private OrderedTable() {
            super(new Object[][]{{"value"}}, new Object[]{"column"});
        }

        @Override public boolean isShowing() { return true; }
        @Override public boolean isDisplayable() { return true; }
        @Override public Container getParent() { return parent; }

        @Override protected void processMouseEvent(final MouseEvent event) {
            if (event.getButton() == MouseEvent.BUTTON3) rightClickSeen = true;
        }

        @Override public Component prepareRenderer(final javax.swing.table.TableCellRenderer renderer,
            final int row, final int column) {
            rendererPrepared = true;
            return super.prepareRenderer(renderer, row, column);
        }

        private boolean rightClickSeen() { return rightClickSeen; }
        private boolean rendererPrepared() { return rendererPrepared; }
    }
}
