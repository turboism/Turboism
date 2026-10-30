package dev.turboism.sdk.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.Incubating;
import dev.turboism.sdk.plugin.Registration;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Contract for the incubating {@link UiScheduler#runUiTeardown} default: existing
 * implementers keep compiling, and the fallback only schedules the cleanup and reports it as
 * deferred.
 */
class UiSchedulerTeardownContractTest {

    @Test
    void defaultImplementationSchedulesCleanupAndReportsItAsDeferred() throws Exception {
        final List<Runnable> scheduled = new ArrayList<>();
        final UiScheduler scheduler = new UiScheduler() {
            @Override
            public Registration runOnUiThread(final Runnable work) {
                scheduled.add(work);
                return () -> {};
            }

            @Override
            public Registration runOnUiThreadLater(final Runnable work, final Duration delay) {
                throw new AssertionError("teardown must not use the delayed path");
            }
        };
        final Runnable cleanup = () -> {};

        assertFalse(scheduler.runUiTeardown(cleanup, Duration.ofSeconds(1)));

        assertEquals(List.of(cleanup), scheduled);
    }

    @Test
    void teardownMethodIsIncubating() throws Exception {
        assertTrue(UiScheduler.class
                .getMethod("runUiTeardown", Runnable.class, Duration.class)
                .isAnnotationPresent(Incubating.class));
    }
}
