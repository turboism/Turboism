package dev.turboism.sdk.ui.appearance.model;

import dev.turboism.sdk.ui.appearance.NativeLabelColor;
import dev.turboism.sdk.ui.appearance.NativeLabelColorState;
import dev.turboism.sdk.ui.appearance.PaletteEntry;

import java.util.Optional;

/** UI projections of one Cubism Deformer in the verified Cubism palettes. */
public interface DeformerAppearance {

    /** Returns the Deformer's entry in the Part palette, when a verified renderer exposes it. */
    Optional<PaletteEntry> partPaletteEntry();

    /** Returns the Deformer's entry in the Deformer palette, when a verified renderer exposes it. */
    Optional<PaletteEntry> deformerPaletteEntry();

    /** Returns the Deformer's native label color state, when the host exposes it. */
    Optional<NativeLabelColorState> nativeLabelColor();

    /** Writes the Deformer's native label color through the Editor authoring path. */
    void setNativeLabelColor(NativeLabelColor color);

    /** Returns a fail-closed projection: no entries are reported and writes throw. */
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
