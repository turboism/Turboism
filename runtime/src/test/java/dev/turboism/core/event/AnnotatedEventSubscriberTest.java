package dev.turboism.core.event;

import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.sidecar.SidecarResult;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.event.EventPriority;
import dev.turboism.sdk.event.SubscribeEvent;
import dev.turboism.sdk.event.TurboismEvent;
import dev.turboism.sdk.failure.ExceptionAdvice;
import dev.turboism.sdk.failure.FailureBoundary;
import dev.turboism.sdk.failure.FailureContext;
import dev.turboism.sdk.failure.HandlesException;
import dev.turboism.sdk.failure.NoFailureInterception;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnnotatedEventSubscriberTest {

    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-08-23T00:00:00Z"),
        ZoneOffset.UTC
    );

    @Test
    void brokerInvokesAnnotatedEntrypointsInPriorityThenCanonicalOrder() throws Exception {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final List<String> calls = new CopyOnWriteArrayList<>();
        final CountDownLatch delivered = new CountDownLatch(3);
        final Subscriber entrypoint = new Subscriber(calls, delivered);
        broker.registerAnnotated(
            "dev.example.subscriber",
            new EntrypointSubscriberCatalog().inspect(List.of(entrypoint))
        );

        broker.publish("turboism.core", new TestEvent("value"));

        assertTrue(delivered.await(1, TimeUnit.SECONDS));
        assertEquals(List.of("highest", "alpha", "zeta"), calls);
        scheduler.shutdown();
    }

    @Test
    void failureAdviceReceivesPrivacySafeContextAndBrokerStillContainsFailure() throws Exception {
        final RuntimeScheduler scheduler = scheduler();
        final List<RuntimeEventBroker.SubscriberFailure> failures =
            new CopyOnWriteArrayList<>();
        final RuntimeEventBroker broker = new RuntimeEventBroker(
            scheduler,
            8,
            ignored -> { },
            failures::add
        );
        final CountDownLatch advised = new CountDownLatch(1);
        final FailureAdvice advice = new FailureAdvice(advised);
        final FailingSubscriber subscriber = new FailingSubscriber();
        broker.registerAnnotated(
            broker.legacyOwner("dev.example.failure"),
            new EntrypointSubscriberCatalog().inspect(List.of(subscriber, advice)),
            List.of(subscriber, advice)
        );

        broker.publish("turboism.core", new TestEvent("value"));

        assertTrue(advised.await(1, TimeUnit.SECONDS));
        assertEquals("event.test", advice.context.operationId());
        assertEquals(TestEvent.class.getName(), advice.context.eventType());
        assertEquals(IllegalStateException.class.getName(), advice.context.exceptionType());
        assertTrue(awaitSize(failures, 1));
        assertTrue(failures.get(0).advised());
        scheduler.shutdown();
    }

    @Test
    void noFailureInterceptionSkipsAdviceButPreservesStructuredContainment() throws Exception {
        final RuntimeScheduler scheduler = scheduler();
        final List<RuntimeEventBroker.SubscriberFailure> failures =
            new CopyOnWriteArrayList<>();
        final RuntimeEventBroker broker = new RuntimeEventBroker(
            scheduler,
            8,
            ignored -> { },
            failures::add
        );
        final CountDownLatch adviceCalls = new CountDownLatch(1);
        final FailureAdvice advice = new FailureAdvice(adviceCalls);
        final UninterceptedSubscriber subscriber = new UninterceptedSubscriber();
        broker.registerAnnotated(
            broker.legacyOwner("dev.example.unintercepted"),
            new EntrypointSubscriberCatalog().inspect(List.of(subscriber, advice)),
            List.of(subscriber, advice)
        );

        broker.publish("turboism.core", new TestEvent("value"));

        assertTrue(awaitSize(failures, 1));
        assertEquals(1L, adviceCalls.getCount());
        assertTrue(!failures.get(0).advised());
        scheduler.shutdown();
    }

    private static boolean awaitSize(final List<?> values, final int size)
        throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (values.size() < size && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        return values.size() >= size;
    }

    private static RuntimeScheduler scheduler() {
        final List<PluginWorkBudgetEvent> diagnostics = new CopyOnWriteArrayList<>();
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 8, diagnostics::add, CLOCK),
            new NoOpSidecarDispatcher(),
            diagnostics::add
        );
    }

    public static final class Subscriber {
        private final List<String> calls;
        private final CountDownLatch delivered;

        Subscriber(final List<String> calls, final CountDownLatch delivered) {
            this.calls = calls;
            this.delivered = delivered;
        }

        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void highest(final TestEvent event) {
            record("highest");
        }

        @SubscribeEvent
        public void zeta(final TestEvent event) {
            record("zeta");
        }

        @SubscribeEvent
        public void alpha(final TestEvent event) {
            record("alpha");
        }

        private void record(final String value) {
            calls.add(value);
            delivered.countDown();
        }
    }

    @FailureBoundary("event.test")
    public static final class FailingSubscriber {
        @SubscribeEvent
        public void onEvent(final TestEvent event) {
            throw new IllegalStateException("private path /home/local-user/model.cmo3");
        }
    }

    public static final class UninterceptedSubscriber {
        @SubscribeEvent
        @NoFailureInterception
        public void onEvent(final TestEvent event) {
            throw new IllegalStateException("not advised");
        }
    }

    @ExceptionAdvice
    public static final class FailureAdvice {
        private final CountDownLatch called;
        private volatile FailureContext context;

        FailureAdvice(final CountDownLatch called) {
            this.called = called;
        }

        @HandlesException(IllegalStateException.class)
        public void handle(
            final IllegalStateException failure,
            final FailureContext context
        ) {
            this.context = context;
            called.countDown();
        }
    }

    public record TestEvent(String value) implements TurboismEvent {
    }

    private static final class NoOpSidecarDispatcher implements SidecarDispatcher {
        @Override
        public CompletionStage<SidecarResult> dispatch(
            final PluginTask task,
            final Runnable callback
        ) {
            return CompletableFuture.completedFuture(SidecarResult.success(""));
        }
    }
}
