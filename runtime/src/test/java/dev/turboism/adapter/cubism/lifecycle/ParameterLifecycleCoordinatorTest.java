package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.core.event.EntrypointSubscriberCatalog;
import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.sidecar.SidecarResult;
import dev.turboism.sdk.event.EventPriority;
import dev.turboism.sdk.event.SubscribeEvent;
import dev.turboism.sdk.event.cubism.ParameterValueEvent;
import dev.turboism.sdk.cubism.hook.ParameterHooks;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParameterLifecycleCoordinatorTest {

    @Test
    void appliesBeforeTransformsInRegistrationAndEntrypointOrder() {
        final List<String> transforms = new ArrayList<>();
        final ParameterLifecycleCoordinator coordinator = new ParameterLifecycleCoordinator();
        coordinator.register(plugin(
            "plugin-a",
            List.of(new TransformingHook("a1", transforms, value -> value * 0.5F),
                new TransformingHook("a2", transforms, value -> value + 3.0F))
        ));
        coordinator.register(plugin(
            "plugin-b",
            List.of(new TransformingHook("b1", transforms, value -> Math.min(value, 20.0F)))
        ));
        final MutableParameter parameter = new MutableParameter(0.0F);

        coordinator.setValue(parameter, 100.0F, parameter::write);

        assertEquals(20.0F, parameter.getValue());
        assertEquals(List.of("a1:100.0", "a2:50.0", "b1:53.0"), transforms);
    }

    @Test
    void failingAndNonFiniteBeforeHooksPreserveThePriorEffectiveValue() {
        final List<String> transforms = new ArrayList<>();
        final ParameterLifecycleCoordinator coordinator = new ParameterLifecycleCoordinator();
        coordinator.register(plugin("plugin-a", List.of(
            new TransformingHook("first", transforms, value -> value * 0.5F),
            new ThrowingBeforeHook(),
            new NonFiniteBeforeHook(),
            new TransformingHook("last", transforms, value -> value + 1.0F)
        )));
        final MutableParameter parameter = new MutableParameter(0.0F);

        coordinator.setValue(parameter, 8.0F, parameter::write);

        assertEquals(5.0F, parameter.getValue());
        assertEquals(List.of("first:8.0", "last:4.0"), transforms);
    }

    @Test
    void completionPublishesChangedOnlyOnAndAlwaysAfterForSuccessfulWrites() {
        final List<String> events = new ArrayList<>();
        final ParameterLifecycleCoordinator coordinator = new ParameterLifecycleCoordinator();
        coordinator.register(plugin("plugin-a", List.of(new RecordingHook("a", events))));
        final MutableParameter parameter = new MutableParameter(5.0F);

        coordinator.setValue(parameter, 5.0F, parameter::write);
        coordinator.awaitIdle();
        assertEquals(List.of("a:after:5.0"), events);

        events.clear();
        coordinator.setValue(parameter, 9.0F, parameter::write);
        coordinator.awaitIdle();
        assertEquals(List.of("a:on:5.0->9.0", "a:after:9.0"), events);
    }

    @Test
    void annotatedBeforeTransformsSynchronouslyWithCheckpointRestoreAndSealing() {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final ParameterLifecycleCoordinator coordinator = new ParameterLifecycleCoordinator();
        coordinator.attachEventBroker(broker);
        final RuntimeEventBroker.Owner firstOwner = broker.admit("annotated-a");
        final RuntimeEventBroker.Owner secondOwner = broker.admit("annotated-b");
        final List<String> callbacks = new ArrayList<>();
        final AtomicReference<ParameterValueEvent.Before> retained = new AtomicReference<>();
        firstOwner.registerAnnotated(new EntrypointSubscriberCatalog().inspect(List.of(
            new AnnotatedBeforeSubscriber(callbacks, retained)
        )));
        secondOwner.registerAnnotated(new EntrypointSubscriberCatalog().inspect(List.of(
            new LaterAnnotatedBeforeSubscriber(callbacks)
        )));
        firstOwner.activate();
        secondOwner.activate();
        final MutableParameter parameter = new MutableParameter(0.0F);

        coordinator.setValue(parameter, 8.0F, parameter::write);

        assertEquals(5.0F, parameter.getValue());
        assertEquals(List.of("first:8.0", "throw:4.0", "non-finite:4.0", "last:4.0"), callbacks);
        assertThrows(IllegalStateException.class, () -> retained.get().setValue(100.0F));
        scheduler.shutdown();
    }

    @Test
    void annotatedCompletionPublishesDetachedChangedOnlyOnAndAlwaysAfter() throws Exception {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final ParameterLifecycleCoordinator coordinator = new ParameterLifecycleCoordinator();
        coordinator.attachEventBroker(broker);
        final RuntimeEventBroker.Owner observer = broker.admit("annotated-observer");
        final CountDownLatch deliveries = new CountDownLatch(3);
        final List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        final AtomicReference<Parameter> observed = new AtomicReference<>();
        observer.registerAnnotated(new EntrypointSubscriberCatalog().inspect(List.of(
            new AnnotatedCompletionSubscriber(events, observed, deliveries)
        )));
        observer.activate();
        final MutableParameter parameter = new MutableParameter(5.0F);

        coordinator.setValue(parameter, 5.0F, parameter::write);
        coordinator.setValue(parameter, 9.0F, parameter::write);

        assertTrue(deliveries.await(1, TimeUnit.SECONDS));
        assertEquals(List.of("after:5.0", "on:5.0->9.0", "after:9.0"), events);
        assertNotSame(parameter, observed.get());
        assertEquals(9.0F, observed.get().getValue());
        assertThrows(UnsupportedOperationException.class, () -> observed.get().setValue(1.0F));
        scheduler.shutdown();
    }

    @Test
    void nativeFailurePreservesValueAndPublishesNoCallbacks() {
        final List<String> events = new ArrayList<>();
        final ParameterLifecycleCoordinator coordinator = new ParameterLifecycleCoordinator();
        coordinator.register(plugin("plugin-a", List.of(new RecordingHook("a", events))));
        final MutableParameter parameter = new MutableParameter(5.0F);

        assertThrows(IllegalStateException.class, () ->
            coordinator.setValue(parameter, 9.0F, ignored -> {
                throw new IllegalStateException("native failed");
            })
        );
        coordinator.awaitIdle();

        assertEquals(5.0F, parameter.getValue());
        assertEquals(List.of(), events);
    }

    @Test
    void callbackFailuresDoNotPreventLaterCallbacksOrSuccessfulWrites() {
        final List<String> events = new ArrayList<>();
        final ParameterLifecycleCoordinator coordinator = new ParameterLifecycleCoordinator();
        coordinator.register(plugin("plugin-a", List.of(
            new ThrowingCallbackHook(),
            new RecordingHook("survives", events)
        )));
        final MutableParameter parameter = new MutableParameter(0.0F);

        coordinator.setValue(parameter, 7.0F, parameter::write);
        coordinator.awaitIdle();

        assertEquals(7.0F, parameter.getValue());
        assertEquals(List.of("survives:on:0.0->7.0", "survives:after:7.0"), events);
    }

    @Test
    void isolatesBadBeforeHooksRejectsRecursionAndRemovesUnregisteredPlugins() {
        final List<String> events = new ArrayList<>();
        final ParameterLifecycleCoordinator coordinator = new ParameterLifecycleCoordinator();
        coordinator.register(plugin("plugin-a", List.of(
            new ThrowingBeforeHook(),
            new NonFiniteBeforeHook(),
            new RecordingHook("a", events)
        )));
        final MutableParameter parameter = new MutableParameter(0.0F);

        coordinator.setValue(parameter, 8.0F, parameter::write);
        coordinator.awaitIdle();
        assertEquals(8.0F, parameter.getValue());
        assertEquals(List.of("a:on:0.0->8.0", "a:after:8.0"), events);

        assertThrows(IllegalStateException.class, () -> coordinator.setValue(
            parameter,
            9.0F,
            value -> coordinator.setValue(parameter, value, parameter::write)
        ));
        assertEquals(8.0F, parameter.getValue());

        events.clear();
        coordinator.unregister("plugin-a");
        coordinator.setValue(parameter, 10.0F, parameter::write);
        coordinator.awaitIdle();
        assertEquals(List.of(), events);
    }

    @Test
    void callbackQueueSaturationDoesNotFailOrInlineTheNativeWrite() throws Exception {
        final List<dev.turboism.core.diagnostics.PluginWorkBudgetEvent> diagnostics =
            new java.util.concurrent.CopyOnWriteArrayList<>();
        final PluginWorkExecutorRegistry executors = new PluginWorkExecutorRegistry(
            1,
            1,
            diagnostics::add,
            Clock.systemUTC()
        );
        final ParameterLifecycleCoordinator coordinator =
            new ParameterLifecycleCoordinator(executors);
        final CountDownLatch callbackStarted = new CountDownLatch(1);
        final CountDownLatch releaseCallback = new CountDownLatch(1);
        coordinator.register(plugin("plugin-a", List.of(new CubismPlugin() {
            @Override public void afterSetParameterValue(
                final Parameter parameter,
                final float value
            ) {
                callbackStarted.countDown();
                try {
                    releaseCallback.await();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
            }
        })));
        final MutableParameter parameter = new MutableParameter(0.0F);

        coordinator.setValue(parameter, 1.0F, parameter::write);
        org.junit.jupiter.api.Assertions.assertTrue(callbackStarted.await(1, TimeUnit.SECONDS));
        coordinator.setValue(parameter, 2.0F, parameter::write);
        coordinator.setValue(parameter, 3.0F, parameter::write);

        assertEquals(3.0F, parameter.getValue());
        org.junit.jupiter.api.Assertions.assertTrue(diagnostics.stream().anyMatch(event ->
            event.phase() == dev.turboism.core.diagnostics.PluginWorkBudgetEvent.Phase.REJECTED
        ));
        releaseCallback.countDown();
        coordinator.close();
    }

    @Test
    void unregisterWaitsForRunningCallbackBeforeReleasingPluginHooks() throws Exception {
        final ParameterLifecycleCoordinator coordinator = new ParameterLifecycleCoordinator();
        final CountDownLatch callbackStarted = new CountDownLatch(1);
        final CountDownLatch releaseCallback = new CountDownLatch(1);
        coordinator.register(plugin("plugin-a", List.of(new CubismPlugin() {
            @Override public void afterSetParameterValue(
                final Parameter parameter,
                final float value
            ) {
                callbackStarted.countDown();
                try {
                    releaseCallback.await();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
            }
        })));
        final MutableParameter parameter = new MutableParameter(0.0F);
        coordinator.setValue(parameter, 1.0F, parameter::write);
        org.junit.jupiter.api.Assertions.assertTrue(callbackStarted.await(1, TimeUnit.SECONDS));

        final CountDownLatch unregisterFinished = new CountDownLatch(1);
        final Thread unregister = new Thread(() -> {
            coordinator.unregister("plugin-a");
            unregisterFinished.countDown();
        });
        unregister.start();
        org.junit.jupiter.api.Assertions.assertFalse(
            unregisterFinished.await(100, TimeUnit.MILLISECONDS),
            "unregister must wait for the in-flight callback executor to quiesce"
        );

        releaseCallback.countDown();
        org.junit.jupiter.api.Assertions.assertTrue(unregisterFinished.await(5, TimeUnit.SECONDS));
        unregister.join(5_000L);

        coordinator.setValue(parameter, 2.0F, parameter::write);
        coordinator.awaitIdle();
        assertEquals(2.0F, parameter.getValue());
        coordinator.close();
    }


    @Test
    void hookPermissionsSeparateInterceptionFromObservation() {
        final List<String> events = new ArrayList<>();
        final ParameterLifecycleCoordinator coordinator = new ParameterLifecycleCoordinator();
        coordinator.register(new ParameterLifecycleCoordinator.PluginHooks(
            descriptor("observe-only"),
            List.of(new CubismPlugin() {
                @Override public float beforeSetParameterValue(
                    final Parameter parameter,
                    final float value
                ) { return value * 0.5F; }
                @Override public void afterSetParameterValue(
                    final Parameter parameter,
                    final float value
                ) { events.add("observe:" + value); }
            }),
            logger(),
            false,
            true
        ));
        coordinator.register(new ParameterLifecycleCoordinator.PluginHooks(
            descriptor("intercept-only"),
            List.of(new CubismPlugin() {
                @Override public float beforeSetParameterValue(
                    final Parameter parameter,
                    final float value
                ) { return value * 0.5F; }
                @Override public void afterSetParameterValue(
                    final Parameter parameter,
                    final float value
                ) { events.add("unexpected"); }
            }),
            logger(),
            true,
            false
        ));
        final MutableParameter parameter = new MutableParameter(0.0F);

        coordinator.setValue(parameter, 8.0F, parameter::write);
        coordinator.awaitIdle();

        assertEquals(4.0F, parameter.getValue());
        assertEquals(List.of("observe:4.0"), events);
    }

    private static ParameterLifecycleCoordinator.PluginHooks plugin(
        final String id,
        final List<? extends ParameterHooks> entrypoints
    ) {
        return new ParameterLifecycleCoordinator.PluginHooks(
            descriptor(id),
            List.copyOf(entrypoints),
            logger()
        );
    }

    private record TransformingHook(
        String name,
        List<String> transforms,
        java.util.function.UnaryOperator<Float> transformation
    ) implements CubismPlugin {
        @Override
        public float beforeSetParameterValue(final Parameter parameter, final float value) {
            transforms.add(name + ":" + value);
            return transformation.apply(value);
        }
    }

    private static final class ThrowingCallbackHook implements CubismPlugin {
        @Override
        public void onParameterValueChanged(
            final Parameter parameter,
            final float oldValue,
            final float newValue
        ) {
            throw new IllegalStateException("on callback failed");
        }

        @Override
        public void afterSetParameterValue(final Parameter parameter, final float value) {
            throw new IllegalStateException("after callback failed");
        }
    }

    private static final class ThrowingBeforeHook implements CubismPlugin {
        @Override
        public float beforeSetParameterValue(final Parameter parameter, final float value) {
            throw new IllegalStateException("bad hook");
        }
    }

    private static final class NonFiniteBeforeHook implements CubismPlugin {
        @Override
        public float beforeSetParameterValue(final Parameter parameter, final float value) {
            return Float.NaN;
        }
    }

    private record RecordingHook(String name, List<String> events) implements CubismPlugin {
        @Override
        public void onParameterValueChanged(
            final Parameter parameter,
            final float oldValue,
            final float newValue
        ) {
            events.add(name + ":on:" + oldValue + "->" + newValue);
        }

        @Override
        public void afterSetParameterValue(final Parameter parameter, final float value) {
            events.add(name + ":after:" + value);
        }
    }

    public static final class AnnotatedBeforeSubscriber {
        private final List<String> callbacks;
        private final AtomicReference<ParameterValueEvent.Before> retained;

        private AnnotatedBeforeSubscriber(
            final List<String> callbacks,
            final AtomicReference<ParameterValueEvent.Before> retained
        ) {
            this.callbacks = callbacks;
            this.retained = retained;
        }

        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void first(final ParameterValueEvent.Before event) {
            callbacks.add("first:" + event.value());
            retained.set(event);
            event.setValue(event.value() * 0.5F);
        }

        @SubscribeEvent(priority = EventPriority.HIGH)
        public void throwing(final ParameterValueEvent.Before event) {
            callbacks.add("throw:" + event.value());
            event.setValue(100.0F);
            throw new IllegalStateException("restore candidate");
        }

        @SubscribeEvent
        public void nonFinite(final ParameterValueEvent.Before event) {
            callbacks.add("non-finite:" + event.value());
            event.setValue(Float.NaN);
        }
    }

    public static final class LaterAnnotatedBeforeSubscriber {
        private final List<String> callbacks;

        private LaterAnnotatedBeforeSubscriber(final List<String> callbacks) {
            this.callbacks = callbacks;
        }

        @SubscribeEvent(priority = EventPriority.LOW)
        public void last(final ParameterValueEvent.Before event) {
            callbacks.add("last:" + event.value());
            event.setValue(event.value() + 1.0F);
        }
    }

    public static final class AnnotatedCompletionSubscriber {
        private final List<String> events;
        private final AtomicReference<Parameter> observed;
        private final CountDownLatch deliveries;

        private AnnotatedCompletionSubscriber(
            final List<String> events,
            final AtomicReference<Parameter> observed,
            final CountDownLatch deliveries
        ) {
            this.events = events;
            this.observed = observed;
            this.deliveries = deliveries;
        }

        @SubscribeEvent
        public void on(final ParameterValueEvent.On event) {
            observed.set(event.parameter());
            events.add("on:" + event.oldValue() + "->" + event.newValue());
            deliveries.countDown();
        }

        @SubscribeEvent
        public void after(final ParameterValueEvent.After event) {
            observed.set(event.parameter());
            events.add("after:" + event.finalValue());
            deliveries.countDown();
        }
    }

    private static RuntimeScheduler scheduler() {
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(
                1,
                8,
                ignored -> { },
                Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC)
            ),
            new NoOpSidecarDispatcher(),
            ignored -> { }
        );
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

    private static final class MutableParameter implements Parameter {
        private float value;

        private MutableParameter(final float value) {
            this.value = value;
        }

        private void write(final float value) {
            this.value = value;
        }

        @Override public ParameterId id() { return new ParameterId("ParamAngleX"); }
        @Override public float getValue() { return value; }
        @Override public float getMinimumValue() { return -30.0F; }
        @Override public float getMaximumValue() { return 30.0F; }
        @Override public float getDefaultValue() { return 0.0F; }
        @Override public void setValue(final float value) { write(value); }
    }

    private static PluginDescriptor descriptor(final String id) {
        return new PluginDescriptor() {
            @Override public String id() { return id; }
            @Override public String name() { return id; }
            @Override public String version() { return "1.0.0"; }
            @Override public String description() { return "test"; }
            @Override public List<String> entrypoints() { return List.of(); }
            @Override public String turboismApi() { return "[0.1.0,0.2.0)"; }
            @Override public List<Author> authors() { return List.of(); }
            @Override public String license() { return "UNLICENSED"; }
            @Override public Optional<String> website() { return Optional.empty(); }
            @Override public List<String> resources() { return List.of(); }
            @Override public I18n i18n() { return new I18n() {
                @Override public String baseName() { return "messages"; }
                @Override public List<String> locales() { return List.of(); }
            }; }
            @Override public List<DependencyRef> dependencies() { return List.of(); }
            @Override public List<PermissionRef> permissions() { return List.of(); }
            @Override public List<String> capabilities() { return List.of(); }
            @Override public Environment environment() { return new Environment() {
                @Override public boolean requiresCubism() { return false; }
                @Override public String ui() { return "none"; }
            }; }
        };
    }

    private static PluginLogger logger() {
        return new PluginLogger() {
            @Override public void debug(final String message) { }
            @Override public void info(final String message) { }
            @Override public void warn(final String message) { }
            @Override public void error(final String message) { }
            @Override public void error(final String message, final Throwable throwable) { }
        };
    }
}
