package dev.turboism.adapter.cubism.editor.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.history.HistoryEntry;
import java.util.List;
import org.junit.jupiter.api.Test;

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
        final var entries = List.of(
                new HistoryEntry(0, "Edit", true),
                new HistoryEntry(1, "Select", false),
                new HistoryEntry(2, "Redo", true));
        assertEquals(1, VerifiedEditorAuthoringTransactionHost.retainedPosition(entries, 2, true));
        assertEquals(3, VerifiedEditorAuthoringTransactionHost.retainedPosition(entries, 3, true));
        assertThrows(
                IllegalArgumentException.class,
                () -> VerifiedEditorAuthoringTransactionHost.retainedPosition(entries, 4, true));
    }

    @Test
    void diagnosticCarriesConcreteFailureTypeAndMessageAfterStableCode() {
        // Host learning (job e12d35db): the bare code hid the registration
        // callback cause; the diagnostic must name it while keeping the code first.
        final var cause = new IllegalStateException("Core evaluated data is unavailable: x");
        assertEquals(
                "authoring.callback-failed: IllegalStateException: Core evaluated data is unavailable: x",
                VerifiedEditorAuthoringTransactionHost.describeFailure("authoring.callback-failed", cause));
        assertEquals(
                "authoring.scope-rejected",
                VerifiedEditorAuthoringTransactionHost.describeFailure("authoring.scope-rejected", null));
        assertEquals(
                "authoring.callback-failed: NullPointerException (no message)",
                VerifiedEditorAuthoringTransactionHost.describeFailure(
                        "authoring.callback-failed", new NullPointerException()));
        assertThrows(
                NullPointerException.class, () -> VerifiedEditorAuthoringTransactionHost.describeFailure(null, cause));
    }

    @Test
    void accessFailureDiagnosticNamesSelectorWithinSdkLengthBudget() {
        // Host learning (jobs ad4d4e75 + 08e1e2e2): the diagnostic must name the
        // failing selector and stay within the SDK's 128-char receipt budget —
        // the fixed sanitized message is dropped in favour of alias/kind.
        final var access = new dev.turboism.mapping.verification.VerifiedAccessException(
                "cubism.editor-model.model-image.create",
                dev.turboism.mapping.verification.VerifiedAccessException.FailureKind.INVOCATION,
                "Verified host method execution failed safely.",
                null);
        assertEquals(
                "authoring.callback-failed: VerifiedAccess[cubism.editor-model.model-image.create/INVOCATION]",
                VerifiedEditorAuthoringTransactionHost.describeFailure("authoring.callback-failed", access));
        // Longest realistic alias plus the RESOLUTION kind must still fit.
        final var longest = new dev.turboism.mapping.verification.VerifiedAccessException(
                "cubism.editor-model.parameter-controllable-source.all-parent-deformers",
                dev.turboism.mapping.verification.VerifiedAccessException.FailureKind.RESOLUTION,
                "Verified host selector resolution failed safely.",
                null);
        final String longForm =
                VerifiedEditorAuthoringTransactionHost.describeFailure("authoring.callback-failed", longest);
        assertTrue(longForm.length() <= 128, "must fit the SDK diagnostic budget: " + longForm);
        assertTrue(longForm.endsWith("/RESOLUTION]"));
        // Non-access failures stay bounded and strip ISO control characters.
        final String bounded = VerifiedEditorAuthoringTransactionHost.describeFailure(
                "authoring.callback-failed", new IllegalStateException("x".repeat(600)));
        assertTrue(bounded.length() <= 128);
        final String withControls = VerifiedEditorAuthoringTransactionHost.describeFailure(
                "authoring.callback-failed", new IllegalStateException("line1\nline2\ttab"));
        assertFalse(withControls.chars().anyMatch(Character::isISOControl));
        assertTrue(withControls.contains("line1 line2 tab"));
        // DEL and C1 are ISO control too (review r5): the sanitize predicate must
        // match the SDK validation exactly or exotic host messages get rejected
        // at result construction again.
        final String delC1 = VerifiedEditorAuthoringTransactionHost.describeFailure(
                "authoring.callback-failed", new IllegalStateException("a\u007Fb\u0085c"));
        assertFalse(delC1.chars().anyMatch(Character::isISOControl));
        assertTrue(delC1.contains("a b c"));
    }
}
