package dev.turboism.sdk.cubism.recentpreview;

import dev.turboism.sdk.cubism.recentfile.RecentFileSummary;

import java.util.Optional;

/**
 * Renders popup content for one hovered recent file. Renderers are consulted in
 * contribution order; the first non-empty result wins. Returning {@link Optional#empty()}
 * means "no content for this file" and hides the popup.
 */
@FunctionalInterface
public interface RecentPreviewRenderer {

    /**
     * Produces popup content for {@code summary}, or {@link Optional#empty()} when this
     * renderer has nothing to show for that file.
     */
    Optional<RecentPreviewContent> render(RecentFileSummary summary);
}
