package dev.turboism.adapter.cubism.editor.history;

import dev.turboism.sdk.cubism.event.CubismOperation;
import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryChange;
import dev.turboism.sdk.cubism.history.HistoryEditContext;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
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
    void aRelationToSomethingThatIsNotADeformerChildKeepsTheOperationWithoutASubject() {
        final NativeHistoryOperations.Resolution resolution = NativeHistoryOperations.resolve(
            relation(HistoryRelationChange.Kind.PART_MEMBERSHIP, "PART", true)
        );

        assertEquals(CubismOperation.SET_HIERARCHY_PARENT, resolution.operation());
        assertTrue(resolution.subjectId().isEmpty(), "only ArtMesh and Deformer children are objects");
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
    void anUndecodedEntryFallsBackToTheGenericEditorCommand() {
        assertEquals(
            CubismOperation.EXECUTE_EDITOR_COMMAND,
            NativeHistoryOperations.resolve(null).operation()
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
