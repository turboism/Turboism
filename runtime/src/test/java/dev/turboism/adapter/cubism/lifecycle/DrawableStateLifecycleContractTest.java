package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.core.event.EntrypointSubscriberCatalog;
import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.sidecar.SidecarResult;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.FloatSequence;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.event.SubscribeEvent;
import dev.turboism.sdk.event.cubism.DrawableGeometryEvent;
import dev.turboism.sdk.event.cubism.DrawableLockEvent;
import dev.turboism.sdk.event.cubism.DrawableVisibilityEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DrawableStateLifecycleContractTest {
    @Test
    void transformsAndPublishesVisibilityLockAndGeometry() throws Exception {
        final RuntimeScheduler scheduler = scheduler();
        try {
            final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
            final DrawableLifecycleCoordinator coordinator = new DrawableLifecycleCoordinator();
            coordinator.attachEventBroker(broker);
            final CountDownLatch completion = new CountDownLatch(6);
            final List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
            final ArtMeshGeometry replacement = geometry(2.0F);
            final RuntimeEventBroker.Owner owner = broker.admit("drawable-state-events");
            owner.registerAnnotated(new EntrypointSubscriberCatalog().inspect(List.of(
                new StateSubscriber(events, completion, replacement)
            )));
            owner.activate();
            final MutableDrawable drawable = new MutableDrawable(geometry(1.0F));

            coordinator.setVisible(drawable, true, drawable::writeVisible);
            coordinator.setLocked(drawable, true, drawable::writeLocked);
            coordinator.replaceGeometry(drawable, geometry(3.0F), drawable::writeGeometry);

            assertTrue(completion.await(1, TimeUnit.SECONDS));
            assertEquals(false, drawable.visible());
            assertEquals(false, drawable.locked());
            assertEquals(replacement, drawable.geometry());
            assertEquals(List.of(
                "visibility-on:true->false", "visibility-after:false",
                "lock-on:true->false", "lock-after:false",
                "geometry-on", "geometry-after"
            ), events);
        } finally {
            scheduler.shutdown();
        }
    }

    public static final class StateSubscriber {
        private final List<String> events;
        private final CountDownLatch completion;
        private final ArtMeshGeometry replacement;

        private StateSubscriber(
            final List<String> events,
            final CountDownLatch completion,
            final ArtMeshGeometry replacement
        ) {
            this.events = events;
            this.completion = completion;
            this.replacement = replacement;
        }

        @SubscribeEvent
        public void beforeVisibility(final DrawableVisibilityEvent.Before event) {
            assertTrue(event.requestedVisible());
            event.setVisible(false);
        }

        @SubscribeEvent
        public void onVisibility(final DrawableVisibilityEvent.On event) {
            events.add("visibility-on:" + event.oldVisible() + "->" + event.newVisible());
            completion.countDown();
        }

        @SubscribeEvent
        public void afterVisibility(final DrawableVisibilityEvent.After event) {
            events.add("visibility-after:" + event.finalVisible());
            assertThrows(
                UnsupportedOperationException.class,
                () -> event.drawable().setVisible(true)
            );
            completion.countDown();
        }

        @SubscribeEvent
        public void beforeLock(final DrawableLockEvent.Before event) {
            assertTrue(event.requestedLocked());
            event.setLocked(false);
        }

        @SubscribeEvent
        public void onLock(final DrawableLockEvent.On event) {
            events.add("lock-on:" + event.oldLocked() + "->" + event.newLocked());
            completion.countDown();
        }

        @SubscribeEvent
        public void afterLock(final DrawableLockEvent.After event) {
            events.add("lock-after:" + event.finalLocked());
            assertThrows(
                UnsupportedOperationException.class,
                () -> event.drawable().setLocked(true)
            );
            completion.countDown();
        }

        @SubscribeEvent
        public void beforeGeometry(final DrawableGeometryEvent.Before event) {
            event.setGeometry(replacement);
        }

        @SubscribeEvent
        public void onGeometry(final DrawableGeometryEvent.On event) {
            events.add("geometry-on");
            assertEquals(replacement, event.newGeometry());
            completion.countDown();
        }

        @SubscribeEvent
        public void afterGeometry(final DrawableGeometryEvent.After event) {
            events.add("geometry-after");
            assertEquals(replacement, event.finalGeometry());
            assertThrows(
                UnsupportedOperationException.class,
                () -> event.drawable().replaceGeometry(replacement)
            );
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

    private static ArtMeshGeometry geometry(final float value) {
        return new ArtMeshGeometry(
            List.of(new Point2(value, value), new Point2(value + 1.0F, value)),
            List.of(new Point2(0.0F, 0.0F), new Point2(1.0F, 0.0F)),
            List.of()
        );
    }

    private static final class MutableDrawable implements Drawable {
        private boolean visible = true;
        private boolean locked = true;
        private ArtMeshGeometry geometry;

        private MutableDrawable(final ArtMeshGeometry geometry) {
            this.geometry = geometry;
        }

        private void writeVisible(final boolean value) { visible = value; }
        private void writeLocked(final boolean value) { locked = value; }
        private void writeGeometry(final ArtMeshGeometry value) { geometry = value; }

        @Override public ArtMeshId id() { return new ArtMeshId("ArtMeshA"); }
        @Override public boolean visible() { return visible; }
        @Override public boolean locked() { return locked; }
        @Override public ArtMeshGeometry geometry() { return geometry; }
        @Override public byte constantFlag() { return 0; }
        @Override public byte dynamicFlag() { return 0; }
        @Override public BlendMode blendMode() { return BlendMode.NORMAL; }
        @Override public int textureIndex() { return 0; }
        @Override public int drawOrder() { return 0; }
        @Override public int renderOrder() { return 0; }
        @Override public float getOpacity() { return 1.0F; }
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

    private static IntSequence ints() {
        return new IntSequence() {
            @Override public int size() { return 0; }
            @Override public int get(final int index) { throw new IndexOutOfBoundsException(index); }
        };
    }

    private static FloatSequence floats() {
        return new FloatSequence() {
            @Override public int size() { return 0; }
            @Override public float get(final int index) { throw new IndexOutOfBoundsException(index); }
        };
    }
}
