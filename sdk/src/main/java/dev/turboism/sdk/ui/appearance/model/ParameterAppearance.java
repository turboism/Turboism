package dev.turboism.sdk.ui.appearance.model;

import dev.turboism.sdk.ui.appearance.PaletteEntry;

import java.util.Optional;

/** UI projection of one Cubism Parameter. Parameters have no native label color. */
public interface ParameterAppearance {

    /** Returns the Parameter's entry in the parameter palette, when a renderer exposes it. */
    Optional<PaletteEntry> parameterPaletteEntry();

    /** Returns a fail-closed projection that reports no palette entry. */
    static ParameterAppearance unavailable() {
        return () -> Optional.empty();
    }
}
