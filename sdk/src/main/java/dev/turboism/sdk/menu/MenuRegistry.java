package dev.turboism.sdk.menu;

import dev.turboism.sdk.plugin.Registration;

/**
 * Registry for menu contributions.
 */
public interface MenuRegistry {

    Registration contribute(MenuContribution contribution);

    interface MenuContribution {
        String menuPath();

        String actionId();

        int order();
    }
}
