package dev.turboism.test.storage;

import dev.turboism.sdk.storage.PluginStorage;
import dev.turboism.sdk.storage.StorageEntry;
import dev.turboism.sdk.storage.StorageEntryType;
import dev.turboism.sdk.storage.StorageError;
import dev.turboism.sdk.storage.StorageErrorCode;
import dev.turboism.sdk.storage.StorageListResult;
import dev.turboism.sdk.storage.StorageMutationResult;
import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.storage.StorageReadResult;
import dev.turboism.sdk.storage.StorageRoot;
import dev.turboism.sdk.storage.StorageWriteResult;
import dev.turboism.test.concurrent.Await;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

/**
 * In-memory implementation of {@link PluginStorage} for testing.
 */
public class InMemoryPluginStorage implements PluginStorage {

    public final Map<StoragePath, byte[]> files = new ConcurrentHashMap<>();
    private final List<String> operations = new CopyOnWriteArrayList<>();
    public volatile StoragePath lastWritePath;

    private volatile CompletableFuture<Void> readGate = CompletableFuture.completedFuture(null);
    private volatile CountDownLatch readStarted = new CountDownLatch(0);

    private volatile boolean failWrite;
    private volatile boolean failRead;
    private volatile StorageError writeError;
    private volatile StorageError readError;

    public InMemoryPluginStorage seed(final StoragePath path, final String content) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(content, "content");
        files.put(path, content.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    public InMemoryPluginStorage seed(final StoragePath path, final byte[] content) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(content, "content");
        files.put(path, content.clone());
        return this;
    }

    public InMemoryPluginStorage seed(final String relativePath, final String content) {
        return seed(new StoragePath(StorageRoot.DATA, relativePath), content);
    }

    public InMemoryPluginStorage seed(final String relativePath, final byte[] content) {
        return seed(new StoragePath(StorageRoot.DATA, relativePath), content);
    }

    public synchronized String content(final StoragePath path) {
        final byte[] bytes = findBytes(path);
        return bytes != null ? new String(bytes, StandardCharsets.UTF_8) : null;
    }

    public String content(final String relativePath) {
        return content(new StoragePath(StorageRoot.DATA, relativePath));
    }

    public synchronized byte[] bytes(final StoragePath path) {
        final byte[] bytes = findBytes(path);
        return bytes != null ? bytes.clone() : null;
    }

    public byte[] bytes(final String relativePath) {
        return bytes(new StoragePath(StorageRoot.DATA, relativePath));
    }

    public void clear() {
        files.clear();
        operations.clear();
    }

    public void setReadGate(final CompletableFuture<Void> gate) {
        this.readGate = Objects.requireNonNull(gate, "gate");
    }

    public CompletableFuture<Void> readGate() {
        return readGate;
    }

    public void setReadStarted(final CountDownLatch latch) {
        this.readStarted = Objects.requireNonNull(latch, "latch");
    }

    public CountDownLatch readStarted() {
        return readStarted;
    }

    public void setFailWrite(final boolean failWrite) {
        this.failWrite = failWrite;
    }

    public void setWriteError(final StorageError writeError) {
        this.writeError = writeError;
    }

    public void setFailRead(final boolean failRead) {
        this.failRead = failRead;
    }

    public void setReadError(final StorageError readError) {
        this.readError = readError;
    }

    public List<String> operations() {
        return List.copyOf(operations);
    }

    public void awaitOperations(final int count) {
        Await.condition(
                "storage operations reached " + count + "; saw " + operations.size(), () -> operationCount() >= count);
    }

    private synchronized int operationCount() {
        return operations.size();
    }

    private byte[] findBytes(final StoragePath path) {
        final byte[] exact = files.get(path);
        if (exact != null) {
            return exact;
        }
        for (final Map.Entry<StoragePath, byte[]> entry : files.entrySet()) {
            if (entry.getKey().relativePath().equals(path.relativePath())) {
                return entry.getValue();
            }
        }
        return null;
    }

    @Override
    public CompletionStage<StorageReadResult<String>> readUtf8(final StoragePath path, final int maxBytes) {
        operations.add("readUtf8:" + path.relativePath());
        readStarted.countDown();
        return readGate.thenApply(ignored -> {
            if (failRead) {
                final StorageError error = readError != null
                        ? readError
                        : new StorageError(StorageErrorCode.IO_FAILURE, "read failed", path);
                return new StorageReadResult<>(Optional.empty(), Optional.of(error), false);
            }
            final byte[] bytes = findBytes(path);
            if (bytes == null) {
                return new StorageReadResult<>(
                        Optional.empty(),
                        Optional.of(new StorageError(
                                StorageErrorCode.NOT_FOUND, "File not found: " + path.relativePath(), path)),
                        false);
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            boolean truncated = false;
            if (maxBytes > 0 && bytes.length > maxBytes) {
                final byte[] sliced = Arrays.copyOf(bytes, maxBytes);
                text = new String(sliced, StandardCharsets.UTF_8);
                truncated = true;
            }
            return new StorageReadResult<>(Optional.of(text), Optional.empty(), truncated);
        });
    }

    @Override
    public CompletionStage<StorageReadResult<byte[]>> readBytes(final StoragePath path, final int maxBytes) {
        operations.add("readBytes:" + path.relativePath());
        readStarted.countDown();
        return readGate.thenApply(ignored -> {
            if (failRead) {
                final StorageError error = readError != null
                        ? readError
                        : new StorageError(StorageErrorCode.IO_FAILURE, "read failed", path);
                return new StorageReadResult<>(Optional.empty(), Optional.of(error), false);
            }
            final byte[] bytes = findBytes(path);
            if (bytes == null) {
                return new StorageReadResult<>(
                        Optional.empty(),
                        Optional.of(new StorageError(
                                StorageErrorCode.NOT_FOUND, "File not found: " + path.relativePath(), path)),
                        false);
            }
            boolean truncated = false;
            byte[] returned = bytes.clone();
            if (maxBytes > 0 && returned.length > maxBytes) {
                returned = Arrays.copyOf(returned, maxBytes);
                truncated = true;
            }
            return new StorageReadResult<>(Optional.of(returned), Optional.empty(), truncated);
        });
    }

    @Override
    public synchronized CompletionStage<StorageWriteResult> writeUtf8Atomic(
            final StoragePath path, final String content) {
        lastWritePath = path;
        operations.add("writeUtf8:" + path.relativePath());
        if (failWrite) {
            final StorageError error = writeError != null
                    ? writeError
                    : new StorageError(StorageErrorCode.IO_FAILURE, "write failed", path);
            return CompletableFuture.completedFuture(new StorageWriteResult(false, Optional.of(error)));
        }
        files.put(path, content.getBytes(StandardCharsets.UTF_8));
        return CompletableFuture.completedFuture(new StorageWriteResult(true, Optional.empty()));
    }

    @Override
    public synchronized CompletionStage<StorageWriteResult> writeBytesAtomic(
            final StoragePath path, final byte[] content) {
        lastWritePath = path;
        operations.add("writeBytes:" + path.relativePath());
        if (failWrite) {
            final StorageError error = writeError != null
                    ? writeError
                    : new StorageError(StorageErrorCode.IO_FAILURE, "write failed", path);
            return CompletableFuture.completedFuture(new StorageWriteResult(false, Optional.of(error)));
        }
        files.put(path, content.clone());
        return CompletableFuture.completedFuture(new StorageWriteResult(true, Optional.empty()));
    }

    @Override
    public CompletionStage<StorageListResult> list(final StoragePath directory, final int maxEntries) {
        operations.add("list:" + directory.relativePath());
        final String prefix =
                directory.relativePath().endsWith("/") ? directory.relativePath() : directory.relativePath() + "/";
        List<StorageEntry> entries = new ArrayList<>();
        for (final Map.Entry<StoragePath, byte[]> entry : files.entrySet()) {
            if (entry.getKey().root() == directory.root()
                    && entry.getKey().relativePath().startsWith(prefix)) {
                entries.add(new StorageEntry(entry.getKey(), StorageEntryType.FILE, entry.getValue().length));
            }
        }
        boolean truncated = false;
        if (maxEntries > 0 && entries.size() > maxEntries) {
            entries = entries.subList(0, maxEntries);
            truncated = true;
        }
        return CompletableFuture.completedFuture(new StorageListResult(entries, Optional.empty(), truncated));
    }

    @Override
    public synchronized CompletionStage<StorageMutationResult> copy(
            final StoragePath source, final StoragePath target, final boolean replaceExisting) {
        operations.add("copy:" + source.relativePath() + "->" + target.relativePath());
        final byte[] src = findBytes(source);
        if (src == null) {
            return CompletableFuture.completedFuture(new StorageMutationResult(
                    false, Optional.of(new StorageError(StorageErrorCode.NOT_FOUND, "source not found", source))));
        }
        if (!replaceExisting && findBytes(target) != null) {
            return CompletableFuture.completedFuture(new StorageMutationResult(
                    false,
                    Optional.of(new StorageError(StorageErrorCode.ALREADY_EXISTS, "target already exists", target))));
        }
        files.put(target, src.clone());
        return CompletableFuture.completedFuture(new StorageMutationResult(true, Optional.empty()));
    }

    @Override
    public synchronized CompletionStage<StorageMutationResult> moveAtomic(
            final StoragePath source, final StoragePath target, final boolean replaceExisting) {
        operations.add("move:" + source.relativePath() + "->" + target.relativePath());
        if (source.root() != target.root()) {
            return CompletableFuture.completedFuture(new StorageMutationResult(
                    false,
                    Optional.of(new StorageError(
                            StorageErrorCode.CROSS_ROOT_ATOMIC_MOVE_UNSUPPORTED, "cross-root", source))));
        }
        final byte[] src = files.remove(source);
        if (src == null) {
            return CompletableFuture.completedFuture(new StorageMutationResult(
                    false, Optional.of(new StorageError(StorageErrorCode.NOT_FOUND, "source not found", source))));
        }
        if (!replaceExisting && findBytes(target) != null) {
            files.put(source, src);
            return CompletableFuture.completedFuture(new StorageMutationResult(
                    false,
                    Optional.of(new StorageError(StorageErrorCode.ALREADY_EXISTS, "target already exists", target))));
        }
        files.put(target, src);
        return CompletableFuture.completedFuture(new StorageMutationResult(true, Optional.empty()));
    }

    @Override
    public synchronized CompletionStage<StorageMutationResult> delete(final StoragePath path, final boolean recursive) {
        operations.add("delete:" + path.relativePath());
        final byte[] removed = files.remove(path);
        boolean anyRemoved = removed != null;
        if (recursive) {
            final String prefix = path.relativePath().endsWith("/") ? path.relativePath() : path.relativePath() + "/";
            final boolean removedChildren = files.keySet()
                    .removeIf(k -> k.root() == path.root() && k.relativePath().startsWith(prefix));
            anyRemoved = anyRemoved || removedChildren;
        }
        if (!anyRemoved) {
            return CompletableFuture.completedFuture(new StorageMutationResult(
                    false, Optional.of(new StorageError(StorageErrorCode.NOT_FOUND, "not found", path))));
        }
        return CompletableFuture.completedFuture(new StorageMutationResult(true, Optional.empty()));
    }
}
