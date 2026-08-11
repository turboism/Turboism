package dev.turboism.ui.panel;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockTreeTraversalTest {

    @Test
    void classifiesComponentsByExactAttestation() {
        DockTreeTraversal traversal = traversal();

        assertTrue(traversal.isPaletteBox(new PaletteBox(List.of())));
        assertFalse(traversal.isPaletteBox(new SplitContainer(List.of())));
        assertFalse(traversal.isPaletteBox(new ContentsBox()));
        assertFalse(traversal.isPaletteBox(null));

        assertTrue(traversal.isSplitContainer(new SplitContainer(List.of())));
        assertFalse(traversal.isSplitContainer(new PaletteBox(List.of())));
        assertFalse(traversal.isSplitContainer(new ContentsBox()));
        assertFalse(traversal.isSplitContainer(null));
    }

    @Test
    void walkVisitsPaletteBoxesInPreOrderAndSkipsUnknownComponents() {
        DockTreeTraversal traversal = traversal();
        // split(contents, split(boxA, boxB), boxC): only the boxes are visited, in
        // pre-order; the unknown contents component is skipped, never expanded.
        Object tree = new SplitContainer(List.of(
            new ContentsBox(),
            new SplitContainer(List.of(new PaletteBox(List.of()), new PaletteBox(List.of()))),
            new PaletteBox(List.of())
        ));

        List<Object> visited = new ArrayList<>();
        traversal.walkComponents(tree, visited::add);

        assertEquals(3, visited.size());
        assertTrue(traversal.isPaletteBox(visited.get(0)));
        assertTrue(traversal.isPaletteBox(visited.get(1)));
        assertTrue(traversal.isPaletteBox(visited.get(2)));
    }

    @Test
    void walkHandlesNullAndLeafRootsSafely() {
        DockTreeTraversal traversal = traversal();
        List<Object> visited = new ArrayList<>();

        traversal.walkComponents(null, visited::add);
        assertEquals(List.of(), visited);

        PaletteBox leaf = new PaletteBox(List.of());
        traversal.walkComponents(leaf, visited::add);
        assertEquals(List.of(leaf), visited);

        traversal.walkComponents(new ContentsBox(), visited::add);
        assertEquals(List.of(leaf), visited, "unknown roots are skipped, not expanded");
    }

    @Test
    void walkRejectsNullVisitor() {
        org.junit.jupiter.api.Assertions.assertThrows(
            NullPointerException.class,
            () -> traversal().walkComponents(new PaletteBox(List.of()), null)
        );
    }

    @Test
    void containsComponentFollowsIdentityAndSkipsUnknownBranches() {
        DockTreeTraversal traversal = traversal();
        PaletteBox box = new PaletteBox(List.of());
        PaletteBox detached = new PaletteBox(List.of());
        ContentsBox contents = new ContentsBox();
        Object tree = new SplitContainer(List.of(contents, new SplitContainer(List.of(box))));

        assertTrue(traversal.containsComponent(tree, box));
        assertTrue(traversal.containsComponent(box, box));
        assertFalse(traversal.containsComponent(tree, detached));
        assertFalse(traversal.containsComponent(contents, box),
            "unknown components are never expanded as containers");
        assertFalse(traversal.containsComponent(null, box));
    }

    @Test
    void pruneEmptyBoxesRemovesEmptyBoxesAndEmptyBranchesButKeepsLiveOnes() {
        DockTreeTraversal traversal = traversal();
        PaletteBox live = new PaletteBox(List.of(new Object()));
        PaletteBox nestedLive = new PaletteBox(List.of(new Object()));
        PaletteBox empty = new PaletteBox(List.of());
        SplitContainer emptyBranch = new SplitContainer(List.of(new PaletteBox(List.of())));
        SplitContainer liveBranch = new SplitContainer(List.of(nestedLive));
        SplitContainer root = new SplitContainer(List.of(live, empty, emptyBranch, liveBranch));

        traversal.pruneEmptyBoxes(root);

        assertEquals(List.of(live, liveBranch), root.contents());
        assertEquals(List.of(nestedLive), liveBranch.contents());
    }

    @Test
    void pruneEmptyBoxesLeavesNullBoxAndUnknownRootsUntouched() {
        DockTreeTraversal traversal = traversal();
        PaletteBox box = new PaletteBox(List.of());
        ContentsBox contents = new ContentsBox();

        traversal.pruneEmptyBoxes(null);
        traversal.pruneEmptyBoxes(box);
        traversal.pruneEmptyBoxes(contents);

        assertEquals(List.of(), box.palettes());
    }

    @Test
    void paletteTabCountReadsTheHostPaletteList() {
        DockTreeTraversal traversal = traversal();
        PaletteBox box = new PaletteBox(List.of(new Object(), new Object()));

        assertEquals(2, traversal.paletteTabCount(box));
    }

    private static DockTreeTraversal traversal() {
        return new DockTreeTraversal(resolver());
    }

    static VerifiedMemberResolver resolver() {
        final List<StaticSelector> selectors = List.of(
            StaticSelector.classSelector(
                "cubism.ui-panel.palette-box.class", internal(PaletteBox.class)
            ),
            StaticSelector.classSelector(
                "cubism.ui-panel.split.class", internal(SplitContainer.class)
            ),
            method(
                "cubism.ui-panel.split.contents",
                SplitContainer.class,
                "contents",
                "()Ljava/util/List;"
            ),
            method(
                "cubism.ui-panel.split.remove",
                SplitContainer.class,
                "remove",
                "(L" + internal(Component.class) + ";)V"
            ),
            method(
                "cubism.ui-panel.component.palette-count",
                Component.class,
                "paletteCount",
                "()I"
            ),
            method(
                "cubism.ui-panel.palette-box.palettes",
                PaletteBox.class,
                "palettes",
                "()Ljava/util/List;"
            )
        );
        return TestVerifiedResolvers.create(
            "adapter.editor-ui.embedded-panel",
            Set.of("cubism.editor-ui.embedded-panel"),
            selectors,
            DockTreeTraversalTest.class.getClassLoader()
        );
    }

    private static StaticSelector method(
        final String alias,
        final Class<?> owner,
        final String name,
        final String descriptor
    ) {
        return StaticSelector.method(
            alias,
            internal(owner),
            name,
            descriptor,
            StaticSelector.ACCESS_PUBLIC
        );
    }

    private static String internal(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    public interface Component {
        int paletteCount();
    }

    public static final class PaletteBox implements Component {
        private final List<Object> palettes;

        public PaletteBox(final List<Object> palettes) {
            this.palettes = new ArrayList<>(palettes);
        }

        public List<Object> palettes() {
            return palettes;
        }

        @Override
        public int paletteCount() {
            return palettes.size();
        }
    }

    public static final class SplitContainer implements Component {
        private final List<Component> contents;

        public SplitContainer(final List<? extends Component> contents) {
            this.contents = new ArrayList<>(contents);
        }

        public List<Component> contents() {
            return contents;
        }

        public void remove(final Component component) {
            contents.remove(component);
        }

        @Override
        public int paletteCount() {
            return contents.stream().mapToInt(Component::paletteCount).sum();
        }
    }

    /** Non-box, non-split workspace component (mirrors CPMContentsBox). */
    public static final class ContentsBox implements Component {
        @Override
        public int paletteCount() {
            return 0;
        }
    }
}
