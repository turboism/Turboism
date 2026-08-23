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
import dev.turboism.sdk.cubism.model.RotationDeformer;
import dev.turboism.sdk.cubism.model.RotationDeformerForm;
import dev.turboism.sdk.event.SubscribeEvent;
import dev.turboism.sdk.event.cubism.RotationDeformerBaseAngleEvent;
import dev.turboism.sdk.event.cubism.RotationDeformerFormEvent;
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

class RotationDeformerLifecycleContractTest {
    @Test
    void transformsAndPublishesDetachedAngleAndFormCompletion() throws Exception {
        final RuntimeScheduler scheduler = scheduler();
        try {
            final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
            final DeformerLifecycleCoordinator coordinator =
                new DeformerLifecycleCoordinator();
            coordinator.attachEventBroker(broker);
            final RuntimeEventBroker.Owner owner = broker.admit("rotation-events");
            final CountDownLatch completion = new CountDownLatch(4);
            final List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
            final RotationDeformerForm replacement = form(20.0F);
            owner.registerAnnotated(new EntrypointSubscriberCatalog().inspect(List.of(
                new Subscriber(events, completion, replacement)
            )));
            owner.activate();
            final MutableRotation deformer = new MutableRotation();

            coordinator.setBaseAngle(deformer, 10.0F, deformer::writeAngle);
            coordinator.replaceForm(deformer, form(5.0F), deformer::writeForm);

            assertTrue(completion.await(1, TimeUnit.SECONDS));
            assertEquals(15.0F, deformer.baseAngle());
            assertEquals(replacement, deformer.form());
            assertEquals(List.of("angle-on", "angle-after", "form-on", "form-after"), events);
        } finally {
            scheduler.shutdown();
        }
    }

    public static final class Subscriber {
        private final List<String> events;
        private final CountDownLatch completion;
        private final RotationDeformerForm replacement;

        private Subscriber(
            final List<String> events,
            final CountDownLatch completion,
            final RotationDeformerForm replacement
        ) {
            this.events = events;
            this.completion = completion;
            this.replacement = replacement;
        }

        @SubscribeEvent
        public void beforeAngle(final RotationDeformerBaseAngleEvent.Before event) {
            event.setAngle(15.0F);
        }

        @SubscribeEvent
        public void onAngle(final RotationDeformerBaseAngleEvent.On event) {
            events.add("angle-on");
            completion.countDown();
        }

        @SubscribeEvent
        public void afterAngle(final RotationDeformerBaseAngleEvent.After event) {
            events.add("angle-after");
            assertThrows(
                UnsupportedOperationException.class,
                () -> event.deformer().setBaseAngle(30.0F)
            );
            completion.countDown();
        }

        @SubscribeEvent
        public void beforeForm(final RotationDeformerFormEvent.Before event) {
            event.setForm(replacement);
        }

        @SubscribeEvent
        public void onForm(final RotationDeformerFormEvent.On event) {
            events.add("form-on");
            completion.countDown();
        }

        @SubscribeEvent
        public void afterForm(final RotationDeformerFormEvent.After event) {
            events.add("form-after");
            assertThrows(
                UnsupportedOperationException.class,
                () -> event.deformer().replaceForm(replacement)
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

    private static RotationDeformerForm form(final float angle) {
        return new RotationDeformerForm(angle, 0, 0, 1, false, false);
    }

    private static final class MutableRotation implements RotationDeformer {
        private float angle;
        private RotationDeformerForm form = RotationDeformerLifecycleContractTest.form(0.0F);
        private void writeAngle(final float value) { angle = value; }
        private void writeForm(final RotationDeformerForm value) { form = value; }
        @Override public DeformerId id() { return new DeformerId("RotationA"); }
        @Override public float getOpacity() { return 1.0F; }
        @Override public Color multiplyColor() { return new Color(1, 1, 1, 1); }
        @Override public Color screenColor() { return new Color(0, 0, 0, 1); }
        @Override public int parentPartIndex() { return -1; }
        @Override public int parentDeformerIndex() { return -1; }
        @Override public IntSequence parameters() { return ints(); }
        @Override public float baseAngle() { return angle; }
        @Override public void setBaseAngle(final float value) { writeAngle(value); }
        @Override public RotationDeformerForm form() { return form; }
        @Override public void replaceForm(final RotationDeformerForm value) { writeForm(value); }
    }

    private static IntSequence ints() {
        return new IntSequence() {
            @Override public int size() { return 0; }
            @Override public int get(final int index) { throw new IndexOutOfBoundsException(index); }
        };
    }
}
