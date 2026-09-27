package dev.turboism.adapter.cubism;

import dev.turboism.sdk.cubism.recentpreview.RecentPreviewRenderer;
import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;

/** Adapter seam for the host-owned Recent Files hover popup bridge. */
public interface RecentPreviewContributionAdapter {

    /**
     * Contributes a renderer to the host-owned Recent Files hover popup.
     *
     * @param renderer the preview renderer to install, non-null
     * @return the registration owning the contribution; closing it removes the renderer
     */
    Registration contribute(RecentPreviewRenderer renderer);

    /**
     * Asks the host popup to re-read the current previews; a hint, not a guarantee that
     * any particular entry repaints immediately.
     */
    void refresh();

    /**
     * An adapter for when no host popup bridge is attached.
     *
     * @return a host-free adapter whose {@link #contribute} throws
     *         {@link UnsupportedOperationException} and whose {@link #refresh} is a no-op
     */
    static RecentPreviewContributionAdapter safeMode() {
        return connected(new HostOperations() {
            @Override
            public Registration contribute(final RecentPreviewRenderer renderer) {
                throw new UnsupportedOperationException("recent preview contribution is not available");
            }

            @Override
            public void refresh() {
            }
        });
    }

    /**
     * An adapter that contributes through the given host operations.
     *
     * @param host the live host operations, non-null
     * @return an adapter bound to that host
     * @throws NullPointerException if {@code host} is null
     */
    static RecentPreviewContributionAdapter connected(final HostOperations host) {
        Objects.requireNonNull(host, "host");
        return new RecentPreviewContributionAdapter() {
            @Override
            public Registration contribute(final RecentPreviewRenderer renderer) {
                return host.contribute(Objects.requireNonNull(renderer, "renderer"));
            }

            @Override
            public void refresh() {
                host.refresh();
            }
        };
    }

    /** The raw host call surface the connected adapter delegates to. */
    interface HostOperations {
        /**
         * @param renderer the renderer to install into the host popup
         * @return the registration owning the contribution
         */
        Registration contribute(RecentPreviewRenderer renderer);

        /** Asks the host popup to refresh its preview content. */
        void refresh();
    }
}
