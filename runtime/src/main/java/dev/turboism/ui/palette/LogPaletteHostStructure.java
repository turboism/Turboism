package dev.turboism.ui.palette;

import javax.swing.JComponent;
import javax.swing.JTextPane;
import javax.swing.JViewport;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.LayoutManager;
import java.awt.Window;
import java.util.Objects;
import java.util.Set;

/** Shared runtime-internal structure operations for the Cubism Log palette. */
public final class LogPaletteHostStructure {

    public static final String FILTERED_TEXT_PANE_KEY = "turboism.paletteFilter.filteredTextPane";
    public static final String FILTER_WRAPPER_MARKER_KEY = "turboism.paletteFilter.wrapper";

    private LogPaletteHostStructure() {
    }

    /** Finds the non-editable Log text pane in the visible Cubism main frame. */
    public static JTextPane findLogTextPane() {
        for (Window window : Window.getWindows()) {
            if (!window.isVisible()
                || !window.getClass().getName().startsWith("com.live2d.ui.window.CFrame")) {
                continue;
            }
            final JTextPane pane = findLogTextPane(window);
            if (pane != null) {
                return pane;
            }
        }
        return null;
    }

    /** Finds a host-shaped Log text pane below one component. */
    public static JTextPane findLogTextPane(final Component component) {
        if (component instanceof JTextPane pane
            && !pane.isEditable()
            && !Boolean.TRUE.equals(pane.getClientProperty(FILTERED_TEXT_PANE_KEY))
            && pane.isDisplayable()
            && findAncestorViewport(pane) != null) {
            return pane;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                final JTextPane found = findLogTextPane(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Finds the closest viewport above a component. */
    public static JViewport findAncestorViewport(final Component component) {
        Component current = component == null ? null : component.getParent();
        while (current != null) {
            if (current instanceof JViewport viewport) {
                return viewport;
            }
            current = current.getParent();
        }
        return null;
    }

    /** Walks from the scroll shell to the outermost wrapper with one of the supplied markers. */
    public static Container outermostMarkedWrapper(
        final Container scrollShell,
        final Set<String> markerKeys
    ) {
        Objects.requireNonNull(scrollShell, "scrollShell");
        Objects.requireNonNull(markerKeys, "markerKeys");
        Container top = scrollShell;
        while (top.getParent() instanceof JComponent parent && hasMarker(parent, markerKeys)) {
            top = parent;
        }
        return top;
    }

    /** Returns the BorderLayout center component, or {@code fallback} when none is present. */
    public static Component centerComponent(final Container container, final Component fallback) {
        if (container.getLayout() instanceof BorderLayout borderLayout) {
            final Component center = borderLayout.getLayoutComponent(BorderLayout.CENTER);
            if (center != null) {
                return center;
            }
        }
        return fallback;
    }

    /** Replaces one child while preserving BorderLayout constraint and component z-order. */
    public static void replaceComponent(
        final Container parent,
        final Component component,
        final Component replacement
    ) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(replacement, "replacement");
        final LayoutManager layout = parent.getLayout();
        final Object constraint = layout instanceof BorderLayout borderLayout
            ? borderLayout.getConstraints(component)
            : null;
        final int index = parent.getComponentZOrder(component);
        parent.remove(component);
        if (constraint != null) {
            parent.add(replacement, constraint);
        } else {
            final int safeIndex = index < 0
                ? parent.getComponentCount()
                : Math.min(index, parent.getComponentCount());
            parent.add(replacement, safeIndex);
        }
        parent.revalidate();
        parent.repaint();
    }

    private static boolean hasMarker(final JComponent component, final Set<String> markerKeys) {
        for (String markerKey : markerKeys) {
            if (Boolean.TRUE.equals(component.getClientProperty(markerKey))) {
                return true;
            }
        }
        return false;
    }
}
