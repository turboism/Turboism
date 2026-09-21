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

/** Swing component and host-object finders for palette attach logic. */

final class PaletteComponentFinder {

    private PaletteComponentFinder() {
    }

    static final String PALETTE_PROPERTY = "dev.turboism.paletteFilter";

    static final String SCENE_PALETTE_PROPERTY = "dev.turboism.scenePalette";

    static boolean isInVisibleCubismWindow(final Component component) {
        final Window window = SwingUtilities.getWindowAncestor(component);
        return window != null
            && window.isVisible()
            && window.getClass().getName().startsWith("com.live2d.ui.window.CFrame");
    }

    static Object findPaletteRoot(
        final Component component,
        final PaletteFilterHostOperations.PaletteKind kind,
        final ClassLoader hostClassLoader
    ) {
        final String cacheKey = PALETTE_PROPERTY + "." + kind.name();
        final Object remembered = component instanceof JComponent
            ? ((JComponent) component).getClientProperty(cacheKey)
            : null;
        if (remembered instanceof java.lang.ref.WeakReference<?> reference && reference.get() != null) {
            return reference.get();
        }
        if (matchesPaletteRoot(component, kind)) {
            if (component instanceof JComponent jComponent) {
                jComponent.putClientProperty(cacheKey, new java.lang.ref.WeakReference<>(component));
            }
            return component;
        }
        if (component instanceof Container container && container.isVisible()) {
            for (Component child : container.getComponents()) {
                final Object root = findPaletteRoot(child, kind, hostClassLoader);
                if (root != null) {
                    return root;
                }
            }
        }
        return null;
    }

    static boolean matchesPaletteRoot(final Component component, final PaletteFilterHostOperations.PaletteKind kind) {
        final String name = component.getClass().getName();
        if (kind == PaletteFilterHostOperations.PaletteKind.DEFORMER) {
            return name.startsWith("com.live2d.cubism.view.palette.deformer.CDeformerTreeTable");
        }
        return name.contains(kind.classHint);
    }

    /** Finds the JTable remembered by the scene-table host (validated 5.3.02 property). */
    static JTable findRememberedSceneTable() {
        for (Window window : Window.getWindows()) {
            if (!window.isShowing()) continue;
            final JTable table = findRememberedSceneTable(window);
            if (table != null) {
                return table;
            }
        }
        return null;
    }

    static JTable findRememberedSceneTable(final Component component) {
        if (component instanceof JTable table) {
            final Object remembered = table.getClientProperty(SCENE_PALETTE_PROPERTY);
            if (remembered instanceof java.lang.ref.WeakReference<?> reference && reference.get() != null) {
                return table;
            }
            // Native scene row listener (exact 5.3.02 class) carrying the palette in field "a".
            for (java.awt.event.MouseListener listener : table.getMouseListeners()) {
                if (listener != null && listener.getClass().getName().equals(
                    "com.live2d.cubism.view.palette.scene.m")) {
                    return table;
                }
            }
        }
        if (component instanceof Container container && container.isVisible()) {
            for (Component child : container.getComponents()) {
                final JTable found = findRememberedSceneTable(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Finds a tree-table whose class name starts with the given 5.3.02 prefix. */
    static JTable findTreeTable(final String classNamePrefix) {
        for (Window window : Window.getWindows()) {
            if (!window.isShowing()) continue;
            final JTable table = findTreeTable(window, classNamePrefix);
            if (table != null) {
                return table;
            }
        }
        return null;
    }

    static JTable findTreeTable(final Component component, final String classNamePrefix) {
        if (component instanceof JTable table
            && table.getClass().getName().startsWith(classNamePrefix)
            && table.isDisplayable()
            && isInVisibleCubismWindow(table)) {
            return table;
        }
        if (component instanceof Container container && container.isVisible()) {
            for (Component child : container.getComponents()) {
                final JTable found = findTreeTable(child, classNamePrefix);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    static JComponent parameterRowsRoot(
        final dev.turboism.ui.appearance.control.PaletteAppearanceCoordinator source
    ) {
        for (dev.turboism.ui.appearance.control.PaletteAppearanceCoordinator.ParameterControlBinding binding
            : source.parameterControlBindings()) {
            final Component label = binding.label();
            if (!label.isDisplayable() || !isInVisibleCubismWindow(label)) {
                continue;
            }
            final JViewport viewport = LogPaletteHostStructure.findAncestorViewport(label);
            if (viewport != null && viewport.getView() instanceof JComponent root
                && findParameterToolbar(root) != null) {
                return root;
            }
        }
        return null;
    }

    static final String PARAM_ADD_COMMAND = "CMD_PARAMETER_PALETTE_ADD_NEW_PARAMETER";

    static final String PARAM_FOLDER_COMMAND = "CMD_PARAMETER_PALETTE_NEW_FOLDER";

    static final String PARAM_DELETE_COMMAND = "CMD_PARAMETER_PALETTE_DELETE_OBJECT";

    static boolean isParameterToolbar(final Component component) {
        if (!(component instanceof Container container)) {
            return false;
        }
        final List<AbstractButton> buttons = collectButtons(container, 2);
        if (buttons.size() != 3) {
            return false;
        }
        boolean hasAdd = false;
        boolean hasFolder = false;
        boolean hasDelete = false;
        for (AbstractButton button : buttons) {
            final String action = PaletteFilterHostOperations.normalize(button.getActionCommand());
            final String tooltip = PaletteFilterHostOperations.normalize(button.getToolTipText());
            final String text = PaletteFilterHostOperations.normalize(button.getText());
            // Exact legacy command first; fall back to multi-language labels.
            hasAdd |= action.equals(PaletteFilterHostOperations.normalize(PARAM_ADD_COMMAND))
                || action.contains("add_new_parameter") || action.contains("newparameter") || action.contains("createparameter")
                || tooltip.contains("创建新参数") || tooltip.contains("create parameter") || tooltip.contains("パラメータ作成")
                || text.contains("创建新参数");
            hasFolder |= action.equals(PaletteFilterHostOperations.normalize(PARAM_FOLDER_COMMAND))
                || action.contains("new_folder") || action.contains("newfolder") || action.contains("createfolder")
                || tooltip.contains("创建新文件夹") || tooltip.contains("create folder") || tooltip.contains("フォルダ作成")
                || text.contains("创建新文件夹");
            hasDelete |= action.equals(PaletteFilterHostOperations.normalize(PARAM_DELETE_COMMAND))
                || action.contains("delete") || action.contains("remove")
                || tooltip.contains("删除选定的元素") || tooltip.contains("delete selected") || tooltip.contains("削除")
                || text.contains("删除");
        }
        return hasAdd && hasFolder && hasDelete;
    }

    static List<AbstractButton> collectButtons(final Container root, final int depth) {
        final List<AbstractButton> buttons = new ArrayList<>();
        collectButtons(root, depth, buttons);
        return buttons;
    }

    static void collectButtons(final Component component, final int depth, final List<AbstractButton> buttons) {
        if (component == null || depth < 0) {
            return;
        }
        if (component instanceof AbstractButton button) {
            buttons.add(button);
            return;
        }
        if (component instanceof Container container && container.isVisible()) {
            for (Component child : container.getComponents()) {
                collectButtons(child, depth - 1, buttons);
            }
        }
    }

    static void fireTableChanged(final JTable table) {
        if (table.getModel() instanceof AbstractTableModel model) {
            model.fireTableDataChanged();
        }
        table.revalidate();
        table.repaint();
    }

    static void refreshTableModel(final JTable table) {
        if (table == null) {
            return;
        }
        if (table.getModel() instanceof AbstractTableModel model) {
            model.fireTableDataChanged();
        }
        table.revalidate();
        table.repaint();
        final Container parent = table.getParent();
        if (parent != null) {
            parent.repaint();
        }
    }

    /** Extracts the embedded JTree from a tree-table via reflective field scan (bounded, fail-closed). */
    static JTree extractTree(final JTable table) {
        for (Component child : table.getComponents()) {
            if (child instanceof JTree tree) {
                return tree;
            }
        }
        Class<?> type = table.getClass();
        int depth = 0;
        while (type != null && depth < 4) {
            for (Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    final Object value = field.get(table);
                    if (value instanceof JTree tree) {
                        return tree;
                    }
                    if (value instanceof JComponent component) {
                        final JTree nested = findTreeInComponent(component);
                        if (nested != null) {
                            return nested;
                        }
                    }
                } catch (ReflectiveOperationException | LinkageError ignored) {
                    // Try the next field.
                }
            }
            type = type.getSuperclass();
            depth++;
        }
        return null;
    }

    static JTree findTreeInComponent(final Component component) {
        if (component instanceof JTree tree) {
            return tree;
        }
        if (component instanceof Container container && container.isVisible()) {
            for (Component child : container.getComponents()) {
                final JTree found = findTreeInComponent(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    static Container findToolbarContainer(final JTable table) {
        final Component scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane.class, table);
        Component current = scrollPane == null ? table : scrollPane;
        while (current != null && current.getParent() != null) {
            final Container parent = current.getParent();
            for (Component child : parent.getComponents()) {
                if (child == current) {
                    continue;
                }
                if (child instanceof Container && containsToolbarButton((Container) child)) {
                    return (Container) child;
                }
            }
            current = parent;
        }
        return null;
    }

    static boolean containsToolbarButton(final Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof AbstractButton) {
                return true;
            }
            if (child instanceof Container && containsToolbarButton((Container) child)) {
                return true;
            }
        }
        return false;
    }

    static Container findParameterToolbar(final Component component) {
        // Walk up from the parameter viewport and inspect bounded sibling subtrees
        // for the exact three-button toolbar.
        Component current = component;
        int hops = 0;
        while (current != null && current.getParent() != null && hops < 8) {
            final Container parent = current.getParent();
            for (Component sibling : parent.getComponents()) {
                if (sibling != current) {
                    final Container toolbar = findParameterToolbarInSubtree(sibling, 3);
                    if (toolbar != null) {
                        return toolbar;
                    }
                }
            }
            current = parent;
            hops++;
        }
        return null;
    }

    static Container findParameterToolbarInSubtree(final Component component, final int depth) {
        if (component == null || depth < 0) {
            return null;
        }
        if (isParameterToolbar(component)) {
            return (Container) component;
        }
        if (component instanceof Container container && container.isVisible()) {
            for (Component child : container.getComponents()) {
                final Container found = findParameterToolbarInSubtree(child, depth - 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    static JTable findTable(final Component root) {
        if (root instanceof JTable table) {
            return table;
        }
        if (root instanceof Container container && container.isVisible()) {
            for (Component child : container.getComponents()) {
                final JTable table = findTable(child);
                if (table != null) {
                    return table;
                }
            }
        }
        return null;
    }

    static JTextPane findTextPane(final Component root) {
        if (root instanceof JTextPane pane && !pane.isEditable()) {
            return pane;
        }
        if (root instanceof Container container && container.isVisible()) {
            for (Component child : container.getComponents()) {
                final JTextPane pane = findTextPane(child);
                if (pane != null) {
                    return pane;
                }
            }
        }
        return null;
    }
}
