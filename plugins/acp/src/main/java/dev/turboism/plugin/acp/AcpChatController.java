package dev.turboism.plugin.acp;

import dev.turboism.sdk.mcp.McpConnectionService;
import dev.turboism.sdk.mcp.McpHttpConnection;
import dev.turboism.sdk.plugin.PluginContext;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Serial lifecycle controller joining the paired Swing view, the MCP registry, and one external
 * ACP agent process.
 *
 * <p>All process and session transitions run on one daemon executor. Session catalogs and any
 * durable-session rows come only from the agent over ACP. When the agent advertises only ephemeral
 * sessions, the controller creates a fresh session and exposes only its active opaque id without
 * calling unsupported lifecycle methods. Turboism never stores agent credentials or the MCP
 * bearer.</p>
 */
final class AcpChatController implements AutoCloseable, AcpListener {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration CLIENT_START_TIMEOUT = Duration.ofSeconds(25);
    private static final int MAX_PENDING_UI_UPDATES = 256;
    static final int MAX_PENDING_LOAD_EVENTS = 64;
    static final long MAX_PENDING_LOAD_TEXT_BYTES = 1024L * 1024L;
    static final int MAX_PROMPT_CHARS = 1024 * 1024;
    static final String SYSTEM_BOUNDARY =
            "Use only the Turboism MCP tools for Cubism automation. Do not use native filesystem, "
                    + "terminal, search, or fetch tools.";

    private final PluginContext context;
    private final AcpPluginSettings settings;
    private final ClientStarter clientStarter;
    private final Duration clientStartTimeout;
    private final View view;
    private final ExecutorService serial;
    private final Object stateLock = new Object();
    private final Object uiLock = new Object();
    private final ArrayDeque<UiUpdate> pendingUi = new ArrayDeque<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<AcpClient> client = new AtomicReference<>();
    private final AtomicReference<PendingSettings> pendingSettings = new AtomicReference<>();
    private boolean uiDrainScheduled;
    private boolean uiOverflowReported;
    private LoadTransaction loadTransaction;
    private long generationCounter;
    private long sessionGeneration;
    private volatile AcpSession session;
    private volatile McpHttpConnection mcpConnection;
    private volatile AgentLaunchSpec launchSpec;
    private volatile boolean prompting;
    private final AtomicReference<dev.turboism.sdk.plugin.Registration> mcpSubscription =
            new AtomicReference<>();
    private final AtomicReference<java.util.Optional<McpHttpConnection>> lastMcpSnapshot =
            new AtomicReference<>(java.util.Optional.empty());
    private final AtomicBoolean mcpReconnectQueued = new AtomicBoolean();

    AcpChatController(final PluginContext context, final AcpPluginSettings settings, final View view) {
        this(context, settings, view, AcpClient::start);
    }

    AcpChatController(
            final PluginContext context,
            final AcpPluginSettings settings,
            final View view,
            final ClientStarter clientStarter) {
        this(context, settings, view, clientStarter, CLIENT_START_TIMEOUT);
    }

    AcpChatController(
            final PluginContext context,
            final AcpPluginSettings settings,
            final View view,
            final ClientStarter clientStarter,
            final Duration clientStartTimeout) {
        this.context = Objects.requireNonNull(context, "context");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clientStarter = Objects.requireNonNull(clientStarter, "clientStarter");
        this.clientStartTimeout = Objects.requireNonNull(clientStartTimeout, "clientStartTimeout");
        if (clientStartTimeout.isZero() || clientStartTimeout.isNegative()) {
            throw new IllegalArgumentException("clientStartTimeout must be positive");
        }
        this.view = Objects.requireNonNull(view, "view");
        this.serial = Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "turboism-acp-controller");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Connects using the persisted agent selection. */
    void connect() {
        submit(() -> {
            ui(() -> view.showConnecting(agentLabel(settings.agentId())));
            connectNow();
        });
    }

    /** Persists the editor state, then connects to the selected agent. */
    void connect(final String agentId, final String customCommand, final String initialPrompt) {
        final PendingSettings pending = new PendingSettings(agentId, customCommand, initialPrompt);
        pendingSettings.set(pending);
        submit(() -> {
            try {
                if (!saveSettingsNow(agentId, customCommand, initialPrompt)) return;
                ui(() -> view.showConnecting(agentLabel(settings.agentId())));
                connectNow();
            } finally {
                pendingSettings.compareAndSet(pending, null);
            }
        });
    }

    /** Persists the editor state without reconnecting. */
    void saveSettings(final String agentId, final String customCommand, final String initialPrompt) {
        final PendingSettings pending = new PendingSettings(agentId, customCommand, initialPrompt);
        pendingSettings.set(pending);
        submit(() -> {
            try {
                if (saveSettingsNow(agentId, customCommand, initialPrompt)) {
                    ui(view::showSettingsSaved);
                }
            } finally {
                pendingSettings.compareAndSet(pending, null);
            }
        });
    }

    /** Resolves every catalog profile and reports which executables were found. */
    void detectAgents() {
        submit(() -> {
            final Map<String, String> detected = new LinkedHashMap<>();
            for (AgentProfile profile : AgentCatalog.profiles()) {
                detected.put(
                        profile.id(),
                        AgentLocator.locate(profile)
                                .map(java.nio.file.Path::toString)
                                .orElse(""));
            }
            ui(() -> view.showDetectedAgents(detected));
        });
    }

    /** Runs the advertised ACP authentication method selected by the user. */
    void authenticate(final String methodId) {
        if (methodId == null || methodId.isBlank()) return;
        submit(() -> authenticateNow(methodId));
    }

    /** Ends the agent's authenticated state when it advertised the logout capability. */
    void logout() {
        submit(this::logoutNow);
    }

    /**
     * Opens the agent's documented interactive login command in a visible terminal. Used for
     * agents that do not advertise a protocol-driven authentication method.
     */
    void openAgentLogin() {
        submit(() -> {
            try {
                final AgentLaunchSpec spec = launchSpec != null ? launchSpec : resolveLaunchSpec();
                final AgentProfile profile = AgentCatalog.profile(settings.agentId()).orElse(null);
                final List<String> arguments = profile != null ? profile.loginArguments() : List.of();
                if (arguments.isEmpty()) {
                    ui(() -> view.showSessionFailure("status.auth-terminal-unavailable"));
                    return;
                }
                openTerminalAuth(spec.executable(), arguments, spec.environment());
            } catch (IllegalArgumentException | IllegalStateException failure) {
                ui(() -> view.showSessionFailure("status.auth-terminal-failed"));
            }
        });
    }

    void sendPrompt(final String text) {
        final String prompt = text == null ? "" : text.strip();
        if (prompt.isEmpty()) return;
        submit(() -> promptNow(prompt));
    }

    void newSession() {
        submit(this::newSessionNow);
    }

    void selectSession(final String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        submit(() -> selectSessionNow(sessionId));
    }

    void refreshSessions() {
        submit(this::refreshSessionsNow);
    }

    void setConfigOption(final String id, final String value) {
        submit(() -> setConfigNow(id, value));
    }

    void cancel() {
        submit(this::cancelNow);
    }

    void disconnect() {
        submit(() -> {
            disconnectNow();
            ui(view::showDisconnected);
        });
    }

    private boolean saveSettingsNow(
            final String agentId, final String customCommand, final String initialPrompt) {
        try {
            if (settings.writeUserSettings(agentId, customCommand, initialPrompt)) return true;
            context.logger().warn("Turboism ACP settings could not be persisted");
            ui(() -> view.showFailure("status.settings-save-failed"));
        } catch (IllegalArgumentException failure) {
            context.logger().warn("Turboism ACP settings were invalid");
            ui(() -> view.showFailure("status.settings-invalid"));
        }
        return false;
    }

    /** Builds the launch spec for the currently selected agent. */
    private AgentLaunchSpec resolveLaunchSpec() {
        final String agentId = settings.agentId();
        final java.util.ArrayList<String> argv = new java.util.ArrayList<>();
        if (AgentCatalog.CUSTOM_AGENT_ID.equals(agentId)) {
            final String command = settings.customCommand();
            if (command == null || command.isBlank()) {
                throw new IllegalStateException("status.agent-custom-missing");
            }
            final List<String> parsed = AcpPluginSettings.splitCommand(command);
            argv.add(AgentLocator.resolve(parsed.get(0)).toString());
            argv.addAll(parsed.subList(1, parsed.size()));
        } else {
            final AgentProfile profile = AgentCatalog.profile(agentId)
                    .orElseThrow(() -> new IllegalStateException("status.agent-unknown"));
            argv.add(AgentLocator.locate(profile)
                    .map(java.nio.file.Path::toString)
                    .orElseThrow(() -> new IllegalStateException("status.agent-not-found-generic")));
            argv.addAll(profile.arguments());
        }
        return new AgentLaunchSpec(List.copyOf(argv), context.paths().stateDir());
    }

    private void connectNow() {
        context.logger().info("ACP connection: starting");
        disconnectNow();
        try {
            final McpConnectionService mcpConnections = context.services()
                    .find(McpConnectionService.class)
                    .orElseGet(McpConnectionService::unavailable);
            ensureMcpSubscription(mcpConnections);
            final McpHttpConnection connection = mcpSubscription.get() != null
                    ? lastMcpSnapshot.get().orElse(null)
                    : mcpConnections.current().orElse(null);
            final AgentLaunchSpec spec;
            try {
                spec = resolveLaunchSpec();
            } catch (IllegalArgumentException | IllegalStateException failure) {
                ui(() -> view.showFailure(failure.getMessage() != null
                                && failure.getMessage().startsWith("status.")
                        ? failure.getMessage()
                        : "status.agent-not-found-generic"));
                return;
            }
            launchSpec = spec;
            context.logger().info("ACP connection: starting agent process");
            final AcpClient connected;
            try {
                connected = startClient(spec);
            } catch (IOException | AcpException | RuntimeException failure) {
                throw failure;
            }
            context.logger().info("ACP connection: ACP initialized");
            if (closed.get()) {
                connected.close();
                return;
            }
            client.set(connected);
            if (closed.get()) {
                client.compareAndSet(connected, null);
                connected.close();
                return;
            }
            mcpConnection = connection;
            establishSession(connected, connection);
        } catch (IOException failure) {
            connectionFailed(failure, "status.executable-start-failed");
        } catch (AcpException failure) {
            if (failure.authRequired()) {
                authRequired(client.get());
            } else {
                connectionFailed(failure, diagnosticKey(failure));
            }
        } catch (RuntimeException failure) {
            connectionFailed(failure, "status.connection-failed");
        }
    }

    /**
     * Restores the durable session when the agent supports it, otherwise creates a new one.
     */
    private void establishSession(
            final AcpClient connected, final McpHttpConnection connection)
            throws AcpException {
        final AcpClient.AcpCapabilities capabilities = connected.capabilities();
        final String savedSessionId = settings.sessionId();
        if (savedSessionId != null && !capabilities.loadSession() && !capabilities.resumeSession()) {
            saveSessionIdSafely(null);
        }
        final String restorable = savedSessionId != null
                        && (capabilities.loadSession() || capabilities.resumeSession())
                ? savedSessionId
                : null;
        if (restorable == null) {
            context.logger().info("ACP connection: creating session");
            final AcpSession created =
                    connected.newSession(context.paths().stateDir(), connection, REQUEST_TIMEOUT);
            activateSession(created);
            context.logger().info("ACP connection: session ready");
            ui(() -> view.showConnected(
                    connected.agentInfo(),
                    created.configOptions(),
                    created.durableSessionsAvailable(),
                    connection != null && capabilities.mcpHttp()));
            refreshSessionsNow();
            return;
        }
        if (capabilities.loadSession()) {
            context.logger().info("ACP connection: loading saved session");
            final LoadTransaction load = beginLoadTransaction(connected, restorable);
            try {
                final AcpSession restored = connected.loadSession(
                        restorable, context.paths().stateDir(), connection, REQUEST_TIMEOUT);
                if (!completeLoadTransaction(load, restored, () -> view.showConnected(
                        connected.agentInfo(),
                        restored.configOptions(),
                        restored.durableSessionsAvailable(),
                        connection != null && capabilities.mcpHttp()))) {
                    return;
                }
                context.logger().info("ACP connection: session ready");
                refreshSessionsNow();
                return;
            } catch (AcpException | RuntimeException loadFailure) {
                if (loadFailure instanceof AcpException acp && acp.authRequired()) {
                    discardCurrentLoadTransaction(load);
                    throw acp;
                }
                if (!discardCurrentLoadTransaction(load) || !loadGenerationCurrent(load)) return;
                context.logger().warn("Stored ACP session could not be loaded; creating a new session");
            }
        } else {
            try {
                context.logger().info("ACP connection: resuming saved session");
                final AcpSession restored = connected.resumeSession(
                        restorable, context.paths().stateDir(), connection, REQUEST_TIMEOUT);
                activateSession(restored);
                ui(() -> {
                    view.clearTranscript();
                    view.showConnected(
                            connected.agentInfo(),
                            restored.configOptions(),
                            restored.durableSessionsAvailable(),
                            connection != null && capabilities.mcpHttp());
                });
                refreshSessionsNow();
                return;
            } catch (AcpException | RuntimeException resumeFailure) {
                if (resumeFailure instanceof AcpException acp && acp.authRequired()) throw acp;
                context.logger().warn("Stored ACP session could not be resumed; creating a new session");
            }
        }
        saveSessionIdSafely(null);
        context.logger().info("ACP connection: creating session");
        final AcpSession created =
                connected.newSession(context.paths().stateDir(), connection, REQUEST_TIMEOUT);
        activateSession(created);
        context.logger().info("ACP connection: session ready");
        ui(() -> view.showConnected(
                connected.agentInfo(),
                created.configOptions(),
                created.durableSessionsAvailable(),
                connection != null && capabilities.mcpHttp()));
        refreshSessionsNow();
    }

    private void authRequired(final AcpClient connected) {
        if (connected == null) {
            ui(() -> view.showFailure("status.auth-required"));
            return;
        }
        context.logger().warn("ACP agent requires authentication");
        final List<AcpAuthMethod> methods = connected.authMethods();
        if (methods.isEmpty()) {
            ui(() -> view.showFailure("status.auth-required-manual"));
            return;
        }
        ui(() -> view.showAuthRequired(methods));
    }

    private void authenticateNow(final String methodId) {
        final AcpClient active = client.get();
        if (active == null) {
            ui(() -> view.showFailure("status.not-connected"));
            return;
        }
        final AcpAuthMethod method = active.authMethods().stream()
                .filter(candidate -> candidate.id().equals(methodId))
                .findFirst()
                .orElse(null);
        if (method == null) {
            ui(() -> view.showSessionFailure("status.auth-failed"));
            return;
        }
        if (method.kind() == AcpAuthMethod.Kind.TERMINAL) {
            final AgentLaunchSpec spec = launchSpec;
            if (spec == null) {
                ui(() -> view.showSessionFailure("status.auth-failed"));
                return;
            }
            // ACP: the terminal command is the configured agent invocation plus the method args.
            final ArrayList<String> terminalCommand = new ArrayList<>(spec.command());
            terminalCommand.addAll(method.args());
            final LinkedHashMap<String, String> environment = new LinkedHashMap<>(spec.environment());
            environment.putAll(method.env());
            openTerminalAuth(terminalCommand.get(0), terminalCommand.subList(1, terminalCommand.size()), environment);
            return;
        }
        try {
            active.authenticate(methodId, REQUEST_TIMEOUT);
            final McpHttpConnection connection = mcpConnection;
            establishSession(active, connection);
        } catch (AcpException failure) {
            if (failure.authRequired()) {
                authRequired(active);
            } else {
                context.logger().error("ACP authentication failed", failure);
                ui(() -> view.showSessionFailure("status.auth-failed"));
            }
        } catch (RuntimeException failure) {
            context.logger().error("ACP authentication failed", failure);
            ui(() -> view.showSessionFailure("status.auth-failed"));
        }
    }

    private void logoutNow() {
        final AcpClient active = client.get();
        if (active == null || !active.capabilities().authLogout()) {
            ui(() -> view.showSessionFailure("status.auth-logout-unsupported"));
            return;
        }
        try {
            active.logout(REQUEST_TIMEOUT);
            saveSessionIdSafely(null);
            disconnectNow();
            ui(view::showDisconnected);
        } catch (AcpException failure) {
            context.logger().error("ACP logout failed", failure);
            ui(() -> view.showSessionFailure("status.auth-failed"));
        }
    }

    private void openTerminalAuth(
            final String executable, final List<String> arguments, final Map<String, String> environment) {
        final ArrayList<String> terminalCommand = new ArrayList<>(List.of(executable));
        terminalCommand.addAll(arguments);
        try {
            AgentTerminalLauncher.open(
                    List.copyOf(terminalCommand), environment, context.paths().stateDir());
            ui(() -> view.showSessionFailure("status.auth-terminal-opened"));
        } catch (IOException | RuntimeException failure) {
            context.logger().error("Turboism ACP could not open an agent login terminal", failure);
            ui(() -> view.showSessionFailure("status.auth-terminal-failed"));
        }
    }

    private AcpClient startClient(final AgentLaunchSpec configuration) throws IOException, AcpException {
        final AtomicBoolean abandoned = new AtomicBoolean();
        final java.util.concurrent.FutureTask<AcpClient> launch = new java.util.concurrent.FutureTask<>(() -> {
            final AcpClient started = clientStarter.start(configuration, this);
            if (abandoned.get() || closed.get()) {
                started.close();
                throw new AcpException("ACP startup was cancelled");
            }
            return started;
        });
        final Thread thread = new Thread(launch, "turboism-acp-agent-start");
        thread.setDaemon(true);
        thread.start();
        try {
            return launch.get(clientStartTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException failure) {
            abandoned.set(true);
            launch.cancel(true);
            throw new AcpException("timed out starting the ACP agent", failure);
        } catch (InterruptedException failure) {
            abandoned.set(true);
            launch.cancel(true);
            Thread.currentThread().interrupt();
            throw new AcpException("interrupted while starting the ACP agent", failure);
        } catch (java.util.concurrent.ExecutionException failure) {
            final Throwable cause = failure.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof AcpException acp) throw acp;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new AcpException("ACP agent startup failed", cause);
        }
    }

    private void connectionFailed(final Throwable failure, final String localizationKey) {
        disconnectNow();
        context.logger().error("Turboism ACP could not connect", failure);
        ui(() -> view.showFailure(localizationKey));
    }

    private void newSessionNow() {
        final AcpClient active = client.get();
        final McpHttpConnection connection = mcpConnection;
        if (active == null || prompting) return;
        try {
            activateSession(active.newSession(context.paths().stateDir(), connection, REQUEST_TIMEOUT));
            final AcpSession created = session;
            ui(() -> {
                view.clearTranscript();
                view.showConfigOptions(created.configOptions());
                view.showSessions(
                        List.of(new AcpSessionSummary(created.sessionId(), "active")),
                        created.sessionId(),
                        created.durableSessionsAvailable());
            });
            refreshSessionsNow();
        } catch (AcpException failure) {
            if (failure.authRequired()) {
                authRequired(active);
                return;
            }
            context.logger().error("ACP session creation failed", failure);
            ui(() -> view.showSessionFailure("status.session-new-failed"));
        }
    }

    private void selectSessionNow(final String sessionId) {
        final AcpClient active = client.get();
        final McpHttpConnection connection = mcpConnection;
        final AcpSession current = session;
        if (active == null
                || current == null
                || prompting
                || current.sessionId().equals(sessionId)) {
            return;
        }
        if (current.capabilities().loadSession()) {
            final LoadTransaction load = beginLoadTransaction(active, sessionId);
            try {
                final AcpSession loaded =
                        active.loadSession(sessionId, context.paths().stateDir(), connection, REQUEST_TIMEOUT);
                if (!completeLoadTransaction(load, loaded, () -> {
                    view.clearTranscript();
                    view.showConfigOptions(loaded.configOptions());
                })) {
                    return;
                }
                refreshSessionsNow();
            } catch (AcpException | RuntimeException failure) {
                if (!discardCurrentLoadTransaction(load)) return;
                context.logger().error("ACP session selection failed", failure);
                ui(() -> view.showSessionFailure("status.session-load-failed"));
            }
            return;
        }
        if (!current.capabilities().resumeSession()) return;
        try {
            final AcpSession restored =
                    active.resumeSession(sessionId, context.paths().stateDir(), connection, REQUEST_TIMEOUT);
            activateSession(restored);
            ui(() -> {
                view.clearTranscript();
                view.showConfigOptions(restored.configOptions());
            });
            refreshSessionsNow();
        } catch (AcpException | RuntimeException failure) {
            context.logger().error("ACP session resume failed", failure);
            ui(() -> view.showSessionFailure("status.session-load-failed"));
        }
    }

    private void refreshSessionsNow() {
        final AcpClient active = client.get();
        final AcpSession current = session;
        if (active == null || current == null || prompting) return;
        if (!current.capabilities().listSessions()) {
            ui(() -> view.showSessions(
                    List.of(new AcpSessionSummary(current.sessionId(), "active")), current.sessionId(), false));
            return;
        }
        try {
            final List<AcpSessionSummary> sessions = active.listSessions(REQUEST_TIMEOUT);
            ui(() -> view.showSessions(sessions, current.sessionId(), true));
        } catch (AcpException failure) {
            context.logger().error("ACP session list failed", failure);
            ui(() -> view.showSessionFailure("status.session-list-failed"));
        }
    }

    private void activateSession(final AcpSession created) {
        synchronized (stateLock) {
            session = created;
            sessionGeneration = generationCounter;
        }
        if (created.durableSessionsAvailable()) {
            saveSessionIdSafely(created.sessionId());
        } else {
            saveSessionIdSafely(null);
        }
    }

    private void saveSessionIdSafely(final String sessionId) {
        if (sessionId == null) settings.clearSessionId();
        else settings.writeSessionId(sessionId);
    }

    private LoadTransaction beginLoadTransaction(final AcpClient source, final String sessionId) {
        synchronized (stateLock) {
            loadTransaction = new LoadTransaction(source, sessionId, generationCounter);
            return loadTransaction;
        }
    }

    @SuppressWarnings("ReferenceEquality")
    private boolean completeLoadTransaction(
            final LoadTransaction load, final AcpSession restored, final Runnable onComplete) {
        final List<ReplayEvent> replay;
        final AcpSession previous;
        final long generation;
        synchronized (stateLock) {
            if (!currentLoadTransactionLocked(load)) return false;
            loadTransaction = null;
            previous = session;
            session = restored;
            sessionGeneration = generationCounter;
            generation = generationCounter;
            replay = load.events();
        }
        if (!enqueueUi(
                () -> {
                    onComplete.run();
                    for (ReplayEvent event : replay) {
                        if (activeGeneration(load.source(), load.sessionId(), generation)) {
                            event.deliver(view);
                        }
                    }
                },
                false)) {
            synchronized (stateLock) {
                if (session == restored) session = previous;
            }
            return false;
        }
        saveSessionIdSafely(restored.durableSessionsAvailable() ? restored.sessionId() : null);
        return true;
    }

    private boolean currentLoadTransactionLocked(final LoadTransaction load) {
        return loadTransaction == load
                && client.get() == load.source()
                && !closed.get()
                && generationCounter == load.generation();
    }

    private void discardLoadTransaction(final LoadTransaction load) {
        synchronized (stateLock) {
            if (loadTransaction == load) loadTransaction = null;
        }
    }

    private boolean discardCurrentLoadTransaction(final LoadTransaction load) {
        synchronized (stateLock) {
            if (!currentLoadTransactionLocked(load)) return false;
            loadTransaction = null;
            return true;
        }
    }

    private boolean loadGenerationCurrent(final LoadTransaction load) {
        synchronized (stateLock) {
            return !closed.get() && client.get() == load.source() && generationCounter == load.generation();
        }
    }

    private void discardLoadTransaction(final AcpClient source) {
        synchronized (stateLock) {
            if (loadTransaction != null && loadTransaction.source() == source) {
                loadTransaction = null;
            }
        }
    }

    private void discardLoadTransaction() {
        synchronized (stateLock) {
            loadTransaction = null;
        }
    }

    private void promptNow(final String text) {
        final AcpClient active = client.get();
        final AcpSession current = session;
        if (active == null || current == null || prompting) return;
        final String initial = settings.initialPrompt().orElse("").strip();
        final String prefix = initial.isEmpty()
                ? SYSTEM_BOUNDARY + "\n\nUser request:\n"
                : SYSTEM_BOUNDARY + "\n\nUser-configured initial instructions:\n" + initial + "\n\nUser request:\n";
        if (text.length() > MAX_PROMPT_CHARS - prefix.length()) {
            context.logger().warn("ACP text limit exceeded; prompt was not sent");
            ui(() -> view.showSessionFailure("status.prompt-failed"));
            return;
        }
        final CompletableFuture<String> prompt;
        try {
            prompt = active.prompt(current.sessionId(), prefix + text);
        } catch (RuntimeException failure) {
            context.logger().error("ACP prompt failed before dispatch", failure);
            ui(() -> view.showSessionFailure("status.prompt-failed"));
            return;
        }
        prompting = true;
        ui(() -> {
            view.appendUser(text);
            view.showPrompting();
        });
        prompt.whenComplete((stopReason, failure) -> submit(() -> {
            if (client.get() != active) return;
            prompting = false;
            if (failure != null) {
                context.logger().error("ACP prompt failed", unwrap(failure));
                ui(() -> view.showSessionFailure("status.prompt-failed"));
            } else {
                ui(() -> view.showPromptComplete(stopReason));
                refreshSessionsNow();
            }
        }));
    }

    private void cancelNow() {
        final AcpClient active = client.get();
        final AcpSession current = session;
        if (active != null && current != null && prompting) {
            active.cancel(current.sessionId());
        }
    }

    // Session currency is identity: a different instance means the async reply is stale.
    @SuppressWarnings("ReferenceEquality")
    private void setConfigNow(final String id, final String value) {
        final AcpClient active = client.get();
        final AcpSession current = session;
        if (active == null || current == null || prompting) return;
        ui(() -> view.showConfigUpdating(id));
        final AcpClient.PendingConfigUpdate update;
        try {
            update = active.setConfigOption(current.sessionId(), id, value);
        } catch (RuntimeException failure) {
            context.logger().error("ACP configuration update failed before dispatch", failure);
            ui(() -> view.showConfigFailure(id, current.configOptions()));
            return;
        }
        try {
            final List<AcpConfigOption> options =
                    update.result().get(REQUEST_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (client.get() != active || session != current) return;
            session = new AcpSession(current.sessionId(), options, current.capabilities());
            ui(() -> view.showConfigOptions(options));
        } catch (InterruptedException interrupted) {
            active.abandon(update.request());
            Thread.currentThread().interrupt();
            ui(() -> view.showConfigFailure(id, current.configOptions()));
        } catch (java.util.concurrent.TimeoutException failure) {
            active.abandon(update.request());
            context.logger().error("ACP configuration update timed out", failure);
            ui(() -> view.showConfigFailure(id, current.configOptions()));
        } catch (java.util.concurrent.ExecutionException failure) {
            context.logger().error("ACP configuration update failed", unwrap(failure));
            ui(() -> view.showConfigFailure(id, current.configOptions()));
        }
    }

    @Override
    public void agentText(final AcpClient source, final String sessionId, final String text) {
        sourceEvent(source, sessionId, new AgentTextEvent(text));
    }

    @Override
    public void agentThought(final AcpClient source, final String sessionId, final String text) {
        sourceEvent(source, sessionId, new AgentThoughtEvent(text));
    }

    @Override
    public void toolCall(
            final AcpClient source,
            final String sessionId,
            final String toolCallId,
            final String title,
            final String kind,
            final String status) {
        sourceEvent(source, sessionId, new ToolCallEvent(toolCallId, title, kind, status));
    }

    @Override
    public void toolCallUpdate(
            final AcpClient source,
            final String sessionId,
            final String toolCallId,
            final String status,
            final String content) {
        sourceEvent(source, sessionId, new ToolCallUpdateEvent(toolCallId, status, content));
    }

    @Override
    public void configOptions(
            final AcpClient source, final String sessionId, final List<AcpConfigOption> options) {
        synchronized (stateLock) {
            final AcpSession current = session;
            if (client.get() != source
                    || current == null
                    || !current.sessionId().equals(sessionId)
                    || loadTransaction != null) {
                return;
            }
            session = new AcpSession(sessionId, options, current.capabilities());
        }
        ui(() -> view.showConfigOptions(options));
    }

    @Override
    public void availableCommands(final AcpClient source, final String sessionId, final List<String> commands) {
        // Slash-command metadata is informational only for now.
    }

    @Override
    public void stderr(final AcpClient source, final String text) {
        if (client.get() == source) context.logger().warn("acp-agent: " + text);
    }

    @Override
    public void terminated(final AcpClient source, final String message) {
        discardLoadTransaction(source);
        submit(() -> {
            if (!client.compareAndSet(source, null)) return;
            discardLoadTransaction(source);
            synchronized (stateLock) {
                session = null;
            }
            mcpConnection = null;
            prompting = false;
            ui(() -> view.showFailure("status.process-terminated"));
        });
    }

    @Override
    public PermissionDecision permission(
            final AcpClient source, final String sessionId, final PermissionRequest request) {
        final long generation;
        synchronized (stateLock) {
            if (loadTransaction != null || !activeSession(source, sessionId)) {
                return PermissionDecision.CANCELLED;
            }
            generation = sessionGeneration;
        }
        final PermissionDecision decision = view.requestPermission(request);
        return activeGeneration(source, sessionId, generation) ? decision : PermissionDecision.CANCELLED;
    }

    private void sourceEvent(final AcpClient source, final String sessionId, final ReplayEvent event) {
        final long generation;
        synchronized (stateLock) {
            if (loadTransaction != null) {
                loadTransaction.add(source, sessionId, event);
                return;
            }
            if (!activeSession(source, sessionId)) return;
            generation = sessionGeneration;
            uiStream(guardedDelivery(source, sessionId, generation, event));
        }
    }

    private Runnable guardedDelivery(
            final AcpClient source, final String sessionId, final long generation, final ReplayEvent event) {
        return () -> {
            if (!activeGeneration(source, sessionId, generation)) return;
            event.deliver(view);
        };
    }

    private boolean activeSession(final AcpClient source, final String sessionId) {
        final AcpSession current = session;
        return client.get() == source && current != null && current.sessionId().equals(sessionId);
    }

    private boolean activeGeneration(final AcpClient source, final String sessionId, final long generation) {
        synchronized (stateLock) {
            return !closed.get()
                    && client.get() == source
                    && session != null
                    && session.sessionId().equals(sessionId)
                    && sessionGeneration == generation;
        }
    }

    private sealed interface ReplayEvent permits AgentTextEvent, AgentThoughtEvent, ToolCallEvent, ToolCallUpdateEvent {
        long textBytes();

        void deliver(View target);
    }

    private record AgentTextEvent(String text) implements ReplayEvent {
        private AgentTextEvent {
            text = Objects.requireNonNull(text, "text");
        }

        @Override
        public long textBytes() {
            return utf8Bytes(text);
        }

        @Override
        public void deliver(final View target) {
            target.appendAgent(text);
        }
    }

    private record AgentThoughtEvent(String text) implements ReplayEvent {
        private AgentThoughtEvent {
            text = Objects.requireNonNull(text, "text");
        }

        @Override
        public long textBytes() {
            return utf8Bytes(text);
        }

        @Override
        public void deliver(final View target) {
            target.appendThinking(text);
        }
    }

    private record ToolCallEvent(String toolCallId, String title, String kind, String status) implements ReplayEvent {
        private ToolCallEvent {
            toolCallId = Objects.requireNonNull(toolCallId, "toolCallId");
            title = Objects.requireNonNull(title, "title");
            kind = Objects.requireNonNull(kind, "kind");
            status = Objects.requireNonNull(status, "status");
        }

        @Override
        public long textBytes() {
            return utf8Bytes(toolCallId) + utf8Bytes(title) + utf8Bytes(kind) + utf8Bytes(status);
        }

        @Override
        public void deliver(final View target) {
            target.appendTool(toolCallId, title, kind, status);
        }
    }

    private record ToolCallUpdateEvent(String toolCallId, String status, String content) implements ReplayEvent {
        private ToolCallUpdateEvent {
            toolCallId = Objects.requireNonNull(toolCallId, "toolCallId");
            status = Objects.requireNonNull(status, "status");
            content = Objects.requireNonNull(content, "content");
        }

        @Override
        public long textBytes() {
            return utf8Bytes(toolCallId) + utf8Bytes(status) + utf8Bytes(content);
        }

        @Override
        public void deliver(final View target) {
            target.updateTool(toolCallId, status, content);
        }
    }

    private final class LoadTransaction {
        private final AcpClient source;
        private final String sessionId;
        private final long generation;
        private final ArrayDeque<ReplayEvent> events = new ArrayDeque<>();
        private long textBytes;
        private boolean overflowReported;

        private LoadTransaction(final AcpClient source, final String sessionId, final long generation) {
            this.source = Objects.requireNonNull(source, "source");
            this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
            this.generation = generation;
        }

        private AcpClient source() {
            return source;
        }

        private String sessionId() {
            return sessionId;
        }

        private long generation() {
            return generation;
        }

        private List<ReplayEvent> events() {
            return List.copyOf(events);
        }

        private void add(final AcpClient eventSource, final String eventSessionId, final ReplayEvent event) {
            if (eventSource != source
                    || !sessionId.equals(eventSessionId)
                    || generation != generationCounter
                    || overflowReported) {
                return;
            }
            final long eventBytes = event.textBytes();
            if (events.size() >= MAX_PENDING_LOAD_EVENTS || eventBytes > MAX_PENDING_LOAD_TEXT_BYTES - textBytes) {
                overflowReported = true;
                context.logger().warn("Turboism ACP dropped excess session-load replay events");
                return;
            }
            events.addLast(event);
            textBytes += eventBytes;
        }
    }

    private static long utf8Bytes(final String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    private boolean ui(final Runnable work) {
        return enqueueUi(work, false);
    }

    private void uiStream(final Runnable work) {
        enqueueUi(work, true);
    }

    private boolean enqueueUi(final Runnable work, final boolean droppable) {
        if (closed.get()) return false;
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            work.run();
            return true;
        }
        boolean schedule = false;
        synchronized (uiLock) {
            if (closed.get()) return false;
            if (pendingUi.size() >= MAX_PENDING_UI_UPDATES) {
                final UiUpdate dropped = pendingUi.stream()
                        .filter(UiUpdate::droppable)
                        .findFirst()
                        .orElse(null);
                if (dropped == null) {
                    if (droppable) reportUiOverflow();
                    return false;
                }
                pendingUi.remove(dropped);
                reportUiOverflow();
            }
            pendingUi.addLast(new UiUpdate(work, droppable));
            if (!uiDrainScheduled) {
                uiDrainScheduled = true;
                schedule = true;
            }
        }
        if (schedule) javax.swing.SwingUtilities.invokeLater(this::drainUi);
        return true;
    }

    private void reportUiOverflow() {
        if (!uiOverflowReported) {
            uiOverflowReported = true;
            context.logger().warn("Turboism ACP dropped excess UI updates");
        }
    }

    private void drainUi() {
        while (true) {
            final UiUpdate update;
            synchronized (uiLock) {
                if (closed.get()) {
                    pendingUi.clear();
                    uiDrainScheduled = false;
                    return;
                }
                update = pendingUi.pollFirst();
                if (update == null) {
                    uiDrainScheduled = false;
                    uiOverflowReported = false;
                    return;
                }
            }
            try {
                update.work().run();
            } catch (RuntimeException failure) {
                context.logger().warn("Turboism ACP UI update failed safely");
            }
        }
    }

    private record PendingSettings(String agentId, String customCommand, String initialPrompt) {
        private PendingSettings {
            agentId = Objects.requireNonNullElse(agentId, "");
            customCommand = Objects.requireNonNullElse(customCommand, "");
            initialPrompt = Objects.requireNonNullElse(initialPrompt, "");
        }
    }

    private record UiUpdate(Runnable work, boolean droppable) {
        private UiUpdate {
            work = Objects.requireNonNull(work, "work");
        }
    }

    private boolean submit(final Runnable work) {
        if (closed.get()) return false;
        try {
            serial.execute(() -> {
                if (!closed.get()) work.run();
            });
            return true;
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            return false;
        }
    }

    /**
     * Subscribes once to MCP connection changes so an established session cannot silently keep a
     * stale endpoint after the MCP server restarts. The subscription lives until {@link #close()}.
     */
    private void ensureMcpSubscription(final McpConnectionService service) {
        if (closed.get() || mcpSubscription.get() != null || !service.isAvailable()) return;
        final dev.turboism.sdk.plugin.Registration registration;
        try {
            registration = service.subscribe(this::onMcpConnectionChanged);
        } catch (RuntimeException failure) {
            context.logger().warn("ACP could not subscribe to MCP connection changes");
            return;
        }
        mcpSubscription.compareAndSet(null, registration);
    }

    /**
     * Records the newest endpoint snapshot for future session binds and hops the publisher-thread
     * notification onto the serial executor for drift detection.
     */
    private void onMcpConnectionChanged(final java.util.Optional<McpHttpConnection> snapshot) {
        lastMcpSnapshot.set(snapshot);
        try {
            serial.execute(() -> applyMcpConnectionChange(snapshot.orElse(null)));
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // The controller is shutting down; the notification is obsolete.
        }
    }

    /**
     * Updates the endpoint used by future session binds and reconnects when the live session's
     * bound endpoint drifted, since ACP cannot rebind mcpServers on an existing session. Runs on
     * the serial executor; durable sessions are restored by the reconnect's normal
     * load/resume path.
     */
    private void applyMcpConnectionChange(final McpHttpConnection latest) {
        if (closed.get()) return;
        final AcpSession current = session;
        final boolean liveMcpSession =
                client.get() != null && current != null && current.capabilities().mcpHttp();
        final java.net.URI boundEndpoint =
                mcpConnection == null ? null : mcpConnection.endpoint();
        final java.net.URI latestEndpoint = latest == null ? null : latest.endpoint();
        if (!liveMcpSession || Objects.equals(boundEndpoint, latestEndpoint)) {
            mcpConnection = latest;
            return;
        }
        ui(() -> view.showSessionFailure("status.mcp-endpoint-changed"));
        // Coalesce restart bursts (publish→revoke→publish) into one reconnect to the final endpoint.
        if (mcpReconnectQueued.compareAndSet(false, true)) {
            try {
                serial.execute(this::reconnectForMcpDrift);
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                mcpReconnectQueued.set(false);
            }
        }
    }

    private void reconnectForMcpDrift() {
        mcpReconnectQueued.set(false);
        if (closed.get()) return;
        final AcpSession current = session;
        if (client.get() == null || current == null || !current.capabilities().mcpHttp()) return;
        final java.net.URI boundEndpoint =
                mcpConnection == null ? null : mcpConnection.endpoint();
        final java.net.URI latestEndpoint = lastMcpSnapshot.get()
                .map(McpHttpConnection::endpoint)
                .orElse(null);
        if (Objects.equals(boundEndpoint, latestEndpoint)) return;
        connectNow();
    }

    private void disconnectNow() {
        discardLoadTransaction();
        final AcpClient active = client.getAndSet(null);
        final AcpSession current;
        synchronized (stateLock) {
            current = session;
            session = null;
        }
        mcpConnection = null;
        prompting = false;
        synchronized (stateLock) {
            generationCounter++;
        }
        if (active != null) {
            if (current != null && current.capabilities().closeSession()) {
                try {
                    active.closeSession(current.sessionId(), Duration.ofSeconds(3));
                } catch (AcpException failure) {
                    context.logger().warn("ACP session did not close cleanly");
                }
            }
            active.close();
        }
    }

    @Override
    public void close() {
        if (closed.get()) return;
        final PendingSettings pending = pendingSettings.getAndSet(null);
        if (pending != null) {
            saveSettingsNow(pending.agentId(), pending.customCommand(), pending.initialPrompt());
        }
        if (!closed.compareAndSet(false, true)) return;
        final dev.turboism.sdk.plugin.Registration subscription = mcpSubscription.getAndSet(null);
        if (subscription != null) {
            subscription.close();
        }
        discardLoadTransaction();
        synchronized (uiLock) {
            pendingUi.clear();
        }
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            serial.shutdownNow();
            startAsyncCleanup();
            return;
        }
        try {
            serial.execute(this::disconnectNow);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            startAsyncCleanup();
        }
        serial.shutdown();
        try {
            if (!serial.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                serial.shutdownNow();
                disconnectNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            serial.shutdownNow();
            disconnectNow();
        }
    }

    private void startAsyncCleanup() {
        final Thread cleanup = new Thread(this::disconnectNow, "turboism-acp-controller-close");
        cleanup.setDaemon(true);
        cleanup.start();
    }

    private static String agentLabel(final String agentId) {
        return AgentCatalog.profile(agentId)
                .map(AgentProfile::displayName)
                .orElse(agentId);
    }

    private static String diagnosticKey(final AcpException failure) {
        if (failure.authRequired()) return "status.auth-required";
        final String message =
                Objects.requireNonNullElse(failure.getMessage(), "").toLowerCase(Locale.ROOT);
        if (message.contains("java_tool_options")) return "status.acp-java-launcher-noise";
        if (message.contains("non-json text") || message.contains("launcher text")) {
            return "status.acp-stdout-noise";
        }
        if (message.contains("mcp server is not available")) return "status.mcp-unavailable";
        if (message.contains("unsupported acp protocol")) return "status.acp-version-unsupported";
        if (message.contains("credential") || message.contains("subscription") || message.contains("login")) {
            return "status.auth-required";
        }
        return "status.acp-failed";
    }

    private static Throwable unwrap(final Throwable failure) {
        return failure instanceof CompletionException || failure instanceof java.util.concurrent.ExecutionException
                ? Objects.requireNonNullElse(failure.getCause(), failure)
                : failure;
    }

    @FunctionalInterface
    interface ClientStarter {
        AcpClient start(AgentLaunchSpec configuration, AcpListener listener)
                throws IOException, AcpException;
    }

    /** UI contract kept independent from Swing so controller behavior remains testable. */
    interface View {
        void showConnecting(String agentLabel);

        void showConnected(
                AcpClient.AcpAgentInfo agentInfo,
                List<AcpConfigOption> options,
                boolean durableSessionsAvailable,
                boolean mcpAttached);

        /** The agent refused session creation until one advertised authentication method completes. */
        void showAuthRequired(List<AcpAuthMethod> methods);

        void showConfigOptions(List<AcpConfigOption> options);

        default void showConfigUpdating(final String optionId) {}

        default void showConfigFailure(final String optionId, final List<AcpConfigOption> confirmedOptions) {
            showConfigOptions(confirmedOptions);
            showSessionFailure("status.config-failed");
        }

        void showSessions(List<AcpSessionSummary> sessions, String activeSessionId, boolean durableSessionsAvailable);

        /** Detection results keyed by agent id; the value is the executable path or empty. */
        default void showDetectedAgents(final Map<String, String> detectedPaths) {}

        void clearTranscript();

        void showPrompting();

        void showPromptComplete(String stopReason);

        void showFailure(String localizationKey);

        void showSessionFailure(String localizationKey);

        void showSettingsSaved();

        default void showDisconnected() {}

        void appendUser(String text);

        void appendAgent(String text);

        default void appendThinking(final String text) {}

        void appendTool(String toolCallId, String title, String kind, String status);

        void updateTool(String toolCallId, String status, String content);

        PermissionDecision requestPermission(PermissionRequest request);
    }
}
