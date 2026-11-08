package dev.turboism.plugin.uitheme.service;

import dev.turboism.plugin.uitheme.domain.ThemePackageArchive;
import dev.turboism.plugin.uitheme.domain.ThemePackageCatalog;
import dev.turboism.plugin.uitheme.domain.ThemePackageData;
import dev.turboism.sdk.storage.PluginStorage;
import dev.turboism.sdk.storage.StorageEntryType;
import dev.turboism.sdk.storage.StorageErrorCode;
import dev.turboism.sdk.storage.StorageListResult;
import dev.turboism.sdk.storage.StorageMutationResult;
import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.storage.StorageReadResult;
import dev.turboism.sdk.storage.StorageRoot;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Stores one bounded atomic archive for every user theme package.
 *
 * <p>Every operation returns a stage and never blocks the caller. The runtime settles plugin storage
 * work on the owning plugin's worker, so waiting for a storage result from that worker can never be
 * released; callers must compose instead of waiting.</p>
 */
public final class ThemePackageRepository {

    private static final String ROOT = "themes";
    private static final String ARCHIVE_SUFFIX = ".zip";
    private static final String ROOT_PREFIX = ROOT + "/";
    private static final int MAX_LISTED_ENTRIES = 128;

    private final PluginStorage storage;

    public ThemePackageRepository(final PluginStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    /**
     * Writes the package as a single bounded archive under the plugin's data storage, atomically.
     *
     * <p>Storage failures come back as {@code FAILED} with the storage error code as the diagnostic
     * id; they are not thrown.
     *
     * @param theme the package to store; its metadata id becomes the archive name
     * @param replaceExisting when false, an already-stored id returns {@code CONFLICT} and nothing is
     *     written
     * @return a stage of the outcome and, on failure, the underlying storage error code
     * @throws NullPointerException if {@code theme} is {@code null}
     * @throws IllegalArgumentException if the package's id is not a valid theme id
     */
    public CompletionStage<SaveResult> save(final ThemePackageData theme, final boolean replaceExisting) {
        Objects.requireNonNull(theme, "theme");
        final String id = validatedId(theme.metadata().id());
        if (replaceExisting) {
            return write(theme, id);
        }
        return find(id).thenCompose(existing -> existing.isPresent()
                ? CompletableFuture.completedFuture(new SaveResult(SaveOutcome.CONFLICT, Optional.empty()))
                : write(theme, id));
    }

    /**
     * @param themeId the stored package id
     * @return a stage of the decoded package, or empty when it does not exist, could not be read,
     *     exceeded the archive size limit, or failed to decode — an unreadable package is
     *     indistinguishable from an absent one by design
     * @throws IllegalArgumentException if {@code themeId} is not a valid theme id
     */
    public CompletionStage<Optional<ThemePackageData>> find(final String themeId) {
        return storage.readBytes(path(validatedId(themeId)), ThemePackageArchive.MAX_ARCHIVE_BYTES)
                .thenApply(ThemePackageRepository::decode);
    }

    /**
     * @return a stage of every readable user package, sorted by id; empty when storage cannot be
     *     listed. Bounded at the runtime's list ceiling, and a truncated listing yields an empty list
     *     rather than a partial one so callers never act on a half-view. Entries that fail to decode
     *     are silently omitted.
     */
    public CompletionStage<List<ThemePackageData>> list() {
        final StoragePath root = new StoragePath(StorageRoot.DATA, ROOT);
        return storage.list(root, MAX_LISTED_ENTRIES).thenCompose(listed -> decodeListed(listed));
    }

    /**
     * Deletes the stored archive for one package.
     *
     * <p>Non-recursive. Deleting something that is not there is reported as {@code NOT_FOUND}, which
     * callers may treat as success.
     *
     * @param themeId the stored package id
     * @return a stage of the outcome and, on failure, the underlying storage error code
     * @throws IllegalArgumentException if {@code themeId} is not a valid theme id
     */
    public CompletionStage<DeleteResult> delete(final String themeId) {
        return storage.delete(path(validatedId(themeId)), false).thenApply(ThemePackageRepository::deleted);
    }

    private CompletionStage<SaveResult> write(final ThemePackageData theme, final String id) {
        return storage.writeBytesAtomic(path(id), ThemePackageArchive.encode(theme))
                .thenApply(written -> written.written()
                        ? new SaveResult(SaveOutcome.SAVED, Optional.empty())
                        : new SaveResult(
                                SaveOutcome.FAILED,
                                written.error().map(error -> error.code().name())));
    }

    private CompletionStage<List<ThemePackageData>> decodeListed(final StorageListResult listed) {
        if (listed.error().isPresent() || listed.truncated()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return decodeAll(candidateIds(listed));
    }

    /** Reads one archive at a time so a listing never holds more than one decoded package at once. */
    private CompletionStage<List<ThemePackageData>> decodeAll(final List<String> themeIds) {
        CompletionStage<List<ThemePackageData>> decoded =
                CompletableFuture.completedFuture(new ArrayList<ThemePackageData>());
        for (String themeId : themeIds) {
            decoded = decoded.thenCompose(collected -> find(themeId).thenApply(found -> {
                found.ifPresent(collected::add);
                return collected;
            }));
        }
        return decoded.thenApply(List::copyOf);
    }

    private static List<String> candidateIds(final StorageListResult listed) {
        return listed.entries().stream()
                .filter(entry -> entry.type() == StorageEntryType.FILE)
                .map(entry -> entry.path().relativePath())
                .filter(relative -> relative.startsWith(ROOT_PREFIX) && relative.endsWith(ARCHIVE_SUFFIX))
                .map(relative -> relative.substring(ROOT_PREFIX.length(), relative.length() - ARCHIVE_SUFFIX.length()))
                .filter(ThemePackageCatalog::isValidId)
                .sorted()
                .toList();
    }

    private static Optional<ThemePackageData> decode(final StorageReadResult<byte[]> read) {
        if (read.error()
                .map(error -> error.code() == StorageErrorCode.NOT_FOUND)
                .orElse(false)) {
            return Optional.empty();
        }
        if (read.error().isPresent() || read.truncated()) {
            return Optional.empty();
        }
        return read.value().flatMap(bytes -> ThemePackageArchive.decode(bytes).theme());
    }

    private static DeleteResult deleted(final StorageMutationResult deleted) {
        if (deleted.changed()) {
            return new DeleteResult(DeleteOutcome.DELETED, Optional.empty());
        }
        if (deleted.error()
                .map(error -> error.code() == StorageErrorCode.NOT_FOUND)
                .orElse(false)) {
            return new DeleteResult(DeleteOutcome.NOT_FOUND, Optional.empty());
        }
        return new DeleteResult(
                DeleteOutcome.FAILED, deleted.error().map(error -> error.code().name()));
    }

    private static StoragePath path(final String themeId) {
        return new StoragePath(StorageRoot.DATA, ROOT_PREFIX + themeId + ARCHIVE_SUFFIX);
    }

    private static String validatedId(final String themeId) {
        if (!ThemePackageCatalog.isValidId(themeId)) {
            throw new IllegalArgumentException("invalid theme id: " + themeId);
        }
        return themeId;
    }

    /** Outcome of a {@link ThemePackageRepository#save} attempt: written, refused because the id exists, or failed. */
    public enum SaveOutcome {
        SAVED,
        CONFLICT,
        FAILED
    }

    /** Outcome of a {@link ThemePackageRepository#delete} attempt: removed, already absent, or failed. */
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
