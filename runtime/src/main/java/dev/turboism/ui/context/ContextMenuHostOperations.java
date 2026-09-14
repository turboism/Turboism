package dev.turboism.ui.context;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.context.ContextMenuSelection;

/** Version-specific native context-menu installation seam. */
public interface ContextMenuHostOperations extends NativeObjectContextMenuBridge.Handler {

    /**
     * Installs one contributed context-menu item.
     *
     * @param contribution the resolved contribution descriptor
     * @param action the callback to run when the item is activated
     * @return a registration that removes the item when disposed
     */
    Registration addItem(ContextMenuContributionDescriptor contribution, MenuAction action);

    @Override
    default Object augment(
        final Object menu,
        final dev.turboism.sdk.ui.context.ContextMenuRegistry.Location location,
        final Object source
    ) {
        return menu;
    }

    /** Callback fired when a contributed context-menu item is activated. */
    @FunctionalInterface
    interface MenuAction {
        /**
         * @param selection the context-menu selection at activation
         * @param actionId the action id the contribution declared
         */
        void run(ContextMenuSelection selection, String actionId);
    }
}
