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
import java.awt.Dimension;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    @Test
    void frameworkPlacementKeepsFilterLeftAndNativeToolbarRightAndRestoresHostToolbar() {
        onEdt(() -> {
            final JPanel parent = new JPanel(new BorderLayout());
            final JPanel nativeToolbar = new JPanel();
            nativeToolbar.setPreferredSize(new Dimension(120, 26));
            nativeToolbar.add(new JButton("native"));
            parent.add(nativeToolbar, BorderLayout.NORTH);
            final JPanel filter = new JPanel();
            filter.setPreferredSize(new Dimension(140, 26));

            final PaletteFilterHostOperations.ToolbarPlacement placement =
                PaletteFilterHostOperations.attachToolbarContribution(nativeToolbar, filter);
            parent.setSize(500, 30);
            parent.doLayout();
            nativeToolbar.getParent().doLayout();

            assertEquals(0, filter.getX());
            assertEquals(
                nativeToolbar.getParent().getWidth(),
                nativeToolbar.getX() + nativeToolbar.getWidth()
            );

            placement.detach();
            assertSame(parent, nativeToolbar.getParent());
            assertEquals(0, parent.getComponentZOrder(nativeToolbar));
            assertNull(filter.getParent());
        });
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
        final dev.turboism.sdk.runtime.CubismLogService logService =
            dev.turboism.sdk.runtime.CubismLogService.unavailable();
        host.bindCubismLogService(logService);
        host.onPaletteFilterVisibilityChanged("probe", List.of(contribution("log", "LOG")));
        SwingUtilities.invokeAndWait(() -> { });

        // Pre-render design: the native pane keeps rendering; its Document is filtered.
        assertEquals("INFO alpha\nWARN beta", source.getText());

        final JTextField field = findFilterField(paletteRoot);
        assertNotNull(field, "filter field must be installed");
        onEdt(() -> field.setText("alpha"));
        assertEquals("INFO alpha", source.getText());
        assertEquals("alpha", logService.filter().keyword());

        onEdt(() -> field.setText(""));
        assertEquals("INFO alpha\nWARN beta", source.getText());

        onEdt(() -> field.setText("beta"));
        host.onPaletteFilterVisibilityChanged("probe", List.of());
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(dev.turboism.sdk.runtime.CubismLogService.LogFilter.all(), logService.filter());
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
            new PaletteFilterHostOperations.FilteredTreeModel(delegate, "angle", String::valueOf);

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
            new PaletteFilterHostOperations.FilteredTreeModel(delegate, "", String::valueOf);
        assertEquals(2, filtered.getChildCount(root));
    }

    @Test
    void deformerSearchTextUsesOnlyVerifiedSourceEditableIdAndLocalName() {
        final DeformerSource source = new DeformerSource(
            new EditableId("Warp4"), "矩形变形器", "wrong-name-1", 1.0f
        );

        assertEquals(
            "warp4 矩形变形器",
            PaletteFilterHostOperations.deformerNodeSearchText(new DeformerNode(source))
        );
    }

    @Test
    void filteredDeformerTreeExpandsAncestorsToRevealMatchingDescendant() {
        final javax.swing.tree.DefaultMutableTreeNode root = new javax.swing.tree.DefaultMutableTreeNode("root");
        final DeformerNode parent = new DeformerNode(
            new DeformerSource(new EditableId("Warp4"), "父变形器", "ignored", 4.0f)
        );
        final DeformerNode child = new DeformerNode(
            new DeformerSource(new EditableId("ArtMesh16"), "矩形 16", "ignored", 16.0f)
        );
        root.add(parent);
        parent.add(child);
        final javax.swing.tree.DefaultTreeModel delegate = new javax.swing.tree.DefaultTreeModel(root);
        final PaletteFilterHostOperations.FilteredTreeModel filtered =
            new PaletteFilterHostOperations.FilteredTreeModel(
                delegate,
                "artmesh16",
                PaletteFilterHostOperations::deformerNodeSearchText
            );
        final javax.swing.JTree tree = onEdt(() -> new javax.swing.JTree(filtered));

        assertEquals(2, onEdt(tree::getRowCount));
        onEdt(() -> PaletteFilterHostOperations.expandFilteredTree(tree));
        assertEquals(3, onEdt(tree::getRowCount));
        assertEquals(child, onEdt(() -> tree.getPathForRow(2).getLastPathComponent()));

        filtered.setKeyword("矩形 16");
        assertEquals(1, filtered.getChildCount(root));
        assertEquals(child, filtered.getChild(parent, 0));

        filtered.setKeyword("wrongnode1");
        assertEquals(0, filtered.getChildCount(root));
    }

    @Test
    void parameterRowsMatchNameOrEditableIdAndKeepAncestorGroups() {
        final JPanel root = new JPanel();
        final JPanel folder = new JPanel();
        final JPanel child = new JPanel();
        final JPanel sibling = new JPanel();
        root.add(folder);
        folder.add(child);
        root.add(sibling);
        final List<PaletteFilterHostOperations.ParameterFilterRow> rows = List.of(
            new PaletteFilterHostOperations.ParameterFilterRow(folder, "facefolder face", true),
            new PaletteFilterHostOperations.ParameterFilterRow(child, "paramanglex angle x", false),
            new PaletteFilterHostOperations.ParameterFilterRow(sibling, "parammouth mouth", false)
        );
        final java.util.Map<JComponent, Boolean> original = new java.util.IdentityHashMap<>();
        rows.forEach(row -> original.put(row.component(), row.component().isVisible()));

        onEdt(() -> PaletteFilterHostOperations.applyParameterRows(rows, original, "paramanglex"));
        assertTrue(folder.isVisible());
        assertTrue(child.isVisible());
        assertFalse(sibling.isVisible());

        onEdt(() -> PaletteFilterHostOperations.applyParameterRows(rows, original, "mouth"));
        assertFalse(folder.isVisible());
        assertFalse(child.isVisible());
        assertTrue(sibling.isVisible());

        onEdt(() -> PaletteFilterHostOperations.applyParameterRows(rows, original, ""));
        assertTrue(folder.isVisible());
        assertTrue(child.isVisible());
        assertTrue(sibling.isVisible());
    }

    private static final class DeformerNode extends javax.swing.tree.DefaultMutableTreeNode {
        private final DeformerSource source;

        private DeformerNode(final DeformerSource source) {
            super(source);
            this.source = source;
        }

        Object i() {
            return source;
        }

        EditableId getId() {
            return new EditableId("WrongNode1");
        }

        String getLocalName() {
            return "错误节点名称 1";
        }

        @Override public Object getUserObject() {
            return new DeformerSource(new EditableId("WrongUser1"), "错误用户物体 1", "ignored", 1.0f);
        }

        Object getSource() {
            return new DeformerSource(new EditableId("WrongSource1"), "错误来源 1", "ignored", 1.0f);
        }
    }

    private static final class DeformerSource {
        private final EditableId id;
        private final String localName;
        private final String wrongName;
        private final float wrongValue;

        private DeformerSource(
            final EditableId id,
            final String localName,
            final String wrongName,
            final float wrongValue
        ) {
            this.id = id;
            this.localName = localName;
            this.wrongName = wrongName;
            this.wrongValue = wrongValue;
        }

        EditableId getId() {
            return id;
        }

        String getLocalName() {
            return localName;
        }

        String getName() {
            return wrongName;
        }

        float getValue() {
            return wrongValue;
        }
    }

    private record EditableId(String value) {
        String getIdString() {
            return value;
        }
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
