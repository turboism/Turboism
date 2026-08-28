package dev.turboism.plugin.turboismwithfx;

import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.window.TurboismWindowFactory;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.ImageIcon;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JPanel;
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
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;

/**
 * Paired plugin-owned Swing windows for fx conversation and settings.
 *
 * <p>The Agent window owns a typed, bounded live transcript and the fx-provided session surface.
 * Durable controls stay disabled when fx advertises only an ephemeral active session. The Settings
 * window owns launch configuration and fx-provided provider/model controls. Both windows share one
 * controller and hide rather than terminate it when closed; plugin lifecycle teardown owns the ACP
 * process.</p>
 */
final class TurboismWithFxWindow implements TurboismWithFxController.View {

    private static final int MAX_TRANSCRIPT_CHARS = 1024 * 1024;
    private static final int MAX_TRANSCRIPT_ENTRIES = 4096;
    private static final int MAX_TOOL_METADATA_CHARS = 4096;
    private static final Pattern GRAPHEME = Pattern.compile("\\X");
    private static final String SEND_ACTION = "turboism-with-fx.send-prompt";
    private static final String SETTINGS_ICON_RESOURCE = "icons/settings.png";
    private static final Dimension PERMISSION_DIALOG_MINIMUM = new Dimension(620, 360);

    private final PluginLocalization localization;
    private final JFrame agentFrame;
    private final JFrame settingsFrame;
    private final JTextField executable = new JTextField(30);
    private final JCheckBox compatibility = new JCheckBox();
    private final JButton connect = new JButton();
    private final JComboBox<FxAcpConfigOption.Choice> providers = new JComboBox<>();
    private final JComboBox<FxAcpConfigOption.Choice> models = new JComboBox<>();
    private final JLabel configAvailability = new JLabel();
    private final JTextArea initialPrompt = new JTextArea(6, 42);
    private final JButton saveSettings = new JButton();
    private final JButton repairRuntime = new JButton();
    private final JLabel runtimeStatus = new JLabel();
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
    private final JLabel providerStatus = new JLabel();
    private final JLabel modelStatus = new JLabel();
    private final JCheckBox showThinking = new JCheckBox();
    private final List<TranscriptEntry> transcriptEntries = new ArrayList<>();
    private final Map<String, List<TranscriptEntry>> tools = new LinkedHashMap<>();
    private final AtomicBoolean acceptingEvents = new AtomicBoolean(true);
    private boolean applyingOptions;
    private boolean applyingSessions;
    private boolean connected;
    private boolean durableSessionsAvailable;
    private boolean prompting;
    private String lastLifecycleMessage = "";
    private int transcriptChars;
    private Runnable onConnect = () -> { };
    private Consumer<String> onPrompt = ignored -> { };
    private Runnable onCancel = () -> { };
    private BiConsumer<String, String> onConfig = (id, value) -> { };
    private Runnable onNewSession = () -> { };
    private Consumer<String> onSelectSession = ignored -> { };
    private Runnable onRefreshSessions = () -> { };
    private Runnable onRepairRuntime = () -> { };
    private Runnable onSaveSettings = () -> { };

    TurboismWithFxWindow(
        final PluginLocalization localization,
        final String initialExecutable,
        final boolean initialCompatibility,
        final String savedInitialPrompt
    ) {
        this.localization = Objects.requireNonNull(localization, "localization");
        agentFrame = frame("window.agent-title");
        settingsFrame = frame("window.settings-title");
        configureComponents(initialExecutable, initialCompatibility, savedInitialPrompt);
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
        final Runnable repairRuntimeAction,
        final Runnable saveSettingsAction
    ) {
        onConnect = Objects.requireNonNull(connectAction, "connectAction");
        onPrompt = Objects.requireNonNull(promptAction, "promptAction");
        onCancel = Objects.requireNonNull(cancelAction, "cancelAction");
        onConfig = Objects.requireNonNull(configAction, "configAction");
        onNewSession = Objects.requireNonNull(newSessionAction, "newSessionAction");
        onSelectSession = Objects.requireNonNull(selectSessionAction, "selectSessionAction");
        onRefreshSessions = Objects.requireNonNull(refreshSessionsAction, "refreshSessionsAction");
        onRepairRuntime = Objects.requireNonNull(repairRuntimeAction, "repairRuntimeAction");
        onSaveSettings = Objects.requireNonNull(saveSettingsAction, "saveSettingsAction");
    }

    String executable() {
        return executable.getText().strip();
    }

    boolean compatibilityMode() {
        return compatibility.isSelected();
    }

    String initialPrompt() {
        return initialPrompt.getText();
    }

    void showAgentAndFront() {
        showAndFront(agentFrame);
        prompt.requestFocusInWindow();
    }

    void showSettingsAndFront() {
        showAndFront(settingsFrame);
    }

    void dispose() {
        if (!acceptingEvents.compareAndSet(true, false)) return;
        agentFrame.dispose();
        settingsFrame.dispose();
    }

    @Override
    public void showConnecting(final boolean compatibilityMode) {
        if (!acceptingEvents.get()) return;
        connected = false;
        prompting = false;
        showLifecycleStatus("status.connecting", StatusTone.WORKING);
        connect.setText(localization.text("button.connecting"));
        connect.setEnabled(false);
        setConversationControls(false, false);
        compatibility.setSelected(compatibilityMode);
    }

    @Override
    public void showConnected(
        final List<FxAcpConfigOption> options,
        final boolean durableSessions
    ) {
        if (!acceptingEvents.get()) return;
        connected = true;
        durableSessionsAvailable = durableSessions;
        prompting = false;
        showConfigOptions(options);
        showLifecycleStatus("status.connected-compatibility", StatusTone.CONNECTED);
        connect.setText(localization.text("button.reconnect"));
        connect.setEnabled(true);
        setConversationControls(true, false);
        appendSystem(localization.text("transcript.compatibility-warning"));
        prompt.requestFocusInWindow();
    }

    @Override
    public void showConfigOptions(final List<FxAcpConfigOption> options) {
        if (!acceptingEvents.get()) return;
        applyingOptions = true;
        try {
            apply(providers, find(options, "provider"));
            apply(models, find(options, "model"));
            updateConfigSummary();
        } finally {
            applyingOptions = false;
        }
    }

    @Override
    public void showConfigUpdating(final String optionId) {
        if (!acceptingEvents.get()) return;
        applyingOptions = true;
        providers.setEnabled(false);
        models.setEnabled(false);
        configAvailability.setText(localization.text("label.config-updating"));
    }

    @Override
    public void showConfigFailure(
        final String optionId,
        final List<FxAcpConfigOption> confirmedOptions
    ) {
        if (!acceptingEvents.get()) return;
        applyingOptions = false;
        showConfigOptions(confirmedOptions);
        showSessionFailure("status.config-failed");
    }

    @Override
    public void showSessions(
        final List<FxAcpSessionSummary> available,
        final String activeSessionId,
        final boolean durableSessions
    ) {
        if (!acceptingEvents.get()) return;
        durableSessionsAvailable = durableSessions;
        final Map<String, FxAcpSessionSummary> unique = new LinkedHashMap<>();
        for (FxAcpSessionSummary summary : available) unique.putIfAbsent(summary.sessionId(), summary);
        if (activeSessionId != null && !unique.containsKey(activeSessionId)) {
            unique.put(activeSessionId, new FxAcpSessionSummary(activeSessionId, "unknown"));
        }
        applyingSessions = true;
        try {
            sessions.clear();
            int index = 1;
            SessionItem selected = null;
            for (FxAcpSessionSummary summary : unique.values()) {
                final SessionItem item = new SessionItem(
                    summary.sessionId(),
                    localization.format("session.label", index++, summary.updatedAt())
                );
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
        durableSessionsAvailable = false;
        prompting = false;
        showLifecycleStatus(localizationKey, StatusTone.ERROR);
        connect.setText(localization.text("button.connect"));
        connect.setEnabled(true);
        setConversationControls(false, false);
        installDisconnectedPlaceholder(providers, "label.provider-unavailable");
        installDisconnectedPlaceholder(models, "label.model-unavailable");
        updateConfigSummary();
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
    public void showSettingsSaved() {
        if (!acceptingEvents.get()) return;
        runtimeStatus.setText(localization.text("status.settings-saved"));
    }

    @Override
    public void showManagedRuntimeInstalling() {
        if (!acceptingEvents.get()) return;
        repairRuntime.setEnabled(false);
        connect.setEnabled(false);
        runtimeStatus.setText(localization.text("status.managed-repair-installing"));
    }

    @Override
    public void showManagedRuntimeResult(final String localizationKey) {
        if (!acceptingEvents.get()) return;
        repairRuntime.setEnabled(true);
        connect.setEnabled(true);
        runtimeStatus.setText(localization.text(localizationKey));
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
    public void appendTool(
        final String toolCallId,
        final String title,
        final String kind,
        final String status
    ) {
        if (!acceptingEvents.get()) return;
        final TranscriptEntry entry = new TranscriptEntry(
            Sender.TOOL,
            Objects.requireNonNullElse(toolCallId, ""),
            boundedToolMetadata(title),
            boundedToolMetadata(kind),
            new StringBuilder(Objects.requireNonNullElse(status, ""))
        );
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
        final TranscriptEntry entry = matching == null || matching.isEmpty()
            ? null : matching.get(matching.size() - 1);
        if (entry == null) {
            appendTool(
                exactToolCallId,
                exactToolCallId,
                "tool",
                Objects.requireNonNullElse(status, "") + contentSuffix(content)
            );
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
    public FxAcpListener.PermissionDecision requestPermission(
        final FxAcpListener.PermissionRequest request
    ) {
        if (!acceptingEvents.get()) return FxAcpListener.PermissionDecision.CANCELLED;
        if (SwingUtilities.isEventDispatchThread()) return permissionDialog(request);
        final AtomicReference<FxAcpListener.PermissionDecision> decision =
            new AtomicReference<>(FxAcpListener.PermissionDecision.CANCELLED);
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
                return FxAcpListener.PermissionDecision.CANCELLED;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return FxAcpListener.PermissionDecision.CANCELLED;
        }
        return decision.get();
    }

    private void configureComponents(
        final String initialExecutable,
        final boolean initialCompatibility,
        final String savedInitialPrompt
    ) {
        executable.setName("turboism-with-fx.executable");
        compatibility.setName("turboism-with-fx.compatibility");
        connect.setName("turboism-with-fx.connect");
        providers.setName("turboism-with-fx.provider");
        models.setName("turboism-with-fx.model");
        initialPrompt.setName("turboism-with-fx.initial-prompt");
        saveSettings.setName("turboism-with-fx.save-settings");
        repairRuntime.setName("turboism-with-fx.repair-runtime");
        runtimeStatus.setName("turboism-with-fx.runtime-status");
        sessionList.setName("turboism-with-fx.sessions");
        newSession.setName("turboism-with-fx.new-session");
        refreshSessions.setName("turboism-with-fx.refresh-sessions");
        openSettings.setName("turboism-with-fx.open-settings");
        transcript.setName("turboism-with-fx.transcript");
        prompt.setName("turboism-with-fx.prompt");
        send.setName("turboism-with-fx.send");
        cancel.setName("turboism-with-fx.cancel");
        agentStatus.setName("turboism-with-fx.status");
        executable.setText(initialExecutable);
        compatibility.setText(localization.text("security.compatibility"));
        compatibility.setSelected(initialCompatibility);
        connect.setText(localization.text("button.connect"));
        send.setText(localization.text("button.send"));
        cancel.setText(localization.text("button.stop"));
        newSession.setText(localization.text("button.new-session-short"));
        newSession.setToolTipText(localization.text("button.new-session"));
        refreshSessions.setText(localization.text("button.refresh-short"));
        refreshSessions.setToolTipText(localization.text("button.refresh-sessions"));
        openSettings.setText("");
        openSettings.setIcon(resourceIcon(SETTINGS_ICON_RESOURCE));
        openSettings.setToolTipText(localization.text("button.settings"));
        openSettings.getAccessibleContext().setAccessibleName(
            localization.text("button.settings")
        );
        saveSettings.setText(localization.text("button.save-settings"));
        repairRuntime.setText(localization.text("button.repair-runtime"));
        repairRuntime.setToolTipText(localization.text("label.managed-repair-detail"));
        showThinking.setText(localization.text("button.show-thinking"));
        initialPrompt.setText(savedInitialPrompt);
        installDisconnectedPlaceholder(providers, "label.provider-unavailable");
        installDisconnectedPlaceholder(models, "label.model-unavailable");
        final Dimension optionSize = new Dimension(300, 28);
        providers.setPreferredSize(optionSize);
        providers.setMinimumSize(new Dimension(180, 28));
        providers.setEditable(true);
        providers.setToolTipText(localization.text("label.provider-entry-detail"));
        models.setPreferredSize(optionSize);
        models.setMinimumSize(new Dimension(180, 28));
        models.setEditable(true);
        models.setToolTipText(localization.text("label.model-entry-detail"));
        send.setEnabled(false);
        cancel.setEnabled(false);
        newSession.setEnabled(false);
        refreshSessions.setEnabled(false);
        sessionList.setEnabled(false);
        sessionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        transcript.setEditable(false);
        transcript.setFont(UIManager.getFont("TextArea.font"));
        transcript.setBackground(color("TextArea.background", Color.WHITE));
        transcript.setForeground(color("TextArea.foreground", Color.BLACK));
        prompt.setLineWrap(true);
        prompt.setWrapStyleWord(true);
        initialPrompt.setLineWrap(true);
        initialPrompt.setWrapStyleWord(true);
        configAvailability.setText(localization.text("label.config-owned-by-fx"));
        configAvailability.setFont(configAvailability.getFont().deriveFont(Font.PLAIN));
        runtimeStatus.setText(localization.text("status.managed-runtime-ready"));
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
        repairRuntime.addActionListener(ignored -> onRepairRuntime.run());
        send.addActionListener(ignored -> submitPrompt());
        cancel.addActionListener(ignored -> onCancel.run());
        newSession.addActionListener(ignored -> onNewSession.run());
        refreshSessions.addActionListener(ignored -> onRefreshSessions.run());
        openSettings.addActionListener(ignored -> showSettingsAndFront());
        showThinking.addActionListener(ignored -> renderTranscript());
        providers.addActionListener(ignored -> selected("provider", providers));
        models.addActionListener(ignored -> selected("model", models));
        sessionList.addListSelectionListener(ignored -> {
            if (ignored.getValueIsAdjusting() || applyingSessions) return;
            final SessionItem selected = sessionList.getSelectedValue();
            if (selected != null) onSelectSession.accept(selected.sessionId());
        });
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
            BorderFactory.createEmptyBorder(4, 6, 4, 4)
        ));
        final JPanel state = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        state.add(connectionDot);
        state.add(agentStatus);
        state.add(separator());
        state.add(providerStatus);
        state.add(separator());
        state.add(modelStatus);
        final JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
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
        tabs.addTab(localization.text("tab.runtime"), settingsPage(runtimeSection()));
        tabs.addTab(localization.text("tab.provider-model"), settingsPage(configSection()));
        tabs.addTab(
            localization.text("tab.security-instructions"),
            settingsPage(instructionsSection())
        );
        root.add(tabs, BorderLayout.CENTER);
        final JPanel footer = new JPanel(new BorderLayout(8, 8));
        footer.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        footer.add(runtimeStatus, BorderLayout.CENTER);
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

    private JPanel runtimeSection() {
        final JPanel panel = section("section.runtime");
        final GridBagConstraints constraints = formConstraints();
        add(panel, constraints, localization.text("label.fx-executable-override"), executable);
        constraints.gridx = 2;
        constraints.weightx = 0;
        panel.add(connect, constraints);
        constraints.gridy++;
        constraints.gridx = 0;
        constraints.gridwidth = 3;
        constraints.weightx = 1;
        final JLabel note = new JLabel(
            "<html>" + localization.text("label.managed-runtime-detail") + "</html>"
        );
        panel.add(note, constraints);
        constraints.gridy++;
        constraints.gridx = 0;
        constraints.gridwidth = 1;
        constraints.weightx = 0;
        panel.add(repairRuntime, constraints);
        constraints.gridx = 1;
        constraints.gridwidth = 2;
        constraints.weightx = 1;
        panel.add(new JLabel(
            "<html>" + localization.text("label.managed-repair-detail") + "</html>"
        ), constraints);
        return panel;
    }

    private JPanel configSection() {
        final JPanel panel = section("section.configuration");
        final GridBagConstraints constraints = formConstraints();
        add(panel, constraints, localization.text("label.provider"), providers);
        constraints.gridy++;
        add(panel, constraints, localization.text("label.model"), models);
        constraints.gridy++;
        constraints.gridx = 0;
        constraints.gridwidth = 3;
        constraints.weightx = 1;
        panel.add(configAvailability, constraints);
        constraints.gridy++;
        final JLabel ownership = new JLabel(
            "<html>" + localization.text("label.config-authentication-detail") + "</html>"
        );
        panel.add(ownership, constraints);
        return panel;
    }

    private JPanel instructionsSection() {
        final JPanel panel = section("section.security-instructions");
        final GridBagConstraints constraints = formConstraints();
        constraints.gridx = 0;
        constraints.gridwidth = 3;
        constraints.weightx = 1;
        panel.add(new JLabel("<html>" + localization.text("security.fixed-boundary") + "</html>"), constraints);
        constraints.gridy++;
        panel.add(compatibility, constraints);
        constraints.gridy++;
        panel.add(new JLabel(localization.text("label.initial-prompt")), constraints);
        constraints.gridy++;
        constraints.weighty = 1;
        constraints.fill = GridBagConstraints.BOTH;
        panel.add(new JScrollPane(initialPrompt), constraints);
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
        final JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.add(new JScrollPane(prompt), BorderLayout.CENTER);
        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(cancel);
        buttons.add(send);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private FxAcpListener.PermissionDecision permissionDialog(
        final FxAcpListener.PermissionRequest request
    ) {
        try {
            return showPermissionDialog(request);
        } catch (Throwable failure) {
            return FxAcpListener.PermissionDecision.CANCELLED;
        }
    }

    private FxAcpListener.PermissionDecision showPermissionDialog(
        final FxAcpListener.PermissionRequest request
    ) {
        if (!acceptingEvents.get()) return FxAcpListener.PermissionDecision.CANCELLED;
        showAgentAndFront();
        final AtomicReference<FxAcpListener.PermissionDecision> decision =
            new AtomicReference<>(FxAcpListener.PermissionDecision.CANCELLED);
        final JDialog dialog = TurboismWindowFactory.dialog(
            agentFrame,
            localization.text("permission.title"),
            true
        );
        if (dialog == null) return FxAcpListener.PermissionDecision.CANCELLED;
        try {
            dialog.setName("turboism-with-fx.permission");
            dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            dialog.setMinimumSize(permissionDialogMinimum());

            final JTextArea details = new JTextArea(request.details(), 12, 68);
            details.setEditable(false);
            details.setLineWrap(true);
            details.setWrapStyleWord(true);
            details.setCaretPosition(0);
            final JPanel message = new JPanel(new BorderLayout(0, 10));
            message.setBorder(BorderFactory.createEmptyBorder(14, 14, 8, 14));
            final JTextArea summary = new JTextArea(localization.format(
                "permission.message",
                request.title(),
                request.kind()
            ), 2, 68);
            summary.setEditable(false);
            summary.setLineWrap(true);
            summary.setWrapStyleWord(true);
            summary.setOpaque(false);
            message.add(summary, BorderLayout.NORTH);
            final JScrollPane detailScroll = new JScrollPane(details);
            detailScroll.setPreferredSize(new Dimension(640, 240));
            message.add(detailScroll, BorderLayout.CENTER);

            final JButton allowOnce = permissionButton(
                "turboism-with-fx.permission.allow-once",
                "permission.allow-once",
                FxAcpListener.PermissionDecision.ALLOW_ONCE,
                decision,
                dialog
            );
            final JButton allowSession = permissionButton(
                "turboism-with-fx.permission.allow-session",
                "permission.allow-session",
                FxAcpListener.PermissionDecision.ALLOW_ALWAYS,
                decision,
                dialog
            );
            final JButton reject = permissionButton(
                "turboism-with-fx.permission.reject",
                "permission.reject",
                FxAcpListener.PermissionDecision.REJECT_ONCE,
                decision,
                dialog
            );
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
        final FxAcpListener.PermissionDecision selected,
        final AtomicReference<FxAcpListener.PermissionDecision> decision,
        final JDialog dialog
    ) {
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
            Math.max(component.getWidth(), minimum.width),
            Math.max(component.getHeight(), minimum.height)
        );
    }

    private void submitPrompt() {
        if (!connected || prompting) return;
        final String text = prompt.getText().strip();
        if (text.isEmpty()) return;
        prompt.setText("");
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
                composing.set(hasUncommittedText(
                    event.getText(),
                    event.getCommittedCharacterCount()
                ));
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
            input.getInputMap().put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, modifiers),
                DefaultEditorKit.insertBreakAction
            );
        }
    }

    static boolean hasUncommittedText(
        final AttributedCharacterIterator text,
        final int committedCharacters
    ) {
        return text != null && committedCharacters < text.getEndIndex() - text.getBeginIndex();
    }

    private void setConversationControls(final boolean connectionReady, final boolean busy) {
        send.setEnabled(connectionReady && !busy);
        cancel.setEnabled(connectionReady && busy);
        prompt.setEnabled(connectionReady && !busy);
        newSession.setEnabled(connectionReady && !busy);
        refreshSessions.setEnabled(connectionReady && !busy && durableSessionsAvailable);
        sessionList.setEnabled(connectionReady && !busy && durableSessionsAvailable);
        providers.setEnabled(connectionReady && !busy && hasRealChoices(providers));
        models.setEnabled(connectionReady && !busy && hasRealChoices(models));
    }

    private void setStatus(final String localizationKey, final Color color) {
        setStatusText(localization.text(localizationKey), color);
    }

    /**
     * Updates the compact connection state and mirrors each state transition into the transcript.
     * Repeating the same callback is ignored so reconnect races cannot flood the conversation.
     */
    private void showLifecycleStatus(
        final String localizationKey,
        final StatusTone tone
    ) {
        final String text = localization.text(localizationKey);
        setStatusText(text, statusColor(tone));
        if (recordLifecycleMessage(lastLifecycleMessage, text)) {
            lastLifecycleMessage = text;
            appendSystem(text);
        }
    }

    static boolean recordLifecycleMessage(
        final String previous,
        final String next
    ) {
        return next != null && !next.isEmpty() && !next.equals(previous);
    }

    private void setStatusText(final String text, final Color color) {
        agentStatus.setText(text);
        runtimeStatus.setText(text);
        connectionDot.setForeground(color);
    }

    private void selected(
        final String id,
        final JComboBox<FxAcpConfigOption.Choice> combo
    ) {
        if (applyingOptions) return;
        final String value = selectedConfigValue(combo);
        if (value != null) onConfig.accept(id, value);
    }

    static String selectedConfigValue(
        final JComboBox<FxAcpConfigOption.Choice> combo
    ) {
        final Object selected = combo.getEditor().getItem();
        if (selected instanceof FxAcpConfigOption.Choice choice) {
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
        final boolean coalesce
    ) {
        if (!acceptingEvents.get() || text == null || text.isEmpty()) return;
        final TranscriptEntry previous = transcriptEntries.isEmpty()
            ? null : transcriptEntries.get(transcriptEntries.size() - 1);
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
            new StringBuilder(text)
        );
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
        while ((transcriptChars > MAX_TRANSCRIPT_CHARS
            || transcriptEntries.size() > MAX_TRANSCRIPT_ENTRIES)
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

    static int safePrefixLength(
        final CharSequence text,
        final int requested
    ) {
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

    private void appendRenderedText(
        final TranscriptEntry entry,
        final String text
    ) {
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

    private void replaceRenderedEntry(
        final TranscriptEntry entry,
        final int previousRenderedLength
    ) {
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

    private void shiftRenderedEntriesAfter(
        final TranscriptEntry changed,
        final int delta
    ) {
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

    private void renderEntry(
        final StyledDocument document,
        final TranscriptEntry entry,
        final int start
    ) throws javax.swing.text.BadLocationException {
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
        document.setParagraphAttributes(
            start,
            entry.renderedLength,
            paragraph,
            false
        );
    }

    private SimpleAttributeSet contentAttributes(final Sender sender) {
        final SimpleAttributeSet content = new SimpleAttributeSet();
        StyleConstants.setForeground(content, senderColor(sender));
        if (sender == Sender.THINKING) StyleConstants.setItalic(content, true);
        return content;
    }

    private String renderedEntry(final TranscriptEntry entry) {
        if (entry.sender != Sender.TOOL) return entry.content.toString();
        final String identity = entry.title.isBlank()
            ? boundedToolMetadata(entry.id)
            : entry.title;
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

    private void updateConfigSummary() {
        providerStatus.setText(localization.format(
            "status.provider-summary", selectedDisplay(providers, localization.text("status.unavailable"))
        ));
        modelStatus.setText(localization.format(
            "status.model-summary", selectedDisplay(models, localization.text("status.unavailable"))
        ));
        configAvailability.setText(connected
            ? localization.text("label.config-applies-immediately")
            : localization.text("label.config-owned-by-fx"));
    }

    private JFrame frame(final String titleKey) {
        final JFrame frame = TurboismWindowFactory.frame(localization.text(titleKey));
        if (frame == null) throw new IllegalStateException("Swing is unavailable in a headless JVM");
        return frame;
    }

    private static ImageIcon resourceIcon(final String path) {
        final ClassLoader loader = TurboismWithFxWindow.class.getClassLoader();
        final URL resource = loader == null ? null : loader.getResource(path);
        if (resource == null) return new ImageIcon();
        return new ImageIcon(resource);
    }

    private static void showAndFront(final JFrame frame) {
        frame.setVisible(true);
        frame.toFront();
        frame.requestFocus();
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
        final JPanel panel,
        final GridBagConstraints constraints,
        final String label,
        final Component component
    ) {
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

    private static FxAcpConfigOption find(
        final List<FxAcpConfigOption> options,
        final String id
    ) {
        return options.stream().filter(option -> option.id().equals(id)).findFirst().orElse(null);
    }

    private void apply(
        final JComboBox<FxAcpConfigOption.Choice> combo,
        final FxAcpConfigOption option
    ) {
        combo.removeAllItems();
        if (option == null) {
            installDisconnectedPlaceholder(
                combo,
                combo == providers ? "label.provider-unavailable" : "label.model-unavailable"
            );
            return;
        }
        FxAcpConfigOption.Choice selected = null;
        for (FxAcpConfigOption.Choice choice : option.choices()) {
            combo.addItem(choice);
            if (choice.value().equals(option.currentValue())) selected = choice;
        }
        combo.setSelectedItem(selected == null && combo.getItemCount() > 0 ? combo.getItemAt(0) : selected);
        combo.setEnabled(combo.getItemCount() > 0 && !prompting);
    }

    private void installDisconnectedPlaceholder(
        final JComboBox<FxAcpConfigOption.Choice> combo,
        final String localizationKey
    ) {
        applyingOptions = true;
        try {
            combo.removeAllItems();
            combo.addItem(new FxAcpConfigOption.Choice(
                "unavailable",
                localization.text(localizationKey)
            ));
            combo.setSelectedIndex(0);
            combo.setEnabled(false);
        } finally {
            applyingOptions = false;
        }
    }

    private static boolean hasRealChoices(final JComboBox<FxAcpConfigOption.Choice> combo) {
        return combo.getItemCount() > 0 && !"unavailable".equals(combo.getItemAt(0).value());
    }

    private static String selectedDisplay(
        final JComboBox<FxAcpConfigOption.Choice> combo,
        final String fallback
    ) {
        if (combo.getSelectedItem() instanceof FxAcpConfigOption.Choice choice
            && !"unavailable".equals(choice.value())) {
            return choice.name();
        }
        return fallback;
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

    private static Color blend(final Color base, final Color accent, final float amount) {
        final float bounded = Math.max(0F, Math.min(1F, amount));
        return new Color(
            Math.round(base.getRed() * (1F - bounded) + accent.getRed() * bounded),
            Math.round(base.getGreen() * (1F - bounded) + accent.getGreen() * bounded),
            Math.round(base.getBlue() * (1F - bounded) + accent.getBlue() * bounded)
        );
    }

    private enum Sender { USER, AGENT, SYSTEM, TOOL, THINKING }
    private enum StatusTone { CONNECTED, WORKING, ERROR }

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
            final StringBuilder content
        ) {
            this.sender = Objects.requireNonNull(sender, "sender");
            this.id = Objects.requireNonNull(id, "id");
            this.title = Objects.requireNonNull(title, "title");
            this.kind = Objects.requireNonNull(kind, "kind");
            this.content = Objects.requireNonNull(content, "content");
        }

        private int weight() {
            if (sender != Sender.TOOL) return content.length() + 1;
            final int identityLength = title.isBlank()
                ? boundedToolMetadata(id).length()
                : title.length();
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
