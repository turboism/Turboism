package dev.turboism.core.event;

import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.sidecar.SidecarResult;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.adapter.cubism.lifecycle.ParameterHookRegistry;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.backup.BackupCompletedEvent;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.event.SubscribeEvent;
import dev.turboism.sdk.event.cubism.ParameterValueEvent;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Delivery-time concrete permission filtering: a root/supertype subscription is
 * authorized per delivered event type against the owner's facade-bound checker.
 */
class RuntimeEventDeliveryPermissionTest {

    private static final String PLUGIN_ID = "dev.turboism.plugin.test";
    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-08-23T00:00:00Z"),
        ZoneOffset.UTC
    );

    private RuntimeScheduler scheduler;

    @AfterEach
    void shutdownScheduler() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Test
    void wildcardSubscriberWithoutDomainPermissionMissesProtectedEvent() throws Exception {
        // Parent probe: descriptorless admitted owner granted only event.subscribe.
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler());
        final PluginEventBus eventBus = new PluginEventBus(
            broker,
            PLUGIN_ID,
            grantingOnly(PermissionIds.TURBOISM_EVENT_SUBSCRIBE)
        );
        final List<EventBus.TurboismEvent> received = new CopyOnWriteArrayList<>();
        eventBus.subscribe(EventBus.TurboismEvent.class, received::add);

        broker.publishRuntime(new BackupCompletedEvent(1L, List.of(), List.of()));

        awaitMailbox(broker, broker.legacyOwner(PLUGIN_ID));
        assertTrue(received.isEmpty(), "protected event must not reach a wildcard subscriber");
    }

    @Test
    void wildcardSubscriberReceivesPermittedConcreteSubset() throws Exception {
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler());
        final PluginEventBus eventBus = new PluginEventBus(
            broker,
            PLUGIN_ID,
            grantingOnly(
                PermissionIds.TURBOISM_EVENT_SUBSCRIBE,
                PermissionIds.TURBOISM_CUBISM_BACKUP_OBSERVE
            )
        );
        final List<EventBus.TurboismEvent> received = new CopyOnWriteArrayList<>();
        final CountDownLatch delivered = new CountDownLatch(2);
        eventBus.subscribe(EventBus.TurboismEvent.class, event -> {
            received.add(event);
            delivered.countDown();
        });
        final BackupCompletedEvent backup = new BackupCompletedEvent(1L, List.of(), List.of());
        final TestEvent custom = new TestEvent("custom");

        broker.publishRuntime(backup);
        broker.publishRuntime(custom);

        assertTrue(delivered.await(5, TimeUnit.SECONDS));
        assertEquals(List.of(backup, custom), received);
    }

    @Test
    void topLevelRootSubscriptionReceivesMigratedRuntimeEvents() throws Exception {
        // The five formerly legacy-only families implement the top-level marker;
        // a dev.turboism.sdk.event.TurboismEvent subscription must observe them.
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler());
        final PluginEventBus eventBus = new PluginEventBus(
            broker,
            PLUGIN_ID,
            grantingOnly(
                PermissionIds.TURBOISM_EVENT_SUBSCRIBE,
                PermissionIds.TURBOISM_CUBISM_BACKUP_OBSERVE
            )
        );
        final CountDownLatch delivered = new CountDownLatch(1);
        final AtomicReference<dev.turboism.sdk.event.TurboismEvent> received =
            new AtomicReference<>();
        eventBus.subscribe(
            dev.turboism.sdk.event.TurboismEvent.class,
            event -> {
                received.set(event);
                delivered.countDown();
            }
        );
        final BackupCompletedEvent backup = new BackupCompletedEvent(
            3L,
            List.of(),
            List.of()
        );

        broker.publishRuntime(backup);

        assertTrue(delivered.await(5, TimeUnit.SECONDS));
        assertEquals(backup, received.get());
    }

    @Test
    void annotatedWildcardSubscriberIsFilteredIdentically() throws Exception {
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler());
        final RuntimeEventBroker.Owner owner = broker.admit(PLUGIN_ID);
        final PluginEventBus eventBus = new PluginEventBus(
            broker,
            owner.key(),
            grantingOnly(PermissionIds.TURBOISM_EVENT_SUBSCRIBE),
            null
        );
        final CountDownLatch delivered = new CountDownLatch(1);
        broker.registerAnnotated(
            owner.key(),
            new EntrypointSubscriberCatalog().inspect(List.of(
                new WildcardAnnotatedSubscriber(delivered)
            ))
        );
        owner.activate();

        broker.publishRuntime(new BackupCompletedEvent(1L, List.of(), List.of()));
        awaitMailbox(broker, owner.key());
        assertEquals(1L, delivered.getCount(), "denied event must not reach the subscriber");

        broker.publishRuntime(new TestEvent("allowed"));
        assertTrue(delivered.await(5, TimeUnit.SECONDS));
    }

    @Test
    void retainedReplayIsFilteredPerConcreteType() throws Exception {
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler());
        final BackupCompletedEvent retained = new BackupCompletedEvent(
            7L,
            List.of(),
            List.of()
        );
        broker.publishRuntimeRetained(retained);

        final PluginEventBus deniedBus = new PluginEventBus(
            broker,
            "dev.example.denied",
            grantingOnly(PermissionIds.TURBOISM_EVENT_SUBSCRIBE)
        );
        final List<EventBus.TurboismEvent> denied = new CopyOnWriteArrayList<>();
        deniedBus.subscribe(EventBus.TurboismEvent.class, denied::add);

        final PluginEventBus allowedBus = new PluginEventBus(
            broker,
            "dev.example.allowed",
            grantingOnly(
                PermissionIds.TURBOISM_EVENT_SUBSCRIBE,
                PermissionIds.TURBOISM_CUBISM_BACKUP_OBSERVE
            )
        );
        final CountDownLatch replayed = new CountDownLatch(1);
        allowedBus.subscribe(
            EventBus.TurboismEvent.class,
            ignored -> replayed.countDown()
        );

        assertTrue(replayed.await(5, TimeUnit.SECONDS));
        awaitMailbox(broker, broker.legacyOwner("dev.example.denied"));
        assertTrue(denied.isEmpty(), "retained replay must honor concrete permissions");
    }

    @Test
    void synchronousTransformFiltersUnauthorizedSubscriber() {
        // A root subscriber must not mutate a gated transform without intercept.
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler());
        final PluginEventBus eventBus = new PluginEventBus(
            broker,
            PLUGIN_ID,
            grantingOnly(PermissionIds.TURBOISM_EVENT_SUBSCRIBE)
        );
        final List<EventBus.TurboismEvent> observed = new CopyOnWriteArrayList<>();
        eventBus.subscribe(EventBus.TurboismEvent.class, event -> {
            observed.add(event);
            if (event instanceof ParameterValueEvent.Before before) {
                before.setValue(99.0F);
            }
        });

        final float transformed = broker.publishRuntimeTransform(
            ParameterValueEvent.Before.class,
            1.0F,
            value -> new BeforeCallback(new TestParameter(), value),
            event -> ((ParameterValueEvent.Before) event).value(),
            Float::isFinite
        );

        assertEquals(1.0F, transformed, "unauthorized subscriber must not transform");
        assertTrue(observed.isEmpty());
    }

    @Test
    void synchronousReferenceTransformFiltersUnauthorizedSubscriber() {
        // The generic overload applies the same concrete-type authorization.
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler());
        final PluginEventBus eventBus = new PluginEventBus(
            broker,
            PLUGIN_ID,
            grantingOnly(PermissionIds.TURBOISM_EVENT_SUBSCRIBE)
        );
        final List<EventBus.TurboismEvent> observed = new CopyOnWriteArrayList<>();
        eventBus.subscribe(EventBus.TurboismEvent.class, event -> {
            observed.add(event);
            if (event instanceof ParameterValueEvent.Before before) {
                before.setValue(55.0F);
            }
        });

        final Float transformed = broker.publishRuntimeTransform(
            ParameterValueEvent.Before.class,
            2.0F,
            value -> new BeforeCallback(new TestParameter(), value),
            event -> ((ParameterValueEvent.Before) event).value(),
            Float::isFinite
        );

        assertEquals(2.0F, transformed, "unauthorized subscriber must not transform");
        assertTrue(observed.isEmpty());
    }

    @Test
    void authorizedTransformSubscriberMutatesBothOverloads() {
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler());
        final PluginEventBus eventBus = new PluginEventBus(
            broker,
            PLUGIN_ID,
            grantingOnly(
                PermissionIds.TURBOISM_EVENT_SUBSCRIBE,
                ParameterHookRegistry.INTERCEPT_PERMISSION
            )
        );
        eventBus.subscribe(EventBus.TurboismEvent.class, event -> {
            if (event instanceof ParameterValueEvent.Before before) {
                before.setValue(7.0F);
            }
        });

        assertEquals(7.0F, broker.publishRuntimeTransform(
            ParameterValueEvent.Before.class,
            1.0F,
            value -> new BeforeCallback(new TestParameter(), value),
            event -> ((ParameterValueEvent.Before) event).value()
        ));
        assertEquals(7.0F, broker.publishRuntimeTransform(
            ParameterValueEvent.Before.class,
            1.0F,
            value -> new BeforeCallback(new TestParameter(), value),
            event -> ((ParameterValueEvent.Before) event).value(),
            Float::isFinite
        ));
    }

    @Test
    void permissionRevokedBetweenEnqueueAndDrainSuppressesQueuedEvent() throws Exception {
        // Held first callback: second event enqueues while the grant stands, then
        // the grant flips before the queue drains; drain must re-authorize.
        final AtomicBoolean granted = new AtomicBoolean(true);
        final CountDownLatch denied = new CountDownLatch(1);
        final RuntimeEventBroker broker = new RuntimeEventBroker(
            scheduler(),
            64,
            diagnostic -> {
                if (diagnostic.code()
                    == RuntimeEventBroker.DeliveryDiagnostic.Code.DELIVERY_PERMISSION_DENIED) {
                    denied.countDown();
                }
            }
        );
        final PluginEventBus eventBus = new PluginEventBus(
            broker,
            PLUGIN_ID,
            (permissionId, operation) -> {
                if (PermissionIds.TURBOISM_EVENT_SUBSCRIBE.equals(permissionId)) {
                    return;
                }
                if (!granted.get()) {
                    throw new CubismPermissionException(operation + " revoked");
                }
            }
        );
        final CountDownLatch holdFirst = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        final List<BackupCompletedEvent> received = new CopyOnWriteArrayList<>();
        try {
            eventBus.subscribe(BackupCompletedEvent.class, event -> {
                received.add(event);
                holdFirst.countDown();
                awaitUninterruptibly(releaseFirst);
            });

            broker.publishRuntime(new BackupCompletedEvent(1L, List.of(), List.of()));
            assertTrue(holdFirst.await(5, TimeUnit.SECONDS));
            broker.publishRuntime(new BackupCompletedEvent(2L, List.of(), List.of()));
            granted.set(false);
        } finally {
            releaseFirst.countDown();
        }

        assertTrue(denied.await(5, TimeUnit.SECONDS), "drain must re-authorize queued events");
        awaitMailbox(broker, broker.legacyOwner(PLUGIN_ID));
        assertEquals(
            1,
            received.size(),
            "queued event must be re-authorized at drain, not only at enqueue"
        );
    }

    @Test
    void secondBroaderFacadeCannotBroadenExistingSubscriptions() throws Exception {
        // Monotonic binding intersection: a second PluginEventBus for the same
        // owner must never widen earlier subscriptions' authorization.
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler());
        final RuntimeEventBroker.Owner owner = broker.admit(PLUGIN_ID);
        new PluginEventBus(
            broker,
            owner.key(),
            grantingOnly(PermissionIds.TURBOISM_EVENT_SUBSCRIBE),
            null
        );
        new PluginEventBus(
            broker,
            owner.key(),
            PermissionChecker.allowAll(),
            null
        );
        final PluginEventBus narrowBus = new PluginEventBus(
            broker,
            owner.key(),
            grantingOnly(PermissionIds.TURBOISM_EVENT_SUBSCRIBE),
            null
        );
        final List<EventBus.TurboismEvent> received = new CopyOnWriteArrayList<>();
        narrowBus.subscribe(EventBus.TurboismEvent.class, received::add);
        owner.activate();

        broker.publishRuntime(new BackupCompletedEvent(1L, List.of(), List.of()));

        awaitMailbox(broker, owner.key());
        assertTrue(received.isEmpty(), "a broader facade must not widen existing grants");
    }

    @Test
    void newGenerationOfSamePluginGetsFreshPermissionBinding() throws Exception {
        // Closing and re-admitting the same plugin id must not leak the previous
        // generation's permission binding into the new owner's deliveries.
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler());
        final RuntimeEventBroker.Owner first = broker.admit(PLUGIN_ID);
        final PluginEventBus narrowBus = new PluginEventBus(
            broker,
            first.key(),
            grantingOnly(PermissionIds.TURBOISM_EVENT_SUBSCRIBE),
            null
        );
        final List<EventBus.TurboismEvent> firstReceived = new CopyOnWriteArrayList<>();
        narrowBus.subscribe(EventBus.TurboismEvent.class, firstReceived::add);
        first.activate();

        broker.publishRuntime(new BackupCompletedEvent(1L, List.of(), List.of()));
        awaitMailbox(broker, first.key());
        assertTrue(firstReceived.isEmpty());

        first.beginClosing();
        assertTrue(first.awaitQuiescence(java.time.Duration.ofSeconds(5)));
        first.close();

        final RuntimeEventBroker.Owner second = broker.admit(PLUGIN_ID);
        final PluginEventBus broadBus = new PluginEventBus(
            broker,
            second.key(),
            PermissionChecker.allowAll(),
            null
        );
        final CountDownLatch delivered = new CountDownLatch(1);
        broadBus.subscribe(EventBus.TurboismEvent.class, ignored -> delivered.countDown());
        second.activate();

        broker.publishRuntime(new BackupCompletedEvent(2L, List.of(), List.of()));

        assertTrue(
            delivered.await(5, TimeUnit.SECONDS),
            "the new generation's own broader grant must apply to its subscriptions"
        );
    }

    @Test
    void legacyAdapterSubscriptionsStayRegistrationGated() throws Exception {
        // Adapters are gated when the hook registry builds them; the delivery
        // filter must not double-deny them.
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler());
        final RuntimeEventBroker.Owner owner = broker.admit(PLUGIN_ID);
        new PluginEventBus(
            broker,
            owner.key(),
            grantingOnly(PermissionIds.TURBOISM_EVENT_SUBSCRIBE),
            null
        );
        final CountDownLatch delivered = new CountDownLatch(1);
        broker.subscribeAdapter(
            owner.key(),
            BackupCompletedEvent.class,
            0,
            0,
            ignored -> delivered.countDown()
        );
        owner.activate();

        broker.publishRuntime(new BackupCompletedEvent(1L, List.of(), List.of()));

        assertTrue(delivered.await(5, TimeUnit.SECONDS));
    }

    @Test
    void rawBrokerOwnerWithoutFacadeBindingIsTrusted() throws Exception {
        // Raw broker subscriptions are a compatibility seam not exposed through
        // the public SDK; they deliver unfiltered.
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler());
        final RuntimeEventBroker.Owner owner = broker.admit("dev.internal.runtime");
        final CountDownLatch delivered = new CountDownLatch(1);
        broker.subscribe(
            owner.key(),
            BackupCompletedEvent.class,
            ignored -> delivered.countDown()
        );
        owner.activate();

        broker.publishRuntime(new BackupCompletedEvent(1L, List.of(), List.of()));

        assertTrue(delivered.await(5, TimeUnit.SECONDS));
    }

    @Test
    void deniedDeliveryIsDiagnosedOncePerOwnerAndType() throws Exception {
        final List<RuntimeEventBroker.DeliveryDiagnostic> diagnostics =
            new CopyOnWriteArrayList<>();
        final RuntimeEventBroker broker = new RuntimeEventBroker(
            scheduler(),
            64,
            diagnostics::add
        );
        final PluginEventBus eventBus = new PluginEventBus(
            broker,
            PLUGIN_ID,
            grantingOnly(PermissionIds.TURBOISM_EVENT_SUBSCRIBE)
        );
        eventBus.subscribe(EventBus.TurboismEvent.class, ignored -> { });

        broker.publishRuntime(new BackupCompletedEvent(1L, List.of(), List.of()));
        broker.publishRuntime(new BackupCompletedEvent(2L, List.of(), List.of()));

        awaitMailbox(broker, broker.legacyOwner(PLUGIN_ID));
        assertEquals(
            1,
            diagnostics.stream()
                .filter(diagnostic -> diagnostic.code()
                    == RuntimeEventBroker.DeliveryDiagnostic.Code.DELIVERY_PERMISSION_DENIED)
                .count(),
            "repeat denials for the same owner/type must not spam diagnostics"
        );
    }

    /**
     * Deterministic mailbox drain: a private barrier event published to the same
     * owner lands behind every previously enqueued delivery, so its callback
     * proves the owner mailbox has processed all prior work.
     */
    private void awaitMailbox(
        final RuntimeEventBroker broker,
        final PluginEventOwnerKey owner
    ) throws InterruptedException {
        final CountDownLatch drained = new CountDownLatch(1);
        final Registration barrier = broker.subscribe(
            owner,
            Barrier.class,
            ignored -> drained.countDown()
        );
        try {
            broker.publish(owner, new Barrier());
            assertTrue(
                drained.await(5, TimeUnit.SECONDS),
                "owner mailbox must drain before asserting delivery absence"
            );
        } finally {
            barrier.close();
        }
    }

    private RuntimeScheduler scheduler() {
        final List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        scheduler = new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 4, events::add, CLOCK),
            new NoOpSidecarDispatcher(),
            events::add
        );
        return scheduler;
    }

    private static PermissionChecker grantingOnly(final String... permissionIds) {
        final Set<String> granted = Set.of(permissionIds);
        return (permissionId, operation) -> {
            if (!granted.contains(permissionId)) {
                throw new CubismPermissionException(
                    "Missing required permission " + permissionId + " for " + operation
                );
            }
        };
    }

    private static void awaitUninterruptibly(final CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class WildcardAnnotatedSubscriber {
        private final CountDownLatch delivered;

        private WildcardAnnotatedSubscriber(final CountDownLatch delivered) {
            this.delivered = delivered;
        }

        @SubscribeEvent
        public void onEvent(final EventBus.TurboismEvent event) {
            delivered.countDown();
        }
    }

    private static final class BeforeCallback
        implements RuntimeEventBroker.TransformCallback {
        private final ParameterValueEvent.Before event;

        private BeforeCallback(final Parameter parameter, final float value) {
            event = new ParameterValueEvent.Before(parameter, value, value);
        }

        @Override
        public EventBus.TurboismEvent event() {
            return event;
        }

        @Override
        public void close() {
        }
    }

    private static final class TestParameter implements Parameter {
        @Override public ParameterId id() { return new ParameterId("ParamAngleX"); }
        @Override public float getValue() { return 1.0F; }
        @Override public float getMinimumValue() { return -30.0F; }
        @Override public float getMaximumValue() { return 30.0F; }
        @Override public float getDefaultValue() { return 0.0F; }
        @Override public void setValue(final float value) { }
    }

    public record TestEvent(String value) implements EventBus.TurboismEvent {
    }

    /** Private exact-type event used only to prove an owner mailbox drained. */
    public record Barrier() implements EventBus.TurboismEvent {
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
