package dev.turboism.test.ui;

import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FakeDirectUiSchedulerTest {

    @Test
    void runOnUiThreadRunsWorkSynchronouslyForTests() {
        // Given
        FakeDirectUiScheduler scheduler = new FakeDirectUiScheduler();
        AtomicInteger executions = new AtomicInteger();

        // When
        Registration registration = scheduler.runOnUiThread(executions::incrementAndGet);

        // Then
        assertEquals(1, executions.get());
        registration.close();
    }

    @Test
    void runOnUiThreadLaterRunsWorkSynchronouslyForTests() {
        // Given
        FakeDirectUiScheduler scheduler = new FakeDirectUiScheduler();
        AtomicInteger executions = new AtomicInteger();

        // When
        Registration registration = scheduler.runOnUiThreadLater(executions::incrementAndGet, Duration.ofDays(1));

        // Then
        assertEquals(1, executions.get());
        registration.close();
    }
}
