package dev.turboism.plugin.parameterbatchtransfer.ui;

import dev.turboism.plugin.parameterbatchtransfer.b1.domain.BoundParameterSnapshot;
import dev.turboism.plugin.parameterbatchtransfer.service.ParameterBatchTransferService;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ParameterBindingFamily;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchTransferDialogTargetSelectionTest {
    private final ParameterBatchTransferService service = new ParameterBatchTransferService();
    private final ParameterBatchTransferService.Session session = session();

    @Test
    void targetsStartAtTheirOwnParameterAndSelfDoesNotReserve() {
        final List<ParameterId> normalized = BatchTransferDialog.normalizeTargets(
            service, session, List.of(new ParameterId("a"), new ParameterId("b"), new ParameterId("c"))
        );

        assertEquals(List.of(new ParameterId("a"), new ParameterId("b"), new ParameterId("c")), normalized);
        assertTrue(BatchTransferDialog.availableTargets(
            service, session, session.bound().get(0), Set.of()
        ).stream().anyMatch(candidate -> candidate.parameterId().equals(new ParameterId("a"))));
    }

    @Test
    void earlierNonNoOpSelectionHidesDestinationAndLaterConflictResetsToSelf() {
        final List<ParameterId> normalized = BatchTransferDialog.normalizeTargets(
            service, session, List.of(new ParameterId("d"), new ParameterId("d"), new ParameterId("c"))
        );

        assertEquals(List.of(new ParameterId("d"), new ParameterId("b"), new ParameterId("c")), normalized);
        assertFalse(BatchTransferDialog.availableTargets(
            service, session, session.bound().get(1), Set.of(new ParameterId("d"))
        ).stream().anyMatch(candidate -> candidate.parameterId().equals(new ParameterId("d"))));
    }

    @Test
    void releasingEarlierDestinationMakesItAvailableAgain() {
        final List<ParameterId> normalized = BatchTransferDialog.normalizeTargets(
            service, session, List.of(new ParameterId("a"), new ParameterId("d"), new ParameterId("c"))
        );

        assertEquals(List.of(new ParameterId("a"), new ParameterId("d"), new ParameterId("c")), normalized);
        assertTrue(BatchTransferDialog.availableTargets(
            service, session, session.bound().get(1), Set.of()
        ).stream().anyMatch(candidate -> candidate.parameterId().equals(new ParameterId("d"))));
    }

    private static ParameterBatchTransferService.Session session() {
        final List<BoundParameterSnapshot> bound = List.of(
            bound("a", "A"),
            bound("b", "B"),
            bound("c", "C")
        );
        final List<BoundParameterSnapshot> candidates = List.of(
            candidate("a", "A"),
            candidate("b", "B"),
            candidate("c", "C"),
            candidate("d", "D")
        );
        return new ParameterBatchTransferService.Session(bound, candidates);
    }

    private static BoundParameterSnapshot bound(final String id, final String label) {
        return new BoundParameterSnapshot(
            new ParameterId(id), label, label, "", false, false,
            ParameterBindingFamily.KEYFORM_GRID, null
        );
    }

    private static BoundParameterSnapshot candidate(final String id, final String label) {
        return new BoundParameterSnapshot(
            new ParameterId(id), label, label, "", false, false, null, null
        );
    }
}
