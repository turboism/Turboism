package dev.turboism.plugin.turboismwithfx;

import dev.turboism.sdk.mcp.McpHttpConnection;
import dev.turboism.sdk.plugin.PluginContext;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Serial lifecycle controller joining the paired Swing view, MCP registry, and one fx process.
 *
 * <p>All process and session transitions run on one daemon executor. Provider/model catalogs and
 * any durable-session rows come only from fx ACP. When fx advertises only ephemeral sessions, the
 * controller creates a fresh session and exposes only its active opaque id without calling unsupported
 * lifecycle methods. Turboism never stores provider credentials or the MCP bearer.</p>
 */
final class TurboismWithFxController implements AutoCloseable, FxAcpListener {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final int MAX_PENDING_UI_UPDATES = 256;
    static final int MAX_PENDING_LOAD_EVENTS = 64;
    static final long MAX_PENDING_LOAD_TEXT_BYTES = 1024L * 1024L;
    static final int MAX_PROMPT_CHARS = 1024 * 1024;
    static final String SYSTEM_BOUNDARY =
        "Use only the Turboism MCP tools for Cubism automation. Do not use native filesystem, "
            + "terminal, search, or fetch tools.";

    private final PluginContext context;
    private final FxPluginSettings settings;
    private final FxRuntimeResolver runtimeResolver;
    private final FxManagedRuntimeService managedRuntime;
    private final ClientStarter clientStarter;
    private final View view;
    private final ExecutorService serial;
    private final Object stateLock = new Object();
    private final Object uiLock = new Object();
    private final ArrayDeque<UiUpdate> pendingUi = new ArrayDeque<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<FxAcpClient> client = new AtomicReference<>();
    private final AtomicReference<PendingSettings> pendingSettings = new AtomicReference<>();
    private boolean uiDrainScheduled;
    private boolean uiOverflowReported;
    private LoadTransaction loadTransaction;
    private long generationCounter;
    private long sessionGeneration;
    private volatile FxAcpSession session;
    private volatile McpHttpConnection mcpConnection;
    private volatile FxOpenAiAdapter customEndpointAdapter;
    private volatile FxDeferredGatewayAdapter deferredGatewayAdapter;
    private volatile boolean prompting;

    TurboismWithFxController(
        final PluginContext context,
        final FxPluginSettings settings,
        final View view
    ) {
        this(context, settings, view, FxAcpClient::start);
    }

    TurboismWithFxController(
        final PluginContext context,
        final FxPluginSettings settings,
        final View view,
        final ClientStarter clientStarter
    ) {
        this.context = Objects.requireNonNull(context, "context");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.runtimeResolver = new FxRuntimeResolver(context.paths());
        this.managedRuntime = new FxManagedRuntimeService(
            context.paths(), this::managedRuntimeDiagnostic
        );
        this.clientStarter = Objects.requireNonNull(clientStarter, "clientStarter");
        this.view = Objects.requireNonNull(view, "view");
        this.serial = Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "turboism-with-fx-controller");
            thread.setDaemon(true);
            return thread;
        });
    }

    void connect(
        final String executable,
        final boolean compatibilityMode,
        final String initialPrompt
    ) {
        connect(executable, compatibilityMode, initialPrompt, settings.customEndpoint());
    }

    void connect(
        final String executable,
        final boolean compatibilityMode,
        final String initialPrompt,
        final FxCustomEndpointSettings customEndpoint
    ) {
        submit(() -> {
            if (!saveSettingsNow(
                executable,
                compatibilityMode,
                initialPrompt,
                customEndpoint
            )) return;
            ui(() -> view.showConnecting(compatibilityMode));
            connectNow(executable, compatibilityMode);
        });
    }

    void connect(
        final String executable,
        final boolean compatibilityMode,
        final String initialPrompt,
        final FxProviderConfiguration providerConfiguration
    ) {
        final PendingSettings pending = new PendingSettings(
            executable, compatibilityMode, initialPrompt, providerConfiguration
        );
        pendingSettings.set(pending);
        submit(() -> {
            try {
                if (!saveSettingsNow(
                    executable,
                    compatibilityMode,
                    initialPrompt,
                    providerConfiguration
                )) return;
                ui(() -> view.showConnecting(compatibilityMode));
                connectNow(executable, compatibilityMode);
            } finally {
                pendingSettings.compareAndSet(pending, null);
            }
        });
    }

    void saveSettings(
        final String executable,
        final boolean compatibilityMode,
        final String initialPrompt
    ) {
        saveSettings(executable, compatibilityMode, initialPrompt, settings.customEndpoint());
    }

    void saveSettings(
        final String executable,
        final boolean compatibilityMode,
        final String initialPrompt,
        final FxCustomEndpointSettings customEndpoint
    ) {
        submit(() -> {
            if (saveSettingsNow(
                executable,
                compatibilityMode,
                initialPrompt,
                customEndpoint
            )) {
                ui(view::showSettingsSaved);
            }
        });
    }

    void saveSettings(
        final String executable,
        final boolean compatibilityMode,
        final String initialPrompt,
        final FxProviderConfiguration providerConfiguration
    ) {
        final PendingSettings pending = new PendingSettings(
            executable, compatibilityMode, initialPrompt, providerConfiguration
        );
        pendingSettings.set(pending);
        submit(() -> {
            try {
                if (saveSettingsNow(
                    executable,
                    compatibilityMode,
                    initialPrompt,
                    providerConfiguration
                )) {
                    ui(view::showSettingsSaved);
                }
            } finally {
                pendingSettings.compareAndSet(pending, null);
            }
        });
    }

    void repairManagedRuntime(final String executable) {
        if (executable != null && !executable.isBlank()) {
            ui(() -> view.showManagedRuntimeResult("status.managed-repair-custom-override"));
            return;
        }
        submit(() -> {
            if (client.get() != null || prompting) {
                ui(() -> view.showManagedRuntimeResult("status.managed-repair-disconnect"));
                return;
            }
            ui(view::showManagedRuntimeInstalling);
            final FxManagedRuntimeService.Result result = managedRuntime.installOrRepair();
            final String key = switch (result) {
                case INSTALLED -> "status.managed-repair-complete";
                case PRODUCT_PAYLOAD_ONLY -> "status.managed-repair-reinstall";
                case PLATFORM_UNSUPPORTED -> "status.managed-platform-unsupported";
                case FAILED -> "status.managed-repair-failed";
            };
            ui(() -> view.showManagedRuntimeResult(key));
        });
    }

    void openInteractiveFx(final String executable, final FxInteractiveAction action) {
        submit(() -> {
            final FxRuntimeResolver.Resolution resolution = runtimeResolver.resolve(executable);
            if (resolution instanceof FxRuntimeResolver.Resolution.Unavailable unavailable) {
                ui(() -> view.showShellResult(runtimeFailureKey(unavailable.problem())));
                return;
            }
            try {
                FxShellLauncher.open(
                    (FxRuntimeResolver.Resolution.Available) resolution,
                    context.paths().stateDir(),
                    Objects.requireNonNull(action, "action")
                );
                ui(() -> view.showShellResult("status.fx-shell-opened"));
            } catch (IOException | RuntimeException failure) {
                context.logger().error("Turboism with fx could not open an interactive shell", failure);
                ui(() -> view.showShellResult("status.fx-shell-failed"));
            }
        });
    }

    void discoverProviderModels(
        final FxProviderProfile profile,
        final String sessionApiKey
    ) {
        final FxProviderProfile selected = Objects.requireNonNull(profile, "profile");
        if (selected.kind() != FxProviderProfile.Kind.OPENAI_COMPATIBLE) {
            ui(view::showProviderModelDiscoveryFailure);
            return;
        }
        submit(() -> {
            try {
                final FxCustomEndpointSettings endpoint = selected.customEndpoint(sessionApiKey);
                final List<String> models = FxOpenAiAdapter.discoverModels(endpoint);
                ui(() -> view.showDiscoveredProviderModels(selected.id(), models));
            } catch (IOException | IllegalArgumentException failure) {
                context.logger().warn("Turboism with fx could not discover custom provider models");
                ui(view::showProviderModelDiscoveryFailure);
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

    private boolean saveSettingsNow(
        final String executable,
        final boolean compatibilityMode,
        final String initialPrompt,
        final FxCustomEndpointSettings customEndpoint
    ) {
        return saveSettingsNow(
            executable,
            compatibilityMode,
            initialPrompt,
            settings.providerConfiguration(),
            customEndpoint
        );
    }

    private boolean saveSettingsNow(
        final String executable,
        final boolean compatibilityMode,
        final String initialPrompt,
        final FxProviderConfiguration providerConfiguration
    ) {
        return saveSettingsNow(
            executable,
            compatibilityMode,
            initialPrompt,
            providerConfiguration,
            null
        );
    }

    private boolean saveSettingsNow(
        final String executable,
        final boolean compatibilityMode,
        final String initialPrompt,
        final FxProviderConfiguration providerConfiguration,
        final FxCustomEndpointSettings legacyCustomEndpoint
    ) {
        try {
            final String executableOverride = validateExecutableOverride(executable);
            final String instructions = validateInitialPrompt(initialPrompt);
            final FxProviderProfile previousProfile =
                settings.providerConfiguration().activeProfile();
            if (legacyCustomEndpoint == null) {
                settings.writeUserSettings(
                    executableOverride,
                    compatibilityMode,
                    instructions,
                    Objects.requireNonNull(providerConfiguration, "providerConfiguration")
                );
            } else {
                settings.writeUserSettings(
                    executableOverride,
                    compatibilityMode,
                    instructions,
                    legacyCustomEndpoint
                );
            }
            if (!previousProfile.equals(settings.providerConfiguration().activeProfile())) {
                settings.clearSessionId();
            }
            return true;
        } catch (IllegalArgumentException failure) {
            context.logger().warn("Turboism with fx settings were invalid");
            ui(() -> view.showFailure("status.settings-invalid"));
            return false;
        } catch (dev.turboism.sdk.config.PluginConfigException failure) {
            context.logger().warn("Turboism with fx settings could not be persisted");
            ui(() -> view.showFailure("status.settings-save-failed"));
            return false;
        }
    }

    private void connectNow(final String executable, final boolean compatibilityMode) {
        disconnectNow();
        try {
            final McpHttpConnection connection = context.mcpConnections().current()
                .orElseThrow(() -> new FxAcpException("Turboism MCP Server is not available"));
            final FxRuntimeResolver.Resolution resolution = runtimeResolver.resolve(executable);
            if (resolution instanceof FxRuntimeResolver.Resolution.Unavailable unavailable) {
                ui(() -> view.showFailure(runtimeFailureKey(unavailable.problem())));
                return;
            }
            final FxRuntimeResolver.Resolution.Available available =
                (FxRuntimeResolver.Resolution.Available) resolution;
            final FxProviderProfile profile = settings.providerConfiguration().activeProfile();
            final FxCustomEndpointSettings customEndpoint = settings.customEndpoint();
            final FxOpenAiAdapter adapter;
            final FxDeferredGatewayAdapter deferred;
            final java.util.Map<String, String> environment;
            final String startupModel;
            if (customEndpoint.enabled()) {
                adapter = FxOpenAiAdapter.start(
                    customEndpoint,
                    profile.models(List.of())
                );
                deferred = null;
                customEndpointAdapter = adapter;
                environment = FxIsolatedHome.gatewayEnvironment(
                    context.paths().stateDir(), profile.id(), adapter.fxEnvironment()
                );
                startupModel = customEndpoint.model();
            } else if (profile.kind() == FxProviderProfile.Kind.NONE) {
                adapter = null;
                deferred = FxDeferredGatewayAdapter.start();
                deferredGatewayAdapter = deferred;
                environment = FxIsolatedHome.gatewayEnvironment(
                    context.paths().stateDir(), profile.id(), deferred.fxEnvironment()
                );
                startupModel = "";
            } else {
                adapter = null;
                deferred = null;
                environment = java.util.Map.of();
                startupModel = "";
            }
            final FxAcpClient connected;
            try {
                connected = clientStarter.start(
                    new FxLaunchConfiguration(
                        available.executable(),
                        context.paths().stateDir(),
                        FxSecurityMode.FX_NATIVE_TOOLS,
                        available.managedRuntime(),
                        environment,
                        startupModel
                    ),
                    this
                );
            } catch (IOException | FxAcpException | RuntimeException failure) {
                if (adapter != null) {
                    customEndpointAdapter = null;
                    adapter.close();
                }
                if (deferred != null) {
                    deferredGatewayAdapter = null;
                    deferred.close();
                }
                throw failure;
            }
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
            final FxAcpClient.FxAcpCapabilities capabilities = connected.capabilities();
            if (!capabilities.loadSession() && settings.sessionId() != null) {
                settings.clearSessionId();
            }
            final String savedSessionId = capabilities.loadSession()
                ? settings.sessionId()
                : null;
            if (savedSessionId == null) {
                final FxAcpSession created = applySavedProvider(
                    connected,
                    connected.newSession(
                        context.paths().stateDir(), connection, REQUEST_TIMEOUT
                    )
                );
                activateSession(created);
                ui(() -> view.showConnected(
                    created.configOptions(),
                    created.durableSessionsAvailable()
                ));
            } else {
                final LoadTransaction load = beginLoadTransaction(connected, savedSessionId);
                try {
                    final FxAcpSession restored = applySavedProvider(
                        connected,
                        connected.loadSession(
                            savedSessionId,
                            context.paths().stateDir(),
                            connection,
                            REQUEST_TIMEOUT
                        )
                    );
                    if (!completeLoadTransaction(load, restored, () -> view.showConnected(
                        restored.configOptions(),
                        restored.durableSessionsAvailable()
                    ))) {
                        return;
                    }
                } catch (FxAcpException | RuntimeException loadFailure) {
                    if (!discardCurrentLoadTransaction(load)) return;
                    context.logger().warn(
                        "Stored fx session could not be loaded; creating a new session"
                    );
                    if (!loadGenerationCurrent(load)) return;
                    final FxAcpSession created = applySavedProvider(
                        connected,
                        connected.newSession(
                            context.paths().stateDir(), connection, REQUEST_TIMEOUT
                        )
                    );
                    if (!loadGenerationCurrent(load)) return;
                    activateSession(created);
                    ui(() -> view.showConnected(
                        created.configOptions(),
                        created.durableSessionsAvailable()
                    ));
                }
            }
            refreshSessionsNow();
        } catch (IOException failure) {
            connectionFailed(failure, "status.executable-start-failed");
        } catch (FxAcpException failure) {
            connectionFailed(failure, diagnosticKey(failure));
        } catch (RuntimeException failure) {
            connectionFailed(failure, "status.connection-failed");
        }
    }

    private void connectionFailed(final Throwable failure, final String localizationKey) {
        disconnectNow();
        context.logger().error("Turboism with fx could not connect", failure);
        ui(() -> view.showFailure(localizationKey));
    }

    private void newSessionNow() {
        final FxAcpClient active = client.get();
        final McpHttpConnection connection = mcpConnection;
        if (active == null || connection == null || prompting) return;
        try {
            activateSession(applySavedProvider(
                active,
                active.newSession(
                    context.paths().stateDir(), connection, REQUEST_TIMEOUT
                )
            ));
            final FxAcpSession created = session;
            ui(() -> {
                view.clearTranscript();
                view.showConfigOptions(created.configOptions());
                view.showSessions(
                    List.of(new FxAcpSessionSummary(created.sessionId(), "active")),
                    created.sessionId(),
                    created.durableSessionsAvailable()
                );
            });
            refreshSessionsNow();
        } catch (FxAcpException failure) {
            context.logger().error("fx session creation failed", failure);
            ui(() -> view.showSessionFailure("status.session-new-failed"));
        }
    }

    private void selectSessionNow(final String sessionId) {
        final FxAcpClient active = client.get();
        final McpHttpConnection connection = mcpConnection;
        final FxAcpSession current = session;
        if (active == null || connection == null || current == null || prompting
            || !current.capabilities().loadSession()
            || current.sessionId().equals(sessionId)) {
            return;
        }
        final LoadTransaction load = beginLoadTransaction(active, sessionId);
        try {
            final FxAcpSession loaded = active.loadSession(
                sessionId,
                context.paths().stateDir(),
                connection,
                REQUEST_TIMEOUT
            );
            if (!completeLoadTransaction(load, loaded, () -> {
                view.clearTranscript();
                view.showConfigOptions(loaded.configOptions());
            })) {
                return;
            }
            refreshSessionsNow();
        } catch (FxAcpException | RuntimeException failure) {
            if (!discardCurrentLoadTransaction(load)) return;
            context.logger().error("fx session selection failed", failure);
            ui(() -> view.showSessionFailure("status.session-load-failed"));
        }
    }

    private void refreshSessionsNow() {
        final FxAcpClient active = client.get();
        final FxAcpSession current = session;
        if (active == null || current == null || prompting) return;
        if (!current.durableSessionsAvailable()) {
            ui(() -> view.showSessions(
                List.of(new FxAcpSessionSummary(current.sessionId(), "active")),
                current.sessionId(),
                false
            ));
            return;
        }
        try {
            final List<FxAcpSessionSummary> sessions = active.listSessions(REQUEST_TIMEOUT);
            ui(() -> view.showSessions(sessions, current.sessionId(), true));
        } catch (FxAcpException failure) {
            context.logger().error("fx session list failed", failure);
            ui(() -> view.showSessionFailure("status.session-list-failed"));
        }
    }

    private FxAcpSession applySavedProvider(
        final FxAcpClient active,
        final FxAcpSession current
    ) throws FxAcpException {
        final FxProviderProfile profile = settings.providerConfiguration().activeProfile();
        if (profile.kind() != FxProviderProfile.Kind.FX_NATIVE) return current;
        final FxAcpConfigOption provider = current.option("provider");
        if (provider == null || profile.nativeProvider().equals(provider.currentValue())
            || provider.choices().stream().noneMatch(choice ->
                profile.nativeProvider().equals(choice.value())
            )) {
            return current;
        }
        final FxAcpClient.PendingConfigUpdate update = active.setConfigOption(
            current.sessionId(), "provider", profile.nativeProvider()
        );
        try {
            final List<FxAcpConfigOption> options = update.result().get(
                REQUEST_TIMEOUT.toMillis(),
                java.util.concurrent.TimeUnit.MILLISECONDS
            );
            return new FxAcpSession(
                current.sessionId(), options, current.capabilities()
            );
        } catch (InterruptedException failure) {
            active.abandon(update.request());
            Thread.currentThread().interrupt();
            throw new FxAcpException("fx provider selection was interrupted", failure);
        } catch (java.util.concurrent.TimeoutException failure) {
            active.abandon(update.request());
            throw new FxAcpException("fx provider selection timed out", failure);
        } catch (java.util.concurrent.ExecutionException failure) {
            throw new FxAcpException("fx provider selection failed", unwrap(failure));
        }
    }

    private void activateSession(final FxAcpSession activeSession) {
        final FxAcpSession activated = Objects.requireNonNull(activeSession, "activeSession");
        synchronized (stateLock) {
            loadTransaction = null;
            session = activated;
            sessionGeneration = ++generationCounter;
        }
        if (activated.capabilities().loadSession()) {
            settings.writeSessionId(activated.sessionId());
        }
    }

    private LoadTransaction beginLoadTransaction(
        final FxAcpClient source,
        final String sessionId
    ) {
        synchronized (stateLock) {
            final LoadTransaction load = new LoadTransaction(
                source,
                sessionId,
                ++generationCounter
            );
            loadTransaction = load;
            return load;
        }
    }

    private boolean completeLoadTransaction(
        final LoadTransaction load,
        final FxAcpSession loaded,
        final Runnable reset
    ) {
        final List<ReplayEvent> replay;
        synchronized (stateLock) {
            if (!currentLoadTransactionLocked(load)
                || !load.sessionId().equals(loaded.sessionId())) {
                if (loadTransaction == load) loadTransaction = null;
                return false;
            }
            replay = load.events();
            final Runnable replayWork = () -> {
                if (!activeGeneration(load.source(), load.sessionId(), load.generation())) return;
                reset.run();
                for (ReplayEvent event : replay) {
                    if (!activeGeneration(load.source(), load.sessionId(), load.generation())) return;
                    event.deliver(view);
                }
            };
            if (!enqueueUi(replayWork, false)) {
                loadTransaction = null;
                return false;
            }
            session = loaded;
            sessionGeneration = load.generation();
            loadTransaction = null;
        }
        if (loaded.capabilities().loadSession()
            && activeGeneration(load.source(), load.sessionId(), load.generation())) {
            settings.writeSessionId(loaded.sessionId());
        }
        return true;
    }

    private boolean currentLoadTransactionLocked(final LoadTransaction load) {
        return !closed.get()
            && loadTransaction == load
            && client.get() == load.source()
            && load.generation() == generationCounter;
    }

    private boolean activeGeneration(
        final FxAcpClient source,
        final String sessionId,
        final long generation
    ) {
        synchronized (stateLock) {
            final FxAcpSession current = session;
            return !closed.get()
                && client.get() == source
                && current != null
                && current.sessionId().equals(sessionId)
                && sessionGeneration == generation;
        }
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
            return !closed.get()
                && client.get() == load.source()
                && generationCounter == load.generation();
        }
    }

    private void discardLoadTransaction(final FxAcpClient source) {
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
        final FxAcpClient active = client.get();
        final FxAcpSession current = session;
        if (active == null || current == null || prompting) return;
        if (!providerAndModelAvailable(current)) {
            ui(view::showProviderModelWarning);
            return;
        }
        final String initial = settings.initialPrompt().strip();
        final String prefix = initial.isEmpty()
            ? SYSTEM_BOUNDARY + "\n\nUser request:\n"
            : SYSTEM_BOUNDARY + "\n\nUser-configured initial instructions:\n"
                + initial + "\n\nUser request:\n";
        if (text.length() > MAX_PROMPT_CHARS - prefix.length()) {
            context.logger().warn("fx prompt exceeded the ACP text limit");
            ui(() -> view.showSessionFailure("status.prompt-failed"));
            return;
        }
        final CompletableFuture<String> prompt;
        try {
            prompt = active.prompt(current.sessionId(), prefix + text);
        } catch (RuntimeException failure) {
            context.logger().error("fx prompt failed before dispatch", failure);
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
                context.logger().error("fx prompt failed", unwrap(failure));
                ui(() -> view.showSessionFailure("status.prompt-failed"));
            } else {
                ui(() -> view.showPromptComplete(stopReason));
                refreshSessionsNow();
            }
        }));
    }

    private boolean providerAndModelAvailable(final FxAcpSession current) {
        final FxProviderProfile profile = settings.providerConfiguration().activeProfile();
        if (profile.kind() == FxProviderProfile.Kind.NONE) return false;
        final FxAcpConfigOption provider = current.option("provider");
        final FxAcpConfigOption model = current.option("model");
        if (provider == null || model == null || !hasCurrentChoice(model)) return false;
        if (profile.kind() == FxProviderProfile.Kind.OPENAI_COMPATIBLE) {
            final FxOpenAiAdapter adapter = customEndpointAdapter;
            return "gateway".equals(provider.currentValue())
                && adapter != null
                && adapter.hasModel(model.currentValue());
        }
        return profile.nativeProvider().equals(provider.currentValue())
            && hasCurrentChoice(provider);
    }

    private static boolean hasCurrentChoice(final FxAcpConfigOption option) {
        return option.choices().stream().anyMatch(choice ->
            choice.value().equals(option.currentValue())
        );
    }

    private void cancelNow() {
        final FxAcpClient active = client.get();
        final FxAcpSession current = session;
        if (active != null && current != null && prompting) {
            active.cancel(current.sessionId());
        }
    }

    private void setConfigNow(final String id, final String value) {
        final FxAcpClient active = client.get();
        final FxAcpSession current = session;
        if (active == null || current == null || prompting) return;
        ui(() -> view.showConfigUpdating(id));
        final FxAcpClient.PendingConfigUpdate update;
        try {
            update = active.setConfigOption(current.sessionId(), id, value);
        } catch (RuntimeException failure) {
            context.logger().error("fx configuration update failed before dispatch", failure);
            ui(() -> view.showConfigFailure(id, current.configOptions()));
            return;
        }
        try {
            final List<FxAcpConfigOption> options = update.result().get(
                REQUEST_TIMEOUT.toMillis(),
                java.util.concurrent.TimeUnit.MILLISECONDS
            );
            if (client.get() != active || session != current) return;
            session = new FxAcpSession(
                current.sessionId(), options, current.capabilities()
            );
            ui(() -> view.showConfigOptions(options));
        } catch (InterruptedException interrupted) {
            active.abandon(update.request());
            Thread.currentThread().interrupt();
            ui(() -> view.showConfigFailure(id, current.configOptions()));
        } catch (java.util.concurrent.TimeoutException failure) {
            active.abandon(update.request());
            context.logger().error("fx configuration update timed out", failure);
            ui(() -> view.showConfigFailure(id, current.configOptions()));
        } catch (java.util.concurrent.ExecutionException failure) {
            context.logger().error("fx configuration update failed", unwrap(failure));
            ui(() -> view.showConfigFailure(id, current.configOptions()));
        }
    }

    @Override
    public void agentText(
        final FxAcpClient source,
        final String sessionId,
        final String text
    ) {
        sourceEvent(source, sessionId, new AgentTextEvent(text));
    }

    @Override
    public void agentThought(
        final FxAcpClient source,
        final String sessionId,
        final String text
    ) {
        sourceEvent(source, sessionId, new AgentThoughtEvent(text));
    }

    @Override
    public void toolCall(
        final FxAcpClient source,
        final String sessionId,
        final String toolCallId,
        final String title,
        final String kind,
        final String status
    ) {
        sourceEvent(
            source,
            sessionId,
            new ToolCallEvent(toolCallId, title, kind, status)
        );
    }

    @Override
    public void toolCallUpdate(
        final FxAcpClient source,
        final String sessionId,
        final String toolCallId,
        final String status,
        final String content
    ) {
        sourceEvent(
            source,
            sessionId,
            new ToolCallUpdateEvent(toolCallId, status, content)
        );
    }

    @Override
    public void stderr(final FxAcpClient source, final String text) {
        if (client.get() == source) context.logger().warn("fx: " + text);
    }

    @Override
    public void terminated(final FxAcpClient source, final String message) {
        discardLoadTransaction(source);
        submit(() -> {
            if (!client.compareAndSet(source, null)) return;
            discardLoadTransaction(source);
            synchronized (stateLock) {
                session = null;
            }
            mcpConnection = null;
            prompting = false;
            final FxOpenAiAdapter adapter = customEndpointAdapter;
            customEndpointAdapter = null;
            final FxDeferredGatewayAdapter deferred = deferredGatewayAdapter;
            deferredGatewayAdapter = null;
            if (adapter != null) adapter.close();
            if (deferred != null) deferred.close();
            ui(() -> view.showFailure("status.process-terminated"));
        });
    }

    @Override
    public PermissionDecision permission(
        final FxAcpClient source,
        final String sessionId,
        final PermissionRequest request
    ) {
        final long generation;
        synchronized (stateLock) {
            if (loadTransaction != null || !activeSession(source, sessionId)) {
                return PermissionDecision.CANCELLED;
            }
            generation = sessionGeneration;
        }
        final PermissionDecision decision = view.requestPermission(request);
        return activeGeneration(source, sessionId, generation)
            ? decision
            : PermissionDecision.CANCELLED;
    }

    private void sourceEvent(
        final FxAcpClient source,
        final String sessionId,
        final ReplayEvent event
    ) {
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
        final FxAcpClient source,
        final String sessionId,
        final long generation,
        final ReplayEvent event
    ) {
        return () -> {
            if (!activeGeneration(source, sessionId, generation)) return;
            event.deliver(view);
        };
    }

    private boolean activeSession(final FxAcpClient source, final String sessionId) {
        final FxAcpSession current = session;
        return client.get() == source
            && current != null
            && current.sessionId().equals(sessionId);
    }

    private sealed interface ReplayEvent permits AgentTextEvent, AgentThoughtEvent,
        ToolCallEvent, ToolCallUpdateEvent {
        long textBytes();
        void deliver(View target);
    }

    private record AgentTextEvent(String text) implements ReplayEvent {
        private AgentTextEvent {
            text = Objects.requireNonNull(text, "text");
        }
        @Override public long textBytes() { return utf8Bytes(text); }
        @Override public void deliver(final View target) { target.appendAgent(text); }
    }

    private record AgentThoughtEvent(String text) implements ReplayEvent {
        private AgentThoughtEvent {
            text = Objects.requireNonNull(text, "text");
        }
        @Override public long textBytes() { return utf8Bytes(text); }
        @Override public void deliver(final View target) { target.appendThinking(text); }
    }

    private record ToolCallEvent(
        String toolCallId,
        String title,
        String kind,
        String status
    ) implements ReplayEvent {
        private ToolCallEvent {
            toolCallId = Objects.requireNonNull(toolCallId, "toolCallId");
            title = Objects.requireNonNull(title, "title");
            kind = Objects.requireNonNull(kind, "kind");
            status = Objects.requireNonNull(status, "status");
        }
        @Override public long textBytes() {
            return utf8Bytes(toolCallId) + utf8Bytes(title) + utf8Bytes(kind) + utf8Bytes(status);
        }
        @Override public void deliver(final View target) {
            target.appendTool(toolCallId, title, kind, status);
        }
    }

    private record ToolCallUpdateEvent(
        String toolCallId,
        String status,
        String content
    ) implements ReplayEvent {
        private ToolCallUpdateEvent {
            toolCallId = Objects.requireNonNull(toolCallId, "toolCallId");
            status = Objects.requireNonNull(status, "status");
            content = Objects.requireNonNull(content, "content");
        }
        @Override public long textBytes() {
            return utf8Bytes(toolCallId) + utf8Bytes(status) + utf8Bytes(content);
        }
        @Override public void deliver(final View target) {
            target.updateTool(toolCallId, status, content);
        }
    }

    private final class LoadTransaction {
        private final FxAcpClient source;
        private final String sessionId;
        private final long generation;
        private final ArrayDeque<ReplayEvent> events = new ArrayDeque<>();
        private long textBytes;
        private boolean overflowReported;

        private LoadTransaction(
            final FxAcpClient source,
            final String sessionId,
            final long generation
        ) {
            this.source = Objects.requireNonNull(source, "source");
            this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
            this.generation = generation;
        }

        private FxAcpClient source() { return source; }
        private String sessionId() { return sessionId; }
        private long generation() { return generation; }
        private List<ReplayEvent> events() { return List.copyOf(events); }
        private void add(
            final FxAcpClient eventSource,
            final String eventSessionId,
            final ReplayEvent event
        ) {
            if (eventSource != source || !sessionId.equals(eventSessionId)
                || generation != generationCounter || overflowReported) {
                return;
            }
            final long eventBytes = event.textBytes();
            if (events.size() >= MAX_PENDING_LOAD_EVENTS
                || eventBytes > MAX_PENDING_LOAD_TEXT_BYTES - textBytes) {
                overflowReported = true;
                context.logger().warn("Turboism with fx dropped excess session-load replay events");
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
            context.logger().warn("Turboism with fx dropped excess UI updates");
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
                context.logger().warn("Turboism with fx UI update failed safely");
            }
        }
    }

    private record PendingSettings(
        String executable,
        boolean compatibilityMode,
        String initialPrompt,
        FxProviderConfiguration providerConfiguration
    ) {
        private PendingSettings {
            executable = Objects.requireNonNullElse(executable, "");
            initialPrompt = Objects.requireNonNullElse(initialPrompt, "");
            providerConfiguration = Objects.requireNonNull(
                providerConfiguration, "providerConfiguration"
            );
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

    private void disconnectNow() {
        discardLoadTransaction();
        final FxAcpClient active = client.getAndSet(null);
        final FxAcpSession current;
        synchronized (stateLock) {
            current = session;
            session = null;
        }
        mcpConnection = null;
        final FxOpenAiAdapter adapter = customEndpointAdapter;
        customEndpointAdapter = null;
        final FxDeferredGatewayAdapter deferred = deferredGatewayAdapter;
        deferredGatewayAdapter = null;
        prompting = false;
        if (active != null) {
            if (current != null && current.capabilities().closeSession()) {
                try {
                    active.closeSession(current.sessionId(), Duration.ofSeconds(3));
                } catch (FxAcpException failure) {
                    context.logger().warn("fx session did not close cleanly");
                }
            }
            active.close();
        }
        if (adapter != null) adapter.close();
        if (deferred != null) deferred.close();
    }

    @Override
    public void close() {
        if (closed.get()) return;
        final PendingSettings pending = pendingSettings.getAndSet(null);
        if (pending != null) {
            saveSettingsNow(
                pending.executable(),
                pending.compatibilityMode(),
                pending.initialPrompt(),
                pending.providerConfiguration()
            );
        }
        if (!closed.compareAndSet(false, true)) return;
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
        final Thread cleanup = new Thread(
            this::disconnectNow,
            "turboism-with-fx-controller-close"
        );
        cleanup.setDaemon(true);
        cleanup.start();
    }

    private static String validateExecutableOverride(final String value) {
        final String executable = Objects.requireNonNullElse(value, "").strip();
        if (executable.isEmpty()) return "";
        return new FxLaunchConfiguration(
            executable,
            java.nio.file.Path.of("."),
            FxSecurityMode.FX_NATIVE_TOOLS
        ).executable();
    }

    private static String validateInitialPrompt(final String value) {
        return FxPluginSettings.boundedInitialPrompt(
            Objects.requireNonNullElse(value, "")
        );
    }

    private static String runtimeFailureKey(final FxRuntimeResolver.Problem problem) {
        return switch (problem) {
            case PLATFORM_UNSUPPORTED -> "status.managed-platform-unsupported";
            case RUNTIME_MISSING -> "status.managed-runtime-missing";
            case RUNTIME_INVALID -> "status.managed-runtime-invalid";
        };
    }

    private void managedRuntimeDiagnostic(final FxManagedRuntimeService.Code code) {
        switch (code) {
            case INSTALLED -> context.logger().info("Managed fx runtime installed");
            case FAILED -> context.logger().warn("Managed fx runtime install failed safely");
            case INTERRUPTED -> context.logger().warn("Managed fx runtime install was interrupted");
            case ACTIVATED_RUNTIME_INVALID ->
                context.logger().warn("Managed fx runtime activation did not verify");
            case ROLLBACK_FAILED ->
                context.logger().error("Managed fx runtime rollback failed");
            case CLEANUP_FAILED ->
                context.logger().warn("Managed fx runtime previous payload cleanup failed");
        }
    }

    private static String diagnosticKey(final FxAcpException failure) {
        final String message = Objects.requireNonNullElse(failure.getMessage(), "")
            .toLowerCase(Locale.ROOT);
        if (message.contains("java_tool_options")) return "status.acp-java-launcher-noise";
        if (message.contains("non-json text") || message.contains("launcher text")) {
            return "status.acp-stdout-noise";
        }
        if (message.contains("mcp server is not available")) return "status.mcp-unavailable";
        if (message.contains("unsupported") || message.contains("install fx 0.0.5")) {
            return "status.fx-version-unsupported";
        }
        if (message.contains("not fx")) return "status.executable-not-fx";
        if (message.contains("credential") || message.contains("subscription")
            || message.contains("login")) {
            return "status.fx-auth-required";
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
        FxAcpClient start(
            FxLaunchConfiguration configuration,
            FxAcpListener listener
        ) throws IOException, FxAcpException;
    }

    /** UI contract kept independent from Swing so controller behavior remains testable. */
    interface View {
        void showConnecting(boolean compatibilityMode);
        void showConnected(
            List<FxAcpConfigOption> options,
            boolean durableSessionsAvailable
        );
        void showConfigOptions(List<FxAcpConfigOption> options);
        default void showConfigUpdating(final String optionId) {
        }
        default void showConfigFailure(
            final String optionId,
            final List<FxAcpConfigOption> confirmedOptions
        ) {
            showConfigOptions(confirmedOptions);
            showSessionFailure("status.config-failed");
        }
        void showSessions(
            List<FxAcpSessionSummary> sessions,
            String activeSessionId,
            boolean durableSessionsAvailable
        );
        void clearTranscript();
        void showPrompting();
        void showPromptComplete(String stopReason);
        void showFailure(String localizationKey);
        void showSessionFailure(String localizationKey);
        default void showProviderModelWarning() {
            showSessionFailure("status.provider-model-required");
        }
        void showSettingsSaved();
        default void showManagedRuntimeInstalling() {
        }
        default void showManagedRuntimeResult(final String localizationKey) {
            showFailure(localizationKey);
        }
        default void showShellResult(final String localizationKey) {
            showFailure(localizationKey);
        }
        default void showDiscoveredProviderModels(
            final String profileId,
            final List<String> models
        ) {
        }
        default void showProviderModelDiscoveryFailure() {
        }
        void appendUser(String text);
        void appendAgent(String text);
        default void appendThinking(final String text) {
        }
        void appendTool(
            String toolCallId,
            String title,
            String kind,
            String status
        );
        void updateTool(String toolCallId, String status, String content);
        PermissionDecision requestPermission(PermissionRequest request);
    }
}
