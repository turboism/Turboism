package dev.turboism.sdk.ui.toolbar;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MainToolbarRegistryContractTest {

    @Test
    void typedButtonTranslatesToCompatibleLegacyContribution() {
        MainToolbarRegistry.MainToolbarButtonContribution contribution =
            new MainToolbarRegistry.MainToolbarButtonContribution(
                "home",
                "home.open",
                "home.label",
                "home.tooltip",
                new MainToolbarRegistry.IconVariants(
                    "icons/home.svg",
                    Optional.of("icons/home-hover.svg"),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of("icons/home-light.svg"),
                    Optional.of("icons/home-dark.svg")
                ),
                MainToolbarRegistry.Placement.before(MainToolbarRegistry.Anchor.HOST_HOME_ENTRY),
                10
            );

        assertEquals(
            new MainToolbarRegistry.MainToolbarContribution(
                "home",
                "home.open",
                "home.label",
                "icons/home.svg",
                "before:host-home-entry",
                10
            ),
            contribution.toLegacyContribution()
        );
    }

    @Test
    void relativePlacementRequiresSemanticAnchor() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new MainToolbarRegistry.Placement(
                MainToolbarRegistry.Position.BEFORE,
                Optional.empty()
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new MainToolbarRegistry.Placement(
                MainToolbarRegistry.Position.FIRST,
                Optional.of(MainToolbarRegistry.Anchor.HOST_HOME_ENTRY)
            )
        );
    }

    @Test
    void iconVariantPathsAreValidated() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new MainToolbarRegistry.IconVariants(
                "icons/home.svg",
                Optional.of(" "),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
            )
        );
    }
}
