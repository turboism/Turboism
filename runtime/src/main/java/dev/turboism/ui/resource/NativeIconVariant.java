package dev.turboism.ui.resource;

import dev.turboism.sdk.ui.resource.CubismIcon;
import java.util.Objects;

/** Runtime-only presentation key for the reviewed logical-16 object-icon family. */
public record NativeIconVariant(CubismIcon icon, Theme theme, int scalePercent, boolean disabled) {
    /** Reviewed icon theme variant; the catalog ships light and dark sheets. */
    public enum Theme {
        LIGHT,
        DARK
    }

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
        final String prefix =
                switch (icon) {
                    case ART_MESH -> "Artmesh-Colored_16x16_";
                    case WARP_DEFORMER -> "WarpDef-Colored_16x16_";
                    case ROTATION_DEFORMER -> "RotDef-Colored_16x16_";
                    case PART -> "Folder-Colored_16x16_";
                    case LOCK -> "Lock_16x16_";
                    case LOCK_PLACEHOLDER -> "None_16x16_";
                };
        return "res/image_" + (theme == Theme.LIGHT ? "light" : "dark") + "/icons/" + prefix
                + (disabled ? "Disabled" : "Default") + ".scale-" + scalePercent + ".png";
    }
}
