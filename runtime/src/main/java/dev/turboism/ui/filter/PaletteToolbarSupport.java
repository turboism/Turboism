package dev.turboism.ui.filter;

import dev.turboism.core.reflect.MethodHandleCache;
import dev.turboism.mapping.verification.VerifiedAccessException;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.ui.filter.PaletteFilterRegistry;
import dev.turboism.ui.palette.LogPaletteHostStructure;
import dev.turboism.ui.toolbar.EditorUiPluginResourceRegistry;
import dev.turboism.ui.toolbar.PaletteToolbarContributionDescriptor;
import dev.turboism.ui.toolbar.PaletteToolbarHostOperations;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.JTree;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.awt.Window;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/** Filter-box construction and toolbar contribution placement. */
final class ToolbarPlacement {
    final Container toolbar;
    final JComponent contribution;
    final JPanel wrapper;
    final Container originalParent;
    final int originalIndex;
    final Object originalConstraint;
    boolean attached = true;

    ToolbarPlacement(
        final Container toolbar,
        final JComponent contribution,
        final JPanel wrapper,
        final Container originalParent,
        final int originalIndex,
        final Object originalConstraint
    ) {
        this.toolbar = toolbar;
        this.contribution = contribution;
        this.wrapper = wrapper;
        this.originalParent = originalParent;
        this.originalIndex = originalIndex;
        this.originalConstraint = originalConstraint;
    }

    boolean isCurrent() {
        return attached && (wrapper == null
            ? contribution.getParent() == toolbar
            : wrapper.getParent() == originalParent
                && contribution.getParent() == wrapper
                && toolbar.getParent() == wrapper);
    }

    void detach() {
        if (!attached) return;
        attached = false;
        if (wrapper == null) {
            toolbar.remove(contribution);
            toolbar.revalidate();
            toolbar.repaint();
            return;
        }
        wrapper.remove(contribution);
        wrapper.remove(toolbar);
        if (wrapper.getParent() == originalParent) {
            originalParent.remove(wrapper);
            if (originalConstraint != null) {
                originalParent.add(toolbar, originalConstraint);
            } else {
                originalParent.add(
                    toolbar,
                    Math.max(0, Math.min(originalIndex, originalParent.getComponentCount()))
                );
            }
            originalParent.revalidate();
            originalParent.repaint();
        }
    }
}

final class FilterBox {
    final JPanel panel;
    final JTextField field;
    final JButton clearButton;

    FilterBox(final JPanel panel, final JTextField field, final JButton clearButton) {
        this.panel = panel;
        this.field = field;
        this.clearButton = clearButton;
    }
}

final class PaletteToolbarSupport {

    private PaletteToolbarSupport() {
    }

    static final String FILTER_PANEL_NAME = "turboismPaletteFilterPanel";

    static final String FILTER_FIELD_NAME = "turboismPaletteFilterField";

    static final String CLEAR_BUTTON_NAME = "turboismPaletteFilterClearButton";

    static final int FILTER_PANEL_WIDTH = 140;

    static final int FILTER_PANEL_HEIGHT = 26;

    static final int CLEAR_BUTTON_WIDTH = 20;

    static final int CLEAR_BUTTON_HEIGHT = 24;

    static final int TEXT_LEFT_INSET = 6;

    static final int TEXT_RIGHT_INSET = 28;

    static final String TOOLBAR_ROW_MARKER_KEY = "turboism.paletteFilter.toolbarRow";

    static final String TOOLBAR_BUTTON_NAME = "turboismPaletteToolbarButton";

    static final String TOOLBAR_BUTTON_MARKER_KEY = "turboism.paletteToolbar.button";

    static int toolbarAlignment(final String anchor) {
        return switch (anchor) {
            case "start", "first" -> FlowLayout.LEFT;
            case "end", "last" -> FlowLayout.RIGHT;
            default -> throw new IllegalStateException("palette toolbar anchor is unsupported: " + anchor);
        };
    }

    /** Creates the filter box (placeholder field + clear button overlay). Pure Swing, ported from legacy. */
    static FilterBox createFilterBox(
        final String placeholder,
        final String initialText,
        final Consumer<String> onTextChanged
    ) {
        final JPanel filterPanel = new JPanel(new BorderLayout());
        filterPanel.setName(FILTER_PANEL_NAME);
        filterPanel.setOpaque(false);
        filterPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
        filterPanel.setPreferredSize(new Dimension(FILTER_PANEL_WIDTH, FILTER_PANEL_HEIGHT));
        filterPanel.setMinimumSize(new Dimension(FILTER_PANEL_WIDTH, FILTER_PANEL_HEIGHT));
        filterPanel.setMaximumSize(new Dimension(FILTER_PANEL_WIDTH, FILTER_PANEL_HEIGHT));

        final JTextField filterField = new JTextField() {
            @Override
            protected void paintComponent(final Graphics graphics) {
                super.paintComponent(graphics);
                if (!getText().isEmpty() || isFocusOwner()) {
                    return;
                }
                final Graphics2D graphics2d = (Graphics2D) graphics.create();
                try {
                    graphics2d.setRenderingHint(
                        RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    graphics2d.setColor(new Color(150, 150, 150));
                    graphics2d.setFont(getFont());
                    final Insets insets = getInsets();
                    final FontMetrics fontMetrics = graphics2d.getFontMetrics();
                    final int textX = insets.left;
                    final int textY = (getHeight() - fontMetrics.getHeight()) / 2 + fontMetrics.getAscent();
                    graphics2d.drawString(placeholder, textX, textY);
                } finally {
                    graphics2d.dispose();
                }
            }
        };
        filterField.setName(FILTER_FIELD_NAME);
        filterField.setMargin(new Insets(0, TEXT_LEFT_INSET, 0, TEXT_RIGHT_INSET));
        filterField.setToolTipText(placeholder);

        final JButton clearButton = new JButton("×");
        clearButton.setName(CLEAR_BUTTON_NAME);
        clearButton.setFocusable(false);
        clearButton.setMargin(new Insets(0, 0, 0, 0));
        clearButton.setBorder(BorderFactory.createEmptyBorder());
        clearButton.setBorderPainted(false);
        clearButton.setContentAreaFilled(false);
        clearButton.setOpaque(false);
        clearButton.setFocusPainted(false);
        clearButton.setFont(filterField.getFont().deriveFont(Font.BOLD, 13f));
        clearButton.setForeground(new Color(70, 70, 70));
        clearButton.setPreferredSize(new Dimension(CLEAR_BUTTON_WIDTH, CLEAR_BUTTON_HEIGHT));
        clearButton.setMinimumSize(new Dimension(CLEAR_BUTTON_WIDTH, CLEAR_BUTTON_HEIGHT));
        clearButton.addActionListener(event -> {
            filterField.setText("");
            filterField.requestFocusInWindow();
        });

        final JPanel fieldOverlay = new JPanel(null) {
            @Override
            public void doLayout() {
                final int width = getWidth();
                final int height = getHeight();
                filterField.setBounds(0, 0, width, height);
                final int buttonHeight = Math.min(CLEAR_BUTTON_HEIGHT, Math.max(0, height));
                final int buttonX = Math.max(0, width - CLEAR_BUTTON_WIDTH - 4);
                final int buttonY = Math.max(0, (height - buttonHeight) / 2);
                clearButton.setBounds(buttonX, buttonY, CLEAR_BUTTON_WIDTH, buttonHeight);
            }
        };
        fieldOverlay.setOpaque(false);
        fieldOverlay.setPreferredSize(new Dimension(FILTER_PANEL_WIDTH, FILTER_PANEL_HEIGHT));
        fieldOverlay.setMinimumSize(new Dimension(FILTER_PANEL_WIDTH, FILTER_PANEL_HEIGHT));
        fieldOverlay.setMaximumSize(new Dimension(FILTER_PANEL_WIDTH, FILTER_PANEL_HEIGHT));
        fieldOverlay.add(filterField);
        fieldOverlay.add(clearButton);
        fieldOverlay.setComponentZOrder(clearButton, 0);
        fieldOverlay.setComponentZOrder(filterField, 1);
        filterPanel.add(fieldOverlay, BorderLayout.CENTER);

        filterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(final DocumentEvent event) { update(); }
            @Override public void removeUpdate(final DocumentEvent event) { update(); }
            @Override public void changedUpdate(final DocumentEvent event) { update(); }
            private void update() {
                onTextChanged.accept(filterField.getText());
            }
        });
        filterField.setText(initialText);
        return new FilterBox(filterPanel, filterField, clearButton);
    }

    /** Framework-owned placement: contribution left, untouched host toolbar right. */
    static ToolbarPlacement attachToolbarContribution(
        final Container toolbar,
        final JComponent contribution
    ) {
        Objects.requireNonNull(toolbar, "toolbar");
        Objects.requireNonNull(contribution, "contribution");
        final Container parent = toolbar.getParent();
        if (parent == null) {
            toolbar.add(contribution, 0);
            toolbar.revalidate();
            toolbar.repaint();
            return new ToolbarPlacement(toolbar, contribution, null, null, -1, null);
        }

        final LayoutManager layout = parent.getLayout();
        final Object constraint = layout instanceof BorderLayout
            ? ((BorderLayout) layout).getConstraints(toolbar)
            : null;
        final int index = parent.getComponentZOrder(toolbar);
        final JPanel wrapper = new JPanel(new BorderLayout(8, 0));
        wrapper.setOpaque(false);
        wrapper.putClientProperty(TOOLBAR_ROW_MARKER_KEY, Boolean.TRUE);

        parent.remove(toolbar);
        wrapper.add(contribution, BorderLayout.WEST);
        wrapper.add(toolbar, BorderLayout.EAST);
        if (constraint != null) {
            parent.add(wrapper, constraint);
        } else {
            parent.add(wrapper, Math.max(0, Math.min(index, parent.getComponentCount())));
        }
        parent.revalidate();
        parent.repaint();
        return new ToolbarPlacement(toolbar, contribution, wrapper, parent, index, constraint);
    }

    static void ensureFilterBox(
        final PaletteFilterState state,
        final Container toolbar,
        final PaletteFilterRegistry.PaletteFilterContribution contribution,
        final Consumer<String> onTextChanged
    ) {
        if (state.filterBox != null && state.toolbarPlacement != null
            && state.toolbarPlacement.isCurrent()) {
            return;
        }
        detachFilterBox(state);
        state.filterBox = createFilterBox(contribution.placeholderKey(), state.filterText, text -> {
            state.filterText = PaletteFilterHostOperations.normalize(text);
            onTextChanged.accept(text);
        });
        state.toolbarPlacement = attachToolbarContribution(toolbar, state.filterBox.panel);
    }

    static void detachFilterBox(final PaletteFilterState state) {
        if (state.toolbarPlacement != null) {
            state.toolbarPlacement.detach();
            state.toolbarPlacement = null;
        } else if (state.filterBox != null) {
            final Container parent = state.filterBox.panel.getParent();
            if (parent != null) {
                parent.remove(state.filterBox.panel);
                parent.revalidate();
                parent.repaint();
            }
        }
        state.filterBox = null;
    }

    static void syncToolbarButtons(final PaletteFilterHostOperations host, final PaletteFilterState state, final Container toolbar) {
        final List<PaletteToolbarHostOperations.ButtonContribution> requested =
            host.toolbarContributions.getOrDefault(state.kind, List.of());
        final boolean current = state.toolbarSnapshot.equals(requested)
            && state.toolbarButtons.values().stream().allMatch(button -> button.getParent() != null);
        if (current) return;
        detachToolbarButtons(state);
        state.toolbarSnapshot = requested;
        if (requested.isEmpty()) return;

        if (state.kind == PaletteFilterHostOperations.PaletteKind.LOG) {
            if (state.toolbarButtonPanel == null) {
                state.toolbarButtonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
                state.toolbarButtonPanel.setOpaque(false);
            }
            if (state.levelPanel == null) {
                state.levelPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
                state.levelPanel.setOpaque(false);
            }
            if (state.toolbarButtonPanel.getParent() != state.toolbarPanel) {
                state.toolbarPanel.add(state.toolbarButtonPanel, BorderLayout.WEST);
            }
            if (state.levelPanel.getParent() != state.toolbarPanel) {
                state.toolbarPanel.add(state.levelPanel, BorderLayout.EAST);
            }
            for (PaletteToolbarHostOperations.ButtonContribution contribution : requested) {
                final JButton button = createToolbarButton(host, contribution);
                state.toolbarButtons.put(contribution.descriptor().nativeId(), button);
                final Container target = toolbarAlignment(contribution.descriptor().anchor()) == FlowLayout.LEFT
                    ? state.toolbarButtonPanel
                    : state.levelPanel;
                target.add(button, target == state.levelPanel
                    ? Math.max(0, target.getComponentCount() - logLevelButtonCount(state))
                    : target.getComponentCount());
            }
            state.toolbarPanel.revalidate();
            state.toolbarPanel.repaint();
            return;
        }

        int startIndex = 0;
        for (PaletteToolbarHostOperations.ButtonContribution contribution : requested) {
            final JButton button = createToolbarButton(host, contribution);
            state.toolbarButtons.put(contribution.descriptor().nativeId(), button);
            if (toolbarAlignment(contribution.descriptor().anchor()) == FlowLayout.LEFT) {
                toolbar.add(button, Math.min(startIndex++, toolbar.getComponentCount()));
            } else {
                toolbar.add(button);
            }
        }
        toolbar.revalidate();
        toolbar.repaint();
    }

    static int logLevelButtonCount(final PaletteFilterState state) {
        return state.infoButton == null ? 0 : 3;
    }

    static JButton createToolbarButton(final PaletteFilterHostOperations host, final PaletteToolbarHostOperations.ButtonContribution contribution) {
        final PaletteToolbarContributionDescriptor descriptor = contribution.descriptor();
        ImageIcon icon = null;
        if (host.resources != null) {
            final URL url = host.resources.resource(descriptor.pluginId(), descriptor.iconResourcePath()).orElse(null);
            if (url != null) icon = new ImageIcon(url);
        }
        final JButton button = new JButton(descriptor.label(), icon);
        button.setName(TOOLBAR_BUTTON_NAME);
        button.putClientProperty(TOOLBAR_BUTTON_MARKER_KEY, descriptor.nativeId());
        button.setToolTipText(descriptor.label());
        button.setFocusable(false);
        button.addActionListener(ignored -> contribution.action().run());
        return button;
    }

    static void detachToolbarButtons(final PaletteFilterState state) {
        for (JButton button : state.toolbarButtons.values()) {
            final Container parent = button.getParent();
            if (parent != null) parent.remove(button);
        }
        state.toolbarButtons.clear();
        if (state.toolbarButtonPanel != null && state.toolbarButtonPanel.getParent() != null) {
            final Container parent = state.toolbarButtonPanel.getParent();
            parent.remove(state.toolbarButtonPanel);
            parent.revalidate();
            parent.repaint();
        }
        state.toolbarButtonPanel = null;
        state.toolbarSnapshot = List.of();
    }
}
