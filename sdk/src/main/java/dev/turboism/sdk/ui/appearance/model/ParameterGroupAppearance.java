package dev.turboism.sdk.ui.appearance.model;

import dev.turboism.sdk.ui.appearance.NativeLabelColor;
import dev.turboism.sdk.ui.appearance.NativeLabelColorState;
import dev.turboism.sdk.ui.appearance.PaletteEntry;

import java.util.Optional;

/** UI projection of one Cubism ParameterGroup in the parameter palette. */
public interface ParameterGroupAppearance {

    Optional<PaletteEntry> parameterPaletteEntry();

    Optional<NativeLabelColorState> nativeLabelColor();

    void setNativeLabelColor(NativeLabelColor color);

    static ParameterGroupAppearance unavailable() {
        return new ParameterGroupAppearance() {
            @Override public Optional<PaletteEntry> parameterPaletteEntry() { return Optional.empty(); }
            @Override public Optional<NativeLabelColorState> nativeLabelColor() { return Optional.empty(); }
            @Override public void setNativeLabelColor(final NativeLabelColor color) {
                throw new UnsupportedOperationException("Cubism ParameterGroup label color is unavailable");
            }
        };
    }
}
