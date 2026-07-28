package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.sdk.cubism.hook.PartHooks;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Contract for the first Part-opacity lifecycle vertical slice. */
class PartOpacityLifecycleContractTest {

    @Test
    void chainsBeforeHooksAndInvokesTheNativeWriteWithTheEffectiveOpacity() {
        final List<String> calls = new ArrayList<>();
        final PartLifecycleCoordinator coordinator = new PartLifecycleCoordinator();
        coordinator.register(plugin("plugin-a", List.of(new PartHooks() {
            @Override
            public float beforeSetPartOpacity(final Part part, final float opacity) {
                calls.add("a:before:" + opacity);
                return opacity * 0.5F;
            }
        })));
        coordinator.register(plugin("plugin-b", List.of(new PartHooks() {
            @Override
            public float beforeSetPartOpacity(final Part part, final float opacity) {
                calls.add("b:before:" + opacity);
                return Math.min(opacity, 0.4F);
            }
        })));
        final MutablePart part = new MutablePart(0.0F);

        coordinator.setOpacity(part, 1.0F, opacity -> {
            calls.add("native:" + opacity);
            part.write(opacity);
        });
        coordinator.awaitIdle();

        assertEquals(0.4F, part.getOpacity());
        assertEquals(List.of("a:before:1.0", "b:before:0.5", "native:0.4"), calls);
    }

    @Test
    void publishesOnOnlyForObservableChangesAndAfterForEveryNormalCompletion() {
        final List<String> events = new ArrayList<>();
        final PartLifecycleCoordinator coordinator = new PartLifecycleCoordinator();
        coordinator.register(plugin("plugin-a", List.of(recordingHook(events))));
        final MutablePart part = new MutablePart(0.5F);

        coordinator.setOpacity(part, 0.5F, part::write);
        coordinator.awaitIdle();
        assertEquals(List.of("after:0.5"), events);

        events.clear();
        coordinator.setOpacity(part, 0.8F, part::write);
        coordinator.awaitIdle();
        assertEquals(List.of("on:0.5->0.8", "after:0.8"), events);
    }

    @Test
    void nativeFailureSuppressesOnAndAfter() {
        final List<String> events = new ArrayList<>();
        final PartLifecycleCoordinator coordinator = new PartLifecycleCoordinator();
        coordinator.register(plugin("plugin-a", List.of(recordingHook(events))));
        final MutablePart part = new MutablePart(0.5F);

        assertThrows(IllegalStateException.class, () -> coordinator.setOpacity(part, 0.8F, ignored -> {
            throw new IllegalStateException("native failed");
        }));
        coordinator.awaitIdle();

        assertEquals(0.5F, part.getOpacity());
        assertEquals(List.of(), events);
    }

    @Test
    void partNameLifecycleChainsBeforeAndPublishesChangedCompletion() {
        final List<String> events = new ArrayList<>();
        final PartLifecycleCoordinator coordinator = new PartLifecycleCoordinator();
        coordinator.register(plugin("plugin-a", List.of(new PartHooks() {
            @Override public String beforeSetPartName(final Part part, final String name) {
                events.add("before:" + name);
                return name + " A";
            }
            @Override public void onPartNameChanged(
                final Part part, final String oldName, final String newName
            ) {
                events.add("on:" + oldName + "->" + newName);
            }
            @Override public void afterSetPartName(final Part part, final String name) {
                events.add("after:" + name);
            }
        })));
        final MutablePart part = new MutablePart(0.5F);

        coordinator.setName(part, "Clip", part::writeName);
        coordinator.awaitIdle();

        assertEquals("Clip A", part.name());
        assertEquals(List.of(
            "before:Clip", "on:PartArmL->Clip A", "after:Clip A"
        ), events);
    }

    @Test
    void partNameNoChangePublishesAfterOnlyAndFailureOrRecursionPublishesNothing() {
        final List<String> events = new ArrayList<>();
        final PartLifecycleCoordinator coordinator = new PartLifecycleCoordinator();
        coordinator.register(plugin("plugin-a", List.of(new PartHooks() {
            @Override public void onPartNameChanged(
                final Part part, final String oldName, final String newName
            ) { events.add("on"); }
            @Override public void afterSetPartName(final Part part, final String name) {
                events.add("after:" + name);
            }
        })));
        final MutablePart part = new MutablePart(0.5F);

        coordinator.setName(part, "PartArmL", part::writeName);
        coordinator.awaitIdle();
        assertEquals(List.of("after:PartArmL"), events);

        events.clear();
        assertThrows(IllegalStateException.class, () -> coordinator.setName(
            part, "Broken", ignored -> { throw new IllegalStateException("native failed"); }
        ));
        assertEquals(List.of(), events);

        assertThrows(IllegalStateException.class, () -> coordinator.setName(
            part, "Recursive", name -> coordinator.setName(part, name, part::writeName)
        ));
        assertEquals("PartArmL", part.name());
        assertEquals(List.of(), events);
    }

    @Test
    void rejectsSameOperationRecursionAndDropsHooksAfterPluginDisable() {
        final List<String> events = new ArrayList<>();
        final PartLifecycleCoordinator coordinator = new PartLifecycleCoordinator();
        coordinator.register(plugin("plugin-a", List.of(recordingHook(events))));
        final MutablePart part = new MutablePart(0.0F);

        assertThrows(IllegalStateException.class, () -> coordinator.setOpacity(
            part,
            0.8F,
            opacity -> coordinator.setOpacity(part, opacity, part::write)
        ));
        assertEquals(0.0F, part.getOpacity());
        assertEquals(List.of(), events);

        coordinator.unregister("plugin-a");
        coordinator.setOpacity(part, 0.8F, part::write);
        coordinator.awaitIdle();
        assertEquals(0.8F, part.getOpacity());
        assertEquals(List.of(), events);
    }


    private static PartHooks recordingHook(final List<String> events) {
        return new PartHooks() {
            @Override
            public void onPartOpacityChanged(
                final Part part,
                final float oldOpacity,
                final float newOpacity
            ) {
                events.add("on:" + oldOpacity + "->" + newOpacity);
            }

            @Override
            public void afterSetPartOpacity(final Part part, final float opacity) {
                events.add("after:" + opacity);
            }
        };
    }

    private static PartLifecycleCoordinator.PluginHooks plugin(
        final String id,
        final List<? extends PartHooks> entrypoints
    ) {
        return new PartLifecycleCoordinator.PluginHooks(
            descriptor(id),
            entrypoints,
            logger()
        );
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
            @Override public I18n i18n() {
                return new I18n() {
                    @Override public String baseName() { return "messages"; }
                    @Override public List<String> locales() { return List.of(); }
                };
            }
            @Override public List<DependencyRef> dependencies() { return List.of(); }
            @Override public List<PermissionRef> permissions() { return List.of(); }
            @Override public List<String> capabilities() { return List.of(); }
            @Override public Environment environment() {
                return new Environment() {
                    @Override public boolean requiresCubism() { return false; }
                    @Override public String ui() { return "none"; }
                };
            }
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

    private static final class MutablePart implements Part {
        private float opacity;
        private String name = "PartArmL";

        private MutablePart(final float opacity) {
            this.opacity = opacity;
        }

        private void write(final float opacity) {
            this.opacity = opacity;
        }

        private void writeName(final String name) { this.name = name; }

        @Override public PartId id() { return new PartId("PartArmL"); }
        @Override public String name() { return name; }
        @Override public void setName(final String name) { writeName(name); }
        @Override public float getOpacity() { return opacity; }
        @Override public int parentIndex() { return -1; }
        @Override public void setOpacity(final float opacity) { write(opacity); }
    }
}
