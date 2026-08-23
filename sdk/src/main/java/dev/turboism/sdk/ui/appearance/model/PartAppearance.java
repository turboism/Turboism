package dev.turboism.sdk.ui.appearance.model;

import dev.turboism.sdk.ui.appearance.NativeLabelColor;
import dev.turboism.sdk.ui.appearance.NativeLabelColorState;
import dev.turboism.sdk.ui.appearance.PaletteEntry;

import java.util.Optional;

/** UI projection of one Cubism Part. */
public interface PartAppearance {

    Optional<PaletteEntry> partPaletteEntry();

    Optional<NativeLabelColorState> nativeLabelColor();

    void setNativeLabelColor(NativeLabelColor color);

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
