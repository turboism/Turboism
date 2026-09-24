package dev.turboism.sdk.plugin;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DisposableScopeFailureTest {
    @Test void fatalFailureIsPreservedAfterAllOtherResourcesAreClosed() throws Exception {
        final DisposableScope scope = new DisposableScope();
        final AtomicInteger healthyCloses = new AtomicInteger();
        final StackOverflowError fatal = new StackOverflowError("injected fatal");
        final IllegalStateException ordinary = new IllegalStateException("ordinary");
        scope.register(healthyCloses::incrementAndGet);
        scope.register(() -> { throw fatal; });
        scope.register(() -> { throw ordinary; });
        assertSame(fatal, assertThrows(StackOverflowError.class, scope::close));
        assertEquals(1, healthyCloses.get(), "a fatal failure must not skip independent cleanup");
        assertSame(ordinary, fatal.getSuppressed()[0]);
        scope.close();
        assertEquals(1, healthyCloses.get());
    }

    @Test void identicalFailureObjectsDoNotAbortCleanupThroughSelfSuppression() {
        final DisposableScope scope = new DisposableScope();
        final AtomicInteger healthyCloses = new AtomicInteger();
        final IllegalStateException shared = new IllegalStateException("same failure");
        scope.register(healthyCloses::incrementAndGet);
        scope.register(() -> { throw shared; });
        scope.register(() -> { throw shared; });
        assertSame(shared, assertThrows(IllegalStateException.class, scope::close));
        assertEquals(1, healthyCloses.get());
    }
}
