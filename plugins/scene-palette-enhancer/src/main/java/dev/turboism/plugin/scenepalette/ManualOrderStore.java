package dev.turboism.plugin.scenepalette;

import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.storage.PluginStorage;
import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.storage.StorageRoot;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

interface ManualOrderStore {

    CompletionStage<List<String>> load(String scopeId);

    CompletionStage<Void> save(String scopeId, List<String> itemIds);

    static ManualOrderStore unavailable() {
        return new ManualOrderStore() {
            @Override public CompletionStage<List<String>> load(final String scopeId) {
                return CompletableFuture.completedFuture(List.of());
            }

            @Override public CompletionStage<Void> save(final String scopeId, final List<String> itemIds) {
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    static ManualOrderStore storage(final PluginStorage storage, final PluginLogger logger) {
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(logger, "logger");
        return new ManualOrderStore() {
            @Override public CompletionStage<List<String>> load(final String scopeId) {
                return storage.readUtf8(path(scopeId), 256 * 1024).thenApply(result -> {
                    if (result.error().isPresent()) {
                        logger.warn("Scene manual order could not be read: " + result.error().orElseThrow().code());
                        return List.of();
                    }
                    return result.value().map(content -> Arrays.stream(content.split("\\R"))
                        .map(String::trim).filter(value -> !value.isEmpty()).distinct().toList()).orElse(List.of());
                });
            }

            @Override public CompletionStage<Void> save(final String scopeId, final List<String> itemIds) {
                return storage.writeUtf8Atomic(path(scopeId), String.join("\n", itemIds) + "\n").thenAccept(result -> {
                    if (!result.written()) {
                        logger.warn("Scene manual order could not be written: "
                            + result.error().map(error -> error.code().toString()).orElse("unknown"));
                    }
                });
            }

            private StoragePath path(final String scopeId) {
                if (!scopeId.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid Scene scopeId");
                return new StoragePath(StorageRoot.STATE, "manual-order-" + scopeId + ".txt");
            }
        };
    }
}
