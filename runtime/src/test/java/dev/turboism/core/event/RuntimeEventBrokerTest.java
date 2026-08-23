package dev.turboism.core.event;

import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.sidecar.SidecarResult;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.event.SubscribeEvent;
import dev.turboism.sdk.event.TurboismEvent;
import dev.turboism.sdk.event.cubism.ParameterValueEvent;
import dev.turboism.sdk.appearance.AppearanceBase;
import dev.turboism.sdk.appearance.AppearanceChangedEvent;
import dev.turboism.sdk.appearance.AppearanceStatus;
import dev.turboism.sdk.cubism.backup.BackupArtifact;
import dev.turboism.sdk.cubism.backup.BackupCompletedEvent;
import dev.turboism.sdk.cubism.event.SelectionChangedEvent;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.service.query.SelectionSummary;
import dev.turboism.sdk.ui.table.SceneTableHeaderClickEvent;
import dev.turboism.sdk.ui.table.SceneTableService;
import dev.turboism.sdk.cubism.model.Parameter;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeEventBrokerTest {

    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-08-23T00:00:00Z"),
        ZoneOffset.UTC
    );

    @Test
    void eventPublishedByOneFacadeReachesSubscriberOwnedByAnotherPlugin() throws Exception {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final PluginEventBus publisher = new PluginEventBus(
            broker,
            "dev.example.publisher",
            PermissionChecker.allowAll()
        );
        final PluginEventBus subscriber = new PluginEventBus(
            broker,
            "dev.example.subscriber",
            PermissionChecker.allowAll()
        );
        final CountDownLatch delivered = new CountDownLatch(1);
        final AtomicReference<TestEvent> received = new AtomicReference<>();
        final TestEvent event = new TestEvent("cross-plugin");
        subscriber.subscribe(TestEvent.class, value -> {
            received.set(value);
            delivered.countDown();
        });

        publisher.publish(event);

        assertTrue(delivered.await(1, TimeUnit.SECONDS));
        assertSame(event, received.get());
        scheduler.shutdown();
    }

    @Test
    void closedRegistrationDoesNotChangePublicationSnapshot() throws Exception {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final RuntimeEventBroker.Owner publisher = broker.admit("dev.example.publisher");
        final RuntimeEventBroker.Owner subscriber = broker.admit("dev.example.subscriber");
        final CountDownLatch blockerEntered = new CountDownLatch(1);
        final CountDownLatch releaseBlocker = new CountDownLatch(1);
        final CountDownLatch closedDelivered = new CountDownLatch(1);
        broker.subscribe(subscriber.key(), TestEvent.class, event -> {
            if ("block".equals(event.value())) {
                blockerEntered.countDown();
                await(releaseBlocker);
            }
        });
        final dev.turboism.sdk.plugin.Registration registration = broker.subscribe(
            subscriber.key(),
            TestEvent.class,
            event -> {
                if ("queued".equals(event.value())) {
                    closedDelivered.countDown();
                }
            }
        );
        publisher.activate();
        subscriber.activate();

        broker.publish(publisher.key(), new TestEvent("block"));
        assertTrue(blockerEntered.await(1, TimeUnit.SECONDS));
        broker.publish(publisher.key(), new TestEvent("queued"));
        registration.close();
        releaseBlocker.countDown();

        assertTrue(closedDelivered.await(1, TimeUnit.SECONDS));
        scheduler.shutdown();
    }

    @Test
    void closedRegistrationDoesNotChangeSynchronousTransformSnapshot() {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final RuntimeEventBroker.Owner subscriber = broker.admit("dev.example.transform");
        final AtomicReference<dev.turboism.sdk.plugin.Registration> second =
            new AtomicReference<>();
        broker.subscribe(
            subscriber.key(),
            TestTransformEvent.class,
            event -> {
                event.value += 1;
                second.get().close();
            }
        );
        second.set(broker.subscribe(
            subscriber.key(),
            TestTransformEvent.class,
            event -> event.value *= 2
        ));
        subscriber.activate();

        final int transformed = broker.publishRuntimeTransform(
            TestTransformEvent.class,
            2,
            value -> new TestTransformCallback(value),
            event -> ((TestTransformEvent) event).value,
            ignored -> true
        );

        assertEquals(6, transformed);
        scheduler.shutdown();
    }

    @Test
    void manualSubscriberRunsWithPluginContextClassLoader() throws Exception {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final RuntimeEventBroker.Owner publisher = broker.admit("dev.example.publisher");
        final RuntimeEventBroker.Owner subscriber = broker.admit("dev.example.subscriber");
        final ClassLoader pluginClassLoader = new ClassLoader(
            RuntimeEventBrokerTest.class.getClassLoader()
        ) { };
        final PluginEventBus eventBus = new PluginEventBus(
            broker,
            subscriber.key(),
            PermissionChecker.allowAll(),
            pluginClassLoader
        );
        final CountDownLatch delivered = new CountDownLatch(1);
        final AtomicReference<ClassLoader> observed = new AtomicReference<>();
        eventBus.subscribe(TestEvent.class, ignored -> {
            observed.set(Thread.currentThread().getContextClassLoader());
            delivered.countDown();
        });
        publisher.activate();
        subscriber.activate();

        broker.publish(publisher.key(), new TestEvent("tccl"));

        assertTrue(delivered.await(1, TimeUnit.SECONDS));
        assertSame(pluginClassLoader, observed.get());
        scheduler.shutdown();
    }

    @Test
    void admittedOwnerStagesSubscriptionsUntilActivation() throws Exception {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final RuntimeEventBroker.Owner publisher = broker.admit("dev.example.publisher");
        final RuntimeEventBroker.Owner subscriber = broker.admit("dev.example.subscriber");
        final CountDownLatch delivered = new CountDownLatch(1);
        broker.subscribe(subscriber.key(), TestEvent.class, ignored -> delivered.countDown());
        publisher.activate();

        broker.publish(publisher.key(), new TestEvent("inactive-subscriber"));
        assertFalse(delivered.await(100, TimeUnit.MILLISECONDS));

        subscriber.activate();
        broker.publish(publisher.key(), new TestEvent("active-subscriber"));

        assertTrue(delivered.await(1, TimeUnit.SECONDS));
        scheduler.shutdown();
    }

    @Test
    void initializingOwnerMayPublishToAnnotatedSubscribersDuringLifecycle() throws Exception {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final RuntimeEventBroker.Owner publisher = broker.admit("dev.example.initializing");
        final RuntimeEventBroker.Owner subscriber = broker.admit("dev.example.active");
        final CountDownLatch manualDelivered = new CountDownLatch(1);
        final CountDownLatch annotatedDelivered = new CountDownLatch(1);
        broker.subscribe(subscriber.key(), TestEvent.class, ignored -> manualDelivered.countDown());
        broker.registerAnnotated(
            publisher.key(),
            new EntrypointSubscriberCatalog().inspect(List.of(
                new InitializingAnnotatedSubscriber(annotatedDelivered)
            ))
        );
        subscriber.activate();
        publisher.beginInitializing();

        broker.publish(publisher.key(), new TestEvent("init"));

        assertTrue(manualDelivered.await(1, TimeUnit.SECONDS));
        assertTrue(annotatedDelivered.await(1, TimeUnit.SECONDS));
        publisher.beginEnabling();
        publisher.activate();
        scheduler.shutdown();
    }

    @Test
    void enablingOwnerMayUseManualEventBusBeforeActivationCommit() throws Exception {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final RuntimeEventBroker.Owner owner = broker.admit("dev.example.enabling");
        final PluginEventBus eventBus = new PluginEventBus(
            broker,
            owner.key(),
            PermissionChecker.allowAll()
        );
        final CountDownLatch delivered = new CountDownLatch(1);
        owner.beginInitializing();
        owner.beginEnabling();

        eventBus.subscribe(TestEvent.class, ignored -> delivered.countDown());
        eventBus.publish(new TestEvent("enable"));

        assertTrue(delivered.await(1, TimeUnit.SECONDS));
        owner.activate();
        scheduler.shutdown();
    }

    @Test
    void closingOwnerRejectsPublicationAndQuiescesWithoutInvokingQueuedWork() throws Exception {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final RuntimeEventBroker.Owner publisher = broker.admit("dev.example.publisher");
        final RuntimeEventBroker.Owner subscriber = broker.admit("dev.example.subscriber");
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch secondDelivered = new CountDownLatch(1);
        broker.subscribe(subscriber.key(), TestEvent.class, event -> {
            if ("first".equals(event.value())) {
                entered.countDown();
                await(release);
            } else {
                secondDelivered.countDown();
            }
        });
        publisher.activate();
        subscriber.activate();

        broker.publish(publisher.key(), new TestEvent("first"));
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        broker.publish(publisher.key(), new TestEvent("second"));
        subscriber.beginClosing();

        assertFalse(subscriber.awaitQuiescence(Duration.ofMillis(50)));
        assertThrows(
            IllegalStateException.class,
            () -> broker.publish(subscriber.key(), new TestEvent("closed"))
        );
        release.countDown();
        assertTrue(subscriber.awaitQuiescence(Duration.ofSeconds(1)));
        assertFalse(secondDelivered.await(100, TimeUnit.MILLISECONDS));
        subscriber.close();
        assertEquals(RuntimeEventBroker.OwnerLifecycle.CLOSED, subscriber.lifecycle());
        scheduler.shutdown();
    }

    @Test
    void staleGenerationCloseCannotDetachReplacementSubscriptions() throws Exception {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final RuntimeEventBroker.Owner publisher = broker.admit("dev.example.publisher");
        final RuntimeEventBroker.Owner first = broker.admit("dev.example.subscriber");
        final RuntimeEventBroker.Owner replacement = broker.admit("dev.example.subscriber");
        final CountDownLatch replacementDelivered = new CountDownLatch(1);
        broker.subscribe(first.key(), TestEvent.class, ignored -> { });
        broker.subscribe(replacement.key(), TestEvent.class, ignored -> replacementDelivered.countDown());
        publisher.activate();
        first.activate();
        replacement.activate();

        first.beginClosing();
        assertTrue(first.awaitQuiescence(Duration.ZERO));
        first.close();
        broker.publish(publisher.key(), new TestEvent("replacement"));

        assertTrue(replacementDelivered.await(1, TimeUnit.SECONDS));
        scheduler.shutdown();
    }

    @Test
    void manualSubscriberFailureIsContainedAsAnEventDiagnostic() throws Exception {
        final List<PluginWorkBudgetEvent> workDiagnostics = new CopyOnWriteArrayList<>();
        final List<RuntimeEventBroker.DeliveryDiagnostic> eventDiagnostics =
            new CopyOnWriteArrayList<>();
        final RuntimeScheduler scheduler = scheduler(workDiagnostics);
        final RuntimeEventBroker broker = new RuntimeEventBroker(
            scheduler,
            64,
            eventDiagnostics::add
        );
        final PluginEventBus eventBus = new PluginEventBus(
            broker,
            "dev.example.failure",
            PermissionChecker.allowAll()
        );
        final CountDownLatch laterSubscriber = new CountDownLatch(1);
        eventBus.subscribe(TestEvent.class, ignored -> {
            throw new IllegalStateException("subscriber failed");
        });
        eventBus.subscribe(TestEvent.class, ignored -> laterSubscriber.countDown());

        eventBus.publish(new TestEvent("failure"));

        assertTrue(laterSubscriber.await(1, TimeUnit.SECONDS));
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (eventDiagnostics.stream().noneMatch(event ->
            event.code() == RuntimeEventBroker.DeliveryDiagnostic.Code.SUBSCRIBER_FAILED
        ) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(eventDiagnostics.stream().anyMatch(event ->
            event.code() == RuntimeEventBroker.DeliveryDiagnostic.Code.SUBSCRIBER_FAILED
                && event.owner().pluginId().equals("dev.example.failure")
        ));
        assertFalse(workDiagnostics.stream().anyMatch(event ->
            event.pluginId().equals("dev.example.failure")
                && event.phase() == PluginWorkBudgetEvent.Phase.FAILED
        ));
        scheduler.shutdown();
    }

    @Test
    void parameterEventSubscriptionsRequireTheirDomainPermissions() {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final PluginEventBus eventBus = new PluginEventBus(
            broker,
            "dev.example.subscriber",
            (permissionId, operation) -> {
                if (dev.turboism.sdk.permission.PermissionIds.TURBOISM_EVENT_SUBSCRIBE.equals(
                    permissionId
                )) {
                    return;
                }
                throw new CubismPermissionException("missing " + permissionId);
            }
        );

        assertThrows(
            CubismPermissionException.class,
            () -> eventBus.subscribe(ParameterValueEvent.Before.class, ignored -> { })
        );
        assertThrows(
            CubismPermissionException.class,
            () -> eventBus.subscribe(ParameterValueEvent.On.class, ignored -> { })
        );
        scheduler.shutdown();
    }

    @Test
    void pluginCannotPublishRuntimeOwnedParameterEvents() {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final PluginEventBus eventBus = new PluginEventBus(
            broker,
            "dev.example.publisher",
            PermissionChecker.allowAll()
        );
        final ParameterValueEvent.After event = new ParameterValueEvent.After(
            new TestParameter(),
            1.0F
        );

        assertThrows(IllegalArgumentException.class, () -> eventBus.publish(event));
        scheduler.shutdown();
    }

    @Test
    void pluginsCannotForgeRuntimeOwnedAppearanceOrBackupObservations() {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final PluginEventBus eventBus = new PluginEventBus(
            broker,
            "dev.example.publisher",
            PermissionChecker.allowAll()
        );
        final AppearanceStatus nativeStatus = new AppearanceStatus(
            AppearanceStatus.Availability.AVAILABLE,
            AppearanceStatus.Source.NATIVE,
            java.util.Optional.empty(),
            AppearanceBase.DARK,
            0L,
            java.util.Optional.empty()
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> eventBus.publish(new AppearanceChangedEvent(
                nativeStatus,
                new AppearanceStatus(
                    AppearanceStatus.Availability.AVAILABLE,
                    AppearanceStatus.Source.PLUGIN_OVERLAY,
                    java.util.Optional.of("theme"),
                    AppearanceBase.DARK,
                    1L,
                    java.util.Optional.empty()
                ),
                "dev.example.publisher"
            ))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> eventBus.publish(new BackupCompletedEvent(
                1L,
                List.of(new BackupArtifact("model_backup.cmo3", 128L, false)),
                List.of()
            ))
        );
        scheduler.shutdown();
    }

    @Test
    void appearanceAndBackupSubscriptionsRequireDomainPermissions() {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final PluginEventBus eventBus = new PluginEventBus(
            broker,
            "dev.example.subscriber",
            (permissionId, operation) -> {
                if (dev.turboism.sdk.permission.PermissionIds.TURBOISM_EVENT_SUBSCRIBE.equals(
                    permissionId
                )) {
                    return;
                }
                throw new CubismPermissionException("missing " + permissionId);
            }
        );

        assertThrows(
            CubismPermissionException.class,
            () -> eventBus.subscribe(AppearanceChangedEvent.class, ignored -> { })
        );
        assertThrows(
            CubismPermissionException.class,
            () -> eventBus.subscribe(BackupCompletedEvent.class, ignored -> { })
        );
        scheduler.shutdown();
    }

    @Test
    void selectionAndSceneTableSubscriptionsRequireDomainPermissions() {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final PluginEventBus eventBus = new PluginEventBus(
            broker,
            "dev.example.subscriber",
            (permissionId, operation) -> {
                if (dev.turboism.sdk.permission.PermissionIds.TURBOISM_EVENT_SUBSCRIBE.equals(
                    permissionId
                )) {
                    return;
                }
                throw new CubismPermissionException("missing " + permissionId);
            }
        );

        assertThrows(
            CubismPermissionException.class,
            () -> eventBus.subscribe(SelectionChangedEvent.class, ignored -> { })
        );
        assertThrows(
            CubismPermissionException.class,
            () -> eventBus.subscribe(SceneTableHeaderClickEvent.class, ignored -> { })
        );
        scheduler.shutdown();
    }

    @Test
    void pluginsCannotForgeRuntimeOwnedSelectionOrSceneTableObservations() {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final PluginEventBus eventBus = new PluginEventBus(
            broker,
            "dev.example.publisher",
            PermissionChecker.allowAll()
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> eventBus.publish(new SelectionChangedEvent(
                SelectionSummary.empty(),
                SelectionSummary.empty()
            ))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> eventBus.publish(new SceneTableHeaderClickEvent(
                new SceneTableService.HeaderClick(SceneTableService.SCENE_TABLE_ID, "name")
            ))
        );
        scheduler.shutdown();
    }

    @Test
    void reviewedFamilySubscriptionReceivesConcreteParameterState() throws Exception {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final RuntimeEventBroker.Owner subscriber = broker.admit("dev.example.observer");
        final CountDownLatch delivered = new CountDownLatch(1);
        broker.subscribe(subscriber.key(), ParameterValueEvent.class, ignored -> delivered.countDown());
        subscriber.activate();

        broker.publishRuntime(new ParameterValueEvent.After(new TestParameter(), 1.0F));

        assertTrue(delivered.await(1, TimeUnit.SECONDS));
        scheduler.shutdown();
    }

    @Test
    void retainedRuntimeEventReplaysOnceAfterStagedSubscriberActivates() throws Exception {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        broker.publishRuntimeRetained(new TestEvent("latest"));
        final RuntimeEventBroker.Owner subscriber = broker.admit("dev.example.replay");
        final java.util.concurrent.atomic.AtomicInteger deliveries =
            new java.util.concurrent.atomic.AtomicInteger();
        final CountDownLatch delivered = new CountDownLatch(1);
        broker.subscribe(subscriber.key(), TestEvent.class, ignored -> {
            deliveries.incrementAndGet();
            delivered.countDown();
        });

        subscriber.activate();

        assertTrue(delivered.await(1, TimeUnit.SECONDS));
        Thread.sleep(50L);
        assertEquals(1, deliveries.get());
        scheduler.shutdown();
    }

    @Test
    void dispatchPlanInvalidatesWhenFamilySubscriberIsAdded() throws Exception {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final RuntimeEventBroker.Owner subscriber = broker.admit("dev.example.observer");
        subscriber.activate();
        broker.publishRuntime(new ParameterValueEvent.After(new TestParameter(), 1.0F));
        final CountDownLatch delivered = new CountDownLatch(1);
        broker.subscribe(subscriber.key(), ParameterValueEvent.class, ignored -> delivered.countDown());

        broker.publishRuntime(new ParameterValueEvent.After(new TestParameter(), 2.0F));

        assertTrue(delivered.await(1, TimeUnit.SECONDS));
        scheduler.shutdown();
    }

    @Test
    void mailboxSaturationProducesStructuredDiagnostic() throws Exception {
        final RuntimeScheduler scheduler = scheduler();
        final List<RuntimeEventBroker.DeliveryDiagnostic> diagnostics =
            new CopyOnWriteArrayList<>();
        final RuntimeEventBroker broker = new RuntimeEventBroker(
            scheduler,
            1,
            diagnostics::add
        );
        final RuntimeEventBroker.Owner publisher = broker.admit("dev.example.publisher");
        final RuntimeEventBroker.Owner subscriber = broker.admit("dev.example.subscriber");
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        broker.subscribe(subscriber.key(), TestEvent.class, ignored -> {
            entered.countDown();
            await(release);
        });
        publisher.activate();
        subscriber.activate();

        broker.publish(publisher.key(), new TestEvent("running"));
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        broker.publish(publisher.key(), new TestEvent("queued"));
        broker.publish(publisher.key(), new TestEvent("dropped"));

        assertEquals(1L, subscriber.droppedDeliveries());
        assertEquals(
            List.of(RuntimeEventBroker.DeliveryDiagnostic.Code.MAILBOX_SATURATED),
            diagnostics.stream().map(RuntimeEventBroker.DeliveryDiagnostic::code).toList()
        );
        release.countDown();
        subscriber.beginClosing();
        assertTrue(subscriber.awaitQuiescence(Duration.ofSeconds(1)));
        scheduler.shutdown();
    }

    @Test
    void taskAttributionBelongsToSubscriberRatherThanPublisher() throws Exception {
        final List<PluginWorkBudgetEvent> diagnostics = new CopyOnWriteArrayList<>();
        final RuntimeScheduler scheduler = scheduler(diagnostics);
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final PluginEventBus publisher = new PluginEventBus(
            broker,
            "dev.example.publisher",
            PermissionChecker.allowAll()
        );
        final PluginEventBus subscriber = new PluginEventBus(
            broker,
            "dev.example.subscriber",
            PermissionChecker.allowAll()
        );
        final CountDownLatch delivered = new CountDownLatch(1);
        subscriber.subscribe(TestEvent.class, ignored -> delivered.countDown());

        publisher.publish(new TestEvent("attributed"));

        assertTrue(delivered.await(1, TimeUnit.SECONDS));
        assertEquals(List.of(), diagnostics);
        scheduler.shutdown();
    }

    private static void await(final CountDownLatch latch) {
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

    private static RuntimeScheduler scheduler() {
        return scheduler(new CopyOnWriteArrayList<>());
    }

    private static RuntimeScheduler scheduler(
        final List<PluginWorkBudgetEvent> diagnostics
    ) {
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 8, diagnostics::add, CLOCK),
            new NoOpSidecarDispatcher(),
            diagnostics::add
        );
    }

    private static final class TestParameter implements Parameter {
        @Override public ParameterId id() { return new ParameterId("ParamAngleX"); }
        @Override public float getValue() { return 1.0F; }
        @Override public float getMinimumValue() { return -30.0F; }
        @Override public float getMaximumValue() { return 30.0F; }
        @Override public float getDefaultValue() { return 0.0F; }
        @Override public void setValue(final float value) { }
    }

    public static final class InitializingAnnotatedSubscriber {
        private final CountDownLatch delivered;

        private InitializingAnnotatedSubscriber(final CountDownLatch delivered) {
            this.delivered = delivered;
        }

        @SubscribeEvent
        public void onEvent(final TestEvent event) {
            delivered.countDown();
        }
    }

    public record TestEvent(String value) implements TurboismEvent {
    }

    private static final class TestTransformEvent implements TurboismEvent {
        private int value;

        private TestTransformEvent(final int value) {
            this.value = value;
        }
    }

    private static final class TestTransformCallback
        implements RuntimeEventBroker.TransformCallback {
        private final TestTransformEvent event;

        private TestTransformCallback(final int value) {
            event = new TestTransformEvent(value);
        }

        @Override
        public TurboismEvent event() {
            return event;
        }

        @Override
        public void close() {
        }
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
