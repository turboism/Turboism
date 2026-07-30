package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.EditorObjectReadSelectorContract;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.RotationDeformerForm;
import dev.turboism.sdk.cubism.model.WarpGrid;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorObjectReadAccessTest {

    @ParameterizedTest
    @ValueSource(strings = {"5.2.0", "5.3.02"})
    void readsArtMeshWarpAndRotationAuthoringState(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver(version), "session-a").active();

        final var mesh = model.drawables().find(new ArtMeshId("ArtMeshFace"));
        assertEquals("Face Mesh", mesh.name());
        assertTrue(mesh.visible());
        assertEquals(false, mesh.locked());
        assertEquals(0.75F, mesh.getOpacity());
        assertEquals(7, mesh.drawOrder());
        assertEquals(new Point2(1.0F, 0.0F), mesh.geometry().positions().get(1));
        assertEquals(List.of(0, 1, 2), mesh.geometry().triangleIndices());
        assertEquals("face", mesh.userData());
        assertTrue(mesh.culling());
        assertTrue(mesh.invertedMask());

        final var warp = model.warpDeformers().find(new DeformerId("WarpFace"));
        assertEquals("Face Warp", warp.name());
        assertEquals(0.8F, warp.getOpacity());
        assertEquals(2, warp.grid().rows());
        assertEquals(3, warp.grid().columns());
        assertEquals(new Point2(4.0F, 4.5F), warp.grid().controlPoints().get(4));

        final var rotation = model.rotationDeformers().find(new DeformerId("RotationHead"));
        assertEquals("Head Rotation", rotation.name());
        assertEquals(0.9F, rotation.getOpacity());
        assertEquals(30.0F, rotation.baseAngle());
        assertEquals(15.0F, rotation.form().angle());
        assertEquals(new Point2(2.0F, 3.0F), rotation.form().origin());
        assertEquals(1.25F, rotation.form().scale());
        assertTrue(rotation.form().reflectedX());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.0", "5.3.02"})
    void sameIdSourceReplacementMakesReferencesStale(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver(version), "session-a").active();
        final var mesh = model.drawables().find(new ArtMeshId("ArtMeshFace"));
        final var warp = model.warpDeformers().find(new DeformerId("WarpFace"));
        final var rotation = model.rotationDeformers().find(new DeformerId("RotationHead"));

        fixture.replaceAllWithSameIds();

        assertThrows(IllegalStateException.class, mesh::name);
        assertThrows(IllegalStateException.class, warp::grid);
        assertThrows(IllegalStateException.class, rotation::form);
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.0", "5.3.02"})
    void writesScalarAuthoringStateWithOneUndoDirtyAndRefresh(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver(version, true), "session-a").active();
        final var mesh = model.drawables().find(new ArtMeshId("ArtMeshFace"));
        final var warp = model.warpDeformers().find(new DeformerId("WarpFace"));
        final var rotation = model.rotationDeformers().find(new DeformerId("RotationHead"));

        mesh.setOpacity(0.5F);
        mesh.setVisible(false);
        mesh.setLocked(true);
        warp.setOpacity(0.6F);
        rotation.setBaseAngle(45.0F);

        assertEquals(0.5F, fixture.mesh().form.opacity);
        assertFalse(fixture.meshSource().visible);
        assertTrue(fixture.meshSource().locked);
        assertEquals(0.6F, fixture.warp().form.opacity);
        assertEquals(45.0F, fixture.rotationSource().baseAngle);
        assertEquals(5, fixture.document.editMode.edits.size());
        assertEquals(5, fixture.source.updateCount);
        assertEquals(3, fixture.document.pack.partRefreshCount);
        assertEquals(2, fixture.document.pack.deformerRefreshCount);
        assertEquals(5, fixture.document.pack.repaintCount);
        assertTrue(fixture.document.dirty);
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.0", "5.3.02"})
    void writeCapabilityIsIndependentFromReadAndNoChangeSkipsMutation(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var readonlyModel = new EditorBackedCubismModelAccess(resolver(version, false), "session-a").active();
        final var readonlyMesh = readonlyModel.drawables().find(new ArtMeshId("ArtMeshFace"));
        assertThrows(UnsupportedOperationException.class, () -> readonlyMesh.setOpacity(0.5F));
        assertEquals(0, fixture.document.editMode.edits.size());

        final var writableModel = new EditorBackedCubismModelAccess(resolver(version, true), "session-a").active();
        final var mesh = writableModel.drawables().find(new ArtMeshId("ArtMeshFace"));
        mesh.setOpacity(0.75F);
        assertEquals(0, fixture.document.editMode.edits.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.0", "5.3.02"})
    void replacesCompleteAuthoringSnapshotsAtomically(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver(version, true), "session-a").active();
        final var mesh = model.drawables().find(new ArtMeshId("ArtMeshFace"));
        final var warp = model.warpDeformers().find(new DeformerId("WarpFace"));
        final var rotation = model.rotationDeformers().find(new DeformerId("RotationHead"));

        final ArtMeshGeometry meshGeometry = new ArtMeshGeometry(
            List.of(new Point2(0, 0), new Point2(2, 0), new Point2(0, 2)),
            List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, 1)),
            List.of(0, 2, 1)
        );
        final WarpGrid warpGrid = new WarpGrid(
            1, 1, true,
            List.of(new Point2(0, 0), new Point2(2, 0), new Point2(0, 2), new Point2(2, 2))
        );
        final RotationDeformerForm rotationForm = new RotationDeformerForm(
            25.0F, 4.0F, 5.0F, 1.5F, false, true
        );

        mesh.replaceGeometry(meshGeometry);
        warp.replaceGrid(warpGrid);
        rotation.replaceForm(rotationForm);

        assertEquals(List.of(new Point2(0, 0), new Point2(2, 0), new Point2(0, 2)), mesh.geometry().positions());
        assertEquals(List.of(0, 2, 1), mesh.geometry().triangleIndices());
        assertEquals(warpGrid, warp.grid());
        assertEquals(rotationForm, rotation.form());
        assertEquals(3, fixture.document.editMode.edits.size());
        assertEquals(3, fixture.source.updateCount);

        mesh.replaceGeometry(meshGeometry);
        warp.replaceGrid(warpGrid);
        rotation.replaceForm(rotationForm);
        assertEquals(3, fixture.document.editMode.edits.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.0", "5.3.02"})
    void rollsBackCompleteSnapshotsWhenALaterHostSetterFails(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver(version, true), "session-a").active();
        final var mesh = model.drawables().find(new ArtMeshId("ArtMeshFace"));
        final var warp = model.warpDeformers().find(new DeformerId("WarpFace"));
        final var rotation = model.rotationDeformers().find(new DeformerId("RotationHead"));
        final ArtMeshGeometry originalMesh = mesh.geometry();
        final WarpGrid originalWarp = warp.grid();
        final RotationDeformerForm originalRotation = rotation.form();

        fixture.failures.failOn(4);
        final ArtMeshGeometry changedMesh = new ArtMeshGeometry(
            List.of(new Point2(4, 5), new Point2(2, 0), new Point2(0, 2)),
            List.of(new Point2(0.2F, 0.3F), new Point2(0.8F, 0.3F), new Point2(0.2F, 0.9F)),
            List.of(0, 2, 1)
        );
        assertThrows(RuntimeException.class, () -> mesh.replaceGeometry(changedMesh));
        assertEquals(originalMesh, mesh.geometry());
        assertAbortedWithoutPublishedEffects(fixture);

        fixture.resetPublishedEffects();
        fixture.failures.failOn(4);
        final WarpGrid changedWarp = new WarpGrid(
            1, 1, !originalWarp.quadTransform(),
            List.of(new Point2(0, 0), new Point2(2, 0), new Point2(0, 2), new Point2(2, 2))
        );
        assertThrows(RuntimeException.class, () -> warp.replaceGrid(changedWarp));
        assertEquals(originalWarp, warp.grid());
        assertAbortedWithoutPublishedEffects(fixture);

        fixture.resetPublishedEffects();
        fixture.failures.failOn(4);
        assertThrows(RuntimeException.class, () -> rotation.replaceForm(
            new RotationDeformerForm(
                22.0F, 8.0F, 9.0F, 1.5F,
                !originalRotation.reflectedX(), !originalRotation.reflectedY()
            )
        ));
        assertEquals(originalRotation, rotation.form());
        assertAbortedWithoutPublishedEffects(fixture);
    }

    private static void assertAbortedWithoutPublishedEffects(final Fixture fixture) {
        assertEquals(0, fixture.document.editMode.edits.size());
        assertFalse(fixture.document.dirty);
        assertEquals(0, fixture.source.updateCount);
        assertEquals(0, fixture.document.pack.partRefreshCount);
        assertEquals(0, fixture.document.pack.deformerRefreshCount);
        assertEquals(0, fixture.document.pack.repaintCount);
    }

    private static VerifiedMemberResolver resolver(final String version) {
        return resolver(version, false);
    }

    private static VerifiedMemberResolver resolver(final String version, final boolean includeWrite) {
        final java.util.HashSet<String> capabilities = new java.util.HashSet<>();
        capabilities.add(EditorObjectReadSelectorContract.CAPABILITY_ID);
        if (includeWrite) {
            capabilities.add(dev.turboism.mapping.verification.EditorObjectWriteSelectorContract.ART_MESH_CAPABILITY_ID);
            capabilities.add(dev.turboism.mapping.verification.EditorObjectWriteSelectorContract.WARP_CAPABILITY_ID);
            capabilities.add(dev.turboism.mapping.verification.EditorObjectWriteSelectorContract.ROTATION_CAPABILITY_ID);
        }
        return TestVerifiedResolvers.create(
            version,
            EditorObjectReadSelectorContract.ADAPTER_SLICE_ID,
            capabilities,
            selectors(),
            Host.class.getClassLoader()
        );
    }

    private static List<StaticSelector> selectors() {
        return List.of(
            StaticSelector.classSelector("cubism.editor-model.app-controller.class", internal(Host.class)),
            StaticSelector.staticMethod("cubism.editor-model.app-controller.instance", internal(Host.class), "instance", desc(Host.class), StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC),
            method("cubism.editor-model.app-controller.current-document", Host.class, "currentDocument", desc(Document.class)),
            method("cubism.editor-model.app-controller.complete-pack", Host.class, "completePack", desc(CompletePack.class)),
            StaticSelector.classSelector("cubism.editor-model.modeling-document.class", internal(Document.class)),
            method("cubism.editor-model.modeling-document.model-source", Document.class, "modelSource", desc(ModelSource.class)),
            method("cubism.editor-model.modeling-document.edit-mode", Document.class, "editMode", desc(EditMode.class)),
            method("cubism.editor-model.modeling-document.mark-dirty", Document.class, "markDirty", "()V"),
            method("cubism.editor-model.edit-mode.begin", EditMode.class, "begin", "(Ljava/lang/String;)" + type(GroupUndo.class)),
            method("cubism.editor-model.edit-mode.end", EditMode.class, "end", "(ZLjava/lang/Object;)V"),
            method("cubism.editor-model.undo.add", GroupUndo.class, "add", "(" + type(Undo.class) + "Z)Z"),
            method("cubism.editor-model.undo.add-listener", Undo.class, "addListener", "(" + type(Listener.class) + ")Z"),
            StaticSelector.classSelector("cubism.editor-model.undo-listener.class", internal(Listener.class)),
            method("cubism.editor-model.model-source.guid", ModelSource.class, "guid", desc(Id.class)),
            method("cubism.editor-model.model-source.current-instance", ModelSource.class, "currentInstance", desc(Model.class)),
            StaticSelector.classSelector("cubism.editor-model.model.class", internal(Model.class)),
            method("cubism.editor-model.guid.value", Id.class, "value", "()Ljava/lang/String;"),
            method("cubism.editor-model.id.value", Id.class, "value", "()Ljava/lang/String;"),
            method("cubism.editor-model.model-source.all-art-meshes", ModelSource.class, "allArtMeshes", "()Ljava/util/List;"),
            method("cubism.editor-model.model.all-art-meshes", Model.class, "allArtMeshes", "()Ljava/util/List;"),
            StaticSelector.classSelector("cubism.editor-model.art-mesh-source.class", internal(ArtMeshSource.class)),
            StaticSelector.classSelector("cubism.editor-model.art-mesh.class", internal(ArtMesh.class)),
            method("cubism.editor-model.parameter-controllable-source.id", ObjectSource.class, "id", desc(Id.class)),
            method("cubism.editor-model.parameter-controllable-source.local-name", ObjectSource.class, "localName", "()Ljava/lang/String;"),
            method("cubism.editor-model.parameter-controllable-source.visible", ObjectSource.class, "visible", "()Z"),
            method("cubism.editor-model.parameter-controllable-source.locked", ObjectSource.class, "locked", "()Z"),
            method("cubism.editor-model.parameter-controllable-source.visible-in-hierarchy", ObjectSource.class, "visibleInHierarchy", "()Z"),
            method("cubism.editor-model.parameter-controllable-source.locked-in-hierarchy", ObjectSource.class, "lockedInHierarchy", "()Z"),
            method("cubism.editor-model.parameter-controllable-source.handler", ObjectSource.class, "handler", desc(Handler.class)),
            method("cubism.editor-model.parameter-controllable-source.set-visible", ObjectSource.class, "setVisible", "(Z)V"),
            method("cubism.editor-model.parameter-controllable-source.set-locked", ObjectSource.class, "setLocked", "(Z)V"),
            StaticSelector.classSelector("cubism.editor-model.parameter-controllable-handler.class", internal(Handler.class)),
            method("cubism.editor-model.parameter-controllable-handler.create-undo-for-all-edit", Handler.class, "undo", "(Ljava/lang/String;)" + type(Undo.class)),
            method("cubism.editor-model.art-mesh.source", ArtMesh.class, "source", desc(ArtMeshSource.class)),
            method("cubism.editor-model.art-mesh.current-keyform", ArtMesh.class, "currentForm", desc(ArtMeshForm.class)),
            method("cubism.editor-model.drawable-form.opacity", Form.class, "opacity", "()F"),
            method("cubism.editor-model.drawable-form.set-opacity", Form.class, "setOpacity", "(F)V"),
            method("cubism.editor-model.drawable-form.draw-order", Form.class, "drawOrder", "()I"),
            method("cubism.editor-model.art-mesh-form.positions", ArtMeshForm.class, "positions", "()[F"),
            method("cubism.editor-model.art-mesh-form.set-positions", ArtMeshForm.class, "setPositions", "([F)V"),
            method("cubism.editor-model.art-mesh-source.positions", ArtMeshSource.class, "positions", "()[F"),
            method("cubism.editor-model.art-mesh-source.set-positions", ArtMeshSource.class, "setPositions", "([F)V"),
            method("cubism.editor-model.art-mesh-source.uvs", ArtMeshSource.class, "uvs", "()[F"),
            method("cubism.editor-model.art-mesh-source.set-uvs", ArtMeshSource.class, "setUvs", "([F)V"),
            method("cubism.editor-model.art-mesh-source.indices", ArtMeshSource.class, "indices", "()[I"),
            method("cubism.editor-model.art-mesh-source.set-indices", ArtMeshSource.class, "setIndices", "([I)V"),
            method("cubism.editor-model.art-mesh-source.culling", ArtMeshSource.class, "culling", "()Z"),
            method("cubism.editor-model.art-mesh-source.user-data", ArtMeshSource.class, "userData", "()Ljava/lang/String;"),
            method("cubism.editor-model.art-mesh-source.inverted-mask", ArtMeshSource.class, "invertedMask", "()Z"),
            method("cubism.editor-model.model-source.all-deformers", ModelSource.class, "allDeformers", "()Ljava/util/List;"),
            method("cubism.editor-model.model.all-deformers", Model.class, "allDeformers", "()Ljava/util/List;"),
            StaticSelector.classSelector("cubism.editor-model.warp-source.class", internal(WarpSource.class)),
            StaticSelector.classSelector("cubism.editor-model.warp.class", internal(Warp.class)),
            StaticSelector.classSelector("cubism.editor-model.rotation-source.class", internal(RotationSource.class)),
            StaticSelector.classSelector("cubism.editor-model.rotation.class", internal(Rotation.class)),
            method("cubism.editor-model.deformer.source", Deformer.class, "source", desc(ObjectSource.class)),
            method("cubism.editor-model.deformer.current-keyform", Deformer.class, "currentForm", desc(Form.class)),
            method("cubism.editor-model.deformer-form.opacity", Form.class, "opacity", "()F"),
            method("cubism.editor-model.deformer-form.set-opacity", Form.class, "setOpacity", "(F)V"),
            method("cubism.editor-model.warp-source.row", WarpSource.class, "row", "()I"),
            method("cubism.editor-model.warp-source.set-row", WarpSource.class, "setRow", "(I)V"),
            method("cubism.editor-model.warp-source.col", WarpSource.class, "col", "()I"),
            method("cubism.editor-model.warp-source.set-col", WarpSource.class, "setCol", "(I)V"),
            method("cubism.editor-model.warp-source.quad-transform", WarpSource.class, "quadTransform", "()Z"),
            method("cubism.editor-model.warp-source.set-quad-transform", WarpSource.class, "setQuadTransform", "(Z)V"),
            method("cubism.editor-model.warp-form.positions", WarpForm.class, "positions", "()[F"),
            method("cubism.editor-model.warp-form.set-positions", WarpForm.class, "setPositions", "([F)V"),
            method("cubism.editor-model.rotation-source.base-angle", RotationSource.class, "baseAngle", "()F"),
            method("cubism.editor-model.rotation-source.set-base-angle", RotationSource.class, "setBaseAngle", "(F)V"),
            method("cubism.editor-model.rotation-form.angle", RotationForm.class, "angle", "()F"),
            method("cubism.editor-model.rotation-form.set-angle", RotationForm.class, "setAngle", "(F)V"),
            method("cubism.editor-model.rotation-form.origin-x", RotationForm.class, "originX", "()F"),
            method("cubism.editor-model.rotation-form.set-origin-x", RotationForm.class, "setOriginX", "(F)V"),
            method("cubism.editor-model.rotation-form.origin-y", RotationForm.class, "originY", "()F"),
            method("cubism.editor-model.rotation-form.set-origin-y", RotationForm.class, "setOriginY", "(F)V"),
            method("cubism.editor-model.rotation-form.scale", RotationForm.class, "scale", "()F"),
            method("cubism.editor-model.rotation-form.set-scale", RotationForm.class, "setScale", "(F)V"),
            method("cubism.editor-model.rotation-form.reflect-x", RotationForm.class, "reflectX", "()Z"),
            method("cubism.editor-model.rotation-form.set-reflect-x", RotationForm.class, "setReflectX", "(Z)V"),
            method("cubism.editor-model.rotation-form.reflect-y", RotationForm.class, "reflectY", "()Z"),
            method("cubism.editor-model.rotation-form.set-reflect-y", RotationForm.class, "setReflectY", "(Z)V"),
            method("cubism.editor-model.complete-pack.update-part-palette", CompletePack.class, "updateParts", "(Z)V"),
            method("cubism.editor-model.complete-pack.update-deformer-palette", CompletePack.class, "updateDeformers", "(Z)V"),
            method("cubism.editor-model.complete-pack.repaint-canvas", CompletePack.class, "repaint", "(Z)V"),
            method("cubism.editor-model.model-source.update-instances", ModelSource.class, "updateInstances", "()V")
        );
    }

    private static StaticSelector method(final String alias, final Class<?> owner, final String name, final String descriptor) {
        return StaticSelector.method(alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC);
    }
    private static String internal(final Class<?> type) { return type.getName().replace('.', '/'); }
    private static String type(final Class<?> type) { return "L" + internal(type) + ";"; }
    private static String desc(final Class<?> type) { return "()" + type(type); }

    public static final class Host {
        private static final Host INSTANCE = new Host();
        static Document document;
        public static Host instance() { return INSTANCE; }
        public Document currentDocument() { return document; }
        public CompletePack completePack() { return document.pack; }
    }
    public interface Listener { void changed(Object ignored); }
    public static class Undo {
        public boolean addListener(final Listener listener) { return true; }
    }
    public static final class GroupUndo {
        public boolean add(final Undo undo, final boolean merge) { return undo != null; }
    }
    public static final class EditMode {
        final java.util.List<GroupUndo> edits = new java.util.ArrayList<>();
        public GroupUndo begin(final String action) { final GroupUndo value = new GroupUndo(); edits.add(value); return value; }
        public void end(final boolean aborted, final Object ignored) {
            if (aborted && !edits.isEmpty()) edits.remove(edits.size() - 1);
        }
    }
    public static final class CompletePack {
        int partRefreshCount;
        int deformerRefreshCount;
        int repaintCount;
        public void updateParts(final boolean force) { partRefreshCount++; }
        public void updateDeformers(final boolean force) { deformerRefreshCount++; }
        public void repaint(final boolean force) { repaintCount++; }
    }
    public static final class Handler {
        public Undo undo(final String action) { return new Undo(); }
    }
    public static final class Document {
        final ModelSource source;
        final EditMode editMode = new EditMode();
        final CompletePack pack = new CompletePack();
        boolean dirty;
        Document(final ModelSource source) { this.source = source; }
        public ModelSource modelSource() { return source; }
        public EditMode editMode() { return editMode; }
        public void markDirty() { dirty = true; }
    }
    public static final class Id {
        final String value;
        Id(final String value) { this.value = value; }
        public String value() { return value; }
    }
    public static final class Failures {
        private int call;
        private int failAt = Integer.MAX_VALUE;
        void failOn(final int value) { call = 0; failAt = value; }
        void reset() { call = 0; failAt = Integer.MAX_VALUE; }
        void setter() {
            call++;
            if (call == failAt) {
                failAt = Integer.MAX_VALUE;
                throw new IllegalStateException("injected host setter failure");
            }
        }
    }
    public static class ObjectSource {
        final Id id;
        final String localName;
        final Handler handler = new Handler();
        final Failures failures;
        boolean visible = true;
        boolean locked;
        ObjectSource(final String id, final String localName, final Failures failures) {
            this.id = new Id(id);
            this.localName = localName;
            this.failures = failures;
        }
        public Id id() { return id; }
        public String localName() { return localName; }
        public boolean visible() { return visible; }
        public void setVisible(final boolean value) { failures.setter(); visible = value; }
        public boolean locked() { return locked; }
        public void setLocked(final boolean value) { failures.setter(); locked = value; }
        public boolean visibleInHierarchy() { return visible; }
        public boolean lockedInHierarchy() { return locked; }
        public Handler handler() { return handler; }
    }
    public static class Form {
        float opacity;
        Form(final float opacity) { this.opacity = opacity; }
        public float opacity() { return opacity; }
        public void setOpacity(final float value) { opacity = value; }
        public int drawOrder() { return 0; }
    }
    public static final class ArtMeshSource extends ObjectSource {
        float[] sourcePositions = new float[] {0, 0, 1, 0, 0, 1};
        float[] sourceUvs = new float[] {0, 0, 1, 0, 0, 1};
        int[] sourceIndices = new int[] {0, 1, 2};
        ArtMeshSource(final Failures failures) { super("ArtMeshFace", "Face Mesh", failures); }
        public float[] positions() { return sourcePositions.clone(); }
        public void setPositions(final float[] values) { failures.setter(); sourcePositions = values.clone(); }
        public float[] uvs() { return sourceUvs.clone(); }
        public void setUvs(final float[] values) { failures.setter(); sourceUvs = values.clone(); }
        public int[] indices() { return sourceIndices.clone(); }
        public void setIndices(final int[] values) { failures.setter(); sourceIndices = values.clone(); }
        public boolean culling() { return true; }
        public String userData() { return "face"; }
        public boolean invertedMask() { return true; }
    }
    public static final class ArtMeshForm extends Form {
        final Failures failures;
        float[] positions = new float[] {0, 0, 1, 0, 0, 1};
        ArtMeshForm(final Failures failures) { super(0.75F); this.failures = failures; }
        @Override public int drawOrder() { return 7; }
        public float[] positions() { return positions.clone(); }
        public void setPositions(final float[] values) { failures.setter(); positions = values.clone(); }
    }
    public static final class ArtMesh {
        final ArtMeshSource source;
        final ArtMeshForm form;
        ArtMesh(final ArtMeshSource source) { this.source = source; this.form = new ArtMeshForm(source.failures); }
        public ArtMeshSource source() { return source; }
        public ArtMeshForm currentForm() { return form; }
    }
    public static class Deformer {
        final ObjectSource source;
        final Form form;
        Deformer(final ObjectSource source, final Form form) { this.source = source; this.form = form; }
        public ObjectSource source() { return source; }
        public Form currentForm() { return form; }
    }
    public static final class WarpSource extends ObjectSource {
        int row = 2;
        int col = 3;
        boolean quadTransform;
        WarpSource(final Failures failures) { super("WarpFace", "Face Warp", failures); }
        public int row() { return row; }
        public void setRow(final int value) { failures.setter(); row = value; }
        public int col() { return col; }
        public void setCol(final int value) { failures.setter(); col = value; }
        public boolean quadTransform() { return quadTransform; }
        public void setQuadTransform(final boolean value) { failures.setter(); quadTransform = value; }
    }
    public static final class WarpForm extends Form {
        final Failures failures;
        float[] positions;
        WarpForm(final Failures failures) { super(0.8F); this.failures = failures; positions = initialPositions(); }
        private static float[] initialPositions() {
            final float[] values = new float[24];
            for (int i = 0; i < 12; i++) { values[i * 2] = i; values[i * 2 + 1] = i + 0.5F; }
            return values;
        }
        public float[] positions() { return positions.clone(); }
        public void setPositions(final float[] values) { failures.setter(); positions = values.clone(); }
    }
    public static final class Warp extends Deformer {
        Warp(final WarpSource source) { super(source, new WarpForm(source.failures)); }
    }
    public static final class RotationSource extends ObjectSource {
        float baseAngle = 30.0F;
        RotationSource(final Failures failures) { super("RotationHead", "Head Rotation", failures); }
        public float baseAngle() { return baseAngle; }
        public void setBaseAngle(final float value) { failures.setter(); baseAngle = value; }
    }
    public static final class RotationForm extends Form {
        final Failures failures;
        float angle = 15.0F;
        float originX = 2.0F;
        float originY = 3.0F;
        float scale = 1.25F;
        boolean reflectX = true;
        boolean reflectY;
        RotationForm(final Failures failures) { super(0.9F); this.failures = failures; }
        public float angle() { return angle; }
        public void setAngle(final float value) { failures.setter(); angle = value; }
        public float originX() { return originX; }
        public void setOriginX(final float value) { failures.setter(); originX = value; }
        public float originY() { return originY; }
        public void setOriginY(final float value) { failures.setter(); originY = value; }
        public float scale() { return scale; }
        public void setScale(final float value) { failures.setter(); scale = value; }
        public boolean reflectX() { return reflectX; }
        public void setReflectX(final boolean value) { failures.setter(); reflectX = value; }
        public boolean reflectY() { return reflectY; }
        public void setReflectY(final boolean value) { failures.setter(); reflectY = value; }
    }
    public static final class Rotation extends Deformer {
        Rotation(final RotationSource source) { super(source, new RotationForm(source.failures)); }
    }
    public static final class ModelSource {
        final Id guid = new Id("model-a");
        final List<ArtMeshSource> artMeshSources = new java.util.ArrayList<>();
        final List<ObjectSource> deformerSources = new java.util.ArrayList<>();
        Model model;
        int updateCount;
        public Id guid() { return guid; }
        public Model currentInstance() { return model; }
        public List<ArtMeshSource> allArtMeshes() { return artMeshSources; }
        public List<ObjectSource> allDeformers() { return deformerSources; }
        public void updateInstances() { updateCount++; }
    }
    public static final class Model {
        final List<ArtMesh> artMeshes = new java.util.ArrayList<>();
        final List<Deformer> deformers = new java.util.ArrayList<>();
        public List<ArtMesh> allArtMeshes() { return artMeshes; }
        public List<Deformer> allDeformers() { return deformers; }
    }
    private static final class Fixture {
        final Failures failures = new Failures();
        final ModelSource source = new ModelSource();
        final Document document = new Document(source);
        Fixture() { install(); }
        void install() {
            failures.reset();
            final ArtMeshSource meshSource = new ArtMeshSource(failures);
            final WarpSource warpSource = new WarpSource(failures);
            final RotationSource rotationSource = new RotationSource(failures);
            source.artMeshSources.clear();
            source.deformerSources.clear();
            source.artMeshSources.add(meshSource);
            source.deformerSources.add(warpSource);
            source.deformerSources.add(rotationSource);
            source.model = new Model();
            source.model.artMeshes.add(new ArtMesh(meshSource));
            source.model.deformers.add(new Warp(warpSource));
            source.model.deformers.add(new Rotation(rotationSource));
        }
        void replaceAllWithSameIds() { install(); }
        void resetPublishedEffects() {
            failures.reset();
            document.editMode.edits.clear();
            document.dirty = false;
            source.updateCount = 0;
            document.pack.partRefreshCount = 0;
            document.pack.deformerRefreshCount = 0;
            document.pack.repaintCount = 0;
        }
        ArtMeshSource meshSource() { return source.artMeshSources.get(0); }
        ArtMesh mesh() { return source.model.artMeshes.get(0); }
        Warp warp() { return (Warp) source.model.deformers.get(0); }
        RotationSource rotationSource() { return (RotationSource) source.deformerSources.get(1); }
    }
}
