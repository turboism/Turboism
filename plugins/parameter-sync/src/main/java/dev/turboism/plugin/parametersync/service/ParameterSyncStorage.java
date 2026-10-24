package dev.turboism.plugin.parametersync.service;

import dev.turboism.plugin.parametersync.domain.ParameterSyncData;
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
 * Service that persists parameter synchronization rules under {@link StorageRoot#STATE}.
 */
public final class ParameterSyncStorage {

    private static final int MAX_FILE_BYTES = 256 * 1024;
    private final PluginStorage storage;
    private final PluginLogger logger;
    private final Executor executor;
    private final Map<String, CompletableFuture<Void>> saveChains = new ConcurrentHashMap<>();

    public ParameterSyncStorage(final PluginStorage storage, final PluginLogger logger, final Executor executor) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public CompletionStage<ParameterSyncData> load(final String modelGuid) {
        if (modelGuid == null || modelGuid.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        final String guid = modelGuid.strip();
        final StoragePath path = storagePathFor(guid);
        return storage.readUtf8(path, MAX_FILE_BYTES)
                .thenApplyAsync(
                        result -> {
                            if (result.value().isEmpty()) {
                                return ParameterSyncData.empty(guid);
                            }
                            try {
                                final var map = Json.parseObject(result.value().orElseThrow());
                                final ParameterSyncData data = ParameterSyncData.fromMap(map);
                                if (data == null) {
                                    logger.warn("Null parameter sync data loaded for model: " + guid);
                                    return ParameterSyncData.empty(guid);
                                }
                                if (data.version() < 1 || data.version() > ParameterSyncData.CURRENT_VERSION) {
                                    logger.warn("Unsupported parameter sync schema version " + data.version()
                                            + " for model: " + guid);
                                    return ParameterSyncData.empty(guid);
                                }
                                if (!guid.equals(data.modelGuid())) {
                                    logger.warn("Model GUID mismatch in sync file: expected " + guid + ", found "
                                            + data.modelGuid());
                                    return ParameterSyncData.empty(guid);
                                }
                                return data;
                            } catch (RuntimeException parseError) {
                                logger.warn("Malformed parameter sync file for model " + guid + ": "
                                        + parseError.getMessage());
                                return ParameterSyncData.empty(guid);
                            }
                        },
                        executor)
                .exceptionally(failure -> {
                    logger.warn("Failed reading parameter sync file for model " + guid + ": " + failure.getMessage());
                    return ParameterSyncData.empty(guid);
                });
    }

    public CompletionStage<Boolean> save(final ParameterSyncData data) {
        if (data == null) {
            return CompletableFuture.completedFuture(false);
        }
        final String guid = data.modelGuid();
        final CompletableFuture<Boolean> writeOutcome = new CompletableFuture<>();

        saveChains.compute(guid, (k, previousChain) -> {
            final CompletableFuture<?> prerequisite =
                    previousChain != null ? previousChain : CompletableFuture.completedFuture(null);
            return prerequisite
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
        });

        return writeOutcome;
    }

    private CompletionStage<Boolean> doSave(final ParameterSyncData data) {
        final StoragePath path = storagePathFor(data.modelGuid());
        final String json;
        try {
            json = Json.stringify(data.toMap());
        } catch (RuntimeException stringifyError) {
            logger.warn("Failed serializing parameter sync data for model " + data.modelGuid() + ": "
                    + stringifyError.getMessage());
            return CompletableFuture.completedFuture(false);
        }
        return storage.writeUtf8Atomic(path, json)
                .thenApplyAsync(StorageWriteResult::written, executor)
                .exceptionally(failure -> {
                    logger.warn("Storage write exception for parameter sync model " + data.modelGuid() + ": "
                            + failure.getMessage());
                    return false;
                });
    }

    static StoragePath storagePathFor(final String modelGuid) {
        final String hashed = sha256Hex(modelGuid);
        return new StoragePath(StorageRoot.STATE, "parameter-sync/" + hashed + ".json");
    }

    private static String sha256Hex(final String value) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
