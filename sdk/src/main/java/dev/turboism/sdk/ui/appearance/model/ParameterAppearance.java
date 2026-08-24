package dev.turboism.sdk.ui.appearance.model;

import dev.turboism.sdk.ui.appearance.PaletteEntry;

import java.util.Optional;

/** UI projection of one Cubism Parameter. Parameters have no native label color. */
public interface ParameterAppearance {

    Optional<PaletteEntry> parameterPaletteEntry();

    static ParameterAppearance unavailable() {
        return () -> Optional.empty();
    }
}
