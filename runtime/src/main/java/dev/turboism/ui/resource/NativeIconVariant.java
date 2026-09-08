package dev.turboism.ui.resource;

import dev.turboism.sdk.ui.resource.CubismIcon;

import java.util.Objects;

/** Runtime-only presentation key for the reviewed logical-16 object-icon family. */
public record NativeIconVariant(CubismIcon icon, Theme theme, int scalePercent, boolean disabled) {
    public enum Theme { LIGHT, DARK }

    public NativeIconVariant {
        Objects.requireNonNull(icon, "icon");
        Objects.requireNonNull(theme, "theme");
        if (scalePercent < 100 || scalePercent > 200 || scalePercent % 25 != 0) {
            throw new IllegalArgumentException("scalePercent must be 100, 125, 150, 175 or 200");
        }
    }

    int physicalSize() {
        return 16 * scalePercent / 100;
    }

    String resourcePath() {
        final String object = switch (icon) {
            case ART_MESH -> "Artmesh";
            case WARP_DEFORMER -> "WarpDef";
            case ROTATION_DEFORMER -> "RotDef";
        };
        return "res/image_" + (theme == Theme.LIGHT ? "light" : "dark") + "/icons/" + object
            + "-Colored_16x16_" + (disabled ? "Disabled" : "Default") + ".scale-" + scalePercent + ".png";
    }
}
