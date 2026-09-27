package dev.turboism.sdk.ui.appearance.model;

import dev.turboism.sdk.ui.appearance.NativeLabelColor;
import dev.turboism.sdk.ui.appearance.NativeLabelColorState;
import dev.turboism.sdk.ui.appearance.PaletteEntry;

import java.util.Optional;

/** UI projection of one Cubism ParameterGroup in the parameter palette. */
public interface ParameterGroupAppearance {

    /** Returns the group's entry in the parameter palette, when a renderer exposes it. */
    Optional<PaletteEntry> parameterPaletteEntry();

    /** Returns the group's native label color state, when the host exposes it. */
    Optional<NativeLabelColorState> nativeLabelColor();

    /** Writes the group's native label color through the Editor authoring path. */
    void setNativeLabelColor(NativeLabelColor color);

    /** Returns a fail-closed projection: no entries are reported and writes throw. */
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
