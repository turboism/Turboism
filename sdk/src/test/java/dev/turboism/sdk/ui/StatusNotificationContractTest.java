package dev.turboism.sdk.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Contract for the backward-compatible {@link StatusNotification} presentation
 * mode: the historic three-argument constructor keeps the
 * {@code NOTIFICATION} default, the record exposes the new non-null
 * {@code presentation} component, and validation stays unchanged.
 */
class StatusNotificationContractTest {

    @Test
    void threeArgumentConstructorDefaultsToNotificationPresentation() {
        StatusNotification notification = new StatusNotification("build", "INFO", "Building");
        assertEquals(StatusNotification.Presentation.NOTIFICATION, notification.presentation());
        assertEquals("build", notification.id());
        assertEquals("INFO", notification.severity());
        assertEquals("Building", notification.message());
    }

    @Test
    void fourArgumentConstructorAcceptsCompactMetricPresentation() {
        StatusNotification notification = new StatusNotification(
            "perf.cpu",
            "INFO",
            "CPU 12.3%",
            StatusNotification.Presentation.COMPACT_METRIC
        );
        assertEquals(StatusNotification.Presentation.COMPACT_METRIC, notification.presentation());
    }

    @Test
    void nullPresentationIsRejected() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new StatusNotification("id", "INFO", "message", null)
        );
    }

    @Test
    void existingValidationIsUnchanged() {
        assertThrows(IllegalArgumentException.class, () -> new StatusNotification("", "INFO", "message"));
        assertThrows(IllegalArgumentException.class, () -> new StatusNotification("id", "DEBUG", "message"));
        assertThrows(IllegalArgumentException.class, () -> new StatusNotification("id", "INFO", " "));
    }

    @Test
    void presentationParticipatesInRecordEquality() {
        StatusNotification plain = new StatusNotification("id", "INFO", "message");
        StatusNotification compact = new StatusNotification(
            "id",
            "INFO",
            "message",
            StatusNotification.Presentation.COMPACT_METRIC
        );
        assertNotEquals(plain, compact);
    }
}
