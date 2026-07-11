package dev.turboism.hook.ingress;

import dev.turboism.sdk.event.EventBus;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class BoundedHookEventMailboxTest {

    private static final HookIngressSpec SPEC = spec("hook.test");

    @Test
    void validatesCapacityAndKeepsEveryDeclaredApiAndNestedTypePackagePrivate() {
        assertThrows(IllegalArgumentException.class,
            () -> new BoundedHookEventMailbox(0, event -> { }, diagnostic -> { }));
        assertPackagePrivate(BoundedHookEventMailbox.class.getModifiers(), "mailbox type");
        for (Class<?> nested : BoundedHookEventMailbox.class.getDeclaredClasses()) {
            assertPackagePrivate(nested.getModifiers(), nested.getSimpleName());
        }
        for (Constructor<?> constructor : BoundedHookEventMailbox.class.getDeclaredConstructors()) {
            assertPackagePrivate(constructor.getModifiers(), constructor.toString());
        }
        for (Method method : BoundedHookEventMailbox.class.getDeclaredMethods()) {
            assertPackagePrivate(method.getModifiers(), method.toString());
        }

        Method offer = assertDoesNotThrow(() -> BoundedHookEventMailbox.class.getDeclaredMethod(
            "offer", HookIngressSpec.class, EventBus.TurboismEvent.class));
        assertEquals(2, offer.getParameterCount());
        assertTrue(List.of(offer.getParameterTypes()).containsAll(List.of(HookIngressSpec.class, EventBus.TurboismEvent.class)));
        assertFalse(List.of(offer.getParameterTypes()).contains(Object.class));
        assertFalse(List.of(offer.getParameterTypes()).contains(Runnable.class));
        assertFalse(List.of(offer.getParameterTypes()).contains(java.util.concurrent.Callable.class));
        assertFalse(Arrays.stream(BoundedHookEventMailbox.DiagnosticCode.values())
            .anyMatch(code -> code.name().equals("DIAGNOSTIC_SINK_FAILED")));
    }

    @Test
    void drainsAcceptedEntriesInFifoOrderAndAtMostOnePerCall() {
        List<EventBus.TurboismEvent> delivered = new ArrayList<>();
        BoundedHookEventMailbox mailbox = mailbox(3, delivered, new ArrayList<>());
        TestEvent first = new TestEvent("first");
        TestEvent second = new TestEvent("second");

        assertEquals(BoundedHookEventMailbox.OfferOutcome.ACCEPTED, mailbox.offer(SPEC, first));
        assertEquals(BoundedHookEventMailbox.OfferOutcome.ACCEPTED, mailbox.offer(SPEC, second));
        assertEquals(BoundedHookEventMailbox.DrainOutcome.DRAINED, mailbox.drainOne().outcome());
        assertEquals(List.of(first), delivered);
        assertEquals(1, mailbox.snapshot().pending());
        assertEquals(BoundedHookEventMailbox.DrainOutcome.DRAINED, mailbox.drainOne().outcome());
        assertEquals(List.of(first, second), delivered);
        assertEquals(BoundedHookEventMailbox.DrainOutcome.EMPTY, mailbox.drainOne().outcome());
    }

    @RepeatedTest(20)
    void concurrentProducersOverCapacityHaveExactAcceptedAndFullCounts() {
        int capacity = 7;
        int producerCount = 19;
        BoundedHookEventMailbox mailbox = mailbox(capacity, new ArrayList<>(), new ArrayList<>());
        CountDownLatch ready = new CountDownLatch(producerCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger full = new AtomicInteger();
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        List<Thread> producers = new ArrayList<>();
        for (int index = 0; index < producerCount; index++) {
            int eventIndex = index;
            Thread producer = thread("mailbox-producer-" + index, threadFailure, () -> {
                ready.countDown();
                await(start);
                switch (mailbox.offer(spec("hook." + eventIndex), new TestEvent("producer-" + eventIndex))) {
                    case ACCEPTED -> accepted.incrementAndGet();
                    case QUEUE_FULL -> full.incrementAndGet();
                    case MAILBOX_CLOSED -> fail("mailbox was not closed");
                }
            });
            producers.add(producer);
            producer.start();
        }
        try {
            await(ready);
        } finally {
            start.countDown();
        }
        producers.forEach(BoundedHookEventMailboxTest::join);
        rethrowThreadFailure(threadFailure);

        assertEquals(capacity, accepted.get());
        assertEquals(producerCount - capacity, full.get());
        BoundedHookEventMailbox.Snapshot snapshot = mailbox.snapshot();
        assertEquals(capacity, snapshot.pending());
        assertEquals(capacity, snapshot.accepted());
        assertEquals(producerCount - capacity, snapshot.queueFullRejected());
    }

    @Test
    void fullMailboxRejectsNewestAndKeepsOriginalPendingEntriesInStrictFifoOrder() {
        List<EventBus.TurboismEvent> delivered = new ArrayList<>();
        List<BoundedHookEventMailbox.Diagnostic> diagnostics = new ArrayList<>();
        BoundedHookEventMailbox mailbox = mailbox(2, delivered, diagnostics);
        TestEvent first = new TestEvent("first");
        TestEvent second = new TestEvent("second");
        TestEvent newest = new TestEvent("newest");

        assertEquals(BoundedHookEventMailbox.OfferOutcome.ACCEPTED, mailbox.offer(spec("hook.first"), first));
        assertEquals(BoundedHookEventMailbox.OfferOutcome.ACCEPTED, mailbox.offer(spec("hook.second"), second));
        assertEquals(BoundedHookEventMailbox.OfferOutcome.QUEUE_FULL, mailbox.offer(spec("hook.newest"), newest));
        assertEquals(BoundedHookEventMailbox.DrainOutcome.DRAINED, mailbox.drainOne().outcome());
        assertEquals(BoundedHookEventMailbox.DrainOutcome.DRAINED, mailbox.drainOne().outcome());
        assertEquals(BoundedHookEventMailbox.DrainOutcome.EMPTY, mailbox.drainOne().outcome());

        assertEquals(List.of(first, second), delivered);
        assertEquals(List.of(BoundedHookEventMailbox.DiagnosticCode.QUEUE_FULL), codes(diagnostics));
        assertEquals("hook.newest", diagnostics.get(0).hookId());
        assertSnapshot(mailbox.snapshot(), 2, 0, false, 2, 2, 1, 0, 0, 0, 0, 0);
    }

    @Test
    void controlledOfferBeforeCloseAndCloseBeforeOfferHaveUnambiguousOutcomes() {
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        BoundedHookEventMailbox offerFirst = mailbox(1, new ArrayList<>(), new ArrayList<>());
        CountDownLatch offered = new CountDownLatch(1);
        CountDownLatch allowClose = new CountDownLatch(1);
        AtomicReference<BoundedHookEventMailbox.OfferOutcome> firstOutcome = new AtomicReference<>();
        Thread producer = thread("offer-before-close", threadFailure, () -> {
            firstOutcome.set(offerFirst.offer(SPEC, new TestEvent("first")));
            offered.countDown();
            await(allowClose);
        });
        producer.start();
        try {
            await(offered);
            assertEquals(1, offerFirst.close());
        } finally {
            allowClose.countDown();
        }
        join(producer);
        rethrowThreadFailure(threadFailure);
        assertEquals(BoundedHookEventMailbox.OfferOutcome.ACCEPTED, firstOutcome.get());

        BoundedHookEventMailbox closeFirst = mailbox(1, new ArrayList<>(), new ArrayList<>());
        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<BoundedHookEventMailbox.OfferOutcome> lateOutcome = new AtomicReference<>();
        AtomicInteger closeResult = new AtomicInteger(-1);
        Thread closer = thread("close-before-offer", threadFailure, () -> {
            closeResult.set(closeFirst.close());
            closed.countDown();
        });
        Thread lateProducer = thread("late-offer", threadFailure, () -> {
            await(closed);
            lateOutcome.set(closeFirst.offer(SPEC, new TestEvent("late")));
        });
        closer.start();
        lateProducer.start();
        join(closer);
        join(lateProducer);
        rethrowThreadFailure(threadFailure);
        assertEquals(0, closeResult.get());
        assertEquals(BoundedHookEventMailbox.OfferOutcome.MAILBOX_CLOSED, lateOutcome.get());
    }

    @Test
    void doubleCloseReturnsZeroWithoutDuplicatingCountersOrDiagnostics() {
        List<BoundedHookEventMailbox.Diagnostic> diagnostics = new ArrayList<>();
        BoundedHookEventMailbox mailbox = mailbox(2, new ArrayList<>(), diagnostics);
        mailbox.offer(spec("hook.one"), new TestEvent("one"));
        mailbox.offer(spec("hook.two"), new TestEvent("two"));

        assertEquals(2, mailbox.close());
        BoundedHookEventMailbox.Snapshot afterFirstClose = mailbox.snapshot();
        assertEquals(0, mailbox.close());

        assertEquals(afterFirstClose, mailbox.snapshot());
        assertEquals(2, mailbox.snapshot().pendingDroppedOnClose());
        assertEquals(1, diagnostics.size());
        assertEquals(BoundedHookEventMailbox.DiagnosticCode.PENDING_DROPPED_ON_CLOSE, diagnostics.get(0).code());
        assertEquals(2, diagnostics.get(0).count());
    }

    @Test
    void closeAggregatesMixedPendingHooksUnderStableMailboxDiagnosticId() {
        List<BoundedHookEventMailbox.Diagnostic> diagnostics = new ArrayList<>();
        BoundedHookEventMailbox mailbox = mailbox(3, new ArrayList<>(), diagnostics);
        mailbox.offer(spec("hook.alpha"), new TestEvent("alpha"));
        mailbox.offer(spec("hook.beta"), new TestEvent("beta"));
        mailbox.offer(spec("hook.gamma"), new TestEvent("gamma"));

        assertEquals(3, mailbox.close());
        assertEquals(1, diagnostics.size());
        BoundedHookEventMailbox.Diagnostic diagnostic = diagnostics.get(0);
        assertEquals(BoundedHookEventMailbox.DiagnosticCode.PENDING_DROPPED_ON_CLOSE, diagnostic.code());
        assertEquals(BoundedHookEventMailbox.MAILBOX_DIAGNOSTIC_ID, diagnostic.hookId());
        assertEquals(3, diagnostic.count());
        assertNotEquals("hook.alpha", diagnostic.hookId());
    }

    @Test
    void inflightPendingConcurrentCloseAndLateOfferCommitExactCounters() {
        CountDownLatch downstreamEntered = new CountDownLatch(1);
        CountDownLatch releaseDownstream = new CountDownLatch(1);
        List<BoundedHookEventMailbox.Diagnostic> diagnostics = new ArrayList<>();
        BoundedHookEventMailbox mailbox = new BoundedHookEventMailbox(3, entry -> {
            downstreamEntered.countDown();
            await(releaseDownstream);
        }, diagnostics::add);
        mailbox.offer(spec("hook.inflight"), new TestEvent("inflight"));
        mailbox.offer(spec("hook.pending.one"), new TestEvent("pending-one"));
        mailbox.offer(spec("hook.pending.two"), new TestEvent("pending-two"));

        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        AtomicReference<BoundedHookEventMailbox.DrainResult> drainResult = new AtomicReference<>();
        Thread consumer = thread("inflight-consumer", threadFailure, () -> drainResult.set(mailbox.drainOne()));
        consumer.start();
        AtomicInteger dropped = new AtomicInteger();
        try {
            await(downstreamEntered);
            Thread closer = thread("concurrent-close", threadFailure, () -> dropped.set(mailbox.close()));
            closer.start();
            join(closer);
            rethrowThreadFailure(threadFailure);
            assertEquals(BoundedHookEventMailbox.OfferOutcome.MAILBOX_CLOSED,
                mailbox.offer(spec("hook.late"), new TestEvent("late")));
        } finally {
            releaseDownstream.countDown();
        }
        join(consumer);
        rethrowThreadFailure(threadFailure);

        assertEquals(BoundedHookEventMailbox.DrainOutcome.DRAINED, drainResult.get().outcome());
        assertEquals(2, dropped.get());
        BoundedHookEventMailbox.Snapshot snapshot = mailbox.snapshot();
        assertSnapshot(snapshot, 3, 0, true, 3, 1, 0, 1, 2, 0, 0, 0);
        assertConservation(snapshot);
        assertEquals(List.of(
            BoundedHookEventMailbox.DiagnosticCode.PENDING_DROPPED_ON_CLOSE,
            BoundedHookEventMailbox.DiagnosticCode.MAILBOX_CLOSED
        ), codes(diagnostics));
    }

    @Test
    void blockingAndReentrantDiagnosticsRunOutsideTheStateLock() {
        CountDownLatch diagnosticEntered = new CountDownLatch(1);
        CountDownLatch releaseDiagnostic = new CountDownLatch(1);
        AtomicReference<BoundedHookEventMailbox> mailboxRef = new AtomicReference<>();
        AtomicReference<BoundedHookEventMailbox.Snapshot> reentrantSnapshot = new AtomicReference<>();
        BoundedHookEventMailbox mailbox = new BoundedHookEventMailbox(1, entry -> { }, diagnostic -> {
            reentrantSnapshot.set(mailboxRef.get().snapshot());
            diagnosticEntered.countDown();
            await(releaseDiagnostic);
        });
        mailboxRef.set(mailbox);
        mailbox.offer(SPEC, new TestEvent("pending"));

        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        AtomicReference<BoundedHookEventMailbox.OfferOutcome> rejectedOutcome = new AtomicReference<>();
        Thread rejectedOffer = thread("blocking-diagnostic", threadFailure,
            () -> rejectedOutcome.set(mailbox.offer(SPEC, new TestEvent("rejected"))));
        rejectedOffer.start();
        AtomicReference<BoundedHookEventMailbox.Snapshot> concurrentSnapshot = new AtomicReference<>();
        try {
            await(diagnosticEntered);
            Thread observer = thread("lock-observer", threadFailure,
                () -> concurrentSnapshot.set(mailbox.snapshot()));
            observer.start();
            join(observer);
            rethrowThreadFailure(threadFailure);
            assertEquals(1, concurrentSnapshot.get().queueFullRejected());
            assertEquals(1, reentrantSnapshot.get().queueFullRejected());
        } finally {
            releaseDiagnostic.countDown();
        }
        join(rejectedOffer);
        rethrowThreadFailure(threadFailure);
        assertEquals(BoundedHookEventMailbox.OfferOutcome.QUEUE_FULL, rejectedOutcome.get());
    }

    @Test
    void downstreamReentrantDrainIsBusyAndOuterDrainReleasesClaim() {
        AtomicReference<BoundedHookEventMailbox> mailboxRef = new AtomicReference<>();
        AtomicReference<BoundedHookEventMailbox.DrainResult> reentrantResult = new AtomicReference<>();
        List<EventBus.TurboismEvent> delivered = new ArrayList<>();
        BoundedHookEventMailbox mailbox = new BoundedHookEventMailbox(2, entry -> {
            delivered.add(entry.event());
            reentrantResult.set(mailboxRef.get().drainOne());
        }, diagnostic -> { });
        mailboxRef.set(mailbox);
        TestEvent first = new TestEvent("first");
        TestEvent second = new TestEvent("second");
        mailbox.offer(SPEC, first);
        mailbox.offer(SPEC, second);

        assertEquals(BoundedHookEventMailbox.DrainOutcome.DRAINED, mailbox.drainOne().outcome());
        assertEquals(BoundedHookEventMailbox.DrainOutcome.BUSY, reentrantResult.get().outcome());
        assertEquals(BoundedHookEventMailbox.DrainOutcome.DRAINED, mailbox.drainOne().outcome());
        assertEquals(List.of(first, second), delivered);
        assertEquals(2, mailbox.snapshot().busyDrains());
        assertEquals(BoundedHookEventMailbox.DrainOutcome.EMPTY, mailbox.drainOne().outcome());
    }

    @Test
    void downstreamErrorCommitsFailureReleasesClaimAndAllowsFurtherDrain() {
        AtomicInteger calls = new AtomicInteger();
        BoundedHookEventMailbox mailbox = new BoundedHookEventMailbox(2, entry -> {
            if (calls.getAndIncrement() == 0) {
                throw new AssertionError("fatal downstream");
            }
        }, diagnostic -> { });
        mailbox.offer(SPEC, new TestEvent("fatal"));
        mailbox.offer(SPEC, new TestEvent("next"));

        assertThrows(AssertionError.class, mailbox::drainOne);
        assertEquals(BoundedHookEventMailbox.DrainOutcome.DRAINED, mailbox.drainOne().outcome());
        BoundedHookEventMailbox.Snapshot snapshot = mailbox.snapshot();
        assertEquals(1, snapshot.downstreamFailures());
        assertEquals(1, snapshot.drained());
        assertEquals(0, snapshot.busyDrains());
        assertConservation(snapshot);
    }

    @Test
    void diagnosticErrorPropagatesOnlyAfterClosedAndDropStateIsCommitted() {
        BoundedHookEventMailbox mailbox = new BoundedHookEventMailbox(2, entry -> { }, diagnostic -> {
            throw new AssertionError("fatal diagnostic");
        });
        mailbox.offer(spec("hook.one"), new TestEvent("one"));
        mailbox.offer(spec("hook.two"), new TestEvent("two"));

        assertThrows(AssertionError.class, mailbox::close);
        BoundedHookEventMailbox.Snapshot snapshot = mailbox.snapshot();
        assertSnapshot(snapshot, 2, 0, true, 2, 0, 0, 0, 2, 0, 1, 0);
        assertConservation(snapshot);
        assertThrows(AssertionError.class, () -> mailbox.offer(SPEC, new TestEvent("late")));
        assertEquals(1, mailbox.snapshot().closedRejected());
        assertEquals(2, mailbox.snapshot().diagnosticSinkFailures());
    }

    @Test
    void runtimeFailuresAndBusyDrainProduceACompleteConservedSnapshot() {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        BoundedHookEventMailbox mailbox = new BoundedHookEventMailbox(3, entry -> {
            int call = calls.getAndIncrement();
            if (call == 0) {
                entered.countDown();
                await(release);
            } else if (call == 1) {
                throw new IllegalStateException("private downstream text");
            }
        }, diagnostic -> {
            if (diagnostic.code() == BoundedHookEventMailbox.DiagnosticCode.DOWNSTREAM_FAILED) {
                throw new IllegalArgumentException("private sink text");
            }
        });
        mailbox.offer(SPEC, new TestEvent("blocked"));
        mailbox.offer(SPEC, new TestEvent("runtime-failure"));
        mailbox.offer(SPEC, new TestEvent("to-close"));
        assertEquals(BoundedHookEventMailbox.OfferOutcome.QUEUE_FULL,
            mailbox.offer(SPEC, new TestEvent("full")));

        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        Thread consumer = thread("snapshot-consumer", threadFailure, mailbox::drainOne);
        consumer.start();
        try {
            await(entered);
            assertEquals(BoundedHookEventMailbox.DrainOutcome.BUSY, mailbox.drainOne().outcome());
        } finally {
            release.countDown();
        }
        join(consumer);
        rethrowThreadFailure(threadFailure);
        BoundedHookEventMailbox.DrainResult failure = mailbox.drainOne();
        assertEquals(BoundedHookEventMailbox.DrainOutcome.DOWNSTREAM_FAILED, failure.outcome());
        assertTrue(failure.diagnosticSinkFailed());
        assertEquals(1, mailbox.close());
        assertEquals(BoundedHookEventMailbox.OfferOutcome.MAILBOX_CLOSED,
            mailbox.offer(SPEC, new TestEvent("closed")));

        BoundedHookEventMailbox.Snapshot snapshot = mailbox.snapshot();
        assertSnapshot(snapshot, 3, 0, true, 3, 1, 1, 1, 1, 1, 1, 1);
        assertConservation(snapshot);
    }

    private static void assertConservation(BoundedHookEventMailbox.Snapshot snapshot) {
        assertEquals(snapshot.accepted(),
            snapshot.drained() + snapshot.pending() + snapshot.pendingDroppedOnClose() + snapshot.downstreamFailures(),
            "every accepted entry must be drained, pending, dropped on close, or failed downstream");
    }

    private static void assertSnapshot(BoundedHookEventMailbox.Snapshot actual, int capacity, int pending,
                                       boolean closed, long accepted, long drained, long fullRejected,
                                       long closedRejected, long dropped, long downstreamFailures,
                                       long diagnosticFailures, long busyDrains) {
        assertAll(
            () -> assertEquals(capacity, actual.capacity()),
            () -> assertEquals(pending, actual.pending()),
            () -> assertEquals(closed, actual.closed()),
            () -> assertEquals(accepted, actual.accepted()),
            () -> assertEquals(drained, actual.drained()),
            () -> assertEquals(fullRejected, actual.queueFullRejected()),
            () -> assertEquals(closedRejected, actual.closedRejected()),
            () -> assertEquals(dropped, actual.pendingDroppedOnClose()),
            () -> assertEquals(downstreamFailures, actual.downstreamFailures()),
            () -> assertEquals(diagnosticFailures, actual.diagnosticSinkFailures()),
            () -> assertEquals(busyDrains, actual.busyDrains())
        );
    }

    private static void assertPackagePrivate(int modifiers, String description) {
        assertFalse(Modifier.isPublic(modifiers), description + " must not be public");
        assertFalse(Modifier.isProtected(modifiers), description + " must not be protected");
        assertFalse(Modifier.isPrivate(modifiers), description + " must not be private");
    }

    private static BoundedHookEventMailbox mailbox(int capacity, List<EventBus.TurboismEvent> delivered,
                                                    List<BoundedHookEventMailbox.Diagnostic> diagnostics) {
        return new BoundedHookEventMailbox(capacity, entry -> delivered.add(entry.event()), diagnostics::add);
    }

    private static HookIngressSpec spec(String hookId) {
        return new HookIngressSpec(hookId, "event.test", false, "fake");
    }

    private static List<BoundedHookEventMailbox.DiagnosticCode> codes(List<BoundedHookEventMailbox.Diagnostic> diagnostics) {
        return diagnostics.stream().map(BoundedHookEventMailbox.Diagnostic::code).toList();
    }

    private static Thread thread(String name, AtomicReference<Throwable> failure, Runnable runnable) {
        return new Thread(() -> {
            try {
                runnable.run();
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        }, name);
    }

    private static void rethrowThreadFailure(AtomicReference<Throwable> failure) {
        Throwable throwable = failure.get();
        if (throwable == null) {
            return;
        }
        if (throwable instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        throw new AssertionError(throwable);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "timed out waiting for deterministic test coordination");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
        assertFalse(thread.isAlive(), () -> "timed out joining " + thread.getName());
    }

    private record TestEvent(String value) implements EventBus.TurboismEvent { }
}
