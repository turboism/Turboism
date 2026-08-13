package dev.turboism.adapter.cubism.core;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.core.MocConsistency;
import dev.turboism.sdk.cubism.core.MocData;
import dev.turboism.sdk.cubism.core.MocLoader;
import dev.turboism.sdk.cubism.core.MocVersion;
import dev.turboism.sdk.cubism.core.OwnedDrawable;
import dev.turboism.sdk.cubism.core.OwnedMoc;
import dev.turboism.sdk.cubism.core.OwnedModel;
import dev.turboism.sdk.cubism.model.BlendMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnedMocRuntimeTest {

    @BeforeEach
    void resetSyntheticCore() {
        TestCoreApiFixture.resetVersion();
        TestCoreApiFixture.Moc.prepare(null, 0L);
    }

    private MocLoader loader(final String artifactProfile) {
        final VerifiedMemberResolver resolver = TestCoreApiFixture.resolverWithExtras(
            artifactProfile,
            TestCoreApiFixture.ownedMocSelectors(),
            TestCoreApiFixture.ownedMocCapability()
        );
        return new CoreRuntimeMetadata(
            CorePublicApiProviderFactory.admitForTesting(
                resolver,
                CoreVersionExpectation.exact(11, 12, 13)
            ).value().orElseThrow(),
            resolver,
            () -> { },
            1024
        ).mocLoader();
    }

    @Test
    void loadsOwnedMocWithByteLevelDiagnostics() {
        final MocLoader loader = loader("5.3.02");
        final TestCoreApiFixture.Model model = modelWithCanvas();
        TestCoreApiFixture.Moc.prepare(model, 42L);

        final OwnedMoc moc = loader.load(MocData.copyOf(new byte[]{6, 1}));

        assertEquals(MocVersion.V5_3, moc.version());
        assertEquals(MocConsistency.CONSISTENT, moc.consistency());
        assertEquals(42L, moc.nativeHandle());
    }

    @Test
    void routesVersionByBytesOnThe52Profile() {
        final MocLoader loader = loader("5.2");
        TestCoreApiFixture.Moc.prepare(modelWithCanvas(), 7L);

        final OwnedMoc moc = loader.load(MocData.copyOf(new byte[]{5, 0}));

        assertEquals(MocVersion.V5_0, moc.version());
        assertEquals(MocConsistency.INCONSISTENT, moc.consistency());
        assertEquals(7L, moc.nativeHandle());
    }

    @Test
    void instantiatesModelAndReadsEvaluatedSurface() {
        final MocLoader loader = loader("5.3.02");
        final TestCoreApiFixture.Model model = modelWithCanvas();
        TestCoreApiFixture.Moc.prepare(model, 42L);

        final OwnedModel owned = loader
            .load(MocData.copyOf(new byte[]{6, 1}))
            .instantiateModel();

        assertEquals(42L, owned.nativeHandle());
        assertEquals(512f, owned.canvasInfo().widthPixels());
        assertEquals(512f, owned.canvasInfo().heightPixels());
        assertEquals(1f, owned.canvasInfo().pixelsPerUnit());

        final var parameter = owned.parameters().get(0);
        assertEquals("ParamA", parameter.id());
        assertEquals(0, parameter.typeNumber());
        assertEquals(-30f, parameter.minimumValue());
        assertEquals(30f, parameter.maximumValue());
        assertEquals(1.5f, parameter.currentValue());
        assertTrue(parameter.repeat().orElseThrow());

        final var part = owned.parts().get(0);
        assertEquals("PartA", part.id());
        assertEquals(1f, part.opacity());

        final OwnedDrawable drawable = owned.drawables().get(0);
        assertEquals("DrawableA", drawable.id());
        assertEquals(BlendMode.ADDITIVE, drawable.blendMode());
        assertEquals(2, drawable.textureIndex());
        assertEquals(0.5f, drawable.opacity());
        assertEquals(8, drawable.vertexPositions().size());
        assertEquals(0f, drawable.vertexPositions().get(0));
        assertEquals(1f, drawable.vertexPositions().get(1));
        assertEquals(7f, drawable.vertexPositions().get(7));
        assertEquals(1f, drawable.multiplyColor().red());
        assertEquals(0f, drawable.multiplyColor().green());
        assertEquals(0f, drawable.multiplyColor().blue());
        assertEquals(1f, drawable.screenColor().alpha());
        assertEquals(0, drawable.parentPartIndex());
        assertEquals(-1, drawable.parentDeformerIndex());

        final var deformer = owned.deformers().get(0);
        assertEquals("DeformerA", deformer.id());
        assertEquals(List.of(0), deformer.parameters());

        final var glue = owned.glues().get(0);
        assertEquals("GlueA", glue.id());
        assertEquals(0, glue.drawableA());
        assertEquals(0, glue.drawableB());
    }

    @Test
    void fiveTwoDerivesBlendModesFromConstantFlags() {
        final MocLoader loader = loader("5.2");
        final TestCoreApiFixture.Model model = model(
            new TestCoreApiFixture.Drawables(
                new String[]{"A", "B", "C"},
                new byte[]{1, 2, 0},  // ADDITIVE, MULTIPLICATIVE, NORMAL
                new byte[]{0, 0, 0},
                new int[0],           // 5.2 has no getBlendModes
                new int[]{0, 0, 0},
                new int[]{0, 1, 2},
                new int[]{0, 1, 2},
                new float[]{1, 1, 1},
                new int[3],
                new int[3][0],
                new int[3],
                new float[3][0],
                new float[3][0],
                new int[3],
                new short[3][0],
                new float[3][4],
                new float[3][4],
                new int[]{-1, -1, -1},
                new int[]{-1, -1, -1},
                new int[3],
                new int[3][0]
            )
        );
        TestCoreApiFixture.Moc.prepare(model, 1L);

        final OwnedModel owned = loader
            .load(MocData.copyOf(new byte[]{5, 1}))
            .instantiateModel();

        final List<OwnedDrawable> drawables = owned.drawables();
        assertEquals(BlendMode.ADDITIVE, drawables.get(0).blendMode());
        assertEquals(BlendMode.MULTIPLICATIVE, drawables.get(1).blendMode());
        assertEquals(BlendMode.NORMAL, drawables.get(2).blendMode());
    }

    @Test
    void contradictoryFiveTwoBlendFlagsFailClosed() {
        final MocLoader loader = loader("5.2");
        final TestCoreApiFixture.Model model = model(
            new TestCoreApiFixture.Drawables(
                new String[]{"A"},
                new byte[]{3},  // ADDITIVE|MULTIPLICATIVE is contradictory
                new byte[]{0},
                new int[0],
                new int[]{0},
                new int[]{0},
                new int[]{0},
                new float[]{1},
                new int[1],
                new int[1][0],
                new int[1],
                new float[1][0],
                new float[1][0],
                new int[1],
                new short[1][0],
                new float[1][4],
                new float[1][4],
                new int[]{-1},
                new int[]{-1},
                new int[1],
                new int[1][0]
            )
        );
        TestCoreApiFixture.Moc.prepare(model, 1L);

        final OwnedModel owned = loader
            .load(MocData.copyOf(new byte[]{5, 1}))
            .instantiateModel();

        assertThrows(IllegalStateException.class, owned::drawables);
    }

    @Test
    void fiveThreeReadsBlendModesDirectly() {
        final MocLoader loader = loader("5.3.02");
        final TestCoreApiFixture.Model model = model(
            new TestCoreApiFixture.Drawables(
                new String[]{"A", "B", "C"},
                new byte[]{0, 0, 0},
                new byte[]{0, 0, 0},
                new int[]{0, 1, 2},       // NORMAL, ADDITIVE, MULTIPLICATIVE
                new int[]{0, 0, 0},
                new int[]{0, 1, 2},
                new int[]{0, 1, 2},
                new float[]{1, 1, 1},
                new int[3],
                new int[3][0],
                new int[3],
                new float[3][0],
                new float[3][0],
                new int[3],
                new short[3][0],
                new float[3][4],
                new float[3][4],
                new int[]{-1, -1, -1},
                new int[]{-1, -1, -1},
                new int[3],
                new int[3][0]
            )
        );
        TestCoreApiFixture.Moc.prepare(model, 1L);

        final OwnedModel owned = loader
            .load(MocData.copyOf(new byte[]{6, 1}))
            .instantiateModel();

        final List<OwnedDrawable> drawables = owned.drawables();
        assertEquals(BlendMode.NORMAL, drawables.get(0).blendMode());
        assertEquals(BlendMode.ADDITIVE, drawables.get(1).blendMode());
        assertEquals(BlendMode.MULTIPLICATIVE, drawables.get(2).blendMode());
    }

    @Test
    void updateRunsCoreEvaluationAndCloseReleasesBothInstances() {
        final MocLoader loader = loader("5.3.02");
        final TestCoreApiFixture.Model model = modelWithCanvas();
        TestCoreApiFixture.Moc.prepare(model, 42L);

        final OwnedMoc moc = loader.load(MocData.copyOf(new byte[]{6, 1}));
        final OwnedModel owned = moc.instantiateModel();

        assertEquals(0, model.updateCount());
        owned.update();
        assertEquals(1, model.updateCount());
        owned.update();
        assertEquals(2, model.updateCount());

        assertEquals(0, model.closeCount());
        owned.close();
        assertEquals(1, model.closeCount());

        final TestCoreApiFixture.Moc rawMoc = TestCoreApiFixture.Moc.lastInstantiated();
        assertEquals(0, rawMoc.closeCount());
        moc.close();
        assertEquals(1, rawMoc.closeCount());
        // Double close is idempotent.
        owned.close();
        moc.close();
        assertEquals(1, model.closeCount());
        assertEquals(1, rawMoc.closeCount());

        // Reads after close fail closed.
        assertThrows(IllegalStateException.class, owned::drawables);
        assertThrows(IllegalStateException.class, owned::update);
    }

    @Test
    void loadFailsClosedWithoutOwnedMocEvidence() {
        final VerifiedMemberResolver resolver = TestCoreApiFixture.resolverWithExtras(
            "5.3.02",
            List.of(),
            java.util.Set.of()
        );
        final CoreRuntimeMetadata metadata = new CoreRuntimeMetadata(
            CorePublicApiProviderFactory.admitForTesting(
                resolver,
                CoreVersionExpectation.exact(11, 12, 13)
            ).value().orElseThrow(),
            resolver,
            () -> { },
            1024
        );

        assertThrows(UnsupportedOperationException.class, metadata::mocLoader);
    }

    @Test
    void loadFailsClosedWithoutResolver() {
        final CoreRuntimeMetadata metadata = new CoreRuntimeMetadata(
            CorePublicApiProvider.safeMode()
        );
        assertThrows(UnsupportedOperationException.class, metadata::mocLoader);
    }

    @Test
    void oversizedMocDataFailsBeforeProviderCalls() {
        final VerifiedMemberResolver resolver = TestCoreApiFixture.resolverWithExtras(
            "5.3.02",
            TestCoreApiFixture.ownedMocSelectors(),
            TestCoreApiFixture.ownedMocCapability()
        );
        final CoreRuntimeMetadata metadata = new CoreRuntimeMetadata(
            CorePublicApiProviderFactory.admitForTesting(
                resolver,
                CoreVersionExpectation.exact(11, 12, 13)
            ).value().orElseThrow(),
            resolver,
            () -> { },
            2
        );

        assertThrows(IllegalArgumentException.class, () ->
            metadata.mocLoader().load(MocData.copyOf(new byte[]{6, 1, 2}))
        );
    }

    @Test
    void ownedSurfaceNeverExposesCoreWrites() {
        final Class<?> ownedModel = OwnedModel.class;
        final Class<?> ownedMoc = OwnedMoc.class;
        for (var method : ownedModel.getMethods()) {
            assertFalse(method.getName().startsWith("set"),
                "OwnedModel must not expose Core writes: " + method);
        }
        for (var method : ownedMoc.getMethods()) {
            assertFalse(method.getName().startsWith("set"),
                "OwnedMoc must not expose Core writes: " + method);
        }
    }

    private static TestCoreApiFixture.Model modelWithCanvas() {
        return model(new TestCoreApiFixture.Drawables(
            new String[]{"DrawableA"},
            new byte[]{1},
            new byte[]{0},
            new int[]{1},
            new int[]{2},
            new int[]{0},
            new int[]{0},
            new float[]{0.5f},
            new int[]{0},
            new int[1][0],
            new int[]{4},
            new float[][]{{0, 1, 2, 3, 4, 5, 6, 7}},
            new float[][]{{0, 0, 0, 0, 0, 0, 0, 0}},
            new int[]{6},
            new short[][]{{0, 1, 2, 0, 2, 3}},
            new float[][]{{1, 0, 0, 1}},
            new float[][]{{0, 0, 0, 1}},
            new int[]{0},
            new int[]{-1},
            new int[]{1},
            new int[][]{{0}}
        ));
    }

    private static TestCoreApiFixture.Model model(final TestCoreApiFixture.Drawables drawables) {
        return new TestCoreApiFixture.Model(
            new TestCoreApiFixture.CanvasInfo(
                new float[]{512f, 512f},
                new float[]{256f, 256f},
                1f
            ),
            new TestCoreApiFixture.Parameters(
                new String[]{"ParamA"},
                new TestCoreApiFixture.ParameterType[]{new TestCoreApiFixture.ParameterType(0)},
                new float[]{-30f},
                new float[]{30f},
                new float[]{0f},
                new float[]{1.5f},
                new int[]{0},
                new float[1][0],
                new boolean[]{true}
            ),
            new TestCoreApiFixture.Parts(
                new String[]{"PartA"},
                new float[]{1f},
                new int[]{-1}
            ),
            drawables,
            new TestCoreApiFixture.Deformers(
                new String[]{"DeformerA"},
                new int[]{-1},
                new int[]{1},
                new int[][]{{0}}
            ),
            new TestCoreApiFixture.Glues(
                new String[]{"GlueA"},
                new int[]{0},
                new int[]{0},
                new int[]{1},
                new int[][]{{0}}
            ),
            null,
            () -> { },
            42L
        );
    }
}
