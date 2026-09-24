package dev.turboism.adapter.cubism.core;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class BorrowedCoreModelSourceTest {

    @Test
    void acquiresScopedMetadataAndKeepsRawAccessPackagePrivate() {
        final BorrowedCoreModelSource source = new BorrowedCoreModelSource();
        final SyntheticModel model = new SyntheticModel("model-a");
        source.publishBorrowedModel(model, "model-a");

        final CoreModelLease lease = source.acquire(provider("5.2.03"))
            .lease().orElseThrow();

        assertEquals(1L, lease.generation());
        assertEquals("model-a", lease.modelIdentity());
        assertEquals("cubism-core-public-5.2.03", lease.providerId());
        assertEquals("5.2.03", lease.artifactProfile());
        assertEquals(
            "model-a",
            lease.readForProvider(raw -> ((SyntheticModel) raw).identity())
        );
        assertFalse(Modifier.isPublic(CoreModelLease.class.getModifiers()));
        assertFalse(Modifier.isPublic(ActiveCoreModelSource.class.getModifiers()));
        assertFalse(Modifier.isPublic(BorrowedCoreModelSource.class.getModifiers()));
        assertFalse(Modifier.isPublic(readMethodModifiers()));

        lease.close();
        source.close();
        assertNoLifecycleCalls(model);
    }

    @Test
    void unavailableProviderAndMissingModelAreDistinctFailures() {
        final BorrowedCoreModelSource source = new BorrowedCoreModelSource();
        source.publishBorrowedModel(new SyntheticModel("model-a"), "model-a");

        final CoreModelAcquisition unavailable =
            source.acquire(CorePublicApiProvider.safeMode());
        assertEquals(
            CoreModelFailure.Code.ADAPTER_UNAVAILABLE,
            failureCode(unavailable)
        );

        source.clearBorrowedModel();
        final CoreModelAcquisition missing = source.acquire(provider("5.3.02"));
        assertEquals(CoreModelFailure.Code.MODEL_UNAVAILABLE, failureCode(missing));
        source.close();
    }

    @Test
    void replacementWaitsForLeaseAndNeverClosesEitherBorrowedModel() {
        final BorrowedCoreModelSource source = new BorrowedCoreModelSource();
        final SyntheticModel first = new SyntheticModel("model-a");
        final SyntheticModel second = new SyntheticModel("model-b");
        source.publishBorrowedModel(first, "model-a");
        final CoreModelLease firstLease = source.acquire(provider("5.2.03"))
            .lease().orElseThrow();

        final AtomicReference<Throwable> replacementFailure = new AtomicReference<>();
        final Thread replacement = new Thread(
            () -> captureFailure(
                () -> source.publishBorrowedModel(second, "model-b"),
                replacementFailure
            ),
            "core-model-replacement"
        );
        replacement.start();
        awaitWaiting(replacement);

        assertEquals(
            CoreModelFailure.Code.TRANSITION_IN_PROGRESS,
            failureCode(source.acquire(provider("5.2.03")))
        );
        assertEquals("model-a", firstLease.readForProvider(
            raw -> ((SyntheticModel) raw).identity()
        ));

        firstLease.close();
        join(replacement);
        assertNull(replacementFailure.get());

        final CoreModelLease secondLease = source.acquire(provider("5.3.02"))
            .lease().orElseThrow();
        assertEquals(2L, secondLease.generation());
        assertEquals("model-b", secondLease.modelIdentity());
        assertEquals(
            "model-b",
            secondLease.readForProvider(raw -> ((SyntheticModel) raw).identity())
        );
        secondLease.close();
        source.close();

        assertNoLifecycleCalls(first);
        assertNoLifecycleCalls(second);
    }

    @Test
    void sourceCloseWaitsForLeaseThenFailsClosed() {
        final BorrowedCoreModelSource source = new BorrowedCoreModelSource();
        final SyntheticModel model = new SyntheticModel("model-a");
        source.publishBorrowedModel(model, "model-a");
        final CoreModelLease lease = source.acquire(provider("5.2.03"))
            .lease().orElseThrow();

        final AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        final Thread closing = new Thread(
            () -> captureFailure(source::close, closeFailure),
            "core-model-source-close"
        );
        closing.start();
        awaitWaiting(closing);
        assertEquals(
            CoreModelFailure.Code.TRANSITION_IN_PROGRESS,
            failureCode(source.acquire(provider("5.2.03")))
        );

        lease.close();
        join(closing);
        assertNull(closeFailure.get());
        assertEquals(
            CoreModelFailure.Code.SOURCE_CLOSED,
            failureCode(source.acquire(provider("5.2.03")))
        );
        source.close();
        assertNoLifecycleCalls(model);
    }

    @Test
    void staleAndClosedLeasesFailWithTypedReasonsAndReleaseOnce() {
        final AtomicLong currentGeneration = new AtomicLong(2L);
        final AtomicInteger releases = new AtomicInteger();
        final CoreModelLease lease = new CoreModelLease(
            1L,
            "model-a",
            "cubism-core-fake",
            "synthetic",
            new SyntheticModel("model-a"),
            currentGeneration::get,
            releases::incrementAndGet
        );

        final CoreModelLeaseException stale = assertThrows(
            CoreModelLeaseException.class,
            () -> lease.readForProvider(raw -> "unused")
        );
        assertEquals(
            CoreModelFailure.Code.STALE_GENERATION,
            stale.failure().code()
        );

        currentGeneration.set(1L);
        lease.close();
        lease.close();
        assertEquals(1, releases.get());
        final CoreModelLeaseException closed = assertThrows(
            CoreModelLeaseException.class,
            () -> lease.readForProvider(raw -> "unused")
        );
        assertEquals(CoreModelFailure.Code.LEASE_CLOSED, closed.failure().code());
    }

    @Test
    void leaseCloseWaitsForInFlightScopedRead() {
        final BorrowedCoreModelSource source = new BorrowedCoreModelSource();
        source.publishBorrowedModel(new SyntheticModel("model-a"), "model-a");
        final CoreModelLease lease = source.acquire(provider("5.2.03"))
            .lease().orElseThrow();
        final java.util.concurrent.CountDownLatch readEntered =
            new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch releaseRead =
            new java.util.concurrent.CountDownLatch(1);
        final AtomicReference<Throwable> readFailure = new AtomicReference<>();
        final AtomicReference<Throwable> closeFailure = new AtomicReference<>();

        final Thread reader = new Thread(
            () -> captureFailure(() -> lease.readForProvider(raw -> {
                readEntered.countDown();
                await(releaseRead);
                return null;
            }), readFailure),
            "core-model-reader"
        );
        reader.start();
        await(readEntered);
        final Thread closing = new Thread(
            () -> captureFailure(lease::close, closeFailure),
            "core-model-lease-close"
        );
        closing.start();
        awaitBlocked(closing);

        releaseRead.countDown();
        join(reader);
        join(closing);
        assertNull(readFailure.get());
        assertNull(closeFailure.get());
        assertFalse(lease.isOpen());
        source.close();
    }

    @Test
    void idleReleaseForgetsTheModelImmediatelyWhenNoLeaseIsOutstanding() {
        final BorrowedCoreModelSource source = new BorrowedCoreModelSource();
        final AtomicInteger cleared = new AtomicInteger();
        source.onModelCleared(cleared::incrementAndGet);
        final SyntheticModel model = new SyntheticModel("model-a");
        source.publishBorrowedModel(model, "model-a");

        source.releaseWhenIdle();
        source.releaseWhenIdle();

        assertNull(source.publishedModel());
        assertEquals(1, cleared.get(), "a repeated idle release stays idempotent");
        assertEquals(
            CoreModelFailure.Code.MODEL_UNAVAILABLE,
            failureCode(source.acquire(provider("5.3.02")))
        );
        source.close();
        assertNoLifecycleCalls(model);
    }

    @Test
    void idleReleaseDefersUntilTheLastLeaseDrains() {
        final BorrowedCoreModelSource source = new BorrowedCoreModelSource();
        final AtomicInteger cleared = new AtomicInteger();
        source.onModelCleared(cleared::incrementAndGet);
        final SyntheticModel model = new SyntheticModel("model-a");
        source.publishBorrowedModel(model, "model-a");
        final CoreModelLease lease = source.acquire(provider("5.2.03"))
            .lease().orElseThrow();

        source.releaseWhenIdle();
        assertSame(model, source.publishedModel(), "a held lease keeps the model published");
        assertEquals(
            "model-a",
            lease.readForProvider(raw -> ((SyntheticModel) raw).identity())
        );
        assertEquals(0, cleared.get());

        lease.close();
        assertNull(source.publishedModel());
        assertEquals(1, cleared.get());
        assertEquals(
            CoreModelFailure.Code.MODEL_UNAVAILABLE,
            failureCode(source.acquire(provider("5.3.02")))
        );
        source.close();
    }

    @Test
    void aLaterPublicationCancelsAPendingIdleRelease() {
        final BorrowedCoreModelSource source = new BorrowedCoreModelSource();
        source.publishBorrowedModel(new SyntheticModel("model-a"), "model-a");
        final CoreModelLease lease = source.acquire(provider("5.2.03"))
            .lease().orElseThrow();
        source.releaseWhenIdle();

        final AtomicReference<Throwable> publishFailure = new AtomicReference<>();
        final Thread publishing = new Thread(
            () -> captureFailure(
                () -> source.publishBorrowedModel(new SyntheticModel("model-b"), "model-b"),
                publishFailure
            ),
            "core-model-republish"
        );
        publishing.start();
        awaitWaiting(publishing);

        lease.close();
        join(publishing);
        assertNull(publishFailure.get());
        final CoreModelLease republished = source.acquire(provider("5.3.02"))
            .lease().orElseThrow();
        assertEquals(
            "model-b",
            republished.modelIdentity(),
            "the pending release must not forget the replacement"
        );
        republished.close();
        source.close();
    }

    @Test
    void modelClearedListenersFireOnEveryClearPath() {
        final BorrowedCoreModelSource source = new BorrowedCoreModelSource();
        final AtomicInteger cleared = new AtomicInteger();
        source.onModelCleared(cleared::incrementAndGet);

        source.publishBorrowedModel(new SyntheticModel("model-a"), "model-a");
        source.releaseWhenIdle();
        assertEquals(1, cleared.get());

        source.publishBorrowedModel(new SyntheticModel("model-b"), "model-b");
        source.clearBorrowedModel();
        assertEquals(2, cleared.get());

        source.publishBorrowedModel(new SyntheticModel("model-c"), "model-c");
        source.close();
        assertEquals(3, cleared.get());
    }

    private static int readMethodModifiers() {
        try {
            return CoreModelLease.class.getDeclaredMethod(
                "readForProvider",
                java.util.function.Function.class
            ).getModifiers();
        } catch (NoSuchMethodException exception) {
            throw new AssertionError(exception);
        }
    }

    private static CoreModelFailure.Code failureCode(
        final CoreModelAcquisition acquisition
    ) {
        assertFalse(acquisition.isAcquired());
        return acquisition.failure().orElseThrow().code();
    }

    private static CorePublicApiProvider provider(final String profile) {
        return new TestProvider(profile);
    }

    private static void assertNoLifecycleCalls(final SyntheticModel model) {
        assertEquals(0, model.closeCalls.get());
        assertEquals(0, model.deleteCalls.get());
    }

    private static void captureFailure(
        final Runnable operation,
        final AtomicReference<Throwable> failure
    ) {
        try {
            operation.run();
        } catch (Throwable throwable) {
            failure.set(throwable);
        }
    }

    private static void awaitWaiting(final Thread thread) {
        awaitState(thread, Thread.State.WAITING);
    }

    private static void awaitBlocked(final Thread thread) {
        awaitState(thread, Thread.State.BLOCKED);
    }

    private static void awaitState(final Thread thread, final Thread.State expected) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (thread.getState() == expected) {
                return;
            }
            Thread.onSpinWait();
        }
        fail("thread did not reach " + expected + ": " + thread.getState());
    }

    private static void await(final java.util.concurrent.CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static void join(final Thread thread) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
        assertFalse(thread.isAlive(), "thread did not terminate");
    }

    private record TestProvider(String artifactProfile)
        implements CorePublicApiProvider {

        @Override
        public String providerId() {
            return "cubism-core-public-" + artifactProfile;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public CoreProviderResult<CoreRuntimeVersion> runtimeVersion() {
            return CoreProviderResult.success(new CoreRuntimeVersion(11, 12, 13));
        }
    }

    private static final class SyntheticModel implements AutoCloseable {

        private final String identity;
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final AtomicInteger deleteCalls = new AtomicInteger();

        private SyntheticModel(final String identity) {
            this.identity = identity;
        }

        private String identity() {
            return identity;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }

        @SuppressWarnings("unused")
        public void delete() {
            deleteCalls.incrementAndGet();
        }
    }
}
