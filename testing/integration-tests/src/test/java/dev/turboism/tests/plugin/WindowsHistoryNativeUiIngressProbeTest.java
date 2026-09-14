package dev.turboism.tests.plugin;

import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void anUnmappedStepHasNoActor() {
        // The steps without a reviewed actor must report "none" so the step keeps its full
        // operator window; an actor that cannot run reports unresolved/failed instead.
        final WindowsHistoryNativeUiIngressProbe probe = new WindowsHistoryNativeUiIngressProbe();

        for (final String id : List.of(
            "unknown"
        )) {
            assertEquals(
                "none",
                probe.act(new WindowsHistoryNativeUiIngressProbe.Step(id, "ACTION", "x"), ""),
                id
            );
        }
    }

    @Test
    void anActorThatCannotResolveItsControlIsRecordedRatherThanThrown() {
        // A test JVM owns no windows, so the canvas actor must come back unresolved and the
        // accelerator actor must fail through the actor's own Throwable guard — neither may
        // throw or pretend the step is done.
        final WindowsHistoryNativeUiIngressProbe probe = new WindowsHistoryNativeUiIngressProbe();

        final String canvas =
            probe.act(new WindowsHistoryNativeUiIngressProbe.Step("canvas-move", "ACTION", "x"), "");
        assertTrue(
            canvas.startsWith("unresolved:") || canvas.startsWith("failed:"),
            "an automated actor with no canvas to drive must be recorded, got " + canvas
        );

        final String parameter =
            probe.act(new WindowsHistoryNativeUiIngressProbe.Step("native-parameter", "ACTION", "x"), "");
        assertTrue(
            parameter.startsWith("unresolved:") || parameter.startsWith("failed:"),
            "an automated actor with no slider to drive must be recorded, got " + parameter
        );

        final String deform =
            probe.act(new WindowsHistoryNativeUiIngressProbe.Step("canvas-deform", "ACTION", "x"), "");
        assertTrue(
            deform.startsWith("unresolved:") || deform.startsWith("failed:"),
            "the canvas actor with no canvas must be recorded, got " + deform
        );

        final String color =
            probe.act(new WindowsHistoryNativeUiIngressProbe.Step("native-color", "ACTION", "x"), "");
        assertTrue(
            color.startsWith("unresolved:") || color.startsWith("failed:"),
            "an automated actor with no colour field to drive must be recorded, got " + color
        );

        final String undo =
            probe.act(new WindowsHistoryNativeUiIngressProbe.Step("native-undo", "UNDO", "x"), "");
        assertFalse("none".equals(undo), "a mapped step must attempt its actor");
        assertFalse(undo.isBlank(), "the actor outcome is evidence and must not be empty");
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

    @Test
    void inspectorContentSelectionUsesTheSelectedLowerStackedTab() {
        // Mirrors r77: the dock is one 159x924 column, while the selected Inspector tab is
        // below the upper tool-details pane at about y=530 and its content starts at y=551.
        final Rectangle dock = new Rectangle(100, 66, 159, 924);
        final Rectangle selectedTab = new Rectangle(100, 530, 78, 21);
        final Object upperColumnRoot = new Object();
        final Object upperToolDetails = new Object();
        final Object inspectorContent = new Object();
        final WindowsHistoryNativeUiIngressProbe.InspectorContentCandidate selected =
            WindowsHistoryNativeUiIngressProbe.selectInspectorContentCandidate(
                List.of(
                    new WindowsHistoryNativeUiIngressProbe.InspectorContentCandidate(
                        upperColumnRoot, new Rectangle(100, 66, 159, 924), true, 100
                    ),
                    new WindowsHistoryNativeUiIngressProbe.InspectorContentCandidate(
                        upperToolDetails, new Rectangle(100, 66, 159, 459), true, 20
                    ),
                    new WindowsHistoryNativeUiIngressProbe.InspectorContentCandidate(
                        inspectorContent,
                        new Rectangle(100, selectedTab.y + selectedTab.height, 159, 438),
                        true,
                        8
                    )
                ),
                selectedTab,
                dock
            );

        assertSame(inspectorContent, selected.widget());
    }

    @Test
    void inspectorContentSelectionRejectsHiddenRootAndMissingCandidates() {
        final Rectangle dock = new Rectangle(100, 66, 159, 924);
        final Rectangle selectedTab = new Rectangle(100, 530, 78, 21);
        final Object hiddenRoot = new Object();
        final Object visibleContent = new Object();

        final WindowsHistoryNativeUiIngressProbe.InspectorContentCandidate selected =
            WindowsHistoryNativeUiIngressProbe.selectInspectorContentCandidate(
                List.of(
                    new WindowsHistoryNativeUiIngressProbe.InspectorContentCandidate(
                        hiddenRoot, new Rectangle(100, 551, 159, 438), false, 100
                    ),
                    new WindowsHistoryNativeUiIngressProbe.InspectorContentCandidate(
                        visibleContent, new Rectangle(100, 551, 159, 438), true, 4
                    )
                ),
                selectedTab,
                dock
            );

        assertSame(visibleContent, selected.widget(), "a hidden high-score root is not usable");
        assertNull(
            WindowsHistoryNativeUiIngressProbe.selectInspectorContentCandidate(
                List.of(
                    new WindowsHistoryNativeUiIngressProbe.InspectorContentCandidate(
                        hiddenRoot, new Rectangle(100, 551, 159, 438), false, 100
                    )
                ),
                selectedTab,
                dock
            ),
            "no visible candidate must leave the inspector root unresolved"
        );
    }

    @Test
    void menuNavigationFallsBackOnlyAfterMeasuredUnchangedSamples() {
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ShortcutResolution.DELIVERED,
            WindowsHistoryNativeUiIngressProbe.shortcutResolution("UNDO", 8L, 7L)
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ShortcutResolution.WRONG_DIRECTION,
            WindowsHistoryNativeUiIngressProbe.shortcutResolution("UNDO", 8L, 9L),
            "a shortcut that moved the cursor the wrong way still must not be repeated"
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ShortcutResolution.FALLBACK,
            WindowsHistoryNativeUiIngressProbe.shortcutResolution("UNDO", 8L, 8L)
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ShortcutResolution.DELIVERED,
            WindowsHistoryNativeUiIngressProbe.shortcutResolution("REDO", 7L, 8L)
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ShortcutResolution.WRONG_DIRECTION,
            WindowsHistoryNativeUiIngressProbe.shortcutResolution("REDO", 8L, 7L)
        );
    }

    @Test
    void menuNavigationDoesNotFallbackWhenPositionEvidenceIsUnknown() {
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ShortcutResolution.UNKNOWN,
            WindowsHistoryNativeUiIngressProbe.shortcutResolution("UNDO", -1L, List.of(7L))
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ShortcutResolution.UNAVAILABLE,
            WindowsHistoryNativeUiIngressProbe.shortcutResolution(
                "UNDO", 8L, Arrays.asList(null, null)
            )
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ShortcutResolution.UNAVAILABLE,
            WindowsHistoryNativeUiIngressProbe.shortcutResolution(
                "UNDO", 8L, Arrays.asList(null, 8L)
            ),
            "one unavailable sample still leaves a possible menu action unobserved"
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ShortcutResolution.FALLBACK,
            WindowsHistoryNativeUiIngressProbe.shortcutResolution("UNDO", 8L, List.of(8L, 8L)),
            "only measured unchanged samples authorize Robot fallback"
        );
    }

    @Test
    void parameterValueChangeIsEvidenceEvenWhenHistoryAdmissionIsNotObserved() {
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot before =
            WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot.available(
                "model-A",
                List.of(new WindowsHistoryNativeUiIngressProbe.ParameterValueSample("ParamAngleX", 0.0f))
            );
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot after =
            WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot.available(
                "model-A",
                List.of(new WindowsHistoryNativeUiIngressProbe.ParameterValueSample("ParamAngleX", 12.5f))
            );

        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterStateOutcome.CHANGED,
            WindowsHistoryNativeUiIngressProbe.compareParameterState(before, after)
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterHistoryAdmission.NOT_OBSERVED,
            WindowsHistoryNativeUiIngressProbe.parameterHistoryAdmission("", ""),
            "a changed value is not relabelled as unchanged just because no significant Undo entry appeared"
        );
    }

    @Test
    void parameterValueEvidenceRejectsAnUnchangedReadback() {
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot before =
            WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot.available(
                "model-A",
                List.of(new WindowsHistoryNativeUiIngressProbe.ParameterValueSample("ParamAngleX", 0.0f))
            );
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot after =
            WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot.available(
                "model-A",
                List.of(new WindowsHistoryNativeUiIngressProbe.ParameterValueSample("ParamAngleX", 0.0f))
            );

        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterStateOutcome.UNCHANGED,
            WindowsHistoryNativeUiIngressProbe.compareParameterState(before, after)
        );
    }

    @Test
    void parameterReadbackFailsClosedForModelSwitchAndUnavailableReads() {
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot before =
            WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot.available(
                "model-A",
                List.of(new WindowsHistoryNativeUiIngressProbe.ParameterValueSample("ParamAngleX", 0.0f))
            );
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot switched =
            WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot.available(
                "model-B",
                List.of(new WindowsHistoryNativeUiIngressProbe.ParameterValueSample("ParamAngleX", 12.5f))
            );

        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterStateOutcome.MODEL_CHANGED,
            WindowsHistoryNativeUiIngressProbe.compareParameterState(before, switched)
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterStateOutcome.UNAVAILABLE,
            WindowsHistoryNativeUiIngressProbe.compareParameterState(
                before,
                WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot.unavailable("read-failed")
            )
        );
    }

    @Test
    void parameterLifecycleEvidenceKeepsRelatedAndUnrelatedCallbacksSeparate() {
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot before = parameterSnapshot(
            "model-A", 0.0f);
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot after = parameterSnapshot(
            "model-A", 12.5f);
        final List<WindowsHistoryNativeUiIngressProbe.ParameterLifecycleEvent> callbacks = List.of(
            lifecycle(1L, "before", "ParamAngleX", 0.0f, 12.5f, "EDT"),
            lifecycle(2L, "on", "ParamAngleX", 0.0f, 12.5f, "callback"),
            lifecycle(3L, "after", "ParamAngleX", null, 12.5f, "callback"),
            lifecycle(4L, "on", "ParamOpacity", 1.0f, 0.8f, "callback")
        );

        assertEquals(
            3,
            WindowsHistoryNativeUiIngressProbe.relevantParameterLifecycle(
                callbacks, Set.of("ParamAngleX")
            ).size()
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterLifecycleStatus.COMPLETE,
            WindowsHistoryNativeUiIngressProbe.parameterLifecycleStatus(
                callbacks, before, after, Set.of("ParamAngleX"), true
            )
        );
        assertTrue(
            callbacks.get(1).json(Set.of("ParamAngleX")).contains("\"sequence\":2"),
            "callback sequence must be retained for evidence review"
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterLifecycleStatus.OBSERVED_UNRELATED,
            WindowsHistoryNativeUiIngressProbe.parameterLifecycleStatus(
                List.of(lifecycle(1L, "on", "ParamOpacity", 1.0f, 0.8f, "callback")),
                Set.of(),
                false
            )
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterLifecycleStatus.MISSING,
            WindowsHistoryNativeUiIngressProbe.parameterLifecycleStatus(
                List.of(),
                Set.of("ParamAngleX"),
                true
            )
        );

        final WindowsHistoryNativeUiIngressProbe probe =
            new WindowsHistoryNativeUiIngressProbe();
        assertEquals(
            2.5f,
            probe.beforeSetParameterValue(null, 2.5f),
            0.0f,
            "before callback must return the requested value unchanged"
        );
    }

    @Test
    void parameterLifecycleAcceptsAContinuousNativeValueChain() {
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot before = parameterSnapshot(
            "model-A", 0.0f);
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot after = parameterSnapshot(
            "model-A", 12.5f);
        final List<WindowsHistoryNativeUiIngressProbe.ParameterLifecycleEvent> callbacks = List.of(
            lifecycle(1L, "before", "ParamAngleX", 0.0f, 12.5f, "callback"),
            lifecycle(2L, "on", "ParamAngleX", 0.0f, 5.0f, "callback"),
            lifecycle(3L, "on", "ParamAngleX", 5.0f, 12.5f, "callback"),
            lifecycle(4L, "after", "ParamAngleX", null, 12.5f, "callback")
        );

        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterLifecycleStatus.COMPLETE,
            WindowsHistoryNativeUiIngressProbe.parameterLifecycleStatus(
                callbacks, before, after, Set.of("ParamAngleX"), true
            )
        );
    }

    @Test
    void parameterLifecycleRejectsReversedAndUnrelatedValues() {
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot before = parameterSnapshot(
            "model-A", 0.0f);
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot after = parameterSnapshot(
            "model-A", 12.5f);

        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterLifecycleStatus.INCOMPLETE,
            WindowsHistoryNativeUiIngressProbe.parameterLifecycleStatus(
                List.of(
                    lifecycle(1L, "after", "ParamAngleX", null, 999.0f, "callback"),
                    lifecycle(2L, "on", "ParamAngleX", 999.0f, 999.0f, "callback"),
                    lifecycle(3L, "before", "ParamAngleX", null, null, "callback")
                ),
                before,
                after,
                Set.of("ParamAngleX"),
                true
            )
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterLifecycleStatus.INCOMPLETE,
            WindowsHistoryNativeUiIngressProbe.parameterLifecycleStatus(
                List.of(
                    lifecycle(1L, "before", "ParamAngleX", 0.0f, 12.5f, "callback"),
                    lifecycle(2L, "on", "ParamAngleX", 0.0f, 999.0f, "callback"),
                    lifecycle(3L, "after", "ParamAngleX", null, 12.5f, "callback")
                ),
                before,
                after,
                Set.of("ParamAngleX"),
                true
            )
        );
    }

    @Test
    void parameterLifecycleRejectsNoChangeMissingPhaseAndMissingValues() {
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot before = parameterSnapshot(
            "model-A", 0.0f);
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot after = parameterSnapshot(
            "model-A", 12.5f);

        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterLifecycleStatus.INCOMPLETE,
            WindowsHistoryNativeUiIngressProbe.parameterLifecycleStatus(
                List.of(
                    lifecycle(1L, "before", "ParamAngleX", 0.0f, 12.5f, "callback"),
                    lifecycle(2L, "on", "ParamAngleX", 0.0f, 0.0f, "callback"),
                    lifecycle(3L, "after", "ParamAngleX", null, 12.5f, "callback")
                ),
                before,
                after,
                Set.of("ParamAngleX"),
                true
            )
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterLifecycleStatus.INCOMPLETE,
            WindowsHistoryNativeUiIngressProbe.parameterLifecycleStatus(
                List.of(
                    lifecycle(1L, "before", "ParamAngleX", 0.0f, 12.5f, "callback"),
                    lifecycle(2L, "after", "ParamAngleX", null, 12.5f, "callback")
                ),
                before,
                after,
                Set.of("ParamAngleX"),
                true
            )
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterLifecycleStatus.INCOMPLETE,
            WindowsHistoryNativeUiIngressProbe.parameterLifecycleStatus(
                List.of(
                    lifecycle(1L, "before", "ParamAngleX", null, null, "callback"),
                    lifecycle(2L, "on", "ParamAngleX", 0.0f, 12.5f, "callback"),
                    lifecycle(3L, "after", "ParamAngleX", null, 12.5f, "callback")
                ),
                before,
                after,
                Set.of("ParamAngleX"),
                true
            )
        );
    }

    @Test
    void parameterLifecycleWithoutReadbackCannotBeComplete() {
        final List<WindowsHistoryNativeUiIngressProbe.ParameterLifecycleEvent> callbacks = List.of(
            lifecycle(1L, "before", "ParamAngleX", 0.0f, 12.5f, "callback"),
            lifecycle(2L, "on", "ParamAngleX", 0.0f, 12.5f, "callback"),
            lifecycle(3L, "after", "ParamAngleX", null, 12.5f, "callback")
        );

        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterLifecycleStatus.UNAVAILABLE,
            WindowsHistoryNativeUiIngressProbe.parameterLifecycleStatus(
                callbacks, Set.of("ParamAngleX"), true
            )
        );
    }

    @Test
    void parameterLifecycleSampleLossIsExplicitlyIncomplete() {
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot before = parameterSnapshot(
            "model-A", 0.0f);
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot after = parameterSnapshot(
            "model-A", 12.5f);
        final List<WindowsHistoryNativeUiIngressProbe.ParameterLifecycleEvent> callbacks = List.of(
            lifecycle(1L, "before", "ParamAngleX", 0.0f, 12.5f, "callback"),
            lifecycle(2L, "on", "ParamAngleX", 0.0f, 12.5f, "callback"),
            lifecycle(3L, "after", "ParamAngleX", null, 12.5f, "callback")
        );

        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterLifecycleStatus.INCOMPLETE,
            WindowsHistoryNativeUiIngressProbe.parameterLifecycleStatus(
                callbacks, before, after, Set.of("ParamAngleX"), true, false
            )
        );
    }

    @Test
    void parameterLifecycleReportsCallbackModelCorrelationAsUnavailable() {
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot before = parameterSnapshot(
            "model-A", 0.0f);
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot after = parameterSnapshot(
            "model-A", 12.5f);
        final WindowsHistoryNativeUiIngressProbe.ParameterLifecycleAssessment assessment =
            WindowsHistoryNativeUiIngressProbe.assessParameterLifecycle(
                List.of(
                    lifecycle(1L, "before", "ParamAngleX", 0.0f, 12.5f, "callback"),
                    lifecycle(2L, "on", "ParamAngleX", 0.0f, 12.5f, "callback"),
                    lifecycle(3L, "after", "ParamAngleX", null, 12.5f, "callback")
                ),
                before,
                after,
                Set.of("ParamAngleX"),
                true
            );

        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterLifecycleStatus.COMPLETE,
            assessment.status()
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterModelCorrelation.UNAVAILABLE,
            assessment.modelCorrelation()
        );
    }

    @Test
    void parameterActorModelFailureCannotBeRecoveredByALaterNormalRead() {
        final WindowsHistoryNativeUiIngressProbe.ParameterChangeObservation actorFailure =
            new WindowsHistoryNativeUiIngressProbe.ParameterChangeObservation(
                WindowsHistoryNativeUiIngressProbe.ParameterStateOutcome.MODEL_CHANGED,
                WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot.unavailable("model-changed"),
                "model-changed"
            );
        final WindowsHistoryNativeUiIngressProbe.ParameterChangeObservation laterChange =
            new WindowsHistoryNativeUiIngressProbe.ParameterChangeObservation(
                WindowsHistoryNativeUiIngressProbe.ParameterStateOutcome.CHANGED,
                parameterSnapshot("model-B", 12.5f),
                "changed"
            );

        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterStateOutcome.MODEL_CHANGED,
            WindowsHistoryNativeUiIngressProbe.preserveParameterActorOutcome(
                actorFailure, laterChange
            ).outcome()
        );

        final WindowsHistoryNativeUiIngressProbe.ParameterChangeObservation unavailable =
            new WindowsHistoryNativeUiIngressProbe.ParameterChangeObservation(
                WindowsHistoryNativeUiIngressProbe.ParameterStateOutcome.UNAVAILABLE,
                WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot.unavailable("read-failed"),
                "read-failed"
            );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterStateOutcome.UNAVAILABLE,
            WindowsHistoryNativeUiIngressProbe.preserveParameterActorOutcome(
                unavailable, laterChange
            ).outcome()
        );
    }

    @Test
    void parameterActorChangeMayUseAChangedSettledReadButNotEraseTheChange() {
        final WindowsHistoryNativeUiIngressProbe.ParameterChangeObservation actorChange =
            new WindowsHistoryNativeUiIngressProbe.ParameterChangeObservation(
                WindowsHistoryNativeUiIngressProbe.ParameterStateOutcome.CHANGED,
                parameterSnapshot("model-A", 5.0f),
                "changed"
            );
        final WindowsHistoryNativeUiIngressProbe.ParameterChangeObservation settledChange =
            new WindowsHistoryNativeUiIngressProbe.ParameterChangeObservation(
                WindowsHistoryNativeUiIngressProbe.ParameterStateOutcome.CHANGED,
                parameterSnapshot("model-A", 12.5f),
                "changed"
            );
        final WindowsHistoryNativeUiIngressProbe.ParameterChangeObservation unchanged =
            new WindowsHistoryNativeUiIngressProbe.ParameterChangeObservation(
                WindowsHistoryNativeUiIngressProbe.ParameterStateOutcome.UNCHANGED,
                parameterSnapshot("model-A", 0.0f),
                "unchanged"
            );

        assertEquals(
            12.5f,
            WindowsHistoryNativeUiIngressProbe.preserveParameterActorOutcome(
                actorChange, settledChange
            ).after().values().get(0).value(),
            0.0f
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterStateOutcome.CHANGED,
            WindowsHistoryNativeUiIngressProbe.preserveParameterActorOutcome(
                actorChange, unchanged
            ).outcome()
        );
    }

    private static WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot parameterSnapshot(
        final String modelId,
        final float value
    ) {
        return WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot.available(
            modelId,
            List.of(new WindowsHistoryNativeUiIngressProbe.ParameterValueSample("ParamAngleX", value))
        );
    }

    private static WindowsHistoryNativeUiIngressProbe.ParameterLifecycleEvent lifecycle(
        final String phase,
        final String parameterId,
        final Float oldValue,
        final Float newValue,
        final String thread
    ) {
        return lifecycle(0L, phase, parameterId, oldValue, newValue, thread);
    }

    private static WindowsHistoryNativeUiIngressProbe.ParameterLifecycleEvent lifecycle(
        final long sequence,
        final String phase,
        final String parameterId,
        final Float oldValue,
        final Float newValue,
        final String thread
    ) {
        return new WindowsHistoryNativeUiIngressProbe.ParameterLifecycleEvent(
            sequence, phase, parameterId, oldValue, newValue, thread
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
