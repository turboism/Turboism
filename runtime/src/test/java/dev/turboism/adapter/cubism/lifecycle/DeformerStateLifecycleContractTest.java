package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.core.event.EntrypointSubscriberCatalog;
import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.sidecar.SidecarResult;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.cubism.model.WarpDeformer;
import dev.turboism.sdk.cubism.model.WarpGrid;
import dev.turboism.sdk.event.SubscribeEvent;
import dev.turboism.sdk.event.cubism.DeformerLockEvent;
import dev.turboism.sdk.event.cubism.DeformerVisibilityEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeformerStateLifecycleContractTest {
    @Test
    void transformsAndPublishesDetachedVisibilityAndLockCompletion() throws Exception {
        final RuntimeScheduler scheduler = scheduler();
        try {
            final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
            final DeformerLifecycleCoordinator coordinator =
                new DeformerLifecycleCoordinator();
            coordinator.attachEventBroker(broker);
            final RuntimeEventBroker.Owner owner = broker.admit("deformer-state");
            final CountDownLatch completion = new CountDownLatch(4);
            final List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
            owner.registerAnnotated(new EntrypointSubscriberCatalog().inspect(List.of(
                new Subscriber(events, completion)
            )));
            owner.activate();
            final MutableWarp deformer = new MutableWarp();

            coordinator.setVisible(deformer, true, deformer::writeVisible);
            coordinator.setLocked(deformer, true, deformer::writeLocked);

            assertTrue(completion.await(1, TimeUnit.SECONDS));
            assertFalse(deformer.visible());
            assertFalse(deformer.locked());
            assertEquals(List.of(
                "visibility-on:true->false", "visibility-after:false",
                "lock-on:true->false", "lock-after:false"
            ), events);
        } finally {
            scheduler.shutdown();
        }
    }

    public static final class Subscriber {
        private final List<String> events;
        private final CountDownLatch completion;

        private Subscriber(final List<String> events, final CountDownLatch completion) {
            this.events = events;
            this.completion = completion;
        }

        @SubscribeEvent
        public void beforeVisibility(final DeformerVisibilityEvent.Before event) {
            event.setVisible(false);
        }

        @SubscribeEvent
        public void onVisibility(final DeformerVisibilityEvent.On event) {
            events.add("visibility-on:" + event.oldVisible() + "->" + event.newVisible());
            completion.countDown();
        }

        @SubscribeEvent
        public void afterVisibility(final DeformerVisibilityEvent.After event) {
            events.add("visibility-after:" + event.finalVisible());
            assertThrows(
                UnsupportedOperationException.class,
                () -> event.deformer().setVisible(true)
            );
            completion.countDown();
        }

        @SubscribeEvent
        public void beforeLock(final DeformerLockEvent.Before event) {
            event.setLocked(false);
        }

        @SubscribeEvent
        public void onLock(final DeformerLockEvent.On event) {
            events.add("lock-on:" + event.oldLocked() + "->" + event.newLocked());
            completion.countDown();
        }

        @SubscribeEvent
        public void afterLock(final DeformerLockEvent.After event) {
            events.add("lock-after:" + event.finalLocked());
            assertThrows(
                UnsupportedOperationException.class,
                () -> event.deformer().setLocked(true)
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

    private static final class MutableWarp implements WarpDeformer {
        private boolean visible = true;
        private boolean locked = true;
        private void writeVisible(final boolean value) { visible = value; }
        private void writeLocked(final boolean value) { locked = value; }
        @Override public DeformerId id() { return new DeformerId("WarpA"); }
        @Override public boolean visible() { return visible; }
        @Override public boolean locked() { return locked; }
        @Override public float getOpacity() { return 1.0F; }
        @Override public Color multiplyColor() { return new Color(1, 1, 1, 1); }
        @Override public Color screenColor() { return new Color(0, 0, 0, 1); }
        @Override public int parentPartIndex() { return -1; }
        @Override public int parentDeformerIndex() { return -1; }
        @Override public IntSequence parameters() { return ints(); }
        @Override public WarpGrid grid() { throw new UnsupportedOperationException(); }
        @Override public void replaceGrid(final WarpGrid grid) {
            throw new UnsupportedOperationException();
        }
    }

    private static IntSequence ints() {
        return new IntSequence() {
            @Override public int size() { return 0; }
            @Override public int get(final int index) { throw new IndexOutOfBoundsException(index); }
        };
    }
}
