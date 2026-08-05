package dev.turboism.ui.panel;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.action.UiActionEvent;
import dev.turboism.sdk.ui.EmbeddedPanelId;
import dev.turboism.sdk.ui.PanelView;
import dev.turboism.sdk.ui.context.PanelTabSelection;

import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/** Exact-version Cubism embedded-panel operations restricted to verified aliases. */
public final class VerifiedEmbeddedPanelHostOperations implements EmbeddedPanelHostOperations {

    private static final String APP_INSTANCE = "cubism.ui-panel.app-controller.instance";
    private static final String APP_MAIN_FRAME = "cubism.ui-panel.app-controller.main-frame";
    private static final String APP_REPAINT = "cubism.ui-panel.app-controller.repaint";
    private static final String MAIN_FRAME_DOCK_MANAGER = "cubism.ui-panel.main-frame.dock-manager";
    private static final String DOCK_PALETTE_MANAGER = "cubism.ui-panel.dock.palette-manager";
    private static final String DOCK_SET_PALETTE_VISIBLE = "cubism.ui-panel.dock.set-palette-visible";
    private static final String DOCK_UPDATE_WINDOW_MENU = "cubism.ui-panel.dock.update-window-menu";
    private static final String PALETTE_MANAGER_GET = "cubism.ui-panel.palette-manager.get";
    private static final String PALETTE_MANAGER_ADD = "cubism.ui-panel.palette-manager.add";
    private static final String PALETTE_MANAGER_CLOSE = "cubism.ui-panel.palette-manager.close";
    private static final String PALETTE_MANAGER_CURRENT_WORKSPACE =
        "cubism.ui-panel.palette-manager.current-workspace";
    private static final String WORKSPACE_ACTIVATE = "cubism.ui-panel.workspace.activate";
    private static final String WORKSPACE_PALETTE_BOX_FOR =
        "cubism.ui-panel.workspace.palette-box-for";
    private static final String PALETTE_BOX_CLASS = "cubism.ui-panel.palette-box.class";
    private static final String PALETTE_BOX_REMOVE_TAB = "cubism.ui-panel.palette-box.remove-tab";
    private static final String PALETTE_MANAGER_REMOVE_UPDATE =
        "cubism.ui-panel.palette-manager.remove-update";
    private static final String PALETTE_MANAGER_MAIN_FRAME_WINDOW =
        "cubism.ui-panel.palette-manager.main-frame-window";
    private static final String PALETTE_MANAGER_VERIFY_CLEANUP =
        "cubism.ui-panel.palette-manager.verify-cleanup";
    private static final String PALETTE_MANAGER_FIRE_STATE =
        "cubism.ui-panel.palette-manager.fire-state";
    private static final String WORKSPACE_ADD_PALETTE_FRAME =
        "cubism.ui-panel.workspace.add-palette-frame";
    private static final String WORKSPACE_REMOVE_PALETTE_FRAME =
        "cubism.ui-panel.workspace.remove-palette-frame";
    private static final String WORKSPACE_FIRST_PALETTE_BOX =
        "cubism.ui-panel.workspace.first-palette-box";
    private static final String PALETTE_BOX_CREATE = "cubism.ui-panel.palette-box.create";
    private static final String PALETTE_BOX_ADD_TAB = "cubism.ui-panel.palette-box.add-tab";
    private static final String PALETTE_BOX_SET_SELECTED = "cubism.ui-panel.palette-box.set-selected";
    private static final String PALETTE_BOX_PALETTES = "cubism.ui-panel.palette-box.palettes";
    private static final String PALETTE_BOX_TAB_PANEL = "cubism.ui-panel.palette-box.tab-panel";
    private static final String TAB_PANEL_ENTRIES = "cubism.ui-panel.tab-panel.entries";
    private static final String TAB_ENTRY_PALETTE = "cubism.ui-panel.tab-entry.palette";
    private static final String TAB_ENTRY_BUTTON = "cubism.ui-panel.tab-entry.button";
    private static final String WIDGET_JCOMPONENT = "cubism.ui-panel.widget.jcomponent";
    private static final String PALETTE_FRAME_CREATE = "cubism.ui-panel.palette-frame.create";
    private static final String PALETTE_FRAME_ROOT = "cubism.ui-panel.palette-frame.root";
    private static final String PALETTE_FRAME_WINDOW = "cubism.ui-panel.palette-frame.window";
    private static final String PALETTE_FRAME_DISPOSE = "cubism.ui-panel.palette-frame.dispose";
    private static final String WORKSPACE_ROOT_CONTAINER = "cubism.ui-panel.workspace.root-container";
    private static final String ROOT_COMPONENT = "cubism.ui-panel.root.component";
    private static final String SPLIT_CONTENTS = "cubism.ui-panel.split.contents";
    private static final String SPLIT_REMOVE = "cubism.ui-panel.split.remove";
    private static final String COMPONENT_PALETTE_COUNT = "cubism.ui-panel.component.palette-count";
    private static final String ROOT_SET_COMPONENT = "cubism.ui-panel.root.set-component";
    private static final String WINDOW_SET_VISIBLE = "cubism.ui-panel.window.set-visible";
    private static final String PALETTE_ID_CREATE = "cubism.ui-panel.palette-id.create";
    private static final String PALETTE_CREATE = "cubism.ui-panel.palette.create";
    private static final String PALETTE_ID = "cubism.ui-panel.palette.id";
    private static final String PALETTE_SET_PANEL = "cubism.ui-panel.palette.set-panel";
    private static final String SWING_CONTAINER_CREATE = "cubism.ui-panel.swing-container.create";
    private static final String MAIN_FRAME_WINDOW = "cubism.ui-panel.main-frame.window";
    private static final String WINDOW_MENU_BAR = "cubism.ui-panel.window.menu-bar";
    private static final String MENU_BAR_MENUS = "cubism.ui-panel.menu-bar.menus";
    private static final String WIDGET_NAME = "cubism.ui-panel.widget.name";
    private static final String WIDGET_SET_NAME = "cubism.ui-panel.widget.set-name";
    private static final String WIDGET_REVALIDATE = "cubism.ui-panel.widget.revalidate";
    private static final String WIDGET_REPAINT = "cubism.ui-panel.widget.repaint";
    private static final String MENU_ITEMS = "cubism.ui-panel.menu.items";
    private static final String MENU_ADD = "cubism.ui-panel.menu.add";
    private static final String TAB_POPUP_ADD = "cubism.ui-panel.dock-tab-popup.menu-append";
    private static final String MENU_SWING = "cubism.ui-panel.menu.swing";
    private static final String MENU_ITEM_CREATE = "cubism.ui-panel.menu-item.create";
    private static final String MENU_ITEM_SWING = "cubism.ui-panel.menu-item.swing";
    private static final Set<String> WINDOW_MENU_LABELS = Set.of(
        "Window", "ウィンドウ", "视窗", "視窗", "窗口", "창"
    );

    private final VerifiedMemberResolver resolver;
    private final dev.turboism.ui.action.EditorUiActionRouter actionRouter;
    private final Map<Object, NativePanel> panels = new IdentityHashMap<>();
    private final Map<Object, FloatingPanel> floatingPanels = new IdentityHashMap<>();
    private final FloatingFrameLifecycle floatingFrameLifecycle = new FloatingFrameLifecycle();
    private volatile long hostGeneration = Long.MIN_VALUE;
    private volatile boolean hostActive;

    public VerifiedEmbeddedPanelHostOperations(
        final VerifiedMemberResolver resolver,
        final dev.turboism.ui.action.EditorUiActionRouter actionRouter
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.actionRouter = Objects.requireNonNull(actionRouter, "actionRouter");
    }

    @Override
    public void bindHostGeneration(final long generation) {
        if (generation <= 0) {
            throw new IllegalArgumentException("generation must be positive");
        }
        hostGeneration = generation;
        hostActive = true;
    }

    @Override
    public void invalidateHost() {
        hostActive = false;
    }

    @Override
    public PanelHandle addPanel(
        final EmbeddedPanelContributionDescriptor descriptor,
        final BiConsumer<String, Optional<UiActionEvent>> action
    ) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(action, "action");
        return onEdt(() -> install(descriptor, action));
    }

    @Override
    public Registration onRebuild(final Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        // No speculative native hook is installed. A future verified lifecycle callback may invoke this.
        return () -> { };
    }


    /** Removes empty dock palette boxes from the current workspace split tree. */
    public void cleanEmptyDocks() {
        cleanEmptyDocks(hostGeneration);
    }

    void cleanEmptyDocks(final long expectedGeneration) {
        onEdt(() -> {
            requireActiveHost(expectedGeneration);
            final NativeDock dock = resolveDock();
            final Object workspace = currentWorkspace(dock);
            final Object rootContainer = resolver.invoke(WORKSPACE_ROOT_CONTAINER, workspace);
            if (rootContainer == null) {
                throw new IllegalStateException("Cubism workspace root container is unavailable");
            }
            final Object rootComponent = resolver.invoke(ROOT_COMPONENT, rootContainer);
            if (rootComponent == null) {
                throw new IllegalStateException("Cubism workspace root component is unavailable");
            }
            pruneEmptyBoxes(rootComponent);
            resolver.invoke(PALETTE_MANAGER_VERIFY_CLEANUP, dock.paletteManager());
            refresh(dock);
            return null;
        });
    }

    /**
     * Recursively removes empty palette boxes and split branches.
     * The root component itself is never removed; singleton branches stay intact because
     * no verified reparent operation exists.
     */
    void pruneEmptyBoxes(final Object component) {
        if (component == null || isPaletteBox(component)) {
            return;
        }
        for (Object child : new ArrayList<>(splitContents(component))) {
            Objects.requireNonNull(child, "Cubism split child");
            if (isEmptyDockComponent(child)) {
                System.err.println(
                    "Turboism removing empty dock component: " + child.getClass().getName()
                );
                resolver.invoke(SPLIT_REMOVE, component, child);
            }
        }
    }

    private boolean isEmptyDockComponent(final Object component) {
        if (isPaletteBox(component)) {
            return (Integer) resolver.invoke(COMPONENT_PALETTE_COUNT, component) == 0;
        }
        pruneEmptyBoxes(component);
        return splitContents(component).isEmpty();
    }

    private PanelHandle install(
        final EmbeddedPanelContributionDescriptor descriptor,
        final BiConsumer<String, Optional<UiActionEvent>> action
    ) {
        final NativeDock dock = resolveDock();
        final String nativeId = "turboism:" + descriptor.pluginId() + ":" + descriptor.contributionId();
        final Object paletteId = resolver.construct(PALETTE_ID_CREATE, nativeId);
        final Object existingPalette = resolver.invoke(PALETTE_MANAGER_GET, dock.paletteManager(), paletteId);
        // Cubism retains palette identity across Window-menu hide/close cycles. Reuse that
        // identity during contribution reconciliation instead of adding a duplicate palette.
        final Object palette = existingPalette == null
            ? resolver.construct(PALETTE_CREATE, paletteId, descriptor.title())
            : existingPalette;

        panels.put(palette, new NativePanel(dock, palette, paletteId));

        final EmbeddedPanelId panelId = new EmbeddedPanelId(descriptor.contributionId());
        final PanelView viewContent = PanelCollapsibleContentCoordinator.shared()
            .merge(panelId, descriptor.content());
        final JComponent panel = SwingPanelViewRenderer.render(viewContent, action);
        panel.setName(nativeId);
        final Object content = resolver.construct(SWING_CONTAINER_CREATE, panel);
        resolver.invoke(PALETTE_SET_PANEL, palette, content, 340, 300);
        if (existingPalette == null) {
            resolver.invoke(PALETTE_MANAGER_ADD, dock.paletteManager(), palette);
        }
        resolver.invoke(DOCK_SET_PALETTE_VISIBLE, dock.dockManager(), palette, true);

        final AtomicBoolean closed = new AtomicBoolean();
        final WindowMenuItem windowMenuItem;
        try {
            refresh(dock);
            windowMenuItem = installWindowMenuItem(
                dock,
                descriptor.title(),
                nativeId + ":window-menu",
                () -> requestActivation(dock, palette, closed)
            );
        } catch (RuntimeException | Error failure) {
            closed.set(true);
            panels.remove(palette);
            floatingPanels.remove(palette);
            try {
                closePanel(
                    () -> removePaletteFromWorkspace(dock, palette),
                    () -> resolver.invoke(PALETTE_MANAGER_CLOSE, dock.paletteManager(), paletteId),
                    () -> refresh(dock)
                );
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }

        // 目标 panel 注册状态由宿主安装生命周期驱动（provider 无事件 API）：
        // install 成功 → 注册；PanelHandle.close → 注销。注入分区 pending→落位→pending。
        PanelCollapsibleContentCoordinator.shared().onPanelRegistered(panelId);
        return new PanelHandle() {
            @Override
            public void activate() {
                if (closed.get()) {
                    throw new IllegalStateException("embedded panel is closed");
                }
                requestActivation(dock, palette, closed);
            }

            @Override
            public void close() {
                if (!closed.compareAndSet(false, true)) {
                    return;
                }
                onEdt(() -> {
                    PanelCollapsibleContentCoordinator.shared().onPanelRemoved(panelId);
                    panels.remove(palette);
                    final FloatingPanel floating = floatingPanels.remove(palette);
                    closePanel(
                        () -> dockFloatingPanel(floating),
                        () -> removePaletteFromWorkspace(dock, palette),
                        () -> resolver.invoke(
                            PALETTE_MANAGER_CLOSE,
                            dock.paletteManager(),
                            paletteId
                        ),
                        () -> removeWindowMenuItem(windowMenuItem),
                        () -> refresh(dock)
                    );
                    return null;
                });
            }
        };
    }

    @Override
    public Registration bindPanelTabMenus(final PanelTabMenuCoordinator coordinator) {
        Objects.requireNonNull(coordinator, "coordinator");
        return coordinator.bindHost(contributions -> NativeDockTabPopupBridge.install(
            (menu, palette) -> onEdt(() -> {
                augmentNativeTabMenu(menu, palette, contributions);
                return null;
            })
        ));
    }

    private void augmentNativeTabMenu(
        final Object menu,
        final Object palette,
        final List<PanelTabMenuContribution> contributions
    ) {
        final boolean floating = floatingPanels.containsKey(palette);
        final String context = floating ? "panel.floating" : "panel.docked";
        contributions.stream()
            .filter(value -> value.contribution().context().equals(context))
            .filter(value -> value.contribution().operation()
                == dev.turboism.sdk.ui.context.ContextMenuRegistry.Operation.TOGGLE_PANEL_FLOATING)
            .sorted(java.util.Comparator.comparingInt(
                value -> value.contribution().priority()
            ))
            .forEach(value -> {
                final Object callback = resolver.createFunctionalConstructorArgumentProxy(
                    MENU_ITEM_CREATE,
                    2,
                    ignored -> {
                        routePanelTabAction(value, palette);
                        return kotlinUnit();
                    }
                );
                final Object nativeItem = resolver.construct(
                    MENU_ITEM_CREATE,
                    value.contribution().label(),
                    null,
                    callback
                );
                // The tab popup is a com.live2d.ui.menu.k container, not a CMenu;
                // items are appended through its k#c(CMenuItem) method.
                resolver.invoke(TAB_POPUP_ADD, menu, nativeItem);
            });
    }

    /**
     * Routes a panel-tab menu click through the plugin action registry so that
     * permission checks and the plugin-owned handler (core plugin) are exercised,
     * instead of invoking the runtime toggle directly.
     */
    void routePanelTabAction(
        final PanelTabMenuContribution contribution,
        final Object palette
    ) {
        if (palette == null
            || !hostActive
            || contribution.hostGeneration() != hostGeneration) {
            return;
        }
        final boolean floating = floatingPanels.containsKey(palette);
        final NativePanel panel = nativePanel(palette);
        final PanelTabSelection selection = new PanelTabSelection(
            contribution.hostGeneration(),
            String.valueOf(panel.paletteId()),
            floating
        );
        actionRouter.invoke(
            contribution.pluginId(),
            contribution.contribution().actionId(),
            new PanelTabActionContext(selection)
        );
    }

    private record PanelTabActionContext(PanelTabSelection selection)
        implements dev.turboism.sdk.action.ActionRegistry.ActionContext {
        @Override
        public java.util.Optional<PanelTabSelection> panelTabSelection() {
            return java.util.Optional.of(selection);
        }
    }

    private NativePanel nativePanel(final Object palette) {
        final NativePanel existing = panels.get(palette);
        if (existing != null) {
            return existing;
        }
        final Object paletteId = resolver.invoke(PALETTE_ID, palette);
        if (paletteId == null) {
            throw new IllegalStateException("Cubism palette identity is unavailable");
        }
        // Cache every palette we touch so floating/docking stays consistent for
        // native Cubism tabs (parameter, parts, ...) as well as Turboism panels.
        final NativePanel created = new NativePanel(resolveDock(), palette, paletteId);
        panels.put(palette, created);
        return created;
    }

    private void togglePanelFloating(final NativePanel panel) {
        onEdt(() -> {
            if (floatingPanels.containsKey(panel.palette())) {
                dockFloatingPanel(floatingPanels.remove(panel.palette()));
            } else {
                floatPanel(panel);
            }
            refresh(panel.dock());
            return null;
        });
    }

    /** Runtime action entry point; selection is validated before native lookup. */
    public void togglePanelFloating(final PanelTabSelection selection) {
        Objects.requireNonNull(selection, "selection");
        onEdt(() -> {
            requireActiveHost(selection.hostGeneration());
            final NativePanel panel = panels.values().stream()
                .filter(candidate -> selection.panelId().equals(String.valueOf(candidate.paletteId())))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("panel-tab selection is stale"));
            togglePanelFloating(panel);
            return null;
        });
    }

    private void floatPanel(final NativePanel panel) {
        final Object sourceBox = resolver.invoke(
            WORKSPACE_PALETTE_BOX_FOR,
            currentWorkspace(panel.dock()),
            panel.palette()
        );
        if (sourceBox == null) {
            throw new IllegalStateException("Cubism panel is not docked");
        }
        final Object rawSourcePalettes = resolver.invoke(PALETTE_BOX_PALETTES, sourceBox);
        if (!(rawSourcePalettes instanceof List<?> sourcePalettes)) {
            throw new IllegalStateException("Cubism source palette list is unavailable");
        }
        final Object siblingAnchor = sourcePalettes.stream()
            .filter(value -> value != panel.palette())
            .findFirst()
            .orElse(null);
        final Object workspace = currentWorkspace(panel.dock());
        final Object paletteArray = paletteArray(panel.palette());
        final Object ownerWindow = resolver.invoke(
            PALETTE_MANAGER_MAIN_FRAME_WINDOW,
            panel.dock().paletteManager()
        );
        final Object floatingBox = resolver.construct(
            PALETTE_BOX_CREATE,
            panel.dock().paletteManager(),
            paletteArray
        );
        final Object frame = resolver.construct(
            PALETTE_FRAME_CREATE,
            panel.dock().paletteManager(),
            ownerWindow
        );
        resolver.invoke(WORKSPACE_ADD_PALETTE_FRAME, workspace, frame);
        resolver.invoke(PALETTE_BOX_REMOVE_TAB, sourceBox, panel.palette());
        resolver.invoke(
            ROOT_SET_COMPONENT,
            resolver.invoke(PALETTE_FRAME_ROOT, frame),
            floatingBox
        );
        resolver.invoke(
            PALETTE_MANAGER_REMOVE_UPDATE,
            panel.dock().paletteManager(),
            workspace,
            sourceBox,
            paletteArray
        );
        resolver.invoke(PALETTE_MANAGER_VERIFY_CLEANUP, panel.dock().paletteManager());
        resolver.invoke(PALETTE_MANAGER_FIRE_STATE, panel.dock().paletteManager(), panel.palette());
        resolver.invoke(WINDOW_SET_VISIBLE, resolver.invoke(PALETTE_FRAME_WINDOW, frame), true);
        floatingPanels.put(panel.palette(), new FloatingPanel(panel, siblingAnchor, sourceBox, frame, floatingBox));
        floatingFrameLifecycle.remember(frame, panel.palette(), siblingAnchor, sourceBox);
    }

    private void dockFloatingPanel(final FloatingPanel floating) {
        if (floating == null) {
            return;
        }
        // Consume the lifecycle entries before the frame is disposed: the verified
        // dispose transformer fires afterDispose for this same frame, and the entries
        // must already be gone so the callback does not dock the palette a second time.
        floatingFrameLifecycle.beginClose(floating.frame());
        final NativePanel panel = floating.panel();
        final Object workspace = currentWorkspace(panel.dock());
        final Object targetBox = resolveDockTargetBox(
            workspace, floating.siblingAnchor(), floating.originalBox()
        );
        if (targetBox == null) {
            throw new IllegalStateException("Cubism dock target is unavailable");
        }
        resolver.invoke(PALETTE_BOX_REMOVE_TAB, floating.floatingBox(), panel.palette());
        resolver.invoke(PALETTE_BOX_ADD_TAB, targetBox, panel.palette());
        resolver.invoke(PALETTE_BOX_SET_SELECTED, targetBox, panel.paletteId());
        resolver.invoke(
            PALETTE_MANAGER_REMOVE_UPDATE,
            panel.dock().paletteManager(),
            workspace,
            floating.floatingBox(),
            paletteArray(panel.palette())
        );
    }


    /**
     * Prefers the palette's original dock box (where it lived before floating),
     * falling back to the sibling anchor box, then the first palette box.
     */
    private Object resolveDockTargetBox(
        final Object workspace,
        final Object siblingAnchor,
        final Object originalBox
    ) {
        if (originalBox != null && isDockBoxInWorkspaceTree(workspace, originalBox)) {
            return originalBox;
        }
        if (siblingAnchor != null) {
            final Object siblingBox = resolver.invoke(
                WORKSPACE_PALETTE_BOX_FOR, workspace, siblingAnchor
            );
            if (siblingBox != null) {
                return siblingBox;
            }
        }
        return resolver.invoke(WORKSPACE_FIRST_PALETTE_BOX, workspace);
    }

    /** Returns whether the exact box identity is still attached to the current workspace tree. */
    boolean isDockBoxInWorkspaceTree(final Object workspace, final Object box) {
        if (workspace == null || box == null) {
            return false;
        }
        try {
            final Object rootContainer = resolver.invoke(WORKSPACE_ROOT_CONTAINER, workspace);
            if (rootContainer == null) {
                return false;
            }
            return containsComponent(resolver.invoke(ROOT_COMPONENT, rootContainer), box);
        } catch (RuntimeException failure) {
            System.err.println(
                "Turboism original dock box tree validation failed safely: "
                    + failure.getClass().getName() + ": " + failure.getMessage()
            );
            return false;
    }
    }

    private boolean containsComponent(final Object component, final Object target) {
        if (component == target) {
            return true;
        }
        if (component == null || isPaletteBox(component)) {
            return false;
        }
        for (Object child : splitContents(component)) {
            if (containsComponent(child, target)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPaletteBox(final Object component) {
        return resolver.isInstance(PALETTE_BOX_CLASS, component);
    }

    private List<?> splitContents(final Object splitContainer) {
        final Object rawContents = resolver.invoke(SPLIT_CONTENTS, splitContainer);
        if (rawContents instanceof List<?> contents) {
            return contents;
        }
        throw new IllegalStateException("Cubism split contents are not a list");
    }

    private void dockEntry(
        final Object workspace,
        final Object sourceBox,
        final NativePanel panel,
        final Object siblingAnchor,
        final Object originalBox
    ) {
        final Object targetBox = resolveDockTargetBox(workspace, siblingAnchor, originalBox);
        if (targetBox == null) {
            throw new IllegalStateException("Cubism dock target is unavailable");
        }
        // Idempotent docking: skip when the palette is already in the target box,
        // which can happen when the tab close and the frame dispose both fire.
        if (paletteBoxContains(targetBox, panel.palette())) {
            System.err.println(
                "Turboism dock entry skipped: palette already docked in target box"
            );
            return;
        }
        resolver.invoke(PALETTE_BOX_REMOVE_TAB, sourceBox, panel.palette());
        resolver.invoke(PALETTE_BOX_ADD_TAB, targetBox, panel.palette());
        resolver.invoke(PALETTE_BOX_SET_SELECTED, targetBox, panel.paletteId());
        resolver.invoke(
            PALETTE_MANAGER_REMOVE_UPDATE,
            panel.dock().paletteManager(),
            workspace,
            sourceBox,
            paletteArray(panel.palette())
        );
        resolver.invoke(PALETTE_MANAGER_VERIFY_CLEANUP, panel.dock().paletteManager());
        resolver.invoke(PALETTE_MANAGER_FIRE_STATE, panel.dock().paletteManager(), panel.palette());
        // Restore palette visibility in the dock wrapper, matching the legacy merge path.
        resolver.invoke(DOCK_SET_PALETTE_VISIBLE, panel.dock().dockManager(), panel.palette(), true);
    }

    private Object findPaletteBox(final NativePanel panel) {
        final FloatingPanel floating = floatingPanels.get(panel.palette());
        return floating == null
            ? resolver.invoke(WORKSPACE_PALETTE_BOX_FOR, currentWorkspace(panel.dock()), panel.palette())
            : floating.floatingBox();
    }

    private Object currentWorkspace(final NativeDock dock) {
        final Object workspace = resolver.invoke(PALETTE_MANAGER_CURRENT_WORKSPACE, dock.paletteManager());
        if (workspace == null) {
            throw new IllegalStateException("Cubism current workspace is unavailable");
        }
        return workspace;
    }

    private Object paletteArray(final Object palette) {
        final Object array = java.lang.reflect.Array.newInstance(palette.getClass(), 1);
        java.lang.reflect.Array.set(array, 0, palette);
        return array;
    }

    private boolean paletteBoxContains(final Object box, final Object palette) {
        if (box == null || palette == null) {
            return false;
        }
        final Object rawPalettes = resolver.invoke(PALETTE_BOX_PALETTES, box);
        if (!(rawPalettes instanceof List<?> palettes)) {
            return false;
        }
        for (Object candidate : palettes) {
            if (candidate == palette) {
                return true;
            }
        }
        return false;
    }

    /** Handles the exact palette-frame disposal callback after the host has detached the frame. */
    public void onFloatingFrameDisposed(final Object frame) {
        if (frame == null) {
            return;
        }
        final long disposedGeneration = hostGeneration;
        final List<FloatingFrameLifecycle.Entry> entries = floatingFrameLifecycle.beginClose(frame);
        if (entries.isEmpty() || !hostActive) {
            return;
        }
        // The dispose transformer fires before the host method returns. Defer the
        // merge with two EDT hops (matching the approved legacy sequence) so the
        // host's own post-dispose cleanup cannot overwrite our docking result.
        SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(() -> {
            if (!hostActive || hostGeneration != disposedGeneration) {
                return;
            }
            try {
                mergeDisposedFrameEntries(frame, entries);
            } catch (RuntimeException | Error failure) {
                System.err.println(
                    "Turboism floating-frame merge failed safely: "
                        + failure.getClass().getName() + ": " + failure.getMessage()
                );
            }
        }));
    }

    private void mergeDisposedFrameEntries(
        final Object disposedFrame,
        final List<FloatingFrameLifecycle.Entry> entries
    ) {
        final FloatingPanel template = entries.stream()
            .map(entry -> floatingPanels.get(entry.palette()))
            .filter(Objects::nonNull)
            .filter(floating -> floating.frame() == disposedFrame)
            .findFirst()
            .orElse(null);
        if (template == null) {
            return;
        }
        final Object workspace = currentWorkspace(template.panel().dock());
        for (FloatingFrameLifecycle.Entry entry : entries) {
            // Any palette can be floating; the FloatingPanel carries the NativePanel.
            final FloatingPanel floating = floatingPanels.get(entry.palette());
            if (floating == null || floating.frame() != disposedFrame) {
                continue;
            }
            // The palette lives in the floating palette box, not in any workspace box.
            final Object sourceBox = floating.floatingBox();
            if (sourceBox != null) {
                dockEntry(
                    workspace,
                    sourceBox,
                    floating.panel(),
                    entry.siblingAnchor(),
                    entry.originalBox()
                );
            }
            floatingPanels.remove(entry.palette());
        }
        refresh(template.panel().dock());
    }

    /**
     * Handles the floating-tab close button callback. When the palette is floating,
     * it is docked back and the native close is cancelled; otherwise the native close
     * proceeds unchanged.
     *
     * @param palette the native palette whose floating tab was closed by the user
     * @return {@code true} when the native close was cancelled and the palette docked
     */
    public boolean onFloatingTabCloseRequested(final Object palette) {
        if (palette == null) {
            return false;
        }
        return onEdt(() -> {
            if (!hostActive) {
                return false;
            }
            final FloatingPanel floating = floatingPanels.get(palette);
            if (floating == null) {
                return false;
            }
            final NativePanel panel = nativePanel(palette);
            final Object workspace = currentWorkspace(panel.dock());
            System.err.println(
                "Turboism floating-tab close: docking palette class=" + palette.getClass().getName()
                    + " frame=" + floating.frame().getClass().getName()
            );
            dockEntry(
                workspace,
                floating.floatingBox(),
                panel,
                floating.siblingAnchor(),
                floating.originalBox()
            );
            floatingPanels.remove(palette);
            floatingFrameLifecycle.forget(palette);
            refresh(panel.dock());
            return true;
        });
    }

    private void requireActiveHost(final long expectedGeneration) {
        if (!hostActive || hostGeneration != expectedGeneration) {
            throw new IllegalStateException("embedded-panel host binding is no longer active");
        }
    }

    private static void closeRegistrations(final List<Registration> registrations) {
        RuntimeException first = null;
        for (int index = registrations.size() - 1; index >= 0; index--) {
            try {
                registrations.get(index).close();
            } catch (RuntimeException failure) {
                if (first == null) {
                    first = failure;
                } else {
                    first.addSuppressed(failure);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    private static void closeRegistrationsSuppressing(
        final List<Registration> registrations,
        final Throwable failure
    ) {
        try {
            closeRegistrations(registrations);
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private void requestActivation(
        final NativeDock dock,
        final Object palette,
        final AtomicBoolean closed
    ) {
        runOnEdtLater(() -> {
            if (closed.get()) {
                return;
            }
            final Object workspace = resolver.invoke(
                PALETTE_MANAGER_CURRENT_WORKSPACE,
                dock.paletteManager()
            );
            if (workspace == null) {
                throw new IllegalStateException("Cubism current workspace is unavailable");
            }
            resolver.invoke(WORKSPACE_ACTIVATE, workspace, palette);
            resolver.invoke(DOCK_SET_PALETTE_VISIBLE, dock.dockManager(), palette, true);
            refresh(dock);
        });
    }

    private void removePaletteFromWorkspace(final NativeDock dock, final Object palette) {
        final Object workspace = resolver.invoke(
            PALETTE_MANAGER_CURRENT_WORKSPACE,
            dock.paletteManager()
        );
        if (workspace == null) {
            throw new IllegalStateException("Cubism current workspace is unavailable during cleanup");
        }
        final Object paletteBox = resolver.invoke(WORKSPACE_PALETTE_BOX_FOR, workspace, palette);
        if (paletteBox == null) {
            return;
        }
        resolver.invoke(PALETTE_BOX_REMOVE_TAB, paletteBox, palette);
        final Object paletteArray = java.lang.reflect.Array.newInstance(palette.getClass(), 1);
        java.lang.reflect.Array.set(paletteArray, 0, palette);
        resolver.invoke(
            PALETTE_MANAGER_REMOVE_UPDATE,
            dock.paletteManager(),
            workspace,
            paletteBox,
            paletteArray
        );

        resolver.invoke(PALETTE_MANAGER_VERIFY_CLEANUP, dock.paletteManager());
    }

    private WindowMenuItem installWindowMenuItem(
        final NativeDock dock,
        final String label,
        final String nativeItemId,
        final Runnable activate
    ) {
        final Object window = resolver.invoke(MAIN_FRAME_WINDOW, dock.mainFrame());
        final Object menuBar = resolver.invoke(WINDOW_MENU_BAR, window);
        if (menuBar == null) {
            throw new IllegalStateException("Cubism menu bar is unavailable");
        }
        final Object rawMenus = resolver.invoke(MENU_BAR_MENUS, menuBar);
        if (!(rawMenus instanceof List<?> menus)) {
            throw new IllegalStateException("Cubism top-menu collection is unavailable");
        }
        final Object windowMenu = menus.stream()
            .filter(menu -> {
                final Object peer = resolver.invoke(MENU_SWING, menu);
                return peer instanceof JMenu swingMenu && WINDOW_MENU_LABELS.contains(swingMenu.getText());
            })
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Cubism Window menu is unavailable"));
        final Object rawItems = resolver.invoke(MENU_ITEMS, windowMenu);
        if (!(rawItems instanceof List<?> items)) {
            throw new IllegalStateException("Cubism Window-menu items are unavailable");
        }
        if (items.stream().anyMatch(item -> nativeItemId.equals(resolver.invoke(WIDGET_NAME, item)))) {
            throw new IllegalStateException("embedded-panel Window-menu item is already materialized");
        }

        final Object callback = resolver.createFunctionalConstructorArgumentProxy(
            MENU_ITEM_CREATE,
            2,
            ignored -> {
                activate.run();
                return kotlinUnit();
            }
        );
        final Object nativeItem = resolver.construct(MENU_ITEM_CREATE, label, null, callback);
        resolver.invoke(WIDGET_SET_NAME, nativeItem, nativeItemId);
        resolver.invoke(MENU_ADD, windowMenu, nativeItem);
        refreshMenu(menuBar);
        return new WindowMenuItem(menuBar, windowMenu, nativeItem);
    }

    @SuppressWarnings("unchecked")
    private void removeWindowMenuItem(final WindowMenuItem installed) {
        final Object rawItems = resolver.invoke(MENU_ITEMS, installed.menu());
        if (!(rawItems instanceof List<?>)) {
            throw new IllegalStateException("Cubism Window-menu items are unavailable during cleanup");
        }
        final List<Object> items = (List<Object>) rawItems;
        if (!items.remove(installed.item())) {
            throw new IllegalStateException("embedded-panel Window-menu item is no longer host-owned");
        }
        final Object menuPeer = resolver.invoke(MENU_SWING, installed.menu());
        final Object itemPeer = resolver.invoke(MENU_ITEM_SWING, installed.item());
        if (!(menuPeer instanceof JMenu menu) || !(itemPeer instanceof JMenuItem item)) {
            throw new IllegalStateException("Cubism Window-menu Swing peers are unavailable");
        }
        if (menu.getPopupMenu().getComponentIndex(item) < 0) {
            throw new IllegalStateException("embedded-panel Window-menu item is no longer attached");
        }
        menu.remove(item);
        refreshMenu(installed.menuBar());
    }

    private void refreshMenu(final Object menuBar) {
        resolver.invoke(WIDGET_REVALIDATE, menuBar);
        resolver.invoke(WIDGET_REPAINT, menuBar);
    }

    private Object kotlinUnit() {
        try {
            final Class<?> unit = Class.forName("kotlin.Unit", false, resolver.hostClassLoader());
            return unit.getField("INSTANCE").get(null);
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IllegalStateException("Kotlin Unit is unavailable for Window-menu callback", failure);
        }
    }

    private NativeDock resolveDock() {
        final Object app = resolver.invokeStatic(APP_INSTANCE);
        final Object mainFrame = resolver.invoke(APP_MAIN_FRAME, app);
        if (mainFrame == null) {
            throw new IllegalStateException("Cubism main frame is not ready");
        }
        final Object dockManager = resolver.invoke(MAIN_FRAME_DOCK_MANAGER, mainFrame);
        if (dockManager == null) {
            throw new IllegalStateException("Cubism dock manager is not ready");
        }
        final Object paletteManager = resolver.invoke(DOCK_PALETTE_MANAGER, dockManager);
        if (paletteManager == null) {
            throw new IllegalStateException("Cubism palette manager is not ready");
        }
        return new NativeDock(app, mainFrame, dockManager, paletteManager);
    }

    private void refresh(final NativeDock dock) {
        resolver.invoke(DOCK_UPDATE_WINDOW_MENU, dock.dockManager());
        resolver.invoke(APP_REPAINT, dock.app());
    }


    static void runOnEdtLater(final Runnable operation) {
        Objects.requireNonNull(operation, "operation");
        if (SwingUtilities.isEventDispatchThread()) {
            operation.run();
            return;
        }
        SwingUtilities.invokeLater(operation);
    }

    static void closePanel(final Runnable... operations) {
        Objects.requireNonNull(operations, "operations");
        RuntimeException first = null;
        for (Runnable operation : operations) {
            try {
                Objects.requireNonNull(operation, "operation").run();
            } catch (RuntimeException failure) {
                if (first == null) {
                    first = failure;
                } else {
                    first.addSuppressed(failure);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    private static <T> T onEdt(final Operation<T> operation) {
        if (SwingUtilities.isEventDispatchThread()) {
            return operation.run();
        }
        final Object[] result = new Object[1];
        final Throwable[] failure = new Throwable[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    result[0] = operation.run();
                } catch (Throwable throwable) {
                    failure[0] = throwable;
                }
            });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("embedded-panel EDT operation was interrupted", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("embedded-panel EDT operation failed", exception);
        }
        if (failure[0] instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure[0] instanceof Error error) {
            throw error;
        }
        if (failure[0] != null) {
            throw new IllegalStateException("embedded-panel EDT operation failed", failure[0]);
        }
        @SuppressWarnings("unchecked") final T value = (T) result[0];
        return value;
    }

    private record NativePanel(NativeDock dock, Object palette, Object paletteId) {
        private NativePanel {
            Objects.requireNonNull(dock, "dock");
            Objects.requireNonNull(palette, "palette");
            Objects.requireNonNull(paletteId, "paletteId");
        }
    }

    private record FloatingPanel(
        NativePanel panel,
        Object siblingAnchor,
        Object originalBox,
        Object frame,
        Object floatingBox
    ) {
        private FloatingPanel {
            Objects.requireNonNull(panel, "panel");
            Objects.requireNonNull(frame, "frame");
            Objects.requireNonNull(floatingBox, "floatingBox");
        }
    }

    private record WindowMenuItem(Object menuBar, Object menu, Object item) {
        private WindowMenuItem {
            Objects.requireNonNull(menuBar, "menuBar");
            Objects.requireNonNull(menu, "menu");
            Objects.requireNonNull(item, "item");
        }
    }

    private record NativeDock(Object app, Object mainFrame, Object dockManager, Object paletteManager) {
        private NativeDock {
            Objects.requireNonNull(app, "app");
            Objects.requireNonNull(mainFrame, "mainFrame");
            Objects.requireNonNull(dockManager, "dockManager");
            Objects.requireNonNull(paletteManager, "paletteManager");
        }
    }

    @FunctionalInterface
    private interface Operation<T> {
        T run();
    }
}
