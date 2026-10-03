package dev.turboism.sdk.ui.appearance.model;

import dev.turboism.sdk.ui.appearance.PaletteEntry;
import java.util.Optional;

/** UI projection of one Cubism Parameter in the parameter palette. */
public interface ParameterAppearance {

    /** Returns the parameter's entry in the parameter palette, when a renderer exposes it. */
    Optional<PaletteEntry> parameterPaletteEntry();

    /**
     * Returns whether the parameter is shown in the parameter palette, or empty when the
     * backend cannot report a palette state. Mirrors the {@code visible} flag of
     * {@link dev.turboism.sdk.cubism.ParameterSnapshot}; a {@code false} value means the
     * parameter is hidden in the palette.
     */
    default Optional<Boolean> visible() {
        return Optional.empty();
    }

    /**
     * Returns whether the palette permits editing the parameter's value, or empty when the
     * backend cannot report a palette state. Mirrors the {@code editable} flag of
     * {@link dev.turboism.sdk.cubism.ParameterSnapshot}; a visible parameter may still be
     * locked ({@code false}).
     */
    default Optional<Boolean> editable() {
        return Optional.empty();
    }

    /** Returns a fail-closed projection: no entries are reported. */
    static ParameterAppearance unavailable() {
        return () -> Optional.empty();
    }
}
