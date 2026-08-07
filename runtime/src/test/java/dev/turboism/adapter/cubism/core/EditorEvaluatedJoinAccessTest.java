package dev.turboism.adapter.cubism.core;

import dev.turboism.adapter.cubism.editor.EditorBackedCubismModelAccess;
import dev.turboism.mapping.verification.EditorObjectReadSelectorContract;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.DrawableEvaluationState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Editor-backed evaluated join: Editor object views overlay Core evaluated
 * fields (blend mode, flags, texture, render order, colors) through the
 * generation-bound join, failing closed on staleness or absence.
 */
class EditorEvaluatedJoinAccessTest {

    @Test
    void editorDrawableOverlaysCoreEvaluatedFieldsThroughTheJoin() {
        final AtomicInteger canvasReads = new AtomicInteger();
        final TestCoreApiFixture.Model coreModel = coreModel(canvasReads, 0x04, 0x21, 1, 3, 11);
        final Fixture editor = new Fixture();

        try (Harness harness = harness("5.3.02", coreModel)) {
            final var access = new EditorBackedCubismModelAccess(
                editor.resolver, "session-a", harness.join
            );
            final Drawable mesh = access.active().drawables().find(new ArtMeshId("ArtMeshFace"));

            assertEquals(0x04, Byte.toUnsignedInt(mesh.constantFlag()));
            assertEquals(0x21, Byte.toUnsignedInt(mesh.dynamicFlag()));
            assertEquals(BlendMode.ADDITIVE, mesh.blendMode());
            assertEquals(3, mesh.textureIndex());
            assertEquals(11, mesh.renderOrder());
            assertEquals(1.0F, mesh.multiplyColor().red());
            assertEquals(0.5F, mesh.multiplyColor().green());
            assertEquals(0.1F, mesh.screenColor().green());
            assertEquals(1, canvasReads.get(), "one bulk snapshot serves the whole generation");

            // A second SDK object in the same generation reuses the snapshot.
            final Drawable again = access.active().drawables().find(new ArtMeshId("ArtMeshFace"));
            assertEquals(0x04, Byte.toUnsignedInt(again.constantFlag()));
            assertEquals(1, canvasReads.get());
        }
    }

    @Test
    void editorEvaluatedReadsFailClosedWithoutTheJoin() {
        final Fixture editor = new Fixture();
        final var access = new EditorBackedCubismModelAccess(editor.resolver, "session-a");
        final Drawable mesh = access.active().drawables().find(new ArtMeshId("ArtMeshFace"));
        assertThrows(IllegalStateException.class, mesh::constantFlag);
    }

    @Test
    void editorEvaluatedReadsFailClosedWhenTheCoreGenerationMoves() {
        final AtomicInteger canvasReads = new AtomicInteger();
        final TestCoreApiFixture.Model first = coreModel(canvasReads, 0x04, 0x21, 1, 3, 11);
        final TestCoreApiFixture.Model second = coreModel(canvasReads, 0x08, 0x21, 1, 3, 11);
        final Fixture editor = new Fixture();

        try (Harness harness = harness("5.3.02", first)) {
            final var access = new EditorBackedCubismModelAccess(
                editor.resolver, "session-a", harness.join
            );
            final CubismModel model = access.active();
            final Drawable mesh = model.drawables().find(new ArtMeshId("ArtMeshFace"));
            assertEquals(0x04, Byte.toUnsignedInt(mesh.constantFlag()));

            harness.source.publishBorrowedModel(second, "model-b");

            final IllegalStateException stale = assertThrows(
                IllegalStateException.class,
                mesh::constantFlag
            );
            assertTrue(stale.getMessage().contains("stale"), stale.getMessage());
        }
    }

    @Test
    void editorDrawableEvaluationStateDerivesBooleansFromDynamicFlags() {
        final AtomicInteger canvasReads = new AtomicInteger();
        final TestCoreApiFixture.Model coreModel = coreModel(canvasReads, 0x04, 0x21, 1, 3, 11);
        final Fixture editor = new Fixture();

        try (Harness harness = harness("5.3.02", coreModel)) {
            final var access = new EditorBackedCubismModelAccess(
                editor.resolver, "session-a", harness.join
            );
            final Drawable mesh = access.active().drawables().find(new ArtMeshId("ArtMeshFace"));

            final DrawableEvaluationState state = mesh.evaluationState();
            assertTrue(state.evaluatedVisible(), "0x01");
            assertFalse(state.visibilityChanged(), "0x02");
            assertFalse(state.opacityChanged(), "0x04");
            assertFalse(state.drawOrderChanged(), "0x08");
            assertFalse(state.renderOrderChanged(), "0x10");
            assertTrue(state.vertexPositionsChanged(), "0x20");
            assertFalse(state.blendColorChanged(), "0x40");
        }
    }

    @Test
    void editorDrawableEvaluationStateFailsClosedWithoutTheJoin() {
        final Fixture editor = new Fixture();
        final var access = new EditorBackedCubismModelAccess(editor.resolver, "session-a");
        final Drawable mesh = access.active().drawables().find(new ArtMeshId("ArtMeshFace"));

        final IllegalStateException failure = assertThrows(IllegalStateException.class, mesh::evaluationState);
        assertTrue(failure.getMessage().contains("Core evaluated"), failure.getMessage());
    }

    @Test
    void editorMocInfoRoutesThroughTheCoreJoinAndFailsClosedWithoutIt() {
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
        final Fixture editor = new Fixture();

        try (Harness harness = harness("5.3.02", coreModel)) {
            final var access = new EditorBackedCubismModelAccess(
                editor.resolver, "session-a", harness.join
            );
            final var info = access.active().mocInfo();
            assertEquals(dev.turboism.sdk.cubism.core.MocVersion.V5_3, info.version());
            assertEquals(dev.turboism.sdk.cubism.core.MocConsistency.UNKNOWN, info.consistency());
        }

        final var withoutJoin = new EditorBackedCubismModelAccess(editor.resolver, "session-a");
        assertThrows(IllegalStateException.class, withoutJoin.active()::mocInfo);
    }

    private static TestCoreApiFixture.Model coreModel(
        final AtomicInteger canvasReads,
        final int constantFlag,
        final int dynamicFlag,
        final int blendMode,
        final int textureIndex,
        final int renderOrder
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
            new TestCoreApiFixture.Drawables(
                new String[]{"ArtMeshFace"},
                new byte[]{(byte) constantFlag},
                new byte[]{(byte) dynamicFlag},
                new int[]{blendMode},
                new int[]{textureIndex}, new int[]{7}, new int[]{renderOrder}, new float[]{0.8F},
                new int[]{0}, new int[][]{{}}, new int[]{3},
                new float[][]{{0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F}},
                new float[][]{{0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F}},
                new int[]{3}, new short[][]{{0, 1, 2}},
                new float[][]{{1.0F, 0.5F, 0.25F, 1.0F}},
                new float[][]{{0.0F, 0.1F, 0.2F, 1.0F}},
                new int[]{-1}, new int[]{-1}, new int[]{1}, new int[][]{{0}}
            ),
            TestCoreApiFixture.Deformers.empty(),
            TestCoreApiFixture.Glues.empty(),
            canvasReads::incrementAndGet
        );
    }

    private static Harness harness(
        final String artifactProfile,
        final TestCoreApiFixture.Model model
    ) {
        final java.util.List<StaticSelector> mocSelectors = new ArrayList<>();
        mocSelectors.add(StaticSelector.method(
            dev.turboism.mapping.verification.CoreMocInfoSelectorContract.MODEL_GET_MOC,
            internalName(TestCoreApiFixture.Model.class),
            "getMoc",
            "()L" + internalName(TestCoreApiFixture.Moc.class) + ";",
            StaticSelector.ACCESS_PUBLIC
        ));
        mocSelectors.add(StaticSelector.classSelector(
            dev.turboism.mapping.verification.CoreMocInfoSelectorContract.MOC_CLASS,
            internalName(TestCoreApiFixture.Moc.class)
        ));
        mocSelectors.add(StaticSelector.method(
            dev.turboism.mapping.verification.CoreMocInfoSelectorContract.MOC_GET_MOC_VERSION,
            internalName(TestCoreApiFixture.Moc.class),
            "getMocVersion",
            "()I",
            StaticSelector.ACCESS_PUBLIC
        ));
        final VerifiedMemberResolver resolver = TestCoreApiFixture.resolverWithExtras(
            artifactProfile,
            mocSelectors,
            java.util.Set.of(dev.turboism.mapping.verification.CoreMocInfoSelectorContract.CAPABILITY_ID)
        );
        final CorePublicApiProvider provider = CorePublicApiProviderFactory.admitForTesting(
            resolver,
            CoreVersionExpectation.exact(11, 12, 13)
        ).value().orElseThrow();
        final CoreStructuralTracer tracer = CoreStructuralTracerFactory.admit(
            provider, resolver
        ).value().orElseThrow();
        final BorrowedCoreModelSource source = new BorrowedCoreModelSource();
        source.publishBorrowedModel(model, "model-a");
        return new Harness(source, provider, tracer);
    }

    private static String internalName(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static final class Harness implements AutoCloseable {
        final BorrowedCoreModelSource source;
        final CorePublicApiProvider provider;
        final CoreStructuralTracer tracer;
        final CoreEvaluatedJoin join;

        Harness(
            final BorrowedCoreModelSource source,
            final CorePublicApiProvider provider,
            final CoreStructuralTracer tracer
        ) {
            this.source = source;
            this.provider = provider;
            this.tracer = tracer;
            this.join = new CoreEvaluatedJoin(source, provider, tracer);
        }

        @Override
        public void close() {
            tracer.close();
            source.close();
        }
    }

    /** Compact editor fixture: one ArtMesh bound to one Core drawable id. */
    private static final class Fixture {
        final VerifiedMemberResolver resolver;
        final Host host = new Host();

        Fixture() {
            Host.document = new Document();
            resolver = TestVerifiedResolvers.create(
                "5.3.02",
                EditorObjectReadSelectorContract.ADAPTER_SLICE_ID,
                java.util.Set.of(EditorObjectReadSelectorContract.CAPABILITY_ID),
                selectors(),
                Host.class.getClassLoader()
            );
        }

        private static List<StaticSelector> selectors() {
            final List<StaticSelector> values = new ArrayList<>();
            values.add(StaticSelector.classSelector("cubism.editor-model.app-controller.class", internal(Host.class)));
            values.add(StaticSelector.staticMethod("cubism.editor-model.app-controller.instance", internal(Host.class), "instance", "()L" + internal(Host.class) + ";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
            values.add(method("cubism.editor-model.app-controller.current-document", Host.class, "currentDocument", "()L" + internal(Document.class) + ";"));
            values.add(StaticSelector.classSelector("cubism.editor-model.modeling-document.class", internal(Document.class)));
            values.add(method("cubism.editor-model.modeling-document.model-source", Document.class, "modelSource", "()L" + internal(ModelSource.class) + ";"));
            values.add(method("cubism.editor-model.model-source.guid", ModelSource.class, "guid", "()L" + internal(Id.class) + ";"));
            values.add(method("cubism.editor-model.model-source.current-instance", ModelSource.class, "currentInstance", "()L" + internal(Model.class) + ";"));
            values.add(method("cubism.editor-model.model-source.all-art-meshes", ModelSource.class, "allArtMeshes", "()Ljava/util/List;"));
            values.add(method("cubism.editor-model.model-source.parts", ModelSource.class, "allParts", "()Ljava/util/List;"));
            values.add(method("cubism.editor-model.model-source.all-deformers", ModelSource.class, "allDeformers", "()Ljava/util/List;"));
            values.add(method("cubism.editor-model.model-source.all-glues", ModelSource.class, "allGlues", "()Ljava/util/List;"));
            values.add(StaticSelector.classSelector("cubism.editor-model.model.class", internal(Model.class)));
            values.add(method("cubism.editor-model.model.all-art-meshes", Model.class, "allArtMeshes", "()Ljava/util/List;"));
            values.add(method("cubism.editor-model.model.all-deformers", Model.class, "allDeformers", "()Ljava/util/List;"));
            values.add(StaticSelector.classSelector("cubism.editor-model.art-mesh-source.class", internal(ArtMeshSource.class)));
            values.add(StaticSelector.classSelector("cubism.editor-model.art-mesh.class", internal(ArtMesh.class)));
            values.add(method("cubism.editor-model.art-mesh.source", ArtMesh.class, "source", "()L" + internal(ArtMeshSource.class) + ";"));
            values.add(method("cubism.editor-model.parameter-controllable-source.id", ObjectSource.class, "id", "()L" + internal(Id.class) + ";"));
            values.add(method("cubism.editor-model.part-source.parent", ObjectSource.class, "parent", "()L" + internal(PartSource.class) + ";"));
            values.add(StaticSelector.classSelector("cubism.editor-model.part-source.class", internal(PartSource.class)));
            values.add(method("cubism.editor-model.part-source.id", PartSource.class, "id", "()L" + internal(Id.class) + ";"));
            values.add(method("cubism.editor-model.guid.value", Id.class, "value", "()Ljava/lang/String;"));
            values.add(method("cubism.editor-model.id.value", Id.class, "value", "()Ljava/lang/String;"));
            values.add(method("cubism.editor-model.part-id.value", Id.class, "value", "()Ljava/lang/String;"));
            values.add(method("cubism.editor-model.model.parameter-set", Model.class, "parameterSet", "()Ljava/lang/Object;"));
            values.add(method("cubism.editor-model.parameter-set.parameters", Object.class, "toString", "()Ljava/lang/String;"));
            values.add(StaticSelector.classSelector("cubism.editor-model.parameter.class", internal(ParameterHolder.class)));
            values.add(method("cubism.editor-model.parameter.id", ParameterHolder.class, "id", "()L" + internal(Id.class) + ";"));
            values.add(method("cubism.editor-model.parameter-controllable-source.local-name", ObjectSource.class, "localName", "()Ljava/lang/String;"));
            values.add(method("cubism.editor-model.parameter-controllable-source.visible", ObjectSource.class, "visible", "()Z"));
            values.add(method("cubism.editor-model.parameter-controllable-source.locked", ObjectSource.class, "locked", "()Z"));
            values.add(method("cubism.editor-model.parameter-controllable-source.visible-in-hierarchy", ObjectSource.class, "visibleInHierarchy", "()Z"));
            values.add(method("cubism.editor-model.parameter-controllable-source.locked-in-hierarchy", ObjectSource.class, "lockedInHierarchy", "()Z"));
            values.add(method("cubism.editor-model.parameter-controllable-source.target-deformer-source", ObjectSource.class, "targetDeformerSource", "()Ljava/lang/Object;"));
            values.add(method("cubism.editor-model.art-mesh.current-keyform", ArtMesh.class, "currentForm", "()L" + internal(Form.class) + ";"));
            values.add(method("cubism.editor-model.drawable-form.opacity", Form.class, "opacity", "()F"));
            values.add(method("cubism.editor-model.drawable-form.draw-order", Form.class, "drawOrder", "()I"));
            values.add(method("cubism.editor-model.art-mesh-form.positions", Form.class, "positions", "()[F"));
            values.add(method("cubism.editor-model.art-mesh-source.guid", ArtMeshSource.class, "guid", "()L" + internal(Id.class) + ";"));
            values.add(method("cubism.editor-model.art-mesh-source.clip-guid-list", ArtMeshSource.class, "clipGuids", "()Ljava/util/List;"));
            values.add(method("cubism.editor-model.art-mesh-source.positions", ArtMeshSource.class, "positions", "()[F"));
            values.add(method("cubism.editor-model.art-mesh-source.uvs", ArtMeshSource.class, "uvs", "()[F"));
            values.add(method("cubism.editor-model.art-mesh-source.indices", ArtMeshSource.class, "indices", "()[I"));
            values.add(method("cubism.editor-model.art-mesh-source.culling", ArtMeshSource.class, "culling", "()Z"));
            values.add(method("cubism.editor-model.art-mesh-source.user-data", ArtMeshSource.class, "userData", "()Ljava/lang/String;"));
            values.add(method("cubism.editor-model.art-mesh-source.inverted-mask", ArtMeshSource.class, "invertedMask", "()Z"));
            values.add(StaticSelector.classSelector("cubism.editor-model.warp-source.class", internal(WarpSource.class)));
            values.add(StaticSelector.classSelector("cubism.editor-model.warp.class", internal(Warp.class)));
            values.add(StaticSelector.classSelector("cubism.editor-model.rotation-source.class", internal(RotationSource.class)));
            values.add(StaticSelector.classSelector("cubism.editor-model.rotation.class", internal(Rotation.class)));
            values.add(method("cubism.editor-model.deformer.source", Deformer.class, "source", "()L" + internal(ObjectSource.class) + ";"));
            values.add(method("cubism.editor-model.deformer.current-keyform", Deformer.class, "currentForm", "()L" + internal(Form.class) + ";"));
            values.add(method("cubism.editor-model.deformer-form.opacity", Form.class, "opacity", "()F"));
            values.add(method("cubism.editor-model.warp-source.row", WarpSource.class, "row", "()I"));
            values.add(method("cubism.editor-model.warp-source.col", WarpSource.class, "col", "()I"));
            values.add(method("cubism.editor-model.warp-source.quad-transform", WarpSource.class, "quadTransform", "()Z"));
            values.add(method("cubism.editor-model.warp-form.positions", WarpForm.class, "positions", "()[F"));
            values.add(method("cubism.editor-model.rotation-source.base-angle", RotationSource.class, "baseAngle", "()F"));
            values.add(method("cubism.editor-model.rotation-form.angle", RotationForm.class, "angle", "()F"));
            values.add(method("cubism.editor-model.rotation-form.origin-x", RotationForm.class, "originX", "()F"));
            values.add(method("cubism.editor-model.rotation-form.origin-y", RotationForm.class, "originY", "()F"));
            values.add(method("cubism.editor-model.rotation-form.scale", RotationForm.class, "scale", "()F"));
            values.add(method("cubism.editor-model.rotation-form.reflect-x", RotationForm.class, "reflectX", "()Z"));
            values.add(method("cubism.editor-model.rotation-form.reflect-y", RotationForm.class, "reflectY", "()Z"));
            values.add(StaticSelector.classSelector("cubism.editor-model.glue-source.class", internal(GlueSource.class)));
            values.add(method("cubism.editor-model.glue-source.target-art-mesh-a", GlueSource.class, "targetA", "()L" + internal(ArtMeshSource.class) + ";"));
            values.add(method("cubism.editor-model.glue-source.target-art-mesh-b", GlueSource.class, "targetB", "()L" + internal(ArtMeshSource.class) + ";"));
            return List.copyOf(values);
        }

        private static StaticSelector method(
            final String alias,
            final Class<?> owner,
            final String name,
            final String descriptor
        ) {
            return StaticSelector.method(
                alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC
            );
        }

        private static String internal(final Class<?> type) {
            return type.getName().replace('.', '/');
        }
    }

    public static final class Host {
        private static final Host INSTANCE = new Host();
        static Document document;

        public static Host instance() {
            return INSTANCE;
        }

        public Document currentDocument() {
            return document;
        }
    }

    public static final class Document {
        public ModelSource modelSource() {
            return Host.document == this ? source : source;
        }
        private final ModelSource source = new ModelSource();
    }

    public static final class ModelSource {
        private final ArtMeshSource meshSource = new ArtMeshSource("ArtMeshFace");
        private final Model model = new Model(meshSource);

        public Id guid() {
            return new Id("model-a");
        }

        public Model currentInstance() {
            return model;
        }

        public List<ArtMeshSource> allArtMeshes() {
            return List.of(meshSource);
        }

        public List<Object> allParts() {
            return List.of();
        }

        public List<Object> allDeformers() {
            return List.of();
        }

        public List<Object> allGlues() {
            return List.of();
        }
    }

    public static final class Model {
        private final List<ArtMesh> meshes = new ArrayList<>();

        Model(final ArtMeshSource source) {
            meshes.add(new ArtMesh(source));
        }

        public List<ArtMesh> allArtMeshes() {
            return meshes;
        }

        public List<Object> allDeformers() {
            return List.of();
        }

        public Object parameterSet() {
            return new Object();
        }
    }

    public static final class Id {
        private final String value;

        Id(final String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    public static class ObjectSource {
        private final Id id;
        private final PartSource parent = null;

        ObjectSource(final String id) {
            this.id = new Id(id);
        }

        public Id id() {
            return id;
        }

        public PartSource parent() {
            return parent;
        }

        public String localName() {
            return "local";
        }

        public boolean visible() {
            return true;
        }

        public boolean locked() {
            return false;
        }

        public boolean visibleInHierarchy() {
            return true;
        }

        public boolean lockedInHierarchy() {
            return false;
        }

        public Object targetDeformerSource() {
            return null;
        }
    }

    public static final class PartSource extends ObjectSource {
        PartSource() {
            super("PartRoot");
        }
    }

    public static final class ArtMeshSource extends ObjectSource {
        private final Id guid = new Id("guid:" + super.id().value());

        ArtMeshSource(final String id) {
            super(id);
        }

        public Id guid() {
            return guid;
        }

        public List<Object> clipGuids() {
            return List.of();
        }

        public float[] positions() {
            return new float[]{0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F};
        }

        public float[] uvs() {
            return new float[]{0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F};
        }

        public int[] indices() {
            return new int[]{0, 1, 2};
        }

        public boolean culling() {
            return true;
        }

        public String userData() {
            return "face";
        }

        public boolean invertedMask() {
            return false;
        }
    }

    public static final class ArtMesh {
        private final ArtMeshSource source;

        ArtMesh(final ArtMeshSource source) {
            this.source = source;
        }

        public ArtMeshSource source() {
            return source;
        }

        public Form currentForm() {
            return new Form();
        }
    }

    public static class Form {
        public float opacity() {
            return 0.75F;
        }

        public int drawOrder() {
            return 7;
        }

        public float[] positions() {
            return new float[]{0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F};
        }
    }

    public static final class WarpForm extends Form {
    }

    public static final class RotationForm {
        public float angle() { return 15.0F; }
        public float originX() { return 2.0F; }
        public float originY() { return 3.0F; }
        public float scale() { return 1.25F; }
        public boolean reflectX() { return false; }
        public boolean reflectY() { return false; }
    }

    public static final class WarpSource extends ObjectSource {
        WarpSource() { super("WarpFace"); }
        public int row() { return 2; }
        public int col() { return 2; }
        public boolean quadTransform() { return false; }
    }

    public static final class RotationSource extends ObjectSource {
        RotationSource() { super("RotationHead"); }
        public float baseAngle() { return 0.0F; }
    }

    public static class Deformer {
        private final ObjectSource source;

        Deformer(final ObjectSource source) {
            this.source = source;
        }

        public ObjectSource source() {
            return source;
        }

        public Form currentForm() {
            return new Form();
        }
    }

    public static final class Warp extends Deformer {
        Warp(final WarpSource source) { super(source); }
    }

    public static final class Rotation extends Deformer {
        Rotation(final RotationSource source) { super(source); }
    }

    public static final class GlueSource {
        public ArtMeshSource targetA() { return null; }
        public ArtMeshSource targetB() { return null; }
    }

    public static final class ParameterHolder {
        public Id id() {
            return new Id("ParamAngleX");
        }
    }
}
