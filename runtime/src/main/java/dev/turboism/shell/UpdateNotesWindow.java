package dev.turboism.shell;

import dev.turboism.core.net.HttpLinks;
import dev.turboism.internal.TurboismEndpoints;
import dev.turboism.internal.core.UpdateNotesService.ReleaseNotes;
import dev.turboism.sdk.i18n.PluginLocalization;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Post-update release-notes dialog built on {@link FramelessCardWindow} and {@link CardPageView}.
 *
 * <p>Splits localized Markdown release notes by {@code ###} section headings into pages so users
 * browse changes using the same controls and appearance as the startup product guide.</p>
 */
final class UpdateNotesWindow {

    private static final String SECTION_PREFIX = "### ";
    private static final String TOP_HEADING_PREFIX = "## ";
    private static final Pattern STANDALONE_LINK = Pattern.compile("^\\[([^\\]\\r\\n]+)]\\(([^)\\s]+)\\)$");

    private final ReleaseNotes releaseNotes;
    private final FramelessCardWindow window;

    UpdateNotesWindow(final PluginLocalization i18n, final ReleaseNotes releaseNotes, final Runnable onDismissed) {
        Objects.requireNonNull(i18n, "i18n");
        this.releaseNotes = Objects.requireNonNull(releaseNotes, "releaseNotes");
        Objects.requireNonNull(onDismissed, "onDismissed");
        final String title = i18n.format("update-notes.window.title", releaseNotes.version());
        final String closeTooltip = i18n.text("update-notes.window.close");
        this.window = new FramelessCardWindow(
                i18n, title, closeTooltip, linkOpener -> buildPages(i18n, releaseNotes, linkOpener), onDismissed);
    }

    /** Opens the release notes on page zero and waits for user dismissal before notifying. */
    void show() {
        window.show();
    }

    /** Hides the card as a user dismissal so the caller can mark this version seen. */
    void close() {
        window.close();
    }

    /** Releases the native window on shell teardown without marking the version seen. */
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

    /** Test seam: version displayed by this window. */
    String version() {
        return releaseNotes.version();
    }

    /** Test seam: active zero-based page index. */
    int pageIndex() {
        return window.pageIndex();
    }

    /** Test seam: rendered card views exposed for verification without requiring a live frame. */
    List<CardPageView> pages() {
        return window.pages();
    }

    /** Test seam: whether the native dialog is currently visible. */
    boolean isShowing() {
        return window.isShowing();
    }

    /** Builds page views from the localized release notes, falling back cleanly when notes are empty. */
    static List<CardPageView> buildPages(
            final PluginLocalization i18n, final ReleaseNotes releaseNotes, final Consumer<String> linkOpener) {
        final String markdown = releaseNotes.notesFor(i18n.locale());
        final String upgradeIntro = releaseNotes
                .previousVersion()
                .map(previous -> i18n.format("update-notes.summary.upgraded-from", previous, releaseNotes.version()))
                .orElse("");
        final String defaultHeading = i18n.format("update-notes.page.title", releaseNotes.version());
        final ParsedNotes parsed = parseMarkdown(markdown, defaultHeading);
        final List<CardPageView.Link> links = mergeLinks(i18n, parsed.links());
        final CardPageView.ClosingSection authorSupport = FramelessCardWindow.authorSupport(i18n);
        if (parsed.sections().isEmpty()) {
            final String fallbackIntro =
                    upgradeIntro.isBlank() ? i18n.text("update-notes.fallback.intro") : upgradeIntro;
            return List.of(CardPageView.markdown(
                    defaultHeading,
                    fallbackIntro,
                    List.of(i18n.text("update-notes.fallback.detail")),
                    List.of(),
                    links,
                    linkOpener,
                    authorSupport));
        }
        final List<CardPageView> views = new ArrayList<>(parsed.sections().size());
        for (int index = 0; index < parsed.sections().size(); index++) {
            final Section section = parsed.sections().get(index);
            final String pageIntro = index == 0 ? upgradeIntro : "";
            final boolean lastPage = index == parsed.sections().size() - 1;
            views.add(CardPageView.markdown(
                    section.heading(),
                    pageIntro,
                    section.paragraphs(),
                    section.bullets(),
                    lastPage ? links : List.of(),
                    linkOpener,
                    lastPage ? authorSupport : null));
        }
        return List.copyOf(views);
    }

    /** Splits release-notes Markdown into sections separated by {@code ###} headings and trailing HTTP links. */
    static ParsedNotes parseMarkdown(final String markdown, final String defaultHeading) {
        if (markdown == null || markdown.isBlank()) {
            return new ParsedNotes(List.of(), List.of());
        }
        final String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n');
        final List<Section> sections = new ArrayList<>();
        final List<String> introParagraphs = new ArrayList<>();
        final List<String> standaloneLinks = new ArrayList<>();

        String currentHeading = null;
        final List<String> paragraphs = new ArrayList<>();
        final List<String> bullets = new ArrayList<>();
        final StringBuilder activeParagraph = new StringBuilder();
        int activeBullet = -1;
        boolean sawTopVersionHeading = false;

        for (final String rawLine : normalized.split("\n", -1)) {
            final String trimmed = rawLine.trim();
            if (!sawTopVersionHeading
                    && currentHeading == null
                    && introParagraphs.isEmpty()
                    && activeParagraph.isEmpty()
                    && trimmed.startsWith(TOP_HEADING_PREFIX)) {
                sawTopVersionHeading = true;
                continue;
            }
            if (trimmed.isEmpty()) {
                flushParagraph(activeParagraph, currentHeading == null ? introParagraphs : paragraphs);
                activeBullet = -1;
                continue;
            }
            if (trimmed.startsWith(SECTION_PREFIX)) {
                flushParagraph(activeParagraph, currentHeading == null ? introParagraphs : paragraphs);
                activeBullet = -1;
                if (currentHeading != null && (!paragraphs.isEmpty() || !bullets.isEmpty())) {
                    sections.add(new Section(currentHeading, paragraphs, bullets));
                    paragraphs.clear();
                    bullets.clear();
                }
                currentHeading = trimmed.substring(SECTION_PREFIX.length()).trim();
                continue;
            }
            if (isBulletLine(trimmed)) {
                flushParagraph(activeParagraph, currentHeading == null ? introParagraphs : paragraphs);
                final String bulletText = trimmed.substring(2).trim();
                if (!bulletText.isEmpty()) {
                    bullets.add(bulletText);
                    activeBullet = bullets.size() - 1;
                }
                continue;
            }
            if (activeBullet >= 0 && startsIndented(rawLine)) {
                bullets.set(activeBullet, bullets.get(activeBullet) + " " + trimmed);
                continue;
            }
            activeBullet = -1;
            if (isAllowedStandaloneLink(trimmed)) {
                flushParagraph(activeParagraph, currentHeading == null ? introParagraphs : paragraphs);
                standaloneLinks.add(trimmed);
                continue;
            }
            if (!activeParagraph.isEmpty()) {
                activeParagraph.append(' ');
            }
            activeParagraph.append(trimmed);
        }

        flushParagraph(activeParagraph, currentHeading == null ? introParagraphs : paragraphs);
        final boolean hasSectionHeadings = currentHeading != null;
        if (hasSectionHeadings) {
            if (!paragraphs.isEmpty() || !bullets.isEmpty()) {
                sections.add(new Section(currentHeading, paragraphs, bullets));
            }
        } else if (!introParagraphs.isEmpty() || !bullets.isEmpty()) {
            sections.add(new Section(defaultHeading, introParagraphs, bullets));
        }

        if (hasSectionHeadings && !sections.isEmpty() && !introParagraphs.isEmpty()) {
            final Section first = sections.get(0);
            sections.set(
                    0,
                    new Section(
                            first.heading(), concatParagraphs(introParagraphs, first.paragraphs()), first.bullets()));
        }

        final List<CardPageView.Link> links = new ArrayList<>();
        for (final String linkLine : standaloneLinks) {
            final Matcher matcher = STANDALONE_LINK.matcher(linkLine);
            if (matcher.matches() && HttpLinks.isAllowed(matcher.group(2))) {
                links.add(new CardPageView.Link(matcher.group(1), matcher.group(2)));
            }
        }
        return new ParsedNotes(List.copyOf(sections), List.copyOf(links));
    }

    private static List<CardPageView.Link> mergeLinks(
            final PluginLocalization i18n, final List<CardPageView.Link> extractedLinks) {
        final Map<String, CardPageView.Link> byUrl = new LinkedHashMap<>();
        for (final CardPageView.Link link : extractedLinks) {
            if (HttpLinks.isAllowed(link.url())) {
                byUrl.putIfAbsent(link.url(), link);
            }
        }
        byUrl.putIfAbsent(
                TurboismEndpoints.GITHUB_RELEASES_URL,
                new CardPageView.Link(i18n.text("update-notes.link.releases"), TurboismEndpoints.GITHUB_RELEASES_URL));
        return List.copyOf(byUrl.values());
    }

    private static boolean isBulletLine(final String trimmed) {
        return trimmed.startsWith("- ") || trimmed.startsWith("* ");
    }

    private static boolean startsIndented(final String rawLine) {
        return !rawLine.isEmpty() && (rawLine.charAt(0) == ' ' || rawLine.charAt(0) == '\t');
    }

    private static boolean isAllowedStandaloneLink(final String trimmed) {
        final Matcher matcher = STANDALONE_LINK.matcher(trimmed);
        return matcher.matches() && HttpLinks.isAllowed(matcher.group(2));
    }

    private static void flushParagraph(final StringBuilder activeParagraph, final List<String> target) {
        if (!activeParagraph.isEmpty()) {
            target.add(activeParagraph.toString());
            activeParagraph.setLength(0);
        }
    }

    private static List<String> concatParagraphs(final List<String> head, final List<String> tail) {
        if (head.isEmpty()) return tail;
        if (tail.isEmpty()) return head;
        final List<String> combined = new ArrayList<>(head.size() + tail.size());
        combined.addAll(head);
        combined.addAll(tail);
        return combined;
    }

    /** Parsed release-notes structure ready to render as one or more card pages. */
    record ParsedNotes(List<Section> sections, List<CardPageView.Link> links) {
        ParsedNotes {
            sections = List.copyOf(sections);
            links = List.copyOf(links);
        }
    }

    /** One heading-delimited section rendered on a single card page. */
    record Section(String heading, List<String> paragraphs, List<String> bullets) {
        Section {
            Objects.requireNonNull(heading, "heading");
            paragraphs = List.copyOf(paragraphs);
            bullets = List.copyOf(bullets);
        }
    }
}
