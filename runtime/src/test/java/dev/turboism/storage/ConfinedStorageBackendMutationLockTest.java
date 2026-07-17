package dev.turboism.storage;

import dev.turboism.cleanup.CleanupEvidenceCollector;
import dev.turboism.sdk.storage.StorageErrorCode;
import dev.turboism.sdk.storage.StorageMutationResult;
import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.storage.StorageRoot;
import dev.turboism.sdk.storage.StorageWriteResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfinedStorageBackendMutationLockTest {

    private static final long JOIN_TIMEOUT_MILLIS = 2_000L;

    @TempDir
    Path temporary;

    @Test
    void canonicalAliasesShareOneMutationLock() throws Exception {
        final Path sharedData = Files.createDirectory(temporary.resolve("data"));
        final Roots firstRoots = roots(sharedData);
        final Roots secondRoots = new Roots(
            sharedData.resolve(".").toAbsolutePath().normalize(),
            firstRoots.state(),
            firstRoots.cache()
        );
        final CountDownLatch writeEnteredMover = new CountDownLatch(1);
        final CountDownLatch releaseWrite = new CountDownLatch(1);
        final ConfinedStorageBackend first = backend(firstRoots, (source, target, replaceExisting) -> {
            writeEnteredMover.countDown();
            await(releaseWrite);
            StorageAtomicMover.move(source, target, replaceExisting);
        });
        final ConfinedStorageBackend alias = backend(secondRoots, StorageAtomicMover::move);
        final AtomicReference<StorageWriteResult> write = new AtomicReference<>();
        final AtomicReference<StorageWriteResult> aliasWrite = new AtomicReference<>();

        final Thread writer = new Thread(
            () -> write.set(first.writeBytesAtomic(path("first.txt"), bytes("first"), true))
        );
        writer.start();
        assertTrue(writeEnteredMover.await(2, TimeUnit.SECONDS));

        final Thread aliasWriter = new Thread(
            () -> aliasWrite.set(alias.writeBytesAtomic(path("second.txt"), bytes("second"), true))
        );
        aliasWriter.start();
        assertBlocked(aliasWriter, "canonical root alias must wait for the same lock");

        releaseWrite.countDown();
        join(writer);
        join(aliasWriter);

        assertTrue(write.get().written());
        assertTrue(aliasWrite.get().written());
        assertEquals("first", Files.readString(sharedData.resolve("first.txt")));
        assertEquals("second", Files.readString(sharedData.resolve("second.txt")));
    }

    @Test
    void writeCheckpointBlocksCompetingMoveAndDelete() throws Exception {
        final Roots roots = roots();
        Files.writeString(roots.data().resolve("source.txt"), "source");
        final CountDownLatch writeEnteredMover = new CountDownLatch(1);
        final CountDownLatch releaseWrite = new CountDownLatch(1);
        final ConfinedStorageBackend first = backend(roots, (source, target, replaceExisting) -> {
            writeEnteredMover.countDown();
            await(releaseWrite);
            StorageAtomicMover.move(source, target, replaceExisting);
        });
        final ConfinedStorageBackend second = backend(roots, StorageAtomicMover::move);
        final AtomicReference<StorageWriteResult> write = new AtomicReference<>();
        final AtomicReference<StorageMutationResult> move = new AtomicReference<>();
        final AtomicReference<StorageMutationResult> delete = new AtomicReference<>();

        final Thread writer = new Thread(
            () -> write.set(first.writeBytesAtomic(path("target.txt"), bytes("written"), true))
        );
        writer.start();
        assertTrue(writeEnteredMover.await(2, TimeUnit.SECONDS));

        final Thread mover = new Thread(
            () -> move.set(second.moveAtomic(path("source.txt"), path("moved.txt"), false))
        );
        final Thread deleter = new Thread(
            () -> delete.set(second.delete(path("source.txt"), false))
        );
        mover.start();
        deleter.start();
        assertBlocked(mover, "competing move must wait for A's checkpoint lock");
        assertBlocked(deleter, "competing delete must wait for A's checkpoint lock");

        releaseWrite.countDown();
        join(writer);
        join(mover);
        join(deleter);

        assertTrue(write.get().written());
        assertOneMutationWon(move.get(), delete.get());
    }

    @Test
    void failedWriteCleansTemporaryBeforeReleasingMutationLock() throws Exception {
        final Roots roots = roots();
        final CountDownLatch cleanupEntered = new CountDownLatch(1);
        final CountDownLatch releaseCleanup = new CountDownLatch(1);
        final ConfinedStorageBackend first = backend(
            roots,
            unsupportedMover(),
            temporary -> {
                cleanupEntered.countDown();
                await(releaseCleanup);
                return Files.deleteIfExists(temporary);
            }
        );
        final ConfinedStorageBackend second = backend(roots, StorageAtomicMover::move);
        final AtomicReference<StorageWriteResult> failedWrite = new AtomicReference<>();
        final AtomicReference<StorageWriteResult> competingWrite = new AtomicReference<>();

        final Thread writer = new Thread(
            () -> failedWrite.set(first.writeBytesAtomic(path("failed.txt"), bytes("first"), true))
        );
        writer.start();
        assertTrue(cleanupEntered.await(2, TimeUnit.SECONDS));

        final Thread contender = new Thread(
            () -> competingWrite.set(second.writeBytesAtomic(path("second.txt"), bytes("second"), true))
        );
        contender.start();
        assertBlocked(contender, "competing mutation entered before temporary cleanup completed");

        releaseCleanup.countDown();
        join(writer);
        join(contender);

        assertEquals(
            StorageErrorCode.ATOMIC_REPLACE_UNAVAILABLE,
            failedWrite.get().error().orElseThrow().code()
        );
        assertTrue(competingWrite.get().written());
        assertFalse(Files.exists(roots.data().resolve("failed.txt")));
        assertEquals("second", Files.readString(roots.data().resolve("second.txt")));
    }

    @Test
    void failedCopyCleansTemporaryBeforeReleasingMutationLock() throws Exception {
        final Roots roots = roots();
        Files.writeString(roots.data().resolve("source.txt"), "source");
        final CountDownLatch cleanupEntered = new CountDownLatch(1);
        final CountDownLatch releaseCleanup = new CountDownLatch(1);
        final ConfinedStorageBackend first = backend(
            roots,
            unsupportedMover(),
            temporary -> {
                cleanupEntered.countDown();
                await(releaseCleanup);
                return Files.deleteIfExists(temporary);
            }
        );
        final ConfinedStorageBackend second = backend(roots, StorageAtomicMover::move);
        final AtomicReference<StorageMutationResult> failedCopy = new AtomicReference<>();
        final AtomicReference<StorageMutationResult> competingDelete = new AtomicReference<>();

        final Thread copier = new Thread(() -> failedCopy.set(first.copy(
            path("source.txt"),
            path("failed.txt"),
            true
        )));
        copier.start();
        assertTrue(cleanupEntered.await(2, TimeUnit.SECONDS));

        final Thread deleter = new Thread(
            () -> competingDelete.set(second.delete(path("source.txt"), false))
        );
        deleter.start();
        assertBlocked(deleter, "competing mutation entered before temporary cleanup completed");

        releaseCleanup.countDown();
        join(copier);
        join(deleter);

        assertEquals(
            StorageErrorCode.ATOMIC_REPLACE_UNAVAILABLE,
            failedCopy.get().error().orElseThrow().code()
        );
        assertTrue(competingDelete.get().changed());
        assertFalse(Files.exists(roots.data().resolve("failed.txt")));
    }

    @Test
    void interruptedLockWaitReturnsCanceledAndPreservesInterruptStatus() throws Exception {
        final Roots roots = roots();
        final CountDownLatch writeEnteredMover = new CountDownLatch(1);
        final CountDownLatch releaseWrite = new CountDownLatch(1);
        final ConfinedStorageBackend holder = backend(roots, (source, target, replaceExisting) -> {
            writeEnteredMover.countDown();
            await(releaseWrite);
            StorageAtomicMover.move(source, target, replaceExisting);
        });
        final ConfinedStorageBackend waiter = backend(roots, StorageAtomicMover::move);
        final Thread holderThread = new Thread(
            () -> holder.writeBytesAtomic(path("held.txt"), new byte[] {1}, true)
        );
        holderThread.start();
        assertTrue(writeEnteredMover.await(2, TimeUnit.SECONDS));

        final AtomicReference<StorageWriteResult> result = new AtomicReference<>();
        final AtomicReference<Boolean> interruptedStatus = new AtomicReference<>();
        final Thread interrupted = new Thread(() -> {
            result.set(waiter.writeBytesAtomic(path("canceled.txt"), new byte[] {2}, true));
            interruptedStatus.set(Thread.currentThread().isInterrupted());
        });
        interrupted.start();
        assertBlocked(interrupted, "waiter must be blocked before interruption");
        interrupted.interrupt();
        join(interrupted);
        releaseWrite.countDown();
        join(holderThread);

        assertNotNull(result.get());
        assertEquals(StorageErrorCode.CANCELED, result.get().error().orElseThrow().code());
        assertTrue(interruptedStatus.get(), "interrupted status must be restored");
        assertFalse(Files.exists(roots.data().resolve("canceled.txt")));
    }

    @Test
    void repeatedOpposingRootOrdersDoNotDeadlock() throws Exception {
        for (int iteration = 0; iteration < 10; iteration++) {
            final Roots roots = roots("race-" + iteration);
            Files.writeString(roots.data().resolve("data-source.txt"), "data");
            Files.writeString(roots.state().resolve("state-source.txt"), "state");
            final ConfinedStorageBackend first = backend(roots, StorageAtomicMover::move);
            final ConfinedStorageBackend second = backend(roots, StorageAtomicMover::move);
            final CountDownLatch ready = new CountDownLatch(2);
            final CountDownLatch start = new CountDownLatch(1);
            final AtomicReference<StorageMutationResult> dataToState = new AtomicReference<>();
            final AtomicReference<StorageMutationResult> stateToData = new AtomicReference<>();

            final Thread forward = new Thread(() -> {
                ready.countDown();
                await(start);
                dataToState.set(first.copy(
                    storagePath(StorageRoot.DATA, "data-source.txt"),
                    storagePath(StorageRoot.STATE, "data-copy.txt"),
                    false
                ));
            });
            final Thread reverse = new Thread(() -> {
                ready.countDown();
                await(start);
                stateToData.set(second.copy(
                    storagePath(StorageRoot.STATE, "state-source.txt"),
                    storagePath(StorageRoot.DATA, "state-copy.txt"),
                    false
                ));
            });
            forward.start();
            reverse.start();
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            join(forward);
            join(reverse);

            assertTrue(
                dataToState.get().changed(),
                "forward copy failed at iteration " + iteration
            );
            assertTrue(
                stateToData.get().changed(),
                "reverse copy failed at iteration " + iteration
            );
        }
    }

    private Roots roots() throws IOException {
        return roots("");
    }

    private Roots roots(final String prefix) throws IOException {
        final Path parent = prefix.isEmpty()
            ? temporary
            : Files.createDirectory(temporary.resolve(prefix));
        return new Roots(
            Files.createDirectory(parent.resolve("data")),
            Files.createDirectory(parent.resolve("state")),
            Files.createDirectory(parent.resolve("cache"))
        );
    }

    private Roots roots(final Path data) throws IOException {
        return new Roots(
            data,
            Files.createDirectory(temporary.resolve("state")),
            Files.createDirectory(temporary.resolve("cache"))
        );
    }

    private ConfinedStorageBackend backend(
        final Roots roots,
        final ConfinedStorageBackend.AtomicMover mover
    ) throws IOException {
        return backend(roots, mover, Files::deleteIfExists);
    }

    private ConfinedStorageBackend backend(
        final Roots roots,
        final ConfinedStorageBackend.AtomicMover mover,
        final ConfinedStorageBackend.TemporaryFileDeleter deleter
    ) throws IOException {
        return new ConfinedStorageBackend(
            Map.of(
                StorageRoot.DATA, roots.data(),
                StorageRoot.STATE, roots.state(),
                StorageRoot.CACHE, roots.cache()
            ),
            new CleanupEvidenceCollector(),
            mover,
            deleter
        );
    }

    private static ConfinedStorageBackend.AtomicMover unsupportedMover() {
        return (source, target, replaceExisting) -> {
            throw new AtomicMoveNotSupportedException(
                source.toString(),
                target.toString(),
                "simulated unsupported atomic move"
            );
        };
    }

    private static StoragePath path(final String relativePath) {
        return storagePath(StorageRoot.DATA, relativePath);
    }

    private static StoragePath storagePath(
        final StorageRoot root,
        final String relativePath
    ) {
        return new StoragePath(root, relativePath);
    }

    private static byte[] bytes(final String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void assertOneMutationWon(
        final StorageMutationResult move,
        final StorageMutationResult delete
    ) {
        assertNotNull(move);
        assertNotNull(delete);
        assertTrue(move.changed() || delete.changed());
        if (!move.changed()) {
            assertEquals(StorageErrorCode.NOT_FOUND, move.error().orElseThrow().code());
        }
        if (!delete.changed()) {
            assertEquals(StorageErrorCode.NOT_FOUND, delete.error().orElseThrow().code());
        }
    }

    private static void assertBlocked(
        final Thread thread,
        final String message
    ) throws Exception {
        Thread.sleep(100L);
        assertTrue(thread.isAlive(), message);
    }

    private static void join(final Thread thread) throws Exception {
        thread.join(JOIN_TIMEOUT_MILLIS);
        assertFalse(thread.isAlive(), "thread did not complete");
    }

    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("test latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test thread interrupted", exception);
        }
    }

    private record Roots(Path data, Path state, Path cache) {
    }
}
