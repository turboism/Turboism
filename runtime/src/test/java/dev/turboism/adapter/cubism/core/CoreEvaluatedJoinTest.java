package dev.turboism.adapter.cubism.core;

import dev.turboism.mapping.verification.selector.CoreMocInfoSelectorContract;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.core.MocConsistency;
import dev.turboism.sdk.cubism.core.MocInfo;
import dev.turboism.sdk.cubism.core.MocVersion;
import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Color;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreEvaluatedJoinTest {

    @BeforeEach
    void resetVersion() {
        TestCoreApiFixture.resetVersion();
    }

    @Test
    void bulkSnapshotsOncePerIdentityAndJoinsDrawablesByStableId() {
        final AtomicInteger canvasReads = new AtomicInteger();
        final TestCoreApiFixture.Model coreModel = model(
            canvasReads,
            new TestCoreApiFixture.Drawables(
                new String[]{"ArtMeshFace"}, new byte[]{(byte) 0x04}, new byte[]{(byte) 0x21}, new int[]{1},
                new int[]{3}, new int[]{7}, new int[]{11}, new float[]{0.8F},
                new int[]{0}, new int[][]{{}}, new int[]{3},
                new float[][]{{0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F}},
                new float[][]{{0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F}},
                new int[]{3}, new short[][]{{0, 1, 2}},
                new float[][]{{1.0F, 0.5F, 0.25F, 1.0F}},
                new float[][]{{0.0F, 0.1F, 0.2F, 1.0F}},
                new int[]{-1}, new int[]{-1}, new int[]{1}, new int[][]{{0}}
            )
        );
        try (Harness harness = harness("5.3.02", coreModel)) {
            final CoreEvaluatedJoin join = harness.join();

            final CoreEvaluatedJoin.CoreEvaluatedSnapshot first =
                join.evaluated("identity-a");
            assertEquals(1, canvasReads.get(), "first identity takes exactly one bulk snapshot");
            final CoreDrawableDefinition drawable = first.drawable("ArtMeshFace");
            assertEquals(0x04, Byte.toUnsignedInt(drawable.constantFlag()));
            assertEquals(0x21, Byte.toUnsignedInt(drawable.dynamicFlag()));
            assertEquals(BlendMode.ADDITIVE, drawable.blendMode());
            assertEquals(3, drawable.textureIndex());
            assertEquals(7, drawable.drawOrder());
            assertEquals(11, drawable.renderOrder());
            assertEquals(0.8F, drawable.opacity());
            assertEquals(new Color(1.0F, 0.5F, 0.25F, 1.0F), drawable.multiplyColor());
            assertEquals(new Color(0.0F, 0.1F, 0.2F, 1.0F), drawable.screenColor());

            // Same identity: O(1) generation re-validation only, no re-trace.
            join.evaluated("identity-a").drawable("ArtMeshFace");
            assertEquals(1, canvasReads.get(), "same identity reuses the pinned snapshot");

            // Different identity takes a fresh bulk snapshot.
            join.evaluated("identity-b").drawable("ArtMeshFace");
            assertEquals(2, canvasReads.get(), "new identity takes one fresh bulk snapshot");
        }
    }

    @Test
    void missingDrawableIdFailsClosed() {
        final TestCoreApiFixture.Model coreModel = model(
            new AtomicInteger(),
            new TestCoreApiFixture.Drawables(
                new String[]{"ArtMeshFace"}, new byte[]{0}, new byte[]{0}, new int[]{0},
                new int[]{0}, new int[]{0}, new int[]{0}, new float[]{1.0F},
                new int[]{0}, new int[][]{{}}, new int[]{0}, new float[][]{{}},
                new float[][]{{}}, new int[]{0}, new short[][]{{}},
                new float[][]{{1.0F, 1.0F, 1.0F, 1.0F}},
                new float[][]{{0.0F, 0.0F, 0.0F, 1.0F}},
                new int[]{-1}, new int[]{-1}, new int[]{0}, new int[][]{{}}
            )
        );
        try (Harness harness = harness("5.3.02", coreModel)) {
            assertThrows(
                NoSuchElementException.class,
                () -> harness.join().evaluated("identity-a").drawable("Missing")
            );
        }
    }

    @Test
    void coreGenerationChangeFailsClosedForPinnedIdentityInsteadOfSilentRefetch() {
        final AtomicInteger canvasReads = new AtomicInteger();
        final TestCoreApiFixture.Model first = model(canvasReads, drawableWithFlag((byte) 0x04));
        final TestCoreApiFixture.Model second = model(canvasReads, drawableWithFlag((byte) 0x08));
        try (Harness harness = harness("5.3.02", first)) {
            final CoreEvaluatedJoin join = harness.join();
            assertEquals(0x04, Byte.toUnsignedInt(
                join.evaluated("identity-a").drawable("ArtMeshFace").constantFlag()
            ));
            assertEquals(1, canvasReads.get());

            harness.source.publishBorrowedModel(second, "model-b");

            final IllegalStateException stale = assertThrows(
                IllegalStateException.class,
                () -> join.evaluated("identity-a").drawable("ArtMeshFace")
            );
            assertTrue(stale.getMessage().contains("stale"), stale.getMessage());
            assertEquals(1, canvasReads.get(), "pinned identity never silently re-snapshots");

            // A fresh identity for the new generation snapshots the new data.
            assertEquals(0x08, Byte.toUnsignedInt(
                join.evaluated("identity-b").drawable("ArtMeshFace").constantFlag()
            ));
            assertEquals(2, canvasReads.get());
        }
    }

    @Test
    void evaluatedFailsClosedWhenNoCoreModelIsPublished() {
        final AtomicInteger canvasReads = new AtomicInteger();
        final TestCoreApiFixture.Model coreModel = model(canvasReads, drawableWithFlag((byte) 0x04));
        try (Harness harness = harness("5.3.02", coreModel)) {
            final CoreEvaluatedJoin join = harness.join();
            join.evaluated("identity-a");
            harness.source.clearBorrowedModel();
            assertThrows(IllegalStateException.class, () -> join.evaluated("identity-a"));
        }
    }

    @Test
    void duplicateDrawableIdsFailClosed() {
        final AtomicInteger canvasReads = new AtomicInteger();
        final TestCoreApiFixture.Model coreModel = model(
            canvasReads,
            new TestCoreApiFixture.Drawables(
                new String[]{"ArtMeshFace", "ArtMeshFace"}, new byte[]{(byte) 0, (byte) 0}, new byte[]{(byte) 0, (byte) 0},
                new int[]{0, 0}, new int[]{0, 0}, new int[]{0, 0}, new int[]{0, 0},
                new float[]{1.0F, 1.0F}, new int[]{0, 0}, new int[][]{{}, {}},
                new int[]{0, 0}, new float[][]{{}, {}}, new float[][]{{}, {}},
                new int[]{0, 0}, new short[][]{{}, {}},
                new float[][]{{1.0F, 1.0F, 1.0F, 1.0F}, {1.0F, 1.0F, 1.0F, 1.0F}},
                new float[][]{{0.0F, 0.0F, 0.0F, 1.0F}, {0.0F, 0.0F, 0.0F, 1.0F}},
                new int[]{-1, -1}, new int[]{-1, -1}, new int[]{0, 0}, new int[][]{{}, {}}
            )
        );
        try (Harness harness = harness("5.3.02", coreModel)) {
            final IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> harness.join().evaluated("identity-a")
            );
            assertTrue(failure.getMessage().contains("stale") || failure.getMessage().contains("duplicate")
                || failure.getMessage().contains("unavailable"), failure.getMessage());
        }
    }

    @Test
    void mocInfoReportsVersionOn5302AndFailsClosedWithoutMocEvidence() {
        final AtomicInteger canvasReads = new AtomicInteger();
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
            canvasReads::incrementAndGet
        );
        try (Harness harness = harness("5.3.02", coreModel)) {
            final MocInfo info = harness.join().mocInfo();
            assertEquals(MocVersion.V5_3, info.version());
            assertEquals(MocConsistency.UNKNOWN, info.consistency());
        }
    }

    @Test
    void mocInfoFailsClosedOn52ProfileAndOnMissingMocInstance() {
        final AtomicInteger canvasReads = new AtomicInteger();
        final TestCoreApiFixture.Model withMoc = new TestCoreApiFixture.Model(
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
            canvasReads::incrementAndGet
        );
        try (Harness harness = harness("5.2", withMoc)) {
            final IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> harness.join().mocInfo()
            );
            assertTrue(failure.getMessage().contains("unavailable"), failure.getMessage());
        }

        final TestCoreApiFixture.Model withoutMoc = model(
            new AtomicInteger(), drawableWithFlag((byte) 0x04)
        );
        try (Harness harness = harness("5.3.02", withoutMoc)) {
            assertThrows(IllegalStateException.class, () -> harness.join().mocInfo());
        }
    }

    @Test
    void mocVersionConstantMappingCoversTheReviewedDomain() {
        final AtomicInteger canvasReads = new AtomicInteger();
        for (int constant = 0; constant <= 6; constant++) {
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
                new TestCoreApiFixture.Moc(constant),
                canvasReads::incrementAndGet
            );
            try (Harness harness = harness("5.3.02", coreModel)) {
                assertEquals(
                    MocVersion.values()[constant],
                    harness.join().mocInfo().version(),
                    "Core constant " + constant
                );
            }
        }
        assertThrows(
            IllegalStateException.class,
            () -> mocVersionForUnsupportedConstant()
        );
    }

    private static MocVersion mocVersionForUnsupportedConstant() {
        final AtomicInteger canvasReads = new AtomicInteger();
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
            new TestCoreApiFixture.Moc(7),
            canvasReads::incrementAndGet
        );
        try (Harness harness = harness("5.3.02", coreModel)) {
            return harness.join().mocInfo().version();
        }
    }

    private static TestCoreApiFixture.Drawables drawableWithFlag(final byte constantFlag) {
        return new TestCoreApiFixture.Drawables(
            new String[]{"ArtMeshFace"}, new byte[]{constantFlag}, new byte[]{0}, new int[]{0},
            new int[]{0}, new int[]{0}, new int[]{0}, new float[]{1.0F},
            new int[]{0}, new int[][]{{}}, new int[]{0}, new float[][]{{}},
            new float[][]{{}}, new int[]{0}, new short[][]{{}},
            new float[][]{{1.0F, 1.0F, 1.0F, 1.0F}},
            new float[][]{{0.0F, 0.0F, 0.0F, 1.0F}},
            new int[]{-1}, new int[]{-1}, new int[]{0}, new int[][]{{}}
        );
    }

    private static TestCoreApiFixture.Model model(
        final AtomicInteger canvasReads,
        final TestCoreApiFixture.Drawables drawables
    ) {
        return new TestCoreApiFixture.Model(
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
            drawables,
            TestCoreApiFixture.Deformers.empty(),
            TestCoreApiFixture.Glues.empty(),
            canvasReads::incrementAndGet
        );
    }

    private static Harness harness(
        final String artifactProfile,
        final TestCoreApiFixture.Model model
    ) {
        final VerifiedMemberResolver resolver = resolver(artifactProfile);
        final CoreProviderResult<CorePublicApiProvider> admission =
            CorePublicApiProviderFactory.admitForTesting(
                resolver,
                CoreVersionExpectation.exact(11, 12, 13)
            );
        if (admission.value().isEmpty()) {
            throw new IllegalStateException(
                "admission failed: " + admission.failure().orElseThrow()
            );
        }
        final CorePublicApiProvider provider = admission.value().orElseThrow();
        final CoreStructuralTracer tracer = CoreStructuralTracerFactory.admit(
            provider,
            resolver
        ).value().orElseThrow();
        final BorrowedCoreModelSource source = new BorrowedCoreModelSource();
        source.publishBorrowedModel(model, "model-a");
        return new Harness(source, provider, tracer);
    }

    private static VerifiedMemberResolver resolver(final String artifactProfile) {
        final List<StaticSelector> mocSelectors = new ArrayList<>();
        mocSelectors.add(StaticSelector.method(
            CoreMocInfoSelectorContract.MODEL_GET_MOC,
            internalName(TestCoreApiFixture.Model.class),
            "getMoc",
            "()L" + internalName(TestCoreApiFixture.Moc.class) + ";",
            StaticSelector.ACCESS_PUBLIC
        ));
        mocSelectors.add(StaticSelector.classSelector(
            CoreMocInfoSelectorContract.MOC_CLASS,
            internalName(TestCoreApiFixture.Moc.class)
        ));
        if ("5.3.02".equals(artifactProfile)) {
            mocSelectors.add(StaticSelector.method(
                CoreMocInfoSelectorContract.MOC_GET_MOC_VERSION,
                internalName(TestCoreApiFixture.Moc.class),
                "getMocVersion",
                "()I",
                StaticSelector.ACCESS_PUBLIC
            ));
        }
        return TestCoreApiFixture.resolverWithExtras(
            artifactProfile,
            mocSelectors,
            java.util.Set.of(CoreMocInfoSelectorContract.CAPABILITY_ID)
        );
    }

    private static String internalName(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private record Harness(
        BorrowedCoreModelSource source,
        CorePublicApiProvider provider,
        CoreStructuralTracer tracer
    ) implements AutoCloseable {

        CoreEvaluatedJoin join() {
            return new CoreEvaluatedJoin(source, provider, tracer);
        }

        @Override
        public void close() {
            tracer.close();
            source.close();
        }
    }
}
