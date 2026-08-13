package dev.turboism.ui.workspace.layout;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.ui.workspace.layout.DockComponent;
import dev.turboism.sdk.ui.workspace.layout.PaletteDock;
import dev.turboism.sdk.ui.workspace.layout.PaletteTab;
import dev.turboism.sdk.ui.workspace.layout.SplitDock;
import dev.turboism.sdk.ui.workspace.layout.WorkspaceLayoutSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifiedWorkspaceLayoutHostProviderTest {

    @Test
    void nestedSplitsProduceOrderedDockTreeWithContentsBoxesSkipped() {
        App.app = new App(new MainFrame(new DockManager(new PaletteManager(new Workspace(
            new RootContainer(new SplitContainer(List.of(
                new ContentsBox(),
                new SplitContainer(List.of(
                    new PaletteBox(List.of(palette("tab-a1"), palette("tab-a2"))),
                    new PaletteBox(List.of(palette("tab-b1")))
                )),
                new PaletteBox(List.of(palette("tab-c1")))
            )))
        )))));
        WorkspaceLayoutHostProvider provider = provider();

        WorkspaceLayoutSnapshot snapshot = provider.readLayout();

        assertEquals(WorkspaceLayoutSnapshot.Availability.AVAILABLE, snapshot.availability());
        assertEquals(Optional.empty(), snapshot.diagnosticCode());
        DockComponent root = snapshot.root().orElseThrow();
        assertTrue(root instanceof SplitDock);
        List<DockComponent> children = ((SplitDock) root).children();
        assertEquals(2, children.size());
        SplitDock nested = (SplitDock) children.get(0);
        assertEquals(2, nested.children().size());
        assertEquals(List.of("tab-a1", "tab-a2"), tabs(nested.children().get(0)));
        assertEquals(List.of("tab-b1"), tabs(nested.children().get(1)));
        assertEquals(List.of("tab-c1"), tabs(children.get(1)));
    }

    @Test
    void rootDirectlyHoldingAPaletteBoxProducesAPaletteDockRoot() {
        App.app = new App(new MainFrame(new DockManager(new PaletteManager(new Workspace(
            new RootContainer(new PaletteBox(List.of(palette("tab-a1"))))
        )))));
        WorkspaceLayoutHostProvider provider = provider();

        WorkspaceLayoutSnapshot snapshot = provider.readLayout();

        assertEquals(WorkspaceLayoutSnapshot.Availability.AVAILABLE, snapshot.availability());
        assertTrue(snapshot.root().orElseThrow() instanceof PaletteDock);
        assertEquals(List.of("tab-a1"), tabs(snapshot.root().orElseThrow()));
    }

    @Test
    void emptyPaletteBoxIsReportedFaithfullyAndTabOrderIsPreserved() {
        App.app = new App(new MainFrame(new DockManager(new PaletteManager(new Workspace(
            new RootContainer(new SplitContainer(List.of(
                new PaletteBox(List.of()),
                new PaletteBox(List.of(palette("first"), palette("second")))
            )))
        )))));
        WorkspaceLayoutHostProvider provider = provider();

        WorkspaceLayoutSnapshot snapshot = provider.readLayout();

        SplitDock root = (SplitDock) snapshot.root().orElseThrow();
        assertEquals(List.of(), tabs(root.children().get(0)), "empty host boxes stay visible");
        assertEquals(List.of("first", "second"), tabs(root.children().get(1)));
    }

    @Test
    void paletteIdsAreStringifiedExactlyLikePanelTabSelection() {
        App.app = new App(new MainFrame(new DockManager(new PaletteManager(new Workspace(
            new RootContainer(new PaletteBox(List.of(palette("turboism:com.example.plugin:myPanel"))))
        )))));
        WorkspaceLayoutHostProvider provider = provider();

        PaletteDock dock = (PaletteDock) provider.readLayout().root().orElseThrow();

        assertEquals(List.of(new PaletteTab("turboism:com.example.plugin:myPanel")), dock.tabs());
    }

    @Test
    void contentsBoxOnlyWorkspaceIsAvailableWithAnEmptyRoot() {
        App.app = new App(new MainFrame(new DockManager(new PaletteManager(new Workspace(
            new RootContainer(new ContentsBox())
        )))));
        WorkspaceLayoutHostProvider provider = provider();

        WorkspaceLayoutSnapshot snapshot = provider.readLayout();

        assertEquals(WorkspaceLayoutSnapshot.Availability.AVAILABLE, snapshot.availability());
        assertEquals(Optional.empty(), snapshot.root(),
            "a canvas-only workspace has no dock component; absence is not failure");
    }

    @Test
    void snapshotsAreImmutable() {
        App.app = new App(new MainFrame(new DockManager(new PaletteManager(new Workspace(
            new RootContainer(new SplitContainer(List.of(new PaletteBox(List.of(palette("tab-a1"))))))
        )))));
        WorkspaceLayoutHostProvider provider = provider();

        SplitDock root = (SplitDock) provider.readLayout().root().orElseThrow();
        PaletteDock dock = (PaletteDock) root.children().get(0);
        assertThrows(UnsupportedOperationException.class,
            () -> dock.tabs().add(new PaletteTab("extra")));
        assertThrows(UnsupportedOperationException.class,
            () -> root.children().add(new PaletteDock(List.of())));
    }

    @Test
    void anyBrokenHostChainLinkFailsClosedWithMappingDiagnostic() {
        App.app = new App(new MainFrame(new DockManager(new PaletteManager(new Workspace(
            new RootContainer(null)
        )))));
        WorkspaceLayoutHostProvider provider = provider();

        WorkspaceLayoutSnapshot snapshot = provider.readLayout();

        assertEquals(WorkspaceLayoutSnapshot.Availability.UNAVAILABLE, snapshot.availability());
        assertEquals(Optional.of("workspace.layout.mapping.failed"), snapshot.diagnosticCode());
        assertEquals(Optional.empty(), snapshot.root());
    }

    @Test
    void missingWorkspaceOrAppFailsClosedWithMappingDiagnostic() {
        App.app = new App(new MainFrame(new DockManager(new PaletteManager(null))));
        WorkspaceLayoutHostProvider provider = provider();

        WorkspaceLayoutSnapshot snapshot = provider.readLayout();

        assertEquals(WorkspaceLayoutSnapshot.Availability.UNAVAILABLE, snapshot.availability());
        assertEquals(Optional.of("workspace.layout.mapping.failed"), snapshot.diagnosticCode());

        App.app = null;
        assertEquals(
            WorkspaceLayoutSnapshot.Availability.UNAVAILABLE,
            provider.readLayout().availability()
        );
    }

    @Test
    void aThrowingHostReadFailsClosedWithMappingDiagnostic() {
        App.app = new App(new MainFrame(new DockManager(new PaletteManager(new Workspace(
            new RootContainer(new FailingSplit())
        )))));
        WorkspaceLayoutHostProvider provider = provider();

        WorkspaceLayoutSnapshot snapshot = provider.readLayout();

        assertEquals(WorkspaceLayoutSnapshot.Availability.UNAVAILABLE, snapshot.availability());
        assertEquals(Optional.of("workspace.layout.mapping.failed"), snapshot.diagnosticCode());
    }

    @Test
    void constructorRejectsAResolverWithoutTheLayoutAliases() {
        VerifiedMemberResolver partial = TestVerifiedResolvers.create(
            "adapter.editor-ui.embedded-panel",
            Set.of("cubism.editor-ui.embedded-panel"),
            List.of(StaticSelector.classSelector(
                "cubism.ui-panel.palette-box.class", PaletteBox.class.getName().replace('.', '/')
            )),
            VerifiedWorkspaceLayoutHostProviderTest.class.getClassLoader()
        );

        assertThrows(IllegalArgumentException.class, () -> new VerifiedWorkspaceLayoutHostProvider(partial));
    }

    private static VerifiedWorkspaceLayoutHostProvider provider() {
        return new VerifiedWorkspaceLayoutHostProvider(resolver());
    }

    private static VerifiedMemberResolver resolver() {
        final List<StaticSelector> selectors = List.of(
            StaticSelector.staticMethod(
                "cubism.ui-panel.app-controller.instance",
                internal(App.class),
                "app",
                "()L" + internal(App.class) + ";",
                StaticSelector.ACCESS_PUBLIC
            ),
            method("cubism.ui-panel.app-controller.main-frame", App.class, "mainFrame", descriptor(MainFrame.class)),
            method("cubism.ui-panel.main-frame.dock-manager", MainFrame.class, "dockManager", descriptor(DockManager.class)),
            method("cubism.ui-panel.dock.palette-manager", DockManager.class, "paletteManager", descriptor(PaletteManager.class)),
            method("cubism.ui-panel.palette-manager.current-workspace", PaletteManager.class, "currentWorkspace", descriptor(Workspace.class)),
            method("cubism.ui-panel.workspace.root-container", Workspace.class, "rootContainer", descriptor(RootContainer.class)),
            method("cubism.ui-panel.root.component", RootContainer.class, "component", descriptor(Component.class)),
            StaticSelector.classSelector("cubism.ui-panel.split.class", internal(SplitContainer.class)),
            StaticSelector.classSelector("cubism.ui-panel.palette-box.class", internal(PaletteBox.class)),
            method("cubism.ui-panel.split.contents", SplitContainer.class, "contents", "()Ljava/util/List;"),
            method("cubism.ui-panel.palette-box.palettes", PaletteBox.class, "palettes", "()Ljava/util/List;"),
            method("cubism.ui-panel.palette.id", Palette.class, "id", descriptor(PaletteId.class))
        );
        return TestVerifiedResolvers.create(
            "adapter.editor-ui.embedded-panel",
            Set.of("cubism.editor-ui.embedded-panel"),
            selectors,
            VerifiedWorkspaceLayoutHostProviderTest.class.getClassLoader()
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

    private static Palette palette(final String id) {
        return new Palette(new PaletteId(id));
    }

    private static List<String> tabs(final DockComponent component) {
        return ((PaletteDock) component).tabs().stream().map(PaletteTab::paletteId).toList();
    }

    private static String internal(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static String descriptor(final Class<?> type) {
        return "()L" + internal(type) + ";";
    }

    public interface Component {
    }

    public static final class PaletteBox implements Component {
        private final List<Palette> palettes;

        public PaletteBox(final List<Palette> palettes) {
            this.palettes = new ArrayList<>(palettes);
        }

        public List<Palette> palettes() {
            return palettes;
        }
    }

    public static class SplitContainer implements Component {
        private final List<Component> contents;

        public SplitContainer(final List<? extends Component> contents) {
            this.contents = new ArrayList<>(contents);
        }

        public List<Component> contents() {
            return contents;
        }
    }

    /** Non-box, non-split workspace component (mirrors CPMContentsBox). */
    public static final class ContentsBox implements Component {
    }

    /** Split whose contents read throws, mirroring a failed host read. */
    public static final class FailingSplit extends SplitContainer {
        public FailingSplit() {
            super(List.of());
        }

        @Override
        public List<Component> contents() {
            throw new IllegalStateException("host split read failed");
        }
    }

    public record RootContainer(Component component) {
    }

    public record Workspace(RootContainer rootContainer) {
    }

    public record Palette(PaletteId id) {
    }

    public record PaletteId(String value) {
        @Override
        public String toString() {
            return value;
        }
    }

    public static final class PaletteManager {
        private final Workspace currentWorkspace;

        public PaletteManager(final Workspace currentWorkspace) {
            this.currentWorkspace = currentWorkspace;
        }

        public Workspace currentWorkspace() {
            return currentWorkspace;
        }
    }

    public static final class DockManager {
        private final PaletteManager paletteManager;

        public DockManager(final PaletteManager paletteManager) {
            this.paletteManager = paletteManager;
        }

        public PaletteManager paletteManager() {
            return paletteManager;
        }
    }

    public static final class MainFrame {
        private final DockManager dockManager;

        public MainFrame(final DockManager dockManager) {
            this.dockManager = dockManager;
        }

        public DockManager dockManager() {
            return dockManager;
        }
    }

    public static final class App {
        private static App app;
        private final MainFrame mainFrame;

        public App(final MainFrame mainFrame) {
            this.mainFrame = mainFrame;
        }

        public static App app() {
            return app;
        }

        public MainFrame mainFrame() {
            return mainFrame;
        }
    }
}
