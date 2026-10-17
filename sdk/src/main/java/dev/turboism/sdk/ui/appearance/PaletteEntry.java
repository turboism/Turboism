package dev.turboism.sdk.ui.appearance;

import dev.turboism.sdk.plugin.Registration;
import java.util.Optional;

/** One model-owned projection of a verified Cubism palette entry. */
public interface PaletteEntry {

    /**
     * Applies a font-size override, in points. Closing the returned {@link Registration}
     * removes the override.
     */
    Registration overrideFontSize(float points);

    /**
     * Applies a bold override. Closing the returned {@link Registration} removes the override.
     */
    Registration overrideBold(boolean bold);

    /**
     * Applies an italic override. Closing the returned {@link Registration} removes the
     * override.
     */
    Registration overrideItalic(boolean italic);

    /**
     * Applies a text-color override. Closing the returned {@link Registration} removes the
     * override.
     */
    Registration overrideTextColor(UiColor color);

    /**
     * Applies a background-color override. Closing the returned {@link Registration} removes
     * the override.
     */
    Registration overrideBackgroundColor(UiColor color);

    /**
     * Installs a leading icon action on this parameter or parameter-group row.
     * The action is scoped to the live model and plugin; closing the registration restores
     * the original native label. Other palettes may reject this operation.
     */
    default Registration overrideLeadingAction(final PaletteRowAction action) {
        java.util.Objects.requireNonNull(action, "action");
        throw new UnsupportedOperationException("Palette row actions are unavailable");
    }

    /** Framework-resolved transient overrides, independent of renderer state. */
    PaletteEntryState resolved();

    /** Renderer state observed when a verified renderer exposes this entry. */
    Optional<PaletteEntryState> actual();

    /** Returns a fail-closed entry: overrides throw and no state is reported. */
    static PaletteEntry unavailable() {
        return new PaletteEntry() {
            @Override
            public Registration overrideFontSize(final float points) {
                throw unavailableOperation();
            }

            @Override
            public Registration overrideBold(final boolean bold) {
                throw unavailableOperation();
            }

            @Override
            public Registration overrideItalic(final boolean italic) {
                throw unavailableOperation();
            }

            @Override
            public Registration overrideTextColor(final UiColor color) {
                throw unavailableOperation();
            }

            @Override
            public Registration overrideBackgroundColor(final UiColor color) {
                throw unavailableOperation();
            }

            @Override
            public PaletteEntryState resolved() {
                return PaletteEntryState.empty();
            }

            @Override
            public Optional<PaletteEntryState> actual() {
                return Optional.empty();
            }
        };
    }

    private static UnsupportedOperationException unavailableOperation() {
        return new UnsupportedOperationException("Cubism palette entry is unavailable");
    }
}
