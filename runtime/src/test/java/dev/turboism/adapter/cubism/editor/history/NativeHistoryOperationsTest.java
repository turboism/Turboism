package dev.turboism.adapter.cubism.editor.history;

import dev.turboism.sdk.cubism.event.CubismOperation;
import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryChange;
import dev.turboism.sdk.cubism.history.HistoryEditContext;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistoryGroup;
import dev.turboism.sdk.cubism.history.HistoryOrigin;
import dev.turboism.sdk.cubism.history.HistoryRelationChange;
import dev.turboism.sdk.cubism.history.HistoryTarget;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the native-entry to semantic-operation mapping stays structural and conservative. */
class NativeHistoryOperationsTest {

    @Test
    void aJoinRelationProvesTheHierarchyParentOperationForItsChild() {
        final NativeHistoryOperations.Resolution resolution = NativeHistoryOperations.resolve(
            relation(HistoryRelationChange.Kind.PART_MEMBERSHIP, "ART_MESH", true)
        );

        assertEquals(CubismOperation.SET_HIERARCHY_PARENT, resolution.operation());
        assertEquals(Optional.of("mesh-1"), resolution.subjectId());
    }

    @Test
    void aLeaveRelationProvesTheDetachOperation() {
        final NativeHistoryOperations.Resolution resolution = NativeHistoryOperations.resolve(
            relation(HistoryRelationChange.Kind.PART_MEMBERSHIP, "WARP_DEFORMER", false)
        );

        assertEquals(CubismOperation.DETACH_HIERARCHY_PARENT, resolution.operation());
        assertEquals(Optional.of("mesh-1"), resolution.subjectId());
    }

    @Test
    void aDeformerParentChangeMapsThroughTheSameStructure() {
        assertEquals(
            CubismOperation.SET_HIERARCHY_PARENT,
            NativeHistoryOperations.resolve(
                relation(HistoryRelationChange.Kind.DEFORMER_PARENT, "ROTATION_DEFORMER", true)
            ).operation()
        );
    }

    @Test
    void aPartChildIsAlsoTheSubjectOfItsHierarchyChange() {
        // The moved object is the subject whatever its admitted type is: a Part drag moves a Part,
        // and dropping the subject would leave the event pointing at nothing.
        final NativeHistoryOperations.Resolution resolution = NativeHistoryOperations.resolve(
            relation(HistoryRelationChange.Kind.PART_MEMBERSHIP, "PART", true)
        );

        assertEquals(CubismOperation.SET_HIERARCHY_PARENT, resolution.operation());
        assertEquals(Optional.of("mesh-1"), resolution.subjectId());
    }

    @Test
    void anEntryWithoutAProvenRelationFallsBackToTheGenericEditorCommand() {
        final HistoryEntryDetail plain = new HistoryEntryDetail(
            "Rename part",
            HistoryAction.DetailLevel.PARTIAL,
            HistoryOrigin.hostUnattributed(),
            List.of(),
            List.of(new HistoryChange(
                HistoryChange.Operation.SET,
                Optional.empty(),
                Optional.of("name"),
                Optional.empty(),
                Optional.empty(),
                new HistoryEditContext(HistoryEditContext.Kind.OBJECT, Optional.empty(), List.of())
            )),
            Optional.empty(),
            Optional.of("history.detail.unsupported")
        );

        assertEquals(
            CubismOperation.EXECUTE_EDITOR_COMMAND,
            NativeHistoryOperations.resolve(plain).operation()
        );
        assertTrue(NativeHistoryOperations.resolve(plain).subjectId().isEmpty());
    }

    @Test
    void anAppearanceChannelChangeProvesTheDrawableColorOperation() {
        for (final String property : List.of(
            "opacity", "drawOrder", "multiplyColor", "screenColor"
        )) {
            final NativeHistoryOperations.Resolution resolution = NativeHistoryOperations.resolve(
                appearance(property)
            );

            assertEquals(
                CubismOperation.SET_DRAWABLE_COLOR,
                resolution.operation(),
                property + " is an admitted appearance channel"
            );
            assertEquals(Optional.of("mesh-1"), resolution.subjectId(), property);
        }
    }

    @Test
    void aValueChangeOutsideTheAppearanceChannelsStaysGeneric() {
        assertEquals(
            CubismOperation.EXECUTE_EDITOR_COMMAND,
            NativeHistoryOperations.resolve(appearance("name")).operation()
        );
    }

    @Test
    void aVertexPositionsChangeStaysGenericAndNeverProvesAMove() {
        // The catalog admits vertexPositions as a bounded fact but forbids deriving a movement
        // verdict from coordinates, so a geometry-only entry is the generic editor command.
        final NativeHistoryOperations.Resolution resolution = NativeHistoryOperations.resolve(
            appearance("vertexPositions")
        );

        assertEquals(CubismOperation.EXECUTE_EDITOR_COMMAND, resolution.operation());
        assertTrue(resolution.subjectId().isEmpty(),
            "a generic entry claims no subject");
    }

    @Test
    void aDetailMixingAppearanceWithAnotherChangeStaysGeneric() {
        // A mixed entry describes more than one fact, so no single operation is proven.
        final HistoryEntryDetail mixed = new HistoryEntryDetail(
            "Edit",
            HistoryAction.DetailLevel.PARTIAL,
            HistoryOrigin.hostUnattributed(),
            List.of(target()),
            List.of(change("multiplyColor"), change("name")),
            Optional.empty(),
            Optional.of("history.detail.unsupported")
        );

        assertEquals(
            CubismOperation.EXECUTE_EDITOR_COMMAND,
            NativeHistoryOperations.resolve(mixed).operation()
        );
    }

    @Test
    void anUndecodedEntryFallsBackToTheGenericEditorCommand() {
        assertEquals(
            CubismOperation.EXECUTE_EDITOR_COMMAND,
            NativeHistoryOperations.resolve(null).operation()
        );
    }

    private static HistoryTarget target() {
        return new HistoryTarget("ART_MESH", Optional.of("mesh-1"), Optional.of("BodyMesh"));
    }

    private static HistoryChange change(final String property) {
        return new HistoryChange(
            HistoryChange.Operation.SET,
            Optional.of(0),
            Optional.of(property),
            Optional.of("#000000"),
            Optional.of("#FFFFFF"),
            new HistoryEditContext(HistoryEditContext.Kind.OBJECT, Optional.empty(), List.of())
        );
    }

    @Test
    void aGroupWhoseChildrenAllProveOneAppearanceChangeIsAColourChange() {
        // The host commits a multiply-colour edit as GroupUndo[GroupUndo[29 x SimpleUndo]], one
        // entry per keyform. The facts live on the children, so a mapping that only reads the
        // top-level change list can never classify it.
        final HistoryEntryDetail grouped = new HistoryEntryDetail(
            "Edit",
            HistoryAction.DetailLevel.FULL,
            HistoryOrigin.hostUnattributed(),
            List.of(),
            List.of(),
            Optional.of(new HistoryGroup(
                Optional.empty(),
                2,
                List.of(appearance("multiplyColor"), appearance("multiplyColor")),
                false
            )),
            Optional.empty()
        );

        final NativeHistoryOperations.Resolution resolution = NativeHistoryOperations.resolve(grouped);

        assertEquals(CubismOperation.SET_DRAWABLE_COLOR, resolution.operation());
        assertEquals(Optional.of("mesh-1"), resolution.subjectId());
    }

    @Test
    void theHostTwoLevelGroupOfPerKeyformAppearanceChangesIsAColourChange() {
        // Exactly what the native palette commits for a multiply-colour edit on 5.3.02:
        // GroupUndo[ GroupUndo[ 29 x SimpleUndo(CArtMeshForm) ] ]. The outer group holds one child
        // and the facts are two levels down, so both the walk and the "one subject" rule have to
        // look through the wrapper.
        final List<HistoryEntryDetail> keyforms = new java.util.ArrayList<>();
        for (int index = 0; index < 29; index++) {
            keyforms.add(appearance("multiplyColor"));
        }
        final HistoryEntryDetail colour = new HistoryEntryDetail(
            "Edit",
            HistoryAction.DetailLevel.FULL,
            HistoryOrigin.hostUnattributed(),
            List.of(),
            List.of(),
            Optional.of(new HistoryGroup(
                Optional.empty(),
                1,
                List.of(new HistoryEntryDetail(
                    "Edit",
                    HistoryAction.DetailLevel.FULL,
                    HistoryOrigin.hostUnattributed(),
                    List.of(),
                    List.of(),
                    Optional.of(new HistoryGroup(Optional.empty(), 29, keyforms, false)),
                    Optional.empty()
                )),
                false
            )),
            Optional.empty()
        );

        final NativeHistoryOperations.Resolution resolution = NativeHistoryOperations.resolve(colour);

        assertEquals(CubismOperation.SET_DRAWABLE_COLOR, resolution.operation());
        assertEquals(Optional.of("mesh-1"), resolution.subjectId());
    }

    @Test
    void aGroupWithOneGeometryChildIsNotAColourChange() {
        final HistoryEntryDetail geometry = new HistoryEntryDetail(
            "Edit",
            HistoryAction.DetailLevel.FULL,
            HistoryOrigin.hostUnattributed(),
            List.of(target()),
            List.of(change("vertexPositions")),
            Optional.empty(),
            Optional.empty()
        );
        final HistoryEntryDetail grouped = new HistoryEntryDetail(
            "Edit",
            HistoryAction.DetailLevel.FULL,
            HistoryOrigin.hostUnattributed(),
            List.of(),
            List.of(),
            Optional.of(new HistoryGroup(
                Optional.empty(),
                2,
                List.of(appearance("multiplyColor"), geometry),
                false
            )),
            Optional.empty()
        );

        assertEquals(
            CubismOperation.EXECUTE_EDITOR_COMMAND,
            NativeHistoryOperations.resolve(grouped).operation(),
            "a mixed group describes more than one fact"
        );
    }

    private static HistoryEntryDetail appearance(final String property) {
        return new HistoryEntryDetail(
            "Edit",
            HistoryAction.DetailLevel.FULL,
            HistoryOrigin.hostUnattributed(),
            List.of(target()),
            List.of(change(property)),
            Optional.empty(),
            Optional.empty()
        );
    }

    private static HistoryEntryDetail relation(
        final HistoryRelationChange.Kind kind,
        final String childType,
        final boolean join
    ) {
        final HistoryRelationChange.Endpoint target = new HistoryRelationChange.Endpoint(
            HistoryRelationChange.State.TARGET,
            Optional.of(new HistoryTarget("PART", Optional.of("PartA"), Optional.of("PartA")))
        );
        final HistoryRelationChange.Endpoint unknown = new HistoryRelationChange.Endpoint(
            HistoryRelationChange.State.UNKNOWN,
            Optional.empty()
        );
        // A one-sided relation is never FULL: the SDK requires both endpoints to be known.
        return new HistoryEntryDetail(
            "Add Part",
            HistoryAction.DetailLevel.PARTIAL,
            HistoryOrigin.hostUnattributed(),
            List.of(new HistoryTarget(childType, Optional.of("mesh-1"), Optional.of("BodyMesh"))),
            List.of(new HistoryChange(
                HistoryChange.Operation.SET,
                Optional.of(0),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                new HistoryEditContext(HistoryEditContext.Kind.OBJECT, Optional.empty(), List.of()),
                Optional.of(new HistoryRelationChange(
                    kind,
                    join ? unknown : target,
                    join ? target : unknown
                ))
            )),
            Optional.empty(),
            Optional.of("history.relation.other-side-unknown")
        );
    }
}
