package dev.turboism.shell;

import dev.turboism.core.net.HttpLinks;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.event.HyperlinkEvent;

/**
 * Reusable card page for the frameless guide surfaces.
 *
 * <p>A caller supplies a resolved title and its content lines; links are optional. The control
 * owns the typography, wrapping, scrolling, and link activation, so a new page is
 * {@code new CardPageView(title, lines)}. The links row sits at the bottom of the page, directly
 * above the owner's footer, and carries no divider.</p>
 */
final class CardPageView extends JPanel {

    private static final int LINK_FONT_SIZE = 12;
    private static final int LINK_GAP = 18;
    private static final int SCROLL_UNIT = 24;
    private static final Color LINK_COLOR = new Color(0x2F6FED);
    private static final Pattern INLINE_TOKEN = Pattern.compile("`([^`\\r\\n]+)`|\\[([^\\]\\r\\n]+)]\\(([^)\\s]+)\\)");
    private static final Pattern CODE_SPAN = Pattern.compile("`([^`\\r\\n]+)`");
    private static final Pattern STRONG = Pattern.compile("\\*\\*([^*\\r\\n]+)\\*\\*");
    private static final Pattern EMPHASIS = Pattern.compile("(?<!\\*)\\*([^*\\r\\n]+)\\*(?!\\*)");
    private static final String CLOSING_SECTION_HEADING = "<h2>%s</h2>";
    private static final String CLOSING_SECTION_BODY = "<p class=\"body\">%s</p>";
    private static final String CLOSING_SECTION_LINK = "<p class=\"body\"><a href=\"%s\">%s</a></p>";
    private static final String PAGE_END = "</body></html>";
    /** One style block for every page, so the reusable control stays visually consistent. */
    private static final String PAGE_STYLE = "<html><head><meta charset=\"UTF-8\"><style>"
            + "body{margin:0;font-family:\"Segoe UI\",\"Microsoft YaHei\",Inter,sans-serif;color:#3F4A5A;}"
            + "h1{margin:0 0 10px 0;font-size:20px;color:#1F2937;}"
            + "h2{margin:14px 0 6px 0;font-size:15px;color:#1F2937;}"
            + "p.intro{margin:0 0 12px 0;font-size:13px;line-height:1.65;}"
            + "p.body{margin:0 0 10px 0;font-size:13px;line-height:1.65;}"
            + "ul{margin:0;padding-left:18px;}"
            + "li{margin:0 0 8px 0;font-size:13px;line-height:1.65;}"
            + "code{font-family:monospace;background:#F1F3F7;padding:1px 4px;}"
            + "a{color:#2F6FED;text-decoration:none;}"
            + "</style></head><body>";

    /** A footer link: the visible label plus the URL that activation opens. */
    record Link(String label, String url) {
        Link {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(url, "url");
        }
    }

    record ClosingSection(String title, String body, Link link) {}

    private final String title;
    private final List<String> bullets;

    /** Creates a page with a title and content lines only. */
    CardPageView(final String title, final List<String> bullets) {
        this(title, "", bullets, List.of(), ignored -> {});
    }

    /** Creates a page with a title, an optional intro line, and content lines. */
    CardPageView(final String title, final String intro, final List<String> bullets) {
        this(title, intro, bullets, List.of(), ignored -> {});
    }

    /**
     * Creates a page with an optional links row.
     *
     * @param title page heading, rendered as given (never as markup)
     * @param intro optional lead-in paragraph; blank omits it
     * @param bullets content lines, each rendered as one bullet
     * @param links footer links pinned above the owner's navigation; empty omits the row
     * @param linkOpener receives the URL of an activated link
     */
    CardPageView(
            final String title,
            final String intro,
            final List<String> bullets,
            final List<Link> links,
            final Consumer<String> linkOpener) {
        this(title, intro, bullets, links, linkOpener, null);
    }

    CardPageView(
            final String title,
            final String intro,
            final List<String> bullets,
            final List<Link> links,
            final Consumer<String> linkOpener,
            final ClosingSection closingSection) {
        this(title, bullets, html(title, intro, bullets, closingSection), links, linkOpener);
    }

    /** Creates a card page whose heading, paragraphs, and bullets support safe inline Markdown. */
    static CardPageView markdown(
            final String title,
            final String intro,
            final List<String> paragraphs,
            final List<String> bullets,
            final List<Link> links,
            final Consumer<String> linkOpener,
            final ClosingSection closingSection) {
        return new CardPageView(
                title, bullets, markdownHtml(title, intro, paragraphs, bullets, closingSection), links, linkOpener);
    }

    private CardPageView(
            final String title,
            final List<String> bullets,
            final String documentHtml,
            final List<Link> links,
            final Consumer<String> linkOpener) {
        super(new BorderLayout());
        setOpaque(false);
        this.title = Objects.requireNonNull(title, "title");
        this.bullets = List.copyOf(bullets);
        final Consumer<String> opener = Objects.requireNonNull(linkOpener, "linkOpener");

        final JEditorPane body = new JEditorPane("text/html", documentHtml);
        body.setEditable(false);
        body.setOpaque(false);
        body.setBorder(BorderFactory.createEmptyBorder());
        body.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        body.setCaretPosition(0);
        body.addHyperlinkListener(event -> {
            if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED && event.getDescription() != null) {
                opener.accept(event.getDescription());
            }
        });
        final JScrollPane scroll = new JScrollPane(body);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.getVerticalScrollBar().setUnitIncrement(SCROLL_UNIT);
        add(scroll, BorderLayout.CENTER);

        if (!links.isEmpty()) {
            add(linksRow(List.copyOf(links), opener), BorderLayout.SOUTH);
        }
    }

    /** Test seam: the title this page renders. */
    String title() {
        return title;
    }

    /** Test seam: the content lines this page renders. */
    List<String> bullets() {
        return bullets;
    }

    /**
     * Renders the page body. Every supplied string is escaped, so localized text can never inject
     * markup; the footer links are Swing controls and carry no HTML.
     */
    static String html(final String title, final String intro, final List<String> bullets) {
        return html(title, intro, bullets, null);
    }

    private static String html(
            final String title, final String intro, final List<String> bullets, final ClosingSection closingSection) {
        final StringBuilder html = new StringBuilder(512);
        html.append(PAGE_STYLE).append("<h1>").append(ShellHtml.escape(title)).append("</h1>");
        if (!intro.isBlank()) {
            html.append("<p class=\"intro\">").append(ShellHtml.escape(intro)).append("</p>");
        }
        html.append("<ul>");
        for (final String bullet : bullets) {
            html.append("<li>").append(ShellHtml.escape(bullet)).append("</li>");
        }
        return finishHtml(html.append("</ul>"), closingSection);
    }

    /** Renders a page body with safe inline Markdown formatting; only validated HTTP links become anchors. */
    static String markdownHtml(
            final String title, final String intro, final List<String> paragraphs, final List<String> bullets) {
        return markdownHtml(title, intro, paragraphs, bullets, null);
    }

    private static String markdownHtml(
            final String title,
            final String intro,
            final List<String> paragraphs,
            final List<String> bullets,
            final ClosingSection closingSection) {
        final StringBuilder html = new StringBuilder(512);
        html.append(PAGE_STYLE).append("<h1>").append(inlineMarkdown(title)).append("</h1>");
        if (intro != null && !intro.isBlank()) {
            html.append("<p class=\"intro\">").append(inlineMarkdown(intro)).append("</p>");
        }
        for (final String paragraph : paragraphs) {
            if (!paragraph.isBlank()) {
                html.append("<p class=\"body\">")
                        .append(inlineMarkdown(paragraph))
                        .append("</p>");
            }
        }
        if (!bullets.isEmpty()) {
            html.append("<ul>");
            for (final String bullet : bullets) {
                html.append("<li>").append(inlineMarkdown(bullet)).append("</li>");
            }
            html.append("</ul>");
        }
        return finishHtml(html, closingSection);
    }

    private static String finishHtml(final StringBuilder html, final ClosingSection section) {
        if (section != null) {
            html.append(CLOSING_SECTION_HEADING.formatted(ShellHtml.escape(section.title())));
            html.append(CLOSING_SECTION_BODY.formatted(ShellHtml.escape(section.body())));
            if (HttpLinks.isAllowed(section.link().url())) {
                html.append(CLOSING_SECTION_LINK.formatted(
                        ShellHtml.escape(section.link().url()),
                        ShellHtml.escape(section.link().label())));
            }
        }
        return html.append(PAGE_END).toString();
    }

    private static String inlineMarkdown(final String value) {
        if (value == null || value.isEmpty()) return "";
        final Matcher matcher = INLINE_TOKEN.matcher(value);
        final StringBuilder out = new StringBuilder(value.length() + 16);
        int offset = 0;
        while (matcher.find()) {
            out.append(formatEmphasis(value.substring(offset, matcher.start())));
            if (matcher.group(1) != null) {
                out.append("<code>").append(ShellHtml.escape(matcher.group(1))).append("</code>");
            } else {
                final String label = formatInlineText(matcher.group(2));
                final String url = matcher.group(3);
                if (HttpLinks.isAllowed(url)) {
                    out.append("<a href=\"")
                            .append(ShellHtml.escape(url))
                            .append("\">")
                            .append(label)
                            .append("</a>");
                } else {
                    out.append(label);
                }
            }
            offset = matcher.end();
        }
        out.append(formatEmphasis(value.substring(offset)));
        return out.toString();
    }

    private static String formatInlineText(final String value) {
        final Matcher matcher = CODE_SPAN.matcher(value);
        final StringBuilder out = new StringBuilder(value.length());
        int offset = 0;
        while (matcher.find()) {
            out.append(formatEmphasis(value.substring(offset, matcher.start())));
            out.append("<code>").append(ShellHtml.escape(matcher.group(1))).append("</code>");
            offset = matcher.end();
        }
        out.append(formatEmphasis(value.substring(offset)));
        return out.toString();
    }

    private static String formatEmphasis(final String value) {
        String escaped = ShellHtml.escape(value);
        escaped = replaceAll(STRONG, escaped, "<strong>$1</strong>");
        return replaceAll(EMPHASIS, escaped, "<em>$1</em>");
    }

    private static String replaceAll(final Pattern pattern, final String input, final String template) {
        final Matcher matcher = pattern.matcher(input);
        final StringBuilder out = new StringBuilder(input.length());
        while (matcher.find()) {
            final String group = Matcher.quoteReplacement(matcher.group(1));
            matcher.appendReplacement(out, template.replace("$1", group));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Builds the links row anchored at the page bottom; a link is a focusable, clickable control. */
    private JComponent linksRow(final List<Link> links, final Consumer<String> opener) {
        final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        for (final Link link : links) {
            final JButton button = new JButton(link.label());
            button.setContentAreaFilled(false);
            button.setBorderPainted(false);
            button.setFocusPainted(false);
            button.setOpaque(false);
            button.setMargin(new Insets(0, 0, 0, 0));
            button.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, LINK_GAP));
            button.setForeground(LINK_COLOR);
            button.setFont(button.getFont().deriveFont((float) LINK_FONT_SIZE));
            button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            button.setToolTipText(link.url());
            button.getAccessibleContext().setAccessibleName(link.label());
            button.addActionListener(event -> opener.accept(link.url()));
            row.add(button);
        }
        return row;
    }
}
