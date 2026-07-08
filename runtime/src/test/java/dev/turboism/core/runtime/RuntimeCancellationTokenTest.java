package dev.turboism.core.runtime;

import dev.turboism.sdk.plugin.TaskCanceledException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void givenTokenBoundInThreadLocal_whenCheckedFromCallbackThread_thenVisibleAndClearedAfter() throws InterruptedException {
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
}
