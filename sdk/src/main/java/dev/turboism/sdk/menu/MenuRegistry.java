package dev.turboism.sdk.menu;

import dev.turboism.sdk.plugin.Registration;

/**
 * Registry for menu contributions.
 */
public interface MenuRegistry {

    Registration contribute(MenuContribution contribution);

    interface MenuContribution {
        /**
         * Slash-delimited path whose first segment is a plugin-owned top-level
         * menu, optional middle segments are submenus, and final segment is the item.
         */
        String menuPath();

        String actionId();

        int order();
    }
}
