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

enum LogLevel {
    INFO, WARN, ERROR
}

final class PaletteToolbarSupport {

    private PaletteToolbarSupport() {
    }
}
