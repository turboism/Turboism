package dev.turboism.sdk.ui.appearance.model;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.ui.appearance.PaletteEntry;

import java.util.Optional;

/** UI projections of one Cubism ArtMesh where a verified renderer seam exists. */
@PreviewApi
public interface DrawableAppearance {

    Optional<PaletteEntry> partPaletteEntry();

    Optional<PaletteEntry> deformerPaletteEntry();

    static DrawableAppearance unavailable() {
        return new DrawableAppearance() {
            @Override public Optional<PaletteEntry> partPaletteEntry() { return Optional.empty(); }
            @Override public Optional<PaletteEntry> deformerPaletteEntry() { return Optional.empty(); }
        };
    }
}
