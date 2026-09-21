package dev.turboism.validation.externalpsd;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;

import dev.turboism.sdk.cubism.clipmask.PsdClipMaskDocumentSnapshot;
import dev.turboism.sdk.cubism.clipmask.PsdClipMaskDocumentSnapshot.PsdLayerSnapshot;
import dev.turboism.sdk.cubism.history.HistoryMoveResult;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.id.RawLayerId;
import dev.turboism.sdk.cubism.model.ArtMeshTextureInputs;
import dev.turboism.sdk.cubism.model.ModelImageEntry;
import dev.turboism.sdk.cubism.model.ModelImageRelation;
import dev.turboism.sdk.cubism.model.RawImageDetails;
import dev.turboism.sdk.cubism.model.RawLayerBinding;
import dev.turboism.sdk.cubism.model.RawTexture;
import dev.turboism.sdk.cubism.model.TextureInputBinding;
import dev.turboism.sdk.cubism.model.TextureRelationsSnapshot;
import dev.turboism.sdk.cubism.psd.PsdEditFile;
import dev.turboism.sdk.cubism.psd.PsdFileOperationResult;
import dev.turboism.sdk.cubism.psd.PsdFileRevision;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.cubism.psd.PsdReplaceResult;
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
        testExactTaskWindowBinding();
        testActiveRowResolver();
        testStableRowKeySafety();
        testGuiReadyTriggerProtocol();
        testGuiWaitDiagnostics();
        testGuiReadyTriggerProductionPath();
        testGuiEnableAndEdtAreNonBlocking();
        testGuiWindowBinding();
        testPrepareFixturePhaseDispatch();
        testNativeCloseDialogHandling();
        testCloseDiagnosticBudgets();
        testBoundedRowDispatches();
        testPopupMarker();
        testPopupAttemptAssociation();
        testAutoImportEvidence();
        testPersistEvidenceGate();
        testNativeFingerprintGates();
        testHistoryMovesRequireMoved();
        testTempCandidateBinding();
        testRawPsdStructureBoundaries();
        testSessionPsdReadiness();
        testNullFileKeySessionReadiness();
        testFileKeyTransitionsFailClosed();
        testConfiguredRealSessionPsd();
        testQuarantineMovesAllTrackedDirectories();
        testQuarantineRejectsConflictAndSymlink();
        testQuarantinePartialMoveEvidence();
        testTrackerStopAggregation();
        testFinalCycleSelection();
        testSaveCycleBytes();
        testRawImageRelationDeltaAndNewRawInspection();
        testCoordinatedNewRawExportIdentityGate();
        testAppliedTargetLineageGate();
        testDiagnosticObservationBoundaries();
        testDiagnosticTargetBinding();
        testDiagnosticPsdSnapshotUsesRawImageId();
        testDiagnosticBudgetIsRecomputedPerStage();
        testPublicProjectionFormatting();
        testBoundedSettleFailureAndTimeoutEvidence();
        testFreshDiagnosticFailureEvidence();
        testBoundedSettleInterrupt();

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

    private static void testSessionPsdReadiness() throws Exception {
        final byte[] valid = validationPsd();
        final ExternalPsdEditHostProbe.FileKeySupplier unavailableKey = attributes -> null;
        final byte[] unsupportedDepth = valid.clone();
        unsupportedDepth[22] = 0;
        unsupportedDepth[23] = 16;
        final ExternalPsdEditHostProbe.PsdStructureValidation depthFailure =
            ExternalPsdEditHostProbe.inspectPsdStructure(unsupportedDepth);
        assertTrue(!depthFailure.complete(), "unsupported bit depth is rejected");
        assertContains(depthFailure.reason(), "bit depth",
            "unsupported depth reports the header section");

        final byte[] unsupportedCompression = valid.clone();
        final int compositeOffset = compositeOffset(unsupportedCompression);
        unsupportedCompression[compositeOffset] = 0;
        unsupportedCompression[compositeOffset + 1] = 2;
        final ExternalPsdEditHostProbe.PsdStructureValidation compressionFailure =
            ExternalPsdEditHostProbe.inspectPsdStructure(unsupportedCompression);
        assertTrue(!compressionFailure.complete(), "unsupported compression is rejected");
        assertContains(compressionFailure.reason(), "compression",
            "unsupported compression reports the composite section");

        final Path root = Files.createTempDirectory("external PSD session readiness ");
        try {
            final Path old = Files.createDirectory(root.resolve("turboism-psd-old"));
            Files.write(old.resolve("external-edit.psd"), valid);
            final Set<Path> before = Set.of(old.toAbsolutePath().normalize());
            final Path session = root.resolve("turboism-psd-session");
            final AtomicReference<Throwable> writerFailure = new AtomicReference<>();
            final Thread writer = new Thread(() -> {
                try {
                    Thread.sleep(10L);
                    Files.createDirectory(session);
                    final Path file = session.resolve("external-edit.psd");
                    Files.createFile(file);
                    Thread.sleep(10L);
                    try (OutputStream output = Files.newOutputStream(file)) {
                        output.write(valid, 0, valid.length / 2);
                        output.flush();
                        Thread.sleep(25L);
                        output.write(valid, valid.length / 2, valid.length - valid.length / 2);
                    }
                } catch (Throwable failure) {
                    writerFailure.set(failure);
                }
            }, "external-psd-readiness-fixture-writer");
            writer.start();
            final ExternalPsdEditHostProbe.StablePsdSnapshot snapshot =
                ExternalPsdEditHostProbe.awaitSessionFileForTest(
                    root, before, 5000L, () -> false,
                    millis -> Thread.sleep(Math.min(millis, 5L)), unavailableKey);
            writer.join(2000L);
            if (writerFailure.get() != null) throw new AssertionError(
                "segmented session writer failed", writerFailure.get());
            assertTrue(Arrays.equals(valid, snapshot.bytes()),
                "pre-created segmented file returns the complete immutable read snapshot");
            assertTrue(snapshot.observation().structure().complete(),
                "stable session snapshot includes complete PSD structure evidence");

            ExternalPsdEditHostProbe.confirmSessionFileForWriteForTest(snapshot, unavailableKey);
            final byte[] renamed = ExternalPsdEditHostProbe.mutateLayerName(snapshot.bytes(), 1)
                .orElseThrow();
            Files.write(snapshot.path(), renamed);
            expectSessionReadinessFailure(
                () -> ExternalPsdEditHostProbe.confirmSessionFileForWriteForTest(
                    snapshot, unavailableKey),
                "write precondition rejects a changed session snapshot");
        } finally {
            deleteTree(root);
        }

        final Path truncatedRoot = Files.createTempDirectory("external PSD truncated readiness ");
        try {
            final Path directory = Files.createDirectory(
                truncatedRoot.resolve("turboism-psd-truncated"));
            final byte[] truncated = Arrays.copyOf(valid, valid.length - 1);
            assertTrue(!ExternalPsdEditHostProbe.layerNameRanges(truncated).isEmpty(),
                "truncated fixture still has a mutable layer name candidate");
            Files.write(directory.resolve("external-edit.psd"), truncated);
            final ExternalPsdEditHostProbe.SessionFileReadinessException failure =
                expectSessionReadinessFailure(
                    () -> ExternalPsdEditHostProbe.awaitSessionFileForTest(
                        truncatedRoot, Set.of(), 30L, () -> false, millis -> Thread.sleep(1L),
                        unavailableKey),
                    "truncated PSD cannot pass merely because a layer name is readable");
            assertContains(failure.getMessage(), "composite",
                "truncated PSD reports the incomplete composite section");
        } finally {
            deleteTree(truncatedRoot);
        }

        final Path ambiguousRoot = Files.createTempDirectory("external PSD ambiguous readiness ");
        try {
            Files.createDirectory(ambiguousRoot.resolve("turboism-psd-first"));
            Files.createDirectory(ambiguousRoot.resolve("turboism-psd-second"));
            final ExternalPsdEditHostProbe.SessionFileReadinessException failure =
                expectSessionReadinessFailure(
                    () -> ExternalPsdEditHostProbe.awaitSessionFileForTest(
                        ambiguousRoot, Set.of(), 1000L, () -> false, millis -> {
                            throw new AssertionError("ambiguous candidates must fail immediately");
                        }, attributes -> null), "multiple new candidate directories are rejected");
            assertContains(failure.getMessage(), "ambiguous",
                "candidate ambiguity is explicit");
        } finally {
            deleteTree(ambiguousRoot);
        }

        final Path oldRoot = Files.createTempDirectory("external PSD old-only readiness ");
        try {
            final Path old = Files.createDirectory(oldRoot.resolve("turboism-psd-old"));
            Files.write(old.resolve("external-edit.psd"), valid);
            final ExternalPsdEditHostProbe.SessionFileReadinessException failure =
                expectSessionReadinessFailure(
                    () -> ExternalPsdEditHostProbe.awaitSessionFileForTest(
                        oldRoot, Set.of(old.toAbsolutePath().normalize()), 20L,
                        () -> false, millis -> Thread.sleep(1L)),
                    "an old session directory is never reused");
            assertContains(failure.getMessage(), "no new PSD candidate",
                "old-only candidate failure explains the before/after binding");
        } finally {
            deleteTree(oldRoot);
        }

        final Path symlinkRoot = Files.createTempDirectory("external PSD symlink readiness ");
        final Path outside = Files.createTempDirectory("external PSD symlink outside ");
        try {
            Files.createSymbolicLink(symlinkRoot.resolve("turboism-psd-link"), outside);
            final ExternalPsdEditHostProbe.SessionFileReadinessException failure =
                expectSessionReadinessFailure(
                    () -> ExternalPsdEditHostProbe.awaitSessionFileForTest(
                        symlinkRoot, Set.of(), 1000L, () -> false, millis -> {
                            throw new AssertionError("symlink candidate must fail immediately");
                        }, unavailableKey), "candidate symlink is rejected before file access");
            assertContains(failure.getMessage(), "symlink",
                "candidate symlink rejection is explicit");
        } finally {
            deleteTree(symlinkRoot);
            deleteTree(outside);
        }

        final Path replacementRoot = Files.createTempDirectory("external PSD replacement readiness ");
        try {
            final Path directory = Files.createDirectory(
                replacementRoot.resolve("turboism-psd-replaced"));
            final Path file = directory.resolve("external-edit.psd");
            Files.write(file, valid);
            final AtomicInteger sleeps = new AtomicInteger();
            final Path replacement = replacementRoot.resolve("replacement.psd");
            final ExternalPsdEditHostProbe.SessionFileReadinessException failure =
                expectSessionReadinessFailure(
                    () -> ExternalPsdEditHostProbe.awaitSessionFileForTest(
                        replacementRoot, Set.of(), 1000L, () -> false, millis -> {
                            if (sleeps.incrementAndGet() == 1) {
                                try {
                                    Files.write(replacement, valid);
                                    Files.move(replacement, file,
                                        StandardCopyOption.REPLACE_EXISTING);
                                } catch (IOException moveFailure) {
                                    throw new AssertionError("replacement fixture failed", moveFailure);
                                }
                            }
                            Thread.sleep(1L);
                        }), "candidate file replacement is rejected after binding");
            assertContains(failure.getMessage(), "replaced",
                "file identity replacement is explicit");
        } finally {
            deleteTree(replacementRoot);
        }

        final Path parentSymlinkRoot = Files.createTempDirectory(
            "external PSD parent symlink readiness ");
        final Path parentOutside = Files.createTempDirectory("external PSD parent outside ");
        try {
            final Path directory = Files.createDirectory(
                parentSymlinkRoot.resolve("turboism-psd-parent"));
            final Path file = directory.resolve("external-edit.psd");
            Files.write(file, valid);
            final ExternalPsdEditHostProbe.StablePsdSnapshot snapshot =
                ExternalPsdEditHostProbe.awaitSessionFileForTest(
                    parentSymlinkRoot, Set.of(), 5000L, () -> false,
                    millis -> Thread.sleep(Math.min(millis, 5L)), unavailableKey);
            final Path moved = parentSymlinkRoot.resolve("turboism-psd-parent-moved");
            Files.move(directory, moved);
            Files.createSymbolicLink(directory, parentOutside);
            final ExternalPsdEditHostProbe.SessionFileReadinessException failure =
                expectSessionReadinessFailure(
                    () -> ExternalPsdEditHostProbe.confirmSessionFileForWriteForTest(
                        snapshot, unavailableKey),
                    "write precondition rejects a parent-directory symlink replacement");
            assertContains(failure.getMessage(), "symlink",
                "parent-directory symlink replacement is explicit");
        } finally {
            deleteTree(parentSymlinkRoot);
            deleteTree(parentOutside);
        }

        final Path stoppedRoot = Files.createTempDirectory("external PSD stopped readiness ");
        try {
            final ExternalPsdEditHostProbe.SessionFileReadinessException failure =
                expectSessionReadinessFailure(
                    () -> ExternalPsdEditHostProbe.awaitSessionFileForTest(
                        stoppedRoot, Set.of(), 1000L, () -> true, millis -> {
                            throw new AssertionError("stopped wait must not sleep");
                        }), "stopped wait fails closed");
            assertContains(failure.getMessage(), "stopped",
                "stopped readiness is diagnosed rather than written");
        } finally {
            deleteTree(stoppedRoot);
        }
    }

    private static void testFileKeyTransitionsFailClosed() throws Exception {
        final byte[] valid = validationPsd();
        assertFileKeyTransition(valid, new Object[]{"available", "available", null},
            "VERIFIED→UNAVAILABLE", "available key becoming null is rejected");
        assertFileKeyTransition(valid, new Object[]{null, null, "available"},
            "UNAVAILABLE→VERIFIED", "null key becoming available is rejected");
        assertFileKeyTransition(valid, new Object[]{"first", "first", "second"},
            "fileKey changed while available", "an available key change is rejected");
    }

    private static void assertFileKeyTransition(final byte[] valid, final Object[] keys,
        final String expectedDiagnostic, final String message) throws Exception {
        final Path root = Files.createTempDirectory("external PSD file-key transition ");
        try {
            final Path directory = Files.createDirectory(
                root.resolve("turboism-psd-transition"));
            Files.write(directory.resolve("external-edit.psd"), valid);
            final AtomicInteger keyIndex = new AtomicInteger();
            final ExternalPsdEditHostProbe.FileKeySupplier changingKey = attributes -> {
                final int index = keyIndex.getAndIncrement();
                return keys[Math.min(index, keys.length - 1)];
            };
            final ExternalPsdEditHostProbe.SessionFileReadinessException failure =
                expectSessionReadinessFailure(
                    () -> ExternalPsdEditHostProbe.awaitSessionFileForTest(
                        root, Set.of(), 3000L, () -> false,
                        millis -> Thread.sleep(Math.min(millis, 5L)), changingKey), message);
            assertContains(failure.getMessage(), expectedDiagnostic, message + " diagnostic");
        } finally {
            deleteTree(root);
        }
    }

    private static void testNullFileKeySessionReadiness() throws Exception {
        final byte[] valid = validationPsd();
        final ExternalPsdEditHostProbe.FileKeySupplier unavailableKey = attributes -> null;
        final Path root = Files.createTempDirectory("external PSD null file-key readiness ");
        try {
            final Path directory = Files.createDirectory(
                root.resolve("turboism-psd-null-key"));
            final Path file = directory.resolve("external-edit.psd");
            Files.write(file, valid);
            final Properties readiness = new Properties();

            final ExternalPsdEditHostProbe.StablePsdSnapshot snapshot =
                ExternalPsdEditHostProbe.awaitSessionFileForTest(
                    root, Set.of(), 3000L, readiness, () -> false,
                    millis -> Thread.sleep(Math.min(millis, 5L)), unavailableKey);
            assertArrayEquals(valid, snapshot.bytes(),
                "a complete PSD remains readable when the platform file key is unavailable");
            assertEquals("UNAVAILABLE", snapshot.observation().fileKeyStatus(),
                "null file key is recorded as unavailable");
            assertTrue(!snapshot.observation().identityVerified(),
                "null file key never claims object identity verification");
            assertEquals((long) valid.length, snapshot.observation().size(),
                "null-key observation records the actual file size");
            assertEquals(sha256(valid), snapshot.observation().sha256(),
                "null-key observation records the complete content SHA-256");
            assertTrue(snapshot.observation().structure().complete(),
                "null-key observation records complete PSD structure");
            assertEquals("UNAVAILABLE", readiness.getProperty("gui.session.read.fileKeyStatus"),
                "readiness result records unavailable file key");
            assertEquals("false", readiness.getProperty("gui.session.read.identityVerified"),
                "readiness result does not claim null-key identity verification");
            assertEquals(Long.toString(valid.length),
                readiness.getProperty("gui.session.read.lastSize"),
                "readiness result records the actual null-key file size");
            assertEquals(Integer.toString(valid.length),
                readiness.getProperty("gui.session.read.lastByteLength"),
                "readiness result records the bytes read with an unavailable file key");
            assertTrue(!readiness.getProperty("gui.session.read.lastMtime", "-1").equals("-1"),
                "readiness result records mtime with an unavailable file key");
            assertEquals(sha256(valid), readiness.getProperty("gui.session.read.lastSha256"),
                "readiness result records the full null-key file SHA-256");
            assertTrue(!readiness.getProperty("gui.session.read.lastRealPath", "").isBlank(),
                "readiness result records the authenticated real task path");

            final Properties writeCheck = new Properties();
            ExternalPsdEditHostProbe.confirmSessionFileForWriteForTest(
                snapshot, writeCheck, unavailableKey);
            assertEquals("UNAVAILABLE",
                writeCheck.getProperty("gui.session.writeCheck.fileKeyStatus"),
                "write check records unavailable file key");
            assertEquals("false",
                writeCheck.getProperty("gui.session.writeCheck.identityVerified"),
                "write check does not claim null-key identity verification");
            assertEquals(Integer.toString(valid.length),
                writeCheck.getProperty("gui.session.writeCheck.byteLength"),
                "write check records the complete bytes read before mutation");

            final byte[] changed = ExternalPsdEditHostProbe.mutateLayerName(valid, 1)
                .orElseThrow();
            Files.write(file, changed);
            Files.setLastModifiedTime(file,
                java.nio.file.attribute.FileTime.fromMillis(
                    snapshot.observation().modifiedMillis()));
            final Properties changedWriteCheck = new Properties();
            expectSessionReadinessFailure(
                () -> ExternalPsdEditHostProbe.confirmSessionFileForWriteForTest(
                    snapshot, changedWriteCheck, unavailableKey),
                "null-key write confirmation rejects changed bytes even with the old mtime");
            assertEquals("UNAVAILABLE",
                changedWriteCheck.getProperty("gui.session.writeCheck.fileKeyStatus"),
                "changed null-key write check retains file-key diagnostic");
        } finally {
            deleteTree(root);
        }
    }

    private static void testRawPsdStructureBoundaries() {
        final RawPsdFixture fixture = rawPsd();
        final ExternalPsdEditHostProbe.PsdStructureValidation valid =
            ExternalPsdEditHostProbe.inspectPsdStructure(fixture.bytes());
        assertTrue(valid.complete(), "raw layer and composite boundaries are accepted");

        final byte[] shortLayer = fixture.bytes().clone();
        putU32(shortLayer, fixture.firstLayerChannelLengthOffset(), 2);
        assertTrue(!ExternalPsdEditHostProbe.inspectPsdStructure(shortLayer).complete(),
            "raw layer one-byte-short boundary is rejected");

        final byte[] longLayer = fixture.bytes().clone();
        putU32(longLayer, fixture.firstLayerChannelLengthOffset(), 4);
        assertTrue(!ExternalPsdEditHostProbe.inspectPsdStructure(longLayer).complete(),
            "raw layer one-byte-long boundary is rejected");

        final byte[] shortComposite = Arrays.copyOf(
            fixture.bytes(), fixture.bytes().length - 1);
        assertTrue(!ExternalPsdEditHostProbe.inspectPsdStructure(shortComposite).complete(),
            "raw composite one-byte-short boundary is rejected");

        final byte[] longComposite = Arrays.copyOf(
            fixture.bytes(), fixture.bytes().length + 1);
        assertTrue(!ExternalPsdEditHostProbe.inspectPsdStructure(longComposite).complete(),
            "raw composite one-byte-long boundary is rejected");
    }

    /** Optional read-only source check for the retained 958a6 real export. */
    private static void testConfiguredRealSessionPsd() throws Exception {
        final String configuredPath = System.getProperty(
            "turboism.validation.externalpsd.sessionSample", "");
        if (configuredPath.isBlank()) {
            System.out.println("REAL_SESSION_SAMPLE=NOT_RUN");
            return;
        }
        final Path source = Path.of(configuredPath);
        assertTrue(!Files.isSymbolicLink(source)
            && Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS),
            "configured real session sample is a regular non-symlink file");
        final byte[] original = Files.readAllBytes(source);
        final String digest = sha256(original);
        final ExternalPsdEditHostProbe.PsdStructureValidation structure =
            ExternalPsdEditHostProbe.inspectPsdStructure(original);
        System.out.println("REAL_SESSION_SAMPLE path=" + source + " bytes=" + original.length
            + " sha256=" + digest + " complete=" + structure.complete()
            + " layers=" + structure.layerCount() + " compositeEnd=" + structure.compositeEnd()
            + " section=" + structure.section() + " reason=" + structure.reason());
        assertTrue(structure.complete(), "retained real session PSD has complete structure");

        final Path root = Files.createTempDirectory("external PSD real session copy ");
        try {
            final Path directory = Files.createDirectory(root.resolve("turboism-psd-real-copy"));
            final Path copy = directory.resolve("external-edit.psd");
            Files.copy(source, copy);
            final ExternalPsdEditHostProbe.FileKeySupplier unavailableKey = attributes -> null;
            final ExternalPsdEditHostProbe.StablePsdSnapshot snapshot =
                ExternalPsdEditHostProbe.awaitSessionFileForTest(
                    root, Set.of(), 5000L, () -> false,
                    millis -> Thread.sleep(Math.min(millis, 5L)), unavailableKey);
            assertArrayEquals(original, snapshot.bytes(),
                "production readiness helper reads the exact real PSD bytes in a temp copy");
            assertEquals("UNAVAILABLE", snapshot.observation().fileKeyStatus(),
                "real PSD copy exercises the null-key readiness path");
            ExternalPsdEditHostProbe.confirmSessionFileForWriteForTest(snapshot, unavailableKey);
            assertArrayEquals(original, Files.readAllBytes(copy),
                "production write precondition is read-only for the real PSD copy");
            System.out.println("REAL_SESSION_SAMPLE_HELPERS=PASS copy=" + copy
                + " structureComplete=" + snapshot.observation().structure().complete());
        } finally {
            deleteTree(root);
        }
    }

    private record RawPsdFixture(byte[] bytes, int firstLayerChannelLengthOffset,
        int compositeOffset) {
    }

    private static RawPsdFixture rawPsd() {
        final PsdBytes record = new PsdBytes();
        record.u32(0);
        record.u32(0);
        record.u32(1);
        record.u32(1);
        record.u16(4);
        int firstLengthOffsetInRecord = -1;
        for (int channel = 0; channel < 4; channel++) {
            record.u16(channel == 3 ? 0xffff : channel);
            if (channel == 0) firstLengthOffsetInRecord = record.size();
            record.u32(3);
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
        extra.u8(1);
        extra.u8('x');
        extra.zeros(2);
        record.u32(extra.size());
        record.bytes(extra.toByteArray());

        final PsdBytes layerInfo = new PsdBytes();
        layerInfo.u16(1);
        layerInfo.bytes(record.toByteArray());
        for (int channel = 0; channel < 4; channel++) {
            layerInfo.u16(0);
            layerInfo.u8(0x20 + channel);
        }
        final PsdBytes layerMask = new PsdBytes();
        layerMask.u32(layerInfo.size());
        layerMask.bytes(layerInfo.toByteArray());
        layerMask.u32(0);

        final PsdBytes file = new PsdBytes();
        file.ascii("8BPS");
        file.u16(1);
        file.zeros(6);
        file.u16(4);
        file.u32(1);
        file.u32(1);
        file.u16(8);
        file.u16(3);
        file.u32(0);
        file.u32(0);
        file.u32(layerMask.size());
        final int layerMaskPayloadStart = file.size();
        file.bytes(layerMask.toByteArray());
        final int compositeOffset = file.size();
        file.u16(0);
        file.u8(0x11);
        file.u8(0x22);
        file.u8(0x33);
        file.u8(0x44);
        final int layerInfoPayloadStart = layerMaskPayloadStart + 4;
        final int firstLengthOffset = layerInfoPayloadStart + 2
            + firstLengthOffsetInRecord;
        return new RawPsdFixture(file.toByteArray(), firstLengthOffset, compositeOffset);
    }

    private static int compositeOffset(final byte[] psd) {
        final ByteBuffer buffer = ByteBuffer.wrap(psd).order(ByteOrder.BIG_ENDIAN);
        buffer.position(26);
        buffer.position(buffer.position() + 4 + buffer.getInt(buffer.position()));
        buffer.position(buffer.position() + 4 + buffer.getInt(buffer.position()));
        buffer.position(buffer.position() + 4 + buffer.getInt(buffer.position()));
        return buffer.position();
    }

    private static ExternalPsdEditHostProbe.SessionFileReadinessException
        expectSessionReadinessFailure(final ThrowingAction action, final String message)
        throws Exception {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (ExternalPsdEditHostProbe.SessionFileReadinessException expected) {
            return expected;
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

    private static void testRawImageRelationDeltaAndNewRawInspection() throws Exception {
        final RawImageId oldRaw = new RawImageId("raw-old");
        final RawImageId newRaw = new RawImageId("raw-new");
        final RawImageId otherRaw = new RawImageId("raw-other");
        final TextureRelationsSnapshot before = rawRelationSnapshot("binding-1", 7,
            oldRaw);
        final TextureRelationsSnapshot unchanged = rawRelationSnapshot("binding-1", 7,
            oldRaw);
        final TextureRelationsSnapshot oneAdded = rawRelationSnapshot("binding-1", 7,
            oldRaw, newRaw);
        final TextureRelationsSnapshot duplicateAdded = rawRelationSnapshot("binding-1", 7,
            oldRaw, newRaw, newRaw);
        final TextureRelationsSnapshot twoAdded = rawRelationSnapshot("binding-1", 7,
            oldRaw, newRaw, otherRaw);

        final var zero = ExternalPsdEditHostProbe.rawImageRelationDelta(before, unchanged);
        assertEquals("ZERO", zero.status().name(), "zero raw candidates are explicit");
        assertEquals(0, zero.candidateCount(), "zero candidate count is recorded");

        final var unique = ExternalPsdEditHostProbe.rawImageRelationDelta(
            before, duplicateAdded);
        assertEquals("UNIQUE", unique.status().name(),
            "duplicate raw entries still identify one new raw");
        assertEquals(List.of(newRaw), unique.addedRawImages(),
            "unique raw candidate is not duplicated");

        final var multiple = ExternalPsdEditHostProbe.rawImageRelationDelta(before, twoAdded);
        assertEquals("MULTIPLE", multiple.status().name(),
            "multiple new raws are ambiguous");
        assertEquals(2, multiple.candidateCount(), "multiple candidate count is explicit");

        final var switched = ExternalPsdEditHostProbe.rawImageRelationDelta(
            before, rawRelationSnapshot("binding-2", 7, oldRaw, newRaw));
        assertEquals("IDENTITY_CHANGED", switched.status().name(),
            "binding changes reject raw attribution");
        assertEquals(List.of("raw-old"), switched.beforeRawIds(),
            "identity change retains the pre-import raw IDs");
        assertEquals(List.of("raw-old", "raw-new"), switched.afterRawIds(),
            "identity change retains the post-import raw IDs");
        assertEquals(1, switched.candidateCount(),
            "identity change records candidates without licensing an export");
        final Properties switchedResult = new Properties();
        ExternalPsdEditHostProbe.recordRawImageRelationDelta(
            switchedResult, "cycle.3.rawRelation", switched);
        assertNull(switchedResult.getProperty("cycle.3.rawRelation.newRawId"),
            "identity-changed candidates are not labeled as an export target");
        final var switchedGeneration = ExternalPsdEditHostProbe.rawImageRelationDelta(
            before, rawRelationSnapshot("binding-1", 8, oldRaw, newRaw));
        assertEquals("IDENTITY_CHANGED", switchedGeneration.status().name(),
            "generation changes reject raw attribution");
        final var unavailable = ExternalPsdEditHostProbe.rawImageRelationDelta(
            before, TextureRelationsSnapshot.unavailable());
        assertEquals("UNAVAILABLE", unavailable.status().name(),
            "unavailable relations reject raw attribution");

        final Properties result = new Properties();
        ExternalPsdEditHostProbe.recordRawImageRelationSnapshot(
            result, "cycle.1.rawRelation.before", before, "");
        assertEquals("AVAILABLE", result.getProperty("cycle.1.rawRelation.before.status"),
            "available relation snapshot is recorded");
        assertEquals("binding-1",
            result.getProperty("cycle.1.rawRelation.before.binding"),
            "relation binding is recorded");
        assertEquals("7",
            result.getProperty("cycle.1.rawRelation.before.generation"),
            "relation generation is recorded");
        assertEquals("[raw-old]",
            result.getProperty("cycle.1.rawRelation.before.rawIds"),
            "relation raw IDs are recorded");
        ExternalPsdEditHostProbe.recordRawImageRelationDelta(
            result, "cycle.1.rawRelation", unique);
        assertEquals("UNIQUE", result.getProperty("cycle.1.rawRelation.status"),
            "raw delta status is recorded");
        assertEquals("1", result.getProperty("cycle.1.rawRelation.candidateCount"),
            "raw delta count is recorded");
        assertEquals("[raw-new]", result.getProperty("cycle.1.rawRelation.addedRawIds"),
            "raw delta ID is recorded without text-key guessing");
        assertEquals("[raw-old, raw-new]",
            result.getProperty("cycle.1.rawRelation.afterRawIds"),
            "raw relation diagnostics record a de-duplicated ID set");
        ExternalPsdEditHostProbe.recordRawImageRelationDelta(
            result, "cycle.0.rawRelation", zero);
        ExternalPsdEditHostProbe.recordRawImageRelationDelta(
            result, "cycle.2.rawRelation", multiple);
        assertEquals("ZERO", result.getProperty("cycle.0.rawRelation.status"),
            "zero raw status is recorded");
        assertEquals("MULTIPLE", result.getProperty("cycle.2.rawRelation.status"),
            "multiple raw status is recorded");

        final Properties zeroGate = new Properties();
        assertTrue(ExternalPsdEditHostProbe.requireRawImageDeltaForCycle(
            zeroGate, "cycle.0.", zero, newRaw).isEmpty(),
            "ZERO delta remains diagnostic-only");
        assertEquals("NOT_ATTEMPTED", zeroGate.getProperty("cycle.0.raw.new.status"),
            "ZERO delta keeps the historical NOT_ATTEMPTED raw export status");

        final Properties lineageGate = new Properties();
        assertEquals(newRaw, ExternalPsdEditHostProbe.requireRawImageDeltaForCycle(
            lineageGate, "cycle.ab.", unique, newRaw).orElseThrow(),
            "A→B unique candidate is accepted only for explicit after raw");
        final var nextUnique = ExternalPsdEditHostProbe.rawImageRelationDelta(
            rawRelationSnapshot("binding-1", 7, oldRaw, newRaw),
            rawRelationSnapshot("binding-1", 7, oldRaw, newRaw, otherRaw));
        assertEquals(otherRaw, ExternalPsdEditHostProbe.requireRawImageDeltaForCycle(
            lineageGate, "cycle.bc.", nextUnique, otherRaw).orElseThrow(),
            "B→C unique candidate remains accepted for explicit after raw");

        final Properties multipleGate = new Properties();
        expectIllegalStateChecked(() -> ExternalPsdEditHostProbe.requireRawImageDeltaForCycle(
            multipleGate, "cycle.multiple.", multiple, newRaw),
            "MULTIPLE delta remains fail-closed");
        assertEquals("REJECTED", multipleGate.getProperty("cycle.multiple.raw.new.status"),
            "MULTIPLE delta records rejection before aborting");

        final Properties identityGate = new Properties();
        expectIllegalStateChecked(() -> ExternalPsdEditHostProbe.requireRawImageDeltaForCycle(
            identityGate, "cycle.identity.", switched, newRaw),
            "IDENTITY_CHANGED delta remains fail-closed");
        assertEquals("REJECTED", identityGate.getProperty("cycle.identity.raw.new.status"),
            "IDENTITY_CHANGED delta records rejection before aborting");

        final Properties unavailableGate = new Properties();
        expectIllegalStateChecked(() -> ExternalPsdEditHostProbe.requireRawImageDeltaForCycle(
            unavailableGate, "cycle.unavailable.", unavailable, newRaw),
            "UNAVAILABLE delta remains fail-closed");
        assertEquals("REJECTED", unavailableGate.getProperty(
            "cycle.unavailable.raw.new.status"),
            "UNAVAILABLE delta records rejection before aborting");

        final Properties mismatchGate = new Properties();
        expectIllegalStateChecked(() -> ExternalPsdEditHostProbe.requireRawImageDeltaForCycle(
            mismatchGate, "cycle.mismatch.", unique, otherRaw),
            "unique candidate different from explicit after remains fail-closed");
        assertEquals("REJECTED", mismatchGate.getProperty("cycle.mismatch.raw.new.status"),
            "mismatched unique candidate records rejection before aborting");

        final AtomicInteger exports = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();
        final byte[] validPsd = validationPsd();
        final var inspected = ExternalPsdEditHostProbe.inspectUniqueNewRaw(
            result, "cycle.1.raw.new", oldRaw, unique, candidate -> {
                exports.incrementAndGet();
                assertEquals(newRaw, candidate, "only the unique new raw is exported");
                return new ExternalPsdEditHostProbe.RawImageExportHandle() {
                    @Override public RawImageId rawId() { return candidate; }
                    @Override public byte[] bytes() { return validPsd; }
                    @Override public PsdFileOperationResult stop() {
                        stops.incrementAndGet();
                        return new PsdFileOperationResult(
                            PsdFileOperationResult.Status.STOPPED, "stopped");
                    }
                };
            });
        assertEquals(1, exports.get(), "unique new raw is exported once");
        assertEquals(1, stops.get(), "new raw handle is stopped immediately");
        assertEquals(targetFingerprint(validPsd), inspected.sha256(),
            "new raw layer 6 RGB is decoded");
        assertEquals("raw-new", result.getProperty("cycle.1.raw.new.id"),
            "new raw identity is recorded");
        assertEquals(targetFingerprint(validPsd), result.getProperty(
            "cycle.1.raw.new.rgb.sha256"), "new raw RGB is recorded");
        assertEquals(sha256(validPsd), result.getProperty("cycle.1.raw.new.sha256"),
            "new raw full bytes are recorded");
        assertEquals("STOPPED", result.getProperty("cycle.1.raw.new.stopStatus"),
            "new raw stop status is recorded");

        final Properties writeResult = new Properties();
        ExternalPsdEditHostProbe.recordCycleWrittenPsd(
            writeResult, "cycle.1.", validPsd);
        assertEquals(Integer.toString(validPsd.length),
            writeResult.getProperty("cycle.1.write.bytes"),
            "written PSD byte length is recorded");
        assertEquals(targetFingerprint(validPsd),
            writeResult.getProperty("cycle.1.write.targetRgb.sha256"),
            "written PSD target RGB is recorded");
        assertEquals(sha256(validPsd), writeResult.getProperty("cycle.1.write.sha256"),
            "written PSD full bytes are recorded");

        final AtomicInteger failedStops = new AtomicInteger();
        final Properties invalidResult = new Properties();
        expectIllegalStateChecked(() -> ExternalPsdEditHostProbe.inspectUniqueNewRaw(
            invalidResult, "cycle.2.raw.new", oldRaw, unique, candidate -> {
                return new ExternalPsdEditHostProbe.RawImageExportHandle() {
                    @Override public RawImageId rawId() { return candidate; }
                    @Override public byte[] bytes() { return "not-a-psd".getBytes(
                        StandardCharsets.UTF_8); }
                    @Override public PsdFileOperationResult stop() {
                        failedStops.incrementAndGet();
                        return new PsdFileOperationResult(
                            PsdFileOperationResult.Status.STOPPED, "stopped");
                    }
                };
            }), "invalid new raw export is rejected");
        assertEquals(1, failedStops.get(),
            "invalid new raw evidence still stops its handle");
        assertEquals("FAILED", invalidResult.getProperty("cycle.2.raw.new.status"),
            "invalid new raw evidence remains an explicit failure");

        final Properties stopFailureResult = new Properties();
        expectIllegalStateChecked(() -> ExternalPsdEditHostProbe.inspectUniqueNewRaw(
            stopFailureResult, "cycle.2.raw.new", oldRaw, unique, candidate ->
                new ExternalPsdEditHostProbe.RawImageExportHandle() {
                    @Override public RawImageId rawId() { return candidate; }
                    @Override public byte[] bytes() { return validPsd; }
                    @Override public PsdFileOperationResult stop() {
                        return new PsdFileOperationResult(
                            PsdFileOperationResult.Status.FAILED, "stop failed");
                    }
                }), "new raw stop failure is not hidden");
        assertEquals("FAILED", stopFailureResult.getProperty("cycle.2.raw.new.stopStatus"),
            "new raw stop failure is recorded");

        expectIllegalStateChecked(() -> ExternalPsdEditHostProbe.inspectUniqueNewRaw(
            new Properties(), "cycle.3.raw.new", oldRaw, multiple,
            candidate -> { throw new AssertionError("ambiguous raw must not be exported"); }),
            "ambiguous new raw is not guessed or exported");
    }

    private static void testCoordinatedNewRawExportIdentityGate() throws Exception {
        final RawImageId oldRaw = new RawImageId("raw-old");
        final RawImageId newRaw = new RawImageId("raw-new");
        final TextureRelationsSnapshot beforeRelations = rawRelationSnapshot(
            "binding-1", 7, oldRaw);
        final ExternalPsdEditHostProbe.RawImageRelationDelta unique =
            ExternalPsdEditHostProbe.rawImageRelationDelta(
            beforeRelations, rawRelationSnapshot("binding-1", 7, oldRaw, newRaw));
        final ExternalPsdEditHostProbe.TargetIdentity expected =
            new ExternalPsdEditHostProbe.TargetIdentity(
                "document-1", "model-1", "binding-1", 7L, "model-image-1",
                "art-mesh-1", newRaw.value());
        final byte[] validPsd = validationPsd();

        final RawExportFixture stable = new RawExportFixture(
            rawExportObservation("document-1", "model-1", "binding-1", "model-image-1",
                newRaw, newRaw, 7, 1, 1),
            rawExportObservation("document-1", "model-1", "binding-1", "model-image-1",
                newRaw, newRaw, 7, 1, 1), validPsd);
        final Properties stableResult = new Properties();
        final var inspected = inspectWithCoordinatedExport(
            stableResult, "cycle.4.raw.new", oldRaw, unique, expected, stable);
        assertEquals(targetFingerprint(validPsd), inspected.sha256(),
            "stable coordinated export reaches the real new-raw decoder");
        assertEquals(1, stable.exportCalls,
            "stable coordinated export invokes the public export once");
        assertEquals(1, stable.handleStopCalls,
            "successful coordinated export is stopped by the existing inspection finally");
        assertEquals(0, stable.coordinatorStopCalls,
            "successful coordinated export does not take the failure stop path");
        assertEquals("AVAILABLE", stableResult.getProperty(
            "cycle.4.raw.new.coordination.status"),
            "stable target identity and candidate license the export");
        assertEquals("1", stableResult.getProperty(
            "cycle.4.raw.new.coordination.before.candidateCount"),
            "preflight records one observed candidate");
        assertEquals("1", stableResult.getProperty(
            "cycle.4.raw.new.coordination.after.candidateCount"),
            "postflight records one observed candidate");
        assertEquals("AVAILABLE", stableResult.getProperty("cycle.4.raw.new.status"),
            "only a fully coordinated export makes RGB available");
        assertEquals("raw-new", stable.before.currentRawId(),
            "new-raw coordination is anchored to the current lineage raw");

        final RawExportFixture switchedDuringExport = new RawExportFixture(
            rawExportObservation("document-1", "model-1", "binding-1", "model-image-1",
                newRaw, newRaw, 7, 1, 1),
            rawExportObservation("document-2", "model-2", "binding-2", "model-image-2",
                newRaw, newRaw, 8, 1, 1), validPsd);
        final Properties switchedResult = assertCoordinatedUnavailable(switchedDuringExport,
            "target document switches during the public export", oldRaw, newRaw, unique, expected,
            "document switch cannot produce available RGB", 1);
        assertContains(switchedResult.getProperty("cycle.gated.raw.new.coordination.diagnostic"),
            "document identity changed", "document switch reason is retained");
        assertContains(switchedResult.getProperty("cycle.gated.raw.new.coordination.diagnostic"),
            "model identity changed", "model switch reason is retained");
        assertContains(switchedResult.getProperty("cycle.gated.raw.new.coordination.diagnostic"),
            "binding identity changed", "binding switch reason is retained");

        final RawExportFixture switchedBeforeExport = new RawExportFixture(
            rawExportObservation("document-2", "model-2", "binding-2", "model-image-2",
                newRaw, newRaw, 8, 1, 1),
            rawExportObservation("document-1", "model-1", "binding-1", "model-image-1",
                newRaw, newRaw, 7, 1, 1), validPsd);
        assertCoordinatedUnavailable(switchedBeforeExport,
            "target document/model/binding switches before the public export", oldRaw, newRaw,
            unique, expected, "preflight target switch cannot invoke export", 0);

        final RawExportFixture candidateDisappears = new RawExportFixture(
            rawExportObservation("document-1", "model-1", "binding-1", "model-image-1",
                newRaw, newRaw, 7, 1, 1),
            rawExportObservation("document-1", "model-1", "binding-1", "model-image-1",
                newRaw, newRaw, 7, 1, 0), validPsd);
        assertCoordinatedUnavailable(candidateDisappears,
            "candidate disappears after the public export", oldRaw, newRaw, unique, expected,
            "candidate disappearance cannot produce available RGB", 1);

        final RawExportFixture candidateBecomesAmbiguous = new RawExportFixture(
            rawExportObservation("document-1", "model-1", "binding-1", "model-image-1",
                newRaw, newRaw, 7, 1, 1),
            rawExportObservation("document-1", "model-1", "binding-1", "model-image-1",
                newRaw, newRaw, 7, 1, 2), validPsd);
        assertCoordinatedUnavailable(candidateBecomesAmbiguous,
            "candidate becomes non-unique after the public export", oldRaw, newRaw, unique,
            expected, "candidate ambiguity cannot produce available RGB", 1);

        final RawExportFixture preMissing = new RawExportFixture(
            rawExportObservation("document-1", "model-1", "binding-1", "model-image-1",
                newRaw, newRaw, 7, 1, 0),
            rawExportObservation("document-1", "model-1", "binding-1", "model-image-1",
                newRaw, newRaw, 7, 1, 1), validPsd);
        assertCoordinatedUnavailable(preMissing,
            "candidate is absent before the public export", oldRaw, newRaw, unique, expected,
            "missing preflight candidate cannot invoke export", 0);
        assertEquals(0, preMissing.exportCalls,
            "missing preflight candidate is rejected before export invocation");
        assertEquals(0, preMissing.coordinatorStopCalls,
            "no handle exists when preflight rejects the candidate");

        final RawExportFixture preDuplicate = new RawExportFixture(
            rawExportObservation("document-1", "model-1", "binding-1", "model-image-1",
                newRaw, newRaw, 7, 1, 2),
            rawExportObservation("document-1", "model-1", "binding-1", "model-image-1",
                newRaw, newRaw, 7, 1, 1), validPsd);
        assertCoordinatedUnavailable(preDuplicate,
            "candidate is duplicated before the public export", oldRaw, newRaw, unique, expected,
            "duplicate preflight candidate cannot invoke export", 0);
        assertEquals(0, preDuplicate.exportCalls,
            "duplicate preflight candidate is rejected before export invocation");
    }

    private static Properties assertCoordinatedUnavailable(final RawExportFixture fixture,
        final String label, final RawImageId oldRaw, final RawImageId newRaw,
        final ExternalPsdEditHostProbe.RawImageRelationDelta delta,
        final ExternalPsdEditHostProbe.TargetIdentity expected, final String assertion,
        final int expectedCoordinatorStops)
        throws Exception {
        final Properties result = new Properties();
        expectIllegalStateChecked(() -> inspectWithCoordinatedExport(
            result, "cycle.gated.raw.new", oldRaw, delta, expected, fixture), label);
        assertEquals("UNAVAILABLE", result.getProperty("cycle.gated.raw.new.status"), assertion);
        assertEquals("UNAVAILABLE", result.getProperty("cycle.gated.raw.new.rgb.status"),
            "rejected coordinated export never exposes decoded RGB");
        assertEquals(expectedCoordinatorStops, fixture.coordinatorStopCalls,
            expectedCoordinatorStops == 0
                ? "preflight rejection does not claim a nonexistent handle"
                : "a handle created before postflight rejection is stopped");
        assertEquals(0, fixture.handleStopCalls,
            "the caller cannot stop a handle withheld by failed coordination");
        assertEquals(expectedCoordinatorStops == 0 ? 0 : 1, fixture.exportCalls,
            expectedCoordinatorStops == 0
                ? "preflight rejection prevents the public export"
                : "postflight rejection occurs after a completed export handle exists");
        if (expectedCoordinatorStops == 1) {
            assertEquals("STOPPED", result.getProperty("cycle.gated.raw.new.stopStatus"),
                "postflight rejection records the coordinated handle stop");
        }
        assertEquals(newRaw.value(), result.getProperty("cycle.gated.raw.new.candidateRawId"),
            "the candidate identity remains explicit in coordination diagnostics");
        return result;
    }

    /**
     * Exercises the production APPLIED lineage gate with a real A→B→C relation sequence.  The
     * same stable document/model/binding/generation/model-image/ArtMesh anchor is retained while
     * only the current raw moves; every identity or revision mismatch is rejected.
     */
    private static void testAppliedTargetLineageGate() {
        final RawImageId rawA = new RawImageId("lineage-A");
        final RawImageId rawB = new RawImageId("lineage-B");
        final RawImageId rawC = new RawImageId("lineage-C");
        final ExternalPsdEditHostProbe.TargetIdentity targetA =
            new ExternalPsdEditHostProbe.TargetIdentity(
                "document-lineage", "model-lineage", "binding-lineage", 7L,
                "model-image-lineage", "art-mesh-lineage", rawA.value());
        final ExternalPsdEditHostProbe.TargetIdentity targetB =
            new ExternalPsdEditHostProbe.TargetIdentity(
                "document-lineage", "model-lineage", "binding-lineage", 7L,
                "model-image-lineage", "art-mesh-lineage", rawB.value());
        final ExternalPsdEditHostProbe.TargetIdentity targetC =
            new ExternalPsdEditHostProbe.TargetIdentity(
                "document-lineage", "model-lineage", "binding-lineage", 7L,
                "model-image-lineage", "art-mesh-lineage", rawC.value());
        final PsdFileRevision revisionAB = new PsdFileRevision() { };
        final PsdFileRevision revisionBC = new PsdFileRevision() { };
        final PsdReplaceResult appliedAB = new PsdReplaceResult(
            PsdReplaceResult.Status.APPLIED, "A to B", rawA, Optional.of(rawB),
            Optional.of(revisionAB), Optional.empty());
        final PsdReplaceResult appliedBC = new PsdReplaceResult(
            PsdReplaceResult.Status.APPLIED, "B to C", rawB, Optional.of(rawC),
            Optional.of(revisionBC), Optional.empty());

        final ExternalPsdEditHostProbe.TargetIdentity acceptedB =
            ExternalPsdEditHostProbe.acceptAppliedTargetLineage(
                targetA, revisionAB, appliedAB, lineageRelationSnapshot(
                    "binding-lineage", 7L, "model-image-lineage", "art-mesh-lineage",
                    rawB), targetB);
        assertEquals(rawB.value(), acceptedB.rawId(),
            "APPLIED A→B advances the current lineage raw");
        final ExternalPsdEditHostProbe.TargetIdentity acceptedC =
            ExternalPsdEditHostProbe.acceptAppliedTargetLineage(
                acceptedB, revisionBC, appliedBC, lineageRelationSnapshot(
                    "binding-lineage", 7L, "model-image-lineage", "art-mesh-lineage",
                    rawC), targetC);
        assertEquals(rawC.value(), acceptedC.rawId(),
            "APPLIED B→C advances from the prior current raw, not initial raw");

        expectIllegalState(() -> ExternalPsdEditHostProbe.acceptAppliedTargetLineage(
            targetA, revisionAB, appliedAB, lineageRelationSnapshot(
                "binding-lineage", 7L, "model-image-lineage", "art-mesh-lineage", rawB),
            targetC), "wrong after raw is rejected");
        expectIllegalState(() -> ExternalPsdEditHostProbe.acceptAppliedTargetLineage(
            targetA, revisionAB, appliedAB, lineageRelationSnapshot(
                "binding-lineage", 7L, "model-image-lineage", "art-mesh-lineage", rawA),
            targetB), "wrong current raw is rejected");
        expectIllegalState(() -> ExternalPsdEditHostProbe.acceptAppliedTargetLineage(
            targetA, revisionAB, appliedAB, lineageRelationSnapshotWithRaws(
                "binding-lineage", 7L, "model-image-lineage", "art-mesh-lineage", rawB,
                List.of(rawA, rawC)),
            targetB), "current after raw absent from relations is rejected");
        expectIllegalState(() -> ExternalPsdEditHostProbe.acceptAppliedTargetLineage(
            targetA, revisionAB, appliedAB, lineageRelationSnapshot(
                "binding-lineage", 7L, "model-image-lineage", "art-mesh-lineage", rawB),
            new ExternalPsdEditHostProbe.TargetIdentity(
                "other-document", "model-lineage", "binding-lineage", 7L,
                "model-image-lineage", "art-mesh-lineage", rawB.value())),
            "document change is rejected");
        expectIllegalState(() -> ExternalPsdEditHostProbe.acceptAppliedTargetLineage(
            targetA, revisionAB, appliedAB, lineageRelationSnapshot(
                "other-binding", 7L, "model-image-lineage", "art-mesh-lineage", rawB),
            targetB), "binding change is rejected");
        expectIllegalState(() -> ExternalPsdEditHostProbe.acceptAppliedTargetLineage(
            targetA, revisionAB, appliedAB, lineageRelationSnapshot(
                "binding-lineage", 8L, "model-image-lineage", "art-mesh-lineage", rawB),
            targetB), "after-relation generation change is rejected");
        expectIllegalState(() -> ExternalPsdEditHostProbe.acceptAppliedTargetLineage(
            targetA, revisionAB, appliedAB, lineageRelationSnapshot(
                "binding-lineage", 7L, "model-image-lineage", "art-mesh-lineage", rawB),
            new ExternalPsdEditHostProbe.TargetIdentity(
                "document-lineage", "model-lineage", "binding-lineage", 7L,
                "other-model-image", "art-mesh-lineage", rawB.value())),
            "model-image change is rejected");
        expectIllegalState(() -> ExternalPsdEditHostProbe.acceptAppliedTargetLineage(
            targetA, revisionAB, appliedAB, lineageRelationSnapshot(
                "binding-lineage", 7L, "model-image-lineage", "other-art-mesh", rawB),
            targetB), "after-relation ArtMesh change is rejected");
        expectIllegalState(() -> ExternalPsdEditHostProbe.acceptAppliedTargetLineage(
            targetA, revisionBC, appliedAB, lineageRelationSnapshot(
                "binding-lineage", 7L, "model-image-lineage", "art-mesh-lineage", rawB),
            targetB), "consumed revision mismatch is rejected");

        // Undo must target B (the last replacement-before target), while redo targets C.
        assertTrue(ExternalPsdEditHostProbe.targetIdentityMatches(targetB,
            new ExternalPsdEditHostProbe.TargetIdentity(
                "document-lineage", "model-lineage", "binding-lineage", 7L,
                "model-image-lineage", "art-mesh-lineage", rawB.value())),
            "Undo target retains the stable anchor and last-before raw B");
        assertTrue(ExternalPsdEditHostProbe.targetIdentityMatches(targetC,
            acceptedC), "Redo target retains the applied raw C");
    }

    private static TextureRelationsSnapshot lineageRelationSnapshot(final String binding,
        final long generation, final String modelImageValue, final String artMeshValue,
        final RawImageId currentRaw) {
        return lineageRelationSnapshotWithRaws(binding, generation, modelImageValue, artMeshValue,
            currentRaw, List.of(
                new RawImageId("lineage-A"), new RawImageId("lineage-B"),
                new RawImageId("lineage-C")));
    }

    private static TextureRelationsSnapshot lineageRelationSnapshotWithRaws(
        final String binding, final long generation, final String modelImageValue,
        final String artMeshValue, final RawImageId currentRaw, final List<RawImageId> raws) {
        final ModelImageId modelImageId = new ModelImageId(modelImageValue);
        final ArtMeshId artMeshId = new ArtMeshId(artMeshValue);
        final ModelImageEntry entry = new ModelImageEntry() {
            @Override public ModelImageId id() { return modelImageId; }
            @Override public String name() { return modelImageValue; }
            @Override public int width() { return 1000; }
            @Override public int height() { return 1000; }
        };
        final List<RawImageDetails> details = raws.stream().map(raw -> {
            final RawTexture texture = new RawTexture() {
                @Override public RawImageId id() { return raw; }
                @Override public String name() { return raw.value(); }
                @Override public int width() { return 1000; }
                @Override public int height() { return 1000; }
            };
            return new RawImageDetails(texture, RawImageDetails.SourceKind.PSD,
                List.of(), false, Optional.empty(), Optional.empty(), Optional.empty());
        }).toList();
        final ModelImageRelation relation = new ModelImageRelation(
            modelImageId, entry, raws, Optional.of(currentRaw), Map.of(), List.of(artMeshId));
        final ArtMeshTextureInputs mesh = new ArtMeshTextureInputs(
            artMeshId, List.of(TextureInputBinding.modelImage(modelImageId)), OptionalInt.of(0));
        return new TextureRelationsSnapshot(TextureRelationsSnapshot.Availability.AVAILABLE,
            binding, generation, 1L, details, List.of(relation), List.of(), List.of(mesh));
    }

    private static PsdValidationContent.Fingerprint inspectWithCoordinatedExport(
        final Properties result, final String prefix, final RawImageId oldRaw,
        final ExternalPsdEditHostProbe.RawImageRelationDelta delta,
        final ExternalPsdEditHostProbe.TargetIdentity expected, final RawExportFixture fixture)
        throws Exception {
        return ExternalPsdEditHostProbe.inspectUniqueNewRaw(
            result, prefix, oldRaw, delta, candidate ->
                ExternalPsdEditHostProbe.coordinateNewRawExport(
                    result, prefix, expected, candidate,
                    fixture::startOnEdt, fixture::observeAfterOnEdt, fixture::stopCoordinated));
    }

    private static ExternalPsdEditHostProbe.RawExportObservation rawExportObservation(
        final String documentId, final String modelId, final String binding,
        final String modelImageId, final RawImageId candidate, final RawImageId currentRaw,
        final long generation, final int modelImageCount, final int candidateCount) {
        return new ExternalPsdEditHostProbe.RawExportObservation(
            documentId, modelId, binding, modelImageId, "art-mesh-1", candidate.value(),
            currentRaw.value(), generation, modelImageCount, candidateCount, true, "");
    }

    private static final class RawExportFixture {
        private final ExternalPsdEditHostProbe.RawExportObservation before;
        private final ExternalPsdEditHostProbe.RawExportObservation postExport;
        private ExternalPsdEditHostProbe.RawExportObservation after;
        private final byte[] bytes;
        private int exportCalls;
        private int coordinatorStopCalls;
        private int handleStopCalls;

        private RawExportFixture(final ExternalPsdEditHostProbe.RawExportObservation before,
            final ExternalPsdEditHostProbe.RawExportObservation after, final byte[] bytes) {
            this.before = before;
            this.postExport = after;
            this.after = before;
            this.bytes = bytes;
        }

        private ExternalPsdEditHostProbe.RawExportStarted startOnEdt(final RawImageId candidate,
            final ExternalPsdEditHostProbe.RawExportPreflight preflight) throws Exception {
            final AtomicReference<ExternalPsdEditHostProbe.RawExportStarted> started =
                new AtomicReference<>();
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final Runnable action = () -> {
                try {
                    assertTrue(SwingUtilities.isEventDispatchThread(),
                        "raw export preflight and invocation run on the EDT");
                    preflight.verify(before);
                    exportCalls++;
                    after = postExport;
                    final ExternalPsdEditHostProbe.RawImageExportHandle handle =
                        new ExternalPsdEditHostProbe.RawImageExportHandle() {
                            @Override public RawImageId rawId() { return candidate; }
                            @Override public byte[] bytes() { return bytes; }
                            @Override public PsdFileOperationResult stop() {
                                handleStopCalls++;
                                return new PsdFileOperationResult(
                                    PsdFileOperationResult.Status.STOPPED, "stopped");
                            }
                        };
                    started.set(new ExternalPsdEditHostProbe.RawExportStarted(before, handle));
                } catch (Throwable error) {
                    failure.set(error);
                }
            };
            SwingUtilities.invokeAndWait(action);
            if (failure.get() != null) throw new IllegalStateException(
                "raw export fixture start failed", failure.get());
            return started.get();
        }

        private ExternalPsdEditHostProbe.RawExportObservation observeAfterOnEdt(
            final RawImageId candidate) throws Exception {
            final AtomicReference<ExternalPsdEditHostProbe.RawExportObservation> observed =
                new AtomicReference<>();
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    assertTrue(SwingUtilities.isEventDispatchThread(),
                        "raw export postflight runs on the EDT");
                    observed.set(after);
                } catch (Throwable error) {
                    failure.set(error);
                }
            });
            if (failure.get() != null) throw new IllegalStateException(
                "raw export fixture postflight failed", failure.get());
            return observed.get();
        }

        private PsdFileOperationResult stopCoordinated(
            final ExternalPsdEditHostProbe.RawImageExportHandle handle) {
            coordinatorStopCalls++;
            return new PsdFileOperationResult(
                PsdFileOperationResult.Status.STOPPED, "coordinated stop");
        }
    }

    private static TextureRelationsSnapshot rawRelationSnapshot(final String binding,
        final long generation, final RawImageId... rawIds) {
        final List<RawImageDetails> details = new ArrayList<>();
        for (final RawImageId raw : rawIds) {
            final RawTexture texture = new RawTexture() {
                @Override public RawImageId id() { return raw; }
                @Override public String name() { return raw.value(); }
                @Override public int width() { return 1000; }
                @Override public int height() { return 1000; }
            };
            details.add(new RawImageDetails(texture, RawImageDetails.SourceKind.PSD,
                List.of(), false, Optional.empty(), Optional.empty(), Optional.empty()));
        }
        return new TextureRelationsSnapshot(
            TextureRelationsSnapshot.Availability.AVAILABLE,
            binding,
            generation,
            1,
            details,
            List.of(),
            List.of(),
            List.of());
    }

    private static void testDiagnosticObservationBoundaries() {
        final PsdValidationContent.Bounds bounds = new PsdValidationContent.Bounds(
            450, 450, 550, 550);
        final PsdValidationContent.Fingerprint fingerprint =
            new PsdValidationContent.Fingerprint(
                "a".repeat(64), bounds, 100, 100, List.of(0, 1, 2));
        final ExternalPsdEditHostProbe.DiagnosticObservation observation =
            ExternalPsdEditHostProbe.DiagnosticObservation.available(
                "raw-1", "model-1", "selector-raw-1=[layer-6]",
                "document-1/layer-6/name=layer/artMeshIds=[ArtMesh4]", fingerprint);
        final Properties result = new Properties();
        ExternalPsdEditHostProbe.recordDiagnosticObservation(
            result, "persist.observation.baseline", observation);
        assertEquals("AVAILABLE", result.getProperty("persist.observation.baseline.status"),
            "available boundary is recorded");
        assertEquals("raw-1", result.getProperty(
            "persist.observation.baseline.relations.rawIds"), "raw IDs are recorded");
        assertEquals("model-1", result.getProperty(
            "persist.observation.baseline.relations.modelImageIds"),
            "model-image IDs are recorded");
        assertEquals("selector-raw-1=[layer-6]", result.getProperty(
            "persist.observation.baseline.selectorProjection"),
            "selector projection is recorded separately");
        assertContains(result.getProperty("persist.observation.baseline.livePsd"),
            "layer-6", "live PSD layer evidence is recorded separately");
        assertEquals("a".repeat(64), result.getProperty(
            "persist.observation.baseline.freshNativeRgb.sha256"),
            "fresh native RGB fingerprint is recorded");

        final Properties unavailable = new Properties();
        ExternalPsdEditHostProbe.recordDiagnosticObservation(
            unavailable, "persist.observation.importCompletion",
            ExternalPsdEditHostProbe.DiagnosticObservation.unavailable(
                "psdDocuments unavailable"));
        assertEquals("UNAVAILABLE", unavailable.getProperty(
            "persist.observation.importCompletion.status"),
            "unavailable public observation is explicit");
        assertEquals("unavailable", unavailable.getProperty(
            "persist.observation.importCompletion.freshNativeRgb.status"),
            "unavailable observation cannot masquerade as pixel evidence");

        final RawImageId raw = new RawImageId("raw-1");
        final PsdReplaceResult applied = new PsdReplaceResult(
            PsdReplaceResult.Status.APPLIED, "public completion", raw,
            java.util.Optional.of(raw), java.util.Optional.of(new PsdFileRevision() { }),
            java.util.Optional.empty());
        ExternalPsdEditHostProbe.recordImportCompletion(
            result, "cycle.1", applied);
        assertEquals("true", result.getProperty("cycle.1.importCompletion.observed"),
            "public import completion is recorded");
        assertEquals("UNAVAILABLE", result.getProperty(
            "cycle.1.importCompletion.nativeReturn.observation"),
            "underlying native return is not falsely claimed");
    }

    private static void testDiagnosticTargetBinding() {
        final PsdValidationContent.Bounds bounds = new PsdValidationContent.Bounds(
            450, 450, 550, 550);
        final PsdValidationContent.Fingerprint fingerprint =
            new PsdValidationContent.Fingerprint(
                "a".repeat(64), bounds, 100, 100, List.of(0, 1, 2));
        final ExternalPsdEditHostProbe.TargetIdentity expected =
            new ExternalPsdEditHostProbe.TargetIdentity(
                "document-1", "model-1", "binding-1", 7L, "model-image-1",
                "art-mesh-1", "raw-1");
        final ExternalPsdEditHostProbe.TargetIdentity same =
            new ExternalPsdEditHostProbe.TargetIdentity(
                "document-1", "model-1", "binding-1", 7L, "model-image-1",
                "art-mesh-1", "raw-1");
        final ExternalPsdEditHostProbe.TargetIdentity switchedModel =
            new ExternalPsdEditHostProbe.TargetIdentity(
                "document-1", "model-2", "binding-1", 7L, "model-image-1",
                "art-mesh-1", "raw-1");
        final ExternalPsdEditHostProbe.TargetIdentity switchedDocument =
            new ExternalPsdEditHostProbe.TargetIdentity(
                "document-2", "model-1", "binding-1", 7L, "model-image-1",
                "art-mesh-1", "raw-1");
        final ExternalPsdEditHostProbe.TargetIdentity switchedBinding =
            new ExternalPsdEditHostProbe.TargetIdentity(
                "document-1", "model-1", "binding-2", 7L, "model-image-1",
                "art-mesh-1", "raw-1");
        final ExternalPsdEditHostProbe.TargetIdentity switchedRaw =
            new ExternalPsdEditHostProbe.TargetIdentity(
                "document-1", "model-1", "binding-1", 7L, "model-image-1",
                "art-mesh-1", "raw-2");

        assertTrue(ExternalPsdEditHostProbe.targetIdentityMatches(expected, same),
            "the captured target identity matches itself");
        assertEquals("AVAILABLE", ExternalPsdEditHostProbe.diagnosticStatusForTarget(
            expected, same, true, true, fingerprint).name(),
            "a complete observation for the bound target is available");
        for (final var mismatch : List.of(
            switchedModel, switchedDocument, switchedBinding, switchedRaw)) {
            assertTrue(!ExternalPsdEditHostProbe.targetIdentityMatches(expected, mismatch),
                "target identity mismatch is detected: " + mismatch);
            assertEquals("UNAVAILABLE", ExternalPsdEditHostProbe.diagnosticStatusForTarget(
                expected, mismatch, true, true, fingerprint).name(),
                "target identity mismatch cannot be combined with an available observation");
        }

        final ExternalPsdEditHostProbe.TargetIdentity legacyIncomplete =
            new ExternalPsdEditHostProbe.TargetIdentity(
                "document-1", "model-1", "binding-1", "model-image-1", "raw-1");
        assertTrue(!ExternalPsdEditHostProbe.targetIdentityMatches(
            legacyIncomplete, legacyIncomplete),
            "legacy five-field identity is rejected fail-closed");
        assertTrue(!ExternalPsdEditHostProbe.stableTargetIdentityMatches(
            legacyIncomplete, legacyIncomplete),
            "legacy five-field identity cannot satisfy the stable anchor");
        assertEquals("UNAVAILABLE", ExternalPsdEditHostProbe.diagnosticStatusForTarget(
            legacyIncomplete, legacyIncomplete, true, true, fingerprint).name(),
            "legacy five-field identity remains unavailable diagnostic evidence");

        final ExternalPsdEditHostProbe.DiagnosticObservation observation =
            new ExternalPsdEditHostProbe.DiagnosticObservation(
                ExternalPsdEditHostProbe.DiagnosticStatus.AVAILABLE,
                "", "[raw-1]", "[model-image-1]", "selector", "live",
                true, true, fingerprint, same, true, 11L, 22L, 33L, 44L);
        final Properties result = new Properties();
        ExternalPsdEditHostProbe.recordDiagnosticObservation(
            result, "persist.observation.bound", observation);
        assertEquals("true", result.getProperty(
            "persist.observation.bound.target.identityVerified"),
            "target verification is recorded");
        assertEquals("document-1", result.getProperty(
            "persist.observation.bound.target.observed.documentId"),
            "observed document identity is recorded");
        assertEquals("22", result.getProperty(
            "persist.observation.bound.observation.freshExportCompletedAtEpochMs"),
            "fresh export completion time is recorded");
        assertEquals("33", result.getProperty(
            "persist.observation.bound.observation.metadataStartedAtEpochMs"),
            "metadata observation start time is recorded");

        final ExternalPsdEditHostProbe.DiagnosticObservation mismatched =
            new ExternalPsdEditHostProbe.DiagnosticObservation(
                ExternalPsdEditHostProbe.DiagnosticStatus.UNAVAILABLE,
                "target identity changed", "[raw-2]", "[model-image-1]", "selector", "live",
                true, true, fingerprint, switchedRaw, false, 11L, 22L, 33L, 44L);
        final Properties unavailable = new Properties();
        ExternalPsdEditHostProbe.recordDiagnosticObservation(
            unavailable, "persist.observation.switched", mismatched);
        assertEquals("UNAVAILABLE", unavailable.getProperty(
            "persist.observation.switched.status"),
            "a switched target is explicitly unavailable");
        assertEquals("unavailable", unavailable.getProperty(
            "persist.observation.switched.freshNativeRgb.status"),
            "a fingerprint from a switched target is not usable evidence");
    }

    private static void testDiagnosticPsdSnapshotUsesRawImageId() {
        final PsdValidationContent.Bounds bounds = new PsdValidationContent.Bounds(
            450, 450, 550, 550);
        final PsdValidationContent.Fingerprint fingerprint =
            new PsdValidationContent.Fingerprint(
                "a".repeat(64), bounds, 100, 100, List.of(0, 1, 2));
        final ExternalPsdEditHostProbe.TargetIdentity expected =
            new ExternalPsdEditHostProbe.TargetIdentity(
                "cmo-document-id", "model-1", "binding-1", 7L, "model-image-1",
                "art-mesh-1", "raw-guid-target");
        final ExternalPsdEditHostProbe.TargetIdentity observed =
            new ExternalPsdEditHostProbe.TargetIdentity(
                "cmo-document-id", "model-1", "binding-1", 7L, "model-image-1",
                "art-mesh-1", "raw-guid-target");
        final PsdLayerSnapshot layer = new PsdLayerSnapshot(
            "layer-6", "Target", true, List.of(), Optional.empty(), List.of());
        final PsdClipMaskDocumentSnapshot targetSnapshot =
            new PsdClipMaskDocumentSnapshot(
                "raw-guid-target", "textures/target.psd", List.of(layer));
        final PsdClipMaskDocumentSnapshot otherSnapshot =
            new PsdClipMaskDocumentSnapshot(
                "raw-guid-other", "textures/other.psd", List.of(layer));

        assertTrue(!expected.documentId().equals(targetSnapshot.documentId()),
            "CMO document ID is distinct from the PSD raw GUID");
        assertTrue(ExternalPsdEditHostProbe.hasUniquePsdSnapshotForRawImage(
            List.of(targetSnapshot, otherSnapshot), expected.rawId()),
            "the PSD snapshot matching the target RawImageId is available");
        assertTrue(!ExternalPsdEditHostProbe.hasUniquePsdSnapshotForRawImage(
            List.of(otherSnapshot), expected.rawId()),
            "a snapshot for another raw image is rejected");
        assertEquals("AVAILABLE", ExternalPsdEditHostProbe.diagnosticStatusForTarget(
            expected, observed, true, true, fingerprint, true).name(),
            "matching raw snapshot does not require matching the CMO document ID");
        assertEquals("UNAVAILABLE", ExternalPsdEditHostProbe.diagnosticStatusForTarget(
            expected, observed, true, true, fingerprint, false).name(),
            "missing target raw snapshot keeps the diagnostic unavailable");
    }

    private static void testDiagnosticBudgetIsRecomputedPerStage() {
        final long started = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(20);
        final long remaining = ExternalPsdEditHostProbe.remainingDiagnosticBudgetMillis(
            started, 1000L);
        assertTrue(remaining > 0L && remaining < 1000L,
            "a later diagnostic stage receives only the remaining budget");

        final PsdValidationContent.Bounds bounds = new PsdValidationContent.Bounds(
            450, 450, 550, 550);
        final ExternalPsdEditHostProbe.DiagnosticObservation observation =
            ExternalPsdEditHostProbe.DiagnosticObservation.available(
                "raw-1", "model-1", "selector", "live",
                new PsdValidationContent.Fingerprint(
                    "a".repeat(64), bounds, 100, 100, List.of(0, 1, 2)));
        final AtomicInteger sdkStageCalls = new AtomicInteger();
        final ExternalPsdEditHostProbe.BoundedSettleResult exhaustedStage =
            ExternalPsdEditHostProbe.awaitBoundedSettle(
                observation,
                remainingMillis -> {
                    // Reproduce a stage that reaches its deadline after the supplier was
                    // admitted. The next SDK-stage seam must reject before the call counter.
                    ExternalPsdEditHostProbe.remainingDiagnosticBudgetMillis(
                        System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(2000),
                        remainingMillis);
                    sdkStageCalls.incrementAndGet();
                    return observation;
                },
                1, 1000, ignored -> { });
        assertEquals("TIMEOUT", exhaustedStage.status().name(),
            "an exhausted positive budget stops the supplier stage");
        assertEquals(0, sdkStageCalls.get(),
            "an exhausted positive budget cannot enter the next SDK stage");
        assertEquals(0L, ExternalPsdEditHostProbe.remainingDiagnosticBudgetMillis(
            System.nanoTime(), 0L),
            "the explicit zero budget remains the unlimited sentinel");
    }

    private static void testPublicProjectionFormatting() {
        final RawImageId raw = new RawImageId("raw-1");
        final ModelImageId model = new ModelImageId("model-1");
        final ArtMeshId mesh = new ArtMeshId("ArtMesh4");
        final RawLayerId layerId = new RawLayerId("raw-layer-6");
        final RawTexture texture = new RawTexture() {
            @Override public RawImageId id() { return raw; }
            @Override public String name() { return "raw"; }
            @Override public int width() { return 1000; }
            @Override public int height() { return 1000; }
        };
        final ModelImageEntry modelImage = new ModelImageEntry() {
            @Override public ModelImageId id() { return model; }
            @Override public String name() { return "model"; }
            @Override public int width() { return 1000; }
            @Override public int height() { return 1000; }
        };
        final RawLayerBinding binding = new RawLayerBinding(
            raw, layerId, 0,
            RawLayerBinding.DetailAvailability.AVAILABLE,
            RawLayerBinding.DetailAvailability.UNKNOWN);
        final ModelImageRelation relation = new ModelImageRelation(
            model, modelImage, List.of(raw), Optional.of(raw),
            Map.of(raw, List.of(binding)), List.of(mesh));
        final TextureRelationsSnapshot relations = new TextureRelationsSnapshot(
            TextureRelationsSnapshot.Availability.AVAILABLE,
            "binding", 1, 1,
            List.of(new RawImageDetails(
                texture, RawImageDetails.SourceKind.PSD, List.of(), false,
                Optional.empty(), Optional.empty(), Optional.empty())),
            List.of(relation), List.of(),
            List.of(new ArtMeshTextureInputs(
                mesh, List.of(TextureInputBinding.modelImage(model)), OptionalInt.of(0))));
        final Properties result = new Properties();
        ExternalPsdEditHostProbe.recordRelationProjection(
            result, "persist.observation.baseline.relations", relations);
        assertEquals("[raw-1]", result.getProperty(
            "persist.observation.baseline.relations.rawIds"),
            "public relation projection records raw IDs");
        assertEquals("[model-1]", result.getProperty(
            "persist.observation.baseline.relations.modelImageIds"),
            "public relation projection records model-image IDs");
        assertContains(result.getProperty(
            "persist.observation.baseline.relations.selectorProjection"),
            "raw-layer-6", "selector projection records raw-layer identity");
        assertContains(result.getProperty(
            "persist.observation.baseline.relations.selectorProjection"),
            "ArtMesh4", "selector projection records model users");

        final PsdLayerSnapshot layer = new PsdLayerSnapshot(
            "layer-6", "Target", true, List.of(mesh), Optional.empty(), List.of());
        final String live = ExternalPsdEditHostProbe.livePsdDocuments(List.of(
            new PsdClipMaskDocumentSnapshot(
                "document-1", "textures/source.psd", List.of(layer))));
        assertContains(live, "layerId=layer-6", "live PSD projection records layer ID");
        assertContains(live, "name=Target", "live PSD projection records layer name");
        assertContains(live, "artMeshIds=[ArtMesh4]",
            "live PSD projection records layer ArtMesh IDs");
    }

    private static void testBoundedSettleFailureAndTimeoutEvidence() throws Exception {
        final PsdValidationContent.Bounds bounds = new PsdValidationContent.Bounds(
            450, 450, 550, 550);
        final ExternalPsdEditHostProbe.DiagnosticObservation first =
            ExternalPsdEditHostProbe.DiagnosticObservation.available(
                "raw-1", "model-1", "selector", "live-a",
                new PsdValidationContent.Fingerprint(
                    "a".repeat(64), bounds, 100, 100, List.of(0, 1, 2)));
        final ExternalPsdEditHostProbe.DiagnosticObservation second =
            ExternalPsdEditHostProbe.DiagnosticObservation.available(
                "raw-1", "model-1", "selector", "live-b",
                new PsdValidationContent.Fingerprint(
                    "b".repeat(64), bounds, 100, 100, List.of(0, 1, 2)));
        final AtomicInteger changing = new AtomicInteger();
        final ExternalPsdEditHostProbe.BoundedSettleResult timeout =
            ExternalPsdEditHostProbe.awaitBoundedSettle(
                first, () -> changing.getAndIncrement() % 2 == 0 ? second : first,
                3, 1000, ignored -> { });
        assertEquals("TIMEOUT", timeout.status().name(),
            "changing observations produce bounded timeout");
        assertEquals(3, timeout.attempts(), "timeout retains bounded attempt count");
        assertContains(timeout.criterion(), "consecutive", "timeout retains stability criterion");
        final Properties timeoutEvidence = new Properties();
        ExternalPsdEditHostProbe.recordBoundedSettle(
            timeoutEvidence, "persist.observation.cycle.1.boundedSettle", timeout);
        assertEquals("3", timeoutEvidence.getProperty(
            "persist.observation.cycle.1.boundedSettle.attempts"),
            "timeout evidence records attempts");
        assertEquals("false", timeoutEvidence.getProperty(
            "persist.observation.cycle.1.boundedSettle.stable"),
            "timeout evidence cannot claim stable");
        assertTrue(Integer.parseInt(timeoutEvidence.getProperty(
            "persist.observation.cycle.1.boundedSettle.durationMs")) >= 0,
            "timeout evidence records elapsed duration");

        final ExternalPsdEditHostProbe.BoundedSettleResult stable =
            ExternalPsdEditHostProbe.awaitBoundedSettle(
                first, () -> second, 3, 1000, ignored -> { });
        assertEquals("STABLE", stable.status().name(),
            "a changed observation that remains stable is accepted");
        assertEquals(2, stable.attempts(),
            "settle requires two equal observations after the completion boundary");

        final ExternalPsdEditHostProbe.BoundedSettleResult lateEqual =
            ExternalPsdEditHostProbe.awaitBoundedSettle(
                first, () -> {
                    Thread.sleep(40);
                    return first;
                }, 2, 10, ignored -> { });
        assertEquals("TIMEOUT", lateEqual.status().name(),
            "an equal observation returned after the deadline cannot be stable");
        assertTrue(!lateEqual.stable(),
            "a late equal observation cannot claim stability");

        final AtomicReference<Long> remainingBudget = new AtomicReference<>(0L);
        final AtomicInteger recordedLate = new AtomicInteger();
        final ExternalPsdEditHostProbe.BoundedSettleResult lateBounded =
            ExternalPsdEditHostProbe.awaitBoundedSettle(
                first,
                remainingMillis -> {
                    remainingBudget.set(remainingMillis);
                    Thread.sleep(40);
                    return first;
                },
                2,
                10,
                ignored -> { },
                ignored -> recordedLate.incrementAndGet());
        assertEquals("TIMEOUT", lateBounded.status().name(),
            "bounded observation returning late cannot be stable");
        assertTrue(remainingBudget.get() > 0L,
            "the remaining settle budget is passed to the observation");
        assertEquals(0, recordedLate.get(),
            "a late observation is not written to settle evidence");
        assertContains(lateBounded.diagnostic(), "after the settle deadline",
            "late observation explains the timeout");

        final Properties stableEvidence = new Properties();
        ExternalPsdEditHostProbe.recordBoundedSettle(
            stableEvidence, "persist.observation.stable.boundedSettle", stable);
        assertEquals("UNAVAILABLE", stableEvidence.getProperty(
            "persist.observation.stable.boundedSettle.nativeCompletion"),
            "observation stability does not claim native completion");
        assertEquals("false", stableEvidence.getProperty(
            "persist.observation.stable.boundedSettle.deadline.hard"),
            "non-cancellable SDK observation is not advertised as a hard deadline");

        final AtomicReference<Throwable> edtFailure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                ExternalPsdEditHostProbe.awaitBoundedSettle(
                    first, () -> first, 1, 1000, ignored -> { });
            } catch (Throwable failure) {
                edtFailure.set(failure);
            }
        });
        assertTrue(edtFailure.get() instanceof IllegalStateException,
            "settle refuses to block the EDT");

        final ExternalPsdEditHostProbe.BoundedSettleResult unavailable =
            ExternalPsdEditHostProbe.awaitBoundedSettle(
                first,
                () -> ExternalPsdEditHostProbe.DiagnosticObservation.unavailable(
                    "relations unavailable"),
                3, 1000, ignored -> { });
        assertEquals("UNAVAILABLE", unavailable.status().name(),
            "unavailable observation is not a stable result");
        assertEquals(1, unavailable.attempts(),
            "unavailable boundary records the attempted observation");

        final ExternalPsdEditHostProbe.BoundedSettleResult failure =
            ExternalPsdEditHostProbe.awaitBoundedSettle(
                first, () -> { throw new IOException("injected observation failure"); },
                2, 1000, ignored -> { });
        assertEquals("FAILED", failure.status().name(),
            "observation failure is not converted to stable success");
        assertEquals(1, failure.attempts(), "failed observation counts the attempted poll");
        assertContains(failure.diagnostic(), "injected observation failure",
            "observation failure is retained");
        assertTrue(!failure.stable(), "failed settle cannot claim stability");
    }

    private static void testFreshDiagnosticFailureEvidence() {
        final Properties result = new Properties();
        final IOException failure = new IOException("injected first fresh export failure");
        ExternalPsdEditHostProbe.recordFreshDiagnosticFailure(
            result,
            "cycle.1.importCompletion",
            "persist.observation.cycle.1.boundedSettle",
            failure);
        assertEquals("FAILED", result.getProperty(
            "cycle.1.importCompletion.diagnosticFailure.status"),
            "first fresh export failure is recorded");
        assertContains(result.getProperty(
            "cycle.1.importCompletion.freshNativeRgb.failure"),
            "injected first fresh export failure",
            "the original fresh export failure is retained");
        assertEquals("NOT_ATTEMPTED", result.getProperty(
            "persist.observation.cycle.1.boundedSettle.status"),
            "settle is explicitly not attempted after the first fresh export fails");
        assertEquals("false", result.getProperty(
            "persist.observation.cycle.1.boundedSettle.stable"),
            "failed first fresh export cannot produce stable settle evidence");
    }

    private static void testBoundedSettleInterrupt() throws Exception {
        Thread.interrupted();
        try {
            final PsdValidationContent.Bounds bounds = new PsdValidationContent.Bounds(
                450, 450, 550, 550);
            final ExternalPsdEditHostProbe.DiagnosticObservation observation =
                ExternalPsdEditHostProbe.DiagnosticObservation.available(
                    "raw-1", "model-1", "selector", "live",
                    new PsdValidationContent.Fingerprint(
                        "a".repeat(64), bounds, 100, 100, List.of(0, 1, 2)));
            final ExternalPsdEditHostProbe.BoundedSettleResult interrupted =
                ExternalPsdEditHostProbe.awaitBoundedSettle(
                    observation,
                    () -> { throw new InterruptedException("injected settle interrupt"); },
                    2,
                    1000,
                    ignored -> { });
            assertEquals("FAILED", interrupted.status().name(),
                "an interrupted observation fails the settle");
            assertTrue(Thread.interrupted(),
                "settle preserves the interrupt status for its caller");
        } finally {
            Thread.interrupted();
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

    private static List<Path> diagnosticFiles(final Path stateDir) throws IOException {
        try (var paths = Files.list(stateDir)) {
            return paths.filter(path -> path.getFileName().toString()
                .startsWith("external-psd-gui-thread-dump-"))
                .sorted()
                .toList();
        }
    }

    private static final class MutableGuiDiagnosticClock
        implements ExternalPsdEditHostProbe.GuiDiagnosticClock {
        private final AtomicLong nanos = new AtomicLong();

        @Override public long nanoTime() { return nanos.get(); }

        @Override public java.time.Instant utcNow() {
            return java.time.Instant.parse("2026-09-15T00:00:00Z")
                .plusNanos(nanos.get());
        }

        private void advanceMillis(final long millis) {
            nanos.addAndGet(millis * 1_000_000L);
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

    private static void testExactTaskWindowBinding() {
        final String sourceClass = ExactHostRowTarget.ART_MESH_SOURCE_CLASS_NAME;
        final ExactHostRowTarget.Identity partsIdentity = new ExactHostRowTarget.Identity(
            ExactHostRowTarget.RowFamily.PARTS, sourceClass, "ArtMesh4");
        final ExactHostRowTarget.Identity deformerIdentity = new ExactHostRowTarget.Identity(
            ExactHostRowTarget.RowFamily.DEFORMER, sourceClass, "ArtMesh4");
        final List<ExternalPsdEditHostProbe.ExactCapturedRow> rows = List.of(
            exactCapturedRow(partsIdentity, "parts-table", "document-window", "parts-model",
                4, true, false),
            exactCapturedRow(deformerIdentity, "deformer-table", "document-window",
                "deformer-model", 2, true, false));
        final var bound = ExternalPsdEditHostProbe.validateTaskWindowBindingForTest(
            "external-psd-edit-025.cmo3", "external-psd-edit-025.cmo3", "document-1",
            "model-1", "document-window", "ArtMesh4", rows);
        assertTrue(bound.bound(),
            "the exact fixture/document/model/domain evidence binds the task window");
        assertContains(bound.diagnostic(), "document=document-1",
            "task-window evidence records the document identity");
        assertContains(bound.diagnostic(), "domain=ArtMesh4",
            "task-window evidence records the complete ArtMesh domain ID");

        final var sameWindowAfterSaveAs =
            ExternalPsdEditHostProbe.validateTaskWindowBindingForTest(
                "external-psd-edit-025.cmo3", "external-psd-edit-025.cmo3", "document-1",
                "model-1", "document-window", "ArtMesh4", rows);
        assertTrue(sameWindowAfterSaveAs.bound(),
            "SAVE_AS does not require re-resolving the retained document window");
        final var focusChanged = ExternalPsdEditHostProbe.decideGuiWindowBindingForTest(
            "document-window", "other-window", true, true);
        assertTrue(focusChanged.waitForBoundWindow() && !focusChanged.bindNow(),
            "a changed active window cannot replace the retained task document window");

        final var onlyOtherWindow = ExternalPsdEditHostProbe.validateTaskWindowBindingForTest(
            "external-psd-edit-025.cmo3", "external-psd-edit-025.cmo3", "document-1", "model-1",
            "document-window", "ArtMesh4", List.of(exactCapturedRow(deformerIdentity,
                "other-table", "other-window", "other-model", 2, true, false)));
        assertTrue(!onlyOtherWindow.bound(),
            "a matching ArtMesh row in another window cannot license a close target");
        assertContains(onlyOtherWindow.diagnostic(), "another window",
            "cross-window task binding rejection is explicit");

        final var fixtureMismatch = ExternalPsdEditHostProbe.validateTaskWindowBindingForTest(
            "external-psd-edit-025.cmo3", "different-document.cmo3", "document-1", "model-1",
            "document-window", "ArtMesh4", rows);
        assertTrue(!fixtureMismatch.bound(),
            "a non-task fixture document cannot license the host close target");

        final var missingDocument = ExternalPsdEditHostProbe.validateTaskWindowBindingForTest(
            "external-psd-edit-025.cmo3", "external-psd-edit-025.cmo3", "", "model-1",
            "document-window", "ArtMesh4", rows);
        assertTrue(!missingDocument.bound(),
            "missing document identity cannot license a host close target");
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

    private static void testGuiReadyTriggerProtocol() throws Exception {
        final Path state = Files.createTempDirectory("external-psd-gui-ready-");
        try {
            final Path expected = state.resolve("gui-ready.flag");
            assertEquals(expected.toAbsolutePath().normalize(),
                ExternalPsdEditHostProbe.guiReadyTriggerPathForTest(state),
                "GUI trigger is fixed below the context-owned state directory");

            final var missing = ExternalPsdEditHostProbe.waitForGuiReadyTriggerForTest(
                state, 15L, () -> false, millis -> Thread.sleep(1L));
            assertTrue(!missing.ready(), "missing GUI trigger times out");
            assertContains(missing.diagnostic(), "timeout", "missing trigger timeout is explicit");

            Files.writeString(expected, "old");
            final var oldArm = ExternalPsdEditHostProbe.prepareGuiTriggerForTest(state);
            assertTrue(!oldArm.armed(), "pre-existing trigger does not arm the production wait");
            assertTrue(oldArm.path() == null, "rejected old trigger has no armed path");
            assertContains(oldArm.diagnostic(), "pre-existed",
                "old trigger rejection is explicit before the armed marker");
            final var old = ExternalPsdEditHostProbe.waitForArmedGuiTriggerForTest(
                oldArm, 1000L, () -> false, millis -> {
                    throw new AssertionError("pre-existing trigger must not be polled");
                });
            assertTrue(!old.ready(), "an unarmed old trigger cannot enter the production wait");
            Files.delete(expected);

            // A late worker can be scheduled after Runner has observed the armed marker and
            // created the trigger. The first poll must accept that new file without mtime/sleep
            // heuristics.
            final var armed = ExternalPsdEditHostProbe.prepareGuiTriggerForTest(state);
            assertTrue(armed.armed(), "a clean state directory arms the production wait");
            Files.writeString(expected, "arrived-after-armed");
            final var immediatelyAvailable = ExternalPsdEditHostProbe
                .waitForArmedGuiTriggerForTest(armed, 1000L, () -> false, millis -> {
                    throw new AssertionError("already-created post-arm trigger is immediate");
                });
            assertTrue(immediatelyAvailable.ready(),
                "a trigger created after arming is accepted on the first poll");
            assertEquals(expected, immediatelyAvailable.path(),
                "post-arm trigger remains task-local");
            Files.delete(expected);

            final Path target = state.resolve("target");
            Files.writeString(target, "target");
            Files.createSymbolicLink(expected, target);
            final var symlink = ExternalPsdEditHostProbe.waitForGuiReadyTriggerForTest(
                state, 1000L, () -> false, millis -> {
                    throw new AssertionError("symlink trigger must not be polled");
                });
            assertTrue(!symlink.ready(), "symlink GUI trigger is rejected");
            assertContains(symlink.diagnostic(), "symlink", "symlink rejection is explicit");
            Files.delete(expected);
            Files.delete(target);

            final AtomicInteger polls = new AtomicInteger();
            final var delayed = ExternalPsdEditHostProbe.waitForGuiReadyTriggerForTest(
                state, 1000L, () -> false, millis -> {
                    if (polls.incrementAndGet() == 2) {
                        try {
                            Files.writeString(expected, "new");
                        } catch (IOException failure) {
                            throw new AssertionError(failure);
                        }
                    }
                    Thread.sleep(1L);
                });
            assertTrue(delayed.ready(), "new regular GUI trigger is accepted");
            assertEquals(expected, delayed.path(), "accepted trigger path is task-local");
            Files.delete(expected);

            final var nonRegular = ExternalPsdEditHostProbe.waitForGuiReadyTriggerForTest(
                state, 1000L, () -> false, millis -> {
                    try {
                        Files.createDirectory(expected);
                    } catch (IOException failure) {
                        throw new AssertionError(failure);
                    }
                });
            assertTrue(!nonRegular.ready(), "non-regular GUI trigger is rejected");
            assertContains(nonRegular.diagnostic(), "regular file",
                "non-regular trigger rejection is explicit");
            Files.delete(expected);

            final Path stateLink = state.resolveSibling(state.getFileName() + "-link");
            Files.createSymbolicLink(stateLink, state);
            try {
                final var invalidState = ExternalPsdEditHostProbe
                    .waitForGuiReadyTriggerForTest(stateLink, 1000L, () -> false,
                        millis -> { throw new AssertionError("symlink state must not poll"); });
                assertTrue(!invalidState.ready(), "symlink state directory is rejected");
                assertContains(invalidState.diagnostic(), "state directory",
                    "state-directory rejection is explicit");
            } finally {
                Files.deleteIfExists(stateLink);
            }
        } finally {
            deleteTree(state);
        }
    }

    private static void testGuiReadyTriggerProductionPath() throws Exception {
        final Path state = Files.createTempDirectory("external-psd-gui-stale-production-");
        final Path trigger = state.resolve("gui-ready.flag");
        Files.writeString(trigger, "old");
        final String oldPhase = System.getProperty("turboism.validation.externalpsd.phase");
        final String oldRunId = System.getProperty("turboism.validation.externalpsd.runId");
        final AtomicInteger armedMarkers = new AtomicInteger();
        ExternalPsdEditHostProbe probe = null;
        try {
            final PluginPaths paths = new PluginPaths() {
                @Override public Path dataDir() { return state; }
                @Override public Path stateDir() { return state; }
                @Override public Path cacheDir() { return state; }
            };
            final PluginLogger logger = new PluginLogger() {
                @Override public void debug(final String message) { }
                @Override public void info(final String message) {
                    if (message.contains("EXTERNAL_PSD_EDIT_GUI_TRIGGER_ARMED")) {
                        armedMarkers.incrementAndGet();
                    }
                }
                @Override public void warn(final String message) { }
                @Override public void error(final String message) { }
                @Override public void error(final String message, final Throwable failure) { }
            };
            final PluginContext context = (PluginContext) java.lang.reflect.Proxy
                .newProxyInstance(PluginContext.class.getClassLoader(),
                    new Class<?>[]{PluginContext.class}, (proxy, method, args) -> {
                        if (method.getName().equals("paths")) return paths;
                        if (method.getName().equals("logger")) return logger;
                        if (method.getName().equals("toString")) return "stale-trigger-context";
                        throw new UnsupportedOperationException(method.getName());
                    });
            System.setProperty("turboism.validation.externalpsd.phase", "gui");
            System.setProperty("turboism.validation.externalpsd.runId", "stale-trigger-run");
            probe = new ExternalPsdEditHostProbe();
            probe.init(context);
            probe.enable();
            final Path result = state.resolve(
                "external-psd-edit-result.properties");
            final long deadline = System.nanoTime()
                + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (!Files.exists(result) && System.nanoTime() < deadline) {
                Thread.sleep(1L);
            }
            assertTrue(Files.exists(result),
                "the production GUI wait records a stale-trigger BLOCKED result");
            assertEquals(0, armedMarkers.get(),
                "a pre-existing trigger cannot publish the armed marker");
        } finally {
            if (probe != null) probe.disable();
            restoreProperty("turboism.validation.externalpsd.phase", oldPhase);
            restoreProperty("turboism.validation.externalpsd.runId", oldRunId);
            deleteTree(state);
        }
    }

    private static void testGuiWaitDiagnostics() throws Exception {
        final Path notDueState = Files.createTempDirectory("external-psd-gui-diagnostic-not-due-");
        try {
            final MutableGuiDiagnosticClock clock = new MutableGuiDiagnosticClock();
            final AtomicInteger samples = new AtomicInteger();
            final var diagnostics = ExternalPsdEditHostProbe.startGuiWaitDiagnosticsForTest(
                notDueState, "not-due-run", clock.nanoTime(), () -> false, () -> false,
                new AtomicReference<>("armed-waiting-for-trigger"), clock,
                millis -> Thread.sleep(1L), () -> {
                    samples.incrementAndGet();
                    return "unexpected sample";
                });
            diagnostics.close("test-not-due");
            assertTrue(diagnostics.awaitForTest(1000L),
                "not-due diagnostic daemon stops when closed");
            assertEquals(0, samples.get(), "an unelapsed diagnostic deadline is not sampled");
            assertEquals(0, diagnosticFiles(notDueState).size(),
                "not-due wait creates no diagnostic file");
        } finally {
            deleteTree(notDueState);
        }

        final Path readyState = Files.createTempDirectory("external-psd-gui-diagnostic-ready-");
        try {
            final MutableGuiDiagnosticClock clock = new MutableGuiDiagnosticClock();
            final AtomicInteger samples = new AtomicInteger();
            final var diagnostics = ExternalPsdEditHostProbe.startGuiWaitDiagnosticsForTest(
                readyState, "ready-run", clock.nanoTime(), () -> false, () -> true,
                new AtomicReference<>("armed-waiting-for-trigger"), clock,
                millis -> { throw new AssertionError("ready trigger must not be delayed"); },
                () -> {
                    samples.incrementAndGet();
                    return "unexpected sample";
                });
            assertTrue(diagnostics.awaitForTest(1000L),
                "ready trigger stops diagnostic daemon without sampling");
            assertEquals(0, samples.get(), "a received trigger suppresses diagnostics");
            assertEquals(0, diagnosticFiles(readyState).size(),
                "ready wait creates no diagnostic file");
        } finally {
            deleteTree(readyState);
        }

        final Path twoState = Files.createTempDirectory("external-psd-gui-diagnostic-two-");
        try {
            final MutableGuiDiagnosticClock clock = new MutableGuiDiagnosticClock();
            final AtomicBoolean trigger = new AtomicBoolean();
            final var diagnostics = ExternalPsdEditHostProbe.startGuiWaitDiagnosticsForTest(
                twoState, "two-sample-run", clock.nanoTime(), () -> false, trigger::get,
                new AtomicReference<>("armed-waiting-for-trigger"), clock,
                millis -> {
                    clock.advanceMillis(millis);
                    Thread.yield();
                }, () -> "fake full ThreadMXBean dump");
            assertTrue(diagnostics.awaitForTest(5000L),
                "continuous trigger wait completes its two bounded samples");
            assertEquals(2, diagnostics.writtenSamples(),
                "continuous wait writes no more than the two scheduled samples");
            assertEquals(2, diagnosticFiles(twoState).size(),
                "the two scheduled diagnostic files are task-local");
            for (final Path file : diagnosticFiles(twoState)) {
                final String content = Files.readString(file);
                assertContains(content, "runId='two-sample-run'",
                    "diagnostic records the task run ID");
                assertContains(content, "sampleOrdinal=",
                    "diagnostic records the sample ordinal");
                assertContains(content, "sampleUtc=",
                    "diagnostic records UTC sample time");
                assertContains(content, "elapsedMillis=",
                    "diagnostic records elapsed wait time");
                assertContains(content, "waitingStage=armed-waiting-for-trigger",
                    "diagnostic records the wait stage");
            }
        } finally {
            deleteTree(twoState);
        }

        final Path failureState = Files.createTempDirectory(
            "external-psd-gui-diagnostic-failure-");
        try {
            final MutableGuiDiagnosticClock clock = new MutableGuiDiagnosticClock();
            final AtomicBoolean trigger = new AtomicBoolean();
            final var diagnostics = ExternalPsdEditHostProbe.startGuiWaitDiagnosticsForTest(
                failureState, "failure-run", clock.nanoTime(), () -> false, trigger::get,
                new AtomicReference<>("armed-waiting-for-trigger"), clock,
                millis -> {
                    clock.advanceMillis(millis);
                    Thread.yield();
                }, () -> { throw new IllegalStateException("injected sampler failure"); });
            assertTrue(diagnostics.awaitForTest(5000L),
                "sampler failures do not leave a diagnostic daemon running");
            assertEquals(2, diagnostics.unavailableSamples(),
                "each bounded sampler failure is recorded as unavailable");
            assertTrue(!trigger.get(), "diagnostic sampler failures cannot create readiness");
            for (final Path file : diagnosticFiles(failureState)) {
                assertContains(Files.readString(file), "diagnostic unavailable",
                    "sampler failure output is explicitly unavailable");
                assertContains(Files.readString(file), "injected sampler failure",
                    "sampler failure cause is retained");
            }
        } finally {
            deleteTree(failureState);
        }

        final Path disabledState = Files.createTempDirectory(
            "external-psd-gui-diagnostic-disabled-");
        try {
            final MutableGuiDiagnosticClock clock = new MutableGuiDiagnosticClock();
            final CountDownLatch firstSample = new CountDownLatch(1);
            final CountDownLatch secondSampleWaitStarted = new CountDownLatch(1);
            final CountDownLatch releaseSecondSampleWait = new CountDownLatch(1);
            final AtomicInteger samples = new AtomicInteger();
            final var diagnostics = ExternalPsdEditHostProbe.startGuiWaitDiagnosticsForTest(
                disabledState, "disabled-run", clock.nanoTime(), () -> false, () -> false,
                new AtomicReference<>("armed-waiting-for-trigger"), clock,
                millis -> {
                    if (firstSample.getCount() == 0) {
                        // The first sampler has returned and writeSample has completed before
                        // the scheduler asks for this next sleep. Hold it before the second
                        // deadline so close() can interrupt this exact scheduling point.
                        secondSampleWaitStarted.countDown();
                        releaseSecondSampleWait.await();
                    }
                    clock.advanceMillis(millis);
                }, () -> {
                    samples.incrementAndGet();
                    firstSample.countDown();
                    return "first sample only";
                });
            assertTrue(firstSample.await(5L, java.util.concurrent.TimeUnit.SECONDS),
                "diagnostic scheduler reaches its first deadline");
            assertTrue(secondSampleWaitStarted.await(5L, java.util.concurrent.TimeUnit.SECONDS),
                "diagnostic scheduler pauses before the second deadline");
            diagnostics.close("disabled");
            assertTrue(diagnostics.awaitForTest(2000L),
                "disable interrupts the diagnostic daemon");
            assertEquals(1, samples.get(), "disable prevents the later diagnostic sample");
            assertEquals(1, diagnosticFiles(disabledState).size(),
                "disable leaves only the already-written bounded diagnostic");
        } finally {
            deleteTree(disabledState);
        }

        final Path boundedState = Files.createTempDirectory(
            "external-psd-gui-diagnostic-bounded-");
        try {
            final MutableGuiDiagnosticClock clock = new MutableGuiDiagnosticClock();
            final String oversized = "x".repeat(
                ExternalPsdEditHostProbe.GUI_WAIT_DIAGNOSTIC_MAX_BYTES * 4);
            final var diagnostics = ExternalPsdEditHostProbe.startGuiWaitDiagnosticsForTest(
                boundedState, "bounded-run", clock.nanoTime(), () -> false, () -> false,
                new AtomicReference<>("armed-waiting-for-trigger"), clock,
                millis -> {
                    clock.advanceMillis(millis);
                    Thread.yield();
                }, () -> oversized);
            assertTrue(diagnostics.awaitForTest(5000L),
                "oversized diagnostic samples remain bounded and terminate");
            for (final Path file : diagnosticFiles(boundedState)) {
                assertTrue(Files.size(file)
                    <= ExternalPsdEditHostProbe.GUI_WAIT_DIAGNOSTIC_MAX_BYTES,
                    "diagnostic file size is bounded");
                assertContains(Files.readString(file), "diagnostic output truncated",
                    "oversized diagnostic output records truncation");
            }
        } finally {
            deleteTree(boundedState);
        }

        final CountDownLatch releaseThreads = new CountDownLatch(1);
        final CountDownLatch startedThreads = new CountDownLatch(2);
        final Thread bootstrap = new Thread(() -> {
            startedThreads.countDown();
            try {
                releaseThreads.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "turboism-bootstrap-test");
        final Thread awt = new Thread(() -> {
            startedThreads.countDown();
            try {
                releaseThreads.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "AWT-EventQueue-test");
        bootstrap.setDaemon(true);
        awt.setDaemon(true);
        bootstrap.start();
        awt.start();
        try {
            assertTrue(startedThreads.await(2L, java.util.concurrent.TimeUnit.SECONDS),
                "real ThreadMXBean smoke threads started");
            final String dump = ExternalPsdEditHostProbe.captureGuiThreadDumpForTest();
            assertContains(dump,
                "threadDumpSource=java.lang.management.ThreadMXBean.dumpAllThreads",
                "real smoke uses the same-JVM ThreadMXBean dump");
            assertContains(dump, "samplingThreadPresent=true",
                "real dump includes the sampling thread in the all-thread result");
            assertContains(dump, "name='turboism-bootstrap-test'",
                "real dump retains bootstrap thread name and state");
            assertContains(dump, "name='AWT-EventQueue-test'",
                "real dump retains AWT event thread name and state");
            assertContains(dump, "state=", "real dump records thread states");
            assertContains(dump, "lockOwnerId=", "real dump records lock owners");
            assertContains(dump, "stackFrames=", "real dump records bounded stacks");
        } finally {
            releaseThreads.countDown();
            bootstrap.join(2000L);
            awt.join(2000L);
        }
    }

    private static void testGuiEnableAndEdtAreNonBlocking() throws Exception {
        final Path state = Files.createTempDirectory("external-psd-gui-worker-");
        final String oldPhase = System.getProperty("turboism.validation.externalpsd.phase");
        final String oldRunId = System.getProperty("turboism.validation.externalpsd.runId");
        try {
            final var edt = onEdt(() -> ExternalPsdEditHostProbe
                .waitForGuiReadyTriggerForTest(state, 10_000L, () -> false,
                    millis -> { throw new AssertionError("EDT wait must return immediately"); }));
            assertTrue(!edt.ready(), "EDT cannot wait for the GUI trigger");
            assertContains(edt.diagnostic(), "off the EDT",
                "EDT rejection explains the thread boundary");

            final PluginPaths paths = new PluginPaths() {
                @Override public Path dataDir() { return state; }
                @Override public Path stateDir() { return state; }
                @Override public Path cacheDir() { return state; }
            };
            final PluginLogger logger = new PluginLogger() {
                @Override public void debug(final String message) { }
                @Override public void info(final String message) { }
                @Override public void warn(final String message) { }
                @Override public void error(final String message) { }
                @Override public void error(final String message, final Throwable failure) { }
            };
            final PluginContext context = (PluginContext) java.lang.reflect.Proxy
                .newProxyInstance(PluginContext.class.getClassLoader(),
                    new Class<?>[]{PluginContext.class}, (proxy, method, args) -> {
                        if (method.getName().equals("paths")) return paths;
                        if (method.getName().equals("logger")) return logger;
                        if (method.getName().equals("toString")) return "trigger-test-context";
                        throw new UnsupportedOperationException(method.getName());
                    });
            System.setProperty("turboism.validation.externalpsd.phase", "gui");
            System.setProperty("turboism.validation.externalpsd.runId", "trigger-test-run");
            final ExternalPsdEditHostProbe probe = new ExternalPsdEditHostProbe();
            probe.init(context);
            final long started = System.nanoTime();
            probe.enable();
            final long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS
                .toMillis(System.nanoTime() - started);
            assertTrue(elapsedMillis < 500L,
                "enable returns while the task-local trigger wait runs on its worker");
            probe.disable();
        } finally {
            restoreProperty("turboism.validation.externalpsd.phase", oldPhase);
            restoreProperty("turboism.validation.externalpsd.runId", oldRunId);
            deleteTree(state);
        }
    }

    private static void testGuiWindowBinding() {
        final var noTarget = ExternalPsdEditHostProbe.decideGuiWindowBindingForTest(
            "", "window-a", true, false);
        assertTrue(!noTarget.bindNow(),
            "a reviewed window without the exact target is not bound");
        assertTrue(!noTarget.waitForBoundWindow(),
            "an unbound window without the target can continue searching");

        final var firstTarget = ExternalPsdEditHostProbe.decideGuiWindowBindingForTest(
            "", "window-a", true, true);
        assertTrue(firstTarget.bindNow(),
            "the first proven window containing the exact target becomes bound");

        final var sameWindow = ExternalPsdEditHostProbe.decideGuiWindowBindingForTest(
            "window-a", "window-a", true, true);
        assertTrue(!sameWindow.bindNow() && !sameWindow.waitForBoundWindow(),
            "the bound target window remains usable");

        final var otherWindow = ExternalPsdEditHostProbe.decideGuiWindowBindingForTest(
            "window-a", "window-b", true, true);
        assertTrue(!otherWindow.bindNow() && otherWindow.waitForBoundWindow(),
            "focus change never rebinds the target to another window");
        assertContains(otherWindow.diagnostic(), "window-a",
            "focus-change diagnostic retains the original window identity");

        final var restoredWindow = ExternalPsdEditHostProbe.decideGuiWindowBindingForTest(
            "window-a", "window-a", true, true);
        assertTrue(!restoredWindow.bindNow() && !restoredWindow.waitForBoundWindow(),
            "the original bound window becomes usable again after focus returns");
        assertContains(restoredWindow.diagnostic(), "window-a",
            "restored-window diagnostic retains the original binding");

        final var hiddenBoundWindow = ExternalPsdEditHostProbe.decideGuiWindowBindingForTest(
            "window-a", "", false, false);
        assertTrue(hiddenBoundWindow.waitForBoundWindow(),
            "an unproven active window waits for the original bound window");

        final var hiddenBeforeBinding = ExternalPsdEditHostProbe.decideGuiWindowBindingForTest(
            "", "", false, false);
        assertTrue(!hiddenBeforeBinding.bindNow() && !hiddenBeforeBinding.waitForBoundWindow(),
            "without an established target window no window is selected blindly");
    }

    private static void testPrepareFixturePhaseDispatch() {
        assertTrue(ExternalPsdEditHostProbe.isPrepareFixturePhase("prepare-fixture"),
            "prepare-fixture has its dedicated pre-readiness dispatch");
        assertTrue(!ExternalPsdEditHostProbe.isPrepareFixturePhase("pipeline"),
            "pipeline remains on ordinary readiness");
        assertTrue(!ExternalPsdEditHostProbe.isPrepareFixturePhase("reopen"),
            "reopen remains on ordinary readiness");
        assertTrue(!ExternalPsdEditHostProbe.isPrepareFixturePhase("gui"),
            "gui remains on trigger and ordinary readiness");
        assertTrue(!ExternalPsdEditHostProbe.isPrepareFixturePhase(null),
            "missing phase cannot enter preparation implicitly");
    }

    private static void testNativeCloseDialogHandling() throws Exception {
        final AtomicInteger closeCalls = new AtomicInteger();
        final var dispatched = ExternalPsdEditHostProbe.dispatchBoundWindowCloseForTest(
            "bound-window", "bound-window", true, true, closeCalls::incrementAndGet);
        assertTrue(dispatched.dispatched(), "the exact bound host window receives WINDOW_CLOSING");
        assertEquals(1, closeCalls.get(), "the native close event is dispatched once");
        assertContains(dispatched.diagnostic(), "WINDOW_CLOSING",
            "close diagnostic names the native event");

        final AtomicInteger rejectedCalls = new AtomicInteger();
        final var wrongWindow = ExternalPsdEditHostProbe.dispatchBoundWindowCloseForTest(
            "bound-window", "other-window", true, true, rejectedCalls::incrementAndGet);
        assertTrue(!wrongWindow.dispatched(), "a different window cannot receive the close event");
        assertEquals(0, rejectedCalls.get(), "cross-window close is not dispatched");
        final var hiddenWindow = ExternalPsdEditHostProbe.dispatchBoundWindowCloseForTest(
            "bound-window", "bound-window", false, true, rejectedCalls::incrementAndGet);
        assertTrue(!hiddenWindow.dispatched(), "a hidden bound window is rejected");

        final AtomicInteger noCalls = new AtomicInteger();
        final var noDialog = ExternalPsdEditHostProbe.classifyCloseDialogForTest(
            "bound-window", List.of(), List.of());
        assertEquals(ExternalPsdEditHostProbe.CloseDialogOutcome.NO_DIALOG,
            noDialog.outcome(), "no dialog permits the host's natural close path");

        final var oldDialog = closeDialog("old-dialog", "bound-window", true, noCalls);
        final var newDialog = closeDialog("new-dialog", "bound-window", true, noCalls);
        final var oldModelessForAssociation = new ExternalPsdEditHostProbe.CloseDialogSnapshot(
            "old-modeless", "bound-window", true, true, true, false, "MODELESS", 0,
            List.of(), List.of(), -1, () -> { }, () -> false);
        final var associated = ExternalPsdEditHostProbe.classifyCloseDialogForTest(
            "bound-window", List.of(oldModelessForAssociation),
            List.of(oldModelessForAssociation, newDialog));
        assertEquals(ExternalPsdEditHostProbe.CloseDialogOutcome.DISMISS_NO,
            associated.outcome(), "only a new dialog from this close is eligible");
        associated.dismissNo().run();
        assertEquals(1, noCalls.get(), "the exact no-save option is selected once");

        final var disabledNo = ExternalPsdEditHostProbe.classifyCloseDialogForTest(
            "bound-window", List.of(), List.of(new ExternalPsdEditHostProbe.CloseDialogSnapshot(
                "disabled-no", "bound-window", true, true, true, true, "APPLICATION_MODAL", 1,
                List.of("javax.swing.JButton", "javax.swing.JButton", "javax.swing.JButton"),
                List.of("Yes (Y)", "No (N)", "Cancel (C)"), 0, noCalls::incrementAndGet,
                () -> false)));
        assertEquals(ExternalPsdEditHostProbe.CloseDialogOutcome.REJECTED,
            disabledNo.outcome(), "a disabled No option is rejected before action");
        assertContains(disabledNo.diagnostic(), "not enabled/showing/displayable",
            "disabled No rejection explains the operability requirement");

        final AtomicInteger gatedCalls = new AtomicInteger();
        final var gatedDecision = ExternalPsdEditHostProbe.CloseDialogDecision.dismissNo(
            "late inspection test", gatedCalls::incrementAndGet);
        final AtomicBoolean coordinatorActive = new AtomicBoolean(true);
        final AtomicBoolean noActionClaimed = new AtomicBoolean();
        final AtomicReference<ExternalPsdEditHostProbe.CloseDialogInspection> firstInspection =
            new AtomicReference<>();
        final AtomicReference<ExternalPsdEditHostProbe.CloseDialogInspection> lateInspection =
            new AtomicReference<>();
        final java.util.concurrent.CountDownLatch edtStarted =
            new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch releaseEdt =
            new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch inspectionsDone =
            new java.util.concurrent.CountDownLatch(2);
        SwingUtilities.invokeLater(() -> {
            edtStarted.countDown();
            try {
                releaseEdt.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertTrue(edtStarted.await(1, java.util.concurrent.TimeUnit.SECONDS),
                "the EDT blocker starts before late inspections are queued");
            SwingUtilities.invokeLater(() -> {
                firstInspection.set(ExternalPsdEditHostProbe.dismissNoForTest(
                    gatedDecision, coordinatorActive, noActionClaimed));
                inspectionsDone.countDown();
            });
            SwingUtilities.invokeLater(() -> {
                lateInspection.set(ExternalPsdEditHostProbe.dismissNoForTest(
                    gatedDecision, coordinatorActive, noActionClaimed));
                inspectionsDone.countDown();
            });
        } finally {
            // Both queued inspections now represent work that could have outlived a bounded
            // invokeEdtBounded wait; always release the synthetic EDT stall.
            releaseEdt.countDown();
        }
        assertTrue(inspectionsDone.await(2, java.util.concurrent.TimeUnit.SECONDS),
            "queued close inspections drain after the EDT stall");
        assertTrue(firstInspection.get() != null && firstInspection.get().dismissedNo(),
            "the first close inspection may perform the No action");
        assertTrue(lateInspection.get() != null && !lateInspection.get().dismissedNo()
                && !lateInspection.get().rejected(),
            "a late inspection observes the shared claim without another action");
        assertEquals(1, gatedCalls.get(),
            "multiple queued inspections produce at most one No button action");
        coordinatorActive.set(false);
        final var cancelledInspection = onEdt(() -> ExternalPsdEditHostProbe.dismissNoForTest(
            gatedDecision, coordinatorActive, noActionClaimed));
        assertTrue(!cancelledInspection.dismissedNo(),
            "an inspection queued after coordinator cancellation cannot act");
        assertEquals(1, gatedCalls.get(),
            "cancelled late inspection does not perform a second action");

        final AtomicInteger failedCalls = new AtomicInteger();
        final var failedDecision = ExternalPsdEditHostProbe.CloseDialogDecision.dismissNo(
            "failing action test", () -> {
                failedCalls.incrementAndGet();
                throw new IllegalStateException("button action failed");
            });
        final AtomicBoolean failedClaim = new AtomicBoolean();
        final var failedInspection = onEdt(() -> ExternalPsdEditHostProbe.dismissNoForTest(
            failedDecision, new AtomicBoolean(true), failedClaim));
        final var failedLateInspection = onEdt(() -> ExternalPsdEditHostProbe.dismissNoForTest(
            failedDecision, new AtomicBoolean(true), failedClaim));
        assertTrue(failedInspection.rejected() && !failedInspection.dismissedNo(),
            "a failed No action is not reported as dismissed");
        assertTrue(!failedLateInspection.dismissedNo(),
            "a late inspection cannot retry a failed claimed action");
        assertEquals(1, failedCalls.get(),
            "a failed action remains bounded to one real button attempt");

        final var crossWindow = ExternalPsdEditHostProbe.classifyCloseDialogForTest(
            "bound-window", List.of(), List.of(
                closeDialog("cross-window", "other-window", false, new AtomicInteger())));
        assertEquals(ExternalPsdEditHostProbe.CloseDialogOutcome.REJECTED,
            crossWindow.outcome(), "a dialog from another window is rejected");
        assertContains(crossWindow.diagnostic(), "owner",
            "cross-window dialog rejection records its owner");
        final var mismatchedOwnerEvidence = ExternalPsdEditHostProbe.classifyCloseDialogForTest(
            "bound-window", List.of(), List.of(
                closeDialog("mismatched-owner", "other-window", true, new AtomicInteger())));
        assertEquals(ExternalPsdEditHostProbe.CloseDialogOutcome.REJECTED,
            mismatchedOwnerEvidence.outcome(), "inconsistent owner evidence is rejected");

        final var multiple = ExternalPsdEditHostProbe.classifyCloseDialogForTest(
            "bound-window", List.of(), List.of(
                closeDialog("dialog-a", "bound-window", true, new AtomicInteger()),
                closeDialog("dialog-b", "bound-window", true, new AtomicInteger())));
        assertEquals(ExternalPsdEditHostProbe.CloseDialogOutcome.REJECTED,
            multiple.outcome(), "multiple close dialogs are rejected");
        assertContains(multiple.diagnostic(), "multiple",
            "multiple dialog rejection is explicit");

        final var unknown = ExternalPsdEditHostProbe.classifyCloseDialogForTest(
            "bound-window", List.of(), List.of(new ExternalPsdEditHostProbe.CloseDialogSnapshot(
                "unknown-dialog", "bound-window", true, true, true, true, "APPLICATION_MODAL", 1,
                List.of("javax.swing.JButton", "javax.swing.JButton"),
                List.of("Yes (Y)", "No (N)"), 0, () -> { }, () -> true)));
        assertEquals(ExternalPsdEditHostProbe.CloseDialogOutcome.REJECTED,
            unknown.outcome(), "unknown option semantics are rejected");
        assertContains(unknown.diagnostic(), "options",
            "unknown option rejection explains the missing exact shape");

        final var missingNoAction = ExternalPsdEditHostProbe.classifyCloseDialogForTest(
            "bound-window", List.of(), List.of(new ExternalPsdEditHostProbe.CloseDialogSnapshot(
                "missing-no-action", "bound-window", true, true, true, true,
                "APPLICATION_MODAL", 1,
                List.of("javax.swing.JButton", "javax.swing.JButton", "javax.swing.JButton"),
                List.of("Yes (Y)", "No (N)", "Cancel (C)"), 0, null, () -> false)));
        assertEquals(ExternalPsdEditHostProbe.CloseDialogOutcome.REJECTED,
            missingNoAction.outcome(), "a verified shape without a No action is rejected");

        final String watchdog = ExternalPsdEditHostProbe.exitThreadDiagnosticForTest();
        assertTrue(watchdog.length() <= ExternalPsdEditHostProbe.EXIT_DIAGNOSTIC_MAX_CHARS,
            "exit watchdog diagnostics have a hard output bound");
        assertContains(watchdog, "JVM exit watchdog", "exit watchdog records its bounded purpose");

        final var preExisting = ExternalPsdEditHostProbe.classifyCloseDialogForTest(
            "bound-window", List.of(oldDialog), List.of(oldDialog));
        assertEquals(ExternalPsdEditHostProbe.CloseDialogOutcome.REJECTED,
            preExisting.outcome(), "a pre-existing owner dialog is not treated as this close");
        assertContains(preExisting.diagnostic(), "modality=",
            "pre-existing dialog rejection records modality evidence");

        final AtomicInteger modelessToolCalls = new AtomicInteger();
        final var oldModelessTool = new ExternalPsdEditHostProbe.CloseDialogSnapshot(
            "javax.swing.JDialog@old-tool", "bound-window", true, true, true, false,
            "MODELESS", 0, List.of(), List.of(), -1,
            modelessToolCalls::incrementAndGet, () -> false);
        final var oldModelessDecision = ExternalPsdEditHostProbe.classifyCloseDialogForTest(
            "bound-window", List.of(oldModelessTool), List.of(oldModelessTool));
        assertEquals(ExternalPsdEditHostProbe.CloseDialogOutcome.NO_DIALOG,
            oldModelessDecision.outcome(),
            "an unchanged exact modeless tool dialog is safely excluded");
        assertContains(oldModelessDecision.diagnostic(), "excludedPreExisting=",
            "safe modeless exclusion records its evidence");
        assertContains(oldModelessDecision.diagnostic(), "modality=MODELESS",
            "modeless dialog evidence records its modality");
        assertContains(oldModelessDecision.diagnostic(), "panes=0",
            "modeless dialog evidence records that it has no option pane");
        assertEquals(0, modelessToolCalls.get(),
            "a pre-existing modeless tool dialog is never operated on");

        final AtomicInteger oldModelessActionCalls = new AtomicInteger();
        final AtomicInteger saveNoCalls = new AtomicInteger();
        final var oldModeless = new ExternalPsdEditHostProbe.CloseDialogSnapshot(
            "javax.swing.JDialog@old-modeless", "bound-window", true, true, true, false,
            "MODELESS", 0, List.of(), List.of(), -1,
            oldModelessActionCalls::incrementAndGet, () -> false);
        final var saveModal = closeDialog("javax.swing.JDialog@save-modal", "bound-window",
            true, saveNoCalls);
        final var dualWindow = ExternalPsdEditHostProbe.classifyCloseDialogForTest(
            "bound-window", List.of(oldModeless), List.of(oldModeless, saveModal));
        assertEquals(ExternalPsdEditHostProbe.CloseDialogOutcome.DISMISS_NO,
            dualWindow.outcome(),
            "a new exact save modal is selected beside an unchanged modeless tool window");
        assertContains(dualWindow.diagnostic(), "excludedPreExisting=",
            "dual-window close records the excluded modeless window");
        dualWindow.dismissNo().run();
        assertEquals(1, saveNoCalls.get(), "the new save modal receives one No action");
        assertEquals(0, oldModelessActionCalls.get(),
            "the pre-existing modeless window receives no action");

        final var changedModality = new ExternalPsdEditHostProbe.CloseDialogSnapshot(
            "javax.swing.JDialog@old-modeless", "bound-window", true, true, true, true,
            "APPLICATION_MODAL", 1,
            List.of("javax.swing.JButton", "javax.swing.JButton", "javax.swing.JButton"),
            List.of("Yes (Y)", "No (N)", "Cancel (C)"), 0, () -> { }, () -> true);
        final var modalityChanged = ExternalPsdEditHostProbe.classifyCloseDialogForTest(
            "bound-window", List.of(oldModeless), List.of(changedModality));
        assertEquals(ExternalPsdEditHostProbe.CloseDialogOutcome.REJECTED,
            modalityChanged.outcome(), "a pre-existing dialog changing modality is rejected");
        assertContains(modalityChanged.diagnostic(), "modality=APPLICATION_MODAL",
            "modality changes remain visible in rejection evidence");

        final var changedPanes = new ExternalPsdEditHostProbe.CloseDialogSnapshot(
            "javax.swing.JDialog@old-modeless", "bound-window", true, true, true, false,
            "MODELESS", 1, List.of("javax.swing.JButton"), List.of("unexpected"), 0,
            () -> { }, () -> false);
        final var panesChanged = ExternalPsdEditHostProbe.classifyCloseDialogForTest(
            "bound-window", List.of(oldModeless), List.of(changedPanes));
        assertEquals(ExternalPsdEditHostProbe.CloseDialogOutcome.REJECTED,
            panesChanged.outcome(), "a pre-existing dialog gaining an option pane is rejected");
        assertContains(panesChanged.diagnostic(), "panes=1",
            "pane changes remain visible in rejection evidence");

        final var changedOwner = new ExternalPsdEditHostProbe.CloseDialogSnapshot(
            "javax.swing.JDialog@old-modeless", "other-window", false, true, true, false,
            "MODELESS", 0, List.of(), List.of(), -1, () -> { }, () -> false);
        final var ownerChanged = ExternalPsdEditHostProbe.classifyCloseDialogForTest(
            "bound-window", List.of(oldModeless), List.of(changedOwner));
        assertEquals(ExternalPsdEditHostProbe.CloseDialogOutcome.REJECTED,
            ownerChanged.outcome(), "a pre-existing dialog changing owner is rejected");
        assertContains(ownerChanged.diagnostic(), "owner=other-window",
            "owner changes remain visible in rejection evidence");

        final var changedVisibility = new ExternalPsdEditHostProbe.CloseDialogSnapshot(
            "javax.swing.JDialog@old-modeless", "bound-window", true, false, true, false,
            "MODELESS", 0, List.of(), List.of(), -1, () -> { }, () -> false);
        final var visibilityChanged = ExternalPsdEditHostProbe.classifyCloseDialogForTest(
            "bound-window", List.of(oldModeless), List.of(changedVisibility));
        assertEquals(ExternalPsdEditHostProbe.CloseDialogOutcome.REJECTED,
            visibilityChanged.outcome(), "a pre-existing dialog becoming hidden is rejected");

        final var unknownModality = new ExternalPsdEditHostProbe.CloseDialogSnapshot(
            "javax.swing.JDialog@old-modeless", "bound-window", true, true, true, false,
            "UNKNOWN", 0, List.of(), List.of(), -1, () -> { }, () -> false);
        final var unknownPreExisting = ExternalPsdEditHostProbe.classifyCloseDialogForTest(
            "bound-window", List.of(oldModeless), List.of(unknownModality));
        assertEquals(ExternalPsdEditHostProbe.CloseDialogOutcome.REJECTED,
            unknownPreExisting.outcome(), "unknown pre-existing modality is rejected");
        assertContains(unknownPreExisting.diagnostic(), "modality=UNKNOWN",
            "unknown modality is explicit in rejection evidence");

        final var changedIdentity = ExternalPsdEditHostProbe.classifyCloseDialogForTest(
            "bound-window", List.of(oldModeless), List.of(saveModal));
        assertEquals(ExternalPsdEditHostProbe.CloseDialogOutcome.REJECTED,
            changedIdentity.outcome(),
            "a changed pre-existing identity cannot be mistaken for a new save modal");
        assertContains(changedIdentity.diagnostic(), "pre-existing",
            "identity changes explain the retained before-window evidence");

        final var preExistingPane = new ExternalPsdEditHostProbe.CloseDialogSnapshot(
            "javax.swing.JDialog@old-pane", "bound-window", true, true, true, false,
            "MODELESS", 1, List.of("javax.swing.JButton"), List.of("tool"), 0,
            () -> { }, () -> false);
        final var preExistingPaneDecision = ExternalPsdEditHostProbe.classifyCloseDialogForTest(
            "bound-window", List.of(preExistingPane), List.of(preExistingPane));
        assertEquals(ExternalPsdEditHostProbe.CloseDialogOutcome.REJECTED,
            preExistingPaneDecision.outcome(),
            "a pre-existing JOptionPane is not silently excluded");

        final var closeTrace = new ExternalPsdEditHostProbe.CloseSessionTrace("close-test");
        closeTrace.recordBefore(List.of(oldModelessTool));
        closeTrace.recordDispatchAttempt();
        closeTrace.recordDispatch(ExternalPsdEditHostProbe.CloseDispatchResult.dispatched(
            "event=WINDOW_CLOSING target=bound-window"));
        closeTrace.recordInspection(List.of(newDialog));
        closeTrace.recordInspection(List.of(newDialog));
        final String closeTraceDiagnostic = closeTrace.diagnostic();
        assertContains(closeTraceDiagnostic, "closeSession=close-test",
            "close diagnostics identify one coordinator session");
        assertContains(closeTraceDiagnostic, "dispatchAttempts=1",
            "close diagnostics count native dispatch attempts");
        assertContains(closeTraceDiagnostic, "inspectionCount=2",
            "close diagnostics count repeated inspections");
        assertContains(closeTraceDiagnostic, "beforeToDispatchMs=",
            "close diagnostics record snapshot-to-dispatch timing");
        assertContains(closeTraceDiagnostic, "dispatchToFirstInspectionMs=",
            "close diagnostics record dispatch-to-inspection timing");
        assertContains(closeTraceDiagnostic, "modality=",
            "close diagnostics include modality in before/after inventories");
        assertContains(closeTraceDiagnostic, "optionLabels=",
            "close diagnostics include option labels in before/after inventories");
        assertTrue(utf8Bytes(closeTraceDiagnostic)
                <= ExternalPsdEditHostProbe.CLOSE_TRACE_MAX_BYTES,
            "close trace diagnostics have a hard UTF-8 byte bound");

        final var generatedSessionA = new ExternalPsdEditHostProbe.CloseSessionTrace();
        final var generatedSessionB = new ExternalPsdEditHostProbe.CloseSessionTrace();
        assertTrue(!generatedSessionA.sessionId().equals(generatedSessionB.sessionId()),
            "repeated close coordinators receive distinct session identities");

        final Properties state = new Properties();
        state.setProperty("gui.after.diagnostic", "auto-import not attempted");
        ExternalPsdEditHostProbe.recordGuiTargetState(state, "after",
            new ExternalPsdEditHostProbe.GuiTargetState(true, "binding", 7L, "raw", true, ""));
        assertTrue(!state.containsKey("gui.after.diagnostic"),
            "a successful target observation clears the stale diagnostic placeholder");
    }

    private static void testCloseDiagnosticBudgets() {
        final String longLabel = "标签🚀".repeat(512);
        assertTrue(utf8Bytes(longLabel)
                > ExternalPsdEditHostProbe.CLOSE_DIALOG_FIELD_MAX_BYTES,
            "long-label input crosses the per-field byte boundary");
        final ExternalPsdEditHostProbe.CloseDialogSnapshot longLabelDialog =
            new ExternalPsdEditHostProbe.CloseDialogSnapshot(
                "long-label-dialog", "bound-window", true, true, true, true,
                "APPLICATION_MODAL", 1,
                List.of("javax.swing.JButton", "javax.swing.JButton", "javax.swing.JButton"),
                List.of(longLabel, "No (N)", "Cancel (C)"), 0, () -> { }, () -> true);
        final var longLabelTrace = new ExternalPsdEditHostProbe.CloseSessionTrace("long-label");
        longLabelTrace.recordBefore(List.of(longLabelDialog));
        longLabelTrace.recordDispatchAttempt();
        longLabelTrace.recordDispatch(ExternalPsdEditHostProbe.CloseDispatchResult.dispatched(
            "dispatch-" + "界".repeat(512)));
        longLabelTrace.recordInspection(List.of(longLabelDialog));
        longLabelTrace.recordReason("rejected identity=" + "拒绝".repeat(1024));
        final String longLabelDiagnostic = longLabelTrace.diagnostic();
        assertTrue(utf8Bytes(longLabelDiagnostic)
                <= ExternalPsdEditHostProbe.CLOSE_TRACE_MAX_BYTES,
            "Unicode labels stay inside the trace UTF-8 byte budget");
        assertNoUnpairedSurrogates(longLabelDiagnostic,
            "UTF-8 truncation does not split a surrogate pair");
        assertContains(longLabelDiagnostic, "optionLabels=",
            "long-label trace retains label evidence");
        assertContains(longLabelDiagnostic, "…",
            "long-label trace records truncation");

        final List<ExternalPsdEditHostProbe.CloseDialogSnapshot> manyDialogs = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            manyDialogs.add(new ExternalPsdEditHostProbe.CloseDialogSnapshot(
                "dialog-" + index, "bound-window", true, true, true, false,
                "MODELESS", 0, List.of(), List.of(), -1, () -> { }, () -> false));
        }
        final var manyDialogTrace = new ExternalPsdEditHostProbe.CloseSessionTrace("many-dialogs");
        manyDialogTrace.recordBefore(manyDialogs);
        manyDialogTrace.recordInspection(manyDialogs);
        final String manyDialogDiagnostic = manyDialogTrace.diagnostic();
        assertTrue(utf8Bytes(manyDialogDiagnostic)
                <= ExternalPsdEditHostProbe.CLOSE_TRACE_MAX_BYTES,
            "more than 32 dialogs stay inside the trace UTF-8 byte budget");
        assertContains(manyDialogDiagnostic, "count=40",
            "dialog inventory records the full dialog count");
        assertContains(manyDialogDiagnostic, "truncated=true",
            "dialog inventory records the dialog-count truncation marker");

        final String longIdentity = "identity-" + "界".repeat(256);
        final String longOwner = "owner-" + "界".repeat(256);
        final List<ExternalPsdEditHostProbe.CloseDialogSnapshot> oversizedDialogs =
            new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            oversizedDialogs.add(new ExternalPsdEditHostProbe.CloseDialogSnapshot(
                longIdentity + index, longOwner, true, true, true, true,
                "APPLICATION_MODAL", 1,
                List.of("javax.swing.JButton", "javax.swing.JButton", "javax.swing.JButton"),
                List.of(longLabel, "No (N)", "Cancel (C)"), 0, () -> { }, () -> true));
        }
        int rawInventoryBytes = 0;
        for (final ExternalPsdEditHostProbe.CloseDialogSnapshot dialog : oversizedDialogs) {
            rawInventoryBytes += utf8Bytes(dialog.diagnostic());
        }
        assertTrue(rawInventoryBytes > ExternalPsdEditHostProbe.CLOSE_TRACE_BEFORE_MAX_BYTES
                && rawInventoryBytes > ExternalPsdEditHostProbe.CLOSE_TRACE_AFTER_MAX_BYTES,
            "before and after inputs both cross their independent byte boundaries");
        final var oversizedTrace = new ExternalPsdEditHostProbe.CloseSessionTrace("oversized");
        oversizedTrace.recordBefore(oversizedDialogs);
        oversizedTrace.recordDispatchAttempt();
        oversizedTrace.recordDispatch(ExternalPsdEditHostProbe.CloseDispatchResult.rejected(
            "dispatch-rejected-" + longIdentity));
        oversizedTrace.recordInspection(oversizedDialogs);
        oversizedTrace.recordReason("rejected identity " + longIdentity
            + " reason=" + "未知".repeat(2048));
        final String oversizedDiagnostic = oversizedTrace.diagnostic();
        assertTrue(utf8Bytes(oversizedDiagnostic)
                <= ExternalPsdEditHostProbe.CLOSE_TRACE_MAX_BYTES,
            "oversized before and after inventories stay bounded independently");
        assertContains(oversizedDiagnostic, "closeSession=oversized",
            "oversized trace retains session identity");
        assertContains(oversizedDiagnostic, "dispatchAttempts=1",
            "oversized trace retains dispatch count");
        assertContains(oversizedDiagnostic, "inspectionCount=1",
            "oversized trace retains inspection count");
        assertContains(oversizedDiagnostic, "beforeToDispatchMs=",
            "oversized trace retains snapshot timing");
        assertContains(oversizedDiagnostic, "dispatchToFirstInspectionMs=",
            "oversized trace retains inspection timing");
        assertContains(oversizedDiagnostic, "before=",
            "oversized trace retains before label");
        assertContains(oversizedDiagnostic, "after=",
            "oversized trace retains after label");
        assertContains(oversizedDiagnostic, "dispatch=",
            "oversized trace retains dispatch label");
        assertContains(oversizedDiagnostic, "reason=",
            "oversized trace retains reason label");
        assertContains(oversizedDiagnostic, "truncated=true",
            "oversized trace retains its truncation marker");
        assertContains(oversizedDiagnostic, "rejected identity",
            "oversized trace retains the identity rejection reason prefix");
        assertTrue(utf8Bytes(diagnosticField(oversizedDiagnostic, "before=", " after="))
                <= ExternalPsdEditHostProbe.CLOSE_TRACE_BEFORE_MAX_BYTES,
            "before inventory uses its own byte budget");
        assertTrue(utf8Bytes(diagnosticField(oversizedDiagnostic, "after=", " dispatch="))
                <= ExternalPsdEditHostProbe.CLOSE_TRACE_AFTER_MAX_BYTES,
            "after inventory uses its own byte budget");
        assertTrue(utf8Bytes(diagnosticField(oversizedDiagnostic, "dispatch=", " reason="))
                <= ExternalPsdEditHostProbe.CLOSE_TRACE_DISPATCH_MAX_BYTES,
            "dispatch evidence uses its own byte budget");
        assertTrue(utf8Bytes(diagnosticField(oversizedDiagnostic, "reason=", null))
                <= ExternalPsdEditHostProbe.CLOSE_TRACE_REASON_MAX_BYTES,
            "decision reason uses its own byte budget");
        assertTrue(oversizedDiagnostic.indexOf("before=")
                < oversizedDiagnostic.indexOf("after="),
            "before is emitted before after");
        assertTrue(oversizedDiagnostic.indexOf("after=")
                < oversizedDiagnostic.indexOf("dispatch="),
            "after is emitted before dispatch");
        assertTrue(oversizedDiagnostic.indexOf("dispatch=")
                < oversizedDiagnostic.indexOf("reason="),
            "dispatch is emitted before reason");

        final String resultDiagnostic = ExternalPsdEditHostProbe.closeDiagnosticForTest(
            oversizedTrace, "result-reason=" + "结果".repeat(4096));
        assertTrue(utf8Bytes(resultDiagnostic)
                <= ExternalPsdEditHostProbe.CLOSE_DIAGNOSTIC_MAX_BYTES,
            "result plus trace has a hard UTF-8 byte bound");
        assertNoUnpairedSurrogates(resultDiagnostic,
            "final close diagnostic does not split a surrogate pair");
        assertContains(resultDiagnostic, "result-reason=",
            "final close diagnostic retains the result reason prefix");
        assertContains(resultDiagnostic, "closeSession=oversized",
            "final close diagnostic retains the trace session");
        assertContains(resultDiagnostic, " after=",
            "final close diagnostic retains the after field");
        assertContains(resultDiagnostic, " reason=",
            "final close diagnostic retains the reason field");
        final int separator = resultDiagnostic.indexOf("; ");
        assertTrue(separator > 0, "final close diagnostic retains its result/trace separator");
        assertTrue(utf8Bytes(resultDiagnostic.substring(0, separator))
                <= ExternalPsdEditHostProbe.CLOSE_RESULT_REASON_MAX_BYTES,
            "result reason uses its own byte budget");
        assertTrue(utf8Bytes(resultDiagnostic.substring(separator + 2))
                <= ExternalPsdEditHostProbe.CLOSE_TRACE_MAX_BYTES,
            "final trace uses its own byte budget");
    }

    private static String diagnosticField(final String diagnostic, final String startLabel,
        final String endLabel) {
        final int start = diagnostic.indexOf(startLabel);
        assertTrue(start >= 0, "diagnostic field is present: " + startLabel);
        final int valueStart = start + startLabel.length();
        final int end = endLabel == null ? diagnostic.length() : diagnostic.indexOf(endLabel,
            valueStart);
        assertTrue(end >= valueStart, "diagnostic field has a bounded end: " + startLabel);
        return diagnostic.substring(valueStart, end);
    }

    private static ExternalPsdEditHostProbe.CloseDialogSnapshot closeDialog(
        final String dialog, final String owner, final boolean ownerMatches,
        final AtomicInteger noCalls) {
        return new ExternalPsdEditHostProbe.CloseDialogSnapshot(
            dialog, owner, ownerMatches, true, true, true, "APPLICATION_MODAL", 1,
            List.of("javax.swing.JButton", "javax.swing.JButton", "javax.swing.JButton"),
            List.of("Yes (Y)", "No (N)", "Cancel (C)"), 0, noCalls::incrementAndGet,
            () -> true);
    }

    private static void restoreProperty(final String name, final String value) {
        if (value == null) System.clearProperty(name);
        else System.setProperty(name, value);
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
        final var stableA = new ExternalPsdEditHostProbe.TargetIdentity(
            "document-a", "model-a", "binding-a", 7L, "model-image-a", "art-mesh-a", "raw-a");
        final var stableB = new ExternalPsdEditHostProbe.TargetIdentity(
            "document-a", "model-a", "binding-a", 7L, "model-image-a", "art-mesh-a", "raw-b");
        final var before = new ExternalPsdEditHostProbe.GuiTargetState(
            true, stableA, false, "");
        final var wrapperAppliedSameRaw = new ExternalPsdEditHostProbe.GuiTargetState(
            true, stableA, true, "");
        final var written = new PsdValidationContent.Fingerprint(
            "b".repeat(64), new PsdValidationContent.Bounds(450, 450, 550, 550),
            100, 100, List.of(0, 1, 2));
        final var different = new PsdValidationContent.Fingerprint(
            "c".repeat(64), new PsdValidationContent.Bounds(450, 450, 550, 550),
            100, 100, List.of(0, 1, 2));

        assertTrue(!ExternalPsdEditHostProbe.acceptsAutoImport(
            before, wrapperAppliedSameRaw, "raw-a"),
            "legacy wrapper false-to-true flag is never native application evidence");
        assertTrue(ExternalPsdEditHostProbe.acceptsNativeGuiApplication(
            before, wrapperAppliedSameRaw, stableA, written, written),
            "same raw is accepted only when fresh native RGB matches the written mutation");
        assertTrue(!ExternalPsdEditHostProbe.acceptsNativeGuiApplication(
            before, wrapperAppliedSameRaw, stableA, written, different),
            "wrapper transition with a mismatched fresh RGB is rejected");
        assertTrue(ExternalPsdEditHostProbe.acceptsNativeGuiApplication(
            before, new ExternalPsdEditHostProbe.GuiTargetState(true, stableB, false, ""),
            stableA, written, written),
            "incoming current raw with matching fresh RGB is accepted independently of wrapper flag");
        assertTrue(!ExternalPsdEditHostProbe.acceptsNativeGuiApplication(
            before, new ExternalPsdEditHostProbe.GuiTargetState(true,
                new ExternalPsdEditHostProbe.TargetIdentity(
                    "document-b", "model-a", "binding-a", 7L,
                    "model-image-a", "art-mesh-a", "raw-b"), false, ""),
            stableA, written, written),
            "document switch is rejected even when current RGB matches");
        assertTrue(!ExternalPsdEditHostProbe.acceptsNativeGuiApplication(
            new ExternalPsdEditHostProbe.GuiTargetState(true, stableA, true, ""),
            new ExternalPsdEditHostProbe.GuiTargetState(true, stableB, false, ""),
            stableA, written, written),
            "an initially replaced target is not a new GUI application");
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

    private static void putU32(final byte[] bytes, final int offset, final long value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }

    private static String sha256(final byte[] bytes) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            final StringBuilder hex = new StringBuilder(digest.length * 2);
            for (final byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new AssertionError("SHA-256 is unavailable", failure);
        }
    }

    private static int utf8Bytes(final String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void assertNoUnpairedSurrogates(final String value, final String message) {
        for (int index = 0; index < value.length(); index++) {
            final char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                assertTrue(index + 1 < value.length()
                        && Character.isLowSurrogate(value.charAt(index + 1)), message);
            } else if (Character.isLowSurrogate(current)) {
                assertTrue(index > 0 && Character.isHighSurrogate(value.charAt(index - 1)),
                    message);
            }
        }
    }

    private static void assertArrayEquals(final byte[] expected, final byte[] actual,
        final String message) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(message + " expected length=" + expected.length
                + " actual length=" + actual.length);
        }
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
