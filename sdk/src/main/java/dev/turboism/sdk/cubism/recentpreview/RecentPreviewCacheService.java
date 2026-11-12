package dev.turboism.sdk.cubism.recentpreview;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.plugin.Registration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Runtime-mediated deletion surface for cached recent-project previews.
 *
 * <p>The cache belongs to the official {@code recent-preview} plugin, so no dependent can
 * remove an entry itself. The runtime owns this broker instead: a dependent asks for a
 * deletion by the opaque recent-file id it already holds, and the provider that registered
 * the cache handler performs it. The provider stays the only writer of its own storage.</p>
 *
 * <p>A deletion removes the cached image and its index entry, and the provider publishes
 * {@link RecentPreviewDeletedEvent} so every dependent surface drops the stale thumbnail. A
 * later capture for the same file stores a fresh entry, so deletion is not permanent.</p>
 */
@CubismEditor
public interface RecentPreviewCacheService {

    /**
     * Deletes the cached preview of one recent file.
     *
     * @param id opaque recent-file identity
     * @return a stage completing with {@code true} when an entry existed and was removed,
     *     {@code false} when nothing was cached or no provider is registered; the stage may
     *     complete exceptionally when the underlying storage fails
     * @throws NullPointerException when {@code id} is {@code null}
     */
    CompletionStage<Boolean> delete(RecentFileId id);

    /**
     * Registers the handler that performs deletions. Only one provider may register; the
     * registration ends with the provider's plugin generation.
     *
     * @param handler performs one deletion and reports whether an entry was removed
     * @return registration whose close removes the handler
     * @throws IllegalStateException when another plugin already registered a handler
     */
    Registration provide(DeletionHandler handler);

    /** Performs one deletion for the registered provider. */
    @FunctionalInterface
    interface DeletionHandler {
        /**
         * @param id opaque recent-file identity
         * @return the deletion outcome
         */
        CompletionStage<Boolean> delete(RecentFileId id);
    }

    /**
     * Reports whether a live runtime surface backs this instance.
     *
     * @return {@code false} for the {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    /** Safe-mode instance: every deletion and registration is refused. */
    static RecentPreviewCacheService unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Fail-closed implementation returned by {@link #unavailable()}. */
    enum Unavailable implements RecentPreviewCacheService {
        INSTANCE;

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public CompletionStage<Boolean> delete(final RecentFileId id) {
            Objects.requireNonNull(id, "id");
            throw new UnsupportedOperationException("recent preview cache service is not available");
        }

        @Override
        public Registration provide(final DeletionHandler handler) {
            throw new UnsupportedOperationException("recent preview cache service is not available");
        }
    }
}
