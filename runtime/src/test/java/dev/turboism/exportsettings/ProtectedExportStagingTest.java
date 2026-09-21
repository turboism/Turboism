package dev.turboism.exportsettings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Publication-atomicity coverage for {@link ProtectedExportStaging#publish}.
 *
 * <p>Every test drives the package-private {@code MoveOp} seam so a mid-publish
 * failure is injected deterministically — no filesystem permissions tricks.</p>
 */
class ProtectedExportStagingTest {

    private static ProtectedExportStaging.BehaviorSnapshot snapshot(
        final java.util.Map<String, float[]> baseline
    ) {
        return new ProtectedExportStaging.BehaviorSnapshot(
            List.of(), baseline, List.of());
    }

    private static java.util.Map<String, String> mapping(final String... pairs) {
        final java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    void behaviorDriftAcceptsEquivalentFrames() {
        final String drift = ProtectedExportStaging.behaviorDrift(
            snapshot(java.util.Map.of(
                "g1", new float[] {0f, 1f, 2f, 3f},
                "g2", new float[] {4f, 5f})),
            snapshot(java.util.Map.of(
                "t1", new float[] {0f, 1f, 2f, 3f},
                "t2", new float[] {4f, 5f})),
            mapping("g1", "t1", "g2", "t2"));
        assertEquals(null, drift);
    }

    @Test
    void behaviorDriftRejectsNonFiniteTransformed() {
        final String drift = ProtectedExportStaging.behaviorDrift(
            snapshot(java.util.Map.of("g1", new float[] {0f, 1f})),
            snapshot(java.util.Map.of("t1", new float[] {0f, Float.NaN})),
            mapping("g1", "t1"));
        assertTrue(drift != null && drift.contains("non-finite"), drift);
    }

    @Test
    void behaviorDriftRejectsEmptyFramesAsCoverage() {
        final String drift = ProtectedExportStaging.behaviorDrift(
            snapshot(java.util.Map.of("g1", new float[0])),
            snapshot(java.util.Map.of("t1", new float[0])),
            mapping("g1", "t1"));
        assertTrue(drift != null && drift.contains("empty"), drift);
    }

    @Test
    void behaviorDriftRejectsSymmetricMissingDrawable() {
        final java.util.Map<String, float[]> partial =
            java.util.Map.of("g1", new float[] {0f, 1f});
        final String drift = ProtectedExportStaging.behaviorDrift(
            snapshot(partial),
            snapshot(java.util.Map.of("t1", new float[] {0f, 1f})),
            mapping("g1", "t1", "g2", "t2"));
        assertTrue(drift != null && drift.contains("missing-original"), drift);
    }

    @Test
    void behaviorDriftRejectsOddLengthAndInfinity() {
        final String odd = ProtectedExportStaging.behaviorDrift(
            snapshot(java.util.Map.of("g1", new float[] {0f, 1f, 2f})),
            snapshot(java.util.Map.of("t1", new float[] {0f, 1f, 2f})),
            mapping("g1", "t1"));
        assertTrue(odd != null && odd.contains("odd-length"), odd);
        final String inf = ProtectedExportStaging.behaviorDrift(
            snapshot(java.util.Map.of(
                "g1", new float[] {0f, Float.POSITIVE_INFINITY})),
            snapshot(java.util.Map.of(
                "t1", new float[] {0f, Float.POSITIVE_INFINITY})),
            mapping("g1", "t1"));
        assertTrue(inf != null && inf.contains("non-finite"), inf);
    }

    @TempDir
    Path tempDir;

    private Path stagedDir;
    private Path stagedPick;
    private Path destinationDir;
    private File realPick;

    private void layout(final List<String> names) throws IOException {
        stagedDir = Files.createDirectories(tempDir.resolve("staged"));
        stagedPick = stagedDir.resolve("model.moc3");
        Files.write(stagedPick, new byte[] {1, 2, 3});
        destinationDir = Files.createDirectories(tempDir.resolve("destination"));
        realPick = destinationDir.resolve("model.moc3").toFile();
        for (String name : names) {
            final Path file = stagedDir.resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "staged-" + name);
        }
    }

    private ProtectedExportStaging staging() {
        return new ProtectedExportStaging(null);
    }

    @Test
    void publishesAllFilesAtomically() throws IOException {
        layout(List.of("model.moc3", "model.model3.json"));
        final List<Path> published = staging().publish(
            stagedPick.toFile(), List.of(
                stagedDir.resolve("model.moc3"),
                stagedDir.resolve("model.model3.json")),
            realPick);

        assertEquals(2, published.size());
        assertEquals("staged-model.moc3",
            Files.readString(destinationDir.resolve("model.moc3")));
        assertEquals("staged-model.model3.json",
            Files.readString(destinationDir.resolve("model.model3.json")));
        assertScratchGone();
    }

    /**
     * The regression the parent agent reproduced: when the second move fails,
     * the first target must still hold its original bytes and no partial new
     * output may remain at the destination.
     */
    @Test
    void secondFileFailureRestoresFirstTarget() throws IOException {
        layout(List.of("model.moc3", "model.model3.json"));
        Files.writeString(destinationDir.resolve("model.moc3"), "EXISTING-MOC");
        Files.writeString(destinationDir.resolve("model.model3.json"), "EXISTING-JSON");

        // Fail only the second file's place move — backup and rollback moves
        // must keep working so the destination ends up byte-identical.
        final ProtectedExportStaging.MoveOp failingSecond =
            placingFailureOn("model.model3.json");

        assertThrows(IOException.class, () -> staging().publish(
            stagedPick.toFile(), List.of(
                stagedDir.resolve("model.moc3"),
                stagedDir.resolve("model.model3.json")),
            realPick, failingSecond));

        assertEquals("EXISTING-MOC",
            Files.readString(destinationDir.resolve("model.moc3")));
        assertEquals("EXISTING-JSON",
            Files.readString(destinationDir.resolve("model.model3.json")));
        assertScratchGone();
    }

    /**
     * A file blocking the target's parent directory fails the publish mid-way —
     * the already-placed first target must come back byte-identical.
     */
    @Test
    void blockedParentDirectoryRollsBackPlacedTarget() throws IOException {
        layout(List.of("model.moc3", "sub/texture.png"));
        Files.writeString(destinationDir.resolve("model.moc3"), "EXISTING-MOC");
        // 'sub' exists as a regular file, so sub/texture.png can never land.
        Files.writeString(destinationDir.resolve("sub"), "blocking-file");

        assertThrows(IOException.class, () -> staging().publish(
            stagedPick.toFile(), List.of(
                stagedDir.resolve("model.moc3"),
                stagedDir.resolve("sub/texture.png")),
            realPick));

        assertEquals("EXISTING-MOC",
            Files.readString(destinationDir.resolve("model.moc3")));
        assertEquals("blocking-file",
            Files.readString(destinationDir.resolve("sub")));
        assertScratchGone();
    }

    @Test
    void directoryBlockingTargetRejectsBeforeAnyWrite() throws IOException {
        layout(List.of("model.moc3", "texture.png"));
        Files.writeString(destinationDir.resolve("model.moc3"), "EXISTING-MOC");
        // The second target is an existing directory — preflight must reject it
        // before model.moc3 is swapped aside.
        Files.createDirectories(destinationDir.resolve("texture.png"));

        assertThrows(IOException.class, () -> staging().publish(
            stagedPick.toFile(), List.of(
                stagedDir.resolve("model.moc3"),
                stagedDir.resolve("texture.png")),
            realPick));

        assertEquals("EXISTING-MOC",
            Files.readString(destinationDir.resolve("model.moc3")));
        assertTrue(Files.isDirectory(destinationDir.resolve("texture.png")));
        assertScratchGone();
    }

    /** A previously-absent target that received a file is removed on rollback. */
    @Test
    void rollbackRemovesNewlyPlacedFiles() throws IOException {
        layout(List.of("model.moc3", "new/texture.png"));
        Files.writeString(destinationDir.resolve("model.moc3"), "EXISTING-MOC");

        assertThrows(IOException.class, () -> staging().publish(
            stagedPick.toFile(), List.of(
                stagedDir.resolve("model.moc3"),
                stagedDir.resolve("new/texture.png")),
            realPick, placingFailureOn("texture.png")));

        assertEquals("EXISTING-MOC",
            Files.readString(destinationDir.resolve("model.moc3")));
        // The directory this publish created for the second file is removed.
        assertFalse(Files.exists(destinationDir.resolve("new")));
        assertScratchGone();
    }

    /**
     * The second supervisor counterexample: the second place-move fails AND the
     * rollback of the first target fails. The publish must still report an
     * IOException with the rollback failure suppressed, must NOT claim the
     * destination is untouched, and must retain the scratch backup holding the
     * only surviving copy of the original bytes — its path is reported through
     * the suppressed recovery note.
     */
    @Test
    void rollbackFailureRetainsRecoveryMaterial() throws IOException {
        layout(List.of("model.moc3", "model.json"));
        Files.writeString(destinationDir.resolve("model.moc3"), "ORIGINAL-UNIQUE-BYTES");
        Files.writeString(destinationDir.resolve("model.json"), "ORIGINAL-JSON");

        final ProtectedExportStaging.MoveOp mover = (source, target) -> {
            if (source.toString().contains("incoming")
                && target.getFileName().toString().equals("model.json")) {
                throw new IOException("publish failure");
            }
            if (source.toString().contains("backups")
                && target.getFileName().toString().equals("model.moc3")) {
                throw new IOException("rollback failure");
            }
            Files.move(source, target,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        };

        final IOException thrown = assertThrows(IOException.class, () -> staging().publish(
            stagedPick.toFile(), List.of(
                stagedDir.resolve("model.moc3"),
                stagedDir.resolve("model.json")),
            realPick, mover));
        assertTrue(thrown.getSuppressed().length >= 1,
            "rollback failure must surface as suppressed evidence");

        // The original bytes must still exist somewhere under the destination —
        // inside the retained scratch backup tree.
        boolean retained;
        try (var walk = Files.walk(destinationDir)) {
            retained = walk.filter(Files::isRegularFile).anyMatch(path -> {
                try {
                    return Files.readString(path).equals("ORIGINAL-UNIQUE-BYTES");
                } catch (IOException failure) {
                    throw new RuntimeException(failure);
                }
            });
        }
        assertTrue(retained,
            "failed rollback must not destroy the only surviving original bytes");
        assertScratchRetained();
    }

    /**
     * When rollback completes, the scratch is removed as before — retention is
     * reserved for the incomplete-rollback case only.
     */
    @Test
    void successfulRollbackStillRemovesScratch() throws IOException {
        layout(List.of("model.moc3", "model.json"));
        Files.writeString(destinationDir.resolve("model.moc3"), "EXISTING-MOC");
        Files.writeString(destinationDir.resolve("model.json"), "EXISTING-JSON");

        assertThrows(IOException.class, () -> staging().publish(
            stagedPick.toFile(), List.of(
                stagedDir.resolve("model.moc3"),
                stagedDir.resolve("model.json")),
            realPick, placingFailureOn("model.json")));

        assertEquals("EXISTING-MOC",
            Files.readString(destinationDir.resolve("model.moc3")));
        assertScratchGone();
    }

    @Test
    void rejectsUnwritableDestinationParent() throws IOException {
        layout(List.of("model.moc3"));
        final Path blocked = tempDir.resolve("blocked");
        Files.writeString(blocked, "not a directory");
        final File pick = blocked.resolve("model.moc3").toFile();

        assertThrows(IOException.class, () -> staging().publish(
            stagedPick.toFile(), List.of(stagedDir.resolve("model.moc3")), pick));
    }

    /**
     * MoveOp that fails only the "place" move for {@code targetName} — backup and
     * rollback moves (which never originate inside {@code incoming}) still run.
     */
    private static ProtectedExportStaging.MoveOp placingFailureOn(
        final String targetName
    ) {
        return (source, target) -> {
            if (target.getFileName().toString().equals(targetName)
                && source.toString().contains("incoming")) {
                throw new IOException("injected place failure on " + targetName);
            }
            Files.move(source, target,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        };
    }

    private void assertScratchGone() throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(
            destinationDir, ".turboism-publish-*")) {
            assertFalse(entries.iterator().hasNext(),
                "publish scratch must be removed");
        }
    }

    private void assertScratchRetained() throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(
            destinationDir, ".turboism-publish-*")) {
            assertTrue(entries.iterator().hasNext(),
                "incomplete rollback must retain the recovery directory");
        }
    }
}
