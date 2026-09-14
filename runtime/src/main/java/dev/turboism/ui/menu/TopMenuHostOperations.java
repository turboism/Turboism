package dev.turboism.ui.menu;

import dev.turboism.sdk.plugin.Registration;

import java.util.function.Consumer;

/** Host mutation seam for one runtime-owned top menu. */
public interface TopMenuHostOperations {

    /**
     * Installs one runtime-owned top menu and its items.
     *
     * @param menu the menu descriptor to install
     * @param action receives the activated item's descriptor
     * @return a registration that removes the menu when disposed
     */
    Registration addMenu(
        TopMenuDescriptor menu,
        Consumer<TopMenuItemDescriptor> action
    );

    /**
     * Registers a callback fired whenever the host rebuilds its menu bar.
     *
     * @param reconcile re-installs the runtime-owned menus after a rebuild
     * @return a registration that removes the callback when disposed
     */
    Registration onRebuild(Runnable reconcile);
}
