package dev.turboism.adapter.cubism.core;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterType;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.model.GlueId;
import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Color;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.lang.reflect.Modifier;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreBackedCubismModelAccessTest {

    @BeforeEach
    void resetVersion() {
        TestCoreApiFixture.resetVersion();
    }

    @Test
    void exposesActiveCoreParametersThroughGenerationBoundSdkObjects() {
        try (Harness harness = harness("5.3.02", model(
            new String[]{"ParamAngleX", "ParamOpacity"},
            new float[]{20.0F, 0.5F}
        ))) {
            final CubismModel model = harness.access.active();
            final List<Parameter> parameters = model.parameters().all();
            final Parameter angle = model.parameters().find(
                new ParameterId("ParamAngleX")
            );

            assertTrue(Modifier.isPublic(CoreBackedCubismModelAccess.class.getModifiers()));
            assertEquals("model-a", model.id().value());
            assertEquals(
                List.of("ParamAngleX", "ParamOpacity"),
                parameters.stream().map(parameter -> parameter.id().value()).toList()
            );
            assertEquals(20.0F, angle.getValue());
            assertEquals(-30.0F, angle.getMinimumValue());
            assertEquals(30.0F, angle.getMaximumValue());
            assertEquals(0.0F, angle.getDefaultValue());
            assertThrows(
                NoSuchElementException.class,
                () -> model.parameters().find(new ParameterId("Missing"))
            );
            assertEquals(1000.0F, model.canvas().widthPixels());
            assertTrue(model.parts().all().isEmpty());
            assertTrue(model.drawables().all().isEmpty());
            assertTrue(model.deformers().all().isEmpty());
            assertTrue(model.glues().all().isEmpty());
        }
    }

    @Test
    void exposesVersionNeutralCoreParameterMetadataWithoutInventingEditorFields() {
        final TestCoreApiFixture.Model coreModel = model(
            new String[]{"ParamAngleX", "ParamCheek", "ParamFuture"},
            new float[]{0.0F, 0.5F, 1.0F}
        );
        coreModel.getParameters().getParameterRepeats()[0] = true;

        try (Harness harness = harness("5.3.02", coreModel)) {
            final List<Parameter> parameters = harness.access.active().parameters().all();

            assertEquals(ParameterType.NORMAL, parameters.get(0).type());
            assertEquals(ParameterType.BLEND_SHAPE, parameters.get(1).type());
            assertEquals(ParameterType.UNKNOWN, parameters.get(2).type());
            assertEquals(Optional.of(true), parameters.get(0).repeat());
            assertEquals(Optional.of(false), parameters.get(1).repeat());
            assertEquals(Optional.empty(), parameters.get(0).name());
            assertEquals(Optional.empty(), parameters.get(0).combined());
        }
    }

    @Test
    void exposesCompleteCoreFamiliesAndCopiesBoundedGeometry() {
        final TestCoreApiFixture.Model coreModel = model(
            new String[]{"ParamAngleX"},
            new float[]{20.0F},
            new TestCoreApiFixture.Parts(
                new String[]{"PartRoot"}, new float[]{0.75F}, new int[]{-1}
            ),
            new TestCoreApiFixture.Drawables(
                new String[]{"ArtMeshFace"}, new byte[]{1}, new byte[]{2}, new int[]{1},
                new int[]{0}, new int[]{4}, new int[]{7}, new float[]{0.8F},
                new int[]{0}, new int[][]{{}}, new int[]{3},
                new float[][]{{0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F}},
                new float[][]{{0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F}},
                new int[]{3}, new short[][]{{0, 1, 2}},
                new float[][]{{1.0F, 0.5F, 0.25F, 1.0F}},
                new float[][]{{0.0F, 0.1F, 0.2F, 1.0F}},
                new int[]{0}, new int[]{0}, new int[]{1}, new int[][]{{0}}
            ),
            new TestCoreApiFixture.Deformers(
                new String[]{"WarpRoot"}, new int[]{-1}, new int[]{1}, new int[][]{{0}}
            ),
            new TestCoreApiFixture.Glues(
                new String[]{"GlueFace"}, new int[]{0}, new int[]{0},
                new int[]{1}, new int[][]{{0}}
            )
        );
        try (Harness harness = harness("5.3.02", coreModel)) {
            final CubismModel model = harness.access.active();
            final var part = model.parts().find(new PartId("PartRoot"));
            final var drawable = model.drawables().find(new ArtMeshId("ArtMeshFace"));
            final var deformer = model.deformers().find(new DeformerId("WarpRoot"));
            final var glue = model.glues().find(new GlueId("GlueFace"));

            assertEquals(0.75F, part.getOpacity());
            assertEquals(-1, part.parentIndex());
            assertEquals(BlendMode.ADDITIVE, drawable.blendMode());
            assertEquals(7, drawable.renderOrder());
            assertEquals(new Color(1.0F, 0.5F, 0.25F, 1.0F), drawable.multiplyColor());
            assertEquals(6, drawable.vertexPositions().size());
            assertEquals(2, drawable.indices().get(2));
            assertEquals(-1, deformer.parentDeformerIndex());
            assertEquals(0, deformer.parameters().get(0));
            assertEquals(0, glue.drawableA());
            assertEquals(0, glue.parameters().get(0));

            coreModel.getDrawables().vertexPositions()[0][0] = 99.0F;
            assertEquals(99.0F, drawable.vertexPositions().get(0));
            final var copied = drawable.vertexPositions();
            coreModel.getDrawables().vertexPositions()[0][0] = 12.0F;
            assertEquals(99.0F, copied.get(0));
            assertThrows(UnsupportedOperationException.class, () -> part.setOpacity(0.5F));
        }
    }

    @Test
    void exposesFixedCoreProjectionMetadataAndTypedRelations() {
        final TestCoreApiFixture.Model coreModel = model(
            new String[]{"ParamA", "ParamB"},
            new float[]{0.25F, 0.75F},
            new TestCoreApiFixture.Parts(
                new String[]{"PartRoot", "PartChild"}, new float[]{1.0F, 0.5F}, new int[]{-1, 0}
            ),
            new TestCoreApiFixture.Drawables(
                new String[]{"MeshA", "MeshB"}, new byte[]{4, 0}, new byte[]{127, 0}, new int[]{0, 2},
                new int[]{0, 1}, new int[]{4, 5}, new int[]{6, 7}, new float[]{1.0F, 0.8F},
                new int[]{1, 0}, new int[][]{{1}, {}}, new int[]{0, 0}, new float[][]{{}, {}},
                new float[][]{{}, {}}, new int[]{0, 0}, new short[][]{{}, {}},
                new float[][]{{1.0F, 1.0F, 1.0F, 1.0F}, {1.0F, 1.0F, 1.0F, 1.0F}},
                new float[][]{{0.0F, 0.0F, 0.0F, 1.0F}, {0.0F, 0.0F, 0.0F, 1.0F}},
                new int[]{0, 1}, new int[]{0, -1}, new int[]{2, 1}, new int[][]{{0, 1}, {1}}
            ),
            new TestCoreApiFixture.Deformers(
                new String[]{"DeformerA"}, new int[]{-1}, new int[]{2}, new int[][]{{0, 1}}
            ),
            new TestCoreApiFixture.Glues(
                new String[]{"GlueA"}, new int[]{0}, new int[]{1}, new int[]{2}, new int[][]{{0, 1}}
            )
        );
        coreModel.getParameters().getKeyCounts()[0] = 2;
        coreModel.getParameters().getKeyValues()[0] = new float[]{-1.0F, 1.0F};

        try (Harness harness = harness("5.3.02", coreModel)) {
            final CubismModel active = harness.access.active();
            final var parameter = active.parameters().find(new ParameterId("ParamA"));
            final var root = active.parts().find(new PartId("PartRoot"));
            final var child = active.parts().find(new PartId("PartChild"));
            final var drawable = active.drawables().find(new ArtMeshId("MeshA"));
            final var deformer = active.deformers().find(new DeformerId("DeformerA"));
            final var glue = active.glues().find(new GlueId("GlueA"));

            assertEquals(0, parameter.index());
            assertEquals(2, parameter.keyValues().size());
            assertEquals(-1.0F, parameter.keyValues().get(0));
            assertEquals(1.0F, parameter.keyValues().get(1));

            assertEquals(0, root.index());
            assertEquals(Optional.empty(), root.parentId());
            assertEquals(List.of(new PartId("PartChild")), root.childIds());
            assertEquals(1, child.index());
            assertEquals(Optional.of(new PartId("PartRoot")), child.parentId());
            assertEquals(List.of(), child.childIds());

            assertEquals(0, drawable.index());
            assertTrue(drawable.doubleSided());
            assertEquals(
                new dev.turboism.sdk.cubism.model.DrawableEvaluationState(true, true, true, true, true, true, true),
                drawable.evaluationState()
            );
            assertEquals(Optional.of(new PartId("PartRoot")), drawable.parentPartId());
            assertEquals(Optional.of(new DeformerId("DeformerA")), drawable.parentDeformerId());
            assertEquals(List.of(new ArtMeshId("MeshB")), drawable.maskIds());
            assertEquals(List.of(new ParameterId("ParamA"), new ParameterId("ParamB")), drawable.parameterIds());

            assertEquals(0, deformer.index());
            assertEquals(Optional.empty(), deformer.parentDeformerId());
            assertEquals(List.of(new ParameterId("ParamA"), new ParameterId("ParamB")), deformer.parameterIds());
            assertThrows(UnsupportedOperationException.class, deformer::parentPartId);

            assertEquals(0, glue.index());
            assertEquals(new ArtMeshId("MeshA"), glue.drawableAId());
            assertEquals(new ArtMeshId("MeshB"), glue.drawableBId());
            assertEquals(List.of(new ParameterId("ParamA"), new ParameterId("ParamB")), glue.parameterIds());
        }
    }

    @Test
    void failsClosedForUnknownDynamicFlagsAndFiveThreeBlendModes() {
        final TestCoreApiFixture.Model dynamicFlags = model(
            new String[0], new float[0], TestCoreApiFixture.Parts.empty(),
            drawableWithFlags((byte) 0, (byte) 0x80, 0),
            TestCoreApiFixture.Deformers.empty(), TestCoreApiFixture.Glues.empty()
        );
        try (Harness harness = harness("5.3.02", dynamicFlags)) {
            assertThrows(IllegalStateException.class, harness.access::active);
        }

        final TestCoreApiFixture.Model blendMode = model(
            new String[0], new float[0], TestCoreApiFixture.Parts.empty(),
            drawableWithFlags((byte) 0, (byte) 0, 3),
            TestCoreApiFixture.Deformers.empty(), TestCoreApiFixture.Glues.empty()
        );
        try (Harness harness = harness("5.3.02", blendMode)) {
            assertThrows(IllegalStateException.class, harness.access::active);
        }
    }

    @Test
    void normalizesFiveTwoBlendModeFromPublicConstantFlags() {
        final TestCoreApiFixture.Model coreModel = model(
            new String[]{"Additive", "Multiplicative", "Normal"},
            new float[]{0.0F, 0.0F, 0.0F},
            TestCoreApiFixture.Parts.empty(),
            new TestCoreApiFixture.Drawables(
                new String[]{"Additive", "Multiplicative", "Normal"},
                new byte[]{1, 2, 0}, new byte[]{0, 0, 0}, new int[]{0, 0, 0},
                new int[]{0, 0, 0}, new int[]{0, 1, 2}, new int[]{10, 11, 12},
                new float[]{1.0F, 1.0F, 1.0F},
                new int[]{0, 0, 0}, new int[][]{{}, {}, {}},
                new int[]{0, 0, 0}, new float[][]{{}, {}, {}}, new float[][]{{}, {}, {}},
                new int[]{0, 0, 0}, new short[][]{{}, {}, {}},
                new float[][]{
                    {1.0F, 1.0F, 1.0F, 1.0F},
                    {1.0F, 1.0F, 1.0F, 1.0F},
                    {1.0F, 1.0F, 1.0F, 1.0F}
                },
                new float[][]{
                    {0.0F, 0.0F, 0.0F, 1.0F},
                    {0.0F, 0.0F, 0.0F, 1.0F},
                    {0.0F, 0.0F, 0.0F, 1.0F}
                },
                new int[]{-1, -1, -1}, new int[]{-1, -1, -1},
                new int[]{0, 0, 0}, new int[][]{{}, {}, {}}
            ),
            TestCoreApiFixture.Deformers.empty(),
            TestCoreApiFixture.Glues.empty()
        );

        try (Harness harness = harness("5.2", coreModel)) {
            assertEquals(
                List.of(BlendMode.ADDITIVE, BlendMode.MULTIPLICATIVE, BlendMode.NORMAL),
                harness.access.active().drawables().all().stream()
                    .map(drawable -> drawable.blendMode())
                    .toList()
            );
        }
    }

    @Test
    void failsClosedForUnknownOrContradictoryFiveTwoBlendFlags() {
        for (byte constantFlag : new byte[]{0x10, 0x03}) {
            try (Harness harness = harness("5.2", model(
                new String[0],
                new float[0],
                TestCoreApiFixture.Parts.empty(),
                drawableWithConstantFlag(constantFlag),
                TestCoreApiFixture.Deformers.empty(),
                TestCoreApiFixture.Glues.empty()
            ))) {
                assertThrows(IllegalStateException.class, harness.access::active);
            }
        }
    }

    @Test
    void malformedCrossFamilyIndicesFailClosed() {
        final TestCoreApiFixture.Model invalid = model(
            new String[]{"ParamAngleX"}, new float[]{20.0F},
            TestCoreApiFixture.Parts.empty(),
            new TestCoreApiFixture.Drawables(
                new String[]{"Mesh"}, new byte[]{0}, new byte[]{0}, new int[]{0},
                new int[]{0}, new int[]{0}, new int[]{0}, new float[]{1.0F},
                new int[]{1}, new int[][]{{2}}, new int[]{0}, new float[][]{{}},
                new float[][]{{}}, new int[]{0}, new short[][]{{}},
                new float[][]{{1, 1, 1, 1}}, new float[][]{{0, 0, 0, 1}},
                new int[]{-1}, new int[]{-1}, new int[]{0}, new int[][]{{}}
            ),
            TestCoreApiFixture.Deformers.empty(), TestCoreApiFixture.Glues.empty()
        );
        try (Harness harness = harness("5.3.02", invalid)) {
            assertThrows(IllegalStateException.class, harness.access::active);
        }
    }

    @Test
    void unavailableProviderAndMissingModelFailExplicitly() {
        final VerifiedMemberResolver resolver = TestCoreApiFixture.resolver("5.2");
        final CorePublicApiProvider provider = CorePublicApiProviderFactory.admit(
            resolver,
            CoreVersionExpectation.exact(11, 12, 13)
        ).value().orElseThrow();
        final CoreStructuralTracer tracer = CoreStructuralTracerFactory.admit(
            provider,
            resolver
        ).value().orElseThrow();
        final BorrowedCoreModelSource source = new BorrowedCoreModelSource();
        final ActiveCoreModelSource unavailableSource = new ActiveCoreModelSource() {
            @Override
            public CoreModelAcquisition acquire(final CorePublicApiProvider ignored) {
                return CoreModelAcquisition.failed(
                    CoreModelFailure.Code.ADAPTER_UNAVAILABLE,
                    "Core adapter unavailable."
                );
            }

            @Override
            public void close() {
            }
        };
        try {
            assertTrue(assertThrows(
                IllegalStateException.class,
                () -> new CoreBackedCubismModelAccess(
                    unavailableSource,
                    provider,
                    tracer
                ).active()
            ).getMessage().contains("unavailable"));
            assertTrue(assertThrows(
                IllegalStateException.class,
                () -> new CoreBackedCubismModelAccess(source, provider, tracer).active()
            ).getMessage().contains("No verified active"));
        } finally {
            tracer.close();
            source.close();
        }
    }

    @Test
    void parameterReadsObserveCurrentCoreStateWithoutLeakingRawArrays() {
        final float[] values = {20.0F};
        try (Harness harness = harness("5.2", model(
            new String[]{"ParamAngleX"},
            values
        ))) {
            final Parameter parameter = harness.access.active()
                .parameters()
                .find(new ParameterId("ParamAngleX"));

            assertEquals(20.0F, parameter.getValue());
            assertEquals(Optional.empty(), parameter.repeat());
            values[0] = -12.5F;
            assertEquals(-12.5F, parameter.getValue());
        }
    }

    @Test
    void staleModelAndChildObjectsFailClosedAfterGenerationReplacement() {
        try (Harness harness = harness("5.3.02", model(
            new String[]{"ParamAngleX"},
            new float[]{20.0F}
        ))) {
            final CubismModel staleModel = harness.access.active();
            final Parameter staleParameter = staleModel.parameters().find(
                new ParameterId("ParamAngleX")
            );

            harness.source.publishBorrowedModel(
                model(new String[]{"ParamAngleX"}, new float[]{5.0F}),
                "model-b"
            );

            final IllegalStateException modelFailure = assertThrows(
                IllegalStateException.class,
                () -> staleModel.parameters().all()
            );
            final IllegalStateException parameterFailure = assertThrows(
                IllegalStateException.class,
                staleParameter::getValue
            );
            assertTrue(modelFailure.getMessage().contains("stale"));
            assertTrue(parameterFailure.getMessage().contains("stale"));
            assertEquals("model-b", harness.access.active().id().value());
        }
    }

    @Test
    void editorAttachedSettersAndUnsupportedCollectionsFailExplicitly() {
        try (Harness harness = harness("5.2", model(
            new String[]{"ParamAngleX"},
            new float[]{20.0F}
        ))) {
            final CubismModel model = harness.access.active();
            final Parameter parameter = model.parameters().find(
                new ParameterId("ParamAngleX")
            );

            assertThrows(
                UnsupportedOperationException.class,
                () -> parameter.setValue(10.0F)
            );
            assertThrows(UnsupportedOperationException.class, parameter::resetToDefault);
            assertThrows(UnsupportedOperationException.class, model::defaultKeyformLocked);
            assertThrows(
                UnsupportedOperationException.class,
                () -> model.setDefaultKeyformLocked(true)
            );
            assertThrows(UnsupportedOperationException.class, model::parameterGroups);
            assertThrows(UnsupportedOperationException.class, model::update);
            assertThrows(
                NoSuchElementException.class,
                () -> model.parts().find(new dev.turboism.sdk.cubism.model.PartId("PartA"))
            );
        }
    }

    private static Harness harness(
        final String artifactProfile,
        final TestCoreApiFixture.Model model
    ) {
        final VerifiedMemberResolver resolver =
            TestCoreApiFixture.resolver(artifactProfile);
        final CorePublicApiProvider provider = CorePublicApiProviderFactory.admit(
            resolver,
            CoreVersionExpectation.exact(11, 12, 13)
        ).value().orElseThrow();
        final CoreStructuralTracer tracer = CoreStructuralTracerFactory.admit(
            provider,
            resolver
        ).value().orElseThrow();
        final BorrowedCoreModelSource source = new BorrowedCoreModelSource();
        source.publishBorrowedModel(model, "model-a");
        return new Harness(
            new CoreBackedCubismModelAccess(source, provider, tracer),
            source,
            tracer
        );
    }

    private static TestCoreApiFixture.Drawables drawableWithConstantFlag(
        final byte constantFlag
    ) {
        return new TestCoreApiFixture.Drawables(
            new String[]{"Mesh"}, new byte[]{constantFlag}, new byte[]{0}, new int[]{0},
            new int[]{0}, new int[]{0}, new int[]{0}, new float[]{1.0F},
            new int[]{0}, new int[][]{{}}, new int[]{0}, new float[][]{{}},
            new float[][]{{}}, new int[]{0}, new short[][]{{}},
            new float[][]{{1.0F, 1.0F, 1.0F, 1.0F}},
            new float[][]{{0.0F, 0.0F, 0.0F, 1.0F}},
            new int[]{-1}, new int[]{-1}, new int[]{0}, new int[][]{{}}
        );
    }
    private static TestCoreApiFixture.Drawables drawableWithFlags(
        final byte constantFlag,
        final byte dynamicFlag,
        final int blendMode
    ) {
        return new TestCoreApiFixture.Drawables(
            new String[]{"Mesh"}, new byte[]{constantFlag}, new byte[]{dynamicFlag}, new int[]{blendMode}, new int[]{0},
            new int[]{0}, new int[]{0}, new float[]{1.0F}, new int[]{0}, new int[][]{{}}, new int[]{0},
            new float[][]{{}}, new float[][]{{}}, new int[]{0}, new short[][]{{}},
            new float[][]{{1.0F, 1.0F, 1.0F, 1.0F}}, new float[][]{{0.0F, 0.0F, 0.0F, 1.0F}},
            new int[]{-1}, new int[]{-1}, new int[]{0}, new int[][]{{}}
        );
    }

    private static TestCoreApiFixture.Model model(
        final String[] ids,
        final float[] values
    ) {
        return model(ids, values, TestCoreApiFixture.Parts.empty(),
            TestCoreApiFixture.Drawables.empty(), TestCoreApiFixture.Deformers.empty(),
            TestCoreApiFixture.Glues.empty());
    }

    private static TestCoreApiFixture.Model model(
        final String[] ids,
        final float[] values,
        final TestCoreApiFixture.Parts parts,
        final TestCoreApiFixture.Drawables drawables,
        final TestCoreApiFixture.Deformers deformers,
        final TestCoreApiFixture.Glues glues
    ) {
        final int count = ids.length;
        final TestCoreApiFixture.ParameterType[] types =
            new TestCoreApiFixture.ParameterType[count];
        final float[] minimumValues = new float[count];
        final float[] maximumValues = new float[count];
        final float[] defaultValues = new float[count];
        final int[] keyCounts = new int[count];
        final float[][] keyValues = new float[count][];
        final boolean[] repeats = new boolean[count];
        for (int index = 0; index < count; index++) {
            types[index] = new TestCoreApiFixture.ParameterType(index);
            minimumValues[index] = -30.0F;
            maximumValues[index] = 30.0F;
            defaultValues[index] = 0.0F;
            keyCounts[index] = 0;
            keyValues[index] = new float[0];
        }
        return new TestCoreApiFixture.Model(
            new TestCoreApiFixture.CanvasInfo(
                new float[]{1000.0F, 500.0F},
                new float[]{500.0F, 250.0F},
                100.0F
            ),
            new TestCoreApiFixture.Parameters(
                ids,
                types,
                minimumValues,
                maximumValues,
                defaultValues,
                values,
                keyCounts,
                keyValues,
                repeats
            ),
            parts,
            drawables,
            deformers,
            glues,
            () -> { }
        );
    }

    private static final class Harness implements AutoCloseable {

        private final CoreBackedCubismModelAccess access;
        private final BorrowedCoreModelSource source;
        private final CoreStructuralTracer tracer;

        private Harness(
            final CoreBackedCubismModelAccess access,
            final BorrowedCoreModelSource source,
            final CoreStructuralTracer tracer
        ) {
            this.access = access;
            this.source = source;
            this.tracer = tracer;
        }

        @Override
        public void close() {
            tracer.close();
            source.close();
        }
    }
}
