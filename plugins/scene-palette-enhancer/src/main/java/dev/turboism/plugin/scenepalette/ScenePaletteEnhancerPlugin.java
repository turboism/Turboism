package dev.turboism.plugin.scenepalette;

import dev.turboism.sdk.event.SubscribeEvent;
import dev.turboism.sdk.plugin.AbstractTurboismPlugin;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.ui.table.SceneTableHeaderClickEvent;
import dev.turboism.sdk.ui.table.SceneTableItemOrderEvent;
import dev.turboism.sdk.ui.table.SceneTableService;
import dev.turboism.sdk.ui.table.SceneTableSnapshotEvent;

/** Scene palette sorting and manual-order workflow. */
public final class ScenePaletteEnhancerPlugin extends AbstractTurboismPlugin {

    private SceneTableSorter sorter;
    private ManualOrderStore store;

    @Override
    public void init(final PluginContext context) {
        try {
            super.init(context);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Scene Palette Enhancer initialization failed", e);
        }
    }

    @Override
    public void enable() {
        try {
            super.enable();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Scene Palette Enhancer must be initialized before enable.", e);
        }
        disable();
        // Reuse one store across disable/enable cycles so a queued write from a previous
        // enable cannot be overtaken by, and then overwrite, a newer write after re-enable.
        if (store == null) {
            if (context().storage().isAvailable()) {
                store = ManualOrderStore.storage(context().storage(), context().logger());
            } else {
                store = ManualOrderStore.unavailable();
                context().logger().info("Scene manual order storage is unavailable; persistence is disabled.");
            }
        }
        sorter = new SceneTableSorter(
                context().services().require(SceneTableService.class),
                store,
                context().logger());
    }

    /** Forwards a scene-table header click to the active palette sorter. */
    @SubscribeEvent
    public void onHeaderClick(final SceneTableHeaderClickEvent event) {
        if (sorter != null) {
            sorter.onHeaderClick(event.click());
        }
    }

    /** Forwards the latest scene-table snapshot to the active palette sorter. */
    @SubscribeEvent
    public void onSnapshot(final SceneTableSnapshotEvent event) {
        if (sorter != null) {
            sorter.onSnapshot(event.snapshot());
        }
    }

    /** Forwards a scene-table order change to the active palette sorter. */
    @SubscribeEvent
    public void onItemOrderChanged(final SceneTableItemOrderEvent event) {
        if (sorter != null) {
            sorter.onItemOrderChanged(event.change());
        }
    }

    @Override
    public void disable() {
        if (sorter != null) {
            sorter.close();
            sorter = null;
        }
        try {
            super.disable();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Scene Palette Enhancer disable failed", e);
        }
    }

    @Override
    public void shutdown() {
        disable();
        store = null;
        try {
            super.shutdown();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Scene Palette Enhancer shutdown failed", e);
        }
    }
}
