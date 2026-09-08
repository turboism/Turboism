package dev.turboism.sdk.cubism.history;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CubismHistoryContractTest {

    @Test
    void snapshotDefensivelyCopiesEntriesAndModelsCursorPartition() {
        final ArrayList<HistoryEntry> entries = new ArrayList<>(List.of(
            new HistoryEntry(0, "First", true),
            new HistoryEntry(1, "Second", true)
        ));
        final HistorySnapshot snapshot = new HistorySnapshot(
            HistorySnapshot.Availability.AVAILABLE,
            4,
            7,
            1,
            entries,
            true,
            true
        );

        entries.clear();

        assertEquals(2, snapshot.entries().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.entries().clear());
    }

    @Test
    void historyEntriesExposeOptionalStructuredActionDetails() {
        final HistoryEntry nativeEntry = new HistoryEntry(0, "Native edit", true);
        final HistoryEntry turboismEntry = new HistoryEntry(
            1,
            "Turboism: Set Parameter Value",
            true,
            java.util.Optional.of(new HistoryAction(
                HistoryAction.Kind.SET_PARAMETER_VALUE,
                "PARAMETER",
                "ParamAngleX",
                "value",
                java.util.Optional.of("0.0"),
                java.util.Optional.of("-19.8"),
                HistoryAction.DetailLevel.FULL
            ))
        );

        assertEquals(HistoryAction.DetailLevel.LABEL_ONLY, nativeEntry.detailLevel());
        assertEquals(HistoryAction.DetailLevel.FULL, turboismEntry.detailLevel());
        assertEquals("ParamAngleX", turboismEntry.action().orElseThrow().targetId());
        assertEquals("-19.8", turboismEntry.action().orElseThrow().after().orElseThrow());
    }

    @Test
    void historyEntriesCarryValidatedStableIdsAndOptionalTransactionIds() {
        final HistoryEntry entry = new HistoryEntry(
            0,
            "Adjust eye glues",
            true,
            Optional.empty(),
            Optional.of(new HistoryEntryId("history-entry-1")),
            Optional.of("transaction-1")
        );

        assertEquals("history-entry-1", entry.entryId().orElseThrow().value());
        assertEquals("transaction-1", entry.transactionId().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> new HistoryEntryId(" "));
        assertThrows(IllegalArgumentException.class, () -> new HistoryEntry(
            0,
            "Invalid",
            true,
            Optional.empty(),
            Optional.empty(),
            Optional.of("transaction-without-entry")
        ));
    }

    @Test
    void rejectsInvalidCursorAndNonContiguousIndexes() {
        assertThrows(IllegalArgumentException.class, () -> new HistorySnapshot(
            HistorySnapshot.Availability.AVAILABLE,
            0,
            0,
            2,
            List.of(new HistoryEntry(0, "Only", true)),
            false,
            false
        ));
        assertThrows(IllegalArgumentException.class, () -> new HistorySnapshot(
            HistorySnapshot.Availability.AVAILABLE,
            0,
            0,
            0,
            List.of(new HistoryEntry(1, "Wrong", true)),
            false,
            true
        ));
    }

    @Test
    void undoAndRedoBridgeBindingSnapshotsToLegacyThreeArgumentProviders() {
        final HistorySnapshot expected = new HistorySnapshot(
            HistorySnapshot.Availability.AVAILABLE,
            4,
            7,
            3,
            List.of(
                new HistoryEntry(0, "First", true),
                new HistoryEntry(1, "Second", true),
                new HistoryEntry(2, "Third", true),
                new HistoryEntry(3, "Fourth", true),
                new HistoryEntry(4, "Fifth", true)
            ),
            true,
            true,
            "document",
            "manager"
        );
        final ArrayList<String> moves = new ArrayList<>();
        final CubismHistory history = new CubismHistory() {
            @Override
            public HistorySnapshot snapshot() {
                return expected;
            }

            @Override
            public HistoryMoveResult moveTo(
                final long expectedGeneration,
                final long expectedRevision,
                final int position
            ) {
                moves.add(expectedGeneration + ":" + expectedRevision + ":" + position);
                return new HistoryMoveResult(
                    HistoryMoveResult.Outcome.MOVED,
                    expected,
                    java.util.Optional.empty()
                );
            }
        };

        assertEquals(HistoryMoveResult.Outcome.MOVED, history.undo(2).outcome());
        assertEquals(HistoryMoveResult.Outcome.MOVED, history.redo(4).outcome());
        assertEquals(List.of("4:7:1", "4:7:5"), moves);
    }


    @Test
    void unavailableProviderFailsClosed() {
        final CubismHistory history = CubismHistory.unavailable();

        assertEquals(HistorySnapshot.Availability.UNAVAILABLE, history.snapshot().availability());
        assertEquals(
            HistoryMoveResult.Outcome.UNAVAILABLE,
            history.moveTo(0, 0, 0).outcome()
        );
        assertEquals(
            "history.provider.unavailable",
            history.moveTo(0, 0, 0).diagnosticId().orElseThrow()
        );
    }

    @Test
    void semanticHistoryDetailsAreImmutableAndPreserveTrustedFacts() {
        final ArrayList<HistoryTarget> targets = new ArrayList<>(List.of(
            new HistoryTarget("PARAMETER", Optional.of("ParamAngleX"), Optional.of("Angle X"))
        ));
        final ArrayList<HistoryChange> changes = new ArrayList<>(List.of(
            HistoryChange.set(0, "value", "0.0", "-19.8")
        ));
        final HistoryEntryDetail detail = new HistoryEntryDetail(
            "Set Angle X",
            HistoryAction.DetailLevel.FULL,
            HistoryOrigin.turboism("runtime", "cubism.parameter.set"),
            targets,
            changes,
            Optional.empty(),
            Optional.empty()
        );
        final HistoryEntry entry = new HistoryEntry(
            0,
            "Turboism: Set Parameter Value",
            true,
            Optional.of(new HistoryAction(
                HistoryAction.Kind.SET_PARAMETER_VALUE,
                "PARAMETER",
                "ParamAngleX",
                "value",
                Optional.of("0.0"),
                Optional.of("-19.8"),
                HistoryAction.DetailLevel.FULL
            )),
            Optional.of(new HistoryEntryId("entry-1")),
            Optional.of("transaction-1"),
            detail
        );

        targets.clear();
        changes.clear();

        assertEquals(1, entry.detail().targets().size());
        assertEquals("PARAMETER", entry.detail().targets().get(0).type());
        assertEquals("ParamAngleX", entry.detail().targets().get(0).id().orElseThrow());
        assertEquals(1, entry.detail().changes().size());
        assertEquals(HistoryOrigin.Kind.TURBOISM, entry.detail().origin().kind());
        assertEquals(HistoryAction.DetailLevel.FULL, entry.detailLevel());
        assertThrows(UnsupportedOperationException.class, () -> entry.detail().targets().clear());
    }

    @Test
    void semanticHistoryDetailsRejectClaimsThatAreNotInternallyConsistent() {
        assertThrows(IllegalArgumentException.class, () -> new HistoryOrigin(
            HistoryOrigin.Kind.HOST_UNATTRIBUTED,
            Optional.of("guessed-plugin"),
            Optional.empty()
        ));
        assertThrows(IllegalArgumentException.class, () -> new HistoryEntryDetail(
            "Invalid label-only detail",
            HistoryAction.DetailLevel.LABEL_ONLY,
            HistoryOrigin.hostUnattributed(),
            List.of(new HistoryTarget("PART", Optional.of("PartArm"), Optional.empty())),
            List.of(),
            Optional.empty(),
            Optional.of("history.detail.invalid")
        ));
        assertThrows(IllegalArgumentException.class, () -> new HistoryEntryDetail(
            "Invalid full detail",
            HistoryAction.DetailLevel.FULL,
            HistoryOrigin.hostUnattributed(),
            List.of(new HistoryTarget("PARAMETER", Optional.of("ParamAngleX"), Optional.empty())),
            List.of(new HistoryChange(
                HistoryChange.Operation.SET,
                Optional.of(0),
                Optional.of("value"),
                Optional.of("0.0"),
                Optional.empty()
            )),
            Optional.empty(),
            Optional.empty()
        ));
        assertThrows(IllegalArgumentException.class, () -> new HistoryEntryDetail(
            "Invalid partial detail",
            HistoryAction.DetailLevel.PARTIAL,
            HistoryOrigin.hostUnattributed(),
            List.of(new HistoryTarget("PART", Optional.of("PartArm"), Optional.empty())),
            List.of(),
            Optional.empty(),
            Optional.empty()
        ));
    }

    @Test
    void groupedHistoryDetailsExposeObservedCountsAndTruncation() {
        final HistoryEntryDetail child = HistoryEntryDetail.labelOnly(
            "Unknown child",
            HistoryOrigin.hostUnattributed(),
            "history.detail.class-unsupported"
        );
        final HistoryEntryDetail grouped = new HistoryEntryDetail(
            "Grouped edit",
            HistoryAction.DetailLevel.PARTIAL,
            HistoryOrigin.hostUnattributed(),
            List.of(),
            List.of(),
            Optional.of(new HistoryGroup(Optional.of("group-1"), 3, List.of(child), true)),
            Optional.of("history.detail.node-limit")
        );

        assertEquals(1, grouped.group().orElseThrow().children().size());
        assertEquals(3, grouped.group().orElseThrow().observedChildCount());
        assertEquals("group-1", grouped.group().orElseThrow().groupId().orElseThrow());
        assertEquals(true, grouped.group().orElseThrow().truncated());
    }


    @Test
    void changeContextsDistinguishDefaultFormsAndCompleteKeyforms() {
        final HistoryTarget angleX = new HistoryTarget(
            "PARAMETER",
            Optional.of("ParamAngleX"),
            Optional.of("Angle X")
        );
        final HistoryTarget angleY = new HistoryTarget(
            "PARAMETER",
            Optional.of("ParamAngleY"),
            Optional.of("Angle Y")
        );
        final ArrayList<HistoryParameterCoordinate> coordinates = new ArrayList<>(List.of(
            new HistoryParameterCoordinate(angleX, "30"),
            new HistoryParameterCoordinate(angleY, "-10")
        ));
        final HistoryEditContext keyform = new HistoryEditContext(
            HistoryEditContext.Kind.KEYFORM,
            Optional.of("form-1"),
            coordinates
        );
        final HistoryEditContext defaultForm = new HistoryEditContext(
            HistoryEditContext.Kind.DEFAULT_FORM,
            Optional.of("default-form"),
            List.of()
        );

        coordinates.clear();

        assertEquals(2, keyform.coordinates().size());
        assertEquals("ParamAngleX", keyform.coordinates().get(0).parameter().id().orElseThrow());
        assertEquals("30", keyform.coordinates().get(0).value());
        assertEquals(HistoryEditContext.Kind.DEFAULT_FORM, defaultForm.kind());
        assertThrows(UnsupportedOperationException.class, () -> keyform.coordinates().clear());
    }

    @Test
    void changeContextsRejectInvalidCoordinatesAndScopeCombinations() {
        final HistoryTarget artMesh = new HistoryTarget(
            "ART_MESH",
            Optional.of("ArtMesh1"),
            Optional.of("Face shadow")
        );
        final HistoryTarget parameter = new HistoryTarget(
            "PARAMETER",
            Optional.of("ParamAngleX"),
            Optional.of("Angle X")
        );

        assertThrows(IllegalArgumentException.class, () ->
            new HistoryParameterCoordinate(artMesh, "30")
        );
        assertThrows(IllegalArgumentException.class, () ->
            new HistoryEditContext(
                HistoryEditContext.Kind.DEFAULT_FORM,
                Optional.empty(),
                List.of(new HistoryParameterCoordinate(parameter, "30"))
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            new HistoryEditContext(
                HistoryEditContext.Kind.OBJECT,
                Optional.of("form-not-valid-here"),
                List.of()
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            new HistoryEditContext(
                HistoryEditContext.Kind.KEYFORM,
                Optional.empty(),
                List.of(
                    new HistoryParameterCoordinate(parameter, "30"),
                    new HistoryParameterCoordinate(parameter, "-30")
                )
            )
        );
    }

    @Test
    void fullDetailsRequireKnownContextAndKeyformCoordinates() {
        final HistoryTarget artMesh = new HistoryTarget(
            "ART_MESH",
            Optional.of("ArtMesh1"),
            Optional.of("Face shadow")
        );
        final HistoryChange unknownContext = new HistoryChange(
            HistoryChange.Operation.SET,
            Optional.of(0),
            Optional.of("multiplyColor"),
            Optional.of("#ffffff"),
            Optional.of("#66ccff")
        );
        final HistoryChange emptyKeyform = new HistoryChange(
            HistoryChange.Operation.SET,
            Optional.of(0),
            Optional.of("multiplyColor"),
            Optional.of("#ffffff"),
            Optional.of("#66ccff"),
            new HistoryEditContext(
                HistoryEditContext.Kind.KEYFORM,
                Optional.of("form-1"),
                List.of()
            )
        );

        assertEquals(HistoryEditContext.Kind.UNKNOWN, unknownContext.context().kind());
        assertThrows(IllegalArgumentException.class, () -> fullDetail(artMesh, unknownContext));
        assertThrows(IllegalArgumentException.class, () -> fullDetail(artMesh, emptyKeyform));
    }

    private static HistoryEntryDetail fullDetail(
        final HistoryTarget target,
        final HistoryChange change
    ) {
        return new HistoryEntryDetail(
            "Semantic change",
            HistoryAction.DetailLevel.FULL,
            HistoryOrigin.hostUnattributed(),
            List.of(target),
            List.of(change),
            Optional.empty(),
            Optional.empty()
        );
    }
}
