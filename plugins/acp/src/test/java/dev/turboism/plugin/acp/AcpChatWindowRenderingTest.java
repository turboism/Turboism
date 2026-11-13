package dev.turboism.plugin.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import dev.turboism.sdk.i18n.PluginLocalization;
import java.awt.Color;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;
import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.StyleConstants;
import org.junit.jupiter.api.Test;

/** Run with xvfb-run when no desktop display is available. */
final class AcpChatWindowRenderingTest {
    @Test
    void managerShowsInstalledVersionAndDistinguishesFailedProbeFromMissingInstall() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            final AcpChatWindow window = new AcpChatWindow(localization(), "pi", "", "");
            try {
                final var path = java.nio.file.Path.of("/installed/pi.cmd");
                window.showDetectionResults(
                        java.util.Map.of("pi", new AgentAvailability.Detection(path, true, "1.2.3", "available")));
                window.showDetectedAgents(java.util.Map.of("pi", path.toString()));
                final var label = component(window, "agentDetection", javax.swing.JLabel.class);
                assertTrue(label.getText().contains("1.2.3"));
                assertTrue(label.getText().contains(path.toString()));
                window.showDetectionResults(java.util.Map.of(
                        "pi", new AgentAvailability.Detection(path, false, "1.2.3", "probe-failed-IOException")));
                window.showDetectedAgents(java.util.Map.of("pi", ""));
                window.showAgentSelected("pi");
                assertEquals(
                        localization().format("status.agent-probe-failed", path.toString(), "1.2.3"), label.getText());
                window.showDetectionResults(java.util.Map.of(
                        "pi", new AgentAvailability.Detection(path, false, "0.1.0", "native-mode-not-advertised")));
                window.showDetectedAgents(java.util.Map.of("pi", ""));
                assertEquals(
                        localization().format("status.agent-unsupported", path.toString(), "0.1.0"), label.getText());
            } finally {
                window.dispose();
            }
        });
    }

    @Test
    void pastesClipboardImagesAndFilesAndSubmitsThemWithoutText(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        final var reference = new java.util.concurrent.atomic.AtomicReference<AcpChatWindow>();
        final var sent = new java.util.concurrent.atomic.AtomicReference<List<AcpAttachment>>();
        final var image = new java.awt.image.BufferedImage(30, 20, java.awt.image.BufferedImage.TYPE_INT_RGB);
        final var file = directory.resolve("notes.txt");
        java.nio.file.Files.writeString(file, "check this file");
        SwingUtilities.invokeAndWait(() -> {
            final AcpChatWindow window = new AcpChatWindow(localization(), "codex", "", "");
            reference.set(window);
            window.showConnected(AcpClient.AcpAgentInfo.UNKNOWN, List.of(), false, AcpClient.McpAttachment.NONE);
            window.bind(
                    () -> {},
                    text -> {
                        sent.set(window.attachments());
                        window.showPrompting();
                    },
                    () -> {},
                    (id, value) -> {},
                    () -> {},
                    ignored -> {},
                    () -> {},
                    () -> {},
                    ignored -> {},
                    () -> {},
                    () -> {},
                    () -> {});
            final JTextArea prompt = component(window, "prompt", JTextArea.class);
            assertTrue(prompt.getTransferHandler()
                    .importData(new javax.swing.TransferHandler.TransferSupport(
                            prompt, transferable(java.awt.datatransfer.DataFlavor.imageFlavor, image))));
        });
        try {
            awaitEdt(() -> reference.get().attachments().size() == 1);
            SwingUtilities.invokeAndWait(() -> {
                final JTextArea prompt = component(reference.get(), "prompt", JTextArea.class);
                assertTrue(prompt.getTransferHandler()
                        .importData(new javax.swing.TransferHandler.TransferSupport(
                                prompt,
                                transferable(
                                        java.awt.datatransfer.DataFlavor.javaFileListFlavor, List.of(file.toFile())))));
            });
            awaitEdt(() -> reference.get().attachments().size() == 2);
            SwingUtilities.invokeAndWait(() -> {
                component(reference.get(), "send", JButton.class).doClick();
                assertEquals(2, sent.get().size());
                assertTrue(reference.get().attachments().isEmpty());
                assertTrue(component(reference.get(), "cancel", JButton.class).isVisible());
            });
        } finally {
            SwingUtilities.invokeAndWait(reference.get()::dispose);
        }
    }

    @Test
    void rendersStreamedLocalMarkdownScreenshotReferences(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        final var image = new java.awt.image.BufferedImage(30, 20, java.awt.image.BufferedImage.TYPE_INT_RGB);
        javax.imageio.ImageIO.write(
                image, "png", directory.resolve("screen.png").toFile());
        final var reference = new java.util.concurrent.atomic.AtomicReference<AcpChatWindow>();
        SwingUtilities.invokeAndWait(() -> {
            final AcpChatWindow window = new AcpChatWindow(localization(), "codex", "", "");
            reference.set(window);
            window.setAttachmentBaseDirectory(directory);
            window.clearTranscript();
            window.appendAgent("![Screenshot](screen");
            window.appendAgent(".png)\nScreenshot ready");
        });
        try {
            awaitEdt(() -> {
                final var document = component(reference.get(), "transcript", JTextPane.class)
                        .getStyledDocument();
                return StyleConstants.getIcon(document.getCharacterElement(0).getAttributes()) != null;
            });
            SwingUtilities.invokeAndWait(() -> {
                final JTextPane transcript = component(reference.get(), "transcript", JTextPane.class);
                assertTrue(transcript.getText().contains("Screenshot ready"));
                assertFalse(transcript.getText().contains("![Screenshot]"));
            });
        } finally {
            SwingUtilities.invokeAndWait(reference.get()::dispose);
        }
    }

    private static java.awt.datatransfer.Transferable transferable(
            final java.awt.datatransfer.DataFlavor flavor, final Object value) {
        return new java.awt.datatransfer.Transferable() {
            public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() {
                return new java.awt.datatransfer.DataFlavor[] {flavor};
            }

            public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor candidate) {
                return flavor.equals(candidate);
            }

            public Object getTransferData(java.awt.datatransfer.DataFlavor candidate)
                    throws java.awt.datatransfer.UnsupportedFlavorException {
                if (!flavor.equals(candidate)) throw new java.awt.datatransfer.UnsupportedFlavorException(candidate);
                return value;
            }
        };
    }

    private static void awaitEdt(final java.util.function.BooleanSupplier condition) throws Exception {
        final var ready = new java.util.concurrent.atomic.AtomicBoolean();
        for (int attempt = 0; attempt < 300 && !ready.get(); attempt++) {
            SwingUtilities.invokeAndWait(() -> ready.set(condition.getAsBoolean()));
            if (!ready.get()) Thread.sleep(10);
        }
        assertTrue(ready.get(), "asynchronous Swing operation did not finish");
    }

    @Test
    void settingsOnlyEditsInstructionsAndAgentSetupLivesInItsOwnMenu() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            final AcpChatWindow window = new AcpChatWindow(localization(), "codex", "", "saved instructions");
            try {
                final var settings = component(window, "settingsFrame", javax.swing.JFrame.class);
                final JTextArea instructions = component(window, "initialPrompt", JTextArea.class);
                assertTrue(SwingUtilities.isDescendingFrom(instructions, settings));
                assertFalse(SwingUtilities.isDescendingFrom(window.agentSelector(), settings));
                assertEquals("saved instructions", instructions.getText());
                assertEquals(
                        "You are an Agent assisting users with Live2D modeling. You can operate Live2D Cubism Editor through MCP.",
                        instructions.getClientProperty("JTextField.placeholderText"));
                final JButton agent = component(window, "composerAgent", JButton.class);
                assertEquals("Codex ▾", agent.getText());
                final var menu = AcpThemeControls.attachedPopup(agent);
                ((JMenuItem) menu.getComponent(menu.getComponentCount() - 1)).doClick();
                final var manager = component(window, "agentManager", javax.swing.JDialog.class);
                assertTrue(manager.isVisible());
                assertTrue(SwingUtilities.isDescendingFrom(window.agentSelector(), manager));
            } finally {
                window.dispose();
            }
        });
    }

    @Test
    void attachmentsEnableAttachmentOnlySendingAndImagesSurviveStreamingAndThemeRefresh() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        final var reference = new java.util.concurrent.atomic.AtomicReference<AcpChatWindow>();
        final AcpAttachment image = AcpAttachment.fromClipboard(
                new java.awt.image.BufferedImage(40, 24, java.awt.image.BufferedImage.TYPE_INT_RGB));
        SwingUtilities.invokeAndWait(() -> {
            final AcpChatWindow window = new AcpChatWindow(localization(), "codex", "", "");
            reference.set(window);
            window.showConnected(AcpClient.AcpAgentInfo.UNKNOWN, List.of(), false, AcpClient.McpAttachment.NONE);
            final JTextArea prompt = component(window, "prompt", JTextArea.class);
            assertTrue(prompt.getTransferHandler()
                    .importData(new javax.swing.TransferHandler.TransferSupport(
                            prompt, new java.awt.datatransfer.StringSelection("plain text"))));
            assertEquals("plain text", prompt.getText());
            prompt.setText("");
            window.addAttachments(List.of(image));
            assertTrue(component(window, "send", JButton.class).isEnabled());
            final JPanel strip = component(window, "attachmentStrip", JPanel.class);
            ((JButton) strip.getComponent(0)).doClick();
            assertTrue(window.attachments().isEmpty());
            assertFalse(component(window, "send", JButton.class).isEnabled());
            window.appendAttachment(image, false);
            window.appendAgent("text after image");
        });
        try {
            final var loaded = new java.util.concurrent.atomic.AtomicBoolean();
            for (int attempt = 0; attempt < 300 && !loaded.get(); attempt++) {
                SwingUtilities.invokeAndWait(() -> {
                    final JTextPane transcript = component(reference.get(), "transcript", JTextPane.class);
                    final var document = transcript.getStyledDocument();
                    for (int i = 0; i < document.getLength(); i++) {
                        if (StyleConstants.getIcon(
                                        document.getCharacterElement(i).getAttributes())
                                != null) loaded.set(true);
                    }
                });
                if (!loaded.get()) Thread.sleep(10);
            }
            assertTrue(loaded.get(), "image preview did not load");
            SwingUtilities.invokeAndWait(() -> {
                final AcpChatWindow window = reference.get();
                window.refreshTheme();
                final JTextPane transcript = component(window, "transcript", JTextPane.class);
                assertTrue(transcript.getText().contains("text after image"));
                assertTrue(StyleConstants.getIcon(transcript
                                .getStyledDocument()
                                .getCharacterElement(transcript.getText().indexOf("clipboard.png") - 2)
                                .getAttributes())
                        != null);
            });
        } finally {
            SwingUtilities.invokeAndWait(reference.get()::dispose);
        }
    }

    @Test
    void taskStatusBlinksCountsFreezesResetsAndKeepsFailureAcrossThemeChanges() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            final java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
            final AcpChatWindow window = new AcpChatWindow(localization(), "", "", "", clock::get);
            try {
                final javax.swing.JLabel status = component(window, "agentStatus", javax.swing.JLabel.class);
                final javax.swing.JLabel dot = component(window, "connectionDot", javax.swing.JLabel.class);
                final javax.swing.Timer timer = component(window, "taskTimer", javax.swing.Timer.class);
                window.showConnected(AcpClient.AcpAgentInfo.UNKNOWN, List.of(), false, AcpClient.McpAttachment.NONE);
                window.showPrompting();
                assertTrue(timer.isRunning());
                assertEquals("Working 0s", status.getText());
                assertEquals("●", dot.getText());
                assertEquals(AcpChatTheme.success(), dot.getForeground());
                final java.awt.Dimension dotSize = dot.getPreferredSize();
                clock.set(500_000_000L);
                window.refreshTaskStatus();
                assertEquals(" ", dot.getText());
                assertEquals(dotSize, dot.getPreferredSize());
                clock.set(750_000_000_000L);
                window.refreshTaskStatus();
                assertEquals("Working 12m30s", status.getText());
                // A duplicate start callback must not reset the running clock.
                window.showPrompting();
                assertEquals("Working 12m30s", status.getText());
                clock.set(1_203_000_000_000L);
                window.showPromptComplete("end_turn");
                assertFalse(timer.isRunning());
                assertEquals("Completed 20m3s", status.getText());
                assertEquals("●", dot.getText());
                clock.addAndGet(60_000_000_000L);
                window.refreshTaskStatus();
                assertEquals("Completed 20m3s", status.getText());
                window.showPrompting();
                assertEquals("Working 0s", status.getText());
                clock.addAndGet(1_000_000_000L);
                window.showSessionFailure("status.prompt-failed", "API unavailable\nretry later");
                assertFalse(timer.isRunning());
                assertEquals("Failed 1s API unavailable retry later", status.getText());
                assertEquals(status.getText(), status.getToolTipText());
                assertEquals("●", dot.getText());
                assertEquals(AcpChatTheme.error(), dot.getForeground());
                window.refreshTheme();
                assertEquals(AcpChatTheme.error(), dot.getForeground());
                assertEquals("Failed 1s API unavailable retry later", status.getText());
                window.showPrompting();
                clock.addAndGet(2_000_000_000L);
                window.showFailure("status.process-terminated");
                assertTrue(status.getText().startsWith("Failed 2s "));
                assertFalse(timer.isRunning());
                window.showPrompting();
                window.dispose();
                assertFalse(timer.isRunning());
            } finally {
                window.dispose();
            }
        });
    }

    @Test
    void streamingMarkdownAndEarlierToolUpdatesPreserveMessageBoundaries() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            final AcpChatWindow window = new AcpChatWindow(localization(), "", "", "");
            try {
                window.clearTranscript();
                window.showSessions(
                        List.of(new AcpSessionSummary("session-1", "修复模型参数", "2026-08-25T10:00:00Z")),
                        "session-1",
                        true);
                window.appendUser("请检查 **模型**");
                window.appendTool("tool-1", "检查参数", "read", "pending");
                window.appendThinking("**分析**参数");
                window.appendAgent("# 结果\n**完");
                window.appendAgent("成**\n```java\n  int x = 1;\n");
                window.appendAgent("```\n正常文本");
                final JTextPane transcript = component(window, "transcript", JTextPane.class);
                final String before = transcript.getText();
                assertTrue(before.contains("请检查 **模型**"));
                assertTrue(before.contains("结果\n完成\n  int x = 1;\n正常文本"));
                assertFalse(before.contains("```"));
                assertFalse(before.contains("分析"));
                assertTrue(StyleConstants.isBold(transcript
                        .getStyledDocument()
                        .getCharacterElement(before.indexOf("完成"))
                        .getAttributes()));

                window.updateTool("tool-1", "completed", "检查通过");
                window.appendAgent("，可以继续。");
                final String after = transcript.getText();
                assertFalse(after.contains("检查通过"));
                assertTrue(after.contains("Tools: 1"));
                assertFalse(after.contains("Thinking"));
                assertFalse(after.contains("Commands: 0"));
                assertTrue(after.contains("正常文本，可以继续。"));
                assertEquals(1, after.split("结果", -1).length - 1);

                toggleDisclosureWithKeyboard(transcript, "Tools: 1");
                assertTrue(transcript.getText().contains("▸  Thinking"));
                assertFalse(transcript.getText().contains("分析参数"));
                toggleDisclosureWithKeyboard(transcript, "Thinking");
                assertTrue(transcript.getText().contains("分析参数"));
                toggleDisclosureWithKeyboard(transcript, "Thinking");
                toggleDisclosureWithKeyboard(transcript, "Tools: 1");
                assertEquals(after, transcript.getText());
            } finally {
                window.dispose();
            }
        });
    }

    @Test
    void groupsCommandsAndToolsAndKeepsExpandedInputsAcrossPartialUpdates() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            final AcpChatWindow window = new AcpChatWindow(localization(), "", "", "");
            try {
                window.clearTranscript();
                window.appendUser("检查模型");
                window.appendTool("command-1", "检查文件", "execute", "in_progress");
                window.updateToolInput("command-1", "printf 'first\\n'\nls -la");
                window.appendTool("tool-2", "读取参数", "read", "completed");
                window.updateTool("tool-2", "completed", "ParamAngleX = 0");
                window.appendAgent("检查完成");
                final JTextPane transcript = component(window, "transcript", JTextPane.class);
                assertTrue(transcript.getText().contains("Commands: 1 · Other tools: 1  ·  1 running"));
                assertFalse(transcript.getText().contains("ls -la"));
                assertFalse(transcript.getText().contains("ParamAngleX"));
                window.showAgentAndFront();
                // Exercise the actual mouse hit target on the summary row.
                final int summary = transcript.getText().indexOf("Commands:");
                try {
                    final java.awt.geom.Rectangle2D bounds = transcript.modelToView2D(summary);
                    transcript.dispatchEvent(new MouseEvent(
                            transcript,
                            MouseEvent.MOUSE_CLICKED,
                            System.currentTimeMillis(),
                            0,
                            (int) bounds.getX() + 2,
                            (int) bounds.getCenterY(),
                            1,
                            false,
                            MouseEvent.BUTTON1));
                } catch (javax.swing.text.BadLocationException failure) {
                    throw new AssertionError(failure);
                }
                assertTrue(transcript.getText().contains("printf 'first\\n'\nls -la"));
                assertTrue(transcript.getText().contains("ParamAngleX = 0"));
                window.updateTool("command-1", "failed", "permission denied");
                window.updateTool("command-1", "failed", "");
                window.appendAgent("，请检查权限");
                assertTrue(transcript.getText().contains("ls -la"));
                assertTrue(transcript.getText().contains("permission denied"));
                assertTrue(transcript.getText().contains("1 failed"));
                assertTrue(transcript.getText().contains("检查完成，请检查权限"));
                toggleSummaryWithKeyboard(transcript);
                assertFalse(transcript.getText().contains("ls -la"));
                toggleSummaryWithKeyboard(transcript);
                assertTrue(transcript.getText().contains("permission denied"));
                window.appendUser("下一轮");
                window.appendTool("command-3", "下一条命令", "execute", "pending");
                assertEquals(2, transcript.getText().split("Commands:", -1).length - 1);
                window.clearTranscript();
                window.appendTool("fresh", "新工具", "read", "completed");
                assertFalse(transcript.getText().contains("permission denied"));
                assertFalse(transcript.getText().contains("新工具"));
            } finally {
                window.dispose();
            }
        });
    }

    @Test
    void userPanelsDifferFromResponsesWithoutSpeakerTitles() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            final AcpChatWindow window = new AcpChatWindow(localization(), "", "", "");
            try {
                window.clearTranscript();
                window.showConfigOptions(modelOptions("model-a", "Model A"));
                window.appendUser("第一行\n第二行 **原文**");
                window.appendAgent("第一轮回答");
                window.showConfigOptions(modelOptions("model-b", "Model B"));
                window.appendUser("继续");
                window.appendAgent("第二轮回答");
                final JTextPane transcript = component(window, "transcript", JTextPane.class);
                final String text = transcript.getText();
                assertTrue(text.contains("第一轮回答"));
                assertFalse(text.contains("Model A"));
                assertFalse(text.contains("You\n"));
                assertTrue(text.contains("第二轮回答"));
                assertFalse(text.contains("Model B"));
                final var document = transcript.getStyledDocument();
                final Object user = document.getParagraphElement(text.indexOf("第一行"))
                        .getAttributes()
                        .getAttribute(AcpTranscriptEditorKit.USER_BACKGROUND);
                final Object model = document.getParagraphElement(text.indexOf("第一轮回答"))
                        .getAttributes()
                        .getAttribute(AcpTranscriptEditorKit.USER_BACKGROUND);
                assertTrue(user instanceof Color);
                assertNotEquals(user, model);
                assertFalse(
                        document.getParagraphElement(document.getLength())
                                        .getAttributes()
                                        .getAttribute(AcpTranscriptEditorKit.USER_BACKGROUND)
                                instanceof Color);
                assertTrue(text.contains("第二行 **原文**"));
                window.showConfigOptions(List.of(new AcpConfigOption(
                        "model", "Model", "raw-model-id", List.of(new AcpConfigOption.Choice("other", "Other")))));
                window.appendUser("第三轮");
                window.appendAgent("第三轮回答");
                assertTrue(transcript.getText().contains("第三轮回答"));
                assertFalse(transcript.getText().contains("raw-model-id"));
            } finally {
                window.dispose();
            }
        });
    }

    private static List<AcpConfigOption> modelOptions(final String id, final String name) {
        return List.of(new AcpConfigOption(
                "custom-model-selector", "Model", "model", "", id, List.of(new AcpConfigOption.Choice(id, name))));
    }

    @Test
    void composerSelectsAdvertisedOptionsAndRestoresConfirmedValuesAfterFailure() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            final AcpChatWindow window = new AcpChatWindow(localization(), "", "", "");
            try {
                final java.util.concurrent.atomic.AtomicReference<List<String>> requested =
                        new java.util.concurrent.atomic.AtomicReference<>();
                window.bind(
                        () -> {},
                        ignored -> {},
                        () -> {},
                        (id, value) -> requested.set(List.of(id, value)),
                        () -> {},
                        ignored -> {},
                        () -> {},
                        () -> {},
                        ignored -> {},
                        () -> {},
                        () -> {},
                        () -> {});
                final List<AcpConfigOption> options = List.of(
                        new AcpConfigOption(
                                "model",
                                "Model",
                                "model-a",
                                List.of(
                                        new AcpConfigOption.Choice("model-a", "Model A"),
                                        new AcpConfigOption.Choice("model-b", "Model B"))),
                        new AcpConfigOption(
                                "reasoning_effort",
                                "Effort",
                                "medium",
                                List.of(new AcpConfigOption.Choice("medium", "Medium"))),
                        new AcpConfigOption(
                                "approval_policy",
                                "Permissions",
                                "review",
                                List.of(new AcpConfigOption.Choice("review", "Auto review"))),
                        new AcpConfigOption(
                                "fast_mode",
                                "Speed",
                                "normal",
                                List.of(new AcpConfigOption.Choice("normal", "Normal speed"))),
                        new AcpConfigOption(
                                "session_mode",
                                "Mode",
                                "plan",
                                List.of(new AcpConfigOption.Choice("plan", "Plan mode"))));
                window.showConnected(AcpClient.AcpAgentInfo.UNKNOWN, options, false, AcpClient.McpAttachment.NONE);
                final var buttons = component(window, "composerOptionButtons", java.util.Map.class);
                assertEquals(5, buttons.size());
                for (String id : List.of("model", "reasoning_effort", "approval_policy")) {
                    assertEquals(null, ((JButton) buttons.get(id)).getIcon());
                }
                for (String id : List.of("fast_mode", "session_mode")) {
                    final JButton mode = (JButton) buttons.get(id);
                    assertEquals("", mode.getText());
                    assertTrue(mode.getIcon() != null);
                    assertTrue(mode.getToolTipText().contains(id.equals("fast_mode") ? "Normal speed" : "Plan mode"));
                    assertTrue(mode.getAccessibleContext().getAccessibleName().contains(":"));
                }
                final JButton model = (JButton) buttons.get("model");
                assertTrue(model.isEnabled());
                assertEquals(AcpChatTheme.foreground(), model.getForeground());
                ((JMenuItem) AcpThemeControls.attachedPopup(model).getComponent(1)).doClick();
                assertEquals(List.of("model", "model-b"), requested.get());
                assertFalse(model.isEnabled());
                assertEquals("Model A ▾", model.getText());
                window.showConfigFailure("model", options);
                assertEquals("Model A ▾", ((JButton) buttons.get("model")).getText());
                assertTrue(((JButton) buttons.get("model")).isEnabled());
                window.showConfigOptions(modelOptions("model-b", "Model B"));
                final JPanel controls = component(window, "composerOptions", JPanel.class);
                assertEquals(3, controls.getComponentCount());
                assertEquals(
                        0,
                        java.util.Arrays.stream(controls.getComponents())
                                .filter(child -> !child.isEnabled())
                                .count());
                assertFalse(component(window, "composerOptionButtons", java.util.Map.class)
                        .containsKey("fast_mode"));
                window.showConfigOptions(List.of(new AcpConfigOption(
                        "session_mode",
                        "Mode",
                        "code",
                        List.of(
                                new AcpConfigOption.Choice("code", "Code"),
                                new AcpConfigOption.Choice("ask", "Ask")))));
                final JButton mode = (JButton) buttons.get("session_mode");
                assertEquals("Code ▾", mode.getText());
                assertEquals(null, mode.getIcon());
            } finally {
                window.dispose();
            }
        });
    }

    @Test
    void composerAgentSelectionConnectsTheChosenAgentAndCannotSwitchDuringAReply() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            final AcpChatWindow window = new AcpChatWindow(localization(), "", "", "");
            try {
                final java.util.concurrent.atomic.AtomicReference<String> requested =
                        new java.util.concurrent.atomic.AtomicReference<>();
                window.bind(
                        () -> requested.set(window.agentId()),
                        ignored -> {},
                        () -> {},
                        (id, value) -> {},
                        () -> {},
                        ignored -> {},
                        () -> {},
                        () -> {},
                        ignored -> {},
                        () -> {},
                        () -> {},
                        () -> {});
                final JButton agent = component(window, "composerAgent", JButton.class);
                assertEquals(AcpAgentIcons.icon(window.agentId()), agent.getIcon());
                window.showDetectedAgents(java.util.Map.of("claude", "/bin/claude", "codex", "/bin/codex", "pi", ""));
                assertEquals(AcpChatTheme.foreground(), agent.getForeground());
                assertEquals(3, window.agentSelector().getItemCount());
                assertEquals("Claude Code ▾", agent.getText());
                final javax.swing.JPopupMenu agentMenu = AcpThemeControls.attachedPopup(agent);
                assertEquals(4, agentMenu.getComponentCount());
                assertEquals("Claude Code  ✓", ((JMenuItem) agentMenu.getComponent(0)).getText());
                for (int i = 0; i < 2; i++) {
                    assertEquals(
                            AcpAgentIcons.icon(AgentCatalog.profiles().get(i).id()),
                            ((JMenuItem) agentMenu.getComponent(i)).getIcon());
                }
                ((JMenuItem) AcpThemeControls.attachedPopup(agent).getComponent(1)).doClick();
                assertEquals(AgentCatalog.profiles().get(1).id(), requested.get());
                assertEquals(requested.get(), window.agentId());
                window.showConnected(AcpClient.AcpAgentInfo.UNKNOWN, List.of(), false, AcpClient.McpAttachment.NONE);
                window.showPrompting();
                assertFalse(agent.isEnabled());
                ((JMenuItem) AcpThemeControls.attachedPopup(agent).getComponent(0)).doClick();
                assertEquals(requested.get(), window.agentId());
            } finally {
                window.dispose();
            }
        });
    }

    @Test
    void sendAndStopShareOnePositionAndDispatchTheActionForTheCurrentState() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            final AcpChatWindow window = new AcpChatWindow(localization(), "", "", "");
            try {
                final java.util.concurrent.atomic.AtomicReference<String> sent =
                        new java.util.concurrent.atomic.AtomicReference<>();
                final java.util.concurrent.atomic.AtomicInteger stopped =
                        new java.util.concurrent.atomic.AtomicInteger();
                window.bind(
                        () -> {},
                        text -> {
                            sent.set(text);
                            window.showPrompting();
                        },
                        stopped::incrementAndGet,
                        (id, value) -> {},
                        () -> {},
                        ignored -> {},
                        () -> {},
                        () -> {},
                        ignored -> {},
                        () -> {},
                        () -> {},
                        () -> {});
                window.showConnected(AcpClient.AcpAgentInfo.UNKNOWN, List.of(), false, AcpClient.McpAttachment.NONE);
                final JButton send = component(window, "send", JButton.class);
                final JButton stop = component(window, "cancel", JButton.class);
                assertTrue(send.isVisible());
                assertFalse(stop.isVisible());
                assertFalse(send.isEnabled());
                component(window, "prompt", JTextArea.class).setText("检查模型");
                window.showAgentAndFront();
                final java.awt.Rectangle bounds = send.getBounds();
                send.doClick();
                assertEquals("检查模型", sent.get());
                assertFalse(send.isVisible());
                assertTrue(stop.isVisible());
                assertEquals(bounds, stop.getBounds());
                stop.doClick();
                assertEquals(1, stopped.get());
                window.showPromptComplete("end_turn");
                assertTrue(send.isVisible());
                assertFalse(stop.isVisible());
                assertFalse(send.isEnabled());
                window.showPrompting();
                window.showFailure("status.disconnected");
                assertTrue(send.isVisible());
                assertFalse(stop.isVisible());
                assertFalse(send.isEnabled());
            } finally {
                window.dispose();
            }
        });
    }

    @Test
    void liveThemeRefreshUsesSemanticColorsAndPreservesDraftAndThinkingExpansion() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            final String[] keys = {
                "CubismCommon.background",
                "CubismCommon.surface",
                "CubismCommon.inputBackground",
                "CubismCommon.foreground",
                "CubismCommon.mutedForeground",
                "CubismCommon.blue",
                "CubismCommon.border"
            };
            final java.util.Map<String, Object> previous = new java.util.HashMap<>();
            for (String key : keys) previous.put(key, javax.swing.UIManager.get(key));
            final AcpChatWindow window = new AcpChatWindow(localization(), "", "", "");
            try {
                window.clearTranscript();
                window.appendUser("问题");
                window.appendThinking("保留展开的思考");
                final JTextPane transcript = component(window, "transcript", JTextPane.class);
                toggleDisclosureWithKeyboard(transcript, "Thinking");
                final JTextArea prompt = component(window, "prompt", JTextArea.class);
                prompt.setText("未发送的草稿");
                final Color[] palette = {
                    new Color(0x171719),
                    new Color(0x232327),
                    new Color(0x202024),
                    new Color(0xE4E4E7),
                    new Color(0xA1A1AA),
                    new Color(0x78B8F8),
                    new Color(0x38383F)
                };
                for (int index = 0; index < keys.length; index++)
                    javax.swing.UIManager.put(keys[index], palette[index]);
                window.refreshTheme();
                assertEquals(palette[0], transcript.getBackground());
                assertEquals(palette[2], prompt.getBackground());
                assertEquals(palette[3], prompt.getForeground());
                assertEquals(palette[5], AcpChatTheme.accent());
                assertEquals(palette[6], AcpChatTheme.border());
                assertEquals("未发送的草稿", prompt.getText());
                assertTrue(transcript.getText().contains("保留展开的思考"));
                assertEquals(
                        palette[1],
                        transcript
                                .getStyledDocument()
                                .getParagraphElement(0)
                                .getAttributes()
                                .getAttribute(AcpTranscriptEditorKit.USER_BACKGROUND));
            } finally {
                window.dispose();
                for (String key : keys) {
                    if (previous.get(key) == null)
                        javax.swing.UIManager.getDefaults().remove(key);
                    else javax.swing.UIManager.put(key, previous.get(key));
                }
            }
        });
    }

    @Test
    void thinkingSectionsMergeIntoUnifiedToolActivityGroupsAndKeepChronologicalOrder() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            final AcpChatWindow window = new AcpChatWindow(localization(), "", "", "");
            try {
                window.clearTranscript();
                window.appendUser("检查模型");
                window.appendThinking("**第一段");
                window.appendThinking("思考**");
                final JTextPane transcript = component(window, "transcript", JTextPane.class);
                assertTrue(transcript.getText().contains("▸  Thinking"));
                assertFalse(transcript.getText().contains("第一段思考"));
                toggleDisclosureWithKeyboard(transcript, "Thinking");
                window.appendThinking("，继续分析");
                assertTrue(transcript.getText().contains("第一段思考，继续分析"));
                window.appendTool("one", "读取模型", "read", "pending");
                window.appendThinking("第二段思考");
                window.appendTool("two", "检查参数", "read", "completed");
                window.appendAgent("最终结论");
                String text = transcript.getText();
                // Summary omits Thinking and shows nested Thinking disclosures inside the expanded group.
                assertFalse(text.contains("Thinking · Tools: 2"));
                assertTrue(text.contains("▾  Tools: 2  ·  1 running"));
                assertEquals(2, text.split("▸  Thinking", -1).length - 1);
                assertFalse(text.contains("第一段思考"));
                assertFalse(text.contains("第二段思考"));
                toggleDisclosureWithKeyboard(transcript, "▸  Thinking");
                text = transcript.getText();
                assertTrue(text.contains("▾  Thinking"));
                assertTrue(text.indexOf("第一段思考") < text.indexOf("读取模型"));
                assertTrue(text.indexOf("读取模型") < text.indexOf("▸  Thinking"));
                assertTrue(text.indexOf("▸  Thinking") < text.indexOf("检查参数"));

                window.updateTool("one", "completed", "读取完成");
                assertTrue(transcript.getText().contains("读取完成"));
                toggleDisclosureWithKeyboard(transcript, "Tools: 2");
                text = transcript.getText();
                assertTrue(text.contains("▸  Tools: 2"));
                assertFalse(text.contains("Thinking"));
                assertFalse(text.contains("第一段思考"));
                assertFalse(text.contains("第二段思考"));
                assertTrue(text.contains("最终结论"));

                // A later turn after an agent message starts a separate independent group.
                window.appendThinking("下一轮思考");
                assertEquals(1, transcript.getText().split("Thinking", -1).length - 1);

                window.clearTranscript();
                window.appendThinking("新会话思考");
                assertFalse(transcript.getText().contains("新会话思考"));
            } finally {
                window.dispose();
            }
        });
    }

    @Test
    void scrollableModelPopupTodoTpsPillsTimestampsAndPermissionControlsWorkTogether() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            final java.util.concurrent.atomic.AtomicLong clock =
                    new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
            final AcpChatWindow window = new AcpChatWindow(localization(), "codex", "", "", clock::get);
            try {
                final var selectedPermMode =
                        new java.util.concurrent.atomic.AtomicReference<>(AcpChatController.PermissionMode.ASK);
                final java.util.concurrent.atomic.AtomicReference<List<String>> configUpdates =
                        new java.util.concurrent.atomic.AtomicReference<>();
                window.bind(
                        () -> {},
                        ignored -> {},
                        () -> {},
                        (id, value) -> configUpdates.set(List.of(id, value)),
                        () -> {},
                        ignored -> {},
                        () -> {},
                        () -> {},
                        ignored -> {},
                        () -> {},
                        () -> {},
                        () -> {});
                window.bindExtendedControls(selectedPermMode::set);

                final java.util.List<AcpConfigOption.Choice> manyModels = new java.util.ArrayList<>();
                for (int i = 1; i <= 15; i++) {
                    manyModels.add(
                            new AcpConfigOption.Choice("mistral/model-" + i, "Model " + i, "mistral/model-" + i));
                }
                final List<AcpConfigOption> options = List.of(
                        new AcpConfigOption("model", "Model", "mistral/model-1", manyModels),
                        new AcpConfigOption(
                                "effort", "Effort", "high", List.of(new AcpConfigOption.Choice("high", "High"))));
                window.showConnected(AcpClient.AcpAgentInfo.UNKNOWN, options, false, AcpClient.McpAttachment.NONE);

                // Transcript scrollbar is visible with positive width instead of 0x0.
                final var scroll = component(window, "transcriptScroll", javax.swing.JScrollPane.class);
                assertTrue(scroll.getVerticalScrollBar().getPreferredSize().width > 0);

                final JButton modelBtn = (JButton) component(window, "composerOptionButtons", java.util.Map.class)
                        .get("model");
                final JButton permBtn = component(window, "composerPermissionMode", JButton.class);

                // 15-choice model menu uses bounded scrollable popup, channel subtitle, agent icon, and keyboard/mouse
                // selection.
                assertEquals(null, modelBtn.getComponentPopupMenu());
                final var modelPopup = (AcpThemeControls.ThemedPopupMenu) AcpThemeControls.attachedPopup(modelBtn);
                assertTrue(modelPopup.isScrollableMenu());
                assertTrue(modelPopup.getPreferredSize().height <= 340);
                final var secondItem = modelPopup.selectableItems().get(1);
                assertEquals("mistral/model-2", secondItem.secondaryLabel());
                assertEquals(AcpAgentIcons.icon("codex"), secondItem.getIcon());

                // Keyboard Down + Enter selects the highlighted second model and applies it.
                modelPopup.prepareForShow();
                modelPopup.moveHighlight(1);
                modelPopup.commitHighlighted();
                assertEquals(List.of("model", "mistral/model-2"), configUpdates.get());
                window.showConfigOptions(options);

                // Filtering narrows visible choices ("model-10".."model-15" plus "model-1"), then Down/Up/Enter
                // navigates only the filtered matches and commits the exact filtered choice.
                final JButton filterModelBtn = (JButton) component(window, "composerOptionButtons", java.util.Map.class)
                        .get("model");
                final var filterPopup =
                        (AcpThemeControls.ThemedPopupMenu) AcpThemeControls.attachedPopup(filterModelBtn);
                filterPopup.prepareForShow();
                final var filterInput = filterPopup.filterInput();
                filterInput.setText("model-1");
                // Filtered visible set: model-1 (index 0), model-10 (index 1), model-11 (index 2), ...
                // Invoke the filter field's InputMap/ActionMap bindings: Down twice -> model-11, Up once -> model-10,
                // Enter -> commit.
                for (int keyCode : List.of(KeyEvent.VK_DOWN, KeyEvent.VK_DOWN, KeyEvent.VK_UP, KeyEvent.VK_ENTER)) {
                    final Object actionKey = filterInput
                            .getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                            .get(KeyStroke.getKeyStroke(keyCode, 0));
                    assertTrue(actionKey != null);
                    filterInput.getActionMap().get(actionKey).actionPerformed(new ActionEvent(filterInput, 0, "key"));
                }
                assertEquals(List.of("model", "mistral/model-10"), configUpdates.get());
                window.showConfigOptions(options);

                // Filtering to a non-first subset ("Model 5") highlights model-5 and wraps cleanly on Up/Down.
                final JButton singleFilterBtn =
                        (JButton) component(window, "composerOptionButtons", java.util.Map.class)
                                .get("model");
                final var singleFilterPopup =
                        (AcpThemeControls.ThemedPopupMenu) AcpThemeControls.attachedPopup(singleFilterBtn);
                singleFilterPopup.prepareForShow();
                final var singleInput = singleFilterPopup.filterInput();
                singleInput.setText("Model 5");
                for (int keyCode : List.of(KeyEvent.VK_DOWN, KeyEvent.VK_ENTER)) {
                    final Object actionKey = singleInput
                            .getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                            .get(KeyStroke.getKeyStroke(keyCode, 0));
                    singleInput.getActionMap().get(actionKey).actionPerformed(new ActionEvent(singleInput, 0, "key"));
                }
                assertEquals(List.of("model", "mistral/model-5"), configUpdates.get());
                window.showConfigOptions(options);

                // Mouse press + release on a scrollable row also selects and applies it cleanly.
                final JButton refreshedModelBtn =
                        (JButton) component(window, "composerOptionButtons", java.util.Map.class)
                                .get("model");
                final var refreshedPopup =
                        (AcpThemeControls.ThemedPopupMenu) AcpThemeControls.attachedPopup(refreshedModelBtn);
                final var thirdItem = refreshedPopup.selectableItems().get(2);
                thirdItem.setSize(220, 30);
                thirdItem.dispatchEvent(new MouseEvent(
                        thirdItem,
                        MouseEvent.MOUSE_PRESSED,
                        System.currentTimeMillis(),
                        0,
                        10,
                        10,
                        1,
                        false,
                        MouseEvent.BUTTON1));
                thirdItem.dispatchEvent(new MouseEvent(
                        thirdItem,
                        MouseEvent.MOUSE_RELEASED,
                        System.currentTimeMillis(),
                        0,
                        10,
                        10,
                        1,
                        false,
                        MouseEvent.BUTTON1));
                assertEquals(List.of("model", "mistral/model-3"), configUpdates.get());
                window.showConfigOptions(options);

                // Permission mode button switches to Auto mode.
                assertEquals("Ask permissions ▾", permBtn.getText());
                ((JMenuItem) AcpThemeControls.attachedPopup(permBtn).getComponent(1)).doClick();
                assertEquals(AcpChatController.PermissionMode.AUTO, selectedPermMode.get());
                assertEquals("Auto approve ▾", permBtn.getText());

                // Todo and TPS pills appear in composerTrack during/after generation.
                window.showPrompting();
                clock.addAndGet(300_000_000L);
                window.appendAgent("Generated reply chunk for speed meter");
                clock.addAndGet(500_000_000L);
                window.recordUsageTokens(40L);
                window.showPlan(List.of(
                        new AcpPlanTracker.Item("Inspect parameters", "", AcpPlanTracker.Status.COMPLETED),
                        new AcpPlanTracker.Item(
                                "Apply folders", "Applying folders", AcpPlanTracker.Status.IN_PROGRESS)));
                window.showPromptComplete("end_turn");

                final JButton todoPill = component(window, "todoPill", JButton.class);
                final JButton tpsPill = component(window, "tpsPill", JButton.class);
                assertTrue(todoPill.isVisible());
                assertTrue(todoPill.getText().contains("1/2"));
                assertTrue(todoPill.getText().contains("Applying folders"));
                assertTrue(tpsPill.isVisible());
                assertTrue(tpsPill.getText().endsWith("tok/s"));
                assertFalse(tpsPill.getText().contains(" tok · "));

                // User message panel centers text vertically inside its painted card bounds, with timestamp below.
                window.showAgentAndFront();
                window.appendUser("测试垂直居中");
                final JTextPane transcript = component(window, "transcript", JTextPane.class);
                transcript.setSize(600, 400);
                transcript.doLayout();
                try {
                    final String rendered = transcript.getText();
                    assertTrue(
                            java.util.regex.Pattern.compile("[A-Za-z]+ \\d{2}:\\d{2}")
                                    .matcher(rendered)
                                    .find(),
                            "Expected local weekday + HH:mm timestamp in transcript");
                    final int userOffset = rendered.indexOf("测试垂直居中");
                    final var paraElem = transcript.getStyledDocument().getParagraphElement(userOffset);
                    final var charBounds = transcript.modelToView2D(userOffset);
                    final var endBounds = transcript.modelToView2D(paraElem.getEndOffset() - 1);
                    final double cardTop = charBounds.getY() - 16.0 + 8.0;
                    final double cardBottom = endBounds.getMaxY() + 14.0 - 6.0;
                    final double topPad = charBounds.getY() - cardTop;
                    final double bottomPad = cardBottom - charBounds.getMaxY();
                    assertEquals(topPad, bottomPad, 1.0);

                    // Timestamp paragraph below user message is right-aligned and has no card background.
                    final var tsPara = transcript.getStyledDocument().getParagraphElement(paraElem.getEndOffset());
                    assertEquals(
                            Boolean.FALSE, tsPara.getAttributes().getAttribute(AcpTranscriptEditorKit.USER_BACKGROUND));
                    assertEquals(StyleConstants.ALIGN_RIGHT, StyleConstants.getAlignment(tsPara.getAttributes()));
                } catch (javax.swing.text.BadLocationException failure) {
                    throw new AssertionError(failure);
                }
            } finally {
                window.dispose();
            }
        });
    }

    @Test
    void permissionDialogClosesWhenRequestLifetimeEnds() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        var window = new java.util.concurrent.atomic.AtomicReference<AcpChatWindow>();
        SwingUtilities.invokeAndWait(() -> window.set(new AcpChatWindow(localization(), "pi", "", "")));
        var shown = new java.util.concurrent.CompletableFuture<javax.swing.JDialog>();
        java.awt.event.AWTEventListener events = event -> {
            if (event.getID() == java.awt.event.WindowEvent.WINDOW_OPENED
                    && event.getSource() instanceof javax.swing.JDialog dialog
                    && "turboism-acp.permission".equals(dialog.getName())) shown.complete(dialog);
        };
        java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(events, java.awt.AWTEvent.WINDOW_EVENT_MASK);
        var request = new AcpListener.PermissionRequest(
                "write", "edit", "tool", "review", new AcpClient.PermissionOptionSet("allow", null, "reject"));
        try {
            var result = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> window.get().requestPermission(request));
            var dialog = shown.get(3, java.util.concurrent.TimeUnit.SECONDS);
            request.cancellation().cancel();
            assertEquals(
                    AcpListener.PermissionDecision.CANCELLED, result.get(3, java.util.concurrent.TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> assertFalse(dialog.isDisplayable()));
            assertEquals(AcpListener.PermissionDecision.CANCELLED, window.get().requestPermission(request));
        } finally {
            request.cancellation().cancel();
            java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(events);
            SwingUtilities.invokeAndWait(() -> window.get().dispose());
        }
    }

    private static void toggleSummaryWithKeyboard(final JTextPane transcript) {
        toggleDisclosureWithKeyboard(transcript, transcript.getText().contains("Commands:") ? "Commands:" : "Tools:");
    }

    private static void toggleDisclosureWithKeyboard(final JTextPane transcript, final String label) {
        transcript.setCaretPosition(transcript.getText().indexOf(label));
        final Object key = transcript.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0));
        transcript.getActionMap().get(key).actionPerformed(new ActionEvent(transcript, 0, "toggle"));
    }

    private static <T> T component(final AcpChatWindow window, final String name, final Class<T> type) {
        try {
            final Field field = AcpChatWindow.class.getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(window));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static PluginLocalization localization() {
        final ResourceBundle bundle = ResourceBundle.getBundle("META-INF/turboism/i18n/messages", Locale.ENGLISH);
        return new PluginLocalization() {
            @Override
            public Locale locale() {
                return Locale.ENGLISH;
            }

            @Override
            public String text(final String key) {
                return bundle.getString(key);
            }

            @Override
            public String format(final String key, final Object... args) {
                return new MessageFormat(text(key), locale()).format(args);
            }

            @Override
            public boolean contains(final String key) {
                return bundle.containsKey(key);
            }
        };
    }
}
