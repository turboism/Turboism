package dev.turboism.adapter.cubism.editor.transaction;

import dev.turboism.sdk.cubism.history.HistoryEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class VerifiedEditorAuthoringTransactionHostTest {
    @Test
    void reproducesExactManagerInsignificantTailBoundary() {
        final var insignificant = List.of(new HistoryEntry(0, "Select", false), new HistoryEntry(1, "Select", false));
        assertEquals(0, VerifiedEditorAuthoringTransactionHost.retainedPosition(insignificant, 0, true));
        assertEquals(1, VerifiedEditorAuthoringTransactionHost.retainedPosition(insignificant, 1, true));
        assertEquals(0, VerifiedEditorAuthoringTransactionHost.retainedPosition(insignificant, 2, true));
        assertEquals(2, VerifiedEditorAuthoringTransactionHost.retainedPosition(insignificant, 2, false));
    }

    @Test
    void stopsAtLastSignificantEntryAndIgnoresRedoTail() {
        final var entries = List.of(new HistoryEntry(0, "Edit", true),
            new HistoryEntry(1, "Select", false), new HistoryEntry(2, "Redo", true));
        assertEquals(1, VerifiedEditorAuthoringTransactionHost.retainedPosition(entries, 2, true));
        assertEquals(3, VerifiedEditorAuthoringTransactionHost.retainedPosition(entries, 3, true));
        assertThrows(IllegalArgumentException.class,
            () -> VerifiedEditorAuthoringTransactionHost.retainedPosition(entries, 4, true));
    }
}
