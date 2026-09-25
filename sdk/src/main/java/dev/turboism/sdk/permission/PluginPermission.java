package dev.turboism.sdk.permission;

/**
 * Declared permission as read from plugin meta.
 */
public interface PluginPermission {

    /** Returns the permission's stable identifier. */
    String id();

    /** Returns the declared scope the permission applies to. */
    String scope();

    /** Returns the plugin-declared justification for the permission. */
    String reason();
}
