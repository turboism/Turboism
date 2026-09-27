package dev.turboism.cleanup;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryableCleanupTest {
    @Test void onlyFailedStagesAreRetriedAndLaterStagesAlwaysRun() {
        final List<String> calls = new ArrayList<>();
        final AtomicBoolean repaired = new AtomicBoolean();
        final RetryableCleanup cleanup = new RetryableCleanup("test cleanup",
            () -> calls.add("first"),
            () -> {
                calls.add("retryable");
                if (!repaired.get()) throw new IllegalStateException("not restored");
            },
            () -> calls.add("last")
        );
        assertThrows(IllegalStateException.class, cleanup::close);
        assertEquals(List.of("first", "retryable", "last"), calls);
        assertFalse(cleanup.complete());
        repaired.set(true);
        cleanup.close();
        cleanup.close();
        assertEquals(List.of("first", "retryable", "last", "retryable"), calls);
        assertTrue(cleanup.complete());
    }

    @Test void fatalFailuresAreRethrownAfterTheLastStageAndSelfSuppressionIsSafe() {
        final List<String> calls = new ArrayList<>();
        final IllegalStateException ordinary = new IllegalStateException("ordinary");
        final StackOverflowError fatal = new StackOverflowError("injected fatal");
        final RetryableCleanup cleanup = new RetryableCleanup("test cleanup",
            () -> { throw ordinary; },
            () -> { throw ordinary; },
            () -> { throw fatal; },
            () -> calls.add("last")
        );
        assertSame(fatal, assertThrows(StackOverflowError.class, cleanup::close));
        assertEquals(List.of("last"), calls);
        assertSame(ordinary, fatal.getSuppressed()[0]);
        assertFalse(cleanup.complete());
    }

    @Test void reentrantCloseDoesNotRepeatInFlightStages() {
        final List<String> calls = new ArrayList<>();
        final AtomicReference<RetryableCleanup> reference = new AtomicReference<>();
        final RetryableCleanup cleanup = new RetryableCleanup("test cleanup",
            () -> {
                calls.add("first");
                reference.get().close();
            },
            () -> calls.add("last")
        );
        reference.set(cleanup);
        cleanup.close();
        assertEquals(List.of("first", "last"), calls);
        assertTrue(cleanup.complete());
    }
}
