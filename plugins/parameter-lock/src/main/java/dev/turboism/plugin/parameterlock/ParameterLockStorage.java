package dev.turboism.plugin.parameterlock;

import dev.turboism.sdk.json.Json;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.storage.PluginStorage;
import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.storage.StorageRoot;
import dev.turboism.sdk.storage.StorageWriteResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Service that persists parameter lock snapshots under {@link StorageRoot#STATE}.
 *
 * <p>Enforces strict per-model sequential saves, SHA-256 collision-free path hashing,
 * and identity/version verification on read.</p>
 */
final class ParameterLockStorage {

    private static final int MAX_LOCK_FILE_BYTES = 128 * 1024;
    private final PluginStorage storage;
    private final PluginLogger logger;
    private final Executor executor;
    private final Map<String, CompletableFuture<Void>> saveChains = new ConcurrentHashMap<>();

    public ParameterLockStorage(final PluginStorage storage, final PluginLogger logger, final Executor executor) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public CompletionStage<ParameterLockData> load(final String modelGuid) {
        if (modelGuid == null || modelGuid.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        final String guid = modelGuid.strip();
        final StoragePath path = storagePathFor(guid);
        return storage.readUtf8(path, MAX_LOCK_FILE_BYTES)
                .thenApplyAsync(
                        result -> {
                            if (result.value().isEmpty()) {
                                return ParameterLockData.empty(guid);
                            }
                            try {
                                final var map = Json.parseObject(result.value().orElseThrow());
                                final ParameterLockData data = ParameterLockData.fromMap(map);
                                if (data == null) {
                                    logger.warn("Null parameter lock data loaded for model: " + guid);
                                    return ParameterLockData.empty(guid);
                                }
                                if (data.version() != ParameterLockData.CURRENT_VERSION) {
                                    logger.warn("Unsupported parameter lock schema version " + data.version()
                                            + " for model: " + guid);
                                    return ParameterLockData.empty(guid);
                                }
                                if (!guid.equals(data.modelGuid())) {
                                    logger.warn("Model GUID mismatch in lock file: expected " + guid + ", found "
                                            + data.modelGuid());
                                    return ParameterLockData.empty(guid);
                                }
                                return data;
                            } catch (RuntimeException parseError) {
                                logger.warn("Malformed parameter lock file for model " + guid + ": "
                                        + parseError.getMessage());
                                return ParameterLockData.empty(guid);
                            }
                        },
                        executor)
                .exceptionally(failure -> {
                    logger.warn("Failed reading parameter lock file for model " + guid + ": " + failure.getMessage());
                    return ParameterLockData.empty(guid);
                });
    }

    /**
     * Serializes save requests per model GUID so that later updates never get overwritten
     * by earlier in-flight writes.
     */
    public CompletionStage<Boolean> save(final ParameterLockData data) {
        if (data == null) {
            return CompletableFuture.completedFuture(false);
        }
        final String guid = data.modelGuid();
        final CompletableFuture<Boolean> writeOutcome = new CompletableFuture<>();

        saveChains.compute(guid, (k, previousChain) -> {
            final CompletableFuture<?> prerequisite =
                    previousChain != null ? previousChain : CompletableFuture.completedFuture(null);
            final CompletableFuture<Void> nextChain = prerequisite
                    .handle((ignored, error) -> null)
                    .thenComposeAsync(ignored -> doSave(data), executor)
                    .whenComplete((written, error) -> {
                        if (error != null) {
                            writeOutcome.complete(false);
                        } else {
                            writeOutcome.complete(written);
                        }
                    })
                    .thenApply(written -> null);
            return nextChain;
        });

        return writeOutcome;
    }

    private CompletionStage<Boolean> doSave(final ParameterLockData data) {
        final StoragePath path = storagePathFor(data.modelGuid());
        final String json;
        try {
            json = Json.stringify(data.toMap());
        } catch (RuntimeException stringifyError) {
            logger.warn("Failed serializing parameter lock data for model " + data.modelGuid() + ": "
                    + stringifyError.getMessage());
            return CompletableFuture.completedFuture(false);
        }
        return storage.writeUtf8Atomic(path, json)
                .thenApplyAsync(StorageWriteResult::written, executor)
                .exceptionally(failure -> {
                    logger.warn("Failed writing parameter lock file for model " + data.modelGuid() + ": "
                            + failure.getMessage());
                    return false;
                });
    }

    static StoragePath storagePathFor(final String modelGuid) {
        final String sha256 = sha256Hex(modelGuid);
        return new StoragePath(StorageRoot.STATE, "locks/" + sha256 + ".json");
    }

    static String sha256Hex(final String text) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(text.strip().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
