package dev.turboism.sdk.cubism.history;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
    void boundMoveFailsClosedWhenAProviderDoesNotImplementIt() {
        final HistorySnapshot expected = new HistorySnapshot(
            HistorySnapshot.Availability.AVAILABLE,
            1,
            2,
            0,
            List.of(),
            false,
            false,
            "document",
            "manager"
        );
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
                throw new AssertionError("legacy move must not be used");
            }
        };

        final HistoryMoveResult result = history.moveTo(expected, 0);

        assertEquals(HistoryMoveResult.Outcome.REJECTED_STALE, result.outcome());
        assertEquals(
            "history.move.binding-unsupported",
            result.diagnosticId().orElseThrow()
        );
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
}
