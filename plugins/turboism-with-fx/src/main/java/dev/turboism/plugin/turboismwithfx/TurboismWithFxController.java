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
    static final int MAX_PROMPT_CHARS = 1024 * 1024;
    static final String SYSTEM_BOUNDARY =
        "Use only the Turboism MCP tools for Cubism automation. Do not use native filesystem, "
            + "terminal, search, or fetch tools.";

    private final PluginContext context;
    private final FxPluginSettings settings;
    private final FxRuntimeResolver runtimeResolver;
    private final FxManagedRuntimeService managedRuntime;
    private final View view;
    private final ExecutorService serial;
    private final Object uiLock = new Object();
    private final ArrayDeque<UiUpdate> pendingUi = new ArrayDeque<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<FxAcpClient> client = new AtomicReference<>();
    private boolean uiDrainScheduled;
    private boolean uiOverflowReported;
    private volatile FxAcpSession session;
    private volatile McpHttpConnection mcpConnection;
    private volatile boolean prompting;

    TurboismWithFxController(
        final PluginContext context,
        final FxPluginSettings settings,
        final View view
    ) {
        this.context = Objects.requireNonNull(context, "context");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.runtimeResolver = new FxRuntimeResolver(context.paths());
        this.managedRuntime = new FxManagedRuntimeService(
            context.paths(), this::managedRuntimeDiagnostic
        );
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
        submit(() -> {
            if (!saveSettingsNow(executable, compatibilityMode, initialPrompt)) return;
            ui(() -> view.showConnecting(compatibilityMode));
            connectNow(executable, compatibilityMode);
        });
    }

    void saveSettings(
        final String executable,
        final boolean compatibilityMode,
        final String initialPrompt
    ) {
        submit(() -> {
            if (saveSettingsNow(executable, compatibilityMode, initialPrompt)) {
                ui(view::showSettingsSaved);
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
                case PLATFORM_UNSUPPORTED -> "status.managed-platform-unsupported";
                case FAILED -> "status.managed-repair-failed";
            };
            ui(() -> view.showManagedRuntimeResult(key));
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
        final String initialPrompt
    ) {
        try {
            final String executableOverride = validateExecutableOverride(executable);
            final String instructions = validateInitialPrompt(initialPrompt);
            settings.writeUserSettings(
                executableOverride,
                compatibilityMode,
                instructions
            );
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
        if (!compatibilityMode) {
            ui(() -> view.showFailure("status.mcp-only-unavailable"));
            return;
        }
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
            final FxAcpClient connected = FxAcpClient.start(new FxLaunchConfiguration(
                available.executable(),
                context.paths().stateDir(),
                FxSecurityMode.FX_NATIVE_TOOLS,
                available.managedRuntime()
            ), this);
            client.set(connected);
            mcpConnection = connection;
            final FxAcpClient.FxAcpCapabilities capabilities = connected.capabilities();
            if (!capabilities.loadSession() && settings.sessionId() != null) {
                settings.clearSessionId();
            }
            final String savedSessionId = capabilities.loadSession()
                ? settings.sessionId()
                : null;
            try {
                session = savedSessionId == null
                    ? connected.newSession(context.paths().stateDir(), connection, REQUEST_TIMEOUT)
                    : connected.loadSession(
                        savedSessionId,
                        context.paths().stateDir(),
                        connection,
                        REQUEST_TIMEOUT
                    );
            } catch (FxAcpException loadFailure) {
                if (savedSessionId == null) throw loadFailure;
                context.logger().warn("Stored fx session could not be loaded; creating a new session");
                session = connected.newSession(
                    context.paths().stateDir(), connection, REQUEST_TIMEOUT
                );
            }
            activateSession(session);
            final FxAcpSession ready = session;
            ui(() -> view.showConnected(
                ready.configOptions(),
                ready.durableSessionsAvailable()
            ));
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
            activateSession(active.newSession(
                context.paths().stateDir(), connection, REQUEST_TIMEOUT
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
        try {
            final FxAcpSession loaded = active.loadSession(
                sessionId,
                context.paths().stateDir(),
                connection,
                REQUEST_TIMEOUT
            );
            activateSession(loaded);
            ui(() -> {
                view.clearTranscript();
                view.showConfigOptions(loaded.configOptions());
            });
            refreshSessionsNow();
        } catch (FxAcpException failure) {
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

    private void activateSession(final FxAcpSession activeSession) {
        session = Objects.requireNonNull(activeSession, "activeSession");
        if (activeSession.capabilities().loadSession()) {
            settings.writeSessionId(activeSession.sessionId());
        }
    }

    private void promptNow(final String text) {
        final FxAcpClient active = client.get();
        final FxAcpSession current = session;
        if (active == null || current == null || prompting) return;
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
        sourceEvent(source, sessionId, () -> view.appendAgent(text));
    }

    @Override
    public void agentThought(
        final FxAcpClient source,
        final String sessionId,
        final String text
    ) {
        sourceEvent(source, sessionId, () -> view.appendThinking(text));
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
            () -> view.appendTool(toolCallId, title, kind, status)
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
            () -> view.updateTool(toolCallId, status, content)
        );
    }

    @Override
    public void stderr(final FxAcpClient source, final String text) {
        if (client.get() == source) context.logger().warn("fx: " + text);
    }

    @Override
    public void terminated(final FxAcpClient source, final String message) {
        submit(() -> {
            if (!client.compareAndSet(source, null)) return;
            session = null;
            mcpConnection = null;
            prompting = false;
            ui(() -> view.showFailure("status.process-terminated"));
        });
    }

    @Override
    public PermissionDecision permission(
        final FxAcpClient source,
        final String sessionId,
        final PermissionRequest request
    ) {
        return activeSession(source, sessionId)
            ? view.requestPermission(request)
            : PermissionDecision.CANCELLED;
    }

    private void sourceEvent(
        final FxAcpClient source,
        final String sessionId,
        final Runnable work
    ) {
        if (activeSession(source, sessionId)) uiStream(() -> {
            if (activeSession(source, sessionId)) work.run();
        });
    }

    private boolean activeSession(final FxAcpClient source, final String sessionId) {
        final FxAcpSession current = session;
        return client.get() == source
            && current != null
            && current.sessionId().equals(sessionId);
    }

    private void ui(final Runnable work) {
        enqueueUi(work, false);
    }

    private void uiStream(final Runnable work) {
        enqueueUi(work, true);
    }

    private void enqueueUi(final Runnable work, final boolean droppable) {
        if (closed.get()) return;
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            work.run();
            return;
        }
        boolean schedule = false;
        synchronized (uiLock) {
            if (closed.get()) return;
            if (pendingUi.size() == MAX_PENDING_UI_UPDATES) {
                final UiUpdate dropped = pendingUi.stream()
                    .filter(UiUpdate::droppable)
                    .findFirst()
                    .orElse(null);
                if (dropped != null) {
                    pendingUi.remove(dropped);
                } else if (droppable) {
                    reportUiOverflow();
                    return;
                }
                if (dropped != null) reportUiOverflow();
            }
            pendingUi.addLast(new UiUpdate(work, droppable));
            if (!uiDrainScheduled) {
                uiDrainScheduled = true;
                schedule = true;
            }
        }
        if (schedule) javax.swing.SwingUtilities.invokeLater(this::drainUi);
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
        final FxAcpClient active = client.getAndSet(null);
        final FxAcpSession current = session;
        session = null;
        mcpConnection = null;
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
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        synchronized (uiLock) {
            pendingUi.clear();
        }
        try {
            serial.execute(this::disconnectNow);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            disconnectNow();
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
        void showSettingsSaved();
        default void showManagedRuntimeInstalling() {
        }
        default void showManagedRuntimeResult(final String localizationKey) {
            showFailure(localizationKey);
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
