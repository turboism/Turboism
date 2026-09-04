package dev.turboism.ui.palette;

import dev.turboism.ui.filter.PaletteFilterHostOperations;
import dev.turboism.ui.toolbar.EditorUiPluginResourceRegistry;

/**
 * Connection-scoped owner of Palette discovery, polling, recovery, Toolbar buttons, Filter controls,
 * and reversible host cleanup for PARAMETER, DEFORMER, SCENE, and LOG.
 */
public final class PaletteSurfaceCoordinator extends PaletteFilterHostOperations {

    public PaletteSurfaceCoordinator(final EditorUiPluginResourceRegistry resources) {
        super(resources);
    }
}
