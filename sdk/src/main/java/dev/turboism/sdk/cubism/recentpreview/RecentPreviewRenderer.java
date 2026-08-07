package dev.turboism.sdk.cubism.recentpreview;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.recentfile.RecentFileSummary;

import java.util.Optional;

/**
 * Renders popup content for one hovered recent file. Renderers are consulted in
 * contribution order; the first non-empty result wins. Returning {@link Optional#empty()}
 * means "no content for this file" and hides the popup.
 */
@PreviewApi
@FunctionalInterface
public interface RecentPreviewRenderer {

    Optional<RecentPreviewContent> render(RecentFileSummary summary);
}
