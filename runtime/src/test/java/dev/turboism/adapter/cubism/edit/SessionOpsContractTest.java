package dev.turboism.adapter.cubism.edit;

import static dev.turboism.adapter.cubism.edit.EditSessionTestFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.edit.DeformerOps;
import dev.turboism.sdk.cubism.edit.EditAlphaBlend;
import dev.turboism.sdk.cubism.edit.EditArtMeshData;
import dev.turboism.sdk.cubism.edit.EditColorBlend;
import dev.turboism.sdk.cubism.edit.EditGlueData;
import dev.turboism.sdk.cubism.edit.EditLabelColor;
import dev.turboism.sdk.cubism.edit.EditLabelColorType;
import dev.turboism.sdk.cubism.edit.EditObjectKind;
import dev.turboism.sdk.cubism.edit.EditObjectNode;
import dev.turboism.sdk.cubism.edit.EditObjectSnapshot;
import dev.turboism.sdk.cubism.edit.EditParameterGroupNode;
import dev.turboism.sdk.cubism.edit.EditParameterKeyCondition;
import dev.turboism.sdk.cubism.edit.EditParameterNode;
import dev.turboism.sdk.cubism.edit.EditPartData;
import dev.turboism.sdk.cubism.edit.EditRotationDeformerData;
import dev.turboism.sdk.cubism.edit.EditSession;
import dev.turboism.sdk.cubism.edit.EditSessionCloseOutcome;
import dev.turboism.sdk.cubism.edit.EditSessionCloseResult;
import dev.turboism.sdk.cubism.edit.EditSessionException;
import dev.turboism.sdk.cubism.edit.EditSessionState;
import dev.turboism.sdk.cubism.edit.EditUnavailableException;
import dev.turboism.sdk.cubism.edit.EditWarpDeformerData;
import dev.turboism.sdk.cubism.edit.ParameterKeyOps;
import dev.turboism.sdk.cubism.edit.ParameterStructureOps;
import dev.turboism.sdk.cubism.edit.PartObjectOps;
import dev.turboism.sdk.cubism.edit.SelectionOps;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.GlueId;
import dev.turboism.sdk.cubism.model.ModelObjectKind;
import dev.turboism.sdk.cubism.model.ModelObjectReference;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Fake-host contract tests for the T3 routed operation families. The scripted {@link
 * FakeOpsAccess} stands in for the verified member surface: every operation must pass its
 * capability gate before a member runs, writes must register undo on the session edit token
 * and finish with the refresh envelope, and unverifiable fields/rows must fail closed before
 * any host member is reached.
 */
final class SessionOpsContractTest {

    // ------------------------------------------------------------------
    // parameter-key family
    // ------------------------------------------------------------------

    @Test
    void addParameterKeyCapturesUndoMutatesAndRefreshes() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        assertTrue(session.parameterKeys()
                .addParameterKey(new ParameterKeyOps.AddParameterKey(
                        new ModelObjectReference(ModelObjectKind.ART_MESH, "mesh1"), new ParameterId("AngleZ"), 0.5)));

        final List<FakeOpsAccess.Call> addKey = fixture.access.callsOf("cubism.editor-model.keyform-grid.add-key");
        assertEquals(1, addKey.size());
        assertEquals(0.5f, addKey.get(0).arguments().get(0));
        assertEquals(fixture.paramSource.guid, addKey.get(0).arguments().get(1));
        assertEquals(List.of("AddParameterKey"), fixture.host.opsDispatchLabels());
        assertTrue(fixture.access.called("cubism.editor-model.undo.add"));
        assertRefreshRan(fixture.access);
    }

    @Test
    void moveParameterKeyRejectsForceOverwriteBeforeDispatch() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        assertThrows(
                EditUnavailableException.class,
                () -> session.parameterKeys()
                        .moveParameterKey(new ParameterKeyOps.MoveParameterKey(
                                Optional.empty(), Optional.of(new ParameterId("AngleZ")), 0.0, 0.5, false, true)));
        assertFalse(fixture.host.opsDispatchLabels().contains("MoveParameterKey"));
        assertFalse(fixture.access.called("cubism.editor-model.undo.add"));
    }

    // ------------------------------------------------------------------
    // parameter-structure family
    // ------------------------------------------------------------------

    @Test
    void parameterStructureReadsTheGroupTree() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        final EditParameterGroupNode root = session.parameterStructure().parameterStructure();

        assertEquals(new ParameterGroupId("root-group"), root.id());
        assertEquals("Root", root.name());
        assertEquals(2, root.children().size());
        final EditParameterNode leaf = (EditParameterNode) root.children().get(1);
        assertEquals(new ParameterId("AngleZ"), leaf.id());
        assertEquals("Angle Z", leaf.name());
        assertEquals(List.of("GetParameterStructure"), fixture.host.opsDispatchLabels());
    }

    @Test
    void addParameterConstructsAndAttachesUnderTheRootGroup() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        assertTrue(session.parameterStructure()
                .addParameter(new ParameterStructureOps.AddParameter(
                        Optional.of("Mouth"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        false)));

        assertTrue(fixture.access.constructed("cubism.editor-model.parameter-source.create"));
        assertEquals(
                1,
                fixture.access
                        .callsOf("cubism.editor-model.parameter-group-handler.add-parameter-child")
                        .size());
        assertTrue(fixture.access.called("cubism.editor-model.undo.add"));
        assertRefreshRan(fixture.access);
    }

    @Test
    void editParameterSnapshotsThenAppliesDirectSettersInsideTheSessionBracket() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        assertTrue(session.parameterStructure()
                .editParameter(new ParameterStructureOps.EditParameter(
                        new ParameterId("AngleZ"),
                        Optional.empty(),
                        Optional.of("Renamed"),
                        Optional.of(0.1),
                        Optional.of(0.2),
                        Optional.of(5.0),
                        Optional.of(true))));

        // The write snapshots the source into a session-owned SimpleUndo before mutating —
        // the property-editor path that opened its own history entry is never reached.
        assertTrue(fixture.access.constructed("cubism.editor-model.simple-undo.create"));
        assertTrue(fixture.access.indexOf("cubism.editor-model.simple-undo.create")
                < fixture.access.indexOf("cubism.editor-model.parameter-source.set-name"));
        assertTrue(fixture.access.called("cubism.editor-model.parameter-source.set-minimum"));
        assertTrue(fixture.access.called("cubism.editor-model.parameter-source.set-maximum"));
        assertTrue(fixture.access.called("cubism.editor-model.parameter-source.set-default"));
        assertTrue(fixture.access.called("cubism.editor-model.parameter-source.set-repeat"));
        assertFalse(fixture.access.called("cubism.editor-model.parameter-property-editor.update-definition"));
        assertTrue(fixture.access.called("cubism.editor-model.undo.add"));
        assertRefreshRan(fixture.access);
    }

    @Test
    void editParameterNewIdValidatesThenRenamesThroughTheVerifiedSetter() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        assertTrue(session.parameterStructure()
                .editParameter(new ParameterStructureOps.EditParameter(
                        new ParameterId("AngleZ"),
                        Optional.of(new ParameterId("AngleZ2")),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty())));

        assertTrue(fixture.access.called("cubism.editor-model.parameter-validator.valid-id"));
        assertTrue(fixture.access.indexOf("cubism.editor-model.parameter-validator.valid-id")
                < fixture.access.indexOf("cubism.editor-model.parameter-source.set-id"));
        assertTrue(fixture.access.called("cubism.editor-model.parameter-source.set-id"));
        assertTrue(fixture.access.constructed("cubism.editor-model.parameter-id.create"));
    }

    @Test
    void editParameterWithNoFieldsSkipsTheUndoCapture() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        assertTrue(session.parameterStructure()
                .editParameter(new ParameterStructureOps.EditParameter(
                        new ParameterId("AngleZ"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty())));

        assertFalse(fixture.access.constructed("cubism.editor-model.simple-undo.create"));
        assertFalse(fixture.access.called("cubism.editor-model.undo.add"));
    }

    @Test
    void moveParameterGroupFailsClosedWithoutDispatch() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        assertThrows(
                EditUnavailableException.class,
                () -> session.parameterStructure()
                        .moveParameterGroup(
                                new ParameterStructureOps.MoveParameterGroup(new ParameterGroupId("g1"), 0)));
        assertFalse(fixture.host.opsDispatchLabels().contains("MoveParameterGroup"));
    }

    // ------------------------------------------------------------------
    // selection family
    // ------------------------------------------------------------------

    @Test
    void selectedObjectsTranslatesGuidsToIdsAndSkipsStaleEntries() throws EditSessionException {
        final Fixture fixture = new Fixture();
        fixture.access.selectionGuids = List.of(fixture.mesh.guid, new HostGuid("stale"));
        final EditSession session = fixture.open();

        assertEquals(List.of(new ModelObjectId("mesh1")), session.selection().selectedObjects());
        assertEquals(List.of("GetSelectedObjects"), fixture.host.opsDispatchLabels());
    }

    @Test
    void addSelectedObjectsWritesTheUnionInHostOrder() throws EditSessionException {
        final Fixture fixture = new Fixture();
        fixture.access.selectionGuids = List.of(fixture.other.guid);
        final EditSession session = fixture.open();

        assertTrue(session.selection()
                .addSelectedObjects(new SelectionOps.AddSelectedObjects(
                        List.of(new ModelObjectId("mesh1"), new ModelObjectId("other")))));

        final List<FakeOpsAccess.Call> writes =
                fixture.access.callsOf("cubism.editor-model.update-manager.set-selection");
        assertEquals(1, writes.size());
        assertEquals(
                List.of(fixture.mesh.guid, fixture.other.guid),
                writes.get(0).arguments().get(1));
        assertEquals(Boolean.FALSE, writes.get(0).arguments().get(2));
        assertEquals(Boolean.TRUE, writes.get(0).arguments().get(3));
    }

    @Test
    void clearSelectedObjectsWritesAnEmptySelection() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        assertTrue(session.selection().clearSelectedObjects());

        final List<FakeOpsAccess.Call> writes =
                fixture.access.callsOf("cubism.editor-model.update-manager.set-selection");
        assertEquals(1, writes.size());
        assertEquals(List.of(), writes.get(0).arguments().get(1));
    }

    // ------------------------------------------------------------------
    // part/object family
    // ------------------------------------------------------------------

    @Test
    void partStructureReadsTheRootTree() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        final EditObjectNode root = session.partObjects().partStructure();

        assertEquals("root-part", root.id().value());
        assertEquals(EditObjectKind.PART, root.kind());
        assertEquals(1, root.children().size());
        assertEquals(EditObjectKind.ART_MESH, root.children().get(0).kind());
        assertEquals("mesh1", root.children().get(0).id().value());
    }

    @Test
    void deleteObjectClearsSelectionRemovesAndRefreshes() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        assertTrue(session.partObjects()
                .deleteObject(
                        new PartObjectOps.DeleteObject(new ModelObjectReference(ModelObjectKind.ART_MESH, "mesh1"))));

        final List<FakeOpsAccess.Call> removed =
                fixture.access.callsOf("cubism.editor-model.model-handler.remove-objects");
        assertEquals(1, removed.size());
        assertEquals(List.of(fixture.mesh), removed.get(0).arguments().get(0));
        assertTrue(fixture.access.called("cubism.editor-model.undo.add"));
        assertRefreshRan(fixture.access);
    }

    @Test
    void deleteObjectRejectsArtPathObjects() throws EditSessionException {
        final Fixture fixture = new Fixture();
        fixture.access.objects.add(fixture.artPath);
        final EditSession session = fixture.open();

        // The kind check inside requireObjectSource refuses a mismatched reference before
        // the operation can touch a member — art paths are never deletable.
        assertThrows(
                IllegalArgumentException.class,
                () -> session.partObjects()
                        .deleteObject(new PartObjectOps.DeleteObject(
                                new ModelObjectReference(ModelObjectKind.ART_MESH, "path1"))));
        assertFalse(fixture.access.called("cubism.editor-model.model-handler.remove-objects"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "grouped",
                "offscreen",
                "clippingIds",
                "reverseMask",
                "multiplyColor",
                "screenColor",
                "colorBlend",
                "parentId"
            })
    void editPartRejectsEveryUnverifiableField(final String field) throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        // Each BLOCKED field is rejected on its own; the operation never dispatches.
        assertThrows(
                EditUnavailableException.class,
                () -> session.partObjects()
                        .editPart(new PartObjectOps.EditPart(
                                new PartId("root-part"),
                                List.of(),
                                false,
                                Optional.empty(),
                                Optional.empty(),
                                "parentId".equals(field) ? Optional.of(new PartId("parent")) : Optional.empty(),
                                "grouped".equals(field) ? Optional.of(Boolean.TRUE) : Optional.empty(),
                                Optional.empty(),
                                "offscreen".equals(field) ? Optional.of(Boolean.TRUE) : Optional.empty(),
                                "clippingIds".equals(field)
                                        ? Optional.of(List.of(new ModelObjectId("clip")))
                                        : Optional.empty(),
                                "reverseMask".equals(field) ? Optional.of(Boolean.TRUE) : Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                "multiplyColor".equals(field) ? Optional.of("#FF0000") : Optional.empty(),
                                "screenColor".equals(field) ? Optional.of("#00FF00") : Optional.empty(),
                                "colorBlend".equals(field)
                                        ? Optional.of(dev.turboism.sdk.cubism.edit.EditColorBlend.ADD)
                                        : Optional.empty(),
                                Optional.empty(),
                                Optional.empty())));
        assertFalse(fixture.host.opsDispatchLabels().contains("EditPart"));
    }

    @Test
    void editPartNameRunsTheWriteEnvelope() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        assertTrue(
                session.partObjects().editPart(editPart(Optional.empty(), Optional.of("Renamed"), Optional.empty())));

        final List<FakeOpsAccess.Call> renames =
                fixture.access.callsOf("cubism.editor-model.part-source.set-local-name");
        assertEquals(1, renames.size());
        assertEquals("Renamed", renames.get(0).arguments().get(0));
        assertTrue(fixture.access.called("cubism.editor-model.undo.add"));
        assertRefreshRan(fixture.access);
    }

    @ParameterizedTest
    @ValueSource(strings = {"colorBlend", "parentId"})
    void editArtMeshRejectsColorBlendAndParentId(final String field) throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        assertThrows(
                EditUnavailableException.class,
                () -> session.partObjects()
                        .editArtMesh(editArtMesh(
                                Optional.empty(),
                                Optional.empty(),
                                "parentId".equals(field) ? Optional.of(new PartId("p")) : Optional.empty(),
                                "colorBlend".equals(field)
                                        ? Optional.of(dev.turboism.sdk.cubism.edit.EditColorBlend.NORMAL)
                                        : Optional.empty(),
                                Optional.empty(),
                                Optional.empty())));
        assertFalse(fixture.host.opsDispatchLabels().contains("EditArtMesh"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"newId", "intensity", "parentId"})
    void editGlueRejectsNewIdIntensityAndParentId(final String field) throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        assertThrows(
                EditUnavailableException.class,
                () -> session.partObjects()
                        .editGlue(editGlue(
                                "newId".equals(field) ? Optional.of(new GlueId("g2")) : Optional.empty(),
                                Optional.empty(),
                                "parentId".equals(field) ? Optional.of(new PartId("p")) : Optional.empty(),
                                "intensity".equals(field) ? Optional.of(0.5) : Optional.empty())));
        assertFalse(fixture.host.opsDispatchLabels().contains("EditGlue"));
    }

    @Test
    void moveObjectReparentingFailsClosed() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        assertThrows(
                EditUnavailableException.class,
                () -> session.partObjects()
                        .moveObjectOnPartsPalette(new PartObjectOps.MoveObjectOnPartsPalette(
                                new ModelObjectReference(ModelObjectKind.ART_MESH, "mesh1"),
                                Optional.of(new PartId("root-part")),
                                Optional.empty(),
                                Optional.of(0))));
    }

    @Test
    void addPartWithIdsOrNestedFailsClosed() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        assertThrows(
                EditUnavailableException.class,
                () -> session.partObjects()
                        .addPart(new PartObjectOps.AddPart(
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                List.of(new ModelObjectId("mesh1")),
                                false)));
        assertThrows(
                EditUnavailableException.class,
                () -> session.partObjects()
                        .addPart(new PartObjectOps.AddPart(
                                Optional.empty(), Optional.empty(), Optional.empty(), List.of(), true)));
        assertFalse(fixture.host.opsDispatchLabels().contains("AddPart"));
    }

    @Test
    void addPartSeedsAnInitialKeyformAndRegistersUndoOnTheSessionToken() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        assertTrue(session.partObjects()
                .addPart(new PartObjectOps.AddPart(
                        Optional.of("Probe Part"), Optional.empty(), Optional.empty(), List.of(), false)));

        // The official AddPart sequence seeds a CPartForm bound through a fresh CFormGuid
        // and a KeyformGridSource primed by setInitialKeyform — the first instance update
        // must find the initial keyform instead of dereferencing an empty binding table.
        assertTrue(fixture.access.constructed("cubism.editor-model.part-form.create"));
        assertTrue(fixture.access.constructed("cubism.editor-model.form-guid.create"));
        assertTrue(fixture.access.called("cubism.editor-model.form.set-guid"));
        assertTrue(fixture.access.called("cubism.editor-model.part-source.keyforms"));
        assertTrue(fixture.access.called("cubism.editor-model.c-array-list.add"));
        assertTrue(fixture.access.constructed("cubism.editor-model.keyform-grid-source.create"));
        assertTrue(fixture.access.called("cubism.editor-model.keyform-grid-source.set-initial-keyform"));
        assertTrue(fixture.access.called("cubism.editor-model.parameter-controllable-source.set-keyform-grid-source"));
        assertTrue(fixture.access.called("cubism.editor-model.undo.add"));
        assertRefreshRan(fixture.access);
    }

    // ------------------------------------------------------------------
    // session bracket invariants (T7)
    // ------------------------------------------------------------------

    @Test
    void sessionOperationsNeverCommitHistoryWhileTheSessionIsOpen() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();
        final HistorySnapshot before = fixture.host.snapshot();

        session.parameterStructure()
                .editParameter(new ParameterStructureOps.EditParameter(
                        new ParameterId("AngleZ"),
                        Optional.empty(),
                        Optional.of("Renamed"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()));
        session.parameterKeys()
                .addParameterKey(new ParameterKeyOps.AddParameterKey(
                        new ModelObjectReference(ModelObjectKind.ART_MESH, "mesh1"), new ParameterId("AngleZ"), 0.5));
        session.partObjects()
                .addPart(new PartObjectOps.AddPart(
                        Optional.of("Probe Part"), Optional.empty(), Optional.empty(), List.of(), false));

        // Official session semantics: EditEnd commits exactly one entry — while the
        // session is open the history position and entry list stay untouched, because no
        // session operation opens or closes a native edit bracket of its own.
        assertEquals(before, fixture.host.snapshot());
        assertEquals(0, fixture.host.position);
        assertTrue(fixture.host.entries.isEmpty());
        assertTrue(fixture.access.called("cubism.editor-model.undo.add"));
    }

    @Test
    void commitLandsExactlyOneHistoryEntry() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();
        session.partObjects()
                .addPart(new PartObjectOps.AddPart(
                        Optional.of("Probe Part"), Optional.empty(), Optional.empty(), List.of(), false));

        final EditSessionCloseResult result = session.close();

        assertEquals(EditSessionCloseOutcome.COMMITTED, result.outcome());
        assertEquals(EditSessionState.CLOSED, session.state());
        assertEquals(1, fixture.host.entries.size());
        assertEquals(1, fixture.host.position);
    }

    @Test
    void cancelRestoresHistoryWhenTheSessionGroupStaysCurrent() throws EditSessionException {
        final Fixture fixture = new Fixture();
        fixture.host.revertVerified = true;
        final EditSession session = fixture.open();
        final HistorySnapshot before = fixture.host.snapshot();
        session.partObjects()
                .addPart(new PartObjectOps.AddPart(
                        Optional.of("Probe Part"), Optional.empty(), Optional.empty(), List.of(), false));

        final EditSessionCloseResult result = session.cancel();

        // Official path: the session group commits (endEdit(false)) and revert() pops it —
        // net effect is the exact pre-session snapshot.
        assertEquals(EditSessionCloseOutcome.CANCELLED, result.outcome());
        assertEquals(EditSessionState.CANCELLED, session.state());
        assertEquals(before, fixture.host.snapshot());
        assertEquals(0, fixture.host.position);
        assertTrue(fixture.host.entries.isEmpty());
        assertEquals(List.of("revert"), fixture.host.revertedLabels);
    }

    @Test
    void cancelReconcilesACommittedForeignGroupThroughRevert() throws EditSessionException {
        final Fixture fixture = new Fixture();
        fixture.host.revertVerified = true;
        final EditSession session = fixture.open();
        final HistorySnapshot before = fixture.host.snapshot();
        session.partObjects()
                .addPart(new PartObjectOps.AddPart(
                        Optional.of("Probe Part"), Optional.empty(), Optional.empty(), List.of(), false));
        // A host-side edit displaced the session's group and committed its own entry —
        // the deferred palette edit pattern observed on the real host.
        fixture.host.displaceSessionGroup(true);

        final EditSessionCloseResult result = session.cancel();

        assertEquals(EditSessionCloseOutcome.CANCELLED, result.outcome());
        assertEquals(EditSessionState.CANCELLED, session.state());
        assertEquals(before, fixture.host.snapshot());
        assertEquals(0, fixture.host.position);
        assertTrue(fixture.host.entries.isEmpty());
        assertEquals(List.of("revert"), fixture.host.revertedLabels);
    }

    @Test
    void cancelReconcilesAnOpenForeignGroupAndCommittedTail() throws EditSessionException {
        final Fixture fixture = new Fixture();
        fixture.host.revertVerified = true;
        final EditSession session = fixture.open();
        final HistorySnapshot before = fixture.host.snapshot();
        // A foreign edit displaced the session group, committed its entry, then a second
        // host edit is still holding the bracket open at cancel time.
        fixture.host.displaceSessionGroup(true);
        fixture.host.displaceSessionGroup(false);

        final EditSessionCloseResult result = session.cancel();

        assertEquals(EditSessionCloseOutcome.CANCELLED, result.outcome());
        assertEquals(before, fixture.host.snapshot());
        assertEquals(0, fixture.host.position);
        assertTrue(fixture.host.entries.isEmpty());
        assertEquals(List.of("revert"), fixture.host.revertedLabels);
    }

    @Test
    void cancelWithoutVerifiedRevertMovesTheCursorWithUndoRedoTo() throws EditSessionException {
        final Fixture fixture = new Fixture();
        // revert unverified → the compensating strategy reconciles the committed tail at
        // the cursor (undoRedoTo); entries cannot be dropped, so the exact-snapshot check
        // reports the residual entry as a typed recovery failure.
        final EditSession session = fixture.open();
        session.partObjects()
                .addPart(new PartObjectOps.AddPart(
                        Optional.of("Probe Part"), Optional.empty(), Optional.empty(), List.of(), false));
        fixture.host.displaceSessionGroup(true);

        final EditSessionCloseResult result = session.cancel();

        assertEquals(EditSessionCloseOutcome.FAILED, result.outcome());
        assertEquals(List.of(0), fixture.host.cursorMoves);
        assertEquals(0, fixture.host.position);
        assertEquals(EditSessionState.CANCELLED, session.state());
    }

    @Test
    void closeOnADisplacedSessionReconcilesAndReportsFailure() throws EditSessionException {
        final Fixture fixture = new Fixture();
        fixture.host.revertVerified = true;
        final EditSession session = fixture.open();
        final HistorySnapshot before = fixture.host.snapshot();
        session.partObjects()
                .addPart(new PartObjectOps.AddPart(
                        Optional.of("Probe Part"), Optional.empty(), Optional.empty(), List.of(), false));
        fixture.host.displaceSessionGroup(true);

        final EditSessionCloseResult result = session.close();

        // Committing would push the foreign group — the session restores the pre-session
        // snapshot and reports failure instead.
        assertEquals(EditSessionCloseOutcome.FAILED, result.outcome());
        assertEquals(before, fixture.host.snapshot());
        assertEquals(0, fixture.host.position);
        assertTrue(fixture.host.entries.isEmpty());
    }

    // ------------------------------------------------------------------
    // deformer family
    // ------------------------------------------------------------------

    @Test
    void deformerStructureBuildsTheParentChildTree() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        final EditObjectNode root = session.deformers().deformerStructure();

        assertEquals(1, root.children().size());
        final EditObjectNode warp = root.children().get(0);
        assertEquals("warp1", warp.id().value());
        assertEquals(EditObjectKind.WARP_DEFORMER, warp.kind());
        assertEquals(1, warp.children().size());
        assertEquals("rot1", warp.children().get(0).id().value());
        assertEquals(EditObjectKind.ROTATION_DEFORMER, warp.children().get(0).kind());
    }

    @ParameterizedTest
    @ValueSource(strings = {"bezierDivH", "bezierDivV", "inheritDrawOrder", "inheritOpacity"})
    void addWarpDeformerRejectsEachUnverifiableLatticeField(final String field) throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        assertThrows(
                EditUnavailableException.class,
                () -> session.deformers()
                        .addWarpDeformer(addWarp(
                                "bezierDivH".equals(field) ? Optional.of(3) : Optional.empty(),
                                "bezierDivV".equals(field) ? Optional.of(3) : Optional.empty(),
                                "inheritDrawOrder".equals(field) ? Optional.of(Boolean.TRUE) : Optional.empty(),
                                "inheritOpacity".equals(field) ? Optional.of(Boolean.TRUE) : Optional.empty())));
        assertFalse(fixture.host.opsDispatchLabels().contains("AddWarpDeformer"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"bezierDivH", "bezierDivV", "condition"})
    void editWarpDeformerRejectsBezierDivAndConditions(final String field) throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        assertThrows(
                EditUnavailableException.class,
                () -> session.deformers()
                        .editWarpDeformer(editWarp(
                                "condition".equals(field) ? List.of(condition()) : List.of(),
                                "bezierDivH".equals(field) ? Optional.of(4) : Optional.empty(),
                                "bezierDivV".equals(field) ? Optional.of(4) : Optional.empty())));
        assertFalse(fixture.host.opsDispatchLabels().contains("EditWarpDeformer"));
    }

    @Test
    void editRotationDeformerNameRunsTheWriteEnvelope() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        assertTrue(session.deformers()
                .editRotationDeformer(editRotation(List.of(), Optional.of("Renamed"), Optional.empty())));

        final List<FakeOpsAccess.Call> renames =
                fixture.access.callsOf("cubism.editor-model.parameter-controllable-source.set-local-name");
        assertEquals(1, renames.size());
        assertEquals("Renamed", renames.get(0).arguments().get(0));
        assertTrue(fixture.access.called("cubism.editor-model.undo.add"));
        assertRefreshRan(fixture.access);
    }

    // ------------------------------------------------------------------
    // capability gate fail-closed checks (one per family)
    // ------------------------------------------------------------------

    @Test
    void deniedCapabilitiesFailClosedBeforeAnyMember() throws EditSessionException {
        final Fixture fixture = new Fixture();
        fixture.access.denyAll();
        final EditSession session = fixture.open();

        assertThrows(
                EditUnavailableException.class,
                () -> session.parameterKeys()
                        .parameterKeys(new ParameterKeyOps.GetParameterKeys(
                                new ModelObjectReference(ModelObjectKind.ART_MESH, "mesh1"))));
        assertThrows(
                EditUnavailableException.class,
                () -> session.parameterStructure().parameterStructure());
        assertThrows(EditUnavailableException.class, () -> session.selection().selectedObjects());
        assertThrows(EditUnavailableException.class, () -> session.partObjects().partStructure());
        assertThrows(EditUnavailableException.class, () -> session.deformers().deformerStructure());
        // The capability gate is the only surface touched: no member ever ran.
        assertEquals(List.of(), fixture.access.memberCalls());
    }

    @Test
    void conditionedRequestsFailClosedBeforeDispatch() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        assertThrows(
                EditUnavailableException.class,
                () -> session.partObjects()
                        .object(new PartObjectOps.GetObject(
                                new ModelObjectReference(ModelObjectKind.ART_MESH, "mesh1"), List.of(condition()))));
        assertThrows(EditUnavailableException.class, () -> session.partObjects().editPart(editPartConditioned()));
        assertThrows(EditUnavailableException.class, () -> session.partObjects().editArtMesh(editArtMeshConditioned()));
        assertThrows(EditUnavailableException.class, () -> session.partObjects().editGlue(editGlueConditioned()));
        assertThrows(
                EditUnavailableException.class,
                () -> session.deformers()
                        .editRotationDeformer(editRotation(List.of(condition()), Optional.empty(), Optional.empty())));
        assertTrue(fixture.host.opsDispatchLabels().isEmpty());
    }

    // ------------------------------------------------------------------
    // GetObject per-kind payload reads (official 1.1.0 data blocks)
    // ------------------------------------------------------------------

    @Test
    void getObjectReadsTheOfficialWarpDeformerBlock() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        final EditObjectSnapshot snapshot = session.partObjects()
                .object(new PartObjectOps.GetObject(
                        new ModelObjectReference(ModelObjectKind.WARP_DEFORMER, "warp1"), List.of()));

        assertEquals(new ModelObjectId("warp1"), snapshot.object());
        final EditWarpDeformerData data = (EditWarpDeformerData) snapshot.data();
        assertEquals(EditObjectKind.WARP_DEFORMER, data.kind());
        assertEquals("WarpName", data.name());
        assertEquals(Optional.of(new PartId("sub-part")), data.parentId());
        assertEquals(Optional.empty(), data.parentDeformerId());
        assertEquals(25.0, data.opacity());
        assertEquals(Optional.of("#0A0B0C"), data.multiplyColor());
        assertEquals(Optional.of("#0D0E0F"), data.screenColor());
        assertEquals(2, data.warpDivH());
        assertEquals(3, data.warpDivV());
        // The level-2 bezier extension wins; the level-3 sibling is ignored.
        assertEquals(Optional.of(4), data.bezierDivH());
        assertEquals(Optional.of(5), data.bezierDivV());
        assertEquals(EditLabelColorType.UNDEFINED, data.labelColor().type());
        assertEquals(new Point2(10.0f, 20.0f), data.rectangle().topLeft());
        assertEquals(new Point2(10.0f, 60.0f), data.rectangle().bottomLeft());
        assertEquals(new Point2(50.0f, 20.0f), data.rectangle().topRight());
        assertEquals(new Point2(50.0f, 60.0f), data.rectangle().bottomRight());
        assertEquals(List.of("GetObject"), fixture.host.opsDispatchLabels());
    }

    @Test
    void getObjectReadsTheOfficialRotationDeformerBlock() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        final EditObjectSnapshot snapshot = session.partObjects()
                .object(new PartObjectOps.GetObject(
                        new ModelObjectReference(ModelObjectKind.ROTATION_DEFORMER, "rot1"), List.of()));

        assertEquals(new ModelObjectId("rot1"), snapshot.object());
        final EditRotationDeformerData data = (EditRotationDeformerData) snapshot.data();
        assertEquals(EditObjectKind.ROTATION_DEFORMER, data.kind());
        assertEquals("RotName", data.name());
        assertEquals(Optional.empty(), data.parentId());
        assertEquals(Optional.of(new DeformerId("warp1")), data.parentDeformerId());
        assertEquals(45.0, data.angle());
        assertEquals(10.0, data.baseAngle());
        assertEquals(150.0, data.scale());
        assertEquals(75.0, data.opacity());
        assertEquals(Optional.of("#222222"), data.multiplyColor());
        assertEquals(Optional.of("#333333"), data.screenColor());
        assertEquals(new Point2(3.0f, 4.0f), data.position());
    }

    @Test
    void getObjectReadsTheOfficialArtMeshBlock() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        final EditObjectSnapshot snapshot = session.partObjects()
                .object(new PartObjectOps.GetObject(
                        new ModelObjectReference(ModelObjectKind.ART_MESH, "mesh1"), List.of()));

        assertEquals(new ModelObjectId("mesh1"), snapshot.object());
        final EditArtMeshData data = (EditArtMeshData) snapshot.data();
        assertEquals(EditObjectKind.ART_MESH, data.kind());
        // ArtMesh Name is the local name; the other kinds read name-or-id.
        assertEquals("MeshName", data.name());
        assertEquals(Optional.of(new PartId("sub-part")), data.parentId());
        assertEquals(Optional.of(new DeformerId("warp1")), data.parentDeformerId());
        assertEquals(List.of(new ModelObjectId("other")), data.clippingIds());
        assertTrue(data.reverseMask());
        assertEquals(3, data.drawOrder());
        assertEquals(80.0, data.opacity());
        assertEquals(Optional.of("#102030"), data.multiplyColor());
        assertEquals(Optional.of("#405060"), data.screenColor());
        assertEquals(EditColorBlend.ADD, data.colorBlend());
        assertEquals(EditAlphaBlend.OUT, data.alphaBlend());
        assertTrue(data.culling());
        assertEquals(4, data.vertexCount());
        assertTrue(fixture.access.called("cubism.editor-model.parameter-controllable-source.local-name"));
        assertFalse(fixture.access.called("cubism.editor-model.parameter-controllable-source.name-or-id-string"));
    }

    @Test
    void getObjectReadsTheOfficialPartBlock() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        final EditObjectSnapshot snapshot = session.partObjects()
                .object(new PartObjectOps.GetObject(
                        new ModelObjectReference(ModelObjectKind.PART, "root-part"), List.of()));

        assertEquals(new ModelObjectId("root-part"), snapshot.object());
        final EditPartData data = (EditPartData) snapshot.data();
        assertEquals(EditObjectKind.PART, data.kind());
        assertEquals("RootPartName", data.name());
        // The root part has no parent: %Root normalizes to empty.
        assertEquals(Optional.empty(), data.parentId());
        assertTrue(data.grouped());
        assertTrue(data.guidImage());
        assertFalse(data.offscreen());
        assertEquals(List.of(new ModelObjectId("other")), data.clippingIds());
        assertTrue(data.reverseMask());
        assertEquals(7, data.drawOrder());
        assertEquals(50.0, data.opacity());
        assertEquals(Optional.of("#AABBCC"), data.multiplyColor());
        assertEquals(Optional.of("#112233"), data.screenColor());
        assertEquals(EditColorBlend.MULTIPLY, data.colorBlend());
        assertEquals(EditAlphaBlend.ATOP, data.alphaBlend());
    }

    @Test
    void getObjectNormalizesTheSyntheticRootParent() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        final EditObjectSnapshot snapshot = session.partObjects()
                .object(new PartObjectOps.GetObject(
                        new ModelObjectReference(ModelObjectKind.PART, "sub-part"), List.of()));

        // subPart's parent IS the root part — %Root serializes as empty.
        assertEquals(Optional.empty(), ((EditPartData) snapshot.data()).parentId());
    }

    @Test
    void getObjectReadsTheOfficialGlueBlock() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        // ModelObjectKind cannot name a glue — the declared kind is not consulted on
        // the glue route; the id resolves through the glue enumeration alone.
        final EditObjectSnapshot snapshot = session.partObjects()
                .object(new PartObjectOps.GetObject(
                        new ModelObjectReference(ModelObjectKind.ART_MESH, "glue1"), List.of()));

        assertEquals(new ModelObjectId("glue1"), snapshot.object());
        final EditGlueData data = (EditGlueData) snapshot.data();
        assertEquals(EditObjectKind.GLUE, data.kind());
        assertEquals("GlueName", data.name());
        assertEquals(Optional.of(new PartId("sub-part")), data.parentId());
        assertEquals(0.65, data.intensity(), 1.0e-6);
        assertEquals(EditLabelColorType.UNDEFINED, data.labelColor().type());
        assertTrue(fixture.access.called("cubism.editor-model.model-source.all-glues"));
        assertTrue(fixture.access.called("cubism.editor-model.model.get-object"));
        assertTrue(fixture.access.called("cubism.editor-model.glue.current-keyform"));
        assertEquals(List.of("GetObject"), fixture.host.opsDispatchLabels());
    }

    @Test
    void getObjectGlueFailsClosedWithoutTheGlueMembers() throws EditSessionException {
        final Fixture fixture = new Fixture();
        // A record missing the glue form read keeps the glue kind locked while the
        // shared read surface stays open.
        fixture.access.denyAlias("cubism.editor-model.glue-form.intensity");
        final EditSession session = fixture.open();

        assertThrows(
                EditUnavailableException.class,
                () -> session.partObjects()
                        .object(new PartObjectOps.GetObject(
                                new ModelObjectReference(ModelObjectKind.ART_MESH, "glue1"), List.of())));
        assertEquals(
                EditObjectKind.WARP_DEFORMER,
                session.partObjects()
                        .object(new PartObjectOps.GetObject(
                                new ModelObjectReference(ModelObjectKind.WARP_DEFORMER, "warp1"), List.of()))
                        .data()
                        .kind());
    }

    @Test
    void getObjectRejectsAnArtPathReference() throws EditSessionException {
        final Fixture fixture = new Fixture();
        fixture.access.objects.add(fixture.artPath);
        final EditSession session = fixture.open();

        assertThrows(
                IllegalArgumentException.class,
                () -> session.partObjects()
                        .object(new PartObjectOps.GetObject(
                                new ModelObjectReference(ModelObjectKind.ART_MESH, "path1"), List.of())));
    }

    @Test
    void getObjectFailsClosedWithoutTheVerifiedSurface() throws EditSessionException {
        final Fixture fixture = new Fixture();
        // Simulates the 5.2.03 record: the part/art-mesh extended readers are absent,
        // so only the warp/rotation payloads may leave the capability gate.
        fixture.access.denyAlias("cubism.editor-model.part-source.use-offscreen");
        fixture.access.denyAlias("cubism.editor-model.art-mesh-source.alpha-composition");
        final EditSession session = fixture.open();

        assertThrows(
                EditUnavailableException.class,
                () -> session.partObjects()
                        .object(new PartObjectOps.GetObject(
                                new ModelObjectReference(ModelObjectKind.PART, "root-part"), List.of())));
        assertThrows(
                EditUnavailableException.class,
                () -> session.partObjects()
                        .object(new PartObjectOps.GetObject(
                                new ModelObjectReference(ModelObjectKind.ART_MESH, "mesh1"), List.of())));
        // Warp, rotation, and glue stay open — their readers never touch the denied
        // members. The glue route needs no extended part/art-mesh reader.
        assertEquals(
                EditObjectKind.WARP_DEFORMER,
                session.partObjects()
                        .object(new PartObjectOps.GetObject(
                                new ModelObjectReference(ModelObjectKind.WARP_DEFORMER, "warp1"), List.of()))
                        .data()
                        .kind());
        assertEquals(
                EditObjectKind.ROTATION_DEFORMER,
                session.partObjects()
                        .object(new PartObjectOps.GetObject(
                                new ModelObjectReference(ModelObjectKind.ROTATION_DEFORMER, "rot1"), List.of()))
                        .data()
                        .kind());
        assertEquals(
                EditObjectKind.GLUE,
                session.partObjects()
                        .object(new PartObjectOps.GetObject(
                                new ModelObjectReference(ModelObjectKind.ART_MESH, "glue1"), List.of()))
                        .data()
                        .kind());
    }

    @Test
    void getObjectFailsClosedWhenTheCapabilityIsDenied() throws EditSessionException {
        final Fixture fixture = new Fixture();
        fixture.access.deny(
                dev.turboism.mapping.verification.selector.EditorEditPartObjectSelectorContract
                        .GET_OBJECT_CAPABILITY_ID);
        final EditSession session = fixture.open();

        assertThrows(
                EditUnavailableException.class,
                () -> session.partObjects()
                        .object(new PartObjectOps.GetObject(
                                new ModelObjectReference(ModelObjectKind.WARP_DEFORMER, "warp1"), List.of())));
        assertEquals(List.of(), fixture.access.memberCalls());
    }

    // ------------------------------------------------------------------
    // request builders
    // ------------------------------------------------------------------

    private static EditParameterKeyCondition condition() {
        return new EditParameterKeyCondition(Optional.of(new ParameterId("AngleZ")), Optional.of(0.5));
    }

    private static PartObjectOps.EditPart editPart(
            final Optional<PartId> newId, final Optional<String> name, final Optional<EditLabelColor> labelColor) {
        return new PartObjectOps.EditPart(
                new PartId("root-part"),
                List.of(),
                false,
                newId,
                name,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                labelColor);
    }

    private static PartObjectOps.EditPart editPartConditioned() {
        return new PartObjectOps.EditPart(
                new PartId("root-part"),
                List.of(condition()),
                false,
                Optional.empty(),
                Optional.of("x"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static PartObjectOps.EditArtMesh editArtMesh(
            final Optional<String> name,
            final Optional<EditLabelColor> labelColor,
            final Optional<PartId> parentId,
            final Optional<dev.turboism.sdk.cubism.edit.EditColorBlend> colorBlend,
            final Optional<dev.turboism.sdk.cubism.edit.EditAlphaBlend> alphaBlend,
            final Optional<Boolean> culling) {
        return new PartObjectOps.EditArtMesh(
                new ArtMeshId("mesh1"),
                List.of(),
                false,
                Optional.empty(),
                name,
                parentId,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                colorBlend,
                alphaBlend,
                culling,
                labelColor);
    }

    private static PartObjectOps.EditArtMesh editArtMeshConditioned() {
        return new PartObjectOps.EditArtMesh(
                new ArtMeshId("mesh1"),
                List.of(condition()),
                false,
                Optional.empty(),
                Optional.of("x"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static PartObjectOps.EditGlue editGlue(
            final Optional<GlueId> newId,
            final Optional<String> name,
            final Optional<PartId> parentId,
            final Optional<Double> intensity) {
        return new PartObjectOps.EditGlue(
                new GlueId("glue1"), List.of(), false, newId, name, parentId, intensity, Optional.empty());
    }

    private static PartObjectOps.EditGlue editGlueConditioned() {
        return new PartObjectOps.EditGlue(
                new GlueId("glue1"),
                List.of(condition()),
                false,
                Optional.empty(),
                Optional.of("x"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static DeformerOps.AddWarpDeformer addWarp(
            final Optional<Integer> bezierDivH,
            final Optional<Integer> bezierDivV,
            final Optional<Boolean> considerChildKeyforms,
            final Optional<Boolean> snapCenter) {
        return new DeformerOps.AddWarpDeformer(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                dev.turboism.sdk.cubism.edit.EditDeformerAttachMode.AS_PARENT,
                Optional.empty(),
                Optional.empty(),
                bezierDivH,
                bezierDivV,
                considerChildKeyforms,
                snapCenter);
    }

    private static DeformerOps.EditWarpDeformer editWarp(
            final List<EditParameterKeyCondition> conditions,
            final Optional<Integer> bezierDivH,
            final Optional<Integer> bezierDivV) {
        return new DeformerOps.EditWarpDeformer(
                new DeformerId("warp1"),
                conditions,
                false,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                bezierDivH,
                bezierDivV,
                Optional.empty());
    }

    private static DeformerOps.EditRotationDeformer editRotation(
            final List<EditParameterKeyCondition> conditions,
            final Optional<String> name,
            final Optional<EditLabelColor> labelColor) {
        return new DeformerOps.EditRotationDeformer(
                new DeformerId("rot1"),
                conditions,
                false,
                Optional.empty(),
                name,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                labelColor);
    }

    private static void assertRefreshRan(final FakeOpsAccess access) {
        assertTrue(access.called("cubism.editor-model.model-source.update-instances"));
        assertTrue(access.called("cubism.editor-model.modeling-document.mark-dirty"));
        // Palette/canvas refreshes post deferred UI callbacks that can displace the
        // session's undo group — they run once at session end, never mid-operation.
        assertFalse(access.called("cubism.editor-model.complete-pack.repaint-canvas"));
        assertFalse(access.called("cubism.editor-model.complete-pack.update-parameter"));
        assertFalse(access.called("cubism.editor-model.complete-pack.update-part-palette"));
        assertFalse(access.called("cubism.editor-model.complete-pack.update-deformer-palette"));
    }

    // ------------------------------------------------------------------
    // fixture
    // ------------------------------------------------------------------

}
