package dev.turboism.sdk.ui.workspace.layout;


import java.util.List;
import java.util.Objects;

/**
 * A palette box leaf of the dock layout tree. Its {@code tabs} are the docked palettes in
 * host order ({@code CPMPaletteBox.getPalettes()}); an empty list is reported faithfully
 * when the host tree still contains an empty box.
 */
public record PaletteDock(List<PaletteTab> tabs) implements DockComponent {

    public PaletteDock {
        tabs = List.copyOf(Objects.requireNonNull(tabs, "tabs"));
    }
}
