package dev.turboism.preview;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class StartupPhaseTimerTest {

    @Test
    void reportsEachPhaseAndCumulativeElapsedTimeFromAMonotonicTicker() {
        final long[] ticks = {
            1_000_000L,
            4_400_000L,
            11_900_000L
        };
        final AtomicInteger index = new AtomicInteger();
        final StartupPhaseTimer timer = new StartupPhaseTimer(
            () -> ticks[index.getAndIncrement()]
        );
        final List<String> messages = new ArrayList<>();

        timer.completed("configuration", messages::add);
        timer.completed("host-adapters", messages::add);

        assertEquals(List.of(
            "Startup phase configuration completed in 3 ms (total 3 ms)",
            "Startup phase host-adapters completed in 7 ms (total 10 ms)"
        ), messages);
    }

    @Test
    void clampsARegressingTickerToNonNegativeDurations() {
        final long[] ticks = {5_000_000L, 4_000_000L};
        final AtomicInteger index = new AtomicInteger();
        final StartupPhaseTimer timer = new StartupPhaseTimer(
            () -> ticks[index.getAndIncrement()]
        );
        final List<String> messages = new ArrayList<>();

        timer.completed("configuration", messages::add);

        assertEquals(
            List.of("Startup phase configuration completed in 0 ms (total 0 ms)"),
            messages
        );
    }
}
