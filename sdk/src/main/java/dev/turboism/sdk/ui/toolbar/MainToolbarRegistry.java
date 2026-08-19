package dev.turboism.sdk.ui.toolbar;

import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;
import java.util.Optional;

/**
 * Registry for plugin-contributed buttons on the Cubism main toolbar.
 *
 * <p>Contributions are declarative: a plugin supplies identity, localisation keys, icon variants
 * and a semantic placement, and the runtime owns attachment, ordering, reconciliation and removal.
 * Plugins never touch host toolbar widgets.</p>
 */
public interface MainToolbarRegistry {

    /**
     * Contributes one toolbar entry in the original anchor-string shape.
     *
     * @param contribution the entry to attach
     * @return a registration whose closure removes the entry
     */
    Registration contribute(MainToolbarContribution contribution);

    /**
     * Contributes one toolbar button using the typed icon and placement model.
     *
     * @param contribution the button to attach
     * @return a registration whose closure removes the button
     * @throws NullPointerException when {@code contribution} is null
     */
    default Registration contributeButton(final MainToolbarButtonContribution contribution) {
        return contribute(Objects.requireNonNull(contribution, "contribution").toLegacyContribution());
    }

    /**
     * A toolbar entry described with a raw anchor string.
     *
     * @param contributionId plugin-scoped entry identity
     * @param actionId action invoked on click
     * @param labelKey localisation key for the label
     * @param iconResourcePath plugin resource path of the icon
     * @param anchor raw placement anchor
     * @param order tie-breaker among entries sharing an anchor
     */
    record MainToolbarContribution(
        String contributionId,
        String actionId,
        String labelKey,
        String iconResourcePath,
        String anchor,
        int order
    ) {
        /** @throws IllegalArgumentException when any text component is blank */
        public MainToolbarContribution {
            contributionId = requireText(contributionId, "contributionId");
            actionId = requireText(actionId, "actionId");
            labelKey = requireText(labelKey, "labelKey");
            iconResourcePath = requireText(iconResourcePath, "iconResourcePath");
            anchor = requireText(anchor, "anchor");
        }
    }

    /**
     * A toolbar button described with typed icon variants and semantic placement.
     *
     * @param contributionId plugin-scoped entry identity
     * @param actionId action invoked on click
     * @param labelKey localisation key for the label
     * @param tooltipKey localisation key for the tooltip
     * @param icons icon variants for the button's visual states
     * @param placement semantic position on the toolbar
     * @param order tie-breaker among entries sharing a placement
     */
    record MainToolbarButtonContribution(
        String contributionId,
        String actionId,
        String labelKey,
        String tooltipKey,
        IconVariants icons,
        Placement placement,
        int order
    ) {
        /**
         * @throws IllegalArgumentException when any text component is blank
         * @throws NullPointerException when icons or placement are null
         */
        public MainToolbarButtonContribution {
            contributionId = requireText(contributionId, "contributionId");
            actionId = requireText(actionId, "actionId");
            labelKey = requireText(labelKey, "labelKey");
            tooltipKey = requireText(tooltipKey, "tooltipKey");
            icons = Objects.requireNonNull(icons, "icons");
            placement = Objects.requireNonNull(placement, "placement");
        }

        /**
         * Projects this button onto the original anchor-string contribution shape.
         *
         * @return an equivalent contribution using the normal icon and the legacy anchor form
         */
        public MainToolbarContribution toLegacyContribution() {
            return new MainToolbarContribution(
                contributionId,
                actionId,
                labelKey,
                icons.normal(),
                placement.legacyAnchor(),
                order
            );
        }
    }

    /**
     * Icon resource paths for a button's visual states.
     *
     * <p>Only {@code normal} is required; the host falls back to it for any variant left empty.</p>
     *
     * @param normal the default icon
     * @param hover icon shown on hover
     * @param selected icon shown while selected
     * @param disabled icon shown while disabled
     * @param light icon for light themes
     * @param dark icon for dark themes
     */
    record IconVariants(
        String normal,
        Optional<String> hover,
        Optional<String> selected,
        Optional<String> disabled,
        Optional<String> light,
        Optional<String> dark
    ) {
        /** @throws IllegalArgumentException when any present path is blank */
        public IconVariants {
            normal = requireText(normal, "normal");
            hover = normalized(hover, "hover");
            selected = normalized(selected, "selected");
            disabled = normalized(disabled, "disabled");
            light = normalized(light, "light");
            dark = normalized(dark, "dark");
        }

        /**
         * Creates variants with only the default icon supplied.
         *
         * @param resourcePath the default icon's plugin resource path
         * @return variants where every other state falls back to the default
         */
        public static IconVariants normal(final String resourcePath) {
            return new IconVariants(
                resourcePath,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
            );
        }
    }

    /**
     * Semantic position of a button on the toolbar.
     *
     * @param position absolute or anchor-relative position
     * @param anchor the semantic anchor, present only for relative positions
     */
    record Placement(Position position, Optional<Anchor> anchor) {
        /**
         * @throws IllegalArgumentException when a relative position has no anchor, or an absolute
         *     position supplies one
         */
        public Placement {
            position = Objects.requireNonNull(position, "position");
            anchor = Objects.requireNonNull(anchor, "anchor");
            if ((position == Position.BEFORE || position == Position.AFTER) != anchor.isPresent()) {
                throw new IllegalArgumentException(
                    "BEFORE/AFTER require one semantic anchor; FIRST/LAST require none"
                );
            }
        }

        /**
         * Places the button before every existing entry.
         *
         * @return an absolute first placement
         */
        public static Placement first() {
            return new Placement(Position.FIRST, Optional.empty());
        }

        /**
         * Places the button after every existing entry.
         *
         * @return an absolute last placement
         */
        public static Placement last() {
            return new Placement(Position.LAST, Optional.empty());
        }

        /**
         * Places the button immediately before a semantic anchor.
         *
         * @param anchor the host entry to anchor against
         * @return a relative placement
         */
        public static Placement before(final Anchor anchor) {
            return new Placement(Position.BEFORE, Optional.of(Objects.requireNonNull(anchor, "anchor")));
        }

        /**
         * Places the button immediately after a semantic anchor.
         *
         * @param anchor the host entry to anchor against
         * @return a relative placement
         */
        public static Placement after(final Anchor anchor) {
            return new Placement(Position.AFTER, Optional.of(Objects.requireNonNull(anchor, "anchor")));
        }

        String legacyAnchor() {
            return switch (position) {
                case FIRST -> "start";
                case LAST -> "end";
                case BEFORE -> "before:" + anchor.orElseThrow().id();
                case AFTER -> "after:" + anchor.orElseThrow().id();
            };
        }
    }

    /** Where a button sits relative to the toolbar's existing entries. */
    enum Position {
        /** Before every existing entry. */
        FIRST,
        /** After every existing entry. */
        LAST,
        /** Immediately before the anchor. */
        BEFORE,
        /** Immediately after the anchor. */
        AFTER
    }

    /** Host toolbar entries a plugin may anchor against by name rather than by index. */
    enum Anchor {
        /** The host's home entry. */
        HOST_HOME_ENTRY("host-home-entry");

        private final String id;

        Anchor(final String id) {
            this.id = id;
        }

        /**
         * Returns the anchor's stable wire identity.
         *
         * @return the anchor id used in the legacy anchor string
         */
        public String id() {
            return id;
        }
    }

    private static Optional<String> normalized(final Optional<String> value, final String name) {
        Objects.requireNonNull(value, name);
        return value.map(text -> requireText(text, name));
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
