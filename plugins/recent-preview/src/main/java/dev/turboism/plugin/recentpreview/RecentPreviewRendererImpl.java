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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Popup renderer for cached thumbnails and the first-capture loading state. */
public final class RecentPreviewRendererImpl implements RecentPreviewRenderer {

    static final DateTimeFormatter LAST_MODIFIED_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final RecentPreviewController controller;
    private final Consumer<RecentFileId> captureRequester;
    private final PluginLogger logger;
    private final String loadingText;
    private final Set<RecentFileId> loading = ConcurrentHashMap.newKeySet();
    private final Set<RecentFileId> hideOnce = ConcurrentHashMap.newKeySet();

    public RecentPreviewRendererImpl(
        final RecentPreviewController controller,
        final Consumer<RecentFileId> captureRequester,
        final PluginLogger logger
    ) {
        this(controller, captureRequester, logger, "Loading preview…");
    }

    public RecentPreviewRendererImpl(
        final RecentPreviewController controller,
        final Consumer<RecentFileId> captureRequester,
        final PluginLogger logger,
        final String loadingText
    ) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.captureRequester = Objects.requireNonNull(captureRequester, "captureRequester");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.loadingText = requireText(loadingText, "loadingText");
    }

    @Override
    public Optional<RecentPreviewContent> render(final RecentFileSummary summary) {
        Objects.requireNonNull(summary, "summary");
        final RecentFileId id = summary.id();
        final Optional<byte[]> cached = controller.image(id);
        if (cached.isPresent()) {
            loading.remove(id);
            hideOnce.remove(id);
            return Optional.of(new RecentPreviewContent(id, contentFor(summary, cached.orElseThrow())));
        }
        if (hideOnce.remove(id)) {
            loading.remove(id);
            return Optional.empty();
        }
        if (loading.add(id)) {
            try {
                captureRequester.accept(id);
            } catch (RuntimeException failure) {
                loading.remove(id);
                logger.warn("Recent preview capture request failed: " + failure.getClass().getSimpleName());
                return Optional.empty();
            }
        }
        return Optional.of(new RecentPreviewContent(id, loadingContentFor(summary, loadingText)));
    }

    /** Capture succeeded; a refresh will resolve the image from the controller cache. */
    void captureStored(final RecentFileId id) {
        loading.remove(Objects.requireNonNull(id, "id"));
        hideOnce.remove(id);
    }

    /** Capture failed; the completion refresh hides loading once, then a later hover may retry. */
    void captureFailed(final RecentFileId id) {
        final RecentFileId target = Objects.requireNonNull(id, "id");
        loading.remove(target);
        hideOnce.add(target);
    }

    void clearTransientState() {
        loading.clear();
        hideOnce.clear();
    }

    /** Builds the thumbnail plus the two legacy information rows. */
    static PanelView contentFor(final RecentFileSummary summary, final byte[] png) {
        return PanelView.column(
            PanelView.image(png, summary.displayName()),
            PanelView.text(summary.displayName()),
            PanelView.text(formatLastModified(summary.lastModified()))
        );
    }

    static PanelView loadingContentFor(final RecentFileSummary summary, final String loadingText) {
        return PanelView.column(
            PanelView.text(summary.displayName()),
            PanelView.text(formatLastModified(summary.lastModified())),
            PanelView.text(requireText(loadingText, "loadingText"))
        );
    }

    /** Formats the last edit time as local {@code yyyy-MM-dd HH:mm:ss}; empty when unknown. */
    static String formatLastModified(final Optional<Instant> lastModified) {
        return lastModified.map(LAST_MODIFIED_FORMAT::format).orElse("");
    }

    private static String requireText(final String value, final String name) {
        final String text = Objects.requireNonNull(value, name).trim();
        if (text.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return text;
    }
}
