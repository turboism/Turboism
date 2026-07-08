package dev.turboism.sdk.ui.toolbar;

import dev.turboism.sdk.plugin.Registration;

public interface PaletteToolbarRegistry {

    Registration contribute(PaletteToolbarContribution contribution);

    record PaletteToolbarContribution(
        String contributionId,
        String actionId,
        String labelKey,
        String iconResourcePath,
        String paletteId,
        String anchor,
        int order
    ) {}
}
