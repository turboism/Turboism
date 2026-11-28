package dev.turboism.shell;

import dev.turboism.internal.TurboismEndpoints;
import dev.turboism.sdk.i18n.PluginLocalization;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The one-time product guide: a frameless card with three pages built on {@link FramelessCardWindow}.
 */
final class GuideWindow {

    static final int CARD_WIDTH = FramelessCardWindow.CARD_WIDTH;
    static final int CARD_HEIGHT = FramelessCardWindow.CARD_HEIGHT;

    static final String DOCUMENTATION_URL = TurboismEndpoints.DOCS_ORIGIN;
    static final String ISSUES_URL = TurboismEndpoints.GITHUB_ISSUES_URL;

    /** Static page model: localized text keys plus fixed first-party links, never feed data. */
    private static final List<Page> PAGES = List.of(
            new Page(
                    "guide.page.about.title",
                    "guide.page.about.intro",
                    List.of(
                            "guide.page.about.capabilities",
                            "guide.page.about.dependencies",
                            "guide.page.about.update"),
                    List.of(
                            new Link("guide.link.website", CoreWindows.ABOUT_HOMEPAGE),
                            new Link("guide.link.download", CoreWindows.UPDATE_DOWNLOAD_PAGE),
                            new Link("guide.link.docs", DOCUMENTATION_URL))),
            new Page(
                    "guide.page.plugins.title",
                    "guide.page.plugins.intro",
                    List.of("guide.page.plugins.install", "guide.page.plugins.manage", "guide.page.plugins.market"),
                    List.of(new Link("guide.link.market", CoreWindows.PLUGIN_MARKET_PAGE))),
            new Page(
                    "guide.page.support.title",
                    "guide.page.support.intro",
                    List.of("guide.page.support.logs", "guide.page.support.issues", "guide.page.support.security"),
                    List.of(
                            new Link("guide.link.issues", ISSUES_URL),
                            new Link("guide.link.docs", DOCUMENTATION_URL))));

    private final FramelessCardWindow window;

    GuideWindow(final PluginLocalization i18n, final Runnable onDismissed) {
        Objects.requireNonNull(i18n, "i18n");
        Objects.requireNonNull(onDismissed, "onDismissed");
        this.window = new FramelessCardWindow(
                i18n,
                i18n.text("guide.window.title"),
                i18n.text("guide.window.close"),
                linkOpener -> buildPages(i18n, linkOpener),
                onDismissed);
    }

    /** Opens the card on its first page and reports nothing until the user dismisses it. */
    void show() {
        window.show();
    }

    /** Hides the card as a user dismissal, which is the only path that records the guide as seen. */
    void close() {
        window.close();
    }

    /** Releases the native window during shell teardown without recording a dismissal. */
    void dispose() {
        window.dispose();
    }

    void next() {
        window.next();
    }

    void previous() {
        window.previous();
    }

    void showPage(final int page) {
        window.showPage(page);
    }

    /** Test seam: the page currently shown, counted from zero. */
    int pageIndex() {
        return window.pageIndex();
    }

    /** Test seam: whether the native card is on screen. */
    boolean isShowing() {
        return window.isShowing();
    }

    /** Test seam: the immutable page model, so key wiring is verified without a display. */
    static List<Page> pages() {
        return PAGES;
    }

    static List<CardPageView> buildPages(final PluginLocalization i18n, final Consumer<String> linkOpener) {
        final List<CardPageView> views = new ArrayList<>(PAGES.size());
        final int lastPage = PAGES.size() - 1;
        final CardPageView.ClosingSection authorSupport = FramelessCardWindow.authorSupport(i18n);
        for (int index = 0; index < PAGES.size(); index++) {
            views.add(createPage(i18n, PAGES.get(index), linkOpener, index == lastPage ? authorSupport : null));
        }
        return List.copyOf(views);
    }

    private static CardPageView createPage(
            final PluginLocalization i18n,
            final Page page,
            final Consumer<String> linkOpener,
            final CardPageView.ClosingSection closingSection) {
        final List<String> bullets = page.bulletKeys().stream().map(i18n::text).toList();
        final List<CardPageView.Link> links = page.links().stream()
                .map(link -> new CardPageView.Link(i18n.text(link.labelKey()), link.url()))
                .toList();
        return new CardPageView(
                i18n.text(page.titleKey()), i18n.text(page.introKey()), bullets, links, linkOpener, closingSection);
    }

    /** One guide page: localized title and body keys, three bullets, and fixed footer links. */
    record Page(String titleKey, String introKey, List<String> bulletKeys, List<Link> links) {
        Page {
            Objects.requireNonNull(titleKey, "titleKey");
            Objects.requireNonNull(introKey, "introKey");
            bulletKeys = List.copyOf(bulletKeys);
            links = List.copyOf(links);
        }
    }

    /** A footer link: a localized label plus the URL it opens. */
    record Link(String labelKey, String url) {
        Link {
            Objects.requireNonNull(labelKey, "labelKey");
            Objects.requireNonNull(url, "url");
        }
    }
}
