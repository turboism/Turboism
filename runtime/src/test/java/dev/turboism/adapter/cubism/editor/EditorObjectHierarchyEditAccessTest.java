package dev.turboism.adapter.cubism.editor;

import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.editor.history.EditorHistorySnapshotProvider;
import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.adapter.cubism.editor.transaction.RuntimeAuthoringTransactionProvider;
import dev.turboism.adapter.cubism.model.RuntimeModelObjectService;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorDeformerInspectorSelectorContract;
import dev.turboism.mapping.verification.selector.EditorHistoryReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorInspectorDrawableWriteSelectorContract;
import dev.turboism.mapping.verification.selector.EditorObjectHierarchyEditSelectorContract;
import dev.turboism.mapping.verification.selector.EditorObjectReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartNameSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartStructureSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartTreeSelectorContract;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryChange;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistoryRelationChange;
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
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

    @ParameterizedTest
    @ValueSource(strings = {"5.3.02", "5.3.04"})
    void sourceGeometryBatchPublishesOnceAndPreservesBothCoordinateDomains(final String version) {
        final Fixture fixture = new Fixture();
        fixture.source.treeMaterialization = true;
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver(version), "session-a").active();
        final var geometry = nativeGeometry();
        final var neutral =
                geometry.positions().stream().map(p -> new Point2(0, p.y())).toList();
        final var before = List.copyOf(fixture.drawableSet.sources);
        final var created = model.drawables()
                .createManyWithSourceGeometry(List.of("First", "First", "Third").stream()
                        .map(name -> new dev.turboism.sdk.cubism.model.Drawables.SourceGeometryRequest(
                                name, java.util.Optional.empty(), geometry, neutral))
                        .toList());
        assertEquals(1, fixture.source.updateCount, "publish each complete mesh batch once");
        final var ids =
                created.stream().map(dev.turboism.sdk.cubism.model.Drawable::id).toList();
        assertEquals(3, new java.util.HashSet<>(ids).size());
        for (var mesh : created) {
            assertStoredSourceGeometry(fixture, mesh.id(), geometry);
            assertEquals(neutral, mesh.geometry().positions());
        }
        assertEquals(1, fixture.editMode.edits.size());
        assertEquals(3, fixture.editMode.edits.get(0).children.size());
        fixture.editMode.edits.get(0).undo();
        assertEquals(before, fixture.drawableSet.sources);
        fixture.editMode.edits.get(0).redo();
        for (var id : ids) {
            var mesh = model.drawables().find(id);
            assertStoredSourceGeometry(fixture, mesh.id(), geometry);
            assertEquals(neutral, mesh.geometry().positions());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.3.02", "5.3.04"})
    void meshBatchOwnsRejectedEagerInsertionsAndRestoresEveryTree(final String version) {
        for (boolean nativeAbort : List.of(false, true)) {
            final var scoped = ScopedFixture.onEdt(version);
            scoped.host.nativeAbort = nativeAbort;
            final var before = List.copyOf(scoped.fixture.drawableSet.sources);
            final var children = List.copyOf(scoped.fixture.rootPart.children);
            scoped.fixture.editMode.rejectAdmissionAt = 2;
            final var input = new dev.turboism.sdk.cubism.model.Drawables.SourceGeometryRequest(
                    "Mesh",
                    java.util.Optional.empty(),
                    nativeGeometry(),
                    nativeGeometry().positions());
            final var result = scoped.execute(
                    AuthoringTransactionOptions.of("mesh batch rejection"),
                    () -> scoped.access.createArtMeshSources(
                            "model", scoped.fixture.source, scoped.fixture.source.model, List.of(input, input, input)));
            assertEquals(
                    AuthoringTransactionOutcome.ROLLED_BACK,
                    result.outcome(),
                    () -> String.valueOf(result.diagnosticId()));
            assertEquals(before, scoped.fixture.drawableSet.sources);
            assertEquals(children, scoped.fixture.rootPart.children);
            assertEquals(0, scoped.fixture.editMode.edits.size());
        }
    }

    @Test
    void meshBatchPreflightsAllParentIdsBeforeAnyInsertion() {
        final Fixture fixture = new Fixture();
        fixture.source.treeMaterialization = true;
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver("5.3.04"), "session-a").active();
        final var before = List.copyOf(fixture.drawableSet.sources);
        final var good = new dev.turboism.sdk.cubism.model.Drawables.SourceGeometryRequest(
                "First",
                java.util.Optional.empty(),
                nativeGeometry(),
                nativeGeometry().positions());
        final var bad = new dev.turboism.sdk.cubism.model.Drawables.SourceGeometryRequest(
                "Second",
                java.util.Optional.of(new PartId("Absent")),
                nativeGeometry(),
                nativeGeometry().positions());
        assertThrows(
                NoSuchElementException.class, () -> model.drawables().createManyWithSourceGeometry(List.of(good, bad)));
        assertEquals(List.of(), model.drawables().createManyWithSourceGeometry(List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new dev.turboism.sdk.cubism.model.Drawables.SourceGeometryRequest(
                        "Mismatch", java.util.Optional.empty(), nativeGeometry(), List.of(new Point2(0, 0))));
        assertEquals(before, fixture.drawableSet.sources);
        assertEquals(0, fixture.source.updateCount);
        assertEquals(0, fixture.editMode.beginCalls);
    }

    @Test
    void meshBatchRetainsDistinctPartOwnershipAcrossUndoRedo() {
        final Fixture fixture = new Fixture();
        fixture.source.treeMaterialization = true;
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver("5.3.04"), "session-a").active();
        final var parts = model.parts().createMany(List.of("Left", "Right"), null);
        final var ids =
                parts.stream().map(dev.turboism.sdk.cubism.model.Part::id).toList();
        fixture.source.updateCount = 0;
        final var inputs = ids.stream()
                .map(id -> new dev.turboism.sdk.cubism.model.Drawables.SourceGeometryRequest(
                        "Mesh",
                        java.util.Optional.of(id),
                        nativeGeometry(),
                        nativeGeometry().positions()))
                .toList();
        final var meshes = model.drawables().createManyWithSourceGeometry(inputs);
        final var meshIds =
                meshes.stream().map(dev.turboism.sdk.cubism.model.Drawable::id).toList();
        assertEquals(1, fixture.source.updateCount);
        for (int index = 0; index < 2; index++)
            assertEquals(
                    java.util.Optional.of(ids.get(index)), meshes.get(index).parentPartId());
        final var undo = fixture.editMode.edits.get(1);
        undo.undo();
        for (var id : ids) assertTrue(model.parts().find(id).childIds().isEmpty());
        for (var id : meshIds)
            assertThrows(NoSuchElementException.class, () -> model.drawables().find(id));
        undo.redo();
        for (int index = 0; index < 2; index++)
            assertEquals(
                    java.util.Optional.of(ids.get(index)),
                    model.drawables().find(meshIds.get(index)).parentPartId());
    }

    private static void assertStoredSourceGeometry(Fixture fixture, ArtMeshId id, ArtMeshGeometry expected) {
        var source = fixture.drawableSet.sources.stream()
                .filter(value -> value.id.value().equals(id.value()))
                .findFirst()
                .orElseThrow();
        for (int i = 0; i < expected.positions().size(); i++) {
            assertEquals(expected.positions().get(i).x(), source.positions()[i * 2]);
            assertEquals(expected.positions().get(i).y(), source.positions()[i * 2 + 1]);
            assertEquals(expected.uvs().get(i).x(), source.uvs()[i * 2]);
            assertEquals(expected.uvs().get(i).y(), source.uvs()[i * 2 + 1]);
        }
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                expected.triangleIndices().stream().mapToInt(Integer::intValue).toArray(), source.indices());
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

    @ParameterizedTest
    @ValueSource(strings = {"5.3.02", "5.3.04"})
    void siblingPartsRejectMiddleAdmissionWithoutResidue(final String version) {
        for (boolean nativeAbort : List.of(false, true)) {
            final var scoped = ScopedFixture.onEdt(version);
            scoped.host.nativeAbort = nativeAbort;
            final var before = List.copyOf(scoped.fixture.partSet.sources);
            final var children = List.copyOf(scoped.fixture.rootPart.children);
            scoped.fixture.editMode.rejectAdmissionAt = 2;
            final var result = scoped.execute(
                    AuthoringTransactionOptions.of("batch siblings"),
                    () -> scoped.access.createPartSources(
                            "model",
                            scoped.fixture.source,
                            scoped.fixture.source.model,
                            List.of("First", "Second", "Third"),
                            scoped.fixture.rootPart));
            assertEquals(
                    AuthoringTransactionOutcome.ROLLED_BACK,
                    result.outcome(),
                    () -> String.valueOf(result.diagnosticId()));
            assertEquals(before, scoped.fixture.partSet.sources);
            assertEquals(children, scoped.fixture.rootPart.children);
            assertEquals(0, scoped.fixture.editMode.edits.size());
        }
    }

    @Test
    void largeSiblingBatchKeepsEveryNativeUndoBeyondSemanticDetailLimit() {
        final Fixture fixture = new Fixture();
        fixture.source.treeMaterialization = true;
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver("5.3.04"), "session-a").active();
        final var before = List.copyOf(fixture.rootPart.children);
        final var created = model.parts()
                .createMany(
                        java.util.stream.IntStream.range(0, 80)
                                .mapToObj(index -> "Patch " + index)
                                .toList(),
                        null);
        assertEquals(80, created.size());
        assertEquals(1, fixture.source.updateCount);
        assertEquals(80, fixture.editMode.edits.get(0).children.size());
        fixture.editMode.edits.get(0).undo();
        assertEquals(before, fixture.rootPart.children);
        fixture.editMode.edits.get(0).redo();
        assertEquals(before.size() + 80, fixture.rootPart.children.size());
    }

    @Test
    void siblingPartsValidateAllNamesBeforeInsertionAndEmptyBatchDoesNothing() {
        final Fixture fixture = new Fixture();
        fixture.source.treeMaterialization = true;
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver("5.3.04"), "session-a").active();
        final var parent = model.parts().find(new PartId("Root"));
        assertThrows(IllegalArgumentException.class, () -> model.parts().createMany(List.of("Valid", " "), parent));
        assertEquals(List.of(), model.parts().createMany(List.of(), parent));
        assertEquals(0, fixture.source.updateCount);
        assertEquals(0, fixture.editMode.beginCalls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.3.02", "5.3.04"})
    void siblingPartsPublishOnceAndPreserveUndoOrder(final String version) {
        final Fixture fixture = new Fixture();
        fixture.source.treeMaterialization = true;
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver(version), "session-a").active();
        final var parent = model.parts().find(new PartId("Root"));
        final var before = List.copyOf(fixture.rootPart.children);
        final var created = model.parts().createMany(List.of("Patch", "Patch", "Third"), parent);
        assertEquals(1, fixture.source.updateCount, "publish the complete sibling batch once");
        final var ids =
                created.stream().map(dev.turboism.sdk.cubism.model.Part::id).toList();
        assertEquals(3, new java.util.HashSet<>(ids).size());
        assertEquals(
                List.of("Patch", "Patch", "Third"),
                created.stream().map(dev.turboism.sdk.cubism.model.Part::name).toList());
        assertEquals(1, fixture.editMode.edits.size());
        assertEquals(3, fixture.editMode.edits.get(0).children.size());
        fixture.editMode.edits.get(0).undo();
        assertEquals(before, fixture.rootPart.children);
        for (var id : ids)
            assertThrows(NoSuchElementException.class, () -> model.parts().find(id));
        fixture.editMode.edits.get(0).redo();
        for (var id : ids)
            assertEquals(
                    java.util.Optional.of(new PartId("Root")),
                    model.parts().find(id).parentId());
        assertEquals(before.size() + 3, fixture.rootPart.children.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.3.02", "5.3.04"})
    void initializedWarpPublishesFinalGridAndBothParentsWithOneUpdate(final String version) {
        for (boolean targeted : List.of(false, true)) {
            final Fixture fixture = new Fixture();
            fixture.source.treeMaterialization = true;
            Host.document = fixture.document;
            final var model = new EditorBackedCubismModelAccess(resolver(version), "session-a").active();
            final var target =
                    targeted ? java.util.Optional.of(new DeformerId("WarpA")) : java.util.Optional.<DeformerId>empty();
            final var grid = new WarpGrid(
                    1,
                    1,
                    true,
                    List.of(
                            new Point2(.1f, .2f), new Point2(.8f, .2f),
                            new Point2(.1f, .9f), new Point2(.8f, .9f)));
            final var warp = model.deformers()
                    .createWarp("Final Warp", model.parts().find(new PartId("Root")), -1, target, grid);
            final var id = warp.id();
            assertEquals(1, fixture.source.updateCount, "one publication, no intermediate reparent/grid updates");
            assertEquals(grid, warp.grid());
            assertEquals(target, warp.parentDeformerId());
            assertEquals(java.util.Optional.of(new PartId("Root")), warp.parentPartId());
            assertEquals(1, fixture.editMode.edits.size());
            assertEquals(1, fixture.editMode.edits.get(0).children.size(), "one native insertion Undo");
            fixture.editMode.edits.get(0).undo();
            assertThrows(
                    NoSuchElementException.class, () -> model.warpDeformers().find(id));
            fixture.editMode.edits.get(0).redo();
            final var restored = model.warpDeformers().find(id);
            assertEquals(grid, restored.grid());
            assertEquals(target, restored.parentDeformerId());
            assertEquals(java.util.Optional.of(new PartId("Root")), restored.parentPartId());
        }
    }

    @Test
    void initializedWarpRejectsMissingTargetBeforeCreation() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver("5.3.04"), "session-a").active();
        final var before = List.copyOf(fixture.deformerSet.sources);
        assertThrows(
                NoSuchElementException.class,
                () -> model.deformers()
                        .createWarp(
                                "Missing target",
                                null,
                                -1,
                                java.util.Optional.of(new DeformerId("Missing")),
                                EditorObjectHierarchyEditAccess.defaultWarpGrid(2, 2)));
        assertEquals(before, fixture.deformerSet.sources);
        assertEquals(0, fixture.source.updateCount);
        assertTrue(fixture.editMode.edits.isEmpty());
    }

    @Test
    void initializedWarpRejectedInsertionRestoresMembershipAndPartTree() {
        final var scoped = ScopedFixture.onEdt("5.3.04");
        final var before = List.copyOf(scoped.fixture.source.deformerSet.sources);
        final var children = List.copyOf(scoped.fixture.rootPart.children);
        scoped.fixture.editMode.rejectNextAdmission = 1;
        final var result = scoped.execute(AuthoringTransactionOptions.of("initialized Warp rejection"), () -> {
            scoped.access.createWarpSource(
                    "model",
                    scoped.fixture.source,
                    scoped.fixture.source.model,
                    "Rejected final Warp",
                    scoped.fixture.rootPart,
                    false,
                    -1,
                    EditorObjectHierarchyEditAccess.defaultWarpGrid(2, 2),
                    scoped.fixture.warpSource);
            return null;
        });
        assertEquals(AuthoringTransactionOutcome.ROLLED_BACK, result.outcome());
        assertEquals(before, scoped.fixture.source.deformerSet.sources);
        assertEquals(children, scoped.fixture.rootPart.children);
        assertTrue(scoped.fixture.editMode.edits.isEmpty());
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

    @ParameterizedTest
    @ValueSource(strings = {"artmesh", "deformer"})
    void targetDeformerAliasCapturesOneTypedRelation(final String targetKind) {
        final Fixture fixture = new Fixture();
        final boolean isArtMesh = "artmesh".equals(targetKind);
        if (isArtMesh) {
            Host.document = fixture.document;
            fixture.meshSource.targetDeformerGuid = fixture.warpSource.guid;
        } else {
            fixture.warpSource.targetDeformerGuid = fixture.rotationSource.guid;
            fixture.attachWarpTarget();
            Host.document = fixture.document;
        }
        final VerifiedMemberResolver resolver = relationAliasResolver("5.3.02");
        final var model = new EditorBackedCubismModelAccess(resolver, "session-a").active();

        if (isArtMesh) {
            model.drawables().find(new ArtMeshId("MeshA")).setTargetDeformer(Optional.of(new DeformerId("RotationA")));
        } else {
            model.warpDeformers().find(new DeformerId("WarpA")).setTargetDeformer(Optional.of(new DeformerId("WarpB")));
        }

        assertEquals(1, fixture.editMode.edits.size());
        assertEquals(1, fixture.document.undoManager.entries().size());
        assertEquals(1, isArtMesh ? fixture.meshSource.setTargetCalls : fixture.warpSource.setTargetCalls);
        assertEquals(
                isArtMesh ? fixture.rotationSource.guid : fixture.warpTargetSource.guid,
                isArtMesh ? fixture.meshSource.targetDeformerGuid : fixture.warpSource.targetDeformerGuid);
        final HistoryEntryDetail detail = lastHistoryDetail(resolver);
        assertEquals(1, detail.changes().size());
        final HistoryRelationChange relation =
                detail.changes().get(0).relation().orElseThrow();
        assertEquals(HistoryRelationChange.Kind.DEFORMER_PARENT, relation.kind());
        assertRelationTarget(
                relation.before(),
                isArtMesh ? "WARP_DEFORMER" : "ROTATION_DEFORMER",
                isArtMesh ? "WarpA" : "RotationA",
                isArtMesh ? "WarpA" : "RotationA");
        assertRelationTarget(
                relation.after(),
                isArtMesh ? "ROTATION_DEFORMER" : "WARP_DEFORMER",
                isArtMesh ? "RotationA" : "WarpB",
                isArtMesh ? "RotationA" : "WarpB");

        fixture.editMode.edits.get(0).undo();
        assertEquals(
                isArtMesh ? fixture.warpSource.guid : fixture.rotationSource.guid,
                isArtMesh ? fixture.meshSource.targetDeformerGuid : fixture.warpSource.targetDeformerGuid);
        fixture.editMode.edits.get(0).redo();
        assertEquals(
                isArtMesh ? fixture.rotationSource.guid : fixture.warpTargetSource.guid,
                isArtMesh ? fixture.meshSource.targetDeformerGuid : fixture.warpSource.targetDeformerGuid);
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
    @CsvSource({
        "5.0.00, WARP", "5.0.06, WARP", "5.1.03, WARP", "5.2.00, WARP", "5.2.03, WARP", "5.3.00, WARP", "5.3.04, WARP",
        "5.0.00, ROTATION", "5.0.06, ROTATION", "5.1.03, ROTATION", "5.2.00, ROTATION", "5.2.03, ROTATION",
                "5.3.00, ROTATION", "5.3.04, ROTATION"
    })
    void deformerCreatesJoinOneAmbientRootTransaction(final String cubismVersion, final String kind) {
        final var scoped = ScopedFixture.onEdt(cubismVersion);
        final var result = scoped.execute(AuthoringTransactionOptions.of("create two " + kind.toLowerCase()), () -> {
            if ("WARP".equals(kind)) {
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
                scoped.access.createWarpSource(
                        "model", scoped.fixture.source, scoped.fixture.source.model, "WARP_A", null, false, -1, grid);
                scoped.access.createWarpSource(
                        "model", scoped.fixture.source, scoped.fixture.source.model, "WARP_B", null, false, -1, grid);
            } else {
                final RotationDeformerForm form = new RotationDeformerForm(0F, 512F, 512F, 1F, false, false);
                scoped.access.createRotationSource(
                        "model",
                        scoped.fixture.source,
                        scoped.fixture.source.model,
                        "ROTATION_A",
                        null,
                        false,
                        -1,
                        form);
                scoped.access.createRotationSource(
                        "model",
                        scoped.fixture.source,
                        scoped.fixture.source.model,
                        "ROTATION_B",
                        null,
                        false,
                        -1,
                        form);
            }
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
        return hierarchySelectors();
    }

    private static List<StaticSelector> relationSelectors() {
        return hierarchyRelationSelectors();
    }
}
