package dev.turboism.core.runtime;

/**
 * A runtime task submitted by or on behalf of a plugin.
 *
 * @param taskType           the operation type (e.g. {@code lifecycle.init}, {@code action.handle})
 * @param pluginId           the plugin that owns the task
 * @param payloadDescription a human-readable description of the payload
 * @param declaredCapability the capability declared by the plugin (e.g. {@code sidecar})
 */
public record PluginTask(
    String taskType,
    String pluginId,
    String payloadDescription,
    String declaredCapability
) {
}
