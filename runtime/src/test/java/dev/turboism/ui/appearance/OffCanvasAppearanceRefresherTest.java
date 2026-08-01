package dev.turboism.ui.appearance;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

final class OffCanvasAppearanceRefresherTest {

    @Test
    void failsClosedWhenHostClassesAreUnavailable() {
        // In the test JVM there is no Cubism host, so the refresher must
        // return false instead of throwing.
        assertFalse(new OffCanvasAppearanceRefresher().refresh("#3333FF"));
    }

    @Test
    void failsClosedOnInvalidColor() {
        assertFalse(new OffCanvasAppearanceRefresher().refresh("not-a-color"));
    }
}
