package dev.turboism.ui.dialog;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.ui.dialog.HostDialogAction;
import dev.turboism.sdk.ui.dialog.HostDialogMatcher;
import dev.turboism.sdk.ui.dialog.HostDialogOutcome;
import dev.turboism.sdk.ui.dialog.HostDialogSnapshot;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.Dialog;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lane B/C focused tests: real Swing JDialog+JOptionPane dialogs driven through the
 * public SDK surface. Requires a display (run under xvfb); skipped when headless.
 */
class RuntimeHostDialogAutomationServiceTest {

    private static final Duration SHORT_TIMEOUT = Duration.ofSeconds(2);

    private final RuntimeHostDialogAutomationService service =
        new RuntimeHostDialogAutomationService(PermissionChecker.allowAll());

    @BeforeEach
    void requireDisplay() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display (xvfb)");
    }

    /** One button per label; clicks recorded per label index. */
    private record ShownDialog(JDialog dialog, List<JButton> buttons, List<AtomicBoolean> clicked) {
    }

    private record LocalizedLabels(HostDialogAction action, List<String> labels) {
    }

    static Stream<Arguments> localizedButtons() {
        return Stream.of(
            Arguments.of(new LocalizedLabels(HostDialogAction.OK, List.of("OK"))),
            Arguments.of(new LocalizedLabels(HostDialogAction.OK, List.of("确定"))),
            Arguments.of(new LocalizedLabels(HostDialogAction.OK, List.of("確定"))),
            Arguments.of(new LocalizedLabels(HostDialogAction.OK, List.of("はい"))),
            Arguments.of(new LocalizedLabels(HostDialogAction.YES, List.of("Yes"))),
            Arguments.of(new LocalizedLabels(HostDialogAction.YES, List.of("是"))),
            Arguments.of(new LocalizedLabels(HostDialogAction.YES, List.of("はい"))),
            Arguments.of(new LocalizedLabels(HostDialogAction.NO, List.of("No"))),
            Arguments.of(new LocalizedLabels(HostDialogAction.NO, List.of("不保存"))),
            Arguments.of(new LocalizedLabels(HostDialogAction.NO, List.of("不儲存"))),
            Arguments.of(new LocalizedLabels(HostDialogAction.NO, List.of("保存しない"))),
            Arguments.of(new LocalizedLabels(HostDialogAction.CANCEL, List.of("Cancel"))),
            Arguments.of(new LocalizedLabels(HostDialogAction.CANCEL, List.of("取消"))),
            Arguments.of(new LocalizedLabels(HostDialogAction.CANCEL, List.of("キャンセル"))),
            // Cubism-localized buttons carry a mnemonic suffix: word(letter) must still match.
            Arguments.of(new LocalizedLabels(HostDialogAction.OK, List.of("OK(&O)"))),
            Arguments.of(new LocalizedLabels(HostDialogAction.YES, List.of("Yes(Y)"))),
            Arguments.of(new LocalizedLabels(HostDialogAction.NO, List.of("No(N)"))),
            Arguments.of(new LocalizedLabels(HostDialogAction.CANCEL, List.of("Cancel(C)"))),
            Arguments.of(new LocalizedLabels(HostDialogAction.CANCEL, List.of("取消(C)")))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("localizedButtons")
    void semanticButtonMatchingClicksTheUniqueButtonAndClosesTheDialog(final LocalizedLabels localized) {
        final ShownDialog shown = showOptionPaneDialog(localized.labels(), JOptionPane.YES_NO_OPTION);
        try {
            final HostDialogOutcome outcome =
                service.act(HostDialogMatcher.anyConfirmation(), localized.action(), SHORT_TIMEOUT);

            assertEquals(HostDialogOutcome.ACTED, outcome);
            assertEquals(1, shown.clicked().stream().filter(AtomicBoolean::get).count(),
                "exactly the single matching button must be clicked");
            assertTrue(shown.clicked().get(0).get(), "the matching button must be clicked");
            assertTrue(closed(shown.dialog()), "dialog must be closed after the action");
        } finally {
            dispose(shown.dialog());
        }
    }

    @Test
    void noDialogAppearsWithinTheDeadlineReturnsNotFound() {
        assertEquals(
            HostDialogOutcome.NOT_FOUND,
            service.act(HostDialogMatcher.anyConfirmation(), HostDialogAction.CANCEL, SHORT_TIMEOUT)
        );
    }

    @Test
    void twoButtonsMatchingTheSameSemanticActionAreAmbiguousAndNeverClicked() {
        final ShownDialog shown = showOptionPaneDialog(List.of("OK", "OK"), JOptionPane.DEFAULT_OPTION);
        try {
            final HostDialogOutcome outcome =
                service.act(HostDialogMatcher.anyConfirmation(), HostDialogAction.OK, SHORT_TIMEOUT);

            assertEquals(HostDialogOutcome.AMBIGUOUS, outcome);
            assertTrue(shown.clicked().stream().noneMatch(AtomicBoolean::get),
                "no button may be clicked on ambiguity");
            assertFalse(closed(shown.dialog()), "ambiguous dialog must stay open");
        } finally {
            dispose(shown.dialog());
        }
    }

    @Test
    void noRecognizableButtonReturnsUnsupportedWithoutClicking() {
        final ShownDialog shown = showOptionPaneDialog(List.of("KEEP"), JOptionPane.DEFAULT_OPTION);
        try {
            final HostDialogOutcome outcome =
                service.act(HostDialogMatcher.anyConfirmation(), HostDialogAction.NO, SHORT_TIMEOUT);

            assertEquals(HostDialogOutcome.UNSUPPORTED, outcome);
            assertTrue(shown.clicked().stream().noneMatch(AtomicBoolean::get));
        } finally {
            dispose(shown.dialog());
        }
    }

    @Test
    void closeDispatchesWindowClosingAndTheDialogDisappears() throws Exception {
        final AtomicReference<JDialog> dialogRef = new AtomicReference<>();
        final AtomicInteger closingEvents = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            final JOptionPane pane = new JOptionPane(
                "Unsaved changes", JOptionPane.WARNING_MESSAGE, JOptionPane.OK_CANCEL_OPTION,
                null, new Object[]{"OK", "Cancel"}, "OK");
            final JDialog dialog = pane.createDialog(null, "Confirm");
            dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            dialog.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(final WindowEvent event) {
                    closingEvents.incrementAndGet();
                }
            });
            dialogRef.set(dialog);
        });
        final JDialog dialog = dialogRef.get();
        SwingUtilities.invokeLater(() -> dialog.setVisible(true));
        try {
            final HostDialogOutcome outcome =
                service.act(HostDialogMatcher.anyConfirmation(), HostDialogAction.CLOSE, SHORT_TIMEOUT);

            assertEquals(HostDialogOutcome.ACTED, outcome);
            assertTrue(closingEvents.get() >= 1, "WINDOW_CLOSING must be dispatched");
            assertTrue(closed(dialog), "dialog must close after WINDOW_CLOSING");
        } finally {
            dispose(dialog);
        }
    }

    @Test
    void snapshotsReportWindowClassModalityOptionTypeAndButtonLabels() {
        final ShownDialog shown = showOptionPaneDialog(List.of("OK", "Cancel"), JOptionPane.OK_CANCEL_OPTION);
        try {
            final List<HostDialogSnapshot> snapshots = service.snapshots();

            final HostDialogSnapshot snapshot = snapshots.stream()
                .filter(item -> item.windowClassName().equals("javax.swing.JDialog"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no JDialog snapshot in " + snapshots));
            assertTrue(snapshot.modal());
            assertEquals(JOptionPane.OK_CANCEL_OPTION, snapshot.optionType());
            assertEquals(List.of("OK", "Cancel"), snapshot.buttonLabels());
        } finally {
            dispose(shown.dialog());
        }
    }

    @Test
    void optionTypeFilterRejectsNonMatchingDialogsAsNotFound() {
        final ShownDialog shown = showOptionPaneDialog(List.of("Yes", "No"), JOptionPane.YES_NO_OPTION);
        try {
            assertEquals(
                HostDialogOutcome.NOT_FOUND,
                service.act(
                    new HostDialogMatcher(Optional.empty(), Optional.of(JOptionPane.OK_CANCEL_OPTION)),
                    HostDialogAction.YES,
                    Duration.ofMillis(800)
                )
            );
            assertEquals(
                HostDialogOutcome.ACTED,
                service.act(
                    new HostDialogMatcher(Optional.empty(), Optional.of(JOptionPane.YES_NO_OPTION)),
                    HostDialogAction.NO,
                    SHORT_TIMEOUT
                )
            );
        } finally {
            dispose(shown.dialog());
        }
    }

    @Test
    void windowClassPrefixFiltersFrontmostDialogs() {
        final ShownDialog shown = showOptionPaneDialog(List.of("OK"), JOptionPane.DEFAULT_OPTION);
        try {
            assertEquals(
                HostDialogOutcome.NOT_FOUND,
                service.act(
                    new HostDialogMatcher(Optional.of("com.live2d.ui.window"), Optional.empty()),
                    HostDialogAction.OK,
                    Duration.ofMillis(800)
                )
            );
            assertEquals(
                HostDialogOutcome.ACTED,
                service.act(
                    new HostDialogMatcher(Optional.of("javax.swing"), Optional.empty()),
                    HostDialogAction.OK,
                    SHORT_TIMEOUT
                )
            );
        } finally {
            dispose(shown.dialog());
        }
    }

    @Test
    void bareDialogWithoutButtonsIsUnsupportedForAnAllEmptyMatcher() {
        final ShownDialog shown = showEmptyBareDialog();
        try {
            assertEquals(
                HostDialogOutcome.UNSUPPORTED,
                service.act(HostDialogMatcher.anyConfirmation(), HostDialogAction.OK, SHORT_TIMEOUT)
            );
        } finally {
            dispose(shown.dialog());
        }
    }

    @Test
    void bareDialogWithButtonsStillMatchesSemanticallyForAnAllEmptyMatcher() {
        final ShownDialog shown = showBareDialog("OK");
        try {
            // Bare dialog (no JOptionPane) with a visible button: semantic matching applies,
            // the unique OK button is clicked; the click does not close it, so TIMEOUT proves the click.
            assertEquals(
                HostDialogOutcome.TIMEOUT,
                service.act(
                    HostDialogMatcher.anyConfirmation(),
                    HostDialogAction.OK,
                    Duration.ofMillis(1200)
                )
            );
            assertTrue(shown.clicked().get(0).get(), "the unique OK button must have been clicked");
        } finally {
            dispose(shown.dialog());
        }
    }

    @Test
    void dialogThatDoesNotCloseAfterTheActionReturnsTimeout() {
        final ShownDialog shown = showBareDialog("OK");
        try {
            final HostDialogOutcome outcome = service.act(
                new HostDialogMatcher(Optional.of("javax.swing"), Optional.empty()),
                HostDialogAction.OK,
                Duration.ofMillis(1200)
            );

            assertEquals(HostDialogOutcome.TIMEOUT, outcome);
            assertTrue(shown.clicked().get(0).get(), "the unique OK button must have been clicked");
            assertFalse(closed(shown.dialog()), "dialog remains open after a non-closing click");
        } finally {
            dispose(shown.dialog());
        }
    }

    @Test
    void missingPermissionRejectsActAndSnapshots() {
        final RuntimeHostDialogAutomationService denied =
            new RuntimeHostDialogAutomationService(PermissionChecker.from(List.of()));

        assertThrows(CubismPermissionException.class,
            () -> denied.act(HostDialogMatcher.anyConfirmation(), HostDialogAction.CLOSE, SHORT_TIMEOUT));
        assertThrows(CubismPermissionException.class, denied::snapshots);
    }

    @Test
    void illegalArgumentsThrowWithoutTouchingThePermissionPath() {
        assertThrows(IllegalArgumentException.class,
            () -> service.act(HostDialogMatcher.anyConfirmation(), HostDialogAction.OK, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
            () -> service.act(HostDialogMatcher.anyConfirmation(), HostDialogAction.OK, Duration.ofSeconds(-1)));
    }

    /**
     * JOptionPane dialog with UI-created buttons (String options, so the option-pane
     * value listener closes the dialog on click — the same mechanism the host uses).
     * Click trackers are attached after the dialog is shown, once the UI exists.
     */
    private static ShownDialog showOptionPaneDialog(final List<String> labels, final int optionType) {
        try {
            final AtomicReference<JDialog> dialogRef = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                final JOptionPane pane = new JOptionPane(
                    "Unsaved changes", JOptionPane.WARNING_MESSAGE, optionType,
                    null, labels.toArray(), labels.get(0));
                final JDialog dialog = pane.createDialog(null, "Confirm");
                dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                dialogRef.set(dialog);
            });
            final JDialog dialog = dialogRef.get();
            SwingUtilities.invokeLater(() -> dialog.setVisible(true));
            final List<JButton> buttons = awaitButtons(dialog);
            final List<AtomicBoolean> clicked = new ArrayList<>();
            for (final JButton button : buttons) {
                final AtomicBoolean flag = new AtomicBoolean();
                button.addActionListener(event -> flag.set(true));
                clicked.add(flag);
            }
            return new ShownDialog(dialog, buttons, clicked);
        } catch (Exception exception) {
            throw new IllegalStateException("could not open the test dialog", exception);
        }
    }

    private static List<JButton> awaitButtons(final JDialog dialog) throws Exception {
        final AtomicReference<List<JButton>> buttonsRef = new AtomicReference<>(List.of());
        for (int attempt = 0; attempt < 50; attempt++) {
            SwingUtilities.invokeAndWait(() ->
                buttonsRef.set(findButtons(dialog)));
            if (!buttonsRef.get().isEmpty()) {
                return buttonsRef.get();
            }
            Thread.sleep(50L);
        }
        throw new IllegalStateException("JOptionPane buttons never appeared");
    }

    /** Ownerless application-modal dialog with no content at all (no buttons). */
    private static ShownDialog showEmptyBareDialog() {
        try {
            final AtomicReference<JDialog> dialogRef = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                final JDialog dialog = new JDialog();
                dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                dialog.setModalityType(Dialog.ModalityType.APPLICATION_MODAL);
                dialog.pack();
                dialogRef.set(dialog);
            });
            final JDialog dialog = dialogRef.get();
            SwingUtilities.invokeLater(() -> dialog.setVisible(true));
            return new ShownDialog(dialog, findButtons(dialog), List.of());
        } catch (Exception exception) {
            throw new IllegalStateException("could not open the test dialog", exception);
        }
    }

    /** Ownerless application-modal dialog with one button whose click does not close it. */
    private static ShownDialog showBareDialog(final String label) {
        try {
            final AtomicReference<JDialog> dialogRef = new AtomicReference<>();
            final AtomicBoolean clicked = new AtomicBoolean();
            SwingUtilities.invokeAndWait(() -> {
                final JDialog dialog = new JDialog();
                dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                dialog.setModalityType(Dialog.ModalityType.APPLICATION_MODAL);
                final JButton button = new JButton(label);
                button.addActionListener(event -> clicked.set(true));
                dialog.add(button);
                dialog.pack();
                dialogRef.set(dialog);
            });
            final JDialog dialog = dialogRef.get();
            SwingUtilities.invokeLater(() -> dialog.setVisible(true));
            return new ShownDialog(dialog, findButtons(dialog), List.of(clicked));
        } catch (Exception exception) {
            throw new IllegalStateException("could not open the test dialog", exception);
        }
    }

    private static List<JButton> findButtons(final JDialog dialog) {
        final List<JButton> buttons = new ArrayList<>();
        collectButtons(dialog, buttons);
        return buttons;
    }

    private static void collectButtons(final java.awt.Component component, final List<JButton> buttons) {
        if (component instanceof JButton button) {
            buttons.add(button);
        }
        if (component instanceof java.awt.Container container) {
            for (final java.awt.Component child : container.getComponents()) {
                collectButtons(child, buttons);
            }
        }
    }

    private static boolean closed(final JDialog dialog) {
        try {
            final AtomicReference<Boolean> closedRef = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() ->
                closedRef.set(!dialog.isDisplayable() || !dialog.isVisible()));
            return closedRef.get();
        } catch (Exception exception) {
            throw new IllegalStateException("could not inspect the test dialog", exception);
        }
    }

    private static void dispose(final JDialog dialog) {
        try {
            SwingUtilities.invokeAndWait(() -> {
                if (dialog.isDisplayable()) {
                    dialog.dispose();
                }
            });
        } catch (Exception ignored) {
            // Dialog already closed/disposed concurrently.
        }
    }
}
