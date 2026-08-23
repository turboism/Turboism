package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.core.event.EntrypointSubscriberCatalog;
import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.sidecar.SidecarResult;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.cubism.hook.PartHooks;
import dev.turboism.sdk.event.SubscribeEvent;
import dev.turboism.sdk.event.cubism.PartNameEvent;
import dev.turboism.sdk.event.cubism.PartOpacityEvent;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
    void annotatedPartEventsTransformAndPublishDetachedCompletion() throws Exception {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final PartLifecycleCoordinator coordinator = new PartLifecycleCoordinator();
        coordinator.attachEventBroker(broker);
        final RuntimeEventBroker.Owner owner = broker.admit("part-events");
        final CountDownLatch completion = new CountDownLatch(4);
        final List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        owner.registerAnnotated(new EntrypointSubscriberCatalog().inspect(List.of(
            new PartEventSubscriber(events, completion)
        )));
        owner.activate();
        final MutablePart part = new MutablePart(0.5F);

        coordinator.setOpacity(part, 1.0F, part::write);
        coordinator.setName(part, "Clip", part::writeName);

        org.junit.jupiter.api.Assertions.assertTrue(completion.await(1, TimeUnit.SECONDS));
        assertEquals(0.75F, part.getOpacity());
        assertEquals("Clip A", part.name());
        assertEquals(List.of(
            "opacity:on:0.5->0.75",
            "opacity:after:0.75",
            "name:on:PartArmL->Clip A",
            "name:after:Clip A"
        ), events);
        scheduler.shutdown();
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


    public static final class PartEventSubscriber {
        private final List<String> events;
        private final CountDownLatch completion;

        private PartEventSubscriber(
            final List<String> events,
            final CountDownLatch completion
        ) {
            this.events = events;
            this.completion = completion;
        }

        @SubscribeEvent
        public void beforeOpacity(final PartOpacityEvent.Before event) {
            event.setOpacity(event.opacity() * 0.75F);
        }

        @SubscribeEvent
        public void onOpacity(final PartOpacityEvent.On event) {
            events.add("opacity:on:" + event.oldOpacity() + "->" + event.newOpacity());
            completion.countDown();
        }

        @SubscribeEvent
        public void afterOpacity(final PartOpacityEvent.After event) {
            events.add("opacity:after:" + event.finalOpacity());
            assertThrows(UnsupportedOperationException.class, () -> event.part().setOpacity(1.0F));
            completion.countDown();
        }

        @SubscribeEvent
        public void beforeName(final PartNameEvent.Before event) {
            event.setName(event.name() + " A");
        }

        @SubscribeEvent
        public void onName(final PartNameEvent.On event) {
            events.add("name:on:" + event.oldName() + "->" + event.newName());
            completion.countDown();
        }

        @SubscribeEvent
        public void afterName(final PartNameEvent.After event) {
            events.add("name:after:" + event.finalName());
            assertThrows(UnsupportedOperationException.class, () -> event.part().setName("Root"));
            completion.countDown();
        }
    }

    private static RuntimeScheduler scheduler() {
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 8, ignored -> { }, Clock.systemUTC()),
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
