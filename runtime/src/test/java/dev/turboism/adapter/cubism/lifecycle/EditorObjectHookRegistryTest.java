package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.sdk.cubism.hook.DeformerHooks;
import dev.turboism.sdk.cubism.event.CubismOperation;
import dev.turboism.sdk.cubism.event.CubismOperationEvent;
import dev.turboism.sdk.cubism.event.CubismOperationOrigin;
import dev.turboism.sdk.cubism.hook.DrawableHooks;
import dev.turboism.sdk.cubism.hook.SemanticOperationHooks;
import dev.turboism.core.event.EntrypointSubscriberCatalog;
import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.sidecar.SidecarResult;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.event.SubscribeEvent;
import dev.turboism.sdk.event.cubism.DrawableVisibilityEvent;
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
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    void registersSemanticHooksWithTheSamePermissionsAndUnloadBoundary() {
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
        lifecycle.semantic().runConfirmed(
            CubismOperation.OPEN_DOCUMENT,
            CubismOperationOrigin.HOST_UI,
            Optional.of("document"),
            () -> { }
        );
        lifecycle.semantic().awaitIdle();
        assertEquals(
            List.of(
                "first-semantic-after:OPEN_DOCUMENT",
                "second-semantic-after:OPEN_DOCUMENT"
            ),
            events
        );

        events.clear();
        registry.register(
            descriptor("plugin", List.of(EditorObjectHookRegistry.INTERCEPT_PERMISSION)),
            List.of(first, second),
            logger()
        );
        lifecycle.semantic().runConfirmed(
            CubismOperation.SAVE_DOCUMENT,
            CubismOperationOrigin.HOST_UI,
            Optional.of("document"),
            () -> { }
        );
        lifecycle.semantic().awaitIdle();
        assertEquals(
            List.of(
                "first-semantic-before:SAVE_DOCUMENT",
                "second-semantic-before:SAVE_DOCUMENT"
            ),
            events
        );

        events.clear();
        registry.unregister("plugin");
        lifecycle.semantic().runConfirmed(
            CubismOperation.CLOSE_DOCUMENT,
            CubismOperationOrigin.HOST_UI,
            Optional.of("document"),
            () -> { }
        );
        lifecycle.semantic().awaitIdle();
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

    @Test
    void previewAdaptersReplaceLegacyDrawableDeliveryAndDeduplicateAnnotatedStates()
        throws Exception {
        final RuntimeScheduler scheduler = scheduler();
        try {
            final EditorObjectLifecycleCoordinator lifecycle =
                new EditorObjectLifecycleCoordinator();
            final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
            lifecycle.attachEventBroker(broker);
            final EditorObjectHookRegistry registry = new EditorObjectHookRegistry(lifecycle);
            final RuntimeEventBroker.Owner owner = broker.admit("drawable-adapter");
            final DisposableScope scope = new DisposableScope();
            final List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
            final CountDownLatch completion = new CountDownLatch(2);
            final MixedDrawablePlugin entrypoint = new MixedDrawablePlugin(events, completion);
            owner.registerAnnotated(new EntrypointSubscriberCatalog().inspect(List.of(entrypoint)));
            registry.register(
                descriptor("drawable-adapter", List.of(
                    EditorObjectHookRegistry.INTERCEPT_PERMISSION,
                    EditorObjectHookRegistry.OBSERVE_PERMISSION
                )),
                List.of(entrypoint),
                logger(), scope, broker, owner.key()
            );
            owner.activate();
            final StatefulDrawable drawable = new StatefulDrawable();

            lifecycle.drawable().setVisible(drawable, true, drawable::writeVisible);

            org.junit.jupiter.api.Assertions.assertTrue(
                completion.await(1, TimeUnit.SECONDS)
            );
            assertEquals(false, drawable.visible());
            assertEquals(List.of("annotated-before", "legacy-after:false"), events);
            scope.close();
        } finally {
            scheduler.shutdown();
        }
    }

    public static final class MixedDrawablePlugin
        implements TurboismPlugin, DrawableHooks {
        private final List<String> events;
        private final CountDownLatch completion;

        private MixedDrawablePlugin(
            final List<String> events,
            final CountDownLatch completion
        ) {
            this.events = events;
            this.completion = completion;
        }

        @Override public boolean beforeSetDrawableVisible(
            final Drawable drawable,
            final boolean visible
        ) {
            events.add("legacy-before");
            return visible;
        }

        @SubscribeEvent
        public void beforeVisible(final DrawableVisibilityEvent.Before event) {
            events.add("annotated-before");
            event.setVisible(false);
            completion.countDown();
        }

        @Override public void afterSetDrawableVisible(
            final Drawable drawable,
            final boolean visible
        ) {
            events.add("legacy-after:" + visible);
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

    private static final class StatefulDrawable extends MutableDrawable {
        private boolean visible;
        private void writeVisible(final boolean value) { visible = value; }
        @Override public boolean visible() { return visible; }
    }

    private static final class OrderedPlugin implements TurboismPlugin, DrawableHooks, DeformerHooks, SemanticOperationHooks {
        private final String name;
        private final List<String> events;
        private OrderedPlugin(String name, List<String> events) { this.name = name; this.events = events; }
        @Override public float beforeSetDrawableOpacity(Drawable drawable, float opacity) {
            events.add(name + "-before:" + opacity); return opacity * 0.5F;
        }
        @Override public void afterSetDrawableOpacity(Drawable drawable, float opacity) {
            events.add(name + "-after:" + opacity);
        }
        @Override public void beforeCubismOperation(CubismOperationEvent event) {
            events.add(name + "-semantic-before:" + event.operation());
        }
        @Override public void afterCubismOperation(CubismOperationEvent event) {
            events.add(name + "-semantic-after:" + event.operation());
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

    private static class MutableDrawable implements Drawable {
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
