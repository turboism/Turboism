package dev.turboism.core.plugin.context;

import dev.turboism.task.RuntimePluginTaskScheduler;

@FunctionalInterface
interface CubismServicesFactory {

    CubismContextServices create(
        CorePluginContext.Dependencies dependencies,
        RuntimePluginTaskScheduler pluginTasks
    );
}
