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
import java.util.concurrent.ThreadLocalRandom;
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
    private static final int MAX_INLINE_MARKDOWN_IMAGES = 8;
    private static final int TASK_TIMER_MILLIS = 250;
    private static final int STICKY_SCROLL_THRESHOLD_PX = 36;
    private static final int MAX_OPTION_BUTTON_WIDTH = 176;
    private static final int MAX_PILL_TEXT_CHARS = 32;
    private static final int POPOVER_MAX_SCROLL_HEIGHT = 220;
    private static final int POPOVER_WIDTH = 300;
    private static final int PERMISSION_DETAIL_SCROLL_HEIGHT = 150;
    private static final Pattern GRAPHEME = Pattern.compile("\\X");
    private static final Pattern MARKDOWN_IMAGE_LINE =
            Pattern.compile("^!\\[[^\\]]*\\]\\(([^\\r\\n]+)\\)$", Pattern.MULTILINE);
    private static final String SEND_ACTION = "turboism-acp.send-prompt";
    private static final String DISCLOSURE = "turboism-acp.disclosure";
    private static final String DETECTED_MARKER = "  ✓";
    private static final String AGENT_MENU_CARET = " ▾";
    private static final Dimension PERMISSION_DIALOG_MINIMUM = new Dimension(460, 200);
    private static final float TIMESTAMP_MIN_FONT_SIZE = 11.0F;
    private static final float TIMESTAMP_FONT_DELTA = 1.5F;
    private static final AgentItem CUSTOM_ITEM = new AgentItem(AgentCatalog.CUSTOM_AGENT_ID, null);

    static {
        installFallbackSwingUis(UIManager.getDefaults());
    }

    private final PluginLocalization localization;
    private final JFrame agentFrame;
    private final JFrame settingsFrame;
    private JDialog agentManager;
    private JDialog activePermissionDialog;
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
    private final JScrollPane transcriptScroll = new JScrollPane(
            transcript, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
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
    private final JButton composerPermissionMode = new AcpComposerPanel.OptionButton("");
    private final JPanel composerOptions = new JPanel(new AcpComposerPanel.WrapLayout());
    private final Map<String, JButton> composerOptionButtons = new LinkedHashMap<>();
    private final JPanel composerTrack = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    private final JButton todoPill = new AcpComposerPanel.OptionButton("");
    private final JButton tpsPill = new AcpComposerPanel.OptionButton("");
    private final JLabel connectionDot = new JLabel("●");
    private final JLabel agentStatus = new JLabel();
    private final java.util.function.LongSupplier taskClock;
    private final javax.swing.Timer taskTimer;
    private final AcpPlanTracker planTracker = new AcpPlanTracker();
    private final AcpTurnMeter turnMeter;
    private final java.beans.PropertyChangeListener uiDefaultsListener;
    private long taskStartedNanos;
    private long taskElapsedSeconds;
    private String taskState = "";
    private String taskError = "";
    private StatusTone currentStatusTone = StatusTone.ERROR;
    private final List<TranscriptEntry> transcriptEntries = new ArrayList<>();
    private final Map<String, List<TranscriptEntry>> tools = new LinkedHashMap<>();
    private final AtomicBoolean acceptingEvents = new AtomicBoolean(true);
    private Map<String, String> detectedPaths = Map.of();
    private Map<String, AgentAvailability.Detection> detectionResults = Map.of();
    private List<AcpAuthMethod> authMethods = List.of();
    private List<AcpConfigOption> currentConfigOptions = List.of();
    private AcpChatController.PermissionMode permissionMode = AcpChatController.PermissionMode.ASK;
    private boolean extendedControlsBound;
    private boolean scrollToBottomScheduled;
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
    private Consumer<AcpChatController.PermissionMode> onSelectPermissionMode = ignored -> {};

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
        this.turnMeter = new AcpTurnMeter(this.taskClock);
        taskTimer = new javax.swing.Timer(TASK_TIMER_MILLIS, event -> refreshTaskStatus());
        this.localization = Objects.requireNonNull(localization, "localization");
        agentFrame = frame("window.agent-title");
        settingsFrame = frame("window.settings-title");
        configureComponents(selectedAgentId, savedCustomCommand, savedInitialPrompt);
        configureFrames();
        bindComponentEvents();
        showFailure("status.disconnected");
        rebuildComposerOptions(List.of());
        refreshTheme();
        uiDefaultsListener = event -> {
            if ("lookAndFeel".equals(event.getPropertyName()) && themeReady && acceptingEvents.get()) {
                SwingUtilities.invokeLater(this::refreshTheme);
            }
        };
        UIManager.addPropertyChangeListener(uiDefaultsListener);
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

    void bindExtendedControls(final Consumer<AcpChatController.PermissionMode> selectPermissionModeAction) {
        onSelectPermissionMode = Objects.requireNonNull(selectPermissionModeAction, "selectPermissionModeAction");
        extendedControlsBound = true;
        updatePermissionModeSelector();
        rebuildComposerOptions(currentConfigOptions);
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
        UIManager.removePropertyChangeListener(uiDefaultsListener);
        taskTimer.stop();
        agentFrame.dispose();
        settingsFrame.dispose();
        if (agentManager != null) agentManager.dispose();
        if (activePermissionDialog != null) activePermissionDialog.dispose();
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
        currentConfigOptions = List.copyOf(Objects.requireNonNullElse(options, List.of()));
        applyingOptions = true;
        try {
            rebuildComposerOptions(currentConfigOptions);
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
    public void showDetectionResults(final Map<String, AgentAvailability.Detection> results) {
        if (acceptingEvents.get()) detectionResults = Map.copyOf(results);
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
                if (!detectedPaths.getOrDefault(profile.id(), "").isBlank()
                        || (detectionResults.containsKey(profile.id())
                                && detectionResults.get(profile.id()).executable() != null))
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
        planTracker.clear();
        turnMeter.reset();
        refreshTodoPill();
        refreshTpsPill();
    }

    @Override
    public void showPrompting() {
        if (!acceptingEvents.get()) return;
        if (!prompting) {
            taskStartedNanos = taskClock.getAsLong();
            taskElapsedSeconds = 0;
            turnMeter.startTurn();
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
        stampLatestAgentTimestamp();
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
        scheduleScrollToBottom(true);
    }

    @Override
    public void appendAgent(final String text) {
        if (text != null && !text.isEmpty()) {
            turnMeter.recordOutputChars(text.length());
            refreshTpsPill();
        }
        appendEntry(Sender.AGENT, null, null, null, text, true);
        if (!transcriptEntries.isEmpty()) loadMarkdownImages(transcriptEntries.get(transcriptEntries.size() - 1));
    }

    @Override
    public void appendThinking(final String text) {
        if (text != null && !text.isEmpty()) {
            turnMeter.recordOutputChars(text.length());
            refreshTpsPill();
        }
        appendEntry(Sender.THINKING, null, null, null, text, true);
    }

    @Override
    public void appendTool(final String toolCallId, final String title, final String kind, final String status) {
        if (!acceptingEvents.get()) return;
        final boolean stickToBottom = isNearBottom();
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
        if (trimTranscript()) {
            renderTranscript();
        } else {
            updateActivityGroupAt(transcriptEntries.size() - 1);
        }
        scheduleScrollToBottom(stickToBottom);
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
        final boolean stickToBottom = isNearBottom();
        transcriptChars -= entry.weight();
        if (status != null && !status.isBlank()) entry.toolStatus = status;
        // ACP tool updates are partial: status-only updates must retain the existing output.
        if (content != null && !content.isEmpty()) {
            entry.content.setLength(0);
            entry.content.append(content);
        }
        transcriptChars += entry.weight();
        final int entryIndex = transcriptEntries.lastIndexOf(entry);
        if (trimTranscript() || entryIndex < 0) {
            renderTranscript();
        } else {
            updateActivityGroupAt(entryIndex);
        }
        scheduleScrollToBottom(stickToBottom);
    }

    @Override
    public void updateToolInput(final String toolCallId, final String input) {
        if (!acceptingEvents.get()) return;
        final List<TranscriptEntry> matching = tools.get(toolCallId);
        if (matching == null || matching.isEmpty()) return;
        final TranscriptEntry entry = matching.get(matching.size() - 1);
        final boolean stickToBottom = isNearBottom();
        transcriptChars -= entry.weight();
        entry.input = Objects.requireNonNullElse(input, "");
        transcriptChars += entry.weight();
        if (!entry.input.isEmpty()) {
            turnMeter.recordOutputChars(entry.input.length());
            refreshTpsPill();
            if (planTracker.observeToolInput(entry.title, entry.input)) {
                refreshTodoPill();
            }
        }
        final int entryIndex = transcriptEntries.lastIndexOf(entry);
        if (trimTranscript() || entryIndex < 0) {
            renderTranscript();
        } else {
            updateActivityGroupAt(entryIndex);
        }
        scheduleScrollToBottom(stickToBottom);
    }

    @Override
    public void showPlan(final List<AcpPlanTracker.Item> items) {
        if (!acceptingEvents.get()) return;
        if (planTracker.applyPlanEntries(Objects.requireNonNullElse(items, List.of()))) {
            refreshTodoPill();
        }
    }

    @Override
    public void recordUsageTokens(final long outputTokens) {
        if (!acceptingEvents.get() || outputTokens <= 0L) return;
        turnMeter.recordUsageOutputTokens(outputTokens);
        refreshTpsPill();
    }

    @Override
    public void onPermissionWaitChanged(final boolean waiting) {
        if (!acceptingEvents.get()) return;
        if (waiting) turnMeter.beginUserWait();
        else turnMeter.endUserWait();
        refreshTpsPill();
    }

    @Override
    public void showPermissionMode(final AcpChatController.PermissionMode mode) {
        if (!acceptingEvents.get() || mode == null) return;
        permissionMode = mode;
        updatePermissionModeSelector();
        rebuildComposerOptions(currentConfigOptions);
    }

    @Override
    public AcpListener.PermissionDecision requestPermission(final AcpListener.PermissionRequest request) {
        if (!acceptingEvents.get() || request.cancellation().isCancelled())
            return AcpListener.PermissionDecision.CANCELLED;
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
                request.cancellation().cancel();
                return AcpListener.PermissionDecision.CANCELLED;
            }
        } catch (InterruptedException interrupted) {
            request.cancellation().cancel();
            Thread.currentThread().interrupt();
            return AcpListener.PermissionDecision.CANCELLED;
        }
        return request.cancellation().isCancelled() ? AcpListener.PermissionDecision.CANCELLED : decision.get();
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
        composerPermissionMode.setName("turboism-acp.composer-permission-mode");
        configureCompactButton(composerPermissionMode);
        todoPill.setName("turboism-acp.todo-pill");
        todoPill.setIcon(AcpIcons.compactIcon(AcpIcons.Kind.PLAN));
        configureCompactButton(todoPill);
        todoPill.setVisible(false);
        tpsPill.setName("turboism-acp.tps-pill");
        tpsPill.setIcon(AcpIcons.compactIcon(AcpIcons.Kind.FAST));
        configureCompactButton(tpsPill);
        tpsPill.setVisible(false);
        composerTrack.setOpaque(false);
        composerTrack.setVisible(false);
        composerTrack.add(todoPill);
        composerTrack.add(tpsPill);
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
        AcpThemeControls.styleButton(connect, AcpThemeControls.ButtonVariant.PRIMARY);
        AcpThemeControls.styleButton(saveSettings, AcpThemeControls.ButtonVariant.PRIMARY);
        for (JButton secondary : List.of(detectAgents, agentLogin, agentLogout, authenticate)) {
            AcpThemeControls.styleButton(secondary, AcpThemeControls.ButtonVariant.SECONDARY);
        }
        for (JButton ghost : List.of(newSession, refreshSessions, openSettings)) {
            AcpThemeControls.styleButton(ghost, AcpThemeControls.ButtonVariant.GHOST);
        }
        AcpThemeControls.styleTextField(customCommand);
        AcpThemeControls.styleComboBox(agentSelector);
        customCommand.setText(Objects.requireNonNullElse(savedCustomCommand, ""));
        initialPrompt.setText(savedInitialPrompt);
        initialPrompt.putClientProperty(
                AcpComposerPanel.PLACEHOLDER_PROPERTY, localization.text("settings.initial-prompt-placeholder"));
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
        prompt.setLineWrap(true);
        prompt.setWrapStyleWord(true);
        prompt.putClientProperty(
                AcpComposerPanel.PLACEHOLDER_PROPERTY,
                AcpComposerPlaceholder.choose(localization, ThreadLocalRandom.current()));
        initialPrompt.setLineWrap(true);
        initialPrompt.setWrapStyleWord(true);
        updateAgentDetail();
        updatePermissionModeSelector();
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
            if (menu != null) AcpThemeControls.showPopup(composerAgent, menu);
        });
        composerPermissionMode.addActionListener(ignored -> {
            final JPopupMenu menu = composerPermissionMode.getComponentPopupMenu();
            if (menu != null) AcpThemeControls.showPopup(composerPermissionMode, menu);
        });
        todoPill.addActionListener(ignored -> AcpThemeControls.showPopup(todoPill, buildTodoPopover()));
        tpsPill.addActionListener(ignored -> AcpThemeControls.showPopup(tpsPill, buildTpsPopover()));
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
        final AcpThemeControls.ThemedPopupMenu menu = new AcpThemeControls.ThemedPopupMenu();
        for (AcpAuthMethod method : authMethods) {
            menu.addChoice(method.displayName(), false, null, () -> onAuthenticate.accept(method.id()));
        }
        AcpThemeControls.showPopup(authenticate, menu.finish());
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
        return agentName(item) + detectionMarker(item);
    }

    /** Resolves the display name shared by the agent menu and the composer button. */
    private String agentName(final AgentItem item) {
        if (AgentCatalog.CUSTOM_AGENT_ID.equals(item.id())) {
            return localization.text("agent.custom");
        }
        final AgentProfile profile = AgentCatalog.profile(item.id()).orElse(null);
        return profile == null
                ? item.id()
                : profile.displayName().replace(" (ACP)", "").replace(" (agy-acp)", "");
    }

    /** Marks menu entries with a detected executable; the composer button stays unmarked. */
    private String detectionMarker(final AgentItem item) {
        final String path = detectedPaths.get(item.id());
        return path == null || path.isEmpty() ? "" : DETECTED_MARKER;
    }

    private void updateAgentDetail() {
        final String selected = agentId();
        composerAgent.setIcon(AcpAgentIcons.icon(selected));
        composerAgent.setText(agentName((AgentItem) agentSelector.getSelectedItem()) + AGENT_MENU_CARET);
        composerAgent.setToolTipText(localization.text("label.agent"));
        composerAgent
                .getAccessibleContext()
                .setAccessibleName(localization.text("label.agent") + ": " + composerAgent.getText());
        final AcpThemeControls.ThemedPopupMenu agentMenu =
                new AcpThemeControls.ThemedPopupMenu(localization.text("composer.filter-options"));
        for (int index = 0; index < agentSelector.getItemCount(); index++) {
            final AgentItem item = agentSelector.getItemAt(index);
            if (detectedPaths.getOrDefault(item.id(), "").isBlank()) continue;
            agentMenu.addChoice(agentItemLabel(item), item.id().equals(selected), AcpAgentIcons.icon(item.id()), () -> {
                if (connecting
                        || prompting
                        || applyingOptions
                        || (connected && item.id().equals(agentId()))) return;
                agentSelector.setSelectedItem(item);
                if (AgentCatalog.CUSTOM_AGENT_ID.equals(item.id())
                        && customCommand().isBlank()) showAgentManager();
                else onConnect.run();
            });
        }
        agentMenu.addFooterAction(
                "turboism-acp.manage-agents", localization.text("button.manage-agents"), this::showAgentManager);
        composerAgent.setComponentPopupMenu(agentMenu.finish());
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
            final AgentAvailability.Detection result = detectionResults.get(profile.id());
            if (result != null && result.executable() != null) {
                final String key = "native-mode-not-advertised".equals(result.reason())
                        ? "status.agent-unsupported"
                        : "status.agent-probe-failed";
                agentDetection.setText(
                        localization.format(key, result.executable().toString(), result.version()));
            } else {
                agentDetection.setText(localization.format(
                        "status.agent-not-found", profile.executableCandidates().get(0)));
            }
        } else {
            final AgentAvailability.Detection result = detectionResults.get(profile.id());
            final String version = result == null ? "" : result.version();
            agentDetection.setText(
                    version.isBlank()
                            ? localization.format("status.agent-found", detected)
                            : localization.format("status.agent-found-version", detected, version));
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
        AcpThemeControls.configureScrollPane(sessionScroll, true);
        sessionPanel.add(sessionScroll, BorderLayout.CENTER);

        final JPanel conversation = new JPanel(new BorderLayout(0, 0));
        transcriptScroll.setName("turboism-acp.transcript-scroll");
        transcriptScroll.setBorder(BorderFactory.createEmptyBorder());
        AcpThemeControls.configureScrollPane(transcriptScroll, false);
        conversation.add(transcriptScroll, BorderLayout.CENTER);
        conversation.add(composer(), BorderLayout.SOUTH);

        final JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sessionPanel, conversation);
        split.setName("turboism-acp.split");
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
        final JPanel composer = new JPanel(new BorderLayout(0, 6));
        composer.setBorder(BorderFactory.createEmptyBorder(0, 16, 12, 16));
        composer.add(composerTrack, BorderLayout.NORTH);
        composer.add(promptPanel(), BorderLayout.CENTER);
        composer.add(statusStrip(), BorderLayout.SOUTH);
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
        final JPanel root = new JPanel(new BorderLayout(0, 12)) {
            @Override
            public void updateUI() {
                super.updateUI();
                if (themeReady) SwingUtilities.invokeLater(AcpChatWindow.this::refreshTheme);
            }
        };
        root.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        root.add(new JLabel(localization.text("settings.initial-prompt")), BorderLayout.NORTH);
        initialPrompt.setMargin(new Insets(10, 10, 10, 10));
        final JScrollPane initialPromptScroll = new JScrollPane(initialPrompt);
        initialPromptScroll.setBorder(BorderFactory.createCompoundBorder(
                AcpThemeControls.dynamicLineBorder(), BorderFactory.createEmptyBorder()));
        AcpThemeControls.configureScrollPane(initialPromptScroll, false);
        root.add(initialPromptScroll, BorderLayout.CENTER);
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
        AcpThemeControls.styleTitledSection(panel, localization.text(titleKey));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return panel;
    }

    private JPanel promptPanel() {
        final JPanel panel = new AcpComposerPanel();
        panel.setLayout(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        final JScrollPane promptScroll = new JScrollPane(
                prompt, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        promptScroll.setBorder(BorderFactory.createEmptyBorder());
        promptScroll.setOpaque(false);
        promptScroll.getViewport().setOpaque(false);
        AcpThemeControls.configureScrollPane(promptScroll, true);
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
        boolean permissionInserted = false;
        boolean hasAgentPermissionOption = false;
        for (AcpConfigOption option : ordered) {
            final int role = composerOptionOrder(option);
            if (role > 2 && !hasAgentPermissionOption && extendedControlsBound && !permissionInserted) {
                updatePermissionModeSelector();
                composerOptions.add(composerPermissionMode);
                permissionInserted = true;
            }
            if (role == 2) hasAgentPermissionOption = true;
            final boolean isAutoPermissionOverride =
                    role == 2 && permissionMode == AcpChatController.PermissionMode.AUTO;
            final String confirmedLabel = option.choices().stream()
                    .filter(choice -> choice.value().equals(option.currentValue()))
                    .map(AcpConfigOption.Choice::name)
                    .findFirst()
                    .orElse(option.currentValue());
            final String value = isAutoPermissionOverride ? localization.text("permission.mode.auto") : confirmedLabel;
            final boolean iconOnly = role == 3 || role == 4;
            final JButton button = new AcpComposerPanel.OptionButton(iconOnly ? "" : value + AGENT_MENU_CARET);
            configureCompactButton(button);
            button.setIcon(composerOptionIcon(role));
            button.setName("turboism-acp.composer-option." + option.id());
            final boolean active = isAutoPermissionOverride
                    || ((role == 3 || role == 4)
                            && java.util.Set.of("fast", "plan", "true", "on", "enabled")
                                    .contains(option.currentValue().toLowerCase(java.util.Locale.ROOT)));
            button.putClientProperty(AcpComposerPanel.ACTIVE_MODE_PROPERTY, active);
            button.setForeground(AcpChatTheme.foreground());
            button.setEnabled(connected && !prompting);
            button.setToolTipText(option.name() + ": " + value
                    + (option.description().isBlank() ? "" : " · " + option.description()));
            button.getAccessibleContext().setAccessibleName(option.name() + ": " + value);
            final Dimension size = button.getPreferredSize();
            button.setPreferredSize(new Dimension(
                    iconOnly ? 28 : Math.min(MAX_OPTION_BUTTON_WIDTH, size.width), Math.max(28, size.height)));
            final AcpThemeControls.ThemedPopupMenu menu =
                    new AcpThemeControls.ThemedPopupMenu(localization.text("composer.filter-options"));
            for (AcpConfigOption.Choice choice : option.choices()) {
                final boolean selectedChoice =
                        !isAutoPermissionOverride && choice.value().equals(option.currentValue());
                menu.addChoice(choice.name(), selectedChoice, null, () -> {
                    if (!button.isEnabled() || applyingOptions) return;
                    if (role == 2 && permissionMode != AcpChatController.PermissionMode.ASK) {
                        permissionMode = AcpChatController.PermissionMode.ASK;
                        onSelectPermissionMode.accept(AcpChatController.PermissionMode.ASK);
                        if (choice.value().equals(option.currentValue())) {
                            rebuildComposerOptions(currentConfigOptions);
                            return;
                        }
                    }
                    if (choice.value().equals(option.currentValue())) return;
                    showConfigUpdating(option.id());
                    onConfig.accept(option.id(), choice.value());
                });
            }
            if (role == 2 && extendedControlsBound) {
                menu.addChoice(localization.text("permission.mode.auto"), isAutoPermissionOverride, null, () -> {
                    if (!button.isEnabled() || applyingOptions) return;
                    permissionMode = AcpChatController.PermissionMode.AUTO;
                    onSelectPermissionMode.accept(AcpChatController.PermissionMode.AUTO);
                    rebuildComposerOptions(currentConfigOptions);
                });
            }
            final JPopupMenu finalizedMenu = menu.finish();
            button.setComponentPopupMenu(finalizedMenu);
            button.addActionListener(ignored -> AcpThemeControls.showPopup(button, finalizedMenu));
            composerOptionButtons.put(option.id(), button);
            composerOptions.add(button);
        }
        if (extendedControlsBound && !hasAgentPermissionOption && !permissionInserted) {
            updatePermissionModeSelector();
            composerOptions.add(composerPermissionMode);
        }
        composerOptions.revalidate();
        composerOptions.repaint();
    }

    private void updatePermissionModeSelector() {
        final boolean auto = permissionMode == AcpChatController.PermissionMode.AUTO;
        final String label = localization.text(auto ? "permission.mode.auto" : "permission.mode.ask");
        composerPermissionMode.setText(label + AGENT_MENU_CARET);
        composerPermissionMode.setForeground(AcpChatTheme.foreground());
        composerPermissionMode.setEnabled(!prompting);
        composerPermissionMode.putClientProperty(AcpComposerPanel.ACTIVE_MODE_PROPERTY, auto);
        final String tooltip = localization.text("composer.permissions") + ": " + label;
        composerPermissionMode.setToolTipText(tooltip);
        composerPermissionMode.getAccessibleContext().setAccessibleName(tooltip);

        final AcpThemeControls.ThemedPopupMenu menu = new AcpThemeControls.ThemedPopupMenu();
        menu.addChoice(localization.text("permission.mode.ask"), !auto, null, () -> {
            permissionMode = AcpChatController.PermissionMode.ASK;
            onSelectPermissionMode.accept(AcpChatController.PermissionMode.ASK);
            updatePermissionModeSelector();
        });
        menu.addChoice(localization.text("permission.mode.auto"), auto, null, () -> {
            permissionMode = AcpChatController.PermissionMode.AUTO;
            onSelectPermissionMode.accept(AcpChatController.PermissionMode.AUTO);
            updatePermissionModeSelector();
        });
        composerPermissionMode.setComponentPopupMenu(menu.finish());
    }

    private void refreshTodoPill() {
        final List<AcpPlanTracker.Item> items = planTracker.items();
        if (items.isEmpty()) {
            todoPill.setVisible(false);
            updateComposerTrackVisibility();
            return;
        }
        final long completed = items.stream()
                .filter(item -> item.status() == AcpPlanTracker.Status.COMPLETED)
                .count();
        final String activeText = items.stream()
                .filter(item -> item.status() == AcpPlanTracker.Status.IN_PROGRESS)
                .map(AcpPlanTracker.Item::displayText)
                .findFirst()
                .orElse("");
        final String label = activeText.isEmpty()
                ? localization.format("todo.pill-progress", completed, items.size())
                : localization.format("todo.pill-active", completed, items.size(), truncatePill(activeText));
        todoPill.setText(label);
        todoPill.setForeground(AcpChatTheme.foreground());
        todoPill.putClientProperty(
                AcpComposerPanel.ACTIVE_MODE_PROPERTY, completed < items.size() && !activeText.isEmpty());
        todoPill.setToolTipText(localization.format("todo.tooltip", completed, items.size()));
        todoPill.getAccessibleContext().setAccessibleName(todoPill.getToolTipText());
        todoPill.setVisible(true);
        updateComposerTrackVisibility();
    }

    private void refreshTpsPill() {
        final AcpTurnMeter.Snapshot snap = turnMeter.snapshot();
        final String text = snap.pillText();
        if (!snap.visible() || text.isEmpty()) {
            tpsPill.setVisible(false);
            updateComposerTrackVisibility();
            return;
        }
        tpsPill.setText(text);
        tpsPill.setForeground(snap.running() ? AcpChatTheme.foreground() : AcpChatTheme.muted());
        tpsPill.putClientProperty(AcpComposerPanel.ACTIVE_MODE_PROPERTY, false);
        final String tooltip = localization.format(
                "tps.tooltip",
                AcpTurnMeter.formatApproximate(AcpTurnMeter.formatRate(snap.averageTokPerSec()), snap.approximate()),
                AcpTurnMeter.formatApproximate(AcpTurnMeter.formatTokens(snap.outputTokens()), snap.approximate()),
                AcpTurnMeter.formatDurationMillis(snap.ttftMillis()));
        tpsPill.setToolTipText(tooltip);
        tpsPill.getAccessibleContext().setAccessibleName(tooltip);
        tpsPill.setVisible(true);
        updateComposerTrackVisibility();
    }

    private void updateComposerTrackVisibility() {
        final boolean visible = todoPill.isVisible() || tpsPill.isVisible();
        if (composerTrack.isVisible() != visible) {
            composerTrack.setVisible(visible);
        }
        composerTrack.revalidate();
        composerTrack.repaint();
    }

    private JPopupMenu buildTodoPopover() {
        final JPopupMenu popup = new JPopupMenu();
        popup.setUI(new javax.swing.plaf.basic.BasicPopupMenuUI());
        popup.setBackground(AcpChatTheme.surface());
        popup.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(AcpChatTheme.border()),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        final JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setOpaque(false);
        final List<AcpPlanTracker.Item> items = planTracker.items();
        final long done = items.stream()
                .filter(i -> i.status() == AcpPlanTracker.Status.COMPLETED)
                .count();
        final JLabel header = new JLabel(localization.format("todo.popover-title", done, items.size()));
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        header.setForeground(AcpChatTheme.foreground());
        root.add(header, BorderLayout.NORTH);

        final JPanel listPanel = new JPanel();
        listPanel.setLayout(new javax.swing.BoxLayout(listPanel, javax.swing.BoxLayout.Y_AXIS));
        listPanel.setOpaque(true);
        listPanel.setBackground(AcpChatTheme.surface());
        for (AcpPlanTracker.Item item : items) {
            final JPanel row = new JPanel(new BorderLayout(8, 0));
            row.setOpaque(false);
            row.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 4));
            final String mark =
                    switch (item.status()) {
                        case COMPLETED -> "✓";
                        case IN_PROGRESS -> "●";
                        case PENDING -> "○";
                    };
            final JLabel bullet = new JLabel(mark);
            bullet.setForeground(
                    switch (item.status()) {
                        case COMPLETED -> AcpChatTheme.success();
                        case IN_PROGRESS -> AcpChatTheme.accent();
                        case PENDING -> AcpChatTheme.muted();
                    });
            final JLabel content = new JLabel(item.displayText());
            content.putClientProperty("html.disable", true);
            content.setForeground(
                    item.status() == AcpPlanTracker.Status.COMPLETED
                            ? AcpChatTheme.muted()
                            : AcpChatTheme.foreground());
            row.add(bullet, BorderLayout.WEST);
            row.add(content, BorderLayout.CENTER);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            listPanel.add(row);
        }
        final JScrollPane scroll = new JScrollPane(
                listPanel, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(true);
        scroll.setBackground(AcpChatTheme.surface());
        scroll.getViewport().setBackground(AcpChatTheme.surface());
        AcpThemeControls.configureScrollPane(scroll, true);
        final Dimension listPref = listPanel.getPreferredSize();
        scroll.setPreferredSize(new Dimension(
                Math.max(POPOVER_WIDTH, Math.min(380, listPref.width + 16)),
                Math.min(POPOVER_MAX_SCROLL_HEIGHT, listPref.height + 4)));
        root.add(scroll, BorderLayout.CENTER);
        popup.add(root);
        return popup;
    }

    private JPopupMenu buildTpsPopover() {
        final AcpTurnMeter.Snapshot snap = turnMeter.snapshot();
        final JPopupMenu popup = new JPopupMenu();
        popup.setUI(new javax.swing.plaf.basic.BasicPopupMenuUI());
        popup.setBackground(AcpChatTheme.surface());
        popup.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(AcpChatTheme.border()),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        final JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        final GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.gridy = 0;
        gc.gridwidth = 2;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.insets = new Insets(0, 0, 6, 0);
        final JLabel title = new JLabel(localization.text("tps.popover-title"));
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        title.setForeground(AcpChatTheme.foreground());
        panel.add(title, gc);

        if (snap.running()) {
            addMetricRow(
                    panel,
                    ++gc.gridy,
                    localization.text("tps.live"),
                    AcpTurnMeter.formatApproximate(AcpTurnMeter.formatRate(snap.liveTokPerSec()), snap.approximate())
                            + " tok/s");
        }
        addMetricRow(
                panel,
                ++gc.gridy,
                localization.text("tps.decode"),
                AcpTurnMeter.formatApproximate(AcpTurnMeter.formatRate(snap.averageTokPerSec()), snap.approximate())
                        + " tok/s");
        addMetricRow(
                panel,
                ++gc.gridy,
                localization.text("tps.tokens"),
                AcpTurnMeter.formatApproximate(AcpTurnMeter.formatTokens(snap.outputTokens()), snap.approximate()));
        addMetricRow(
                panel, ++gc.gridy, localization.text("tps.ttft"), AcpTurnMeter.formatDurationMillis(snap.ttftMillis()));
        addMetricRow(
                panel,
                ++gc.gridy,
                localization.text("tps.duration"),
                AcpTurnMeter.formatDurationMillis(snap.activeMillis()));
        popup.add(panel);
        return popup;
    }

    private static void addMetricRow(
            final JPanel panel, final int row, final String labelText, final String valueText) {
        final GridBagConstraints left = new GridBagConstraints();
        left.gridx = 0;
        left.gridy = row;
        left.anchor = GridBagConstraints.WEST;
        left.insets = new Insets(2, 0, 2, 20);
        final JLabel label = new JLabel(labelText);
        label.setForeground(AcpChatTheme.muted());
        panel.add(label, left);

        final GridBagConstraints right = new GridBagConstraints();
        right.gridx = 1;
        right.gridy = row;
        right.weightx = 1;
        right.anchor = GridBagConstraints.EAST;
        right.insets = new Insets(2, 0, 2, 0);
        final JLabel value = new JLabel(valueText);
        value.setFont(new Font(Font.MONOSPACED, Font.PLAIN, value.getFont().getSize()));
        value.setForeground(AcpChatTheme.foreground());
        panel.add(value, right);
    }

    private static String truncatePill(final String text) {
        if (text == null || text.length() <= MAX_PILL_TEXT_CHARS) return Objects.requireNonNullElse(text, "");
        final int cut = Character.isLowSurrogate(text.charAt(MAX_PILL_TEXT_CHARS))
                ? MAX_PILL_TEXT_CHARS - 1
                : MAX_PILL_TEXT_CHARS;
        return text.substring(0, cut) + "…";
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
        if (!acceptingEvents.get() || request.cancellation().isCancelled())
            return AcpListener.PermissionDecision.CANCELLED;
        showAgentAndFront();
        final AtomicReference<AcpListener.PermissionDecision> decision =
                new AtomicReference<>(AcpListener.PermissionDecision.CANCELLED);
        final JDialog dialog = TurboismWindowFactory.dialog(agentFrame, localization.text("permission.title"), true);
        if (dialog == null) return AcpListener.PermissionDecision.CANCELLED;
        activePermissionDialog = dialog;
        final var cancellation = request.cancellation().onCancel(() -> SwingUtilities.invokeLater(dialog::dispose));
        try {
            if (request.cancellation().isCancelled()) return AcpListener.PermissionDecision.CANCELLED;
            dialog.setName("turboism-acp.permission");
            dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            dialog.setMinimumSize(permissionDialogMinimum());

            final AcpPermissionFormatter.FormattedPermission formatted =
                    AcpPermissionFormatter.format(localization, request);
            final JPanel body = new JPanel();
            body.setLayout(new javax.swing.BoxLayout(body, javax.swing.BoxLayout.Y_AXIS));
            body.setBorder(BorderFactory.createEmptyBorder(16, 18, 10, 18));

            final JLabel headline = new JLabel(formatted.headline());
            headline.putClientProperty("html.disable", true);
            headline.setFont(
                    headline.getFont().deriveFont(Font.BOLD, headline.getFont().getSize2D() + 1.5F));
            headline.setAlignmentX(Component.LEFT_ALIGNMENT);
            body.add(headline);

            if (!formatted.toolBadge().isEmpty()) {
                body.add(javax.swing.Box.createVerticalStrut(4));
                final JLabel badge = new JLabel(formatted.toolBadge());
                badge.putClientProperty("html.disable", true);
                badge.setFont(new Font(
                        Font.MONOSPACED,
                        Font.PLAIN,
                        Math.max(11, badge.getFont().getSize() - 1)));
                badge.setForeground(AcpChatTheme.muted());
                badge.setAlignmentX(Component.LEFT_ALIGNMENT);
                body.add(badge);
            }

            if (!formatted.summaryRows().isEmpty()) {
                body.add(javax.swing.Box.createVerticalStrut(12));
                final JPanel summaryCard = new JPanel(new GridBagLayout());
                summaryCard.setAlignmentX(Component.LEFT_ALIGNMENT);
                summaryCard.setBackground(AcpChatTheme.surface());
                summaryCard.setBorder(BorderFactory.createCompoundBorder(
                        AcpThemeControls.dynamicLineBorder(), BorderFactory.createEmptyBorder(8, 10, 8, 10)));
                int rowIndex = 0;
                for (AcpPermissionFormatter.SummaryRow row : formatted.summaryRows()) {
                    final GridBagConstraints keyGc = new GridBagConstraints();
                    keyGc.gridx = 0;
                    keyGc.gridy = rowIndex;
                    keyGc.anchor = GridBagConstraints.NORTHWEST;
                    keyGc.insets = new Insets(2, 0, 2, 12);
                    final JLabel keyLabel = new JLabel(row.label());
                    keyLabel.putClientProperty("html.disable", true);
                    keyLabel.setForeground(AcpChatTheme.muted());
                    summaryCard.add(keyLabel, keyGc);

                    final GridBagConstraints valGc = new GridBagConstraints();
                    valGc.gridx = 1;
                    valGc.gridy = rowIndex;
                    valGc.weightx = 1;
                    valGc.fill = GridBagConstraints.HORIZONTAL;
                    valGc.anchor = GridBagConstraints.NORTHWEST;
                    valGc.insets = new Insets(2, 0, 2, 0);
                    final JLabel valLabel = new JLabel(row.value());
                    valLabel.putClientProperty("html.disable", true);
                    valLabel.setForeground(AcpChatTheme.foreground());
                    summaryCard.add(valLabel, valGc);
                    rowIndex++;
                }
                body.add(summaryCard);
            }

            if (!formatted.rawDetails().isBlank()) {
                body.add(javax.swing.Box.createVerticalStrut(8));
                final JTextArea details = new JTextArea(formatted.rawDetails());
                details.setEditable(false);
                details.setLineWrap(true);
                details.setWrapStyleWord(false);
                details.setFont(new Font(
                        Font.MONOSPACED,
                        Font.PLAIN,
                        Math.max(11, details.getFont().getSize() - 1)));
                details.setMargin(new Insets(8, 8, 8, 8));
                details.setCaretPosition(0);
                final JScrollPane detailScroll = new JScrollPane(
                        details, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
                detailScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
                detailScroll.setBorder(AcpThemeControls.dynamicLineBorder());
                AcpThemeControls.configureScrollPane(detailScroll, true);
                detailScroll.setPreferredSize(new Dimension(440, PERMISSION_DETAIL_SCROLL_HEIGHT));
                detailScroll.setVisible(formatted.summaryRows().isEmpty());

                final JButton toggleDetails = new JButton(localization.text(
                        detailScroll.isVisible() ? "permission.hide-details" : "permission.show-details"));
                toggleDetails.setAlignmentX(Component.LEFT_ALIGNMENT);
                AcpThemeControls.styleButton(toggleDetails, AcpThemeControls.ButtonVariant.GHOST);
                toggleDetails.addActionListener(ignored -> {
                    final boolean nextVisible = !detailScroll.isVisible();
                    detailScroll.setVisible(nextVisible);
                    toggleDetails.setText(
                            localization.text(nextVisible ? "permission.hide-details" : "permission.show-details"));
                    dialog.pack();
                    ensureMinimumSize(dialog, permissionDialogMinimum());
                });
                body.add(toggleDetails);
                body.add(javax.swing.Box.createVerticalStrut(4));
                body.add(detailScroll);
            }

            final JButton reject = permissionButton(
                    "turboism-acp.permission.reject",
                    "permission.reject",
                    AcpListener.PermissionDecision.REJECT_ONCE,
                    AcpThemeControls.ButtonVariant.SECONDARY,
                    decision,
                    dialog);
            final JButton allowOnce = permissionButton(
                    "turboism-acp.permission.allow-once",
                    "permission.allow-once",
                    AcpListener.PermissionDecision.ALLOW_ONCE,
                    AcpThemeControls.ButtonVariant.SECONDARY,
                    decision,
                    dialog);
            final JButton allowSession = permissionButton(
                    "turboism-acp.permission.allow-session",
                    "permission.allow-session",
                    AcpListener.PermissionDecision.ALLOW_ALWAYS,
                    AcpThemeControls.ButtonVariant.PRIMARY,
                    decision,
                    dialog);
            if (request.options() != null) {
                final boolean canAllow = request.options().allowOnce() != null
                        || request.options().allowAlways() != null;
                allowOnce.setVisible(request.options().allowOnce() != null);
                allowSession.setVisible(canAllow);
                reject.setVisible(request.options().rejectOnce() != null);
            }
            final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 10));
            buttons.setBorder(BorderFactory.createEmptyBorder(0, 10, 6, 10));
            buttons.add(reject);
            buttons.add(allowOnce);
            buttons.add(allowSession);

            final JPanel content = new JPanel(new BorderLayout()) {
                @Override
                public void updateUI() {
                    super.updateUI();
                    if (themeReady) SwingUtilities.invokeLater(() -> applyThemeTree(this));
                }
            };
            content.add(body, BorderLayout.CENTER);
            content.add(buttons, BorderLayout.SOUTH);
            applyThemeTree(content);
            if (!formatted.toolBadge().isEmpty()) {
                // Keep secondary muted contrast on the tool identifier after tree theming.
                for (Component child : body.getComponents()) {
                    if (child instanceof JLabel lbl && formatted.toolBadge().equals(lbl.getText())) {
                        lbl.setForeground(AcpChatTheme.muted());
                    }
                }
            }
            dialog.getRootPane()
                    .registerKeyboardAction(
                            ignored -> dialog.dispose(),
                            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                            javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
            dialog.setContentPane(content);
            dialog.getRootPane().setDefaultButton(reject);
            dialog.pack();
            ensureMinimumSize(dialog, permissionDialogMinimum());
            dialog.setLocationRelativeTo(agentFrame);
            dialog.setVisible(true);
            return request.cancellation().isCancelled() ? AcpListener.PermissionDecision.CANCELLED : decision.get();
        } finally {
            activePermissionDialog = null;
            cancellation.close();
            dialog.dispose();
        }
    }

    private JButton permissionButton(
            final String name,
            final String localizationKey,
            final AcpListener.PermissionDecision selected,
            final AcpThemeControls.ButtonVariant variant,
            final AtomicReference<AcpListener.PermissionDecision> decision,
            final JDialog dialog) {
        final JButton button = new JButton(localization.text(localizationKey));
        button.setName(name);
        AcpThemeControls.styleButton(button, variant);
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
        if (entry.attachment || entry.requestedImages.size() >= MAX_INLINE_MARKDOWN_IMAGES) return;
        int scanStart = Math.min(entry.imageScanOffset, entry.content.length());
        while (scanStart > 0 && entry.content.charAt(scanStart - 1) != '\n') {
            scanStart--;
        }
        final Matcher images = MARKDOWN_IMAGE_LINE.matcher(entry.content);
        images.region(scanStart, entry.content.length());
        while (images.find() && entry.requestedImages.size() < MAX_INLINE_MARKDOWN_IMAGES) {
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
                        if (trimTranscript()) {
                            renderTranscript();
                        } else {
                            replaceRenderedEntry(entry, entry.renderedLength);
                        }
                    } catch (Exception failure) {
                        // Preserve the original Markdown reference when the local file cannot be previewed.
                    }
                }
            }.execute();
        }
        int committedEnd = entry.content.length();
        while (committedEnd > scanStart && entry.content.charAt(committedEnd - 1) != '\n') {
            committedEnd--;
        }
        entry.imageScanOffset = committedEnd;
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
        composerPermissionMode.setEnabled(!busy);
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
        turnMeter.finishTurn();
        taskState = state;
        taskError = error.replaceAll("[\\r\\n\\t]+", " ").strip();
        refreshTaskStatus();
        refreshTpsPill();
    }

    void refreshTaskStatus() {
        if (!acceptingEvents.get()) return;
        refreshTpsPill();
        if (taskState.isEmpty()) return;
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
        final boolean stickToBottom = isNearBottom();
        final TranscriptEntry previous =
                transcriptEntries.isEmpty() ? null : transcriptEntries.get(transcriptEntries.size() - 1);
        if (coalesce && previous != null && previous.sender == sender && !previous.attachment) {
            previous.content.append(text);
            transcriptChars += text.length();
            if (trimTranscript()) {
                renderTranscript();
            } else if (isActivity(sender)) {
                updateActivityGroupAt(transcriptEntries.size() - 1);
            } else {
                appendRenderedText(previous);
            }
            scheduleScrollToBottom(stickToBottom);
            return;
        }
        final TranscriptEntry entry = new TranscriptEntry(
                sender,
                Objects.requireNonNullElse(id, ""),
                Objects.requireNonNullElse(title, ""),
                Objects.requireNonNullElse(kind, ""),
                new StringBuilder(text),
                sender == Sender.USER || sender == Sender.AGENT ? currentMessageTimestamp() : "");
        transcriptEntries.add(entry);
        transcriptChars += entry.weight();
        if (trimTranscript()) {
            renderTranscript();
        } else if (isActivity(sender)) {
            updateActivityGroupAt(transcriptEntries.size() - 1);
        } else {
            appendRenderedEntry(entry);
        }
        scheduleScrollToBottom(stickToBottom);
    }

    private static boolean isActivity(final Sender sender) {
        return sender == Sender.THINKING || sender == Sender.TOOL;
    }

    private boolean isNearBottom() {
        final javax.swing.JScrollBar bar = transcriptScroll.getVerticalScrollBar();
        if (bar == null || !bar.isShowing()) return true;
        if (bar.getValueIsAdjusting()) return false;
        return bar.getMaximum() - (bar.getValue() + bar.getVisibleAmount()) <= STICKY_SCROLL_THRESHOLD_PX;
    }

    private void scheduleScrollToBottom(final boolean shouldScroll) {
        if (!shouldScroll || scrollToBottomScheduled) return;
        scrollToBottomScheduled = true;
        SwingUtilities.invokeLater(() -> {
            scrollToBottomScheduled = false;
            if (!acceptingEvents.get()) return;
            final javax.swing.JScrollBar bar = transcriptScroll.getVerticalScrollBar();
            if (bar != null && !bar.getValueIsAdjusting()) {
                bar.setValue(bar.getMaximum());
            }
        });
    }

    private boolean trimTranscript() {
        boolean changed = false;
        while ((transcriptChars > MAX_TRANSCRIPT_CHARS || transcriptEntries.size() > MAX_TRANSCRIPT_ENTRIES)
                && transcriptEntries.size() > 1) {
            final int removalIndex = transcriptRemovalIndex();
            final boolean wasGroupStart =
                    removalIndex == 0 || !isActivity(transcriptEntries.get(removalIndex - 1).sender);
            final TranscriptEntry removed = transcriptEntries.remove(removalIndex);
            if (wasGroupStart
                    && isActivity(removed.sender)
                    && removalIndex < transcriptEntries.size()
                    && isActivity(transcriptEntries.get(removalIndex).sender)) {
                transcriptEntries.get(removalIndex).expanded = removed.expanded;
            }
            transcriptChars -= removed.weight();
            removeToolEntry(removed);
            changed = true;
        }
        if (transcriptChars > MAX_TRANSCRIPT_CHARS && !transcriptEntries.isEmpty()) {
            final TranscriptEntry first = transcriptEntries.get(0);
            final int excess = transcriptChars - MAX_TRANSCRIPT_CHARS;
            final int removed = safePrefixLength(first.content, excess);
            first.content.delete(0, removed);
            first.imageScanOffset = Math.max(0, first.imageScanOffset - removed);
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
        } catch (javax.swing.text.BadLocationException failure) {
            renderTranscript();
        }
    }

    private void appendRenderedText(final TranscriptEntry entry) {
        // Only the trailing line of the current message is re-rendered, so a stream chunk
        // costs its own length instead of the whole message.
        if (appendIncrementalEntry(entry)) {
            return;
        }
        replaceRenderedEntry(entry, entry.renderedLength);
    }

    private boolean appendIncrementalEntry(final TranscriptEntry entry) {
        final AcpMessageRenderer.StreamRender stream = entry.stream;
        if (stream == null || entry.renderedStart < 0 || entry.image != null) {
            return false;
        }
        final StyledDocument document = transcript.getStyledDocument();
        try {
            if (entry.renderedStart + entry.renderedLength > document.getLength()) {
                return false;
            }
            final int tailStart = stream.tailDocumentStart();
            final int previousLength = entry.renderedLength;
            final int contentEndBefore = entry.renderedStart + previousLength - entry.renderedFooterLength;
            if (contentEndBefore < tailStart || contentEndBefore > document.getLength()) {
                return false;
            }
            if (entry.renderedFooterLength > 0) {
                document.remove(contentEndBefore, entry.renderedFooterLength);
                entry.renderedFooterLength = 0;
            }
            if (!AcpMessageRenderer.append(
                    document,
                    stream,
                    entry.content,
                    contentAttributes(entry.sender),
                    entry.sender == Sender.AGENT,
                    entry.inlineImages)) {
                return false;
            }
            final int tailEnd = stream.tailDocumentStart() + stream.tailLength();
            applyEntryParagraphs(entry, document, tailStart, tailEnd - tailStart, tailEnd - entry.renderedStart);
            final int footerLen = insertEntryTimestamp(document, entry, tailEnd);
            entry.renderedFooterLength = footerLen;
            entry.renderedLength = (tailEnd - entry.renderedStart) + footerLen;
            document.setParagraphAttributes(document.getLength(), 0, new SimpleAttributeSet(), true);
            shiftRenderedEntriesAfter(entry, entry.renderedLength - previousLength);
            return true;
        } catch (javax.swing.text.BadLocationException failure) {
            return false;
        }
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
        } catch (javax.swing.text.BadLocationException failure) {
            renderTranscript();
        }
    }

    private void shiftRenderedEntriesAfter(final TranscriptEntry changed, final int delta) {
        final int idx = transcriptEntries.indexOf(changed);
        if (idx >= 0) shiftRenderedEntriesFromIndex(idx + 1, delta);
    }

    private void shiftRenderedEntriesFromIndex(final int startIndex, final int delta) {
        if (delta == 0) return;
        for (int index = Math.max(0, startIndex); index < transcriptEntries.size(); index++) {
            final TranscriptEntry entry = transcriptEntries.get(index);
            if (entry.renderedStart >= 0) {
                entry.renderedStart += delta;
                if (entry.stream != null) entry.stream.shift(delta);
            }
        }
    }

    /**
     * Updates one unified activity group (consecutive thinking and tool entries) in-place inside
     * the document, skipping document edits completely when a collapsed group's summary is
     * unchanged.
     */
    private void updateActivityGroupAt(final int memberIndex) {
        if (memberIndex < 0 || memberIndex >= transcriptEntries.size()) return;
        int groupStart = memberIndex;
        while (groupStart > 0 && isActivity(transcriptEntries.get(groupStart - 1).sender)) {
            groupStart--;
        }
        int groupEnd = memberIndex + 1;
        while (groupEnd < transcriptEntries.size() && isActivity(transcriptEntries.get(groupEnd).sender)) {
            groupEnd++;
        }
        final List<TranscriptEntry> group = transcriptEntries.subList(groupStart, groupEnd);
        final TranscriptEntry first = group.get(0);
        final StyledDocument document = transcript.getStyledDocument();
        try {
            if (first.renderedStart < 0) {
                if (groupEnd == transcriptEntries.size()) {
                    renderActivityGroup(document, group, document.getLength());
                    return;
                }
                renderTranscript();
                return;
            }
            if (!first.expanded) {
                final String nextSummary = activitySummaryText(group);
                final boolean nextFailed = activityHasFailure(group);
                if (nextSummary.equals(first.cachedSummary) && nextFailed == first.cachedFailed) {
                    return;
                }
            }
            if (first.renderedStart + first.renderedLength > document.getLength()) {
                renderTranscript();
                return;
            }
            final int oldLength = first.renderedLength;
            document.remove(first.renderedStart, oldLength);
            renderActivityGroup(document, group, first.renderedStart);
            shiftRenderedEntriesFromIndex(groupEnd, first.renderedLength - oldLength);
        } catch (javax.swing.text.BadLocationException failure) {
            renderTranscript();
        }
    }

    private void renderTranscript() {
        final StyledDocument document = transcript.getStyledDocument();
        try {
            document.remove(0, document.getLength());
            for (TranscriptEntry entry : transcriptEntries) {
                entry.renderedStart = -1;
                entry.renderedLength = 0;
                entry.renderedFooterLength = 0;
                entry.stream = null;
                entry.cachedSummary = "";
                entry.cachedFailed = false;
            }
            for (int index = 0; index < transcriptEntries.size(); ) {
                final TranscriptEntry entry = transcriptEntries.get(index++);
                if (isActivity(entry.sender)) {
                    final List<TranscriptEntry> group = new ArrayList<>();
                    group.add(entry);
                    while (index < transcriptEntries.size()) {
                        final TranscriptEntry next = transcriptEntries.get(index);
                        if (isActivity(next.sender)) group.add(next);
                        else break;
                        index++;
                    }
                    renderActivityGroup(document, group, document.getLength());
                } else {
                    renderEntry(document, entry, document.getLength());
                }
            }
        } catch (javax.swing.text.BadLocationException failure) {
            transcript.setText("");
        }
    }

    private void renderEntry(final StyledDocument document, final TranscriptEntry entry, final int start)
            throws javax.swing.text.BadLocationException {
        final int before = document.getLength();
        if (entry.image != null) {
            final SimpleAttributeSet imageStyle = new SimpleAttributeSet();
            StyleConstants.setIcon(imageStyle, entry.image);
            document.insertString(start, " ", imageStyle);
            document.insertString(start + 1, "\n", contentAttributes(entry.sender));
        }
        AcpMessageRenderer.StreamRender stream = AcpMessageRenderer.insert(
                document,
                document.getLength() == before ? start : start + 2,
                renderedEntry(entry),
                contentAttributes(entry.sender),
                entry.sender == Sender.AGENT,
                entry.inlineImages);
        final int contentLength = document.getLength() - before;
        entry.renderedStart = start;
        entry.stream = entry.image == null ? stream : null;
        applyEntryParagraphs(entry, document, start, contentLength, contentLength);
        final int footerLen = insertEntryTimestamp(document, entry, start + contentLength);
        entry.renderedFooterLength = footerLen;
        entry.renderedLength = contentLength + footerLen;
        document.setParagraphAttributes(document.getLength(), 0, new SimpleAttributeSet(), true);
    }

    private int insertEntryTimestamp(final StyledDocument document, final TranscriptEntry entry, final int offset)
            throws javax.swing.text.BadLocationException {
        if ((entry.sender != Sender.USER && entry.sender != Sender.AGENT)
                || entry.attachment
                || entry.timestampText.isEmpty()) {
            return 0;
        }
        final String line = entry.timestampText + "\n";
        final SimpleAttributeSet charStyle = new SimpleAttributeSet();
        final Font baseFont = transcript.getFont();
        StyleConstants.setFontFamily(charStyle, baseFont.getFamily());
        StyleConstants.setFontSize(
                charStyle, Math.round(Math.max(TIMESTAMP_MIN_FONT_SIZE, baseFont.getSize2D() - TIMESTAMP_FONT_DELTA)));
        StyleConstants.setForeground(charStyle, secondaryForeground());
        StyleConstants.setBold(charStyle, false);
        StyleConstants.setItalic(charStyle, false);
        document.insertString(offset, line, charStyle);

        final SimpleAttributeSet paraStyle = new SimpleAttributeSet();
        paraStyle.addAttribute(AcpTranscriptEditorKit.USER_BACKGROUND, Boolean.FALSE);
        paraStyle.addAttribute(AcpTranscriptEditorKit.USER_ACCENT, Boolean.FALSE);
        paraStyle.addAttribute(AcpTranscriptEditorKit.USER_CARD_FIRST, Boolean.FALSE);
        paraStyle.addAttribute(AcpTranscriptEditorKit.USER_CARD_LAST, Boolean.FALSE);
        if (entry.sender == Sender.USER) {
            StyleConstants.setAlignment(paraStyle, StyleConstants.ALIGN_RIGHT);
            StyleConstants.setLeftIndent(paraStyle, 12F);
            StyleConstants.setRightIndent(paraStyle, 4F);
            StyleConstants.setSpaceAbove(paraStyle, 0F);
            StyleConstants.setSpaceBelow(paraStyle, 6F);
        } else {
            StyleConstants.setAlignment(paraStyle, StyleConstants.ALIGN_LEFT);
            StyleConstants.setLeftIndent(paraStyle, 4F);
            StyleConstants.setRightIndent(paraStyle, 12F);
            StyleConstants.setSpaceAbove(paraStyle, 2F);
            StyleConstants.setSpaceBelow(paraStyle, 8F);
        }
        document.setParagraphAttributes(offset, line.length(), paraStyle, true);
        return line.length();
    }

    private void stampLatestAgentTimestamp() {
        for (int index = transcriptEntries.size() - 1; index >= 0; index--) {
            final TranscriptEntry entry = transcriptEntries.get(index);
            if (entry.sender == Sender.USER) return;
            if (entry.sender == Sender.AGENT && !entry.attachment) {
                final String refreshed = currentMessageTimestamp();
                if (!refreshed.equals(entry.timestampText)) {
                    entry.timestampText = refreshed;
                    replaceRenderedEntry(entry, entry.renderedLength);
                }
                return;
            }
        }
    }

    private String currentMessageTimestamp() {
        return formatMessageTimestamp(Instant.now(), ZoneId.systemDefault(), localization.locale());
    }

    static String formatMessageTimestamp(final Instant instant, final ZoneId zone, final java.util.Locale locale) {
        final java.util.Locale effective = locale == null ? java.util.Locale.getDefault() : locale;
        return DateTimeFormatter.ofPattern("EEEE HH:mm", effective)
                .withZone(zone)
                .format(instant);
    }

    /** Applies the entry-level paragraph attributes over a rendered content range. */
    private void applyEntryParagraphs(
            final TranscriptEntry entry,
            final StyledDocument document,
            final int start,
            final int length,
            final int contentLength)
            throws javax.swing.text.BadLocationException {
        if (entry.sender == Sender.USER) {
            final SimpleAttributeSet userMiddle = new SimpleAttributeSet();
            userMiddle.addAttribute(AcpTranscriptEditorKit.USER_BACKGROUND, AcpChatTheme.surface());
            userMiddle.addAttribute(AcpTranscriptEditorKit.USER_ACCENT, userAccent());
            userMiddle.addAttribute(AcpTranscriptEditorKit.USER_CARD_FIRST, Boolean.FALSE);
            userMiddle.addAttribute(AcpTranscriptEditorKit.USER_CARD_LAST, Boolean.FALSE);
            StyleConstants.setAlignment(userMiddle, StyleConstants.ALIGN_LEFT);
            StyleConstants.setLeftIndent(userMiddle, 12F);
            StyleConstants.setRightIndent(userMiddle, 12F);
            StyleConstants.setSpaceAbove(userMiddle, 2F);
            StyleConstants.setSpaceBelow(userMiddle, 2F);
            document.setParagraphAttributes(entry.renderedStart, contentLength, userMiddle, false);

            final javax.swing.text.Element firstPara = document.getParagraphElement(entry.renderedStart);
            final int lastPos = Math.max(entry.renderedStart, entry.renderedStart + contentLength - 1);
            final javax.swing.text.Element lastPara = document.getParagraphElement(lastPos);
            if (firstPara == lastPara) {
                final SimpleAttributeSet single = new SimpleAttributeSet();
                single.addAttribute(AcpTranscriptEditorKit.USER_CARD_FIRST, Boolean.TRUE);
                single.addAttribute(AcpTranscriptEditorKit.USER_CARD_LAST, Boolean.TRUE);
                StyleConstants.setSpaceAbove(single, 16F);
                StyleConstants.setSpaceBelow(single, 14F);
                document.setParagraphAttributes(entry.renderedStart, 1, single, false);
            } else {
                final SimpleAttributeSet firstAttrs = new SimpleAttributeSet();
                firstAttrs.addAttribute(AcpTranscriptEditorKit.USER_CARD_FIRST, Boolean.TRUE);
                firstAttrs.addAttribute(AcpTranscriptEditorKit.USER_CARD_LAST, Boolean.FALSE);
                StyleConstants.setSpaceAbove(firstAttrs, 16F);
                StyleConstants.setSpaceBelow(firstAttrs, 2F);
                document.setParagraphAttributes(entry.renderedStart, 1, firstAttrs, false);

                final SimpleAttributeSet lastAttrs = new SimpleAttributeSet();
                lastAttrs.addAttribute(AcpTranscriptEditorKit.USER_CARD_FIRST, Boolean.FALSE);
                lastAttrs.addAttribute(AcpTranscriptEditorKit.USER_CARD_LAST, Boolean.TRUE);
                StyleConstants.setSpaceAbove(lastAttrs, 2F);
                StyleConstants.setSpaceBelow(lastAttrs, 14F);
                document.setParagraphAttributes(lastPara.getStartOffset(), 1, lastAttrs, false);
            }
        } else {
            final SimpleAttributeSet message = new SimpleAttributeSet();
            message.addAttribute(AcpTranscriptEditorKit.USER_BACKGROUND, Boolean.FALSE);
            message.addAttribute(AcpTranscriptEditorKit.USER_ACCENT, Boolean.FALSE);
            message.addAttribute(AcpTranscriptEditorKit.USER_CARD_FIRST, Boolean.FALSE);
            message.addAttribute(AcpTranscriptEditorKit.USER_CARD_LAST, Boolean.FALSE);
            StyleConstants.setAlignment(message, StyleConstants.ALIGN_LEFT);
            document.setParagraphAttributes(start, length, message, false);
            if (start == entry.renderedStart) {
                final SimpleAttributeSet paragraph = new SimpleAttributeSet();
                StyleConstants.setLeftIndent(paragraph, 4F);
                StyleConstants.setRightIndent(paragraph, 12F);
                StyleConstants.setSpaceAbove(paragraph, 10F);
                StyleConstants.setSpaceBelow(paragraph, 4F);
                document.setParagraphAttributes(start, 1, paragraph, false);
            }
        }
    }

    private String activitySummaryText(final List<TranscriptEntry> group) {
        final TranscriptEntry first = group.get(0);
        long commands = 0L;
        long otherTools = 0L;
        long running = 0L;
        long failed = 0L;
        for (TranscriptEntry entry : group) {
            if (entry.sender == Sender.TOOL) {
                if ("execute".equals(entry.kind)) commands++;
                else otherTools++;
                if ("pending".equals(entry.toolStatus) || "in_progress".equals(entry.toolStatus)) running++;
                if ("failed".equals(entry.toolStatus)) failed++;
            }
        }
        final String prefix = first.expanded ? "▾  " : "▸  ";
        if (commands == 0L && otherTools == 0L) {
            return prefix + localization.text("sender.thinking") + "\n";
        }
        final String toolCounts = commands == 0L
                ? localization.format("transcript.tools-other-only", otherTools)
                : otherTools == 0L
                        ? localization.format("transcript.tools-commands-only", commands)
                        : localization.format("transcript.tools-summary", commands, otherTools);
        return prefix
                + toolCounts
                + (running > 0L ? "  ·  " + localization.format("transcript.tools-running", running) : "")
                + (failed > 0L ? "  ·  " + localization.format("transcript.tools-failed", failed) : "")
                + "\n";
    }

    private static boolean activityHasFailure(final List<TranscriptEntry> group) {
        for (TranscriptEntry entry : group) {
            if (entry.sender == Sender.TOOL && "failed".equals(entry.toolStatus)) return true;
        }
        return false;
    }

    private void renderActivityGroup(final StyledDocument document, final List<TranscriptEntry> group, final int start)
            throws javax.swing.text.BadLocationException {
        final TranscriptEntry first = group.get(0);
        boolean hasTools = false;
        for (int i = 0; i < group.size(); i++) {
            final TranscriptEntry member = group.get(i);
            if (member.sender == Sender.TOOL) hasTools = true;
            if (i > 0) {
                member.renderedStart = -1;
                member.renderedLength = 0;
                member.stream = null;
            }
        }
        final int beforeLength = document.getLength();
        final String summary = activitySummaryText(group);
        final boolean hasFailed = activityHasFailure(group);
        final SimpleAttributeSet style = contentAttributes(Sender.TOOL);
        StyleConstants.setForeground(style, hasFailed ? statusColor(StatusTone.ERROR) : secondaryForeground());
        final SimpleAttributeSet link = new SimpleAttributeSet(style);
        link.addAttribute(DISCLOSURE, first);
        document.insertString(start, summary, link);

        final SimpleAttributeSet paragraph = new SimpleAttributeSet();
        paragraph.addAttribute(AcpTranscriptEditorKit.USER_BACKGROUND, Boolean.FALSE);
        paragraph.addAttribute(AcpTranscriptEditorKit.USER_ACCENT, Boolean.FALSE);
        StyleConstants.setLeftIndent(paragraph, 6F);
        StyleConstants.setSpaceAbove(paragraph, 8F);
        StyleConstants.setSpaceBelow(paragraph, first.expanded ? 4F : 8F);
        document.setParagraphAttributes(start, summary.length(), paragraph, true);

        int cursor = start + summary.length();
        if (first.expanded) {
            final SimpleAttributeSet thinkingStyle = contentAttributes(Sender.THINKING);
            StyleConstants.setForeground(thinkingStyle, secondaryForeground());
            StyleConstants.setItalic(thinkingStyle, false);

            final SimpleAttributeSet code = new SimpleAttributeSet(style);
            StyleConstants.setFontFamily(code, Font.MONOSPACED);
            StyleConstants.setForeground(code, transcript.getForeground());

            for (TranscriptEntry entry : group) {
                if (entry.sender == Sender.THINKING) {
                    if (entry.content.isEmpty()) continue;
                    if (!hasTools) {
                        final int detailStart = cursor;
                        final int lenBeforeItem = document.getLength();
                        AcpMessageRenderer.insert(document, cursor, entry.content.toString(), thinkingStyle, true);
                        final int insertedForItem = document.getLength() - lenBeforeItem;
                        if (insertedForItem > 0) {
                            final SimpleAttributeSet detail = new SimpleAttributeSet(paragraph);
                            StyleConstants.setLeftIndent(detail, 18F);
                            StyleConstants.setSpaceAbove(detail, 2F);
                            StyleConstants.setSpaceBelow(detail, 4F);
                            document.setParagraphAttributes(detailStart, insertedForItem, detail, true);
                            cursor += insertedForItem;
                        }
                        continue;
                    }
                    final int headerStart = cursor;
                    final String thinkingHeader =
                            (entry.detailExpanded ? "▾  " : "▸  ") + localization.text("sender.thinking") + "\n";
                    final SimpleAttributeSet thinkingLink = new SimpleAttributeSet(style);
                    StyleConstants.setForeground(thinkingLink, secondaryForeground());
                    thinkingLink.addAttribute(DISCLOSURE, entry);
                    document.insertString(cursor, thinkingHeader, thinkingLink);
                    final SimpleAttributeSet headerParagraph = new SimpleAttributeSet(paragraph);
                    StyleConstants.setLeftIndent(headerParagraph, 18F);
                    StyleConstants.setSpaceAbove(headerParagraph, 3F);
                    StyleConstants.setSpaceBelow(headerParagraph, entry.detailExpanded ? 2F : 4F);
                    document.setParagraphAttributes(headerStart, thinkingHeader.length(), headerParagraph, true);
                    cursor += thinkingHeader.length();
                    if (entry.detailExpanded) {
                        final int bodyStart = cursor;
                        final int lenBeforeBody = document.getLength();
                        AcpMessageRenderer.insert(document, cursor, entry.content.toString(), thinkingStyle, true);
                        final int insertedBody = document.getLength() - lenBeforeBody;
                        if (insertedBody > 0) {
                            final SimpleAttributeSet bodyParagraph = new SimpleAttributeSet(paragraph);
                            StyleConstants.setLeftIndent(bodyParagraph, 30F);
                            StyleConstants.setSpaceAbove(bodyParagraph, 2F);
                            StyleConstants.setSpaceBelow(bodyParagraph, 4F);
                            document.setParagraphAttributes(bodyStart, insertedBody, bodyParagraph, true);
                            cursor += insertedBody;
                        }
                    }
                } else {
                    final int detailStart = cursor;
                    final int lenBeforeItem = document.getLength();
                    final String title = entry.title.isBlank() ? boundedToolMetadata(entry.id) : entry.title;
                    final SimpleAttributeSet heading = new SimpleAttributeSet(style);
                    StyleConstants.setBold(heading, true);
                    if ("failed".equals(entry.toolStatus)) {
                        StyleConstants.setForeground(heading, statusColor(StatusTone.ERROR));
                    }
                    final String headerLine = title + " · " + entry.toolStatus + "\n";
                    document.insertString(cursor, headerLine, heading);
                    int toolCursor = cursor + headerLine.length();
                    if (!entry.input.isBlank()) {
                        final String inputLine = entry.input + "\n";
                        document.insertString(toolCursor, inputLine, code);
                        toolCursor += inputLine.length();
                    }
                    if (!entry.content.isEmpty()) {
                        final String outLine = entry.content + "\n";
                        document.insertString(toolCursor, outLine, code);
                    }
                    final int insertedForItem = document.getLength() - lenBeforeItem;
                    if (insertedForItem > 0) {
                        final SimpleAttributeSet detail = new SimpleAttributeSet(paragraph);
                        StyleConstants.setLeftIndent(detail, 18F);
                        StyleConstants.setSpaceAbove(detail, 2F);
                        StyleConstants.setSpaceBelow(detail, 4F);
                        document.setParagraphAttributes(detailStart, insertedForItem, detail, true);
                        cursor += insertedForItem;
                    }
                }
            }
        }
        first.renderedStart = start;
        first.renderedLength = document.getLength() - beforeLength;
        first.cachedSummary = summary;
        first.cachedFailed = hasFailed;
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
        final TranscriptEntry target = disclosureAt(offset);
        if (target == null) return;
        final int index = transcriptEntries.indexOf(target);
        final java.awt.Rectangle visible = transcript.getVisibleRect();
        if (index < 0) {
            target.expanded = !target.expanded;
            renderTranscript();
            transcript.scrollRectToVisible(visible);
            return;
        }
        int groupStart = index;
        while (groupStart > 0 && isActivity(transcriptEntries.get(groupStart - 1).sender)) {
            groupStart--;
        }
        final TranscriptEntry groupFirst = transcriptEntries.get(groupStart);
        final int anchorOffset = groupFirst.renderedStart >= 0 ? groupFirst.renderedStart : offset;
        if (target == groupFirst && offset < anchorOffset + groupFirst.cachedSummary.length()) {
            groupFirst.expanded = !groupFirst.expanded;
        } else {
            target.detailExpanded = !target.detailExpanded;
        }
        updateActivityGroupAt(index);
        transcript.setCaretPosition(
                Math.min(Math.max(0, anchorOffset), transcript.getDocument().getLength()));
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
        // MultiUIDefaults.putIfAbsent bypasses the active LookAndFeel table and overwrites global UIs.
        fallbacks.forEach((key, value) -> {
            if (defaults.get(key) == null) {
                defaults.put(key, value);
            }
        });
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
        if (activePermissionDialog != null) applyThemeTree(activePermissionDialog.getContentPane());
        for (Component child : agentFrame.getContentPane().getComponents()) {
            if (child instanceof JSplitPane split
                    && split.getUI() instanceof javax.swing.plaf.basic.BasicSplitPaneUI ui) {
                ui.getDivider().setBackground(outlineColor());
            }
        }
        transcript.setBackground(AcpChatTheme.background());
        transcript.setForeground(AcpChatTheme.foreground());
        transcriptScroll.setBackground(AcpChatTheme.background());
        transcriptScroll.getViewport().setBackground(AcpChatTheme.background());
        prompt.setOpaque(false);
        openSettings.setForeground(AcpChatTheme.muted());
        agentStatus.setForeground(AcpChatTheme.muted());
        composerAgent.setForeground(AcpChatTheme.foreground());
        composerPermissionMode.setForeground(AcpChatTheme.foreground());
        composerOptionButtons.values().forEach(button -> button.setForeground(AcpChatTheme.foreground()));
        refreshTodoPill();
        refreshTpsPill();
        connectionDot.setForeground(statusColor(currentStatusTone));
        refreshTaskStatus();
        final java.awt.Rectangle visible = transcript.getVisibleRect();
        renderTranscript();
        transcript.scrollRectToVisible(visible);
        agentFrame.repaint();
        settingsFrame.repaint();
        if (agentManager != null) agentManager.repaint();
        if (activePermissionDialog != null) activePermissionDialog.repaint();
    }

    private static void applyThemeTree(final Component component) {
        AcpThemeControls.applyThemeTree(component);
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
        private boolean detailExpanded;
        private boolean attachment;
        private String timestampText;
        private javax.swing.ImageIcon image;
        private final Map<String, javax.swing.ImageIcon> inlineImages = new LinkedHashMap<>();
        private final java.util.Set<String> requestedImages = new java.util.HashSet<>();
        private int renderedStart = -1;
        private int renderedLength;
        private int renderedFooterLength;
        private int imageScanOffset;
        private String cachedSummary = "";
        private boolean cachedFailed;
        private AcpMessageRenderer.StreamRender stream;

        private TranscriptEntry(
                final Sender sender,
                final String id,
                final String title,
                final String kind,
                final StringBuilder content) {
            this(sender, id, title, kind, content, "");
        }

        private TranscriptEntry(
                final Sender sender,
                final String id,
                final String title,
                final String kind,
                final StringBuilder content,
                final String timestampText) {
            this.sender = Objects.requireNonNull(sender, "sender");
            this.id = Objects.requireNonNull(id, "id");
            this.title = Objects.requireNonNull(title, "title");
            this.kind = Objects.requireNonNull(kind, "kind");
            this.content = Objects.requireNonNull(content, "content");
            this.timestampText = Objects.requireNonNullElse(timestampText, "");
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
