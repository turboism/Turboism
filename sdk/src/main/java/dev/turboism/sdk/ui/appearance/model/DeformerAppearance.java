package dev.turboism.sdk.ui.appearance.model;

import dev.turboism.sdk.ui.appearance.NativeLabelColor;
import dev.turboism.sdk.ui.appearance.NativeLabelColorState;
import dev.turboism.sdk.ui.appearance.PaletteEntry;

import java.util.Optional;

/** UI projections of one Cubism Deformer in the verified Cubism palettes. */
public interface DeformerAppearance {

    Optional<PaletteEntry> partPaletteEntry();

    Optional<PaletteEntry> deformerPaletteEntry();

    Optional<NativeLabelColorState> nativeLabelColor();

    void setNativeLabelColor(NativeLabelColor color);

    static DeformerAppearance unavailable() {
        return new DeformerAppearance() {
            @Override public Optional<PaletteEntry> partPaletteEntry() { return Optional.empty(); }
            @Override public Optional<PaletteEntry> deformerPaletteEntry() { return Optional.empty(); }
            @Override public Optional<NativeLabelColorState> nativeLabelColor() { return Optional.empty(); }
            @Override public void setNativeLabelColor(final NativeLabelColor color) {
                throw new UnsupportedOperationException("Cubism Deformer label color is unavailable");
            }
        };
    }
}
