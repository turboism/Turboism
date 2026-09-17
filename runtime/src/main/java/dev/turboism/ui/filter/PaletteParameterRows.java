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

/** Parameter-palette row capture, filtering, and replay helpers. */
record ParameterFilterRow(JComponent component, String searchText, boolean folder) {
    ParameterFilterRow {
        component = Objects.requireNonNull(component, "component");
        searchText = PaletteFilterHostOperations.normalize(searchText);
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

final class PaletteParameterRows {

    private PaletteParameterRows() {
    }

    static final String APP_INSTANCE = "cubism.editor-model.app-controller.instance";

    static final String APP_MAIN_FRAME = "cubism.editor-model.app-controller.main-frame";

    static final String MAIN_FRAME_PARAMETER_PALETTE =
        "cubism.editor-model.main-frame.parameter-palette";

    static final String PARAMETER_PALETTE_VIEW = "cubism.editor-model.parameter-palette.view";

    static final String PARAMETER_VIEW_OPERATION =
        "cubism.editor-model.parameter-palette-view.operation";

    static final String PARAMETER_OPERATION_ROWS =
        "cubism.editor-model.parameter-operation.rows";

    /** Resolves the parameter viewport from exact row bindings, never from an unrelated JTree. */
    static JComponent findParameterRowsRoot(final PaletteFilterHostOperations host) {
        final dev.turboism.ui.appearance.control.PaletteAppearanceCoordinator source = host.parameterRows;
        if (source == null) {
            return null;
        }
        JComponent root = PaletteComponentFinder.parameterRowsRoot(source);
        if (root != null) {
            return root;
        }
        final long now = System.currentTimeMillis();
        if (now - host.lastParameterReplayMillis >= 1_000) {
            host.lastParameterReplayMillis = now;
            replayExistingParameterRows(host);
            root = PaletteComponentFinder.parameterRowsRoot(source);
        }
        return root;
    }

    static void replayExistingParameterRows(final PaletteFilterHostOperations host) {
        final VerifiedMemberResolver resolver = host.parameterRowsResolver;
        if (resolver == null) {
            return;
        }
        try {
            final Object app = resolver.invokeStatic(APP_INSTANCE);
            final Object mainFrame = app == null ? null : resolver.invoke(APP_MAIN_FRAME, app);
            final Object palette = mainFrame == null
                ? null : resolver.invoke(MAIN_FRAME_PARAMETER_PALETTE, mainFrame);
            final Object view = palette == null ? null : resolver.invoke(PARAMETER_PALETTE_VIEW, palette);
            final Object operation = view == null ? null : resolver.invoke(PARAMETER_VIEW_OPERATION, view);
            final Object rows = operation == null ? null : resolver.invoke(PARAMETER_OPERATION_ROWS, operation);
            if (rows instanceof Iterable<?> iterable) {
                dev.turboism.ui.appearance.control.NativeParameterAppearanceBridge.replayExistingRows(iterable);
            }
        } catch (RuntimeException ignored) {
            // Palette/document may not be ready yet; the bounded connector retries.
        }
    }

    static List<ParameterFilterRow> parameterFilterRows(final PaletteFilterHostOperations host, final JComponent root) {
        final dev.turboism.ui.appearance.control.PaletteAppearanceCoordinator source = host.parameterRows;
        if (source == null) {
            return List.of();
        }
        final Map<JComponent, StringBuilder> textByRow = new java.util.IdentityHashMap<>();
        final Map<JComponent, Boolean> folderByRow = new java.util.IdentityHashMap<>();
        final List<JComponent> order = new ArrayList<>();
        for (dev.turboism.ui.appearance.control.PaletteAppearanceCoordinator.ParameterControlBinding binding
            : source.parameterControlBindings()) {
            final Component label = binding.label();
            if (!SwingUtilities.isDescendingFrom(label, root)) {
                continue;
            }
            final JComponent row = parameterRowComponent(label, binding.folder());
            if (row == null) {
                continue;
            }
            final StringBuilder searchText = textByRow.computeIfAbsent(row, ignored -> {
                order.add(row);
                return new StringBuilder();
            });
            PaletteFilterHostOperations.appendToken(searchText, binding.id());
            if (label instanceof JLabel swingLabel) {
                PaletteFilterHostOperations.appendToken(searchText, swingLabel.getText());
            }
            folderByRow.merge(row, binding.folder(), Boolean::logicalOr);
        }
        final List<ParameterFilterRow> rows = new ArrayList<>(order.size());
        for (JComponent row : order) {
            rows.add(new ParameterFilterRow(
                row,
                textByRow.get(row).toString(),
                Boolean.TRUE.equals(folderByRow.get(row))
            ));
        }
        return List.copyOf(rows);
    }

    static JComponent parameterRowComponent(final Component label, final boolean folder) {
        final String expected = folder
            ? "com.live2d.ui.swingImpl.n"
            : "com.live2d.ui.swingImpl.p";
        Component current = label;
        while (current != null && !(current instanceof JViewport)) {
            if (current instanceof JComponent component && current.getClass().getName().equals(expected)) {
                return component;
            }
            current = current.getParent();
        }
        return null;
    }

    static void applyParameterFilter(final PaletteFilterState state, final String text) {
        state.filterText = PaletteFilterHostOperations.normalize(text);
        final ParameterFilterStamp input = ParameterFilterStamp.capture(state.rows, state.filterText);
        if (input.equals(state.parameterFilterStamp)) return;
        applyParameterRows(state.rows, state.originalRowVisibility, state.filterText);
        state.parameterFilterStamp = ParameterFilterStamp.capture(state.rows, state.filterText);
    }

    static void applyParameterRows(
        final List<ParameterFilterRow> rows,
        final Map<JComponent, Boolean> originalVisibility,
        final String text
    ) {
        final String keyword = PaletteFilterHostOperations.normalize(text);
        final Set<JComponent> visible = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        if (!keyword.isEmpty()) {
            final Set<JComponent> folders = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (ParameterFilterRow row : rows) {
                if (row.folder()) folders.add(row.component());
            }
            final Set<Component> visitedAncestors = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (ParameterFilterRow row : rows) {
                if (row.searchText().contains(keyword)) {
                    visible.add(row.component());
                    Component ancestor = row.component().getParent();
                    while (ancestor != null && visitedAncestors.add(ancestor)) {
                        if (folders.contains(ancestor)) visible.add((JComponent) ancestor);
                        ancestor = ancestor.getParent();
                    }
                }
            }
        }
        final Set<Container> dirty = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (ParameterFilterRow row : rows) {
            final boolean next = keyword.isEmpty()
                ? originalVisibility.getOrDefault(row.component(), true)
                : visible.contains(row.component());
            if (row.component().isVisible() != next) {
                row.component().setVisible(next);
                if (row.component().getParent() != null) {
                    dirty.add(row.component().getParent());
                }
            }
        }
        for (Container container : dirty) {
            container.revalidate();
            container.repaint();
        }
    }

    static void restoreParameterRows(final PaletteFilterState state) {
        state.parameterFilterStamp = null;
        for (Map.Entry<JComponent, Boolean> entry : state.originalRowVisibility.entrySet()) {
            entry.getKey().setVisible(entry.getValue());
            if (entry.getKey().getParent() != null) {
                entry.getKey().getParent().revalidate();
                entry.getKey().getParent().repaint();
            }
        }
    }

    static void restoreDiscardedParameterRows(
        final Map<JComponent, Boolean> originalVisibility,
        final Set<JComponent> live
    ) {
        final java.util.Iterator<Map.Entry<JComponent, Boolean>> iterator =
            originalVisibility.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<JComponent, Boolean> entry = iterator.next();
            if (live.contains(entry.getKey())) {
                continue;
            }
            entry.getKey().setVisible(entry.getValue());
            if (entry.getKey().getParent() != null) {
                entry.getKey().getParent().revalidate();
                entry.getKey().getParent().repaint();
            }
            iterator.remove();
        }
    }
}
