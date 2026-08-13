package dev.turboism.ui.context;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.context.ContextMenuSelection;

/** Version-specific native context-menu installation seam. */
public interface ContextMenuHostOperations extends NativeObjectContextMenuBridge.Handler {

    Registration addItem(ContextMenuContributionDescriptor contribution, MenuAction action);

    @Override
    default Object augment(
        final Object menu,
        final dev.turboism.sdk.ui.context.ContextMenuRegistry.Location location,
        final Object source
    ) {
        return menu;
    }

    @FunctionalInterface
    interface MenuAction {
        void run(ContextMenuSelection selection, String actionId);
    }
}
