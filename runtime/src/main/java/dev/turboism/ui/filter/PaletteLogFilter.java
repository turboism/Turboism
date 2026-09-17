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

/** Log-palette toolbar, filtered document install, and level filtering. */
/** Package-visible for the filter regression tests (debounce seam). */
final class PaletteFilterState {
    final PaletteFilterHostOperations.PaletteKind kind;
    volatile Object controller;
    volatile Object scenePalette;
    volatile JComponent root;
    volatile JTable table;
    volatile JTextPane sourceTextPane;
    volatile javax.swing.text.Document sourceDoc;
    volatile javax.swing.text.Document filteredDoc;
    volatile JViewport viewport;
    volatile Container scrollShell;
    volatile JPanel wrapper;
    volatile JPanel toolbarPanel;
    volatile Container toolbar;
    volatile FilterBox filterBox;
    volatile ToolbarPlacement toolbarPlacement;
    volatile JPanel toolbarButtonPanel;
    volatile List<PaletteToolbarHostOperations.ButtonContribution> toolbarSnapshot = List.of();
    final Map<String, JButton> toolbarButtons = new LinkedHashMap<>();
    volatile JPanel levelPanel;
    volatile JButton infoButton;
    volatile JButton warnButton;
    volatile JButton errorButton;
    volatile boolean showInfo = true;
    volatile boolean showWarn = true;
    volatile boolean showError = true;
    volatile JTree tree;
    volatile TreeModel treeModel;
    volatile FilteredTreeModel filteredTreeModel;
    volatile FilteredTreeModel pendingFilteredTreeModel;
    volatile Timer treeFilterTimer;
    volatile Object tableModel;
    volatile List<ParameterFilterRow> rows = List.of();
    ParameterFilterStamp parameterFilterStamp;
    final Map<JComponent, Boolean> originalRowVisibility = new java.util.IdentityHashMap<>();
    volatile String filterText = "";
    volatile boolean refreshScheduled;
    volatile String lastRawText = "";
    volatile String lastKeyword = "";
    volatile String lastFiltered = "";
    volatile DocumentListener sourceDocumentListener;

    PaletteFilterState(final PaletteFilterHostOperations.PaletteKind kind) {
        this.kind = Objects.requireNonNull(kind, "kind");
    }
}

enum LogLevel {
    INFO, WARN, ERROR
}

final class PaletteLogFilter {

    private PaletteLogFilter() {
    }

    static final String FILTERED_TEXT_PANE_KEY = LogPaletteHostStructure.FILTERED_TEXT_PANE_KEY;

    static final String WRAPPER_MARKER_KEY = LogPaletteHostStructure.FILTER_WRAPPER_MARKER_KEY;

    /** Publishes the current log filter to the framework service (pre-render interception). */
    static void publishLogFilter(final PaletteFilterHostOperations host, final PaletteFilterState state) {
        if (host.cubismLogService == null) {
            return;
        }
        host.cubismLogService.setFilter(new dev.turboism.sdk.runtime.CubismLogService.LogFilter(
            state.showInfo, state.showWarn, state.showError, state.filterText));
    }

    static final Color LOG_INFO_ON = new Color(60, 146, 72);

    static final Color LOG_WARN_ON = new Color(208, 165, 45);

    static final Color LOG_ERROR_ON = new Color(184, 64, 64);

    static final Color LOG_OFF = new Color(150, 150, 150);

    static void ensureLogToolbar(final PaletteFilterHostOperations host, 
        final PaletteFilterState state,
        final Container scrollShell,
        final PaletteFilterRegistry.PaletteFilterContribution contribution
    ) {
        if (state.toolbarPanel == null) {
            state.toolbarPanel = new JPanel(new BorderLayout(4, 0));
            state.toolbarPanel.setOpaque(false);
            state.toolbarPanel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        }
        if (contribution != null) {
            if (state.filterBox == null) {
                state.filterBox = PaletteToolbarSupport.createFilterBox(contribution.placeholderKey(), state.filterText, text -> {
                    state.filterText = PaletteFilterHostOperations.normalize(text);
                    refreshFilteredLogText(state);
                    publishLogFilter(host, state);
                });
            }
            if (state.filterBox.panel.getParent() != state.toolbarPanel) {
                state.toolbarPanel.add(state.filterBox.panel, BorderLayout.CENTER);
            }
            if (state.levelPanel == null) {
                state.levelPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
                state.levelPanel.setOpaque(false);
            }
            if (state.infoButton == null) {
                state.infoButton = createLogLevelButton("info", LOG_INFO_ON, () -> {
                    state.showInfo = !state.showInfo;
                    refreshLogLevelButtons(state);
                    refreshFilteredLogText(state);
                    publishLogFilter(host, state);
                });
                state.warnButton = createLogLevelButton("warn", LOG_WARN_ON, () -> {
                    state.showWarn = !state.showWarn;
                    refreshLogLevelButtons(state);
                    refreshFilteredLogText(state);
                    publishLogFilter(host, state);
                });
                state.errorButton = createLogLevelButton("error", LOG_ERROR_ON, () -> {
                    state.showError = !state.showError;
                    refreshLogLevelButtons(state);
                    refreshFilteredLogText(state);
                    publishLogFilter(host, state);
                });
                state.levelPanel.add(state.infoButton);
                state.levelPanel.add(state.warnButton);
                state.levelPanel.add(state.errorButton);
                refreshLogLevelButtons(state);
            }
            if (state.levelPanel.getParent() != state.toolbarPanel) {
                state.toolbarPanel.add(state.levelPanel, BorderLayout.EAST);
            }
        }
        if (state.wrapper != null && state.wrapper.getParent() == scrollShell.getParent()) return;
        final Container parent = scrollShell.getParent();
        if (parent == null) return;
        final JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.putClientProperty(WRAPPER_MARKER_KEY, Boolean.TRUE);
        final LayoutManager parentLayout = parent.getLayout();
        final Object constraint = parentLayout instanceof BorderLayout
            ? ((BorderLayout) parentLayout).getConstraints(scrollShell)
            : null;
        final int zOrder = parent.getComponentZOrder(scrollShell);
        parent.remove(scrollShell);
        wrapper.add(state.toolbarPanel, BorderLayout.NORTH);
        wrapper.add(scrollShell, BorderLayout.CENTER);
        if (constraint != null) parent.add(wrapper, constraint);
        else parent.add(wrapper, zOrder < 0 ? parent.getComponentCount() : Math.min(zOrder, parent.getComponentCount()));
        parent.revalidate();
        parent.repaint();
        state.wrapper = wrapper;
    }

    static JButton createLogLevelButton(final String text, final Color activeColor, final Runnable action) {
        final JButton button = new JButton(text);
        button.setFocusable(false);
        button.setOpaque(false);
        button.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setForeground(activeColor);
        button.addActionListener(event -> action.run());
        return button;
    }

    static void refreshLogLevelButtons(final PaletteFilterState state) {
        refreshLogLevelButton(state.infoButton, state.showInfo, LOG_INFO_ON);
        refreshLogLevelButton(state.warnButton, state.showWarn, LOG_WARN_ON);
        refreshLogLevelButton(state.errorButton, state.showError, LOG_ERROR_ON);
    }

    static void refreshLogLevelButton(final JButton button, final boolean active, final Color activeColor) {
        if (button == null) {
            return;
        }
        button.setForeground(active ? activeColor : LOG_OFF);
        button.repaint();
    }

    /**
     * Installs a filtered Document on the native log text pane. The pane itself
     * (and its viewport/scrollbars) stay untouched; only the data written into
     * the document is filtered (pre-render filtering).
     */
    static void installFilteredDocument(final PaletteFilterState state) {
        if (state.sourceDoc == null || state.sourceTextPane == null) {
            return;
        }
        if (state.sourceDocumentListener == null) {
            state.sourceDocumentListener = new DocumentListener() {
                @Override public void insertUpdate(final DocumentEvent event) { scheduleLogRefresh(state); }
                @Override public void removeUpdate(final DocumentEvent event) { scheduleLogRefresh(state); }
                @Override public void changedUpdate(final DocumentEvent event) { scheduleLogRefresh(state); }
            };
            state.sourceDoc.addDocumentListener(state.sourceDocumentListener);
        }
        if (state.filteredDoc == null) {
            state.filteredDoc = new javax.swing.text.DefaultStyledDocument();
        }
        state.sourceTextPane.setDocument(state.filteredDoc);
        state.lastRawText = "";
        refreshFilteredLogText(state);
    }

    static void scheduleLogRefresh(final PaletteFilterState state) {
        if (state.refreshScheduled) {
            return;
        }
        state.refreshScheduled = true;
        PaletteFilterHostOperations.onEdt(() -> {
            state.refreshScheduled = false;
            refreshFilteredLogText(state);
        });
    }

    /** Returns true when the user is reading the tail of the filtered log (follow-tail mode). */
    static boolean isAtTail(final JTextPane pane) {
        if (pane == null) {
            return false;
        }
        final int length = pane.getDocument().getLength();
        if (length <= 0) {
            return true;
        }
        return pane.getCaretPosition() >= length - 80;
    }

    static void refreshFilteredLogText(final PaletteFilterState state) {
        final JTextPane pane = state.sourceTextPane;
        if (pane == null || state.sourceDoc == null) {
            return;
        }
        final String raw;
        try {
            raw = state.sourceDoc.getText(0, state.sourceDoc.getLength());
        } catch (javax.swing.text.BadLocationException impossible) {
            return;
        }
        final String filteredText = filterLogText(raw, state.filterText, state.showInfo, state.showWarn, state.showError);
        if (state.lastRawText.equals(raw)
            && state.lastKeyword.equals(state.filterText)
            && state.lastFiltered.equals(filteredText)) {
            return;
        }
        state.lastRawText = raw;
        state.lastKeyword = state.filterText;
        state.lastFiltered = filteredText;
        final boolean tail = isAtTail(pane);
        final javax.swing.text.StyleContext context = new javax.swing.text.StyleContext();
        state.filteredDoc = new javax.swing.text.DefaultStyledDocument(context);
        try {
            state.filteredDoc.insertString(0, filteredText, null);
        } catch (javax.swing.text.BadLocationException ignored) {
        }
        pane.setDocument(state.filteredDoc);
        if (tail) {
            pane.setCaretPosition(state.filteredDoc.getLength());
        }
    }

    /** Pure log-line filter ported from the legacy log palette installer (keyword + level). */
    static String filterLogText(
        final String rawText,
        final String keyword,
        final boolean showInfo,
        final boolean showWarn,
        final boolean showError
    ) {
        if (rawText == null || rawText.isEmpty()) {
            return "";
        }
        final String[] lines = rawText.split("\\R", -1);
        final StringBuilder builder = new StringBuilder(rawText.length());
        LogLevel currentLevel = LogLevel.INFO;
        final String normalizedKeyword = PaletteFilterHostOperations.normalize(keyword);
        for (String line : lines) {
            final LogLevel explicitLevel = detectExplicitLogLevel(line);
            if (explicitLevel != null) {
                currentLevel = explicitLevel;
            }
            final boolean levelVisible = (currentLevel == LogLevel.INFO && showInfo)
                || (currentLevel == LogLevel.WARN && showWarn)
                || (currentLevel == LogLevel.ERROR && showError);
            final boolean keywordVisible = normalizedKeyword.isEmpty()
                || PaletteFilterHostOperations.normalize(line).contains(normalizedKeyword);
            if (levelVisible && keywordVisible) {
                if (builder.length() > 0) {
                    builder.append(System.lineSeparator());
                }
                builder.append(line);
            }
        }
        return builder.toString();
    }

    static LogLevel detectExplicitLogLevel(final String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        if (line.contains("ERROR") || line.contains("FATAL") || line.contains("[e")) {
            return LogLevel.ERROR;
        }
        if (line.contains("WARN") || line.contains("WARNING") || line.contains("[w")) {
            return LogLevel.WARN;
        }
        if (line.contains("INFO") || line.contains("DEBUG") || line.contains("TRACE") || line.contains("[i")) {
            return LogLevel.INFO;
        }
        return null;
    }
}
