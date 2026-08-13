package dev.turboism.bootstrap.carrier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused test against the production carrier: cleanup races must never escape into Cubism. */
class PerformanceProbeCarrierTest {

    private static final long CAMERA_MASK = 0b11111L;

    private PerformanceProbeCallback installed;

    @AfterEach
    void clearCarrier() {
        PerformanceProbeCarrier.clear(installed);
        installed = null;
    }

    @Test
    void disabledOrNoCallbackEntryNeverDereferences() {
        PerformanceProbeCarrier.clear(null);
        assertEquals(0L, PerformanceProbeCarrier.enter(0));
        PerformanceProbeCarrier.enable(CAMERA_MASK);
        // Mask is enabled but no callback is installed: entry must no-op, not NPE.
        assertEquals(0L, PerformanceProbeCarrier.enter(0));
        PerformanceProbeCarrier.disable();
        assertEquals(0L, PerformanceProbeCarrier.enter(0));
    }

    @Test
    void nonzeroTokenExitAfterClearIsANoOp() {
        final AtomicInteger exits = new AtomicInteger();
        installed = new PerformanceProbeCallback() {
            @Override public long enter(final int metricId) { return 1L; }
            @Override public void exit(final int metricId, final long startedNanos) { exits.incrementAndGet(); }
        };
        PerformanceProbeCarrier.install(installed);
        PerformanceProbeCarrier.clear(installed);
        // An in-flight transformed frame that entered before clear() now exits after it.
        PerformanceProbeCarrier.exit(0, 123_456L);
        assertEquals(0, exits.get());
    }

    @Test
    void installEnableDisableClearLifecycle() {
        final AtomicLong entered = new AtomicLong();
        final AtomicInteger exits = new AtomicInteger();
        installed = new PerformanceProbeCallback() {
            @Override public long enter(final int metricId) { return entered.incrementAndGet(); }
            @Override public void exit(final int metricId, final long startedNanos) { exits.incrementAndGet(); }
        };
        assertThrows(IllegalStateException.class, () -> {
            PerformanceProbeCarrier.install(installed);
            PerformanceProbeCarrier.install(installed);
        });
        PerformanceProbeCarrier.clear(installed);

        PerformanceProbeCarrier.install(installed);
        PerformanceProbeCarrier.enable(CAMERA_MASK);
        final long token = PerformanceProbeCarrier.enter(0);
        assertTrue(token > 0L);
        PerformanceProbeCarrier.exit(0, token);
        assertEquals(1, exits.get());

        PerformanceProbeCarrier.disable();
        assertEquals(0L, PerformanceProbeCarrier.enter(1));
        // A nonzero token proves the enter was admitted and inFlight incremented;
        // the matching exit must still reach the recorder even while disabled.
        PerformanceProbeCarrier.exit(1, 7L);
        assertEquals(2, exits.get());

        PerformanceProbeCarrier.clear(installed);
        assertEquals(0L, PerformanceProbeCarrier.enter(0));
        // After clear, a fresh install is allowed again.
        PerformanceProbeCarrier.install(installed);
        PerformanceProbeCarrier.enable(CAMERA_MASK);
        assertTrue(PerformanceProbeCarrier.enter(0) > 0L);
    }
}
