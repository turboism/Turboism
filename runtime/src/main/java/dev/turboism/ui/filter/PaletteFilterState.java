package dev.turboism.ui.filter;

import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;

/** Per-palette live binding state for the filter host operations. */
record ParameterFilterStamp(
        List<ParameterFilterRow> rows,
        String keyword,
        Map<JComponent, Boolean> visibility,
        Map<Component, Container> parents) {
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
