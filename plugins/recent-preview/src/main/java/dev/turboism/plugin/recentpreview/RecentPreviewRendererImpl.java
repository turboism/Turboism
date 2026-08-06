package dev.turboism.plugin.recentpreview;

import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.cubism.recentfile.RecentFileSummary;
import dev.turboism.sdk.cubism.recentpreview.RecentPreviewContent;
import dev.turboism.sdk.cubism.recentpreview.RecentPreviewRenderer;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.ui.PanelView;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Popup content renderer for one recent file: cached thumbnail image plus the
 * legacy {@code buildRecentPreviewInfoHtml} equivalent rows — file name and
 * last edit time. The absolute path is intentionally not rendered (popup width).
 * When the thumbnail is not cached yet, an asynchronous capture is requested
 * and the popup refresh is left to the request completion (the runtime
 * re-renders the active popup via {@code refresh()}).
 */
public final class RecentPreviewRendererImpl implements RecentPreviewRenderer {

    static final DateTimeFormatter LAST_MODIFIED_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final RecentPreviewController controller;
    private final Consumer<RecentFileId> captureRequester;
    private final PluginLogger logger;

    public RecentPreviewRendererImpl(
        final RecentPreviewController controller,
        final Consumer<RecentFileId> captureRequester,
        final PluginLogger logger
    ) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.captureRequester = Objects.requireNonNull(captureRequester, "captureRequester");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public Optional<RecentPreviewContent> render(final RecentFileSummary summary) {
        final Optional<byte[]> cached = controller.image(summary.id());
        if (cached.isPresent()) {
            return Optional.of(new RecentPreviewContent(summary.id(), contentFor(summary, cached.get())));
        }
        try {
            captureRequester.accept(summary.id());
        } catch (RuntimeException failure) {
            logger.warn("Recent preview capture request failed: " + failure.getClass().getSimpleName());
        }
        return Optional.empty();
    }

    /** Builds the popup {@link PanelView}: thumbnail image plus the two info rows. */
    static PanelView contentFor(final RecentFileSummary summary, final byte[] png) {
        return PanelView.column(
            PanelView.image(png, summary.displayName()),
            PanelView.text(summary.displayName()),
            PanelView.text(formatLastModified(summary.lastModified()))
        );
    }

    /** Formats the last edit time as local {@code yyyy-MM-dd HH:mm:ss}; empty when unknown. */
    static String formatLastModified(final Optional<Instant> lastModified) {
        return lastModified
            .map(value -> LAST_MODIFIED_FORMAT.format(value))
            .orElse("");
    }
}
