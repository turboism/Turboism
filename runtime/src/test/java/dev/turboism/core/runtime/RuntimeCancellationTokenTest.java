package dev.turboism.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.plugin.TaskCanceledException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

public class RuntimeCancellationTokenTest {

    @Test
    void givenFreshToken_whenChecked_thenNotCancelled() {
        final RuntimeCancellationToken token = new RuntimeCancellationToken();

        assertFalse(token.isCancellationRequested());
        token.checkCanceled();
    }

    @Test
    void givenToken_whenCancelled_thenCancellationRequested() {
        final RuntimeCancellationToken token = new RuntimeCancellationToken();

        token.cancel();

        assertTrue(token.isCancellationRequested());
    }

    @Test
    void givenToken_whenCancelled_thenCheckCanceledThrows() {
        final RuntimeCancellationToken token = new RuntimeCancellationToken();

        token.cancel();

        assertThrows(TaskCanceledException.class, token::checkCanceled);
    }

    @Test
    void givenToken_whenCancelledMultipleTimes_thenIdempotentAndStillCancelled() {
        final RuntimeCancellationToken token = new RuntimeCancellationToken();

        token.cancel();
        token.cancel();
        token.cancel();

        assertTrue(token.isCancellationRequested());
        assertThrows(TaskCanceledException.class, token::checkCanceled);
    }

    @Test
    void givenTokenBoundInThreadLocal_whenCheckedFromCallbackThread_thenVisibleAndClearedAfter()
            throws InterruptedException {
        final RuntimeCancellationToken token = new RuntimeCancellationToken();
        final AtomicReference<RuntimeCancellationToken> observed = new AtomicReference<>();
        final AtomicReference<RuntimeCancellationToken> after = new AtomicReference<>();
        final Thread callbackThread = new Thread(() -> {
            CancellationContext.set(token);
            observed.set(CancellationContext.get());
            CancellationContext.clear();
            after.set(CancellationContext.get());
        });

        callbackThread.start();
        callbackThread.join();

        assertSame(token, observed.get());
        assertNull(after.get());
    }

    @Test
    void givenCancelHook_whenCancelled_thenHookRunsOnce() {
        final RuntimeCancellationToken token = new RuntimeCancellationToken();
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        token.onCancel(calls::incrementAndGet);

        token.cancel();
        token.cancel();

        assertEquals(1, calls.get());
    }

    @Test
    void givenCancelledToken_whenHookRegistered_thenRunsImmediately() {
        final RuntimeCancellationToken token = new RuntimeCancellationToken();
        token.cancel();
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

        token.onCancel(calls::incrementAndGet);

        assertEquals(1, calls.get());
    }

    @Test
    void givenMultipleHooks_whenCancelled_thenAllRun() {
        final RuntimeCancellationToken token = new RuntimeCancellationToken();
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        token.onCancel(calls::incrementAndGet);
        token.onCancel(calls::incrementAndGet);

        token.cancel();

        assertEquals(2, calls.get());
    }
}
