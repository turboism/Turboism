package dev.turboism.tests.plugin;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
    void fixtureAtPromptStartIsAcceptedAsACompleteToken() {
        final String fixture = "history-native-ui-5302-r1.cmo3";

        assertEquals(
            WindowsHistoryNativeUiHostClose.HostCloseDecision.DISCARD,
            WindowsHistoryNativeUiHostClose.hostCloseDecision(
                true,
                dialog(
                    fixture,
                    "Save Changes",
                    JOptionPane.YES_NO_OPTION,
                    button("Yes"),
                    button("No")
                ),
                fixture
            )
        );
    }

    @Test
    void fixtureAtPromptEndIsAcceptedWithoutTrailingPunctuation() {
        final String fixture = "history-native-ui-5302-r1.cmo3";

        assertEquals(
            WindowsHistoryNativeUiHostClose.HostCloseDecision.DISCARD,
            WindowsHistoryNativeUiHostClose.hostCloseDecision(
                true,
                dialog(
                    "Save Changes",
                    "Do you want to save changes to " + fixture,
                    JOptionPane.YES_NO_OPTION,
                    button("Yes"),
                    button("No")
                ),
                fixture
            )
        );
    }

    @Test
    void fixtureInsideALongerFilenameIsRejected() {
        final String fixture = "history-native-ui-5302-r1.cmo3";

        assertEquals(
            WindowsHistoryNativeUiHostClose.HostCloseDecision.UNSUPPORTED_CONFIRMATION,
            WindowsHistoryNativeUiHostClose.hostCloseDecision(
                true,
                dialog(
                    "Save Changes",
                    "Do you want to save changes to " + fixture + "-backup",
                    JOptionPane.YES_NO_OPTION,
                    button("Yes"),
                    button("No")
                ),
                fixture
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
    void observedR80ChineseSavePromptSelectsItsExplicitNoAction() {
        final String fixture = "queue-19112224a5154b1f81b996cc432c1f10.cmo3";
        final WindowsHistoryNativeUiHostClose.CloseDialogSnapshot prompt = dialog(
            "确定",
            "你想保存" + fixture + "的文件吗?",
            JOptionPane.YES_NO_CANCEL_OPTION,
            button("Yes(Y)"),
            button("No(N)"),
            button("Cancel(C)")
        );

        assertEquals(
            WindowsHistoryNativeUiHostClose.HostCloseDecision.DISCARD,
            WindowsHistoryNativeUiHostClose.hostCloseDecision(true, prompt, fixture)
        );
        assertEquals(1, WindowsHistoryNativeUiHostClose.selectDiscardButton(prompt.buttons()));
    }

    @Test
    void aRealOptionPaneSubtreeExcludesTheIndependentTitlebarButton() throws Exception {
        final String fixture = "queue-19112224a5154b1f81b996cc432c1f10.cmo3";
        final SwingDialogFixture dialog = swingDialog(
            "你想保存" + fixture + "的文件吗?",
            JOptionPane.YES_NO_CANCEL_OPTION,
            "Yes(Y)",
            "No(N)",
            "Cancel(C)"
        );

        final WindowsHistoryNativeUiHostClose.CloseDialogSnapshot snapshot =
            WindowsHistoryNativeUiHostClose.snapshotCloseDialogForTest(dialog.root(), "确定");

        assertTrue(snapshot.optionPanePresent());
        assertEquals(3, snapshot.buttons().size());
        assertEquals(
            List.of("Yes(Y)", "No(N)", "Cancel(C)"),
            snapshot.buttons().stream()
                .map(WindowsHistoryNativeUiHostClose.ButtonSnapshot::text)
                .toList()
        );
        assertEquals(
            WindowsHistoryNativeUiHostClose.HostCloseDecision.DISCARD,
            WindowsHistoryNativeUiHostClose.handleCloseDialogForTest(
                dialog.root(), "确定", fixture
            )
        );
        assertEquals(1, dialog.noClicks().get());
        assertEquals(1, dialog.paneClicks().get());
        assertEquals(0, dialog.titlebarClicks().get());
    }

    @Test
    void aWrongFixtureInARealOptionPaneIsRejectedWithoutClicking() throws Exception {
        final String fixture = "queue-19112224a5154b1f81b996cc432c1f10.cmo3";
        final SwingDialogFixture dialog = swingDialog(
            "你想保存other-queue.cmo3的文件吗?",
            JOptionPane.YES_NO_CANCEL_OPTION,
            "Yes(Y)",
            "No(N)",
            "Cancel(C)"
        );

        assertThrows(
            IllegalStateException.class,
            () -> WindowsHistoryNativeUiHostClose.handleCloseDialogForTest(
                dialog.root(), "确定", fixture
            )
        );
        assertEquals(0, dialog.paneClicks().get());
        assertEquals(0, dialog.titlebarClicks().get());
    }

    @Test
    void multipleDiscardActionsInARealOptionPaneAreRejectedWithoutClicking() throws Exception {
        final String fixture = "queue-19112224a5154b1f81b996cc432c1f10.cmo3";
        final SwingDialogFixture dialog = swingDialog(
            "你想保存" + fixture + "的文件吗?",
            JOptionPane.YES_NO_CANCEL_OPTION,
            "Yes(Y)",
            "No(N)",
            "Discard"
        );

        assertThrows(
            IllegalStateException.class,
            () -> WindowsHistoryNativeUiHostClose.handleCloseDialogForTest(
                dialog.root(), "确定", fixture
            )
        );
        assertEquals(0, dialog.paneClicks().get());
        assertEquals(0, dialog.titlebarClicks().get());
    }

    @Test
    void aMissingOptionPaneIsRejectedWithoutUsingTheTitlebarButton() throws Exception {
        final AtomicInteger titlebarClicks = new AtomicInteger();
        final JPanel root = rootWithTitlebar(new JPanel(), titlebarClicks);
        final WindowsHistoryNativeUiHostClose.CloseDialogSnapshot snapshot =
            WindowsHistoryNativeUiHostClose.snapshotCloseDialogForTest(root, "确定");

        assertFalse(snapshot.optionPanePresent());
        assertTrue(snapshot.buttons().isEmpty());
        assertThrows(
            IllegalStateException.class,
            () -> WindowsHistoryNativeUiHostClose.handleCloseDialogForTest(
                root, "确定", "queue-19112224a5154b1f81b996cc432c1f10.cmo3"
            )
        );
        assertEquals(0, titlebarClicks.get());
    }

    @Test
    void multipleOptionPanesAreAmbiguousAndCannotBeClicked() throws Exception {
        final SwingDialogFixture first = swingDialog(
            "你想保存queue-19112224a5154b1f81b996cc432c1f10.cmo3的文件吗?",
            JOptionPane.YES_NO_CANCEL_OPTION,
            "Yes(Y)",
            "No(N)",
            "Cancel(C)"
        );
        final SwingDialogFixture second = swingDialog(
            "你想保存queue-19112224a5154b1f81b996cc432c1f10.cmo3的文件吗?",
            JOptionPane.YES_NO_CANCEL_OPTION,
            "Yes(Y)",
            "No(N)",
            "Cancel(C)"
        );
        final JPanel panes = new JPanel();
        panes.add(first.optionPane());
        panes.add(second.optionPane());
        final AtomicInteger titlebarClicks = new AtomicInteger();
        final JPanel root = rootWithTitlebar(panes, titlebarClicks);
        final WindowsHistoryNativeUiHostClose.CloseDialogSnapshot snapshot =
            WindowsHistoryNativeUiHostClose.snapshotCloseDialogForTest(root, "确定");

        assertFalse(snapshot.optionPanePresent());
        assertTrue(snapshot.buttons().isEmpty());
        assertThrows(
            IllegalStateException.class,
            () -> WindowsHistoryNativeUiHostClose.handleCloseDialogForTest(
                root, "确定", "queue-19112224a5154b1f81b996cc432c1f10.cmo3"
            )
        );
        assertEquals(0, first.paneClicks().get());
        assertEquals(0, second.paneClicks().get());
        assertEquals(0, titlebarClicks.get());
    }

    @Test
    void observedR80ChineseSavePromptForAnotherFixtureIsRejected() {
        final String fixture = "queue-19112224a5154b1f81b996cc432c1f10.cmo3";

        assertEquals(
            WindowsHistoryNativeUiHostClose.HostCloseDecision.UNSUPPORTED_CONFIRMATION,
            WindowsHistoryNativeUiHostClose.hostCloseDecision(
                true,
                dialog(
                    "确定",
                    "你想保存other-queue.cmo3的文件吗?",
                    JOptionPane.YES_NO_CANCEL_OPTION,
                    button("Yes(Y)"),
                    button("No(N)"),
                    button("Cancel(C)")
                ),
                fixture
            )
        );
    }

    @Test
    void observedR80ChineseSavePromptWithPrefixedFixtureIsRejected() {
        final String fixture = "queue-19112224a5154b1f81b996cc432c1f10.cmo3";

        assertEquals(
            WindowsHistoryNativeUiHostClose.HostCloseDecision.UNSUPPORTED_CONFIRMATION,
            WindowsHistoryNativeUiHostClose.hostCloseDecision(
                true,
                dialog(
                    "确定",
                    "你想保存prefix-" + fixture + "的文件吗?",
                    JOptionPane.YES_NO_CANCEL_OPTION,
                    button("Yes(Y)"),
                    button("No(N)"),
                    button("Cancel(C)")
                ),
                fixture
            )
        );
    }

    @Test
    void observedR80ChineseSavePromptWithSuffixedFixtureIsRejected() {
        final String fixture = "queue-19112224a5154b1f81b996cc432c1f10.cmo3";

        assertEquals(
            WindowsHistoryNativeUiHostClose.HostCloseDecision.UNSUPPORTED_CONFIRMATION,
            WindowsHistoryNativeUiHostClose.hostCloseDecision(
                true,
                dialog(
                    "确定",
                    "你想保存" + fixture + "-backup的文件吗?",
                    JOptionPane.YES_NO_CANCEL_OPTION,
                    button("Yes(Y)"),
                    button("No(N)"),
                    button("Cancel(C)")
                ),
                fixture
            )
        );
    }

    @Test
    void negativeR80ChinesePromptIsRejected() {
        final String fixture = "queue-19112224a5154b1f81b996cc432c1f10.cmo3";

        assertEquals(
            WindowsHistoryNativeUiHostClose.HostCloseDecision.UNSUPPORTED_CONFIRMATION,
            WindowsHistoryNativeUiHostClose.hostCloseDecision(
                true,
                dialog(
                    "确定",
                    "不要保存" + fixture + "的文件吗?",
                    JOptionPane.YES_NO_CANCEL_OPTION,
                    button("Yes(Y)"),
                    button("No(N)"),
                    button("Cancel(C)")
                ),
                fixture
            )
        );
    }

    @Test
    void unrelatedR80ChinesePromptIsRejected() {
        final String fixture = "queue-19112224a5154b1f81b996cc432c1f10.cmo3";

        assertEquals(
            WindowsHistoryNativeUiHostClose.HostCloseDecision.UNSUPPORTED_CONFIRMATION,
            WindowsHistoryNativeUiHostClose.hostCloseDecision(
                true,
                dialog(
                    "确定",
                    "你想打开" + fixture + "的文件吗?",
                    JOptionPane.YES_NO_CANCEL_OPTION,
                    button("Yes(Y)"),
                    button("No(N)"),
                    button("Cancel(C)")
                ),
                fixture
            )
        );
    }

    @Test
    void observedR80ChinesePromptWithAmbiguousDiscardButtonsIsRejected() {
        final String fixture = "queue-19112224a5154b1f81b996cc432c1f10.cmo3";

        assertEquals(
            WindowsHistoryNativeUiHostClose.HostCloseDecision.UNSUPPORTED_CONFIRMATION,
            WindowsHistoryNativeUiHostClose.hostCloseDecision(
                true,
                dialog(
                    "确定",
                    "你想保存" + fixture + "的文件吗?",
                    JOptionPane.YES_NO_CANCEL_OPTION,
                    button("Yes(Y)"),
                    button("No(N)"),
                    button("不保存")
                ),
                fixture
            )
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

    private record SwingDialogFixture(
        JPanel root,
        JOptionPane optionPane,
        AtomicInteger titlebarClicks,
        AtomicInteger paneClicks,
        AtomicInteger noClicks
    ) {
    }

    private static SwingDialogFixture swingDialog(
        final String message,
        final int optionType,
        final String... buttonTexts
    ) {
        final AtomicInteger titlebarClicks = new AtomicInteger();
        final AtomicInteger paneClicks = new AtomicInteger();
        final AtomicInteger noClicks = new AtomicInteger();
        final JPanel buttonPanel = new JPanel();
        for (final String text : buttonTexts) {
            final JButton button = new JButton(text);
            button.addActionListener(event -> {
                paneClicks.incrementAndGet();
                if ("No(N)".equals(text)) noClicks.incrementAndGet();
            });
            buttonPanel.add(button);
        }

        final JOptionPane optionPane = new JOptionPane();
        optionPane.setMessage(message);
        optionPane.setOptionType(optionType);
        optionPane.removeAll();
        optionPane.setLayout(new BorderLayout());
        optionPane.add(buttonPanel, BorderLayout.SOUTH);

        final JButton titlebarClose = new JButton();
        titlebarClose.setName("titlebarClose");
        titlebarClose.setActionCommand("close");
        titlebarClose.addActionListener(event -> titlebarClicks.incrementAndGet());

        final JPanel root = new JPanel(new BorderLayout());
        root.add(optionPane, BorderLayout.CENTER);
        root.add(titlebarClose, BorderLayout.NORTH);
        return new SwingDialogFixture(root, optionPane, titlebarClicks, paneClicks, noClicks);
    }

    private static JPanel rootWithTitlebar(
        final JPanel content,
        final AtomicInteger titlebarClicks
    ) {
        final JButton titlebarClose = new JButton();
        titlebarClose.setName("titlebarClose");
        titlebarClose.setActionCommand("close");
        titlebarClose.addActionListener(event -> titlebarClicks.incrementAndGet());

        final JPanel root = new JPanel(new BorderLayout());
        root.add(content, BorderLayout.CENTER);
        root.add(titlebarClose, BorderLayout.NORTH);
        return root;
    }
}
