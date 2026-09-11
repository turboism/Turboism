package dev.turboism.tests.plugin;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the operator-driven probe's verdicts.
 *
 * <p>Each operator step is judged only on what the runtime actually published for it, so these
 * cases fix what counts as the hook firing, what counts as navigation, and what must be reported
 * instead of silently passing.</p>
 */
class WindowsHistoryNativeUiIngressProbeTest {

    private static final WindowsHistoryNativeUiIngressProbe.Step ACTION =
        new WindowsHistoryNativeUiIngressProbe.Step("parts-tree-drag", "ACTION", "drag");
    private static final WindowsHistoryNativeUiIngressProbe.Step UNDO =
        new WindowsHistoryNativeUiIngressProbe.Step("native-undo", "UNDO", "ctrl-z");
    private static final WindowsHistoryNativeUiIngressProbe.Step REDO =
        new WindowsHistoryNativeUiIngressProbe.Step("native-redo", "REDO", "ctrl-y");

    @Test
    void anOperatorActionMustBeAnnouncedBeforeItIsObserved() {
        final WindowsHistoryNativeUiIngressProbe.Verdict verdict =
            WindowsHistoryNativeUiIngressProbe.checkStep(
                ACTION,
                List.of(event("before", 1, "HOST_UI"), event("on", 2, "HOST_UI"))
            );

        assertTrue(verdict.ok(), verdict.detail());
        assertEquals("action-announced-before-it-was-observed", verdict.code());
    }

    @Test
    void anOperatorActionWithoutABeforeEventIsAFailure() {
        final WindowsHistoryNativeUiIngressProbe.Verdict verdict =
            WindowsHistoryNativeUiIngressProbe.checkStep(
                ACTION,
                List.of(event("on", 2, "HOST_UI"), event("after", 3, "HOST_UI"))
            );

        assertFalse(verdict.ok());
        assertEquals("no-before-event", verdict.code());
    }

    @Test
    void anOperatorActionThatWasNeverConfirmedIsAFailure() {
        final WindowsHistoryNativeUiIngressProbe.Verdict verdict =
            WindowsHistoryNativeUiIngressProbe.checkStep(
                ACTION,
                List.of(event("before", 1, "HOST_UI"))
            );

        assertFalse(verdict.ok());
        assertEquals("no-confirmed-event", verdict.code());
    }

    @Test
    void anEditEntryEnteredDuringNavigationIsAFailure() {
        final WindowsHistoryNativeUiIngressProbe.Verdict verdict =
            WindowsHistoryNativeUiIngressProbe.checkStep(
                UNDO,
                List.of(event("before", 7, "HOST_UI"), event("on", 8, "UNDO"))
            );

        assertFalse(verdict.ok(), "a native Undo does not enter the edit entry");
        assertEquals("navigation-was-announced-as-an-edit", verdict.code());
    }

    @Test
    void nativeNavigationMustBeAttributedAndMayNotLookLikeAnEdit() {
        final WindowsHistoryNativeUiIngressProbe.Verdict undo =
            WindowsHistoryNativeUiIngressProbe.checkStep(
                UNDO, List.of(event("on", 4, "UNDO"), event("after", 5, "UNDO"))
            );
        assertTrue(undo.ok(), undo.detail());
        assertEquals("navigation-attributed-without-an-edit", undo.code());

        final WindowsHistoryNativeUiIngressProbe.Verdict redo =
            WindowsHistoryNativeUiIngressProbe.checkStep(
                REDO, List.of(event("on", 6, "REDO"), event("after", 7, "REDO"))
            );
        assertTrue(redo.ok(), redo.detail());

        final WindowsHistoryNativeUiIngressProbe.Verdict unattributed =
            WindowsHistoryNativeUiIngressProbe.checkStep(
                UNDO, List.of(event("on", 4, "HOST_UI"), event("after", 5, "HOST_UI"))
            );
        assertFalse(unattributed.ok(), "an un-attributed navigation is not evidence of Undo");
        assertEquals("navigation-not-attributed", unattributed.code());

        final WindowsHistoryNativeUiIngressProbe.Verdict silent =
            WindowsHistoryNativeUiIngressProbe.checkStep(UNDO, List.of());
        assertFalse(silent.ok());
        assertEquals("navigation-not-attributed", silent.code());
    }

    @Test
    void theRecordedEventCarriesThePhaseTheNameAndTheOrigin() {
        final String json = event("before", 12, "HOST_UI").json("canvas-move");

        assertTrue(json.contains("\"step\":\"canvas-move\""), json);
        assertTrue(json.contains("\"phase\":\"before\""), json);
        assertTrue(json.contains("\"sequence\":12"), json);
        assertTrue(json.contains("\"label\":\"Add Part\""), json);
        assertTrue(json.endsWith("}\n"), json);
    }

    private static WindowsHistoryNativeUiIngressProbe.Observed event(
        final String phase,
        final long sequence,
        final String origin
    ) {
        return new WindowsHistoryNativeUiIngressProbe.Observed(
            phase,
            sequence,
            "EXECUTE_EDITOR_COMMAND",
            origin,
            "",
            "Add Part",
            "AWT-EventQueue-0",
            "2026-01-01T00:00:00Z"
        );
    }
}
