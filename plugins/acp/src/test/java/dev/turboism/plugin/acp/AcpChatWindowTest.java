package dev.turboism.plugin.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.i18n.PluginLocalization;
import java.awt.Dimension;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.text.AttributedString;
import java.text.MessageFormat;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    void sessionDatesUseLocalDateAndTimeAndHandleMissingMetadata() {
        assertEquals(
                "2026-08-25 18:00",
                AcpChatWindow.sessionDateTime("2026-08-25T10:00:00Z", java.time.ZoneId.of("Asia/Shanghai")));
        assertEquals("", AcpChatWindow.sessionDateTime("active", java.time.ZoneId.of("UTC")));
        assertEquals("", AcpChatWindow.sessionDateTime("", java.time.ZoneId.of("UTC")));
        assertEquals("invalid", AcpChatWindow.sessionDateTime("invalid", java.time.ZoneId.of("UTC")));
    }

    @Test
    void sessionRendererKeepsTitleAndDateOnSeparateLinesAndDoesNotInterpretHtml() {
        final AcpChatWindow.SessionRenderer renderer = new AcpChatWindow.SessionRenderer();
        final javax.swing.JList<AcpChatWindow.SessionItem> list = new javax.swing.JList<>();
        renderer.getListCellRendererComponent(
                list, new AcpChatWindow.SessionItem("opaque", "<html>标题", "2026-08-25 18:00"), 0, true, false);
        final javax.swing.JLabel title = (javax.swing.JLabel) renderer.getComponent(0);
        final javax.swing.JLabel date = (javax.swing.JLabel) renderer.getComponent(1);
        assertEquals("<html>标题", title.getText());
        assertEquals(Boolean.TRUE, title.getClientProperty("html.disable"));
        assertEquals("2026-08-25 18:00", date.getText());
        assertEquals(list.getSelectionForeground(), title.getForeground());
    }

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

        final Object lafComboUi = javax.swing.UIManager.getLookAndFeelDefaults().get("ComboBoxUI");
        assertNotNull(lafComboUi);
        javax.swing.UIManager.put("ComboBoxUI", null);
        AcpChatWindow.installFallbackSwingUis(javax.swing.UIManager.getDefaults());
        assertEquals(
                lafComboUi,
                javax.swing.UIManager.getDefaults().get("ComboBoxUI"),
                "MultiUIDefaults must keep the active LookAndFeel ComboBoxUI instead of overriding with BasicComboBoxUI");
    }

    @Test
    void preCachesLookAndFeelUiClassesSoIsolatedPluginLoadersResolveTextAreaUi() {
        final javax.swing.UIDefaults defaults = new javax.swing.UIDefaults();
        final String uiClassName = "javax.swing.plaf.basic.BasicTextAreaUI";
        defaults.put("TextAreaUI", uiClassName);
        final ClassLoader isolatedPluginLoader = new ClassLoader(null) {
            @Override
            public Class<?> loadClass(final String name) throws ClassNotFoundException {
                if (uiClassName.equals(name)) {
                    throw new ClassNotFoundException("blocked by plugin boundary: " + name);
                }
                return ClassLoader.getPlatformClassLoader().loadClass(name);
            }
        };

        assertEquals(null, defaults.getUIClass("TextAreaUI", isolatedPluginLoader));

        AcpChatWindow.installFallbackSwingUis(defaults);

        assertEquals(
                javax.swing.plaf.basic.BasicTextAreaUI.class, defaults.getUIClass("TextAreaUI", isolatedPluginLoader));
        assertEquals("javax.swing.plaf.basic.BasicPopupMenuUI", defaults.get("PopupMenuUI"));
    }

    @Test
    void permissionDialogHasAConcreteMinimumSize() {
        final Dimension minimum = AcpChatWindow.permissionDialogMinimum();
        assertTrue(minimum.width >= 460);
        assertTrue(minimum.height >= 200);

        final JPanel panel = new JPanel();
        panel.setSize(12, 0);
        AcpChatWindow.ensureMinimumSize(panel, minimum);
        assertEquals(minimum, panel.getSize());
    }

    @Test
    void turnMeterComputesLiveAndDecodeSpeedsExcludesUserWaitAndCalibratesExactTokens() {
        final java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        final AcpTurnMeter meter = new AcpTurnMeter(clock::get);

        meter.startTurn();
        clock.addAndGet(200_000_000L);
        meter.recordOutputChars(36);
        clock.addAndGet(500_000_000L);
        meter.recordOutputChars(72);
        AcpTurnMeter.Snapshot running = meter.snapshot();
        assertTrue(running.visible());
        assertTrue(running.running());
        assertTrue(running.approximate());
        assertEquals(200L, running.ttftMillis());
        assertTrue(running.liveTokPerSec() > 25.0);
        assertTrue(running.pillText().startsWith("≈"));

        // Pause for 5 seconds on a permission prompt: decode rate must exclude user wait.
        meter.beginUserWait();
        clock.addAndGet(5_000_000_000L);
        meter.endUserWait();
        clock.addAndGet(500_000_000L);
        meter.recordOutputChars(72);
        meter.recordUsageOutputTokens(50L);
        meter.finishTurn();

        final AcpTurnMeter.Snapshot settled = meter.snapshot();
        assertFalse(settled.running());
        assertFalse(settled.approximate());
        assertEquals(50L, settled.outputTokens());
        assertEquals(1_000L, settled.activeMillis());
        assertEquals("50.0 tok/s", settled.pillText());
    }

    @Test
    void planTrackerNormalizesAcpPlansAndClaudeCodexPiTodoPayloads() {
        final AcpPlanTracker tracker = new AcpPlanTracker();
        assertTrue(
                tracker.observeToolInput(
                        "TodoWrite",
                        "{\"todos\":[{\"content\":\"Read model\",\"status\":\"completed\"},{\"content\":\"Edit deformers\",\"activeForm\":\"Editing deformers\",\"status\":\"in_progress\"}]}"));
        assertEquals(2, tracker.items().size());
        assertEquals(AcpPlanTracker.Status.COMPLETED, tracker.items().get(0).status());
        assertEquals("Editing deformers", tracker.items().get(1).displayText());

        assertTrue(tracker.observeToolInput("todo", "{\"action\":\"clear\"}"));
        assertTrue(tracker.items().isEmpty());

        assertTrue(tracker.observeToolInput(
                "todo",
                "{\"action\":\"create\",\"tasks\":[{\"subject\":\"Inspect mesh\"},{\"subject\":\"Apply rig\"}]}"));
        assertTrue(tracker.observeToolInput(
                "todo",
                "{\"action\":\"update\",\"id\":1,\"status\":\"in_progress\",\"activeForm\":\"Inspecting mesh\"}"));
        assertEquals(2, tracker.items().size());
        assertEquals("Inspecting mesh", tracker.items().get(0).displayText());
        assertEquals(AcpPlanTracker.Status.IN_PROGRESS, tracker.items().get(0).status());
    }

    @Test
    void permissionFormatterUnwrapsNestedJsonAndMessageTimestampsUseHostClock() {
        final PluginLocalization i18n = catalog(Map.of(
                "permission.desc.parameters", "Modify model parameters and folders",
                "permission.field.project", "Project",
                "permission.field.operation", "Operation",
                "sender.tool", "Tool",
                "permission.desc.generic", "Run {0}"));
        final AcpListener.PermissionRequest request = new AcpListener.PermissionRequest(
                "turboism: turboism.parameters.structure.apply",
                "other",
                "call-1",
                "{\"message\":\"{\\\"documentId\\\":\\\"document-session-4\\\",\\\"modelId\\\":\\\"model-session-5\\\",\\\"operationId\\\":\\\"setup-eyes\\\",\\\"planId\\\":\\\"90343b91-b50f\\\"}\"}",
                new AcpClient.PermissionOptionSet("allow", null, "reject"));

        final AcpPermissionFormatter.FormattedPermission formatted = AcpPermissionFormatter.format(i18n, request);
        assertEquals("turboism.parameters.structure.apply", AcpPermissionFormatter.approvalKey(request));
        assertEquals("Modify model parameters and folders", formatted.headline());
        assertEquals("turboism.parameters.structure.apply", formatted.toolBadge());
        assertEquals("document-session-4", formatted.summaryRows().get(0).value());
        assertFalse(formatted.rawDetails().contains("\\\"documentId\\\""));
        assertTrue(formatted.rawDetails().contains("\"documentId\": \"document-session-4\""));

        assertEquals(
                "Thursday 23:05",
                AcpChatWindow.formatMessageTimestamp(
                        java.time.Instant.parse("2026-08-27T15:05:00Z"),
                        java.time.ZoneId.of("Asia/Shanghai"),
                        Locale.ENGLISH));
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
                "The agent started but ACP initialization failed.",
                "The agent started but ACP initialization failed."));
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

    @Test
    void composerPlaceholderDrawsEveryLocalizedSuggestionInsteadOfTheGenericInvitation() {
        final PluginLocalization localization = catalog(Map.of(
                "composer.placeholder",
                "Message the agent…",
                "composer.suggestion.mask-import",
                "Import PSD masks",
                "composer.suggestion.folder-colors",
                "Set folder colors",
                "composer.suggestion.missing-display",
                "Why is this hidden"));

        final Set<String> drawn = new HashSet<>();
        for (int seed = 0; seed < 200; seed++) {
            drawn.add(AcpComposerPlaceholder.choose(localization, new java.util.Random(seed)));
        }

        assertEquals(Set.of("Import PSD masks", "Set folder colors", "Why is this hidden"), drawn);
    }

    @Test
    void composerPlaceholderFallsBackToTheGenericInvitationForPartialCatalogs() {
        assertEquals(
                "Message the agent…",
                AcpComposerPlaceholder.choose(
                        catalog(Map.of("composer.placeholder", "Message the agent…")), new java.util.Random(1)));
        assertEquals("", AcpComposerPlaceholder.choose(catalog(Map.of()), new java.util.Random(1)));
    }

    @Test
    void transcriptImageUtilitiesCopyFullResolutionAndSaveWithExpectedFormat(
            @org.junit.jupiter.api.io.TempDir final java.nio.file.Path directory) throws Exception {
        final var source = new java.awt.image.BufferedImage(640, 480, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        final var base = AcpAttachment.fromClipboard(source);
        final var toolImage = new AcpAttachment(
                "Tool image · abc · reported: capture=c1", base.mimeType(), base.uri(), base.data(), base.byteSize());

        assertEquals("image.png", AcpTranscriptImages.suggestedFileName(toolImage));
        assertEquals(
                directory.resolve("pose.png"), AcpTranscriptImages.ensureExtension(directory.resolve("pose"), "png"));

        final var clipboard = new java.awt.datatransfer.Clipboard("test-image-clipboard");
        final var decoded = AcpTranscriptImages.toBufferedImage(toolImage, null);
        assertEquals(640, decoded.getWidth());
        assertEquals(480, decoded.getHeight());
        AcpTranscriptImages.copyToClipboard(decoded, clipboard);
        assertTrue(clipboard.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.imageFlavor));
        final var copied = (java.awt.Image) clipboard.getData(java.awt.datatransfer.DataFlavor.imageFlavor);
        assertEquals(640, copied.getWidth(null));

        final var pngOut = directory.resolve("saved.png");
        AcpTranscriptImages.saveToFile(toolImage, null, pngOut);
        assertEquals(640, javax.imageio.ImageIO.read(pngOut.toFile()).getWidth());

        final var jpgOut = directory.resolve("saved.jpg");
        AcpTranscriptImages.saveToFile(toolImage, null, jpgOut);
        assertEquals(640, javax.imageio.ImageIO.read(jpgOut.toFile()).getWidth());
    }

    private static PluginLocalization catalog(final Map<String, String> entries) {
        return new PluginLocalization() {
            @Override
            public Locale locale() {
                return Locale.ENGLISH;
            }

            @Override
            public String text(final String key) {
                return entries.get(key);
            }

            @Override
            public String format(final String key, final Object... arguments) {
                return new MessageFormat(text(key), locale()).format(arguments);
            }

            @Override
            public boolean contains(final String key) {
                return entries.containsKey(key);
            }
        };
    }
}
