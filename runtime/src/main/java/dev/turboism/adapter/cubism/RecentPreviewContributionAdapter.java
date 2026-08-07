package dev.turboism.adapter.cubism;

import dev.turboism.sdk.cubism.recentpreview.RecentPreviewRenderer;
import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;

/** Adapter seam for the host-owned Recent Files hover popup bridge. */
public interface RecentPreviewContributionAdapter {

    Registration contribute(RecentPreviewRenderer renderer);

    void refresh();

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

    interface HostOperations {
        Registration contribute(RecentPreviewRenderer renderer);

        void refresh();
    }
}
