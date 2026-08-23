package dev.turboism.sdk.appearance;


import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Bounded semantic colors understood by the Editor appearance capability. */
public record AppearancePalette(
    String accent,
    String background,
    String surface,
    String inputBackground,
    String foreground,
    String mutedForeground,
    String selectionBackground,
    String selectionForeground,
    String border,
    String viewportBackground
) {
    private static final Pattern COLOR = Pattern.compile("#[0-9A-F]{6}");

    public AppearancePalette {
        accent = color(accent, "accent");
        background = color(background, "background");
        surface = color(surface, "surface");
        inputBackground = color(inputBackground, "inputBackground");
        foreground = color(foreground, "foreground");
        mutedForeground = color(mutedForeground, "mutedForeground");
        selectionBackground = color(selectionBackground, "selectionBackground");
        selectionForeground = color(selectionForeground, "selectionForeground");
        border = color(border, "border");
        viewportBackground = color(viewportBackground, "viewportBackground");
    }

    private static String color(final String value, final String name) {
        final String normalized = Objects.requireNonNull(value, name)
            .toUpperCase(Locale.ROOT);
        if (!COLOR.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be a #RRGGBB color");
        }
        return normalized;
    }
}
