package dev.turboism.sdk.ui.workspace.layout;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;

/**
 * One docked palette tab identity. {@code paletteId} matches the string form used by
 * {@code PanelTabSelection} ({@code String.valueOf} of the host palette id), so custom
 * contributions can be matched with their {@code turboism:&lt;plugin&gt;:&lt;contributionId&gt;}
 * naming.
 */
@PreviewApi
public record PaletteTab(String paletteId) {

    public PaletteTab {
        paletteId = Objects.requireNonNull(paletteId, "paletteId");
        if (paletteId.isBlank()) {
            throw new IllegalArgumentException("paletteId must not be blank");
        }
    }
}
