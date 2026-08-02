package dev.turboism.ui.filter;

import dev.turboism.sdk.ui.filter.PaletteFilterRegistry;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused tests for the palette filter host operations: pure logic and Swing attachment. */
class PaletteFilterHostOperationsTest {

    // ------------------------------------------------------------- filter box

    @Test
    void filterBoxRendersPlaceholderFieldAndClearButton() {
        final AtomicReference<String> lastText = new AtomicReference<>();
        final PaletteFilterHostOperations.FilterBox box = onEdt(() ->
            PaletteFilterHostOperations.createFilterBox("输入关键词过滤", "", lastText::set));

        assertNotNull(box.panel);
        assertNotNull(box.field);
        assertNotNull(box.clearButton);
        assertEquals("turboismPaletteFilterPanel", box.panel.getName());
        assertEquals("turboismPaletteFilterField", box.field.getName());
        assertEquals("turboismPaletteFilterClearButton", box.clearButton.getName());
        assertEquals("×", box.clearButton.getText());
    }

    @Test
    void filterBoxPropagatesTextChangesAndClearResetsField() throws Exception {
        final AtomicReference<String> lastText = new AtomicReference<>();
        final PaletteFilterHostOperations.FilterBox box = onEdt(() ->
            PaletteFilterHostOperations.createFilterBox("placeholder", "", lastText::set));

        onEdt(() -> box.field.setText("eye"));
        assertEquals("eye", lastText.get());

        onEdt(() -> box.clearButton.doClick());
        assertEquals("", box.field.getText());
        assertEquals("", lastText.get());
    }

    // --------------------------------------------------------- pure matching

    @Test
    void filterLogTextKeepsOnlyMatchingLinesAndPreservesOthersWhenKeywordEmpty() {
        final String raw = "INFO loaded\nWARN missing texture\nERROR failed";
        assertEquals(
            "WARN missing texture",
            PaletteFilterHostOperations.filterLogText(raw, "missing", true, true, true)
        );
        assertEquals(raw, PaletteFilterHostOperations.filterLogText(raw, "", true, true, true));
        assertEquals("", PaletteFilterHostOperations.filterLogText(null, "x", true, true, true));
        assertEquals("", PaletteFilterHostOperations.filterLogText("", "x", true, true, true));
        assertEquals("", PaletteFilterHostOperations.filterLogText(raw, "nonexistent", true, true, true));
    }

    @Test
    void filterLogTextHonoursLevelVisibility() {
        final String raw = "INFO loaded\nWARN missing texture\nERROR failed";
        assertEquals(
            "WARN missing texture",
            PaletteFilterHostOperations.filterLogText(raw, "", false, true, false)
        );
        assertEquals(
            "ERROR failed",
            PaletteFilterHostOperations.filterLogText(raw, "", false, false, true)
        );
        assertEquals(
            "",
            PaletteFilterHostOperations.filterLogText(raw, "", false, false, false)
        );
    }

    // ---------------------------------------------------------- log attach

    @Test
    void logPaletteAttachWrapsScrollPaneAndFiltersLines() throws Exception {
        final JTextPane source = new JTextPane();
        source.setEditable(false);
        source.setText("INFO alpha\nWARN beta");
        final JScrollPane scroll = new JScrollPane(source);
        final JPanel parent = new JPanel(new BorderLayout());
        parent.add(scroll, BorderLayout.CENTER);

        final JComponent paletteRoot = new JPanel(new BorderLayout());
        paletteRoot.add(parent, BorderLayout.CENTER);

        final PaletteFilterHostOperations host = new PaletteFilterHostOperations(
            kind -> kind == PaletteFilterHostOperations.PaletteKind.LOG
                ? paletteRoot
                : null
        );
        host.onPaletteFilterVisibilityChanged("probe", List.of(contribution("log", "LOG")));
        SwingUtilities.invokeAndWait(() -> { });

        // Pre-render design: the native pane keeps rendering; its Document is filtered.
        assertEquals("INFO alpha\nWARN beta", source.getText());

        final JTextField field = findFilterField(paletteRoot);
        assertNotNull(field, "filter field must be installed");
        onEdt(() -> field.setText("alpha"));
        assertEquals("INFO alpha", source.getText());

        onEdt(() -> field.setText(""));
        assertEquals("INFO alpha\nWARN beta", source.getText());
    }

    private static JTextField findFilterField(final Component root) {
        if (root instanceof JTextField field
            && "turboismPaletteFilterField".equals(field.getName())) {
            return field;
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                final JTextField found = findFilterField(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static JTextPane findFilteredPane(final Component root) {
        if (root instanceof JTextPane pane
            && Boolean.TRUE.equals(pane.getClientProperty("turboism.paletteFilter.filteredTextPane"))) {
            return pane;
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                final JTextPane found = findFilteredPane(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------- tree filtering

    @Test
    void filteredTreeModelKeepsMatchingNodesAndTheirAncestors() {
        final javax.swing.tree.DefaultMutableTreeNode root = new javax.swing.tree.DefaultMutableTreeNode("root");
        final javax.swing.tree.DefaultMutableTreeNode folderA = new javax.swing.tree.DefaultMutableTreeNode("Folder A");
        final javax.swing.tree.DefaultMutableTreeNode child1 = new javax.swing.tree.DefaultMutableTreeNode("ParamAngleX");
        final javax.swing.tree.DefaultMutableTreeNode child2 = new javax.swing.tree.DefaultMutableTreeNode("ParamLip");
        final javax.swing.tree.DefaultMutableTreeNode folderB = new javax.swing.tree.DefaultMutableTreeNode("Folder B");
        final javax.swing.tree.DefaultMutableTreeNode child3 = new javax.swing.tree.DefaultMutableTreeNode("ParamEye");
        root.add(folderA);
        folderA.add(child1);
        folderA.add(child2);
        root.add(folderB);
        folderB.add(child3);
        final javax.swing.tree.DefaultTreeModel delegate = new javax.swing.tree.DefaultTreeModel(root);

        final PaletteFilterHostOperations.FilteredTreeModel filtered =
            new PaletteFilterHostOperations.FilteredTreeModel(delegate, "angle");

        // root visible (always), folderA visible (descendant matches), child1 visible, child2 hidden
        assertEquals(1, filtered.getChildCount(root));
        assertEquals(folderA, filtered.getChild(root, 0));
        assertEquals(1, filtered.getChildCount(folderA));
        assertEquals(child1, filtered.getChild(folderA, 0));
    }

    @Test
    void filteredTreeModelEmptyKeywordShowsEverything() {
        final javax.swing.tree.DefaultMutableTreeNode root = new javax.swing.tree.DefaultMutableTreeNode("root");
        root.add(new javax.swing.tree.DefaultMutableTreeNode("Alpha"));
        root.add(new javax.swing.tree.DefaultMutableTreeNode("Beta"));
        final javax.swing.tree.DefaultTreeModel delegate = new javax.swing.tree.DefaultTreeModel(root);
        final PaletteFilterHostOperations.FilteredTreeModel filtered =
            new PaletteFilterHostOperations.FilteredTreeModel(delegate, "");
        assertEquals(2, filtered.getChildCount(root));
    }

    // ------------------------------------------------------------- helpers

    private static PaletteFilterRegistry.PaletteFilterContribution contribution(String id, String paletteId) {
        return new PaletteFilterRegistry.PaletteFilterContribution(id, paletteId, "placeholder", 10);
    }

    private static void onEdt(final Runnable runnable) {
        onEdt(() -> {
            runnable.run();
            return null;
        });
    }

    private static <T> T onEdt(final java.util.concurrent.Callable<T> callable) {
        final AtomicReference<T> result = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    result.set(callable.call());
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            });
        } catch (InterruptedException | InvocationTargetException interrupted) {
            throw new AssertionError(interrupted);
        }
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        return result.get();
    }
}
