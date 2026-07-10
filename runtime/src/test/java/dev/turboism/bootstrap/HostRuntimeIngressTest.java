package dev.turboism.bootstrap;

import dev.turboism.adapter.host.HostInstanceDescriptor;
import dev.turboism.adapter.host.HostSession;
import dev.turboism.adapter.host.HostSessionFailure;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostRuntimeIngressTest {

    @Test
    void productionIngressFailsClosedWhenPinnedTrustInputsAreNotReviewed() {
        HostRuntimeIngress ingress = new HostRuntimeIngress();

        assertEquals(HostSession.State.SAFE_MODE, ingress.state());
        assertSame(ingress.adapters(), ingress.adapters());
        assertEquals(HostSession.State.FAILED, ingress.publish(descriptor("unreviewed")));
        assertFalse(ingress.adapters().projectWorkspace().activeProject().isAvailable());
        assertEquals(
            HostSessionFailure.Code.CONNECTION_FAILED,
            ingress.lastFailure().orElseThrow().code()
        );

        assertEquals(HostSession.State.SAFE_MODE, ingress.clear());
        ingress.close();
        ingress.close();
        assertEquals(HostSession.State.CLOSED, ingress.state());
    }

    @Test
    void publishAfterCloseCannotReconnect() {
        HostRuntimeIngress ingress = new HostRuntimeIngress();
        ingress.close();
        ingress.publish(descriptor("late"));

        assertEquals(HostSession.State.CLOSED, ingress.state());
        assertFalse(ingress.adapters().projectWorkspace().activeProject().isAvailable());
        assertTrue(ingress.lastFailure().isEmpty());
    }

    @Test
    void closeRacingPublishClearsDescriptorAndRejectsLaterPublish() throws Exception {
        CountDownLatch sourceEntered = new CountDownLatch(1);
        CountDownLatch releaseSource = new CountDownLatch(1);
        HostRuntimeIngress ingress = new HostRuntimeIngress(source -> new HostSession(() -> {
            sourceEntered.countDown();
            await(releaseSource);
            return source.current();
        }));
        AtomicReference<HostSession.State> publishResult = new AtomicReference<>();
        AtomicReference<Throwable> publishFailure = new AtomicReference<>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        Thread publishing = new Thread(
            () -> captureFailure(
                () -> publishResult.set(ingress.publish(descriptor("racing"))),
                publishFailure
            ),
            "ingress-publish"
        );
        publishing.start();
        assertTrue(sourceEntered.await(5, TimeUnit.SECONDS));
        Thread closing = new Thread(
            () -> captureFailure(ingress::close, closeFailure),
            "ingress-close"
        );
        closing.start();
        final long closeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!ingress.isCloseRequestedForTest() && System.nanoTime() < closeDeadline) {
            Thread.sleep(1);
        }
        assertTrue(ingress.isCloseRequestedForTest());
        releaseSource.countDown();
        publishing.join(5_000);
        closing.join(5_000);

        assertFalse(publishing.isAlive());
        assertFalse(closing.isAlive());
        assertEquals(null, publishFailure.get());
        assertEquals(null, closeFailure.get());
        assertEquals(HostSession.State.CLOSED, publishResult.get());
        assertEquals(HostSession.State.CLOSED, ingress.state());
        assertFalse(ingress.hasCurrentDescriptorForTest());
        assertEquals(HostSession.State.CLOSED, ingress.publish(descriptor("late")));
        assertFalse(ingress.hasCurrentDescriptorForTest());
    }

    @Test
    void publicApiDoesNotExposeClosableHostSession() {
        assertFalse(java.util.Arrays.stream(HostRuntimeIngress.class.getMethods())
            .anyMatch(method -> method.getName().equals("hostSession")));
        assertTrue(dev.turboism.adapter.host.RuntimeHostAdapterAccess.class
            .isInstance(new HostRuntimeIngress().adapterAccess()));
    }

    @Test
    void lifecycleDoesNotHoldIngressMonitorAcrossSessionCallbacks() {
        AtomicReference<HostRuntimeIngress> ingressRef = new AtomicReference<>();
        HostRuntimeIngress ingress = new HostRuntimeIngress(
            source -> new HostSession(() -> {
                Thread callback = new Thread(() -> ingressRef.get().state());
                callback.start();
                try {
                    callback.join(2_000);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                if (callback.isAlive()) {
                    throw new IllegalStateException("ingress callback blocked");
                }
                return source.current();
            })
        );
        ingressRef.set(ingress);

        assertEquals(HostSession.State.FAILED, ingress.publish(descriptor("session-a")));
        assertEquals(HostSession.State.SAFE_MODE, ingress.clear());
    }

    private static void captureFailure(
        final Runnable action,
        final AtomicReference<Throwable> failure
    ) {
        try {
            action.run();
        } catch (Throwable throwable) {
            failure.set(throwable);
        }
    }

    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static HostInstanceDescriptor descriptor(final String sessionId) {
        return new HostInstanceDescriptor(
            sessionId,
            Path.of("records/reviewed.json"),
            Path.of("host/Live2D_Cubism.jar"),
            HostRuntimeIngressTest.class.getClassLoader()
        );
    }
}
