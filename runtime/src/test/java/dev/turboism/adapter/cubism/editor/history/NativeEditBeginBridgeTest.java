package dev.turboism.adapter.cubism.editor.history;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeEditBeginBridgeTest {

    private final List<Optional<String>> starts = new ArrayList<>();
    private final AtomicInteger drainRequests = new AtomicInteger();

    @AfterEach
    void resetBridge() {
        NativeEditBeginBridge.reset();
    }

    private void bind() {
        NativeEditBeginBridge.bind(starts::add, drainRequests::incrementAndGet);
    }

    @Test
    void anEditThatFallsThroughToTheBaseImplementationReportsOneStart() {
        bind();

        // The modeling override enters first and, with no form animation active, calls its base
        // implementation, which is instrumented too. Only the outermost entry is an edit start.
        NativeEditBeginBridge.ingress().accept("Add Part");
        NativeEditBeginBridge.ingress().accept("Add Part");

        assertEquals(1, drainRequests.get());
        assertEquals(0, starts.size(), "nothing is published from the host entry");
        assertTrue(NativeEditBeginBridge.hasPending());

        NativeEditBeginBridge.drain(starts::add);

        assertEquals(List.of(Optional.of("Add Part")), starts);
        assertEquals(2, NativeEditBeginBridge.notificationCount(), "both entries were observed");
        assertEquals(1, NativeEditBeginBridge.nestedEntryCount(), "only the inner one was nested");
    }

    @Test
    void theAnimationBranchAndASecondEditBothReportTheirOwnStart() {
        bind();

        // The animation branch returns without reaching the base, so the frame is released by the
        // drain rather than by a nested entry.
        NativeEditBeginBridge.ingress().accept("Animation Edit");
        NativeEditBeginBridge.drain(starts::add);
        NativeEditBeginBridge.ingress().accept("Rename part");
        NativeEditBeginBridge.drain(starts::add);

        assertEquals(List.of(Optional.of("Animation Edit"), Optional.of("Rename part")), starts);
        assertEquals(0, NativeEditBeginBridge.nestedEntryCount());
        assertEquals(2, drainRequests.get());
    }

    @Test
    void anAbandonedEditReleasesItsFrameOnTheDrain() {
        bind();

        // The host started an edit and never committed it. The next edit must not look nested.
        NativeEditBeginBridge.ingress().accept("Abandoned");
        NativeEditBeginBridge.drain(starts::add);
        NativeEditBeginBridge.ingress().accept("Second");

        assertTrue(NativeEditBeginBridge.hasPending(), "the second edit must not be suppressed");
        assertEquals(0, NativeEditBeginBridge.nestedEntryCount());
    }

    @Test
    void aBlankOrMissingNameIsStillAStartWithoutALabel() {
        bind();

        NativeEditBeginBridge.ingress().accept("   ");
        NativeEditBeginBridge.drain(starts::add);
        NativeEditBeginBridge.ingress().accept(null);
        NativeEditBeginBridge.drain(starts::add);

        assertEquals(List.of(Optional.empty(), Optional.empty()), starts);
    }

    @Test
    void aLongNativeNameIsTruncatedInsteadOfRejected() {
        bind();
        final String longName = "x".repeat(NativeEditBeginBridge.MAX_LABEL_LENGTH + 40);

        NativeEditBeginBridge.ingress().accept(longName);
        NativeEditBeginBridge.drain(starts::add);

        assertEquals(NativeEditBeginBridge.MAX_LABEL_LENGTH, starts.get(0).orElseThrow().length());
    }

    @Test
    void anUnboundOrClosedBridgeCountsInsteadOfThrowing() {
        // No session attached.
        NativeEditBeginBridge.ingress().accept("Add Part");
        assertEquals(1, NativeEditBeginBridge.unboundCount());
        assertFalse(NativeEditBeginBridge.hasPending());

        bind();
        NativeEditBeginBridge.close();
        NativeEditBeginBridge.ingress().accept("Add Part");

        assertEquals(1, NativeEditBeginBridge.unboundCount(), "a closed bridge is not unbound");
        assertFalse(NativeEditBeginBridge.hasPending(), "a closed bridge observes nothing");
    }

    @Test
    void aFailingDrainRequestReleasesTheFrameAndKeepsTheStartQueued() {
        NativeEditBeginBridge.bind(starts::add, () -> {
            throw new IllegalStateException("event thread refused");
        });

        NativeEditBeginBridge.ingress().accept("Add Part");
        NativeEditBeginBridge.ingress().accept("Second Edit");

        assertEquals(2, NativeEditBeginBridge.failureCount());
        assertEquals(0, NativeEditBeginBridge.nestedEntryCount(), "the frame was released");
        assertEquals(0, starts.size(), "nothing was published by the failed request");

        // The starts stay queued, so a later working drain publishes them instead of losing them.
        NativeEditBeginBridge.drain(starts::add);
        assertEquals(
            List.of(Optional.of("Add Part"), Optional.of("Second Edit")),
            starts
        );
    }

    @Test
    void aFailingSinkCostsOnlyThatStart() {
        bind();

        NativeEditBeginBridge.ingress().accept("Add Part");
        NativeEditBeginBridge.drain(label -> {
            throw new AssertionError("sink failure");
        });

        assertEquals(1, NativeEditBeginBridge.failureCount());
        assertEquals(1, NativeEditBeginBridge.drainedCount());

        // A failing drain must still leave the queue empty so the start cannot be replayed forever.
        assertFalse(NativeEditBeginBridge.hasPending());

        // And the frame it released must not suppress the next edit.
        NativeEditBeginBridge.ingress().accept("Second Edit");
        NativeEditBeginBridge.drain(starts::add);
        assertEquals(List.of(Optional.of("Second Edit")), starts);
    }

    @Test
    void twoHostThreadsEachQueueTheirOwnStart() throws Exception {
        bind();

        // The frame is per thread, so a second host thread can legitimately start an edit while the
        // first still holds its frame. Both starts must survive, in the order they were recorded.
        final Thread other = new Thread(
            () -> NativeEditBeginBridge.ingress().accept("Other Thread"),
            "other-host-thread"
        );
        NativeEditBeginBridge.ingress().accept("Add Part");
        other.start();
        other.join();

        assertEquals(2, drainRequests.get(), "each host thread asks for its own drain");
        assertTrue(NativeEditBeginBridge.hasPending());
        assertEquals(0, NativeEditBeginBridge.nestedEntryCount());

        NativeEditBeginBridge.drain(starts::add);

        assertEquals(2, starts.size());
        assertTrue(starts.contains(Optional.of("Add Part")));
        assertTrue(starts.contains(Optional.of("Other Thread")));
    }
}
