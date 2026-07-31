package dev.turboism.plugin.scenepalette;

import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.Objects;

/** Scene palette sorting and manual-order workflow. */
public final class ScenePaletteEnhancerPlugin implements TurboismPlugin {

    private PluginContext context;
    private SceneTableSorter sorter;

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public void enable() {
        if (context == null) {
            throw new IllegalStateException("Scene Palette Enhancer must be initialized before enable.");
        }
        disable();
        sorter = new SceneTableSorter(
            context.sceneTable(),
            ManualOrderStore.storage(context.storage(), context.logger()),
            context.logger()
        );
        sorter.enable();
    }

    @Override
    public void disable() {
        if (sorter != null) {
            sorter.close();
            sorter = null;
        }
    }

    @Override
    public void shutdown() {
        disable();
        context = null;
    }
}
