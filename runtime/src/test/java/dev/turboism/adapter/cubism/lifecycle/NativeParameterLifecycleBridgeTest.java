package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NativeParameterLifecycleBridgeTest {

    @Test
    void nativeUiInvocationUsesTheSameLifecycleAndFacadeCorrelationPreventsDuplicates() {
        final List<String> events = new ArrayList<>();
        final ParameterLifecycleCoordinator coordinator = new ParameterLifecycleCoordinator();
        coordinator.register(new ParameterLifecycleCoordinator.PluginHooks(
            descriptor(),
            List.of(new CubismPlugin() {
                @Override public float beforeSetParameterValue(
                    final Parameter parameter,
                    final float value
                ) { return value * 0.5F; }
                @Override public void onParameterValueChanged(
                    final Parameter parameter,
                    final float oldValue,
                    final float newValue
                ) { events.add("on:" + oldValue + "->" + newValue); }
                @Override public void afterSetParameterValue(
                    final Parameter parameter,
                    final float value
                ) { events.add("after:" + value); }
            }),
            logger()
        ));
        final MutableParameter parameter = new MutableParameter();
        final NativeParameterLifecycleBridge bridge = new NativeParameterLifecycleBridge(coordinator);
        NativeParameterLifecycleBridge.install(bridge);

        final long nativeToken = NativeParameterLifecycleBridge.before(parameter, 8.0F);
        parameter.value = NativeParameterLifecycleBridge.effectiveValue(nativeToken);
        NativeParameterLifecycleBridge.after(nativeToken);
        coordinator.awaitIdle();
        assertEquals(4.0F, parameter.value);
        assertEquals(List.of("on:0.0->4.0", "after:4.0"), events);

        events.clear();
        coordinator.setValue(parameter, 10.0F, value -> {
            final long token = NativeParameterLifecycleBridge.before(parameter, value);
            parameter.value = NativeParameterLifecycleBridge.effectiveValue(token);
            NativeParameterLifecycleBridge.after(token);
        });
        coordinator.awaitIdle();
        assertEquals(5.0F, parameter.value);
        assertEquals(List.of("on:4.0->5.0", "after:5.0"), events);
    }

    @Test
    void instrumentedStaticBridgeResolvesParameterByIdAndFailsOpenWhenUnavailable() {
        final List<String> events = new ArrayList<>();
        final ParameterLifecycleCoordinator coordinator = new ParameterLifecycleCoordinator();
        coordinator.register(new ParameterLifecycleCoordinator.PluginHooks(
            descriptor(),
            List.of(new CubismPlugin() {
                @Override public float beforeSetParameterValue(
                    final Parameter parameter,
                    final float value
                ) { return value + 1.0F; }
                @Override public void afterSetParameterValue(
                    final Parameter parameter,
                    final float value
                ) { events.add("after:" + value); }
            }),
            logger()
        ));
        final MutableParameter parameter = new MutableParameter();
        final NativeParameterLifecycleBridge bridge = new NativeParameterLifecycleBridge(
            coordinator,
            () -> model(parameter)
        );
        NativeParameterLifecycleBridge.install(bridge);

        parameter.value = NativeParameterLifecycleBridge.beforeNative("ParamA", 2.0F);
        NativeParameterLifecycleBridge.afterNative();
        coordinator.awaitIdle();
        assertEquals(3.0F, parameter.value);
        assertEquals(List.of("after:3.0"), events);

        NativeParameterLifecycleBridge.uninstall(bridge);
        assertEquals(7.0F, NativeParameterLifecycleBridge.beforeNative("ParamA", 7.0F));
        NativeParameterLifecycleBridge.afterNative();
    }

    private static dev.turboism.sdk.cubism.model.CubismModel model(
        final Parameter parameter
    ) {
        return new dev.turboism.sdk.cubism.model.CubismModel() {
            @Override public dev.turboism.sdk.cubism.id.ModelId id() {
                return new dev.turboism.sdk.cubism.id.ModelId("model-a");
            }
            @Override public dev.turboism.sdk.cubism.model.Parameters parameters() {
                return new dev.turboism.sdk.cubism.model.Parameters() {
                    @Override public List<Parameter> all() { return List.of(parameter); }
                    @Override public Parameter find(final ParameterId id) { return parameter; }
                };
            }
            @Override public dev.turboism.sdk.cubism.model.Parts parts() { throw unsupported(); }
            @Override public dev.turboism.sdk.cubism.model.Drawables drawables() { throw unsupported(); }
            @Override public dev.turboism.sdk.cubism.model.Deformers deformers() { throw unsupported(); }
            @Override public dev.turboism.sdk.cubism.model.Glues glues() { throw unsupported(); }
            @Override public void update() { throw unsupported(); }
            private UnsupportedOperationException unsupported() {
                return new UnsupportedOperationException();
            }
        };
    }

    private static final class MutableParameter implements Parameter {
        private float value;
        @Override public ParameterId id() { return new ParameterId("ParamA"); }
        @Override public float getValue() { return value; }
        @Override public float getMinimumValue() { return -30.0F; }
        @Override public float getMaximumValue() { return 30.0F; }
        @Override public float getDefaultValue() { return 0.0F; }
        @Override public void setValue(final float value) { this.value = value; }
    }

    private static PluginDescriptor descriptor() {
        return new PluginDescriptor() {
            @Override public String id() { return "plugin-a"; }
            @Override public String name() { return "plugin-a"; }
            @Override public String version() { return "1.0.0"; }
            @Override public String description() { return "test"; }
            @Override public List<String> entrypoints() { return List.of(); }
            @Override public String turboismApi() { return "[0.1.0,0.2.0)"; }
            @Override public List<Author> authors() { return List.of(); }
            @Override public String license() { return "Test"; }
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
