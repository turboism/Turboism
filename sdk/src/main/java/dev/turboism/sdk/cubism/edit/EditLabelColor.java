package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.CubismEditor;
import java.util.Objects;
import java.util.Optional;

/**
 * A palette label color: a preset {@link EditLabelColorType}, or {@link EditLabelColorType#CUSTOM}
 * together with a {@code #RRGGBB}/{@code #RRGGBBAA} hex string.
 *
 * @param type the label color preset
 * @param customColor the custom hex color; required iff {@code type} is
 *     {@link EditLabelColorType#CUSTOM}, absent otherwise
 */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public record EditLabelColor(EditLabelColorType type, Optional<String> customColor) {

    public EditLabelColor {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(customColor, "customColor");
        if (type == EditLabelColorType.CUSTOM && customColor.isEmpty()) {
            throw new IllegalArgumentException("CUSTOM label color requires a customColor");
        }
        if (type != EditLabelColorType.CUSTOM && customColor.isPresent()) {
            throw new IllegalArgumentException("customColor is only valid with CUSTOM label color");
        }
    }

    /** Returns a preset label color with no custom hex value. */
    public static EditLabelColor of(final EditLabelColorType type) {
        return new EditLabelColor(type, Optional.empty());
    }

    /** Returns a custom label color with the given hex value. */
    public static EditLabelColor custom(final String customColor) {
        return new EditLabelColor(
            EditLabelColorType.CUSTOM,
            Optional.of(Objects.requireNonNull(customColor, "customColor")));
    }
}
