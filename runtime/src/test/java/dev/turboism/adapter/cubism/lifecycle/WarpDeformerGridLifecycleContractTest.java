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
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.WarpDeformer;
import dev.turboism.sdk.cubism.model.WarpGrid;
import dev.turboism.sdk.event.SubscribeEvent;
import dev.turboism.sdk.event.cubism.WarpDeformerGridEvent;
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

class WarpDeformerGridLifecycleContractTest {
    @Test
    void transformsAndPublishesDetachedGridCompletion() throws Exception {
        final RuntimeScheduler scheduler = scheduler();
        try {
            final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
            final DeformerLifecycleCoordinator coordinator =
                new DeformerLifecycleCoordinator();
            coordinator.attachEventBroker(broker);
            final RuntimeEventBroker.Owner owner = broker.admit("warp-grid");
            final CountDownLatch completion = new CountDownLatch(2);
            final List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
            final WarpGrid replacement = grid(2.0F);
            owner.registerAnnotated(new EntrypointSubscriberCatalog().inspect(List.of(
                new Subscriber(events, completion, replacement)
            )));
            owner.activate();
            final MutableWarp deformer = new MutableWarp(grid(0.0F));

            coordinator.replaceGrid(deformer, grid(1.0F), deformer::writeGrid);

            assertTrue(completion.await(1, TimeUnit.SECONDS));
            assertEquals(replacement, deformer.grid());
            assertEquals(List.of("on", "after"), events);
        } finally {
            scheduler.shutdown();
        }
    }

    public static final class Subscriber {
        private final List<String> events;
        private final CountDownLatch completion;
        private final WarpGrid replacement;

        private Subscriber(
            final List<String> events,
            final CountDownLatch completion,
            final WarpGrid replacement
        ) {
            this.events = events;
            this.completion = completion;
            this.replacement = replacement;
        }

        @SubscribeEvent
        public void before(final WarpDeformerGridEvent.Before event) {
            event.setGrid(replacement);
        }

        @SubscribeEvent
        public void on(final WarpDeformerGridEvent.On event) {
            events.add("on");
            assertEquals(replacement, event.newGrid());
            completion.countDown();
        }

        @SubscribeEvent
        public void after(final WarpDeformerGridEvent.After event) {
            events.add("after");
            assertThrows(
                UnsupportedOperationException.class,
                () -> event.deformer().replaceGrid(replacement)
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

    private static WarpGrid grid(final float offset) {
        return new WarpGrid(1, 1, false, List.of(
            new Point2(offset, 0), new Point2(offset + 1, 0),
            new Point2(offset, 1), new Point2(offset + 1, 1)
        ));
    }

    private static final class MutableWarp implements WarpDeformer {
        private WarpGrid grid;
        private MutableWarp(final WarpGrid grid) { this.grid = grid; }
        private void writeGrid(final WarpGrid value) { grid = value; }
        @Override public DeformerId id() { return new DeformerId("WarpA"); }
        @Override public float getOpacity() { return 1.0F; }
        @Override public Color multiplyColor() { return new Color(1, 1, 1, 1); }
        @Override public Color screenColor() { return new Color(0, 0, 0, 1); }
        @Override public int parentPartIndex() { return -1; }
        @Override public int parentDeformerIndex() { return -1; }
        @Override public IntSequence parameters() { return ints(); }
        @Override public WarpGrid grid() { return grid; }
        @Override public void replaceGrid(final WarpGrid value) { writeGrid(value); }
    }

    private static IntSequence ints() {
        return new IntSequence() {
            @Override public int size() { return 0; }
            @Override public int get(final int index) { throw new IndexOutOfBoundsException(index); }
        };
    }
}
