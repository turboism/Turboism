package dev.turboism.plugin.uitheme.service;

import dev.turboism.plugin.uitheme.b1.domain.ThemePackageArchive;
import dev.turboism.plugin.uitheme.b1.domain.ThemePackageCatalog;
import dev.turboism.plugin.uitheme.b1.domain.ThemePackageData;
import dev.turboism.sdk.storage.PluginStorage;
import dev.turboism.sdk.storage.StorageErrorCode;
import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.storage.StorageReadResult;
import dev.turboism.sdk.storage.StorageRoot;
import dev.turboism.sdk.storage.StorageWriteResult;
import dev.turboism.sdk.storage.StorageEntryType;
import dev.turboism.sdk.storage.StorageListResult;
import dev.turboism.sdk.storage.StorageMutationResult;

import java.util.Objects;
import java.util.Optional;

/** Stores one bounded atomic archive for every user theme package. */
public final class ThemePackageRepository {

    private static final String ROOT = "themes";

    private final PluginStorage storage;

    public ThemePackageRepository(final PluginStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public SaveResult save(final ThemePackageData theme, final boolean replaceExisting) {
        Objects.requireNonNull(theme, "theme");
        final String id = validatedId(theme.metadata().id());
        if (!replaceExisting && find(id).isPresent()) {
            return new SaveResult(SaveOutcome.CONFLICT, Optional.empty());
        }
        final StorageWriteResult written = storage.writeBytesAtomic(
            path(id), ThemePackageArchive.encode(theme)
        ).toCompletableFuture().join();
        if (!written.written()) {
            return new SaveResult(
                SaveOutcome.FAILED,
                written.error().map(error -> error.code().name())
            );
        }
        return new SaveResult(SaveOutcome.SAVED, Optional.empty());
    }

    public Optional<ThemePackageData> find(final String themeId) {
        final StorageReadResult<byte[]> read = storage.readBytes(
            path(validatedId(themeId)), ThemePackageArchive.MAX_ARCHIVE_BYTES
        ).toCompletableFuture().join();
        if (read.error().map(error -> error.code() == StorageErrorCode.NOT_FOUND).orElse(false)) {
            return Optional.empty();
        }
        if (read.error().isPresent() || read.truncated()) {
            return Optional.empty();
        }
        return read.value().flatMap(bytes -> ThemePackageArchive.decode(bytes).theme());
    }

    public java.util.List<ThemePackageData> list() {
        final StoragePath root = new StoragePath(StorageRoot.DATA, ROOT);
        final StorageListResult listed = storage.list(root, 128).toCompletableFuture().join();
        if (listed.error().isPresent() || listed.truncated()) {
            return java.util.List.of();
        }
        return listed.entries().stream()
            .filter(entry -> entry.type() == StorageEntryType.FILE)
            .map(entry -> entry.path().relativePath())
            .filter(relative -> relative.startsWith(ROOT + "/") && relative.endsWith(".zip"))
            .map(relative -> relative.substring((ROOT + "/").length(), relative.length() - 4))
            .filter(ThemePackageCatalog::isValidId)
            .sorted()
            .map(this::find)
            .flatMap(Optional::stream)
            .toList();
    }

    public DeleteResult delete(final String themeId) {
        final StorageMutationResult deleted = storage.delete(path(validatedId(themeId)), false)
            .toCompletableFuture().join();
        if (deleted.changed()) {
            return new DeleteResult(DeleteOutcome.DELETED, Optional.empty());
        }
        if (deleted.error().map(error -> error.code() == StorageErrorCode.NOT_FOUND).orElse(false)) {
            return new DeleteResult(DeleteOutcome.NOT_FOUND, Optional.empty());
        }
        return new DeleteResult(
            DeleteOutcome.FAILED,
            deleted.error().map(error -> error.code().name())
        );
    }

    private static StoragePath path(final String themeId) {
        return new StoragePath(StorageRoot.DATA, ROOT + "/" + themeId + ".zip");
    }

    private static String validatedId(final String themeId) {
        if (!ThemePackageCatalog.isValidId(themeId)) {
            throw new IllegalArgumentException("invalid theme id: " + themeId);
        }
        return themeId;
    }

    public enum SaveOutcome {
        SAVED,
        CONFLICT,
        FAILED
    }

    public enum DeleteOutcome {
        DELETED,
        NOT_FOUND,
        FAILED
    }

    public record DeleteResult(DeleteOutcome outcome, Optional<String> diagnosticId) {
        public DeleteResult {
            outcome = Objects.requireNonNull(outcome, "outcome");
            diagnosticId = Objects.requireNonNull(diagnosticId, "diagnosticId");
        }
    }

    public record SaveResult(SaveOutcome outcome, Optional<String> diagnosticId) {
        public SaveResult {
            outcome = Objects.requireNonNull(outcome, "outcome");
            diagnosticId = Objects.requireNonNull(diagnosticId, "diagnosticId");
        }
    }
}
