package dev.turboism.core.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.core.io.DurableWriteException.Failure;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Focused regression for the one durable-write primitive.
 *
 * <p>Every call site this module replaces claims its write is atomic; the baseline had 26 separate
 * implementations, 10 of which never forced the staged file and 7 of which silently degraded to a
 * non-atomic replace. The cases below pin the parts a caller relies on: payload and ordering, the
 * forced write before the rename, fail-closed versus fallback atomicity, create-once, the
 * owner-only permission model with its fail-closed branch, the guard hook, cancellation, and that
 * no staged sibling survives any outcome.
 *
 * <p>The fail-closed atomicity and owner-only cases use a zip {@link FileSystem}: it is a real
 * second provider with no POSIX permission model, no ACL view and no cross-provider atomic move, so
 * both branches are exercised without a production fault-injection seam.
 */
class DurableFileTest {

    private static final Set<PosixFilePermission> FILE_OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    @TempDir
    Path temporary;

    @Test
    void replacePublishesTheExactBytesAndLeavesNoStagedSibling() throws Exception {
        final Path target = temporary.resolve("report.json");
        Files.writeString(target, "old");

        DurableFile.at(target).replace(bytes("new"));

        assertEquals("new", Files.readString(target));
        assertEquals(List.of("report.json"), names(temporary));
    }

    @Test
    void theGuardSeesACompleteStagedSiblingBeforeTheRenameAndTheTargetAfterIt() throws Exception {
        final Path target = temporary.resolve("document.json");
        Files.writeString(target, "old");
        final List<String> observed = new ArrayList<>();

        DurableFile.at(target)
                .guardedBy(new PublishGuard() {
                    @Override
                    public void beforePublish(final Path stagedPath, final Path targetPath) throws IOException {
                        assertEquals(targetPath.getParent(), stagedPath.getParent());
                        assertNotEquals(targetPath, stagedPath);
                        assertArrayEquals(bytes("new"), Files.readAllBytes(stagedPath));
                        assertEquals("old", Files.readString(targetPath));
                        observed.add("before");
                    }

                    @Override
                    public void afterPublish(final Path stagedPath, final Path targetPath) throws IOException {
                        assertTrue(Files.notExists(stagedPath));
                        assertEquals("new", Files.readString(targetPath));
                        observed.add("after");
                    }
                })
                .replace(bytes("new"));

        assertEquals(List.of("before", "after"), observed);
        assertEquals(List.of("document.json"), names(temporary));
    }

    @Test
    void requiredAtomicityFailsClosedWhenTheFileSystemRefusesAnAtomicMove() throws Exception {
        final Path target = temporary.resolve("receipt.json");
        Files.writeString(target, "old");

        try (FileSystem signalFree = zipFileSystem("no-atomic-move.zip")) {
            final Path staged = signalFree.getPath("/staged.json");
            Files.writeString(staged, "new");

            final DurableWriteException failure = assertThrows(
                    DurableWriteException.class, () -> DurableFile.at(target).adopt(staged));

            assertEquals(Failure.ATOMIC_REPLACE_UNAVAILABLE, failure.failure());
            assertEquals("old", Files.readString(target));
            assertTrue(Files.notExists(staged), "the staged file is deleted when publication fails");
        }
    }

    @Test
    void preferredAtomicityFallsBackToAPlainReplaceWhenAtomicMovesAreUnavailable() throws Exception {
        final Path target = temporary.resolve("keybindings.properties");
        Files.writeString(target, "old");

        try (FileSystem plainMoveOnly = zipFileSystem("fallback.zip")) {
            final Path staged = plainMoveOnly.getPath("/staged.properties");
            Files.writeString(staged, "version=1");

            DurableFile.at(target)
                    .withOptions(WriteOptions.DEFAULT.withAtomicity(WriteOptions.Atomicity.PREFERRED))
                    .adopt(staged);

            assertEquals("version=1", Files.readString(target));
            assertTrue(Files.notExists(staged));
        }
    }

    @Test
    void createOnceFailsWithAlreadyExistsInsteadOfReplacingTheTarget() throws Exception {
        final Path target = temporary.resolve("installed-build.json");
        Files.writeString(target, "old");

        final DurableWriteException failure = assertThrows(
                DurableWriteException.class,
                () -> DurableFile.createOnce(target).replace(bytes("new")));

        assertEquals(Failure.ALREADY_EXISTS, failure.failure());
        assertEquals("old", Files.readString(target));
        assertEquals(List.of("installed-build.json"), names(temporary));
    }

    @Test
    void createOncePublishesWhenTheTargetIsAbsentAndKeepsThatDecisionAcrossCopies() throws Exception {
        final Path first = temporary.resolve("manifest.json");
        DurableFile.createOnce(first).replace(bytes("new"));
        assertEquals("new", Files.readString(first));

        final Path second = temporary.resolve("manifest.json");
        final DurableWriteException failure = assertThrows(
                DurableWriteException.class,
                () -> DurableFile.createOnce(second)
                        .withOptions(WriteOptions.DEFAULT.withDurability(WriteOptions.Durability.FILE_AND_DIRECTORY))
                        .replace(bytes("replaced")));
        assertEquals(Failure.ALREADY_EXISTS, failure.failure());
        assertEquals("new", Files.readString(second));
    }

    @Test
    void ownerOnlyRestrictsTheFileBeforeAnyPayloadByteIsWritten() throws Exception {
        Assumptions.assumeTrue(
                Files.getFileAttributeView(temporary, PosixFileAttributeView.class) != null,
                "POSIX attribute view is unavailable; the owner-only bit assertions do not apply");
        final Path target = temporary.resolve("config.json");
        final List<Set<PosixFilePermission>> stagedModes = new ArrayList<>();

        DurableFile.at(target)
                .withOptions(WriteOptions.DEFAULT.withPermissions(WriteOptions.Permissions.OWNER_ONLY))
                .guardedBy((stagedPath, targetPath) -> stagedModes.add(Files.getPosixFilePermissions(stagedPath)))
                .replace(bytes("secret"));

        assertEquals(List.of(FILE_OWNER_ONLY), stagedModes);
        assertEquals(FILE_OWNER_ONLY, Files.getPosixFilePermissions(target));
    }

    @Test
    void ownerOnlyFailsClosedWhenTheFileSystemExposesNoPermissionModel() throws Exception {
        try (FileSystem permissionFree = zipFileSystem("no-permission-model.zip")) {
            final Path state = permissionFree.getPath("/state");
            Files.createDirectories(state);
            final Path target = state.resolve("credentials.cfg");

            final DurableWriteException failure = assertThrows(
                    DurableWriteException.class,
                    () -> DurableFile.at(target)
                            .withOptions(WriteOptions.DEFAULT.withPermissions(WriteOptions.Permissions.OWNER_ONLY))
                            .replace(bytes("secret")));

            assertEquals(Failure.OWNER_ONLY_UNAVAILABLE, failure.failure());
            assertTrue(Files.notExists(target));
            assertEquals(List.of(), names(state), "the staged file is deleted when owner-only fails");
        }
    }

    @Test
    void aRejectingGuardLeavesThePreviousBytesAndDeletesTheStagedFile() throws Exception {
        final Path target = temporary.resolve("rejected.json");
        Files.writeString(target, "old");
        final IOException rejection = new IOException("the staged file is not confined to the vetted parent");

        final IOException thrown = assertThrows(
                IOException.class,
                () -> DurableFile.at(target)
                        .guardedBy((stagedPath, targetPath) -> {
                            throw rejection;
                        })
                        .replace(bytes("new")));

        assertSame(rejection, thrown, "a guard failure reaches the caller as the caller's own type");
        assertEquals("old", Files.readString(target));
        assertEquals(List.of("rejected.json"), names(temporary));
    }

    @Test
    void replaceCancellableAbandonsTheWriteWhenTheCallerIsInterrupted() throws Exception {
        final Path target = temporary.resolve("cancelled.json");
        Files.writeString(target, "old");

        Thread.currentThread().interrupt();
        try {
            assertThrows(
                    InterruptedException.class, () -> DurableFile.at(target).replaceCancellable(bytes("new")));
        } finally {
            assertTrue(Thread.interrupted(), "the interrupt status is observed, not consumed");
        }

        assertEquals("old", Files.readString(target));
        assertEquals(List.of("cancelled.json"), names(temporary));
    }

    @Test
    void replaceCancellablePublishesWhenTheCallerIsNotInterrupted() throws Exception {
        final Path target = temporary.resolve("published.json");

        DurableFile.at(target).replaceCancellable(bytes("new"));

        assertEquals("new", Files.readString(target));
        assertEquals(List.of("published.json"), names(temporary));
    }

    @Test
    void adoptPublishesAFileAnotherComponentProduced() throws Exception {
        final Path staged = temporary.resolve(".encoder-output.turboism.tmp");
        Files.writeString(staged, "encoded");
        final Path target = temporary.resolve("export.gif");

        DurableFile.at(target).adopt(staged);

        assertEquals("encoded", Files.readString(target));
        assertEquals(List.of("export.gif"), names(temporary));
    }

    @Test
    void replaceStreamingPublishesWhatTheProducerWrites() throws Exception {
        final Path target = temporary.resolve("streamed.properties");

        DurableFile.at(target).replaceStreaming(out -> out.write(bytes("version=1")));

        assertEquals("version=1", Files.readString(target));
        assertEquals(List.of("streamed.properties"), names(temporary));
    }

    @Test
    void replaceStreamingDeletesTheStagedFileWhenTheProducerFails() throws Exception {
        final Path target = temporary.resolve("streamed.properties");
        Files.writeString(target, "old");

        final DurableWriteException failure = assertThrows(
                DurableWriteException.class,
                () -> DurableFile.at(target).replaceStreaming(out -> {
                    out.write(bytes("partial"));
                    throw new IOException("payload is not available anymore");
                }));

        assertEquals(Failure.IO_FAILURE, failure.failure());
        assertEquals("old", Files.readString(target));
        assertEquals(List.of("streamed.properties"), names(temporary));
    }

    @Test
    void fileAndDirectoryDurabilityPublishesOnEveryPlatform() throws Exception {
        final Path target = temporary.resolve("durable.json");

        DurableFile.at(target)
                .withOptions(WriteOptions.DEFAULT.withDurability(WriteOptions.Durability.FILE_AND_DIRECTORY))
                .replace(bytes("new"));

        assertEquals("new", Files.readString(target));
        assertEquals(List.of("durable.json"), names(temporary));
    }

    @Test
    void aMissingParentDirectoryIsReportedAsAnIoFailure() {
        final Path target = temporary.resolve("absent/state.json");

        final DurableWriteException failure = assertThrows(
                DurableWriteException.class, () -> DurableFile.at(target).replace(bytes("new")));

        assertEquals(Failure.IO_FAILURE, failure.failure());
        assertTrue(Files.notExists(target));
    }

    @Test
    void appendAddsBytesWithoutReplacingTheFile() throws Exception {
        final Path target = temporary.resolve("session.log");
        Files.writeString(target, "first\n");

        DurableFile.at(target).append(bytes("second\n"));

        assertEquals("first\nsecond\n", Files.readString(target));
    }

    @Test
    void writeOptionsRejectNullComponents() {
        assertThrows(
                NullPointerException.class,
                () -> new WriteOptions(null, WriteOptions.Permissions.INHERIT, WriteOptions.Durability.FILE));
        assertThrows(
                NullPointerException.class,
                () -> new WriteOptions(WriteOptions.Atomicity.REQUIRED, null, WriteOptions.Durability.FILE));
        assertThrows(
                NullPointerException.class,
                () -> new WriteOptions(WriteOptions.Atomicity.REQUIRED, WriteOptions.Permissions.INHERIT, null));
    }

    private static byte[] bytes(final String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> names(final Path directory) throws IOException {
        try (Stream<Path> children = Files.list(directory)) {
            return children.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private FileSystem zipFileSystem(final String name) throws IOException {
        return FileSystems.newFileSystem(temporary.resolve(name), Map.of("create", "true"));
    }
}
