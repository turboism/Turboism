package dev.turboism.plugin.uitheme.service;

import dev.turboism.plugin.uitheme.b1.domain.ThemeBase;
import dev.turboism.plugin.uitheme.b1.domain.ThemeIcons;
import dev.turboism.plugin.uitheme.b1.domain.ThemePackageData;
import dev.turboism.plugin.uitheme.b1.domain.ThemePackageMetadata;
import dev.turboism.sdk.storage.PluginStorage;
import dev.turboism.sdk.storage.StorageEntry;
import dev.turboism.sdk.storage.StorageError;
import dev.turboism.sdk.storage.StorageListResult;
import dev.turboism.sdk.storage.StorageMutationResult;
import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.storage.StorageReadResult;
import dev.turboism.sdk.storage.StorageWriteResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class ThemePackageRepositoryTest {

    @Test
    void savingAndLoadingAThemeUsesBoundedAtomicPluginStorage() {
        MemoryPluginStorage storage = new MemoryPluginStorage();
        ThemePackageRepository repository = new ThemePackageRepository(storage);

        ThemePackageRepository.SaveResult saved = repository.save(theme("user.aurora"), false);
        Optional<ThemePackageData> loaded = repository.find("user.aurora");

        assertEquals(ThemePackageRepository.SaveOutcome.SAVED, saved.outcome());
        assertEquals(theme("user.aurora"), loaded.orElseThrow());
        assertEquals(1, storage.files.size());
        assertFalse(storage.lastWritePath.relativePath().contains(".."));
    }

    @Test
    void savingAnExistingThemeWithoutReplacementReportsConflictAndPreservesIt() {
        MemoryPluginStorage storage = new MemoryPluginStorage();
        ThemePackageRepository repository = new ThemePackageRepository(storage);
        repository.save(theme("user.aurora"), false);

        ThemePackageRepository.SaveResult result = repository.save(theme("user.aurora"), false);

        assertEquals(ThemePackageRepository.SaveOutcome.CONFLICT, result.outcome());
        assertEquals(theme("user.aurora"), repository.find("user.aurora").orElseThrow());
    }

    @Test
    void listsValidArchivesAndDeletesOnePackage() {
        MemoryPluginStorage storage = new MemoryPluginStorage();
        ThemePackageRepository repository = new ThemePackageRepository(storage);
        repository.save(theme("user.aurora"), false);
        repository.save(theme("user.dusk"), false);

        assertEquals(
            java.util.List.of("user.aurora", "user.dusk"),
            repository.list().stream().map(data -> data.metadata().id()).toList()
        );
        assertEquals(ThemePackageRepository.DeleteOutcome.DELETED, repository.delete("user.aurora").outcome());
        assertEquals(Optional.empty(), repository.find("user.aurora"));
    }

    private static ThemePackageData theme(final String id) {
        return new ThemePackageData(
            new ThemePackageMetadata(
                id, "Aurora", "", "Turboism", "", "1", null,
                ThemeBase.DARK, ThemeIcons.LIGHT, false
            ),
            Map.of(
                "accent", "#88C0D0", "background", "#2E3440", "surface", "#3B4252",
                "input.background", "#434C5E", "foreground", "#ECEFF4",
                "foreground.muted", "#D8DEE9", "selection.background", "#4C566A",
                "selection.foreground", "#ECEFF4", "border", "#4C566A",
                "viewport.background", "#242933"
            ),
            Map.of(), "", ""
        );
    }

    private static final class MemoryPluginStorage implements PluginStorage {
        private final Map<StoragePath, byte[]> files = new LinkedHashMap<>();
        private StoragePath lastWritePath;

        @Override
        public CompletionStage<StorageReadResult<String>> readUtf8(StoragePath path, int maxBytes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<StorageReadResult<byte[]>> readBytes(StoragePath path, int maxBytes) {
            byte[] value = files.get(path);
            if (value == null) {
                return CompletableFuture.completedFuture(new StorageReadResult<>(
                    Optional.empty(),
                    Optional.of(new StorageError(
                        dev.turboism.sdk.storage.StorageErrorCode.NOT_FOUND,
                        "not found",
                        path
                    )),
                    false
                ));
            }
            return CompletableFuture.completedFuture(new StorageReadResult<>(
                Optional.of(value), Optional.empty(), false
            ));
        }

        @Override
        public CompletionStage<StorageWriteResult> writeUtf8Atomic(StoragePath path, String content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<StorageWriteResult> writeBytesAtomic(StoragePath path, byte[] content) {
            lastWritePath = path;
            files.put(path, content.clone());
            return CompletableFuture.completedFuture(new StorageWriteResult(true, Optional.empty()));
        }

        @Override
        public CompletionStage<StorageListResult> list(StoragePath directory, int maxEntries) {
            return CompletableFuture.completedFuture(new StorageListResult(
                files.keySet().stream()
                    .filter(path -> path.relativePath().startsWith(directory.relativePath() + "/"))
                    .map(path -> new StorageEntry(path, dev.turboism.sdk.storage.StorageEntryType.FILE, files.get(path).length))
                    .toList(),
                Optional.empty(), false
            ));
        }

        @Override
        public CompletionStage<StorageMutationResult> copy(StoragePath source, StoragePath target, boolean replaceExisting) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<StorageMutationResult> moveAtomic(StoragePath source, StoragePath target, boolean replaceExisting) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<StorageMutationResult> delete(StoragePath path, boolean recursive) {
            boolean removed = files.remove(path) != null;
            return CompletableFuture.completedFuture(new StorageMutationResult(
                removed,
                removed ? Optional.empty() : Optional.of(new StorageError(
                    dev.turboism.sdk.storage.StorageErrorCode.NOT_FOUND,
                    "not found",
                    path
                ))
            ));
        }
    }
}
