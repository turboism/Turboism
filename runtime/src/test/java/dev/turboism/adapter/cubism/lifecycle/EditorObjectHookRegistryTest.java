package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.sdk.cubism.hook.DeformerHooks;
import dev.turboism.sdk.cubism.hook.DrawableHooks;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.FloatSequence;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.TurboismPlugin;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EditorObjectHookRegistryTest {

    @Test
    void discoversEntrypointsInOrderAndSeparatesInterceptFromObservePermission() {
        final List<String> events = new ArrayList<>();
        final EditorObjectLifecycleCoordinator lifecycle = new EditorObjectLifecycleCoordinator();
        final EditorObjectHookRegistry registry = new EditorObjectHookRegistry(lifecycle);
        final OrderedPlugin first = new OrderedPlugin("first", events);
        final OrderedPlugin second = new OrderedPlugin("second", events);
        registry.register(
            descriptor("plugin", List.of(EditorObjectHookRegistry.OBSERVE_PERMISSION)),
            List.of(first, second),
            logger()
        );
        final MutableDrawable drawable = new MutableDrawable();

        lifecycle.drawable().setOpacity(drawable, 0.5F, drawable::write);
        lifecycle.drawable().awaitIdle();

        assertEquals(0.5F, drawable.getOpacity());
        assertEquals(List.of("first-after:0.5", "second-after:0.5"), events);

        events.clear();
        registry.register(
            descriptor("plugin", List.of(EditorObjectHookRegistry.INTERCEPT_PERMISSION)),
            List.of(first, second),
            logger()
        );
        lifecycle.drawable().setOpacity(drawable, 0.8F, drawable::write);
        lifecycle.drawable().awaitIdle();
        assertEquals(0.2F, drawable.getOpacity());
        assertEquals(List.of("first-before:0.8", "second-before:0.4"), events);

        events.clear();
        registry.unregister("plugin");
        lifecycle.drawable().setOpacity(drawable, 0.7F, drawable::write);
        lifecycle.drawable().awaitIdle();
        assertEquals(0.7F, drawable.getOpacity());
        assertEquals(List.of(), events);
    }


    @Test
    void staleOwnershipCannotDetachReplacementAndDuplicatesDoNotPerturbLiveGeneration() throws Exception {
        final EditorObjectLifecycleCoordinator lifecycle = new EditorObjectLifecycleCoordinator();
        final EditorObjectHookRegistry registry = new EditorObjectHookRegistry(lifecycle);
        final DisposableScope firstScope = new DisposableScope();
        final DisposableScope secondScope = new DisposableScope();
        final List<String> events = new ArrayList<>();
        final OrderedPlugin first = new OrderedPlugin("first", events);
        final OrderedPlugin second = new OrderedPlugin("second", events);
        final PluginDescriptor plugin = descriptor(
            "plugin", List.of(EditorObjectHookRegistry.OBSERVE_PERMISSION)
        );
        registry.register(plugin, List.of(first), logger(), firstScope);

        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class,
            () -> registry.register(plugin, List.of(second), logger(), secondScope)
        );
        final MutableDrawable drawable = new MutableDrawable();
        lifecycle.drawable().setOpacity(drawable, 0.8F, drawable::write);
        lifecycle.drawable().awaitIdle();
        assertEquals(List.of("first-after:0.8"), events);

        registry.unregister("plugin");
        registry.register(plugin, List.of(second), logger(), secondScope);
        firstScope.close();
        events.clear();
        lifecycle.drawable().setOpacity(drawable, 0.6F, drawable::write);
        lifecycle.drawable().awaitIdle();
        assertEquals(List.of("second-after:0.6"), events);

        secondScope.close();
        events.clear();
        lifecycle.drawable().setOpacity(drawable, 0.4F, drawable::write);
        lifecycle.drawable().awaitIdle();
        assertEquals(List.of(), events);
    }

    @Test
    void closedScopeRegistrationRollsBackWithoutCallbacks() throws Exception {
        final EditorObjectLifecycleCoordinator lifecycle = new EditorObjectLifecycleCoordinator();
        final EditorObjectHookRegistry registry = new EditorObjectHookRegistry(lifecycle);
        final DisposableScope closedScope = new DisposableScope();
        closedScope.close();
        final List<String> events = new ArrayList<>();
        final PluginDescriptor plugin = descriptor(
            "plugin", List.of(EditorObjectHookRegistry.OBSERVE_PERMISSION)
        );

        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class,
            () -> registry.register(
                plugin, List.of(new OrderedPlugin("closed", events)), logger(), closedScope
            )
        );
        final MutableDrawable drawable = new MutableDrawable();
        lifecycle.drawable().setOpacity(drawable, 0.5F, drawable::write);
        lifecycle.drawable().awaitIdle();
        assertEquals(List.of(), events);
    }


    @Test
    void registrationRacingScopeCloseAlwaysLeavesNoCallbacks() throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            final EditorObjectLifecycleCoordinator lifecycle = new EditorObjectLifecycleCoordinator();
            final EditorObjectHookRegistry registry = new EditorObjectHookRegistry(lifecycle);
            final DisposableScope scope = new DisposableScope();
            final List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
            final PluginDescriptor plugin = descriptor(
                "plugin", List.of(EditorObjectHookRegistry.OBSERVE_PERMISSION)
            );
            final AtomicReference<Throwable> registrationFailure = new AtomicReference<>();
            final Thread registration = new Thread(() -> {
                try {
                    registry.register(
                        plugin, List.of(new OrderedPlugin("race", events)), logger(), scope
                    );
                } catch (Throwable failure) {
                    registrationFailure.set(failure);
                }
            });
            final Thread close = new Thread(() -> {
                try {
                    scope.close();
                } catch (Throwable failure) {
                    throw new AssertionError(failure);
                }
            });

            registration.start();
            close.start();
            registration.join();
            close.join();
            registry.unregister("plugin");
            final MutableDrawable drawable = new MutableDrawable();
            lifecycle.drawable().setOpacity(drawable, 0.5F, drawable::write);
            lifecycle.drawable().awaitIdle();
            assertEquals(List.of(), events);
            final Throwable failure = registrationFailure.get();
            if (failure != null) {
                org.junit.jupiter.api.Assertions.assertInstanceOf(
                    IllegalStateException.class, failure
                );
            }
        }
    }

    private static final class OrderedPlugin implements TurboismPlugin, DrawableHooks, DeformerHooks {
        private final String name;
        private final List<String> events;
        private OrderedPlugin(String name, List<String> events) { this.name = name; this.events = events; }
        @Override public float beforeSetDrawableOpacity(Drawable drawable, float opacity) {
            events.add(name + "-before:" + opacity); return opacity * 0.5F;
        }
        @Override public void afterSetDrawableOpacity(Drawable drawable, float opacity) {
            events.add(name + "-after:" + opacity);
        }
    }

    private static PluginDescriptor descriptor(String id, List<String> permissionIds) {
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
            @Override public List<PermissionRef> permissions() {
                return permissionIds.stream().<PermissionRef>map(permissionId -> new PermissionRef() {
                    @Override public String id() { return permissionId; }
                    @Override public String scope() { return "plugin"; }
                    @Override public Optional<String> reason() { return Optional.of("test"); }
                }).toList();
            }
            @Override public List<String> capabilities() { return List.of(); }
            @Override public Environment environment() { return new Environment() {
                @Override public boolean requiresCubism() { return false; }
                @Override public String ui() { return "none"; }
            }; }
        };
    }

    private static PluginLogger logger() {
        return new PluginLogger() {
            @Override public void debug(String message) { }
            @Override public void info(String message) { }
            @Override public void warn(String message) { }
            @Override public void error(String message) { }
            @Override public void error(String message, Throwable throwable) { }
        };
    }

    private static final class MutableDrawable implements Drawable {
        private float opacity = 1.0F;
        private void write(float value) { opacity = value; }
        @Override public ArtMeshId id() { return new ArtMeshId("ArtMeshA"); }
        @Override public float getOpacity() { return opacity; }
        @Override public byte constantFlag() { return 0; }
        @Override public byte dynamicFlag() { return 0; }
        @Override public BlendMode blendMode() { return BlendMode.NORMAL; }
        @Override public int textureIndex() { return 0; }
        @Override public int drawOrder() { return 0; }
        @Override public int renderOrder() { return 0; }
        @Override public IntSequence masks() { return ints(); }
        @Override public FloatSequence vertexPositions() { return floats(); }
        @Override public FloatSequence vertexUvs() { return floats(); }
        @Override public IntSequence indices() { return ints(); }
        @Override public Color multiplyColor() { return new Color(1, 1, 1, 1); }
        @Override public Color screenColor() { return new Color(0, 0, 0, 1); }
        @Override public int parentPartIndex() { return -1; }
        @Override public int parentDeformerIndex() { return -1; }
        @Override public IntSequence parameters() { return ints(); }
    }

    private static IntSequence ints() { return new IntSequence() {
        @Override public int size() { return 0; }
        @Override public int get(int index) { throw new IndexOutOfBoundsException(index); }
    }; }
    private static FloatSequence floats() { return new FloatSequence() {
        @Override public int size() { return 0; }
        @Override public float get(int index) { throw new IndexOutOfBoundsException(index); }
    }; }
}
