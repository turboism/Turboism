package dev.turboism.adapter.cubism.edit;

import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.sdk.cubism.edit.CancelSource;
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
import dev.turboism.sdk.cubism.edit.EditSessionOptions;
import dev.turboism.sdk.cubism.edit.EditSessionState;
import dev.turboism.sdk.cubism.edit.EditUnavailableException;
import dev.turboism.sdk.cubism.edit.EditWarpDeformerData;
import dev.turboism.sdk.cubism.edit.ParameterKeyOps;
import dev.turboism.sdk.cubism.edit.ParameterStructureOps;
import dev.turboism.sdk.cubism.edit.PartObjectOps;
import dev.turboism.sdk.cubism.edit.SelectionOps;
import dev.turboism.sdk.cubism.history.HistoryEntry;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.DocumentId;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.GlueId;
import dev.turboism.sdk.cubism.model.ModelObjectKind;
import dev.turboism.sdk.cubism.model.ModelObjectReference;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.model.Point2;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fake-host contract tests for the T3 routed operation families. The scripted {@link
 * FakeOpsAccess} stands in for the verified member surface: every operation must pass its
 * capability gate before a member runs, writes must register undo on the session edit token
 * and finish with the refresh envelope, and unverifiable fields/rows must fail closed before
 * any host member is reached.
 */
final class SessionOpsContractTest {

    private static final DocumentId DOCUMENT = new DocumentId("document-1");

    // ------------------------------------------------------------------
    // parameter-key family
    // ------------------------------------------------------------------

    @Test
    void addParameterKeyCapturesUndoMutatesAndRefreshes() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        assertTrue(session.parameterKeys().addParameterKey(
            new ParameterKeyOps.AddParameterKey(
                new ModelObjectReference(ModelObjectKind.ART_MESH, "mesh1"),
                new ParameterId("AngleZ"),
                0.5)));

        final List<FakeOpsAccess.Call> addKey =
            fixture.access.callsOf("cubism.editor-model.keyform-grid.add-key");
        assertEquals(1, addKey.size());
        assertEquals(0.5f, addKey.get(0).arguments().get(0));
        assertEquals(fixture.paramSource.guid, addKey.get(0).arguments().get(1));
        assertEquals(
            List.of("AddParameterKey"),
            fixture.host.opsDispatchLabels());
        assertTrue(fixture.access.called("cubism.editor-model.undo.add"));
        assertRefreshRan(fixture.access);
    }

    @Test
    void moveParameterKeyRejectsForceOverwriteBeforeDispatch() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        assertThrows(
            EditUnavailableException.class,
            () -> session.parameterKeys().moveParameterKey(
                new ParameterKeyOps.MoveParameterKey(
                    Optional.empty(),
                    Optional.of(new ParameterId("AngleZ")),
                    0.0,
                    0.5,
                    false,
                    true)));
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
        final EditParameterNode leaf =
            (EditParameterNode) root.children().get(1);
        assertEquals(new ParameterId("AngleZ"), leaf.id());
        assertEquals("Angle Z", leaf.name());
        assertEquals(List.of("GetParameterStructure"), fixture.host.opsDispatchLabels());
    }

    @Test
    void addParameterConstructsAndAttachesUnderTheRootGroup() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        assertTrue(session.parameterStructure().addParameter(
            new ParameterStructureOps.AddParameter(
                Optional.of("Mouth"), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), false)));

        assertTrue(fixture.access.constructed("cubism.editor-model.parameter-source.create"));
        assertEquals(
            1,
            fixture.access.callsOf(
                "cubism.editor-model.parameter-group-handler.add-parameter-child").size());
        assertTrue(fixture.access.called("cubism.editor-model.undo.add"));
        assertRefreshRan(fixture.access);
    }

    @Test
    void editParameterSnapshotsThenAppliesDirectSettersInsideTheSessionBracket()
            throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        assertTrue(session.parameterStructure().editParameter(
            new ParameterStructureOps.EditParameter(
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
        assertTrue(
            fixture.access.indexOf("cubism.editor-model.simple-undo.create")
                < fixture.access.indexOf("cubism.editor-model.parameter-source.set-name"));
        assertTrue(fixture.access.called("cubism.editor-model.parameter-source.set-minimum"));
        assertTrue(fixture.access.called("cubism.editor-model.parameter-source.set-maximum"));
        assertTrue(fixture.access.called("cubism.editor-model.parameter-source.set-default"));
        assertTrue(fixture.access.called("cubism.editor-model.parameter-source.set-repeat"));
        assertFalse(fixture.access.called(
            "cubism.editor-model.parameter-property-editor.update-definition"));
        assertTrue(fixture.access.called("cubism.editor-model.undo.add"));
        assertRefreshRan(fixture.access);
    }

    @Test
    void editParameterNewIdValidatesThenRenamesThroughTheVerifiedSetter()
            throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        assertTrue(session.parameterStructure().editParameter(
            new ParameterStructureOps.EditParameter(
                new ParameterId("AngleZ"),
                Optional.of(new ParameterId("AngleZ2")),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty())));

        assertTrue(fixture.access.called("cubism.editor-model.parameter-validator.valid-id"));
        assertTrue(
            fixture.access.indexOf("cubism.editor-model.parameter-validator.valid-id")
                < fixture.access.indexOf("cubism.editor-model.parameter-source.set-id"));
        assertTrue(fixture.access.called("cubism.editor-model.parameter-source.set-id"));
        assertTrue(fixture.access.constructed("cubism.editor-model.parameter-id.create"));
    }

    @Test
    void editParameterWithNoFieldsSkipsTheUndoCapture() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        assertTrue(session.parameterStructure().editParameter(
            new ParameterStructureOps.EditParameter(
                new ParameterId("AngleZ"),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty())));

        assertFalse(fixture.access.constructed("cubism.editor-model.simple-undo.create"));
        assertFalse(fixture.access.called("cubism.editor-model.undo.add"));
    }

    @Test
    void moveParameterGroupFailsClosedWithoutDispatch() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        assertThrows(
            EditUnavailableException.class,
            () -> session.parameterStructure().moveParameterGroup(
                new ParameterStructureOps.MoveParameterGroup(
                    new ParameterGroupId("g1"), 0)));
        assertFalse(fixture.host.opsDispatchLabels().contains("MoveParameterGroup"));
    }

    // ------------------------------------------------------------------
    // selection family
    // ------------------------------------------------------------------

    @Test
    void selectedObjectsTranslatesGuidsToIdsAndSkipsStaleEntries() throws EditSessionException {
        final Fixture fixture = new Fixture();
        fixture.access.selectionGuids = List.of(
            fixture.mesh.guid, new HostGuid("stale"));
        final EditSession session = fixture.open();

        assertEquals(
            List.of(new ModelObjectId("mesh1")),
            session.selection().selectedObjects());
        assertEquals(List.of("GetSelectedObjects"), fixture.host.opsDispatchLabels());
    }

    @Test
    void addSelectedObjectsWritesTheUnionInHostOrder() throws EditSessionException {
        final Fixture fixture = new Fixture();
        fixture.access.selectionGuids = List.of(fixture.other.guid);
        final EditSession session = fixture.open();

        assertTrue(session.selection().addSelectedObjects(
            new SelectionOps.AddSelectedObjects(
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

        assertTrue(session.partObjects().deleteObject(
            new PartObjectOps.DeleteObject(
                new ModelObjectReference(ModelObjectKind.ART_MESH, "mesh1"))));

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
            () -> session.partObjects().deleteObject(
                new PartObjectOps.DeleteObject(
                    new ModelObjectReference(ModelObjectKind.ART_MESH, "path1"))));
        assertFalse(
            fixture.access.called("cubism.editor-model.model-handler.remove-objects"));
    }

    @Test
    void editPartRejectsEveryUnverifiableField() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        // Each BLOCKED field is rejected on its own; the operation never dispatches.
        // Field order: newId, name, parentId, grouped, guidImage, offscreen, clippingIds,
        // reverseMask, drawOrder, opacity, multiplyColor, screenColor, colorBlend.
        assertEditPartRejected(session, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.of(Boolean.TRUE), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        assertEditPartRejected(session, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(Boolean.TRUE),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty());
        assertEditPartRejected(session, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.of(List.of(new ModelObjectId("clip"))), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty());
        assertEditPartRejected(session, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.of(Boolean.TRUE), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        assertEditPartRejected(session, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.of("#FF0000"), Optional.empty(), Optional.empty());
        assertEditPartRejected(session, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.of("#00FF00"), Optional.empty());
        assertEditPartRejected(session, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(),
            Optional.of(dev.turboism.sdk.cubism.edit.EditColorBlend.ADD));
        assertEditPartRejected(session, Optional.empty(), Optional.empty(),
            Optional.of(new PartId("parent")), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        assertFalse(fixture.host.opsDispatchLabels().contains("EditPart"));
    }

    @Test
    void editPartNameRunsTheWriteEnvelope() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        assertTrue(session.partObjects().editPart(editPart(
            Optional.empty(), Optional.of("Renamed"), Optional.empty())));

        final List<FakeOpsAccess.Call> renames =
            fixture.access.callsOf("cubism.editor-model.part-source.set-local-name");
        assertEquals(1, renames.size());
        assertEquals("Renamed", renames.get(0).arguments().get(0));
        assertTrue(fixture.access.called("cubism.editor-model.undo.add"));
        assertRefreshRan(fixture.access);
    }

    @Test
    void editArtMeshRejectsColorBlendAndParentId() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        assertThrows(
            EditUnavailableException.class,
            () -> session.partObjects().editArtMesh(editArtMesh(
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(dev.turboism.sdk.cubism.edit.EditColorBlend.NORMAL),
                Optional.empty(), Optional.empty())));
        assertThrows(
            EditUnavailableException.class,
            () -> session.partObjects().editArtMesh(editArtMesh(
                Optional.empty(), Optional.empty(), Optional.of(new PartId("p")),
                Optional.empty(), Optional.empty(), Optional.empty())));
        assertFalse(fixture.host.opsDispatchLabels().contains("EditArtMesh"));
    }

    @Test
    void editGlueRejectsNewIdIntensityAndParentId() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        assertThrows(
            EditUnavailableException.class,
            () -> session.partObjects().editGlue(editGlue(
                Optional.of(new GlueId("g2")), Optional.empty(), Optional.empty(),
                Optional.empty())));
        assertThrows(
            EditUnavailableException.class,
            () -> session.partObjects().editGlue(editGlue(
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(0.5))));
        assertThrows(
            EditUnavailableException.class,
            () -> session.partObjects().editGlue(editGlue(
                Optional.empty(), Optional.empty(), Optional.of(new PartId("p")),
                Optional.empty())));
        assertFalse(fixture.host.opsDispatchLabels().contains("EditGlue"));
    }

    @Test
    void moveObjectReparentingFailsClosed() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        assertThrows(
            EditUnavailableException.class,
            () -> session.partObjects().moveObjectOnPartsPalette(
                new PartObjectOps.MoveObjectOnPartsPalette(
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
            () -> session.partObjects().addPart(
                new PartObjectOps.AddPart(
                    Optional.empty(), Optional.empty(), Optional.empty(),
                    List.of(new ModelObjectId("mesh1")), false)));
        assertThrows(
            EditUnavailableException.class,
            () -> session.partObjects().addPart(
                new PartObjectOps.AddPart(
                    Optional.empty(), Optional.empty(), Optional.empty(),
                    List.of(), true)));
        assertFalse(fixture.host.opsDispatchLabels().contains("AddPart"));
    }

    @Test
    void addPartSeedsAnInitialKeyformAndRegistersUndoOnTheSessionToken()
            throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        assertTrue(session.partObjects().addPart(
            new PartObjectOps.AddPart(
                Optional.of("Probe Part"), Optional.empty(), Optional.empty(),
                List.of(), false)));

        // The official AddPart sequence seeds a CPartForm bound through a fresh CFormGuid
        // and a KeyformGridSource primed by setInitialKeyform — the first instance update
        // must find the initial keyform instead of dereferencing an empty binding table.
        assertTrue(fixture.access.constructed("cubism.editor-model.part-form.create"));
        assertTrue(fixture.access.constructed("cubism.editor-model.form-guid.create"));
        assertTrue(fixture.access.called("cubism.editor-model.form.set-guid"));
        assertTrue(fixture.access.called("cubism.editor-model.part-source.keyforms"));
        assertTrue(fixture.access.called("cubism.editor-model.c-array-list.add"));
        assertTrue(fixture.access.constructed(
            "cubism.editor-model.keyform-grid-source.create"));
        assertTrue(fixture.access.called(
            "cubism.editor-model.keyform-grid-source.set-initial-keyform"));
        assertTrue(fixture.access.called(
            "cubism.editor-model.parameter-controllable-source.set-keyform-grid-source"));
        assertTrue(fixture.access.called("cubism.editor-model.undo.add"));
        assertRefreshRan(fixture.access);
    }

    // ------------------------------------------------------------------
    // session bracket invariants (T7)
    // ------------------------------------------------------------------

    @Test
    void sessionOperationsNeverCommitHistoryWhileTheSessionIsOpen()
            throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();
        final HistorySnapshot before = fixture.host.snapshot();

        session.parameterStructure().editParameter(new ParameterStructureOps.EditParameter(
            new ParameterId("AngleZ"), Optional.empty(), Optional.of("Renamed"),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
        session.parameterKeys().addParameterKey(new ParameterKeyOps.AddParameterKey(
            new ModelObjectReference(ModelObjectKind.ART_MESH, "mesh1"),
            new ParameterId("AngleZ"), 0.5));
        session.partObjects().addPart(new PartObjectOps.AddPart(
            Optional.of("Probe Part"), Optional.empty(), Optional.empty(),
            List.of(), false));

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
        session.partObjects().addPart(new PartObjectOps.AddPart(
            Optional.of("Probe Part"), Optional.empty(), Optional.empty(),
            List.of(), false));

        final EditSessionCloseResult result = session.close();

        assertEquals(EditSessionCloseOutcome.COMMITTED, result.outcome());
        assertEquals(EditSessionState.CLOSED, session.state());
        assertEquals(1, fixture.host.entries.size());
        assertEquals(1, fixture.host.position);
    }

    @Test
    void cancelRestoresHistoryWhenTheSessionGroupStaysCurrent()
            throws EditSessionException {
        final Fixture fixture = new Fixture();
        fixture.host.revertVerified = true;
        final EditSession session = fixture.open();
        final HistorySnapshot before = fixture.host.snapshot();
        session.partObjects().addPart(new PartObjectOps.AddPart(
            Optional.of("Probe Part"), Optional.empty(), Optional.empty(),
            List.of(), false));

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
        session.partObjects().addPart(new PartObjectOps.AddPart(
            Optional.of("Probe Part"), Optional.empty(), Optional.empty(),
            List.of(), false));
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
    void cancelWithoutVerifiedRevertMovesTheCursorWithUndoRedoTo()
            throws EditSessionException {
        final Fixture fixture = new Fixture();
        // revert unverified → the compensating strategy reconciles the committed tail at
        // the cursor (undoRedoTo); entries cannot be dropped, so the exact-snapshot check
        // reports the residual entry as a typed recovery failure.
        final EditSession session = fixture.open();
        session.partObjects().addPart(new PartObjectOps.AddPart(
            Optional.of("Probe Part"), Optional.empty(), Optional.empty(),
            List.of(), false));
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
        session.partObjects().addPart(new PartObjectOps.AddPart(
            Optional.of("Probe Part"), Optional.empty(), Optional.empty(),
            List.of(), false));
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

    @Test
    void addWarpDeformerRejectsEachUnverifiableLatticeField() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        assertThrows(
            EditUnavailableException.class,
            () -> session.deformers().addWarpDeformer(addWarp(
                Optional.of(3), Optional.empty(), Optional.empty(), Optional.empty())));
        assertThrows(
            EditUnavailableException.class,
            () -> session.deformers().addWarpDeformer(addWarp(
                Optional.empty(), Optional.of(3), Optional.empty(), Optional.empty())));
        assertThrows(
            EditUnavailableException.class,
            () -> session.deformers().addWarpDeformer(addWarp(
                Optional.empty(), Optional.empty(), Optional.of(Boolean.TRUE),
                Optional.empty())));
        assertThrows(
            EditUnavailableException.class,
            () -> session.deformers().addWarpDeformer(addWarp(
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(Boolean.TRUE))));
        assertFalse(fixture.host.opsDispatchLabels().contains("AddWarpDeformer"));
    }

    @Test
    void editWarpDeformerRejectsBezierDivAndConditions() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        assertThrows(
            EditUnavailableException.class,
            () -> session.deformers().editWarpDeformer(editWarp(
                List.of(), Optional.of(4), Optional.empty())));
        assertThrows(
            EditUnavailableException.class,
            () -> session.deformers().editWarpDeformer(editWarp(
                List.of(), Optional.empty(), Optional.of(4))));
        assertThrows(
            EditUnavailableException.class,
            () -> session.deformers().editWarpDeformer(editWarp(
                List.of(condition()), Optional.empty(), Optional.empty())));
        assertFalse(fixture.host.opsDispatchLabels().contains("EditWarpDeformer"));
    }

    @Test
    void editRotationDeformerNameRunsTheWriteEnvelope() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        assertTrue(session.deformers().editRotationDeformer(editRotation(
            List.of(), Optional.of("Renamed"), Optional.empty())));

        final List<FakeOpsAccess.Call> renames = fixture.access.callsOf(
            "cubism.editor-model.parameter-controllable-source.set-local-name");
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

        assertThrows(EditUnavailableException.class,
            () -> session.parameterKeys().parameterKeys(
                new ParameterKeyOps.GetParameterKeys(
                    new ModelObjectReference(ModelObjectKind.ART_MESH, "mesh1"))));
        assertThrows(EditUnavailableException.class,
            () -> session.parameterStructure().parameterStructure());
        assertThrows(EditUnavailableException.class,
            () -> session.selection().selectedObjects());
        assertThrows(EditUnavailableException.class,
            () -> session.partObjects().partStructure());
        assertThrows(EditUnavailableException.class,
            () -> session.deformers().deformerStructure());
        // The capability gate is the only surface touched: no member ever ran.
        assertEquals(List.of(), fixture.access.memberCalls());
    }

    @Test
    void conditionedRequestsFailClosedBeforeDispatch() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        assertThrows(EditUnavailableException.class,
            () -> session.partObjects().object(
                new PartObjectOps.GetObject(
                    new ModelObjectReference(ModelObjectKind.ART_MESH, "mesh1"),
                    List.of(condition()))));
        assertThrows(EditUnavailableException.class,
            () -> session.partObjects().editPart(editPartConditioned()));
        assertThrows(EditUnavailableException.class,
            () -> session.partObjects().editArtMesh(editArtMeshConditioned()));
        assertThrows(EditUnavailableException.class,
            () -> session.partObjects().editGlue(editGlueConditioned()));
        assertThrows(EditUnavailableException.class,
            () -> session.deformers().editRotationDeformer(editRotation(
                List.of(condition()), Optional.empty(), Optional.empty())));
        assertTrue(fixture.host.opsDispatchLabels().isEmpty());
    }

    // ------------------------------------------------------------------
    // GetObject per-kind payload reads (official 1.1.0 data blocks)
    // ------------------------------------------------------------------

    @Test
    void getObjectReadsTheOfficialWarpDeformerBlock() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        final EditObjectSnapshot snapshot = session.partObjects().object(
            new PartObjectOps.GetObject(
                new ModelObjectReference(ModelObjectKind.WARP_DEFORMER, "warp1"),
                List.of()));

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

        final EditObjectSnapshot snapshot = session.partObjects().object(
            new PartObjectOps.GetObject(
                new ModelObjectReference(ModelObjectKind.ROTATION_DEFORMER, "rot1"),
                List.of()));

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

        final EditObjectSnapshot snapshot = session.partObjects().object(
            new PartObjectOps.GetObject(
                new ModelObjectReference(ModelObjectKind.ART_MESH, "mesh1"),
                List.of()));

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
        assertTrue(fixture.access.called(
            "cubism.editor-model.parameter-controllable-source.local-name"));
        assertFalse(fixture.access.called(
            "cubism.editor-model.parameter-controllable-source.name-or-id-string"));
    }

    @Test
    void getObjectReadsTheOfficialPartBlock() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        final EditObjectSnapshot snapshot = session.partObjects().object(
            new PartObjectOps.GetObject(
                new ModelObjectReference(ModelObjectKind.PART, "root-part"),
                List.of()));

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

        final EditObjectSnapshot snapshot = session.partObjects().object(
            new PartObjectOps.GetObject(
                new ModelObjectReference(ModelObjectKind.PART, "sub-part"),
                List.of()));

        // subPart's parent IS the root part — %Root serializes as empty.
        assertEquals(Optional.empty(), ((EditPartData) snapshot.data()).parentId());
    }

    @Test
    void getObjectReadsTheOfficialGlueBlock() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        // ModelObjectKind cannot name a glue — the declared kind is not consulted on
        // the glue route; the id resolves through the glue enumeration alone.
        final EditObjectSnapshot snapshot = session.partObjects().object(
            new PartObjectOps.GetObject(
                new ModelObjectReference(ModelObjectKind.ART_MESH, "glue1"),
                List.of()));

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

        assertThrows(EditUnavailableException.class,
            () -> session.partObjects().object(
                new PartObjectOps.GetObject(
                    new ModelObjectReference(ModelObjectKind.ART_MESH, "glue1"),
                    List.of())));
        assertEquals(EditObjectKind.WARP_DEFORMER,
            session.partObjects().object(
                new PartObjectOps.GetObject(
                    new ModelObjectReference(ModelObjectKind.WARP_DEFORMER, "warp1"),
                    List.of())).data().kind());
    }

    @Test
    void getObjectRejectsAnArtPathReference() throws EditSessionException {
        final Fixture fixture = new Fixture();
        fixture.access.objects.add(fixture.artPath);
        final EditSession session = fixture.open();

        assertThrows(
            IllegalArgumentException.class,
            () -> session.partObjects().object(
                new PartObjectOps.GetObject(
                    new ModelObjectReference(ModelObjectKind.ART_MESH, "path1"),
                    List.of())));
    }

    @Test
    void getObjectFailsClosedWithoutTheVerifiedSurface() throws EditSessionException {
        final Fixture fixture = new Fixture();
        // Simulates the 5.2.03 record: the part/art-mesh extended readers are absent,
        // so only the warp/rotation payloads may leave the capability gate.
        fixture.access.denyAlias("cubism.editor-model.part-source.use-offscreen");
        fixture.access.denyAlias("cubism.editor-model.art-mesh-source.alpha-composition");
        final EditSession session = fixture.open();

        assertThrows(EditUnavailableException.class,
            () -> session.partObjects().object(
                new PartObjectOps.GetObject(
                    new ModelObjectReference(ModelObjectKind.PART, "root-part"),
                    List.of())));
        assertThrows(EditUnavailableException.class,
            () -> session.partObjects().object(
                new PartObjectOps.GetObject(
                    new ModelObjectReference(ModelObjectKind.ART_MESH, "mesh1"),
                    List.of())));
        // Warp, rotation, and glue stay open — their readers never touch the denied
        // members. The glue route needs no extended part/art-mesh reader.
        assertEquals(EditObjectKind.WARP_DEFORMER,
            session.partObjects().object(
                new PartObjectOps.GetObject(
                    new ModelObjectReference(ModelObjectKind.WARP_DEFORMER, "warp1"),
                    List.of())).data().kind());
        assertEquals(EditObjectKind.ROTATION_DEFORMER,
            session.partObjects().object(
                new PartObjectOps.GetObject(
                    new ModelObjectReference(ModelObjectKind.ROTATION_DEFORMER, "rot1"),
                    List.of())).data().kind());
        assertEquals(EditObjectKind.GLUE,
            session.partObjects().object(
                new PartObjectOps.GetObject(
                    new ModelObjectReference(ModelObjectKind.ART_MESH, "glue1"),
                    List.of())).data().kind());
    }

    @Test
    void getObjectFailsClosedWhenTheCapabilityIsDenied() throws EditSessionException {
        final Fixture fixture = new Fixture();
        fixture.access.deny(
            dev.turboism.mapping.verification.selector
                .EditorEditPartObjectSelectorContract.GET_OBJECT_CAPABILITY_ID);
        final EditSession session = fixture.open();

        assertThrows(EditUnavailableException.class,
            () -> session.partObjects().object(
                new PartObjectOps.GetObject(
                    new ModelObjectReference(ModelObjectKind.WARP_DEFORMER, "warp1"),
                    List.of())));
        assertEquals(List.of(), fixture.access.memberCalls());
    }

    // ------------------------------------------------------------------
    // request builders
    // ------------------------------------------------------------------

    private static EditParameterKeyCondition condition() {
        return new EditParameterKeyCondition(
            Optional.of(new ParameterId("AngleZ")), Optional.of(0.5));
    }

    private static void assertEditPartRejected(
        final EditSession session,
        final Optional<PartId> newId,
        final Optional<String> name,
        final Optional<PartId> parentId,
        final Optional<Boolean> grouped,
        final Optional<Boolean> guidImage,
        final Optional<Boolean> offscreen,
        final Optional<List<ModelObjectId>> clippingIds,
        final Optional<Boolean> reverseMask,
        final Optional<Integer> drawOrder,
        final Optional<Double> opacity,
        final Optional<String> multiplyColor,
        final Optional<String> screenColor,
        final Optional<dev.turboism.sdk.cubism.edit.EditColorBlend> colorBlend
    ) {
        assertThrows(
            EditUnavailableException.class,
            () -> session.partObjects().editPart(new PartObjectOps.EditPart(
                new PartId("root-part"), List.of(), false, newId, name, parentId,
                grouped, guidImage, offscreen, clippingIds, reverseMask, drawOrder,
                opacity, multiplyColor, screenColor, colorBlend, Optional.empty(),
                Optional.empty())));
    }

    private static PartObjectOps.EditPart editPart(
        final Optional<PartId> newId,
        final Optional<String> name,
        final Optional<EditLabelColor> labelColor
    ) {
        return new PartObjectOps.EditPart(
            new PartId("root-part"), List.of(), false, newId, name, Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), labelColor);
    }

    private static PartObjectOps.EditPart editPartConditioned() {
        return new PartObjectOps.EditPart(
            new PartId("root-part"), List.of(condition()), false, Optional.empty(),
            Optional.of("x"), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty());
    }

    private static PartObjectOps.EditArtMesh editArtMesh(
        final Optional<String> name,
        final Optional<EditLabelColor> labelColor,
        final Optional<PartId> parentId,
        final Optional<dev.turboism.sdk.cubism.edit.EditColorBlend> colorBlend,
        final Optional<dev.turboism.sdk.cubism.edit.EditAlphaBlend> alphaBlend,
        final Optional<Boolean> culling
    ) {
        return new PartObjectOps.EditArtMesh(
            new ArtMeshId("mesh1"), List.of(), false, Optional.empty(), name, parentId,
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), colorBlend,
            alphaBlend, culling, labelColor);
    }

    private static PartObjectOps.EditArtMesh editArtMeshConditioned() {
        return new PartObjectOps.EditArtMesh(
            new ArtMeshId("mesh1"), List.of(condition()), false, Optional.empty(),
            Optional.of("x"), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty());
    }

    private static PartObjectOps.EditGlue editGlue(
        final Optional<GlueId> newId,
        final Optional<String> name,
        final Optional<PartId> parentId,
        final Optional<Double> intensity
    ) {
        return new PartObjectOps.EditGlue(
            new GlueId("glue1"), List.of(), false, newId, name, parentId, intensity,
            Optional.empty());
    }

    private static PartObjectOps.EditGlue editGlueConditioned() {
        return new PartObjectOps.EditGlue(
            new GlueId("glue1"), List.of(condition()), false, Optional.empty(),
            Optional.of("x"), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static DeformerOps.AddWarpDeformer addWarp(
        final Optional<Integer> bezierDivH,
        final Optional<Integer> bezierDivV,
        final Optional<Boolean> considerChildKeyforms,
        final Optional<Boolean> snapCenter
    ) {
        return new DeformerOps.AddWarpDeformer(
            Optional.empty(), Optional.empty(), Optional.empty(), List.of(),
            dev.turboism.sdk.cubism.edit.EditDeformerAttachMode.AS_PARENT,
            Optional.empty(), Optional.empty(), bezierDivH, bezierDivV,
            considerChildKeyforms, snapCenter);
    }

    private static DeformerOps.EditWarpDeformer editWarp(
        final List<EditParameterKeyCondition> conditions,
        final Optional<Integer> bezierDivH,
        final Optional<Integer> bezierDivV
    ) {
        return new DeformerOps.EditWarpDeformer(
            new DeformerId("warp1"), conditions, false, Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            bezierDivH, bezierDivV, Optional.empty());
    }

    private static DeformerOps.EditRotationDeformer editRotation(
        final List<EditParameterKeyCondition> conditions,
        final Optional<String> name,
        final Optional<EditLabelColor> labelColor
    ) {
        return new DeformerOps.EditRotationDeformer(
            new DeformerId("rot1"), conditions, false, Optional.empty(), name,
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            labelColor);
    }

    private static void assertRefreshRan(
        final FakeOpsAccess access
    ) {
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

    private static final class Fixture {
        final AtomicBoolean editScopeGate = new AtomicBoolean();
        final FakeOpsAccess access = new FakeOpsAccess();
        final FakeOpsHost host = new FakeOpsHost(access);
        final MeshSource mesh;
        final MeshSource other;
        final HostSource artPath;
        final PartSource rootPart;
        final PartSource subPart;
        final GlueSource glue;
        final ParamSource paramSource;
        final RuntimeEditSessionManager manager = new RuntimeEditSessionManager(
            host,
            editScopeGate,
            context -> new EditSessionUiLock() {
                @Override public void engage(final boolean silent) {}
                @Override public void log(final String message) {}
                @Override public void progress(final double value) {}
                @Override public void disengage() {}
            },
            EditSessionRecoveries.PREFER_REVERT_WHEN_VERIFIED);

        Fixture() {
            mesh = new MeshSource("mesh1", "g-mesh");
            other = new MeshSource("other", "g-other");
            artPath = new HostSource("path1", "g-path");
            rootPart = new PartSource("root-part", "g-root");
            rootPart.children.add(mesh);
            subPart = new PartSource("sub-part", "g-sub");
            subPart.parent = rootPart;
            glue = new GlueSource("glue1", "g-glue");
            paramSource = new ParamSource("AngleZ", "pg1", "Angle Z", 0.0, 0.0, 30.0);
            final Group childGroup = new Group("group-a", "Group A");
            final Group rootGroup = new Group("root-group", "Root");
            rootGroup.children.add(childGroup);
            rootGroup.children.add(paramSource);
            final WarpSource warp = new WarpSource("warp1", "g-warp");
            final RotSource rot = new RotSource("rot1", "g-rot");
            rot.targetDeformer = warp;

            rootPart.name = "RootPartName";
            rootPart.enableDrawOrderGroup = true;
            rootPart.sketch = true;
            rootPart.clipGuids = List.of(other.guid);
            rootPart.reverseMask = true;
            rootPart.colorBlend = COLOR_BLEND_VALUES[EditColorBlend.MULTIPLY.ordinal()];
            rootPart.alphaBlend = ALPHA_BLEND_VALUES[EditAlphaBlend.ATOP.ordinal()];
            final PartForm rootForm = new PartForm();
            rootForm.drawOrder = 7;
            rootForm.opacity = 0.5;
            rootForm.multiply = new FloatColor("#AABBCC");
            rootForm.screen = new FloatColor("#112233");
            rootPart.form = rootForm;
            subPart.name = "SubPartName";
            subPart.colorBlend = COLOR_BLEND_VALUES[EditColorBlend.NORMAL.ordinal()];
            subPart.alphaBlend = ALPHA_BLEND_VALUES[EditAlphaBlend.OVER.ordinal()];
            subPart.form = new PartForm();

            mesh.name = "MeshName";
            mesh.parent = subPart;
            mesh.targetDeformerId = warp.id;
            mesh.clipGuids = List.of(other.guid);
            mesh.reverseMask = true;
            mesh.culling = true;
            mesh.positions = new float[] {0, 0, 1, 0, 0, 1, 1, 1};
            mesh.colorBlend = COLOR_BLEND_VALUES[EditColorBlend.ADD.ordinal()];
            mesh.alphaBlend = ALPHA_BLEND_VALUES[EditAlphaBlend.OUT.ordinal()];
            final DrawableForm meshForm = new DrawableForm();
            meshForm.drawOrder = 3;
            meshForm.opacity = 0.8;
            meshForm.multiply = new FloatColor("#102030");
            meshForm.screen = new FloatColor("#405060");
            mesh.form = meshForm;
            other.name = "OtherName";
            other.colorBlend = COLOR_BLEND_VALUES[EditColorBlend.NORMAL.ordinal()];
            other.alphaBlend = ALPHA_BLEND_VALUES[EditAlphaBlend.OVER.ordinal()];
            other.form = new DrawableForm();

            warp.name = "WarpName";
            warp.parent = subPart;
            warp.col = 2;
            warp.row = 3;
            warp.extensions = List.of(new BezierExt(3, 9, 9), new BezierExt(2, 4, 5));
            final WarpForm warpForm = new WarpForm();
            warpForm.opacity = 0.25;
            warpForm.multiply = new FloatColor("#0A0B0C");
            warpForm.screen = new FloatColor("#0D0E0F");
            warpForm.positions = new float[] {
                10, 20, 0, 0, 50, 20,
                0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0,
                10, 60, 0, 0, 50, 60,
            };
            warp.form = warpForm;

            rot.name = "RotName";
            rot.targetDeformerId = warp.id;
            rot.baseAngle = 10.0;
            final RotForm rotForm = new RotForm();
            rotForm.angle = 45.0;
            rotForm.scale = 1.5;
            rotForm.opacity = 0.75;
            rotForm.multiply = new FloatColor("#222222");
            rotForm.screen = new FloatColor("#333333");
            rotForm.originX = 3.0;
            rotForm.originY = 4.0;
            rot.form = rotForm;

            glue.name = "GlueName";
            glue.parent = subPart;
            final GlueForm glueForm = new GlueForm();
            glueForm.intensity = 0.65f;
            glue.form = glueForm;

            access.script(
                mesh, other, artPath, rootPart, subPart, glue, paramSource, rootGroup,
                List.of(warp, rot));
        }

        EditSession open() throws EditSessionException {
            return manager.open(host.binding, DOCUMENT, EditSessionOptions.defaults());
        }
    }

    // ------------------------------------------------------------------
    // fake host object model
    // ------------------------------------------------------------------

    private record HostId(String value) {}

    private record HostGuid(String value) {}

    private static class HostSource {
        final HostId id;
        final HostGuid guid;
        String name;
        Object handler = new Handler();
        Object grid = new Object();
        Object targetDeformer;
        Object targetDeformerId;
        Object labelColor;
        Object parent;
        Object form;
        List<Object> extensions = List.of();
        List<Object> clipGuids = List.of();
        boolean enableDrawOrderGroup;
        boolean sketch;
        boolean offscreen;
        boolean reverseMask;
        boolean culling;
        float[] positions = new float[0];
        Object colorBlend;
        Object alphaBlend;

        HostSource(final String id, final String guid) {
            this.id = new HostId(id);
            this.guid = new HostGuid(guid);
            this.name = id;
        }
    }

    private static final class MeshSource extends HostSource {
        MeshSource(final String id, final String guid) { super(id, guid); }
    }

    private static final class PartSource extends HostSource {
        final List<Object> children = new ArrayList<>();
        final List<Object> keyforms = new ArrayList<>();
        PartSource(final String id, final String guid) {
            super(id, guid);
            handler = new PartHandler();
        }
    }

    private static final class WarpSource extends HostSource {
        int col;
        int row;
        WarpSource(final String id, final String guid) { super(id, guid); }
    }

    private static final class RotSource extends HostSource {
        double baseAngle;
        RotSource(final String id, final String guid) { super(id, guid); }
    }

    private static final class GlueSource extends HostSource {
        GlueSource(final String id, final String guid) { super(id, guid); }
    }

    /** Fake {@code CGlueForm}: only the verified {@code getIntensity} member is scripted. */
    private static final class GlueForm {
        float intensity;
    }

    /** A live model instance: {@code *.source} resolves back to the scripted source. */
    private static class HostInstance {
        final HostSource source;

        HostInstance(final HostSource source) {
            this.source = source;
        }
    }

    private static final class PartInstance extends HostInstance {
        PartInstance(final HostSource source) { super(source); }
    }

    private static final class MeshInstance extends HostInstance {
        MeshInstance(final HostSource source) { super(source); }
    }

    private static final class WarpInstance extends HostInstance {
        WarpInstance(final HostSource source) { super(source); }
    }

    private static final class RotInstance extends HostInstance {
        RotInstance(final HostSource source) { super(source); }
    }

    private static final class GlueInstance extends HostInstance {
        GlueInstance(final HostSource source) { super(source); }
    }

    /** Fake {@code CFloatColor}: only the verified {@code getHexRGB} member is scripted. */
    private static final class FloatColor {
        final String hex;

        FloatColor(final String hex) {
            this.hex = hex;
        }
    }

    /** Fake {@code CWarpDeformerBezierExtension}. */
    private static final class BezierExt {
        final int editLevel;
        final int col;
        final int row;

        BezierExt(final int editLevel, final int col, final int row) {
            this.editLevel = editLevel;
            this.col = col;
            this.row = row;
        }
    }

    private static final class PartForm {
        int drawOrder;
        double opacity;
        Object multiply;
        Object screen;
    }

    private static final class DrawableForm {
        int drawOrder;
        double opacity;
        Object multiply;
        Object screen;
    }

    private static class DeformerForm {
        double opacity;
        Object multiply;
        Object screen;
    }

    private static final class WarpForm extends DeformerForm {
        float[] positions = new float[0];
    }

    private static final class RotForm extends DeformerForm {
        double angle;
        double scale;
        double originX;
        double originY;
    }

    /** Host {@code ColorComposition} constants, in the 5.3.x declaration order. */
    private static final Object[] COLOR_BLEND_VALUES;

    /** Host {@code AlphaComposition} constants, in declaration order. */
    private static final Object[] ALPHA_BLEND_VALUES;

    static {
        COLOR_BLEND_VALUES = new Object[EditColorBlend.values().length];
        for (int i = 0; i < COLOR_BLEND_VALUES.length; i++) {
            COLOR_BLEND_VALUES[i] = new Object();
        }
        ALPHA_BLEND_VALUES = new Object[EditAlphaBlend.values().length];
        for (int i = 0; i < ALPHA_BLEND_VALUES.length; i++) {
            ALPHA_BLEND_VALUES[i] = new Object();
        }
    }

    private static final class Handler {}

    private static final class PartHandler {}

    private static final class GroupHandler {}

    private static final class LabelColor {}

    private static final class Param {}

    private static final class ParamSource {
        final HostId id;
        final HostGuid guid;
        final String name;
        final double minimum;
        final double defaultValue;
        final double maximum;

        ParamSource(
            final String id,
            final String guid,
            final String name,
            final double minimum,
            final double defaultValue,
            final double maximum
        ) {
            this.id = new HostId(id);
            this.guid = new HostGuid(guid);
            this.name = name;
            this.minimum = minimum;
            this.defaultValue = defaultValue;
            this.maximum = maximum;
        }
    }

    private static final class Group {
        final HostId id;
        final String name;
        final GroupHandler handler = new GroupHandler();
        final List<Object> children = new ArrayList<>();

        Group(final String id, final String name) {
            this.id = new HostId(id);
            this.name = name;
        }
    }

    // ------------------------------------------------------------------
    // scripted member surface
    // ------------------------------------------------------------------

    /**
     * Scripted {@link EditSessionOpsAccess}: member calls dispatch through an alias handler
     * table and every call is recorded. {@code authorizesFeature} admits everything unless a
     * capability is explicitly denied — the fail-closed tests assert no member call survives a
     * denied gate.
     */
    private static final class FakeOpsAccess implements EditSessionOpsAccess {
        record Call(String alias, Object target, List<Object> arguments) {}

        final Object document = new Object();
        final Object modelSource = new Object();
        final Object model = new Object();
        final Object app = new Object();
        final Object mainFrame = new Object();
        final Object palette = new Object();
        final Object paletteView = new Object();
        final Object operation = new Object();
        final Object validator = new Object();
        final Object updateManager = new Object();
        final Object pack = new Object();
        final Object modelHandler = new Object();
        final Object paramSet = new Object();
        final Param param = new Param();
        final Object undo = new Object();
        final List<Object> objects = new ArrayList<>();
        final List<Object> glues = new ArrayList<>();
        List<Object> deformers = List.of();
        List<Object> selectionGuids = List.of();

        private final Map<String, BiFunction<Object, Object[], Object>> handlers =
            new HashMap<>();
        private final Map<String, java.util.function.Function<Object[], Object>>
            staticHandlers = new HashMap<>();
        private final Map<String, java.util.function.Function<Object[], Object>>
            constructorHandlers = new HashMap<>();
        private final Map<String, Predicate<Object>> instanceChecks = new HashMap<>();
        private final Map<String, Object> staticFields = new HashMap<>();
        private final List<Call> calls = new ArrayList<>();
        private boolean denyAll;
        private final Set<String> denied = new HashSet<>();
        private final Set<String> deniedAliases = new HashSet<>();

        void script(
            final HostSource mesh,
            final HostSource other,
            final HostSource artPath,
            final PartSource rootPart,
            final PartSource subPart,
            final GlueSource glue,
            final ParamSource paramSource,
            final Group rootGroup,
            final List<Object> deformerList
        ) {
            objects.add(mesh);
            objects.add(other);
            objects.add(rootPart);
            objects.add(subPart);
            objects.addAll(deformerList);
            deformers = deformerList;
            glues.add(glue);

            final List<Object> partInstances =
                List.of(new PartInstance(rootPart), new PartInstance(subPart));
            final List<Object> meshInstances =
                List.of(new MeshInstance(mesh), new MeshInstance(other));
            final ArrayList<Object> deformerInstances = new ArrayList<>();
            for (final Object deformer : deformerList) {
                deformerInstances.add(
                    deformer instanceof WarpSource
                        ? new WarpInstance((HostSource) deformer)
                        : new RotInstance((HostSource) deformer));
            }
            final Map<String, Object> instancesById = new HashMap<>();
            instancesById.put(glue.id.value(), new GlueInstance(glue));

            on("cubism.editor-model.model-source.all-objects", (t, a) -> objects);
            on("cubism.editor-model.model-source.all-deformers", (t, a) -> deformers);
            on("cubism.editor-model.model-source.all-art-meshes",
                (t, a) -> List.of(mesh, other));
            on("cubism.editor-model.model-source.all-glues", (t, a) -> glues);
            on("cubism.editor-model.model.get-object",
                (t, a) -> instancesById.get(((HostId) a[0]).value()));
            on("cubism.editor-model.model-source.parts", (t, a) -> List.of(rootPart));
            on("cubism.editor-model.model-source.root-part", (t, a) -> rootPart);
            on("cubism.editor-model.model-source.root-parameter-group", (t, a) -> rootGroup);
            on("cubism.editor-model.model-source.handler", (t, a) -> modelHandler);
            on("cubism.editor-model.model-source.update-instances", (t, a) -> null);
            on("cubism.editor-model.model.parameter-set", (t, a) -> paramSet);
            on("cubism.editor-model.parameter-set.parameters", (t, a) -> List.of(param));
            on("cubism.editor-model.parameter.source", (t, a) -> paramSource);
            on("cubism.editor-model.parameter-source.id", (t, a) -> paramSource.id);
            on("cubism.editor-model.parameter-source.guid", (t, a) -> paramSource.guid);
            on("cubism.editor-model.parameter-source.name", (t, a) -> paramSource.name);
            on("cubism.editor-model.parameter-source.minimum", (t, a) -> paramSource.minimum);
            on("cubism.editor-model.parameter-source.default",
                (t, a) -> paramSource.defaultValue);
            on("cubism.editor-model.parameter-source.maximum", (t, a) -> paramSource.maximum);
            on("cubism.editor-model.parameter-source.repeat", (t, a) -> Boolean.FALSE);
            on("cubism.editor-model.parameter-source.morph-target", (t, a) -> Boolean.FALSE);
            on("cubism.editor-model.parameter-controllable-source.id",
                (t, a) -> ((HostSource) t).id);
            on("cubism.editor-model.parameter-controllable-source.guid",
                (t, a) -> ((HostSource) t).guid);
            on("cubism.editor-model.parameter-controllable-source.local-name",
                (t, a) -> ((HostSource) t).name);
            on("cubism.editor-model.parameter-controllable-source.handler",
                (t, a) -> ((HostSource) t).handler);
            on("cubism.editor-model.parameter-controllable-source.target-deformer-source",
                (t, a) -> ((HostSource) t).targetDeformer);
            on("cubism.editor-model.parameter-controllable-source.label-color",
                (t, a) -> ((HostSource) t).labelColor);
            on("cubism.editor-model.parameter-controllable-source.name-or-id-string",
                (t, a) -> ((HostSource) t).name);
            on("cubism.editor-model.parameter-controllable-source.target-deformer-id",
                (t, a) -> ((HostSource) t).targetDeformerId);
            on("cubism.editor-model.parameter-controllable-source.extensions",
                (t, a) -> ((HostSource) t).extensions);
            on("cubism.editor-model.parameter-controllable.interpolated-form",
                (t, a) -> ((HostInstance) t).source.form);
            on("cubism.editor-model.part-source.parent",
                (t, a) -> ((HostSource) t).parent);
            on("cubism.editor-model.float-color.hex-rgb",
                (t, a) -> ((FloatColor) t).hex);
            on("cubism.editor-model.part-source.enable-draw-order-group",
                (t, a) -> ((HostSource) t).enableDrawOrderGroup);
            on("cubism.editor-model.part-source.sketch",
                (t, a) -> ((HostSource) t).sketch);
            on("cubism.editor-model.part-source.use-offscreen",
                (t, a) -> ((HostSource) t).offscreen);
            on("cubism.editor-model.part-source.clip-guid-list",
                (t, a) -> ((HostSource) t).clipGuids);
            on("cubism.editor-model.part-source.invert-clipping-mask",
                (t, a) -> ((HostSource) t).reverseMask);
            on("cubism.editor-model.part-source.color-composition",
                (t, a) -> ((HostSource) t).colorBlend);
            on("cubism.editor-model.part-source.alpha-composition",
                (t, a) -> ((HostSource) t).alphaBlend);
            on("cubism.editor-model.part-form.draw-order",
                (t, a) -> ((PartForm) t).drawOrder);
            on("cubism.editor-model.part-form.opacity",
                (t, a) -> ((PartForm) t).opacity);
            on("cubism.editor-model.part-form.multiply-color",
                (t, a) -> ((PartForm) t).multiply);
            on("cubism.editor-model.part-form.screen-color",
                (t, a) -> ((PartForm) t).screen);
            on("cubism.editor-model.art-mesh-source.clip-guid-list",
                (t, a) -> ((HostSource) t).clipGuids);
            on("cubism.editor-model.art-mesh-source.inverted-mask",
                (t, a) -> ((HostSource) t).reverseMask);
            on("cubism.editor-model.art-mesh-source.color-composition",
                (t, a) -> ((HostSource) t).colorBlend);
            on("cubism.editor-model.art-mesh-source.alpha-composition",
                (t, a) -> ((HostSource) t).alphaBlend);
            on("cubism.editor-model.art-mesh-source.culling",
                (t, a) -> ((HostSource) t).culling);
            on("cubism.editor-model.art-mesh-source.positions",
                (t, a) -> ((HostSource) t).positions);
            on("cubism.editor-model.drawable-form.draw-order",
                (t, a) -> ((DrawableForm) t).drawOrder);
            on("cubism.editor-model.drawable-form.opacity",
                (t, a) -> ((DrawableForm) t).opacity);
            on("cubism.editor-model.drawable-form.multiply-color",
                (t, a) -> ((DrawableForm) t).multiply);
            on("cubism.editor-model.drawable-form.screen-color",
                (t, a) -> ((DrawableForm) t).screen);
            on("cubism.editor-model.warp-source.col",
                (t, a) -> ((WarpSource) t).col);
            on("cubism.editor-model.warp-source.row",
                (t, a) -> ((WarpSource) t).row);
            on("cubism.editor-model.warp-form.positions",
                (t, a) -> ((WarpForm) t).positions);
            on("cubism.editor-model.deformer-form.opacity",
                (t, a) -> ((DeformerForm) t).opacity);
            on("cubism.editor-model.deformer-form.multiply-color",
                (t, a) -> ((DeformerForm) t).multiply);
            on("cubism.editor-model.deformer-form.screen-color",
                (t, a) -> ((DeformerForm) t).screen);
            on("cubism.editor-model.warp-bezier-extension.edit-level",
                (t, a) -> ((BezierExt) t).editLevel);
            on("cubism.editor-model.warp-bezier-extension.bezier-col",
                (t, a) -> ((BezierExt) t).col);
            on("cubism.editor-model.warp-bezier-extension.bezier-row",
                (t, a) -> ((BezierExt) t).row);
            on("cubism.editor-model.rotation-form.angle",
                (t, a) -> ((RotForm) t).angle);
            on("cubism.editor-model.rotation-form.scale",
                (t, a) -> ((RotForm) t).scale);
            on("cubism.editor-model.rotation-form.origin-x",
                (t, a) -> ((RotForm) t).originX);
            on("cubism.editor-model.rotation-form.origin-y",
                (t, a) -> ((RotForm) t).originY);
            on("cubism.editor-model.rotation-source.base-angle",
                (t, a) -> ((RotSource) t).baseAngle);
            on("cubism.editor-model.model.parts", (t, a) -> partInstances);
            on("cubism.editor-model.part.source",
                (t, a) -> ((HostInstance) t).source);
            on("cubism.editor-model.model.all-art-meshes", (t, a) -> meshInstances);
            on("cubism.editor-model.art-mesh.source",
                (t, a) -> ((HostInstance) t).source);
            on("cubism.editor-model.model.all-deformers", (t, a) -> deformerInstances);
            on("cubism.editor-model.deformer.source",
                (t, a) -> ((HostInstance) t).source);
            on("cubism.editor-model.parameter-controllable-source.set-local-name",
                (t, a) -> null);
            on("cubism.editor-model.parameter-controllable.keyform-grid",
                (t, a) -> ((HostSource) t).grid);
            on("cubism.editor-model.parameter-controllable-handler"
                + ".create-undo-for-all-edit", (t, a) -> undo);
            on("cubism.editor-model.undo.add", (t, a) -> Boolean.TRUE);
            on("cubism.editor-model.id.value", (t, a) -> ((HostId) t).value());
            on("cubism.editor-model.part-id.value", (t, a) -> ((HostId) t).value());
            on("cubism.editor-model.guid.value", (t, a) -> ((HostGuid) t).value());
            on("cubism.editor-model.keyform-grid.add-key", (t, a) -> null);
            on("cubism.editor-model.keyform-grid.bindings", (t, a) -> List.of());
            on("cubism.editor-model.keyform-grid-source.set-initial-keyform",
                (t, a) -> null);
            on("cubism.editor-model.parameter-controllable-source.set-keyform-grid-source",
                (t, a) -> {
                    ((HostSource) t).grid = a[0];
                    return null;
                });
            on("cubism.editor-model.c-array-list.add",
                (t, a) -> Boolean.valueOf(((List<Object>) t).add(a[0])));
            on("cubism.editor-model.part-source.keyforms",
                (t, a) -> ((PartSource) t).keyforms);
            on("cubism.editor-model.part-source.id", (t, a) -> ((PartSource) t).id);
            on("cubism.editor-model.part-source.children",
                (t, a) -> ((PartSource) t).children);
            on("cubism.editor-model.part-source.handler",
                (t, a) -> ((PartSource) t).handler);
            on("cubism.editor-model.part-handler.add-part-child",
                (t, a) -> {
                    rootPart.children.add(a[0]);
                    return undo;
                });
            on("cubism.editor-model.part-source.set-id", (t, a) -> null);
            on("cubism.editor-model.part-source.set-guid", (t, a) -> null);
            on("cubism.editor-model.part-source.set-local-name", (t, a) -> null);
            on("cubism.editor-model.part-source.set-default-order", (t, a) -> null);
            on("cubism.editor-model.parameter-group.children",
                (t, a) -> ((Group) t).children);
            on("cubism.editor-model.parameter-group.id", (t, a) -> ((Group) t).id);
            on("cubism.editor-model.parameter-group.name", (t, a) -> ((Group) t).name);
            on("cubism.editor-model.parameter-group.label-color", (t, a) -> null);
            on("cubism.editor-model.parameter-group.handler",
                (t, a) -> ((Group) t).handler);
            on("cubism.editor-model.deformer-source.guid",
                (t, a) -> ((HostSource) t).guid);
            on("cubism.editor-model.deformer-source.set-local-name", (t, a) -> null);
            on("cubism.editor-model.glue-source.set-local-name", (t, a) -> null);
            on("cubism.editor-model.glue-source.local-name",
                (t, a) -> ((HostSource) t).name);
            on("cubism.editor-model.glue.current-keyform",
                (t, a) -> ((HostInstance) t).source.form);
            on("cubism.editor-model.glue-form.intensity",
                (t, a) -> ((GlueForm) t).intensity);
            on("cubism.editor-model.update-manager.selection-guid-list",
                (t, a) -> selectionGuids);
            on("cubism.editor-model.update-manager.set-selection", (t, a) -> null);
            on("cubism.editor-model.app-controller.update-manager", (t, a) -> updateManager);
            on("cubism.editor-model.app-controller.complete-pack", (t, a) -> pack);
            on("cubism.editor-model.complete-pack.update-parameter", (t, a) -> null);
            on("cubism.editor-model.complete-pack.update-part-palette", (t, a) -> null);
            on("cubism.editor-model.complete-pack.update-deformer-palette", (t, a) -> null);
            on("cubism.editor-model.complete-pack.repaint-canvas", (t, a) -> null);
            on("cubism.editor-model.modeling-document.mark-dirty", (t, a) -> null);
            on("cubism.editor-model.model-handler.remove-objects", (t, a) -> undo);
            on("cubism.editor-model.model-handler.add-source-undo", (t, a) -> undo);
            on("cubism.editor-model.parameter-group-handler.add-parameter-child",
                (t, a) -> undo);
            on("cubism.editor-model.app-controller.main-frame", (t, a) -> mainFrame);
            on("cubism.editor-model.main-frame.parameter-palette", (t, a) -> palette);
            on("cubism.editor-model.parameter-palette.view", (t, a) -> paletteView);
            on("cubism.editor-model.parameter-palette-view.operation",
                (t, a) -> operation);
            on("cubism.editor-model.parameter-validator.valid-id", (t, a) -> Boolean.TRUE);
            on("cubism.editor-model.parameter-validator.keys-outside-range",
                (t, a) -> Boolean.FALSE);
            on("cubism.editor-model.parameter-validator.allow-repeat",
                (t, a) -> Boolean.TRUE);
            on("cubism.editor-model.parameter-validator.default-change-affects-morph-target",
                (t, a) -> Boolean.FALSE);
            on("cubism.editor-model.form.set-guid", (t, a) -> null);
            on("cubism.editor-model.parameter-source.set-name", (t, a) -> null);
            on("cubism.editor-model.parameter-source.set-minimum", (t, a) -> null);
            on("cubism.editor-model.parameter-source.set-maximum", (t, a) -> null);
            on("cubism.editor-model.parameter-source.set-default", (t, a) -> null);
            on("cubism.editor-model.parameter-source.set-repeat", (t, a) -> null);
            on("cubism.editor-model.parameter-source.set-id", (t, a) -> null);

            onStatic("cubism.editor-model.app-controller.instance", a -> app);
            onStatic("cubism.editor-model.parameter-operation.validator", a -> validator);
            onStatic("cubism.editor-model.model-handler.create-free-id-default",
                a -> a[1]);
            onStatic("cubism.editor-model.model-source.verify", a -> null);
            onStatic("cubism.editor-model.color-composition.values",
                a -> COLOR_BLEND_VALUES);
            onStatic("cubism.editor-model.alpha-composition.values",
                a -> ALPHA_BLEND_VALUES);

            onConstruct("cubism.editor-model.parameter-id.create", a -> new HostId((String) a[0]));
            onConstruct("cubism.editor-model.parameter-source.create", a -> new ParamSource(
                "created", "g-created", (String) a[1], 0.0, 0.0, 1.0));
            onConstruct("cubism.editor-model.parameter-group-id.create",
                a -> new HostId((String) a[0]));
            onConstruct("cubism.editor-model.parameter-group-guid.create",
                a -> new HostGuid("g-new"));
            onConstruct("cubism.editor-model.part-source.create",
                a -> new PartSource("created", "g-created"));
            onConstruct("cubism.editor-model.part-id.create", a -> new HostId((String) a[0]));
            onConstruct("cubism.editor-model.part-guid.create", a -> new HostGuid("g-new"));
            onConstruct("cubism.editor-model.part-form.create", a -> new PartForm());
            onConstruct("cubism.editor-model.form-guid.create", a -> new HostGuid("g-form"));
            onConstruct("cubism.editor-model.keyform-grid-source.create", a -> new Object());
            onConstruct("cubism.editor-model.simple-undo.create", a -> undo);

            isInstanceOf("cubism.editor-model.part-source.class", PartSource.class);
            isInstanceOf("cubism.editor-model.art-mesh-source.class", MeshSource.class);
            isInstanceOf("cubism.editor-model.warp-source.class", WarpSource.class);
            isInstanceOf("cubism.editor-model.rotation-source.class", RotSource.class);
            isInstanceOf("cubism.editor-model.glue-source.class", GlueSource.class);
            isInstanceOf("cubism.editor-model.part.class", PartInstance.class);
            isInstanceOf("cubism.editor-model.art-mesh.class", MeshInstance.class);
            isInstanceOf("cubism.editor-model.warp.class", WarpInstance.class);
            isInstanceOf("cubism.editor-model.rotation.class", RotInstance.class);
            isInstanceOf("cubism.editor-model.warp-bezier-extension.class",
                BezierExt.class);
            isInstanceOf("cubism.editor-model.parameter-group.class", Group.class);
            isInstanceOf("cubism.editor-model.parameter-source.class", ParamSource.class);
            isInstanceOf("cubism.editor-model.parameter-group-handler.class",
                GroupHandler.class);
            isInstanceOf("cubism.editor-model.parameter-controllable-handler.class",
                Handler.class);
            isInstanceOf("cubism.editor-model.part-handler.class", PartHandler.class);
            isInstanceOf("cubism.editor-model.label-color.class", LabelColor.class);
        }

        void denyAll() {
            denyAll = true;
        }

        void deny(final String capabilityId) {
            denied.add(capabilityId);
        }

        void denyAlias(final String alias) {
            deniedAliases.add(alias);
        }

        @Override
        public Object document() {
            return document;
        }

        @Override
        public Object modelSource() {
            return modelSource;
        }

        @Override
        public Object model() {
            return model;
        }

        @Override
        public boolean authorizesFeature(final String capabilityId, final Set<String> aliases) {
            return !denyAll
                && !denied.contains(capabilityId)
                && deniedAliases.stream().noneMatch(aliases::contains);
        }

        @Override
        public Object invoke(final String alias, final Object target, final Object... arguments) {
            calls.add(new Call(alias, target, java.util.Arrays.asList(arguments)));
            final BiFunction<Object, Object[], Object> handler = handlers.get(alias);
            if (handler == null) {
                throw new AssertionError("unscripted member call: " + alias);
            }
            return handler.apply(target, arguments);
        }

        @Override
        public Object invokeStatic(final String alias, final Object... arguments) {
            calls.add(new Call(alias, null, java.util.Arrays.asList(arguments)));
            final var handler = staticHandlers.get(alias);
            if (handler == null) {
                throw new AssertionError("unscripted static call: " + alias);
            }
            return handler.apply(arguments);
        }

        @Override
        public Object construct(final String alias, final Object... arguments) {
            calls.add(new Call(alias, null, java.util.Arrays.asList(arguments)));
            final var handler = constructorHandlers.get(alias);
            if (handler == null) {
                throw new AssertionError("unscripted constructor: " + alias);
            }
            return handler.apply(arguments);
        }

        @Override
        public Object readStaticField(final String alias) {
            return staticFields.get(alias);
        }

        @Override
        public boolean isInstance(final String alias, final Object value) {
            final Predicate<Object> check = instanceChecks.get(alias);
            return check != null && check.test(value);
        }

        void on(final String alias, final BiFunction<Object, Object[], Object> handler) {
            handlers.put(alias, handler);
        }

        void onStatic(final String alias, final java.util.function.Function<Object[], Object> handler) {
            staticHandlers.put(alias, handler);
        }

        void onConstruct(final String alias, final java.util.function.Function<Object[], Object> handler) {
            constructorHandlers.put(alias, handler);
        }

        void isInstanceOf(final String alias, final Class<?> type) {
            instanceChecks.put(alias, type::isInstance);
        }

        boolean called(final String alias) {
            return calls.stream().anyMatch(call -> call.alias().equals(alias));
        }

        boolean constructed(final String alias) {
            return called(alias);
        }

        List<Call> callsOf(final String alias) {
            return calls.stream()
                .filter(call -> call.alias().equals(alias))
                .toList();
        }

        int indexOf(final String alias) {
            for (int i = 0; i < calls.size(); i++) {
                if (calls.get(i).alias().equals(alias)) {
                    return i;
                }
            }
            return -1;
        }

        List<String> memberCalls() {
            return calls.stream().map(Call::alias).toList();
        }
    }

    /**
     * Fake session host: the lifecycle members mirror {@code RuntimeEditSessionManagerTest}'s
     * fake; {@link #opsAccess} hands out the scripted member surface so routed operations can
     * run. {@link #dispatch} records labels and runs inline.
     *
     * <p>The host models the native edit-mode/history semantics the session relies on:
     * {@code beginEdit} installs the returned token as the current undo group (a second
     * {@code beginEdit} REPLACES it without closing the first — exactly like the host's
     * deferred palette edits), {@code endEdit(false)} commits the current group as one history
     * entry, {@code endEdit(true)} discards it, {@code revert()} pops the entry at the cursor,
     * and {@code undoRedoTo} only moves the cursor. Session operations never call
     * {@code beginEdit}/{@code endEdit}, so an open session must leave the history position and
     * entry list untouched.</p>
     */
    private static final class FakeOpsHost implements EditorEditSessionHost {
        final EditorAuthoringTransactionCoordinator.Binding binding =
            new EditorAuthoringTransactionCoordinator.Binding(
                "plugin.test", "document-1", 1, "model-1", 1, Thread.currentThread());
        final Object editToken = new Object();
        final List<Object> entries = new ArrayList<>();
        final List<String> revertedLabels = new ArrayList<>();
        final List<Integer> cursorMoves = new ArrayList<>();
        int position;
        Object currentGroup;
        boolean revertVerified;
        private final FakeOpsAccess access;
        private final List<String> dispatchLabels = new ArrayList<>();

        FakeOpsHost(final FakeOpsAccess access) {
            this.access = access;
        }

        List<String> opsDispatchLabels() {
            return dispatchLabels.stream()
                .filter(label -> !label.equals("edit-session.open")
                    && !label.equals("edit-session.close")
                    && !label.equals("edit-session.cancel"))
                .toList();
        }

        /**
         * Simulates a host-side edit displacing the session's undo group: a foreign {@code
         * GroupUndo} replaces {@code currentGroup} (native {@code beginEdit} semantics); with
         * {@code commit} it is additionally pushed to history, as the foreign edit's own
         * {@code endEdit(false)} would.
         */
        void displaceSessionGroup(final boolean commit) {
            final Object foreign = new Object();
            currentGroup = foreign;
            if (commit) {
                entries.add(foreign);
                position = entries.size();
                currentGroup = null;
            }
        }

        HistorySnapshot snapshot() {
            final List<HistoryEntry> rows = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) {
                rows.add(new HistoryEntry(i, "entry-" + i, true));
            }
            return new HistorySnapshot(
                HistorySnapshot.Availability.AVAILABLE,
                1,
                7,
                position,
                rows,
                position > 0,
                position < entries.size());
        }

        @Override
        public Optional<EditorAuthoringTransactionCoordinator.Binding> currentBinding(
            final String pluginId
        ) {
            return Optional.of(binding);
        }

        @Override
        public boolean isCurrent(final EditorAuthoringTransactionCoordinator.Binding expected) {
            return true;
        }

        @Override
        public boolean admits(final EditorAuthoringTransactionCoordinator.Binding expected) {
            return true;
        }

        @Override
        public HistorySnapshot history(final EditorAuthoringTransactionCoordinator.Binding expected) {
            return snapshot();
        }

        @Override
        public Object beginEdit(
            final EditorAuthoringTransactionCoordinator.Binding expected,
            final String label
        ) {
            currentGroup = editToken;
            return editToken;
        }

        @Override
        public void endEdit(
            final EditorAuthoringTransactionCoordinator.Binding expected,
            final Object edit,
            final boolean cancel
        ) {
            if (!cancel && currentGroup != null) {
                entries.add(currentGroup);
                position = entries.size();
            }
            currentGroup = null;
        }

        @Override
        public Object currentEditGroup(
            final EditorAuthoringTransactionCoordinator.Binding expected
        ) {
            return currentGroup;
        }

        @Override
        public void undoEditGroup(
            final EditorAuthoringTransactionCoordinator.Binding expected,
            final Object edit
        ) {
        }

        @Override
        public void undoGroup(
            final EditorAuthoringTransactionCoordinator.Binding expected,
            final Object group
        ) {
        }

        @Override
        public void undoRedoTo(
            final EditorAuthoringTransactionCoordinator.Binding expected,
            final int target
        ) {
            cursorMoves.add(target);
            position = Math.min(target, entries.size());
        }

        @Override
        public boolean undoRevertVerified(
            final EditorAuthoringTransactionCoordinator.Binding expected
        ) {
            return revertVerified;
        }

        @Override
        public void revert(final EditorAuthoringTransactionCoordinator.Binding expected) {
            revertedLabels.add("revert");
            if (position > 0) {
                // CUndoManager.revert: undo the entry at the cursor, then drop it and every
                // entry above — position and the entry list both shrink.
                entries.subList(position - 1, entries.size()).clear();
                position--;
            }
        }

        @Override
        public Optional<Object> mainWindow(
            final EditorAuthoringTransactionCoordinator.Binding expected
        ) {
            return Optional.empty();
        }

        @Override
        public void refreshAfterSession(
            final EditorAuthoringTransactionCoordinator.Binding expected
        ) {
        }

        @Override
        public <T> T dispatch(final String label, final HostTask<T> task)
            throws EditSessionException {
            dispatchLabels.add(label);
            return task.run();
        }

        @Override
        public EditSessionOpsAccess opsAccess(
            final EditorAuthoringTransactionCoordinator.Binding binding
        ) {
            return access;
        }

        @Override
        public String diagnosticId(final String code, final Throwable failure) {
            return code + ":fake";
        }
    }
}
