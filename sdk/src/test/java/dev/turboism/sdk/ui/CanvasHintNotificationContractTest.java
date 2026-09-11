package dev.turboism.sdk.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanvasHintNotificationContractTest {
    @Test
    void twoArgumentConstructorUsesNativeWarningTimeout() {
        CanvasHintNotification notification = new CanvasHintNotification("screen-color", "Incompatible");

        assertEquals("screen-color", notification.id());
        assertEquals("Incompatible", notification.message());
        assertEquals(CanvasHintNotification.DEFAULT_DURATION_SECONDS, notification.durationSeconds());
    }

    @Test
    void persistentHintsAcceptTheExplicitCloseSentinel() {
        CanvasHintNotification notification = new CanvasHintNotification(
            "screen-color",
            "Incompatible",
            CanvasHintNotification.UNTIL_DISMISSED
        );

        assertEquals(Float.POSITIVE_INFINITY, notification.durationSeconds());
    }

    @Test
    void blankTextAndNonPositiveTimeoutsAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new CanvasHintNotification("", "message"));
        assertThrows(IllegalArgumentException.class,
            () -> new CanvasHintNotification("id", " "));
        assertThrows(IllegalArgumentException.class,
            () -> new CanvasHintNotification("id", "message", 0.0f));
        assertThrows(IllegalArgumentException.class,
            () -> new CanvasHintNotification("id", "message", Float.NaN));
    }

    @Test
    void passiveHintsCarryNoClickAction() {
        CanvasHintNotification notification = new CanvasHintNotification("id", "message", 1.0f);

        assertTrue(notification.onClick().isEmpty());
    }

    @Test
    void withOnClickAddsTheActionAndKeepsEveryOtherComponent() {
        Runnable action = () -> { };
        CanvasHintNotification base = new CanvasHintNotification("id", "message", 1.0f);

        CanvasHintNotification clickable = base.withOnClick(action);

        assertEquals("id", clickable.id());
        assertEquals("message", clickable.message());
        assertEquals(1.0f, clickable.durationSeconds());
        assertSame(action, clickable.onClick().orElseThrow());
        assertTrue(base.onClick().isEmpty(), "the original notification must stay passive");
    }

    @Test
    void withOnClickReplacesAnExistingActionAndRejectsNull() {
        Runnable first = () -> { };
        Runnable second = () -> { };

        CanvasHintNotification replaced = new CanvasHintNotification("id", "message")
            .withOnClick(first)
            .withOnClick(second);
        assertSame(second, replaced.onClick().orElseThrow());

        assertThrows(NullPointerException.class,
            () -> new CanvasHintNotification("id", "message").withOnClick(null));
    }
}
