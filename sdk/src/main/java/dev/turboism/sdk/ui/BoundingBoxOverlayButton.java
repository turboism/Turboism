package dev.turboism.sdk.ui;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Preview contribution for one button attached to Cubism's red bounding-box overlay. */
public record BoundingBoxOverlayButton(
        String id, String tooltip, IconVariants icons, int order, Runnable onClick, Set<TargetKind> targetKinds) {
    public BoundingBoxOverlayButton {
        id = requireText(id, "id");
        tooltip = requireText(tooltip, "tooltip");
        icons = Objects.requireNonNull(icons, "icons");
        onClick = Objects.requireNonNull(onClick, "onClick");
        targetKinds = Set.copyOf(Objects.requireNonNull(targetKinds, "targetKinds"));
    }

    /**
     * Creates a button shown for every bounding-box selection.
     *
     * @param id unique contribution identifier
     * @param tooltip hover tooltip text
     * @param icons icon variants for each button state
     * @param order ascending display order
     * @param onClick click handler
     */
    public BoundingBoxOverlayButton(
            final String id, final String tooltip, final IconVariants icons, final int order, final Runnable onClick) {
        this(id, tooltip, icons, order, onClick, Set.of());
    }

    /**
     * Returns a copy of this button whose click handler is {@code callback},
     * leaving id, tooltip, icons, order, and target kinds untouched. Used by the runtime to
     * re-target a declared button at a live handler.
     *
     * @param callback the replacement click handler
     * @return a new button sharing every other component with this one
     * @throws NullPointerException when {@code callback} is {@code null}
     */
    public BoundingBoxOverlayButton withOnClick(final Runnable callback) {
        return new BoundingBoxOverlayButton(
                id, tooltip, icons, order, Objects.requireNonNull(callback, "callback"), targetKinds);
    }

    /**
     * Object kinds that a bounding-box overlay button can target.
     *
     * <p>An empty {@code targetKinds} set matches every bounding-box selection. A
     * non-empty set displays the button only when the active bounding-box selection
     * contains at least one object of a listed kind.</p>
     */
    public enum TargetKind {
        WARP_DEFORMER,
        ROTATION_DEFORMER,
        ART_MESH,
        OTHER
    }

    /**
     * The four icon states of a bounding-box overlay button.
     *
     * <p>Every path is validated as a normalized classpath resource: it must be
     * non-blank, must not start with {@code /}, and must contain neither
     * {@code ..} nor a backslash.</p>
     *
     * @param normal   resource path of the resting icon, always present
     * @param hover    resource path used while the pointer is over the button,
     *                 empty to reuse {@code normal}
     * @param pressed  resource path used while the button is held down, empty to
     *                 reuse {@code normal}
     * @param disabled resource path used when the button is not actionable, empty
     *                 to reuse {@code normal}
     */
    public record IconVariants(
            String normal, Optional<String> hover, Optional<String> pressed, Optional<String> disabled) {
        /**
         * Validates the record components.
         *
         * @throws IllegalArgumentException when any supplied path is blank or not a
         *     normalized classpath resource
         */
        public IconVariants {
            normal = requireResourcePath(normal, "normal");
            hover = normalizeResourcePath(hover, "hover");
            pressed = normalizeResourcePath(pressed, "pressed");
            disabled = normalizeResourcePath(disabled, "disabled");
        }

        /**
         * Builds an icon set with only the resting icon; the runtime reuses it for
         * the hover, pressed, and disabled states.
         *
         * @param resourcePath normalized classpath resource path of the icon
         * @return icon variants carrying {@code resourcePath} and no state overrides
         * @throws IllegalArgumentException when the path is blank or not normalized
         */
        public static IconVariants normal(final String resourcePath) {
            return new IconVariants(resourcePath, Optional.empty(), Optional.empty(), Optional.empty());
        }
    }

    private static Optional<String> normalizeResourcePath(final Optional<String> value, final String name) {
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
