package dev.turboism.ui;

import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class IdempotentRegistrationTest {

    @Test
    void failedDelegateCloseCanBeRetried() {
        AtomicInteger closes = new AtomicInteger();
        Registration registration = IdempotentRegistration.of(() -> {
            if (closes.incrementAndGet() == 1) {
                throw new IllegalStateException("first close failed");
            }
        });

        assertThrows(IllegalStateException.class, registration::close);
        registration.close();
        registration.close();

        assertEquals(2, closes.get());
    }

    @Test
    void concurrentSuccessfulCloseRunsDelegateOnceAndWaiterDoesNotReturnEarly() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        AtomicReference<Boolean> waiterReturned = new AtomicReference<>(false);
        Registration registration = IdempotentRegistration.of(() -> {
            closes.incrementAndGet();
            ownerEntered.countDown();
            await(releaseOwner);
        });
        Thread owner = new Thread(registration::close, "registration-owner");
        Thread waiter = new Thread(() -> {
            registration.close();
            waiterReturned.set(true);
        }, "registration-waiter");

        owner.start();
        assertTrue(ownerEntered.await(5, TimeUnit.SECONDS));
        waiter.start();
        awaitWaiting(waiter);
        assertFalse(waiterReturned.get());
        releaseOwner.countDown();
        join(owner);
        join(waiter);

        assertEquals(1, closes.get());
        assertTrue(waiterReturned.get());
    }

    @Test
    void ownerFailureIsObservedByWaiterAndLaterCloseRetries() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
        AtomicReference<Throwable> waiterFailure = new AtomicReference<>();
        Registration registration = IdempotentRegistration.of(() -> {
            int attempt = closes.incrementAndGet();
            ownerEntered.countDown();
            await(releaseOwner);
            if (attempt == 1) {
                throw new IllegalStateException("first close failed");
            }
        });
        Thread owner = new Thread(() -> capture(registration::close, ownerFailure));
        Thread waiter = new Thread(() -> capture(registration::close, waiterFailure));

        owner.start();
        assertTrue(ownerEntered.await(5, TimeUnit.SECONDS));
        waiter.start();
        awaitWaiting(waiter);
        releaseOwner.countDown();
        join(owner);
        join(waiter);

        assertTrue(ownerFailure.get() instanceof IllegalStateException);
        assertTrue(waiterFailure.get() instanceof IllegalStateException);
        registration.close();
        assertEquals(2, closes.get());
    }

    @Test
    void ownerErrorIsObservedByWaiterAndLaterCloseRetries() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
        AtomicReference<Throwable> waiterFailure = new AtomicReference<>();
        Registration registration = IdempotentRegistration.of(() -> {
            int attempt = closes.incrementAndGet();
            ownerEntered.countDown();
            await(releaseOwner);
            if (attempt == 1) {
                throw new AssertionError("first close error");
            }
        });
        Thread owner = new Thread(() -> capture(registration::close, ownerFailure));
        Thread waiter = new Thread(() -> capture(registration::close, waiterFailure));

        owner.start();
        assertTrue(ownerEntered.await(5, TimeUnit.SECONDS));
        waiter.start();
        awaitWaiting(waiter);
        releaseOwner.countDown();
        join(owner);
        join(waiter);

        assertTrue(ownerFailure.get() instanceof AssertionError);
        assertTrue(waiterFailure.get() instanceof AssertionError);
        registration.close();
        assertEquals(2, closes.get());
    }

    @Test
    void sameThreadReentrantCloseDoesNotDeadlockOrDoubleClose() {
        AtomicInteger closes = new AtomicInteger();
        AtomicReference<Registration> reference = new AtomicReference<>();
        Registration registration = IdempotentRegistration.of(() -> {
            closes.incrementAndGet();
            reference.get().close();
        });
        reference.set(registration);

        registration.close();
        registration.close();

        assertEquals(1, closes.get());
        assertNull(capture(registration::close, new AtomicReference<>()));
    }

    private static Throwable capture(final Runnable runnable, final AtomicReference<Throwable> target) {
        try {
            runnable.run();
            return null;
        } catch (Throwable throwable) {
            target.set(throwable);
            return throwable;
        }
    }

    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                fail("latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static void awaitWaiting(final Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (thread.getState() == Thread.State.WAITING
                || thread.getState() == Thread.State.TIMED_WAITING) {
                return;
            }
            Thread.sleep(1);
        }
        fail("waiter did not wait");
    }

    private static void join(final Thread thread) {
        try {
            thread.join(5_000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail(exception);
        }
        assertFalse(thread.isAlive());
    }
}
