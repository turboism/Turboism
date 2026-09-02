package dev.turboism.ui.panel;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.action.UiActionEvent;
import dev.turboism.sdk.ui.EmbeddedPanelId;
import dev.turboism.sdk.ui.PanelView;
import dev.turboism.ui.action.EditorUiActionRouter;
import dev.turboism.sdk.ui.context.PanelTabSelection;

import java.awt.BorderLayout;
import javax.swing.JPanel;

import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
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
    private static final String MENU_ITEM_CHECK_CREATE = "cubism.ui-panel.menu-item.check.create";
    private static final String MENU_ITEM_SWING = "cubism.ui-panel.menu-item.swing";
    private static final String MENU_ITEM_IS_SELECTED = "cubism.ui-panel.menu-item.is-selected";
    private static final String DOCK_MAIN_FRAME_CTRL = "cubism.ui-panel.dock.main-frame-ctrl";
    private static final String MAIN_FRAME_PALETTE_MENU_MAP =
        "cubism.ui-panel.main-frame.palette-menu-map";
    private static final Set<String> WINDOW_MENU_LABELS = Set.of(
        "Window", "ウィンドウ", "视窗", "視窗", "窗口", "창"
    );

    private final VerifiedMemberResolver resolver;
    private final DockTreeTraversal traversal;
    private final dev.turboism.ui.action.EditorUiActionRouter actionRouter;
    private final Map<Object, NativePanel> panels = new IdentityHashMap<>();
    private final Map<Object, FloatingPanel> floatingPanels = new IdentityHashMap<>();
    private final Map<Object, Long> lastFloatMillis = new IdentityHashMap<>();
    private final FloatingFrameLifecycle floatingFrameLifecycle = new FloatingFrameLifecycle();
    private volatile long hostGeneration = Long.MIN_VALUE;
    private final java.util.Locale locale;
    private volatile boolean hostActive;

    public VerifiedEmbeddedPanelHostOperations(
        final VerifiedMemberResolver resolver,
        final dev.turboism.ui.action.EditorUiActionRouter actionRouter
    ) {
        this(resolver, actionRouter, dev.turboism.i18n.CubismHostLocale.resolve());
    }

    public VerifiedEmbeddedPanelHostOperations(
        final VerifiedMemberResolver resolver,
        final dev.turboism.ui.action.EditorUiActionRouter actionRouter,
        final java.util.Locale locale
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.traversal = new DockTreeTraversal(resolver);
        this.actionRouter = Objects.requireNonNull(actionRouter, "actionRouter");
        this.locale = Objects.requireNonNull(locale, "locale");
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
        traversal.pruneEmptyBoxes(component);
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
        final JComponent contentRoot = buildContentRoot(descriptor);
        // The native palette keeps one stable layout-neutral wrapper for its whole
        // lifetime; refresh swaps the wrapper's single child, so the host container
        // and any floating window are never rebuilt and layout-specific renderer
        // roots are never nested inside one another.
        final JPanel stableContentRoot = new JPanel(new BorderLayout());
        stableContentRoot.add(contentRoot, BorderLayout.CENTER);
        final Object content = resolver.construct(SWING_CONTAINER_CREATE, stableContentRoot);
        resolver.invoke(PALETTE_SET_PANEL, palette, content, 340, 300);
        final AtomicBoolean closed = new AtomicBoolean();
        WindowMenuItem windowMenuItem = null;
        try {
            // The Window-menu check item and its paletteMenuMap entry must exist before
            // addPalette/setPaletteVisible: native updateWindowMenuItem runs at the end of
            // setPaletteVisible (and on workspace switch/serialization) and iterates the
            // palette list against the map. An unregistered palette crashes DEVELOPER_MODE
            // builds (RuntimeException "Illegal state :_") and is silently skipped otherwise.
            windowMenuItem = installWindowMenuItem(
                dock,
                descriptor.title(),
                nativeId + ":window-menu",
                paletteId,
                palette,
                () -> requestActivation(dock, palette, paletteId, closed)
            );
            if (existingPalette == null) {
                resolver.invoke(PALETTE_MANAGER_ADD, dock.paletteManager(), palette);
            }
            // Dock into the first existing workspace palette box (native getFirstPaletteBox
            // semantics) so a custom tab reuses the current dock column instead of opening a
            // new one; only a workspace without any box runs the native new-column path.
            showPaletteInWorkspace(dock, palette, paletteId);
            if (descriptor.floatingByDefault()) {
                // Present the pane as a floating window in the same EDT batch as the
                // install, so the user never sees a docked intermediate state. Runs
                // only after the synchronous workspace attachment: floatPanel resolves
                // the palette's source box, which exists only once showPaletteInWorkspace
                // attached the palette to the workspace.
                floatPanel(nativePanel(palette));
            }
            // Runs native updateWindowMenuItem after the palette is shown so the host
            // derives the initial check state (visible palette => checked menu item).
            refresh(dock);
        } catch (RuntimeException | Error failure) {
            final WindowMenuItem installedItem = windowMenuItem;
            closed.set(true);
            panels.remove(palette);
            floatingPanels.remove(palette);
            try {
                closePanel(
                    () -> removeWindowMenuItem(installedItem),
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
        final WindowMenuItem installedWindowMenuItem = windowMenuItem;
        return new PanelHandle() {
            @Override
            public void activate() {
                if (closed.get()) {
                    throw new IllegalStateException("embedded panel is closed");
                }
                requestActivation(dock, palette, paletteId, closed);
            }

            @Override
            public void floatPanel() {
                if (closed.get()) {
                    throw new IllegalStateException("embedded panel is closed");
                }
                onEdt(() -> {
                    VerifiedEmbeddedPanelHostOperations.this.floatPanel(nativePanel(palette));
                    return null;
                });
            }

            @Override
            public void updateContent(final EmbeddedPanelContributionDescriptor descriptor) {
                if (closed.get()) {
                    throw new IllegalStateException("embedded panel is closed");
                }
                onEdt(() -> {
                    // Add the fresh root before removing the previous one so the
                    // stable wrapper is never observed empty (no blank flash) and
                    // still ends with exactly one child; the native palette (and its
                    // floating window) is never rebuilt.
                    final JComponent next = buildContentRoot(descriptor);
                    final java.awt.Component previous = stableContentRoot.getComponent(0);
                    stableContentRoot.add(next, BorderLayout.CENTER);
                    stableContentRoot.remove(previous);
                    stableContentRoot.revalidate();
                    stableContentRoot.repaint();
                    return null;
                });
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
                        () -> removeWindowMenuItem(installedWindowMenuItem),
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

    private JComponent buildContentRoot(final EmbeddedPanelContributionDescriptor descriptor) {
        final JComponent[] holder = new JComponent[1];
        onEdt(() -> {
            final EmbeddedPanelId panelId = new EmbeddedPanelId(descriptor.contributionId());
            final PanelView viewContent = PanelCollapsibleContentCoordinator.shared()
                .merge(panelId, descriptor.content());
            final Map<String, String> actionOwners =
                PanelCollapsibleContentCoordinator.shared().actionOwners(panelId);
            final JComponent panel = SwingPanelViewRenderer.render(
                viewContent, routedAction(actionRouter, actionOwners, descriptor.pluginId()), locale);
            final String nativeId = "turboism:" + descriptor.pluginId() + ":" + descriptor.contributionId();
            panel.setName(nativeId);
            holder[0] = panel;
            return null;
        });
        return holder[0];
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
        final Object rawSourcePalettes = resolver.invoke(DockTreeTraversal.PALETTE_BOX_PALETTES, sourceBox);
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
        lastFloatMillis.put(panel.palette(), System.currentTimeMillis());
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
            return traversal.containsComponent(resolver.invoke(ROOT_COMPONENT, rootContainer), box);
        } catch (RuntimeException failure) {
            dev.turboism.runtime.log.RuntimeDiagnostics.error(
                "floating-panels",
                "Original dock box tree validation failed safely",
                failure
            );
            return false;
    }
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
            dev.turboism.runtime.log.RuntimeDiagnostics.debug(
                "floating-panels",
                "Dock entry skipped because the panel is already docked"
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
        final Object rawPalettes = resolver.invoke(DockTreeTraversal.PALETTE_BOX_PALETTES, box);
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
        // The host resets (dispose + rebuild) the floating frame while creating a
        // new floating window for the same palette. Within a short window after
        // floatPanel this dispose is that reset, not a user close; merging the
        // panel back into the dock then would drop the floating window right
        // after it appeared. Only merge when the dispose happens outside the
        // window (a real user close).
        final long now = System.currentTimeMillis();
        final boolean recentFloat = lastFloatMillis.values().stream()
            .anyMatch(at -> now - at < 1_500L);
        final List<FloatingFrameLifecycle.Entry> entries = floatingFrameLifecycle.beginClose(frame);
        if (entries.isEmpty() || !hostActive || recentFloat) {
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
                dev.turboism.runtime.log.RuntimeDiagnostics.error(
                    "floating-panels",
                    "Floating-frame merge failed safely",
                    failure
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
            dev.turboism.runtime.log.RuntimeDiagnostics.debug(
                "floating-panels",
                "Docking one closed floating panel"
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

    /**
     * Returns the workspace palette box holding the fewest docked tabs, or null when
     * the workspace has no palette box at all. Empty boxes are preferred first and
     * ties resolve toward the first box in traversal order, so a custom tab reuses
     * the least-loaded dock column and leftover empty docks get filled.
     */
    private Object findSparsestPaletteBox(final Object workspace) {
        final Object rootContainer = resolver.invoke(WORKSPACE_ROOT_CONTAINER, workspace);
        if (rootContainer == null) {
            return null;
        }
        final Object rootComponent = resolver.invoke(ROOT_COMPONENT, rootContainer);
        if (rootComponent == null) {
            return null;
        }
        return sparsestBoxInTree(rootComponent);
    }

    /** Depth-first traversal over the workspace split tree; palette boxes are leaves. */
    private Object sparsestBoxInTree(final Object component) {
        if (component == null || traversal.isPaletteBox(component)) {
            return component;
        }
        if (!traversal.isSplitContainer(component)) {
            // Non-split components (e.g. CPMContentsBox) are never palette-box
            // containers and cannot be expanded.
            return null;
        }
        Object sparsest = null;
        int sparsestTabs = Integer.MAX_VALUE;
        for (Object child : traversal.splitContents(component)) {
            final Object candidate = sparsestBoxInTree(child);
            if (candidate == null) {
                continue;
            }
            final int tabs = traversal.paletteTabCount(candidate);
            if (tabs < sparsestTabs) {
                sparsest = candidate;
                sparsestTabs = tabs;
            }
        }
        return sparsest;
    }

    /**
     * Shows the palette by docking it into the workspace palette box with the fewest
     * docked tabs (see {@link #findSparsestPaletteBox}), so a custom tab reuses the
     * least-loaded dock column instead of opening a new one. Only a workspace without
     * any palette box at all runs the native new-column path (workspace activate
     * query + setPaletteVisible(true)).
     */
    private void showPaletteInWorkspace(
        final NativeDock dock,
        final Object palette,
        final Object paletteId
    ) {
        final Object workspace = resolver.invoke(
            PALETTE_MANAGER_CURRENT_WORKSPACE,
            dock.paletteManager()
        );
        if (workspace == null) {
            throw new IllegalStateException("Cubism current workspace is unavailable");
        }
        final Object targetBox = findSparsestPaletteBox(workspace);
        if (targetBox != null) {
            // The palette is now attached to the workspace tree; the following native
            // updateWindowMenuItem visibility derivation checks the menu item.
            resolver.invoke(PALETTE_BOX_ADD_TAB, targetBox, palette);
            resolver.invoke(PALETTE_BOX_SET_SELECTED, targetBox, paletteId);
            return;
        }
        resolver.invoke(WORKSPACE_ACTIVATE, workspace, palette);
        resolver.invoke(DOCK_SET_PALETTE_VISIBLE, dock.dockManager(), palette, true);
    }

    private void requestActivation(
        final NativeDock dock,
        final Object palette,
        final Object paletteId,
        final AtomicBoolean closed
    ) {
        runOnEdtLater(() -> {
            if (closed.get()) {
                return;
            }
            showPaletteInWorkspace(dock, palette, paletteId);
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
        final Object paletteId,
        final Object palette,
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
        // Idempotent materialization: a previous panel teardown may have failed
        // before detaching the menu item (e.g. the palette was already closed).
        // Detach any stale item with the same id instead of failing, so a rebuild
        // after a host-side panel close can always re-install cleanly.
        final List<?> staleItems = items.stream()
            .filter(item -> nativeItemId.equals(resolver.invoke(WIDGET_NAME, item)))
            .toList();
        if (!staleItems.isEmpty()) {
            for (Object stale : staleItems) {
                items.remove(stale);
                final Object stalePeer = resolver.invoke(MENU_ITEM_SWING, stale);
                if (stalePeer instanceof JMenuItem staleItem) {
                    final Object menuPeer = resolver.invoke(MENU_SWING, windowMenu);
                    if (menuPeer instanceof JMenu menu) {
                        menu.remove(staleItem);
                    }
                }
            }
            refreshMenu(menuBar);
        }

        // CCheckMenuItem (Swing peer JCheckBoxMenuItem) carries the selected state that the
        // native updateWindowMenuItem derives from palette visibility; a plain CMenuItem has no
        // check mark. The functional callback is a constructor argument, so it must exist before
        // the item; the item is only observable at click time, hence the holder.
        final Object[] nativeItemRef = new Object[1];
        final Object callback = resolver.createFunctionalConstructorArgumentProxy(
            MENU_ITEM_CHECK_CREATE,
            1,
            ignored -> {
                // Native toggle semantics (com.live2d.cubism.view.aa): the Swing peer already
                // flipped its selected state on click, so isSelected is the target visibility —
                // checked shows the palette, unchecked hides it. The hide path runs the native
                // full route (removeTab/removePaletteUpdate and the trailing updateWindowMenuItem)
                // through setPaletteVisible(false); the show path keeps the verified activation.
                final boolean visible = (Boolean) resolver.invoke(
                    MENU_ITEM_IS_SELECTED,
                    nativeItemRef[0]
                );
                if (visible) {
                    activate.run();
                } else {
                    resolver.invoke(DOCK_SET_PALETTE_VISIBLE, dock.dockManager(), palette, false);
                }
                return kotlinUnit();
            }
        );
        final Object nativeItem = resolver.construct(MENU_ITEM_CHECK_CREATE, label, callback);
        nativeItemRef[0] = nativeItem;
        resolver.invoke(WIDGET_SET_NAME, nativeItem, nativeItemId);
        resolver.invoke(MENU_ADD, windowMenu, nativeItem);
        // Register the palette in CEMainFrameCtrl.paletteMenuMap so the native
        // updateWindowMenuItem can maintain the check state (and does not raise
        // "Illegal state :_" in DEVELOPER_MODE builds).
        final Object mainFrameCtrl = resolver.invoke(DOCK_MAIN_FRAME_CTRL, dock.dockManager());
        final Object paletteMenuMap = resolver.invoke(MAIN_FRAME_PALETTE_MENU_MAP, mainFrameCtrl);
        putPaletteMenuEntry(paletteMenuMap, paletteId, nativeItem);
        refreshMenu(menuBar);
        return new WindowMenuItem(menuBar, windowMenu, nativeItem, paletteMenuMap, paletteId);
    }

    /** Registers one palette check item in the host paletteMenuMap (JDK HashMap). */
    private static void putPaletteMenuEntry(
        final Object paletteMenuMap,
        final Object paletteId,
        final Object item
    ) {
        try {
            final java.lang.reflect.Method put =
                java.util.HashMap.class.getMethod("put", Object.class, Object.class);
            put.invoke(paletteMenuMap, paletteId, item);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                "Cubism palette Window-menu map is not writable", failure);
        }
    }

    /** Removes the palette check item from the host paletteMenuMap (JDK HashMap). */
    private static void removePaletteMenuEntry(final Object paletteMenuMap, final Object paletteId) {
        try {
            final java.lang.reflect.Method remove =
                java.util.HashMap.class.getMethod("remove", Object.class);
            remove.invoke(paletteMenuMap, paletteId);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                "Cubism palette Window-menu map is not writable during cleanup", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private void removeWindowMenuItem(final WindowMenuItem installed) {
        if (installed == null) {
            return;
        }
        // Drop the paletteMenuMap entry before detaching the menu item so the host can
        // never observe a map entry without a menu item.
        removePaletteMenuEntry(installed.paletteMenuMap(), installed.paletteId());
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

    /**
     * 渲染 action 回调：注入分区（B）按钮 actionId 命中 {@code actionOwners} 时路由到
     * 贡献者 pluginId；未命中（面板自身 A 按钮）回落 {@code defaultPluginId}（面板 owner）。
     */
    static BiConsumer<String, Optional<UiActionEvent>> routedAction(
        final EditorUiActionRouter router,
        final Map<String, String> actionOwners,
        final String defaultPluginId
    ) {
        Objects.requireNonNull(router, "router");
        Objects.requireNonNull(actionOwners, "actionOwners");
        Objects.requireNonNull(defaultPluginId, "defaultPluginId");
        return (actionId, event) ->
            router.invoke(actionOwners.getOrDefault(actionId, defaultPluginId), actionId, event);
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

    private record WindowMenuItem(
        Object menuBar,
        Object menu,
        Object item,
        Object paletteMenuMap,
        Object paletteId
    ) {
        private WindowMenuItem {
            Objects.requireNonNull(menuBar, "menuBar");
            Objects.requireNonNull(menu, "menu");
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(paletteMenuMap, "paletteMenuMap");
            Objects.requireNonNull(paletteId, "paletteId");
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
