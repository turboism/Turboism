package dev.turboism.ui.panel;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.action.UiActionEvent;

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
    private static final String MENU_SWING = "cubism.ui-panel.menu.swing";
    private static final String MENU_ITEM_CREATE = "cubism.ui-panel.menu-item.create";
    private static final String MENU_ITEM_SWING = "cubism.ui-panel.menu-item.swing";
    private static final Set<String> WINDOW_MENU_LABELS = Set.of(
        "Window", "ウィンドウ", "视窗", "視窗", "窗口", "창"
    );

    private final VerifiedMemberResolver resolver;
    private final Map<Object, NativePanel> panels = new IdentityHashMap<>();
    private final Map<Object, FloatingPanel> floatingPanels = new IdentityHashMap<>();

    public VerifiedEmbeddedPanelHostOperations(final VerifiedMemberResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
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


    /** Removes only host palette boxes that Cubism itself identifies as empty. */
    public void cleanEmptyDocks() {
        onEdt(() -> {
            final NativeDock dock = resolveDock();
            resolver.invoke(PALETTE_MANAGER_VERIFY_CLEANUP, dock.paletteManager());
            refresh(dock);
            return null;
        });
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

        final JComponent panel = SwingPanelViewRenderer.render(descriptor.content(), action);
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
        final List<dev.turboism.sdk.ui.context.ContextMenuRegistry.ContextMenuContribution> contributions
    ) {
        final boolean floating = floatingPanels.containsKey(palette);
        final String context = floating ? "panel.floating" : "panel.docked";
        contributions.stream()
            .filter(value -> value.context().equals(context))
            .filter(value -> value.operation()
                == dev.turboism.sdk.ui.context.ContextMenuRegistry.Operation.TOGGLE_PANEL_FLOATING)
            .sorted(java.util.Comparator.comparingInt(
                dev.turboism.sdk.ui.context.ContextMenuRegistry.ContextMenuContribution::priority
            ))
            .forEach(value -> {
                final Object callback = resolver.createFunctionalConstructorArgumentProxy(
                    MENU_ITEM_CREATE,
                    2,
                    ignored -> {
                        togglePanelFloating(nativePanel(palette));
                        return kotlinUnit();
                    }
                );
                final Object nativeItem = resolver.construct(MENU_ITEM_CREATE, value.label(), null, callback);
                resolver.invoke(MENU_ADD, menu, nativeItem);
            });
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
        return new NativePanel(resolveDock(), palette, paletteId);
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
        floatingPanels.put(panel.palette(), new FloatingPanel(panel, siblingAnchor, frame, floatingBox));
    }

    private void dockFloatingPanel(final FloatingPanel floating) {
        if (floating == null) {
            return;
        }
        final NativePanel panel = floating.panel();
        final Object workspace = currentWorkspace(panel.dock());
        Object targetBox = floating.siblingAnchor() == null
            ? null
            : resolver.invoke(WORKSPACE_PALETTE_BOX_FOR, workspace, floating.siblingAnchor());
        if (targetBox == null) {
            targetBox = resolver.invoke(WORKSPACE_FIRST_PALETTE_BOX, workspace);
        }
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
        resolver.invoke(WORKSPACE_REMOVE_PALETTE_FRAME, workspace, floating.frame());
        resolver.invoke(PALETTE_FRAME_DISPOSE, floating.frame());
        resolver.invoke(PALETTE_MANAGER_VERIFY_CLEANUP, panel.dock().paletteManager());
        resolver.invoke(PALETTE_MANAGER_FIRE_STATE, panel.dock().paletteManager(), panel.palette());
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
