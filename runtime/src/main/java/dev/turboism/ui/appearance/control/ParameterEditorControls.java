package dev.turboism.ui.appearance.control;

import java.util.Objects;
import javax.swing.JComponent;

/** Exact native row, shared drag surface and numeric editor for one parameter axis. */
public record ParameterEditorControls(JComponent row, JComponent drag, JComponent value) {
    public ParameterEditorControls {
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(drag, "drag");
        Objects.requireNonNull(value, "value");
        if (!javax.swing.SwingUtilities.isDescendingFrom(drag, row)
                || !javax.swing.SwingUtilities.isDescendingFrom(value, row)) {
            throw new IllegalArgumentException("Editors must belong to the exact native row");
        }
    }
}
