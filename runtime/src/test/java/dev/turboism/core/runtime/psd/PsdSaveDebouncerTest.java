package dev.turboism.core.runtime.psd;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PsdSaveDebouncerTest {
    private static final String INITIAL = "a".repeat(64);
    private static final String FIRST = "b".repeat(64);
    private static final String SECOND = "c".repeat(64);
    private static final long WINDOW = Duration.ofMillis(750).toNanos();

    @Test
    void initialExportAndDuplicateObservationsNeverNotify() {
        final PsdSaveDebouncer gate = new PsdSaveDebouncer(INITIAL);
        assertTrue(gate.observe(INITIAL, 0).isEmpty());
        assertTrue(gate.observe(INITIAL, WINDOW * 10).isEmpty());
    }

    @Test
    void changedContentNeedsTwoObservationsAndTheEntireQuietWindow() {
        final PsdSaveDebouncer gate = new PsdSaveDebouncer(INITIAL);
        assertTrue(gate.observe(FIRST, 0).isEmpty());
        assertTrue(gate.observe(FIRST, WINDOW - 1).isEmpty());
        assertEquals(Optional.of(FIRST), gate.observe(FIRST, WINDOW));
        assertTrue(gate.observe(FIRST, WINDOW * 10).isEmpty());
    }

    @Test
    void slowWritesRestartTheWindowForTheLatestContent() {
        final PsdSaveDebouncer gate = new PsdSaveDebouncer(INITIAL);
        gate.observe(FIRST, 0);
        assertTrue(gate.observe(SECOND, WINDOW - 1).isEmpty());
        assertTrue(gate.observe(SECOND, WINDOW).isEmpty());
        assertEquals(Optional.of(SECOND), gate.observe(SECOND, WINDOW * 2 - 1));
    }

    @Test
    void returnToBaselineCancelsUnstableSave() {
        final PsdSaveDebouncer gate = new PsdSaveDebouncer(INITIAL);
        gate.observe(FIRST, 0);
        assertTrue(gate.observe(INITIAL, WINDOW).isEmpty());
        assertTrue(gate.observe(FIRST, WINDOW * 2).isEmpty());
        assertEquals(Optional.of(FIRST), gate.observe(FIRST, WINDOW * 3));
    }

    @Test
    void missingOrUnreadableObservationRequiresANewStableWindow() {
        final PsdSaveDebouncer gate = new PsdSaveDebouncer(INITIAL);
        gate.observe(FIRST, 0);
        gate.invalidateObservation();
        assertTrue(gate.observe(FIRST, WINDOW).isEmpty());
        assertEquals(Optional.of(FIRST), gate.observe(FIRST, WINDOW * 2));
    }

    @Test
    void noNewFileContentDoesNotReplayAnImportAfterHostUndo() {
        final PsdSaveDebouncer gate = new PsdSaveDebouncer(INITIAL);
        gate.observe(FIRST, 0);
        gate.observe(FIRST, WINDOW);
        // No host undo/reset method: the file baseline is independent of model history.
        gate.invalidateObservation();
        assertTrue(gate.observe(FIRST, WINDOW * 20).isEmpty());
    }

    @Test
    void savingEarlierBytesAgainIsANewChangeNotGlobalDigestSuppression() {
        final PsdSaveDebouncer gate = new PsdSaveDebouncer(INITIAL);
        gate.observe(FIRST, 0);
        gate.observe(FIRST, WINDOW);
        assertTrue(gate.observe(INITIAL, WINDOW * 2).isEmpty());
        assertEquals(Optional.of(INITIAL), gate.observe(INITIAL, WINDOW * 3));
    }

    @Test
    void stopIsIdempotentAndPreventsPendingAndFutureNotifications() {
        final PsdSaveDebouncer gate = new PsdSaveDebouncer(INITIAL);
        gate.observe(FIRST, 0);
        gate.stop();
        gate.stop();
        assertTrue(gate.observe(FIRST, WINDOW).isEmpty());
        gate.invalidateObservation();
        assertTrue(gate.observe(SECOND, WINDOW * 2).isEmpty());
        assertTrue(gate.observe(SECOND, WINDOW * 3).isEmpty());
    }

    @Test
    void monotonicClockMayBeNegativeOrWrap() {
        final PsdSaveDebouncer gate = new PsdSaveDebouncer(INITIAL);
        gate.observe(FIRST, -WINDOW);
        assertEquals(Optional.of(FIRST), gate.observe(FIRST, 0));
        final long start = Long.MAX_VALUE - WINDOW / 2;
        gate.observe(SECOND, start);
        assertEquals(Optional.of(SECOND), gate.observe(SECOND, start + WINDOW));
    }

    @Test
    void invalidDigestsAndQuietWindowsAreRejected() {
        assertThrows(NullPointerException.class, () -> new PsdSaveDebouncer(null));
        assertThrows(IllegalArgumentException.class, () -> new PsdSaveDebouncer("not-a-digest"));
        assertThrows(IllegalArgumentException.class, () -> new PsdSaveDebouncer(INITIAL.toUpperCase()));
        assertThrows(IllegalArgumentException.class,
            () -> new PsdSaveDebouncer(INITIAL, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
            () -> new PsdSaveDebouncer(INITIAL, Duration.ofNanos(-1)));
        final PsdSaveDebouncer gate = new PsdSaveDebouncer(INITIAL);
        assertThrows(IllegalArgumentException.class, () -> gate.observe("", 0));
    }
}
