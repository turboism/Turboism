package dev.turboism.plugin.acp;

import dev.turboism.sdk.io.BoundedLineReader;
import dev.turboism.sdk.json.Json;
import dev.turboism.sdk.mcp.McpHttpConnection;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dependency-free ACP v1 JSON-RPC client over a supervised agent JSONL process.
 *
 * <p>One daemon reader owns stdout parsing, one daemon reader drains stderr, and all writes are
 * serialized under a private lock. Response correlation is bounded by the client's pending map;
 * EOF, malformed UTF-8, invalid JSON, oversized lines, and process closure fail every pending
 * request. The client never logs protocol lines and redacts credential-shaped material from
 * forwarded stderr.</p>
 */
final class AcpClient implements AutoCloseable {

    static final int MAX_ACP_LINE_CHARS = 8 * 1024 * 1024;
    static final long AUTH_REQUIRED_CODE = -32000L;
    private static final int MAX_STDERR_LINE_CHARS = 16 * 1024;
    private static final int MAX_PERMISSION_DETAILS_CHARS = 32 * 1024;
    private static final int MAX_UI_METADATA_CHARS = 8 * 1024;
    private static final int MAX_UI_CONTENT_CHARS = 256 * 1024;
    private static final int MAX_PENDING_REQUESTS = 64;
    private static final java.util.regex.Pattern GRAPHEME = java.util.regex.Pattern.compile("\\X");
    private static final java.util.regex.Pattern CREDENTIAL_ASSIGNMENT = java.util.regex.Pattern.compile(
            "(?i)(\\b(?:authorization|token|secret|credential|password)\\s*[:=]\\s*)" + "(?:bearer\\s+)?[^\\s,;]+");
    private static final java.util.regex.Pattern BEARER_VALUE =
            java.util.regex.Pattern.compile("(?i)\\bbearer\\s+[^\\s,;]+");
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    private final AcpTransport transport;
    private final AcpListener listener;
    private final BufferedWriter writer;
    private final Object writeLock = new Object();
    private final ConcurrentHashMap<Long, CompletableFuture<Object>> pending = new ConcurrentHashMap<>();
    private final AtomicLong requestIds = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean terminationReported = new AtomicBoolean();
    private final AtomicReference<String> protocolFailureHint = new AtomicReference<>();
    private final AtomicReference<String> protocolFailurePreview = new AtomicReference<>();
    private final AtomicReference<String> stderrFailureHint = new AtomicReference<>();
    private final CountDownLatch stderrFinished = new CountDownLatch(1);
    private volatile AcpCapabilities capabilities = AcpCapabilities.NONE;
    private volatile AcpAgentInfo agentInfo = AcpAgentInfo.UNKNOWN;
    private volatile List<AcpAuthMethod> authMethods = List.of();
    private final java.util.concurrent.ThreadPoolExecutor permissionExecutor =
            new java.util.concurrent.ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(8),
                    runnable -> {
                        final Thread thread = new Thread(runnable, "turboism-acp-permission");
                        thread.setDaemon(true);
                        return thread;
                    },
                    new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private final Thread stdoutThread;
    private final Thread stderrThread;

    /** Starts the resolved agent process and completes the ACP initialize handshake. */
    static AcpClient start(final AgentLaunchSpec configuration, final AcpListener listener)
            throws IOException, AcpException {
        final AcpClient client = new AcpClient(AcpProcessTransport.start(configuration), listener);
        try {
            client.initialize(DEFAULT_TIMEOUT);
            return client;
        } catch (AcpException | RuntimeException failure) {
            client.close();
            throw failure;
        }
    }

    AcpClient(final AcpTransport transport, final AcpListener listener) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.writer = new BufferedWriter(new OutputStreamWriter(transport.stdin(), StandardCharsets.UTF_8));
        stdoutThread = daemon("turboism-acp-stdout", this::readStdout);
        stderrThread = daemon("turboism-acp-stderr", this::readStderr);
        stdoutThread.start();
        stderrThread.start();
    }

    /**
     * Performs the ACP v1 initialize handshake, advertising terminal-authentication support but no
     * filesystem, terminal, or elicitation hosts.
     */
    void initialize(final Duration timeout) throws AcpException {
        final LinkedHashMap<String, Object> clientInfo = new LinkedHashMap<>();
        clientInfo.put("name", "turboism-acp");
        clientInfo.put("title", "Turboism ACP");
        clientInfo.put("version", "0.1.0");
        final LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", 1L);
        params.put(
                "clientCapabilities",
                Map.of("auth", Map.of("terminal", true)));
        params.put("clientInfo", clientInfo);
        final Map<String, Object> result = object(await(request("initialize", params), timeout));
        if (longValue(result.get("protocolVersion")) != 1L) {
            throw new AcpException("agent returned an unsupported ACP protocol version");
        }
        final Map<String, Object> info = objectOrEmpty(result.get("agentInfo"));
        agentInfo = new AcpAgentInfo(
                redactedUi(display(info.get("name")), MAX_UI_METADATA_CHARS),
                redactedUi(display(info.get("title")), MAX_UI_METADATA_CHARS),
                redactedUi(display(info.get("version")), MAX_UI_METADATA_CHARS));
        capabilities = AcpCapabilities.from(result.get("agentCapabilities"));
        authMethods = authMethods(result.get("authMethods"));
    }

    /** Identity advertised by the connected agent during initialization. */
    record AcpAgentInfo(String name, String title, String version) {
        static final AcpAgentInfo UNKNOWN = new AcpAgentInfo("", "", "");

        AcpAgentInfo {
            name = Objects.requireNonNullElse(name, "");
            title = Objects.requireNonNullElse(title, "");
            version = Objects.requireNonNullElse(version, "");
        }

        String displayName() {
            return !title.isBlank() ? title : name;
        }
    }

    /** Returns the connected agent's self-reported identity. */
    AcpAgentInfo agentInfo() {
        return agentInfo;
    }

    /** Returns the exact session lifecycle surface advertised by the agent during initialization. */
    AcpCapabilities capabilities() {
        return capabilities;
    }

    /** Returns the authentication methods the agent advertised during initialization. */
    List<AcpAuthMethod> authMethods() {
        return authMethods;
    }

    /**
     * Creates a new session, passing the Turboism MCP endpoint only when the agent advertised HTTP
     * MCP support.
     */
    AcpSession newSession(final Path cwd, final McpHttpConnection connection, final Duration timeout)
            throws AcpException {
        final LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        params.put("cwd", absolute(cwd));
        params.put("mcpServers", mcpServers(connection));
        return newSessionResponse(await(request("session/new", params), timeout), capabilities);
    }

    /** Loads a durable session and rebinds its MCP runtime to the current Turboism endpoint. */
    AcpSession loadSession(
            final String sessionId, final Path cwd, final McpHttpConnection connection, final Duration timeout)
            throws AcpException {
        final LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        params.put("sessionId", requireText(sessionId, "sessionId", 512));
        params.put("cwd", absolute(cwd));
        params.put("mcpServers", mcpServers(connection));
        return loadSessionResponse(
                requireText(sessionId, "sessionId", 512),
                await(request("session/load", params), timeout),
                capabilities);
    }

    /** Resumes a durable session without replaying its conversation history. */
    AcpSession resumeSession(
            final String sessionId, final Path cwd, final McpHttpConnection connection, final Duration timeout)
            throws AcpException {
        final LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        params.put("sessionId", requireText(sessionId, "sessionId", 512));
        params.put("cwd", absolute(cwd));
        params.put("mcpServers", mcpServers(connection));
        return loadSessionResponse(
                requireText(sessionId, "sessionId", 512),
                await(request("session/resume", params), timeout),
                capabilities);
    }

    /** Lists agent-owned durable sessions in the workspace established during ACP initialization. */
    List<AcpSessionSummary> listSessions(final Duration timeout) throws AcpException {
        final Map<String, Object> response = object(await(request("session/list", Map.of()), timeout));
        final ArrayList<AcpSessionSummary> sessions = new ArrayList<>();
        for (Object rawSession : list(response.get("sessions"))) {
            final Map<String, Object> entry = object(rawSession);
            final String sessionId = string(entry.get("sessionId"));
            final String updatedAt = string(entry.get("updatedAt"));
            if (sessionId != null && updatedAt != null) {
                sessions.add(new AcpSessionSummary(sessionId, redactedUi(updatedAt, MAX_UI_METADATA_CHARS)));
            }
        }
        return List.copyOf(sessions);
    }

    /** Sends one text prompt and completes with the agent's ACP stop reason. */
    CompletableFuture<String> prompt(final String sessionId, final String text) {
        final LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        params.put("sessionId", requireText(sessionId, "sessionId", 512));
        params.put("prompt", List.of(Map.of("type", "text", "text", requireText(text, "text", 1024 * 1024))));
        return request("session/prompt", params).thenApply(result -> {
            final String stopReason = string(object(result).get("stopReason"));
            return stopReason == null ? "unknown" : redactedUi(stopReason, MAX_UI_METADATA_CHARS);
        });
    }

    /** Applies an agent-owned configuration option and returns the refreshed catalog. */
    PendingConfigUpdate setConfigOption(final String sessionId, final String configId, final String value) {
        final CompletableFuture<Object> request = request(
                "session/set_config_option",
                Map.of(
                        "sessionId", requireText(sessionId, "sessionId", 512),
                        "configId", requireText(configId, "configId", 128),
                        "value", requireText(value, "value", 8192)));
        return new PendingConfigUpdate(
                request,
                request.thenApply(result -> configOptions(object(result).get("configOptions"))));
    }

    /** Runs one advertised protocol-driven authentication method. */
    void authenticate(final String methodId, final Duration timeout) throws AcpException {
        await(
                request("authenticate", Map.of("methodId", requireText(methodId, "methodId", 512))),
                timeout);
    }

    /** Ends the agent's authenticated state when it advertises the logout capability. */
    void logout(final Duration timeout) throws AcpException {
        await(request("logout", Map.of()), timeout);
    }

    /**
     * Removes a request that the caller stopped waiting for.
     *
     * <p>Conditional removal leaves a concurrently completed response untouched and makes a later
     * response for an abandoned request harmless.</p>
     */
    void abandon(final CompletableFuture<?> future) {
        Objects.requireNonNull(future, "future");
        pending.entrySet().removeIf(entry -> entry.getValue() == future);
        future.cancel(false);
    }

    record PendingConfigUpdate(CompletableFuture<Object> request, CompletableFuture<List<AcpConfigOption>> result) {
        PendingConfigUpdate {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(result, "result");
        }
    }

    /** Closed ACP session lifecycle surface; omitted fields remain unsupported. */
    record AcpCapabilities(
            boolean loadSession,
            boolean listSessions,
            boolean closeSession,
            boolean resumeSession,
            boolean deleteSession,
            boolean mcpHttp,
            boolean authLogout) {
        static final AcpCapabilities NONE = new AcpCapabilities(false, false, false, false, false, false, false);

        private static AcpCapabilities from(final Object value) {
            final Map<String, Object> advertised = objectOrEmpty(value);
            final Map<String, Object> sessions = objectOrEmpty(advertised.get("sessionCapabilities"));
            final Map<String, Object> mcp = objectOrEmpty(advertised.get("mcpCapabilities"));
            final Map<String, Object> auth = objectOrEmpty(advertised.get("auth"));
            return new AcpCapabilities(
                    Boolean.TRUE.equals(advertised.get("loadSession")),
                    sessions.containsKey("list"),
                    sessions.containsKey("close"),
                    sessions.containsKey("resume"),
                    sessions.containsKey("delete"),
                    Boolean.TRUE.equals(mcp.get("http")),
                    auth.containsKey("logout"));
        }
    }

    /** Best-effort notification that interrupts the active agent prompt. */
    void cancel(final String sessionId) {
        sendNotification("session/cancel", Map.of("sessionId", requireText(sessionId, "sessionId", 512)));
    }

    /** Flushes and closes the active durable session before process teardown. */
    void closeSession(final String sessionId, final Duration timeout) throws AcpException {
        await(request("session/close", Map.of("sessionId", requireText(sessionId, "sessionId", 512))), timeout);
    }

    private CompletableFuture<Object> request(final String method, final Object params) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new AcpException("ACP client is closed"));
        }
        if (pending.size() >= MAX_PENDING_REQUESTS) {
            return CompletableFuture.failedFuture(new AcpException("too many pending ACP requests"));
        }
        final long id = requestIds.getAndIncrement();
        final CompletableFuture<Object> result = new CompletableFuture<>();
        if (pending.putIfAbsent(id, result) != null) {
            return CompletableFuture.failedFuture(new AcpException("ACP request id collision"));
        }
        try {
            final LinkedHashMap<String, Object> message = new LinkedHashMap<>();
            message.put("jsonrpc", "2.0");
            message.put("id", id);
            message.put("method", method);
            message.put("params", params);
            write(message);
        } catch (RuntimeException | AcpException failure) {
            pending.remove(id);
            result.completeExceptionally(failure);
        }
        return result;
    }

    private void sendNotification(final String method, final Object params) {
        if (closed.get()) return;
        final LinkedHashMap<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        message.put("params", params);
        try {
            write(message);
        } catch (AcpException failure) {
            fail(failure);
        }
    }

    private void write(final Map<String, ?> message) throws AcpException {
        final String line = Json.stringify(message);
        if (line.length() > MAX_ACP_LINE_CHARS) {
            throw new AcpException("outgoing ACP message exceeds the line limit");
        }
        synchronized (writeLock) {
            if (closed.get()) throw new AcpException("ACP client is closed");
            try {
                writer.write(line);
                writer.write('\n');
                writer.flush();
            } catch (IOException failure) {
                throw new AcpException("could not write to ACP", failure);
            }
        }
    }

    private void readStdout() {
        try (BoundedLineReader lines = new BoundedLineReader(strictUtf8(transport.stdout()), MAX_ACP_LINE_CHARS)) {
            for (String line; (line = lines.readLine()) != null; ) {
                if (line.isBlank()) continue;
                try {
                    dispatch(Json.parseObject(line.getBytes(StandardCharsets.UTF_8)));
                } catch (RuntimeException failure) {
                    protocolFailureHint.compareAndSet(null, protocolLineHint(line));
                    protocolFailurePreview.compareAndSet(null, protocolLinePreview(redact(line)));
                    throw failure;
                }
            }
            if (!closed.get()) {
                awaitStderrAfterProcessExit();
                final String hint = stderrFailureHint.get();
                fail(new AcpException(
                        hint == null
                                ? "ACP stdout closed unexpectedly"
                                : "ACP stdout closed unexpectedly: " + hint));
            }
        } catch (IOException | RuntimeException failure) {
            if (!closed.get()) {
                fail(new AcpException(protocolFailureMessage(), failure));
            }
        }
    }

    private void readStderr() {
        try (BoundedLineReader lines = new BoundedLineReader(strictUtf8(transport.stderr()), MAX_STDERR_LINE_CHARS)) {
            for (BoundedLineReader.Line line; (line = lines.readLineTruncated()) != null; ) {
                stderrFailureHint.compareAndSet(null, protocolLinePreview(redact(line.text())));
                final String redacted = redact(line.text());
                listener.stderr(
                        this,
                        unsafeTruncatedSecret(redacted, line.truncated())
                                ? "<redacted>…"
                                : redacted + (line.truncated() ? "…" : ""));
            }
        } catch (IOException failure) {
            if (!closed.get()) listener.stderr(this, "agent stderr could not be read");
        } finally {
            stderrFinished.countDown();
        }
    }

    private void awaitStderrAfterProcessExit() {
        try {
            stderrFinished.await(250L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void dispatch(final Object value) {
        final Map<String, Object> message = object(value);
        if (!"2.0".equals(string(message.get("jsonrpc")))) {
            throw new IllegalArgumentException("ACP message has an invalid jsonrpc version");
        }
        final Object idValue = message.get("id");
        final String method = string(message.get("method"));
        if (method != null) {
            if (idValue == null) {
                notification(method, objectOrEmpty(message.get("params")));
            } else {
                incomingRequest(idValue, method, objectOrEmpty(message.get("params")));
            }
            return;
        }
        final long id = longValue(idValue);
        final CompletableFuture<Object> future = pending.remove(id);
        if (future == null) return;
        if (message.containsKey("error")) {
            future.completeExceptionally(rpcError(message.get("error")));
        } else if (message.containsKey("result")) {
            future.complete(message.get("result"));
        } else {
            future.completeExceptionally(new AcpException("ACP response has no result or error"));
        }
    }

    private void notification(final String method, final Map<String, Object> params) {
        if (!"session/update".equals(method)) return;
        final String sessionId = safeSessionId(params.get("sessionId"));
        if (sessionId == null) return;
        final Map<String, Object> update = objectOrEmpty(params.get("update"));
        final String kind = string(update.get("sessionUpdate"));
        if ("agent_message_chunk".equals(kind)) {
            final Map<String, Object> content = objectOrEmpty(update.get("content"));
            final String text = string(content.get("text"));
            if (text != null) {
                listener.agentText(this, sessionId, redactedUi(text, MAX_UI_CONTENT_CHARS));
            }
        } else if ("agent_thought_chunk".equals(kind)) {
            final Map<String, Object> content = objectOrEmpty(update.get("content"));
            final String text = string(content.get("text"));
            if (text != null) {
                listener.agentThought(this, sessionId, redactedUi(text, MAX_UI_CONTENT_CHARS));
            }
        } else if ("tool_call".equals(kind)) {
            listener.toolCall(
                    this,
                    sessionId,
                    toolCallKey(update.get("toolCallId")),
                    redactedUi(display(update.get("title")), MAX_UI_METADATA_CHARS),
                    redactedUi(display(update.get("kind")), MAX_UI_METADATA_CHARS),
                    redactedUi(display(update.get("status")), MAX_UI_METADATA_CHARS));
        } else if ("tool_call_update".equals(kind)) {
            listener.toolCallUpdate(
                    this,
                    sessionId,
                    toolCallKey(update.get("toolCallId")),
                    redactedUi(display(update.get("status")), MAX_UI_METADATA_CHARS),
                    redactedUi(contentText(update.get("content")), MAX_UI_CONTENT_CHARS));
        } else if ("config_option_update".equals(kind)) {
            listener.configOptions(this, sessionId, configOptions(update.get("configOptions")));
        } else if ("available_commands_update".equals(kind)) {
            listener.availableCommands(this, sessionId, commandNames(update.get("availableCommands")));
        }
    }

    private void incomingRequest(final Object id, final String method, final Map<String, Object> params) {
        if (!"session/request_permission".equals(method)) {
            sendError(id, -32601L, "Method not found");
            return;
        }
        final String sessionId = safeSessionId(params.get("sessionId"));
        if (sessionId == null) {
            sendError(id, -32602L, "Invalid session id");
            return;
        }
        final Map<String, Object> toolCall = objectOrEmpty(params.get("toolCall"));
        final PermissionOptionSet optionSet = permissionOptions(params.get("options"));
        if (optionSet == null) {
            sendError(id, -32602L, "Unsupported permission options");
            return;
        }
        final AcpListener.PermissionRequest request = new AcpListener.PermissionRequest(
                redactedUi(display(toolCall.get("title")), MAX_UI_METADATA_CHARS),
                redactedUi(display(toolCall.get("kind")), MAX_UI_METADATA_CHARS),
                redactedUi(display(toolCall.get("toolCallId")), MAX_UI_METADATA_CHARS),
                permissionDetails(toolCall.get("rawInput")),
                optionSet);
        try {
            permissionExecutor.execute(() -> answerPermission(id, sessionId, request));
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            if (!closed.get()) sendCancelledPermission(id);
        }
    }

    private void answerPermission(
            final Object id, final String sessionId, final AcpListener.PermissionRequest request) {
        final AcpListener.PermissionDecision decision;
        try {
            decision = Objects.requireNonNullElse(
                    listener.permission(this, sessionId, request), AcpListener.PermissionDecision.CANCELLED);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            sendCancelledPermission(id);
            return;
        }
        final String optionId = switch (decision) {
            case ALLOW_ONCE -> request.options().allowOnce();
            case ALLOW_ALWAYS -> request.options().allowAlways();
            case REJECT_ONCE -> request.options().rejectOnce();
            case CANCELLED -> null;
        };
        final Map<String, Object> outcome = optionId == null
                ? Map.of("outcome", "cancelled")
                : Map.of("outcome", "selected", "optionId", optionId);
        sendResult(id, Map.of("outcome", outcome));
    }

    private void sendCancelledPermission(final Object id) {
        sendResult(id, Map.of("outcome", Map.of("outcome", "cancelled")));
    }

    private void sendResult(final Object id, final Object result) {
        final LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        try {
            write(response);
        } catch (AcpException failure) {
            fail(failure);
        }
    }

    private void sendError(final Object id, final long code, final String message) {
        final LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message));
        try {
            write(response);
        } catch (AcpException failure) {
            fail(failure);
        }
    }

    private AcpSession newSessionResponse(final Object result, final AcpCapabilities sessionCapabilities) {
        final Map<String, Object> response = object(result);
        final String sessionId = safeSessionId(response.get("sessionId"));
        if (sessionId == null) {
            throw new IllegalArgumentException("ACP new-session response has an invalid sessionId");
        }
        return loadSessionResponse(sessionId, response, sessionCapabilities);
    }

    private AcpSession loadSessionResponse(
            final String sessionId, final Object result, final AcpCapabilities sessionCapabilities) {
        final Map<String, Object> response = objectOrEmpty(result);
        return new AcpSession(sessionId, configOptions(response.get("configOptions")), sessionCapabilities);
    }

    private List<AcpConfigOption> configOptions(final Object value) {
        final List<Object> entries = list(value);
        final ArrayList<AcpConfigOption> options = new ArrayList<>();
        for (Object entry : entries) {
            final Map<String, Object> option = object(entry);
            if (!"select".equals(string(option.get("type")))) continue;
            final String id = configId(option);
            final String name = string(option.get("name"));
            final String current = string(option.get("currentValue"));
            if (id == null || name == null || current == null) continue;
            final ArrayList<AcpConfigOption.Choice> choices = new ArrayList<>();
            for (Object rawChoice : list(option.get("options"))) {
                final Map<String, Object> choice = object(rawChoice);
                final String choiceValue = string(choice.get("value"));
                final String choiceName = string(choice.get("name"));
                if (choiceValue != null && choiceName != null) {
                    choices.add(new AcpConfigOption.Choice(
                            choiceValue, redactedUi(choiceName, MAX_UI_METADATA_CHARS)));
                }
            }
            if (!choices.isEmpty()) {
                options.add(new AcpConfigOption(
                        id,
                        redactedUi(name, MAX_UI_METADATA_CHARS),
                        redactedUi(display(option.get("category")), MAX_UI_METADATA_CHARS),
                        redactedUi(display(option.get("description")), MAX_UI_METADATA_CHARS),
                        current,
                        choices));
            }
        }
        return List.copyOf(options);
    }

    private static String configId(final Map<String, Object> option) {
        // The ACP v1 schema used "id" while later revisions renamed the field to "configId".
        final String renamed = string(option.get("configId"));
        return renamed == null ? string(option.get("id")) : renamed;
    }

    private static List<AcpAuthMethod> authMethods(final Object value) {
        final ArrayList<AcpAuthMethod> methods = new ArrayList<>();
        for (Object entry : list(value)) {
            final Map<String, Object> method = object(entry);
            final String id = string(method.get("id"));
            if (id == null) continue;
            final ArrayList<String> args = new ArrayList<>();
            for (Object arg : list(method.get("args"))) {
                final String argument = string(arg);
                if (argument != null) args.add(argument);
            }
            final LinkedHashMap<String, String> env = new LinkedHashMap<>();
            final Object environment = method.get("env");
            if (environment instanceof Map<?, ?> map) {
                map.forEach((key, val) -> {
                    if (key instanceof String name && val instanceof String text) {
                        env.put(name, text);
                    }
                });
            }
            methods.add(new AcpAuthMethod(
                    id,
                    redactedUi(display(method.get("name")), MAX_UI_METADATA_CHARS),
                    redactedUi(display(method.get("description")), MAX_UI_METADATA_CHARS),
                    "terminal".equals(string(method.get("type")))
                            ? AcpAuthMethod.Kind.TERMINAL
                            : AcpAuthMethod.Kind.AGENT,
                    args,
                    env));
        }
        return List.copyOf(methods);
    }

    private static List<String> commandNames(final Object value) {
        final ArrayList<String> names = new ArrayList<>();
        for (Object entry : list(value)) {
            final Map<String, Object> command = object(entry);
            final String name = string(command.get("name"));
            if (name != null) names.add(name);
        }
        return List.copyOf(names);
    }

    private Object await(final CompletableFuture<Object> future, final Duration timeout) throws AcpException {
        try {
            return future.get(Objects.requireNonNull(timeout, "timeout").toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException failure) {
            abandon(future);
            throw new AcpException("timed out waiting for ACP", failure);
        } catch (InterruptedException failure) {
            abandon(future);
            Thread.currentThread().interrupt();
            throw new AcpException("interrupted while waiting for ACP", failure);
        } catch (java.util.concurrent.ExecutionException failure) {
            final Throwable cause = failure.getCause();
            if (cause instanceof AcpException acp) throw acp;
            throw new AcpException("ACP request failed", cause);
        }
    }

    private List<Object> mcpServers(final McpHttpConnection connection) {
        if (connection == null || !capabilities.mcpHttp()) {
            return List.of();
        }
        return List.of(mcpServer(connection));
    }

    private static Map<String, Object> mcpServer(final McpHttpConnection connection) {
        final LinkedHashMap<String, Object> server = new LinkedHashMap<>();
        server.put("type", "http");
        server.put("name", "turboism");
        server.put("url", connection.endpoint().toString());
        return server;
    }

    /** Agent-side selectable outcome identifiers for a permission request. */
    record PermissionOptionSet(String allowOnce, String allowAlways, String rejectOnce) {
        PermissionOptionSet {
            if (allowOnce == null && rejectOnce == null) {
                throw new IllegalArgumentException("at least one permission outcome id is required");
            }
        }
    }

    /**
     * Maps the agent's advertised permission option ids to the client's fixed decisions.
     *
     * <p>ACP v1 agents offer per-kind option sets whose ids are not fully fixed by the schema;
     * well-known ids are preferred and the first available option of each polarity is used as a
     * fallback so mainstream agents remain usable.</p>
     */
    private static PermissionOptionSet permissionOptions(final Object value) {
        String allowOnce = null;
        String allowAlways = null;
        String rejectOnce = null;
        for (Object entry : list(value)) {
            final Map<String, Object> option = object(entry);
            final String optionId = string(option.get("optionId"));
            final String kind = string(option.get("kind"));
            if (optionId == null) continue;
            if ("allow_once".equals(optionId) || "allow".equals(optionId)) {
                allowOnce = optionId;
            } else if ("allow_always".equals(optionId)) {
                allowAlways = optionId;
            } else if ("reject_once".equals(optionId) || "reject".equals(optionId)) {
                rejectOnce = optionId;
            } else if (kind != null) {
                if (allowOnce == null && kind.startsWith("allow")) allowOnce = optionId;
                if (allowAlways == null && "allow_always".equals(kind)) allowAlways = optionId;
                if (rejectOnce == null && kind.startsWith("reject")) rejectOnce = optionId;
            }
        }
        if (allowOnce == null && rejectOnce == null) return null;
        return new PermissionOptionSet(allowOnce, allowAlways, rejectOnce);
    }

    private String permissionDetails(final Object rawInput) {
        final String json;
        try {
            json = redact(Json.stringify(objectOrEmpty(rawInput)));
        } catch (RuntimeException failure) {
            return "<unavailable>";
        }
        return bounded(json, MAX_PERMISSION_DETAILS_CHARS);
    }

    private static String redact(final String text) {
        final String assignments = CREDENTIAL_ASSIGNMENT.matcher(text).replaceAll("$1<redacted>");
        return BEARER_VALUE.matcher(assignments).replaceAll("<redacted>");
    }

    private static boolean unsafeTruncatedSecret(final String redacted, final boolean truncated) {
        return truncated && redacted.contains("<redacted>");
    }

    private static String redactedUi(final String text, final int maximum) {
        return bounded(redact(text), maximum);
    }

    private static String toolCallKey(final Object value) {
        final String toolCallId = display(value);
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        return java.util.HexFormat.of().formatHex(digest.digest(toolCallId.getBytes(StandardCharsets.UTF_8)));
    }

    private static String bounded(final String text, final int maximum) {
        if (text.length() <= maximum) return text;
        final int limit = maximum - 1;
        final java.util.regex.Matcher graphemes = GRAPHEME.matcher(text);
        int end = 0;
        while (graphemes.find() && graphemes.end() <= limit) end = graphemes.end();
        return text.substring(0, end) + "…";
    }

    private void fail(final AcpException failure) {
        if (!closed.compareAndSet(false, true)) return;
        permissionExecutor.shutdownNow();
        completePendingExceptionally(failure);
        closeWriter();
        transport.close();
        if (terminationReported.compareAndSet(false, true)) {
            listener.terminated(this, failure.getMessage());
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            transport.close();
            return;
        }
        permissionExecutor.shutdownNow();
        completePendingExceptionally(new AcpException("ACP client closed"));
        closeWriter();
        transport.close();
    }

    private void closeWriter() {
        synchronized (writeLock) {
            try {
                writer.close();
            } catch (IOException ignored) {
                // Process termination below owns final cleanup.
            }
        }
    }

    private void completePendingExceptionally(final AcpException failure) {
        final List<CompletableFuture<Object>> requests = List.copyOf(pending.values());
        pending.clear();
        requests.forEach(future -> future.completeExceptionally(failure));
    }

    private static Thread daemon(final String name, final Runnable work) {
        final Thread thread = new Thread(work, name);
        thread.setDaemon(true);
        return thread;
    }

    private static InputStreamReader strictUtf8(final java.io.InputStream stream) {
        return new InputStreamReader(
                Objects.requireNonNull(stream, "stream"),
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(final Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("ACP value must be an object");
        }
        return (Map<String, Object>) map;
    }

    private static Map<String, Object> objectOrEmpty(final Object value) {
        return value == null ? Map.of() : object(value);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(final Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("ACP value must be an array");
        }
        return (List<Object>) list;
    }

    private static long longValue(final Object value) {
        if (value instanceof Long number) return number;
        if (value instanceof Integer number) return number.longValue();
        if (value instanceof BigDecimal number) return number.longValueExact();
        throw new IllegalArgumentException("ACP id must be an integer");
    }

    private static String string(final Object value) {
        return value instanceof String text ? text : null;
    }

    private static String display(final Object value) {
        final String text = string(value);
        return text == null ? "" : text;
    }

    private static String contentText(final Object value) {
        final StringBuilder text = new StringBuilder();
        for (Object entry : list(value)) {
            final Map<String, Object> wrapper = object(entry);
            final Map<String, Object> content = objectOrEmpty(wrapper.get("content"));
            final String chunk = string(content.get("text"));
            if (chunk != null) text.append(chunk);
        }
        return text.toString();
    }

    private AcpException rpcError(final Object value) {
        final Map<String, Object> error = object(value);
        final String message = string(error.get("message"));
        final Long code = errorCode(error.get("code"));
        final String text = message == null ? "ACP request returned an error" : redact(message);
        return new AcpException(text, code);
    }

    private static Long errorCode(final Object value) {
        if (value instanceof Long number) return number;
        if (value instanceof Integer number) return number.longValue();
        if (value instanceof BigDecimal number) return number.longValueExact();
        return null;
    }

    private static String absolute(final Path path) {
        return Objects.requireNonNull(path, "cwd").toAbsolutePath().normalize().toString();
    }

    private static String protocolLineHint(final String line) {
        if (line.startsWith("Picked up JAVA_TOOL_OPTIONS")) {
            return "the selected Java launcher wrote JAVA_TOOL_OPTIONS text to ACP stdout";
        }
        if (line.startsWith("Error:") || line.startsWith("Usage:")) {
            return "the configured executable wrote launcher text to ACP stdout";
        }
        return "the configured executable wrote non-JSON text to ACP stdout";
    }

    private static String protocolLinePreview(final String line) {
        final String lower = line.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("bearer ")
                || containsCredentialAssignment(lower)
                || lower.contains("authorization:")
                || lower.contains("password=")) {
            return "<redacted>";
        }
        final int maximum = Math.min(line.length(), 160);
        final StringBuilder preview = new StringBuilder(maximum);
        for (int index = 0; index < maximum; index++) {
            final char value = line.charAt(index);
            preview.append(value >= 0x20 && value <= 0x7e ? value : '?');
        }
        return preview.toString();
    }

    private static boolean containsCredentialAssignment(final String lower) {
        return lower.contains("token=")
                || lower.contains("token:")
                || lower.contains("credential=")
                || lower.contains("credential:")
                || lower.contains("secret=")
                || lower.contains("secret:")
                || lower.contains("authorization=");
    }

    private String protocolFailureMessage() {
        final String hint = protocolFailureHint.get();
        final String preview = protocolFailurePreview.get();
        final StringBuilder message = new StringBuilder("ACP output is invalid");
        if (hint != null) message.append(": ").append(hint);
        if (preview != null) message.append("; first line: ").append(preview);
        return message.toString();
    }

    private String safeSessionId(final Object value) {
        final String sessionId = string(value);
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 512 || sessionId.indexOf('\0') >= 0) {
            return null;
        }
        final String redacted = redact(sessionId);
        if (!redacted.equals(sessionId) || sessionId.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            throw new IllegalArgumentException("ACP sessionId contained authorization material");
        }
        return sessionId;
    }

    private static String requireText(final String value, final String name, final int maximum) {
        final String text = Objects.requireNonNull(value, name);
        if (text.isBlank() || text.length() > maximum || text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return text;
    }
}
