package dev.turboism.adapter.cubism.core;

import dev.turboism.mapping.verification.CorePublicApiSelectorContract;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class CoreStructuralTracerTest {

    @BeforeEach
    void resetVersion() {
        TestCoreApiFixture.resetVersion();
    }

    @Test
    void projectsBothProfilesWithoutInventingVersionSpecificRepeatData() {
        final ModelData data = validModelData();
        final CoreStructuralSnapshot snapshot52;
        final CoreStructuralSnapshot snapshot53;

        try (
            Harness harness52 = harness("5.2", data.model);
            Harness harness53 = harness("5.3.02", data.model)
        ) {
            snapshot52 = harness52.tracer.trace(harness52.lease)
                .value().orElseThrow();
            snapshot53 = harness53.tracer.trace(harness53.lease)
                .value().orElseThrow();
        }

        assertEquals(1L, snapshot52.generation());
        assertEquals("model-a", snapshot52.modelIdentity());
        assertEquals("cubism-core-public-5.2", snapshot52.providerId());
        assertEquals("5.2", snapshot52.artifactProfile());
        assertEquals(
            new CoreCanvasSnapshot(1000.0F, 500.0F, 500.0F, 250.0F, 100.0F),
            snapshot52.canvas()
        );

        final CoreParameterDefinition angle52 = snapshot52.parameters().get(0);
        assertEquals("ParamAngleX", angle52.id());
        assertEquals(0, angle52.typeNumber());
        assertEquals(-30.0F, angle52.minimumValue());
        assertEquals(30.0F, angle52.maximumValue());
        assertEquals(0.0F, angle52.defaultValue());
        assertEquals(42.0F, angle52.currentValue());
        assertEquals(List.of(-30.0F, 0.0F, 30.0F), angle52.keyValues());
        assertEquals(Optional.empty(), angle52.repeat());

        assertEquals(Optional.of(true), snapshot53.parameters().get(0).repeat());
        assertEquals(Optional.of(false), snapshot53.parameters().get(1).repeat());
        assertEquals("cubism-core-public-5.3.02", snapshot53.providerId());
        assertEquals("5.3.02", snapshot53.artifactProfile());
    }

    @Test
    void snapshotCopiesCoreOwnedArraysAndExposesOnlyAdapterOwnedShapes() {
        final ModelData data = validModelData();
        final CoreStructuralSnapshot snapshot;
        try (Harness harness = harness("5.3.02", data.model)) {
            snapshot = harness.tracer.trace(harness.lease).value().orElseThrow();
        }

        data.canvasSize[0] = -1.0F;
        data.canvasOrigin[0] = -2.0F;
        data.ids[0] = "mutated";
        data.currentValues[0] = -99.0F;
        data.keyValues[0][0] = -999.0F;
        data.repeats[0] = false;

        assertEquals(1000.0F, snapshot.canvas().widthPixels());
        assertEquals(500.0F, snapshot.canvas().originXPixels());
        assertEquals("ParamAngleX", snapshot.parameters().get(0).id());
        assertEquals(42.0F, snapshot.parameters().get(0).currentValue());
        assertEquals(-30.0F, snapshot.parameters().get(0).keyValues().get(0));
        assertEquals(Optional.of(true), snapshot.parameters().get(0).repeat());

        assertThrows(
            UnsupportedOperationException.class,
            () -> snapshot.parameters().add(snapshot.parameters().get(0))
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> snapshot.parameters().get(0).keyValues().add(1.0F)
        );

        assertFalse(Modifier.isPublic(CoreStructuralSnapshot.class.getModifiers()));
        assertFalse(Modifier.isPublic(CoreCanvasSnapshot.class.getModifiers()));
        assertFalse(Modifier.isPublic(CoreParameterDefinition.class.getModifiers()));
        assertTrue(Arrays.stream(CoreStructuralSnapshot.class.getRecordComponents())
            .noneMatch(component -> component.getType().isArray()
                || component.getType().equals(Object.class)));
        assertTrue(Arrays.stream(CoreParameterDefinition.class.getRecordComponents())
            .noneMatch(component -> component.getType().isArray()
                || component.getType().equals(Object.class)));
        assertEquals(
            List.of(
                "generation",
                "modelIdentity",
                "providerId",
                "artifactProfile",
                "canvas",
                "parameters",
                "parts",
                "drawables",
                "deformers",
                "glues"
            ),
            Arrays.stream(CoreStructuralSnapshot.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList()
        );
    }

    @Test
    void rejectsIncompleteProfileSpecificEvidenceBeforeBinding() {
        assertEquals(
            69,
            CorePublicApiSelectorContract.requiredAliasesFor("5.2")
                .orElseThrow().size()
        );
        assertEquals(
            70,
            CorePublicApiSelectorContract.requiredAliasesFor("5.3.02")
                .orElseThrow().size()
        );

        final CoreProviderResult<CorePublicApiProvider> missingCommon =
            CorePublicApiProviderFactory.admit(
                TestCoreApiFixture.resolver(
                    "5.2",
                    CorePublicApiSelectorContract.MODEL_GET_PARAMETERS
                ),
                CoreVersionExpectation.exact(11, 12, 13)
            );
        final CoreProviderResult<CorePublicApiProvider> missingRepeat =
            CorePublicApiProviderFactory.admit(
                TestCoreApiFixture.resolver(
                    "5.3.02",
                    CorePublicApiSelectorContract.PARAMETERS_GET_REPEATS
                ),
                CoreVersionExpectation.exact(11, 12, 13)
            );

        assertEquals(
            CoreProviderFailure.Code.EVIDENCE_REJECTED,
            failureCode(missingCommon)
        );
        assertEquals(
            CoreProviderFailure.Code.EVIDENCE_REJECTED,
            failureCode(missingRepeat)
        );
    }

    @Test
    void rejectsProviderResolverAndLeaseProfileMismatches() {
        final VerifiedMemberResolver resolver52 =
            TestCoreApiFixture.resolver("5.2");
        final CorePublicApiProvider provider52 = admittedProvider(resolver52);
        final CoreProviderResult<CoreStructuralTracer> mismatchedResolver =
            CoreStructuralTracerFactory.admit(
                provider52,
                TestCoreApiFixture.resolver("5.3.02")
            );
        assertEquals(
            CoreProviderFailure.Code.EVIDENCE_REJECTED,
            failureCode(mismatchedResolver)
        );

        final CoreStructuralTracer tracer = CoreStructuralTracerFactory.admit(
            provider52,
            resolver52
        ).value().orElseThrow();
        final CoreModelLease wrongLease = new CoreModelLease(
            1L,
            "model-a",
            "cubism-core-public-5.3.02",
            "5.3.02",
            validModelData().model,
            () -> 1L,
            () -> { }
        );
        try {
            assertEquals(
                CoreProviderFailure.Code.EVIDENCE_REJECTED,
                failureCode(tracer.trace(wrongLease))
            );
        } finally {
            wrongLease.close();
            tracer.close();
        }
    }

    @Test
    void malformedLengthsNonFiniteValuesAndKeyCountsFailClosed() {
        assertInvalidStructure(modelWith(
            new float[]{42.0F},
            new int[]{3, 0},
            new float[][]{{-30.0F, 0.0F, 30.0F}, {}},
            new boolean[]{true, false}
        ));
        assertInvalidStructure(modelWith(
            new float[]{Float.NaN, 0.5F},
            new int[]{3, 0},
            new float[][]{{-30.0F, 0.0F, 30.0F}, {}},
            new boolean[]{true, false}
        ));
        assertInvalidStructure(modelWith(
            new float[]{42.0F, 0.5F},
            new int[]{3, 0},
            new float[][]{{-30.0F, 30.0F}, {}},
            new boolean[]{true, false}
        ));
        assertInvalidStructure(modelWith(
            new float[]{42.0F, 0.5F},
            new int[]{3, 0},
            new float[][]{{-30.0F, 0.0F, 30.0F}, {}},
            new boolean[]{true}
        ));
        assertInvalidStructure(modelWithIds(
            new String[]{"ParamDuplicate", "ParamDuplicate"}
        ));
    }

    @Test
    void nullCoreSubobjectsFailAsInvalidStructure() {
        final ModelData data = validModelData();
        final TestCoreApiFixture.Model model =
            new TestCoreApiFixture.Model(null, data.parameters);
        assertInvalidStructure(model);
    }

    @Test
    void closedAndStaleLeasesHaveDistinctSanitizedFailures() {
        final VerifiedMemberResolver resolver =
            TestCoreApiFixture.resolver("5.2");
        final CorePublicApiProvider provider = admittedProvider(resolver);
        final CoreStructuralTracer tracer = CoreStructuralTracerFactory.admit(
            provider,
            resolver
        ).value().orElseThrow();
        final AtomicLong generation = new AtomicLong(1L);
        final CoreModelLease closedLease = new CoreModelLease(
            1L,
            "model-a",
            provider.providerId(),
            provider.artifactProfile(),
            validModelData().model,
            generation::get,
            () -> { }
        );
        closedLease.close();

        final CoreModelLease staleLease = new CoreModelLease(
            1L,
            "model-a",
            provider.providerId(),
            provider.artifactProfile(),
            validModelData().model,
            () -> 2L,
            () -> { }
        );
        try {
            assertEquals(
                CoreProviderFailure.Code.LEASE_CLOSED,
                failureCode(tracer.trace(closedLease))
            );
            assertEquals(
                CoreProviderFailure.Code.STALE_GENERATION,
                failureCode(tracer.trace(staleLease))
            );
        } finally {
            staleLease.close();
            tracer.close();
        }
    }

    @Test
    void generationChangeDuringProjectionDiscardsTheCompletedSnapshot() {
        final VerifiedMemberResolver resolver =
            TestCoreApiFixture.resolver("5.2");
        final CorePublicApiProvider provider = admittedProvider(resolver);
        final CoreStructuralTracer tracer = CoreStructuralTracerFactory.admit(
            provider,
            resolver
        ).value().orElseThrow();
        final AtomicLong generation = new AtomicLong(1L);
        final ModelData data = validModelData();
        final TestCoreApiFixture.Model model = new TestCoreApiFixture.Model(
            new TestCoreApiFixture.CanvasInfo(
                new float[]{1000.0F, 500.0F},
                new float[]{500.0F, 250.0F},
                100.0F
            ),
            data.parameters,
            () -> generation.set(2L)
        );
        final CoreModelLease lease = new CoreModelLease(
            1L,
            "model-a",
            provider.providerId(),
            provider.artifactProfile(),
            model,
            generation::get,
            () -> { }
        );
        try {
            final CoreProviderResult<CoreStructuralSnapshot> result =
                tracer.trace(lease);
            assertEquals(
                CoreProviderFailure.Code.STALE_GENERATION,
                failureCode(result)
            );
            assertTrue(result.value().isEmpty());
        } finally {
            lease.close();
            tracer.close();
        }
    }

    @Test
    void tracerCloseReleasesCallSitesAndFutureReadsFailClosed() {
        final ModelData data = validModelData();
        final Harness harness = harness("5.2", data.model);
        harness.tracer.close();
        harness.tracer.close();

        assertEquals(
            CoreProviderFailure.Code.ADAPTER_UNAVAILABLE,
            failureCode(harness.tracer.trace(harness.lease))
        );
        harness.close();
    }

    @Test
    void tracerCloseWaitsForAnInFlightProjectionBeforeClearingCallSites() {
        final ModelData data = validModelData();
        final CountDownLatch readEntered = new CountDownLatch(1);
        final CountDownLatch releaseRead = new CountDownLatch(1);
        final TestCoreApiFixture.Model blockingModel =
            new TestCoreApiFixture.Model(
                new TestCoreApiFixture.CanvasInfo(
                    new float[]{1000.0F, 500.0F},
                    new float[]{500.0F, 250.0F},
                    100.0F
                ),
                data.parameters,
                () -> {
                    readEntered.countDown();
                    await(releaseRead);
                }
            );
        final Harness harness = harness("5.2", blockingModel);
        final AtomicReference<CoreProviderResult<CoreStructuralSnapshot>> result =
            new AtomicReference<>();
        final AtomicReference<Throwable> readFailure = new AtomicReference<>();
        final AtomicReference<Throwable> closeFailure = new AtomicReference<>();

        final Thread reader = new Thread(
            () -> captureFailure(
                () -> result.set(harness.tracer.trace(harness.lease)),
                readFailure
            ),
            "core-structural-reader"
        );
        reader.start();
        await(readEntered);

        final Thread closing = new Thread(
            () -> captureFailure(harness.tracer::close, closeFailure),
            "core-structural-tracer-close"
        );
        closing.start();
        try {
            awaitWaiting(closing);
        } finally {
            releaseRead.countDown();
        }

        join(reader);
        join(closing);

        assertNull(readFailure.get());
        assertNull(closeFailure.get());
        assertTrue(result.get().isSuccess());
        assertEquals(
            CoreProviderFailure.Code.ADAPTER_UNAVAILABLE,
            failureCode(harness.tracer.trace(harness.lease))
        );
        harness.close();
    }

    private static void assertInvalidStructure(
        final TestCoreApiFixture.Model model
    ) {
        try (Harness harness = harness("5.3.02", model)) {
            final CoreProviderResult<CoreStructuralSnapshot> result =
                harness.tracer.trace(harness.lease);
            assertEquals(
                CoreProviderFailure.Code.INVALID_STRUCTURE,
                failureCode(result)
            );
            assertTrue(result.value().isEmpty());
        }
    }

    private static Harness harness(
        final String artifactProfile,
        final TestCoreApiFixture.Model model
    ) {
        final VerifiedMemberResolver resolver =
            TestCoreApiFixture.resolver(artifactProfile);
        final CorePublicApiProvider provider = admittedProvider(resolver);
        final CoreStructuralTracer tracer = CoreStructuralTracerFactory.admit(
            provider,
            resolver
        ).value().orElseThrow();
        final BorrowedCoreModelSource source = new BorrowedCoreModelSource();
        source.publishBorrowedModel(model, "model-a");
        final CoreModelLease lease = source.acquire(provider)
            .lease().orElseThrow();
        return new Harness(tracer, lease, source);
    }

    private static CorePublicApiProvider admittedProvider(
        final VerifiedMemberResolver resolver
    ) {
        return CorePublicApiProviderFactory.admit(
            resolver,
            CoreVersionExpectation.exact(11, 12, 13)
        ).value().orElseThrow();
    }

    private static CoreProviderFailure.Code failureCode(
        final CoreProviderResult<?> result
    ) {
        assertFalse(result.isSuccess());
        return result.failure().orElseThrow().code();
    }

    private static ModelData validModelData() {
        final float[] canvasSize = {1000.0F, 500.0F};
        final float[] canvasOrigin = {500.0F, 250.0F};
        final String[] ids = {"ParamAngleX", "ParamOpacity"};
        final float[] currentValues = {42.0F, 0.5F};
        final float[][] keyValues = {{-30.0F, 0.0F, 30.0F}, {}};
        final boolean[] repeats = {true, false};
        final TestCoreApiFixture.Parameters parameters = parameters(
            ids,
            currentValues,
            new int[]{3, 0},
            keyValues,
            repeats
        );
        final TestCoreApiFixture.Model model = new TestCoreApiFixture.Model(
            new TestCoreApiFixture.CanvasInfo(
                canvasSize,
                canvasOrigin,
                100.0F
            ),
            parameters
        );
        return new ModelData(
            model,
            parameters,
            canvasSize,
            canvasOrigin,
            ids,
            currentValues,
            keyValues,
            repeats
        );
    }

    private static TestCoreApiFixture.Model modelWithIds(
        final String[] ids
    ) {
        return new TestCoreApiFixture.Model(
            new TestCoreApiFixture.CanvasInfo(
                new float[]{1000.0F, 500.0F},
                new float[]{500.0F, 250.0F},
                100.0F
            ),
            parameters(
                ids,
                new float[]{42.0F, 0.5F},
                new int[]{3, 0},
                new float[][]{{-30.0F, 0.0F, 30.0F}, {}},
                new boolean[]{true, false}
            )
        );
    }

    private static TestCoreApiFixture.Model modelWith(
        final float[] currentValues,
        final int[] keyCounts,
        final float[][] keyValues,
        final boolean[] repeats
    ) {
        return new TestCoreApiFixture.Model(
            new TestCoreApiFixture.CanvasInfo(
                new float[]{1000.0F, 500.0F},
                new float[]{500.0F, 250.0F},
                100.0F
            ),
            parameters(
                new String[]{"ParamAngleX", "ParamOpacity"},
                currentValues,
                keyCounts,
                keyValues,
                repeats
            )
        );
    }

    private static TestCoreApiFixture.Parameters parameters(
        final String[] ids,
        final float[] currentValues,
        final int[] keyCounts,
        final float[][] keyValues,
        final boolean[] repeats
    ) {
        return new TestCoreApiFixture.Parameters(
            ids,
            new TestCoreApiFixture.ParameterType[]{
                new TestCoreApiFixture.ParameterType(0),
                new TestCoreApiFixture.ParameterType(1)
            },
            new float[]{-30.0F, 0.0F},
            new float[]{30.0F, 1.0F},
            new float[]{0.0F, 1.0F},
            currentValues,
            keyCounts,
            keyValues,
            repeats
        );
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
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (thread.getState() == Thread.State.WAITING) {
                return;
            }
            Thread.onSpinWait();
        }
        fail("thread did not wait for the active projection: " + thread.getState());
    }

    private static void await(final CountDownLatch latch) {
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

    private record ModelData(
        TestCoreApiFixture.Model model,
        TestCoreApiFixture.Parameters parameters,
        float[] canvasSize,
        float[] canvasOrigin,
        String[] ids,
        float[] currentValues,
        float[][] keyValues,
        boolean[] repeats
    ) {
    }

    private static final class Harness implements AutoCloseable {

        private final CoreStructuralTracer tracer;
        private final CoreModelLease lease;
        private final BorrowedCoreModelSource source;
        private boolean closed;

        private Harness(
            final CoreStructuralTracer tracer,
            final CoreModelLease lease,
            final BorrowedCoreModelSource source
        ) {
            this.tracer = tracer;
            this.lease = lease;
            this.source = source;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            lease.close();
            tracer.close();
            source.close();
        }
    }
}
