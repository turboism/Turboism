package dev.turboism.sdk.ui.toolbar;

import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;
import java.util.Optional;

public interface MainToolbarRegistry {

    Registration contribute(MainToolbarContribution contribution);

    default Registration contributeButton(final MainToolbarButtonContribution contribution) {
        return contribute(Objects.requireNonNull(contribution, "contribution").toLegacyContribution());
    }

    record MainToolbarContribution(
        String contributionId,
        String actionId,
        String labelKey,
        String iconResourcePath,
        String anchor,
        int order
    ) {
        public MainToolbarContribution {
            contributionId = requireText(contributionId, "contributionId");
            actionId = requireText(actionId, "actionId");
            labelKey = requireText(labelKey, "labelKey");
            iconResourcePath = requireText(iconResourcePath, "iconResourcePath");
            anchor = requireText(anchor, "anchor");
        }
    }

    record MainToolbarButtonContribution(
        String contributionId,
        String actionId,
        String labelKey,
        String tooltipKey,
        IconVariants icons,
        Placement placement,
        int order
    ) {
        public MainToolbarButtonContribution {
            contributionId = requireText(contributionId, "contributionId");
            actionId = requireText(actionId, "actionId");
            labelKey = requireText(labelKey, "labelKey");
            tooltipKey = requireText(tooltipKey, "tooltipKey");
            icons = Objects.requireNonNull(icons, "icons");
            placement = Objects.requireNonNull(placement, "placement");
        }

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

    record IconVariants(
        String normal,
        Optional<String> hover,
        Optional<String> selected,
        Optional<String> disabled,
        Optional<String> light,
        Optional<String> dark
    ) {
        public IconVariants {
            normal = requireText(normal, "normal");
            hover = normalized(hover, "hover");
            selected = normalized(selected, "selected");
            disabled = normalized(disabled, "disabled");
            light = normalized(light, "light");
            dark = normalized(dark, "dark");
        }

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

    record Placement(Position position, Optional<Anchor> anchor) {
        public Placement {
            position = Objects.requireNonNull(position, "position");
            anchor = Objects.requireNonNull(anchor, "anchor");
            if ((position == Position.BEFORE || position == Position.AFTER) != anchor.isPresent()) {
                throw new IllegalArgumentException(
                    "BEFORE/AFTER require one semantic anchor; FIRST/LAST require none"
                );
            }
        }

        public static Placement first() {
            return new Placement(Position.FIRST, Optional.empty());
        }

        public static Placement last() {
            return new Placement(Position.LAST, Optional.empty());
        }

        public static Placement before(final Anchor anchor) {
            return new Placement(Position.BEFORE, Optional.of(Objects.requireNonNull(anchor, "anchor")));
        }

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

    enum Position {
        FIRST,
        LAST,
        BEFORE,
        AFTER
    }

    enum Anchor {
        HOST_HOME_ENTRY("host-home-entry");

        private final String id;

        Anchor(final String id) {
            this.id = id;
        }

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
