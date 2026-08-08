package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.sdk.cubism.CubismPlugin;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ParameterLifecycleCoordinatorTest {

    @Test
    void coordinatesParameterSetterInPluginAndEntrypointOrder() {
        final List<String> events = new ArrayList<>();
        final ParameterLifecycleCoordinator coordinator = new ParameterLifecycleCoordinator();
        coordinator.register(plugin(
            "plugin-a",
            List.of(new HalvingHook(), new RecordingHook("a2", events))
        ));
        coordinator.register(plugin(
            "plugin-b",
            List.of(new ClampingHook(), new RecordingHook("b2", events))
        ));
        final MutableParameter parameter = new MutableParameter(0.0F);

        coordinator.setValue(parameter, 100.0F, parameter::write);
        coordinator.awaitIdle();

        assertEquals(20.0F, parameter.getValue());
        assertEquals(4, events.size());
        assertEquals(1, events.stream().filter(value -> value.equals("a2:on:0.0->20.0")).count());
        assertEquals(1, events.stream().filter(value -> value.equals("a2:after:20.0")).count());
        assertEquals(1, events.stream().filter(value -> value.equals("b2:on:0.0->20.0")).count());
        assertEquals(1, events.stream().filter(value -> value.equals("b2:after:20.0")).count());
        assertEquals(
            events.indexOf("a2:on:0.0->20.0") + 1,
            events.indexOf("a2:after:20.0")
        );
        assertEquals(
            events.indexOf("b2:on:0.0->20.0") + 1,
            events.indexOf("b2:after:20.0")
        );
    }

    @Test
    void unchangedCompletionPublishesOnlyAfterAndNativeFailurePublishesNothing() {
        final List<String> events = new ArrayList<>();
        final ParameterLifecycleCoordinator coordinator = new ParameterLifecycleCoordinator();
        coordinator.register(plugin("plugin-a", List.of(new RecordingHook("a", events))));
        final MutableParameter parameter = new MutableParameter(5.0F);

        coordinator.setValue(parameter, 5.0F, parameter::write);
        coordinator.awaitIdle();
        assertEquals(List.of("a:after:5.0"), events);

        events.clear();
        assertThrows(IllegalStateException.class, () ->
            coordinator.setValue(parameter, 9.0F, ignored -> {
                throw new IllegalStateException("native failed");
            })
        );
        coordinator.awaitIdle();
        assertEquals(List.of(), events);
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
    void staleGenerationCleanupCannotRemoveReplacementHooks() {
        final List<String> events = new ArrayList<>();
        final ParameterLifecycleCoordinator coordinator = new ParameterLifecycleCoordinator();
        final Object firstGeneration = new Object();
        final Object replacementGeneration = new Object();
        coordinator.register(firstGeneration, plugin("plugin-a", List.of(new RecordingHook("old", events))));
        coordinator.register(replacementGeneration, plugin("plugin-a", List.of(new RecordingHook("new", events))));

        coordinator.unregister("plugin-a", firstGeneration);
        final MutableParameter parameter = new MutableParameter(0.0F);
        coordinator.setValue(parameter, 1.0F, parameter::write);
        coordinator.awaitIdle();

        assertEquals(List.of("new:on:0.0->1.0", "new:after:1.0"), events);
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

    private static final class HalvingHook implements CubismPlugin {
        @Override
        public float beforeSetParameterValue(final Parameter parameter, final float value) {
            return value * 0.5F;
        }
    }

    private static final class ClampingHook implements CubismPlugin {
        @Override
        public float beforeSetParameterValue(final Parameter parameter, final float value) {
            return Math.min(value, 20.0F);
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
