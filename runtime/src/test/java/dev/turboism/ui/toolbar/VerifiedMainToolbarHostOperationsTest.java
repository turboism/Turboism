package dev.turboism.ui.toolbar;

import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VerifiedMainToolbarHostOperationsTest {

    @Test
    void pluginButtonsUseTheNativeMainToolbarFootprint() {
        assertEquals(32, VerifiedMainToolbarHostOperations.nativeButtonSize());
    }

    @Test
    void afterHomeSkipsTheHostOwnedHomeDivider() {
        final Object home = new Object();
        final Object divider = new Object();
        final List<Object> children = List.of(home, divider, new Object());

        assertEquals(
            2,
            VerifiedMainToolbarHostOperations.insertionIndex(
                children,
                MainToolbarRegistry.Placement.after(
                    MainToolbarRegistry.Anchor.HOST_HOME_ENTRY
                ),
                home,
                value -> value == divider
            )
        );
    }

    @Test
    void laterAfterHomeContributionsInsertBeforeEarlierOnesWithinTheGroup() {
        final Object home = new Object();
        final Object divider = new Object();
        final Object turboismHome = new Object();
        final List<Object> children = List.of(home, divider, turboismHome, new Object());

        assertEquals(
            2,
            VerifiedMainToolbarHostOperations.insertionIndex(
                children,
                MainToolbarRegistry.Placement.after(
                    MainToolbarRegistry.Anchor.HOST_HOME_ENTRY
                ),
                home,
                value -> value == divider
            )
        );
    }

    @Test
    void afterHomeFallsBackToTheImmediateNeighborWhenThereIsNoDivider() {
        final Object home = new Object();
        final List<Object> children = List.of(home, new Object());

        assertEquals(
            1,
            VerifiedMainToolbarHostOperations.insertionIndex(
                children,
                MainToolbarRegistry.Placement.after(
                    MainToolbarRegistry.Anchor.HOST_HOME_ENTRY
                ),
                home,
                ignored -> false
            )
        );
    }
}
