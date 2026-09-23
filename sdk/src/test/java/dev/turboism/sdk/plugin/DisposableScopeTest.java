package dev.turboism.sdk.plugin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * Permanent regression matrix for per-registration release ownership in
 * {@link DisposableScope}: every successful registration is released at most
 * once, independently of resource equality, close ordering, concurrency or
 * reentrancy, and user close code never runs under the scope monitor.
 */
class DisposableScopeTest {

    private static final long WAIT_SECONDS = 10;
    private static final long WAIT_MILLIS = TimeUnit.SECONDS.toMillis(WAIT_SECONDS);

    @Test
    void scopeThenHandleThenHandleCloseReleasesOnce() throws Exception {
        DisposableScope scope = new DisposableScope();
        CountingCloseable resource = new CountingCloseable();
        Registration handle = scope.register(resource);

        scope.close();
        handle.close();
        handle.close();

        assertEquals(1, resource.closes.get(), "one registration must be released exactly once");
    }

    @Test
    void handleThenScopeThenHandleCloseReleasesOnce() throws Exception {
        DisposableScope scope = new DisposableScope();
        CountingCloseable resource = new CountingCloseable();
        Registration handle = scope.register(resource);

        handle.close();
        scope.close();
        handle.close();

        assertEquals(1, resource.closes.get(), "one registration must be released exactly once");
    }

    @Test
    void repeatedHandleCloseReleasesOnce() throws Exception {
        DisposableScope scope = new DisposableScope();
        CountingCloseable resource = new CountingCloseable();
        Registration handle = scope.register(resource);

        handle.close();
        handle.close();
        handle.close();
        scope.close();

        assertEquals(1, resource.closes.get(), "repeated handle close must not release again");
    }

    @Test
    void scopeCloseReleasesUnclaimedEntriesInReverseRegistrationOrder() throws Exception {
        DisposableScope scope = new DisposableScope();
        List<String> order = new CopyOnWriteArrayList<>();
        scope.register(named("first", order));
        scope.register(named("second", order));
        Registration third = scope.register(named("third", order));

        third.close();
        scope.close();

        assertEquals(List.of("third", "second", "first"), order,
                "scope must close still-registered entries in reverse registration order");
    }

    @Test
    void equalResourcesRemainIndependentRegistrations() throws Exception {
        DisposableScope scope = new DisposableScope();
        EqualCloseable first = new EqualCloseable();
        EqualCloseable second = new EqualCloseable();
        scope.register(first);
        Registration secondHandle = scope.register(second);

        secondHandle.close();

        assertEquals(0, first.closes.get(), "closing one handle must not release an equal resource");
        assertEquals(1, second.closes.get());

        scope.close();

        assertEquals(1, first.closes.get(), "the equal resource must still be owned by its own entry");
        assertEquals(1, second.closes.get(), "the closed registration must not be released again");
    }

    @Test
    void sameObjectRegisteredTwiceKeepsTwoIndependentOwnerships() throws Exception {
        DisposableScope scope = new DisposableScope();
        CountingCloseable shared = new CountingCloseable();
        Registration first = scope.register(shared);
        Registration second = scope.register(shared);

        first.close();
        first.close();

        assertEquals(1, shared.closes.get(),
                "a duplicate handle close must not consume the other registration");

        scope.close();
        second.close();

        assertEquals(2, shared.closes.get(),
                "two registrations of the same object are two independent ownerships");
    }

    @Test
    void userCloseRunsOutsideScopeMonitorOnBothPaths() throws Exception {
        DisposableScope scope = new DisposableScope();
        AtomicBoolean handleHeldMonitor = new AtomicBoolean(true);
        AtomicBoolean scopeHeldMonitor = new AtomicBoolean(true);
        Registration handle = scope.register(() -> handleHeldMonitor.set(Thread.holdsLock(scope)));
        scope.register(() -> scopeHeldMonitor.set(Thread.holdsLock(scope)));

        handle.close();
        scope.close();

        assertFalse(handleHeldMonitor.get(),
                "handle-driven close must not run under the scope monitor");
        assertFalse(scopeHeldMonitor.get(),
                "scope-driven close must not run under the scope monitor");
    }

    @Test
    void concurrentScopeAndHandleCloseReleasesOnce() throws Exception {
        DisposableScope scope = new DisposableScope();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        AtomicBoolean heldMonitor = new AtomicBoolean();
        Registration handle = scope.register(gated(scope, closes, entered, proceed, heldMonitor));

        AtomicReference<Throwable> scopeError = new AtomicReference<>();
        Thread scopeThread = new Thread(() -> {
            try {
                scope.close();
            } catch (Throwable failure) {
                scopeError.set(failure);
            }
        }, "scope-close");
        try {
            scopeThread.start();
            assertTrue(entered.await(WAIT_SECONDS, TimeUnit.SECONDS),
                    "scope close must reach the user closeable");

            handle.close();

            assertEquals(1, closes.get(),
                    "handle close must not release an entry already claimed by the scope");
        } finally {
            proceed.countDown();
            joinBounded(scopeThread);
        }

        assertFalse(scopeThread.isAlive(), "scope close worker must terminate");
        assertNull(scopeError.get(), "scope close must not fail");
        assertFalse(heldMonitor.get(), "user close must run outside the scope monitor");
        assertEquals(1, closes.get(), "the registration must be released exactly once");
    }

    @Test
    void scopeCloseDuringHandleCloseReleasesOnce() throws Exception {
        DisposableScope scope = new DisposableScope();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        AtomicBoolean heldMonitor = new AtomicBoolean();
        Registration handle = scope.register(gated(scope, closes, entered, proceed, heldMonitor));

        AtomicReference<Throwable> handleError = new AtomicReference<>();
        Thread handleThread = new Thread(() -> {
            try {
                handle.close();
            } catch (Throwable failure) {
                handleError.set(failure);
            }
        }, "handle-close");
        try {
            handleThread.start();
            assertTrue(entered.await(WAIT_SECONDS, TimeUnit.SECONDS),
                    "handle close must reach the user closeable");

            scope.close();

            assertEquals(1, closes.get(),
                    "scope close must not release an entry already claimed by a handle");
        } finally {
            proceed.countDown();
            joinBounded(handleThread);
        }

        assertFalse(handleThread.isAlive(), "handle close worker must terminate");
        assertNull(handleError.get(), "handle close must not fail");
        assertFalse(heldMonitor.get(), "user close must run outside the scope monitor");
        assertEquals(1, closes.get(), "the registration must be released exactly once");
    }

    @Test
    void concurrentHandleClosesReleaseOnce() throws Exception {
        DisposableScope scope = new DisposableScope();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        AtomicBoolean heldMonitor = new AtomicBoolean();
        Registration handle = scope.register(gated(scope, closes, entered, proceed, heldMonitor));

        AtomicReference<Throwable> firstError = new AtomicReference<>();
        AtomicReference<Throwable> secondError = new AtomicReference<>();
        Thread first = new Thread(() -> {
            try {
                handle.close();
            } catch (Throwable failure) {
                firstError.set(failure);
            }
        }, "handle-close-1");
        Thread second = new Thread(() -> {
            try {
                handle.close();
            } catch (Throwable failure) {
                secondError.set(failure);
            }
        }, "handle-close-2");
        try {
            first.start();
            assertTrue(entered.await(WAIT_SECONDS, TimeUnit.SECONDS),
                    "the first handle close must reach the user closeable");

            second.start();
            second.join(WAIT_MILLIS);

            assertFalse(second.isAlive(),
                    "the second handle close must return while the claimed close is in flight");
            assertEquals(1, closes.get(),
                    "the losing handle close must not release the registration again");
        } finally {
            proceed.countDown();
            joinBounded(first);
            joinBounded(second);
        }

        assertFalse(first.isAlive(), "first handle close worker must terminate");
        assertFalse(second.isAlive(), "second handle close worker must terminate");
        assertNull(firstError.get(), "first handle close must not fail");
        assertNull(secondError.get(), "second handle close must not fail");
        assertFalse(heldMonitor.get(), "user close must run outside the scope monitor");
        assertEquals(1, closes.get(), "the registration must be released exactly once");
    }

    @Test
    void handleCloseReenteredFromResourceCloseReleasesOnce() {
        DisposableScope scope = new DisposableScope();
        AtomicInteger closes = new AtomicInteger();
        AtomicBoolean reenter = new AtomicBoolean(true);
        AtomicReference<Registration> handle = new AtomicReference<>();
        handle.set(scope.register(() -> {
            closes.incrementAndGet();
            if (reenter.getAndSet(false)) {
                handle.get().close();
            }
        }));

        handle.get().close();

        assertEquals(1, closes.get(), "reentrant handle close must not release the registration again");
    }

    @Test
    void scopeCloseReenteredFromResourceCloseReleasesEachEntryOnce() throws Exception {
        DisposableScope scope = new DisposableScope();
        CountingCloseable first = new CountingCloseable();
        AtomicBoolean reenter = new AtomicBoolean(true);
        CountingCloseable reentrant = new CountingCloseable();
        scope.register(first);
        scope.register(() -> {
            reentrant.closes.incrementAndGet();
            if (reenter.getAndSet(false)) {
                assertDoesNotThrow(scope::close, "reentrant scope close must return without re-releasing");
            }
        });

        scope.close();

        assertEquals(1, first.closes.get());
        assertEquals(1, reentrant.closes.get());
    }

    @Test
    void handleCloseSwallowsExceptionAndDoesNotRetry() throws Exception {
        DisposableScope scope = new DisposableScope();
        AtomicInteger closes = new AtomicInteger();
        Registration handle = scope.register(() -> {
            closes.incrementAndGet();
            throw new Exception("boom");
        });

        assertDoesNotThrow(handle::close, "handle close keeps swallowing Exception");
        assertDoesNotThrow(handle::close);
        assertEquals(1, closes.get(), "a failed release is still claimed and must not be retried");

        scope.close();
        assertEquals(1, closes.get());
    }

    @Test
    void scopeCloseAggregatesExceptionsAndStillClaimsEachEntry() throws Exception {
        DisposableScope scope = new DisposableScope();
        CountingCloseable normal = new CountingCloseable();
        Exception firstFailure = new Exception("first-in-reverse-order");
        Exception lastFailure = new Exception("last-in-reverse-order");
        AtomicInteger firstThrows = new AtomicInteger();
        AtomicInteger lastThrows = new AtomicInteger();
        Registration firstHandle = scope.register(() -> {
            firstThrows.incrementAndGet();
            throw firstFailure;
        });
        scope.register(normal);
        Registration lastHandle = scope.register(() -> {
            lastThrows.incrementAndGet();
            throw lastFailure;
        });

        Exception thrown = assertThrows(Exception.class, scope::close);

        assertSame(lastFailure, thrown, "scope rethrows the first failure in reverse close order");
        assertArrayEquals(new Throwable[]{firstFailure}, thrown.getSuppressed(),
                "later failures stay suppressed on the first one");
        assertEquals(1, firstThrows.get());
        assertEquals(1, normal.closes.get(), "other entries are still released");
        assertEquals(1, lastThrows.get());

        firstHandle.close();
        lastHandle.close();
        assertEquals(1, firstThrows.get(), "a claimed entry is not retried after an Exception");
        assertEquals(1, lastThrows.get());
    }

    @Test
    void closedScopeRejectsRegisterWithoutClosingResource() throws Exception {
        DisposableScope scope = new DisposableScope();
        scope.close();

        CountingCloseable rejected = new CountingCloseable();
        assertThrows(IllegalStateException.class, () -> scope.register(rejected));

        assertEquals(0, rejected.closes.get(), "a rejected resource is neither registered nor closed");
    }

    private static AutoCloseable named(String name, List<String> order) {
        return () -> order.add(name);
    }

    private static AutoCloseable gated(
            DisposableScope scope,
            AtomicInteger closes,
            CountDownLatch entered,
            CountDownLatch proceed,
            AtomicBoolean heldMonitor) {
        return () -> {
            closes.incrementAndGet();
            heldMonitor.set(Thread.holdsLock(scope));
            entered.countDown();
            assertTrue(proceed.await(WAIT_SECONDS, TimeUnit.SECONDS),
                    "the in-flight close was not released within the bound");
        };
    }

    private static void joinBounded(Thread thread) {
        try {
            thread.join(WAIT_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static class CountingCloseable implements AutoCloseable {
        final AtomicInteger closes = new AtomicInteger();

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }

    private static final class EqualCloseable extends CountingCloseable {
        @Override
        public boolean equals(Object other) {
            return other instanceof EqualCloseable;
        }

        @Override
        public int hashCode() {
            return EqualCloseable.class.hashCode();
        }
    }
}
