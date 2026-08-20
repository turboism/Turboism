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

    /**
     * Writes the package as a single bounded archive under the plugin's data storage, atomically.
     *
     * <p>Blocks on the storage write. Storage failures come back as {@code FAILED} with the storage
     * error code as the diagnostic id; they are not thrown.
     *
     * @param theme the package to store; its metadata id becomes the archive name
     * @param replaceExisting when false, an already-stored id returns {@code CONFLICT} and nothing is
     *     written
     * @return the outcome and, on failure, the underlying storage error code
     * @throws NullPointerException if {@code theme} is {@code null}
     * @throws IllegalArgumentException if the package's id is not a valid theme id
     */
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

    /**
     * @param themeId the stored package id
     * @return the decoded package, or empty when it does not exist, could not be read, exceeded the
     *     archive size limit, or failed to decode — an unreadable package is indistinguishable from an
     *     absent one by design
     * @throws IllegalArgumentException if {@code themeId} is not a valid theme id
     */
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

    /**
     * @return every readable user package, sorted by id; empty when storage cannot be listed. Bounded
     *     at 128 storage entries, and a truncated listing yields an empty list rather than a partial
     *     one so callers never act on a half-view. Entries that fail to decode are silently omitted.
     */
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

    /**
     * Deletes the stored archive for one package.
     *
     * <p>Non-recursive and blocking. Deleting something that is not there is reported as
     * {@code NOT_FOUND}, which callers may treat as success.
     *
     * @param themeId the stored package id
     * @return the outcome and, on failure, the underlying storage error code
     * @throws IllegalArgumentException if {@code themeId} is not a valid theme id
     */
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

    /**
     * Outcome of a delete attempt.
     *
     * @param outcome whether the archive was removed, was already absent, or could not be removed
     * @param diagnosticId the underlying storage error code on {@code FAILED}, otherwise empty
     */
    public record DeleteResult(DeleteOutcome outcome, Optional<String> diagnosticId) {
        public DeleteResult {
            outcome = Objects.requireNonNull(outcome, "outcome");
            diagnosticId = Objects.requireNonNull(diagnosticId, "diagnosticId");
        }
    }

    /**
     * Outcome of a save attempt.
     *
     * @param outcome whether the archive was written, refused because the id exists, or failed
     * @param diagnosticId the underlying storage error code on {@code FAILED}, otherwise empty
     */
    public record SaveResult(SaveOutcome outcome, Optional<String> diagnosticId) {
        public SaveResult {
            outcome = Objects.requireNonNull(outcome, "outcome");
            diagnosticId = Objects.requireNonNull(diagnosticId, "diagnosticId");
        }
    }
}
