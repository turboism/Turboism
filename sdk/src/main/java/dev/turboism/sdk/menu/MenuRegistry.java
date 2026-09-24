package dev.turboism.sdk.menu;

import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;

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

        /**
         * Returns a menu contribution with the given path, bound action and order.
         *
         * @param menuPath slash-delimited menu path
         * @param actionId action invoked on click
         * @param order position hint within the menu
         */
        static MenuContribution of(final String menuPath, final String actionId, final int order) {
            return new SimpleMenuContribution(menuPath, actionId, order);
        }
    }

    /**
     * Value-style {@link MenuContribution} implementation.
     *
     * @param menuPath slash-delimited menu path
     * @param actionId action invoked on click
     * @param order position hint within the menu
     */
    record SimpleMenuContribution(String menuPath, String actionId, int order)
        implements MenuContribution {
        public SimpleMenuContribution {
            Objects.requireNonNull(menuPath, "menuPath");
            Objects.requireNonNull(actionId, "actionId");
        }
    }
}
