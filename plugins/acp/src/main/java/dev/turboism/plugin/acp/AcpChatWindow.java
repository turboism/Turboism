package dev.turboism.plugin.acp;

import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.window.TurboismWindowFactory;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.InputMethodEvent;
import java.awt.event.InputMethodListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.AttributedCharacterIterator;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/**
 * Paired plugin-owned Swing windows for agent conversation and settings.
 *
 * <p>The Agent window owns a typed, bounded live transcript and the session surface the connected
 * ACP agent advertises. Durable controls stay disabled when the agent advertises only an ephemeral
 * active session. Settings contains initial instructions; Agent setup is available from the composer.
 * Both windows share one controller and hide rather than
 * terminate it when closed; plugin lifecycle teardown owns the ACP process.</p>
 */
final class AcpChatWindow implements AcpChatController.View {

    private static final int MAX_TRANSCRIPT_CHARS = 1024 * 1024;
    private static final int MAX_TRANSCRIPT_ENTRIES = 4096;
    private static final int MAX_TOOL_METADATA_CHARS = 4096;
    private static final Pattern GRAPHEME = Pattern.compile("\\X");
    private static final String SEND_ACTION = "turboism-acp.send-prompt";
    private static final String DISCLOSURE = "turboism-acp.disclosure";
    private static final Dimension PERMISSION_DIALOG_MINIMUM = new Dimension(620, 360);
    private static final AgentItem CUSTOM_ITEM = new AgentItem(AgentCatalog.CUSTOM_AGENT_ID, null);

    static {
        installFallbackSwingUis(UIManager.getDefaults());
    }

    private final PluginLocalization localization;
    private final JFrame agentFrame;
    private final JFrame settingsFrame;
    private JDialog agentManager;
    private final JComboBox<AgentItem> agentSelector = new JComboBox<>();
    private final JTextField customCommand = new JTextField(30);
    private final JButton connect = new JButton();
    private final JButton detectAgents = new JButton();
    private final JButton agentLogin = new JButton();
    private final JButton agentLogout = new JButton();
    private final JLabel agentDetection = new JLabel();
    private final JLabel agentHint = new JLabel();
    private final JLabel settingsStatus = new JLabel();
    private final JLabel managerStatus = new JLabel();
    private final JTextArea initialPrompt = new AcpComposerPanel.PromptArea();
    private final JButton saveSettings = new JButton();
    private final JButton authenticate = new JButton();
    private final DefaultListModel<SessionItem> sessions = new DefaultListModel<>();
    private final JList<SessionItem> sessionList = new JList<>(sessions);
    private final JButton newSession = new JButton();
    private final JButton refreshSessions = new JButton();
    private final JButton openSettings = new JButton();
    private final Map<String, SessionItem> sessionDisplay = new LinkedHashMap<>();
    private final JTextPane transcript = new JTextPane();
    private final JTextArea prompt = new AcpComposerPanel.PromptArea();
    private final JButton send = new JButton();
    private final JButton attach = new AcpComposerPanel.OptionButton("");
    private final JPanel attachmentStrip = new JPanel(new AcpComposerPanel.WrapLayout());
    private final List<AcpAttachment> pendingAttachments = new ArrayList<>();
    private int attachmentLoads;
    private final Map<AcpAttachment, javax.swing.ImageIcon> attachmentPreviews = new LinkedHashMap<>();
    private final java.util.Set<AcpAttachment> loadingPreviews = new java.util.HashSet<>();
    private java.nio.file.Path attachmentBaseDirectory;
    private final JButton cancel = new JButton();
    private final JPanel submitSlot = new JPanel(new CardLayout());
    private final JButton composerAgent = new AcpComposerPanel.OptionButton("");
    private final JPanel composerOptions = new JPanel(new AcpComposerPanel.WrapLayout());
    private final Map<String, JButton> composerOptionButtons = new LinkedHashMap<>();
    private final JLabel connectionDot = new JLabel("●");
    private final JLabel agentStatus = new JLabel();
    private final java.util.function.LongSupplier taskClock;
    private final javax.swing.Timer taskTimer;
    private long taskStartedNanos;
    private long taskElapsedSeconds;
    private String taskState = "";
    private String taskError = "";
    private StatusTone currentStatusTone = StatusTone.ERROR;
    private final List<TranscriptEntry> transcriptEntries = new ArrayList<>();
    private final Map<String, List<TranscriptEntry>> tools = new LinkedHashMap<>();
    private final AtomicBoolean acceptingEvents = new AtomicBoolean(true);
    private Map<String, String> detectedPaths = Map.of();
    private List<AcpAuthMethod> authMethods = List.of();
    private boolean applyingOptions;
    private boolean applyingSessions;
    private boolean applyingAgents;
    private volatile boolean userSelectionDirty;
    private volatile boolean themeReady;
    private boolean connected;
    private boolean connecting;
    private boolean durableSessionsAvailable;
    private boolean prompting;
    private String submittedPrompt;
    private String lastLifecycleMessage = "";
    private int transcriptChars;
    private Runnable onConnect = () -> {};
    private Consumer<String> onPrompt = ignored -> {};
    private Runnable onCancel = () -> {};
    private BiConsumer<String, String> onConfig = (id, value) -> {};
    private Runnable onNewSession = () -> {};
    private Consumer<String> onSelectSession = ignored -> {};
    private Runnable onRefreshSessions = () -> {};
    private Runnable onDetectAgents = () -> {};
    private Consumer<String> onAuthenticate = ignored -> {};
    private Runnable onAgentLogin = () -> {};
    private Runnable onAgentLogout = () -> {};
    private Runnable onSaveSettings = () -> {};

    AcpChatWindow(
            final PluginLocalization localization,
            final String selectedAgentId,
            final String savedCustomCommand,
            final String savedInitialPrompt) {
        this(localization, selectedAgentId, savedCustomCommand, savedInitialPrompt, System::nanoTime);
    }

    AcpChatWindow(
            final PluginLocalization localization,
            final String selectedAgentId,
            final String savedCustomCommand,
            final String savedInitialPrompt,
            final java.util.function.LongSupplier taskClock) {
        this.taskClock = Objects.requireNonNull(taskClock, "taskClock");
        taskTimer = new javax.swing.Timer(500, event -> refreshTaskStatus());
        this.localization = Objects.requireNonNull(localization, "localization");
        agentFrame = frame("window.agent-title");
        settingsFrame = frame("window.settings-title");
        configureComponents(selectedAgentId, savedCustomCommand, savedInitialPrompt);
        configureFrames();
        bindComponentEvents();
        showFailure("status.disconnected");
        rebuildComposerOptions(List.of());
        refreshTheme();
        themeReady = true;
    }

    void bind(
            final Runnable connectAction,
            final Consumer<String> promptAction,
            final Runnable cancelAction,
            final BiConsumer<String, String> configAction,
            final Runnable newSessionAction,
            final Consumer<String> selectSessionAction,
            final Runnable refreshSessionsAction,
            final Runnable detectAgentsAction,
            final Consumer<String> authenticateAction,
            final Runnable agentLoginAction,
            final Runnable agentLogoutAction,
            final Runnable saveSettingsAction) {
        onConnect = Objects.requireNonNull(connectAction, "connectAction");
        onPrompt = Objects.requireNonNull(promptAction, "promptAction");
        onCancel = Objects.requireNonNull(cancelAction, "cancelAction");
        onConfig = Objects.requireNonNull(configAction, "configAction");
        onNewSession = Objects.requireNonNull(newSessionAction, "newSessionAction");
        onSelectSession = Objects.requireNonNull(selectSessionAction, "selectSessionAction");
        onRefreshSessions = Objects.requireNonNull(refreshSessionsAction, "refreshSessionsAction");
        onDetectAgents = Objects.requireNonNull(detectAgentsAction, "detectAgentsAction");
        onAuthenticate = Objects.requireNonNull(authenticateAction, "authenticateAction");
        onAgentLogin = Objects.requireNonNull(agentLoginAction, "agentLoginAction");
        onAgentLogout = Objects.requireNonNull(agentLogoutAction, "agentLogoutAction");
        onSaveSettings = Objects.requireNonNull(saveSettingsAction, "saveSettingsAction");
    }

    JComboBox<AgentItem> agentSelector() {
        return agentSelector;
    }

    String agentId() {
        final Object selected = agentSelector.getSelectedItem();
        return selected instanceof AgentItem item ? item.id() : AcpPluginSettings.defaultAgentId();
    }

    String customCommand() {
        return customCommand.getText().strip();
    }

    String initialPrompt() {
        return initialPrompt.getText();
    }

    boolean claimAutoConnect() {
        if (connected || connecting) return false;
        connecting = true;
        connect.setEnabled(false);
        return true;
    }

    void showAgentAndFront() {
        showAndFront(agentFrame, prompt);
    }

    void showSettingsAndFront() {
        showAndFront(settingsFrame, null);
    }

    void dispose() {
        if (!acceptingEvents.compareAndSet(true, false)) return;
        taskTimer.stop();
        agentFrame.dispose();
        settingsFrame.dispose();
        if (agentManager != null) agentManager.dispose();
    }

    @Override
    public void showConnecting(final String agentLabel) {
        if (!acceptingEvents.get()) return;
        connected = false;
        connecting = true;
        prompting = false;
        authenticate.setVisible(false);
        showLifecycleStatus("status.connecting", StatusTone.WORKING);
        connect.setText(localization.text("button.connecting"));
        connect.setEnabled(false);
        setConversationControls(false, false);
        transcript.requestFocusInWindow();
    }

    @Override
    public void showConnected(
            final AcpClient.AcpAgentInfo agentInfo,
            final List<AcpConfigOption> options,
            final boolean durableSessions,
            final AcpClient.McpAttachment mcpAttachment) {
        if (!acceptingEvents.get()) return;
        connected = true;
        connecting = false;
        durableSessionsAvailable = durableSessions;
        prompting = false;
        authenticate.setVisible(false);
        showConfigOptions(options);
        showLifecycleStatus("status.connected", StatusTone.CONNECTED);
        connect.setText(localization.text("button.reconnect"));
        connect.setEnabled(true);
        agentLogout.setEnabled(true);
        setConversationControls(true, false);
        final String display = agentInfo.displayName();
        final String version = agentInfo.version();
        if (!display.isBlank() || !version.isBlank()) {
            appendSystem(localization.format("transcript.agent-info", display, version.isBlank() ? "?" : version));
        }
        switch (mcpAttachment) {
            case STDIO -> appendSystem(localization.text("transcript.mcp-writable"));
            case HTTP_READ_ONLY -> appendSystem(localization.text("transcript.mcp-read-only"));
            case NONE -> appendSystem(localization.text("transcript.mcp-unavailable"));
        }
        prompt.requestFocusInWindow();
    }

    @Override
    public void showAuthRequired(final List<AcpAuthMethod> methods) {
        if (!acceptingEvents.get()) return;
        connected = false;
        connecting = false;
        prompting = false;
        authMethods = List.copyOf(Objects.requireNonNullElse(methods, List.of()));
        authenticate.setText(localization.text("button.sign-in"));
        authenticate.setVisible(!authMethods.isEmpty());
        showLifecycleStatus("status.auth-required", StatusTone.WORKING);
        connect.setText(localization.text("button.reconnect"));
        connect.setEnabled(true);
        agentLogout.setEnabled(false);
        setConversationControls(false, false);
        appendSystem(localization.text("transcript.auth-required"));
    }

    @Override
    public void showConfigOptions(final List<AcpConfigOption> options) {
        if (!acceptingEvents.get()) return;
        applyingOptions = true;
        try {
            rebuildComposerOptions(options);
        } finally {
            applyingOptions = false;
        }
    }

    @Override
    public void showConfigUpdating(final String optionId) {
        if (!acceptingEvents.get()) return;
        applyingOptions = true;
        composerOptionButtons.values().forEach(button -> button.setEnabled(false));
    }

    @Override
    public void showConfigFailure(final String optionId, final List<AcpConfigOption> confirmedOptions) {
        if (!acceptingEvents.get()) return;
        applyingOptions = false;
        showConfigOptions(confirmedOptions);
        showSessionFailure("status.config-failed");
    }

    @Override
    public void showSessions(
            final List<AcpSessionSummary> available, final String activeSessionId, final boolean durableSessions) {
        if (!acceptingEvents.get()) return;
        durableSessionsAvailable = durableSessions;
        final Map<String, AcpSessionSummary> unique = new LinkedHashMap<>();
        for (AcpSessionSummary summary : available) unique.putIfAbsent(summary.sessionId(), summary);
        if (activeSessionId != null && !unique.containsKey(activeSessionId)) {
            unique.put(activeSessionId, new AcpSessionSummary(activeSessionId, "unknown"));
        }
        applyingSessions = true;
        try {
            sessions.clear();
            int index = 1;
            SessionItem selected = null;
            for (AcpSessionSummary summary : unique.values()) {
                final SessionItem previous = sessionDisplay.get(summary.sessionId());
                final String fallback =
                        previous == null ? localization.format("session.label", index) : previous.label();
                final String date = sessionDateTime(summary.updatedAt(), ZoneId.systemDefault());
                index++;
                final SessionItem item = new SessionItem(
                        summary.sessionId(),
                        summary.title().isBlank() ? fallback : summary.title(),
                        date.isBlank() && previous != null ? previous.dateTime() : date);
                sessionDisplay.put(item.sessionId(), item);
                sessions.addElement(item);
                if (item.sessionId().equals(activeSessionId)) selected = item;
            }
            sessionDisplay.keySet().retainAll(unique.keySet());
            sessionList.setSelectedValue(selected, true);
        } finally {
            applyingSessions = false;
        }
        setConversationControls(connected, prompting);
    }

    @Override
    public void showDetectedAgents(final Map<String, String> detected) {
        if (!acceptingEvents.get()) return;
        detectedPaths = Map.copyOf(Objects.requireNonNullElse(detected, Map.of()));
        final String previous = agentId();
        applyingAgents = true;
        try {
            agentSelector.removeAllItems();
            for (AgentProfile profile : AgentCatalog.profiles()) {
                if (!detectedPaths.getOrDefault(profile.id(), "").isBlank())
                    agentSelector.addItem(new AgentItem(profile.id(), null));
            }
            agentSelector.addItem(CUSTOM_ITEM);
            selectAgentItem(previous);
        } finally {
            applyingAgents = false;
        }
        agentSelector.repaint();
        updateAgentDetail();
        managerStatus.setText(localization.text("status.detection-complete"));
    }

    @Override
    public boolean isUserSelectionDirty() {
        return userSelectionDirty;
    }

    @Override
    public void showAgentSelected(final String agentId) {
        if (!acceptingEvents.get() || userSelectionDirty) return;
        selectAgentItem(agentId);
        updateAgentDetail();
    }

    @Override
    public void clearTranscript() {
        if (!acceptingEvents.get()) return;
        transcriptEntries.clear();
        tools.clear();
        transcriptChars = 0;
        transcript.setText("");
    }

    @Override
    public void showPrompting() {
        if (!acceptingEvents.get()) return;
        if (!prompting) {
            taskStartedNanos = taskClock.getAsLong();
            taskElapsedSeconds = 0;
        }
        prompting = true;
        taskState = "working";
        taskError = "";
        taskTimer.start();
        if (submittedPrompt != null && prompt.getText().strip().equals(submittedPrompt)) {
            prompt.setText("");
        }
        if (submittedPrompt != null) {
            pendingAttachments.clear();
            attachmentPreviews.clear();
            refreshAttachmentStrip();
        }
        submittedPrompt = null;
        refreshTaskStatus();
        setConversationControls(true, true);
    }

    @Override
    public void showPromptComplete(final String stopReason) {
        if (!acceptingEvents.get()) return;
        prompting = false;
        finishTask("complete", "");
        setConversationControls(connected, false);
        prompt.requestFocusInWindow();
    }

    @Override
    public void showFailure(final String localizationKey) {
        if (!acceptingEvents.get()) return;
        final boolean wasWorking = taskState.equals("working");
        connected = false;
        connecting = false;
        durableSessionsAvailable = false;
        prompting = false;
        authMethods = List.of();
        authenticate.setVisible(false);
        final long elapsed = elapsedTaskSeconds();
        showLifecycleStatus(localizationKey, StatusTone.ERROR);
        if (wasWorking) {
            taskElapsedSeconds = elapsed;
            finishTask("failed", localization.text(localizationKey));
        }
        connect.setText(localization.text("button.connect"));
        connect.setEnabled(true);
        agentLogout.setEnabled(false);
        setConversationControls(false, false);
    }

    @Override
    public void showSessionFailure(final String localizationKey) {
        if (!acceptingEvents.get()) return;
        showSessionFailure(localizationKey, "");
    }

    @Override
    public void showSessionFailure(final String localizationKey, final String details) {
        if (!acceptingEvents.get()) return;
        prompting = false;
        final String error = details == null || details.isBlank() ? localization.text(localizationKey) : details;
        if (!taskState.equals("working")) taskElapsedSeconds = 0;
        finishTask("failed", error);
        connect.setEnabled(true);
        setConversationControls(connected, false);
    }

    @Override
    public void showDisconnected() {
        if (!acceptingEvents.get()) return;
        connected = false;
        connecting = false;
        prompting = false;
        showLifecycleStatus("status.disconnected", StatusTone.WORKING);
        connect.setText(localization.text("button.connect"));
        connect.setEnabled(true);
        agentLogout.setEnabled(false);
        setConversationControls(false, false);
    }

    @Override
    public void showSettingsSaved() {
        if (!acceptingEvents.get()) return;
        settingsStatus.setText(localization.text("status.settings-saved"));
    }

    @Override
    public void appendUser(final String text) {
        appendEntry(Sender.USER, null, null, null, text, false);
    }

    @Override
    public void appendAgent(final String text) {
        appendEntry(Sender.AGENT, null, null, null, text, true);
        if (!transcriptEntries.isEmpty()) loadMarkdownImages(transcriptEntries.get(transcriptEntries.size() - 1));
    }

    @Override
    public void appendThinking(final String text) {
        appendEntry(Sender.THINKING, null, null, null, text, true);
    }

    @Override
    public void appendTool(final String toolCallId, final String title, final String kind, final String status) {
        if (!acceptingEvents.get()) return;
        final TranscriptEntry entry = new TranscriptEntry(
                Sender.TOOL,
                Objects.requireNonNullElse(toolCallId, ""),
                boundedToolMetadata(title),
                boundedToolMetadata(kind),
                new StringBuilder());
        entry.toolStatus = Objects.requireNonNullElse(status, "");
        transcriptEntries.add(entry);
        transcriptChars += entry.weight();
        if (!entry.id.isBlank()) {
            tools.computeIfAbsent(entry.id, ignored -> new ArrayList<>()).add(entry);
        }
        trimTranscript();
        renderTranscript();
    }

    @Override
    public void updateTool(final String toolCallId, final String status, final String content) {
        if (!acceptingEvents.get()) return;
        final String exactToolCallId = Objects.requireNonNullElse(toolCallId, "");
        final List<TranscriptEntry> matching = tools.get(exactToolCallId);
        TranscriptEntry entry = matching == null || matching.isEmpty() ? null : matching.get(matching.size() - 1);
        if (entry == null) {
            appendTool(exactToolCallId, exactToolCallId, "tool", status);
            entry = transcriptEntries.get(transcriptEntries.size() - 1);
        }
        transcriptChars -= entry.weight();
        if (status != null && !status.isBlank()) entry.toolStatus = status;
        // ACP tool updates are partial: status-only updates must retain the existing output.
        if (content != null && !content.isEmpty()) {
            entry.content.setLength(0);
            entry.content.append(content);
        }
        transcriptChars += entry.weight();
        trimTranscript();
        renderTranscript();
    }

    @Override
    public void updateToolInput(final String toolCallId, final String input) {
        if (!acceptingEvents.get()) return;
        final List<TranscriptEntry> matching = tools.get(toolCallId);
        if (matching == null || matching.isEmpty()) return;
        final TranscriptEntry entry = matching.get(matching.size() - 1);
        transcriptChars -= entry.weight();
        entry.input = Objects.requireNonNullElse(input, "");
        transcriptChars += entry.weight();
        trimTranscript();
        renderTranscript();
    }

    @Override
    public AcpListener.PermissionDecision requestPermission(final AcpListener.PermissionRequest request) {
        if (!acceptingEvents.get()) return AcpListener.PermissionDecision.CANCELLED;
        if (SwingUtilities.isEventDispatchThread()) return permissionDialog(request);
        final AtomicReference<AcpListener.PermissionDecision> decision =
                new AtomicReference<>(AcpListener.PermissionDecision.CANCELLED);
        final CountDownLatch settled = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            try {
                decision.set(permissionDialog(request));
            } finally {
                settled.countDown();
            }
        });
        try {
            if (!settled.await(5, TimeUnit.MINUTES)) {
                return AcpListener.PermissionDecision.CANCELLED;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return AcpListener.PermissionDecision.CANCELLED;
        }
        return decision.get();
    }

    private void configureComponents(
            final String selectedAgentId, final String savedCustomCommand, final String savedInitialPrompt) {
        agentSelector.setName("turboism-acp.agent-selector");
        customCommand.setName("turboism-acp.custom-command");
        connect.setName("turboism-acp.connect");
        detectAgents.setName("turboism-acp.detect-agents");
        agentLogin.setName("turboism-acp.agent-login");
        agentLogout.setName("turboism-acp.agent-logout");
        agentDetection.setName("turboism-acp.agent-detection");
        agentHint.setName("turboism-acp.agent-hint");
        authenticate.setName("turboism-acp.authenticate");
        initialPrompt.setName("turboism-acp.initial-prompt");
        saveSettings.setName("turboism-acp.save-settings");
        settingsStatus.setName("turboism-acp.settings-status");
        sessionList.setName("turboism-acp.sessions");
        newSession.setName("turboism-acp.new-session");
        refreshSessions.setName("turboism-acp.refresh-sessions");
        openSettings.setName("turboism-acp.open-settings");
        transcript.setName("turboism-acp.transcript");
        prompt.setName("turboism-acp.prompt");
        send.setName("turboism-acp.send");
        cancel.setName("turboism-acp.cancel");
        agentStatus.setName("turboism-acp.status");
        attach.setName("turboism-acp.attach");
        attach.setIcon(AcpIcons.icon(AcpIcons.Kind.ATTACH));
        configureCompactButton(attach);
        attach.setToolTipText(localization.text("button.attach"));
        attach.getAccessibleContext().setAccessibleName(localization.text("button.attach"));
        attachmentStrip.setOpaque(false);
        attachmentStrip.setVisible(false);
        attachmentStrip.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(final java.awt.event.ComponentEvent event) {
                attachmentStrip.revalidate();
            }
        });

        for (AgentProfile profile : AgentCatalog.profiles()) {
            agentSelector.addItem(new AgentItem(profile.id(), null));
        }
        agentSelector.addItem(CUSTOM_ITEM);
        agentSelector.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    final JList<?> list,
                    final Object value,
                    final int index,
                    final boolean isSelected,
                    final boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof AgentItem item) {
                    setText(agentItemLabel(item));
                    setIcon(AcpAgentIcons.icon(item.id()));
                }
                return this;
            }
        });
        selectAgentItem(Objects.requireNonNullElse(selectedAgentId, ""));

        connect.setText(localization.text("button.connect"));
        detectAgents.setText(localization.text("button.detect-agents"));
        agentLogin.setText(localization.text("button.agent-login"));
        agentLogout.setText(localization.text("button.agent-logout"));
        authenticate.setText(localization.text("button.sign-in"));
        authenticate.setVisible(false);
        for (JButton button : List.of(send, cancel)) {
            button.setText("");
            button.setBorder(BorderFactory.createEmptyBorder());
            button.setContentAreaFilled(false);
            button.setPreferredSize(new Dimension(36, 36));
        }
        send.setIcon(AcpComposerPanel.submitIcon(false));
        cancel.setIcon(AcpComposerPanel.submitIcon(true));
        send.setToolTipText(localization.text("button.send"));
        cancel.setToolTipText(localization.text("button.stop"));
        send.getAccessibleContext().setAccessibleName(localization.text("button.send"));
        cancel.getAccessibleContext().setAccessibleName(localization.text("button.stop"));
        composerAgent.setName("turboism-acp.composer-agent");
        configureCompactButton(composerAgent);
        composerOptions.setOpaque(false);
        composerOptions.setMinimumSize(new Dimension(0, 32));
        composerOptions.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(final java.awt.event.ComponentEvent event) {
                composerOptions.revalidate();
            }
        });
        newSession.setText(localization.text("button.new-session-short"));
        newSession.setToolTipText(localization.text("button.new-session"));
        refreshSessions.setText(localization.text("button.refresh-short"));
        refreshSessions.setToolTipText(localization.text("button.refresh-sessions"));
        openSettings.setText("");
        openSettings.setIcon(AcpIcons.icon(AcpIcons.Kind.SETTINGS));
        openSettings.setForeground(AcpChatTheme.muted());
        openSettings.setToolTipText(localization.text("button.settings"));
        openSettings.getAccessibleContext().setAccessibleName(localization.text("button.settings"));
        saveSettings.setText(localization.text("button.save-settings"));
        customCommand.setText(Objects.requireNonNullElse(savedCustomCommand, ""));
        initialPrompt.setText(savedInitialPrompt);
        initialPrompt.putClientProperty(
                "JTextField.placeholderText", localization.text("settings.initial-prompt-placeholder"));
        send.setEnabled(false);
        cancel.setEnabled(false);
        newSession.setEnabled(false);
        refreshSessions.setEnabled(false);
        sessionList.setEnabled(false);
        sessionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sessionList.setCellRenderer(new SessionRenderer());
        sessionList.setFixedCellHeight(40);
        sessionList.setFixedCellWidth(0);
        sessionList.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        agentLogout.setEnabled(false);
        transcript.setEditorKit(new AcpTranscriptEditorKit());
        transcript.setEditable(false);
        transcript.setFont(UIManager.getFont("Label.font").deriveFont(Font.PLAIN));
        transcript.setBackground(color("TextArea.background", Color.WHITE));
        transcript.setForeground(color("TextArea.foreground", Color.BLACK));
        transcript.setMargin(new Insets(16, 24, 16, 24));
        prompt.setFont(transcript.getFont());
        prompt.setMargin(new Insets(10, 12, 10, 12));
        prompt.setRows(3);
        agentStatus.setForeground(secondaryForeground());
        for (JButton button : List.of(newSession, refreshSessions, openSettings)) {
            button.setMargin(new Insets(4, 8, 4, 8));
            button.setContentAreaFilled(false);
            button.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        }
        prompt.setLineWrap(true);
        prompt.setWrapStyleWord(true);
        prompt.putClientProperty("JTextField.placeholderText", localization.text("composer.placeholder"));
        initialPrompt.setLineWrap(true);
        initialPrompt.setWrapStyleWord(true);
        updateAgentDetail();
        configurePromptKeys(prompt, this::submitPrompt);
        configureDisclosures();
        configureAttachments();
    }

    private void configureFrames() {
        agentFrame.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        agentFrame.setMinimumSize(new Dimension(900, 640));
        agentFrame.setPreferredSize(new Dimension(1040, 720));
        agentFrame.setContentPane(agentContent());
        agentFrame.pack();
        agentFrame.setLocationByPlatform(true);
        settingsFrame.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        settingsFrame.setMinimumSize(new Dimension(480, 320));
        settingsFrame.setPreferredSize(new Dimension(560, 400));
        settingsFrame.setContentPane(settingsContent());
        settingsFrame.pack();
        settingsFrame.setLocationByPlatform(true);
    }

    private void bindComponentEvents() {
        connect.addActionListener(ignored -> onConnect.run());
        saveSettings.addActionListener(ignored -> onSaveSettings.run());
        detectAgents.addActionListener(ignored -> {
            detectAgents.setEnabled(false);
            managerStatus.setText(localization.text("status.detecting"));
            onDetectAgents.run();
            detectAgents.setEnabled(true);
        });
        agentLogin.addActionListener(ignored -> onAgentLogin.run());
        agentLogout.addActionListener(ignored -> onAgentLogout.run());
        authenticate.addActionListener(ignored -> offerAuthMethods());
        agentSelector.addActionListener(ignored -> {
            if (applyingAgents) return;
            userSelectionDirty = true;
            updateAgentDetail();
        });
        composerAgent.addActionListener(ignored -> {
            final JPopupMenu menu = composerAgent.getComponentPopupMenu();
            if (menu != null) menu.show(composerAgent, 0, composerAgent.getHeight());
        });
        prompt.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(final javax.swing.event.DocumentEvent event) {
                updateSendEnabled();
            }

            public void removeUpdate(final javax.swing.event.DocumentEvent event) {
                updateSendEnabled();
            }

            public void changedUpdate(final javax.swing.event.DocumentEvent event) {
                updateSendEnabled();
            }
        });
        customCommand.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                userSelectionDirty = true;
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                userSelectionDirty = true;
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                userSelectionDirty = true;
            }
        });
        send.addActionListener(ignored -> submitPrompt());
        cancel.addActionListener(ignored -> onCancel.run());
        newSession.addActionListener(ignored -> onNewSession.run());
        refreshSessions.addActionListener(ignored -> onRefreshSessions.run());
        openSettings.addActionListener(ignored -> showSettingsAndFront());
        sessionList.addListSelectionListener(ignored -> {
            if (ignored.getValueIsAdjusting() || applyingSessions) return;
            final SessionItem selected = sessionList.getSelectedValue();
            if (selected != null) onSelectSession.accept(selected.sessionId());
        });
    }

    private void offerAuthMethods() {
        if (authMethods.isEmpty()) return;
        if (authMethods.size() == 1) {
            onAuthenticate.accept(authMethods.get(0).id());
            return;
        }
        final JPopupMenu menu = new JPopupMenu();
        for (AcpAuthMethod method : authMethods) {
            final javax.swing.JMenuItem item = new javax.swing.JMenuItem(method.displayName());
            item.addActionListener(ignored -> onAuthenticate.accept(method.id()));
            menu.add(item);
        }
        menu.show(authenticate, 0, authenticate.getHeight());
    }

    private void selectAgentItem(final String agentId) {
        applyingAgents = true;
        try {
            for (int index = 0; index < agentSelector.getItemCount(); index++) {
                if (agentSelector.getItemAt(index).id().equals(agentId)) {
                    agentSelector.setSelectedIndex(index);
                    return;
                }
            }
            agentSelector.setSelectedIndex(0);
        } finally {
            applyingAgents = false;
        }
    }

    private String agentItemLabel(final AgentItem item) {
        if (AgentCatalog.CUSTOM_AGENT_ID.equals(item.id())) {
            return localization.text("agent.custom");
        }
        final AgentProfile profile = AgentCatalog.profile(item.id()).orElse(null);
        final String name = profile == null
                ? item.id()
                : profile.displayName().replace(" (ACP)", "").replace(" (agy-acp)", "");
        final String path = detectedPaths.get(item.id());
        return path == null ? name : name + (path.isEmpty() ? "" : "  ✓");
    }

    private void updateAgentDetail() {
        final String selected = agentId();
        composerAgent.setIcon(AcpAgentIcons.icon(selected));
        composerAgent.setText(agentItemLabel((AgentItem) agentSelector.getSelectedItem()) + " ▾");
        composerAgent.setToolTipText(localization.text("label.agent"));
        composerAgent
                .getAccessibleContext()
                .setAccessibleName(localization.text("label.agent") + ": " + composerAgent.getText());
        final JPopupMenu agentMenu = new JPopupMenu();
        for (int index = 0; index < agentSelector.getItemCount(); index++) {
            final AgentItem item = agentSelector.getItemAt(index);
            if (detectedPaths.getOrDefault(item.id(), "").isBlank()) continue;
            final javax.swing.JRadioButtonMenuItem choice = new javax.swing.JRadioButtonMenuItem(
                    agentItemLabel(item), item.id().equals(selected));
            choice.setIcon(AcpAgentIcons.icon(item.id()));
            choice.addActionListener(ignored -> {
                if (connecting
                        || prompting
                        || applyingOptions
                        || (connected && item.id().equals(agentId()))) return;
                agentSelector.setSelectedItem(item);
                if (AgentCatalog.CUSTOM_AGENT_ID.equals(item.id())
                        && customCommand().isBlank()) showAgentManager();
                else onConnect.run();
            });
            agentMenu.add(choice);
        }
        agentMenu.addSeparator();
        final javax.swing.JMenuItem manage = new javax.swing.JMenuItem(localization.text("button.manage-agents"));
        manage.setName("turboism-acp.manage-agents");
        manage.addActionListener(ignored -> showAgentManager());
        agentMenu.add(manage);
        composerAgent.setComponentPopupMenu(agentMenu);
        final boolean custom = AgentCatalog.CUSTOM_AGENT_ID.equals(selected);
        customCommand.setEnabled(custom);
        final AgentProfile profile = AgentCatalog.profile(selected).orElse(null);
        if (custom) {
            agentDetection.setText(localization.text("status.agent-custom"));
            agentHint.setText(localization.text("label.custom-command-detail"));
            agentLogin.setEnabled(false);
            return;
        }
        if (profile == null) {
            agentDetection.setText(localization.text("status.agent-unknown"));
            agentHint.setText("");
            agentLogin.setEnabled(false);
            return;
        }
        final String detected = detectedPaths.get(profile.id());
        if (detected == null) {
            agentDetection.setText(localization.text("status.agent-undetected"));
        } else if (detected.isEmpty()) {
            agentDetection.setText(localization.format(
                    "status.agent-not-found", profile.executableCandidates().get(0)));
        } else {
            agentDetection.setText(localization.format("status.agent-found", detected));
        }
        agentHint.setText(
                profile.installHint().isEmpty()
                        ? profile.homepage()
                        : localization.format("label.agent-install-hint", profile.installHint()));
        agentLogin.setEnabled(!profile.loginArguments().isEmpty());
    }

    private JPanel agentContent() {
        final JPanel root = new JPanel(new BorderLayout()) {
            @Override
            public void updateUI() {
                super.updateUI();
                if (themeReady) SwingUtilities.invokeLater(AcpChatWindow.this::refreshTheme);
            }
        };

        final JPanel sessionPanel = new JPanel(new BorderLayout());
        sessionPanel.setMinimumSize(new Dimension(180, 0));
        final JPanel sessionHeader = new JPanel(new BorderLayout(4, 0));
        sessionHeader.setBorder(BorderFactory.createEmptyBorder(10, 12, 8, 8));
        final JLabel sessionTitle = new JLabel(localization.text("label.sessions"));
        sessionTitle.setFont(sessionTitle.getFont().deriveFont(Font.BOLD));
        final JPanel sessionActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        sessionActions.add(newSession);
        sessionActions.add(refreshSessions);
        sessionHeader.add(sessionTitle, BorderLayout.WEST);
        sessionHeader.add(sessionActions, BorderLayout.EAST);
        sessionPanel.add(sessionHeader, BorderLayout.NORTH);
        final JScrollPane sessionScroll = new JScrollPane(sessionList);
        sessionScroll.setBorder(BorderFactory.createEmptyBorder());
        sessionScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sessionPanel.add(sessionScroll, BorderLayout.CENTER);

        final JPanel conversation = new JPanel(new BorderLayout(0, 0));
        final JScrollPane transcriptScroll = new JScrollPane(transcript);
        transcriptScroll.setBorder(BorderFactory.createEmptyBorder());
        conversation.add(transcriptScroll, BorderLayout.CENTER);
        conversation.add(composer(), BorderLayout.SOUTH);

        final JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sessionPanel, conversation);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setDividerSize(1);
        split.setContinuousLayout(true);
        if (split.getUI() instanceof javax.swing.plaf.basic.BasicSplitPaneUI ui) {
            ui.getDivider().setBorder(BorderFactory.createEmptyBorder());
            ui.getDivider().setBackground(outlineColor());
        }
        split.setResizeWeight(0.2);
        split.setDividerLocation(220);
        root.add(split, BorderLayout.CENTER);
        return root;
    }

    private JPanel composer() {
        final JPanel composer = new JPanel(new BorderLayout(0, 8));
        composer.setBorder(BorderFactory.createEmptyBorder(0, 16, 12, 16));
        composer.add(statusStrip(), BorderLayout.SOUTH);
        composer.add(promptPanel(), BorderLayout.CENTER);
        return composer;
    }

    private JPanel statusStrip() {
        final JPanel strip = new JPanel(new BorderLayout(8, 0));
        strip.setBorder(BorderFactory.createEmptyBorder(6, 0, 2, 0));
        final JPanel state = new JPanel(new BorderLayout(6, 0));
        connectionDot.setPreferredSize(new Dimension(12, connectionDot.getPreferredSize().height));
        connectionDot.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        state.add(connectionDot, BorderLayout.WEST);
        state.add(agentStatus, BorderLayout.CENTER);
        final JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.add(authenticate);
        actions.add(openSettings);
        strip.add(state, BorderLayout.CENTER);
        strip.add(actions, BorderLayout.EAST);
        return strip;
    }

    private JPanel settingsContent() {
        final JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        root.add(new JLabel(localization.text("settings.initial-prompt")), BorderLayout.NORTH);
        initialPrompt.setMargin(new Insets(10, 10, 10, 10));
        root.add(new JScrollPane(initialPrompt), BorderLayout.CENTER);
        final JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.add(settingsStatus, BorderLayout.CENTER);
        footer.add(saveSettings, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);
        return root;
    }

    void showAgentManager() {
        if (!acceptingEvents.get()) return;
        if (agentManager == null) {
            agentManager = new JDialog(agentFrame, localization.text("button.manage-agents"), false);
            agentManager.setName("turboism-acp.agent-manager");
            agentManager.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
            final JPanel content = new JPanel(new BorderLayout(0, 10));
            content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
            content.add(agentSection(), BorderLayout.CENTER);
            content.add(managerStatus, BorderLayout.SOUTH);
            agentManager.setContentPane(content);
            agentManager.setMinimumSize(new Dimension(600, 360));
            agentManager.pack();
            agentManager.setLocationRelativeTo(agentFrame);
            applyThemeTree(content);
        }
        agentManager.setVisible(true);
        agentManager.toFront();
    }

    private JPanel agentSection() {
        final JPanel panel = section("section.agent");
        final GridBagConstraints constraints = formConstraints();
        add(panel, constraints, localization.text("label.agent"), agentSelector);
        constraints.gridx = 2;
        constraints.weightx = 0;
        panel.add(detectAgents, constraints);
        constraints.gridy++;
        constraints.gridx = 0;
        constraints.gridwidth = 3;
        constraints.weightx = 1;
        panel.add(agentDetection, constraints);
        constraints.gridy++;
        add(panel, constraints, localization.text("label.custom-command"), customCommand);
        constraints.gridy++;
        constraints.gridx = 0;
        constraints.gridwidth = 3;
        constraints.weightx = 1;
        panel.add(new JLabel("<html>" + localization.text("label.custom-command-detail") + "</html>"), constraints);
        constraints.gridy++;
        constraints.gridx = 0;
        constraints.gridwidth = 3;
        constraints.weightx = 1;
        panel.add(agentHint, constraints);
        constraints.gridy++;
        constraints.gridx = 0;
        constraints.gridwidth = 1;
        constraints.weightx = 0;
        final JPanel authButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        authButtons.add(agentLogin);
        authButtons.add(agentLogout);
        panel.add(authButtons, constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        panel.add(new JLabel("<html>" + localization.text("label.agent-login-detail") + "</html>"), constraints);
        constraints.gridx = 2;
        constraints.weightx = 0;
        panel.add(connect, constraints);
        constraints.gridy++;
        constraints.gridx = 0;
        constraints.gridwidth = 3;
        constraints.weightx = 1;
        panel.add(new JLabel("<html>" + localization.text("label.agent-external-detail") + "</html>"), constraints);
        return panel;
    }

    private JPanel section(final String titleKey) {
        final JPanel panel = new JPanel(new GridBagLayout());
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setBorder(BorderFactory.createTitledBorder(localization.text(titleKey)));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return panel;
    }

    private JPanel promptPanel() {
        final JPanel panel = new AcpComposerPanel();
        panel.setLayout(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        final JScrollPane promptScroll = new JScrollPane(prompt);
        promptScroll.setBorder(BorderFactory.createEmptyBorder());
        promptScroll.setOpaque(false);
        promptScroll.getViewport().setOpaque(false);
        prompt.setOpaque(false);
        panel.add(promptScroll, BorderLayout.CENTER);
        panel.add(attachmentStrip, BorderLayout.NORTH);
        submitSlot.setOpaque(false);
        submitSlot.add(send, "send");
        submitSlot.add(cancel, "stop");
        final JPanel toolbar = new JPanel(new BorderLayout(8, 0));
        toolbar.setOpaque(false);
        toolbar.add(composerOptions, BorderLayout.CENTER);
        toolbar.add(submitSlot, BorderLayout.EAST);
        panel.add(toolbar, BorderLayout.SOUTH);
        return panel;
    }

    private static void configureCompactButton(final JButton button) {
        button.setFont(button.getFont().deriveFont(Font.PLAIN));
        button.setContentAreaFilled(false);
        button.setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));
        button.putClientProperty("html.disable", true);
    }

    private void rebuildComposerOptions(final List<AcpConfigOption> options) {
        composerOptions.removeAll();
        composerOptions.add(attach);
        composerOptions.add(composerAgent);
        composerOptionButtons.clear();
        final List<AcpConfigOption> ordered = new ArrayList<>(options);
        ordered.sort(java.util.Comparator.comparingInt(AcpChatWindow::composerOptionOrder));
        for (AcpConfigOption option : ordered) {
            final String value = option.choices().stream()
                    .filter(choice -> choice.value().equals(option.currentValue()))
                    .map(AcpConfigOption.Choice::name)
                    .findFirst()
                    .orElse(option.currentValue());
            final int role = composerOptionOrder(option);
            final boolean iconOnly = role == 3 || role == 4;
            final JButton button = new AcpComposerPanel.OptionButton(iconOnly ? "" : value + " ▾");
            configureCompactButton(button);
            button.setIcon(composerOptionIcon(role));
            button.setName("turboism-acp.composer-option." + option.id());
            final boolean active = (role == 3 || role == 4)
                    && java.util.Set.of("fast", "plan", "true", "on", "enabled")
                            .contains(option.currentValue().toLowerCase(java.util.Locale.ROOT));
            button.putClientProperty("acp.active-mode", active);
            button.setForeground(AcpChatTheme.foreground());
            button.setEnabled(connected && !prompting);
            button.setToolTipText(option.name() + ": " + value
                    + (option.description().isBlank() ? "" : " · " + option.description()));
            button.getAccessibleContext().setAccessibleName(option.name() + ": " + value);
            final Dimension size = button.getPreferredSize();
            button.setPreferredSize(
                    new Dimension(iconOnly ? 28 : Math.min(176, size.width), Math.max(28, size.height)));
            final JPopupMenu menu = new JPopupMenu();
            for (AcpConfigOption.Choice choice : option.choices()) {
                final javax.swing.JRadioButtonMenuItem item = new javax.swing.JRadioButtonMenuItem(
                        choice.name(), choice.value().equals(option.currentValue()));
                item.addActionListener(ignored -> {
                    if (!button.isEnabled() || applyingOptions || choice.value().equals(option.currentValue())) return;
                    showConfigUpdating(option.id());
                    onConfig.accept(option.id(), choice.value());
                });
                menu.add(item);
            }
            button.setComponentPopupMenu(menu);
            button.addActionListener(ignored -> menu.show(button, 0, button.getHeight()));
            composerOptionButtons.put(option.id(), button);
            composerOptions.add(button);
        }
        composerOptions.revalidate();
        composerOptions.repaint();
    }

    private static javax.swing.Icon composerOptionIcon(final int role) {
        return switch (role) {
            case 3 -> AcpIcons.icon(AcpIcons.Kind.FAST);
            case 4 -> AcpIcons.icon(AcpIcons.Kind.PLAN);
            default -> null;
        };
    }

    private static int composerOptionOrder(final AcpConfigOption option) {
        final String key = (option.category() + " " + option.id()).toLowerCase(java.util.Locale.ROOT);
        if (key.contains("model")) return 0;
        if (key.contains("effort") || key.contains("thought") || key.contains("thinking") || key.contains("reasoning"))
            return 1;
        if (key.contains("permission") || key.contains("approval")) return 2;
        if (key.contains("fast") || key.contains("speed")) return 3;
        if (key.contains("plan")
                || option.choices().stream().anyMatch(choice -> choice.value().equalsIgnoreCase("plan"))) return 4;
        return 5;
    }

    private AcpListener.PermissionDecision permissionDialog(final AcpListener.PermissionRequest request) {
        try {
            return showPermissionDialog(request);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            return AcpListener.PermissionDecision.CANCELLED;
        }
    }

    private AcpListener.PermissionDecision showPermissionDialog(final AcpListener.PermissionRequest request) {
        if (!acceptingEvents.get()) return AcpListener.PermissionDecision.CANCELLED;
        showAgentAndFront();
        final AtomicReference<AcpListener.PermissionDecision> decision =
                new AtomicReference<>(AcpListener.PermissionDecision.CANCELLED);
        final JDialog dialog = TurboismWindowFactory.dialog(agentFrame, localization.text("permission.title"), true);
        if (dialog == null) return AcpListener.PermissionDecision.CANCELLED;
        try {
            dialog.setName("turboism-acp.permission");
            dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            dialog.setMinimumSize(permissionDialogMinimum());

            final JTextArea details = new JTextArea(request.details(), 12, 68);
            details.setEditable(false);
            details.setLineWrap(true);
            details.setWrapStyleWord(true);
            details.setCaretPosition(0);
            final JPanel message = new JPanel(new BorderLayout(0, 10));
            message.setBorder(BorderFactory.createEmptyBorder(14, 14, 8, 14));
            final JTextArea summary =
                    new JTextArea(localization.format("permission.message", request.title(), request.kind()), 2, 68);
            summary.setEditable(false);
            summary.setLineWrap(true);
            summary.setWrapStyleWord(true);
            summary.setOpaque(false);
            message.add(summary, BorderLayout.NORTH);
            final JScrollPane detailScroll = new JScrollPane(details);
            detailScroll.setPreferredSize(new Dimension(640, 240));
            message.add(detailScroll, BorderLayout.CENTER);

            final JButton allowOnce = permissionButton(
                    "turboism-acp.permission.allow-once",
                    "permission.allow-once",
                    AcpListener.PermissionDecision.ALLOW_ONCE,
                    decision,
                    dialog);
            final JButton allowSession = permissionButton(
                    "turboism-acp.permission.allow-session",
                    "permission.allow-session",
                    AcpListener.PermissionDecision.ALLOW_ALWAYS,
                    decision,
                    dialog);
            final JButton reject = permissionButton(
                    "turboism-acp.permission.reject",
                    "permission.reject",
                    AcpListener.PermissionDecision.REJECT_ONCE,
                    decision,
                    dialog);
            if (request.options() != null) {
                allowOnce.setVisible(request.options().allowOnce() != null);
                allowSession.setVisible(request.options().allowAlways() != null);
                reject.setVisible(request.options().rejectOnce() != null);
            }
            final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
            buttons.add(reject);
            buttons.add(allowSession);
            buttons.add(allowOnce);

            final JPanel content = new JPanel(new BorderLayout());
            content.add(message, BorderLayout.CENTER);
            content.add(buttons, BorderLayout.SOUTH);
            dialog.setContentPane(content);
            dialog.getRootPane().setDefaultButton(reject);
            dialog.pack();
            ensureMinimumSize(dialog, permissionDialogMinimum());
            dialog.setLocationRelativeTo(agentFrame);
            dialog.setVisible(true);
            return decision.get();
        } finally {
            dialog.dispose();
        }
    }

    private JButton permissionButton(
            final String name,
            final String localizationKey,
            final AcpListener.PermissionDecision selected,
            final AtomicReference<AcpListener.PermissionDecision> decision,
            final JDialog dialog) {
        final JButton button = new JButton(localization.text(localizationKey));
        button.setName(name);
        button.addActionListener(ignored -> {
            decision.set(selected);
            dialog.dispose();
        });
        return button;
    }

    static Dimension permissionDialogMinimum() {
        return new Dimension(PERMISSION_DIALOG_MINIMUM);
    }

    static void ensureMinimumSize(final Component component, final Dimension minimum) {
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(minimum, "minimum");
        component.setSize(
                Math.max(component.getWidth(), minimum.width), Math.max(component.getHeight(), minimum.height));
    }

    private void submitPrompt() {
        if (!connected || prompting) return;
        final String text = prompt.getText().strip();
        if ((text.isEmpty() && pendingAttachments.isEmpty()) || attachmentLoads > 0) return;
        final int selected = sessionList.getSelectedIndex();
        if (selected >= 0) {
            final SessionItem current = sessions.get(selected);
            final String preview = (text.isEmpty() ? pendingAttachments.get(0).name() : text).replaceAll("\\s+", " ");
            final String fallback = localization.format("session.label", selected + 1);
            final String title = current.label().equals(fallback)
                    ? preview.substring(0, safePrefixLength(preview, Math.min(48, preview.length())))
                    : current.label();
            final SessionItem updated = new SessionItem(
                    current.sessionId(), title, sessionDateTime(Instant.now().toString(), ZoneId.systemDefault()));
            sessionDisplay.put(updated.sessionId(), updated);
            applyingSessions = true;
            try {
                sessions.set(selected, updated);
            } finally {
                applyingSessions = false;
            }
        }
        submittedPrompt = text;
        onPrompt.accept(text);
    }

    void setAttachmentBaseDirectory(final java.nio.file.Path directory) {
        attachmentBaseDirectory = directory.toAbsolutePath().normalize();
    }

    List<AcpAttachment> attachments() {
        return List.copyOf(pendingAttachments);
    }

    private void configureAttachments() {
        attach.addActionListener(ignored -> {
            final javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
            chooser.setMultiSelectionEnabled(true);
            if (chooser.showOpenDialog(agentFrame) == javax.swing.JFileChooser.APPROVE_OPTION) {
                final java.io.File[] files = chooser.getSelectedFiles();
                loadAttachments(() -> {
                    if (files.length > AcpAttachment.MAX_COUNT)
                        throw new IllegalArgumentException(localization.text("attachment.limit"));
                    final List<AcpAttachment> result = new ArrayList<>();
                    for (java.io.File file : files) result.add(AcpAttachment.fromPath(file.toPath()));
                    return result;
                });
            }
        });
        final javax.swing.TransferHandler textHandler = prompt.getTransferHandler();
        prompt.setTransferHandler(new javax.swing.TransferHandler() {
            @Override
            public boolean canImport(final TransferSupport support) {
                return !prompting
                        && (support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)
                                || support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.imageFlavor)
                                || textHandler.canImport(support));
            }

            @Override
            public boolean importData(final TransferSupport support) {
                if (prompting) return false;
                final var transfer = support.getTransferable();
                try {
                    if (transfer.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
                        final Object value =
                                transfer.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
                        if (!(value instanceof List<?> files)) return false;
                        final List<java.nio.file.Path> paths = new ArrayList<>();
                        for (Object file : files) if (file instanceof java.io.File f) paths.add(f.toPath());
                        loadAttachments(() -> {
                            if (paths.size() > AcpAttachment.MAX_COUNT)
                                throw new IllegalArgumentException(localization.text("attachment.limit"));
                            final List<AcpAttachment> result = new ArrayList<>();
                            for (var path : paths) result.add(AcpAttachment.fromPath(path));
                            return result;
                        });
                        return true;
                    }
                    if (transfer.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.imageFlavor)) {
                        final java.awt.Image image =
                                (java.awt.Image) transfer.getTransferData(java.awt.datatransfer.DataFlavor.imageFlavor);
                        loadAttachments(() -> List.of(AcpAttachment.fromClipboard(image)));
                        return true;
                    }
                    return textHandler.importData(support);
                } catch (Exception failure) {
                    attachmentFailure(failure);
                    return false;
                }
            }

            @Override
            public void exportToClipboard(
                    final javax.swing.JComponent source,
                    final java.awt.datatransfer.Clipboard clipboard,
                    final int action) {
                textHandler.exportToClipboard(source, clipboard, action);
            }

            @Override
            public int getSourceActions(final javax.swing.JComponent component) {
                return textHandler.getSourceActions(component);
            }
        });
    }

    private void loadAttachments(final java.util.concurrent.Callable<List<AcpAttachment>> loader) {
        if (prompting || attachmentLoads > 0) return;
        attachmentLoads++;
        attach.setEnabled(false);
        updateSendEnabled();
        new javax.swing.SwingWorker<List<AcpAttachment>, Void>() {
            @Override
            protected List<AcpAttachment> doInBackground() throws Exception {
                return loader.call();
            }

            @Override
            protected void done() {
                attachmentLoads--;
                if (!acceptingEvents.get()) return;
                try {
                    addAttachments(get());
                } catch (Exception failure) {
                    attachmentFailure(failure.getCause() == null ? failure : failure.getCause());
                }
                attach.setEnabled(!prompting && attachmentLoads == 0);
                updateSendEnabled();
            }
        }.execute();
    }

    void addAttachments(final List<AcpAttachment> attachments) {
        final long bytes = java.util.stream.Stream.concat(pendingAttachments.stream(), attachments.stream())
                .mapToLong(AcpAttachment::byteSize)
                .sum();
        if (pendingAttachments.size() + attachments.size() > AcpAttachment.MAX_COUNT || bytes > AcpAttachment.MAX_BYTES)
            throw new IllegalArgumentException(localization.text("attachment.limit"));
        pendingAttachments.addAll(attachments);
        refreshAttachmentStrip();
        updateSendEnabled();
    }

    private void refreshAttachmentStrip() {
        attachmentStrip.removeAll();
        for (AcpAttachment attachment : pendingAttachments) {
            final AcpAttachmentCard chip =
                    new AcpAttachmentCard(attachment, localization.format("attachment.remove", attachment.name()));
            chip.setPreview(attachmentPreviews.get(attachment));
            if (attachment.mimeType().startsWith("image/")
                    && !attachmentPreviews.containsKey(attachment)
                    && loadingPreviews.add(attachment)) {
                new javax.swing.SwingWorker<javax.swing.ImageIcon, Void>() {
                    @Override
                    protected javax.swing.ImageIcon doInBackground() throws Exception {
                        return attachment.preview();
                    }

                    @Override
                    protected void done() {
                        loadingPreviews.remove(attachment);
                        if (!acceptingEvents.get() || !pendingAttachments.contains(attachment)) return;
                        try {
                            attachmentPreviews.put(attachment, get());
                            refreshAttachmentStrip();
                        } catch (Exception failure) {
                            attachmentPreviews.put(attachment, null);
                        }
                    }
                }.execute();
            }
            chip.addActionListener(ignored -> {
                if (prompting) return;
                pendingAttachments.remove(attachment);
                attachmentPreviews.remove(attachment);
                refreshAttachmentStrip();
                updateSendEnabled();
            });
            attachmentStrip.add(chip);
        }
        attachmentStrip.setVisible(!pendingAttachments.isEmpty());
        attachmentStrip.revalidate();
        attachmentStrip.repaint();
    }

    private void attachmentFailure(final Throwable failure) {
        final String message = localization.format("attachment.error", AcpClient.failureDetails(failure));
        agentStatus.setText(message);
        agentStatus.setToolTipText(message);
    }

    private void loadMarkdownImages(final TranscriptEntry entry) {
        if (entry.attachment) return;
        final Matcher images = Pattern.compile("^!\\[[^\\]]*\\]\\(([^\\r\\n]+)\\)$", Pattern.MULTILINE)
                .matcher(entry.content);
        while (images.find() && entry.requestedImages.size() < 8) {
            final String reference = images.group(1);
            if (!entry.requestedImages.add(reference)) continue;
            final java.nio.file.Path path;
            try {
                java.nio.file.Path candidate = reference.startsWith("file:")
                        ? java.nio.file.Path.of(java.net.URI.create(reference))
                        : java.nio.file.Path.of(reference);
                if (!candidate.isAbsolute()) {
                    if (attachmentBaseDirectory == null || reference.contains("://")) continue;
                    candidate = attachmentBaseDirectory.resolve(candidate).normalize();
                }
                path = candidate;
            } catch (IllegalArgumentException failure) {
                continue;
            }
            new javax.swing.SwingWorker<javax.swing.ImageIcon, Void>() {
                @Override
                protected javax.swing.ImageIcon doInBackground() throws Exception {
                    return AcpAttachment.fromPath(path).preview();
                }

                @Override
                protected void done() {
                    if (!acceptingEvents.get() || !transcriptEntries.contains(entry)) return;
                    try {
                        final javax.swing.ImageIcon preview = get();
                        if (preview == null) return;
                        final int before = entry.weight();
                        entry.inlineImages.put(reference, preview);
                        transcriptChars += entry.weight() - before;
                        trimTranscript();
                        renderTranscript();
                    } catch (Exception failure) {
                        // Preserve the original Markdown reference when the local file cannot be previewed.
                    }
                }
            }.execute();
        }
    }

    @Override
    public void appendAttachment(final AcpAttachment attachment, final boolean user) {
        if (!acceptingEvents.get()) return;
        final TranscriptEntry entry = new TranscriptEntry(
                user ? Sender.USER : Sender.AGENT, "", "", "", new StringBuilder(attachment.name()));
        entry.attachment = true;
        transcriptEntries.add(entry);
        transcriptChars += entry.weight();
        trimTranscript();
        renderTranscript();
        if (!attachment.mimeType().startsWith("image/")) return;
        new javax.swing.SwingWorker<javax.swing.ImageIcon, Void>() {
            @Override
            protected javax.swing.ImageIcon doInBackground() throws Exception {
                return attachment.preview();
            }

            @Override
            protected void done() {
                if (!acceptingEvents.get() || !transcriptEntries.contains(entry)) return;
                try {
                    final int previousWeight = entry.weight();
                    entry.image = get();
                    transcriptChars += entry.weight() - previousWeight;
                    trimTranscript();
                } catch (Exception failure) {
                    entry.content.append(" · ").append(localization.text("attachment.preview-unavailable"));
                }
                renderTranscript();
            }
        }.execute();
    }

    /**
     * Installs the conversation composer contract: unmodified Enter submits, while Shift+Enter,
     * Ctrl+Enter, and Ctrl+Shift+Enter retain multiline editing. Enter remains available to the
     * platform input method while it owns uncommitted composition text.
     */
    static void configurePromptKeys(final JTextArea input, final Runnable submit) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(submit, "submit");
        input.enableInputMethods(true);
        final AtomicBoolean composing = new AtomicBoolean();
        input.addInputMethodListener(new InputMethodListener() {
            @Override
            public void inputMethodTextChanged(final InputMethodEvent event) {
                composing.set(hasUncommittedText(event.getText(), event.getCommittedCharacterCount()));
            }

            @Override
            public void caretPositionChanged(final InputMethodEvent event) {
                // Text-change callbacks own the composition lifetime.
            }
        });
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), SEND_ACTION);
        input.getActionMap().put(SEND_ACTION, new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent event) {
                if (!composing.get()) submit.run();
            }
        });
        final int[] newlineModifiers = {
            InputEvent.SHIFT_DOWN_MASK,
            InputEvent.CTRL_DOWN_MASK,
            InputEvent.SHIFT_DOWN_MASK | InputEvent.CTRL_DOWN_MASK
        };
        for (int modifiers : newlineModifiers) {
            input.getInputMap()
                    .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, modifiers), DefaultEditorKit.insertBreakAction);
        }
    }

    static boolean hasUncommittedText(final AttributedCharacterIterator text, final int committedCharacters) {
        return text != null && committedCharacters < text.getEndIndex() - text.getBeginIndex();
    }

    private void setConversationControls(final boolean connectionReady, final boolean busy) {
        updateSendEnabled();
        attach.setEnabled(!busy && attachmentLoads == 0);
        cancel.setEnabled(connectionReady && busy);
        ((CardLayout) submitSlot.getLayout()).show(submitSlot, busy ? "stop" : "send");
        composerAgent.setEnabled(!connecting && !busy);
        prompt.setEnabled(connectionReady && !busy);
        newSession.setEnabled(connectionReady && !busy);
        refreshSessions.setEnabled(connectionReady && !busy && durableSessionsAvailable);
        sessionList.setEnabled(connectionReady && !busy && durableSessionsAvailable);
        composerOptionButtons
                .values()
                .forEach(button -> button.setEnabled(connectionReady && !busy && !applyingOptions));
    }

    private void updateSendEnabled() {
        send.setEnabled(connected
                && !prompting
                && attachmentLoads == 0
                && (!prompt.getText().isBlank() || !pendingAttachments.isEmpty()));
    }

    /**
     * Updates the compact connection state and mirrors each state transition into the transcript.
     * Repeating the same callback is ignored so reconnect races cannot flood the conversation.
     */
    private void showLifecycleStatus(final String localizationKey, final StatusTone tone) {
        final String text = localization.text(localizationKey);
        currentStatusTone = tone;
        setStatusText(text, statusColor(tone));
        if (recordLifecycleMessage(lastLifecycleMessage, text)) {
            lastLifecycleMessage = text;
            appendSystem(text);
        }
    }

    static boolean recordLifecycleMessage(final String previous, final String next) {
        return next != null && !next.isEmpty() && !next.equals(previous);
    }

    private void setStatusText(final String text, final Color color) {
        taskTimer.stop();
        taskState = "";
        connectionDot.setText("●");
        agentStatus.setToolTipText(null);
        agentStatus.setText(text);
        managerStatus.setText(text);
        connectionDot.setForeground(color);
    }

    private long elapsedTaskSeconds() {
        return taskState.equals("working")
                ? Math.max(0, (taskClock.getAsLong() - taskStartedNanos) / 1_000_000_000L)
                : taskElapsedSeconds;
    }

    private void finishTask(final String state, final String error) {
        taskElapsedSeconds = elapsedTaskSeconds();
        taskTimer.stop();
        taskState = state;
        taskError = error.replaceAll("[\\r\\n\\t]+", " ").strip();
        refreshTaskStatus();
    }

    void refreshTaskStatus() {
        if (!acceptingEvents.get() || taskState.isEmpty()) return;
        final long seconds = elapsedTaskSeconds();
        final String duration = seconds >= 60 ? seconds / 60 + "m" + seconds % 60 + "s" : seconds + "s";
        final String text = localization.format("status.task-" + taskState, duration, taskError);
        agentStatus.setText(text);
        agentStatus.setToolTipText(text);
        managerStatus.setText(text);
        connectionDot.setForeground(taskState.equals("failed") ? AcpChatTheme.error() : AcpChatTheme.success());
        // Keep the dot's layout slot stable while blinking; do not hide the component.
        final boolean lit =
                !taskState.equals("working") || ((taskClock.getAsLong() - taskStartedNanos) / 500_000_000L) % 2 == 0;
        connectionDot.setText(lit ? "●" : " ");
    }

    static String selectedConfigValue(final JComboBox<AcpConfigOption.Choice> combo) {
        final Object selected = combo.getEditor().getItem();
        if (selected instanceof AcpConfigOption.Choice choice) {
            return "unavailable".equals(choice.value()) ? null : choice.value();
        }
        if (selected instanceof String text && !text.isBlank()) {
            return text;
        }
        return null;
    }

    private void appendSystem(final String text) {
        appendEntry(Sender.SYSTEM, null, null, null, text, false);
    }

    private void appendEntry(
            final Sender sender,
            final String id,
            final String title,
            final String kind,
            final String text,
            final boolean coalesce) {
        if (!acceptingEvents.get() || text == null || text.isEmpty()) return;
        final TranscriptEntry previous =
                transcriptEntries.isEmpty() ? null : transcriptEntries.get(transcriptEntries.size() - 1);
        if (coalesce && previous != null && previous.sender == sender && !previous.attachment) {
            previous.content.append(text);
            transcriptChars += text.length();
            if (trimTranscript()) {
                renderTranscript();
            } else if (sender != Sender.THINKING || previous.expanded) {
                appendRenderedText(previous, text);
            }
            return;
        }
        final TranscriptEntry entry = new TranscriptEntry(
                sender,
                Objects.requireNonNullElse(id, ""),
                Objects.requireNonNullElse(title, ""),
                Objects.requireNonNullElse(kind, ""),
                new StringBuilder(text));
        transcriptEntries.add(entry);
        transcriptChars += entry.weight();
        if (trimTranscript()) {
            renderTranscript();
        } else {
            appendRenderedEntry(entry);
        }
    }

    private boolean trimTranscript() {
        boolean changed = false;
        while ((transcriptChars > MAX_TRANSCRIPT_CHARS || transcriptEntries.size() > MAX_TRANSCRIPT_ENTRIES)
                && transcriptEntries.size() > 1) {
            final int removalIndex = transcriptRemovalIndex();
            final TranscriptEntry removed = transcriptEntries.remove(removalIndex);
            transcriptChars -= removed.weight();
            removeToolEntry(removed);
            changed = true;
        }
        if (transcriptChars > MAX_TRANSCRIPT_CHARS && !transcriptEntries.isEmpty()) {
            final TranscriptEntry first = transcriptEntries.get(0);
            final int excess = transcriptChars - MAX_TRANSCRIPT_CHARS;
            final int removed = safePrefixLength(first.content, excess);
            first.content.delete(0, removed);
            transcriptChars -= removed;
            changed |= removed > 0;
        }
        return changed;
    }

    private void removeToolEntry(final TranscriptEntry entry) {
        if (entry.id.isBlank()) return;
        final List<TranscriptEntry> matching = tools.get(entry.id);
        if (matching == null) return;
        matching.remove(entry);
        if (matching.isEmpty()) tools.remove(entry.id);
    }

    private int transcriptRemovalIndex() {
        for (int index = 0; index < transcriptEntries.size(); index++) {
            if (transcriptEntries.get(index).sender == Sender.THINKING && !transcriptEntries.get(index).expanded)
                return index;
        }
        return 0;
    }

    static int safePrefixLength(final CharSequence text, final int requested) {
        if (requested <= 0 || text.length() == 0) return 0;
        if (requested >= text.length()) return text.length();
        final Matcher graphemes = GRAPHEME.matcher(text);
        while (graphemes.find()) {
            if (graphemes.end() >= requested) return graphemes.end();
        }
        return text.length();
    }

    private void appendRenderedEntry(final TranscriptEntry entry) {
        final StyledDocument document = transcript.getStyledDocument();
        try {
            renderEntry(document, entry, document.getLength());
            transcript.setCaretPosition(document.getLength());
        } catch (javax.swing.text.BadLocationException failure) {
            renderTranscript();
        }
    }

    private void appendRenderedText(final TranscriptEntry entry, final String text) {
        // Reparse the whole current message: delimiters and fences can span ACP stream chunks.
        replaceRenderedEntry(entry, entry.renderedLength);
    }

    private void replaceRenderedEntry(final TranscriptEntry entry, final int previousRenderedLength) {
        final StyledDocument document = transcript.getStyledDocument();
        try {
            if (entry.renderedStart < 0
                    || entry.renderedLength != previousRenderedLength
                    || entry.renderedStart + entry.renderedLength > document.getLength()) {
                renderTranscript();
                return;
            }
            final int previousLength = entry.renderedLength;
            document.remove(entry.renderedStart, previousLength);
            renderEntry(document, entry, entry.renderedStart);
            shiftRenderedEntriesAfter(entry, entry.renderedLength - previousLength);
            transcript.setCaretPosition(document.getLength());
        } catch (javax.swing.text.BadLocationException failure) {
            renderTranscript();
        }
    }

    private void shiftRenderedEntriesAfter(final TranscriptEntry changed, final int delta) {
        if (delta == 0) return;
        boolean after = false;
        for (TranscriptEntry entry : transcriptEntries) {
            if (after && entry.renderedStart >= 0) entry.renderedStart += delta;
            if (entry == changed) after = true;
        }
    }

    private void renderTranscript() {
        final StyledDocument document = transcript.getStyledDocument();
        try {
            document.remove(0, document.getLength());
            for (TranscriptEntry entry : transcriptEntries) {
                entry.renderedStart = -1;
                entry.renderedLength = 0;
            }
            for (int index = 0; index < transcriptEntries.size(); ) {
                final TranscriptEntry entry = transcriptEntries.get(index++);
                if (entry.sender == Sender.TOOL) {
                    final List<TranscriptEntry> group = new ArrayList<>();
                    group.add(entry);
                    while (index < transcriptEntries.size()) {
                        final TranscriptEntry next = transcriptEntries.get(index);
                        if (next.sender == Sender.TOOL) group.add(next);
                        else break;
                        index++;
                    }
                    renderToolGroup(document, group);
                } else {
                    renderEntry(document, entry, document.getLength());
                }
            }
            transcript.setCaretPosition(document.getLength());
        } catch (javax.swing.text.BadLocationException failure) {
            transcript.setText("");
        }
    }

    private void renderEntry(final StyledDocument document, final TranscriptEntry entry, final int start)
            throws javax.swing.text.BadLocationException {
        if (entry.sender == Sender.THINKING) {
            renderThinking(document, entry, start);
            return;
        }
        final int before = document.getLength();
        if (entry.image != null) {
            final SimpleAttributeSet imageStyle = new SimpleAttributeSet();
            StyleConstants.setIcon(imageStyle, entry.image);
            document.insertString(start, " ", imageStyle);
            document.insertString(start + 1, "\n", contentAttributes(entry.sender));
        }
        AcpMessageRenderer.insert(
                document,
                document.getLength() == before ? start : start + 2,
                renderedEntry(entry),
                contentAttributes(entry.sender),
                entry.sender == Sender.AGENT,
                entry.inlineImages);
        entry.renderedStart = start;
        entry.renderedLength = document.getLength() - before;
        final SimpleAttributeSet message = new SimpleAttributeSet();
        message.addAttribute(
                AcpTranscriptEditorKit.USER_BACKGROUND,
                entry.sender == Sender.USER ? AcpChatTheme.surface() : Boolean.FALSE);
        message.addAttribute(
                AcpTranscriptEditorKit.USER_ACCENT, entry.sender == Sender.USER ? userAccent() : Boolean.FALSE);
        if (entry.sender == Sender.USER) {
            StyleConstants.setLeftIndent(message, 12F);
            StyleConstants.setRightIndent(message, 12F);
            StyleConstants.setSpaceAbove(message, 4F);
            StyleConstants.setSpaceBelow(message, 8F);
        }
        document.setParagraphAttributes(start, entry.renderedLength, message, false);
        final SimpleAttributeSet paragraph = new SimpleAttributeSet();
        StyleConstants.setLeftIndent(paragraph, entry.sender == Sender.USER ? 12F : 4F);
        StyleConstants.setRightIndent(paragraph, 12F);
        StyleConstants.setSpaceAbove(paragraph, 10F);
        StyleConstants.setSpaceBelow(paragraph, 4F);
        document.setParagraphAttributes(start, 1, paragraph, false);
        // Swing retains the last paragraph's attributes after its trailing newline.
        // Keep that empty paragraph neutral so later messages do not inherit a user panel.
        document.setParagraphAttributes(document.getLength(), 0, new SimpleAttributeSet(), true);
    }

    private void renderThinking(final StyledDocument document, final TranscriptEntry entry, final int start)
            throws javax.swing.text.BadLocationException {
        final int before = document.getLength();
        final SimpleAttributeSet style = contentAttributes(Sender.THINKING);
        StyleConstants.setForeground(style, secondaryForeground());
        StyleConstants.setItalic(style, false);
        final SimpleAttributeSet link = new SimpleAttributeSet(style);
        link.addAttribute(DISCLOSURE, entry);
        final String summary = (entry.expanded ? "▾  " : "▸  ") + localization.text("sender.thinking") + "\n";
        document.insertString(start, summary, link);
        final SimpleAttributeSet paragraph = new SimpleAttributeSet();
        paragraph.addAttribute(AcpTranscriptEditorKit.USER_BACKGROUND, Boolean.FALSE);
        paragraph.addAttribute(AcpTranscriptEditorKit.USER_ACCENT, Boolean.FALSE);
        StyleConstants.setLeftIndent(paragraph, 6F);
        StyleConstants.setSpaceAbove(paragraph, 8F);
        StyleConstants.setSpaceBelow(paragraph, 8F);
        document.setParagraphAttributes(start, summary.length(), paragraph, true);
        if (entry.expanded) {
            final int detailStart = start + summary.length();
            AcpMessageRenderer.insert(document, detailStart, entry.content.toString(), style, true);
            final SimpleAttributeSet detail = new SimpleAttributeSet(paragraph);
            StyleConstants.setLeftIndent(detail, 18F);
            StyleConstants.setSpaceAbove(detail, 2F);
            StyleConstants.setSpaceBelow(detail, 4F);
            document.setParagraphAttributes(
                    detailStart, document.getLength() - before - summary.length(), detail, true);
        }
        entry.renderedStart = start;
        entry.renderedLength = document.getLength() - before;
        document.setParagraphAttributes(document.getLength(), 0, new SimpleAttributeSet(), true);
    }

    private void renderToolGroup(final StyledDocument document, final List<TranscriptEntry> group)
            throws javax.swing.text.BadLocationException {
        final TranscriptEntry first = group.get(0);
        final int start = document.getLength();
        final long commands =
                group.stream().filter(entry -> "execute".equals(entry.kind)).count();
        final long running = group.stream()
                .filter(entry -> "pending".equals(entry.toolStatus) || "in_progress".equals(entry.toolStatus))
                .count();
        final long failed = group.stream()
                .filter(entry -> "failed".equals(entry.toolStatus))
                .count();
        final String summary = (first.expanded ? "▾  " : "▸  ")
                + localization.format("transcript.tools-summary", commands, group.size() - commands)
                + (running > 0 ? "  ·  " + localization.format("transcript.tools-running", running) : "")
                + (failed > 0 ? "  ·  " + localization.format("transcript.tools-failed", failed) : "") + "\n";
        final SimpleAttributeSet style = contentAttributes(Sender.TOOL);
        StyleConstants.setForeground(style, failed > 0 ? statusColor(StatusTone.ERROR) : secondaryForeground());
        final SimpleAttributeSet link = new SimpleAttributeSet(style);
        link.addAttribute(DISCLOSURE, first);
        document.insertString(start, summary, link);
        final SimpleAttributeSet paragraph = new SimpleAttributeSet();
        paragraph.addAttribute(AcpTranscriptEditorKit.USER_BACKGROUND, Boolean.FALSE);
        paragraph.addAttribute(AcpTranscriptEditorKit.USER_ACCENT, Boolean.FALSE);
        StyleConstants.setLeftIndent(paragraph, 6F);
        StyleConstants.setSpaceAbove(paragraph, 8F);
        StyleConstants.setSpaceBelow(paragraph, 8F);
        document.setParagraphAttributes(start, summary.length(), paragraph, true);
        if (first.expanded) {
            final SimpleAttributeSet code = new SimpleAttributeSet(style);
            StyleConstants.setFontFamily(code, Font.MONOSPACED);
            StyleConstants.setForeground(code, transcript.getForeground());
            for (TranscriptEntry entry : group) {
                final int detailStart = document.getLength();
                final String title = entry.title.isBlank() ? boundedToolMetadata(entry.id) : entry.title;
                final SimpleAttributeSet heading = new SimpleAttributeSet(style);
                StyleConstants.setBold(heading, true);
                if ("failed".equals(entry.toolStatus))
                    StyleConstants.setForeground(heading, statusColor(StatusTone.ERROR));
                document.insertString(document.getLength(), title + " · " + entry.toolStatus + "\n", heading);
                if (!entry.input.isBlank()) document.insertString(document.getLength(), entry.input + "\n", code);
                if (!entry.content.isEmpty()) document.insertString(document.getLength(), entry.content + "\n", code);
                final SimpleAttributeSet detail = new SimpleAttributeSet(paragraph);
                StyleConstants.setLeftIndent(detail, 18F);
                StyleConstants.setSpaceAbove(detail, 2F);
                StyleConstants.setSpaceBelow(detail, 4F);
                document.setParagraphAttributes(detailStart, document.getLength() - detailStart, detail, true);
            }
        }
        first.renderedStart = start;
        first.renderedLength = document.getLength() - start;
        document.setParagraphAttributes(document.getLength(), 0, new SimpleAttributeSet(), true);
    }

    private Color userAccent() {
        return AcpChatTheme.accent();
    }

    private void configureDisclosures() {
        final MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseClicked(final MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event)) {
                    toggleDisclosure(transcript.viewToModel2D(event.getPoint()));
                }
            }

            @Override
            public void mouseMoved(final MouseEvent event) {
                transcript.setCursor(Cursor.getPredefinedCursor(
                        disclosureAt(transcript.viewToModel2D(event.getPoint())) == null
                                ? Cursor.TEXT_CURSOR
                                : Cursor.HAND_CURSOR));
            }
        };
        transcript.addMouseListener(mouse);
        transcript.addMouseMotionListener(mouse);
        final String action = "turboism-acp.toggle-disclosure";
        transcript.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), action);
        transcript.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), action);
        transcript.getActionMap().put(action, new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent event) {
                toggleDisclosure(transcript.getCaretPosition());
            }
        });
    }

    private TranscriptEntry disclosureAt(final int offset) {
        if (offset < 0 || offset >= transcript.getDocument().getLength()) return null;
        final Object value = transcript
                .getStyledDocument()
                .getCharacterElement(offset)
                .getAttributes()
                .getAttribute(DISCLOSURE);
        return value instanceof TranscriptEntry entry ? entry : null;
    }

    private void toggleDisclosure(final int offset) {
        final TranscriptEntry group = disclosureAt(offset);
        if (group == null) return;
        final int start = group.renderedStart;
        final java.awt.Rectangle visible = transcript.getVisibleRect();
        group.expanded = !group.expanded;
        renderTranscript();
        transcript.setCaretPosition(Math.min(start, transcript.getDocument().getLength()));
        transcript.scrollRectToVisible(visible);
    }

    private SimpleAttributeSet contentAttributes(final Sender sender) {
        final SimpleAttributeSet content = new SimpleAttributeSet();
        StyleConstants.setForeground(
                content,
                sender == Sender.AGENT || sender == Sender.USER ? transcript.getForeground() : senderColor(sender));
        final Font font = transcript.getFont();
        StyleConstants.setFontFamily(content, font.getFamily());
        StyleConstants.setFontSize(content, font.getSize());
        StyleConstants.setBold(content, false);
        StyleConstants.setItalic(content, false);
        if (sender == Sender.THINKING) StyleConstants.setItalic(content, true);
        return content;
    }

    private String renderedEntry(final TranscriptEntry entry) {
        if (entry.sender != Sender.TOOL) return entry.content.toString();
        final String identity = entry.title.isBlank() ? boundedToolMetadata(entry.id) : entry.title;
        if (identity.isBlank()) return entry.content.toString();
        final String kind = entry.kind.isBlank() ? "" : " (" + entry.kind + ")";
        return identity + kind + ": " + entry.content;
    }

    private Color senderColor(final Sender sender) {
        return sender == Sender.USER ? AcpChatTheme.accent() : AcpChatTheme.muted();
    }

    static void installFallbackSwingUis(final javax.swing.UIDefaults defaults) {
        final Map<String, String> fallbacks = Map.ofEntries(
                Map.entry("ButtonUI", "javax.swing.plaf.basic.BasicButtonUI"),
                Map.entry("CheckBoxUI", "javax.swing.plaf.basic.BasicCheckBoxUI"),
                Map.entry("ComboBoxUI", "javax.swing.plaf.basic.BasicComboBoxUI"),
                Map.entry("EditorPaneUI", "javax.swing.plaf.basic.BasicEditorPaneUI"),
                Map.entry("LabelUI", "javax.swing.plaf.basic.BasicLabelUI"),
                Map.entry("ListUI", "javax.swing.plaf.basic.BasicListUI"),
                Map.entry("MenuUI", "javax.swing.plaf.basic.BasicMenuUI"),
                Map.entry("MenuItemUI", "javax.swing.plaf.basic.BasicMenuItemUI"),
                Map.entry("OptionPaneUI", "javax.swing.plaf.basic.BasicOptionPaneUI"),
                Map.entry("PanelUI", "javax.swing.plaf.basic.BasicPanelUI"),
                Map.entry("PasswordFieldUI", "javax.swing.plaf.basic.BasicPasswordFieldUI"),
                Map.entry("RootPaneUI", "javax.swing.plaf.basic.BasicRootPaneUI"),
                Map.entry("ScrollPaneUI", "javax.swing.plaf.basic.BasicScrollPaneUI"),
                Map.entry("SeparatorUI", "javax.swing.plaf.basic.BasicSeparatorUI"),
                Map.entry("SplitPaneUI", "javax.swing.plaf.basic.BasicSplitPaneUI"),
                Map.entry("TabbedPaneUI", "javax.swing.plaf.basic.BasicTabbedPaneUI"),
                Map.entry("TextAreaUI", "javax.swing.plaf.basic.BasicTextAreaUI"),
                Map.entry("TextFieldUI", "javax.swing.plaf.basic.BasicTextFieldUI"),
                Map.entry("TextPaneUI", "javax.swing.plaf.basic.BasicTextPaneUI"),
                Map.entry("ToolTipUI", "javax.swing.plaf.basic.BasicToolTipUI"),
                Map.entry("ViewportUI", "javax.swing.plaf.basic.BasicViewportUI"));
        fallbacks.forEach(defaults::putIfAbsent);
    }

    private JFrame frame(final String titleKey) {
        final JFrame frame = TurboismWindowFactory.frame(localization.text(titleKey));
        if (frame == null) throw new IllegalStateException("Swing is unavailable in a headless JVM");
        return frame;
    }

    private static void showAndFront(final JFrame frame, final Component preferredFocus) {
        frame.setExtendedState(frame.getExtendedState() & ~Frame.ICONIFIED);
        frame.setVisible(true);
        frame.toFront();
        frame.requestFocus();
        SwingUtilities.invokeLater(() -> {
            if (!frame.isVisible()) return;
            frame.toFront();
            frame.requestFocus();
            if (preferredFocus != null) preferredFocus.requestFocusInWindow();
        });
    }

    private static GridBagConstraints formConstraints() {
        final GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(5, 5, 5, 5);
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.gridy = 0;
        return constraints;
    }

    private static void add(
            final JPanel panel, final GridBagConstraints constraints, final String label, final Component component) {
        constraints.gridx = 0;
        constraints.gridwidth = 1;
        constraints.weightx = 0;
        panel.add(new JLabel(label), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        panel.add(component, constraints);
    }

    static String boundedToolMetadata(final String value) {
        final String text = Objects.requireNonNullElse(value, "");
        if (text.length() <= MAX_TOOL_METADATA_CHARS) return text;
        final Matcher graphemes = GRAPHEME.matcher(text);
        int end = 0;
        while (graphemes.find() && graphemes.end() <= MAX_TOOL_METADATA_CHARS) {
            end = graphemes.end();
        }
        return text.substring(0, end);
    }

    private static Color statusColor(final StatusTone tone) {
        return switch (tone) {
            case CONNECTED -> AcpChatTheme.success();
            case WORKING -> AcpChatTheme.warning();
            case ERROR -> AcpChatTheme.error();
        };
    }

    private static Color color(final String key, final Color fallback) {
        final Color value = UIManager.getColor(key);
        return value == null ? fallback : value;
    }

    private static Color secondaryForeground() {
        return AcpChatTheme.muted();
    }

    private static Color outlineColor() {
        return AcpChatTheme.border();
    }

    void refreshTheme() {
        if (!acceptingEvents.get()) return;
        applyThemeTree(agentFrame.getContentPane());
        applyThemeTree(settingsFrame.getContentPane());
        if (agentManager != null) applyThemeTree(agentManager.getContentPane());
        transcript.setBackground(AcpChatTheme.background());
        transcript.setForeground(AcpChatTheme.foreground());
        prompt.setOpaque(false);
        agentStatus.setForeground(AcpChatTheme.muted());
        composerAgent.setForeground(AcpChatTheme.foreground());
        composerOptionButtons.values().forEach(button -> button.setForeground(AcpChatTheme.foreground()));
        connectionDot.setForeground(statusColor(currentStatusTone));
        refreshTaskStatus();
        final java.awt.Rectangle visible = transcript.getVisibleRect();
        renderTranscript();
        transcript.scrollRectToVisible(visible);
        agentFrame.repaint();
        settingsFrame.repaint();
        if (agentManager != null) agentManager.repaint();
    }

    private static void applyThemeTree(final Component component) {
        if (component instanceof JScrollPane scroll) {
            scroll.setBackground(AcpChatTheme.background());
            scroll.getViewport().setBackground(AcpChatTheme.background());
            // Keep the scrollbar models active for wheel/trackpad scrolling, without visible bars.
            scroll.getVerticalScrollBar().setPreferredSize(new Dimension(0, 0));
            scroll.getHorizontalScrollBar().setPreferredSize(new Dimension(0, 0));
        }
        if (component instanceof JPanel) component.setBackground(AcpChatTheme.background());
        if (component instanceof JLabel || component instanceof javax.swing.AbstractButton) {
            component.setForeground(AcpChatTheme.foreground());
        }
        if (component instanceof javax.swing.AbstractButton) component.setBackground(AcpChatTheme.surface());
        if (component instanceof javax.swing.text.JTextComponent text) {
            text.setBackground(AcpChatTheme.inputBackground());
            text.setForeground(AcpChatTheme.foreground());
            text.setCaretColor(AcpChatTheme.foreground());
            text.setDisabledTextColor(AcpChatTheme.muted());
            text.setSelectionColor(AcpChatTheme.selectionBackground());
            text.setSelectedTextColor(AcpChatTheme.selectionForeground());
        }
        if (component instanceof JList<?> list) {
            list.setBackground(AcpChatTheme.background());
            list.setForeground(AcpChatTheme.foreground());
            list.setSelectionBackground(AcpChatTheme.selectionBackground());
            list.setSelectionForeground(AcpChatTheme.selectionForeground());
        }
        if (component instanceof javax.swing.plaf.basic.BasicSplitPaneDivider divider) {
            divider.setBackground(AcpChatTheme.border());
        }
        if (component instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) applyThemeTree(child);
        }
    }

    private enum Sender {
        USER,
        AGENT,
        SYSTEM,
        TOOL,
        THINKING
    }

    private enum StatusTone {
        CONNECTED,
        WORKING,
        ERROR
    }

    /** Selector item carrying the profile id; the label is rendered from the catalog. */
    private record AgentItem(String id, String unused) {
        private AgentItem {
            Objects.requireNonNull(id, "id");
        }
    }

    private static final class TranscriptEntry {
        private final Sender sender;
        private final String id;
        private final String title;
        private final String kind;
        private final StringBuilder content;
        private String input = "";
        private String toolStatus = "";
        private boolean expanded;
        private boolean attachment;
        private javax.swing.ImageIcon image;
        private final Map<String, javax.swing.ImageIcon> inlineImages = new LinkedHashMap<>();
        private final java.util.Set<String> requestedImages = new java.util.HashSet<>();
        private int renderedStart = -1;
        private int renderedLength;

        private TranscriptEntry(
                final Sender sender,
                final String id,
                final String title,
                final String kind,
                final StringBuilder content) {
            this.sender = Objects.requireNonNull(sender, "sender");
            this.id = Objects.requireNonNull(id, "id");
            this.title = Objects.requireNonNull(title, "title");
            this.kind = Objects.requireNonNull(kind, "kind");
            this.content = Objects.requireNonNull(content, "content");
        }

        private int weight() {
            if (sender != Sender.TOOL)
                return content.length()
                        + 1
                        + (image == null ? 0 : image.getIconWidth() * image.getIconHeight())
                        + inlineImages.values().stream()
                                .mapToInt(icon -> icon.getIconWidth() * icon.getIconHeight())
                                .sum();
            final int identityLength = title.isBlank() ? boundedToolMetadata(id).length() : title.length();
            final int kindLength = kind.isBlank() ? 0 : kind.length() + 3;
            final int separatorLength = identityLength == 0 ? 0 : 2;
            return identityLength
                    + kindLength
                    + separatorLength
                    + content.length()
                    + input.length()
                    + toolStatus.length()
                    + 1;
        }
    }

    static String sessionDateTime(final String timestamp, final ZoneId zone) {
        try {
            return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(zone)
                    .format(Instant.parse(timestamp));
        } catch (DateTimeParseException invalid) {
            // "active" and "unknown" are local placeholders, not user-facing dates.
            return "active".equals(timestamp) || "unknown".equals(timestamp) ? "" : timestamp;
        }
    }

    static final class SessionRenderer extends JPanel implements javax.swing.ListCellRenderer<SessionItem> {
        private final JLabel title = new JLabel();
        private final JLabel date = new JLabel();

        SessionRenderer() {
            super(new BorderLayout(0, 2));
            setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            title.putClientProperty("html.disable", true);
            date.putClientProperty("html.disable", true);
            title.setFont(title.getFont().deriveFont(Font.PLAIN));
            date.setFont(date.getFont()
                    .deriveFont(Font.PLAIN, Math.max(10F, date.getFont().getSize2D() - 1F)));
            add(title, BorderLayout.NORTH);
            add(date, BorderLayout.SOUTH);
        }

        @Override
        public Component getListCellRendererComponent(
                final JList<? extends SessionItem> list,
                final SessionItem value,
                final int index,
                final boolean selected,
                final boolean focus) {
            title.setText(value.label());
            date.setText(value.dateTime());
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            title.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            date.setForeground(selected ? list.getSelectionForeground() : secondaryForeground());
            setToolTipText(value.label() + (value.dateTime().isBlank() ? "" : " · " + value.dateTime()));
            getAccessibleContext().setAccessibleName(getToolTipText());
            return this;
        }
    }

    static record SessionItem(String sessionId, String label, String dateTime) {
        SessionItem {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(label, "label");
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
