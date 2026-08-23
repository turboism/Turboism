package dev.turboism.sdk.ui.appearance;


import java.util.Objects;

/** Exact Cubism Editor native label-color value. */
public sealed interface NativeLabelColor permits NativeLabelColor.Default, NativeLabelColor.Preset,
    NativeLabelColor.Custom {

    /** Restore the host's default/undefined label color. */
    record Default() implements NativeLabelColor { }

    /** Preserve one exact host label-color preset. */
    record Preset(PresetColor color) implements NativeLabelColor {
        public Preset {
            color = Objects.requireNonNull(color, "color");
        }
    }

    /** One canonical custom RGBA label color. */
    record Custom(UiColor color) implements NativeLabelColor {
        public Custom {
            color = Objects.requireNonNull(color, "color");
        }
    }
}
