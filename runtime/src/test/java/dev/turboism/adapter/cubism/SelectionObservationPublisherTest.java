package dev.turboism.adapter.cubism;

import dev.turboism.adapter.host.HostSessionSnapshotSource;
import dev.turboism.adapter.ui.SafeModeDiagnostic;
import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.hostread.SharedAsyncHostReadLane;
import dev.turboism.sdk.cubism.DocumentKind;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;
import dev.turboism.sdk.cubism.event.SelectionChangedEvent;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.service.query.SelectionSummary;
import dev.turboism.sdk.hostread.ProjectWorkspaceSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bounded session-driven selection observation: subscriber demand starts a fixed-interval
 * poll whose host reads run on the shared host-read lane, never on the scheduler timer.
 *
 * <p>These tests drive the observer machinery over mutable fixtures; per the production
 * source review they prove plumbing only — {@link HostSessionSnapshotSource#selection()}
 * is currently a constant empty read, so live native object-selection ids are not covered
 * here (documented limitation). The real composition path is exercised through
 * {@link HostSessionSnapshotSource} over a mutable {@link ProjectWorkspaceAdapter} in
 * {@link #sessionSourceObservesDocumentCloseToEmpty()}.</p>
 */
class SelectionObservationPublisherTest {

    private static final Clock FIXED_CLOCK =
        Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC);
    private static final Duration FAST = Duration.ofMillis(10);

    private RuntimeScheduler scheduler;
    private SharedAsyncHostReadLane lane;
    private RuntimeEventBroker broker;
    private SelectionObservationPublisher publisher;

    @AfterEach
    void teardown() {
        if (publisher != null) {
            publisher.close();
        }
        if (lane != null) {
            lane.close();
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Test
    void subscribingAloneStartsObservationAndPublishesTransition() throws Exception {
        final MutableHostSource source = new MutableHostSource();
        harness(source);
        final List<SelectionChangedEvent> events = new ArrayList<>();
        final CountDownLatch delivered = new CountDownLatch(1);
        subscribe("plugin.alpha", events, delivered);

        // Wait for the silent initial baseline before mutating — otherwise the
        // first observation may already see the new state and stay silent.
        awaitBaselineCommit();
        source.replaceSelection(List.of("mesh-face"));

        assertTrue(delivered.await(5, TimeUnit.SECONDS));
        assertTrue(
            events.get(0).currentSelection().selectedModelObjectIds()
                .contains(new ModelObjectId("mesh-face"))
        );
    }

    @Test
    void multiplePluginSubscribersEachObserveTheTransition() throws Exception {
        final MutableHostSource source = new MutableHostSource();
        harness(source);
        final List<SelectionChangedEvent> first = new ArrayList<>();
        final List<SelectionChangedEvent> second = new ArrayList<>();
        final CountDownLatch delivered = new CountDownLatch(2);
        subscribe("plugin.alpha", first, delivered);
        subscribe("plugin.beta", second, delivered);

        awaitBaselineCommit();
        source.replaceSelection(List.of("param-angle-x"));

        assertTrue(delivered.await(5, TimeUnit.SECONDS));
        assertEquals(1, first.size());
        assertEquals(1, second.size());
        assertEquals(
            first.get(0).currentSelection(),
            second.get(0).currentSelection()
        );
    }

    @Test
    void noSubscriptionMeansNoHostReads() throws Exception {
        final MutableHostSource source = new MutableHostSource();
        harness(source);

        Thread.sleep(60);

        assertEquals(0, source.reads.get(), "idle observer must not read the host");
    }

    @Test
    void queryAndSamplerCommitsDeduplicateOnSharedBaseline() {
        // Query and sampler paths both commit through SelectionObservation.commit
        // on the same baseline — identical host state never emits twice.
        final AtomicReference<SelectionObservation> baseline = new AtomicReference<>();
        final List<String> transitions = new ArrayList<>();
        final MutableHostSource source = new MutableHostSource();
        final SelectionSummary stateA = observedIdentity(List.of("a"));
        final SelectionSummary stateB = observedIdentity(List.of("b"));

        // Initial silent baseline (as the first query/observation establishes it).
        assertFalse(SelectionObservation.commit(
            baseline, new SelectionObservation(source, 1, stateA), record(transitions)
        ));
        // Sampler sees the same state at a newer revision: no duplicate event.
        assertFalse(SelectionObservation.commit(
            baseline, new SelectionObservation(source, 2, stateA), record(transitions)
        ));
        // Real transition publishes exactly once.
        assertTrue(SelectionObservation.commit(
            baseline, new SelectionObservation(source, 3, stateB), record(transitions)
        ));
        // Query path observes the identical state: still no duplicate.
        assertFalse(SelectionObservation.commit(
            baseline, new SelectionObservation(source, 4, stateB), record(transitions)
        ));
        assertEquals(List.of("A>B"), transitions);
    }

    @Test
    void equalSummaryCommitAdvancesSameSourceWatermark() {
        // Regression for the reviewed defect: baseline (rev10,A); observe (rev20,A)
        // must raise the watermark; a late (rev15,B) completion must be discarded.
        final AtomicReference<SelectionObservation> baseline = new AtomicReference<>();
        final List<String> transitions = new ArrayList<>();
        final MutableHostSource source = new MutableHostSource();
        final SelectionSummary stateA = observedIdentity(List.of("a"));
        final SelectionSummary stateB = observedIdentity(List.of("b"));

        assertFalse(SelectionObservation.commit(
            baseline, new SelectionObservation(source, 10, stateA), record(transitions)
        ));
        assertFalse(SelectionObservation.commit(
            baseline, new SelectionObservation(source, 20, stateA), record(transitions)
        ));
        assertFalse(
            SelectionObservation.commit(
                baseline, new SelectionObservation(source, 15, stateB), record(transitions)
            ),
            "stale same-source read must not regress the newer baseline"
        );
        assertEquals(stateA, baseline.get().summary());
        assertEquals(20L, baseline.get().sourceVersion());
        assertTrue(transitions.isEmpty());
    }

    @Test
    void commitsPublishInCommitOrderUnderContention() throws Exception {
        // Regression for reversed transition delivery: serialize commit+enqueue.
        final AtomicReference<SelectionObservation> baseline = new AtomicReference<>();
        final List<String> transitions = new ArrayList<>();
        final MutableHostSource source = new MutableHostSource();
        final SelectionSummary stateA = observedIdentity(List.of("a"));
        final SelectionSummary stateB = observedIdentity(List.of("b"));
        final SelectionSummary stateC = observedIdentity(List.of("c"));

        // Establish the baseline silently.
        SelectionObservation.commit(
            baseline, new SelectionObservation(source, 1, stateA), record(transitions)
        );

        final CountDownLatch firstPublishEntered = new CountDownLatch(1);
        final CountDownLatch releaseFirstPublish = new CountDownLatch(1);
        final Thread firstCommitter = new Thread(() -> SelectionObservation.commit(
            baseline,
            new SelectionObservation(source, 2, stateB),
            (previous, current) -> {
                firstPublishEntered.countDown();
                awaitQuietly(releaseFirstPublish);
                transitions.add("A>B");
            }
        ));
        firstCommitter.start();
        assertTrue(firstPublishEntered.await(5, TimeUnit.SECONDS));

        final Thread secondCommitter = new Thread(() -> SelectionObservation.commit(
            baseline,
            new SelectionObservation(source, 3, stateC),
            (previous, current) -> transitions.add("B>C")
        ));
        try {
            secondCommitter.start();
            // The second commit cannot publish while the first commit's
            // publication is still inside the baseline monitor.
            Thread.sleep(60);
            assertTrue(
                transitions.isEmpty(),
                "a concurrent commit must not publish before the in-flight transition"
            );
            releaseFirstPublish.countDown();
            firstCommitter.join(5_000);
            secondCommitter.join(5_000);
        } finally {
            releaseFirstPublish.countDown();
        }

        assertEquals(List.of("A>B", "B>C"), transitions);
    }

    @Test
    void closingPublisherFencesInFlightRead() throws Exception {
        final BlockingHostSource source = new BlockingHostSource();
        harness(source);
        final List<SelectionChangedEvent> events = new ArrayList<>();
        subscribe("plugin.alpha", events, new CountDownLatch(1));

        assertTrue(source.readEntered.await(5, TimeUnit.SECONDS));
        publisher.close();
        source.releaseReads.countDown();
        source.replaceSelection(List.of("mesh-face"));
        publisher.signalDemand();

        Thread.sleep(60);
        assertTrue(events.isEmpty(), "a read fenced by close must not publish");
    }

    @Test
    void unresponsiveHostReadDoesNotStallSchedulerOrClose() throws Exception {
        final BlockingHostSource source = new BlockingHostSource();
        harness(source);
        subscribe("plugin.alpha", new ArrayList<>(), new CountDownLatch(1));

        assertTrue(source.readEntered.await(5, TimeUnit.SECONDS));
        try {
            // While the host read is blocked, the shared scheduler must still
            // serve unrelated timers.
            final CountDownLatch timerFired = new CountDownLatch(1);
            assertTrue(scheduler.schedule(Duration.ZERO, timerFired::countDown).accepted());
            assertTrue(
                timerFired.await(5, TimeUnit.SECONDS),
                "a blocked host read must not stall unrelated scheduler timers"
            );
            // And close must return promptly without waiting for the read.
            publisher.close();
        } finally {
            source.releaseReads.countDown();
        }
    }

    @Test
    void atMostOnePhysicalReadIsInFlight() throws Exception {
        final MutableHostSource source = new MutableHostSource();
        harness(source);
        subscribe("plugin.alpha", new ArrayList<>(), new CountDownLatch(1));

        Thread.sleep(120);

        assertTrue(source.reads.get() > 0, "demand must produce periodic reads");
        assertEquals(1, source.maxConcurrentReads.get(), "reads must not overlap");
    }

    @Test
    void sessionSourceObservesDocumentCloseToEmpty() throws Exception {
        // Real production composition: HostSessionSnapshotSource over a mutable
        // ProjectWorkspaceAdapter. Closing the last document must publish the
        // transition to the empty selection — isHostPresent must not suppress it.
        final MutableProjectWorkspace workspace = new MutableProjectWorkspace();
        workspace.openDocument(modelDocument("doc-1", "model-1"));
        final HostSnapshotSource source =
            HostSessionSnapshotSource.forSession(workspace);
        harness(source);
        final List<SelectionChangedEvent> events = new ArrayList<>();
        final CountDownLatch delivered = new CountDownLatch(1);
        subscribe("plugin.alpha", events, delivered);

        // Wait for the initial (silent) baseline commit before mutating.
        awaitBaselineCommit();
        workspace.closeDocument();

        assertTrue(delivered.await(5, TimeUnit.SECONDS));
        final SelectionChangedEvent event = events.get(0);
        assertTrue(
            event.previousSelection().activeDocumentId().isPresent(),
            "previous selection must carry the closed document"
        );
        assertTrue(
            event.currentSelection().activeDocumentId().isEmpty()
                && event.currentSelection().selectedModelObjectIds().isEmpty(),
            "closing the last document must publish the empty selection"
        );
    }

    @Test
    void initialObservationIsSilent() throws Exception {
        final MutableHostSource source = new MutableHostSource();
        source.replaceSelection(List.of("mesh-face"));
        harness(source);
        final List<SelectionChangedEvent> events = new ArrayList<>();
        subscribe("plugin.alpha", events, new CountDownLatch(1));

        Thread.sleep(120);

        assertTrue(
            events.isEmpty(),
            "the first observation establishes the baseline without an event"
        );
    }

    // -- harness ------------------------------------------------------------

    private void harness(final HostSnapshotSource source) {
        scheduler = directScheduler();
        lane = new SharedAsyncHostReadLane(16);
        broker = new RuntimeEventBroker(scheduler);
        publisher = new SelectionObservationPublisher(
            source,
            lane,
            scheduler,
            broker,
            broker.observationBaseline(SelectionObservation.class),
            FAST
        );
        publisher.signalDemand();
    }

    private void subscribe(
        final String pluginId,
        final List<SelectionChangedEvent> sink,
        final CountDownLatch delivered
    ) {
        final RuntimeEventBroker.Owner owner = broker.admit(pluginId);
        broker.subscribe(owner.key(), SelectionChangedEvent.class, event -> {
            sink.add(event);
            delivered.countDown();
        });
        owner.activate();
    }

    private void awaitBaselineCommit() throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (broker.observationBaseline(SelectionObservation.class).get() == null) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("baseline was never committed");
            }
            Thread.sleep(5);
        }
    }

    private static java.util.function.BiConsumer<SelectionSummary, SelectionSummary> record(
        final List<String> transitions
    ) {
        return (previous, current) -> transitions.add(
            marker(previous) + ">" + marker(current)
        );
    }

    private static String marker(final SelectionSummary summary) {
        return summary.selectedModelObjectIds().isEmpty()
            ? "?"
            : summary.selectedModelObjectIds().get(0).value().toUpperCase();
    }

    private static SelectionSummary observedIdentity(final List<String> selectedIds) {
        return new SelectionSummary(
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            List.of(),
            List.of(),
            List.of(),
            selectedIds.stream().map(ModelObjectId::new).toList()
        );
    }

    private static void awaitQuietly(final CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static DocumentSnapshot modelDocument(
        final String documentId,
        final String modelId
    ) {
        return new DocumentSnapshot(
            documentId,
            documentId,
            "model.moc",
            Optional.empty(),
            Optional.of(new ModelSnapshot(
                modelId, modelId, List.of(), List.of(), List.of(), List.of()
            )),
            DocumentKind.MODEL,
            Optional.empty(),
            Optional.empty()
        );
    }

    private static RuntimeScheduler directScheduler() {
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 2, event -> { }, FIXED_CLOCK),
            (task, callback) -> {
                callback.run();
                return CompletableFuture.completedFuture(
                    dev.turboism.core.runtime.sidecar.SidecarResult.success("")
                );
            },
            event -> { }
        );
    }

    // -- fixtures -------------------------------------------------------------

    /** Mutable source: object selection ids can be replaced; each read is counted. */
    private static final class MutableHostSource implements HostSnapshotSource {

        private static final HostArtMesh MESH = new HostArtMesh(
            "mesh-face", "Face Mesh", Optional.empty(), true, true
        );
        private static final HostParameter PARAMETER = new HostParameter(
            "param-angle-x", "Angle X", 0.0, 0.0, -30.0, 30.0, true, true
        );
        private static final HostModel MODEL = new HostModel(
            "model-1", "Model", List.of(PARAMETER), List.of(MESH), List.of()
        );

        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger concurrentReads = new AtomicInteger();
        final AtomicInteger maxConcurrentReads = new AtomicInteger();
        private final AtomicLong invalidationToken = new AtomicLong();
        private volatile List<String> selectedObjectIds = List.of();

        void replaceSelection(final List<String> next) {
            selectedObjectIds = List.copyOf(next);
            invalidationToken.incrementAndGet();
        }

        @Override
        public Optional<HostProject> activeProject() {
            return Optional.empty();
        }

        @Override
        public Optional<HostDocument> activeDocument() {
            return Optional.empty();
        }

        @Override
        public Optional<HostModel> activeModel() {
            return Optional.of(MODEL);
        }

        @Override
        public HostSelection selection() {
            final int inFlight = concurrentReads.incrementAndGet();
            maxConcurrentReads.accumulateAndGet(inFlight, Math::max);
            try {
                return new HostSelection(
                    selectedObjectIds,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()
                );
            } finally {
                concurrentReads.decrementAndGet();
            }
        }

        @Override
        public boolean isHostPresent() {
            return true;
        }

        @Override
        public long invalidationToken() {
            reads.incrementAndGet();
            return invalidationToken.get();
        }
    }

    /** Source whose reads block until released — proves reads cannot stall close/timers. */
    private static final class BlockingHostSource implements HostSnapshotSource {

        final CountDownLatch readEntered = new CountDownLatch(1);
        final CountDownLatch releaseReads = new CountDownLatch(1);
        private volatile List<String> selectedObjectIds = List.of();

        void replaceSelection(final List<String> next) {
            selectedObjectIds = List.copyOf(next);
        }

        private <T> T block(final T value) {
            readEntered.countDown();
            awaitQuietly(releaseReads);
            return value;
        }

        @Override
        public Optional<HostProject> activeProject() {
            return block(Optional.empty());
        }

        @Override
        public Optional<HostDocument> activeDocument() {
            return block(Optional.empty());
        }

        @Override
        public Optional<HostModel> activeModel() {
            return block(Optional.empty());
        }

        @Override
        public HostSelection selection() {
            return block(new HostSelection(
                selectedObjectIds,
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
            ));
        }

        @Override
        public boolean isHostPresent() {
            return true;
        }

        @Override
        public long invalidationToken() {
            readEntered.countDown();
            awaitQuietly(releaseReads);
            return 0L;
        }
    }

    /** Real-adapter composition fixture: mutable document/project/workspace snapshots. */
    private static final class MutableProjectWorkspace implements ProjectWorkspaceAdapter {

        private volatile Optional<DocumentSnapshot> document = Optional.empty();

        void openDocument(final DocumentSnapshot snapshot) {
            document = Optional.of(snapshot);
        }

        void closeDocument() {
            document = Optional.empty();
        }

        @Override
        public AdapterResult<Optional<ProjectSnapshot>> activeProject() {
            return AdapterResult.available(Optional.empty());
        }

        @Override
        public AdapterResult<Optional<DocumentSnapshot>> activeDocument() {
            return AdapterResult.available(document);
        }

        @Override
        public AdapterResult<Optional<WorkspaceSnapshot>> workspace() {
            return AdapterResult.available(Optional.empty());
        }

        @Override
        public AdapterResult<ProjectWorkspaceSnapshot> projectWorkspaceSnapshot() {
            return AdapterResult.unavailable(
                SafeModeDiagnostic.capabilityUnavailable(WORKSPACE_CAPABILITY_ID)
            );
        }
    }
}
