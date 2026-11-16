package dev.turboism.sdk.cubism.recentpreview;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.plugin.Registration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
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

    /**
     * Registers deletion and cache-replay handlers for the active provider generation.
     *
     * <p>The default implementation keeps a provider that only implements
     * {@link #provide(DeletionHandler)} source compatible: replay requests then complete without
     * publishing anything, so a dependent must treat a missing replay as "no cached previews".</p>
     *
     * @param deletionHandler performs one deletion and reports whether an entry was removed
     * @param replayHandler replays cached preview events to dependent plugins
     * @return registration whose close removes both handlers
     * @throws IllegalStateException when another plugin already registered handlers
     */
    default Registration provide(final DeletionHandler deletionHandler, final ReplayHandler replayHandler) {
        Objects.requireNonNull(replayHandler, "replayHandler");
        return provide(deletionHandler);
    }

    /**
     * Requests the active provider to replay its cached previews to subscribers.
     *
     * @return a stage completing once the replay finishes, or immediately when no provider
     *     is registered
     */
    default CompletionStage<Void> requestReplay() {
        return CompletableFuture.completedStage(null);
    }

    /** Performs one deletion for the registered provider. */
    @FunctionalInterface
    interface DeletionHandler {
        /**
         * @param id opaque recent-file identity
         * @return the deletion outcome
         */
        CompletionStage<Boolean> delete(RecentFileId id);
    }

    /** Replays cached previews for newly enabled or hot-loaded dependent plugins. */
    @FunctionalInterface
    interface ReplayHandler {
        /**
         * Publishes cached preview events for live recent files.
         *
         * @return a stage completing once replay publication finishes
         */
        CompletionStage<Void> replay();
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

        @Override
        public Registration provide(final DeletionHandler deletionHandler, final ReplayHandler replayHandler) {
            throw new UnsupportedOperationException("recent preview cache service is not available");
        }

        @Override
        public CompletionStage<Void> requestReplay() {
            throw new UnsupportedOperationException("recent preview cache service is not available");
        }
    }
}
