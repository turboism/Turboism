package dev.turboism.sdk.ui.appearance;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.model.Color;

import java.util.Objects;

/**
 * Bounded native label-background request for Editor control appearance.
 *
 * <p>{@link Default} restores the native {@code UNDEFINED} label type, {@link Preset} keeps the
 * exact native preset identity, and {@link Custom} carries canonical RGBA in {@code [0,1]}.</p>
 */
@PreviewApi
public sealed interface NativeControlBackground {

    /** Restore the native {@code UNDEFINED} label type. */
    @PreviewApi
    record Default() implements NativeControlBackground {
    }

    /** One exact native label-background preset. */
    @PreviewApi
    record Preset(PresetColor color) implements NativeControlBackground {

        public Preset {
            color = Objects.requireNonNull(color, "color");
        }
    }

    /** A canonical custom RGBA label background; components must be finite and in {@code [0,1]}. */
    @PreviewApi
    record Custom(Color color) implements NativeControlBackground {

        public Custom {
            color = Objects.requireNonNull(color, "color");
            requireUnit(color.red(), "red");
            requireUnit(color.green(), "green");
            requireUnit(color.blue(), "blue");
            requireUnit(color.alpha(), "alpha");
        }

        private static void requireUnit(final float value, final String name) {
            if (!Float.isFinite(value) || value < 0.0F || value > 1.0F) {
                throw new IllegalArgumentException(
                    name + " must be finite and in [0,1], but was " + value
                );
            }
        }
    }
}
