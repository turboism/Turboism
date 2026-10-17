package dev.turboism.sdk.ui.appearance;

import dev.turboism.sdk.ui.resource.UiIconRef;
import java.util.Objects;

/**
 * Declarative leading action for one model-owned parameter or parameter-group row.
 *
 * @param editingDisabled whether this parameter's value editor and drag surface reject user input;
 *     the leading action remains usable. A combined row shares its drag surface between axes,
 *     while each axis retains its own numeric editor. Group rows do not have value editors.
 */
public record PaletteRowAction(UiIconRef icon, State state, String tooltip, Runnable onClick, boolean editingDisabled) {
    /** Leading action without an editing restriction (for example a group row). */
    public PaletteRowAction(UiIconRef icon, State state, String tooltip, Runnable onClick) {
        this(icon, state, tooltip, onClick, false);
    }

    /** Visual state; mixed groups remain distinct from fully locked and unlocked rows. */
    public enum State {
        UNLOCKED,
        LOCKED,
        MIXED
    }

    public PaletteRowAction {
        Objects.requireNonNull(icon, "icon");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(tooltip, "tooltip");
        Objects.requireNonNull(onClick, "onClick");
    }
}
