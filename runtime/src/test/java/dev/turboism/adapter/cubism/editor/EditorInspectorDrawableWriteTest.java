package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.selector.EditorInspectorDrawableWrite52SelectorContract;
import dev.turboism.mapping.verification.selector.EditorInspectorDrawableWriteSelectorContract;
import dev.turboism.mapping.verification.selector.EditorObjectReadSelectorContract;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.AlphaComposition;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.ColorComposition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mock-resolver tests for the Editor Inspector Drawable/ArtMesh family write surface. */
public class EditorInspectorDrawableWriteTest {

    @ParameterizedTest
    @ValueSource(strings = {"5.2.0", "5.3.02"})
    void setIdAppliesTheInspectorEnvelope(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var mesh = new EditorBackedCubismModelAccess(resolver(version, true), "session-a")
            .active().drawables().find(new ArtMeshId("ArtMeshFace"));

        mesh.setId("ArtMeshFace2");

        assertEquals(1, fixture.document.editMode.edits.size());
        assertEquals(1, fixture.meshSource.idCalls);
        assertEquals("ArtMeshFace2", fixture.meshSource.idValue);
        assertEquals(1, fixture.meshSource.verifyCalls);
        assertEquals(1, fixture.source.updateCount);
        assertEquals(1, fixture.document.pack.updateManager.partCalls);
        assertEquals(1, fixture.document.pack.updateManager.deformerCalls);
        assertEquals(1, fixture.document.pack.partRefreshCount);
        assertEquals(1, fixture.document.pack.deformerRefreshCount);
        assertEquals(1, fixture.document.pack.repaintCount);
        assertTrue(fixture.document.dirty);
        assertEquals(1, fixture.handler.basicSettingUndoCount);
        assertEquals(0, fixture.handler.keyformUndoCount);
        assertEquals(1, fixture.meshSource.modelHandler.idMap.containsCalls);
        assertEquals("ArtMeshFace2", fixture.meshSource.modelHandler.idMap.checked);
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.0", "5.3.02"})
    void setIdSkipsUnchangedAndRejectsInvalidIds(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var mesh = new EditorBackedCubismModelAccess(resolver(version, true), "session-a")
            .active().drawables().find(new ArtMeshId("ArtMeshFace"));

        mesh.setId("ArtMeshFace");
        assertEquals(0, fixture.document.editMode.edits.size());

        assertThrows(IllegalArgumentException.class, () -> mesh.setId("  "));
        assertThrows(IllegalArgumentException.class, () -> mesh.setId("1bad"));
        assertThrows(IllegalArgumentException.class, () -> mesh.setId("bad id!"));
        assertThrows(IllegalArgumentException.class, () -> mesh.setId("x".repeat(64)));
        fixture.meshSource.modelHandler.idMap.contains = true;
        assertThrows(IllegalArgumentException.class, () -> mesh.setId("Taken"));
        assertEquals(0, fixture.document.editMode.edits.size());
        assertEquals(0, fixture.meshSource.idCalls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.0", "5.3.02"})
    void setTargetDeformerMovesToDeformerOrRoot(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver(version, true), "session-a").active();
        final var mesh = model.drawables().find(new ArtMeshId("ArtMeshFace"));

        mesh.setTargetDeformer(Optional.of(new DeformerId("RotationHead")));
        assertEquals(1, fixture.document.editMode.edits.size());
        assertEquals("RotationHead", fixture.handler.lastTargetDeformerId);
        assertEquals(0, fixture.handler.rootGuidCalls);
        assertEquals(1, fixture.source.updateCount);
        assertTrue(fixture.document.dirty);

        fixture.resetPublishedEffects();
        mesh.setTargetDeformer(Optional.empty());
        assertEquals(1, fixture.handler.rootGuidCalls);
        assertEquals(1, fixture.handler.lastRootTargetCalls);
        assertEquals(1, fixture.source.updateCount);

        assertThrows(java.util.NoSuchElementException.class,
            () -> mesh.setTargetDeformer(Optional.of(new DeformerId("Missing"))));
        assertEquals(1, fixture.document.editMode.edits.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.0", "5.3.02"})
    void setClippingMaskIdsResolvesDrawablesAndRejectsUnknown(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var mesh = new EditorBackedCubismModelAccess(resolver(version, true), "session-a")
            .active().drawables().find(new ArtMeshId("ArtMeshFace"));

        mesh.setClippingMaskIds(List.of(new ArtMeshId("ArtMeshMask")));
        assertEquals(1, fixture.document.editMode.edits.size());
        assertEquals(List.of("guid:ArtMeshMask"),
            fixture.meshSource.clipGuids.stream().map(value -> value.value).toList());
        assertEquals(1, fixture.source.getObjectCalls);

        assertThrows(IllegalArgumentException.class,
            () -> mesh.setClippingMaskIds(List.of(new ArtMeshId("PartFace"))));
        assertThrows(IllegalArgumentException.class,
            () -> mesh.setClippingMaskIds(List.of(new ArtMeshId("Missing"))));
        assertEquals(1, fixture.document.editMode.edits.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.0", "5.3.02"})
    void setInvertedMaskGatesOnTheCubism40TargetVersion(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var mesh = new EditorBackedCubismModelAccess(resolver(version, true), "session-a")
            .active().drawables().find(new ArtMeshId("ArtMeshFace"));

        fixture.source.targetVersionNumber = 3030; // SDK 3.3 target: CUB3-2528 rejects enabling
        mesh.setInvertedMask(false);
        assertEquals(1, fixture.meshSource.invertCalls);
        assertFalse(fixture.meshSource.invertValue);
        assertEquals(1, fixture.document.editMode.edits.size());
        assertThrows(UnsupportedOperationException.class, () -> mesh.setInvertedMask(true));
        assertEquals(1, fixture.document.editMode.edits.size());

        fixture.resetPublishedEffects();
        fixture.source.targetVersionNumber = 400_000; // SDK 4.0 target: allowed
        mesh.setInvertedMask(true);
        assertEquals(2, fixture.meshSource.invertCalls);
        assertTrue(fixture.meshSource.invertValue);
        assertEquals(1, fixture.document.editMode.edits.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.0", "5.3.02"})
    void setDrawOrderClampsAndUsesTheKeyformUndoEnvelope(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var mesh = new EditorBackedCubismModelAccess(resolver(version, true), "session-a")
            .active().drawables().find(new ArtMeshId("ArtMeshFace"));

        mesh.setDrawOrder(42);
        assertEquals(42, fixture.mesh.form.drawOrder);
        assertEquals(1, fixture.handler.keyformUndoCount);
        assertEquals(0, fixture.handler.basicSettingUndoCount);
        assertEquals(1, fixture.document.editMode.edits.size());

        fixture.resetPublishedEffects();
        mesh.setDrawOrder(-5);
        assertEquals(0, fixture.mesh.form.drawOrder);
        mesh.setDrawOrder(5000);
        assertEquals(1000, fixture.mesh.form.drawOrder);
        assertEquals(2, fixture.document.editMode.edits.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.0", "5.3.02"})
    void colorsGateOnTheCubism42TargetVersionAndWriteFormColors(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var mesh = new EditorBackedCubismModelAccess(resolver(version, true), "session-a")
            .active().drawables().find(new ArtMeshId("ArtMeshFace"));

        fixture.source.targetVersionNumber = 400_000;
        assertThrows(UnsupportedOperationException.class,
            () -> mesh.setMultiplyColor(new Color(1, 0, 0, 1)));
        assertThrows(UnsupportedOperationException.class,
            () -> mesh.setScreenColor(new Color(0, 1, 0, 1)));
        assertEquals(0, fixture.document.editMode.edits.size());

        fixture.source.targetVersionNumber = 4_020_000;
        mesh.setMultiplyColor(new Color(0.25F, 0.5F, 0.75F, 1.0F));
        assertEquals(List.of(0.25F, 0.5F, 0.75F, 1.0F), fixture.mesh.form.multiply.values);
        assertEquals(1, fixture.handler.keyformUndoCount);
        mesh.setScreenColor(new Color(0.1F, 0.2F, 0.3F, 0.4F));
        assertEquals(List.of(0.1F, 0.2F, 0.3F, 0.4F), fixture.mesh.form.screen.values);
        assertEquals(2, fixture.document.editMode.edits.size());

        mesh.setMultiplyColor(new Color(0.25F, 0.5F, 0.75F, 1.0F));
        assertEquals(2, fixture.document.editMode.edits.size());
    }

    @Test
    void setColorCompositionFailsClosedOn52HostValues() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var mesh = new EditorBackedCubismModelAccess(resolver("5.2.0", true), "session-a")
            .active().drawables().find(new ArtMeshId("ArtMeshFace"));

        mesh.setColorComposition(ColorComposition.MULTIPLY);
        assertEquals("MULTIPLY", fixture.meshSource.colorCompositionValue);
        assertEquals(1, fixture.document.editMode.edits.size());

        assertThrows(UnsupportedOperationException.class,
            () -> mesh.setColorComposition(ColorComposition.SCREEN));
        assertEquals(1, fixture.document.editMode.edits.size());
    }

    @Test
    void setColorCompositionWrites5302HostValues() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var mesh = new EditorBackedCubismModelAccess(resolver("5.3.02", true), "session-a")
            .active().drawables().find(new ArtMeshId("ArtMeshFace"));

        mesh.setColorComposition(ColorComposition.SCREEN);
        assertEquals("SCREEN", fixture.meshSource.colorCompositionValue);
        assertEquals(1, fixture.document.editMode.edits.size());
        mesh.setColorComposition(ColorComposition.HSL_COLOR);
        assertEquals("HSL_COLOR", fixture.meshSource.colorCompositionValue);
        assertEquals(2, fixture.document.editMode.edits.size());
    }

    @Test
    void setAlphaCompositionFailsClosedOn52AndWritesOn5302() {
        final Fixture fixture52 = new Fixture();
        Host.document = fixture52.document;
        final var mesh52 = new EditorBackedCubismModelAccess(resolver("5.2.0", true), "session-a")
            .active().drawables().find(new ArtMeshId("ArtMeshFace"));
        assertThrows(UnsupportedOperationException.class,
            () -> mesh52.setAlphaComposition(AlphaComposition.ATOP));
        assertEquals(0, fixture52.document.editMode.edits.size());

        final Fixture fixture5302 = new Fixture();
        Host.document = fixture5302.document;
        final var mesh = new EditorBackedCubismModelAccess(resolver("5.3.02", true), "session-a")
            .active().drawables().find(new ArtMeshId("ArtMeshFace"));
        mesh.setAlphaComposition(AlphaComposition.DISJOINT);
        assertEquals("DISJOINT", fixture5302.meshSource.alphaCompositionValue);
        assertEquals(1, fixture5302.document.editMode.edits.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.0", "5.3.02"})
    void setCullingWritesSourceAndRefreshesShader(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var mesh = new EditorBackedCubismModelAccess(resolver(version, true), "session-a")
            .active().drawables().find(new ArtMeshId("ArtMeshFace"));

        mesh.setCulling(false);
        assertEquals(1, fixture.meshSource.cullingCalls);
        assertFalse(fixture.meshSource.cullingValue);
        assertEquals(1, fixture.mesh.setupShaderCalls);
        assertEquals(1, fixture.document.editMode.edits.size());

        mesh.setCulling(false);
        assertEquals(1, fixture.meshSource.cullingCalls);
        assertEquals(1, fixture.document.editMode.edits.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.0", "5.3.02"})
    void setUserDataWritesTheSource(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var mesh = new EditorBackedCubismModelAccess(resolver(version, true), "session-a")
            .active().drawables().find(new ArtMeshId("ArtMeshFace"));

        mesh.setUserData("eyebrow");
        assertEquals("eyebrow", fixture.meshSource.userDataValue);
        assertEquals(1, fixture.document.editMode.edits.size());
        assertThrows(IllegalArgumentException.class, () -> mesh.setUserData(null));
        assertEquals(1, fixture.document.editMode.edits.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.0", "5.3.02"})
    void inspectorWritesRequireTheDedicatedCapability(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var mesh = new EditorBackedCubismModelAccess(resolver(version, false), "session-a")
            .active().drawables().find(new ArtMeshId("ArtMeshFace"));

        assertThrows(UnsupportedOperationException.class, () -> mesh.setId("Renamed"));
        assertThrows(UnsupportedOperationException.class,
            () -> mesh.setTargetDeformer(Optional.of(new DeformerId("WarpFace"))));
        assertThrows(UnsupportedOperationException.class,
            () -> mesh.setClippingMaskIds(List.of(new ArtMeshId("ArtMeshMask"))));
        assertThrows(UnsupportedOperationException.class, () -> mesh.setInvertedMask(true));
        assertThrows(UnsupportedOperationException.class, () -> mesh.setDrawOrder(5));
        assertThrows(UnsupportedOperationException.class,
            () -> mesh.setMultiplyColor(new Color(1, 1, 1, 1)));
        assertThrows(UnsupportedOperationException.class,
            () -> mesh.setScreenColor(new Color(1, 1, 1, 1)));
        assertThrows(UnsupportedOperationException.class,
            () -> mesh.setColorComposition(ColorComposition.NORMAL));
        assertThrows(UnsupportedOperationException.class,
            () -> mesh.setAlphaComposition(AlphaComposition.OVER));
        assertThrows(UnsupportedOperationException.class, () -> mesh.setCulling(true));
        assertThrows(UnsupportedOperationException.class, () -> mesh.setUserData("x"));
        assertEquals(0, fixture.document.editMode.edits.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.0", "5.3.02"})
    void hostSetterFailureRollsBackTheEnvelope(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var mesh = new EditorBackedCubismModelAccess(resolver(version, true), "session-a")
            .active().drawables().find(new ArtMeshId("ArtMeshFace"));

        fixture.failures.failOn(1);
        assertThrows(RuntimeException.class, () -> mesh.setUserData("boom"));
        assertEquals(0, fixture.document.editMode.edits.size());
        assertFalse(fixture.document.dirty);
        assertEquals(0, fixture.source.updateCount);
        assertEquals(0, fixture.document.pack.partRefreshCount);
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.0", "5.3.02"})
    void undoListenerRefreshesInstancesAndPalettes(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var mesh = new EditorBackedCubismModelAccess(resolver(version, true), "session-a")
            .active().drawables().find(new ArtMeshId("ArtMeshFace"));

        mesh.setUserData("eyebrow");
        final Listener listener = fixture.handler.lastUndo.listener;
        listener.changed(null);
        assertEquals(2, fixture.source.updateCount);
        assertEquals(2, fixture.document.pack.partRefreshCount);
        assertEquals(2, fixture.document.pack.deformerRefreshCount);
        assertEquals(2, fixture.document.pack.repaintCount);
    }

    private static VerifiedMemberResolver resolver(final String version, final boolean includeWrite) {
        final java.util.HashSet<String> capabilities = new java.util.HashSet<>();
        capabilities.add(EditorObjectReadSelectorContract.CAPABILITY_ID);
        if (includeWrite) {
            capabilities.add(EditorInspectorDrawableWriteSelectorContract.CAPABILITY_ID);
        }
        return TestVerifiedResolvers.create(
            version,
            EditorObjectReadSelectorContract.ADAPTER_SLICE_ID,
            capabilities,
            selectors(version),
            Host.class.getClassLoader()
        );
    }

    private static List<StaticSelector> selectors(final String version) {
        final List<StaticSelector> selectors = new ArrayList<>(List.of(
            StaticSelector.classSelector("cubism.editor-model.app-controller.class", internal(Host.class)),
            StaticSelector.staticMethod("cubism.editor-model.app-controller.instance", internal(Host.class), "instance", desc(Host.class), StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC),
            method("cubism.editor-model.app-controller.current-document", Host.class, "currentDocument", desc(Document.class)),
            method("cubism.editor-model.app-controller.complete-pack", Host.class, "completePack", desc(CompletePack.class)),
            StaticSelector.classSelector("cubism.editor-model.modeling-document.class", internal(Document.class)),
            method("cubism.editor-model.modeling-document.model-source", Document.class, "modelSource", desc(ModelSource.class)),
            method("cubism.editor-model.model-source.guid", ModelSource.class, "guid", desc(Id.class)),
            method("cubism.editor-model.model-source.current-instance", ModelSource.class, "currentInstance", desc(Model.class)),
            method("cubism.editor-model.guid.value", Id.class, "value", "()Ljava/lang/String;"),
            StaticSelector.classSelector("cubism.editor-model.model.class", internal(Model.class)),
            method("cubism.editor-model.modeling-document.edit-mode", Document.class, "editMode", desc(EditMode.class)),
            method("cubism.editor-model.modeling-document.mark-dirty", Document.class, "markDirty", "()V"),
            method("cubism.editor-model.edit-mode.begin", EditMode.class, "begin", "(Ljava/lang/String;)" + type(GroupUndo.class)),
            method("cubism.editor-model.edit-mode.end", EditMode.class, "end", "(ZLjava/lang/Object;)V"),
            method("cubism.editor-model.undo.add", GroupUndo.class, "add", "(" + type(Undo.class) + "Z)Z"),
            method("cubism.editor-model.undo.add-listener", Undo.class, "addListener", "(" + type(Listener.class) + ")Z"),
            StaticSelector.classSelector("cubism.editor-model.undo-listener.class", internal(Listener.class)),
            method("cubism.editor-model.model-source.update-instances", ModelSource.class, "updateInstances", "()V"),
            method("cubism.editor-model.model-source.all-art-meshes", ModelSource.class, "allArtMeshes", "()Ljava/util/List;"),
            method("cubism.editor-model.model.all-art-meshes", Model.class, "allArtMeshes", "()Ljava/util/List;"),
            method("cubism.editor-model.model-source.all-deformers", ModelSource.class, "allDeformers", "()Ljava/util/List;"),
            method("cubism.editor-model.model.all-deformers", Model.class, "allDeformers", "()Ljava/util/List;"),
            StaticSelector.classSelector("cubism.editor-model.art-mesh-source.class", internal(ArtMeshSource.class)),
            StaticSelector.classSelector("cubism.editor-model.art-mesh.class", internal(ArtMesh.class)),
            method("cubism.editor-model.art-mesh.source", ArtMesh.class, "source", desc(ArtMeshSource.class)),
            method("cubism.editor-model.art-mesh.current-keyform", ArtMesh.class, "currentForm", desc(ArtMeshForm.class)),
            method("cubism.editor-model.art-mesh-source.guid", ArtMeshSource.class, "guid", desc(DrawableGuid.class)),
            method("cubism.editor-model.art-mesh-source.clip-guid-list", ArtMeshSource.class, "clipGuids", "()Ljava/util/List;"),
            method("cubism.editor-model.art-mesh-source.inverted-mask", ArtMeshSource.class, "invertedMask", "()Z"),
            method("cubism.editor-model.art-mesh-source.user-data", ArtMeshSource.class, "id", desc(Id.class)),
            method("cubism.editor-model.art-mesh-source.culling", ArtMeshSource.class, "culling", "()Z"),
            method("cubism.editor-model.parameter-controllable-source.id", ObjectSource.class, "id", desc(Id.class)),
            method("cubism.editor-model.parameter-controllable-source.handler", ObjectSource.class, "handler", desc(Handler.class)),
            StaticSelector.classSelector("cubism.editor-model.parameter-controllable-handler.class", internal(Handler.class)),
            method("cubism.editor-model.parameter-controllable-handler.create-undo-for-all-edit", Handler.class, "undo", "(Ljava/lang/String;)" + type(Undo.class)),
            method("cubism.editor-model.parameter-controllable-handler.create-undo-for-basic-setting", Handler.class, "createUndoForBasicSetting", "(Ljava/lang/String;)" + type(Undo.class)),
            method("cubism.editor-model.parameter-controllable-handler.create-undo-for-keyform-edit", Handler.class, "createUndoForKeyformEdit", "(Ljava/lang/String;)" + type(Undo.class)),
            method("cubism.editor-model.parameter-controllable-handler.change-target-deformer", Handler.class, "changeTargetDeformer", "(" + type(Model.class) + type(Id.class) + ")" + type(GroupUndo.class)),
            method("cubism.editor-model.parameter-controllable-handler.change-target-deformer-guid", Handler.class, "changeTargetDeformer", "(" + type(Model.class) + type(Guid.class) + "Z)" + type(GroupUndo.class)),
            StaticSelector.constructor("cubism.editor-model.deformer-id.create", internal(Id.class), "(Ljava/lang/String;)V", StaticSelector.ACCESS_PUBLIC),
            StaticSelector.field("cubism.editor-model.deformer-guid.companion", internal(DeformerGuid.class), "Companion", type(DeformerGuid.CompanionField.class), StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC),
            method("cubism.editor-model.deformer-guid.root", DeformerGuid.CompanionField.class, "root", desc(Guid.class)),
            method("cubism.editor-model.drawable-source.set-id", ArtMeshSource.class, "setId", "(" + type(Id.class) + ")V"),
            StaticSelector.constructor("cubism.editor-model.drawable-id.create", internal(Id.class), "(Ljava/lang/String;)V", StaticSelector.ACCESS_PUBLIC),
            method("cubism.editor-model.model-source.handler", ModelSource.class, "modelHandler", desc(ModelHandler.class)),
            method("cubism.editor-model.model-handler.id-map", ModelHandler.class, "idMap", desc(IdMap.class)),
            method("cubism.editor-model.id-map.contains", IdMap.class, "contains", "(Ljava/lang/String;)Z"),
            StaticSelector.staticMethod(
                "cubism.editor-model.model-source.verify", internal(ModelSource.class), "verify$default",
                "(L" + internal(ModelSource.class) + ";ZLjava/lang/Object;ILjava/lang/Object;)V",
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC),
            method("cubism.editor-model.complete-pack.update-manager", CompletePack.class, "updateManager", desc(UpdateManager.class)),
            method("cubism.editor-model.update-manager.update-part", UpdateManager.class, "updatePart", "(Z)V"),
            method("cubism.editor-model.update-manager.update-deformer", UpdateManager.class, "updateDeformer", "(Z)V"),
            method("cubism.editor-model.model-source.get-object", ModelSource.class, "getObject", "(Ljava/lang/String;)" + type(ObjectSource.class)),
            method("cubism.editor-model.parameter-controllable-source.guid", ObjectSource.class, "guid", desc(Guid.class)),
            StaticSelector.classSelector("cubism.editor-model.drawable-guid.class", internal(DrawableGuid.class)),
            method("cubism.editor-model.id-list.clear", ArrayList.class, "clear", "()V"),
            method("cubism.editor-model.id-list.add-all", ArrayList.class, "addAll", "(Ljava/util/Collection;)Z"),
            method("cubism.editor-model.art-mesh-source.set-invert-clipping-mask", ArtMeshSource.class, "setInvertClippingMask", "(Z)V"),
            method("cubism.editor-model.model-source.target-version", ModelSource.class, "targetVersion", desc(TargetVersion.class)),
            method("cubism.editor-model.target-version.number", TargetVersion.class, "number", "()I"),
            method("cubism.editor-model.drawable-form.draw-order", Form.class, "drawOrder", "()I"),
            method("cubism.editor-model.drawable-form.set-draw-order", Form.class, "setDrawOrder", "(I)V"),
            method("cubism.editor-model.drawable-form.multiply-color", Form.class, "multiplyColor", desc(FloatColor.class)),
            method("cubism.editor-model.drawable-form.screen-color", Form.class, "screenColor", desc(FloatColor.class)),
            method("cubism.editor-model.float-color.red", FloatColor.class, "getR", "()F"),
            method("cubism.editor-model.float-color.green", FloatColor.class, "getG", "()F"),
            method("cubism.editor-model.float-color.blue", FloatColor.class, "getB", "()F"),
            method("cubism.editor-model.float-color.alpha", FloatColor.class, "getA", "()F"),
            method("cubism.editor-model.float-color.set-red", FloatColor.class, "setR", "(F)V"),
            method("cubism.editor-model.float-color.set-green", FloatColor.class, "setG", "(F)V"),
            method("cubism.editor-model.float-color.set-blue", FloatColor.class, "setB", "(F)V"),
            method("cubism.editor-model.float-color.set-alpha", FloatColor.class, "setA", "(F)V"),
            method("cubism.editor-model.art-mesh-source.set-color-composition", ArtMeshSource.class, "setColorComposition", "(" + colorCompositionType(version) + ")V"),
            StaticSelector.staticMethod("cubism.editor-model.color-composition.values", internal(colorCompositionClass(version)), "values", "()[" + colorCompositionType(version), StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC),
            method("cubism.editor-model.art-mesh-source.set-culling", ArtMeshSource.class, "setCulling", "(Z)V"),
            method("cubism.editor-model.art-mesh.setup-shader", ArtMesh.class, "setupShader", "(Ljava/util/Map;)V"),
            method("cubism.editor-model.art-mesh-source.set-user-data", ArtMeshSource.class, "setUserData", "(Ljava/lang/String;)V"),
            method("cubism.editor-model.complete-pack.update-part-palette", CompletePack.class, "updateParts", "(Z)V"),
            method("cubism.editor-model.complete-pack.update-deformer-palette", CompletePack.class, "updateDeformers", "(Z)V"),
            method("cubism.editor-model.complete-pack.repaint-canvas", CompletePack.class, "repaint", "(Z)V"),
            // read-contract aliases required by the plan (never invoked by these writes)
            method("cubism.editor-model.id.value", Id.class, "value", "()Ljava/lang/String;"),
            method("cubism.editor-model.part-id.value", Id.class, "value", "()Ljava/lang/String;"),
            method("cubism.editor-model.model-source.parts", ModelSource.class, "allArtMeshes", "()Ljava/util/List;"),
            StaticSelector.classSelector("cubism.editor-model.part-source.class", internal(ObjectSource.class)),
            method("cubism.editor-model.part-source.id", ObjectSource.class, "id", desc(Id.class)),
            method("cubism.editor-model.part-source.parent", ObjectSource.class, "id", desc(Id.class)),
            method("cubism.editor-model.model.parameter-set", Model.class, "allArtMeshes", "()Ljava/util/List;"),
            method("cubism.editor-model.parameter-set.parameters", Model.class, "allArtMeshes", "()Ljava/util/List;"),
            StaticSelector.classSelector("cubism.editor-model.parameter.class", internal(ObjectSource.class)),
            method("cubism.editor-model.parameter.id", ObjectSource.class, "id", desc(Id.class)),
            method("cubism.editor-model.parameter-controllable-source.local-name", ObjectSource.class, "id", desc(Id.class)),
            method("cubism.editor-model.parameter-controllable-source.visible", ObjectSource.class, "id", desc(Id.class)),
            method("cubism.editor-model.parameter-controllable-source.locked", ObjectSource.class, "id", desc(Id.class)),
            method("cubism.editor-model.parameter-controllable-source.visible-in-hierarchy", ObjectSource.class, "id", desc(Id.class)),
            method("cubism.editor-model.parameter-controllable-source.locked-in-hierarchy", ObjectSource.class, "id", desc(Id.class)),
            method("cubism.editor-model.parameter-controllable-source.target-deformer-source", ObjectSource.class, "id", desc(Id.class)),
            method("cubism.editor-model.drawable-form.opacity", Form.class, "drawOrder", "()I"),
            method("cubism.editor-model.art-mesh-form.positions", Form.class, "drawOrder", "()I"),
            method("cubism.editor-model.art-mesh-source.positions", ArtMeshSource.class, "id", desc(Id.class)),
            method("cubism.editor-model.art-mesh-source.uvs", ArtMeshSource.class, "id", desc(Id.class)),
            method("cubism.editor-model.art-mesh-source.indices", ArtMeshSource.class, "id", desc(Id.class)),
            StaticSelector.classSelector("cubism.editor-model.warp-source.class", internal(ObjectSource.class)),
            StaticSelector.classSelector("cubism.editor-model.warp.class", internal(ObjectSource.class)),
            StaticSelector.classSelector("cubism.editor-model.rotation-source.class", internal(ObjectSource.class)),
            StaticSelector.classSelector("cubism.editor-model.rotation.class", internal(ObjectSource.class)),
            method("cubism.editor-model.deformer.source", ObjectSource.class, "id", desc(Id.class)),
            method("cubism.editor-model.deformer.current-keyform", ObjectSource.class, "id", desc(Id.class)),
            method("cubism.editor-model.deformer-form.opacity", Form.class, "drawOrder", "()I"),
            method("cubism.editor-model.warp-source.row", ObjectSource.class, "id", desc(Id.class)),
            method("cubism.editor-model.warp-source.col", ObjectSource.class, "id", desc(Id.class)),
            method("cubism.editor-model.warp-source.quad-transform", ObjectSource.class, "id", desc(Id.class)),
            method("cubism.editor-model.warp-form.positions", Form.class, "drawOrder", "()I"),
            method("cubism.editor-model.rotation-source.base-angle", ObjectSource.class, "id", desc(Id.class)),
            method("cubism.editor-model.rotation-form.angle", Form.class, "drawOrder", "()I"),
            method("cubism.editor-model.rotation-form.origin-x", Form.class, "drawOrder", "()I"),
            method("cubism.editor-model.rotation-form.origin-y", Form.class, "drawOrder", "()I"),
            method("cubism.editor-model.rotation-form.scale", Form.class, "drawOrder", "()I"),
            method("cubism.editor-model.rotation-form.reflect-x", Form.class, "drawOrder", "()I"),
            method("cubism.editor-model.rotation-form.reflect-y", Form.class, "drawOrder", "()I"),
            method("cubism.editor-model.model-source.all-glues", ModelSource.class, "allArtMeshes", "()Ljava/util/List;"),
            StaticSelector.classSelector("cubism.editor-model.glue-source.class", internal(ObjectSource.class)),
            method("cubism.editor-model.glue-source.target-art-mesh-a", ObjectSource.class, "id", desc(Id.class)),
            method("cubism.editor-model.glue-source.target-art-mesh-b", ObjectSource.class, "id", desc(Id.class))
        ));
        if (!"5.2.0".equals(version)) {
            selectors.add(StaticSelector.staticMethod("cubism.editor-model.alpha-composition.values", internal(AlphaCompositionHost.class), "values", "()[" + alphaCompositionType(), StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
            selectors.add(method("cubism.editor-model.art-mesh-source.set-alpha-composition", ArtMeshSource.class, "setAlphaComposition", "(" + alphaCompositionType() + ")V"));
        }
        return selectors;
    }

    private static Class<?> colorCompositionClass(final String version) {
        return "5.2.0".equals(version) ? ColorCompositionHost52.class : ColorCompositionHost.class;
    }
    private static String colorCompositionType(final String version) {
        return type(colorCompositionClass(version));
    }
    private static String alphaCompositionType() { return type(AlphaCompositionHost.class); }

    private static StaticSelector method(final String alias, final Class<?> owner, final String name, final String descriptor) {
        return StaticSelector.method(alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC);
    }
    private static String internal(final Class<?> type) { return type.getName().replace('.', '/'); }
    private static String type(final Class<?> type) { return "L" + internal(type) + ";"; }
    private static String desc(final Class<?> type) { return "()" + type(type); }

    public enum ColorCompositionHost { NORMAL, ADD, MULTIPLY, ADD_R2_TSL, ADD_R2, DARKEN, MULTIPLY_R2, COLORBURN_TSL, LINEARBURN_TSL, LIGHTEN, SCREEN, COLORDODGE_TSL, OVERLAY, SOFTLIGHT, HARDLIGHT, LINEARLIGHT_TSL, HSL_HUE, HSL_COLOR }
    public enum ColorCompositionHost52 { NORMAL, ADD, MULTIPLY }
    public enum AlphaCompositionHost { OVER, ATOP, OUT, CONJOINT, DISJOINT }

    public static final class Host {
        private static final Host INSTANCE = new Host();
        static Document document;
        public static Host instance() { return INSTANCE; }
        public Document currentDocument() { return document; }
        public CompletePack completePack() { return document.pack; }
    }
    public interface Listener { void changed(Object ignored); }
    public static class Undo {
        Listener listener;
        public boolean addListener(final Listener listener) { this.listener = listener; return true; }
    }
    public static final class GroupUndo extends Undo {
        public boolean add(final Undo undo, final boolean merge) { return undo != null; }
    }
    public static final class EditMode {
        final List<GroupUndo> edits = new ArrayList<>();
        public GroupUndo begin(final String action) { final GroupUndo value = new GroupUndo(); edits.add(value); return value; }
        public void end(final boolean aborted, final Object ignored) {
            if (aborted && !edits.isEmpty()) edits.remove(edits.size() - 1);
        }
    }
    public static final class UpdateManager {
        int partCalls;
        int deformerCalls;
        public void updatePart(final boolean force) { partCalls++; }
        public void updateDeformer(final boolean force) { deformerCalls++; }
    }
    public static final class CompletePack {
        int partRefreshCount;
        int deformerRefreshCount;
        int repaintCount;
        final UpdateManager updateManager = new UpdateManager();
        public void updateParts(final boolean force) { partRefreshCount++; }
        public void updateDeformers(final boolean force) { deformerRefreshCount++; }
        public void repaint(final boolean force) { repaintCount++; }
        public UpdateManager updateManager() { return updateManager; }
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
    public static class Guid {
        final String value;
        Guid(final String value) { this.value = value; }
        public String value() { return value; }
    }
    public static final class DrawableGuid extends Guid {
        DrawableGuid(final String value) { super(value); }
    }
    public static final class Id {
        final String value;
        public Id(final String value) { this.value = value; }
        public String value() { return value; }
    }
    public static final class DeformerGuid {
        public static final CompanionField Companion = new CompanionField();
        public static final class CompanionField {
            public Guid root() { return new Guid("root"); }
        }
    }
    public static final class IdMap {
        int containsCalls;
        String checked;
        boolean contains = true;
        public boolean contains(final String value) { containsCalls++; checked = value; return contains; }
    }
    public static final class ModelHandler {
        final IdMap idMap = new IdMap();
        public IdMap idMap() { return idMap; }
    }
    public static final class TargetVersion {
        int number;
        public int number() { return number; }
    }
    public static final class FloatColor {
        final List<Float> values = new ArrayList<>(List.of(1.0F, 1.0F, 1.0F, 1.0F));
        public float getR() { return values.get(0); }
        public float getG() { return values.get(1); }
        public float getB() { return values.get(2); }
        public float getA() { return values.get(3); }
        public void setR(final float v) { values.set(0, v); }
        public void setG(final float v) { values.set(1, v); }
        public void setB(final float v) { values.set(2, v); }
        public void setA(final float v) { values.set(3, v); }
    }
    public static class ObjectSource {
        final Id id;
        final Handler handler = new Handler();
        Guid guid;
        ObjectSource(final String id) {
            this.id = new Id(id);
            this.guid = new Guid("guid:" + id);
        }
        public Id id() { return id; }
        public Handler handler() { return handler; }
        public Guid guid() { return guid; }
    }
    public static class Form {
        int drawOrder;
        final FloatColor multiply = new FloatColor();
        final FloatColor screen = new FloatColor();
        public int drawOrder() { return drawOrder; }
        public void setDrawOrder(final int value) { drawOrder = value; }
        public FloatColor multiplyColor() { return multiply; }
        public FloatColor screenColor() { return screen; }
    }
    public static final class ArtMeshSource extends ObjectSource {
        final Failures failures;
        final List<DrawableGuid> clipGuids = new ArrayList<>();
        final ModelHandler modelHandler = new ModelHandler();
        int idCalls;
        String idValue;
        int verifyCalls;
        int invertCalls;
        boolean invertValue;
        int cullingCalls;
        boolean cullingValue = true;
        String userDataValue;
        String colorCompositionValue;
        String alphaCompositionValue;
        boolean invertedMask;
        ArtMeshSource(final String id, final Failures failures) {
            super(id);
            this.failures = failures;
            this.guid = new DrawableGuid("guid:" + id);
            this.invertedMask = "ArtMeshFace".equals(id);
        }
        public boolean invertedMask() { return invertedMask; }
        public boolean culling() { return cullingValue; }
        public List<?> clipGuids() { return clipGuids; }
        public void setId(final Id id) { failures.setter(); idCalls++; idValue = id.value(); }
        public void setInvertClippingMask(final boolean value) { failures.setter(); invertCalls++; invertValue = value; invertedMask = value; }
        public void setCulling(final boolean value) { failures.setter(); cullingCalls++; cullingValue = value; }
        public void setUserData(final String value) { failures.setter(); userDataValue = value; }
        public void setColorComposition(final ColorCompositionHost value) { failures.setter(); colorCompositionValue = value.toString(); }
        public void setColorComposition(final ColorCompositionHost52 value) { failures.setter(); colorCompositionValue = value.toString(); }
        public void setAlphaComposition(final AlphaCompositionHost value) { failures.setter(); alphaCompositionValue = value.toString(); }
    }
    public static final class ArtMesh {
        final ArtMeshSource source;
        final ArtMeshForm form;
        int setupShaderCalls;
        ArtMesh(final ArtMeshSource source) { this.source = source; this.form = new ArtMeshForm(); }
        public ArtMeshSource source() { return source; }
        public ArtMeshForm currentForm() { return form; }
        public void setupShader(final java.util.Map<?, ?> ignored) { source.failures.setter(); setupShaderCalls++; }
    }
    public static final class ArtMeshForm extends Form {
    }
    public static final class ModelSource {
        final Id guid = new Id("model-a");
        final List<ArtMeshSource> artMeshSources = new ArrayList<>();
        final List<ObjectSource> deformerSources = new ArrayList<>();
        Model model;
        int updateCount;
        int targetVersionNumber = 4_020_000;
        int getObjectCalls;
        public List<ArtMeshSource> allArtMeshes() { return artMeshSources; }
        public List<ObjectSource> allDeformers() { return deformerSources; }
        public Id guid() { return guid; }
        public Model currentInstance() { return model; }
        public void updateInstances() { updateCount++; }
        public static void verify$default(final ModelSource source, final boolean flag, final Object ignored, final int mask, final Object unused) {
            Fixture.current.source.artMeshSources.get(0).failures.setter();
            Fixture.current.source.artMeshSources.get(0).verifyCalls++;
        }
        public TargetVersion targetVersion() { final TargetVersion version = new TargetVersion(); version.number = targetVersionNumber; return version; }
        public ObjectSource getObject(final String id) {
            getObjectCalls++;
            for (ArtMeshSource source : artMeshSources) {
                if (source.id.value.equals(id)) return source;
            }
            for (ObjectSource source : deformerSources) {
                if (source.id.value.equals(id)) return source;
            }
            return null;
        }
        public ModelHandler modelHandler() { return artMeshSources.get(0).modelHandler; }
    }
    public static final class Model {
        final List<ArtMesh> artMeshes = new ArrayList<>();
        final List<ObjectSource> deformerSources = new ArrayList<>();
        public List<ArtMesh> allArtMeshes() { return artMeshes; }
        public List<ObjectSource> allDeformers() { return deformerSources; }
    }
    public static final class Handler {
        int basicSettingUndoCount;
        int keyformUndoCount;
        int rootGuidCalls;
        int lastRootTargetCalls;
        String lastTargetDeformerId;
        Undo lastUndo;
        final Failures failures = new Failures();
        public Undo undo(final String action) { return createUndoForBasicSetting(action); }
        public Undo createUndoForBasicSetting(final String action) { basicSettingUndoCount++; return track(new Undo()); }
        public Undo createUndoForKeyformEdit(final String action) { keyformUndoCount++; return track(new Undo()); }
        private Undo track(final Undo undo) { lastUndo = undo; return undo; }
        public GroupUndo changeTargetDeformer(final Model model, final Id deformerId) {
            failures.setter();
            lastTargetDeformerId = deformerId.value;
            return new GroupUndo();
        }
        public GroupUndo changeTargetDeformer(final Model model, final Guid guid, final boolean update) {
            failures.setter();
            rootGuidCalls++;
            if ("root".equals(guid.value)) lastRootTargetCalls++;
            return new GroupUndo();
        }
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

    private static final class Fixture {
        static Fixture current;
        final Failures failures = new Failures();
        final ModelSource source = new ModelSource();
        final Document document = new Document(source);
        final ArtMeshSource meshSource;
        final ArtMesh mesh;
        final Handler handler;
        Fixture() {
            current = this;
            final ArtMeshSource maskSource = new ArtMeshSource("ArtMeshMask", failures);
            meshSource = new ArtMeshSource("ArtMeshFace", failures);
            maskSource.clipGuids.add((DrawableGuid) maskSource.guid);
            source.artMeshSources.add(meshSource);
            source.artMeshSources.add(maskSource);
            source.deformerSources.add(new ObjectSource("WarpFace"));
            source.deformerSources.add(new ObjectSource("RotationHead"));
            source.deformerSources.add(new ObjectSource("PartFace"));
            mesh = new ArtMesh(meshSource);
            source.model = new Model();
            source.model.artMeshes.add(mesh);
            source.model.artMeshes.add(new ArtMesh(maskSource));
            handler = meshSource.handler;
            meshSource.modelHandler.idMap.contains = false;
        }
        void resetPublishedEffects() {
            failures.reset();
            document.editMode.edits.clear();
            document.dirty = false;
            source.updateCount = 0;
            document.pack.partRefreshCount = 0;
            document.pack.deformerRefreshCount = 0;
            document.pack.repaintCount = 0;
        }
    }
}
