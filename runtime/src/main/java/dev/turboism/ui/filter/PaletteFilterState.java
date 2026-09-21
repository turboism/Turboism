package dev.turboism.ui.filter;

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

import dev.turboism.ui.toolbar.PaletteToolbarHostOperations;

/** Per-palette live binding state for the filter host operations. */
record ParameterFilterStamp(
    List<ParameterFilterRow> rows,
    String keyword,
    Map<JComponent, Boolean> visibility,
    Map<Component, Container> parents
) {
    static ParameterFilterStamp capture(final List<ParameterFilterRow> rows, final String keyword) {
        final Map<JComponent, Boolean> visibility = new java.util.IdentityHashMap<>();
        final Map<Component, Container> parents = new java.util.IdentityHashMap<>();
        for (ParameterFilterRow row : rows) {
            visibility.put(row.component(), row.component().isVisible());
            Component component = row.component();
            while (component != null && !parents.containsKey(component)) {
                final Container parent = component.getParent();
                parents.put(component, parent);
                component = parent;
            }
        }
        return new ParameterFilterStamp(List.copyOf(rows), keyword, visibility, parents);
    }
}
