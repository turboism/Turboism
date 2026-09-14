package dev.turboism.sdk.menu;

import dev.turboism.sdk.plugin.Registration;

/**
 * Registry for menu contributions.
 */
public interface MenuRegistry {

    /**
     * Registers a menu contribution. Closing the returned {@link Registration} removes the
     * contributed item.
     */
    Registration contribute(MenuContribution contribution);

    /** One menu item contribution bound to a registered action. */
    interface MenuContribution {
        /**
         * Slash-delimited path whose first segment is a plugin-owned top-level
         * menu, optional middle segments are submenus, and final segment is the item.
         */
        String menuPath();

        /** Returns the identifier of the registered action this item invokes. */
        String actionId();

        /** Returns the item's ordering position within its menu segment. */
        int order();
    }
}
