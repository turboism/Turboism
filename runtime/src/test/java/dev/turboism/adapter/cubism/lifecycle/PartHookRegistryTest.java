package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PartHookRegistryTest {

    @Test
    void discoversOrderedCubismEntrypointsAndRemovesThemOnPluginClose() {
        final List<String> events = new ArrayList<>();
        final PartLifecycleCoordinator coordinator = new PartLifecycleCoordinator();
        final PartHookRegistry registry = new PartHookRegistry(coordinator);
        final PluginDescriptor descriptor = descriptor("plugin-a");
        final List<CubismPlugin> entrypoints = List.of(
            new NamedPlugin("first", events),
            new NamedPlugin("second", events)
        );

        registry.register(descriptor, entrypoints, logger());
        final MutablePart part = new MutablePart();
        coordinator.setOpacity(part, 0.5F, value -> part.opacity = value);
        coordinator.awaitIdle();
        assertEquals(List.of(
            "first:on", "first:after", "second:on", "second:after"
        ), events);

        events.clear();
        registry.unregister(descriptor.id());
        coordinator.setOpacity(part, 0.25F, value -> part.opacity = value);
        coordinator.awaitIdle();
        assertEquals(List.of(), events);
    }

    @Test
    void derivesHookPermissionsFromThePluginDescriptor() {
        final List<String> events = new ArrayList<>();
        final PartLifecycleCoordinator coordinator = new PartLifecycleCoordinator();
        final PartHookRegistry registry = new PartHookRegistry(coordinator);
        registry.register(
            descriptor(
                "observe-only",
                List.of(permission(PartHookRegistry.OBSERVE_PERMISSION))
            ),
            List.of(new CubismPlugin() {
                @Override public float beforeSetPartOpacity(
                    final Part part,
                    final float opacity
                ) { return opacity * 0.5F; }
                @Override public void afterSetPartOpacity(
                    final Part part,
                    final float opacity
                ) { events.add("after:" + opacity); }
            }),
            logger()
        );
        final MutablePart part = new MutablePart();

        coordinator.setOpacity(part, 0.5F, value -> part.opacity = value);
        coordinator.awaitIdle();

        assertEquals(0.5F, part.opacity);
        assertEquals(List.of("after:0.5"), events);
    }

    private record NamedPlugin(String name, List<String> events) implements CubismPlugin {
        @Override public void onPartOpacityChanged(
            final Part part, final float oldOpacity, final float newOpacity
        ) { events.add(name + ":on"); }
        @Override public void afterSetPartOpacity(
            final Part part, final float opacity
        ) { events.add(name + ":after"); }
    }

    private static final class MutablePart implements Part {
        private float opacity = 1.0F;
        @Override public PartId id() { return new PartId("PartA"); }
        @Override public void setName(final String name) { }
        @Override public float getOpacity() { return opacity; }
        @Override public int parentIndex() { return -1; }
        @Override public void setOpacity(final float opacity) { this.opacity = opacity; }
    }

    private static PluginDescriptor descriptor(final String id) {
        return descriptor(id, List.of(
            permission(PartHookRegistry.INTERCEPT_PERMISSION),
            permission(PartHookRegistry.OBSERVE_PERMISSION)
        ));
    }

    private static PluginDescriptor descriptor(
        final String id,
        final List<PluginDescriptor.PermissionRef> permissions
    ) {
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
            @Override public List<PermissionRef> permissions() { return permissions; }
            @Override public List<String> capabilities() { return List.of(); }
            @Override public Environment environment() { return new Environment() {
                @Override public boolean requiresCubism() { return false; }
                @Override public String ui() { return "none"; }
            }; }
        };
    }

    private static PluginDescriptor.PermissionRef permission(final String id) {
        return new PluginDescriptor.PermissionRef() {
            @Override public String id() { return id; }
            @Override public String scope() { return "model"; }
            @Override public Optional<String> reason() { return Optional.of("test"); }
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
