package dev.turboism.ui.workspace.layout;

import dev.turboism.mapping.verification.EmbeddedPanelVerificationManifest;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.ui.workspace.layout.DockComponent;
import dev.turboism.sdk.ui.workspace.layout.PaletteDock;
import dev.turboism.sdk.ui.workspace.layout.PaletteTab;
import dev.turboism.sdk.ui.workspace.layout.SplitDock;
import dev.turboism.sdk.ui.workspace.layout.WorkspaceLayoutSnapshot;
import dev.turboism.ui.panel.DockTreeTraversal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Version-neutral read-only workspace dock-layout provider.
 *
 * <p>The whole host chain (app-controller instance to workspace root component) is resolved
 * on every read over the already-verified embedded-panel aliases; any null link or failed
 * mapping yields a typed {@code UNAVAILABLE} snapshot instead of a partial or guessed tree.
 * The dock tree itself is built with the shared {@link DockTreeTraversal} kernel, which
 * attests {@code split.class} / {@code palette-box.class} before expanding and skips unknown
 * component types (for example CPMContentsBox).</p>
 */
public final class VerifiedWorkspaceLayoutHostProvider implements WorkspaceLayoutHostProvider {

    static final String APP_INSTANCE = "cubism.ui-panel.app-controller.instance";
    static final String APP_MAIN_FRAME = "cubism.ui-panel.app-controller.main-frame";
    static final String MAIN_FRAME_DOCK_MANAGER = "cubism.ui-panel.main-frame.dock-manager";
    static final String DOCK_PALETTE_MANAGER = "cubism.ui-panel.dock.palette-manager";
    static final String PALETTE_MANAGER_CURRENT_WORKSPACE =
        "cubism.ui-panel.palette-manager.current-workspace";
    static final String WORKSPACE_ROOT_CONTAINER = "cubism.ui-panel.workspace.root-container";
    static final String ROOT_COMPONENT = "cubism.ui-panel.root.component";
    static final String PALETTE_ID = "cubism.ui-panel.palette.id";

    static final Set<String> REQUIRED_ALIASES = Set.of(
        APP_INSTANCE,
        APP_MAIN_FRAME,
        MAIN_FRAME_DOCK_MANAGER,
        DOCK_PALETTE_MANAGER,
        PALETTE_MANAGER_CURRENT_WORKSPACE,
        WORKSPACE_ROOT_CONTAINER,
        ROOT_COMPONENT,
        PALETTE_ID,
        DockTreeTraversal.SPLIT_CLASS,
        DockTreeTraversal.SPLIT_CONTENTS,
        DockTreeTraversal.PALETTE_BOX_CLASS,
        DockTreeTraversal.PALETTE_BOX_PALETTES
    );

    private static final String MAPPING_FAILED = "workspace.layout.mapping.failed";

    private final VerifiedMemberResolver resolver;
    private final DockTreeTraversal traversal;

    public VerifiedWorkspaceLayoutHostProvider(final VerifiedMemberResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        if (!resolver.authorizesFeature(
            EmbeddedPanelVerificationManifest.ADAPTER_SLICE_ID,
            EmbeddedPanelVerificationManifest.CAPABILITY_ID,
            REQUIRED_ALIASES
        )) {
            throw new IllegalArgumentException(
                "resolver is not admitted for workspace layout reads"
            );
        }
        this.traversal = new DockTreeTraversal(resolver);
    }

    @Override
    public WorkspaceLayoutSnapshot readLayout() {
        try {
            final Object app = resolver.invokeStatic(APP_INSTANCE);
            if (app == null) {
                return mappingFailed();
            }
            final Object mainFrame = resolver.invoke(APP_MAIN_FRAME, app);
            if (mainFrame == null) {
                return mappingFailed();
            }
            final Object dockManager = resolver.invoke(MAIN_FRAME_DOCK_MANAGER, mainFrame);
            if (dockManager == null) {
                return mappingFailed();
            }
            final Object paletteManager = resolver.invoke(DOCK_PALETTE_MANAGER, dockManager);
            if (paletteManager == null) {
                return mappingFailed();
            }
            final Object workspace = resolver.invoke(
                PALETTE_MANAGER_CURRENT_WORKSPACE,
                paletteManager
            );
            if (workspace == null) {
                return mappingFailed();
            }
            final Object rootContainer = resolver.invoke(WORKSPACE_ROOT_CONTAINER, workspace);
            if (rootContainer == null) {
                return mappingFailed();
            }
            final Object rootComponent = resolver.invoke(ROOT_COMPONENT, rootContainer);
            if (rootComponent == null) {
                return mappingFailed();
            }
            return new WorkspaceLayoutSnapshot(
                WorkspaceLayoutSnapshot.Availability.AVAILABLE,
                buildTree(rootComponent),
                Optional.empty()
            );
        } catch (RuntimeException failure) {
            return mappingFailed();
        }
    }

    /**
     * Recursive snapshot construction over the shared traversal kernel: palette boxes are
     * leaves (ordered tabs), split containers keep child order, unknown components are
     * skipped, and a subtree without any dock component yields an empty branch.
     */
    private Optional<DockComponent> buildTree(final Object component) {
        if (component == null) {
            return Optional.empty();
        }
        if (traversal.isPaletteBox(component)) {
            return Optional.of(new PaletteDock(paletteTabs(component)));
        }
        if (!traversal.isSplitContainer(component)) {
            return Optional.empty();
        }
        final List<DockComponent> children = new ArrayList<>();
        for (Object child : traversal.splitContents(component)) {
            buildTree(child).ifPresent(children::add);
        }
        return Optional.of(new SplitDock(children));
    }

    private List<PaletteTab> paletteTabs(final Object paletteBox) {
        final Object rawPalettes = resolver.invoke(
            DockTreeTraversal.PALETTE_BOX_PALETTES,
            paletteBox
        );
        if (!(rawPalettes instanceof List<?> palettes)) {
            throw new IllegalStateException("Cubism palette box palettes are not a list");
        }
        final List<PaletteTab> tabs = new ArrayList<>(palettes.size());
        for (Object palette : palettes) {
            tabs.add(new PaletteTab(String.valueOf(resolver.invoke(PALETTE_ID, palette))));
        }
        return List.copyOf(tabs);
    }

    private static WorkspaceLayoutSnapshot mappingFailed() {
        return new WorkspaceLayoutSnapshot(
            WorkspaceLayoutSnapshot.Availability.UNAVAILABLE,
            Optional.empty(),
            Optional.of(MAPPING_FAILED)
        );
    }
}
