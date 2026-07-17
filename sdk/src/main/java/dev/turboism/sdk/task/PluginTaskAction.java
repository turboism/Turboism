package dev.turboism.sdk.task;

import dev.turboism.sdk.plugin.CancellationToken;

@FunctionalInterface
public interface PluginTaskAction {
    void run(CancellationToken cancellationToken) throws Exception;
}
