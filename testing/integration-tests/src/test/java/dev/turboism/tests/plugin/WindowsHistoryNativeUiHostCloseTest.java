package dev.turboism.tests.plugin;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JOptionPane;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
                true, false, true, "history-native-ui-5302-r1", "5302"
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
                    true, true, true, runId, "5302"
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
                true, true, true, "history-native-ui-5302-r1", "5302"
            );

        assertTrue(eligibility.eligible());
        assertEquals(
            WindowsHistoryNativeUiHostClose.HostCloseRoute.ROBOT_ALT_F4,
            eligibility.route()
        );
        assertEquals("eligible", eligibility.reason());
    }

    @Test
    void noVisibleHostWindowFailsClosed() {
        assertThrows(
            IllegalStateException.class,
            () -> WindowsHistoryNativeUiHostClose.selectHostWindow(new java.awt.Window[0])
        );
    }

    @Test
    void missingConfirmationIsACleanClose() {
        assertEquals(
            WindowsHistoryNativeUiHostClose.HostCloseDecision.CLEAN_CLOSE,
            WindowsHistoryNativeUiHostClose.hostCloseDecision(
                false, JOptionPane.DEFAULT_OPTION, 0
            )
        );
    }

    @Test
    void onlyKnownConfirmationShapesPermitDiscard() {
        assertEquals(
            WindowsHistoryNativeUiHostClose.HostCloseDecision.DISCARD,
            WindowsHistoryNativeUiHostClose.hostCloseDecision(
                true, JOptionPane.YES_NO_CANCEL_OPTION, 3
            )
        );
        assertEquals(
            WindowsHistoryNativeUiHostClose.HostCloseDecision.DISCARD,
            WindowsHistoryNativeUiHostClose.hostCloseDecision(
                true, JOptionPane.DEFAULT_OPTION, 2
            )
        );
        assertEquals(
            WindowsHistoryNativeUiHostClose.HostCloseDecision.UNSUPPORTED_CONFIRMATION,
            WindowsHistoryNativeUiHostClose.hostCloseDecision(
                true, JOptionPane.OK_CANCEL_OPTION, 2
            )
        );
    }

    @Test
    void discardSelectionRequiresOneSemanticNoAction() {
        final JButton save = new JButton("Save");
        save.setActionCommand("save");
        final JButton cancel = new JButton("Cancel");
        cancel.setActionCommand("cancel");
        final JButton no = new JButton("No (N)");
        no.setActionCommand("No (N)");

        assertSame(
            no,
            WindowsHistoryNativeUiHostClose.selectDiscardButton(List.of(save, cancel, no))
        );
        assertNull(
            WindowsHistoryNativeUiHostClose.selectDiscardButton(
                List.of(save, cancel, new JButton("Maybe"))
            )
        );
    }

    @Test
    void ambiguousDiscardLabelsAreNotClicked() {
        final JButton no = new JButton("No");
        final JButton discard = new JButton("Discard");

        assertNull(
            WindowsHistoryNativeUiHostClose.selectDiscardButton(List.of(no, discard))
        );
    }
}
