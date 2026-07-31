package dev.turboism.sdk.ui;

import java.util.Objects;
import java.util.Optional;

/** Preview contribution for one button attached to Cubism's red bounding-box overlay. */
public record BoundingBoxOverlayButton(
    String id,
    String tooltip,
    IconVariants icons,
    int order,
    Runnable onClick
) {
    public BoundingBoxOverlayButton {
        id = requireText(id, "id");
        tooltip = requireText(tooltip, "tooltip");
        icons = Objects.requireNonNull(icons, "icons");
        onClick = Objects.requireNonNull(onClick, "onClick");
    }

    public BoundingBoxOverlayButton withOnClick(final Runnable callback) {
        return new BoundingBoxOverlayButton(
            id,
            tooltip,
            icons,
            order,
            Objects.requireNonNull(callback, "callback")
        );
    }

    public record IconVariants(
        String normal,
        Optional<String> hover,
        Optional<String> pressed,
        Optional<String> disabled
    ) {
        public IconVariants {
            normal = requireResourcePath(normal, "normal");
            hover = normalizeResourcePath(hover, "hover");
            pressed = normalizeResourcePath(pressed, "pressed");
            disabled = normalizeResourcePath(disabled, "disabled");
        }

        public static IconVariants normal(final String resourcePath) {
            return new IconVariants(
                resourcePath,
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
            );
        }
    }

    private static Optional<String> normalizeResourcePath(
        final Optional<String> value,
        final String name
    ) {
        Objects.requireNonNull(value, name);
        return value.map(path -> requireResourcePath(path, name));
    }

    private static String requireResourcePath(final String value, final String name) {
        final String path = requireText(value, name);
        if (path.startsWith("/") || path.contains("..") || path.contains("\\")) {
            throw new IllegalArgumentException(name + " must be a normalized classpath resource");
        }
        return path;
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
