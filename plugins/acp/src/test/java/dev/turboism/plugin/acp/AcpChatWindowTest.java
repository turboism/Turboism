package dev.turboism.plugin.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Dimension;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.text.AttributedString;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.Action;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.text.DefaultEditorKit;
import org.junit.jupiter.api.Test;

final class AcpChatWindowTest {

    @Test
    void enterSendsWhileShiftAndControlEnterInsertNewlines() {
        final JTextArea input = new JTextArea();
        final AtomicInteger submissions = new AtomicInteger();
        AcpChatWindow.configurePromptKeys(input, submissions::incrementAndGet);

        final Object sendKey = input.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0));
        final Action send = input.getActionMap().get(sendKey);
        assertNotNull(send);
        send.actionPerformed(new java.awt.event.ActionEvent(input, 0, "send"));
        assertEquals(1, submissions.get());

        assertEquals(
                DefaultEditorKit.insertBreakAction,
                input.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK)));
        assertEquals(
                DefaultEditorKit.insertBreakAction,
                input.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK)));
        assertEquals(
                DefaultEditorKit.insertBreakAction,
                input.getInputMap()
                        .get(KeyStroke.getKeyStroke(
                                KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK | InputEvent.CTRL_DOWN_MASK)));
    }

    @Test
    void inputMethodCompositionDistinguishesCommittedAndUncommittedText() {
        final AttributedString composed = new AttributedString("中文");
        assertTrue(AcpChatWindow.hasUncommittedText(composed.getIterator(), 0));
        assertFalse(AcpChatWindow.hasUncommittedText(composed.getIterator(), 2));
        assertFalse(AcpChatWindow.hasUncommittedText(null, 0));
    }

    @Test
    void settingsRegistersFallbackUisBeforeComponentConstruction() {
        final javax.swing.UIDefaults defaults = new javax.swing.UIDefaults();
        defaults.put("PanelUI", "test.CustomPanelUI");

        AcpChatWindow.installFallbackSwingUis(defaults);

        assertEquals("test.CustomPanelUI", defaults.get("PanelUI"));
        assertEquals("javax.swing.plaf.basic.BasicTabbedPaneUI", defaults.get("TabbedPaneUI"));
        assertEquals("javax.swing.plaf.basic.BasicScrollPaneUI", defaults.get("ScrollPaneUI"));
        assertEquals("javax.swing.plaf.basic.BasicComboBoxUI", defaults.get("ComboBoxUI"));
        assertEquals("javax.swing.plaf.basic.BasicTextFieldUI", defaults.get("TextFieldUI"));
        assertEquals("javax.swing.plaf.basic.BasicTextAreaUI", defaults.get("TextAreaUI"));
        assertEquals("javax.swing.plaf.basic.BasicListUI", defaults.get("ListUI"));
        assertEquals("javax.swing.plaf.basic.BasicButtonUI", defaults.get("ButtonUI"));
    }

    @Test
    void permissionDialogHasAConcreteMinimumSize() {
        final Dimension minimum = AcpChatWindow.permissionDialogMinimum();
        assertTrue(minimum.width >= 620);
        assertTrue(minimum.height >= 360);

        final JPanel panel = new JPanel();
        panel.setSize(12, 0);
        AcpChatWindow.ensureMinimumSize(panel, minimum);
        assertEquals(minimum, panel.getSize());
    }

    @Test
    void editableModelValuesAndCatalogChoicesRemainOpaque() {
        final JComboBox<AcpConfigOption.Choice> choices = new JComboBox<>();
        choices.setEditable(true);
        choices.getEditor().setItem("custom-model-id");
        assertEquals("custom-model-id", AcpChatWindow.selectedConfigValue(choices));

        choices.addItem(new AcpConfigOption.Choice("opaque-catalog-id", "Display label"));
        choices.setSelectedIndex(0);
        assertEquals("opaque-catalog-id", AcpChatWindow.selectedConfigValue(choices));
    }

    @Test
    void unavailableAndBlankSelectionsDoNotEmitConfigValues() {
        final JComboBox<AcpConfigOption.Choice> choices = new JComboBox<>();
        choices.addItem(new AcpConfigOption.Choice("unavailable", "Connect the agent first"));
        choices.setSelectedIndex(0);
        assertEquals(null, AcpChatWindow.selectedConfigValue(choices));

        choices.setEditable(true);
        choices.getEditor().setItem("   ");
        assertEquals(null, AcpChatWindow.selectedConfigValue(choices));
    }

    @Test
    void lifecycleMessagesRecordTransitionsWithoutRepeatingTheSameState() {
        assertTrue(AcpChatWindow.recordLifecycleMessage("", "Connecting to the agent…"));
        assertFalse(AcpChatWindow.recordLifecycleMessage("Connecting to the agent…", "Connecting to the agent…"));
        assertTrue(AcpChatWindow.recordLifecycleMessage(
                "Connecting to the agent…", "The agent started but ACP initialization failed."));
        assertFalse(AcpChatWindow.recordLifecycleMessage(
                "The agent started but ACP initialization failed.", "The agent started but ACP initialization failed."));
    }

    @Test
    void transcriptPrefixPruningDoesNotSplitGraphemeClusters() {
        assertEquals(2, AcpChatWindow.safePrefixLength("😀message", 1));
        assertEquals(2, AcpChatWindow.safePrefixLength("ámessage", 1));
        assertEquals(5, AcpChatWindow.safePrefixLength("👩‍💻message", 3));
        assertEquals(3, AcpChatWindow.safePrefixLength("😀message", 3));
    }

    @Test
    void toolMetadataIsBoundedWithoutSplittingSurrogatePairs() {
        final String prefix = "x".repeat(4095);
        final String bounded = AcpChatWindow.boundedToolMetadata(prefix + "😀suffix");
        assertEquals(4095, bounded.length());
        assertFalse(Character.isHighSurrogate(bounded.charAt(bounded.length() - 1)));
    }
}
