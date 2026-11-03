package dev.turboism.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fault-injection matrix for the recovery point that precedes a destructive typed-config migration.
 *
 * <p>The invariant every case pins is the same: whatever fails — the recovery area, the process, a
 * concurrent writer, a swapped path — the bytes the migration was about to replace stay intact and
 * remain the exact image an operator can restore. Recovery points are captured by
 * {@link TypedConfigDocumentStore#recordRecoveryPoint} and never consulted by {@code read}, so an
 * unusable recovery area cannot take readable documents offline.
 *
 * <p>These cases run without a Cubism host: a typed config document, a recovery point and a
 * temporary-file-plus-atomic-rename are the whole surface under test. Power loss is simulated with
 * the two observable crash windows the atomic replace admits — before the rename, and after the
 * fsync of a replacement temporary that never reached the target.
 */
class TypedConfigRecoveryPointTest {

    private static final String DOCUMENT = "settings/sync.cfg";

    @TempDir
    Path temporary;

    /** Portable IO-error injection: the recovery root exists but cannot become a directory. */
    @Test
    void unreachableRecoveryAreaFailsTheCaptureWithoutTouchingTheDocument() throws Exception {
        final Path root = temporary.resolve("typed-config");
        final TypedConfigDocumentStore store = new TypedConfigDocumentStore(root);
        final TypedConfigDocumentStore.StoredDocument stored = document(1, 7, "enabled", "true");
        store.writeAtomic(DOCUMENT, stored);
        final byte[] before = Files.readAllBytes(root.resolve(DOCUMENT));
        Files.write(root.resolve(".recovery-points"), new byte[] {0x00});

        assertThrows(IOException.class, () -> store.recordRecoveryPoint(DOCUMENT, stored));

        assertArrayEquals(before, Files.readAllBytes(root.resolve(DOCUMENT)));
        assertEquals("true", store.read(DOCUMENT).orElseThrow().encodedValues().get("enabled"));
        assertTrue(temporaryFiles(root).isEmpty(), "a failed capture must not leave a temporary file behind");
    }

    /**
     * A recovery namespace that cannot become a directory fails the capture instead of replacing
     * whatever occupies it, even though the older point still exists on disk.
     */
    @Test
    void occupiedRecoveryNamespaceFailsTheCaptureWithoutReplacingForeignBytes() throws Exception {
        final Path root = temporary.resolve("typed-config");
        final TypedConfigDocumentStore store = new TypedConfigDocumentStore(root);
        store.writeAtomic(DOCUMENT, document(1, 7, "enabled", "true"));
        final Path directory = store.recordRecoveryPoint(DOCUMENT, document(1, 7, "enabled", "true"))
                .getParent();
        store.writeAtomic(DOCUMENT, document(1, 9, "enabled", "false"));
        final byte[] current = Files.readAllBytes(root.resolve(DOCUMENT));
        final byte[] occupant = {0x00};
        Files.move(directory, temporary.resolve("recovery-moved"));
        Files.write(directory, occupant);

        assertThrows(IOException.class, () -> store.recordRecoveryPoint(DOCUMENT, document(1, 9, "enabled", "false")));

        assertArrayEquals(occupant, Files.readAllBytes(directory), "foreign bytes must never be replaced by a capture");
        assertArrayEquals(current, Files.readAllBytes(root.resolve(DOCUMENT)));
        assertEquals("false", store.read(DOCUMENT).orElseThrow().encodedValues().get("enabled"));
    }

    /** Power loss between capturing the image and replacing the document. */
    @Test
    void interruptionBetweenCaptureAndReplacementKeepsThePreMigrationBytes() throws Exception {
        final Path root = temporary.resolve("typed-config");
        final TypedConfigDocumentStore store = new TypedConfigDocumentStore(root);
        final TypedConfigDocumentStore.StoredDocument before = document(1, 7, "enabled", "true");
        store.writeAtomic(DOCUMENT, before);
        final byte[] original = Files.readAllBytes(root.resolve(DOCUMENT));
        final Path point = store.recordRecoveryPoint(DOCUMENT, before);

        final TypedConfigDocumentStore restarted = new TypedConfigDocumentStore(root);

        assertEquals(
                1, restarted.read(DOCUMENT).orElseThrow().schemaVersion(), "the restart must still see the old schema");
        assertEquals(7, restarted.read(DOCUMENT).orElseThrow().revision());
        assertArrayEquals(original, Files.readAllBytes(root.resolve(DOCUMENT)));
        assertArrayEquals(original, Files.readAllBytes(point));
        assertPointNameMatchesContent(point);
        assertPreMigrationBytesRecoverable(restarted, original);
        assertTrue(temporaryFiles(root).isEmpty());
    }

    /**
     * Power loss after the replacement temporary was fsynced but before the atomic rename: the
     * orphaned temporary must never be read as the document, complete or truncated.
     */
    @Test
    void leftoverReplacementTemporariesAreNeverReadAsTheDocument() throws Exception {
        final Path root = temporary.resolve("typed-config");
        final TypedConfigDocumentStore store = new TypedConfigDocumentStore(root);
        final TypedConfigDocumentStore.StoredDocument before = document(1, 7, "enabled", "true");
        store.writeAtomic(DOCUMENT, before);
        final byte[] original = Files.readAllBytes(root.resolve(DOCUMENT));
        store.recordRecoveryPoint(DOCUMENT, before);
        final byte[] migrated = encodedImage(document(2, 8, "enabled", "true"));
        final Path directory = root.resolve("settings");
        Files.write(directory.resolve(".sync.cfg.turboism-config-" + UUID.randomUUID() + ".tmp"), migrated);
        Files.write(
                directory.resolve(".sync.cfg.turboism-config-" + UUID.randomUUID() + ".tmp"),
                Arrays.copyOfRange(migrated, 0, migrated.length / 2));

        final TypedConfigDocumentStore restarted = new TypedConfigDocumentStore(root);

        final TypedConfigDocumentStore.StoredDocument replayed =
                restarted.read(DOCUMENT).orElseThrow();
        assertEquals(1, replayed.schemaVersion());
        assertEquals(7, replayed.revision());
        assertEquals("true", replayed.encodedValues().get("enabled"));
        assertArrayEquals(original, Files.readAllBytes(root.resolve(DOCUMENT)));
        assertPreMigrationBytesRecoverable(restarted, original);
    }

    /** Concurrent writers capturing the same pre-migration image converge on one complete point. */
    @Test
    void concurrentCapturesOfTheSameImageStoreOneCompletePoint() throws Exception {
        final Path root = temporary.resolve("typed-config");
        final TypedConfigDocumentStore store = new TypedConfigDocumentStore(root);
        final TypedConfigDocumentStore.StoredDocument stored = document(1, 7, "enabled", "true");
        store.writeAtomic(DOCUMENT, stored);
        final byte[] original = Files.readAllBytes(root.resolve(DOCUMENT));
        final int writers = 8;
        final CyclicBarrier barrier = new CyclicBarrier(writers);
        final ExecutorService pool = Executors.newFixedThreadPool(writers);
        final List<Path> captured = new ArrayList<>();
        try {
            final List<Future<Path>> results = new ArrayList<>();
            for (int index = 0; index < writers; index++) {
                results.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return store.recordRecoveryPoint(DOCUMENT, stored);
                }));
            }
            for (final Future<Path> result : results) {
                captured.add(result.get(10, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, Set.copyOf(captured).size(), "every concurrent capture must resolve to one point");
        final List<Path> retained = store.recoveryPoints(DOCUMENT);
        assertEquals(1, retained.size(), "one image must not accumulate duplicate points");
        assertEquals(captured.get(0), retained.get(0));
        assertArrayEquals(original, Files.readAllBytes(retained.get(0)));
        assertPointNameMatchesContent(retained.get(0));
        assertTrue(temporaryFiles(root).isEmpty());
        assertArrayEquals(original, Files.readAllBytes(root.resolve(DOCUMENT)));
    }

    /**
     * A concurrent writer racing the capture either leaves a complete pre-migration image behind or
     * makes the capture fail closed; a torn image is never stored.
     */
    @Test
    void concurrentCaptureAndDocumentWriteNeverStoreATornImage() throws Exception {
        final byte[] migrated = encodedImage(document(2, 8, "enabled", "true"));
        for (int iteration = 0; iteration < 20; iteration++) {
            final Path root = temporary.resolve("race-" + iteration);
            final TypedConfigDocumentStore store = new TypedConfigDocumentStore(root);
            final TypedConfigDocumentStore.StoredDocument before = document(1, 7, "enabled", "true");
            store.writeAtomic(DOCUMENT, before);
            final byte[] original = Files.readAllBytes(root.resolve(DOCUMENT));
            final CyclicBarrier barrier = new CyclicBarrier(2);
            final ExecutorService pool = Executors.newFixedThreadPool(2);
            final Optional<IOException> captureFailure;
            try {
                final Future<Optional<IOException>> capture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        store.recordRecoveryPoint(DOCUMENT, before);
                        return Optional.empty();
                    } catch (IOException expected) {
                        return Optional.of(expected);
                    }
                });
                final Future<?> replacement = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    store.writeAtomic(DOCUMENT, document(2, 8, "enabled", "true"));
                    return null;
                });
                captureFailure = capture.get(10, TimeUnit.SECONDS);
                replacement.get(10, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            captureFailure.ifPresent(failure -> assertTrue(
                    failure.getMessage().contains("changed"), "only a changed document may abort a capture"));
            assertArrayEquals(
                    migrated, Files.readAllBytes(root.resolve(DOCUMENT)), "the replacement image must never be torn");
            for (final Path point : store.recoveryPoints(DOCUMENT)) {
                assertArrayEquals(
                        original, Files.readAllBytes(point), "a stored point must be the whole pre-migration image");
                assertPointNameMatchesContent(point);
            }
            assertTrue(temporaryFiles(root).isEmpty());
        }
    }

    /** Symlink attack on the recovery root: nothing is written through the link. */
    @Test
    void symlinkedRecoveryRootIsRejectedBeforeCapture() throws Exception {
        final Path root = temporary.resolve("typed-config");
        final TypedConfigDocumentStore store = new TypedConfigDocumentStore(root);
        final TypedConfigDocumentStore.StoredDocument stored = document(1, 7, "enabled", "true");
        store.writeAtomic(DOCUMENT, stored);
        final byte[] original = Files.readAllBytes(root.resolve(DOCUMENT));
        assumeSymlinksSupported();
        final Path outside = Files.createDirectories(temporary.resolve("outside"));
        Files.createSymbolicLink(root.resolve(".recovery-points"), outside);

        assertThrows(IOException.class, () -> store.recordRecoveryPoint(DOCUMENT, stored));

        assertArrayEquals(original, Files.readAllBytes(root.resolve(DOCUMENT)));
        try (Stream<Path> written = Files.walk(outside)) {
            assertTrue(
                    written.filter(Files::isRegularFile).toList().isEmpty(),
                    "no recovery point may be written through a symlinked recovery root");
        }
    }

    /** Path replacement attack: the document parent becomes a link before the capture. */
    @Test
    void parentDirectorySwappedForASymlinkIsRejectedBeforeCapture() throws Exception {
        final Path root = temporary.resolve("typed-config");
        final TypedConfigDocumentStore store = new TypedConfigDocumentStore(root);
        final TypedConfigDocumentStore.StoredDocument stored = document(1, 7, "enabled", "true");
        store.writeAtomic(DOCUMENT, stored);
        final byte[] original = Files.readAllBytes(root.resolve(DOCUMENT));
        assumeSymlinksSupported();
        final Path moved = temporary.resolve("settings-moved");
        Files.move(root.resolve("settings"), moved);
        final Path outside = Files.createDirectories(temporary.resolve("outside-settings"));
        Files.createSymbolicLink(root.resolve("settings"), outside);

        assertThrows(IOException.class, () -> store.recordRecoveryPoint(DOCUMENT, stored));

        assertFalse(Files.exists(outside.resolve("sync.cfg")), "the capture must not write through the link");
        assertArrayEquals(
                original, Files.readAllBytes(moved.resolve("sync.cfg")), "the pre-migration bytes survive the attack");
    }

    @Test
    void boundedRetentionKeepsTheNewestPointsAndPrunesTheOldest() throws Exception {
        final Path root = temporary.resolve("typed-config");
        final TypedConfigDocumentStore store = new TypedConfigDocumentStore(root);
        final List<byte[]> images = new ArrayList<>();
        for (int revision = 1; revision <= 5; revision++) {
            final TypedConfigDocumentStore.StoredDocument stored =
                    document(1, revision, "enabled", String.valueOf(revision % 2 == 0));
            store.writeAtomic(DOCUMENT, stored);
            images.add(Files.readAllBytes(root.resolve(DOCUMENT)));
            store.recordRecoveryPoint(DOCUMENT, stored);
        }

        final List<Path> retained = store.recoveryPoints(DOCUMENT);

        assertEquals(TypedConfigDocumentStore.RECOVERY_POINT_RETENTION, retained.size());
        assertEquals(List.of(5L, 4L, 3L), revisions(retained), "retention must keep the newest document states");
        assertArrayEquals(images.get(4), Files.readAllBytes(retained.get(0)));
        assertArrayEquals(images.get(2), Files.readAllBytes(retained.get(2)));
        retained.forEach(TypedConfigRecoveryPointTest::assertPointNameMatchesContentUnchecked);
        assertTrue(temporaryFiles(root).isEmpty());
    }

    /** A damaged point with a matching name is rewritten instead of trusted. */
    @Test
    void damagedPointFileIsRepairedOnRetryInsteadOfTrusted() throws Exception {
        final Path root = temporary.resolve("typed-config");
        final TypedConfigDocumentStore store = new TypedConfigDocumentStore(root);
        final TypedConfigDocumentStore.StoredDocument stored = document(1, 7, "enabled", "true");
        store.writeAtomic(DOCUMENT, stored);
        final byte[] original = Files.readAllBytes(root.resolve(DOCUMENT));
        final Path point = store.recordRecoveryPoint(DOCUMENT, stored);
        Files.write(point, Arrays.copyOfRange(original, 0, original.length / 2));

        final Path repaired = store.recordRecoveryPoint(DOCUMENT, stored);

        assertEquals(point, repaired, "a retry must repair the same point instead of adding a duplicate");
        assertArrayEquals(original, Files.readAllBytes(repaired), "a damaged point must never be trusted");
        assertArrayEquals(original, Files.readAllBytes(root.resolve(DOCUMENT)));
    }

    /** A damaged recovery area must never take a document that needs no migration offline. */
    @Test
    void aDamagedRecoveryAreaNeverBlocksReadingACurrentDocument() throws Exception {
        final Path root = temporary.resolve("typed-config");
        final TypedConfigDocumentStore store = new TypedConfigDocumentStore(root);
        store.writeAtomic(DOCUMENT, document(2, 8, "enabled", "true"));
        final byte[] current = Files.readAllBytes(root.resolve(DOCUMENT));
        Files.write(root.resolve(".recovery-points"), new byte[] {0x00});

        final TypedConfigDocumentStore.StoredDocument replayed =
                store.read(DOCUMENT).orElseThrow();

        assertEquals(2, replayed.schemaVersion());
        assertEquals("true", replayed.encodedValues().get("enabled"));
        assertArrayEquals(current, Files.readAllBytes(root.resolve(DOCUMENT)));
        assertThrows(
                IOException.class,
                () -> store.recoveryPoints(DOCUMENT),
                "a damaged recovery area must be reported, not reported as empty");
    }

    /** A snapshot that no longer matches the stored bytes is rejected instead of captured. */
    @Test
    void captureRejectsASnapshotThatNoLongerMatchesTheStoredBytes() throws Exception {
        final Path root = temporary.resolve("typed-config");
        final TypedConfigDocumentStore store = new TypedConfigDocumentStore(root);
        store.writeAtomic(DOCUMENT, document(1, 7, "enabled", "true"));
        final TypedConfigDocumentStore.StoredDocument stale =
                store.read(DOCUMENT).orElseThrow();
        store.writeAtomic(DOCUMENT, document(1, 9, "enabled", "false"));
        final byte[] current = Files.readAllBytes(root.resolve(DOCUMENT));

        final IOException failure = assertThrows(IOException.class, () -> store.recordRecoveryPoint(DOCUMENT, stale));

        assertTrue(failure.getMessage().contains("changed"), failure.getMessage());
        assertArrayEquals(
                current,
                Files.readAllBytes(root.resolve(DOCUMENT)),
                "a rejected capture must not overwrite newer bytes");
        assertTrue(
                store.recoveryPoints(DOCUMENT).isEmpty(),
                "bytes the migration never saw must not become a recovery point");
    }

    /** The retained image restores the pre-migration document through the production reader. */
    @Test
    void recoveryPointBytesReReadAsThePreMigrationDocument() throws Exception {
        final Path root = temporary.resolve("typed-config");
        final TypedConfigDocumentStore store = new TypedConfigDocumentStore(root);
        final TypedConfigDocumentStore.StoredDocument before = document(1, 7, "enabled", "true");
        store.writeAtomic(DOCUMENT, before);
        final Path point = store.recordRecoveryPoint(DOCUMENT, before);
        store.writeAtomic(DOCUMENT, document(2, 8, "enabled", "true"));
        final byte[] recovered = Files.readAllBytes(point);

        Files.write(root.resolve(DOCUMENT), recovered);

        final TypedConfigDocumentStore.StoredDocument restored =
                store.read(DOCUMENT).orElseThrow();
        assertEquals(1, restored.schemaVersion());
        assertEquals(7, restored.revision());
        assertEquals("true", restored.encodedValues().get("enabled"));
        assertArrayEquals(recovered, Files.readAllBytes(root.resolve(DOCUMENT)));
    }

    /** The recovery namespace belongs to the store, not to a document schema. */
    @Test
    void recoveryNamespaceCannotBeDeclaredAsADocumentPath() throws Exception {
        final Path root = temporary.resolve("typed-config");
        final TypedConfigDocumentStore store = new TypedConfigDocumentStore(root);

        assertThrows(
                IOException.class,
                () -> store.writeAtomic(".recovery-points/foreign.cfg", document(1, 1, "enabled", "true")));
        assertThrows(IOException.class, () -> store.read(".recovery-points/foreign.cfg"));

        assertTrue(Files.notExists(root.resolve(".recovery-points/foreign.cfg")));
    }

    private static TypedConfigDocumentStore.StoredDocument document(
            final int version, final long revision, final String key, final String value) {
        return new TypedConfigDocumentStore.StoredDocument(version, revision, Map.of(key, value));
    }

    /** Produces a canonical document image through the production writer instead of a hand encoder. */
    private byte[] encodedImage(final TypedConfigDocumentStore.StoredDocument document) throws Exception {
        final Path scratch = temporary.resolve("scratch");
        final TypedConfigDocumentStore store = new TypedConfigDocumentStore(scratch);
        store.writeAtomic(DOCUMENT, document);
        return Files.readAllBytes(scratch.resolve(DOCUMENT));
    }

    private static void assertPreMigrationBytesRecoverable(final TypedConfigDocumentStore store, final byte[] expected)
            throws IOException {
        final List<Path> points = store.recoveryPoints(DOCUMENT);
        assertFalse(points.isEmpty(), "a destructive migration must leave a recovery point behind");
        assertTrue(
                points.stream().anyMatch(point -> matchesImage(point, expected)),
                "no retained recovery point is byte-identical to the pre-migration document");
    }

    private static boolean matchesImage(final Path point, final byte[] expected) {
        try {
            return Arrays.equals(Files.readAllBytes(point), expected);
        } catch (IOException exception) {
            return false;
        }
    }

    private static List<Long> revisions(final List<Path> points) {
        return points.stream()
                .map(point -> Long.parseLong(
                        point.getFileName().toString().split("-r")[1].split("-")[0]))
                .toList();
    }

    private static void assertPointNameMatchesContent(final Path point) throws Exception {
        final String name = point.getFileName().toString();
        final String recorded = name.substring(name.length() - ".bak".length() - 64, name.length() - ".bak".length());
        assertEquals(
                recorded,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(point))),
                "the recovery point name must be the SHA-256 of its content");
    }

    private static void assertPointNameMatchesContentUnchecked(final Path point) {
        try {
            assertPointNameMatchesContent(point);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static List<Path> temporaryFiles(final Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".tmp"))
                    .toList();
        }
    }

    private void assumeSymlinksSupported() {
        try {
            final Path probe = temporary.resolve("symlink-probe");
            Files.createSymbolicLink(probe, temporary);
            Files.delete(probe);
        } catch (IOException | UnsupportedOperationException unsupported) {
            Assumptions.abort("symbolic links are unavailable: " + unsupported.getMessage());
        }
    }
}
