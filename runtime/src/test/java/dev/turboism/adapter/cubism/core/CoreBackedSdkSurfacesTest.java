package dev.turboism.adapter.cubism.core;

import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.ParameterType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SDK surfaces declared on {@code CubismModel} that the Core-backed path now
 * wires read-only: parameter definitions, MOC info, and the documented
 * fail-closed Warp Deformer projection.
 */
class CoreBackedSdkSurfacesTest {

    @BeforeEach
    void resetVersion() {
        TestCoreApiFixture.resetVersion();
    }

    @Test
    void exposesReadOnlyParameterDefinitionsOnTheCorePath() {
        final TestCoreApiFixture.Model coreModel = new TestCoreApiFixture.Model(
            new TestCoreApiFixture.CanvasInfo(
                new float[]{1000.0F, 500.0F}, new float[]{500.0F, 250.0F}, 100.0F
            ),
            new TestCoreApiFixture.Parameters(
                new String[]{"ParamAngleX", "ParamCheek", "ParamFuture"},
                new TestCoreApiFixture.ParameterType[]{
                    new TestCoreApiFixture.ParameterType(0),
                    new TestCoreApiFixture.ParameterType(1),
                    new TestCoreApiFixture.ParameterType(0),
                },
                new float[]{-30.0F, -10.0F, 0.0F},
                new float[]{30.0F, 10.0F, 1.0F},
                new float[]{0.0F, 0.0F, 0.5F},
                new float[]{0.0F, 0.5F, 1.0F},
                new int[]{0, 0, 0},
                new float[][]{new float[0], new float[0], new float[0]},
                new boolean[]{false, true, false}
            ),
            TestCoreApiFixture.Parts.empty(),
            TestCoreApiFixture.Drawables.empty(),
            TestCoreApiFixture.Deformers.empty(),
            TestCoreApiFixture.Glues.empty(),
            () -> { }
        );
        try (var harness = harness("5.3.02", coreModel)) {
            final CubismModel model = harness.access.active();
            final var definitions = model.parameterDefinitions();
            assertEquals(3, definitions.all().size());
            final var angle = definitions.find(new ParameterId("ParamAngleX"));
            assertEquals("ParamAngleX", angle.name());
            assertEquals(-30.0F, angle.minimumValue());
            assertEquals(0.0F, angle.defaultValue());
            assertEquals(30.0F, angle.maximumValue());
            assertEquals(ParameterType.NORMAL, angle.type());
            final var cheek = definitions.find(new ParameterId("ParamCheek"));
            assertEquals(ParameterType.BLEND_SHAPE, cheek.type());
            assertTrue(cheek.repeat());
            assertThrows(
                java.util.NoSuchElementException.class,
                () -> definitions.find(new ParameterId("Missing"))
            );
        }
    }

    @Test
    void mocInfoOnTheCorePathFailsClosedUntilMocEvidenceIsAdmitted() {
        // The reviewed production plan admits no MOC selectors yet (W3 promotion);
        // resolution failure must surface as a fail-closed IllegalStateException.
        final TestCoreApiFixture.Model coreModel = new TestCoreApiFixture.Model(
            new TestCoreApiFixture.CanvasInfo(
                new float[]{1000.0F, 500.0F}, new float[]{500.0F, 250.0F}, 100.0F
            ),
            new TestCoreApiFixture.Parameters(
                new String[]{"ParamAngleX"},
                new TestCoreApiFixture.ParameterType[]{new TestCoreApiFixture.ParameterType(0)},
                new float[]{-30.0F}, new float[]{30.0F}, new float[]{0.0F}, new float[]{0.0F},
                new int[]{0}, new float[][]{new float[0]}, new boolean[]{false}
            ),
            TestCoreApiFixture.Parts.empty(),
            TestCoreApiFixture.Drawables.empty(),
            TestCoreApiFixture.Deformers.empty(),
            TestCoreApiFixture.Glues.empty(),
            new TestCoreApiFixture.Moc(6),
            () -> { }
        );
        try (var harness = harness("5.3.02", coreModel)) {
            final CubismModel model = harness.access.active();
            final IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                model::mocInfo
            );
            assertTrue(failure.getMessage().contains("unavailable"), failure.getMessage());
        }
    }

    @Test
    void warpDeformersOnTheCorePathFailClosedWithKindEvidence() {
        final TestCoreApiFixture.Model coreModel = new TestCoreApiFixture.Model(
            new TestCoreApiFixture.CanvasInfo(
                new float[]{1000.0F, 500.0F}, new float[]{500.0F, 250.0F}, 100.0F
            ),
            new TestCoreApiFixture.Parameters(
                new String[]{"ParamAngleX"},
                new TestCoreApiFixture.ParameterType[]{new TestCoreApiFixture.ParameterType(0)},
                new float[]{-30.0F}, new float[]{30.0F}, new float[]{0.0F}, new float[]{0.0F},
                new int[]{0}, new float[][]{new float[0]}, new boolean[]{false}
            ),
            TestCoreApiFixture.Parts.empty(),
            TestCoreApiFixture.Drawables.empty(),
            new TestCoreApiFixture.Deformers(
                new String[]{"WarpRoot"}, new int[]{-1}, new int[]{1}, new int[][]{{0}}
            ),
            TestCoreApiFixture.Glues.empty(),
            () -> { }
        );
        try (var harness = harness("5.3.02", coreModel)) {
            final CubismModel model = harness.access.active();
            final UnsupportedOperationException failure = assertThrows(
                UnsupportedOperationException.class,
                model::warpDeformers
            );
            assertTrue(failure.getMessage().contains("kind"), failure.getMessage());
            // The neutral deformer family stays available.
            assertEquals(
                List.of("WarpRoot"),
                model.deformers().all().stream()
                    .map(deformer -> deformer.id().value())
                    .toList()
            );
        }
    }

    private static CoreBackedCubismModelAccessHarness harness(
        final String artifactProfile,
        final TestCoreApiFixture.Model model
    ) {
        return CoreBackedCubismModelAccessHarness.create(artifactProfile, model);
    }

    /** Reuses the package-local harness shared by the Core access tests. */
    private record CoreBackedCubismModelAccessHarness(
        CoreBackedCubismModelAccess access
    ) implements AutoCloseable {

        static CoreBackedCubismModelAccessHarness create(
            final String artifactProfile,
            final TestCoreApiFixture.Model model
        ) {
            final var resolver = TestCoreApiFixture.resolver(artifactProfile);
            final var provider = CorePublicApiProviderFactory.admit(
                resolver, CoreVersionExpectation.exact(11, 12, 13)
            ).value().orElseThrow();
            final var tracer = CoreStructuralTracerFactory.admit(provider, resolver)
                .value().orElseThrow();
            final var source = new BorrowedCoreModelSource();
            source.publishBorrowedModel(model, "model-a");
            return new CoreBackedCubismModelAccessHarness(
                new CoreBackedCubismModelAccess(source, provider, tracer)
            );
        }

        @Override
        public void close() {
            // The access owns no closeable state beyond the shared source/tracer.
        }
    }
}
