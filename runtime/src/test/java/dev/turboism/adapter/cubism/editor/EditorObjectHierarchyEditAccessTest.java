package dev.turboism.adapter.cubism.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.editor.history.EditorHistorySnapshotProvider;
import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.adapter.cubism.editor.transaction.EditorRefreshRequirement;
import dev.turboism.adapter.cubism.editor.transaction.RuntimeAuthoringTransactionProvider;
import dev.turboism.adapter.cubism.model.RuntimeModelObjectService;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorDeformerInspectorSelectorContract;
import dev.turboism.mapping.verification.selector.EditorHistoryReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorInspectorDrawableWriteNoAlphaCompositionSelectorContract;
import dev.turboism.mapping.verification.selector.EditorInspectorDrawableWriteSelectorContract;
import dev.turboism.mapping.verification.selector.EditorObjectHierarchyEditSelectorContract;
import dev.turboism.mapping.verification.selector.EditorObjectReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartNameSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartStructureSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartTreeSelectorContract;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryChange;
import dev.turboism.sdk.cubism.history.HistoryEntry;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistoryRelationChange;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.ModelObjectCreateRequest;
import dev.turboism.sdk.cubism.model.ModelObjectKind;
import dev.turboism.sdk.cubism.model.ModelObjectReference;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.RotationDeformerForm;
import dev.turboism.sdk.cubism.model.WarpGrid;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Fixture-based verification of the object-hierarchy editing projection (Part/Deformer/Drawable
 * create, delete, rename, reparent) against both Cubism 5.2.0 and 5.3.02 host shapes.
 *
 * <p>The fixture classes mirror the exact native surface verified by javap on both JARs:
 * {@code CPartSourceSet.add/remove}, {@code CDeformerSourceSet.add/remove},
 * {@code CDrawableSourceSet.remove}, {@code CPartSource.addChild/removeChild},
 * {@code ACParameterControllableSource.internal_setParent/setTargetDeformerGuid/...},
 * {@code CEUpdateManager.setSelection}, {@code CEAppCtrl.command_delete}, and the documented
 * constructors. The fixture records which native member was invoked so the tests can prove that
 * child semantics (Part cascade, Deformer child re-parent) are DELEGATED to the native delete
 * command and never re-implemented by the adapter.</p>
 */
class EditorObjectHierarchyEditAccessTest {

    @Test
    void creates5302DefaultPartInRootTreeAndRestoresSiblingOrder() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver("5.3.02"), "session-a").active();
        final var siblings = List.copyOf(fixture.rootPart.children);
        final var created = model.parts().create("Default Parent", null, 0);
        final var id = created.id();
        final var source = fixture.partSet.sources.stream()
                .filter(value -> value.id.value().equals(created.id().value()))
                .findFirst()
                .orElseThrow();
        assertEquals("Root", created.parentId().orElseThrow().value());
        assertNull(fixture.rootPart.currentUndo, "native insertion must not snapshot the entire parent");
        assertNull(source.currentUndo, "native insertion owns registry and tree together");
        assertEquals(source, fixture.rootPart.children.get(0));
        assertEquals(siblings, fixture.rootPart.children.subList(1, fixture.rootPart.children.size()));
        assertEquals(1, fixture.editMode.edits.size());
        fixture.editMode.edits.get(0).undo();
        assertEquals(siblings, fixture.rootPart.children);
        assertFalse(fixture.partSet.sources.contains(source));
        fixture.editMode.edits.get(0).redo();
        assertEquals(source, fixture.rootPart.children.get(0));
        assertEquals("Root", model.parts().find(id).parentId().orElseThrow().value());
    }

    @Test
    void rejects5302DefaultPartWhenRootIsMissingBeforeAnyWrite() {
        final Fixture fixture = new Fixture();
        fixture.source.rootPart = null;
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver("5.3.02"), "session-a").active();
        final var original = List.copyOf(fixture.partSet.sources);
        assertThrows(IllegalStateException.class, () -> model.parts().create("Missing root", null, -1));
        assertEquals(original, fixture.partSet.sources);
        assertTrue(fixture.editMode.edits.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.0.00", "5.0.06", "5.1.03", "5.2.00", "5.2.03", "5.3.00", "5.3.04"})
    void createsPartWithParentIndexUndoAndRefreshCounts(final String cubismVersion) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver(cubismVersion), "session-a").active();

        final var root = model.parts().find(new PartId("Root"));
        final var created = model.parts().create("New Part", root, -1);

        assertEquals("New Part", created.name());
        assertEquals("Root", created.parentId().orElseThrow().value());
        assertEquals(4, model.parts().all().size());
        assertEquals(1, fixture.editMode.edits.size());
        assertEquals(1, fixture.source.updateCount);
        assertEquals(1, fixture.pack.partRefreshCount);
        assertEquals(1, fixture.pack.repaintCount);
        assertTrue(fixture.document.dirty);
        assertEquals(List.of("Root", "Parent", "Child", "New Part"), fixture.partSet.addLog);
        assertEquals(1, fixture.rootPart.addChildCalls);

        // Undo removes the created Part; redo re-creates it.
        fixture.editMode.edits.get(0).undo();
        assertEquals(3, model.parts().all().size());
        assertThrows(
                NoSuchElementException.class,
                () -> model.parts().find(new PartId(created.id().value())));
        fixture.editMode.edits.get(0).redo();
        assertEquals(4, model.parts().all().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.0.00", "5.0.06", "5.1.03", "5.2.00", "5.2.03", "5.3.00", "5.3.04"})
    void createsWarpWithGridAndRotationDeformerUnderPart(final String cubismVersion) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver(cubismVersion), "session-a").active();

        final var warp = model.deformers().createWarp("New Warp", null, -1, 4, 3);
        assertEquals("New Warp", warp.name());
        assertEquals(4, warp.grid().rows());
        assertEquals(3, warp.grid().columns());
        assertEquals(2, model.warpDeformers().all().size());
        // Both reviewed versions attach through the native Part composition undo
        // (no registry add-source).
        final int registryAddsAfterWarp = 0;
        assertEquals(registryAddsAfterWarp, fixture.deformerSet.addCount);
        final WarpDeformerSource createdWarp = (WarpDeformerSource) fixture.source.deformerSet.sources.get(2);
        assertEquals(List.of(4, 3), createdWarp.rowColLog);

        final var rotation = model.deformers().createRotation("New Rotation", null, -1);
        assertEquals("New Rotation", rotation.name());
        assertEquals(2, model.rotationDeformers().all().size());
        assertEquals(registryAddsAfterWarp * 2, fixture.deformerSet.addCount);
        assertEquals(2, fixture.editMode.edits.size());

        // Undo removes the created Rotation; redo re-creates it.
        fixture.editMode.edits.get(1).undo();
        assertEquals(1, model.rotationDeformers().all().size());
        fixture.editMode.edits.get(1).redo();
        assertEquals(2, model.rotationDeformers().all().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.0.00", "5.0.06", "5.1.03", "5.2.00", "5.2.03", "5.3.00", "5.3.04"})
    void createsArtMeshWithExplicitGeometryAndUndo(final String cubismVersion) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver(cubismVersion), "session-a").active();
        final ArtMeshGeometry geometry = new ArtMeshGeometry(
                List.of(new Point2(-1F, -1F), new Point2(1F, -1F), new Point2(0F, 1F)),
                List.of(new Point2(0F, 0F), new Point2(1F, 0F), new Point2(0.5F, 1F)),
                List.of(0, 1, 2));

        final var created = model.drawables().create("New Mesh", model.parts().find(new PartId("Root")), -1, geometry);

        assertEquals("New Mesh", created.name());
        assertEquals("Root", created.parentPartId().orElseThrow().value());
        assertEquals(geometry, created.geometry());
        assertEquals(2, model.drawables().all().size());
        assertEquals(1, fixture.editMode.edits.size());
        assertEquals(1, fixture.source.updateCount);
        assertEquals(1, fixture.pack.partRefreshCount);
        assertTrue(fixture.document.dirty);

        fixture.editMode.edits.get(0).undo();
        assertEquals(1, model.drawables().all().size());
        fixture.editMode.edits.get(0).redo();
        assertEquals(2, model.drawables().all().size());
    }

    /** 5.3.02 rebuilds instances from the Part tree, not from the source registry. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void creates5302ArtMeshThroughPartTreeWithSingleUndo(final boolean explicitPart) {
        final Fixture fixture = new Fixture();
        fixture.source.treeMaterialization = true;
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver("5.3.02"), "session-a").active();
        final var siblings = List.copyOf(fixture.rootPart.children);
        final var geometry = nativeGeometry();
        final var created = model.drawables()
                .create("Native Mesh", explicitPart ? model.parts().find(new PartId("Root")) : null, 0, geometry);
        final var id = created.id();
        final var source = fixture.drawableSet.sources.stream()
                .filter(value -> value.id.value().equals(id.value()))
                .findFirst()
                .orElseThrow();
        assertEquals(geometry, created.geometry());
        assertEquals(source, fixture.rootPart.children.get(0));
        assertEquals(fixture.rootPart, source.parent);
        assertEquals(siblings, fixture.rootPart.children.subList(1, fixture.rootPart.children.size()));
        assertEquals(1, fixture.editMode.edits.size());
        // Native attachment owns BOTH membership changes. A separate add-source undo double-deletes.
        assertEquals(1, fixture.editMode.edits.get(0).children.size());
        for (int round = 0; round < 3; round++) {
            fixture.editMode.edits.get(0).undo();
            assertEquals(siblings, fixture.rootPart.children);
            assertFalse(fixture.drawableSet.sources.contains(source));
            assertEquals(null, source.parent);
            assertEquals(1, model.drawables().all().size());
            fixture.editMode.edits.get(0).redo();
            assertEquals(source, fixture.rootPart.children.get(0));
            assertEquals(fixture.rootPart, source.parent);
            assertEquals(2, model.drawables().all().size());
            assertEquals(geometry, model.drawables().find(id).geometry());
        }
        assertEquals(7, fixture.source.updateCount);
        assertTrue(fixture.document.dirty);
    }

    @Test
    void selectsExactMeshWithoutChangingDataOrHistoryAndRejectsInvalidIds() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver("5.3.02"), "session-a").active();
        final var mesh = model.drawables().all().get(0);
        model.drawables().select(List.of(mesh.id()));
        assertEquals(List.of(fixture.meshSource), fixture.document.selector.selected());
        assertEquals(1, fixture.updateManager.selectionCalls.size());
        assertEquals(
                fixture.document, fixture.updateManager.selectionCalls.get(0).source());
        assertEquals(
                List.of(fixture.meshSource.guid),
                fixture.updateManager.selectionCalls.get(0).guids());
        assertTrue(
                fixture.updateManager.selectionCalls.get(0).notifyListeners(),
                "native selector listeners must receive the new GUIDs");
        assertEquals(0, fixture.editMode.beginCalls);
        assertFalse(fixture.document.dirty);
        assertThrows(IllegalArgumentException.class, () -> model.drawables().select(List.of(mesh.id(), mesh.id())));
        assertThrows(
                java.util.NoSuchElementException.class,
                () -> model.drawables().select(List.of(new ArtMeshId("absent"))));
        assertEquals(1, fixture.updateManager.selectionCalls.size());
        model.drawables().select(List.of());
        assertTrue(fixture.updateManager.selectionCalls.get(1).guids().isEmpty());
        assertTrue(fixture.document.selector.selected().isEmpty());
    }

    @Test
    void keepsUnfoldedSourceSeparateFromCollapsedDefaultAcrossUndoRedo() {
        final Fixture fixture = new Fixture();
        fixture.source.treeMaterialization = true;
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver("5.3.02"), "session-a").active();
        final var geometry = nativeGeometry();
        final var neutral =
                geometry.positions().stream().map(p -> new Point2(0, p.y())).toList();
        final var created = model.drawables().createWithSourceGeometry("Edge-on Face", null, -1, geometry, neutral);
        final var id = created.id();
        final var source = fixture.drawableSet.sources.stream()
                .filter(value -> value.id.value().equals(id.value()))
                .findFirst()
                .orElseThrow();
        final var form = (ArtMeshForm) source.keyforms.first();
        final float[] sourceBefore = source.positions();
        final float[] formBefore = form.positions();
        assertFalse(java.util.Arrays.equals(sourceBefore, formBefore));
        final var editable = source.extensions.get(0);
        assertEquals(source, editable.owner());
        assertFalse(editable.locked());
        org.junit.jupiter.api.Assertions.assertArrayEquals(sourceBefore, editable.mesh().positions);
        org.junit.jupiter.api.Assertions.assertArrayEquals(source.indices(), editable.mesh().indices);

        assertEquals(neutral, created.geometry().positions());
        assertEquals(1, fixture.editMode.edits.size());
        for (int round = 0; round < 3; round++) {
            fixture.editMode.edits.get(0).undo();
            assertFalse(fixture.drawableSet.sources.contains(source));
            fixture.editMode.edits.get(0).redo();
            assertTrue(fixture.drawableSet.sources.contains(source));
            org.junit.jupiter.api.Assertions.assertArrayEquals(sourceBefore, source.positions());
            org.junit.jupiter.api.Assertions.assertArrayEquals(formBefore, form.positions());
            assertEquals(neutral, model.drawables().find(id).geometry().positions());
            assertEquals(List.of(editable), source.extensions);
            org.junit.jupiter.api.Assertions.assertArrayEquals(sourceBefore, editable.mesh().positions);
        }
    }

    @Test
    void rejectsMismatchedCoordinateDomainsBeforeCreatingOrRecordingUndo() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver("5.3.02"), "session-a").active();
        final var sources = List.copyOf(fixture.drawableSet.sources);
        assertThrows(
                IllegalArgumentException.class,
                () -> model.drawables()
                        .createWithSourceGeometry("Bad Face", null, -1, nativeGeometry(), List.of(new Point2(0, 0))));
        assertEquals(sources, fixture.drawableSet.sources);
        assertTrue(fixture.editMode.edits.isEmpty());
        assertFalse(fixture.document.dirty);
    }

    /** 5.3.02 Warp creation attaches through the Part tree with a single native Undo member. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void creates5302WarpThroughPartTreeWithSingleUndo(final boolean explicitPart) {
        final Fixture fixture = new Fixture();
        fixture.source.treeMaterialization = true;
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver("5.3.02"), "session-a").active();
        final var siblings = List.copyOf(fixture.rootPart.children);
        final var created = model.deformers()
                .createWarp("Native Warp", explicitPart ? model.parts().find(new PartId("Root")) : null, 0, 4, 3);
        final var id = created.id();
        final ACDeformerSource source = fixture.deformerSet.sources.stream()
                .filter(value -> value.id.value().equals(id.value()))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of(4, 3), ((WarpDeformerSource) source).rowColLog);
        assertEquals(source, fixture.rootPart.children.get(0));
        assertEquals(fixture.rootPart, source.parent);
        assertEquals(siblings, fixture.rootPart.children.subList(1, fixture.rootPart.children.size()));
        assertEquals(1, fixture.editMode.edits.size());
        // Native attachment owns BOTH membership changes. A separate add-source undo double-deletes.
        assertEquals(1, fixture.editMode.edits.get(0).children.size());
        for (int round = 0; round < 3; round++) {
            fixture.editMode.edits.get(0).undo();
            assertEquals(siblings, fixture.rootPart.children);
            assertFalse(fixture.deformerSet.sources.contains(source));
            assertEquals(null, source.parent);
            assertEquals(1, model.warpDeformers().all().size());
            fixture.editMode.edits.get(0).redo();
            assertEquals(source, fixture.rootPart.children.get(0));
            assertEquals(fixture.rootPart, source.parent);
            assertEquals(2, model.warpDeformers().all().size());
            assertEquals(List.of(4, 3), ((WarpDeformerSource) source).rowColLog);
        }
        assertEquals(7, fixture.source.updateCount);
        assertTrue(fixture.document.dirty);
    }

    @ParameterizedTest
    @ValueSource(strings = {"root", "handler"})
    void rejectsMissing5302RootBeforeBeginningEdit(final String missing) {
        final Fixture fixture = new Fixture();
        fixture.source.treeMaterialization = true;
        if (missing.equals("root")) fixture.source.rootPart = null;
        else fixture.rootPart.handlerUnavailable = true;
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver("5.3.02"), "session-a").active();
        assertThrows(
                IllegalStateException.class, () -> model.drawables().create("Native Mesh", null, -1, nativeGeometry()));
        assertEquals(0, fixture.editMode.beginCalls);
        assertEquals(1, fixture.drawableSet.sources.size());
        assertFalse(fixture.document.dirty);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "model-source.root-part",
                "part-handler.class",
                "part-handler.add-part-child",
                "editable-mesh-extension.class",
                "editable-mesh-extension.create",
                "editable-mesh-extension.mesh",
                "editable-mesh-extension.owner",
                "editable-mesh-extension.locked",
                "editable-mesh.init-by-indices",
                "parameter-controllable-source.add-extension"
            })
    void rejectsMissing5302AttachmentAliasBeforeBeginningEdit(final String missing) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(
                        resolver("5.3.02", "cubism.editor-model." + missing), "session-a")
                .active();
        assertThrows(
                UnsupportedOperationException.class,
                () -> model.drawables().create("Native Mesh", null, -1, nativeGeometry()));
        assertEquals(0, fixture.editMode.beginCalls);
        assertEquals(1, fixture.drawableSet.sources.size());
        assertFalse(fixture.document.dirty);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "model-source.root-part",
                "part-handler.class",
                "part-handler.add-part-child",
                "undo.execute-undo"
            })
    void rejectsMissing5302PartInsertionAliasBeforeBeginningEdit(final String missing) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(
                        resolver("5.3.02", "cubism.editor-model." + missing), "session-a")
                .active();
        final var original = List.copyOf(fixture.partSet.sources);
        // Aliases shared with REQUIRED_ALIASES fail the access-authorization gate; aliases only
        // needed for 5.3.02 Part insertion fail the insertion gate. Both reject before begin.
        final Class<? extends RuntimeException> expected =
                missing.startsWith("part-handler.") ? UnsupportedOperationException.class : IllegalStateException.class;
        assertThrows(expected, () -> model.parts().create("Native Part", null, -1));
        assertEquals(original, fixture.partSet.sources);
        assertEquals(0, fixture.editMode.beginCalls);
        assertFalse(fixture.document.dirty);
    }

    private static ArtMeshGeometry nativeGeometry() {
        return new ArtMeshGeometry(
                List.of(new Point2(16, 24), new Point2(272, 24), new Point2(272, 152), new Point2(16, 152)),
                List.of(new Point2(0, 0), new Point2(1, 0), new Point2(1, 1), new Point2(0, 1)),
                List.of(0, 1, 2, 0, 2, 3));
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.0.00", "5.0.06", "5.1.03", "5.2.00", "5.2.03", "5.3.00", "5.3.04"})
    void serviceCreatesExactDeformersUnderADeformerInOneUndoEach(final String cubismVersion) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var access = new EditorBackedCubismModelAccess(resolver(cubismVersion), "session-a");
        final RuntimeModelObjectService service =
                new RuntimeModelObjectService(access, PermissionChecker.allowAll(), () -> true);
        final ModelObjectReference rotationParent =
                new ModelObjectReference(ModelObjectKind.ROTATION_DEFORMER, "RotationA");
        final WarpGrid grid = new WarpGrid(
                1,
                2,
                true,
                List.of(
                        new Point2(-2F, -1F),
                        new Point2(0F, -1F),
                        new Point2(2F, -1F),
                        new Point2(-2F, 1F),
                        new Point2(0F, 1F),
                        new Point2(2F, 1F)));

        final var warp = service.create(
                new ModelObjectCreateRequest.WarpDeformer("Exact Warp", java.util.Optional.of(rotationParent), grid));
        final RotationDeformerForm form = new RotationDeformerForm(15F, 2F, 3F, 1.5F, true, false);
        final var rotation = service.create(new ModelObjectCreateRequest.RotationDeformer(
                "Exact Rotation",
                java.util.Optional.of(new ModelObjectReference(ModelObjectKind.WARP_DEFORMER, "WarpA")),
                form));

        assertEquals(2, fixture.editMode.edits.size(), "each create must commit one Undo");
        assertEquals(
                grid,
                access.active()
                        .warpDeformers()
                        .find(new DeformerId(warp.reference().id()))
                        .grid());
        assertEquals(
                form,
                access.active()
                        .rotationDeformers()
                        .find(new DeformerId(rotation.reference().id()))
                        .form());
        assertEquals(
                java.util.Optional.of(new DeformerId("RotationA")),
                access.active()
                        .warpDeformers()
                        .find(new DeformerId(warp.reference().id()))
                        .parentDeformerId());
        assertEquals(
                java.util.Optional.of(new DeformerId("WarpA")),
                access.active()
                        .rotationDeformers()
                        .find(new DeformerId(rotation.reference().id()))
                        .parentDeformerId());
    }

    @Test
    void createPreflightFailureDoesNotBeginAnEmptyUndo() {
        final Fixture fixture = new Fixture();
        fixture.rootPart.handlerUnavailable = true;
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver("5.3.02"), "session-a").active();

        assertThrows(
                IllegalStateException.class,
                () -> model.parts().create("Blocked Part", model.parts().find(new PartId("Root")), -1));

        assertEquals(0, fixture.editMode.beginCalls);
        assertEquals(0, fixture.editMode.edits.size());
        assertEquals(3, model.parts().all().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.0.00", "5.0.06", "5.1.03", "5.2.00", "5.2.03", "5.3.00", "5.3.04"})
    void deletesPartDeformerDrawableThroughNativeSelectionAndDeleteCommand(final String cubismVersion) {
        final Fixture fixture = new Fixture();
        fixture.document.requireNativeDeleteSelection = "5.3.02".equals(cubismVersion);
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver(cubismVersion), "session-a").active();

        model.parts().remove(model.parts().find(new PartId("Child")));

        // The adapter selected the object natively and invoked the native DELETE command; it did
        // NOT call any source-set remove or removeChild itself (child cascade is host-owned).
        assertEquals(1, fixture.updateManager.selectionCalls.size());
        assertEquals(
                fixture.document, fixture.updateManager.selectionCalls.get(0).source());
        assertEquals(
                List.of(fixture.childPart.guid),
                fixture.updateManager.selectionCalls.get(0).guids());
        assertTrue(fixture.updateManager.selectionCalls.get(0).notifyListeners());
        assertEquals(1, fixture.document.deleteCount);
        assertEquals(0, fixture.partSet.removedDirectly);
        assertEquals(1, fixture.partSet.removedByCommand);
        assertEquals(0, fixture.parentPart.removeChildCalls);

        // Deformer delete follows the same native path.
        model.deformers().remove(model.warpDeformers().find(new DeformerId("WarpA")));
        assertEquals(2, fixture.document.deleteCount);
        assertEquals(0, fixture.deformerSet.removedDirectly);
        assertEquals(1, fixture.deformerSet.removedByCommand);

        // Drawable delete follows the same native path.
        model.drawables().remove(model.drawables().find(new ArtMeshId("MeshA")));
        assertEquals(3, fixture.document.deleteCount);
        assertEquals(1, fixture.drawableSet.removedByCommand);

        assertEquals(3, fixture.editMode.edits.size());
        assertEquals(3, fixture.editMode.beginCalls, "only the native commands begin edits");
        assertFalse(fixture.editMode.active);
        fixture.editMode.edits.get(2).undo();
        assertTrue(fixture.drawableSet.sources.contains(fixture.meshSource));
        fixture.editMode.edits.get(2).redo();
        assertFalse(fixture.drawableSet.sources.contains(fixture.meshSource));
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.0.00", "5.0.06", "5.1.03", "5.2.00", "5.2.03", "5.3.00", "5.3.04"})
    void renamesDeformerAndDrawableThroughSharedLocalNameSelector(final String cubismVersion) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver(cubismVersion), "session-a").active();

        final var warp = model.warpDeformers().find(new DeformerId("WarpA"));
        warp.setName("Renamed Warp");
        assertEquals("Renamed Warp", warp.name());
        assertEquals("Renamed Warp", fixture.warpSource.localName);

        final var mesh = model.drawables().find(new ArtMeshId("MeshA"));
        mesh.setName("Renamed Mesh");
        assertEquals("Renamed Mesh", mesh.name());
        assertEquals("Renamed Mesh", fixture.meshSource.localName);

        assertEquals(2, fixture.editMode.edits.size());
        assertEquals(2, fixture.source.updateCount);
        assertEquals(2, fixture.pack.partRefreshCount);
        assertEquals(2, fixture.pack.repaintCount);
        assertTrue(fixture.document.dirty);

        // No-op elision: renaming to the same value adds no edit.
        mesh.setName("Renamed Mesh");
        assertEquals(2, fixture.editMode.edits.size());

        // Undo restores the previous names; redo re-applies them.
        fixture.editMode.edits.get(1).undo();
        assertEquals("MeshA", fixture.meshSource.localName);
        fixture.editMode.edits.get(1).redo();
        assertEquals("Renamed Mesh", fixture.meshSource.localName);
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.0.00", "5.0.06", "5.1.03", "5.2.00", "5.2.03", "5.3.00", "5.3.04"})
    void reparentsPartToPartThroughNativeAddChildDetach(final String cubismVersion) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver(cubismVersion), "session-a").active();

        final var root = model.parts().find(new PartId("Root"));
        final var child = model.parts().find(new PartId("Child"));
        child.setParent(root, 0);

        // Native addChild was invoked on the new parent; the fixture's host-side addChild natively
        // detached the node from its old parent (removeChild + internal_setParent).
        assertEquals(1, fixture.rootPart.addChildCalls);
        assertEquals(1, fixture.parentPart.removeChildCalls);
        assertEquals("Root", child.parentId().orElseThrow().value());
        assertEquals(1, fixture.editMode.edits.size());

        fixture.editMode.edits.get(0).undo();
        assertEquals("Parent", child.parentId().orElseThrow().value());
        fixture.editMode.edits.get(0).redo();
        assertEquals("Root", child.parentId().orElseThrow().value());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.0.00", "5.0.06", "5.1.03", "5.2.00", "5.2.03", "5.3.00", "5.3.04"})
    void reparentsDeformerToPartAndToDeformer(final String cubismVersion) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver(cubismVersion), "session-a").active();

        final var warp = model.warpDeformers().find(new DeformerId("WarpA"));
        final var rotation = model.rotationDeformers().find(new DeformerId("RotationA"));

        // Deformer → Part: native addChild on the target Part.
        warp.setParent(model.parts().find(new PartId("Child")), -1);
        assertEquals("Child", warp.parentPartId().orElseThrow().value());
        assertEquals(1, fixture.childPart.addChildCalls);

        // Deformer → Deformer: native target-deformer relation (setTargetDeformerGuid).
        warp.setParent(rotation, -1);
        assertEquals("RotationA", warp.parentDeformerId().orElseThrow().value());
        assertEquals(fixture.rotationSource.guid, fixture.warpSource.targetDeformerGuid);
        assertEquals(1, fixture.warpSource.setTargetCalls);

        assertEquals(2, fixture.editMode.edits.size());

        // Undo of the deformer→deformer move restores the previous target; redo re-applies.
        fixture.editMode.edits.get(1).undo();
        assertTrue(warp.parentDeformerId().isEmpty());
        fixture.editMode.edits.get(1).redo();
        assertEquals("RotationA", warp.parentDeformerId().orElseThrow().value());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.0.00", "5.0.06", "5.1.03", "5.2.00", "5.2.03", "5.3.00", "5.3.04"})
    void reparentsDrawableToPartAndToDeformer(final String cubismVersion) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver(cubismVersion), "session-a").active();

        final var mesh = model.drawables().find(new ArtMeshId("MeshA"));

        // Drawable → Part: native addChild on the target Part.
        mesh.setParent(model.parts().find(new PartId("Parent")), -1);
        assertEquals("Parent", mesh.parentPartId().orElseThrow().value());

        // Drawable → Deformer: native target-deformer relation.
        mesh.setParent(model.warpDeformers().find(new DeformerId("WarpA")), -1);
        assertEquals("WarpA", mesh.parentDeformerId().orElseThrow().value());
        assertEquals(fixture.warpSource.guid, fixture.meshSource.targetDeformerGuid);

        assertEquals(2, fixture.editMode.edits.size());
        fixture.editMode.edits.get(1).undo();
        assertTrue(mesh.parentDeformerId().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.0.00", "5.0.06", "5.1.03", "5.2.00", "5.2.03", "5.3.00", "5.3.04"})
    void rejectsCyclesBeforeNativeInvocation(final String cubismVersion) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver(cubismVersion), "session-a").active();

        // Part cycle: set Root under its own descendant Child.
        final var root = model.parts().find(new PartId("Root"));
        final var child = model.parts().find(new PartId("Child"));
        assertThrows(IllegalArgumentException.class, () -> root.setParent(child, -1));
        assertEquals(0, fixture.rootPart.addChildCalls);
        assertEquals(0, fixture.editMode.edits.size());

        // Deformer cycle: first move WarpA under RotationA (legitimate), then attempt to move
        // RotationA under WarpA — the native ancestor walk must reject it before any mutation.
        final var warp = model.warpDeformers().find(new DeformerId("WarpA"));
        final var rotation = model.rotationDeformers().find(new DeformerId("RotationA"));
        warp.setParent(rotation, -1);
        assertThrows(IllegalArgumentException.class, () -> rotation.setParent(warp, -1));
        assertEquals(1, fixture.warpSource.setTargetCalls);
        assertEquals(0, fixture.rotationSource.setTargetCalls);
        assertEquals(1, fixture.editMode.edits.size());
    }

    @Test
    void failsClosedBeforeSelectorUseWithoutHierarchyCapability() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var access = new EditorBackedCubismModelAccess(resolverWithoutHierarchyCapability(), "session-a");
        final var part = access.active().parts().find(new PartId("Root"));
        final var root = access.active().parts().find(new PartId("Root"));
        assertThrows(UnsupportedOperationException.class, () -> part.setParent(root, -1));
        assertThrows(
                UnsupportedOperationException.class,
                () -> access.active().parts().remove(part));
        assertThrows(
                UnsupportedOperationException.class,
                () -> access.active().parts().create("X", null, -1));
        assertThrows(
                UnsupportedOperationException.class,
                () -> access.active()
                        .warpDeformers()
                        .find(new DeformerId("WarpA"))
                        .setName("X"));
        assertEquals(0, fixture.editMode.edits.size());
    }

    @Test
    void rejectsBlankNamesAndStaleReferences() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var access = new EditorBackedCubismModelAccess(resolver("5.3.02"), "session-a");
        final var model = access.active();

        final var part = model.parts().find(new PartId("Root"));
        assertThrows(IllegalArgumentException.class, () -> part.setName("  "));
        assertThrows(IllegalArgumentException.class, () -> model.parts().create(" ", null, -1));
        assertThrows(IllegalArgumentException.class, () -> model.deformers().createWarp("W", null, -1, 0, 3));
        assertThrows(
                IllegalArgumentException.class,
                () -> model.warpDeformers().find(new DeformerId("WarpA")).setName(""));

        // Stale reference: replace the source set with a same-id replacement; the old views must
        // be rejected before any native mutation.
        fixture.replacePartWithSameId();
        assertThrows(IllegalStateException.class, () -> model.parts().remove(part));
        assertThrows(IllegalStateException.class, () -> model.parts().create("X", part, -1));
        assertEquals(0, fixture.editMode.edits.size());
    }

    @Test
    void appliesDeformerToChildrenThroughExactNativeCommandOnce() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver("5.3.02"), "session-a").active();
        final var target = model.deformers().find(new DeformerId("WarpA"));

        model.deformers().applyToChildren(target);

        assertEquals(1, fixture.updateManager.selectionCalls.size());
        final var selection = fixture.updateManager.selectionCalls.get(0);
        assertEquals(fixture.document, selection.source());
        assertEquals(List.of(fixture.warpSource.guid), selection.guids());
        assertFalse(selection.append());
        assertTrue(selection.sendEvent());
        assertEquals(1, fixture.document.applyCount);
        assertEquals(0, fixture.document.deleteCount);
        assertEquals(0, fixture.deformerSet.removedDirectly);
        assertEquals(1, fixture.deformerSet.removedByCommand);
        assertEquals(0, fixture.editMode.edits.size());
        assertEquals(0, fixture.source.updateCount);
        assertEquals(0, fixture.pack.deformerRefreshCount);
        assertEquals(0, fixture.pack.repaintCount);
        assertFalse(fixture.document.dirty);
        assertEquals(1, model.deformers().all().size());
        assertThrows(NoSuchElementException.class, () -> model.deformers().find(new DeformerId("WarpA")));
    }

    @Test
    void rejectsInvalidTargetGuidBeforeSelectionOrNativeWrite() {
        final Fixture fixture = new Fixture();
        fixture.warpSource.guid = new Guid(" ");
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver("5.3.02"), "session-a").active();
        final var target = model.deformers().find(new DeformerId("WarpA"));

        assertThrows(IllegalStateException.class, () -> model.deformers().applyToChildren(target));

        assertEquals(0, fixture.updateManager.selectionCalls.size());
        assertEquals(0, fixture.document.applyCount);
        assertEquals(0, fixture.document.deleteCount);
        assertEquals(0, fixture.deformerSet.removedDirectly);
        assertEquals(0, fixture.deformerSet.removedByCommand);
        assertEquals(0, fixture.editMode.edits.size());
    }

    @Test
    void rejectsApplyToChildrenWhenNativePostconditionStillContainsTargetGuid() {
        final Fixture fixture = new Fixture();
        fixture.source.applyNoop = true;
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver("5.3.02"), "session-a").active();
        final var target = model.deformers().find(new DeformerId("WarpA"));

        assertThrows(IllegalStateException.class, () -> model.deformers().applyToChildren(target));

        assertEquals(1, fixture.updateManager.selectionCalls.size());
        assertEquals(1, fixture.document.applyCount);
        assertEquals(0, fixture.document.deleteCount);
        assertEquals(0, fixture.deformerSet.removedDirectly);
        assertEquals(0, fixture.deformerSet.removedByCommand);
        assertEquals(2, fixture.deformerSet.sources.size());
        assertEquals(2, model.deformers().all().size());
        assertEquals(0, fixture.editMode.edits.size());
        assertEquals(0, fixture.source.updateCount);
        assertEquals(0, fixture.pack.deformerRefreshCount);
        assertEquals(0, fixture.pack.repaintCount);
        assertFalse(fixture.document.dirty);
    }

    @Test
    void rejectsApplyToChildrenWithoutAdmittedCapability() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolverWithoutHierarchyCapability(), "session-a").active();
        final var target = model.deformers().find(new DeformerId("WarpA"));

        assertThrows(
                UnsupportedOperationException.class, () -> model.deformers().applyToChildren(target));

        assertEquals(0, fixture.updateManager.selectionCalls.size());
        assertEquals(0, fixture.document.applyCount);
        assertEquals(0, fixture.document.deleteCount);
        assertEquals(0, fixture.deformerSet.removedDirectly);
        assertEquals(0, fixture.deformerSet.removedByCommand);
        assertEquals(0, fixture.editMode.edits.size());
    }

    @Test
    void rejectsForeignDeformerBeforeNativeInvocation() {
        final Fixture foreignFixture = new Fixture();
        Host.document = foreignFixture.document;
        final var foreignModel = new EditorBackedCubismModelAccess(resolver("5.3.02"), "foreign-session").active();
        final var foreign = foreignModel.deformers().find(new DeformerId("WarpA"));

        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver("5.3.02"), "session-a").active();

        assertThrows(IllegalStateException.class, () -> model.deformers().applyToChildren(foreign));
        assertEquals(0, fixture.updateManager.selectionCalls.size());
        assertEquals(0, fixture.document.applyCount);
        assertEquals(0, fixture.document.deleteCount);
    }

    @Test
    void rejectsStaleDeformerBeforeNativeInvocation() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver("5.3.02"), "session-a").active();
        final var target = model.deformers().find(new DeformerId("WarpA"));
        fixture.replaceDeformerWithSameId();

        assertThrows(IllegalStateException.class, () -> model.deformers().applyToChildren(target));
        assertEquals(0, fixture.updateManager.selectionCalls.size());
        assertEquals(0, fixture.document.applyCount);
        assertEquals(0, fixture.document.deleteCount);
    }

    @Test
    void rejectsReentrantActiveDocumentSwitchBeforeNativeInvocation() {
        final Fixture original = new Fixture();
        final Fixture replacement = new Fixture();
        Host.document = original.document;
        final var model = new EditorBackedCubismModelAccess(resolver("5.3.02"), "session-a").active();
        final var target = model.deformers().find(new DeformerId("WarpA"));
        final int[] callbacks = {0};
        original.updateManager.selectionCallback = () -> {
            callbacks[0]++;
            Host.document = replacement.document;
        };

        assertThrows(IllegalStateException.class, () -> model.deformers().applyToChildren(target));

        assertEquals(1, callbacks[0]);
        assertEquals(1, original.updateManager.selectionCalls.size());
        assertEquals(
                original.document, original.updateManager.selectionCalls.get(0).source());
        assertEquals(0, original.document.applyCount);
        assertEquals(0, replacement.document.applyCount);
        assertEquals(0, original.deformerSet.removedByCommand);
        assertEquals(0, replacement.deformerSet.removedByCommand);
    }

    @Test
    void rejectsReentrantSameIdDeformerReplacementBeforeNativeInvocation() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver("5.3.02"), "session-a").active();
        final var target = model.deformers().find(new DeformerId("WarpA"));
        final Guid originalGuid = fixture.warpSource.guid;
        final int[] callbacks = {0};
        fixture.updateManager.selectionCallback = () -> {
            callbacks[0]++;
            fixture.replaceDeformerWithSameId();
        };

        assertThrows(IllegalStateException.class, () -> model.deformers().applyToChildren(target));

        assertEquals(1, callbacks[0]);
        assertEquals(1, fixture.updateManager.selectionCalls.size());
        assertEquals(0, fixture.document.applyCount);
        assertEquals(0, fixture.deformerSet.removedByCommand);
        assertTrue(fixture.warpSource != fixture.deformerSet.sources.get(0));
        assertTrue(originalGuid != fixture.deformerSet.sources.get(0).guid);
    }

    @Test
    void centralWriterCapturesDirectPartMembershipAndFreezesNames() {
        final Fixture fixture = new Fixture();
        fixture.childPart.localName = "A";
        fixture.parentPart.localName = "B";
        fixture.rootPart.localName = "C";
        Host.document = fixture.document;
        final VerifiedMemberResolver resolver = centralResolver("5.3.02");
        final var model = new EditorBackedCubismModelAccess(resolver, "session-a").active();

        model.parts().find(new PartId("Child")).setParent(model.parts().find(new PartId("Root")), 0);

        assertEquals(1, fixture.editMode.edits.size());
        assertEquals(1, fixture.document.undoManager.entries().size());
        assertEquals("Root", fixture.childPart.parent().id().value());
        final HistoryEntryDetail detail = lastHistoryDetail(resolver);
        assertEquals(HistoryAction.DetailLevel.FULL, detail.detailLevel());
        assertEquals("A", detail.targets().get(0).displayName().orElseThrow());
        final var change = detail.changes().get(0);
        assertEquals(HistoryChange.Operation.SET, change.operation());
        assertEquals(Optional.of(0), change.targetIndex());
        assertTrue(change.property().isEmpty());
        assertTrue(change.before().isEmpty());
        assertTrue(change.after().isEmpty());
        assertEquals(
                dev.turboism.sdk.cubism.history.HistoryEditContext.Kind.OBJECT,
                change.context().kind());
        final HistoryRelationChange relation = change.relation().orElseThrow();
        assertEquals(HistoryRelationChange.Kind.PART_MEMBERSHIP, relation.kind());
        assertRelationTarget(relation.before(), "PART", "Parent", "B");
        assertRelationTarget(relation.after(), "PART", "Root", "C");

        // The semantic detail is immutable even when the live native names subsequently change.
        fixture.childPart.localName = "mutated-A";
        fixture.parentPart.localName = "mutated-B";
        fixture.rootPart.localName = "mutated-C";
        assertEquals("A", detail.targets().get(0).displayName().orElseThrow());
        assertEquals("B", relation.before().target().orElseThrow().displayName().orElseThrow());
        assertEquals("C", relation.after().target().orElseThrow().displayName().orElseThrow());

        fixture.editMode.edits.get(0).undo();
        assertEquals("Parent", fixture.childPart.parent().id().value());
        fixture.editMode.edits.get(0).redo();
        assertEquals("Root", fixture.childPart.parent().id().value());
    }

    @Test
    void centralWriterFreezesDirectRelationNamesAcrossABCTemporalSequence() {
        final Fixture fixture = new Fixture();
        fixture.parentPart.localName = "A";
        fixture.rootPart.localName = "B";
        final PartSource finalPart = new PartSource("Final", fixture.source);
        finalPart.localName = "C";
        fixture.partSet.sources.add(finalPart);
        fixture.source.allObjects.add(finalPart);
        fixture.source.model.parts.add(new HostPart(finalPart));
        fixture.rootPart.addChild(finalPart, -1);
        Host.document = fixture.document;
        final VerifiedMemberResolver resolver = centralResolver("5.3.02");
        final var model = new EditorBackedCubismModelAccess(resolver, "session-a").active();
        final var child = model.parts().find(new PartId("Child"));

        child.setParent(model.parts().find(new PartId("Root")), 0);
        final HistoryEntryDetail first = lastHistoryDetail(resolver);
        final HistoryRelationChange firstRelation =
                first.changes().get(0).relation().orElseThrow();
        assertRelationTarget(firstRelation.before(), "PART", "Parent", "A");
        assertRelationTarget(firstRelation.after(), "PART", "Root", "B");

        child.setParent(model.parts().find(new PartId("Final")), -1);
        final HistoryEntryDetail second = lastHistoryDetail(resolver);
        final HistoryRelationChange secondRelation =
                second.changes().get(0).relation().orElseThrow();
        assertRelationTarget(secondRelation.before(), "PART", "Root", "B");
        assertRelationTarget(secondRelation.after(), "PART", "Final", "C");

        fixture.parentPart.localName = "mutated-A";
        fixture.rootPart.localName = "mutated-B";
        finalPart.localName = "mutated-C";
        assertRelationTarget(firstRelation.before(), "PART", "Parent", "A");
        assertRelationTarget(firstRelation.after(), "PART", "Root", "B");
        assertRelationTarget(secondRelation.before(), "PART", "Root", "B");
        assertRelationTarget(secondRelation.after(), "PART", "Final", "C");
    }

    @Test
    void publicArtMeshTargetDeformerAliasCapturesOneTypedRelation() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        fixture.meshSource.targetDeformerGuid = fixture.warpSource.guid;
        final VerifiedMemberResolver resolver = relationAliasResolver("5.3.02");
        final var model = new EditorBackedCubismModelAccess(resolver, "session-a").active();

        model.drawables().find(new ArtMeshId("MeshA")).setTargetDeformer(Optional.of(new DeformerId("RotationA")));

        assertEquals(1, fixture.editMode.edits.size());
        assertEquals(1, fixture.document.undoManager.entries().size());
        assertEquals(1, fixture.meshSource.setTargetCalls);
        assertEquals(fixture.rotationSource.guid, fixture.meshSource.targetDeformerGuid);
        final HistoryEntryDetail detail = lastHistoryDetail(resolver);
        assertEquals(1, detail.changes().size());
        final HistoryRelationChange relation =
                detail.changes().get(0).relation().orElseThrow();
        assertEquals(HistoryRelationChange.Kind.DEFORMER_PARENT, relation.kind());
        assertRelationTarget(relation.before(), "WARP_DEFORMER", "WarpA", "WarpA");
        assertRelationTarget(relation.after(), "ROTATION_DEFORMER", "RotationA", "RotationA");

        fixture.editMode.edits.get(0).undo();
        assertEquals(fixture.warpSource.guid, fixture.meshSource.targetDeformerGuid);
        fixture.editMode.edits.get(0).redo();
        assertEquals(fixture.rotationSource.guid, fixture.meshSource.targetDeformerGuid);
    }

    @Test
    void publicDeformerTargetDeformerAliasCapturesOneTypedRelation() {
        final Fixture fixture = new Fixture();
        fixture.warpSource.targetDeformerGuid = fixture.rotationSource.guid;
        fixture.attachWarpTarget();
        Host.document = fixture.document;
        final VerifiedMemberResolver resolver = relationAliasResolver("5.3.02");
        final var model = new EditorBackedCubismModelAccess(resolver, "session-a").active();

        model.warpDeformers().find(new DeformerId("WarpA")).setTargetDeformer(Optional.of(new DeformerId("WarpB")));

        assertEquals(1, fixture.editMode.edits.size());
        assertEquals(1, fixture.document.undoManager.entries().size());
        assertEquals(1, fixture.warpSource.setTargetCalls);
        assertEquals(fixture.warpTargetSource.guid, fixture.warpSource.targetDeformerGuid);
        final HistoryEntryDetail detail = lastHistoryDetail(resolver);
        assertEquals(1, detail.changes().size());
        final HistoryRelationChange relation =
                detail.changes().get(0).relation().orElseThrow();
        assertEquals(HistoryRelationChange.Kind.DEFORMER_PARENT, relation.kind());
        assertRelationTarget(relation.before(), "ROTATION_DEFORMER", "RotationA", "RotationA");
        assertRelationTarget(relation.after(), "WARP_DEFORMER", "WarpB", "WarpB");
    }

    @Test
    void centralWriterAllowsCrossTypeSameIdRelation() {
        final Fixture fixture = new Fixture();
        fixture.rotationSource.id = new Id("MeshA");
        fixture.rotationSource.localName = "Rotation with Mesh ID";
        fixture.meshSource.targetDeformerGuid = fixture.warpSource.guid;
        Host.document = fixture.document;
        final VerifiedMemberResolver resolver = centralResolver("5.3.02");
        final var model = new EditorBackedCubismModelAccess(resolver, "session-a").active();

        model.drawables()
                .find(new ArtMeshId("MeshA"))
                .setParent(model.rotationDeformers().find(new DeformerId("MeshA")), -1);

        assertEquals(fixture.rotationSource.guid, fixture.meshSource.targetDeformerGuid);
        final HistoryRelationChange relation =
                lastHistoryDetail(resolver).changes().get(0).relation().orElseThrow();
        assertEquals(HistoryRelationChange.Kind.DEFORMER_PARENT, relation.kind());
        assertRelationTarget(relation.before(), "WARP_DEFORMER", "WarpA", "WarpA");
        assertRelationTarget(relation.after(), "ROTATION_DEFORMER", "MeshA", "Rotation with Mesh ID");
    }

    @Test
    void centralWriterCapturesDeformerRootToTargetAndRestoresIt() {
        final Fixture fixture = new Fixture();
        // No direct Deformer parent: the child sits at the Deformer root.
        fixture.meshSource.targetDeformerGuid = null;
        fixture.warpSource.localName = "Warp with name";
        Host.document = fixture.document;
        final VerifiedMemberResolver resolver = centralResolver("5.3.02");
        final var model = new EditorBackedCubismModelAccess(resolver, "session-a").active();

        model.drawables()
                .find(new ArtMeshId("MeshA"))
                .setParent(model.warpDeformers().find(new DeformerId("WarpA")), -1);

        assertEquals(fixture.warpSource.guid, fixture.meshSource.targetDeformerGuid);
        assertEquals(1, fixture.editMode.edits.size());
        assertEquals(1, fixture.document.undoManager.entries().size());
        final HistoryEntryDetail detail = lastHistoryDetail(resolver);
        assertEquals(HistoryAction.DetailLevel.FULL, detail.detailLevel());
        final HistoryRelationChange relation =
                detail.changes().get(0).relation().orElseThrow();
        assertEquals(HistoryRelationChange.Kind.DEFORMER_PARENT, relation.kind());
        assertEquals(HistoryRelationChange.State.ROOT, relation.before().state());
        assertTrue(relation.before().target().isEmpty());
        assertRelationTarget(relation.after(), "WARP_DEFORMER", "WarpA", "Warp with name");

        // Undo returns the child to the Deformer root; redo re-applies the captured target.
        fixture.editMode.edits.get(0).undo();
        assertEquals(null, fixture.meshSource.targetDeformerGuid);
        fixture.editMode.edits.get(0).redo();
        assertEquals(fixture.warpSource.guid, fixture.meshSource.targetDeformerGuid);
    }

    @Test
    void centralWriterUsesActualAfterNameAndNeverRequestedName() {
        final Fixture fixture = new Fixture();
        fixture.childPart.localName = "A";
        fixture.parentPart.localName = "B";
        fixture.rootPart.localName = "Requested C";
        fixture.rootPart.renameOnNextAdd = "Actual C";
        Host.document = fixture.document;
        final VerifiedMemberResolver resolver = centralResolver("5.3.02");
        final var model = new EditorBackedCubismModelAccess(resolver, "session-a").active();

        model.parts().find(new PartId("Child")).setParent(model.parts().find(new PartId("Root")), 0);

        final HistoryRelationChange relation =
                lastHistoryDetail(resolver).changes().get(0).relation().orElseThrow();
        assertEquals("B", relation.before().target().orElseThrow().displayName().orElseThrow());
        assertEquals(
                "Actual C",
                relation.after().target().orElseThrow().displayName().orElseThrow());
    }

    @Test
    void centralWriterObservationFailureCommitsPartialUnknownAfter() {
        final Fixture fixture = new Fixture();
        fixture.childPart.localName = "A";
        fixture.parentPart.localName = "B";
        fixture.rootPart.localName = "C";
        fixture.rootPart.failNameReadOnNextAdd = true;
        Host.document = fixture.document;
        final VerifiedMemberResolver resolver = centralResolver("5.3.02");
        final var model = new EditorBackedCubismModelAccess(resolver, "session-a").active();

        model.parts().find(new PartId("Child")).setParent(model.parts().find(new PartId("Root")), 0);

        final HistoryEntryDetail detail = lastHistoryDetail(resolver);
        assertEquals(HistoryAction.DetailLevel.PARTIAL, detail.detailLevel());
        assertEquals(Optional.of("history.capture.after-unavailable"), detail.degradationCode());
        final HistoryRelationChange relation =
                detail.changes().get(0).relation().orElseThrow();
        assertRelationTarget(relation.before(), "PART", "Parent", "B");
        assertEquals(HistoryRelationChange.State.UNKNOWN, relation.after().state());
        assertTrue(relation.after().target().isEmpty());
        assertEquals("Root", fixture.childPart.parent().id().value());
        assertEquals(1, fixture.document.undoManager.entries().size());
    }

    @Test
    void centralWriterRejectsSelfCycleStaleForeignAndFailedAdmissionBeforeMutation() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final VerifiedMemberResolver resolver = centralResolver("5.3.02");
        final var access = new EditorBackedCubismModelAccess(resolver, "session-a");
        final var model = access.active();
        final var child = model.parts().find(new PartId("Child"));
        final var root = model.parts().find(new PartId("Root"));

        assertThrows(IllegalArgumentException.class, () -> child.setParent(child, -1));
        assertThrows(IllegalArgumentException.class, () -> root.setParent(child, -1));
        assertEquals(0, fixture.rootPart.addChildCalls);
        assertEquals(0, fixture.editMode.edits.size());

        fixture.source.allObjects.add(new PartSource("Foreign", fixture.source));
        assertThrows(NoSuchElementException.class, () -> model.parts().find(new PartId("Foreign")));
        assertEquals(0, fixture.editMode.edits.size());

        fixture.replacePartWithSameId();
        assertThrows(IllegalStateException.class, () -> root.setParent(child, -1));
        assertEquals(0, fixture.editMode.edits.size());

        final Fixture rejected = new Fixture();
        // Part membership attaches through the requested parent Part handler, so an unavailable
        // handler must reject the write before any mutation.
        rejected.rootPart.handlerUnavailable = true;
        Host.document = rejected.document;
        final VerifiedMemberResolver rejectedResolver = centralResolver("5.3.02");
        final var rejectedModel = new EditorBackedCubismModelAccess(rejectedResolver, "session-a").active();
        assertThrows(
                IllegalStateException.class,
                () -> rejectedModel
                        .parts()
                        .find(new PartId("Child"))
                        .setParent(rejectedModel.parts().find(new PartId("Root")), 0));
        assertEquals("Parent", rejected.childPart.parent().id().value());
        assertEquals(0, rejected.rootPart.addChildCalls);
        assertEquals(0, rejected.document.undoManager.entries().size());
    }

    @Test
    void centralWriterNoChangeAndReorderDoNotPublishRelationSuccess() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final VerifiedMemberResolver resolver = centralResolver("5.3.02");
        final var model = new EditorBackedCubismModelAccess(resolver, "session-a").active();
        final var child = model.parts().find(new PartId("Child"));
        final var parent = model.parts().find(new PartId("Parent"));

        child.setParent(parent, 0);
        assertEquals(0, fixture.editMode.edits.size());
        assertEquals(0, fixture.document.undoManager.entries().size());

        final PartSource sibling = new PartSource("Sibling", fixture.source);
        fixture.partSet.sources.add(sibling);
        fixture.source.allObjects.add(sibling);
        fixture.source.model.parts.add(new HostPart(sibling));
        fixture.parentPart.children.add(sibling);
        sibling.parent = fixture.parentPart;
        child.setParent(parent, 1);

        assertEquals(List.of(sibling, fixture.childPart), fixture.parentPart.children);
        assertEquals(1, fixture.editMode.edits.size());
        assertEquals(1, fixture.document.undoManager.entries().size());
        final HistoryEntryDetail detail = lastHistoryDetail(resolver);
        assertTrue(
                detail.changes().stream().noneMatch(change -> change.relation().isPresent()));
    }

    @Test
    void centralWriterGroupsPartAndDeformerRelationsInOneAmbientEntry() {
        final Fixture fixture = new Fixture();
        fixture.meshSource.targetDeformerGuid = fixture.warpSource.guid;
        Host.document = fixture.document;
        final VerifiedMemberResolver resolver = relationAliasResolver("5.3.02");
        final var access = new EditorBackedCubismModelAccess(resolver, "session-a");
        final var service = ((RuntimeAuthoringTransactionProvider) access).authoringTransactions("plugin.test");

        final var result = service.execute(AuthoringTransactionOptions.of("Move hierarchy"), () -> {
            final var model = access.active();
            model.parts().find(new PartId("Child")).setParent(model.parts().find(new PartId("Root")), 0);
            model.drawables().find(new ArtMeshId("MeshA")).setTargetDeformer(Optional.of(new DeformerId("RotationA")));
            return "done";
        });

        assertEquals(
                AuthoringTransactionOutcome.COMMITTED, result.outcome(), () -> String.valueOf(result.diagnosticId()));
        assertEquals(Optional.of("done"), result.value());
        assertEquals(1, fixture.editMode.beginCalls);
        assertEquals(1, fixture.editMode.edits.size());
        assertEquals(1, fixture.document.undoManager.entries().size());
        final HistoryEntryDetail detail = lastHistoryDetail(resolver);
        assertEquals(HistoryAction.DetailLevel.FULL, detail.detailLevel());
        assertEquals(2, detail.changes().size());
        assertEquals(
                HistoryRelationChange.Kind.PART_MEMBERSHIP,
                detail.changes().get(0).relation().orElseThrow().kind());
        assertEquals(
                HistoryRelationChange.Kind.DEFORMER_PARENT,
                detail.changes().get(1).relation().orElseThrow().kind());
        assertEquals(2, detail.group().orElseThrow().children().size());
        // One host attach Undo entry for the Part membership, one child-side entry for the move.
        assertEquals(2, fixture.editMode.edits.get(0).children.size());
    }

    private static HistoryEntryDetail lastHistoryDetail(final VerifiedMemberResolver resolver) {
        final var snapshot = new EditorHistorySnapshotProvider(() -> Optional.of(resolver), () -> 1).snapshot();
        assertEquals(dev.turboism.sdk.cubism.history.HistorySnapshot.Availability.AVAILABLE, snapshot.availability());
        assertEquals(snapshot.entries().size(), snapshot.position());
        assertFalse(snapshot.entries().isEmpty());
        return snapshot.entries().get(snapshot.entries().size() - 1).detail();
    }

    private static void assertRelationTarget(
            final HistoryRelationChange.Endpoint endpoint, final String type, final String id, final String name) {
        assertEquals(HistoryRelationChange.State.TARGET, endpoint.state());
        final var target = endpoint.target().orElseThrow();
        assertEquals(type, target.type());
        assertEquals(Optional.of(id), target.id());
        assertEquals(Optional.of(name), target.displayName());
    }

    // ------------------------------------------------------------------
    // ambient authoring participation: two ArtMesh creates join one root
    // transaction. The fixture's edit bank IS the native history — a create
    // that bypasses the ambient scope still mutates it and is detected.
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"5.0.00", "5.0.06", "5.1.03", "5.2.00", "5.2.03", "5.3.00", "5.3.04"})
    void artMeshCreatesJoinOneAmbientRootTransaction(final String cubismVersion) {
        final var scoped = ScopedFixture.onEdt(cubismVersion);
        final var geometry = triangle();
        final var result = scoped.execute(AuthoringTransactionOptions.of("import two surfaces"), () -> {
            scoped.access.createArtMeshSource(
                    "model", scoped.fixture.source, scoped.fixture.source.model, "B0_TRI", null, false, -1, geometry);
            scoped.access.createArtMeshSource(
                    "model", scoped.fixture.source, scoped.fixture.source.model, "B0_QUAD", null, false, -1, geometry);
            return null;
        });
        assertEquals(
                AuthoringTransactionOutcome.COMMITTED, result.outcome(), () -> String.valueOf(result.diagnosticId()));
        // Both creates were admitted through ONE native root edit.
        assertEquals(1, scoped.host.begins);
        assertEquals(1, scoped.host.commits);
        assertEquals(0, scoped.host.aborts);
        assertEquals(1, scoped.fixture.editMode.edits.size());
        assertEquals(3, scoped.fixture.source.drawableSet.sources.size());
        // One native Undo removes both created surfaces; Redo restores them.
        scoped.fixture.editMode.edits.get(0).undo();
        assertEquals(1, scoped.fixture.source.drawableSet.sources.size());
        scoped.fixture.editMode.edits.get(0).redo();
        assertEquals(3, scoped.fixture.source.drawableSet.sources.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.0.00", "5.0.06", "5.1.03", "5.2.00", "5.2.03", "5.3.00", "5.3.04"})
    void standaloneArtMeshCreateCommitsThroughItsOwnTransaction(final String cubismVersion) {
        final var scoped = ScopedFixture.onEdt(cubismVersion);
        scoped.access.createArtMeshSource(
                "model", scoped.fixture.source, scoped.fixture.source.model, "STANDALONE", null, false, -1, triangle());
        assertEquals(1, scoped.host.begins);
        assertEquals(1, scoped.host.commits);
        assertEquals(1, scoped.fixture.editMode.edits.size());
        assertEquals(2, scoped.fixture.source.drawableSet.sources.size());
        scoped.fixture.editMode.edits.get(0).undo();
        assertEquals(1, scoped.fixture.source.drawableSet.sources.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.0.00", "5.0.06", "5.1.03", "5.2.00", "5.2.03", "5.3.00", "5.3.04"})
    void warpCreatesJoinOneAmbientRootTransaction(final String cubismVersion) {
        final var scoped = ScopedFixture.onEdt(cubismVersion);
        final WarpGrid grid = new WarpGrid(
                1,
                2,
                true,
                List.of(
                        new Point2(-2F, -1F),
                        new Point2(0F, -1F),
                        new Point2(2F, -1F),
                        new Point2(-2F, 1F),
                        new Point2(0F, 1F),
                        new Point2(2F, 1F)));
        final var result = scoped.execute(AuthoringTransactionOptions.of("create two warps"), () -> {
            scoped.access.createWarpSource(
                    "model", scoped.fixture.source, scoped.fixture.source.model, "WARP_A", null, false, -1, grid);
            scoped.access.createWarpSource(
                    "model", scoped.fixture.source, scoped.fixture.source.model, "WARP_B", null, false, -1, grid);
            return null;
        });
        assertEquals(
                AuthoringTransactionOutcome.COMMITTED, result.outcome(), () -> String.valueOf(result.diagnosticId()));
        assertEquals(1, scoped.host.begins);
        assertEquals(1, scoped.host.commits);
        assertEquals(0, scoped.host.aborts);
        assertEquals(1, scoped.fixture.editMode.edits.size());
        final int created = scoped.fixture.source.deformerSet.sources.size() - 2;
        assertEquals(2, created);
        scoped.fixture.editMode.edits.get(0).undo();
        assertEquals(2, scoped.fixture.source.deformerSet.sources.size());
        scoped.fixture.editMode.edits.get(0).redo();
        assertEquals(4, scoped.fixture.source.deformerSet.sources.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.0.00", "5.0.06", "5.1.03", "5.2.00", "5.2.03", "5.3.00", "5.3.04"})
    void rotationCreatesJoinOneAmbientRootTransaction(final String cubismVersion) {
        final var scoped = ScopedFixture.onEdt(cubismVersion);
        final RotationDeformerForm form = new RotationDeformerForm(0F, 512F, 512F, 1F, false, false);
        final var result = scoped.execute(AuthoringTransactionOptions.of("create two rotations"), () -> {
            scoped.access.createRotationSource(
                    "model", scoped.fixture.source, scoped.fixture.source.model, "ROTATION_A", null, false, -1, form);
            scoped.access.createRotationSource(
                    "model", scoped.fixture.source, scoped.fixture.source.model, "ROTATION_B", null, false, -1, form);
            return null;
        });
        assertEquals(
                AuthoringTransactionOutcome.COMMITTED, result.outcome(), () -> String.valueOf(result.diagnosticId()));
        assertEquals(1, scoped.host.begins);
        assertEquals(1, scoped.host.commits);
        assertEquals(0, scoped.host.aborts);
        assertEquals(1, scoped.fixture.editMode.edits.size());
        final int created = scoped.fixture.source.deformerSet.sources.size() - 2;
        assertEquals(2, created);
        scoped.fixture.editMode.edits.get(0).undo();
        assertEquals(2, scoped.fixture.source.deformerSet.sources.size());
        scoped.fixture.editMode.edits.get(0).redo();
        assertEquals(4, scoped.fixture.source.deformerSet.sources.size());
    }

    /**
     * Full ownership matrix: first/second create admission rejection x Part-root
     * vs deformer parent x native abort behaviour x Cubism version. The native
     * Undo factories attach/register eagerly, so a rejected admission leaves a
     * real side effect — the transaction must either revert it (the reviewed
     * versions have a verified self-revert primitive) or honestly report
     * RECOVERY_FAILED. A ROLLED_BACK result with residue still registered is a
     * false clean rollback and must never happen.
     */
    @ParameterizedTest
    @ValueSource(strings = {"5.0.00", "5.0.06", "5.1.03", "5.2.00", "5.2.03", "5.3.00", "5.3.04"})
    void rejectedArtMeshCreateNeverReportsFalseRollback(final String cubismVersion) {
        for (final boolean deformerParent : List.of(false, true))
            for (final boolean nativeAbort : List.of(false, true))
                for (final boolean rejectFirst : List.of(false, true)) {
                    final var scoped = ScopedFixture.onEdt(cubismVersion);
                    scoped.host.nativeAbort = nativeAbort;
                    final var geometry = triangle();
                    final Object parent = deformerParent ? scoped.fixture.source.deformerSet.sources.get(0) : null;
                    if (rejectFirst) scoped.fixture.editMode.rejectNextAdmission = 1;
                    final var result = scoped.execute(AuthoringTransactionOptions.of("import two surfaces"), () -> {
                        scoped.access.createArtMeshSource(
                                "model",
                                scoped.fixture.source,
                                scoped.fixture.source.model,
                                "B0_TRI",
                                parent,
                                deformerParent,
                                -1,
                                geometry);
                        // The native host rejects the second surface's Undo admission.
                        scoped.fixture.editMode.rejectNextAdmission = 1;
                        scoped.access.createArtMeshSource(
                                "model",
                                scoped.fixture.source,
                                scoped.fixture.source.model,
                                "B0_QUAD",
                                parent,
                                deformerParent,
                                -1,
                                geometry);
                        return null;
                    });
                    final String tag = cubismVersion + " parent=" + (deformerParent ? "deformer" : "part") + " abort="
                            + nativeAbort + " rejected=" + (rejectFirst ? "first" : "second");
                    // undo.execute-undo is verified on both versions and compensates
                    // every eager side effect.
                    assertEquals(AuthoringTransactionOutcome.ROLLED_BACK, result.outcome(), tag);
                    assertEquals(1, scoped.fixture.source.drawableSet.sources.size(), tag);
                    assertEquals(0, scoped.fixture.editMode.edits.size(), tag);
                    assertEquals(0, scoped.host.commits, tag);
                }
    }

    /** Same false-rollback contract for the Warp creation path. */
    @ParameterizedTest
    @ValueSource(strings = {"5.0.00", "5.0.06", "5.1.03", "5.2.00", "5.2.03", "5.3.00", "5.3.04"})
    void rejectedWarpCreateNeverReportsFalseRollback(final String cubismVersion) {
        final WarpGrid grid = new WarpGrid(
                1,
                2,
                true,
                List.of(
                        new Point2(-2F, -1F),
                        new Point2(0F, -1F),
                        new Point2(2F, -1F),
                        new Point2(-2F, 1F),
                        new Point2(0F, 1F),
                        new Point2(2F, 1F)));
        for (final boolean deformerParent : List.of(false, true))
            for (final boolean nativeAbort : List.of(false, true))
                for (final boolean rejectFirst : List.of(false, true)) {
                    final var scoped = ScopedFixture.onEdt(cubismVersion);
                    scoped.host.nativeAbort = nativeAbort;
                    final Object parent = deformerParent ? scoped.fixture.source.deformerSet.sources.get(0) : null;
                    if (rejectFirst) scoped.fixture.editMode.rejectNextAdmission = 1;
                    final var result = scoped.execute(AuthoringTransactionOptions.of("create two warps"), () -> {
                        scoped.access.createWarpSource(
                                "model",
                                scoped.fixture.source,
                                scoped.fixture.source.model,
                                "WARP_A",
                                parent,
                                deformerParent,
                                -1,
                                grid);
                        scoped.fixture.editMode.rejectNextAdmission = 1;
                        scoped.access.createWarpSource(
                                "model",
                                scoped.fixture.source,
                                scoped.fixture.source.model,
                                "WARP_B",
                                parent,
                                deformerParent,
                                -1,
                                grid);
                        return null;
                    });
                    final String tag = cubismVersion + " parent=" + (deformerParent ? "deformer" : "part") + " abort="
                            + nativeAbort + " rejected=" + (rejectFirst ? "first" : "second");
                    assertEquals(AuthoringTransactionOutcome.ROLLED_BACK, result.outcome(), tag);
                    assertEquals(2, scoped.fixture.source.deformerSet.sources.size(), tag);
                    assertEquals(0, scoped.fixture.editMode.edits.size(), tag);
                    assertEquals(0, scoped.host.commits, tag);
                }
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.0.00", "5.0.06", "5.1.03", "5.2.00", "5.2.03", "5.3.00", "5.3.04"})
    void partAndMeshCreateJoinOneAmbientRoot(final String cubismVersion) {
        final var scoped = ScopedFixture.onEdt(cubismVersion);
        final var originalParts = List.copyOf(scoped.fixture.partSet.sources);
        final var originalMeshes = List.copyOf(scoped.fixture.source.drawableSet.sources);
        final var result = scoped.execute(AuthoringTransactionOptions.of("import grouped surface"), () -> {
            final var part = scoped.access.createPartSource(
                    "model",
                    scoped.fixture.source,
                    scoped.fixture.source.model,
                    "Imported",
                    scoped.fixture.rootPart,
                    -1);
            scoped.access.createArtMeshSource(
                    "model",
                    scoped.fixture.source,
                    scoped.fixture.source.model,
                    "Surface",
                    part.source(),
                    false,
                    -1,
                    triangle());
            return part;
        });
        assertEquals(
                AuthoringTransactionOutcome.COMMITTED, result.outcome(), () -> String.valueOf(result.diagnosticId()));
        assertEquals(1, scoped.host.begins);
        assertEquals(1, scoped.fixture.editMode.edits.size());
        assertEquals(originalParts.size() + 1, scoped.fixture.partSet.sources.size());
        assertEquals(originalMeshes.size() + 1, scoped.fixture.source.drawableSet.sources.size());
        scoped.fixture.editMode.edits.get(0).undo();
        assertEquals(originalParts, scoped.fixture.partSet.sources);
        assertEquals(originalMeshes, scoped.fixture.source.drawableSet.sources);
        scoped.fixture.editMode.edits.get(0).redo();
        assertEquals(originalParts.size() + 1, scoped.fixture.partSet.sources.size());
        assertEquals(originalMeshes.size() + 1, scoped.fixture.source.drawableSet.sources.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.0.00", "5.0.06", "5.1.03", "5.2.00", "5.2.03", "5.3.00", "5.3.04"})
    void rejectedPartCreateOwnsEagerMutation(final String cubismVersion) {
        for (final boolean nativeAbort : List.of(false, true))
            for (final boolean rejectFirst : List.of(false, true)) {
                final var scoped = ScopedFixture.onEdt(cubismVersion);
                final var originalParts = List.copyOf(scoped.fixture.partSet.sources);
                final var originalChildren = List.copyOf(scoped.fixture.rootPart.children);
                scoped.host.nativeAbort = nativeAbort;
                if (rejectFirst) scoped.fixture.editMode.rejectNextAdmission = 1;
                final var result = scoped.execute(AuthoringTransactionOptions.of("import groups"), () -> {
                    scoped.access.createPartSource(
                            "model",
                            scoped.fixture.source,
                            scoped.fixture.source.model,
                            "First",
                            scoped.fixture.rootPart,
                            -1);
                    scoped.fixture.editMode.rejectNextAdmission = 1;
                    scoped.access.createPartSource(
                            "model",
                            scoped.fixture.source,
                            scoped.fixture.source.model,
                            "Second",
                            scoped.fixture.rootPart,
                            -1);
                    return null;
                });
                assertEquals(AuthoringTransactionOutcome.ROLLED_BACK, result.outcome());
                assertEquals(originalParts, scoped.fixture.partSet.sources);
                assertEquals(originalChildren, scoped.fixture.rootPart.children);
                assertEquals(0, scoped.fixture.editMode.edits.size());
                assertEquals(0, scoped.host.commits);
            }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedGroupedSurfaceImportRestoresSourcesAndTree(final boolean nativeAbort) {
        final var scoped = ScopedFixture.onEdt("5.3.02");
        scoped.host.nativeAbort = nativeAbort;
        final var originalParts = List.copyOf(scoped.fixture.partSet.sources);
        final var originalChildren = List.copyOf(scoped.fixture.rootPart.children);
        final var originalMeshes = List.copyOf(scoped.fixture.source.drawableSet.sources);
        final var result = scoped.execute(AuthoringTransactionOptions.of("failing grouped import"), () -> {
            final var part = scoped.access.createPartSource(
                    "model",
                    scoped.fixture.source,
                    scoped.fixture.source.model,
                    "Imported",
                    scoped.fixture.rootPart,
                    -1);
            scoped.access.createArtMeshSource(
                    "model",
                    scoped.fixture.source,
                    scoped.fixture.source.model,
                    "Surface",
                    part.source(),
                    false,
                    -1,
                    triangle());
            throw new IllegalStateException("failure after geometry");
        });
        assertEquals(AuthoringTransactionOutcome.ROLLED_BACK, result.outcome());
        assertEquals(originalParts, scoped.fixture.partSet.sources);
        assertEquals(originalChildren, scoped.fixture.rootPart.children);
        assertEquals(originalMeshes, scoped.fixture.source.drawableSet.sources);
        assertEquals(0, scoped.fixture.editMode.edits.size());
        assertEquals(1, scoped.host.begins);
        assertEquals(0, scoped.host.commits);
    }

    private static ArtMeshGeometry triangle() {
        return new ArtMeshGeometry(
                List.of(new Point2(-1F, -1F), new Point2(1F, -1F), new Point2(0F, 1F)),
                List.of(new Point2(0F, 0F), new Point2(1F, 0F), new Point2(0.5F, 1F)),
                List.of(0, 1, 2));
    }

    private static final class ScopedFixture {
        final Fixture fixture = new Fixture();
        final ScopedHost host = new ScopedHost(fixture);
        final EditorAuthoringTransactionCoordinator.Binding binding = new EditorAuthoringTransactionCoordinator.Binding(
                "hierarchy-test", "document", 1, "model", 1, Thread.currentThread());
        final EditorAuthoringTransactionCoordinator coordinator = new EditorAuthoringTransactionCoordinator(host);
        final EditorObjectHierarchyEditAccess access;

        ScopedFixture(final String cubismVersion) {
            Host.document = fixture.document;
            final EditorParameterCombinedAccess.ModelGuard guard = (identity, model) -> {};
            access = new EditorObjectHierarchyEditAccess(resolver(cubismVersion), guard, coordinator, () -> binding);
        }

        // The fixture must live on the Swing EDT: the coordinator's ambient scope and the
        // binding's host thread are thread-confined, and access writes dispatch to the EDT.
        static ScopedFixture onEdt(final String cubismVersion) {
            final java.util.concurrent.atomic.AtomicReference<ScopedFixture> ref =
                    new java.util.concurrent.atomic.AtomicReference<>();
            try {
                javax.swing.SwingUtilities.invokeAndWait(() -> ref.set(new ScopedFixture(cubismVersion)));
            } catch (java.lang.reflect.InvocationTargetException failure) {
                final Throwable cause = failure.getCause();
                if (cause instanceof RuntimeException exception) throw exception;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException(cause);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
            return ref.get();
        }

        <T> dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult<T> execute(
                final AuthoringTransactionOptions options,
                final dev.turboism.sdk.cubism.transaction.AuthoringTransactionWork<T> work) {
            final java.util.concurrent.atomic.AtomicReference<
                            dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult<T>>
                    ref = new java.util.concurrent.atomic.AtomicReference<>();
            try {
                javax.swing.SwingUtilities.invokeAndWait(() -> ref.set(coordinator.execute(binding, options, work)));
            } catch (java.lang.reflect.InvocationTargetException failure) {
                final Throwable cause = failure.getCause();
                if (cause instanceof RuntimeException exception) throw exception;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException(cause);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
            return ref.get();
        }
    }

    /** Coordinator host whose history bank is the fixture's native edit list. */
    private static final class ScopedHost implements EditorAuthoringTransactionCoordinator.Host {
        @Override
        public void undoEditGroup(
                final dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator.Binding
                        expected,
                final Object edit) {
            ((GroupUndo) edit).groupUndo();
        }

        private final Fixture fixture;
        boolean nativeAbort = true;
        int begins, commits, aborts, refreshes;

        ScopedHost(final Fixture fixture) {
            this.fixture = fixture;
        }

        @Override
        public boolean isCurrent(final EditorAuthoringTransactionCoordinator.Binding binding) {
            return binding.hostThread() == Thread.currentThread();
        }

        @Override
        public HistorySnapshot history(final EditorAuthoringTransactionCoordinator.Binding binding) {
            final var edits = fixture.editMode.edits;
            final var entries = new ArrayList<HistoryEntry>();
            for (int index = 0; index < edits.size(); index++) {
                entries.add(new HistoryEntry(index, "edit-" + index, true));
            }
            return new HistorySnapshot(
                    HistorySnapshot.Availability.AVAILABLE,
                    1,
                    edits.size(),
                    edits.size(),
                    List.copyOf(entries),
                    !entries.isEmpty(),
                    false,
                    "document-binding",
                    "manager-binding");
        }

        @Override
        public Object beginEdit(final EditorAuthoringTransactionCoordinator.Binding binding, final String label) {
            begins++;
            return fixture.editMode.begin(label);
        }

        @Override
        public void endEdit(
                final EditorAuthoringTransactionCoordinator.Binding binding, final Object edit, final boolean abort) {
            final var group = (Undo) edit;
            if (!abort) {
                commits++;
                return;
            }
            aborts++;
            // Once the envelope group-undo ran, the bracket close must not replay inverses twice.
            if (nativeAbort && (!(group instanceof GroupUndo g) || g.groupUndoCount == 0)) group.undo();
            fixture.editMode.edits.remove(group);
        }

        @Override
        public void refresh(
                final EditorAuthoringTransactionCoordinator.Binding binding,
                final Set<EditorRefreshRequirement> requirements) {
            refreshes++;
        }

        @Override
        public Optional<String> committedHistoryEntryId(
                final EditorAuthoringTransactionCoordinator.Binding binding,
                final HistorySnapshot before,
                final HistorySnapshot after,
                final String transactionId,
                final String label) {
            return after.position() == before.position() + 1
                    ? Optional.of("entry-" + after.position())
                    : Optional.empty();
        }

        @Override
        public String diagnosticId(final String code, final Throwable failure) {
            return code;
        }
    }

    // ------------------------------------------------------------------
    // resolver + selectors
    // ------------------------------------------------------------------

    private static VerifiedMemberResolver resolver(final String cubismVersion) {
        return resolver(cubismVersion, "");
    }

    private static VerifiedMemberResolver resolver(final String cubismVersion, final String excludedAlias) {
        final java.util.HashSet<String> capabilities = new java.util.HashSet<>();
        capabilities.add(EditorPartNameSelectorContract.CAPABILITY_ID);
        capabilities.add(EditorObjectReadSelectorContract.CAPABILITY_ID);
        capabilities.add(dev.turboism.mapping.verification.selector.EditorPartTreeSelectorContract.CAPABILITY_ID);
        capabilities.add(EditorObjectHierarchyEditSelectorContract.CAPABILITY_ID);
        capabilities.add(EditorObjectHierarchyEditSelectorContract.RENAME_CAPABILITY_ID);
        capabilities.add(EditorObjectHierarchyEditSelectorContract.ART_MESH_CREATE_CAPABILITY_ID);
        capabilities.add(dev.turboism.mapping.verification.selector.EditorHistoryReadSelectorContract.CAPABILITY_ID);
        capabilities.add(
                dev.turboism.mapping.verification.selector.EditorEditSessionSelectorContract.EDIT_BEGIN_CAPABILITY_ID);
        capabilities.add(EditorObjectHierarchyEditSelectorContract.APPLY_TO_CHILDREN_CAPABILITY_ID);
        return TestVerifiedResolvers.create(
                cubismVersion,
                EditorObjectHierarchyEditSelectorContract.ADAPTER_SLICE_ID,
                capabilities,
                selectors().stream()
                        .filter(selector -> !selector.alias().equals(excludedAlias))
                        .toList(),
                Host.class.getClassLoader());
    }

    private static VerifiedMemberResolver centralResolver(final String cubismVersion) {
        final java.util.HashSet<String> capabilities = new java.util.HashSet<>();
        capabilities.add(EditorPartNameSelectorContract.CAPABILITY_ID);
        capabilities.add(EditorObjectReadSelectorContract.CAPABILITY_ID);
        capabilities.add(EditorPartTreeSelectorContract.CAPABILITY_ID);
        capabilities.add(EditorObjectHierarchyEditSelectorContract.CAPABILITY_ID);
        capabilities.add(EditorObjectHierarchyEditSelectorContract.RENAME_CAPABILITY_ID);
        capabilities.add(EditorObjectHierarchyEditSelectorContract.ART_MESH_CREATE_CAPABILITY_ID);
        capabilities.add(EditorHistoryReadSelectorContract.CAPABILITY_ID);
        capabilities.add(EditorPartStructureSelectorContract.CAPABILITY_ID);
        return TestVerifiedResolvers.create(
                cubismVersion,
                EditorObjectHierarchyEditSelectorContract.ADAPTER_SLICE_ID,
                capabilities,
                selectors(),
                Host.class.getClassLoader());
    }

    private static VerifiedMemberResolver relationAliasResolver(final String cubismVersion) {
        final java.util.HashSet<String> capabilities = new java.util.HashSet<>();
        capabilities.add(EditorPartNameSelectorContract.CAPABILITY_ID);
        capabilities.add(EditorObjectReadSelectorContract.CAPABILITY_ID);
        capabilities.add(EditorPartTreeSelectorContract.CAPABILITY_ID);
        capabilities.add(EditorObjectHierarchyEditSelectorContract.CAPABILITY_ID);
        capabilities.add(EditorObjectHierarchyEditSelectorContract.RENAME_CAPABILITY_ID);
        capabilities.add(EditorObjectHierarchyEditSelectorContract.ART_MESH_CREATE_CAPABILITY_ID);
        capabilities.add(EditorHistoryReadSelectorContract.CAPABILITY_ID);
        capabilities.add(EditorPartStructureSelectorContract.CAPABILITY_ID);
        capabilities.add(EditorInspectorDrawableWriteSelectorContract.CAPABILITY_ID);
        capabilities.add(EditorDeformerInspectorSelectorContract.CAPABILITY_ID);
        return TestVerifiedResolvers.create(
                cubismVersion,
                EditorObjectHierarchyEditSelectorContract.ADAPTER_SLICE_ID,
                capabilities,
                relationSelectors(),
                Host.class.getClassLoader());
    }

    private static VerifiedMemberResolver resolverWithoutHierarchyCapability() {
        final java.util.HashSet<String> capabilities = new java.util.HashSet<>();
        capabilities.add(EditorPartNameSelectorContract.CAPABILITY_ID);
        capabilities.add(EditorObjectReadSelectorContract.CAPABILITY_ID);
        return TestVerifiedResolvers.create(
                "5.3.02",
                EditorObjectHierarchyEditSelectorContract.ADAPTER_SLICE_ID,
                capabilities,
                selectors(),
                Host.class.getClassLoader());
    }

    private static List<StaticSelector> selectors() {
        final List<StaticSelector> selectors = new ArrayList<>();
        selectors.add(StaticSelector.classSelector("cubism.editor-model.app-controller.class", internal(Host.class)));
        selectors.add(StaticSelector.staticMethod(
                "cubism.editor-model.app-controller.instance",
                internal(Host.class),
                "instance",
                desc(Host.class),
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        selectors.add(method(
                "cubism.editor-model.app-controller.current-document",
                Host.class,
                "currentDocument",
                desc(Document.class)));
        selectors.add(method(
                "cubism.editor-model.app-controller.complete-pack",
                Host.class,
                "completePack",
                desc(CompletePack.class)));
        selectors.add(method(
                "cubism.editor-model.app-controller.update-manager",
                Host.class,
                "updateManager",
                desc(UpdateManager.class)));
        selectors.add(method("cubism.editor-model.app-controller.command-delete", Host.class, "commandDelete", "()V"));
        selectors.add(method(
                "cubism.editor-model.app-controller.command-delete-deformer-and-set-param",
                Host.class,
                "commandDeleteDeformerAndSetParam",
                "()V"));
        selectors.add(StaticSelector.classSelector(
                "cubism.editor-model.update-manager.class", internal(UpdateManager.class)));
        selectors.add(method(
                "cubism.editor-model.update-manager.set-selection",
                UpdateManager.class,
                "setSelection",
                "(Ljava/lang/Object;Ljava/util/List;ZZ)V"));
        selectors.add(
                StaticSelector.classSelector("cubism.editor-model.modeling-document.class", internal(Document.class)));
        selectors.add(method(
                "cubism.editor-model.modeling-document.model-source",
                Document.class,
                "modelSource",
                desc(ModelSource.class)));
        selectors.add(method(
                "cubism.editor-model.modeling-document.edit-mode", Document.class, "editMode", desc(EditMode.class)));
        selectors.add(method(
                "cubism.editor-history.document.undo-manager", Document.class, "undoManager", desc(UndoManager.class)));
        selectors.add(method(
                "cubism.editor-model.modeling-document.selector",
                Document.class,
                "selector",
                desc(NativeSelector.class)));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable-source.selection",
                ObjectSource.class,
                "selection",
                desc(ObjectSource.class)));
        selectors.add(method(
                "cubism.editor-model.selector.replace-selected",
                NativeSelector.class,
                "replace",
                "(Ljava/util/List;I)V"));
        selectors.add(method(
                "cubism.editor-model.selector.selected", NativeSelector.class, "selected", "()Ljava/util/List;"));
        selectors.add(method("cubism.editor-model.modeling-document.mark-dirty", Document.class, "markDirty", "()V"));
        selectors.add(method(
                "cubism.editor-model.edit-mode.begin",
                EditMode.class,
                "begin",
                "(Ljava/lang/String;)" + type(GroupUndo.class)));
        selectors.add(method("cubism.editor-model.edit-mode.end", EditMode.class, "end", "(ZLjava/lang/Object;)V"));
        selectors.add(method("cubism.editor-model.undo.add", GroupUndo.class, "add", "(" + type(Undo.class) + "Z)Z"));
        selectors.add(method(
                "cubism.editor-model.undo.add-listener", Undo.class, "addListener", "(" + type(Listener.class) + ")Z"));
        selectors.add(StaticSelector.classSelector("cubism.editor-history.manager.class", internal(UndoManager.class)));
        selectors.add(
                method("cubism.editor-history.manager.entries", UndoManager.class, "entries", "()Ljava/util/List;"));
        selectors.add(method("cubism.editor-history.manager.position", UndoManager.class, "position", "()I"));
        selectors.add(method(
                "cubism.editor-history.manager.can-undo", UndoManager.class, "canUndo", "()Ljava/lang/Boolean;"));
        selectors.add(method(
                "cubism.editor-history.manager.can-redo", UndoManager.class, "canRedo", "()Ljava/lang/Boolean;"));
        selectors.add(StaticSelector.classSelector("cubism.editor-history.entry.class", internal(GroupUndo.class)));
        selectors.add(method(
                "cubism.editor-history.entry.presentation-name",
                GroupUndo.class,
                "presentationName",
                "()Ljava/lang/String;"));
        selectors.add(method(
                "cubism.editor-history.entry.significant", GroupUndo.class, "significant", "()Ljava/lang/Boolean;"));
        selectors.add(
                StaticSelector.classSelector("cubism.editor-model.undo-listener.class", internal(Listener.class)));
        selectors.add(method("cubism.editor-model.undo.execute-undo", Undo.class, "executeUndo", "()V"));
        selectors.add(method("cubism.editor-model.undo.group-undo", GroupUndo.class, "groupUndo", "()V"));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.model.class", internal(Model.class)));
        selectors.add(method("cubism.editor-model.model-source.guid", ModelSource.class, "guid", desc(Id.class)));
        selectors.add(method(
                "cubism.editor-model.model-source.current-instance",
                ModelSource.class,
                "currentInstance",
                desc(Model.class)));
        selectors.add(method("cubism.editor-model.guid.value", Id.class, "value", "()Ljava/lang/String;"));
        selectors.add(method("cubism.editor-model.id.value", Id.class, "value", "()Ljava/lang/String;"));
        selectors.add(method("cubism.editor-model.part-id.value", Id.class, "value", "()Ljava/lang/String;"));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.part-id.class", internal(Id.class)));
        selectors.add(method(
                "cubism.editor-model.model-source.update-instances", ModelSource.class, "updateInstances", "()V"));
        selectors.add(method(
                "cubism.editor-model.model-source.update-visible-lock-hierarchy",
                ModelSource.class,
                "updateVisibleAndLockHierarchy",
                "()V"));
        selectors.add(
                method("cubism.editor-model.model-source.parts", ModelSource.class, "allParts", "()Ljava/util/List;"));
        selectors.add(method(
                "cubism.editor-model.model-source.all-deformers",
                ModelSource.class,
                "allDeformers",
                "()Ljava/util/List;"));
        selectors.add(method(
                "cubism.editor-model.model-source.all-art-meshes",
                ModelSource.class,
                "allArtMeshes",
                "()Ljava/util/List;"));
        selectors.add(method(
                "cubism.editor-model.model-source.all-objects", ModelSource.class, "allObjects", "()Ljava/util/List;"));
        selectors.add(method(
                "cubism.editor-model.model-source.handler", ModelSource.class, "handler", desc(ModelHandler.class)));
        selectors.add(method(
                "cubism.editor-model.model-handler.add-source-undo",
                ModelHandler.class,
                "addSourceUndo",
                "(" + type(ObjectSource.class) + "I)" + type(Undo.class)));
        selectors.add(method(
                "cubism.editor-model.model-source.part-source-set",
                ModelSource.class,
                "partSourceSet",
                desc(PartSourceSet.class)));
        selectors.add(method(
                "cubism.editor-model.model-source.deformer-source-set",
                ModelSource.class,
                "deformerSourceSet",
                desc(DeformerSourceSet.class)));
        selectors.add(StaticSelector.classSelector(
                "cubism.editor-model.part-source-set.class", internal(PartSourceSet.class)));
        selectors.add(method(
                "cubism.editor-model.part-source-set.add",
                PartSourceSet.class,
                "add",
                "(" + type(PartSource.class) + "I)V"));
        selectors.add(method(
                "cubism.editor-model.part-source-set.remove",
                PartSourceSet.class,
                "remove",
                "(" + type(PartSource.class) + ")V"));
        selectors.add(StaticSelector.classSelector(
                "cubism.editor-model.deformer-source-set.class", internal(DeformerSourceSet.class)));
        selectors.add(method(
                "cubism.editor-model.deformer-source-set.add",
                DeformerSourceSet.class,
                "add",
                "(" + type(ACDeformerSource.class) + "I)V"));
        selectors.add(method(
                "cubism.editor-model.deformer-source-set.remove",
                DeformerSourceSet.class,
                "remove",
                "(" + type(ACDeformerSource.class) + ")V"));
        selectors.add(StaticSelector.classSelector(
                "cubism.editor-model.drawable-source-set.class", internal(DrawableSourceSet.class)));
        selectors.add(method(
                "cubism.editor-model.drawable-source-set.remove",
                DrawableSourceSet.class,
                "remove",
                "(" + type(ACDrawableSource.class) + ")V"));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.part-source.create",
                internal(PartSource.class),
                "(" + type(ModelSource.class) + ")V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.warp-source.create",
                internal(WarpDeformerSource.class),
                "(" + type(ModelSource.class) + ")V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.rotation-source.create",
                internal(RotationDeformerSource.class),
                "(" + type(ModelSource.class) + ")V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.part-id.create",
                internal(Id.class),
                "(Ljava/lang/String;)V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.deformer-id.create",
                internal(Id.class),
                "(Ljava/lang/String;)V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(method(
                "cubism.editor-model.part-source.set-id", PartSource.class, "setId", "(" + type(Id.class) + ")V"));
        selectors.add(method(
                "cubism.editor-model.deformer-source.set-id",
                ACDeformerSource.class,
                "setId",
                "(" + type(Id.class) + ")V"));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.part-form.create",
                internal(PartForm.class),
                "(" + type(PartSource.class) + "Ljava/lang/Object;)V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.warp-form.create",
                internal(WarpForm.class),
                "(" + type(WarpDeformerSource.class) + type(Warp.class) + type(CoordType.class) + ")V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.rotation-form.create",
                internal(RotationForm.class),
                "(" + type(RotationDeformerSource.class) + type(Rotation.class) + type(CoordType.class) + ")V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.form-guid.create", internal(Guid.class), "()V", StaticSelector.ACCESS_PUBLIC));
        selectors.add(
                method("cubism.editor-model.form.set-guid", Form.class, "setGuid", "(" + type(Guid.class) + ")V"));
        selectors.add(method(
                "cubism.editor-model.part-source.keyforms", PartSource.class, "keyforms", desc(ObjectList.class)));
        selectors.add(method(
                "cubism.editor-model.warp-source.keyforms",
                WarpDeformerSource.class,
                "keyforms",
                desc(ObjectList.class)));
        selectors.add(method(
                "cubism.editor-model.rotation-source.keyforms",
                RotationDeformerSource.class,
                "keyforms",
                desc(ObjectList.class)));
        selectors.add(method(
                "cubism.editor-model.model-source.root-part", ModelSource.class, "rootPart", desc(PartSource.class)));
        selectors.add(
                StaticSelector.classSelector("cubism.editor-model.part-handler.class", internal(PartHandler.class)));
        selectors.add(method(
                "cubism.editor-model.part-handler.add-part-child",
                PartHandler.class,
                "addPartChild",
                "(" + type(ObjectSource.class) + "I)" + type(Undo.class)));
        selectors.add(method("cubism.editor-model.c-array-list.add", ObjectList.class, "add", "(Ljava/lang/Object;)Z"));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.keyform-grid-source.create",
                internal(KeyformGridSource.class),
                "(" + type(ObjectSource.class) + ")V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(method(
                "cubism.editor-model.keyform-grid-source.import-cubism21",
                KeyformGridSource.class,
                "importCubism21",
                "(" + type(ModelSource.class) + "Ljava/util/List;Ljava/util/List;Ljava/lang/Object;)V"));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable-source.set-keyform-grid-source",
                ObjectSource.class,
                "setKeyformGridSource",
                "(" + type(KeyformGridSource.class) + ")V"));
        selectors.add(StaticSelector.staticMethod(
                "cubism.editor-model.coord-type.canvas",
                internal(CoordType.class),
                "canvas",
                desc(CoordType.class),
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        selectors.add(method("cubism.editor-model.warp-source.set-col", WarpDeformerSource.class, "setCol", "(I)V"));
        selectors.add(method("cubism.editor-model.warp-source.set-row", WarpDeformerSource.class, "setRow", "(I)V"));
        selectors.add(method(
                "cubism.editor-model.warp-source.set-quad-transform",
                WarpDeformerSource.class,
                "setQuadTransform",
                "(Z)V"));
        selectors.add(method("cubism.editor-model.warp-source.col", WarpDeformerSource.class, "col", "()I"));
        selectors.add(method("cubism.editor-model.warp-source.row", WarpDeformerSource.class, "row", "()I"));
        selectors.add(
                StaticSelector.classSelector("cubism.editor-model.part-source.class", internal(PartSource.class)));
        selectors.add(method("cubism.editor-model.part-source.id", PartSource.class, "id", desc(Id.class)));
        selectors.add(method(
                "cubism.editor-model.part-source.local-name", ObjectSource.class, "localName", "()Ljava/lang/String;"));
        selectors.add(
                method("cubism.editor-model.part-source.parent", ObjectSource.class, "parent", desc(PartSource.class)));
        selectors.add(
                method("cubism.editor-model.part-source.children", PartSource.class, "children", "()Ljava/util/List;"));
        selectors.add(method(
                "cubism.editor-model.part-source.add-child",
                PartSource.class,
                "addChild",
                "(" + type(ObjectSource.class) + "I)V"));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.part.class", internal(HostPart.class)));
        selectors.add(method("cubism.editor-model.part.source", HostPart.class, "source", desc(PartSource.class)));
        selectors.add(method("cubism.editor-model.model.parts", Model.class, "allParts", "()Ljava/util/List;"));
        selectors.add(StaticSelector.classSelector(
                "cubism.editor-model.warp-source.class", internal(WarpDeformerSource.class)));
        selectors.add(StaticSelector.classSelector(
                "cubism.editor-model.rotation-source.class", internal(RotationDeformerSource.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.warp.class", internal(Warp.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.rotation.class", internal(Rotation.class)));
        selectors.add(StaticSelector.classSelector(
                "cubism.editor-model.art-mesh-source.class", internal(ACDrawableSource.class)));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.art-mesh-source.create",
                internal(ACDrawableSource.class),
                "(" + type(ModelSource.class) + ")V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.drawable-id.create",
                internal(Id.class),
                "(Ljava/lang/String;)V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(method(
                "cubism.editor-model.drawable-source.set-id",
                ACDrawableSource.class,
                "setId",
                "(" + type(Id.class) + ")V"));
        selectors.add(method(
                "cubism.editor-model.art-mesh-source.keyforms",
                ACDrawableSource.class,
                "keyforms",
                desc(ObjectList.class)));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.art-mesh-form.create",
                internal(ArtMeshForm.class),
                "(" + type(ACDrawableSource.class) + type(ArtMesh.class) + type(CoordType.class) + ")V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(
                method("cubism.editor-model.art-mesh-form.set-positions", ArtMeshForm.class, "setPositions", "([F)V"));
        selectors.add(method(
                "cubism.editor-model.art-mesh-source.set-positions", ACDrawableSource.class, "setPositions", "([F)V"));
        selectors.add(method("cubism.editor-model.art-mesh-source.set-uvs", ACDrawableSource.class, "setUvs", "([F)V"));
        selectors.add(method(
                "cubism.editor-model.art-mesh-source.set-indices", ACDrawableSource.class, "setIndices", "([I)V"));
        selectors.add(StaticSelector.classSelector(
                "cubism.editor-model.editable-mesh-extension.class", internal(EditableExtension.class)));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.editable-mesh-extension.create",
                internal(EditableExtension.class),
                "(" + type(ACDrawableSource.class) + ")V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(method(
                "cubism.editor-model.editable-mesh-extension.mesh",
                EditableExtension.class,
                "mesh",
                desc(EditableMesh.class)));
        selectors.add(method(
                "cubism.editor-model.editable-mesh-extension.owner",
                EditableExtension.class,
                "owner",
                desc(ACDrawableSource.class)));
        selectors.add(
                method("cubism.editor-model.editable-mesh-extension.locked", EditableExtension.class, "locked", "()Z"));
        selectors.add(method(
                "cubism.editor-model.editable-mesh.init-by-indices",
                EditableMesh.class,
                "init",
                "([F[I" + type(CoordType.class) + ")V"));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable-source.add-extension",
                ACDrawableSource.class,
                "addExtension",
                "(" + type(EditableExtension.class) + "I)V"));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.art-mesh.class", internal(ArtMesh.class)));
        selectors.add(
                method("cubism.editor-model.deformer.source", Deformer.class, "source", desc(ObjectSource.class)));
        selectors.add(
                method("cubism.editor-model.art-mesh.source", ArtMesh.class, "source", desc(ACDrawableSource.class)));
        selectors.add(method(
                "cubism.editor-model.art-mesh.current-keyform", ArtMesh.class, "currentForm", desc(ArtMeshForm.class)));
        selectors.add(method("cubism.editor-model.art-mesh-form.positions", ArtMeshForm.class, "positions", "()[F"));
        selectors.add(method("cubism.editor-model.drawable-form.opacity", Form.class, "opacity", "()F"));
        selectors.add(method("cubism.editor-model.drawable-form.draw-order", Form.class, "drawOrder", "()I"));
        selectors.add(
                method("cubism.editor-model.art-mesh-source.guid", ACDrawableSource.class, "guid", desc(Guid.class)));
        selectors.add(method(
                "cubism.editor-model.art-mesh-source.clip-guid-list",
                ACDrawableSource.class,
                "clipGuids",
                "()Ljava/util/List;"));
        selectors.add(
                method("cubism.editor-model.art-mesh-source.positions", ACDrawableSource.class, "positions", "()[F"));
        selectors.add(method("cubism.editor-model.art-mesh-source.uvs", ACDrawableSource.class, "uvs", "()[F"));
        selectors.add(method("cubism.editor-model.art-mesh-source.indices", ACDrawableSource.class, "indices", "()[I"));
        selectors.add(method("cubism.editor-model.art-mesh-source.culling", ACDrawableSource.class, "culling", "()Z"));
        selectors.add(method(
                "cubism.editor-model.art-mesh-source.user-data",
                ACDrawableSource.class,
                "userData",
                "()Ljava/lang/String;"));
        selectors.add(method(
                "cubism.editor-model.art-mesh-source.inverted-mask", ACDrawableSource.class, "invertedMask", "()Z"));
        selectors.add(method(
                "cubism.editor-model.deformer.current-keyform", Deformer.class, "currentForm", desc(Form.class)));
        selectors.add(method("cubism.editor-model.deformer-form.opacity", Form.class, "opacity", "()F"));
        selectors.add(method(
                "cubism.editor-model.warp-source.quad-transform", WarpDeformerSource.class, "quadTransform", "()Z"));
        selectors.add(method("cubism.editor-model.warp-form.positions", WarpForm.class, "positions", "()[F"));
        selectors.add(method("cubism.editor-model.warp-form.set-positions", WarpForm.class, "setPositions", "([F)V"));
        selectors.add(method(
                "cubism.editor-model.rotation-source.base-angle", RotationDeformerSource.class, "baseAngle", "()F"));
        selectors.add(method("cubism.editor-model.rotation-form.angle", RotationForm.class, "angle", "()F"));
        selectors.add(method("cubism.editor-model.rotation-form.origin-x", RotationForm.class, "originX", "()F"));
        selectors.add(method("cubism.editor-model.rotation-form.origin-y", RotationForm.class, "originY", "()F"));
        selectors.add(method("cubism.editor-model.rotation-form.scale", RotationForm.class, "scale", "()F"));
        selectors.add(method("cubism.editor-model.rotation-form.reflect-x", RotationForm.class, "reflectX", "()Z"));
        selectors.add(method("cubism.editor-model.rotation-form.reflect-y", RotationForm.class, "reflectY", "()Z"));
        selectors.add(method("cubism.editor-model.rotation-form.set-angle", RotationForm.class, "setAngle", "(F)V"));
        selectors.add(
                method("cubism.editor-model.rotation-form.set-origin-x", RotationForm.class, "setOriginX", "(F)V"));
        selectors.add(
                method("cubism.editor-model.rotation-form.set-origin-y", RotationForm.class, "setOriginY", "(F)V"));
        selectors.add(method("cubism.editor-model.rotation-form.set-scale", RotationForm.class, "setScale", "(F)V"));
        selectors.add(
                method("cubism.editor-model.rotation-form.set-reflect-x", RotationForm.class, "setReflectX", "(Z)V"));
        selectors.add(
                method("cubism.editor-model.rotation-form.set-reflect-y", RotationForm.class, "setReflectY", "(Z)V"));
        selectors.add(method(
                "cubism.editor-model.model-source.all-glues", ModelSource.class, "allGlues", "()Ljava/util/List;"));
        selectors.add(
                StaticSelector.classSelector("cubism.editor-model.glue-source.class", internal(GlueSource.class)));
        selectors.add(method(
                "cubism.editor-model.glue-source.target-art-mesh-a",
                GlueSource.class,
                "targetA",
                desc(ACDrawableSource.class)));
        selectors.add(method(
                "cubism.editor-model.glue-source.target-art-mesh-b",
                GlueSource.class,
                "targetB",
                desc(ACDrawableSource.class)));
        selectors.add(method(
                "cubism.editor-model.model.parameter-set", Model.class, "parameterSet", desc(ParameterSet.class)));
        selectors.add(method(
                "cubism.editor-model.parameter-set.parameters",
                ParameterSet.class,
                "parameters",
                "()Ljava/util/List;"));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.parameter.class", internal(Parameter.class)));
        selectors.add(method("cubism.editor-model.parameter.id", Parameter.class, "id", desc(Id.class)));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable-source.visible", ObjectSource.class, "visible", "()Z"));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable-source.locked", ObjectSource.class, "locked", "()Z"));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable-source.visible-in-hierarchy",
                ObjectSource.class,
                "visibleInHierarchy",
                "()Z"));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable-source.locked-in-hierarchy",
                ObjectSource.class,
                "lockedInHierarchy",
                "()Z"));
        selectors.add(
                method("cubism.editor-model.model.all-deformers", Model.class, "allDeformers", "()Ljava/util/List;"));
        selectors.add(
                method("cubism.editor-model.model.all-art-meshes", Model.class, "allArtMeshes", "()Ljava/util/List;"));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable-source.id", ObjectSource.class, "id", desc(Id.class)));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable-source.local-name",
                ObjectSource.class,
                "localName",
                "()Ljava/lang/String;"));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable-source.set-local-name",
                ObjectSource.class,
                "setLocalName",
                "(Ljava/lang/String;)V"));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable-source.guid",
                ObjectSource.class,
                "guid",
                desc(Guid.class)));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable-source.handler",
                ObjectSource.class,
                "handler",
                desc(ACParameterControllableHandler.class)));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable-source.target-deformer-guid",
                ObjectSource.class,
                "targetDeformerGuid",
                desc(Guid.class)));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable-source.set-target-deformer-guid",
                ObjectSource.class,
                "setTargetDeformerGuid",
                "(" + type(Guid.class) + ")V"));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable-source.all-parent-deformers",
                ObjectSource.class,
                "allParentDeformers",
                "()Ljava/lang/Iterable;"));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable-source.target-deformer-source",
                ObjectSource.class,
                "targetDeformerSource",
                desc(ObjectSource.class)));
        selectors.add(StaticSelector.classSelector(
                "cubism.editor-model.parameter-controllable-handler.class",
                internal(ACParameterControllableHandler.class)));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable-handler.create-undo-for-all-edit",
                ACParameterControllableHandler.class,
                "undo",
                "(Ljava/lang/String;)" + type(Undo.class)));
        selectors.add(method(
                "cubism.editor-model.part-source.handler",
                PartSource.class,
                "partHandler",
                "()" + type(PartHandler.class)));
        selectors.add(method(
                "cubism.editor-model.complete-pack.update-part-palette",
                CompletePack.class,
                "updatePartPalette",
                "(Z)V"));
        selectors.add(method(
                "cubism.editor-model.complete-pack.update-deformer-palette",
                CompletePack.class,
                "updateDeformerPalette",
                "(Z)V"));
        selectors.add(method(
                "cubism.editor-model.complete-pack.repaint-canvas", CompletePack.class, "repaintCanvas", "(Z)V"));
        return selectors;
    }

    /**
     * Selectors for the public target-deformer routes. Both routes are gated on the reviewed
     * Inspector admission, so the fixture record must carry each contract's complete
     * required-alias inventory. Aliases this fixture never invokes are recorded with a placeholder
     * owner because the fixture host only mirrors the members the tests actually exercise; an
     * invoked alias must still resolve against a real fixture class.
     */
    private static List<StaticSelector> relationSelectors() {
        final List<StaticSelector> selectors = new ArrayList<>(selectors());
        selectors.add(StaticSelector.classSelector(
                "cubism.editor-model.deformer-source.class", internal(ACDeformerSource.class)));
        selectors.add(method("cubism.editor-model.deformer-source.guid", ObjectSource.class, "guid", desc(Guid.class)));
        final java.util.Set<String> recorded = new java.util.HashSet<>();
        for (final StaticSelector selector : selectors) recorded.add(selector.alias());
        final java.util.Set<String> required =
                new java.util.HashSet<>(EditorInspectorDrawableWriteSelectorContract.REQUIRED_ALIASES);
        required.addAll(EditorDeformerInspectorSelectorContract.REQUIRED_ALIASES);
        required.addAll(EditorInspectorDrawableWriteNoAlphaCompositionSelectorContract.REQUIRED_ALIASES);
        for (final String alias : required) {
            if (!recorded.contains(alias)) {
                selectors.add(StaticSelector.method(alias, "fixture/uninvoked/Host", "uninvoked", "()V"));
            }
        }
        return selectors;
    }

    private static StaticSelector method(
            final String alias, final Class<?> owner, final String name, final String descriptor) {
        return StaticSelector.method(alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC);
    }

    private static String internal(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static String type(final Class<?> type) {
        return "L" + internal(type) + ";";
    }

    private static String desc(final Class<?> type) {
        return "()" + type(type);
    }

    // ------------------------------------------------------------------
    // fixture: host surface mirroring the verified native members
    // ------------------------------------------------------------------

    public static final class Host {
        private static final Host INSTANCE = new Host();
        static Document document;

        public static Host instance() {
            return INSTANCE;
        }

        public Document currentDocument() {
            return document;
        }

        public CompletePack completePack() {
            return document.pack;
        }

        public UpdateManager updateManager() {
            return document.updateManager;
        }

        public void commandDelete() {
            // The native command consumes selector objects, not update-manager GUIDs.
            if (document.requireNativeDeleteSelection
                    && !document.selector.selected().equals(List.of(document.source.pendingDeleteSource))) {
                throw new IllegalStateException("native delete target not selected");
            }
            // Native DELETE owns its edit lifecycle; a generic SDK edit cannot surround it.
            if (document.editMode.active) throw new IllegalStateException("previous edit not finished");
            final var edit = document.editMode.begin("Native Delete");
            try {
                edit.add(document.source.pendingDeleteSource.handler().undo("Native Delete"), true);
                document.deleteCount++;
                document.source.commandDelete();
            } finally {
                document.editMode.end(false, null);
            }
        }

        public void commandDeleteDeformerAndSetParam() {
            document.applyCount++;
            document.source.commandDeleteDeformerAndSetParam();
        }
    }

    public static final class Document {
        final ModelSource source;
        final NativeSelector selector = new NativeSelector();

        public NativeSelector selector() {
            return selector;
        }

        final EditMode editMode = new EditMode();
        final UndoManager undoManager = new UndoManager(editMode);
        final CompletePack pack = new CompletePack();
        final UpdateManager updateManager = new UpdateManager();
        int deleteCount;
        int applyCount;
        boolean requireNativeDeleteSelection;
        boolean dirty;

        Document(final ModelSource source) {
            this.source = source;
        }

        public ModelSource modelSource() {
            return source;
        }

        public EditMode editMode() {
            return editMode;
        }

        public UndoManager undoManager() {
            return undoManager;
        }

        public void markDirty() {
            dirty = true;
        }
    }

    public static final class NativeSelector {
        final List<ObjectSource> values = new ArrayList<>();

        public List<ObjectSource> selected() {
            return List.copyOf(values);
        }

        public void replace(List<ObjectSource> sources, int level) {
            assertEquals(-1, level);
            values.clear();
            values.addAll(sources);
        }
    }

    public static final class ModelSource {
        static int counter;
        final Id guid = new Id("model-a");
        final List<ObjectSource> allObjects = new ArrayList<>();
        final PartSourceSet partSet = new PartSourceSet();
        final DeformerSourceSet deformerSet = new DeformerSourceSet();
        final DrawableSourceSet drawableSet = new DrawableSourceSet();
        final ModelHandler handler = new ModelHandler(this);
        final Model model = new Model(this);
        int updateCount;
        int hierarchyUpdateCount;
        boolean applyNoop;
        PartSource rootPart;
        boolean treeMaterialization;

        public PartSource rootPart() {
            return rootPart;
        }

        public String name() {
            return "Fixture Model";
        }

        public Id guid() {
            return guid;
        }

        public Model currentInstance() {
            return model;
        }

        public List<PartSource> allParts() {
            return partSet.sources;
        }

        public List<ACDeformerSource> allDeformers() {
            return deformerSet.sources;
        }

        public List<ACDrawableSource> allArtMeshes() {
            return drawableSet.sources;
        }

        public List<ObjectSource> allObjects() {
            return allObjects;
        }

        public ModelHandler handler() {
            return handler;
        }

        public PartSourceSet partSourceSet() {
            return partSet;
        }

        public DeformerSourceSet deformerSourceSet() {
            return deformerSet;
        }

        public List<Object> allGlues() {
            return List.of();
        }

        public void updateVisibleAndLockHierarchy() {
            hierarchyUpdateCount++;
        }

        public void updateInstances() {
            updateCount++;
            if (!treeMaterialization) return;
            final var existing = new java.util.IdentityHashMap<ACDrawableSource, ArtMesh>();
            model.meshes.forEach(mesh -> existing.put(mesh.source, mesh));
            model.meshes.clear();
            model.deformers.clear();
            materialize(rootPart, existing);
        }

        private void materialize(final PartSource part, final java.util.Map<ACDrawableSource, ArtMesh> existing) {
            if (part == null) return;
            for (ObjectSource child : part.children) {
                if (!allObjects.contains(child)) continue;
                if (child instanceof ACDrawableSource mesh && drawableSet.sources.contains(mesh)) {
                    model.meshes.add(existing.getOrDefault(mesh, new ArtMesh(mesh)));
                } else if (child instanceof ACDeformerSource deformer && deformerSet.sources.contains(deformer)) {
                    materializeDeformer(deformer);
                } else if (child instanceof PartSource nested) {
                    materialize(nested, existing);
                }
            }
        }

        private void materializeDeformer(final ACDeformerSource source) {
            if (model.deformers.stream().anyMatch(d -> d.source == source)) return;
            model.deformers.add(
                    source instanceof WarpDeformerSource
                            ? new Warp((WarpDeformerSource) source)
                            : new Rotation((RotationDeformerSource) source));
            // Deformer children attach through the target-deformer relation, not part children.
            for (ACDeformerSource candidate : deformerSet.sources) {
                if (allObjects.contains(candidate) && candidate.targetDeformerSource() == source) {
                    materializeDeformer(candidate);
                }
            }
        }

        ObjectSource findByGuid(final Guid target) {
            for (ObjectSource candidate : allObjects) {
                if (candidate.guid == target) return candidate;
            }
            return null;
        }

        void commandDelete() {
            // Simulates the native single-node DELETE command. Cascade / child re-parent is a
            // host responsibility and is intentionally NOT simulated here; the adapter only
            // selects the node and invokes the command (delegation, verified below).
            for (int i = 0; i < partSet.sources.size(); i++) {
                if (partSet.sources.get(i) == pendingDeleteSource) {
                    partSet.removeByCommand((PartSource) pendingDeleteSource);
                    return;
                }
            }
            for (int i = 0; i < deformerSet.sources.size(); i++) {
                if (deformerSet.sources.get(i) == pendingDeleteSource) {
                    deformerSet.removeByCommand((ACDeformerSource) pendingDeleteSource);
                    return;
                }
            }
            for (int i = 0; i < drawableSet.sources.size(); i++) {
                if (drawableSet.sources.get(i) == pendingDeleteSource) {
                    drawableSet.removeByCommand((ACDrawableSource) pendingDeleteSource);
                    return;
                }
            }
        }

        void commandDeleteDeformerAndSetParam() {
            if (applyNoop) return;
            if (pendingDeleteSource instanceof ACDeformerSource deformer) {
                deformerSet.removeByCommand(deformer);
            }
        }

        ObjectSource pendingDeleteSource;
    }

    public static final class Model {
        final List<HostPart> parts = new ArrayList<>();
        final List<Deformer> deformers = new ArrayList<>();
        final List<ArtMesh> meshes = new ArrayList<>();
        final ModelSource source;

        Model(final ModelSource source) {
            this.source = source;
        }

        public List<HostPart> allParts() {
            return parts;
        }

        public List<Deformer> allDeformers() {
            return deformers;
        }

        public List<ArtMesh> allArtMeshes() {
            return meshes;
        }

        public ParameterSet parameterSet() {
            return new ParameterSet();
        }
    }

    public static final class ParameterSet {
        public List<Parameter> parameters() {
            return List.of();
        }
    }

    public static final class Parameter {
        public Id id() {
            return new Id("Param");
        }
    }

    /** Base source: mirrors ACParameterControllableSource. */
    public static class ObjectSource {
        public ObjectSource selection() {
            return this;
        }

        Id id;
        final ModelSource modelSource;
        Guid guid = new Guid();
        String localName;
        PartSource parent;
        Guid targetDeformerGuid;
        KeyformGridSource keyformGridSource;
        Undo currentUndo;
        int setTargetCalls;
        boolean handlerUnavailable;
        boolean failLocalNameRead;

        ObjectSource(final String id, final ModelSource modelSource) {
            this.id = new Id(id);
            this.modelSource = modelSource;
        }

        public Id id() {
            return id;
        }

        public Guid guid() {
            return guid;
        }

        public String localName() {
            if (failLocalNameRead) {
                failLocalNameRead = false;
                throw new IllegalStateException("fixture display name read failed");
            }
            return localName;
        }

        public void setLocalName(final String value) {
            final String previous = localName;
            record("local-name", () -> localName = previous, () -> localName = value);
            localName = value;
        }

        public PartSource parent() {
            return parent;
        }

        public void internalSetParent(final PartSource value) {
            parent = value;
        }

        public Guid targetDeformerGuid() {
            return targetDeformerGuid;
        }

        public void setTargetDeformerGuid(final Guid value) {
            setTargetCalls++;
            final Guid previous = targetDeformerGuid;
            record("target-deformer", () -> targetDeformerGuid = previous, () -> targetDeformerGuid = value);
            targetDeformerGuid = value;
        }

        public ObjectSource targetDeformerSource() {
            return targetDeformerGuid == null ? null : modelSource.findByGuid(targetDeformerGuid);
        }

        public void setKeyformGridSource(final KeyformGridSource value) {
            keyformGridSource = value;
        }

        public Iterable<ObjectSource> allParentDeformers() {
            final ArrayList<ObjectSource> ancestors = new ArrayList<>();
            ObjectSource current = targetDeformerSource();
            while (current != null) {
                ancestors.add(current);
                current = current.targetDeformerSource();
            }
            return ancestors;
        }

        public ACParameterControllableHandler handler() {
            if (handlerUnavailable) return null;
            return this instanceof PartSource part ? new PartHandler(part) : new ACParameterControllableHandler(this);
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

        void record(final String label, final Runnable undo, final Runnable redo) {
            if (currentUndo != null) currentUndo.record(label, undo, redo);
        }

        void recordInto(final ObjectSource target, final String label, final Runnable undo, final Runnable redo) {
            if (target.currentUndo != null) target.currentUndo.record(label, undo, redo);
        }
    }

    /** Deformer source base: mirrors ACDeformerSource. */
    public static class ACDeformerSource extends ObjectSource {
        ACDeformerSource(final String id, final ModelSource modelSource) {
            super(id, modelSource);
        }

        public void setId(final Id value) {
            id = value;
        }
    }

    /** Drawable source: mirrors ACDrawableSource. */
    public static class ACDrawableSource extends ObjectSource {
        final ObjectList keyforms = new ObjectList();
        final List<EditableExtension> extensions = new ArrayList<>();

        public void addExtension(final EditableExtension extension, final int index) {
            if (index < 0) extensions.add(extension);
            else extensions.add(index, extension);
        }

        float[] sourcePositions = new float[0];
        float[] sourceUvs = new float[0];
        int[] sourceIndices = new int[0];

        public ACDrawableSource(final ModelSource modelSource) {
            this("ArtMesh_" + (++ModelSource.counter), modelSource);
        }

        public ACDrawableSource(final String id, final ModelSource modelSource) {
            super(id, modelSource);
        }

        public void setId(final Id value) {
            id = value;
        }

        public ObjectList keyforms() {
            return keyforms;
        }

        @Override
        public Guid guid() {
            return super.guid();
        }

        public List<Object> clipGuids() {
            return List.of();
        }

        public float[] positions() {
            return sourcePositions.clone();
        }

        public void setPositions(final float[] values) {
            sourcePositions = values.clone();
        }

        public float[] uvs() {
            return sourceUvs.clone();
        }

        public void setUvs(final float[] values) {
            sourceUvs = values.clone();
        }

        public int[] indices() {
            return sourceIndices.clone();
        }

        public void setIndices(final int[] values) {
            sourceIndices = values.clone();
        }

        public boolean culling() {
            return false;
        }

        public String userData() {
            return "";
        }

        public boolean invertedMask() {
            return false;
        }
    }

    public static final class EditableExtension {
        final ACDrawableSource owner;
        final EditableMesh mesh = new EditableMesh();

        public EditableExtension(final ACDrawableSource owner) {
            this.owner = owner;
        }

        public ACDrawableSource owner() {
            return owner;
        }

        public EditableMesh mesh() {
            return mesh;
        }

        public boolean locked() {
            return false;
        }
    }

    public static final class EditableMesh {
        float[] positions;
        int[] indices;

        public void init(final float[] positions, final int[] indices, final CoordType coord) {
            this.positions = positions.clone();
            this.indices = indices.clone();
        }
    }

    public static final class PartSource extends ObjectSource {
        final List<ObjectSource> children = new ArrayList<>();
        final ObjectList keyforms = new ObjectList();
        int addChildCalls;
        int removeChildCalls;
        String renameOnNextAdd;
        boolean failNameReadOnNextAdd;

        public PartSource(final ModelSource modelSource) {
            this("Part_" + (++ModelSource.counter), modelSource);
        }

        public PartSource(final String name, final ModelSource modelSource) {
            super(name, modelSource);
            this.localName = name;
        }

        public void setId(final Id value) {
            id = value;
        }

        public ObjectList keyforms() {
            return keyforms;
        }

        public List<ObjectSource> children() {
            return children;
        }

        @Override
        public Id id() {
            return super.id();
        }

        @Override
        public Guid guid() {
            return super.guid();
        }

        public void addChild(final ObjectSource child, final int index) {
            addChildCalls++;
            final PartSource oldParent = child.parent;
            // Part attachment belongs to the parent's separately admitted Undo.
            recordInto(
                    child instanceof PartSource && oldParent == null ? this : child,
                    "add-child",
                    () -> {
                        children.remove(child);
                        if (oldParent != null) {
                            oldParent.children.add(child);
                            child.internalSetParent(oldParent);
                        } else {
                            child.internalSetParent(null);
                        }
                    },
                    () -> {
                        if (oldParent != null) oldParent.children.remove(child);
                        children.remove(child);
                        if (index < 0) children.add(child);
                        else children.add(Math.min(index, children.size()), child);
                        child.internalSetParent(PartSource.this);
                    });
            if (oldParent != null) oldParent.removeChild(child);
            if (index < 0) children.add(child);
            else children.add(Math.min(index, children.size()), child);
            child.internalSetParent(this);
            if (!modelSource.allObjects.contains(child)) modelSource.allObjects.add(child);
            if (renameOnNextAdd != null) {
                localName = renameOnNextAdd;
                renameOnNextAdd = null;
            }
            if (failNameReadOnNextAdd) {
                failLocalNameRead = true;
                failNameReadOnNextAdd = false;
            }
        }

        /** Mirrors the host PartHandler: attaches the child and returns the Undo entry for it. */
        public PartHandler partHandler() {
            return handlerUnavailable ? null : new PartHandler(this);
        }

        public void removeChild(final ObjectSource child) {
            removeChildCalls++;
            children.remove(child);
            child.internalSetParent(null);
            modelSource.allObjects.remove(child);
        }
    }

    public static final class WarpDeformerSource extends ACDeformerSource {
        int row = 2;
        int col = 2;
        boolean quadTransform;
        final ObjectList keyforms = new ObjectList();
        final List<Integer> rowColLog = new ArrayList<>();

        public WarpDeformerSource(final ModelSource modelSource) {
            super("Warp_" + (++ModelSource.counter), modelSource);
        }

        WarpDeformerSource(final String id, final ModelSource modelSource) {
            super(id, modelSource);
            this.localName = id;
        }

        public int row() {
            return row;
        }

        public int col() {
            return col;
        }

        public ObjectList keyforms() {
            return keyforms;
        }

        public boolean quadTransform() {
            return quadTransform;
        }

        public void setQuadTransform(final boolean value) {
            quadTransform = value;
        }

        public void setRow(final int value) {
            final int previous = row;
            record("set-row", () -> row = previous, () -> row = value);
            row = value;
            rowColLog.add(value);
        }

        public void setCol(final int value) {
            final int previous = col;
            record("set-col", () -> col = previous, () -> col = value);
            col = value;
            rowColLog.add(value);
        }
    }

    public static final class RotationDeformerSource extends ACDeformerSource {
        final ObjectList keyforms = new ObjectList();

        public RotationDeformerSource(final ModelSource modelSource) {
            super("Rotation_" + (++ModelSource.counter), modelSource);
        }

        RotationDeformerSource(final String id, final ModelSource modelSource) {
            super(id, modelSource);
            this.localName = id;
        }

        public ObjectList keyforms() {
            return keyforms;
        }

        public float baseAngle() {
            return 0F;
        }
    }

    public static final class ModelHandler {
        private final ModelSource source;

        ModelHandler(final ModelSource source) {
            this.source = source;
        }

        public Undo addSourceUndo(final ObjectSource item, final int index) {
            final Undo undo = new Undo();
            item.currentUndo = undo;
            if (item instanceof PartSource part) {
                source.partSet.add(part, index);
            } else if (item instanceof ACDeformerSource deformer) {
                source.deformerSet.add(deformer, index);
            } else if (item instanceof ACDrawableSource drawable) {
                source.drawableSet.add(drawable, index);
            } else {
                throw new IllegalArgumentException("Unsupported fixture source: " + item);
            }
            return undo;
        }
    }

    public static final class PartSourceSet {
        final List<PartSource> sources = new ArrayList<>();
        final List<String> addLog = new ArrayList<>();
        int removedDirectly;
        int removedByCommand;

        public void add(final PartSource source, final int index) {
            if (index < 0) sources.add(source);
            else sources.add(Math.min(index, sources.size()), source);
            source.modelSource.allObjects.add(source);
            source.modelSource.model.parts.add(new HostPart(source));
            addLog.add(source.localName == null ? source.id.value() : source.localName);
            source.record(
                    "part-add",
                    () -> {
                        sources.remove(source);
                        source.modelSource.allObjects.remove(source);
                        source.modelSource.model.parts.removeIf(part -> part.source == source);
                    },
                    () -> add(source, index));
        }

        public void remove(final PartSource source) {
            removedDirectly++;
            sources.remove(source);
            source.modelSource.allObjects.remove(source);
            source.modelSource.model.parts.removeIf(part -> part.source == source);
        }

        void removeByCommand(final PartSource source) {
            removedByCommand++;
            sources.remove(source);
            source.modelSource.allObjects.remove(source);
            source.modelSource.model.parts.removeIf(part -> part.source == source);
            if (source.currentUndo != null) {
                source.currentUndo.record("delete", () -> add(source, -1), () -> {
                    sources.remove(source);
                    source.modelSource.allObjects.remove(source);
                    source.modelSource.model.parts.removeIf(part -> part.source == source);
                });
            }
        }
    }

    public static final class DeformerSourceSet {
        final List<ACDeformerSource> sources = new ArrayList<>();
        int addCount;
        int removedDirectly;
        int removedByCommand;

        public void add(final ACDeformerSource source, final int index) {
            addCount++;
            if (index < 0) sources.add(source);
            else sources.add(Math.min(index, sources.size()), source);
            source.modelSource.allObjects.add(source);
            // The real host materializes instances from the tree at updateInstances, never
            // from source-set membership alone — a registered-but-unattached source has none.
            if (!source.modelSource.treeMaterialization) {
                source.modelSource.model.deformers.add(
                        source instanceof WarpDeformerSource
                                ? new Warp((WarpDeformerSource) source)
                                : new Rotation((RotationDeformerSource) source));
            }
            source.record(
                    "deformer-add",
                    () -> {
                        sources.remove(source);
                        source.modelSource.allObjects.remove(source);
                        source.modelSource.model.deformers.removeIf(d -> d.source == source);
                    },
                    () -> add(source, index));
        }

        public void remove(final ACDeformerSource source) {
            removedDirectly++;
            sources.remove(source);
            source.modelSource.allObjects.remove(source);
            source.modelSource.model.deformers.removeIf(d -> d.source == source);
        }

        void removeByCommand(final ACDeformerSource source) {
            removedByCommand++;
            sources.remove(source);
            source.modelSource.allObjects.remove(source);
            source.modelSource.model.deformers.removeIf(d -> d.source == source);
            if (source.currentUndo != null) {
                source.currentUndo.record("delete", () -> add(source, -1), () -> {
                    sources.remove(source);
                    source.modelSource.allObjects.remove(source);
                    source.modelSource.model.deformers.removeIf(d -> d.source == source);
                });
            }
        }
    }

    public static final class DrawableSourceSet {
        final List<ACDrawableSource> sources = new ArrayList<>();
        int removedByCommand;

        public void add(final ACDrawableSource source, final int index) {
            if (index < 0) sources.add(source);
            else sources.add(Math.min(index, sources.size()), source);
            source.modelSource.allObjects.add(source);
            if (!source.modelSource.treeMaterialization) source.modelSource.model.meshes.add(new ArtMesh(source));
            source.record(
                    "drawable-add",
                    () -> {
                        sources.remove(source);
                        source.modelSource.allObjects.remove(source);
                        source.modelSource.model.meshes.removeIf(mesh -> mesh.source == source);
                    },
                    () -> add(source, index));
        }

        public void remove(final ACDrawableSource source) {
            sources.remove(source);
            source.modelSource.allObjects.remove(source);
            source.modelSource.model.meshes.removeIf(m -> m.source == source);
        }

        void removeByCommand(final ACDrawableSource source) {
            removedByCommand++;
            sources.remove(source);
            source.modelSource.allObjects.remove(source);
            source.modelSource.model.meshes.removeIf(m -> m.source == source);
            if (source.currentUndo != null) {
                source.currentUndo.record(
                        "delete",
                        () -> {
                            sources.add(source);
                            source.modelSource.allObjects.add(source);
                            source.modelSource.model.meshes.add(new ArtMesh(source));
                        },
                        () -> {
                            sources.remove(source);
                            source.modelSource.allObjects.remove(source);
                            source.modelSource.model.meshes.removeIf(m -> m.source == source);
                        });
            }
        }
    }

    public static final class UpdateManager {
        final List<SelectionCall> selectionCalls = new ArrayList<>();
        Runnable selectionCallback;

        public void setSelection(
                final Object source, final List<?> guids, final boolean append, final boolean sendEvent) {
            selectionCalls.add(new SelectionCall(source, new ArrayList<>(guids), append, sendEvent));
            if (source instanceof Document document && !guids.isEmpty()) {
                document.source.pendingDeleteSource = document.source.findByGuid((Guid) guids.get(0));
            }
            final Runnable callback = selectionCallback;
            selectionCallback = null;
            if (sendEvent && callback != null) callback.run();
        }

        public record SelectionCall(Object source, List<Object> guids, boolean append, boolean sendEvent) {
            // The adapter's first boolean publishes native selector notifications (see impl).
            public boolean notifyListeners() {
                return append;
            }
        }
    }

    public static class ACParameterControllableHandler {
        final ObjectSource source;

        ACParameterControllableHandler(final ObjectSource source) {
            this.source = source;
        }

        public Undo undo(final String name) {
            final Undo undo = new Undo();
            source.currentUndo = undo;
            return undo;
        }
    }

    /** Mirrors PartHandler's dedicated tree undo, independent of the registry-only undo. */
    public static final class PartHandler extends ACParameterControllableHandler {
        PartHandler(final PartSource part) {
            super(part);
        }

        public Undo addPartChild(final ObjectSource child, final int index) {
            final PartSource part = (PartSource) source;
            final ModelSource model = source.modelSource;
            if (child instanceof PartSource && child == part) {
                throw new IllegalStateException("duplicate native attachment");
            }
            final Undo undo = new Undo();
            final Undo previousParentUndo = part.currentUndo;
            final Undo previousChildUndo = child.currentUndo;
            // The host attach entry records the detach/attach inverse for both ends.
            part.currentUndo = undo;
            child.currentUndo = undo;
            try {
                part.addChild(child, index);
                // A source not yet registered in the model sets is a native create-attach:
                // the same command also registers it into the owning source set.
                if (child instanceof ACDrawableSource mesh && !model.drawableSet.sources.contains(mesh)) {
                    model.drawableSet.sources.add(mesh);
                    if (!model.treeMaterialization) model.model.meshes.add(new ArtMesh(mesh));
                    undo.record(
                            "register-mesh",
                            () -> {
                                model.drawableSet.sources.remove(mesh);
                                model.model.meshes.removeIf(value -> value.source == mesh);
                            },
                            () -> {
                                model.drawableSet.sources.add(mesh);
                                if (!model.treeMaterialization) model.model.meshes.add(new ArtMesh(mesh));
                            });
                } else if (child instanceof PartSource nested && !model.partSet.sources.contains(nested)) {
                    model.partSet.sources.add(nested);
                    model.partSet.addLog.add(nested.localName);
                    model.model.parts.add(new HostPart(nested));
                    undo.record(
                            "register-part",
                            () -> {
                                model.partSet.sources.remove(nested);
                                model.model.parts.removeIf(value -> value.source == nested);
                            },
                            () -> {
                                model.partSet.sources.add(nested);
                                model.model.parts.add(new HostPart(nested));
                            });
                } else if (child instanceof ACDeformerSource deformer
                        && !model.deformerSet.sources.contains(deformer)) {
                    model.deformerSet.sources.add(deformer);
                    if (!model.treeMaterialization) {
                        model.model.deformers.add(
                                deformer instanceof WarpDeformerSource
                                        ? new Warp((WarpDeformerSource) deformer)
                                        : new Rotation((RotationDeformerSource) deformer));
                    }
                    undo.record(
                            "register-deformer",
                            () -> {
                                model.deformerSet.sources.remove(deformer);
                                model.model.deformers.removeIf(value -> value.source == deformer);
                            },
                            () -> {
                                model.deformerSet.sources.add(deformer);
                                if (!model.treeMaterialization) {
                                    model.model.deformers.add(
                                            deformer instanceof WarpDeformerSource
                                                    ? new Warp((WarpDeformerSource) deformer)
                                                    : new Rotation((RotationDeformerSource) deformer));
                                }
                            });
                }
            } finally {
                part.currentUndo = previousParentUndo;
                child.currentUndo = previousChildUndo;
            }
            return undo;
        }
    }

    public static final class EditMode {
        final List<Undo> edits = new ArrayList<>();
        int beginCalls;
        boolean active;
        int rejectNextAdmission;

        public GroupUndo begin(final String name) {
            beginCalls++;
            active = true;
            return new GroupUndo(name, edits, this);
        }

        public void end(final boolean abort, final Object ignored) {
            active = false;
            if (abort && !edits.isEmpty()) edits.remove(edits.size() - 1);
        }
    }

    /** Native undo-manager projection over the edit bank (one history entry per edit). */
    public static final class UndoManager {
        private final EditMode editMode;

        UndoManager(final EditMode editMode) {
            this.editMode = editMode;
        }

        public List<Undo> entries() {
            return new ArrayList<>(editMode.edits);
        }

        public int position() {
            return editMode.edits.size();
        }

        public Boolean canUndo() {
            return !editMode.edits.isEmpty();
        }

        public Boolean canRedo() {
            return false;
        }
    }

    public static final class GroupUndo extends Undo {
        private final EditMode owner;

        GroupUndo(final String label, final List<Undo> edits, final EditMode owner) {
            this.owner = owner;
            this.label = label;
            edits.add(this);
        }

        public boolean add(final Undo undo, final boolean significant) {
            if (owner.rejectNextAdmission > 0) {
                owner.rejectNextAdmission--;
                return false;
            }
            children.add(undo);
            return true;
        }

        public String presentationName() {
            return label;
        }

        public Boolean significant() {
            return Boolean.TRUE;
        }

        int groupUndoCount;
        // cubism.editor-model.undo.group-undo: rewinds every admitted undo in place while the
        // edit bracket stays open, exactly like the verified session recovery path.
        public void groupUndo() {
            groupUndoCount++;
            for (int index = children.size() - 1; index >= 0; index--) {
                children.get(index).undo();
            }
        }
    }

    public static class Undo {
        // Native history replay does not author new commands. Suppress recording
        // across different Undo objects too (a newly created Part can later be a parent).
        private static final ThreadLocal<Boolean> REPLAYING = ThreadLocal.withInitial(() -> false);
        String label;
        final List<Undo> children = new ArrayList<>();
        final List<Runnable> undoSteps = new ArrayList<>();
        final List<Runnable> redoSteps = new ArrayList<>();
        final List<Listener> listeners = new ArrayList<>();

        public boolean addListener(final Listener listener) {
            listeners.add(listener);
            return true;
        }

        void record(final String label, final Runnable undo, final Runnable redo) {
            if (!REPLAYING.get()) {
                undoSteps.add(undo);
                redoSteps.add(redo);
            }
        }

        public void executeUndo() {
            undo();
        }

        public void undo() {
            replay(() -> {
                for (int i = children.size() - 1; i >= 0; i--) children.get(i).undo();
                for (int i = undoSteps.size() - 1; i >= 0; i--) undoSteps.get(i).run();
                listeners.forEach(listener -> listener.changed(null));
            });
        }

        public void redo() {
            replay(() -> {
                new ArrayList<>(redoSteps).forEach(Runnable::run);
                new ArrayList<>(children).forEach(Undo::redo);
                listeners.forEach(listener -> listener.changed(null));
            });
        }

        private static void replay(final Runnable action) {
            final boolean previous = REPLAYING.get();
            REPLAYING.set(true);
            try {
                action.run();
            } finally {
                if (previous) REPLAYING.set(true);
                else REPLAYING.remove();
            }
        }
    }

    @FunctionalInterface
    public interface Listener {
        void changed(Object ignored);
    }

    public static final class CompletePack {
        int partRefreshCount;
        int deformerRefreshCount;
        int repaintCount;

        public void updatePartPalette(final boolean immediate) {
            partRefreshCount++;
        }

        public void updateDeformerPalette(final boolean immediate) {
            deformerRefreshCount++;
        }

        public void repaintCanvas(final boolean immediate) {
            repaintCount++;
        }
    }

    public static final class HostPart {
        final PartSource source;

        HostPart(final PartSource source) {
            this.source = source;
        }

        public PartSource source() {
            return source;
        }
    }

    public abstract static class Deformer {
        final ObjectSource source;

        Deformer(final ObjectSource source) {
            this.source = source;
        }

        public ObjectSource source() {
            return source;
        }

        public Form currentForm() {
            return new WarpForm();
        }
    }

    public static class Form {
        Guid guid;

        public void setGuid(final Guid value) {
            guid = value;
        }

        public float opacity() {
            return 1F;
        }

        public int drawOrder() {
            return 0;
        }
    }

    public static final class PartForm extends Form {
        public PartForm(final PartSource source, final Object instance) {}
    }

    public static final class ArtMeshForm extends Form {
        float[] values = new float[0];

        public ArtMeshForm() {}

        public ArtMeshForm(final ACDrawableSource source, final ArtMesh instance, final CoordType coordType) {}

        public float[] positions() {
            return values.clone();
        }

        public void setPositions(final float[] positions) {
            values = positions.clone();
        }
    }

    public static final class WarpForm extends Form {
        float[] positions;

        WarpForm() {
            this.positions = new float[0];
        }

        WarpForm(final float[] positions) {
            this.positions = positions.clone();
        }

        public WarpForm(final WarpDeformerSource source, final Warp instance, final CoordType coordType) {
            this.positions = new float[0];
        }

        public float[] positions() {
            return positions.clone();
        }

        public void setPositions(final float[] values) {
            positions = values.clone();
        }
    }

    public static final class RotationForm extends Form {
        float angle;
        float originX;
        float originY;
        float scale = 1F;
        boolean reflectX;
        boolean reflectY;

        public RotationForm() {}

        public RotationForm(final RotationDeformerSource source, final Rotation instance, final CoordType coordType) {}

        public float angle() {
            return angle;
        }

        public void setAngle(final float value) {
            angle = value;
        }

        public float originX() {
            return originX;
        }

        public void setOriginX(final float value) {
            originX = value;
        }

        public float originY() {
            return originY;
        }

        public void setOriginY(final float value) {
            originY = value;
        }

        public float scale() {
            return scale;
        }

        public void setScale(final float value) {
            scale = value;
        }

        public boolean reflectX() {
            return reflectX;
        }

        public void setReflectX(final boolean value) {
            reflectX = value;
        }

        public boolean reflectY() {
            return reflectY;
        }

        public void setReflectY(final boolean value) {
            reflectY = value;
        }
    }

    public static final class ObjectList {
        final List<Object> values = new ArrayList<>();

        public boolean add(final Object value) {
            return values.add(value);
        }

        Object first() {
            return values.isEmpty() ? null : values.get(0);
        }
    }

    public static final class KeyformGridSource {
        final ObjectSource source;
        List<Guid> forms = List.of();

        public KeyformGridSource(final ObjectSource source) {
            this.source = source;
        }

        public void importCubism21(
                final ModelSource modelSource,
                final List<?> parameters,
                final List<Guid> formGuids,
                final Object context) {
            forms = List.copyOf(formGuids);
        }
    }

    public static final class CoordType {
        private static final CoordType CANVAS = new CoordType();

        public static CoordType canvas() {
            return CANVAS;
        }
    }

    public static final class Warp extends Deformer {
        Warp(final WarpDeformerSource source) {
            super(source);
        }

        @Override
        public Form currentForm() {
            final Object form = ((WarpDeformerSource) source).keyforms.first();
            if (form instanceof WarpForm warpForm) return warpForm;
            final WarpDeformerSource warp = (WarpDeformerSource) source;
            return new WarpForm(new float[(warp.row() + 1) * (warp.col() + 1) * 2]);
        }
    }

    public static final class Rotation extends Deformer {
        Rotation(final RotationDeformerSource source) {
            super(source);
        }

        @Override
        public Form currentForm() {
            final Object form = ((RotationDeformerSource) source).keyforms.first();
            return form instanceof RotationForm rotationForm ? rotationForm : new RotationForm();
        }
    }

    public static final class ArtMesh {
        final ACDrawableSource source;

        ArtMesh(final ACDrawableSource source) {
            this.source = source;
        }

        public ACDrawableSource source() {
            return source;
        }

        public ArtMeshForm currentForm() {
            final Object form = source.keyforms.first();
            return form instanceof ArtMeshForm artMeshForm ? artMeshForm : new ArtMeshForm();
        }
    }

    public static final class GlueSource {
        public ACDrawableSource targetA() {
            return null;
        }

        public ACDrawableSource targetB() {
            return null;
        }
    }

    public static class Id {
        final String value;

        public Id(final String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    public static final class Guid extends Id {
        private static int counter;

        public Guid() {
            super("guid-" + (++counter));
        }

        Guid(final String value) {
            super(value);
        }
    }

    private static final class Fixture {
        static {
            ModelSource.counter = 0;
        }

        final ModelSource source = new ModelSource();
        final PartSource rootPart = new PartSource("Root", source);
        final PartSource parentPart = new PartSource("Parent", source);
        final PartSource childPart = new PartSource("Child", source);
        final WarpDeformerSource warpSource = new WarpDeformerSource("WarpA", source);
        final WarpDeformerSource warpTargetSource = new WarpDeformerSource("WarpB", source);
        final RotationDeformerSource rotationSource = new RotationDeformerSource("RotationA", source);
        final ACDrawableSource meshSource = new ACDrawableSource("MeshA", source);
        final PartSourceSet partSet = source.partSet;
        final DeformerSourceSet deformerSet = source.deformerSet;
        final DrawableSourceSet drawableSet = source.drawableSet;
        final UpdateManager updateManager;
        final Document document;
        final EditMode editMode;
        final CompletePack pack;
        final Host host = Host.instance();

        Fixture() {
            source.rootPart = rootPart;
            // Part tree: Root → Parent → Child.
            source.partSet.add(rootPart, -1);
            source.partSet.add(parentPart, -1);
            source.partSet.add(childPart, -1);
            rootPart.addChild(parentPart, -1);
            parentPart.addChild(childPart, -1);

            // Deformers and the drawable hang under Child.
            source.deformerSet.add(warpSource, -1);
            source.deformerSet.add(rotationSource, -1);
            childPart.addChild(warpSource, -1);
            childPart.addChild(rotationSource, -1);

            meshSource.localName = "MeshA";
            source.drawableSet.sources.add(meshSource);
            source.allObjects.add(meshSource);
            source.model.meshes.add(new ArtMesh(meshSource));
            childPart.addChild(meshSource, -1);

            // Reset counters polluted by fixture construction (host-side bootstrap is not a
            // hierarchy edit performed through the adapter).
            source.deformerSet.addCount = 0;
            rootPart.addChildCalls = 0;
            parentPart.addChildCalls = 0;
            childPart.addChildCalls = 0;
            rootPart.removeChildCalls = 0;
            parentPart.removeChildCalls = 0;
            childPart.removeChildCalls = 0;

            document = new Document(source);
            updateManager = document.updateManager;
            editMode = document.editMode;
            pack = document.pack;
        }

        void replacePartWithSameId() {
            final PartSource replacement = new PartSource("Root", source);
            source.partSet.sources.clear();
            source.partSet.sources.add(replacement);
            source.model.parts.clear();
            source.model.parts.add(new HostPart(replacement));
        }

        void replaceDeformerWithSameId() {
            final WarpDeformerSource replacement = new WarpDeformerSource("WarpA", source);
            source.deformerSet.sources.clear();
            source.deformerSet.sources.add(replacement);
            source.model.deformers.clear();
            source.model.deformers.add(new Warp(replacement));
        }

        /**
         * Registers the spare deformer target. Kept out of the constructor so the shared fixture
         * keeps its documented deformer/drawable counts for every other test.
         */
        WarpDeformerSource attachWarpTarget() {
            source.deformerSet.add(warpTargetSource, -1);
            childPart.addChild(warpTargetSource, -1);
            source.deformerSet.addCount = 0;
            childPart.addChildCalls = 0;
            return warpTargetSource;
        }
    }
}
