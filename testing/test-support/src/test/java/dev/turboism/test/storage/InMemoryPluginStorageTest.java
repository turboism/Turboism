package dev.turboism.test.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.storage.StorageListResult;
import dev.turboism.sdk.storage.StorageMutationResult;
import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.storage.StorageReadResult;
import dev.turboism.sdk.storage.StorageRoot;
import dev.turboism.sdk.storage.StorageWriteResult;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryPluginStorageTest {

    private InMemoryPluginStorage storage;

    @BeforeEach
    void setUp() {
        storage = new InMemoryPluginStorage();
    }

    @Test
    void writeAndReadUtf8() throws Exception {
        final StoragePath path = new StoragePath(StorageRoot.DATA, "test.txt");
        final StorageWriteResult write = storage.writeUtf8Atomic(path, "hello world")
                .toCompletableFuture()
                .get();
        assertTrue(write.written());
        assertTrue(write.error().isEmpty());

        final StorageReadResult<String> read =
                storage.readUtf8(path, 1024).toCompletableFuture().get();
        assertEquals("hello world", read.value().orElseThrow());
        assertFalse(read.truncated());
        assertEquals("hello world", storage.content(path));
    }

    @Test
    void readMissingFileReturnsError() throws Exception {
        final StoragePath path = new StoragePath(StorageRoot.DATA, "missing.txt");
        final StorageReadResult<String> read =
                storage.readUtf8(path, 1024).toCompletableFuture().get();
        assertTrue(read.value().isEmpty());
        assertTrue(read.error().isPresent());
    }

    @Test
    void writeAndReadBytes() throws Exception {
        final StoragePath path = new StoragePath(StorageRoot.DATA, "data.bin");
        final byte[] raw = new byte[] {1, 2, 3, 4};
        storage.writeBytesAtomic(path, raw).toCompletableFuture().get();

        final StorageReadResult<byte[]> read =
                storage.readBytes(path, 1024).toCompletableFuture().get();
        assertArrayEquals(raw, read.value().orElseThrow());
    }

    @Test
    void seedAndContentConvenienceMethods() {
        storage.seed("sample.json", "{\"k\":\"v\"}");
        assertEquals("{\"k\":\"v\"}", storage.content("sample.json"));
        assertArrayEquals("{\"k\":\"v\"}".getBytes(StandardCharsets.UTF_8), storage.bytes("sample.json"));
    }

    @Test
    void listFiles() throws Exception {
        storage.seed(new StoragePath(StorageRoot.DATA, "dir/a.txt"), "a");
        storage.seed(new StoragePath(StorageRoot.DATA, "dir/b.txt"), "bb");

        final StorageListResult result = storage.list(new StoragePath(StorageRoot.DATA, "dir"), 10)
                .toCompletableFuture()
                .get();
        assertEquals(2, result.entries().size());
    }

    @Test
    void deleteAndCopy() throws Exception {
        final StoragePath src = new StoragePath(StorageRoot.DATA, "orig.txt");
        final StoragePath dst = new StoragePath(StorageRoot.DATA, "copied.txt");
        storage.seed(src, "content");

        final StorageMutationResult copyResult =
                storage.copy(src, dst, false).toCompletableFuture().get();
        assertTrue(copyResult.changed());
        assertEquals("content", storage.content(dst));

        final StorageMutationResult delResult =
                storage.delete(src, false).toCompletableFuture().get();
        assertTrue(delResult.changed());
        org.junit.jupiter.api.Assertions.assertNull(storage.content(src));
    }
}
