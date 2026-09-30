package dev.turboism.sdk.action;

import java.util.List;

/**
 * Enumerates and invokes actions registered by plugins through {@link ActionRegistry}.
 *
 * <p>The catalog is a detached point-in-time view of every plugin's action registry,
 * keyed by owning plugin id. Both enumeration and invocation require the caller to hold
 * {@code turboism.action.invoke}; without it enumeration returns an empty list and
 * invocation is refused. Handlers always run on the owning plugin's scheduler.</p>
 */
public interface ActionCatalogService {

    /**
     * Returns a detached snapshot of every registered action, in registration order.
     *
     * @return the registered action descriptors; empty when nothing is registered or the
     *     caller lacks {@code turboism.action.invoke}
     */
    List<ActionDescriptor> actions();

    /**
     * Routes {@code actionId} on {@code pluginId}'s registry to its handler.
     *
     * <p>A missing plugin or action id is a silent no-op, matching the runtime action
     * router's semantics. Invocation carries no UI or selection context.</p>
     *
     * @param pluginId owner of the action's registry
     * @param actionId the action to invoke
     * @throws dev.turboism.sdk.permission.CubismPermissionException when the caller lacks
     *     {@code turboism.action.invoke}
     */
    void invoke(String pluginId, String actionId);

    /**
     * Reports whether a live runtime surface backs this instance.
     *
     * @return {@code false} only for the {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    /** Returns the fail-closed service that enumerates and invokes nothing. */
    static ActionCatalogService unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Singleton fail-closed implementation returned by {@link #unavailable()}. */
    enum Unavailable implements ActionCatalogService {
        INSTANCE;

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public List<ActionDescriptor> actions() {
            return List.of();
        }

        @Override
        public void invoke(final String pluginId, final String actionId) {}
    }
}
