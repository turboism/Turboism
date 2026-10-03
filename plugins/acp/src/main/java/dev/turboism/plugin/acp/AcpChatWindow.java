package dev.turboism.plugin.acp;

import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.window.TurboismWindowFactory;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
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
import java.net.URL;
import java.text.AttributedCharacterIterator;
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
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
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
 * active session. The Settings window owns agent selection, executable detection, authentication,
 * and the standing instruction prompt. Both windows share one controller and hide rather than
 * terminate it when closed; plugin lifecycle teardown owns the ACP process.</p>
 */
final class AcpChatWindow implements AcpChatController.View {

    private static final int MAX_TRANSCRIPT_CHARS = 1024 * 1024;
    private static final int MAX_TRANSCRIPT_ENTRIES = 4096;
    private static final int MAX_TOOL_METADATA_CHARS = 4096;
    private static final int MAX_STATUS_OPTIONS = 4;
    private static final Pattern GRAPHEME = Pattern.compile("\\X");
    private static final String SEND_ACTION = "turboism-acp.send-prompt";
    private static final String SETTINGS_ICON_RESOURCE = "icons/settings.png";
    private static final Dimension PERMISSION_DIALOG_MINIMUM = new Dimension(620, 360);
    private static final AgentItem CUSTOM_ITEM =
            new AgentItem(AgentCatalog.CUSTOM_AGENT_ID, null);

    static {
        installFallbackSwingUis(UIManager.getDefaults());
    }

    private final PluginLocalization localization;
    private final JFrame agentFrame;
    private final JFrame settingsFrame;
    private final JComboBox<AgentItem> agentSelector = new JComboBox<>();
    private final JTextField customCommand = new JTextField(30);
    private final JButton connect = new JButton();
    private final JButton detectAgents = new JButton();
    private final JButton agentLogin = new JButton();
    private final JButton agentLogout = new JButton();
    private final JLabel agentDetection = new JLabel();
    private final JLabel agentHint = new JLabel();
    private final JLabel configStatus = new JLabel();
    private final JPanel optionsPanel = new JPanel(new GridBagLayout());
    private final Map<String, JComboBox<AcpConfigOption.Choice>> optionCombos = new LinkedHashMap<>();
    private final JLabel settingsStatus = new JLabel();
    private final JTextArea initialPrompt = new JTextArea(6, 42);
    private final JButton saveSettings = new JButton();
    private final JButton authenticate = new JButton();
    private final DefaultListModel<SessionItem> sessions = new DefaultListModel<>();
    private final JList<SessionItem> sessionList = new JList<>(sessions);
    private final JButton newSession = new JButton();
    private final JButton refreshSessions = new JButton();
    private final JButton openSettings = new JButton();
    private final JTextPane transcript = new JTextPane();
    private final JTextArea prompt = new JTextArea(4, 50);
    private final JButton send = new JButton();
    private final JButton cancel = new JButton();
    private final JLabel connectionDot = new JLabel("●");
    private final JLabel agentStatus = new JLabel();
    private final JCheckBox showThinking = new JCheckBox();
    private final List<TranscriptEntry> transcriptEntries = new ArrayList<>();
    private final Map<String, List<TranscriptEntry>> tools = new LinkedHashMap<>();
    private final AtomicBoolean acceptingEvents = new AtomicBoolean(true);
    private Map<String, String> detectedPaths = Map.of();
    private List<AcpAuthMethod> authMethods = List.of();
    private boolean applyingOptions;
    private boolean applyingSessions;
    private boolean applyingAgents;
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
        this.localization = Objects.requireNonNull(localization, "localization");
        agentFrame = frame("window.agent-title");
        settingsFrame = frame("window.settings-title");
        configureComponents(selectedAgentId, savedCustomCommand, savedInitialPrompt);
        configureFrames();
        bindComponentEvents();
        showFailure("status.disconnected");
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
        agentFrame.dispose();
        settingsFrame.dispose();
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
            final boolean mcpAttached) {
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
            appendSystem(localization.format(
                    "transcript.agent-info", display, version.isBlank() ? "?" : version));
        }
        if (!mcpAttached) {
            appendSystem(localization.text("transcript.mcp-unavailable"));
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
            rebuildOptionsPanel(options);
            updateConfigStatus(options);
        } finally {
            applyingOptions = false;
        }
    }

    @Override
    public void showConfigUpdating(final String optionId) {
        if (!acceptingEvents.get()) return;
        applyingOptions = true;
        optionCombos.values().forEach(combo -> combo.setEnabled(false));
        configStatus.setText(localization.text("label.config-updating"));
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
                final SessionItem item = new SessionItem(
                        summary.sessionId(), localization.format("session.label", index++, summary.updatedAt()));
                sessions.addElement(item);
                if (item.sessionId().equals(activeSessionId)) selected = item;
            }
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
        agentSelector.repaint();
        updateAgentDetail();
        settingsStatus.setText(localization.text("status.detection-complete"));
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
        prompting = true;
        if (submittedPrompt != null && prompt.getText().strip().equals(submittedPrompt)) {
            prompt.setText("");
        }
        submittedPrompt = null;
        setStatus("status.prompting", statusColor(StatusTone.WORKING));
        setConversationControls(true, true);
    }

    @Override
    public void showPromptComplete(final String stopReason) {
        if (!acceptingEvents.get()) return;
        prompting = false;
        final String text = localization.format("status.prompt-complete", stopReason);
        setStatusText(text, statusColor(StatusTone.CONNECTED));
        setConversationControls(connected, false);
        prompt.requestFocusInWindow();
    }

    @Override
    public void showFailure(final String localizationKey) {
        if (!acceptingEvents.get()) return;
        connected = false;
        connecting = false;
        durableSessionsAvailable = false;
        prompting = false;
        authMethods = List.of();
        authenticate.setVisible(false);
        showLifecycleStatus(localizationKey, StatusTone.ERROR);
        connect.setText(localization.text("button.connect"));
        connect.setEnabled(true);
        agentLogout.setEnabled(false);
        setConversationControls(false, false);
        rebuildOptionsPanel(List.of());
        updateConfigStatus(List.of());
    }

    @Override
    public void showSessionFailure(final String localizationKey) {
        if (!acceptingEvents.get()) return;
        prompting = false;
        setStatus(localizationKey, statusColor(StatusTone.ERROR));
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
                new StringBuilder(Objects.requireNonNullElse(status, "")));
        transcriptEntries.add(entry);
        transcriptChars += entry.weight();
        if (!entry.id.isBlank()) {
            tools.computeIfAbsent(entry.id, ignored -> new ArrayList<>()).add(entry);
        }
        if (trimTranscript()) {
            renderTranscript();
        } else {
            appendRenderedEntry(entry);
        }
    }

    @Override
    public void updateTool(final String toolCallId, final String status, final String content) {
        if (!acceptingEvents.get()) return;
        final String exactToolCallId = Objects.requireNonNullElse(toolCallId, "");
        final List<TranscriptEntry> matching = tools.get(exactToolCallId);
        final TranscriptEntry entry = matching == null || matching.isEmpty() ? null : matching.get(matching.size() - 1);
        if (entry == null) {
            appendTool(
                    exactToolCallId,
                    exactToolCallId,
                    "tool",
                    Objects.requireNonNullElse(status, "") + contentSuffix(content));
            return;
        }
        final int previousRenderedLength = renderedEntry(entry).length() + 1;
        transcriptChars -= entry.weight();
        entry.content.setLength(0);
        entry.content.append(Objects.requireNonNullElse(status, ""));
        if (content != null && !content.isBlank()) entry.content.append(": ").append(content);
        transcriptChars += entry.weight();
        if (trimTranscript()) {
            renderTranscript();
        } else {
            replaceRenderedEntry(entry, previousRenderedLength);
        }
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
        configStatus.setName("turboism-acp.config-status");
        optionsPanel.setName("turboism-acp.options");
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
        showThinking.setName("turboism-acp.show-thinking");

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
        send.setText(localization.text("button.send"));
        cancel.setText(localization.text("button.stop"));
        newSession.setText(localization.text("button.new-session-short"));
        newSession.setToolTipText(localization.text("button.new-session"));
        refreshSessions.setText(localization.text("button.refresh-short"));
        refreshSessions.setToolTipText(localization.text("button.refresh-sessions"));
        openSettings.setText("");
        openSettings.setIcon(resourceIcon(SETTINGS_ICON_RESOURCE));
        openSettings.setToolTipText(localization.text("button.settings"));
        openSettings.getAccessibleContext().setAccessibleName(localization.text("button.settings"));
        saveSettings.setText(localization.text("button.save-settings"));
        showThinking.setText(localization.text("button.show-thinking"));
        customCommand.setText(Objects.requireNonNullElse(savedCustomCommand, ""));
        initialPrompt.setText(savedInitialPrompt);
        send.setEnabled(false);
        cancel.setEnabled(false);
        newSession.setEnabled(false);
        refreshSessions.setEnabled(false);
        sessionList.setEnabled(false);
        sessionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        agentLogout.setEnabled(false);
        transcript.setEditable(false);
        transcript.setFont(UIManager.getFont("TextArea.font"));
        transcript.setBackground(color("TextArea.background", Color.WHITE));
        transcript.setForeground(color("TextArea.foreground", Color.BLACK));
        prompt.setLineWrap(true);
        prompt.setWrapStyleWord(true);
        initialPrompt.setLineWrap(true);
        initialPrompt.setWrapStyleWord(true);
        updateAgentDetail();
        configurePromptKeys(prompt, this::submitPrompt);
    }

    private void configureFrames() {
        agentFrame.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        agentFrame.setMinimumSize(new Dimension(900, 640));
        agentFrame.setPreferredSize(new Dimension(1040, 720));
        agentFrame.setContentPane(agentContent());
        agentFrame.pack();
        agentFrame.setLocationByPlatform(true);
        settingsFrame.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        settingsFrame.setMinimumSize(new Dimension(720, 560));
        settingsFrame.setContentPane(settingsContent());
        settingsFrame.pack();
        settingsFrame.setLocationByPlatform(true);
    }

    private void bindComponentEvents() {
        connect.addActionListener(ignored -> onConnect.run());
        saveSettings.addActionListener(ignored -> onSaveSettings.run());
        detectAgents.addActionListener(ignored -> {
            detectAgents.setEnabled(false);
            settingsStatus.setText(localization.text("status.detecting"));
            onDetectAgents.run();
            detectAgents.setEnabled(true);
        });
        agentLogin.addActionListener(ignored -> onAgentLogin.run());
        agentLogout.addActionListener(ignored -> onAgentLogout.run());
        authenticate.addActionListener(ignored -> offerAuthMethods());
        agentSelector.addActionListener(ignored -> {
            if (applyingAgents) return;
            updateAgentDetail();
        });
        send.addActionListener(ignored -> submitPrompt());
        cancel.addActionListener(ignored -> onCancel.run());
        newSession.addActionListener(ignored -> onNewSession.run());
        refreshSessions.addActionListener(ignored -> onRefreshSessions.run());
        openSettings.addActionListener(ignored -> showSettingsAndFront());
        showThinking.addActionListener(ignored -> renderTranscript());
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
        final String name = profile == null ? item.id() : profile.displayName();
        final String path = detectedPaths.get(item.id());
        return path == null ? name : name + (path.isEmpty() ? "" : "  ✓");
    }

    private void updateAgentDetail() {
        final String selected = agentId();
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
            agentDetection.setText(localization.format("status.agent-not-found", profile.executableCandidates().get(0)));
        } else {
            agentDetection.setText(localization.format("status.agent-found", detected));
        }
        agentHint.setText(profile.installHint().isEmpty()
                ? profile.homepage()
                : localization.format("label.agent-install-hint", profile.installHint()));
        agentLogin.setEnabled(!profile.loginArguments().isEmpty());
    }

    private JPanel agentContent() {
        final JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        final JPanel sessionPanel = new JPanel(new BorderLayout(4, 4));
        final JPanel sessionHeader = new JPanel(new BorderLayout(4, 0));
        final JLabel sessionTitle = new JLabel(localization.text("label.sessions"));
        sessionTitle.setFont(sessionTitle.getFont().deriveFont(Font.BOLD));
        final JPanel sessionActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        sessionActions.add(newSession);
        sessionActions.add(refreshSessions);
        sessionHeader.add(sessionTitle, BorderLayout.WEST);
        sessionHeader.add(sessionActions, BorderLayout.EAST);
        sessionPanel.add(sessionHeader, BorderLayout.NORTH);
        sessionPanel.add(new JScrollPane(sessionList), BorderLayout.CENTER);

        final JPanel conversation = new JPanel(new BorderLayout(0, 0));
        conversation.add(new JScrollPane(transcript), BorderLayout.CENTER);
        conversation.add(composer(), BorderLayout.SOUTH);

        final JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sessionPanel, conversation);
        split.setResizeWeight(0.2);
        split.setDividerLocation(220);
        root.add(split, BorderLayout.CENTER);
        return root;
    }

    private JPanel composer() {
        final JPanel composer = new JPanel(new BorderLayout(0, 6));
        composer.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        composer.add(statusStrip(), BorderLayout.NORTH);
        composer.add(promptPanel(), BorderLayout.CENTER);
        return composer;
    }

    private JPanel statusStrip() {
        final JPanel strip = new JPanel(new BorderLayout(8, 0));
        strip.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 1, 0, color("Separator.foreground", Color.GRAY)),
                BorderFactory.createEmptyBorder(4, 6, 4, 4)));
        final JPanel state = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        state.add(connectionDot);
        state.add(agentStatus);
        state.add(separator());
        state.add(configStatus);
        final JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.add(authenticate);
        actions.add(showThinking);
        actions.add(openSettings);
        strip.add(state, BorderLayout.CENTER);
        strip.add(actions, BorderLayout.EAST);
        return strip;
    }

    private JPanel settingsContent() {
        final JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        final JTabbedPane tabs = new JTabbedPane();
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.addTab(localization.text("tab.agent"), settingsPage(agentSection()));
        tabs.addTab(localization.text("tab.session-options"), settingsPage(sessionOptionsSection()));
        tabs.addTab(localization.text("tab.instructions"), settingsPage(instructionsSection()));
        root.add(tabs, BorderLayout.CENTER);
        final JPanel footer = new JPanel(new BorderLayout(8, 8));
        footer.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        footer.add(settingsStatus, BorderLayout.CENTER);
        footer.add(saveSettings, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);
        return root;
    }

    private static JScrollPane settingsPage(final JPanel content) {
        final JPanel page = new JPanel(new BorderLayout());
        page.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        page.add(content, BorderLayout.NORTH);
        final JScrollPane scroll = new JScrollPane(page);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        return scroll;
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

    private JPanel sessionOptionsSection() {
        final JPanel panel = section("section.session-options");
        final GridBagConstraints constraints = formConstraints();
        constraints.gridx = 0;
        constraints.gridwidth = 3;
        constraints.weightx = 1;
        panel.add(new JLabel("<html>" + localization.text("label.session-options-detail") + "</html>"), constraints);
        constraints.gridy++;
        constraints.weighty = 1;
        constraints.fill = GridBagConstraints.BOTH;
        panel.add(optionsPanel, constraints);
        return panel;
    }

    private JPanel instructionsSection() {
        final JPanel panel = section("section.instructions");
        final GridBagConstraints constraints = formConstraints();
        constraints.gridx = 0;
        constraints.gridwidth = 3;
        constraints.weightx = 1;
        panel.add(new JLabel("<html>" + localization.text("security.fixed-boundary") + "</html>"), constraints);
        constraints.gridy++;
        panel.add(new JLabel(localization.text("label.initial-prompt")), constraints);
        constraints.gridy++;
        constraints.weighty = 1;
        constraints.fill = GridBagConstraints.BOTH;
        panel.add(new JScrollPane(initialPrompt), constraints);
        return panel;
    }

    private void rebuildOptionsPanel(final List<AcpConfigOption> options) {
        optionsPanel.removeAll();
        optionCombos.clear();
        final GridBagConstraints constraints = formConstraints();
        if (options.isEmpty()) {
            constraints.gridx = 0;
            constraints.gridwidth = 3;
            constraints.weightx = 1;
            optionsPanel.add(new JLabel(localization.text("label.options-unavailable")), constraints);
        } else {
            for (AcpConfigOption option : options) {
                final JComboBox<AcpConfigOption.Choice> combo = new JComboBox<>();
                combo.setName("turboism-acp.option." + option.id());
                combo.setPreferredSize(new Dimension(300, 28));
                combo.setMinimumSize(new Dimension(180, 28));
                for (AcpConfigOption.Choice choice : option.choices()) {
                    combo.addItem(choice);
                    if (choice.value().equals(option.currentValue())) {
                        combo.setSelectedItem(choice);
                    }
                }
                if (combo.getSelectedIndex() < 0 && combo.getItemCount() > 0) {
                    combo.setSelectedIndex(0);
                }
                combo.setEnabled(connected && !prompting);
                combo.addActionListener(ignored -> selected(option.id(), combo));
                optionCombos.put(option.id(), combo);
                final String label = option.name().isBlank() ? option.id() : option.name();
                add(optionsPanel, constraints, label, combo);
                constraints.gridy++;
            }
        }
        optionsPanel.revalidate();
        optionsPanel.repaint();
    }

    private void updateConfigStatus(final List<AcpConfigOption> options) {
        if (options.isEmpty()) {
            configStatus.setText("");
            return;
        }
        final StringBuilder summary = new StringBuilder();
        int shown = 0;
        for (AcpConfigOption option : options) {
            if (shown >= MAX_STATUS_OPTIONS) {
                summary.append(", …");
                break;
            }
            final String name = option.name().isBlank() ? option.id() : option.name();
            final String value = option.choices().stream()
                    .filter(choice -> choice.value().equals(option.currentValue()))
                    .map(AcpConfigOption.Choice::name)
                    .findFirst()
                    .orElse(option.currentValue());
            if (summary.length() > 0) summary.append("  •  ");
            summary.append(name).append('=').append(value);
            shown++;
        }
        configStatus.setText(summary.toString());
        configStatus.setToolTipText(summary.toString());
    }

    private JPanel section(final String titleKey) {
        final JPanel panel = new JPanel(new GridBagLayout());
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setBorder(BorderFactory.createTitledBorder(localization.text(titleKey)));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return panel;
    }

    private JPanel promptPanel() {
        final JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.add(new JScrollPane(prompt), BorderLayout.CENTER);
        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(cancel);
        buttons.add(send);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
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
        if (text.isEmpty()) return;
        submittedPrompt = text;
        onPrompt.accept(text);
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
        send.setEnabled(connectionReady && !busy);
        cancel.setEnabled(connectionReady && busy);
        prompt.setEnabled(connectionReady && !busy);
        newSession.setEnabled(connectionReady && !busy);
        refreshSessions.setEnabled(connectionReady && !busy && durableSessionsAvailable);
        sessionList.setEnabled(connectionReady && !busy && durableSessionsAvailable);
        optionCombos.values().forEach(combo -> combo.setEnabled(connectionReady && !busy));
    }

    private void setStatus(final String localizationKey, final Color color) {
        setStatusText(localization.text(localizationKey), color);
    }

    /**
     * Updates the compact connection state and mirrors each state transition into the transcript.
     * Repeating the same callback is ignored so reconnect races cannot flood the conversation.
     */
    private void showLifecycleStatus(final String localizationKey, final StatusTone tone) {
        final String text = localization.text(localizationKey);
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
        agentStatus.setText(text);
        settingsStatus.setText(text);
        connectionDot.setForeground(color);
    }

    private void selected(final String id, final JComboBox<AcpConfigOption.Choice> combo) {
        if (applyingOptions) return;
        final String value = selectedConfigValue(combo);
        if (value != null) onConfig.accept(id, value);
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
        if (coalesce && previous != null && previous.sender == sender) {
            previous.content.append(text);
            transcriptChars += text.length();
            if (trimTranscript()) {
                renderTranscript();
            } else if (sender != Sender.THINKING || showThinking.isSelected()) {
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
        if (showThinking.isSelected()) return 0;
        for (int index = 0; index < transcriptEntries.size(); index++) {
            if (transcriptEntries.get(index).sender == Sender.THINKING) return index;
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
        if (entry.sender == Sender.THINKING && !showThinking.isSelected()) {
            entry.renderedStart = -1;
            entry.renderedLength = 0;
            return;
        }
        final StyledDocument document = transcript.getStyledDocument();
        try {
            renderEntry(document, entry, document.getLength());
            transcript.setCaretPosition(document.getLength());
        } catch (javax.swing.text.BadLocationException failure) {
            renderTranscript();
        }
    }

    private void appendRenderedText(final TranscriptEntry entry, final String text) {
        final StyledDocument document = transcript.getStyledDocument();
        try {
            final int newline = entry.renderedStart + entry.renderedLength - 1;
            if (entry.renderedStart < 0
                    || newline < 0
                    || newline >= document.getLength()
                    || !"\n".equals(document.getText(newline, 1))) {
                renderTranscript();
                return;
            }
            document.insertString(newline, text, contentAttributes(entry.sender));
            entry.renderedLength += text.length();
            shiftRenderedEntriesAfter(entry, text.length());
            transcript.setCaretPosition(document.getLength());
        } catch (javax.swing.text.BadLocationException failure) {
            renderTranscript();
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
                if (entry.sender != Sender.THINKING || showThinking.isSelected()) {
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
        final SimpleAttributeSet content = contentAttributes(entry.sender);
        final String rendered = renderedEntry(entry) + "\n";
        document.insertString(start, rendered, content);
        entry.renderedStart = start;
        entry.renderedLength = rendered.length();
        final SimpleAttributeSet paragraph = new SimpleAttributeSet();
        StyleConstants.setLeftIndent(paragraph, 2F);
        StyleConstants.setRightIndent(paragraph, 2F);
        StyleConstants.setSpaceAbove(paragraph, 0F);
        StyleConstants.setSpaceBelow(paragraph, 1F);
        document.setParagraphAttributes(start, entry.renderedLength, paragraph, false);
    }

    private SimpleAttributeSet contentAttributes(final Sender sender) {
        final SimpleAttributeSet content = new SimpleAttributeSet();
        StyleConstants.setForeground(content, senderColor(sender));
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
        return switch (sender) {
            case USER -> new Color(0x2F, 0x6F, 0xD6);
            case AGENT -> new Color(0x20, 0x8A, 0x55);
            case SYSTEM -> new Color(0xB0, 0x6A, 0x18);
            case TOOL -> new Color(0x82, 0x4D, 0xB5);
            case THINKING -> color("Label.disabledForeground", Color.GRAY);
        };
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

    private static ImageIcon resourceIcon(final String path) {
        final ClassLoader loader = AcpChatWindow.class.getClassLoader();
        final URL resource = loader == null ? null : loader.getResource(path);
        if (resource == null) return new ImageIcon();
        return new ImageIcon(resource);
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

    private static JLabel separator() {
        final JLabel separator = new JLabel("•");
        separator.setForeground(color("Label.disabledForeground", Color.GRAY));
        return separator;
    }

    private static String contentSuffix(final String content) {
        return content == null || content.isBlank() ? "" : ": " + content;
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
            case CONNECTED -> new Color(0x20, 0x9A, 0x5B);
            case WORKING -> new Color(0xD0, 0x88, 0x1B);
            case ERROR -> new Color(0xC7, 0x3D, 0x3D);
        };
    }

    private static Color color(final String key, final Color fallback) {
        final Color value = UIManager.getColor(key);
        return value == null ? fallback : value;
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
            if (sender != Sender.TOOL) return content.length() + 1;
            final int identityLength = title.isBlank() ? boundedToolMetadata(id).length() : title.length();
            final int kindLength = kind.isBlank() ? 0 : kind.length() + 3;
            final int separatorLength = identityLength == 0 ? 0 : 2;
            return identityLength + kindLength + separatorLength + content.length() + 1;
        }
    }

    private record SessionItem(String sessionId, String label) {
        private SessionItem {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(label, "label");
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
