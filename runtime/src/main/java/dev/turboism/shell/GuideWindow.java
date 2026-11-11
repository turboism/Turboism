package dev.turboism.shell;

import dev.turboism.core.net.HttpLinks;
import dev.turboism.sdk.i18n.PluginLocalization;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.LayoutManager;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import java.util.Objects;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.WindowConstants;

/**
 * The one-time product guide: a frameless card with three pages.
 *
 * <p>Corner arrow buttons with transparent backgrounds switch pages, page dots show position, and
 * the window chrome is drawn in-house because the card has no native title bar. Closing the card,
 * pressing Esc, or advancing past the last page reports one dismissal, which is what makes the
 * guide one-time; disposing the window during shell teardown never reports one.</p>
 */
final class GuideWindow {

    static final int CARD_WIDTH = 660;
    /** Tall enough for the longest shipped translation, so no page opens with a scrollbar. */
    static final int CARD_HEIGHT = 510;

    static final String DOCUMENTATION_URL = "https://docs.turboism.dev";
    static final String ISSUES_URL = "https://github.com/turboism/Turboism/issues";

    private static final int CARD_ARC = 18;
    private static final int CARD_PADDING = 24;
    private static final int ARROW_SIZE = 40;
    private static final int CLOSE_SIZE = 28;
    private static final int DOT_DIAMETER = 7;
    private static final int DOT_GAP = 7;
    private static final float TITLE_FONT_SIZE = 16f;
    private static final float STROKE_WIDTH = 1.8f;
    private static final Color CARD = new Color(0xFFFFFF);
    private static final Color CARD_BORDER = new Color(0xD8DEE8);
    private static final Color CARD_BACKDROP = new Color(0xF1F3F7);
    private static final Color TITLE_COLOR = new Color(0x1F2937);
    private static final Color MUTED_COLOR = new Color(0x6B7280);
    private static final Color ARROW_COLOR = new Color(0x44506A);
    private static final Color DISABLED_COLOR = new Color(0xC9CFDA);
    private static final Color DOT_COLOR = new Color(0x2F6FED);
    private static final Color DOT_IDLE_COLOR = new Color(0xD3D9E3);
    private static final Color HOVER_COLOR = new Color(47, 111, 237, 26);
    private static final Color TRANSPARENT = new Color(0, 0, 0, 0);

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

    private final PluginLocalization i18n;
    private final Runnable onDismissed;
    private final JDialog dialog;
    private final CardLayout cards = new CardLayout();
    private final JPanel cardHolder = new JPanel(cards);
    private final JButton previous;
    private final JButton next;
    private final PageDots dots;
    private int index;
    private boolean dismissed;

    GuideWindow(final PluginLocalization i18n, final Runnable onDismissed) {
        this.i18n = Objects.requireNonNull(i18n, "i18n");
        this.onDismissed = Objects.requireNonNull(onDismissed, "onDismissed");
        dialog = CoreDialogs.create(i18n.text("guide.window.title"), CARD_WIDTH, CARD_HEIGHT);
        if (dialog == null) {
            previous = new IconButton(IconButton.Kind.PREVIOUS, "");
            next = new IconButton(IconButton.Kind.NEXT, "");
            dots = new PageDots(PAGES.size());
        } else {
            dialog.setUndecorated(true);
            dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            dialog.getRootPane().setOpaque(false);
            dialog.setAutoRequestFocus(true);
            dialog.setBackground(supportsTranslucency() ? TRANSPARENT : CARD_BACKDROP);
            dialog.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(final WindowEvent event) {
                    close();
                }
            });
            previous = new IconButton(IconButton.Kind.PREVIOUS, i18n.text("guide.nav.previous"));
            next = new IconButton(IconButton.Kind.NEXT, i18n.text("guide.nav.next"));
            dots = new PageDots(PAGES.size());
            final CardPanel content = new CardPanel(new BorderLayout(0, CARD_PADDING / 2));
            content.setBorder(
                    BorderFactory.createEmptyBorder(CARD_PADDING, CARD_PADDING, CARD_PADDING / 2, CARD_PADDING));
            content.add(createHeader(), BorderLayout.NORTH);
            cardHolder.setOpaque(false);
            for (final Page page : PAGES) {
                cardHolder.add(createPage(page), page.titleKey());
            }
            content.add(cardHolder, BorderLayout.CENTER);
            content.add(createFooter(), BorderLayout.SOUTH);
            dialog.setContentPane(content);
            installKeys(dialog);
            showPage(0);
        }
    }

    /** Opens the card on its first page and reports nothing until the user dismisses it. */
    void show() {
        if (dialog == null) return;
        dismissed = false;
        showPage(0);
        CoreDialogs.show(dialog);
    }

    /** Hides the card as a user dismissal, which is the only path that records the guide as seen. */
    void close() {
        if (dialog == null) return;
        dialog.setVisible(false);
        if (dismissed) return;
        dismissed = true;
        onDismissed.run();
    }

    /** Releases the native window during shell teardown without recording a dismissal. */
    void dispose() {
        if (dialog == null) return;
        dialog.dispose();
    }

    void next() {
        if (dialog == null) return;
        if (index >= PAGES.size() - 1) {
            close();
            return;
        }
        showPage(index + 1);
    }

    void previous() {
        if (dialog == null || index <= 0) return;
        showPage(index - 1);
    }

    void showPage(final int page) {
        if (dialog == null) return;
        index = Math.max(0, Math.min(page, PAGES.size() - 1));
        cards.show(cardHolder, PAGES.get(index).titleKey());
        previous.setEnabled(index > 0);
        final boolean last = index == PAGES.size() - 1;
        next.setToolTipText(i18n.text(last ? "guide.nav.finish" : "guide.nav.next"));
        dots.setIndex(index);
    }

    /** Test seam: the page currently shown, counted from zero. */
    int pageIndex() {
        return index;
    }

    /** Test seam: whether the native card is on screen. */
    boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    /** Test seam: the immutable page model, so key wiring is verified without a display. */
    static List<Page> pages() {
        return PAGES;
    }

    private JComponent createHeader() {
        final JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, CARD_PADDING / 2, 0));
        final JLabel title = new JLabel(i18n.text("guide.window.title"));
        title.setFont(title.getFont().deriveFont(Font.BOLD, TITLE_FONT_SIZE));
        title.setForeground(TITLE_COLOR);
        header.add(title, BorderLayout.WEST);
        final JButton close = new IconButton(IconButton.Kind.CLOSE, i18n.text("guide.window.close"));
        close.addActionListener(event -> this.close());
        header.add(close, BorderLayout.EAST);
        installDrag(header);
        return header;
    }

    private JComponent createFooter() {
        final JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        previous.addActionListener(event -> this.previous());
        next.addActionListener(event -> this.next());
        footer.add(previous, BorderLayout.WEST);
        footer.add(dots, BorderLayout.CENTER);
        footer.add(next, BorderLayout.EAST);
        return footer;
    }

    private JComponent createPage(final Page page) {
        final List<String> bullets = page.bulletKeys().stream().map(i18n::text).toList();
        final List<CardPageView.Link> links = page.links().stream()
                .map(link -> new CardPageView.Link(i18n.text(link.labelKey()), link.url()))
                .toList();
        return new CardPageView(i18n.text(page.titleKey()), i18n.text(page.introKey()), bullets, links, this::openLink);
    }

    private void openLink(final String value) {
        if (HttpLinks.isAllowed(value) && CoreWindows.openHttpLink(value)) return;
        CoreDialogs.message(dialog, i18n.text("common.turboism"), i18n.text("guide.open-failed"));
    }

    private void installKeys(final JDialog window) {
        bind(window.getRootPane(), KeyEvent.VK_LEFT, "guide-previous", this::previous);
        bind(window.getRootPane(), KeyEvent.VK_RIGHT, "guide-next", this::next);
        bind(window.getRootPane(), KeyEvent.VK_ESCAPE, "guide-close", this::close);
    }

    private static void bind(final JComponent component, final int keyCode, final String name, final Runnable action) {
        component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(keyCode, 0), name);
        component.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent event) {
                action.run();
            }
        });
    }

    private void installDrag(final JComponent handle) {
        final Point press = new Point();
        handle.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(final MouseEvent event) {
                press.setLocation(event.getLocationOnScreen());
            }
        });
        handle.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseDragged(final MouseEvent event) {
                if (dialog == null) return;
                final Point current = event.getLocationOnScreen();
                final Point origin = dialog.getLocation();
                dialog.setLocation(origin.x + current.x - press.x, origin.y + current.y - press.y);
                press.setLocation(current);
            }
        });
    }

    private static boolean supportsTranslucency() {
        if (GraphicsEnvironment.isHeadless()) return false;
        try {
            return GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice()
                    .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
        } catch (RuntimeException unsupported) {
            return false;
        }
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

    /** Rounded card surface; the window background stays transparent around it when supported. */
    private static final class CardPanel extends JPanel {
        CardPanel(final LayoutManager layout) {
            super(layout);
            setOpaque(false);
        }

        @Override
        protected void paintComponent(final Graphics graphics) {
            super.paintComponent(graphics);
            final Graphics2D painter = (Graphics2D) graphics.create();
            try {
                painter.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                final RoundRectangle2D card =
                        new RoundRectangle2D.Float(0f, 0f, getWidth() - 1f, getHeight() - 1f, CARD_ARC, CARD_ARC);
                painter.setColor(CARD);
                painter.fill(card);
                painter.setColor(CARD_BORDER);
                painter.setStroke(new BasicStroke(1f));
                painter.draw(card);
            } finally {
                painter.dispose();
            }
        }
    }

    /** Transparent-background chevron or close button with a painted hover disc. */
    private static final class IconButton extends JButton {
        private enum Kind {
            PREVIOUS,
            NEXT,
            CLOSE
        }

        private final Kind kind;

        IconButton(final Kind kind, final String tooltip) {
            this.kind = kind;
            setToolTipText(tooltip);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(BorderFactory.createEmptyBorder());
            final int size = kind == Kind.CLOSE ? CLOSE_SIZE : ARROW_SIZE;
            setPreferredSize(new Dimension(size, size));
            setMinimumSize(new Dimension(size, size));
            getAccessibleContext().setAccessibleName(tooltip);
        }

        @Override
        protected void paintComponent(final Graphics graphics) {
            final Graphics2D painter = (Graphics2D) graphics.create();
            try {
                painter.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                painter.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                if (getModel().isRollover() || getModel().isPressed()) {
                    painter.setColor(HOVER_COLOR);
                    painter.fill(new RoundRectangle2D.Float(0f, 0f, getWidth(), getHeight(), getWidth(), getHeight()));
                }
                painter.setColor(isEnabled() ? (kind == Kind.CLOSE ? MUTED_COLOR : ARROW_COLOR) : DISABLED_COLOR);
                painter.setStroke(new BasicStroke(STROKE_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                final double centerX = getWidth() / 2.0;
                final double centerY = getHeight() / 2.0;
                final double radius = Math.min(getWidth(), getHeight()) / 6.0;
                if (kind == Kind.CLOSE) {
                    painter.draw(
                            new Line2D.Double(centerX - radius, centerY - radius, centerX + radius, centerY + radius));
                    painter.draw(
                            new Line2D.Double(centerX + radius, centerY - radius, centerX - radius, centerY + radius));
                } else {
                    drawChevron(painter, centerX, centerY, radius, kind == Kind.NEXT);
                }
            } finally {
                painter.dispose();
            }
        }

        private static void drawChevron(
                final Graphics2D painter,
                final double centerX,
                final double centerY,
                final double radius,
                final boolean forward) {
            final double direction = forward ? radius : -radius;
            final Path2D chevron = new Path2D.Double();
            chevron.moveTo(centerX - direction / 2.0, centerY - radius);
            chevron.lineTo(centerX + direction / 2.0, centerY);
            chevron.lineTo(centerX - direction / 2.0, centerY + radius);
            painter.draw(chevron);
        }
    }

    /** Page dots centered between the corner arrow buttons. */
    private static final class PageDots extends JPanel {
        private final int count;
        private int index;

        PageDots(final int count) {
            this.count = count;
            setOpaque(false);
            setPreferredSize(new Dimension(count * (DOT_DIAMETER + DOT_GAP), ARROW_SIZE));
        }

        void setIndex(final int value) {
            index = value;
            repaint();
        }

        @Override
        protected void paintComponent(final Graphics graphics) {
            super.paintComponent(graphics);
            final Graphics2D painter = (Graphics2D) graphics.create();
            try {
                painter.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                final int total = count * DOT_DIAMETER + (count - 1) * DOT_GAP;
                int x = (getWidth() - total) / 2;
                final int y = (getHeight() - DOT_DIAMETER) / 2;
                for (int dot = 0; dot < count; dot++) {
                    painter.setColor(dot == index ? DOT_COLOR : DOT_IDLE_COLOR);
                    painter.fillOval(x, y, DOT_DIAMETER, DOT_DIAMETER);
                    x += DOT_DIAMETER + DOT_GAP;
                }
            } finally {
                painter.dispose();
            }
        }
    }
}
