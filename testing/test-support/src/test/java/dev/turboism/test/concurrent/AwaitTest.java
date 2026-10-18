package dev.turboism.test.concurrent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AwaitTest {

    @Test
    void conditionSucceedsWhenConditionEventuallyHolds() {
        final AtomicInteger counter = new AtomicInteger();
        assertDoesNotThrow(() -> Await.condition(() -> counter.incrementAndGet() >= 3));
        assertTrue(counter.get() >= 3);
    }

    @Test
    void conditionTimesOutWhenNeverTrue() {
        final AtomicBoolean condition = new AtomicBoolean(false);
        final AssertionError error = assertThrows(
                AssertionError.class, () -> Await.condition("test condition", condition::get, Duration.ofMillis(50)));
        assertTrue(error.getMessage().contains("test condition"));
        assertTrue(error.getMessage().contains("timed out after 50 ms"));
    }

    @Test
    void awaitConditionAliasWorks() {
        final AtomicBoolean condition = new AtomicBoolean(true);
        assertDoesNotThrow(() -> Await.awaitCondition(condition::get));
    }
}
