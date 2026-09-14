package dev.turboism.sdk.ui.appearance.model;

import dev.turboism.sdk.ui.appearance.NativeLabelColor;
import dev.turboism.sdk.ui.appearance.NativeLabelColorState;
import dev.turboism.sdk.ui.appearance.PaletteEntry;

import java.util.Optional;

/** UI projection of one Cubism Part. */
public interface PartAppearance {

    /** Returns the Part's entry in the Part palette, when a renderer exposes it. */
    Optional<PaletteEntry> partPaletteEntry();

    /** Returns the Part's native label color state, when the host exposes it. */
    Optional<NativeLabelColorState> nativeLabelColor();

    /** Writes the Part's native label color through the Editor authoring path. */
    void setNativeLabelColor(NativeLabelColor color);

    /** Returns a fail-closed projection: no entries are reported and writes throw. */
    static PartAppearance unavailable() {
        return new PartAppearance() {
            @Override public Optional<PaletteEntry> partPaletteEntry() { return Optional.empty(); }
            @Override public Optional<NativeLabelColorState> nativeLabelColor() { return Optional.empty(); }
            @Override public void setNativeLabelColor(final NativeLabelColor color) {
                throw new UnsupportedOperationException("Cubism Part label color is unavailable");
            }
        };
    }
}
