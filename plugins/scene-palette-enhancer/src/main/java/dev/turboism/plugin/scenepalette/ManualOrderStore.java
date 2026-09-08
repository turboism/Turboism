package dev.turboism.plugin.scenepalette;

import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.storage.PluginStorage;
import dev.turboism.sdk.storage.StorageErrorCode;
import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.storage.StorageReadResult;
import dev.turboism.sdk.storage.StorageRoot;
import dev.turboism.sdk.storage.StorageWriteResult;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

interface ManualOrderStore {

    String FORMAT_PREFIX = "# turboism.scene.manual-order v";
    String FORMAT_HEADER = FORMAT_PREFIX + "1";

    CompletionStage<LoadResult> load(String scopeId);

    CompletionStage<Void> save(String scopeId, List<String> itemIds);

    enum LoadStatus {
        CURRENT,
        LEGACY,
        MISSING,
        UNUSABLE
    }

    record LoadResult(LoadStatus status, List<String> itemIds) {
        public LoadResult {
            status = Objects.requireNonNull(status, "status");
            itemIds = List.copyOf(itemIds);
        }

        static LoadResult current(final List<String> itemIds) {
            return new LoadResult(LoadStatus.CURRENT, itemIds);
        }

        static LoadResult legacy(final List<String> itemIds) {
            return new LoadResult(LoadStatus.LEGACY, itemIds);
        }

        static LoadResult missing() {
            return new LoadResult(LoadStatus.MISSING, List.of());
        }

        static LoadResult unusable() {
            return new LoadResult(LoadStatus.UNUSABLE, List.of());
        }
    }

    static ManualOrderStore unavailable() {
        return new ManualOrderStore() {
            @Override public CompletionStage<LoadResult> load(final String scopeId) {
                return CompletableFuture.completedStage(LoadResult.unusable());
            }

            @Override public CompletionStage<Void> save(final String scopeId, final List<String> itemIds) {
                return CompletableFuture.completedStage(null);
            }
        };
    }

    static ManualOrderStore storage(final PluginStorage storage, final PluginLogger logger) {
        return new StorageManualOrderStore(storage, logger);
    }
}

final class StorageManualOrderStore implements ManualOrderStore {

    private static final int MAX_BYTES = 256 * 1024;

    private final PluginStorage storage;
    private final PluginLogger logger;
    private final Object saveLock = new Object();
    private CompletionStage<Void> saveTail = CompletableFuture.completedStage(null);

    StorageManualOrderStore(final PluginStorage storage, final PluginLogger logger) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public CompletionStage<LoadResult> load(final String scopeId) {
        final CompletionStage<Void> priorWrites;
        synchronized (saveLock) {
            priorWrites = saveTail.handle((ignored, failure) -> null);
        }
        return priorWrites.thenCompose(ignored -> read(scopeId));
    }

    private CompletionStage<LoadResult> read(final String scopeId) {
        final CompletionStage<StorageReadResult<String>> read;
        try {
            read = Objects.requireNonNull(
                storage.readUtf8(path(scopeId), MAX_BYTES),
                "storage read stage"
            );
        } catch (RuntimeException failure) {
            warnFailure("read", failure);
            return CompletableFuture.completedStage(LoadResult.unusable());
        }
        return read.handle((result, failure) -> {
            if (failure != null) {
                warnFailure("read", failure);
                return LoadResult.unusable();
            }
            if (result == null) {
                logger.warn("Scene manual order could not be read: missing storage result");
                return LoadResult.unusable();
            }
            if (result.error().map(error -> error.code() == StorageErrorCode.NOT_FOUND).orElse(false)) {
                return LoadResult.missing();
            }
            if (result.error().isPresent()) {
                logger.warn("Scene manual order could not be read: " + result.error().orElseThrow().code());
                return LoadResult.unusable();
            }
            if (result.truncated()) {
                logger.warn("Scene manual order could not be read: truncated");
                return LoadResult.unusable();
            }
            return result.value().map(StorageManualOrderStore::parse).orElseGet(() -> {
                logger.warn("Scene manual order could not be read: missing content");
                return LoadResult.unusable();
            });
        });
    }

    @Override
    public CompletionStage<Void> save(final String scopeId, final List<String> itemIds) {
        final StoragePath target;
        final String content;
        try {
            target = path(scopeId);
            content = serialize(List.copyOf(itemIds));
        } catch (RuntimeException failure) {
            warnFailure("written", failure);
            return CompletableFuture.completedStage(null);
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            logger.warn("Scene manual order could not be written: exceeds " + MAX_BYTES + " byte limit");
            return CompletableFuture.completedStage(null);
        }
        synchronized (saveLock) {
            final CompletionStage<Void> next = saveTail.handle((ignored, failure) -> null)
                .thenCompose(ignored -> write(target, content));
            saveTail = next;
            return next;
        }
    }

    private CompletionStage<Void> write(final StoragePath target, final String content) {
        final CompletionStage<StorageWriteResult> write;
        try {
            write = Objects.requireNonNull(
                storage.writeUtf8Atomic(target, content),
                "storage write stage"
            );
        } catch (RuntimeException failure) {
            warnFailure("written", failure);
            return CompletableFuture.failedStage(failure);
        }
        return write.handle((result, failure) -> {
            if (failure != null) {
                warnFailure("written", failure);
                throw unwrap(failure);
            }
            if (result == null) {
                logger.warn("Scene manual order could not be written: missing storage result");
                throw new IllegalStateException("Scene manual order write failed: missing storage result");
            }
            if (!result.written()) {
                final String reason = result.error()
                    .map(error -> error.code().toString())
                    .orElse("unknown");
                logger.warn("Scene manual order could not be written: " + reason);
                throw new IllegalStateException("Scene manual order write failed: " + reason);
            }
            return null;
        });
    }

    private static RuntimeException unwrap(final Throwable failure) {
        return failure instanceof RuntimeException runtime ? runtime : new RuntimeException(failure);
    }

    private void warnFailure(final String operation, final Throwable failure) {
        logger.warn("Scene manual order could not be " + operation + ": "
            + failure.getClass().getSimpleName());
    }

    private static LoadResult parse(final String content) {
        final String[] lines = content.split("\\R", -1);
        if (lines.length > 0 && FORMAT_HEADER.equals(lines[0].trim())) {
            return LoadResult.current(itemIds(lines, 1));
        }
        if (lines.length > 0 && lines[0].trim().startsWith(FORMAT_PREFIX)) {
            return LoadResult.unusable();
        }
        return LoadResult.legacy(itemIds(lines, 0));
    }

    private static List<String> itemIds(final String[] lines, final int start) {
        return Arrays.stream(lines, start, lines.length)
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .distinct()
            .toList();
    }

    private static String serialize(final List<String> itemIds) {
        final String body = String.join("\n", itemIds);
        return FORMAT_HEADER + "\n" + (body.isEmpty() ? "" : body + "\n");
    }

    private static StoragePath path(final String scopeId) {
        if (scopeId == null || !scopeId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid Scene scopeId");
        }
        return new StoragePath(StorageRoot.STATE, "manual-order-" + scopeId + ".txt");
    }
}
