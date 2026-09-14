package dev.turboism.tests.plugin;

import org.junit.jupiter.api.Test;

import javax.swing.JOptionPane;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused safety and route coverage for the native UI probe's normal-close helper. */
class WindowsHistoryNativeUiHostCloseTest {

    @Test
    void manualModeNeverAttemptsAWindowClose() throws Exception {
        final WindowsHistoryNativeUiHostClose.CloseResult result =
            WindowsHistoryNativeUiHostClose.closeIfEligible(
                false, true, true, "history-native-ui-5302-r1", "5302"
            );

        assertEquals(
            WindowsHistoryNativeUiHostClose.CloseStatus.SKIPPED,
            result.status()
        );
        assertEquals("automation-disabled", result.reason());
    }

    @Test
    void terminalEvidenceIsRequiredBeforeAnAutomatedClose() throws Exception {
        final WindowsHistoryNativeUiHostClose.CloseResult result =
            WindowsHistoryNativeUiHostClose.closeIfEligible(
                true, true, false, "history-native-ui-5302-r1", "5302"
            );

        assertEquals(
            WindowsHistoryNativeUiHostClose.CloseStatus.SKIPPED,
            result.status()
        );
        assertEquals("terminal-summary-not-written", result.reason());
    }

    @Test
    void anInactiveProbeCannotCloseAWindowAfterItsRunEnds() {
        final WindowsHistoryNativeUiHostClose.CloseEligibility eligibility =
            WindowsHistoryNativeUiHostClose.eligibility(
                true,
                false,
                true,
                "history-native-ui-5302-r1",
                "5302",
                "history-native-ui-5302-r1.cmo3"
            );

        assertFalse(eligibility.eligible());
        assertEquals("probe-not-running", eligibility.reason());
    }

    @Test
    void missingOrUntrustedTaskIdentitySkipsWithoutTouchingAWindow() throws Exception {
        for (final String runId : Arrays.asList(
            null, "", "unknown", "history native ui", "../other"
        )) {
            final WindowsHistoryNativeUiHostClose.CloseEligibility eligibility =
                WindowsHistoryNativeUiHostClose.eligibility(
                    true, true, true, runId, "5302", "history-native-ui-5302-r1.cmo3"
                );

            assertFalse(eligibility.eligible(), runId);
            assertEquals("missing-or-invalid-task-run-id", eligibility.reason(), runId);
        }
    }

    @Test
    void theExistingVersionRoutesAreKeptWithoutAdmittingNewVersions() {
        assertEquals(
            WindowsHistoryNativeUiHostClose.HostCloseRoute.ROBOT_ALT_F4,
            WindowsHistoryNativeUiHostClose.hostCloseRoute("5302")
        );
        assertEquals(
            WindowsHistoryNativeUiHostClose.HostCloseRoute.SYNTHETIC_WINDOW_CLOSING,
            WindowsHistoryNativeUiHostClose.hostCloseRoute("5203")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> WindowsHistoryNativeUiHostClose.hostCloseRoute("5303")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> WindowsHistoryNativeUiHostClose.hostCloseRoute(null)
        );
    }

    @Test
    void anEligibleIdentitySelectsTheRobotRouteWithoutStartingAHostInThisTest() {
        final WindowsHistoryNativeUiHostClose.CloseEligibility eligibility =
            WindowsHistoryNativeUiHostClose.eligibility(
                true,
                true,
                true,
                "history-native-ui-5302-r1",
                "5302",
                "history-native-ui-5302-r1.cmo3"
            );

        assertTrue(eligibility.eligible());
        assertEquals(
            WindowsHistoryNativeUiHostClose.HostCloseRoute.ROBOT_ALT_F4,
            eligibility.route()
        );
        assertEquals("eligible", eligibility.reason());
    }

    @Test
    void fixtureIdentityIsRequiredBeforeWindowSelection() {
        assertThrows(
            IllegalStateException.class,
            () -> WindowsHistoryNativeUiHostClose.selectHostWindowCandidate(
                List.of(new WindowsHistoryNativeUiHostClose.HostWindowCandidate(
                    "Cubism Editor", ""
                )),
                "history-native-ui-5302-r1.cmo3"
            )
        );
    }

    @Test
    void missingConfirmationIsACleanClose() {
        assertEquals(
            WindowsHistoryNativeUiHostClose.HostCloseDecision.CLEAN_CLOSE,
            WindowsHistoryNativeUiHostClose.hostCloseDecision(
                false,
                null,
                "history-native-ui-5302-r1.cmo3"
            )
        );
    }

    @Test
    void unknownYesNoConfirmationIsNotEnoughToPermitDiscard() {
        assertEquals(
            WindowsHistoryNativeUiHostClose.HostCloseDecision.UNSUPPORTED_CONFIRMATION,
            WindowsHistoryNativeUiHostClose.hostCloseDecision(
                true,
                dialog(
                    "Confirm",
                    "Are you sure?",
                    JOptionPane.YES_NO_OPTION,
                    button("Yes"),
                    button("No")
                ),
                "history-native-ui-5302-r1.cmo3"
            )
        );
    }

    @Test
    void negativeDiscardPhraseIsNotADiscardAction() {
        assertEquals(
            -1,
            WindowsHistoryNativeUiHostClose.selectDiscardButton(
                List.of(button("Do not discard"))
            )
        );
        assertEquals(
            WindowsHistoryNativeUiHostClose.HostCloseDecision.UNSUPPORTED_CONFIRMATION,
            WindowsHistoryNativeUiHostClose.hostCloseDecision(
                true,
                dialog(
                    "Save Changes",
                    "Do you want to save changes to history-native-ui-5302-r1.cmo3?",
                    JOptionPane.YES_NO_OPTION,
                    button("Yes"),
                    button("Do not discard")
                ),
                "history-native-ui-5302-r1.cmo3"
            )
        );
    }

    @Test
    void otherFixtureSavePromptIsRejected() {
        assertEquals(
            WindowsHistoryNativeUiHostClose.HostCloseDecision.UNSUPPORTED_CONFIRMATION,
            WindowsHistoryNativeUiHostClose.hostCloseDecision(
                true,
                dialog(
                    "Save Changes",
                    "Do you want to save changes to another-fixture.cmo3?",
                    JOptionPane.YES_NO_OPTION,
                    button("Yes"),
                    button("No")
                ),
                "history-native-ui-5302-r1.cmo3"
            )
        );
    }

    @Test
    void legalTaskFixtureSavePromptSelectsItsExplicitNoAction() {
        final WindowsHistoryNativeUiHostClose.CloseDialogSnapshot prompt = dialog(
            "Save Changes",
            "Do you want to save changes to history-native-ui-5302-r1.cmo3?",
            JOptionPane.YES_NO_CANCEL_OPTION,
            button("Yes"),
            button("No (N)"),
            button("Cancel")
        );

        assertEquals(
            WindowsHistoryNativeUiHostClose.HostCloseDecision.DISCARD,
            WindowsHistoryNativeUiHostClose.hostCloseDecision(
                true, prompt, "history-native-ui-5302-r1.cmo3"
            )
        );
        assertEquals(
            1,
            WindowsHistoryNativeUiHostClose.selectDiscardButton(prompt.buttons())
        );
    }

    @Test
    void ambiguousDiscardLabelsAreNotClicked() {
        assertEquals(
            -1,
            WindowsHistoryNativeUiHostClose.selectDiscardButton(
                List.of(button("No"), button("Discard"))
            )
        );
    }

    @Test
    void windowCandidateSelectionRequiresOneFixtureTitleMatch() {
        final String fixture = "history-native-ui-5302-r1.cmo3";
        final List<WindowsHistoryNativeUiHostClose.HostWindowCandidate> candidates = List.of(
            new WindowsHistoryNativeUiHostClose.HostWindowCandidate(
                "" + fixture + " - Cubism Editor", ""
            ),
            new WindowsHistoryNativeUiHostClose.HostWindowCandidate("Other", "")
        );

        assertEquals(
            0,
            WindowsHistoryNativeUiHostClose.selectHostWindowCandidate(candidates, fixture)
        );
        assertThrows(
            IllegalStateException.class,
            () -> WindowsHistoryNativeUiHostClose.selectHostWindowCandidate(
                List.of(
                    new WindowsHistoryNativeUiHostClose.HostWindowCandidate(
                        "" + fixture + " - Cubism Editor", ""
                    ),
                    new WindowsHistoryNativeUiHostClose.HostWindowCandidate(
                        "Cubism - " + fixture, ""
                    )
                ),
                fixture
            )
        );
    }

    @Test
    void fixtureNameMustBelongToTheTaskRun() {
        final WindowsHistoryNativeUiHostClose.CloseEligibility eligibility =
            WindowsHistoryNativeUiHostClose.eligibility(
                true,
                true,
                true,
                "history-native-ui-5302-r1",
                "5302",
                "other-task.cmo3"
            );

        assertFalse(eligibility.eligible());
        assertEquals("missing-or-invalid-fixture-name", eligibility.reason());
    }

    private static WindowsHistoryNativeUiHostClose.ButtonSnapshot button(final String text) {
        return new WindowsHistoryNativeUiHostClose.ButtonSnapshot(
            "javax.swing.JButton", text, text, null, text, true, true
        );
    }

    private static WindowsHistoryNativeUiHostClose.CloseDialogSnapshot dialog(
        final String title,
        final String message,
        final int optionType,
        final WindowsHistoryNativeUiHostClose.ButtonSnapshot... buttons
    ) {
        return new WindowsHistoryNativeUiHostClose.CloseDialogSnapshot(
            "javax.swing.JDialog", title, message, true, optionType, List.of(buttons)
        );
    }
}
