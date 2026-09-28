package dev.turboism.core.runtime.work;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FatalErrorsTest {

    @Test
    void rethrowsVirtualMachineErrorFamily() {
        final OutOfMemoryError outOfMemory = new OutOfMemoryError("probe");
        final StackOverflowError stackOverflow = new StackOverflowError("probe");

        assertSame(outOfMemory, assertThrows(
            OutOfMemoryError.class,
            () -> FatalErrors.rethrowIfFatal(outOfMemory)
        ));
        assertSame(stackOverflow, assertThrows(
            StackOverflowError.class,
            () -> FatalErrors.rethrowIfFatal(stackOverflow)
        ));
    }

    @Test
    void rethrowsThreadDeath() {
        final ThreadDeath death = new ThreadDeath();

        assertSame(death, assertThrows(
            ThreadDeath.class,
            () -> FatalErrors.rethrowIfFatal(death)
        ));
    }

    @Test
    void returnsForContainableThrowables() {
        assertDoesNotThrow(() -> FatalErrors.rethrowIfFatal(new RuntimeException("probe")));
        assertDoesNotThrow(() -> FatalErrors.rethrowIfFatal(new InterruptedException("probe")));
        assertDoesNotThrow(() -> FatalErrors.rethrowIfFatal(new LinkageError("probe")));
        assertDoesNotThrow(() -> FatalErrors.rethrowIfFatal(new AssertionError("probe")));
    }

    @Test
    void isFatalMatchesRethrowBehavior() {
        assertTrue(FatalErrors.isFatal(new VirtualMachineError("probe") { }));
        assertTrue(FatalErrors.isFatal(new OutOfMemoryError("probe")));
        assertTrue(FatalErrors.isFatal(new ThreadDeath()));
        assertFalse(FatalErrors.isFatal(new RuntimeException("probe")));
        assertFalse(FatalErrors.isFatal(new LinkageError("probe")));
    }
}
