package dev.turboism.adapter.cubism.optimization.modelopen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class ParameterPaletteCoalescingTest {

    @AfterEach
    void resetBridge() {
        ParameterPaletteCoalesceBridge.resetForTest(null);
    }

    @Test
    void coalescesLoadAndPostLoadBurstIntoSingleAuthoritativeStructureRebuild() throws Exception {
        final List<Boolean> executed = new ArrayList<>();
        final CountDownLatch settled = new CountDownLatch(1);
        ParameterPaletteCoalesceBridge.resetForTest(force -> {
            synchronized (executed) {
                executed.add(force);
            }
            settled.countDown();
        });

        final Object fakeManager = new Object();
        ParameterPaletteCoalesceBridge.beginDocumentLoad();
        // Mix of value syncs and forced structure rebuilds during load must queue cleanly.
        for (int i = 0; i < 12; i++) {
            assertTrue(ParameterPaletteCoalesceBridge.tryInterceptUpdateParameter(fakeManager, i == 5));
        }
        ParameterPaletteCoalesceBridge.endDocumentLoad();

        // Simulate the 38x Update Parameter Structure storm right after load document end.
        for (int i = 0; i < 38; i++) {
            assertTrue(ParameterPaletteCoalesceBridge.tryInterceptUpdateParameter(fakeManager, true));
        }

        assertEquals(0, executed.size(), "Rebuilds must coalesce until settle window completes");
        assertTrue(settled.await(2L, TimeUnit.SECONDS), "Coalesced settle flush did not run");
        // Allow a short beat to prove no extra spurious rebuilds arrive.
        Thread.sleep(100L);
        assertEquals(List.of(Boolean.TRUE), executed);
    }

    @Test
    void promotesFalseToTrueInSameBatchAndExecutesInteractiveNonForceImmediately() throws Exception {
        final List<Boolean> executed = new ArrayList<>();
        final CountDownLatch settled = new CountDownLatch(1);
        ParameterPaletteCoalesceBridge.resetForTest(force -> {
            executed.add(force);
            settled.countDown();
        });

        final Object fakeManager = new Object();
        final Object fakeController = new Object();
        ParameterPaletteCoalesceBridge.beginDocumentLoad();
        assertTrue(ParameterPaletteCoalesceBridge.tryInterceptUpdateParameter(fakeManager, false));
        assertTrue(ParameterPaletteCoalesceBridge.tryDeferControllerUpdate(fakeController, false));
        assertTrue(ParameterPaletteCoalesceBridge.tryInterceptUpdateParameter(fakeManager, true));
        ParameterPaletteCoalesceBridge.endDocumentLoad();

        assertTrue(settled.await(2L, TimeUnit.SECONDS));
        // Interactive selection updates (force=false) outside load window must never be deferred.
        Thread.sleep(180L);
        assertFalse(ParameterPaletteCoalesceBridge.isCoalescingWindowActive());
        assertFalse(ParameterPaletteCoalesceBridge.tryInterceptUpdateParameter(fakeManager, false));
        assertFalse(ParameterPaletteCoalesceBridge.tryDeferControllerUpdate(fakeController, false));
        assertTrue(executed.contains(Boolean.TRUE));
    }

    @Test
    void coalescesBurstControllerCallsOnRealTargetInstance() {
        final AtomicInteger rebuildCalls = new AtomicInteger();
        final AtomicInteger selectionCalls = new AtomicInteger();
        final FakeController controller = new FakeController(rebuildCalls, selectionCalls);

        ParameterPaletteCoalesceBridge.resetForTest(null);
        ParameterPaletteCoalesceBridge.beginDocumentLoad();
        for (int i = 0; i < 38; i++) {
            assertTrue(ParameterPaletteCoalesceBridge.tryDeferControllerUpdate(controller, i == 10));
        }
        ParameterPaletteCoalesceBridge.endDocumentLoad();
        ParameterPaletteCoalesceBridge.flushNow();

        assertEquals(1, rebuildCalls.get());
        assertEquals(0, selectionCalls.get());
    }

    public static final class FakeController {
        private final AtomicInteger rebuildCalls;
        private final AtomicInteger selectionCalls;

        FakeController(final AtomicInteger rebuildCalls, final AtomicInteger selectionCalls) {
            this.rebuildCalls = rebuildCalls;
            this.selectionCalls = selectionCalls;
        }

        public void a(final boolean forceStructure) {
            if (forceStructure) {
                rebuildCalls.incrementAndGet();
            } else {
                selectionCalls.incrementAndGet();
            }
        }
    }
}
