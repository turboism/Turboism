package dev.turboism.ui.menu;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.plugin.Registration;

import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Exact-version top-menu operations restricted to pinned verified aliases. */
public final class VerifiedTopMenuHostOperations implements TopMenuHostOperations {

    private static final String APP_INSTANCE = "cubism.ui-top-menu.app-controller.instance";
    private static final String APP_MAIN_FRAME = "cubism.ui-top-menu.app-controller.main-frame";
    private static final String MAIN_FRAME_WINDOW = "cubism.ui-top-menu.main-frame.window";
    private static final String WINDOW_MENU_BAR = "cubism.ui-top-menu.window.menu-bar";
    private static final String MENU_BAR_MENUS = "cubism.ui-top-menu.menu-bar.menus";
    private static final String MENU_BAR_ADD = "cubism.ui-top-menu.menu-bar.add";
    private static final String MENU_BAR_SWING = "cubism.ui-top-menu.menu-bar.swing";
    private static final String WIDGET_NAME = "cubism.ui-top-menu.widget.name";
    private static final String WIDGET_SET_NAME = "cubism.ui-top-menu.widget.set-name";
    private static final String WIDGET_REVALIDATE = "cubism.ui-top-menu.widget.revalidate";
    private static final String WIDGET_REPAINT = "cubism.ui-top-menu.widget.repaint";
    private static final String MENU_CREATE = "cubism.ui-top-menu.menu.create";
    private static final String MENU_ADD = "cubism.ui-top-menu.menu.add";
    private static final String MENU_SWING = "cubism.ui-top-menu.menu.swing";
    private static final String MENU_ITEM_CREATE = "cubism.ui-top-menu.menu-item.create";

    private final VerifiedMemberResolver resolver;

    public VerifiedTopMenuHostOperations(final VerifiedMemberResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public Registration addMenu(
        final TopMenuDescriptor descriptor,
        final Consumer<TopMenuItemDescriptor> action
    ) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(action, "action");
        return onEdt(() -> installMenu(descriptor, action));
    }

    @Override
    public Registration onRebuild(final Runnable reconcile) {
        Objects.requireNonNull(reconcile, "reconcile");
        return () -> { };
    }

    private Registration installMenu(
        final TopMenuDescriptor descriptor,
        final Consumer<TopMenuItemDescriptor> action
    ) {
        final Object menuBar = resolveMenuBar();
        final List<?> menus = menus(menuBar);
        if (menus.stream().anyMatch(menu -> descriptor.menuId().equals(
            resolver.invoke(WIDGET_NAME, menu)
        ))) {
            throw new IllegalStateException("Turboism top menu is already materialized");
        }

        final Object menu = resolver.construct(MENU_CREATE, descriptor.label());
        resolver.invoke(WIDGET_SET_NAME, menu, descriptor.menuId());
        for (TopMenuItemDescriptor item : descriptor.items()) {
            final Object callback = resolver.createFunctionalConstructorArgumentProxy(
                MENU_ITEM_CREATE,
                2,
                ignored -> {
                    action.accept(item);
                    return kotlinUnit();
                }
            );
            final Object nativeItem = resolver.construct(
                MENU_ITEM_CREATE,
                item.label(),
                null,
                callback
            );
            resolver.invoke(WIDGET_SET_NAME, nativeItem, item.nativeItemId());
            resolver.invoke(MENU_ADD, menu, nativeItem);
        }

        boolean added = false;
        try {
            resolver.invoke(MENU_BAR_ADD, menuBar, menu);
            added = true;
            refresh(menuBar);
        } catch (RuntimeException | Error failure) {
            if (added) {
                try {
                    removeMenu(menuBar, menu);
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }

        final AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            onEdt(() -> {
                removeMenu(menuBar, menu);
                return null;
            });
        };
    }

    private Object resolveMenuBar() {
        final Object app = resolver.invokeStatic(APP_INSTANCE);
        final Object mainFrame = resolver.invoke(APP_MAIN_FRAME, app);
        if (mainFrame == null) {
            throw new IllegalStateException("Cubism main-frame controller is not ready");
        }
        final Object window = resolver.invoke(MAIN_FRAME_WINDOW, mainFrame);
        if (window == null) {
            throw new IllegalStateException("Cubism main window is not ready");
        }
        final Object menuBar = resolver.invoke(WINDOW_MENU_BAR, window);
        if (menuBar == null) {
            throw new IllegalStateException("Cubism top menu bar is not ready");
        }
        return menuBar;
    }

    private List<?> menus(final Object menuBar) {
        final Object value = resolver.invoke(MENU_BAR_MENUS, menuBar);
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException("Cubism top menu collection is unavailable");
        }
        return list;
    }

    private void removeMenu(final Object menuBar, final Object menu) {
        cleanupMenu(
            () -> removeFromHostList(menuBar, menu),
            () -> removeFromSwingMenuBar(menuBar, menu),
            () -> refresh(menuBar)
        );
    }

    @SuppressWarnings("unchecked")
    private void removeFromHostList(final Object menuBar, final Object menu) {
        final List<Object> mutable = (List<Object>) menus(menuBar);
        if (!mutable.remove(menu)) {
            throw new IllegalStateException("Turboism top menu is no longer owned by its host list");
        }
    }

    private void removeFromSwingMenuBar(final Object menuBar, final Object menu) {
        final Object swingBar = resolver.invoke(MENU_BAR_SWING, menuBar);
        final Object swingMenu = resolver.invoke(MENU_SWING, menu);
        if (!(swingBar instanceof JMenuBar bar) || !(swingMenu instanceof JMenu item)) {
            throw new IllegalStateException("Cubism Swing top-menu peers are unavailable");
        }
        if (bar.getComponentZOrder(item) < 0) {
            throw new IllegalStateException("Turboism Swing top menu is no longer attached");
        }
        bar.remove(item);
    }

    private Object kotlinUnit() {
        try {
            final Class<?> unit = Class.forName(
                "kotlin.Unit",
                false,
                resolver.hostClassLoader()
            );
            return unit.getField("INSTANCE").get(null);
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IllegalStateException("Kotlin Unit is unavailable for top-menu callback", failure);
        }
    }

    private void refresh(final Object menuBar) {
        resolver.invoke(WIDGET_REVALIDATE, menuBar);
        resolver.invoke(WIDGET_REPAINT, menuBar);
    }

    static void cleanupMenu(
        final Runnable removeHostEntry,
        final Runnable removeSwingEntry,
        final Runnable refresh
    ) {
        final Runnable[] operations = {
            Objects.requireNonNull(removeHostEntry, "removeHostEntry"),
            Objects.requireNonNull(removeSwingEntry, "removeSwingEntry"),
            Objects.requireNonNull(refresh, "refresh")
        };
        RuntimeException first = null;
        for (Runnable operation : operations) {
            try {
                operation.run();
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
            throw new IllegalStateException("top-menu EDT operation was interrupted", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("top-menu EDT operation failed", exception);
        }
        if (failure[0] instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure[0] instanceof Error error) {
            throw error;
        }
        if (failure[0] != null) {
            throw new IllegalStateException("top-menu EDT operation failed", failure[0]);
        }
        @SuppressWarnings("unchecked") final T value = (T) result[0];
        return value;
    }

    @FunctionalInterface
    private interface Operation<T> {
        T run();
    }
}
