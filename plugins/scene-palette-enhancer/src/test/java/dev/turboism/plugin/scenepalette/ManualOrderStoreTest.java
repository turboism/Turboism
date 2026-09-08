package dev.turboism.plugin.scenepalette;

import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.storage.PluginStorage;
import dev.turboism.sdk.storage.StorageError;
import dev.turboism.sdk.storage.StorageErrorCode;
import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.storage.StorageReadResult;
import dev.turboism.sdk.storage.StorageWriteResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ManualOrderStoreTest {

    private static final String SCOPE = "a".repeat(64);

    @Test
    void readsCurrentAndLegacyFormats() {
        final TestLogger logger = new TestLogger();
        final ManualOrderStore current = ManualOrderStore.storage(storage(
            path -> CompletableFuture.completedStage(read(
                ManualOrderStore.FORMAT_HEADER + "\nscene-2\nscene-1\nscene-2\n", false
            )),
            (path, content) -> CompletableFuture.completedStage(written())
        ), logger);
        final ManualOrderStore legacy = ManualOrderStore.storage(storage(
            path -> CompletableFuture.completedStage(read("scene-1\nscene-2\n", false)),
            (path, content) -> CompletableFuture.completedStage(written())
        ), logger);

        final ManualOrderStore.LoadResult currentResult = current.load(SCOPE).toCompletableFuture().join();
        final ManualOrderStore.LoadResult legacyResult = legacy.load(SCOPE).toCompletableFuture().join();

        assertEquals(ManualOrderStore.LoadStatus.CURRENT, currentResult.status());
        assertEquals(List.of("scene-2", "scene-1"), currentResult.itemIds());
        assertEquals(ManualOrderStore.LoadStatus.LEGACY, legacyResult.status());
        assertEquals(List.of("scene-1", "scene-2"), legacyResult.itemIds());
    }

    @Test
    void rejectsTruncatedAndFutureFormatsAndKeepsNotFoundSilent() {
        final TestLogger logger = new TestLogger();
        final StoragePath path = new StoragePath(
            dev.turboism.sdk.storage.StorageRoot.STATE,
            "manual-order-" + SCOPE + ".txt"
        );
        final List<StorageReadResult<String>> reads = new ArrayList<>(List.of(
            read(ManualOrderStore.FORMAT_HEADER + "\nscene-1", true),
            read(ManualOrderStore.FORMAT_PREFIX + "2\nscene-1\n", false),
            new StorageReadResult<>(Optional.empty(), Optional.of(new StorageError(
                StorageErrorCode.NOT_FOUND, "not found", path
            )), false)
        ));
        final ManualOrderStore store = ManualOrderStore.storage(storage(
            ignored -> CompletableFuture.completedStage(reads.remove(0)),
            (ignored, content) -> CompletableFuture.completedStage(written())
        ), logger);

        assertEquals(ManualOrderStore.LoadStatus.UNUSABLE, store.load(SCOPE).toCompletableFuture().join().status());
        assertEquals(ManualOrderStore.LoadStatus.UNUSABLE, store.load(SCOPE).toCompletableFuture().join().status());
        final int warningsBeforeMissing = logger.warnings.size();
        assertEquals(ManualOrderStore.LoadStatus.MISSING, store.load(SCOPE).toCompletableFuture().join().status());
        assertEquals(warningsBeforeMissing, logger.warnings.size());
        assertTrue(logger.warnings.stream().anyMatch(message -> message.contains("truncated")));
    }

    @Test
    void loadConvertsStorageExceptionsToSafeResultsButSaveReportsThem() {
        final TestLogger logger = new TestLogger();
        final ManualOrderStore store = ManualOrderStore.storage(storage(
            path -> {
                throw new IllegalStateException("offline");
            },
            (path, content) -> CompletableFuture.failedStage(new IllegalStateException("offline"))
        ), logger);

        assertEquals(ManualOrderStore.LoadStatus.UNUSABLE, store.load(SCOPE).toCompletableFuture().join().status());
        assertThrows(java.util.concurrent.CompletionException.class,
            () -> store.save(SCOPE, List.of("scene-1")).toCompletableFuture().join());
        assertEquals(2, logger.warnings.size());
    }

    @Test
    void writesV1AndSerializesOverlappingSaves() {
        final TestLogger logger = new TestLogger();
        final AtomicInteger calls = new AtomicInteger();
        final List<String> contents = new ArrayList<>();
        final CompletableFuture<StorageWriteResult> firstWrite = new CompletableFuture<>();
        final CompletableFuture<StorageWriteResult> secondWrite = new CompletableFuture<>();
        final ManualOrderStore store = ManualOrderStore.storage(storage(
            path -> CompletableFuture.completedStage(read("", false)),
            (path, content) -> {
                contents.add(content);
                return calls.getAndIncrement() == 0 ? firstWrite : secondWrite;
            }
        ), logger);

        final CompletionStage<Void> first = store.save(SCOPE, List.of("scene-1"));
        final CompletionStage<Void> second = store.save(SCOPE, List.of("scene-2"));

        assertEquals(1, calls.get());
        assertTrue(contents.get(0).startsWith(ManualOrderStore.FORMAT_HEADER + "\n"));
        firstWrite.complete(written());
        first.toCompletableFuture().join();
        assertEquals(2, calls.get());
        assertEquals(ManualOrderStore.FORMAT_HEADER + "\nscene-2\n", contents.get(1));
        assertFalse(second.toCompletableFuture().isDone());
        secondWrite.complete(written());
        second.toCompletableFuture().join();
    }

    @Test
    void refusesToWriteOrderExceedingByteLimit() {
        final TestLogger logger = new TestLogger();
        final AtomicInteger writes = new AtomicInteger();
        final ManualOrderStore store = ManualOrderStore.storage(storage(
            path -> CompletableFuture.completedStage(read("", false)),
            (path, content) -> {
                writes.incrementAndGet();
                return CompletableFuture.completedStage(written());
            }
        ), logger);

        store.save(SCOPE, List.of("x".repeat(300_000))).toCompletableFuture().join();

        assertEquals(0, writes.get());
        assertTrue(logger.warnings.stream().anyMatch(message -> message.contains("byte limit")));
    }

    @Test
    void loadWaitsForQueuedWritesBeforeReading() {
        final TestLogger logger = new TestLogger();
        final CompletableFuture<StorageWriteResult> pendingWrite = new CompletableFuture<>();
        final AtomicInteger reads = new AtomicInteger();
        final ManualOrderStore store = ManualOrderStore.storage(storage(
            path -> {
                reads.incrementAndGet();
                return CompletableFuture.completedStage(read("", false));
            },
            (path, content) -> pendingWrite
        ), logger);

        final CompletionStage<Void> save = store.save(SCOPE, List.of("scene-1"));
        final CompletableFuture<ManualOrderStore.LoadResult> load = store.load(SCOPE).toCompletableFuture();

        assertEquals(0, reads.get());
        pendingWrite.complete(written());
        save.toCompletableFuture().join();
        assertEquals(1, reads.get());
        assertEquals(ManualOrderStore.LoadStatus.LEGACY, load.join().status());
    }

    private static StorageReadResult<String> read(final String content, final boolean truncated) {
        return new StorageReadResult<>(Optional.of(content), Optional.empty(), truncated);
    }

    private static StorageWriteResult written() {
        return new StorageWriteResult(true, Optional.empty());
    }

    private static PluginStorage storage(final Reader reader, final Writer writer) {
        return (PluginStorage) Proxy.newProxyInstance(
            PluginStorage.class.getClassLoader(),
            new Class<?>[] {PluginStorage.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "readUtf8" -> reader.read((StoragePath) args[0]);
                case "writeUtf8Atomic" -> writer.write((StoragePath) args[0], (String) args[1]);
                case "toString" -> "TestPluginStorage";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new AssertionError("Unexpected storage call: " + method.getName());
            }
        );
    }

    @FunctionalInterface
    private interface Reader {
        CompletionStage<StorageReadResult<String>> read(StoragePath path);
    }

    @FunctionalInterface
    private interface Writer {
        CompletionStage<StorageWriteResult> write(StoragePath path, String content);
    }

    private static final class TestLogger implements PluginLogger {
        private final List<String> warnings = new ArrayList<>();

        @Override public void debug(final String message) { }
        @Override public void info(final String message) { }
        @Override public void warn(final String message) { warnings.add(message); }
        @Override public void error(final String message) { }
        @Override public void error(final String message, final Throwable throwable) { }
    }
}
