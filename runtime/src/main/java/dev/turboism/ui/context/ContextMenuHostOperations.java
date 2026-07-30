package dev.turboism.ui.context;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.context.ContextMenuSelection;

/** Version-specific native context-menu installation seam. */
public interface ContextMenuHostOperations {

    Registration addItem(ContextMenuContributionDescriptor contribution, MenuAction action);

    @FunctionalInterface
    interface MenuAction {
        void run(ContextMenuSelection selection);
    }
}
