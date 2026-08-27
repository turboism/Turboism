package dev.turboism.adapter.cubism.editor;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorHostThreadTest {

    @Test
    void dispatchesSynchronouslyToTheEventThread() {
        assertEquals("EDT", EditorHostThread.dispatch(
            "test",
            () -> EditorHostThread.isCurrent() ? "EDT" : "worker"
        ));
    }

    @Test
    void executesInlineWhenAlreadyOnTheEventThread() throws Exception {
        final AtomicReference<Boolean> current = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> current.set(EditorHostThread.dispatch(
            "test",
            EditorHostThread::isCurrent
        )));
        assertEquals(Boolean.TRUE, current.get());
    }

    @Test
    void propagatesRuntimeExceptionsAndErrors() {
        final IllegalArgumentException runtime = new IllegalArgumentException("runtime");
        assertSame(runtime, assertThrows(
            IllegalArgumentException.class,
            () -> EditorHostThread.dispatch("test", () -> { throw runtime; })
        ));
        final AssertionError error = new AssertionError("error");
        assertSame(error, assertThrows(
            AssertionError.class,
            () -> EditorHostThread.dispatch("test", () -> { throw error; })
        ));
    }

    @Test
    void restoresInterruptedStatusAndWrapsInterruption() throws Exception {
        final CountDownLatch eventThreadBlocked = new CountDownLatch(1);
        final CountDownLatch releaseEventThread = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            eventThreadBlocked.countDown();
            try {
                releaseEventThread.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(eventThreadBlocked.await(5, java.util.concurrent.TimeUnit.SECONDS));

        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicReference<Boolean> interrupted = new AtomicReference<>(false);
        final Thread caller = new Thread(() -> {
            try {
                EditorHostThread.dispatch("test", () -> null);
            } catch (Throwable throwable) {
                failure.set(throwable);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        caller.start();
        caller.interrupt();
        caller.join(5_000);
        releaseEventThread.countDown();

        assertFalse(caller.isAlive());
        assertTrue(failure.get() instanceof IllegalStateException);
        assertTrue(failure.get().getMessage().contains("interrupted"));
        assertEquals(Boolean.TRUE, interrupted.get());
    }
}
