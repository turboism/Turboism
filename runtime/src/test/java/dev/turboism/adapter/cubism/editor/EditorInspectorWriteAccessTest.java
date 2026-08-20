package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.selector.EditorDeformerInspectorSelectorContract;
import dev.turboism.mapping.verification.selector.EditorGlueInspectorSelectorContract;
import dev.turboism.mapping.verification.selector.EditorObjectReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartInspector52SelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartInspectorSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartOpacitySelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartTreeSelectorContract;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.model.AlphaComposition;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.GlueId;
import dev.turboism.sdk.cubism.model.PartId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Editor Inspector family writes (Deformer / Part / Glue) against a simulated
 * host, asserting the native Undo envelope (edit begin, undo admit, mutation,
 * model instance refresh, palette refresh, dirty, edit end) and the fail-closed
 * rules from the decompiled wrappers.
 */
class EditorInspectorWriteAccessTest {

    @Test
    void deformerNameWriteUsesNativeUndoEnvelopeAndBothPaletteRefreshes() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var access = new EditorBackedCubismModelAccess(resolver(false), "session-a");

        final var deformer = access.active().deformers().find(new DeformerId("Warp1"));
        assertEquals("Warp1", deformer.name());
        deformer.setName("BodyWarp");

        assertEquals("BodyWarp", fixture.warp.source.localName);
        assertEquals(1, fixture.editMode.edits.size());
        assertTrue(fixture.document.dirty);
        assertEquals(1, fixture.source.updateCount);
        assertEquals(1, fixture.pack.partRefreshCount);
        assertEquals(1, fixture.pack.deformerRefreshCount);
        assertEquals(1, fixture.pack.repaintCount);
        assertFalse(fixture.editMode.aborted);
    }

    @Test
    void deformerIdWriteValidatesRulesAndVerifiesModel() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var access = new EditorBackedCubismModelAccess(resolver(false), "session-a");
        final var deformer = access.active().deformers().find(new DeformerId("Warp1"));

        assertThrows(IllegalArgumentException.class, () -> deformer.setId(new DeformerId("")));
        assertThrows(IllegalArgumentException.class, () -> deformer.setId(new DeformerId("1leading-digit")));
        assertThrows(IllegalArgumentException.class, () -> deformer.setId(new DeformerId("has space")));
        assertThrows(IllegalArgumentException.class, () -> deformer.setId(new DeformerId("Warp2")));
        assertEquals(0, fixture.editMode.edits.size());
        assertEquals("Warp1", fixture.warp.source.id.value());

        deformer.setId(new DeformerId("WarpRenamed"));
        assertEquals("WarpRenamed", fixture.warp.source.id.value());
        assertEquals(1, fixture.verifyCount);
        assertEquals(1, fixture.source.updateCount);
        assertEquals(1, fixture.editMode.edits.size());
        assertTrue(fixture.document.dirty);
    }

    @Test
    void deformerTargetWriteUsesHostChangeTargetDeformer() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var access = new EditorBackedCubismModelAccess(resolver(false), "session-a");
        final var deformer = access.active().deformers().find(new DeformerId("Warp1"));

        assertThrows(IllegalArgumentException.class, () -> deformer.setTargetDeformer(Optional.of(new DeformerId("Warp1"))));
        assertThrows(IllegalArgumentException.class, () -> deformer.setTargetDeformer(Optional.of(new DeformerId("Rotation1"))));
        assertThrows(IllegalArgumentException.class, () -> deformer.setTargetDeformer(Optional.of(new DeformerId("Absent"))));
        assertEquals(0, fixture.editMode.edits.size());

        deformer.setTargetDeformer(Optional.of(new DeformerId("Warp2")));
        assertEquals(fixture.warp2.source.guid, fixture.warp.source.targetGuid);
        assertEquals(1, fixture.warp.source.handler.changeCount);
        assertEquals(1, fixture.editMode.edits.size());
        assertTrue(fixture.document.dirty);
        assertFalse(fixture.editMode.aborted);
    }

    @Test
    void deformerTargetDetachUsesRootGuid() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var access = new EditorBackedCubismModelAccess(resolver(false), "session-a");
        final var deformer = access.active().deformers().find(new DeformerId("Warp1"));

        deformer.setTargetDeformer(Optional.empty());
        assertEquals(DeformerGuid.ROOT, fixture.warp.source.targetGuid);
        assertEquals(1, fixture.warp.source.handler.changeCount);
    }

    @Test
    void deformerColorWriteRejectsOldTargetVersionAndMutatesFormColor() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        fixture.source.targetVersionNo = 4_000_000;
        final var access = new EditorBackedCubismModelAccess(resolver(false), "session-a");
        final var deformer = access.active().deformers().find(new DeformerId("Warp1"));

        assertThrows(IllegalStateException.class, () -> deformer.setMultiplyColor(new Color(0.5F, 0.5F, 0.5F, 1.0F)));
        assertThrows(IllegalArgumentException.class, () -> deformer.setScreenColor(new Color(1.5F, 0F, 0F, 1F)));
        assertEquals(0, fixture.editMode.edits.size());

        fixture.source.targetVersionNo = 4_020_000;
        deformer.setMultiplyColor(new Color(0.25F, 0.5F, 0.75F, 1.0F));
        assertEquals(0.25F, fixture.warp.form.multiply.r);
        assertEquals(0.5F, fixture.warp.form.multiply.g);
        assertEquals(0.75F, fixture.warp.form.multiply.b);
        assertEquals(1.0F, fixture.warp.form.multiply.a);
        assertEquals(1, fixture.editMode.edits.size());
        assertTrue(fixture.document.dirty);

        deformer.setScreenColor(new Color(1.0F, 0.0F, 0.0F, 1.0F));
        assertEquals(1.0F, fixture.warp.form.screen.r);
        assertEquals(0.0F, fixture.warp.form.screen.g);
    }

    @Test
    void partIdWriteWorksOnBothVersionsAnd52RejectsMaskAndAlpha() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var access = new EditorBackedCubismModelAccess(resolver(false), "session-a");
        var part = access.active().parts().find(new PartId("PartClip"));

        part.setId(new PartId("PartRenamed"));
        assertEquals("PartRenamed", fixture.partClip.source.id.value());
        assertEquals(1, fixture.verifyCount);
        assertEquals(1, fixture.editMode.edits.size());
        assertTrue(fixture.document.dirty);

        part = access.active().parts().find(new PartId("PartRenamed"));
        part.setMaskIds(List.of(new ArtMeshId("ArtA"), new ArtMeshId("ArtB")));
        assertEquals(List.of(fixture.artA.guid, fixture.artB.guid), fixture.partClip.source.clipGuids);
        assertEquals(2, fixture.editMode.edits.size());

        part.setAlphaComposition(AlphaComposition.ATOP);
        assertEquals(AlphaMode.ATOP, fixture.partClip.source.alphaComposition);
        assertEquals(3, fixture.editMode.edits.size());
    }

    @Test
    void part52ResolverAllowsIdWriteButFailsClosedForMaskAndAlpha() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var access = new EditorBackedCubismModelAccess(resolver(true), "session-a");
        var part = access.active().parts().find(new PartId("PartClip"));

        part.setId(new PartId("Part52Renamed"));
        assertEquals("Part52Renamed", fixture.partClip.source.id.value());
        assertEquals(1, fixture.editMode.edits.size());

        final var renamed = access.active().parts().find(new PartId("Part52Renamed"));
        // Reads and writes both fail closed on 5.2 with UnsupportedOperationException
        // (never VerifiedAccessException from a missing 5.3-only alias).
        assertThrows(UnsupportedOperationException.class, () -> renamed.maskIds());
        assertThrows(UnsupportedOperationException.class, () -> renamed.alphaComposition());
        assertThrows(UnsupportedOperationException.class, () -> renamed.setMaskIds(List.of(new ArtMeshId("ArtA"))));
        assertThrows(UnsupportedOperationException.class, () -> renamed.setAlphaComposition(AlphaComposition.OUT));
        assertEquals(1, fixture.editMode.edits.size());
        assertEquals(0, fixture.partClip.source.clipGuids.size());
    }

    @Test
    void partMaskWriteFailsClosedWhenArtMeshIsAbsent() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var access = new EditorBackedCubismModelAccess(resolver(false), "session-a");
        final var part = access.active().parts().find(new PartId("PartClip"));

        assertThrows(IllegalArgumentException.class, () -> part.setMaskIds(List.of(new ArtMeshId("Absent"))));
        assertEquals(0, fixture.editMode.edits.size());
    }

    @Test
    void glueNameIdIntensityAndDrawableWritesUseNativeUndoEnvelope() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var access = new EditorBackedCubismModelAccess(resolver(false), "session-a");
        var glue = access.active().glues().find(new GlueId("Glue1"));

        assertEquals("Glue1", glue.name());
        assertEquals(0.5F, glue.intensity());

        glue.setName("MainGlue");
        assertEquals("MainGlue", fixture.glue.source.localName);
        assertEquals(1, fixture.verifyCount);

        glue.setId(new GlueId("GlueRenamed"));
        assertEquals("GlueRenamed", fixture.glue.source.id.value());
        assertEquals(2, fixture.verifyCount);

        glue = access.active().glues().find(new GlueId("GlueRenamed"));
        glue.setIntensity(0.75F);
        assertEquals(0.75F, fixture.glue.form.intensity);

        glue.setDrawableA(new ArtMeshId("ArtB"));
        assertEquals(fixture.artB.guid, fixture.glue.source.targetAGuid);
        glue.setDrawableB(new ArtMeshId("ArtA"));
        assertEquals(fixture.artA.guid, fixture.glue.source.targetBGuid);

        assertEquals(5, fixture.editMode.edits.size());
        assertTrue(fixture.document.dirty);
        assertFalse(fixture.editMode.aborted);
    }

    @Test
    void glueIntensityValidatesModelSpaceRange() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var access = new EditorBackedCubismModelAccess(resolver(false), "session-a");
        final var glue = access.active().glues().find(new GlueId("Glue1"));

        assertThrows(IllegalArgumentException.class, () -> glue.setIntensity(-0.1F));
        assertThrows(IllegalArgumentException.class, () -> glue.setIntensity(1.1F));
        glue.setIntensity(0.5F); // unchanged value skips the envelope
        assertEquals(0, fixture.editMode.edits.size());
    }

    @Test
    void noOpWritesSkipTheUndoEnvelope() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var access = new EditorBackedCubismModelAccess(resolver(false), "session-a");
        access.active().deformers().find(new DeformerId("Warp1")).setName("Warp1");
        access.active().deformers().find(new DeformerId("Warp1")).setId(new DeformerId("Warp1"));
        access.active().parts().find(new PartId("PartClip")).setId(new PartId("PartClip"));
        access.active().glues().find(new GlueId("Glue1")).setDrawableA(new ArtMeshId("ArtA"));

        assertEquals(0, fixture.editMode.edits.size());
        assertFalse(fixture.document.dirty);
    }

    // ------------------------------------------------------------------
    // Fixture
    // ------------------------------------------------------------------

    private static VerifiedMemberResolver resolver(final boolean cubism52) {
        final String version = cubism52 ? "5.2.03" : "5.3.02";
        final List<StaticSelector> selectors = new ArrayList<>();
        selectors.add(StaticSelector.classSelector("cubism.editor-model.app-controller.class", internal(Host.class)));
        selectors.add(StaticSelector.staticMethod(
            "cubism.editor-model.app-controller.instance", internal(Host.class), "instance",
            desc(Host.class), StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        selectors.add(method("cubism.editor-model.app-controller.current-document", Host.class, "currentDocument", desc(Document.class)));
        selectors.add(method("cubism.editor-model.app-controller.complete-pack", Host.class, "completePack", desc(CompletePack.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.modeling-document.class", internal(Document.class)));
        selectors.add(method("cubism.editor-model.modeling-document.model-source", Document.class, "modelSource", desc(ModelSource.class)));
        selectors.add(method("cubism.editor-model.modeling-document.edit-mode", Document.class, "editMode", desc(EditMode.class)));
        selectors.add(method("cubism.editor-model.modeling-document.mark-dirty", Document.class, "markDirty", "()V"));
        selectors.add(method("cubism.editor-model.edit-mode.begin", EditMode.class, "begin", "(Ljava/lang/String;)" + type(GroupUndo.class)));
        selectors.add(method("cubism.editor-model.edit-mode.end", EditMode.class, "end", "(ZLjava/lang/Object;)V"));
        selectors.add(method("cubism.editor-model.undo.add", GroupUndo.class, "add", "(" + type(Undo.class) + "Z)Z"));
        selectors.add(method("cubism.editor-model.undo.add-listener", Undo.class, "addListener", "(" + type(Listener.class) + ")Z"));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.undo-listener.class", internal(Listener.class)));
        selectors.add(method("cubism.editor-model.model-source.guid", ModelSource.class, "guid", desc(Id.class)));
        selectors.add(method("cubism.editor-model.model-source.current-instance", ModelSource.class, "currentInstance", desc(Model.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.model.class", internal(Model.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.guid.class", internal(Id.class)));
        selectors.add(method("cubism.editor-model.guid.value", Id.class, "value", "()Ljava/lang/String;"));
        selectors.add(method("cubism.editor-model.id.value", Id.class, "value", "()Ljava/lang/String;"));
        selectors.add(method("cubism.editor-model.model-source.parts", ModelSource.class, "parts", "()Ljava/util/List;"));
        selectors.add(method("cubism.editor-model.model-source.update-instances", ModelSource.class, "updateInstances", "()V"));
        selectors.add(StaticSelector.staticMethod(
            "cubism.editor-model.model-source.verify", internal(ModelSource.class), "verify$default",
            "(L" + internal(ModelSource.class) + ";ZLjava/lang/Object;ILjava/lang/Object;)V",
            StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        selectors.add(method("cubism.editor-model.model-source.target-version", ModelSource.class, "getTargetVersion", desc(TargetVersion.class)));
        selectors.add(method("cubism.editor-model.target-version.number", TargetVersion.class, "a", "()I"));
        selectors.add(method("cubism.editor-model.model.parts", Model.class, "parts", "()Ljava/util/List;"));
        selectors.add(method("cubism.editor-model.model.all-art-meshes", Model.class, "allArtMeshes", "()Ljava/util/List;"));
        selectors.add(method("cubism.editor-model.model.all-deformers", Model.class, "allDeformers", "()Ljava/util/List;"));
        selectors.add(method("cubism.editor-model.model.get-object", Model.class, "getObject", "(" + type(Id.class) + ")" + type(Object.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.part.class", internal(HostPart.class)));
        selectors.add(method("cubism.editor-model.part.id", HostPart.class, "id", desc(Id.class)));
        selectors.add(method("cubism.editor-model.part.source", HostPart.class, "source", desc(PartSource.class)));
        selectors.add(method("cubism.editor-model.art-mesh.source", HostArtMesh.class, "source", desc(ArtMeshSource.class)));
        selectors.add(method("cubism.editor-model.part.current-keyform", HostPart.class, "currentForm", desc(PartForm.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.part-source.class", internal(PartSource.class)));
        selectors.add(method("cubism.editor-model.part-source.id", ParamSource.class, "id", desc(Id.class)));
        selectors.add(method("cubism.editor-model.part-source.parent", PartSource.class, "parent", desc(PartSource.class)));
        selectors.add(method("cubism.editor-model.part-source.handler", ParamSource.class, "handler", desc(ParamHandler.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.part-handler.class", internal(ParamHandler.class)));
        selectors.add(method("cubism.editor-model.part-handler.create-undo-for-all-edit", ParamHandler.class, "undo", "(Ljava/lang/String;)" + type(Undo.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.part-id.class", internal(Id.class)));
        selectors.add(method("cubism.editor-model.part-source.set-id", PartSource.class, "setId", "(" + type(Id.class) + ")V"));
        selectors.add(method("cubism.editor-model.part-source.clip-guid-list", PartSource.class, "getClipGuidList", "()Ljava/util/List;"));
        selectors.add(method("cubism.editor-model.part-source.alpha-composition", PartSource.class, "getAlphaComposition", desc(AlphaMode.class)));
        selectors.add(method("cubism.editor-model.part-source.set-alpha-composition", PartSource.class, "setAlphaComposition", "(" + type(AlphaMode.class) + ")V"));
        selectors.add(method("cubism.editor-model.part-id.create", Id.class, "<init>", "(Ljava/lang/String;)V", true));
        selectors.add(method("cubism.editor-model.part-id.value", Id.class, "value", "()Ljava/lang/String;"));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.alpha-composition.class", internal(AlphaMode.class)));
        selectors.add(field("cubism.editor-model.alpha-composition.over", AlphaMode.class, "OVER"));
        selectors.add(field("cubism.editor-model.alpha-composition.atop", AlphaMode.class, "ATOP"));
        selectors.add(field("cubism.editor-model.alpha-composition.out", AlphaMode.class, "OUT"));
        selectors.add(field("cubism.editor-model.alpha-composition.conjoint", AlphaMode.class, "CONJOINT"));
        selectors.add(field("cubism.editor-model.alpha-composition.disjoint", AlphaMode.class, "DISJOINT"));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.art-mesh-source.class", internal(ArtMeshSource.class)));
        selectors.add(method("cubism.editor-model.art-mesh-source.guid", ArtMeshSource.class, "guid", "()Ljava/lang/Object;"));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.art-mesh.class", internal(HostArtMesh.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.deformer-source.class", internal(DeformerSource.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.warp.class", internal(HostDeformer.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.warp-source.class", internal(DeformerSource.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.rotation.class", internal(HostDeformer.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.rotation-source.class", internal(DeformerSource.class)));
        selectors.add(method("cubism.editor-model.deformer-source.set-local-name", ParamSource.class, "setLocalName", "(Ljava/lang/String;)V"));
        selectors.add(method("cubism.editor-model.deformer-source.set-id", DeformerSource.class, "setId", "(" + type(Id.class) + ")V"));
        selectors.add(method("cubism.editor-model.deformer-source.guid", DeformerSource.class, "getGuid", desc(DeformerGuid.class)));
        selectors.add(method("cubism.editor-model.deformer-id.create", Id.class, "<init>", "(Ljava/lang/String;)V", true));
        selectors.add(method("cubism.editor-model.deformer.source", HostDeformer.class, "source", desc(DeformerSource.class)));
        selectors.add(method("cubism.editor-model.deformer.current-keyform", HostDeformer.class, "currentForm", desc(DeformerForm.class)));
        selectors.add(method("cubism.editor-model.deformer-form.multiply-color", DeformerForm.class, "getMultiplyColor", desc(FloatColor.class)));
        selectors.add(method("cubism.editor-model.deformer-form.screen-color", DeformerForm.class, "getScreenColor", desc(FloatColor.class)));
        selectors.add(method("cubism.editor-model.float-color.red", FloatColor.class, "getR", "()F"));
        selectors.add(method("cubism.editor-model.float-color.green", FloatColor.class, "getG", "()F"));
        selectors.add(method("cubism.editor-model.float-color.blue", FloatColor.class, "getB", "()F"));
        selectors.add(method("cubism.editor-model.float-color.alpha", FloatColor.class, "getA", "()F"));
        selectors.add(method("cubism.editor-model.float-color.set-red", FloatColor.class, "setR", "(F)V"));
        selectors.add(method("cubism.editor-model.float-color.set-green", FloatColor.class, "setG", "(F)V"));
        selectors.add(method("cubism.editor-model.float-color.set-blue", FloatColor.class, "setB", "(F)V"));
        selectors.add(method("cubism.editor-model.float-color.set-alpha", FloatColor.class, "setA", "(F)V"));
        selectors.add(method("cubism.editor-model.parameter-controllable-source.id", ParamSource.class, "id", desc(Id.class)));
        selectors.add(method("cubism.editor-model.parameter-controllable-source.local-name", ParamSource.class, "getLocalName", "()Ljava/lang/String;"));
        selectors.add(method("cubism.editor-model.parameter-controllable-source.handler", ParamSource.class, "handler", desc(ParamHandler.class)));
        selectors.add(method("cubism.editor-model.parameter-controllable-source.target-deformer-source", DeformerSource.class, "getTargetDeformerSource", desc(DeformerSource.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.parameter-controllable-handler.class", internal(ParamHandler.class)));
        selectors.add(method("cubism.editor-model.parameter-controllable-handler.create-undo-for-all-edit", ParamHandler.class, "undo", "(Ljava/lang/String;)" + type(Undo.class)));
        selectors.add(method("cubism.editor-model.parameter-controllable-handler.change-target-deformer", ParamHandler.class, "changeTargetDeformer", "(" + type(Model.class) + type(DeformerGuid.class) + "Z)" + type(Undo.class)));
        selectors.add(StaticSelector.field(
            "cubism.editor-model.deformer-guid.companion", internal(DeformerGuid.class), "Companion",
            "L" + internal(DeformerGuidCompanion.class) + ";",
            StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        selectors.add(method("cubism.editor-model.deformer-guid.root", DeformerGuidCompanion.class, "a", desc(DeformerGuid.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.glue-source.class", internal(GlueSource.class)));
        selectors.add(method("cubism.editor-model.glue-source.set-local-name", ParamSource.class, "setLocalName", "(Ljava/lang/String;)V"));
        selectors.add(method("cubism.editor-model.glue-source.local-name", ParamSource.class, "getLocalName", "()Ljava/lang/String;"));
        selectors.add(method("cubism.editor-model.glue-source.set-id", GlueSource.class, "setId", "(" + type(Id.class) + ")V"));
        selectors.add(method("cubism.editor-model.glue-source.target-art-mesh-a", GlueSource.class, "targetArtMeshA", desc(ArtMeshSource.class)));
        selectors.add(method("cubism.editor-model.glue-source.target-art-mesh-b", GlueSource.class, "targetArtMeshB", desc(ArtMeshSource.class)));
        selectors.add(method("cubism.editor-model.glue-source.set-target-art-mesh-a", GlueSource.class, "setTargetArtMeshA_guid", "(Ljava/lang/Object;)V"));
        selectors.add(method("cubism.editor-model.glue-source.set-target-art-mesh-b", GlueSource.class, "setTargetArtMeshB_guid", "(Ljava/lang/Object;)V"));
        selectors.add(method("cubism.editor-model.glue-id.create", Id.class, "<init>", "(Ljava/lang/String;)V", true));
        selectors.add(method("cubism.editor-model.glue.current-keyform", HostGlue.class, "getCurrentKeyform", desc(GlueForm.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.glue-form.class", internal(GlueForm.class)));
        selectors.add(method("cubism.editor-model.glue-form.intensity", GlueForm.class, "intensity", "()F"));
        selectors.add(method("cubism.editor-model.glue-form.set-intensity", GlueForm.class, "setIntensity", "(F)V"));
        selectors.add(method("cubism.editor-model.complete-pack.update-part-palette", CompletePack.class, "updateParts", "(Z)V"));
        selectors.add(method("cubism.editor-model.complete-pack.update-deformer-palette", CompletePack.class, "updateDeformers", "(Z)V"));
        selectors.add(method("cubism.editor-model.complete-pack.repaint-canvas", CompletePack.class, "repaint", "(Z)V"));
        selectors.add(method("cubism.editor-model.model-source.all-art-meshes", ModelSource.class, "allArtMeshes", "()Ljava/util/List;"));
        selectors.add(method("cubism.editor-model.model-source.all-deformers", ModelSource.class, "allDeformers", "()Ljava/util/List;"));
        selectors.add(method("cubism.editor-model.model-source.all-glues", ModelSource.class, "allGlues", "()Ljava/util/List;"));

        for (String readAlias : new String[]{
            "cubism.editor-model.model.parameter-set",
            "cubism.editor-model.parameter-set.parameters",
            "cubism.editor-model.parameter.class",
            "cubism.editor-model.parameter.id",
            "cubism.editor-model.parameter-controllable-source.visible",
            "cubism.editor-model.parameter-controllable-source.locked",
            "cubism.editor-model.parameter-controllable-source.visible-in-hierarchy",
            "cubism.editor-model.parameter-controllable-source.locked-in-hierarchy",
            "cubism.editor-model.art-mesh.current-keyform",
            "cubism.editor-model.drawable-form.opacity",
            "cubism.editor-model.drawable-form.draw-order",
            "cubism.editor-model.art-mesh-source.clip-guid-list",
            "cubism.editor-model.art-mesh-form.positions",
            "cubism.editor-model.art-mesh-source.positions",
            "cubism.editor-model.art-mesh-source.uvs",
            "cubism.editor-model.art-mesh-source.indices",
            "cubism.editor-model.art-mesh-source.culling",
            "cubism.editor-model.art-mesh-source.user-data",
            "cubism.editor-model.art-mesh-source.inverted-mask",
            "cubism.editor-model.deformer-form.opacity",
            "cubism.editor-model.warp-source.row",
            "cubism.editor-model.warp-source.col",
            "cubism.editor-model.warp-source.quad-transform",
            "cubism.editor-model.warp-form.positions",
            "cubism.editor-model.rotation-source.base-angle",
            "cubism.editor-model.rotation-form.angle",
            "cubism.editor-model.rotation-form.origin-x",
            "cubism.editor-model.rotation-form.origin-y",
            "cubism.editor-model.rotation-form.scale",
            "cubism.editor-model.rotation-form.reflect-x",
            "cubism.editor-model.rotation-form.reflect-y"
        }) {
            selectors.add(StaticSelector.method(
                readAlias, internal(ParamSource.class), "unusedReadAlias", "()V",
                StaticSelector.ACCESS_PUBLIC));
        }

        final java.util.Set<String> capabilities = new java.util.HashSet<>(java.util.Set.of(
            "cubism.editor-model.read",
            EditorPartOpacitySelectorContract.CAPABILITY_ID,
            EditorPartTreeSelectorContract.CAPABILITY_ID,
            EditorObjectReadSelectorContract.CAPABILITY_ID,
            EditorPartInspectorSelectorContract.CAPABILITY_ID,
            EditorDeformerInspectorSelectorContract.CAPABILITY_ID,
            EditorGlueInspectorSelectorContract.CAPABILITY_ID
        ));
        if (cubism52) {
            capabilities.remove(EditorPartInspectorSelectorContract.CAPABILITY_ID);
            capabilities.add(EditorPartInspector52SelectorContract.CAPABILITY_ID);
            // The 5.2 host record has no Part mask/alpha evidence; drop those
            // aliases so the fixture record mirrors the reviewed 5.2 record.
            final java.util.Set<String> excluded = java.util.Set.of(
                "cubism.editor-model.part.id",
                "cubism.editor-model.part.current-keyform",
                "cubism.editor-model.part-source.clip-guid-list",
                "cubism.editor-model.part-source.alpha-composition",
                "cubism.editor-model.part-source.set-alpha-composition",
                "cubism.editor-model.alpha-composition.class",
                "cubism.editor-model.alpha-composition.over",
                "cubism.editor-model.alpha-composition.atop",
                "cubism.editor-model.alpha-composition.out",
                "cubism.editor-model.alpha-composition.conjoint",
                "cubism.editor-model.alpha-composition.disjoint"
            );
            selectors.removeIf(selector -> excluded.contains(selector.alias()));
        }
        return TestVerifiedResolvers.create(
            version,
            "adapter.editor-model.readwrite",
            capabilities,
            selectors,
            Fixture.class.getClassLoader()
        );
    }

    private static StaticSelector method(
        final String alias, final Class<?> owner, final String name, final String descriptor
    ) {
        return StaticSelector.method(alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC);
    }

    private static StaticSelector method(
        final String alias, final Class<?> owner, final String name, final String descriptor, final boolean ctor
    ) {
        return ctor
            ? StaticSelector.constructor(alias, internal(owner), descriptor, StaticSelector.ACCESS_PUBLIC)
            : method(alias, owner, name, descriptor);
    }

    private static StaticSelector field(final String alias, final Class<?> owner, final String name) {
        return StaticSelector.field(alias, internal(owner), name, "L" + internal(owner) + ";",
            StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC);
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

    public static final class ModelSource {
        final Id guid = new Id("model-a");
        final List<ParamSource> all = new ArrayList<>();
        Model model;
        int updateCount;
        int targetVersionNo = 5_030_000;
        public Id guid() { return guid; }
        public Model currentInstance() { return model; }
        public List<PartSource> parts() { return all.stream().filter(s -> s instanceof PartSource).map(s -> (PartSource) s).toList(); }
        public List<ArtMeshSource> allArtMeshes() { return all.stream().filter(s -> s instanceof ArtMeshSource).map(s -> (ArtMeshSource) s).toList(); }
        public List<DeformerSource> allDeformers() { return all.stream().filter(s -> s instanceof DeformerSource).map(s -> (DeformerSource) s).toList(); }
        public List<GlueSource> allGlues() { return all.stream().filter(s -> s instanceof GlueSource).map(s -> (GlueSource) s).toList(); }
        public void updateInstances() { updateCount++; }
        public static void verify$default(final ModelSource source, final boolean fix, final Object unused, final int mask, final Object unused2) {
            Fixture.current.verifyCount++;
        }
        public TargetVersion getTargetVersion() { return new TargetVersion(targetVersionNo); }
    }

    public static final class TargetVersion {
        final int number;
        TargetVersion(final int number) { this.number = number; }
        public int a() { return number; }
    }

    public static final class Model {
        final List<HostPart> parts = new ArrayList<>();
        final List<HostArtMesh> artMeshes = new ArrayList<>();
        final List<HostDeformer> deformers = new ArrayList<>();
        final List<HostGlue> glues = new ArrayList<>();
        public List<HostPart> parts() { return parts; }
        public List<HostArtMesh> allArtMeshes() { return artMeshes; }
        public List<HostDeformer> allDeformers() { return deformers; }
        public Object getObject(final Id id) {
            return glues.stream().filter(g -> g.source().id().value().equals(id.value())).findFirst().orElse(null);
        }
    }

    public static class ParamSource {
        final Id id;
        final ParamHandler handler = new ParamHandler();
        String localName;
        ParamSource(final String id) { this.id = new Id(id); }
        public Id id() { return id; }
        public ParamHandler handler() { return handler; }
        public void setLocalName(final String name) { localName = name; }
        public String getLocalName() { return localName; }
    }

    public static final class PartSource extends ParamSource {
        final List<Object> clipGuids = new ArrayList<>();
        AlphaMode alphaComposition = AlphaMode.OVER;
        PartSource(final String id) { super(id); }
        public void setId(final Id newId) { id.value = newId.value; }
        public PartSource parent() { return null; }
        public List<Object> getClipGuidList() { return clipGuids; }
        public AlphaMode getAlphaComposition() { return alphaComposition; }
        public void setAlphaComposition(final AlphaMode mode) { alphaComposition = mode; }
    }

    public static final class DeformerSource extends ParamSource {
        final DeformerGuid guid = new DeformerGuid();
        DeformerSource targetDeformer;
        Object targetGuid;
        DeformerSource(final String id) { super(id); }
        public void setId(final Id newId) { id.value = newId.value; }
        public DeformerGuid getGuid() { return guid; }
        public DeformerSource getTargetDeformerSource() { return targetDeformer; }
    }

    public static final class ArtMeshSource extends ParamSource {
        final Object guid = new Object();
        ArtMeshSource(final String id) { super(id); }
        public Object guid() { return guid; }
    }

    public static final class GlueSource extends ParamSource {
        Object targetAGuid;
        Object targetBGuid;
        final ArtMeshSource targetA;
        final ArtMeshSource targetB;
        GlueSource(final String id, final ArtMeshSource targetA, final ArtMeshSource targetB) {
            super(id);
            this.targetA = targetA;
            this.targetB = targetB;
            targetAGuid = targetA.guid;
            targetBGuid = targetB.guid;
        }
        public void setId(final Id newId) { id.value = newId.value; }
        public ArtMeshSource targetArtMeshA() { return targetA; }
        public ArtMeshSource targetArtMeshB() { return targetB; }
        public void setTargetArtMeshA_guid(final Object guid) { targetAGuid = guid; }
        public void setTargetArtMeshB_guid(final Object guid) { targetBGuid = guid; }
    }

    public static final class ParamHandler {
        int changeCount;
        public Undo undo(final String name) { return new Undo(); }
        public Undo changeTargetDeformer(final Model model, final DeformerGuid guid, final boolean withUndo) {
            changeCount++;
            final DeformerSource source = Fixture.current.warp.source;
            source.targetGuid = guid;
            return new Undo();
        }
    }

    public static final class HostPart {
        final Id id;
        final PartSource source;
        final PartForm form = new PartForm();
        HostPart(final String id, final PartSource source) { this.id = new Id(id); this.source = source; }
        public Id id() { return id; }
        public PartSource source() { return source; }
        public PartForm currentForm() { return form; }
    }

    public static final class PartForm {
        float opacity = 1.0F;
        public float opacity() { return opacity; }
        public void setOpacity(final float value) { opacity = value; }
    }

    public static final class HostDeformer {
        final Id id;
        final DeformerSource source;
        final DeformerForm form = new DeformerForm();
        HostDeformer(final String id, final DeformerSource source) { this.id = new Id(id); this.source = source; }
        public Id id() { return id; }
        public DeformerSource source() { return source; }
        public DeformerForm currentForm() { return form; }
    }

    public static final class DeformerForm {
        final FloatColor multiply = new FloatColor();
        final FloatColor screen = new FloatColor();
        float opacity = 1.0F;
        public FloatColor getMultiplyColor() { return multiply; }
        public FloatColor getScreenColor() { return screen; }
        public float opacity() { return opacity; }
        public void setOpacity(final float value) { opacity = value; }
    }

    public static final class FloatColor {
        float r; float g; float b; float a = 1.0F;
        public float getR() { return r; }
        public float getG() { return g; }
        public float getB() { return b; }
        public float getA() { return a; }
        public void setR(final float v) { r = v; }
        public void setG(final float v) { g = v; }
        public void setB(final float v) { b = v; }
        public void setA(final float v) { a = v; }
    }

    public static final class DeformerGuid {
        public static final DeformerGuid ROOT = new DeformerGuid();
        public static final DeformerGuidCompanion Companion = new DeformerGuidCompanion();
    }

    public static final class DeformerGuidCompanion {
        public DeformerGuid a() { return DeformerGuid.ROOT; }
    }

    public static final class HostGlue {
        final Id id;
        final GlueSource source;
        final GlueForm form = new GlueForm();
        HostGlue(final String id, final GlueSource source) { this.id = new Id(id); this.source = source; }
        public Id id() { return id; }
        public GlueSource source() { return source; }
        public GlueForm getCurrentKeyform() { return form; }
    }

    public static final class GlueForm {
        float intensity = 0.5F;
        public float intensity() { return intensity; }
        public void setIntensity(final float value) { intensity = value; }
    }

    public static final class HostArtMesh {
        final Id id;
        final ArtMeshSource source;
        HostArtMesh(final String id, final ArtMeshSource source) { this.id = new Id(id); this.source = source; }
        public Id id() { return id; }
        public ArtMeshSource source() { return source; }
    }

    public static final class AlphaMode {
        public static final AlphaMode OVER = new AlphaMode();
        public static final AlphaMode ATOP = new AlphaMode();
        public static final AlphaMode OUT = new AlphaMode();
        public static final AlphaMode CONJOINT = new AlphaMode();
        public static final AlphaMode DISJOINT = new AlphaMode();
    }

    public static final class Id {
        String value;
        public Id(final String value) { this.value = value; }
        public String value() { return value; }
    }

    public static final class GroupUndo {
        public boolean add(final Undo undo, final boolean redoable) { return true; }
    }

    public static final class Undo {
        public boolean addListener(final Listener listener) { return true; }
    }

    public interface Listener {
        void onUndoRedo();
    }

    public static final class EditMode {
        final List<String> edits = new ArrayList<>();
        boolean aborted;
        public GroupUndo begin(final String action) { edits.add(action); return new GroupUndo(); }
        public void end(final boolean aborted, final Object unused) { this.aborted = aborted; }
    }

    public static final class CompletePack {
        int partRefreshCount;
        int deformerRefreshCount;
        int repaintCount;
        public void updateParts(final boolean value) { partRefreshCount++; }
        public void updateDeformers(final boolean value) { deformerRefreshCount++; }
        public void repaint(final boolean value) { repaintCount++; }
    }

    static final class Fixture {
        static Fixture current;
        final ModelSource source = new ModelSource();
        final Model model = new Model();
        final Document document = new Document(source);
        final EditMode editMode = document.editMode;
        final CompletePack pack = document.pack;
        int verifyCount;

        final ArtMeshSource artA = new ArtMeshSource("ArtA");
        final ArtMeshSource artB = new ArtMeshSource("ArtB");
        final PartSource partClipSource = new PartSource("PartClip");
        final HostPart partClip = new HostPart("PartClip", partClipSource);
        final DeformerSource warpSource = new DeformerSource("Warp1");
        final DeformerSource warp2Source = new DeformerSource("Warp2");
        final DeformerSource rotationSource = new DeformerSource("Rotation1");
        final HostDeformer warp = new HostDeformer("Warp1", warpSource);
        final HostDeformer warp2 = new HostDeformer("Warp2", warp2Source);
        final HostDeformer rotation = new HostDeformer("Rotation1", rotationSource);
        final GlueSource glueSource = new GlueSource("Glue1", artA, artB);
        final HostGlue glue = new HostGlue("Glue1", glueSource);

        Fixture() {
            current = this;
            source.model = model;
            source.all.add(partClipSource);
            source.all.add(artA);
            source.all.add(artB);
            source.all.add(warpSource);
            source.all.add(warp2Source);
            source.all.add(rotationSource);
            source.all.add(glueSource);
            model.parts.add(partClip);
            model.artMeshes.add(new HostArtMesh("ArtA", artA));
            model.artMeshes.add(new HostArtMesh("ArtB", artB));
            model.deformers.add(warp);
            model.deformers.add(warp2);
            model.deformers.add(rotation);
            model.glues.add(glue);
            // hierarchy: Warp1 -> Warp2 (parent); Rotation1 is Warp1's ancestor target
            warpSource.targetDeformer = warp2Source;
            rotationSource.targetDeformer = warpSource;
        }
    }
}
