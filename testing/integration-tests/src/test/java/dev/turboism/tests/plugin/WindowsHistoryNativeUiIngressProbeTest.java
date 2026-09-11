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

    @Test
    void theTerminalSummaryLineIsExactlyWhatTheHostRunnerMatches() {
        // result_file_contains compares one whole line, so a summary carrying extra fields would
        // make the runner wait out its entire result timeout after the probe had already finished.
        // The failure list therefore travels on its own line.
        assertEquals(
            "{\"type\":\"summary\",\"status\":\"PASS\"}\n",
            WindowsHistoryNativeUiIngressProbe.summaryLine(true)
        );
        assertEquals(
            "{\"type\":\"summary\",\"status\":\"FAIL\"}\n",
            WindowsHistoryNativeUiIngressProbe.summaryLine(false)
        );
        assertFalse(
            WindowsHistoryNativeUiIngressProbe.summaryLine(false).contains("failures"),
            "the failure list must not ride on the terminal line"
        );
    }

    @Test
    void aSelectionDoesNotSatisfyAStep() {
        // A native selection is an ordinary undo entry. Counting it would close the step on the
        // click that precedes the operator's real action, and the real action would land in the
        // next step's window. The host marks every selection entry insignificant, so counting only
        // significant entries is what keeps one action per step.
        final List<WindowsHistoryManagerValidationProbe.Entry> afterSelection = List.of(
            entry(0, "\u9009\u62e9\u5bf9\u8c61", false)
        );
        final List<WindowsHistoryManagerValidationProbe.Entry> afterSelectionAndEdit = List.of(
            entry(0, "\u9009\u62e9\u5bf9\u8c61", false),
            entry(1, "\u7269\u4f53\u306e\u79fb\u52d5", true)
        );

        assertEquals(0L, WindowsHistoryNativeUiIngressProbe.significantEntries(afterSelection));
        assertEquals(
            0L,
            WindowsHistoryNativeUiIngressProbe.significantEntries(afterSelection)
                - WindowsHistoryNativeUiIngressProbe.significantEntries(List.of()),
            "a selection alone must leave the significant count unchanged"
        );
        assertEquals(
            1L,
            WindowsHistoryNativeUiIngressProbe.significantEntries(afterSelectionAndEdit),
            "the operator's real edit is what advances the count"
        );
        assertEquals(
            0L,
            WindowsHistoryNativeUiIngressProbe.significantEntries(
                List.of(entry(2, "\u62d6\u52a8\u9009\u62e9", false))
            ),
            "a rubber-band selection adds no significant entry either"
        );
    }

    private static WindowsHistoryManagerValidationProbe.Entry entry(
        final int index,
        final String label,
        final boolean significant
    ) {
        return new WindowsHistoryManagerValidationProbe.Entry(
            index,
            label,
            significant,
            WindowsHistoryManagerValidationProbe.NativeDetail.degraded(
                "com.live2d.undo.GroupUndo",
                "LIMIT",
                "history.detail.node-or-depth-limit"
            )
        );
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
