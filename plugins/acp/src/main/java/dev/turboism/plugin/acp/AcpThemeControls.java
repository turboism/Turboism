package dev.turboism.plugin.acp;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.swing.AbstractAction;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.AbstractBorder;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicComboPopup;
import javax.swing.plaf.basic.BasicMenuItemUI;
import javax.swing.plaf.basic.BasicPopupMenuUI;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.plaf.basic.ComboPopup;
import javax.swing.text.JTextComponent;

/**
 * Reusable Swing controls and painters that read semantic colors dynamically from {@link
 * AcpChatTheme} so live theme switches update every frame, dialog, scrollbar, and popup.
 */
final class AcpThemeControls {

    static final int SCROLL_ITEM_THRESHOLD = 8;
    static final int FILTER_ITEM_THRESHOLD = 10;
    static final int MAX_POPUP_SCROLL_HEIGHT = 256;
    static final String POPUP_PROPERTY = "acp.popup-menu";
    private static final int MIN_POPUP_WIDTH = 196;
    private static final int MAX_POPUP_WIDTH = 380;
    private static final int POPUP_GAP = 4;
    private static final int POPUP_INSET = 4;
    private static final int MENU_ITEM_HEIGHT = 30;
    private static final int MENU_ITEM_H_PAD = 10;
    private static final int MENU_ITEM_ICON_GAP = 8;
    private static final int MENU_ITEM_SECONDARY_GAP = 8;
    private static final int MENU_ITEM_CHECK_WIDTH = 18;
    private static final int MIN_SECONDARY_RENDER_WIDTH = 36;
    private static final float SECONDARY_FONT_DELTA = 0.5F;
    private static final int MENU_CORNER_ARC = 8;
    private static final int BUTTON_CORNER_ARC = 8;
    private static final int SCROLLBAR_WIDTH = 8;
    private static final int COMPACT_SCROLLBAR_WIDTH = 6;
    private static final int SCROLLBAR_MIN_THUMB = 24;
    private static final int SCROLLBAR_MARGIN = 1;
    private static final int SCROLLBAR_ARC = 6;
    private static final int SCROLL_UNIT_INCREMENT = 18;
    private static final int HOVER_ALPHA = 38;
    private static final int PRESSED_ALPHA = 64;
    private static final int SELECTED_ALPHA = 28;
    private static final String SELECTED_MARK = "✓";
    private static final String COMBO_CHEVRON = "▾";
    private static final String ELLIPSIS = "…";
    private static final String VARIANT_PROPERTY = "acp.button-variant";

    private AcpThemeControls() {}

    enum ButtonVariant {
        PRIMARY,
        SECONDARY,
        GHOST
    }

    /** Installs sleek themed scrollbars on a scroll pane without native arrow buttons. */
    static void configureScrollPane(final JScrollPane scroll, final boolean compact) {
        Objects.requireNonNull(scroll, "scroll");
        final int thickness = compact ? COMPACT_SCROLLBAR_WIDTH : SCROLLBAR_WIDTH;
        scroll.setWheelScrollingEnabled(true);
        configureScrollBar(scroll.getVerticalScrollBar(), thickness, true);
        configureScrollBar(scroll.getHorizontalScrollBar(), thickness, false);
    }

    private static void configureScrollBar(final JScrollBar bar, final int thickness, final boolean vertical) {
        if (bar == null) return;
        bar.setOpaque(false);
        bar.setUnitIncrement(SCROLL_UNIT_INCREMENT);
        bar.setPreferredSize(vertical ? new Dimension(thickness, 0) : new Dimension(0, thickness));
        bar.setUI(new ThemedScrollBarUI());
    }

    /** Applies semantic button painting that updates automatically across theme changes. */
    static void styleButton(final AbstractButton button, final ButtonVariant variant) {
        Objects.requireNonNull(button, "button");
        Objects.requireNonNull(variant, "variant");
        button.putClientProperty(VARIANT_PROPERTY, variant);
        button.putClientProperty("html.disable", true);
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setRolloverEnabled(true);
        button.setBorder(
                variant == ButtonVariant.GHOST
                        ? BorderFactory.createEmptyBorder(4, 8, 4, 8)
                        : BorderFactory.createEmptyBorder(6, 12, 6, 12));
        button.setUI(new ThemedButtonUI());
    }

    /** Styles a single-line text field with a dynamic theme-aware border and palette. */
    static void styleTextField(final JTextField field) {
        Objects.requireNonNull(field, "field");
        field.setBorder(BorderFactory.createCompoundBorder(
                new DynamicOutlineBorder(field), BorderFactory.createEmptyBorder(5, 8, 5, 8)));
        applyTextPalette(field);
    }

    /** Styles a combo box and its dropdown list to follow {@link AcpChatTheme}. */
    static void styleComboBox(final JComboBox<?> combo) {
        Objects.requireNonNull(combo, "combo");
        combo.setOpaque(false);
        combo.setBorder(BorderFactory.createCompoundBorder(
                new DynamicOutlineBorder(combo), BorderFactory.createEmptyBorder(3, 6, 3, 4)));
        combo.setUI(new ThemedComboBoxUI());
        combo.setBackground(AcpChatTheme.inputBackground());
        combo.setForeground(AcpChatTheme.foreground());
    }

    /** Applies a theme-aware titled section border whose label remains legible in dark mode. */
    static void styleTitledSection(final JPanel panel, final String title) {
        Objects.requireNonNull(panel, "panel");
        final TitledBorder border =
                BorderFactory.createTitledBorder(new DynamicLineBorder(), Objects.requireNonNull(title, "title"));
        border.setTitleColor(AcpChatTheme.foreground());
        panel.setBorder(BorderFactory.createCompoundBorder(border, BorderFactory.createEmptyBorder(6, 6, 6, 6)));
    }

    /** Creates a 1px border whose stroke resolves {@link AcpChatTheme#border()} on each paint. */
    static javax.swing.border.Border dynamicLineBorder() {
        return new DynamicLineBorder();
    }

    /** Recursively applies semantic colors and themed scrollbars across a Swing component tree. */
    static void applyThemeTree(final Component component) {
        if (component == null) return;
        if (component instanceof JScrollPane scroll) {
            final boolean keepTransparent = !scroll.isOpaque();
            if (!keepTransparent) {
                scroll.setBackground(AcpChatTheme.background());
                scroll.getViewport().setBackground(AcpChatTheme.background());
            }
            if (!(scroll.getVerticalScrollBar().getUI() instanceof ThemedScrollBarUI)) {
                configureScrollPane(scroll, keepTransparent);
            }
        } else if (component instanceof JPanel panel) {
            if (panel.isOpaque()) {
                panel.setBackground(AcpChatTheme.background());
            }
            if (panel.getBorder() instanceof javax.swing.border.CompoundBorder compound
                    && compound.getOutsideBorder() instanceof TitledBorder titled) {
                titled.setTitleColor(AcpChatTheme.foreground());
            } else if (panel.getBorder() instanceof TitledBorder titled) {
                titled.setTitleColor(AcpChatTheme.foreground());
            }
        }
        if (component instanceof JLabel) {
            component.setForeground(AcpChatTheme.foreground());
        }
        if (component instanceof AbstractButton button) {
            button.setForeground(AcpChatTheme.foreground());
            button.setBackground(AcpChatTheme.surface());
            final JPopupMenu popup = attachedPopup(button);
            if (popup != null) applyThemeTree(popup);
        }
        if (component instanceof JComboBox<?> combo) {
            combo.setBackground(AcpChatTheme.inputBackground());
            combo.setForeground(AcpChatTheme.foreground());
        }
        if (component instanceof JTextComponent text) {
            applyTextPalette(text);
        }
        if (component instanceof JList<?> list) {
            list.setBackground(AcpChatTheme.background());
            list.setForeground(AcpChatTheme.foreground());
            list.setSelectionBackground(AcpChatTheme.selectionBackground());
            list.setSelectionForeground(AcpChatTheme.selectionForeground());
        }
        if (component instanceof JPopupMenu popup) {
            popup.setBackground(AcpChatTheme.surface());
            popup.setForeground(AcpChatTheme.foreground());
        }
        if (component instanceof javax.swing.plaf.basic.BasicSplitPaneDivider divider) {
            divider.setBackground(AcpChatTheme.border());
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                applyThemeTree(child);
            }
        }
    }

    private static void applyTextPalette(final JTextComponent text) {
        text.setBackground(AcpChatTheme.inputBackground());
        text.setForeground(AcpChatTheme.foreground());
        text.setCaretColor(AcpChatTheme.foreground());
        text.setDisabledTextColor(AcpChatTheme.muted());
        text.setSelectionColor(AcpChatTheme.selectionBackground());
        text.setSelectedTextColor(AcpChatTheme.selectionForeground());
    }

    static void attachPopup(final AbstractButton button, final JPopupMenu popup) {
        Objects.requireNonNull(button, "button");
        button.putClientProperty(POPUP_PROPERTY, popup);
    }

    static JPopupMenu attachedPopup(final AbstractButton button) {
        if (button == null) return null;
        final Object attached = button.getClientProperty(POPUP_PROPERTY);
        return attached instanceof JPopupMenu popup ? popup : button.getComponentPopupMenu();
    }

    /**
     * Shows or toggles a popup anchored to {@code invoker}, opening upward when space below the
     * control is insufficient so bottom-docked composer menus never clip off the display.
     */
    static void showPopup(final JComponent invoker, final JPopupMenu popup) {
        if (invoker == null || popup == null || !invoker.isShowing()) return;
        if (popup.isShowing() && popup.getInvoker() == invoker) {
            MenuSelectionManager.defaultManager().clearSelectedPath();
            popup.setVisible(false);
            return;
        }
        applyThemeTree(popup);
        if (popup instanceof ThemedPopupMenu themed) {
            themed.prepareForShow();
        }
        final Dimension size = popup.getPreferredSize();
        int x = 0;
        int y = invoker.getHeight() + POPUP_GAP;
        boolean opensUpward = false;
        final GraphicsConfiguration gc = invoker.getGraphicsConfiguration();
        if (gc != null) {
            final Rectangle screen = gc.getBounds();
            final Insets screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
            final int usableTop = screen.y + screenInsets.top;
            final int usableBottom = screen.y + screen.height - screenInsets.bottom;
            final int usableRight = screen.x + screen.width - screenInsets.right;
            final Point onScreen = invoker.getLocationOnScreen();
            final int spaceBelow = usableBottom - (onScreen.y + invoker.getHeight() + POPUP_GAP);
            final int spaceAbove = onScreen.y - POPUP_GAP - usableTop;
            if (size.height > spaceBelow && spaceAbove > spaceBelow) {
                opensUpward = true;
                y = -Math.min(size.height, spaceAbove) - POPUP_GAP;
            }
            if (onScreen.x + size.width > usableRight) {
                x = Math.min(0, usableRight - (onScreen.x + size.width));
            }
        }
        if (popup instanceof ThemedPopupMenu themed) {
            themed.opensUpward = opensUpward;
        }
        popup.show(invoker, x, y);
        if (popup instanceof ThemedPopupMenu themed) {
            themed.focusInitialControl();
        }
    }

    private static Color withAlpha(final Color base, final int alpha) {
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    private static Color contrastingForeground(final Color fill) {
        final double luminance = (0.299 * fill.getRed() + 0.587 * fill.getGreen() + 0.114 * fill.getBlue()) / 255.0;
        return luminance > 0.58 ? new Color(0x18181B) : Color.WHITE;
    }

    /**
     * Bounded, theme-styled popup menu. Small option sets stay flat for zero overhead; large sets
     * (such as 20–100 model choices) automatically gain a compact scroll viewport and instant
     * filter input so the popup never grows taller than {@link #MAX_POPUP_SCROLL_HEIGHT}.
     */
    static final class ThemedPopupMenu extends JPopupMenu {
        private final List<ThemedMenuItem> choices = new ArrayList<>();
        private final List<Component> footerItems = new ArrayList<>();
        private final String filterPlaceholder;
        private JTextField filterField;
        private JPanel listContainer;
        private JScrollPane scrollPane;
        private boolean built;
        boolean opensUpward;

        ThemedPopupMenu() {
            this("");
        }

        ThemedPopupMenu(final String filterPlaceholder) {
            this.filterPlaceholder = Objects.requireNonNullElse(filterPlaceholder, "");
            setUI(new BasicPopupMenuUI());
            setLightWeightPopupEnabled(false);
            setOpaque(true);
            setBackground(AcpChatTheme.surface());
            setBorder(BorderFactory.createCompoundBorder(
                    new DynamicSurfaceBorder(),
                    BorderFactory.createEmptyBorder(POPUP_INSET, POPUP_INSET, POPUP_INSET, POPUP_INSET)));
            installPopupKeyBindings();
        }

        ThemedMenuItem addChoice(final String label, final boolean selected, final Icon icon, final Runnable onSelect) {
            return addChoice(label, "", selected, icon, onSelect);
        }

        ThemedMenuItem addChoice(
                final String label,
                final String secondaryLabel,
                final boolean selected,
                final Icon icon,
                final Runnable onSelect) {
            final ThemedMenuItem item = new ThemedMenuItem(label, secondaryLabel, selected, icon, this, onSelect);
            choices.add(item);
            return item;
        }

        JMenuItem addFooterAction(final String name, final String label, final Runnable onSelect) {
            final ThemedMenuItem item = new ThemedMenuItem(label, "", false, null, this, onSelect);
            if (name != null && !name.isBlank()) item.setName(name);
            footerItems.add(item);
            return item;
        }

        /** Finalizes the layout as either a flat compact menu or a bounded scrollable list. */
        ThemedPopupMenu finish() {
            if (built) return this;
            built = true;
            removeAll();
            if (choices.size() <= SCROLL_ITEM_THRESHOLD) {
                for (ThemedMenuItem item : choices) add(item);
                if (!footerItems.isEmpty() && !choices.isEmpty()) {
                    add(new ThemedSeparator());
                }
                for (Component footer : footerItems) add(footer);
                setFocusable(true);
                return this;
            }
            setLayout(new BorderLayout(0, POPUP_INSET));
            listContainer = new JPanel();
            listContainer.setLayout(new BoxLayout(listContainer, BoxLayout.Y_AXIS));
            listContainer.setOpaque(true);
            listContainer.setBackground(AcpChatTheme.surface());
            int preferredWidth = MIN_POPUP_WIDTH;
            for (ThemedMenuItem item : choices) {
                item.setAlignmentX(Component.LEFT_ALIGNMENT);
                item.setMaximumSize(new Dimension(Integer.MAX_VALUE, MENU_ITEM_HEIGHT));
                preferredWidth = Math.max(preferredWidth, item.getPreferredSize().width + SCROLLBAR_WIDTH + 8);
                listContainer.add(item);
            }
            preferredWidth = Math.min(MAX_POPUP_WIDTH, preferredWidth);
            scrollPane = new JScrollPane(
                    listContainer, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            scrollPane.setBorder(BorderFactory.createEmptyBorder());
            scrollPane.setOpaque(true);
            scrollPane.setBackground(AcpChatTheme.surface());
            scrollPane.getViewport().setBackground(AcpChatTheme.surface());
            configureScrollPane(scrollPane, true);
            final int scrollHeight = Math.min(MAX_POPUP_SCROLL_HEIGHT, choices.size() * MENU_ITEM_HEIGHT);
            scrollPane.setPreferredSize(new Dimension(preferredWidth, scrollHeight));
            if (choices.size() >= FILTER_ITEM_THRESHOLD) {
                filterField = new AcpComposerPanel.PromptSearchField(filterPlaceholder);
                styleTextField(filterField);
                filterField.setPreferredSize(new Dimension(preferredWidth, MENU_ITEM_HEIGHT + 2));
                installFilterBehavior(filterField);
                add(filterField, BorderLayout.NORTH);
            }
            add(scrollPane, BorderLayout.CENTER);
            if (!footerItems.isEmpty()) {
                final JPanel footerPanel = new JPanel();
                footerPanel.setLayout(new BoxLayout(footerPanel, BoxLayout.Y_AXIS));
                footerPanel.setOpaque(false);
                footerPanel.add(new ThemedSeparator());
                for (Component footer : footerItems) {
                    if (footer instanceof JComponent jc) {
                        jc.setAlignmentX(Component.LEFT_ALIGNMENT);
                        jc.setMaximumSize(new Dimension(Integer.MAX_VALUE, MENU_ITEM_HEIGHT));
                    }
                    footerPanel.add(footer);
                }
                add(footerPanel, BorderLayout.SOUTH);
            }
            setFocusable(true);
            return this;
        }

        List<ThemedMenuItem> selectableItems() {
            return List.copyOf(choices);
        }

        boolean isScrollableMenu() {
            return scrollPane != null;
        }

        void prepareForShow() {
            finish();
            setBackground(AcpChatTheme.surface());
            if (listContainer != null) listContainer.setBackground(AcpChatTheme.surface());
            if (scrollPane != null) {
                scrollPane.setBackground(AcpChatTheme.surface());
                scrollPane.getViewport().setBackground(AcpChatTheme.surface());
            }
            if (filterField != null && !filterField.getText().isEmpty()) {
                filterField.setText("");
            }
            resetInitialHighlight();
            scrollSelectedIntoView();
        }

        void focusInitialControl() {
            SwingUtilities.invokeLater(() -> {
                if (!isShowing()) return;
                if (filterField != null) {
                    filterField.requestFocusInWindow();
                } else {
                    requestFocusInWindow();
                }
            });
        }

        private void resetInitialHighlight() {
            ThemedMenuItem target = null;
            for (ThemedMenuItem item : choices) {
                if (item.isVisible() && item.isEnabled() && item.selectedChoice) {
                    target = item;
                    break;
                }
            }
            if (target == null) {
                for (ThemedMenuItem item : choices) {
                    if (item.isVisible() && item.isEnabled()) {
                        target = item;
                        break;
                    }
                }
            }
            setKeyboardHighlight(target);
        }

        void setKeyboardHighlight(final ThemedMenuItem target) {
            for (ThemedMenuItem item : choices) {
                item.keyboardHighlighted = item == target;
            }
            for (Component footer : footerItems) {
                if (footer instanceof ThemedMenuItem item) {
                    item.keyboardHighlighted = item == target;
                }
            }
            repaint();
        }

        private List<ThemedMenuItem> navigableItems() {
            final List<ThemedMenuItem> result = new ArrayList<>();
            for (ThemedMenuItem item : choices) {
                if (item.isVisible() && item.isEnabled()) result.add(item);
            }
            for (Component footer : footerItems) {
                if (footer instanceof ThemedMenuItem item && item.isVisible() && item.isEnabled()) {
                    result.add(item);
                }
            }
            return result;
        }

        private void installPopupKeyBindings() {
            final var selfMap = getInputMap(JComponent.WHEN_FOCUSED);
            selfMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "acp.popup.down");
            selfMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "acp.popup.up");
            selfMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "acp.popup.commit");
            selfMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "acp.popup.close");
            final var actions = getActionMap();
            actions.put("acp.popup.down", new AbstractAction() {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent event) {
                    moveHighlight(1);
                }
            });
            actions.put("acp.popup.up", new AbstractAction() {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent event) {
                    moveHighlight(-1);
                }
            });
            actions.put("acp.popup.commit", new AbstractAction() {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent event) {
                    commitHighlighted();
                }
            });
            actions.put("acp.popup.close", new AbstractAction() {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent event) {
                    MenuSelectionManager.defaultManager().clearSelectedPath();
                    setVisible(false);
                }
            });
        }

        private void scrollSelectedIntoView() {
            if (scrollPane == null) return;
            for (ThemedMenuItem item : choices) {
                if (item.selectedChoice) {
                    SwingUtilities.invokeLater(() -> {
                        if (item.isShowing()) {
                            listContainer.scrollRectToVisible(item.getBounds());
                        }
                    });
                    break;
                }
            }
        }

        JTextField filterInput() {
            return filterField;
        }

        private void installFilterBehavior(final JTextField field) {
            field.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(final DocumentEvent event) {
                    applyFilter(field.getText());
                }

                @Override
                public void removeUpdate(final DocumentEvent event) {
                    applyFilter(field.getText());
                }

                @Override
                public void changedUpdate(final DocumentEvent event) {
                    applyFilter(field.getText());
                }
            });
            final var inputMap = field.getInputMap(JComponent.WHEN_FOCUSED);
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "acp.filter.down");
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "acp.filter.up");
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "acp.filter.commit");
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "acp.filter.close");
            final var actions = field.getActionMap();
            actions.put("acp.filter.down", new AbstractAction() {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent event) {
                    moveHighlight(1);
                }
            });
            actions.put("acp.filter.up", new AbstractAction() {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent event) {
                    moveHighlight(-1);
                }
            });
            actions.put("acp.filter.commit", new AbstractAction() {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent event) {
                    commitHighlighted();
                }
            });
            actions.put("acp.filter.close", new AbstractAction() {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent event) {
                    MenuSelectionManager.defaultManager().clearSelectedPath();
                    setVisible(false);
                }
            });
        }

        void applyFilter(final String rawQuery) {
            if (listContainer == null || scrollPane == null) return;
            final String query = rawQuery == null ? "" : rawQuery.strip().toLowerCase(Locale.ROOT);
            int visibleCount = 0;
            ThemedMenuItem firstVisible = null;
            ThemedMenuItem selectedVisible = null;
            for (ThemedMenuItem item : choices) {
                final boolean match = query.isEmpty() || item.matchesQuery(query);
                item.setVisible(match);
                if (match) {
                    visibleCount++;
                    if (firstVisible == null) firstVisible = item;
                    if (item.selectedChoice && selectedVisible == null) selectedVisible = item;
                }
            }
            setKeyboardHighlight(query.isEmpty() && selectedVisible != null ? selectedVisible : firstVisible);
            final int height =
                    Math.max(MENU_ITEM_HEIGHT, Math.min(MAX_POPUP_SCROLL_HEIGHT, visibleCount * MENU_ITEM_HEIGHT));
            scrollPane.setPreferredSize(new Dimension(scrollPane.getPreferredSize().width, height));
            listContainer.revalidate();
            scrollPane.revalidate();
            revalidate();
            if (isShowing()) {
                final Dimension pref = getPreferredSize();
                final Component invoker = getInvoker();
                if (opensUpward && invoker != null && invoker.isShowing()) {
                    final Point invokerLoc = invoker.getLocationOnScreen();
                    setLocation(getLocationOnScreen().x, invokerLoc.y - pref.height - POPUP_GAP);
                }
                pack();
            }
            repaint();
        }

        void moveHighlight(final int delta) {
            final List<ThemedMenuItem> visible = navigableItems();
            if (visible.isEmpty()) return;
            int current = -1;
            for (int i = 0; i < visible.size(); i++) {
                if (visible.get(i).keyboardHighlighted) {
                    current = i;
                    break;
                }
            }
            final int next =
                    current < 0 ? (delta > 0 ? 0 : visible.size() - 1) : Math.floorMod(current + delta, visible.size());
            final ThemedMenuItem target = visible.get(next);
            setKeyboardHighlight(target);
            if (listContainer != null && target.getParent() == listContainer) {
                listContainer.scrollRectToVisible(target.getBounds());
            }
        }

        void commitHighlighted() {
            final List<ThemedMenuItem> visible = navigableItems();
            if (visible.isEmpty()) return;
            for (ThemedMenuItem item : visible) {
                if (item.keyboardHighlighted) {
                    item.activateSelection();
                    return;
                }
            }
            visible.get(0).activateSelection();
        }
    }

    /** Theme-painted menu row with hover highlight, optional icon, secondary channel tag, and active checkmark. */
    static final class ThemedMenuItem extends JMenuItem {
        private final String secondaryText;
        private final boolean selectedChoice;
        private final ThemedPopupMenu owner;
        private final Runnable onSelect;
        private boolean keyboardHighlighted;

        ThemedMenuItem(
                final String label,
                final String secondaryText,
                final boolean selectedChoice,
                final Icon icon,
                final ThemedPopupMenu owner,
                final Runnable onSelect) {
            super(label);
            this.secondaryText = Objects.requireNonNullElse(secondaryText, "").strip();
            this.selectedChoice = selectedChoice;
            this.owner = owner;
            this.onSelect = onSelect;
            setUI(new BasicMenuItemUI() {
                @Override
                protected void installListeners() {
                    // Bypass MenuSelectionManager drag/arm tracking so scrollable popups and mouse
                    // movement across search/scrollbar regions never dismiss or drop clicks.
                }

                @Override
                protected void uninstallListeners() {}
            });
            putClientProperty("html.disable", true);
            setIcon(icon);
            setOpaque(false);
            setFocusable(false);
            setBorder(BorderFactory.createEmptyBorder(0, MENU_ITEM_H_PAD, 0, MENU_ITEM_H_PAD));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setRolloverEnabled(true);
            setToolTipText(this.secondaryText.isEmpty() ? label : label + " · " + this.secondaryText);
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseEntered(final java.awt.event.MouseEvent event) {
                    if (isEnabled() && owner != null) {
                        owner.setKeyboardHighlight(ThemedMenuItem.this);
                    }
                    getModel().setRollover(true);
                    repaint();
                }

                @Override
                public void mouseExited(final java.awt.event.MouseEvent event) {
                    getModel().setRollover(false);
                    repaint();
                }

                @Override
                public void mousePressed(final java.awt.event.MouseEvent event) {
                    if (SwingUtilities.isLeftMouseButton(event) && isEnabled()) {
                        getModel().setPressed(true);
                        repaint();
                    }
                }

                @Override
                public void mouseReleased(final java.awt.event.MouseEvent event) {
                    if (!SwingUtilities.isLeftMouseButton(event)) return;
                    final boolean wasPressed = getModel().isPressed();
                    getModel().setPressed(false);
                    if (wasPressed && isEnabled() && contains(event.getPoint())) {
                        activateSelection();
                    } else {
                        repaint();
                    }
                }
            });
            addActionListener(ignored -> invokeSelection());
        }

        boolean matchesQuery(final String lowerQuery) {
            return Objects.requireNonNullElse(getText(), "")
                            .toLowerCase(Locale.ROOT)
                            .contains(lowerQuery)
                    || secondaryText.toLowerCase(Locale.ROOT).contains(lowerQuery);
        }

        String secondaryLabel() {
            return secondaryText;
        }

        void activateSelection() {
            doClick(0);
        }

        private void invokeSelection() {
            MenuSelectionManager.defaultManager().clearSelectedPath();
            if (owner != null) owner.setVisible(false);
            if (onSelect != null) onSelect.run();
        }

        @Override
        public Dimension getPreferredSize() {
            final Font baseFont = getFont();
            final Font primaryFont = baseFont.deriveFont(selectedChoice ? Font.BOLD : Font.PLAIN);
            final FontMetrics primaryMetrics = getFontMetrics(primaryFont);
            final int iconWidth = getIcon() == null ? 0 : getIcon().getIconWidth() + MENU_ITEM_ICON_GAP;
            final int primaryWidth = primaryMetrics.stringWidth(Objects.requireNonNullElse(getText(), ""));
            int secondaryWidth = 0;
            if (!secondaryText.isEmpty()) {
                final Font secFont =
                        baseFont.deriveFont(Font.PLAIN, Math.max(11F, baseFont.getSize2D() - SECONDARY_FONT_DELTA));
                secondaryWidth =
                        MENU_ITEM_SECONDARY_GAP + getFontMetrics(secFont).stringWidth(secondaryText);
            }
            final int totalWidth = Math.min(
                    MAX_POPUP_WIDTH - 2 * POPUP_INSET,
                    Math.max(
                            MIN_POPUP_WIDTH - 2 * POPUP_INSET,
                            2 * MENU_ITEM_H_PAD + iconWidth + primaryWidth + secondaryWidth + MENU_ITEM_CHECK_WIDTH));
            return new Dimension(totalWidth, MENU_ITEM_HEIGHT);
        }

        @Override
        protected void paintComponent(final Graphics graphics) {
            final Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                final boolean hovered = isArmed() || getModel().isRollover() || keyboardHighlighted;
                if (hovered && isEnabled()) {
                    g.setColor(withAlpha(AcpChatTheme.accent(), HOVER_ALPHA));
                    g.fillRoundRect(0, 1, getWidth(), getHeight() - 2, MENU_CORNER_ARC, MENU_CORNER_ARC);
                } else if (selectedChoice) {
                    g.setColor(withAlpha(AcpChatTheme.accent(), SELECTED_ALPHA));
                    g.fillRoundRect(0, 1, getWidth(), getHeight() - 2, MENU_CORNER_ARC, MENU_CORNER_ARC);
                }
                int x = MENU_ITEM_H_PAD;
                final Icon icon = getIcon();
                if (icon != null) {
                    setForeground(isEnabled() ? AcpChatTheme.foreground() : AcpChatTheme.muted());
                    icon.paintIcon(this, g, x, (getHeight() - icon.getIconHeight()) / 2);
                    x += icon.getIconWidth() + MENU_ITEM_ICON_GAP;
                }
                final Font baseFont = getFont();
                final Font primaryFont = baseFont.deriveFont(selectedChoice ? Font.BOLD : Font.PLAIN);
                g.setFont(primaryFont);
                final FontMetrics primaryMetrics = g.getFontMetrics();
                final int rightLimit = getWidth() - MENU_ITEM_H_PAD - (selectedChoice ? MENU_ITEM_CHECK_WIDTH : 0);
                final int availableWidth = Math.max(24, rightLimit - x);
                final String rawPrimary = Objects.requireNonNullElse(getText(), "");
                final int fullPrimaryWidth = primaryMetrics.stringWidth(rawPrimary);
                final int primaryMaxWidth = !secondaryText.isEmpty()
                                && availableWidth
                                        > fullPrimaryWidth + MENU_ITEM_SECONDARY_GAP + MIN_SECONDARY_RENDER_WIDTH
                        ? fullPrimaryWidth
                        : (!secondaryText.isEmpty() && availableWidth > 140
                                ? Math.min(fullPrimaryWidth, availableWidth - MENU_ITEM_SECONDARY_GAP - 56)
                                : availableWidth);
                final String renderedPrimary = truncateEnd(rawPrimary, primaryMetrics, primaryMaxWidth);
                g.setColor(isEnabled() ? AcpChatTheme.foreground() : AcpChatTheme.muted());
                final int baseline = (getHeight() - primaryMetrics.getHeight()) / 2 + primaryMetrics.getAscent();
                g.drawString(renderedPrimary, x, baseline);

                final int afterPrimaryX = x + primaryMetrics.stringWidth(renderedPrimary) + MENU_ITEM_SECONDARY_GAP;
                if (!secondaryText.isEmpty() && rightLimit - afterPrimaryX >= MIN_SECONDARY_RENDER_WIDTH) {
                    final Font secFont =
                            baseFont.deriveFont(Font.PLAIN, Math.max(11F, baseFont.getSize2D() - SECONDARY_FONT_DELTA));
                    g.setFont(secFont);
                    final FontMetrics secMetrics = g.getFontMetrics();
                    final String renderedSecondary = truncateEnd(secondaryText, secMetrics, rightLimit - afterPrimaryX);
                    g.setColor(AcpChatTheme.muted());
                    g.drawString(renderedSecondary, afterPrimaryX, baseline);
                    g.setFont(primaryFont);
                }

                if (selectedChoice) {
                    g.setColor(AcpChatTheme.accent());
                    final int checkX = getWidth() - MENU_ITEM_H_PAD - primaryMetrics.stringWidth(SELECTED_MARK);
                    g.drawString(SELECTED_MARK, checkX, baseline);
                }
            } finally {
                g.dispose();
            }
        }
    }

    static String truncateEnd(final String text, final FontMetrics metrics, final int maxWidth) {
        if (metrics.stringWidth(text) <= maxWidth) return text;
        final int ellipsisWidth = metrics.stringWidth(ELLIPSIS);
        if (maxWidth <= ellipsisWidth) return ELLIPSIS;
        int end = text.length();
        while (end > 0 && metrics.stringWidth(text.substring(0, end)) + ellipsisWidth > maxWidth) {
            end = Character.offsetByCodePoints(text, end, -1);
        }
        return text.substring(0, end) + ELLIPSIS;
    }

    private static final class ThemedSeparator extends JSeparator {
        private ThemedSeparator() {
            setOpaque(false);
            setPreferredSize(new Dimension(1, 7));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 7));
        }

        @Override
        protected void paintComponent(final Graphics graphics) {
            graphics.setColor(AcpChatTheme.border());
            final int y = getHeight() / 2;
            graphics.drawLine(6, y, getWidth() - 6, y);
        }
    }

    private static final class ThemedScrollBarUI extends BasicScrollBarUI {
        @Override
        protected void configureScrollBarColors() {
            thumbColor = AcpChatTheme.border();
            trackColor = AcpChatTheme.background();
        }

        @Override
        protected JButton createDecreaseButton(final int orientation) {
            return zeroButton();
        }

        @Override
        protected JButton createIncreaseButton(final int orientation) {
            return zeroButton();
        }

        private static JButton zeroButton() {
            final JButton button = new JButton();
            final Dimension zero = new Dimension(0, 0);
            button.setPreferredSize(zero);
            button.setMinimumSize(zero);
            button.setMaximumSize(zero);
            button.setBorder(BorderFactory.createEmptyBorder());
            button.setContentAreaFilled(false);
            return button;
        }

        @Override
        protected Dimension getMinimumThumbSize() {
            return scrollbar.getOrientation() == SwingConstants.VERTICAL
                    ? new Dimension(SCROLLBAR_WIDTH, SCROLLBAR_MIN_THUMB)
                    : new Dimension(SCROLLBAR_MIN_THUMB, SCROLLBAR_WIDTH);
        }

        @Override
        protected void paintTrack(final Graphics graphics, final JComponent component, final Rectangle trackBounds) {
            // Keep the scrollbar track visually clean against the parent surface.
        }

        @Override
        protected void paintThumb(final Graphics graphics, final JComponent component, final Rectangle thumbBounds) {
            if (thumbBounds.isEmpty() || !scrollbar.isEnabled()) return;
            final Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                final Color color = isDragging
                        ? AcpChatTheme.accent()
                        : isThumbRollover() ? AcpChatTheme.muted() : AcpChatTheme.border();
                g.setColor(color);
                g.fillRoundRect(
                        thumbBounds.x + SCROLLBAR_MARGIN,
                        thumbBounds.y + SCROLLBAR_MARGIN,
                        Math.max(2, thumbBounds.width - 2 * SCROLLBAR_MARGIN),
                        Math.max(2, thumbBounds.height - 2 * SCROLLBAR_MARGIN),
                        SCROLLBAR_ARC,
                        SCROLLBAR_ARC);
            } finally {
                g.dispose();
            }
        }
    }

    private static final class ThemedButtonUI extends BasicButtonUI {
        @Override
        public void paint(final Graphics graphics, final JComponent component) {
            final AbstractButton button = (AbstractButton) component;
            final Object prop = button.getClientProperty(VARIANT_PROPERTY);
            final ButtonVariant variant = prop instanceof ButtonVariant v ? v : ButtonVariant.SECONDARY;
            final Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                final boolean enabled = button.isEnabled();
                final boolean pressed = enabled && button.getModel().isPressed();
                final boolean rollover = enabled && button.getModel().isRollover();
                final Color textColor;
                switch (variant) {
                    case PRIMARY -> {
                        final Color base = enabled ? AcpChatTheme.accent() : AcpChatTheme.surface();
                        g.setColor(pressed ? base.darker() : rollover ? base.brighter() : base);
                        g.fillRoundRect(
                                0, 0, button.getWidth(), button.getHeight(), BUTTON_CORNER_ARC, BUTTON_CORNER_ARC);
                        if (!enabled) {
                            g.setColor(AcpChatTheme.border());
                            g.drawRoundRect(
                                    0,
                                    0,
                                    button.getWidth() - 1,
                                    button.getHeight() - 1,
                                    BUTTON_CORNER_ARC,
                                    BUTTON_CORNER_ARC);
                        }
                        textColor = enabled ? contrastingForeground(base) : AcpChatTheme.muted();
                    }
                    case GHOST -> {
                        if (pressed || rollover) {
                            g.setColor(withAlpha(AcpChatTheme.accent(), pressed ? PRESSED_ALPHA : HOVER_ALPHA));
                            g.fillRoundRect(
                                    0, 0, button.getWidth(), button.getHeight(), BUTTON_CORNER_ARC, BUTTON_CORNER_ARC);
                        }
                        textColor = enabled ? AcpChatTheme.foreground() : AcpChatTheme.muted();
                    }
                    default -> {
                        g.setColor(AcpChatTheme.surface());
                        g.fillRoundRect(
                                0, 0, button.getWidth(), button.getHeight(), BUTTON_CORNER_ARC, BUTTON_CORNER_ARC);
                        if (pressed || rollover) {
                            g.setColor(withAlpha(AcpChatTheme.accent(), pressed ? PRESSED_ALPHA : HOVER_ALPHA));
                            g.fillRoundRect(
                                    0, 0, button.getWidth(), button.getHeight(), BUTTON_CORNER_ARC, BUTTON_CORNER_ARC);
                        }
                        g.setColor(button.hasFocus() ? AcpChatTheme.accent() : AcpChatTheme.border());
                        g.drawRoundRect(
                                0,
                                0,
                                button.getWidth() - 1,
                                button.getHeight() - 1,
                                BUTTON_CORNER_ARC,
                                BUTTON_CORNER_ARC);
                        textColor = enabled ? AcpChatTheme.foreground() : AcpChatTheme.muted();
                    }
                }
                g.setFont(button.getFont());
                final Insets insets = button.getInsets();
                final Rectangle view = new Rectangle(
                        insets.left,
                        insets.top,
                        button.getWidth() - insets.left - insets.right,
                        button.getHeight() - insets.top - insets.bottom);
                final Rectangle iconBounds = new Rectangle();
                final Rectangle textBounds = new Rectangle();
                final String clipped = SwingUtilities.layoutCompoundLabel(
                        button,
                        g.getFontMetrics(),
                        button.getText(),
                        button.getIcon(),
                        button.getVerticalAlignment(),
                        button.getHorizontalAlignment(),
                        button.getVerticalTextPosition(),
                        button.getHorizontalTextPosition(),
                        view,
                        iconBounds,
                        textBounds,
                        button.getIconTextGap());
                if (button.getIcon() != null) {
                    button.setForeground(textColor);
                    button.getIcon().paintIcon(button, g, iconBounds.x, iconBounds.y);
                }
                if (clipped != null && !clipped.isEmpty()) {
                    g.setColor(textColor);
                    javax.swing.plaf.basic.BasicGraphicsUtils.drawStringUnderlineCharAt(
                            g,
                            clipped,
                            button.getDisplayedMnemonicIndex(),
                            textBounds.x,
                            textBounds.y + g.getFontMetrics().getAscent());
                }
            } finally {
                g.dispose();
            }
        }
    }

    private static final class ThemedComboBoxUI extends BasicComboBoxUI {
        @Override
        protected JButton createArrowButton() {
            final JButton arrow = new JButton(COMBO_CHEVRON) {
                @Override
                protected void paintComponent(final Graphics graphics) {
                    final Graphics2D g = (Graphics2D) graphics.create();
                    try {
                        g.setRenderingHint(
                                RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                        g.setFont(getFont());
                        g.setColor(comboBox.isEnabled() ? AcpChatTheme.muted() : AcpChatTheme.border());
                        final FontMetrics metrics = g.getFontMetrics();
                        final int x = (getWidth() - metrics.stringWidth(COMBO_CHEVRON)) / 2;
                        final int y = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
                        g.drawString(COMBO_CHEVRON, x, y);
                    } finally {
                        g.dispose();
                    }
                }
            };
            arrow.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
            arrow.setContentAreaFilled(false);
            arrow.setOpaque(false);
            arrow.setFocusable(false);
            return arrow;
        }

        @Override
        protected ComboPopup createPopup() {
            return new BasicComboPopup(comboBox) {
                @Override
                protected JScrollPane createScroller() {
                    final JScrollPane scroller = super.createScroller();
                    configureScrollPane(scroller, true);
                    return scroller;
                }

                @Override
                public void show() {
                    setBorder(new DynamicSurfaceBorder());
                    setBackground(AcpChatTheme.surface());
                    list.setBackground(AcpChatTheme.surface());
                    list.setForeground(AcpChatTheme.foreground());
                    list.setSelectionBackground(AcpChatTheme.selectionBackground());
                    list.setSelectionForeground(AcpChatTheme.selectionForeground());
                    super.show();
                }
            };
        }

        @Override
        public void paintCurrentValueBackground(
                final Graphics graphics, final Rectangle bounds, final boolean hasFocus) {
            final Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(AcpChatTheme.inputBackground());
                g.fillRoundRect(0, 0, comboBox.getWidth(), comboBox.getHeight(), BUTTON_CORNER_ARC, BUTTON_CORNER_ARC);
            } finally {
                g.dispose();
            }
        }
    }

    private static final class DynamicOutlineBorder extends AbstractBorder {
        private final JComponent target;

        private DynamicOutlineBorder(final JComponent target) {
            this.target = target;
        }

        @Override
        public void paintBorder(
                final Component component,
                final Graphics graphics,
                final int x,
                final int y,
                final int width,
                final int height) {
            final Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(target.hasFocus() ? AcpChatTheme.accent() : AcpChatTheme.border());
                g.drawRoundRect(x, y, width - 1, height - 1, BUTTON_CORNER_ARC, BUTTON_CORNER_ARC);
            } finally {
                g.dispose();
            }
        }

        @Override
        public Insets getBorderInsets(final Component component) {
            return new Insets(1, 1, 1, 1);
        }
    }

    private static final class DynamicSurfaceBorder extends AbstractBorder {
        @Override
        public void paintBorder(
                final Component component,
                final Graphics graphics,
                final int x,
                final int y,
                final int width,
                final int height) {
            graphics.setColor(AcpChatTheme.border());
            graphics.drawRect(x, y, width - 1, height - 1);
        }

        @Override
        public Insets getBorderInsets(final Component component) {
            return new Insets(1, 1, 1, 1);
        }
    }

    private static final class DynamicLineBorder extends AbstractBorder {
        @Override
        public void paintBorder(
                final Component component,
                final Graphics graphics,
                final int x,
                final int y,
                final int width,
                final int height) {
            graphics.setColor(AcpChatTheme.border());
            graphics.drawRect(x, y, width - 1, height - 1);
        }

        @Override
        public Insets getBorderInsets(final Component component) {
            return new Insets(1, 1, 1, 1);
        }
    }
}
