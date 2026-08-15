package dev.turboism.ui.filter;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.ui.filter.PaletteFilterRegistry;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;

import javax.swing.JTree;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assertions.fail;

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
        // The two accessors model the two exact-version node shapes: the 5.2.03 node exposes
        // {@code h()} and the 5.3.02 node exposes {@code i()}, each returning its own source.
        final DeformerSource hSource = new DeformerSource(
            new EditableId("Warp4"), "矩形变形器", "wrong-name-1", 1.0f
        );
        final DeformerSource iSource = new DeformerSource(
            new EditableId("ArtMesh16"), "矩形 16", "wrong-name-2", 2.0f
        );
        final ClassLoader loader = PaletteFilterHostOperationsTest.class.getClassLoader();

        // 5.2.03 profile: node→source accessor h() (the fixed routing; would yield the 5.3
        // text if the accessor were still hard-coded to i()).
        final PaletteFilterHostOperations host52 = hostWithResolver("5.2.0", loader);
        assertEquals("warp4 矩形变形器", host52.deformerNodeSearchText(new DeformerNode(hSource, iSource)));

        // 5.3.02 profile: node→source accessor i().
        final PaletteFilterHostOperations host53 = hostWithResolver("5.3.02", loader);
        assertEquals("artmesh16 矩形 16", host53.deformerNodeSearchText(new DeformerNode(hSource, iSource)));
    }

    @Test
    void nodeSourceProfileRoutesExactVersionsToPinnedAccessors() {
        // Runtime resolver spelling (EditorModelVerificationManifest52) and record spelling.
        assertEquals("h", DeformerNodeSourceProfile.forVersion("5.2.0").orElseThrow().accessorName());
        assertEquals("h", DeformerNodeSourceProfile.forVersion("5.2.03").orElseThrow().accessorName());
        assertEquals("i", DeformerNodeSourceProfile.forVersion("5.3.02").orElseThrow().accessorName());
        assertTrue(DeformerNodeSourceProfile.forVersion("5.4.0").isEmpty());
        assertTrue(DeformerNodeSourceProfile.forVersion("").isEmpty());
        assertTrue(DeformerNodeSourceProfile.forVersion(null).isEmpty());
        assertEquals("com.live2d.ui.treeTable.c", DeformerNodeSourceProfile.OWNER_BINARY_NAME);
        assertEquals("()Ljava/lang/Object;", DeformerNodeSourceProfile.ACCESSOR_DESCRIPTOR);
    }

    @Test
    void nonMatchingSourceTypeYieldsEmptySearchText() {
        // A node whose accessor returns an object that is not the verified source type: the
        // id/name chain cannot resolve (owner isInstance check) → per-node fail closed.
        final PaletteFilterHostOperations host = hostWithResolver(
            "5.3.02", PaletteFilterHostOperationsTest.class.getClassLoader());
        final javax.swing.tree.DefaultMutableTreeNode node = new javax.swing.tree.DefaultMutableTreeNode() {
            Object h() {
                return "not-a-source";
            }

            Object i() {
                return "not-a-source";
            }
        };
        assertEquals("", host.deformerNodeSearchText(node));
    }

    @Test
    void filteredDeformerTreeExpandsAncestorsToRevealMatchingDescendant() {
        final PaletteFilterHostOperations host = hostWithResolver(
            "5.3.02", PaletteFilterHostOperationsTest.class.getClassLoader());
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
            new PaletteFilterHostOperations.FilteredTreeModel(delegate, "artmesh16", host::deformerNodeSearchText);
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

    // ------------------------------------------------ debounced tree filtering

    /**
     * Regression (edt-perf-fix R2): a keystroke arriving through the debounce seam must apply the
     * LATEST text, never the initial keyword. Fails on the R1 implementation, where
     * {@code scheduleTreeFilter} ignored its text argument and the timer re-applied the stale
     * {@code state.filterText} (this seam was only masked end-to-end by an ensureFilterBox side
     * effect, which the deformer path must not depend on).
     */
    @Test
    void debouncedTreeFilterAppliesLatestKeystrokeNotInitialText() throws Exception {
        final PaletteFilterHostOperations host = hostWithResolver(
            "5.3.02", PaletteFilterHostOperationsTest.class.getClassLoader());
        final PaletteFilterHostOperations.PaletteFilterState state =
            new PaletteFilterHostOperations.PaletteFilterState(PaletteFilterHostOperations.PaletteKind.DEFORMER);
        final javax.swing.tree.DefaultMutableTreeNode root = new javax.swing.tree.DefaultMutableTreeNode("root");
        root.add(new javax.swing.tree.DefaultMutableTreeNode("ParamAngleX"));
        final javax.swing.tree.DefaultTreeModel model = new javax.swing.tree.DefaultTreeModel(root);
        final JTree tree = onEdt(() -> new JTree(model));

        onEdt(() -> {
            state.tree = tree;
            state.treeModel = model;
            state.filterText = "initial"; // attach-time keyword
            host.scheduleTreeFilter(state, "angle"); // a keystroke lands with the new text
        });

        // Debounce timer → background prewarm → EDT model swap must apply "angle", not "initial".
        final java.util.concurrent.atomic.AtomicReference<PaletteFilterHostOperations.FilteredTreeModel> applied =
            new java.util.concurrent.atomic.AtomicReference<>();
        awaitUntil(() -> {
            final PaletteFilterHostOperations.FilteredTreeModel filtered = state.filteredTreeModel;
            if (filtered != null) {
                applied.set(filtered);
            }
            return filtered != null;
        });
        assertEquals("angle", applied.get().keyword(),
            "debounced filter must apply the latest keystroke, not the initial text");
    }

    /** End-to-end: real DocumentListener text change → debounce timer → filtered tree model keyword. */
    @Test
    void deformerPaletteTypingAppliesLatestKeywordEndToEnd() throws Exception {
        final DeformerPaletteFixture fixture = DeformerPaletteFixture.create();
        final PaletteFilterHostOperations host = new PaletteFilterHostOperations(
            kind -> kind == PaletteFilterHostOperations.PaletteKind.DEFORMER ? fixture.paletteRoot : null);
        host.bindParameterRowsResolver(editorModelResolver(
            "5.3.02", PaletteFilterHostOperationsTest.class.getClassLoader()));
        host.onPaletteFilterVisibilityChanged("probe", List.of(contribution("def", "DEFORMER")));
        SwingUtilities.invokeAndWait(() -> { });

        final JTextField field = findFilterField(fixture.paletteRoot);
        assertNotNull(field, "filter field must be installed");
        onEdt(() -> field.setText("warp4")); // keystroke
        awaitUntil(() -> {
            final javax.swing.tree.TreeModel current = onEdt(fixture.tree::getModel);
            return current instanceof PaletteFilterHostOperations.FilteredTreeModel filtered
                && "warp4".equals(filtered.keyword());
        });
    }

    @Test
    void unboundEditorModelResolverFailsClosedAndKeepsOriginalTreeModel() throws Exception {
        final DeformerPaletteFixture fixture = DeformerPaletteFixture.create();
        final PaletteFilterHostOperations host = new PaletteFilterHostOperations(
            kind -> kind == PaletteFilterHostOperations.PaletteKind.DEFORMER ? fixture.paletteRoot : null);
        // No bindParameterRowsResolver: deformer filtering must fail closed, never silently
        // collapse the tree with an empty search text for every node.
        host.onPaletteFilterVisibilityChanged("probe", List.of(contribution("def", "DEFORMER")));
        SwingUtilities.invokeAndWait(() -> { });

        assertNull(findFilterField(fixture.paletteRoot), "no filter box when deformer filtering fails closed");
        assertSame(fixture.treeModel, onEdt(fixture.tree::getModel), "original tree model must stay installed");
        final String status = host.attachStatus().get(PaletteFilterHostOperations.PaletteKind.DEFORMER);
        assertTrue(status.startsWith("tree-filter:node-source-unavailable resolver=unbound"), status);
    }

    @Test
    void unknownVersionFailsClosedAndKeepsOriginalTreeModel() throws Exception {
        final DeformerPaletteFixture fixture = DeformerPaletteFixture.create();
        final PaletteFilterHostOperations host = new PaletteFilterHostOperations(
            kind -> kind == PaletteFilterHostOperations.PaletteKind.DEFORMER ? fixture.paletteRoot : null);
        host.bindParameterRowsResolver(editorModelResolver(
            "5.4.0", PaletteFilterHostOperationsTest.class.getClassLoader()));
        host.onPaletteFilterVisibilityChanged("probe", List.of(contribution("def", "DEFORMER")));
        SwingUtilities.invokeAndWait(() -> { });

        assertNull(findFilterField(fixture.paletteRoot));
        assertSame(fixture.treeModel, onEdt(fixture.tree::getModel));
        final String status = host.attachStatus().get(PaletteFilterHostOperations.PaletteKind.DEFORMER);
        assertTrue(status.startsWith("tree-filter:node-source-unavailable version=5.4.0"), status);
    }

    @Test
    void unresolvableNodeSourceAccessorFailsClosedAndKeepsOriginalTreeModel() throws Exception {
        // A loader that cannot see the pinned treeTable/c owner class: the binding check must
        // fail closed instead of guessing a fallback accessor.
        final ClassLoader blindLoader =
            new java.net.URLClassLoader(new java.net.URL[0], ClassLoader.getPlatformClassLoader());
        final DeformerPaletteFixture fixture = DeformerPaletteFixture.create();
        final PaletteFilterHostOperations host = new PaletteFilterHostOperations(
            kind -> kind == PaletteFilterHostOperations.PaletteKind.DEFORMER ? fixture.paletteRoot : null);
        host.bindParameterRowsResolver(editorModelResolver("5.3.02", blindLoader));
        host.onPaletteFilterVisibilityChanged("probe", List.of(contribution("def", "DEFORMER")));
        SwingUtilities.invokeAndWait(() -> { });

        assertNull(findFilterField(fixture.paletteRoot));
        assertSame(fixture.treeModel, onEdt(fixture.tree::getModel));
        final String status = host.attachStatus().get(PaletteFilterHostOperations.PaletteKind.DEFORMER);
        assertTrue(status.startsWith("tree-filter:node-source-unavailable accessor="), status);
    }

    @Test
    void missingIdNameChainAliasFailsClosedAndKeepsOriginalTreeModel() throws Exception {
        final DeformerPaletteFixture fixture = DeformerPaletteFixture.create();
        final PaletteFilterHostOperations host = new PaletteFilterHostOperations(
            kind -> kind == PaletteFilterHostOperations.PaletteKind.DEFORMER ? fixture.paletteRoot : null);
        host.bindParameterRowsResolver(editorModelResolverWithoutLocalName(
            "5.3.02", PaletteFilterHostOperationsTest.class.getClassLoader()));
        host.onPaletteFilterVisibilityChanged("probe", List.of(contribution("def", "DEFORMER")));
        SwingUtilities.invokeAndWait(() -> { });

        assertNull(findFilterField(fixture.paletteRoot));
        assertSame(fixture.treeModel, onEdt(fixture.tree::getModel));
        final String status = host.attachStatus().get(PaletteFilterHostOperations.PaletteKind.DEFORMER);
        assertTrue(status.startsWith("tree-filter:node-source-unavailable alias="), status);
    }

    /** Polls while pumping the EDT (Swing timers and the prewarm apply run on it). */
    private static void awaitUntil(final java.util.function.BooleanSupplier condition) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            SwingUtilities.invokeAndWait(() -> { });
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        fail("condition not met within 5s");
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
        private final DeformerSource hSource;
        private final DeformerSource iSource;

        private DeformerNode(final DeformerSource source) {
            this(source, source);
        }

        private DeformerNode(final DeformerSource hSource, final DeformerSource iSource) {
            super(hSource);
            this.hSource = hSource;
            this.iSource = iSource;
        }

        /** 5.2.03 node→source accessor (pinned by the ui-control-appearance records). */
        Object h() {
            return hSource;
        }

        /** 5.3.02 node→source accessor (pinned by the ui-control-appearance records). */
        Object i() {
            return iSource;
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

        public EditableId getId() {
            return id;
        }

        public String getLocalName() {
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
        public String getIdString() {
            return value;
        }
    }

    // ------------------------------------------------------------- helpers


    /** Verified Editor-model id/name chain selectors shaped after the real fixture classes. */
    private static List<StaticSelector> idNameSelectors() {
        final String sourceOwner = "dev/turboism/ui/filter/PaletteFilterHostOperationsTest$DeformerSource";
        final String idOwner = "dev/turboism/ui/filter/PaletteFilterHostOperationsTest$EditableId";
        return List.of(
            StaticSelector.method("fixture", "cubism.editor-model.parameter-controllable-source.id",
                sourceOwner, "getId", "()L" + idOwner + ";", 0),
            StaticSelector.method("fixture", "cubism.editor-model.id.value",
                idOwner, "getIdString", "()Ljava/lang/String;", 0),
            StaticSelector.method("fixture", "cubism.editor-model.parameter-controllable-source.local-name",
                sourceOwner, "getLocalName", "()Ljava/lang/String;", 0)
        );
    }

    /** Test resolver for one exact Cubism version with the full id/name chain. */
    private static VerifiedMemberResolver editorModelResolver(
        final String version,
        final ClassLoader classLoader
    ) {
        return TestVerifiedResolvers.create(
            version,
            "adapter.editor-model.readwrite",
            Set.of("cubism.editor-model.read"),
            idNameSelectors(),
            classLoader
        );
    }

    /** Test resolver whose verified plan lacks the local-name alias (whole-chain fail closed). */
    private static VerifiedMemberResolver editorModelResolverWithoutLocalName(
        final String version,
        final ClassLoader classLoader
    ) {
        return TestVerifiedResolvers.create(
            version,
            "adapter.editor-model.readwrite",
            Set.of("cubism.editor-model.read"),
            idNameSelectors().subList(0, 2),
            classLoader
        );
    }

    /** Host with the exact-version Editor-model resolver bound. */
    private static PaletteFilterHostOperations hostWithResolver(
        final String version,
        final ClassLoader classLoader
    ) {
        final PaletteFilterHostOperations host = new PaletteFilterHostOperations();
        host.bindParameterRowsResolver(editorModelResolver(version, classLoader));
        return host;
    }

    /** Deformer palette attachment fixture: embedded tree-table seam, toolbar and palette root. */
    private record DeformerPaletteFixture(JPanel paletteRoot, JTree tree, javax.swing.tree.TreeModel treeModel) {

        static DeformerPaletteFixture create() {
            final javax.swing.tree.DefaultMutableTreeNode root =
                new javax.swing.tree.DefaultMutableTreeNode("root");
            final javax.swing.tree.DefaultMutableTreeNode folder =
                new javax.swing.tree.DefaultMutableTreeNode("Folder");
            root.add(folder);
            folder.add(new DeformerNode(new DeformerSource(
                new EditableId("Warp4"), "矩形变形器", "ignored", 4.0f)));
            final javax.swing.tree.DefaultTreeModel treeModel = new javax.swing.tree.DefaultTreeModel(root);
            final JTable table = new JTable();
            final JTree[] embedded = new JTree[1];
            onEdt(() -> {
                final JTree tree = new JTree(treeModel); // embedded tree-table seam for extractTree
                embedded[0] = tree;
                table.add(tree);
            });
            final JPanel parent = new JPanel(new BorderLayout());
            final JPanel toolbar = new JPanel();
            toolbar.add(new JButton("native"));
            parent.add(new JScrollPane(table), BorderLayout.CENTER);
            parent.add(toolbar, BorderLayout.NORTH);
            final JPanel paletteRoot = new JPanel(new BorderLayout());
            paletteRoot.add(parent, BorderLayout.CENTER);
            return new DeformerPaletteFixture(paletteRoot, embedded[0], treeModel);
        }
    }
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
