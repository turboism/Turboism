package dev.turboism.adapter.cubism.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.test.Stubs;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ParameterHookRegistryTest {

    @Test
    void discoversOrderedCubismEntrypointsAndRemovesThemOnPluginClose() {
        final List<String> events = new ArrayList<>();
        final ParameterLifecycleCoordinator coordinator = new ParameterLifecycleCoordinator();
        final ParameterHookRegistry registry = new ParameterHookRegistry(coordinator);
        final PluginDescriptor descriptor = descriptor("plugin-a");
        final List<CubismPlugin> entrypoints =
                List.of(new NamedPlugin("first", events), new NamedPlugin("second", events));

        registry.register(descriptor, entrypoints, logger(), new DisposableScope());
        final MutableParameter parameter = new MutableParameter();
        coordinator.setValue(parameter, 1.0F, value -> parameter.value = value);
        coordinator.awaitIdle();
        assertEquals(List.of("first:on", "first:after", "second:on", "second:after"), events);

        events.clear();
        registry.unregister(descriptor.id());
        coordinator.setValue(parameter, 2.0F, value -> parameter.value = value);
        coordinator.awaitIdle();
        assertEquals(List.of(), events);
    }

    @Test
    void scopedRegistryCleanupCannotRemoveReplacementGeneration() throws Exception {
        final List<String> events = new ArrayList<>();
        final ParameterLifecycleCoordinator coordinator = new ParameterLifecycleCoordinator();
        final ParameterHookRegistry registry = new ParameterHookRegistry(coordinator);
        final PluginDescriptor descriptor = descriptor("plugin-a");
        final DisposableScope firstScope = new DisposableScope();
        final DisposableScope replacementScope = new DisposableScope();
        try {
            registry.register(descriptor, List.of(new NamedPlugin("old", events)), logger(), firstScope);
            registry.register(descriptor, List.of(new NamedPlugin("new", events)), logger(), replacementScope);
            firstScope.close();

            final MutableParameter parameter = new MutableParameter();
            coordinator.setValue(parameter, 1.0F, value -> parameter.value = value);
            coordinator.awaitIdle();
            assertEquals(List.of("new:on", "new:after"), events);

            events.clear();
            replacementScope.close();
            coordinator.setValue(parameter, 2.0F, value -> parameter.value = value);
            coordinator.awaitIdle();
            assertEquals(List.of(), events);
        } finally {
            firstScope.close();
            replacementScope.close();
            coordinator.close();
        }
    }

    @Test
    void derivesHookPermissionsFromThePluginDescriptor() {
        final List<String> events = new ArrayList<>();
        final ParameterLifecycleCoordinator coordinator = new ParameterLifecycleCoordinator();
        final ParameterHookRegistry registry = new ParameterHookRegistry(coordinator);
        registry.register(
                descriptor("observe-only", List.of(permission(ParameterHookRegistry.OBSERVE_PERMISSION))),
                List.of(new CubismPlugin() {
                    @Override
                    public float beforeSetParameterValue(final Parameter parameter, final float value) {
                        return value * 0.5F;
                    }

                    @Override
                    public void afterSetParameterValue(final Parameter parameter, final float value) {
                        events.add("after:" + value);
                    }
                }),
                logger(),
                new DisposableScope());
        final MutableParameter parameter = new MutableParameter();

        coordinator.setValue(parameter, 4.0F, value -> parameter.value = value);
        coordinator.awaitIdle();

        assertEquals(4.0F, parameter.value);
        assertEquals(List.of("after:4.0"), events);
    }

    private record NamedPlugin(String name, List<String> events) implements CubismPlugin {
        @Override
        public void onParameterValueChanged(final Parameter parameter, final float oldValue, final float newValue) {
            events.add(name + ":on");
        }

        @Override
        public void afterSetParameterValue(final Parameter parameter, final float value) {
            events.add(name + ":after");
        }
    }

    private static final class MutableParameter implements Parameter {
        private float value;

        @Override
        public dev.turboism.sdk.cubism.id.ParameterId id() {
            return new dev.turboism.sdk.cubism.id.ParameterId("ParamA");
        }

        @Override
        public float getValue() {
            return value;
        }

        @Override
        public float getMinimumValue() {
            return -1.0F;
        }

        @Override
        public float getMaximumValue() {
            return 1.0F;
        }

        @Override
        public float getDefaultValue() {
            return 0.0F;
        }

        @Override
        public void setValue(final float value) {
            this.value = value;
        }
    }

    private static PluginDescriptor descriptor(final String id) {
        return descriptor(
                id,
                List.of(
                        permission(ParameterHookRegistry.INTERCEPT_PERMISSION),
                        permission(ParameterHookRegistry.OBSERVE_PERMISSION)));
    }

    private static PluginDescriptor descriptor(
            final String id, final List<PluginDescriptor.PermissionRef> permissions) {
        return Stubs.pluginDescriptor(id, permissions);
    }

    private static PluginDescriptor.PermissionRef permission(final String id) {
        return new PluginDescriptor.PermissionRef() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String scope() {
                return "model";
            }

            @Override
            public Optional<String> reason() {
                return Optional.of("test");
            }
        };
    }

    private static PluginLogger logger() {
        return Stubs.noopLogger();
    }
}
