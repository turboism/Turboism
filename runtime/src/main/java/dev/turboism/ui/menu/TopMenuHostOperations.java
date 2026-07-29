package dev.turboism.ui.menu;

import dev.turboism.sdk.plugin.Registration;

import java.util.function.Consumer;

/** Host mutation seam for one runtime-owned top menu. */
public interface TopMenuHostOperations {

    Registration addMenu(
        TopMenuDescriptor menu,
        Consumer<TopMenuItemDescriptor> action
    );

    Registration onRebuild(Runnable reconcile);
}
