package dev.turboism.ui.palette;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import java.awt.BorderLayout;
import java.awt.Container;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class LogPaletteHostStructureTest {

    @Test
    void discoversHostShapedLogPaneAndRejectsFilteredMirror() {
        final JPanel root = new JPanel(new BorderLayout());
        final JTextPane pane = displayablePane();
        pane.setEditable(false);
        root.add(new JScrollPane(pane), BorderLayout.CENTER);

        assertSame(pane, LogPaletteHostStructure.findLogTextPane(root));
        pane.putClientProperty(LogPaletteHostStructure.FILTERED_TEXT_PANE_KEY, Boolean.TRUE);
        assertNull(LogPaletteHostStructure.findLogTextPane(root));
    }

    @Test
    void walksMarkedWrappersAndReplacesComponentsWithoutChangingConstraint() {
        final JPanel parent = new JPanel(new BorderLayout());
        final JScrollPane scroll = new JScrollPane(new JTextPane());
        final JPanel filterWrapper = wrapper(LogPaletteHostStructure.FILTER_WRAPPER_MARKER_KEY, scroll);
        final JPanel toolbarWrapper = wrapper("toolbar.marker", filterWrapper);
        parent.add(toolbarWrapper, BorderLayout.CENTER);

        final Container top = LogPaletteHostStructure.outermostMarkedWrapper(
            scroll,
            Set.of(LogPaletteHostStructure.FILTER_WRAPPER_MARKER_KEY, "toolbar.marker")
        );
        assertSame(toolbarWrapper, top);

        LogPaletteHostStructure.replaceComponent(parent, toolbarWrapper, filterWrapper);
        assertSame(parent, filterWrapper.getParent());
        assertEquals(BorderLayout.CENTER, ((BorderLayout) parent.getLayout()).getConstraints(filterWrapper));
    }

    private static JPanel wrapper(final String marker, final Container child) {
        final JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.putClientProperty(marker, Boolean.TRUE);
        wrapper.add(child, BorderLayout.CENTER);
        return wrapper;
    }

    private static JTextPane displayablePane() {
        return new JTextPane() {
            @Override
            public boolean isDisplayable() {
                return true;
            }
        };
    }
}
