package dev.turboism.sdk.permission;

/**
 * Declared permission as read from plugin meta.
 */
public interface PluginPermission {

    String id();

    String scope();

    String reason();
}
