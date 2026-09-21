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

/** Scene-palette keyword filtering against the scene document list. */

final class PaletteSceneFilter {

    private PaletteSceneFilter() {
    }

    static void applySceneFilter(final PaletteFilterHostOperations host, final PaletteFilterState state, final String text) {
        // Scene filtering now belongs to the scene table host (single owner).
        // This path is retained only for evidence when no sink is bound.
        if (host.sceneFilterSink == null) {
            host.lastAttachStatus.put(state.kind, "scene-filter:no-sink");
        }
    }

    static void applySceneFilterUnsafe(final PaletteFilterHostOperations host, final PaletteFilterState state, final String text) {
        final JTable table = state.table;
        final Object palette = state.scenePalette;
        if (table == null || palette == null) {
            host.lastAttachStatus.put(state.kind, "scene-filter:no-binding");
            return;
        }
        final List<Object> documents = sceneDocs(palette);
        final List<Object> rows = tableData(palette);
        if (documents.isEmpty() && rows != null && !rows.isEmpty()) {
            host.lastAttachStatus.put(state.kind, "scene-filter:no-docs cells=" + rows.size());
            return;
        }
        final String keyword = PaletteFilterHostOperations.normalize(text);
        final List<Object> visibleDocs = new ArrayList<>();
        for (Object document : documents) {
            if (matchesSceneDocument(document, keyword)) {
                visibleDocs.add(document);
            }
        }
        final int totalDocs = documents.size();
        if (rows != null) {
            rewriteTableRows(rows, visibleDocs);
        }
        PaletteComponentFinder.fireTableChanged(table);
        final int cells = rows == null ? -1 : rows.size();
        host.lastAttachStatus.put(state.kind, "scene-filter keyword=" + keyword
            + " totalDocs=" + totalDocs + " visibleDocs=" + visibleDocs.size()
            + " cells=" + cells
            + " model=" + table.getModel().getClass().getName());
    }

    static boolean matchesSceneDocument(final Object document, final String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return true;
        }
        final Object source = PaletteFilterHostOperations.invoke(document, "getSceneSource");
        final Object movieInfo = source == null ? null : PaletteFilterHostOperations.invoke(source, "getMovieInfo");
        final String haystack = (PaletteFilterHostOperations.text(PaletteFilterHostOperations.invoke(source, "getSceneName")) + "\n"
            + PaletteFilterHostOperations.text(PaletteFilterHostOperations.invoke(movieInfo, "getDisplayDuration")) + "\n"
            + PaletteFilterHostOperations.text(PaletteFilterHostOperations.invoke(source, "getTag"))).toLowerCase(Locale.ROOT);
        return haystack.contains(keyword);
    }

    @SuppressWarnings("unchecked")
    static List<Object> sceneDocs(final Object palette) {
        final Object value = PaletteFilterHostOperations.invoke(PaletteFilterHostOperations.invoke(palette, "e"), "getSceneDocs");
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    static List<Object> tableData(final Object palette) {
        final Object value = PaletteFilterHostOperations.field(palette, "h");
        return value instanceof List<?> list ? (List<Object>) list : null;
    }

    /** Rewrites the scene display cells (name/duration/tag per document) matching the validated host path. */
    static void rewriteTableRows(final List<Object> rows, final List<Object> documents) {
        rows.clear();
        for (Object document : documents) {
            final Object source = PaletteFilterHostOperations.invoke(document, "getSceneSource");
            rows.add(PaletteFilterHostOperations.text(PaletteFilterHostOperations.invoke(source, "getSceneName")));
            final Object movieInfo = PaletteFilterHostOperations.invoke(source, "getMovieInfo");
            final Object duration = PaletteFilterHostOperations.invoke(movieInfo, "getDisplayDuration");
            rows.add(duration instanceof Number ? String.valueOf(((Number) duration).intValue()) : "0");
            rows.add(PaletteFilterHostOperations.text(PaletteFilterHostOperations.invoke(source, "getTag")));
        }
    }

    /** Reverses a Scene palette controller from its table (scene-table host property or native listener field "a"). */
    static Object reverseResolvePalette(final JTable table) {
        final Object remembered = table.getClientProperty(PaletteComponentFinder.SCENE_PALETTE_PROPERTY);
        if (remembered instanceof java.lang.ref.WeakReference<?> reference && reference.get() != null) {
            return reference.get();
        }
        for (java.awt.event.MouseListener listener : table.getMouseListeners()) {
            if (listener != null && listener.getClass().getName().equals(
                "com.live2d.cubism.view.palette.scene.m")) {
                final Object palette = PaletteFilterHostOperations.field(listener, "a");
                if (palette != null) {
                    return palette;
                }
            }
        }
        return null;
    }
}
