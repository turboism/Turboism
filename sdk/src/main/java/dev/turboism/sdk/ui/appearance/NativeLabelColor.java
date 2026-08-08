package dev.turboism.sdk.ui.appearance;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;

/** Exact Cubism Editor native label-color value. */
@PreviewApi
public sealed interface NativeLabelColor permits NativeLabelColor.Default, NativeLabelColor.Preset,
    NativeLabelColor.Custom {

    /** Restore the host's default/undefined label color. */
    @PreviewApi
    record Default() implements NativeLabelColor { }

    /** Preserve one exact host label-color preset. */
    @PreviewApi
    record Preset(PresetColor color) implements NativeLabelColor {
        public Preset {
            color = Objects.requireNonNull(color, "color");
        }
    }

    /** One canonical custom RGBA label color. */
    @PreviewApi
    record Custom(UiColor color) implements NativeLabelColor {
        public Custom {
            color = Objects.requireNonNull(color, "color");
        }
    }
}
