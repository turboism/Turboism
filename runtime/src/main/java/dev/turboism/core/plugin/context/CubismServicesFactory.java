package dev.turboism.core.plugin.context;

@FunctionalInterface
interface CubismServicesFactory {

    CubismContextServices create(CorePluginContext.Dependencies dependencies);
}
