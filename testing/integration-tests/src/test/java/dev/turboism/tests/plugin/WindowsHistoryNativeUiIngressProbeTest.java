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
    void aSelectionDoesNotCloseAnActionStep() {
        // The host records a selection as an ordinary undo entry, and this run recorded
        // 选择对象, 拖动选择 and 选择顶点 that way. An ACTION step
        // must be closed only by the significant entries changing, so the click that precedes an
        // action cannot satisfy it.
        final String none = WindowsHistoryNativeUiIngressProbe.significantSequence(List.of());
        final String selectionOnly = WindowsHistoryNativeUiIngressProbe.significantSequence(
            List.of(entry(0, "选择对象", false))
        );
        final String withEdit = WindowsHistoryNativeUiIngressProbe.significantSequence(
            List.of(
                entry(0, "选择对象", false),
                entry(1, "物体的移动", true)
            )
        );

        assertEquals("", none);
        assertEquals("", selectionOnly, "a selection contributes no significant entry");
        assertEquals("1:物体的移动", withEdit);
        assertFalse(
            WindowsHistoryNativeUiIngressProbe.hasMoved(ACTION, selectionOnly, 1L, none, 0L),
            "a selection advances the position and adds an entry, but is not the operator's action"
        );
        assertTrue(
            WindowsHistoryNativeUiIngressProbe.hasMoved(ACTION, withEdit, 2L, selectionOnly, 1L),
            "the operator's real edit is what closes an ACTION step"
        );
    }

    @Test
    void anUndoOrRedoStepIsClosedByThePositionAndNotByTheEntries() {
        // Undo and Redo move the cursor and add no entry, so an entry-based rule would never fire;
        // an ACTION step must not fire on the position, because a selection moves it too.
        final String significant = WindowsHistoryNativeUiIngressProbe.significantSequence(
            List.of(entry(0, "物体的移动", true))
        );

        assertFalse(
            WindowsHistoryNativeUiIngressProbe.hasMoved(ACTION, significant, 0L, significant, 1L),
            "a position change alone must not close an ACTION step"
        );
        assertTrue(
            WindowsHistoryNativeUiIngressProbe.hasMoved(UNDO, significant, 0L, significant, 1L),
            "an undo moves the position and changes no entry"
        );
        assertFalse(
            WindowsHistoryNativeUiIngressProbe.hasMoved(REDO, significant, 1L, significant, 1L),
            "an unchanged position is not a redo"
        );
        assertFalse(
            WindowsHistoryNativeUiIngressProbe.hasMoved(ACTION, null, 0L, significant, 1L),
            "a sampler that could not read the manager is not a move"
        );
    }

    @Test
    void aNavigationStepIsClosedOnlyByAMoveInItsOwnDirection() {
        // A selection also moves the position — it adds an entry — so accepting any change would
        // let the click before the operator's real action close an Undo step. A Redo must not
        // close an Undo step either, which is what the previous runs misattributed.
        final String significant = WindowsHistoryNativeUiIngressProbe.significantSequence(
            List.of(entry(0, "物体的移动", true))
        );

        assertTrue(
            WindowsHistoryNativeUiIngressProbe.hasMoved(UNDO, significant, 4L, significant, 5L),
            "an Undo is closed by the position going down"
        );
        assertFalse(
            WindowsHistoryNativeUiIngressProbe.hasMoved(UNDO, significant, 6L, significant, 5L),
            "a Redo must not close an Undo step"
        );
        assertFalse(
            WindowsHistoryNativeUiIngressProbe.hasMoved(UNDO, significant, 5L, significant, 5L),
            "an unchanged position is not an undo"
        );
        assertTrue(
            WindowsHistoryNativeUiIngressProbe.hasMoved(REDO, significant, 6L, significant, 5L),
            "a Redo is closed by the position going up"
        );
        assertFalse(
            WindowsHistoryNativeUiIngressProbe.hasMoved(REDO, significant, 4L, significant, 5L),
            "an Undo must not close a Redo step"
        );
        // A selection adds an insignificant entry and moves the position up, so it closes no
        // navigation step at all — including the Undo step it would have closed under the old
        // "any change" rule.
        assertFalse(
            WindowsHistoryNativeUiIngressProbe.hasMoved(UNDO, significant, 6L, significant, 5L),
            "a selection moves the position up and must not close an Undo step"
        );
        assertFalse(
            WindowsHistoryNativeUiIngressProbe.hasMoved(ACTION, significant, 6L, significant, 5L),
            "a selection changes no significant entry and must not close an ACTION step"
        );
    }

    @Test
    void navigationSettlesFarShorterThanAnEdit() {
        // A long settle on a navigation step is what let the operator's next keystroke land
        // inside the previous step's window.
        assertTrue(
            WindowsHistoryNativeUiIngressProbe.settleMillis(UNDO)
                < WindowsHistoryNativeUiIngressProbe.settleMillis(ACTION),
            "navigation must settle faster than an edit"
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.settleMillis(UNDO),
            WindowsHistoryNativeUiIngressProbe.settleMillis(REDO)
        );
    }

    @Test
    void anEditInsideTheWindowDoesNotHideTheUndoThatFollowsIt() {
        // Measured against the last observed position, not the window's opening one. The operator
        // made an edit inside the native-undo window (position 7 -> 8) and then undid it (8 -> 7):
        // against the opening position of 7 that undo never crosses the baseline and the step
        // never closes, which is what left the r8 run with no verdict at all.
        final String significant = WindowsHistoryNativeUiIngressProbe.significantSequence(
            List.of(entry(0, "正片叠底色 の編集", true))
        );

        assertFalse(
            WindowsHistoryNativeUiIngressProbe.hasMoved(UNDO, significant, 8L, significant, 7L),
            "an edit inside the window is not the undo"
        );
        assertTrue(
            WindowsHistoryNativeUiIngressProbe.hasMoved(UNDO, significant, 7L, significant, 8L),
            "the undo is seen against the position the edit left behind"
        );
        assertTrue(
            WindowsHistoryNativeUiIngressProbe.hasMoved(REDO, significant, 8L, significant, 7L),
            "the same holds for a redo"
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
