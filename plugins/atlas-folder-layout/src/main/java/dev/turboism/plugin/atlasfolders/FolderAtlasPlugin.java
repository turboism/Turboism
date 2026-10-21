package dev.turboism.plugin.atlasfolders;

import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.TurboismPlugin;
import java.util.Objects;

/** Owns folder-based multi-page authoring; packing algorithms come solely from the registry. */
public final class FolderAtlasPlugin implements TurboismPlugin {
    private PluginContext context;

    @Override
    public void init(PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public void enable() {
        new FolderAtlasAction(Objects.requireNonNull(context, "Plugin is not initialized")).register();
    }

    @Override
    public void disable() {}

    @Override
    public void shutdown() {}
}
