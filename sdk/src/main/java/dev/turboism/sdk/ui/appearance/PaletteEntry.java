package dev.turboism.sdk.ui.appearance;

import dev.turboism.sdk.plugin.Registration;

import java.util.Optional;

/** One model-owned projection of a verified Cubism palette entry. */
public interface PaletteEntry {

    Registration overrideFontSize(float points);

    Registration overrideBold(boolean bold);

    Registration overrideItalic(boolean italic);

    Registration overrideTextColor(UiColor color);

    Registration overrideBackgroundColor(UiColor color);

    /** Framework-resolved transient overrides, independent of renderer state. */
    PaletteEntryState resolved();

    /** Renderer state observed when a verified renderer exposes this entry. */
    Optional<PaletteEntryState> actual();

    static PaletteEntry unavailable() {
        return new PaletteEntry() {
            @Override public Registration overrideFontSize(final float points) {
                throw unavailableOperation();
            }

            @Override public Registration overrideBold(final boolean bold) {
                throw unavailableOperation();
            }

            @Override public Registration overrideItalic(final boolean italic) {
                throw unavailableOperation();
            }

            @Override public Registration overrideTextColor(final UiColor color) {
                throw unavailableOperation();
            }

            @Override public Registration overrideBackgroundColor(final UiColor color) {
                throw unavailableOperation();
            }

            @Override public PaletteEntryState resolved() {
                return PaletteEntryState.empty();
            }

            @Override public Optional<PaletteEntryState> actual() {
                return Optional.empty();
            }
        };
    }

    private static UnsupportedOperationException unavailableOperation() {
        return new UnsupportedOperationException("Cubism palette entry is unavailable");
    }
}
