package dev.turboism.core.runtime.work;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FatalErrorsTest {

    @Test
    void rethrowsVirtualMachineErrorFamily() {
        final OutOfMemoryError outOfMemory = new OutOfMemoryError("probe");
        final StackOverflowError stackOverflow = new StackOverflowError("probe");

        assertSame(outOfMemory, assertThrows(OutOfMemoryError.class, () -> FatalErrors.rethrowIfFatal(outOfMemory)));
        assertSame(
                stackOverflow, assertThrows(StackOverflowError.class, () -> FatalErrors.rethrowIfFatal(stackOverflow)));
    }

    @Test
    void rethrowsThreadDeath() {
        final ThreadDeath death = new ThreadDeath();

        assertSame(death, assertThrows(ThreadDeath.class, () -> FatalErrors.rethrowIfFatal(death)));
    }

    @Test
    void restoresInterruptFlagForInterruptedException() {
        try {
            FatalErrors.rethrowIfFatal(new InterruptedException("probe"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void returnsForContainableThrowables() {
        assertDoesNotThrow(() -> FatalErrors.rethrowIfFatal(new RuntimeException("probe")));
        try {
            assertDoesNotThrow(() -> FatalErrors.rethrowIfFatal(new InterruptedException("probe")));
        } finally {
            Thread.interrupted();
        }
        assertDoesNotThrow(() -> FatalErrors.rethrowIfFatal(new LinkageError("probe")));
        assertDoesNotThrow(() -> FatalErrors.rethrowIfFatal(new AssertionError("probe")));
    }

    @Test
    void isFatalMatchesRethrowBehavior() {
        assertTrue(FatalErrors.isFatal(new VirtualMachineError("probe") {}));
        assertTrue(FatalErrors.isFatal(new OutOfMemoryError("probe")));
        assertTrue(FatalErrors.isFatal(new ThreadDeath()));
        assertFalse(FatalErrors.isFatal(new RuntimeException("probe")));
        assertFalse(FatalErrors.isFatal(new LinkageError("probe")));
    }

    @Test
    void mergeForCleanupHandlesNullAndSameInstance() {
        final RuntimeException ex = new RuntimeException("ex");
        assertSame(ex, FatalErrors.mergeForCleanup(ex, null));
        assertSame(ex, FatalErrors.mergeForCleanup(null, ex));
        org.junit.jupiter.api.Assertions.assertNull(FatalErrors.mergeForCleanup(null, null));
        assertSame(ex, FatalErrors.mergeForCleanup(ex, ex));
        assertEquals(0, ex.getSuppressed().length);
    }

    @Test
    void mergeForCleanupPrioritizesFatalSecondaryOverNonFatalPrimary() {
        final RuntimeException primary = new RuntimeException("primary ordinary");
        final OutOfMemoryError secondaryFatal = new OutOfMemoryError("secondary OOM");

        final Throwable merged = FatalErrors.mergeForCleanup(primary, secondaryFatal);
        assertSame(secondaryFatal, merged, "Fatal secondary must be prioritized over ordinary primary");
        assertEquals(1, merged.getSuppressed().length);
        assertSame(primary, merged.getSuppressed()[0]);
    }

    @Test
    void mergeForCleanupPreservesFatalPrimaryOverNonFatalSecondary() {
        final OutOfMemoryError primaryFatal = new OutOfMemoryError("primary OOM");
        final RuntimeException secondary = new RuntimeException("secondary ordinary");

        final Throwable merged = FatalErrors.mergeForCleanup(primaryFatal, secondary);
        assertSame(primaryFatal, merged);
        assertEquals(1, merged.getSuppressed().length);
        assertSame(secondary, merged.getSuppressed()[0]);
    }

    @Test
    void mergeForCleanupPreservesNonFatalPrimaryOverNonFatalSecondary() {
        final RuntimeException primary = new RuntimeException("first");
        final IllegalStateException secondary = new IllegalStateException("second");

        final Throwable merged = FatalErrors.mergeForCleanup(primary, secondary);
        assertSame(primary, merged);
        assertEquals(1, merged.getSuppressed().length);
        assertSame(secondary, merged.getSuppressed()[0]);
    }

    @Test
    void mergeForCleanupPreservesFatalPrimaryOverFatalSecondary() {
        final OutOfMemoryError primaryOom = new OutOfMemoryError("primary OOM");
        final ThreadDeath secondaryDeath = new ThreadDeath();

        final Throwable merged = FatalErrors.mergeForCleanup(primaryOom, secondaryDeath);
        assertSame(primaryOom, merged);
        assertEquals(1, merged.getSuppressed().length);
        assertSame(secondaryDeath, merged.getSuppressed()[0]);
    }

    @Test
    void mergeForCleanupRestoresInterruptFlagForInterruptedException() {
        try {
            FatalErrors.mergeForCleanup(new InterruptedException("probe"), new RuntimeException("other"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertFalse(Thread.currentThread().isInterrupted());

        try {
            FatalErrors.mergeForCleanup(new RuntimeException("other"), new InterruptedException("probe"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void propagateRethrowsErrorAndReturnsRuntimeException() {
        final RuntimeException ex = new RuntimeException("test");
        assertSame(ex, assertThrows(RuntimeException.class, () -> {
            throw FatalErrors.propagate(ex);
        }));

        final OutOfMemoryError oom = new OutOfMemoryError("test OOM");
        assertSame(oom, assertThrows(OutOfMemoryError.class, () -> {
            throw FatalErrors.propagate(oom);
        }));

        final Exception checked = new Exception("checked");
        final IllegalStateException wrapped = assertThrows(IllegalStateException.class, () -> {
            throw FatalErrors.propagate(checked);
        });
        assertSame(checked, wrapped.getCause());
    }
}
