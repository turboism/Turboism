package dev.turboism.kernel.context;

import dev.turboism.task.RuntimePluginTaskScheduler;
import java.util.function.BooleanSupplier;

@FunctionalInterface
interface CubismServicesFactory {

    CubismContextServices create(CorePluginContext.Dependencies dependencies, RuntimePluginTaskScheduler pluginTasks);

    default CubismContextServices create(
            CorePluginContext.Dependencies dependencies,
            RuntimePluginTaskScheduler pluginTasks,
            BooleanSupplier generationAdmission) {
        return create(dependencies, pluginTasks);
    }
}
