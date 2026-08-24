package dev.turboism.plugin.recentpreview.cache;

import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.cubism.recentfile.RecentFileSummary;
import dev.turboism.sdk.cubism.screenshot.ScreenshotImage;
import dev.turboism.sdk.storage.PluginStorage;
import dev.turboism.sdk.storage.StorageError;
import dev.turboism.sdk.storage.StorageErrorCode;
import dev.turboism.sdk.storage.StorageListResult;
import dev.turboism.sdk.storage.StorageMutationResult;
import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.storage.StorageReadResult;
import dev.turboism.sdk.storage.StorageWriteResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PreviewCacheIndexTest {

    @Test
    void publishesIndexOnlyAfterPngWriteSucceeds() {
        final RecordingStorage storage = new RecordingStorage();
        final PreviewCacheIndex index = new PreviewCacheIndex(storage);

        final PreviewCacheWriteResult result = store(index);

        assertEquals(PreviewCacheWriteResult.STORED, result);
        assertEquals(4, storage.operations.size());
        assertTrue(storage.operations.get(0).startsWith("bytes:recent-preview/staging/"));
        assertTrue(storage.operations.get(0).endsWith(".png"));
        assertTrue(storage.operations.get(1).startsWith("text:recent-preview/staging/"));
        assertTrue(storage.operations.get(1).endsWith(".entry"));
        assertTrue(storage.operations.get(2).contains("->recent-preview/images/"));
        assertTrue(storage.operations.get(3).contains("->recent-preview/index/"));
    }

    @Test
    void doesNotPublishIndexWhenPngWriteFails() {
        final RecordingStorage storage = new RecordingStorage();
        storage.failImageWrite = true;

        assertEquals(PreviewCacheWriteResult.IMAGE_WRITE_FAILED, store(new PreviewCacheIndex(storage)));
        assertEquals(1, storage.operations.size());
    }

    @Test
    void removesOrphanPngWhenIndexWriteFails() {
        final RecordingStorage storage = new RecordingStorage();
        storage.failIndexWrite = true;

        assertEquals(PreviewCacheWriteResult.INDEX_WRITE_FAILED, store(new PreviewCacheIndex(storage)));
        assertEquals(4, storage.operations.size());
        assertTrue(storage.operations.get(2).startsWith("delete:recent-preview/staging/"));
        assertTrue(storage.operations.get(3).startsWith("delete:recent-preview/staging/"));
    }

    @Test
    void generationInvalidationDeletesStagedFilesWithoutPublishing() {
        final RecordingStorage storage = new RecordingStorage();
        final java.util.concurrent.atomic.AtomicBoolean allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
        storage.afterImageWrite = () -> allowed.set(false);
        final PreviewCacheIndex index = new PreviewCacheIndex(storage);

        final PreviewCacheWriteResult result = index.store(
            new RecentFileSummary(new RecentFileId("recent-1"), "model.cmo3"),
            new ScreenshotImage(1, 1, png()),
            allowed::get
        ).toCompletableFuture().join();

        assertEquals(PreviewCacheWriteResult.DISABLED, result);
        assertTrue(storage.operations.stream().noneMatch(operation -> operation.startsWith("move:")));
        assertEquals(2, storage.operations.stream()
            .filter(operation -> operation.startsWith("delete:recent-preview/staging/"))
            .count());
    }

    @Test
    void writesIndexWithoutAnyPath() {
        final RecordingStorage storage = new RecordingStorage();
        final PreviewCacheIndex index = new PreviewCacheIndex(storage);

        assertEquals(PreviewCacheWriteResult.STORED, store(index));

        final String entry = storage.lastIndexEntry;
        assertTrue(entry.startsWith("v2\n"));
        assertTrue(entry.contains("recent-1\n"));
        assertTrue(entry.contains("model.cmo3\n"));
        assertFalse(entry.contains("/"));
        assertFalse(entry.contains("\\"));
        assertFalse(entry.contains(":"));
    }

    @Test
    void loadsOnlyBoundedPngForCurrentRecentFiles() {
        final RecordingStorage storage = new RecordingStorage();
        storage.readBytes = png();
        final RecentFileSummary file = new RecentFileSummary(new RecentFileId("recent-1"), "model.cmo3");

        final Map<RecentFileId, byte[]> loaded = new PreviewCacheIndex(storage)
            .loadPng(List.of(file)).toCompletableFuture().join();

        assertArrayEquals(png(), loaded.get(file.id()));
        assertEquals(1_048_576, storage.lastMaxBytes);
        assertTrue(storage.operations.get(0).startsWith("read:recent-preview/images/"));
    }

    @Test
    void ignoresCorruptCachedPng() {
        final RecordingStorage storage = new RecordingStorage();
        storage.readBytes = new byte[]{1, 2, 3};
        final RecentFileSummary file = new RecentFileSummary(new RecentFileId("recent-1"), "model.cmo3");

        assertTrue(new PreviewCacheIndex(storage).loadPng(List.of(file))
            .toCompletableFuture().join().isEmpty());
    }

    @Test
    void keyAndImagePathAreDerivedFromTheOpaqueIdOnly() {
        assertEquals(64, PreviewCacheIndex.key("recent-1").length());
        assertTrue(PreviewCacheIndex.key("recent-1").matches("[0-9a-f]{64}"));
        assertEquals(
            "recent-preview/images/" + PreviewCacheIndex.key("recent-1") + ".png",
            PreviewCacheIndex.imageRelativePath("recent-1")
        );
        assertEquals(
            PreviewCacheIndex.key("recent-1"),
            PreviewCacheIndex.key(new RecentFileId("recent-1").value())
        );
    }

    private static PreviewCacheWriteResult store(final PreviewCacheIndex index) {
        return index.store(
            new RecentFileSummary(new RecentFileId("recent-1"), "model.cmo3"),
            new ScreenshotImage(1, 1, png())
        ).toCompletableFuture().join();
    }

    private static byte[] png() {
        return java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        );
    }

    private static final class RecordingStorage implements PluginStorage {
        private final List<String> operations = new ArrayList<>();
        private boolean failImageWrite;
        private boolean failIndexWrite;
        private byte[] readBytes;
        private int lastMaxBytes;
        private String lastIndexEntry;
        private Runnable afterImageWrite = () -> { };

        @Override
        public CompletionStage<StorageWriteResult> writeBytesAtomic(StoragePath path, byte[] content) {
            operations.add("bytes:" + path.relativePath());
            final StorageWriteResult result = failImageWrite ? failedWrite(path) : successfulWrite();
            afterImageWrite.run();
            return CompletableFuture.completedStage(result);
        }

        @Override
        public CompletionStage<StorageWriteResult> writeUtf8Atomic(StoragePath path, String content) {
            operations.add("text:" + path.relativePath());
            lastIndexEntry = content;
            return CompletableFuture.completedStage(failIndexWrite ? failedWrite(path) : successfulWrite());
        }

        @Override
        public CompletionStage<StorageReadResult<String>> readUtf8(StoragePath path, int maxBytes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<StorageReadResult<byte[]>> readBytes(StoragePath path, int maxBytes) {
            operations.add("read:" + path.relativePath());
            lastMaxBytes = maxBytes;
            return CompletableFuture.completedStage(
                new StorageReadResult<>(Optional.ofNullable(readBytes), Optional.empty(), false)
            );
        }

        @Override
        public CompletionStage<StorageListResult> list(StoragePath directory, int maxEntries) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<StorageMutationResult> copy(
            StoragePath source, StoragePath target, boolean replaceExisting
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<StorageMutationResult> moveAtomic(
            StoragePath source, StoragePath target, boolean replaceExisting
        ) {
            operations.add("move:" + source.relativePath() + "->" + target.relativePath());
            return CompletableFuture.completedStage(new StorageMutationResult(true, Optional.empty()));
        }

        @Override
        public CompletionStage<StorageMutationResult> delete(StoragePath path, boolean recursive) {
            operations.add("delete:" + path.relativePath());
            return CompletableFuture.completedStage(new StorageMutationResult(true, Optional.empty()));
        }

        private static StorageWriteResult successfulWrite() {
            return new StorageWriteResult(true, Optional.empty());
        }

        private static StorageWriteResult failedWrite(final StoragePath path) {
            return new StorageWriteResult(false, Optional.of(new StorageError(
                StorageErrorCode.IO_FAILURE, "failed", path
            )));
        }
    }
}
