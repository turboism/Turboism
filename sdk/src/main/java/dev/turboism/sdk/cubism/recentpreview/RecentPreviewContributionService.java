package dev.turboism.sdk.cubism.recentpreview;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.plugin.Registration;

/**
 * Host-owned hover popup bridge for the Recent Files menu. The runtime installs the
 * menu listeners, tracks the hovered item, and renders contributed content next to it;
 * plugins only provide {@link RecentPreviewRenderer}s.
 */
@PreviewApi
public interface RecentPreviewContributionService {

    /**
     * Registers a renderer for the recent-file hover popup. Renderers are consulted in
     * contribution order; the first non-empty result wins. Closing the returned
     * {@link Registration} removes the renderer.
     */
    Registration contribute(RecentPreviewRenderer renderer);

    /**
     * Requests the runtime to re-render the currently active popup (used after an
     * asynchronous capture completes so the new image appears without re-hovering).
     * No-op when no popup is active.
     */
    default void refresh() {
    }

    /** Safe-mode instance: contribution is refused and refresh is a no-op. */
    static RecentPreviewContributionService unavailable() {
        return Unavailable.INSTANCE;
    }

        @PreviewApi
    enum Unavailable implements RecentPreviewContributionService {
        INSTANCE;

        @Override
        public Registration contribute(final RecentPreviewRenderer renderer) {
            throw new UnsupportedOperationException("recent preview contribution service is not available");
        }

        @Override
        public void refresh() {
        }
    }
}
