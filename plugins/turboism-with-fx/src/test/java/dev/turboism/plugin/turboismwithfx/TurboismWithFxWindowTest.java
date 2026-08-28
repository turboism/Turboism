package dev.turboism.plugin.turboismwithfx;

import org.junit.jupiter.api.Test;

import javax.swing.Action;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.text.DefaultEditorKit;
import java.awt.Dimension;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.lang.reflect.Method;
import java.text.AttributedString;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TurboismWithFxWindowTest {

    @Test
    void enterSendsWhileShiftAndControlEnterInsertNewlines() {
        final JTextArea input = new JTextArea();
        final AtomicInteger submissions = new AtomicInteger();
        TurboismWithFxWindow.configurePromptKeys(input, submissions::incrementAndGet);

        final Object sendKey = input.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0));
        final Action send = input.getActionMap().get(sendKey);
        assertNotNull(send);
        send.actionPerformed(new java.awt.event.ActionEvent(input, 0, "send"));
        assertEquals(1, submissions.get());

        assertEquals(
            DefaultEditorKit.insertBreakAction,
            input.getInputMap().get(KeyStroke.getKeyStroke(
                KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK
            ))
        );
        assertEquals(
            DefaultEditorKit.insertBreakAction,
            input.getInputMap().get(KeyStroke.getKeyStroke(
                KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK
            ))
        );
        assertEquals(
            DefaultEditorKit.insertBreakAction,
            input.getInputMap().get(KeyStroke.getKeyStroke(
                KeyEvent.VK_ENTER,
                InputEvent.SHIFT_DOWN_MASK | InputEvent.CTRL_DOWN_MASK
            ))
        );
    }

    @Test
    void inputMethodCompositionDistinguishesCommittedAndUncommittedText() {
        final AttributedString composed = new AttributedString("中文");
        assertTrue(TurboismWithFxWindow.hasUncommittedText(
            composed.getIterator(),
            0
        ));
        assertFalse(TurboismWithFxWindow.hasUncommittedText(
            composed.getIterator(),
            2
        ));
        assertFalse(TurboismWithFxWindow.hasUncommittedText(null, 0));
    }

    @Test
    void permissionDialogHasAConcreteMinimumSize() {
        final Dimension minimum = TurboismWithFxWindow.permissionDialogMinimum();
        assertTrue(minimum.width >= 620);
        assertTrue(minimum.height >= 360);

        final JPanel panel = new JPanel();
        panel.setSize(12, 0);
        TurboismWithFxWindow.ensureMinimumSize(panel, minimum);
        assertEquals(minimum, panel.getSize());
    }

    @Test
    void editableProviderAndModelValuesRemainOpaque() {
        final JComboBox<FxAcpConfigOption.Choice> choices = new JComboBox<>();
        choices.setEditable(true);
        choices.getEditor().setItem("custom-provider-id");
        assertEquals(
            "custom-provider-id",
            TurboismWithFxWindow.selectedConfigValue(choices)
        );

        choices.addItem(new FxAcpConfigOption.Choice(
            "opaque-catalog-id",
            "Display label"
        ));
        choices.setSelectedIndex(0);
        assertEquals(
            "opaque-catalog-id",
            TurboismWithFxWindow.selectedConfigValue(choices)
        );
    }

    @Test
    void statusSummaryUsesFxDisplayNamesWithoutChangingOpaqueValues() throws Exception {
        final JComboBox<FxAcpConfigOption.Choice> choices = new JComboBox<>();
        choices.addItem(new FxAcpConfigOption.Choice("opaque-provider-id", "Codex subscription"));
        choices.setSelectedIndex(0);

        assertEquals("Codex subscription", selectedDisplay(choices, "Unavailable"));
        assertEquals(
            "opaque-provider-id",
            ((FxAcpConfigOption.Choice) choices.getSelectedItem()).value()
        );
    }

    @Test
    void unavailableOptionUsesSummaryFallback() throws Exception {
        final JComboBox<FxAcpConfigOption.Choice> choices = new JComboBox<>();
        choices.addItem(new FxAcpConfigOption.Choice("unavailable", "Connect fx"));
        choices.setSelectedIndex(0);

        assertEquals("Unavailable", selectedDisplay(choices, "Unavailable"));
    }

    @Test
    void lifecycleMessagesRecordTransitionsWithoutRepeatingTheSameState() {
        assertTrue(TurboismWithFxWindow.recordLifecycleMessage("", "Connecting to fx…"));
        assertFalse(TurboismWithFxWindow.recordLifecycleMessage(
            "Connecting to fx…",
            "Connecting to fx…"
        ));
        assertTrue(TurboismWithFxWindow.recordLifecycleMessage(
            "Connecting to fx…",
            "fx started but ACP initialization failed."
        ));
        assertFalse(TurboismWithFxWindow.recordLifecycleMessage(
            "fx started but ACP initialization failed.",
            "fx started but ACP initialization failed."
        ));
    }

    @Test
    void transcriptPrefixPruningDoesNotSplitGraphemeClusters() {
        assertEquals(2, TurboismWithFxWindow.safePrefixLength("😀message", 1));
        assertEquals(2, TurboismWithFxWindow.safePrefixLength("ámessage", 1));
        assertEquals(5, TurboismWithFxWindow.safePrefixLength("👩‍💻message", 3));
        assertEquals(3, TurboismWithFxWindow.safePrefixLength("😀message", 3));
    }

    @Test
    void toolMetadataIsBoundedWithoutSplittingSurrogatePairs() {
        final String prefix = "x".repeat(4095);
        final String bounded = TurboismWithFxWindow.boundedToolMetadata(prefix + "😀suffix");
        assertEquals(4095, bounded.length());
        assertFalse(Character.isHighSurrogate(bounded.charAt(bounded.length() - 1)));
    }

    private static String selectedDisplay(
        final JComboBox<FxAcpConfigOption.Choice> choices,
        final String fallback
    ) throws Exception {
        final Method method = TurboismWithFxWindow.class.getDeclaredMethod(
            "selectedDisplay", JComboBox.class, String.class
        );
        method.setAccessible(true);
        return (String) method.invoke(null, choices, fallback);
    }
}
