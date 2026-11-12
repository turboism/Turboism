package dev.turboism.shell;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;

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
    /** One style block for every page, so the reusable control stays visually consistent. */
    private static final String PAGE_STYLE = "<html><head><meta charset=\"UTF-8\"><style>"
            + "body{margin:0;font-family:\"Segoe UI\",\"Microsoft YaHei\",Inter,sans-serif;color:#3F4A5A;}"
            + "h1{margin:0 0 10px 0;font-size:20px;color:#1F2937;}"
            + "p.intro{margin:0 0 12px 0;font-size:13px;line-height:1.65;}"
            + "ul{margin:0;padding-left:18px;}"
            + "li{margin:0 0 8px 0;font-size:13px;line-height:1.65;}"
            + "</style></head><body>";

    /** A footer link: the visible label plus the URL that activation opens. */
    record Link(String label, String url) {
        Link {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(url, "url");
        }
    }

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
        super(new BorderLayout());
        setOpaque(false);
        this.title = Objects.requireNonNull(title, "title");
        this.bullets = List.copyOf(bullets);
        final Consumer<String> opener = Objects.requireNonNull(linkOpener, "linkOpener");

        final JEditorPane body = new JEditorPane("text/html", html(title, intro, this.bullets));
        body.setEditable(false);
        body.setOpaque(false);
        body.setBorder(BorderFactory.createEmptyBorder());
        body.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
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
        final StringBuilder html = new StringBuilder(512);
        html.append(PAGE_STYLE).append("<h1>").append(ShellHtml.escape(title)).append("</h1>");
        if (!intro.isBlank()) {
            html.append("<p class=\"intro\">").append(ShellHtml.escape(intro)).append("</p>");
        }
        html.append("<ul>");
        for (final String bullet : bullets) {
            html.append("<li>").append(ShellHtml.escape(bullet)).append("</li>");
        }
        return html.append("</ul></body></html>").toString();
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
