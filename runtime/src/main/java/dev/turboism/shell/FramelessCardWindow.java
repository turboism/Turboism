package dev.turboism.shell;

import dev.turboism.core.net.HttpLinks;
import dev.turboism.internal.TurboismEndpoints;
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
import java.util.function.Consumer;
import java.util.function.Function;
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
 * Shared frameless rounded card window for multi-page onboarding and release-note dialogs.
 *
 * <p>Corner arrow buttons switch pages, page dots show position, and user dismissals (close button,
 * window close, Esc, or advancing past the last page) notify {@code onDismissed} at most once per
 * {@link #show()} cycle; {@link #dispose()} never notifies it.</p>
 */
final class FramelessCardWindow {

    static final int CARD_WIDTH = 660;
    static final int CARD_HEIGHT = 510;

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
    private static final String AUTHOR_SUPPORT_TITLE_KEY = "card.author-support.title";
    private static final String AUTHOR_SUPPORT_BODY_KEY = "card.author-support.body";

    private final PluginLocalization i18n;
    private final Runnable onDismissed;
    private final JDialog dialog;
    private final List<CardPageView> pages;
    private final CardLayout cards = new CardLayout();
    private final JPanel cardHolder = new JPanel(cards);
    private final JButton previous;
    private final JButton next;
    private final PageDots dots;
    private int index;
    private boolean dismissed;

    static CardPageView.ClosingSection authorSupport(final PluginLocalization i18n) {
        return new CardPageView.ClosingSection(
                i18n.text(AUTHOR_SUPPORT_TITLE_KEY),
                i18n.text(AUTHOR_SUPPORT_BODY_KEY),
                new CardPageView.Link(
                        i18n.text(CoreWindows.ABOUT_SPONSOR_LABEL_KEY), TurboismEndpoints.SPONSOR_PAGE_URL));
    }

    FramelessCardWindow(
            final PluginLocalization i18n,
            final String windowTitle,
            final String closeTooltip,
            final Function<Consumer<String>, List<CardPageView>> pageFactory,
            final Runnable onDismissed) {
        this.i18n = Objects.requireNonNull(i18n, "i18n");
        this.onDismissed = Objects.requireNonNull(onDismissed, "onDismissed");
        Objects.requireNonNull(windowTitle, "windowTitle");
        Objects.requireNonNull(closeTooltip, "closeTooltip");
        Objects.requireNonNull(pageFactory, "pageFactory");

        this.dialog = CoreDialogs.create(windowTitle, CARD_WIDTH, CARD_HEIGHT);
        this.pages = List.copyOf(pageFactory.apply(this::openLink));
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("card window requires at least one page");
        }
        this.dots = new PageDots(pages.size());
        if (dialog == null) {
            previous = new IconButton(IconButton.Kind.PREVIOUS, "");
            next = new IconButton(IconButton.Kind.NEXT, "");
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
            final CardPanel content = new CardPanel(new BorderLayout(0, CARD_PADDING / 2));
            content.setBorder(
                    BorderFactory.createEmptyBorder(CARD_PADDING, CARD_PADDING, CARD_PADDING / 2, CARD_PADDING));
            content.add(createHeader(windowTitle, closeTooltip), BorderLayout.NORTH);
            cardHolder.setOpaque(false);
            for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
                cardHolder.add(pages.get(pageIndex), Integer.toString(pageIndex));
            }
            content.add(cardHolder, BorderLayout.CENTER);
            content.add(createFooter(), BorderLayout.SOUTH);
            dialog.setContentPane(content);
            installKeys(dialog);
            showPage(0);
        }
    }

    /** Opens the card on its first page and defers the dismissal callback until the user closes it. */
    void show() {
        if (dialog == null) return;
        dismissed = false;
        showPage(0);
        CoreDialogs.show(dialog);
    }

    /** Hides the card as a user dismissal and fires the dismissal callback once per show cycle. */
    void close() {
        if (dialog == null) return;
        dialog.setVisible(false);
        if (dismissed) return;
        dismissed = true;
        onDismissed.run();
    }

    /** Releases the native window during shell teardown without reporting a user dismissal. */
    void dispose() {
        if (dialog == null) return;
        dialog.dispose();
    }

    void next() {
        if (dialog == null) return;
        if (index >= pages.size() - 1) {
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
        index = Math.max(0, Math.min(page, pages.size() - 1));
        cards.show(cardHolder, Integer.toString(index));
        previous.setEnabled(index > 0);
        final boolean last = index == pages.size() - 1;
        next.setToolTipText(i18n.text(last ? "guide.nav.finish" : "guide.nav.next"));
        dots.setIndex(index);
    }

    /** Test seam: the zero-based page index currently shown. */
    int pageIndex() {
        return index;
    }

    /** Test seam: total number of card pages. */
    int pageCount() {
        return pages.size();
    }

    /** Test seam: immutable page views managed by this card window. */
    List<CardPageView> pages() {
        return pages;
    }

    /** Test seam: whether the native dialog is visible on screen. */
    boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    void openLink(final String value) {
        if (HttpLinks.isAllowed(value) && CoreWindows.openHttpLink(value)) return;
        CoreDialogs.message(dialog, i18n.text("common.turboism"), i18n.text("guide.open-failed"));
    }

    private JComponent createHeader(final String windowTitle, final String closeTooltip) {
        final JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, CARD_PADDING / 2, 0));
        final JLabel title = new JLabel(windowTitle);
        title.setFont(title.getFont().deriveFont(Font.BOLD, TITLE_FONT_SIZE));
        title.setForeground(TITLE_COLOR);
        header.add(title, BorderLayout.WEST);
        final JButton close = new IconButton(IconButton.Kind.CLOSE, closeTooltip);
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

    private void installKeys(final JDialog window) {
        bind(window.getRootPane(), KeyEvent.VK_LEFT, "card-previous", this::previous);
        bind(window.getRootPane(), KeyEvent.VK_RIGHT, "card-next", this::next);
        bind(window.getRootPane(), KeyEvent.VK_ESCAPE, "card-close", this::close);
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
            setPreferredSize(new Dimension(Math.max(1, count) * (DOT_DIAMETER + DOT_GAP), ARROW_SIZE));
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
