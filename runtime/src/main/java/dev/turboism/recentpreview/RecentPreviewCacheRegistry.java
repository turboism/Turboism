package dev.turboism.recentpreview;

import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.cubism.recentpreview.RecentPreviewCacheService;
import dev.turboism.sdk.plugin.Registration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Runtime-owned broker between a dependent that deletes a preview and the provider that
 * owns the cache.
 *
 * <p>One provider registers a {@link RecentPreviewCacheService.DeletionHandler}; every
 * dependent reaches it through this broker. A deletion request without a registered
 * provider reports {@code false} instead of failing, because a dependent cannot distinguish
 * "no provider" from "nothing cached" and both leave the thumbnail absent.</p>
 */
public final class RecentPreviewCacheRegistry {

    private final Object monitor = new Object();
    private Provider provider;
    private long generation;
    private boolean closed;

    /**
     * Registers the deletion handler for one plugin generation.
     *
     * @param ownerPluginId the registering plugin
     * @param handler performs one deletion
     * @return a registration that removes the handler when closed
     * @throws IllegalStateException when another plugin already registered a handler
     */
    public Registration provide(final String ownerPluginId, final RecentPreviewCacheService.DeletionHandler handler) {
        return provide(ownerPluginId, handler, null);
    }

    /**
     * Registers deletion and cache-replay handlers for one plugin generation.
     *
     * @param ownerPluginId the registering plugin
     * @param deletionHandler performs one deletion
     * @param replayHandler replays cached preview events on demand, or {@code null} when unused
     * @return a registration that removes the handlers when closed
     * @throws IllegalStateException when another plugin already registered handlers
     */
    public Registration provide(
            final String ownerPluginId,
            final RecentPreviewCacheService.DeletionHandler deletionHandler,
            final RecentPreviewCacheService.ReplayHandler replayHandler) {
        Objects.requireNonNull(ownerPluginId, "ownerPluginId");
        Objects.requireNonNull(deletionHandler, "deletionHandler");
        final long registrationGeneration;
        synchronized (monitor) {
            if (closed) {
                throw new IllegalStateException("recent preview cache registry is closed");
            }
            if (provider != null && !provider.ownerPluginId().equals(ownerPluginId)) {
                throw new IllegalStateException("A recent preview cache provider is already registered");
            }
            registrationGeneration = ++generation;
            provider = new Provider(ownerPluginId, deletionHandler, replayHandler, registrationGeneration);
        }
        return () -> {
            synchronized (monitor) {
                if (provider != null && provider.generation() == registrationGeneration) {
                    provider = null;
                }
            }
        };
    }

    /**
     * Runs one deletion through the registered provider.
     *
     * @param id opaque recent-file identity
     * @return a stage completing with the provider's outcome, or {@code false} when no
     *     provider is registered
     */
    public CompletionStage<Boolean> delete(final RecentFileId id) {
        Objects.requireNonNull(id, "id");
        final Provider current;
        synchronized (monitor) {
            current = provider;
        }
        if (current == null) {
            return CompletableFuture.completedStage(false);
        }
        try {
            return current.deletionHandler().delete(id);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedStage(failure);
        }
    }

    /**
     * Requests the registered provider to replay its cached previews.
     *
     * @return a stage completing when replay finishes, or immediately when no replay handler is
     *     registered
     */
    public CompletionStage<Void> requestReplay() {
        final Provider current;
        synchronized (monitor) {
            current = provider;
        }
        if (current == null || current.replayHandler() == null) {
            return CompletableFuture.completedStage(null);
        }
        try {
            final CompletionStage<Void> stage = current.replayHandler().replay();
            return stage != null ? stage : CompletableFuture.completedStage(null);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedStage(failure);
        }
    }

    /** Drops the registered provider; later deletions report {@code false}. */
    public void close() {
        synchronized (monitor) {
            closed = true;
            provider = null;
        }
    }

    private record Provider(
            String ownerPluginId,
            RecentPreviewCacheService.DeletionHandler deletionHandler,
            RecentPreviewCacheService.ReplayHandler replayHandler,
            long generation) {}
}
