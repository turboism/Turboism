package dev.turboism.plugin.turboismwithfx;

import dev.turboism.sdk.io.BoundedLineReader;
import dev.turboism.protocol.json.StrictJson;
import dev.turboism.sdk.mcp.McpHttpConnection;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.net.URI;
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
 * Dependency-free ACP v1 JSON-RPC client over a supervised fx JSONL process.
 *
 * <p>One daemon reader owns stdout parsing, one daemon reader drains stderr, and all writes are
 * serialized under a private lock. Response correlation is bounded by the client's pending map;
 * EOF, malformed UTF-8, invalid JSON, oversized lines, and process closure fail every pending
 * request. The client never logs protocol lines and redacts the current MCP bearer value from
 * forwarded stderr.</p>
 */
final class FxAcpClient implements AutoCloseable {

    static final int MAX_ACP_LINE_CHARS = 8 * 1024 * 1024;
    static final String SUPPORTED_FX_VERSION = "0.0.5";
    private static final int MAX_STDERR_LINE_CHARS = 16 * 1024;
    private static final int MAX_PERMISSION_DETAILS_CHARS = 32 * 1024;
    private static final int MAX_UI_METADATA_CHARS = 8 * 1024;
    private static final int MAX_UI_CONTENT_CHARS = 256 * 1024;
    private static final int MAX_PENDING_REQUESTS = 64;
    private static final java.util.regex.Pattern GRAPHEME =
        java.util.regex.Pattern.compile("\\X");
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    private final FxAcpTransport transport;
    private final FxAcpListener listener;
    private final BufferedWriter writer;
    private final Object writeLock = new Object();
    private final ConcurrentHashMap<Long, CompletableFuture<Object>> pending =
        new ConcurrentHashMap<>();
    private final AtomicLong requestIds = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean terminationReported = new AtomicBoolean();
    private final java.util.Set<String> authorizations = ConcurrentHashMap.newKeySet();
    private final AtomicReference<String> protocolFailureHint = new AtomicReference<>();
    private final AtomicReference<String> protocolFailurePreview = new AtomicReference<>();
    private final AtomicReference<String> stderrFailureHint = new AtomicReference<>();
    private final CountDownLatch stderrFinished = new CountDownLatch(1);
    private volatile FxAcpCapabilities capabilities = FxAcpCapabilities.NONE;
    private final java.util.concurrent.ThreadPoolExecutor permissionExecutor =
        new java.util.concurrent.ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(8),
            runnable -> {
                final Thread thread = new Thread(runnable, "turboism-fx-acp-permission");
                thread.setDaemon(true);
                return thread;
            },
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy()
        );
    private final Thread stdoutThread;
    private final Thread stderrThread;

    /** Starts the resolved fx process and completes the ACP initialize handshake. */
    static FxAcpClient start(
        final FxLaunchConfiguration configuration,
        final FxAcpListener listener
    ) throws IOException, FxAcpException {
        final FxAcpClient client = new FxAcpClient(
            FxProcessTransport.start(configuration),
            listener
        );
        try {
            client.initialize(DEFAULT_TIMEOUT);
            return client;
        } catch (FxAcpException | RuntimeException failure) {
            client.close();
            throw failure;
        }
    }

    FxAcpClient(final FxAcpTransport transport, final FxAcpListener listener) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.writer = new BufferedWriter(new OutputStreamWriter(
            transport.stdin(),
            StandardCharsets.UTF_8
        ));
        stdoutThread = daemon("turboism-fx-acp-stdout", this::readStdout);
        stderrThread = daemon("turboism-fx-acp-stderr", this::readStderr);
        stdoutThread.start();
        stderrThread.start();
    }

    /** Performs the ACP v1 initialize handshake without advertising filesystem or terminal hosts. */
    void initialize(final Duration timeout) throws FxAcpException {
        final LinkedHashMap<String, Object> clientInfo = new LinkedHashMap<>();
        clientInfo.put("name", "turboism-with-fx");
        clientInfo.put("title", "Turboism with fx");
        clientInfo.put("version", "0.1.0");
        final LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", 1L);
        params.put("clientCapabilities", Map.of());
        params.put("clientInfo", clientInfo);
        final Map<String, Object> result = object(await(request("initialize", params), timeout));
        if (longValue(result.get("protocolVersion")) != 1L) {
            throw new FxAcpException("fx returned an unsupported ACP protocol version");
        }
        final Map<String, Object> agentInfo = object(result.get("agentInfo"));
        if (!"fx".equals(string(agentInfo.get("name")))) {
            throw new FxAcpException("ACP executable is not fx");
        }
        if (!SUPPORTED_FX_VERSION.equals(string(agentInfo.get("version")))) {
            throw new FxAcpException(
                "fx version is unsupported; install fx " + SUPPORTED_FX_VERSION
            );
        }
        capabilities = FxAcpCapabilities.from(result.get("agentCapabilities"));
    }

    /** Returns the exact session lifecycle surface advertised by fx during initialization. */
    FxAcpCapabilities capabilities() {
        return capabilities;
    }

    /** Creates a new fx session using only the supplied Turboism MCP endpoint. */
    FxAcpSession newSession(
        final Path cwd,
        final McpHttpConnection connection,
        final Duration timeout
    ) throws FxAcpException {
        final McpHttpConnection mcp = remember(connection);
        final LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        params.put("cwd", absolute(cwd));
        params.put("mcpServers", List.of(mcpServer(mcp)));
        return newSessionResponse(
            await(request("session/new", params), timeout),
            capabilities
        );
    }

    /** Loads a durable fx session and rebinds its MCP runtime to the current Turboism endpoint. */
    FxAcpSession loadSession(
        final String sessionId,
        final Path cwd,
        final McpHttpConnection connection,
        final Duration timeout
    ) throws FxAcpException {
        final McpHttpConnection mcp = remember(connection);
        final LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        params.put("sessionId", requireText(sessionId, "sessionId", 512));
        params.put("cwd", absolute(cwd));
        params.put("mcpServers", List.of(mcpServer(mcp)));
        return loadSessionResponse(
            requireText(sessionId, "sessionId", 512),
            await(request("session/load", params), timeout),
            capabilities
        );
    }

    /** Lists fx-owned durable sessions in the workspace established during ACP initialization. */
    List<FxAcpSessionSummary> listSessions(final Duration timeout) throws FxAcpException {
        final Map<String, Object> response = object(await(
            request("session/list", Map.of()),
            timeout
        ));
        final ArrayList<FxAcpSessionSummary> sessions = new ArrayList<>();
        for (Object rawSession : list(response.get("sessions"))) {
            final Map<String, Object> entry = object(rawSession);
            final String sessionId = string(entry.get("sessionId"));
            final String updatedAt = string(entry.get("updatedAt"));
            if (sessionId != null && updatedAt != null) {
                sessions.add(new FxAcpSessionSummary(
                    sessionId,
                    redactedUi(updatedAt, MAX_UI_METADATA_CHARS)
                ));
            }
        }
        return List.copyOf(sessions);
    }

    /** Sends one text prompt and completes with fx's ACP stop reason. */
    CompletableFuture<String> prompt(final String sessionId, final String text) {
        final LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        params.put("sessionId", requireText(sessionId, "sessionId", 512));
        params.put("prompt", List.of(Map.of(
            "type", "text",
            "text", requireText(text, "text", 1024 * 1024)
        )));
        return request("session/prompt", params).thenApply(result -> {
            final String stopReason = string(object(result).get("stopReason"));
            return stopReason == null
                ? "unknown"
                : redactedUi(stopReason, MAX_UI_METADATA_CHARS);
        });
    }

    /** Applies an fx-owned provider/model option and returns fx's refreshed catalog. */
    PendingConfigUpdate setConfigOption(
        final String sessionId,
        final String configId,
        final String value
    ) {
        final CompletableFuture<Object> request = request("session/set_config_option", Map.of(
            "sessionId", requireText(sessionId, "sessionId", 512),
            "configId", requireText(configId, "configId", 128),
            "value", requireText(value, "value", 8192)
        ));
        return new PendingConfigUpdate(
            request,
            request.thenApply(result -> configOptions(object(result).get("configOptions")))
        );
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

    record PendingConfigUpdate(
        CompletableFuture<Object> request,
        CompletableFuture<List<FxAcpConfigOption>> result
    ) {
        PendingConfigUpdate {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(result, "result");
        }
    }

    /** Closed ACP session lifecycle surface; omitted fields remain unsupported. */
    record FxAcpCapabilities(
        boolean loadSession,
        boolean listSessions,
        boolean closeSession
    ) {
        static final FxAcpCapabilities NONE = new FxAcpCapabilities(
            false, false, false
        );

        private static FxAcpCapabilities from(final Object value) {
            final Map<String, Object> advertised = objectOrEmpty(value);
            final Map<String, Object> sessions = objectOrEmpty(
                advertised.get("sessionCapabilities")
            );
            return new FxAcpCapabilities(
                Boolean.TRUE.equals(advertised.get("loadSession")),
                sessions.containsKey("list"),
                sessions.containsKey("close")
            );
        }
    }

    /** Best-effort notification that interrupts the active fx prompt. */
    void cancel(final String sessionId) {
        sendNotification("session/cancel", Map.of(
            "sessionId", requireText(sessionId, "sessionId", 512)
        ));
    }

    /** Flushes and closes the active durable session before process teardown. */
    void closeSession(final String sessionId, final Duration timeout) throws FxAcpException {
        await(request("session/close", Map.of(
            "sessionId", requireText(sessionId, "sessionId", 512)
        )), timeout);
    }

    private CompletableFuture<Object> request(final String method, final Object params) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new FxAcpException("fx ACP client is closed"));
        }
        if (pending.size() >= MAX_PENDING_REQUESTS) {
            return CompletableFuture.failedFuture(new FxAcpException("too many pending fx ACP requests"));
        }
        final long id = requestIds.getAndIncrement();
        final CompletableFuture<Object> result = new CompletableFuture<>();
        if (pending.putIfAbsent(id, result) != null) {
            return CompletableFuture.failedFuture(new FxAcpException("fx ACP request id collision"));
        }
        try {
            final LinkedHashMap<String, Object> message = new LinkedHashMap<>();
            message.put("jsonrpc", "2.0");
            message.put("id", id);
            message.put("method", method);
            message.put("params", params);
            write(message);
        } catch (RuntimeException | FxAcpException failure) {
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
        } catch (FxAcpException failure) {
            fail(failure);
        }
    }

    private void write(final Object message) throws FxAcpException {
        final String line = StrictJson.stringify(message);
        if (line.length() > MAX_ACP_LINE_CHARS) {
            throw new FxAcpException("outgoing fx ACP message exceeds the line limit");
        }
        synchronized (writeLock) {
            if (closed.get()) throw new FxAcpException("fx ACP client is closed");
            try {
                writer.write(line);
                writer.write('\n');
                writer.flush();
            } catch (IOException failure) {
                throw new FxAcpException("could not write to fx ACP", failure);
            }
        }
    }

    private void readStdout() {
        try (BoundedLineReader lines = new BoundedLineReader(
            strictUtf8(transport.stdout()),
            MAX_ACP_LINE_CHARS
        )) {
            for (String line; (line = lines.readLine()) != null;) {
                if (line.isBlank()) continue;
                try {
                    dispatch(StrictJson.parse(line.getBytes(StandardCharsets.UTF_8)));
                } catch (RuntimeException failure) {
                    protocolFailureHint.compareAndSet(null, protocolLineHint(line));
                    protocolFailurePreview.compareAndSet(
                        null,
                        protocolLinePreview(redact(line))
                    );
                    throw failure;
                }
            }
            if (!closed.get()) {
                awaitStderrAfterProcessExit();
                final String hint = stderrFailureHint.get();
                fail(new FxAcpException(hint == null
                    ? "fx ACP stdout closed unexpectedly"
                    : "fx ACP stdout closed unexpectedly: " + hint));
            }
        } catch (IOException | RuntimeException failure) {
            if (!closed.get()) {
                fail(new FxAcpException(protocolFailureMessage(), failure));
            }
        }
    }

    private void readStderr() {
        try (BoundedLineReader lines = new BoundedLineReader(
            strictUtf8(transport.stderr()),
            MAX_STDERR_LINE_CHARS
        )) {
            for (BoundedLineReader.Line line; (line = lines.readLineTruncated()) != null;) {
                stderrFailureHint.compareAndSet(
                    null,
                    protocolLinePreview(redact(line.text()))
                );
                final String redacted = redact(line.text());
                listener.stderr(this, unsafeTruncatedSecret(redacted, line.truncated())
                    ? "<redacted>…"
                    : redacted + (line.truncated() ? "…" : ""));
            }
        } catch (IOException failure) {
            if (!closed.get()) listener.stderr(this, "fx stderr could not be read");
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
            throw new IllegalArgumentException("fx ACP message has an invalid jsonrpc version");
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
            future.completeExceptionally(new FxAcpException("fx ACP response has no result or error"));
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
                listener.agentText(
                    this,
                    sessionId,
                    redactedUi(text, MAX_UI_CONTENT_CHARS)
                );
            }
        } else if ("agent_thought_chunk".equals(kind)) {
            final Map<String, Object> content = objectOrEmpty(update.get("content"));
            final String text = string(content.get("text"));
            if (text != null) {
                listener.agentThought(
                    this,
                    sessionId,
                    redactedUi(text, MAX_UI_CONTENT_CHARS)
                );
            }
        } else if ("tool_call".equals(kind)) {
            listener.toolCall(
                this,
                sessionId,
                toolCallKey(update.get("toolCallId")),
                redactedUi(display(update.get("title")), MAX_UI_METADATA_CHARS),
                redactedUi(display(update.get("kind")), MAX_UI_METADATA_CHARS),
                redactedUi(display(update.get("status")), MAX_UI_METADATA_CHARS)
            );
        } else if ("tool_call_update".equals(kind)) {
            listener.toolCallUpdate(
                this,
                sessionId,
                toolCallKey(update.get("toolCallId")),
                redactedUi(display(update.get("status")), MAX_UI_METADATA_CHARS),
                redactedUi(contentText(update.get("content")), MAX_UI_CONTENT_CHARS)
            );
        }
    }

    private void incomingRequest(
        final Object id,
        final String method,
        final Map<String, Object> params
    ) {
        if (!"session/request_permission".equals(method)) {
            sendError(id, -32601L, "Method not found");
            return;
        }
        final String sessionId = safeSessionId(params.get("sessionId"));
        if (sessionId == null) {
            sendError(id, -32602L, "Invalid session id");
            return;
        }
        if (!supportsPermissionOptions(params.get("options"))) {
            sendError(id, -32602L, "Unsupported permission options");
            return;
        }
        final Map<String, Object> toolCall = objectOrEmpty(params.get("toolCall"));
        final FxAcpListener.PermissionRequest request = new FxAcpListener.PermissionRequest(
            redactedUi(display(toolCall.get("title")), MAX_UI_METADATA_CHARS),
            redactedUi(display(toolCall.get("kind")), MAX_UI_METADATA_CHARS),
            redactedUi(display(toolCall.get("toolCallId")), MAX_UI_METADATA_CHARS),
            permissionDetails(toolCall.get("rawInput"))
        );
        try {
            permissionExecutor.execute(() -> answerPermission(id, sessionId, request));
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            if (!closed.get()) sendCancelledPermission(id);
        }
    }

    private void answerPermission(
        final Object id,
        final String sessionId,
        final FxAcpListener.PermissionRequest request
    ) {
        final FxAcpListener.PermissionDecision decision;
        try {
            decision = Objects.requireNonNullElse(
                listener.permission(this, sessionId, request),
                FxAcpListener.PermissionDecision.CANCELLED
            );
        } catch (Throwable failure) {
            sendCancelledPermission(id);
            return;
        }
        final Map<String, Object> outcome = switch (decision) {
            case ALLOW_ONCE -> Map.of("outcome", "selected", "optionId", "allow_once");
            case ALLOW_ALWAYS -> Map.of("outcome", "selected", "optionId", "allow_always");
            case REJECT_ONCE -> Map.of("outcome", "selected", "optionId", "reject_once");
            case CANCELLED -> Map.of("outcome", "cancelled");
        };
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
        } catch (FxAcpException failure) {
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
        } catch (FxAcpException failure) {
            fail(failure);
        }
    }

    private FxAcpSession newSessionResponse(
        final Object result,
        final FxAcpCapabilities sessionCapabilities
    ) {
        final Map<String, Object> response = object(result);
        final String sessionId = safeSessionId(response.get("sessionId"));
        if (sessionId == null) {
            throw new IllegalArgumentException("fx ACP new-session response has an invalid sessionId");
        }
        return loadSessionResponse(sessionId, response, sessionCapabilities);
    }

    private FxAcpSession loadSessionResponse(
        final String sessionId,
        final Object result,
        final FxAcpCapabilities sessionCapabilities
    ) {
        final Map<String, Object> response = objectOrEmpty(result);
        return new FxAcpSession(
            sessionId,
            configOptions(response.get("configOptions")),
            sessionCapabilities
        );
    }

    private List<FxAcpConfigOption> configOptions(final Object value) {
        final List<Object> entries = list(value);
        final ArrayList<FxAcpConfigOption> options = new ArrayList<>();
        for (Object entry : entries) {
            final Map<String, Object> option = object(entry);
            if (!"select".equals(string(option.get("type")))) continue;
            final String id = string(option.get("id"));
            final String name = string(option.get("name"));
            final String current = string(option.get("currentValue"));
            if (id == null || name == null || current == null) continue;
            final ArrayList<FxAcpConfigOption.Choice> choices = new ArrayList<>();
            for (Object rawChoice : list(option.get("options"))) {
                final Map<String, Object> choice = object(rawChoice);
                final String choiceValue = string(choice.get("value"));
                final String choiceName = string(choice.get("name"));
                if (choiceValue != null && choiceName != null) {
                    choices.add(new FxAcpConfigOption.Choice(
                        choiceValue,
                        redactedUi(choiceName, MAX_UI_METADATA_CHARS)
                    ));
                }
            }
            if (!choices.isEmpty()) {
                options.add(new FxAcpConfigOption(
                    id,
                    redactedUi(name, MAX_UI_METADATA_CHARS),
                    current,
                    choices
                ));
            }
        }
        return List.copyOf(options);
    }

    private Object await(
        final CompletableFuture<Object> future,
        final Duration timeout
    ) throws FxAcpException {
        try {
            return future.get(Objects.requireNonNull(timeout, "timeout").toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException failure) {
            abandon(future);
            throw new FxAcpException("timed out waiting for fx ACP", failure);
        } catch (InterruptedException failure) {
            abandon(future);
            Thread.currentThread().interrupt();
            throw new FxAcpException("interrupted while waiting for fx ACP", failure);
        } catch (java.util.concurrent.ExecutionException failure) {
            final Throwable cause = failure.getCause();
            if (cause instanceof FxAcpException acp) throw acp;
            throw new FxAcpException("fx ACP request failed", cause);
        }
    }

    private McpHttpConnection remember(final McpHttpConnection connection) {
        final McpHttpConnection value = Objects.requireNonNull(connection, "connection");
        authorizations.add(value.authorization());
        return value;
    }

    private static Map<String, Object> mcpServer(final McpHttpConnection connection) {
        final LinkedHashMap<String, Object> server = new LinkedHashMap<>();
        server.put("type", "http");
        server.put("name", "turboism");
        server.put("url", connection.endpoint().toString());
        server.put("headers", List.of(Map.of(
            "name", "Authorization",
            "value", connection.authorization()
        )));
        return server;
    }

    private static boolean supportsPermissionOptions(final Object value) {
        final java.util.Set<String> available = new java.util.HashSet<>();
        for (Object entry : list(value)) {
            final String optionId = string(object(entry).get("optionId"));
            if (optionId != null) available.add(optionId);
        }
        return available.containsAll(java.util.Set.of(
            "allow_once", "allow_always", "reject_once"
        ));
    }

    private String permissionDetails(final Object rawInput) {
        final String json;
        try {
            json = redact(StrictJson.stringify(rawInput == null ? Map.of() : rawInput));
        } catch (RuntimeException failure) {
            return "<unavailable>";
        }
        return bounded(json, MAX_PERMISSION_DETAILS_CHARS);
    }

    private String redact(final String text) {
        String redacted = text;
        for (String secret : authorizations) {
            if (secret == null || secret.isEmpty()) continue;
            redacted = redacted.replace(secret, "<redacted>");
            if (secret.startsWith("Bearer ")) {
                redacted = redacted.replace(
                    secret.substring("Bearer ".length()),
                    "<redacted>"
                );
            }
        }
        return redacted;
    }

    private boolean unsafeTruncatedSecret(
        final String redacted,
        final boolean truncated
    ) {
        return truncated && !authorizations.isEmpty();
    }

    private String redactedUi(final String text, final int maximum) {
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
        return java.util.HexFormat.of().formatHex(
            digest.digest(toolCallId.getBytes(StandardCharsets.UTF_8))
        );
    }

    private static String bounded(final String text, final int maximum) {
        if (text.length() <= maximum) return text;
        final int limit = maximum - 1;
        final java.util.regex.Matcher graphemes = GRAPHEME.matcher(text);
        int end = 0;
        while (graphemes.find() && graphemes.end() <= limit) end = graphemes.end();
        return text.substring(0, end) + "…";
    }

    private void fail(final FxAcpException failure) {
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
        completePendingExceptionally(new FxAcpException("fx ACP client closed"));
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

    private void completePendingExceptionally(final FxAcpException failure) {
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
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(final Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("fx ACP value must be an object");
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
            throw new IllegalArgumentException("fx ACP value must be an array");
        }
        return (List<Object>) list;
    }

    private static long longValue(final Object value) {
        if (value instanceof Long number) return number;
        if (value instanceof Integer number) return number.longValue();
        if (value instanceof BigDecimal number) return number.longValueExact();
        throw new IllegalArgumentException("fx ACP id must be an integer");
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

    private FxAcpException rpcError(final Object value) {
        final Map<String, Object> error = object(value);
        final String message = string(error.get("message"));
        return new FxAcpException(message == null
            ? "fx ACP returned an error"
            : redact(message));
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
        if (lower.contains("bearer ") || containsCredentialAssignment(lower)
            || lower.contains("authorization:") || lower.contains("password=")) {
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
        return lower.contains("token=") || lower.contains("token:")
            || lower.contains("credential=") || lower.contains("credential:")
            || lower.contains("secret=") || lower.contains("secret:")
            || lower.contains("authorization=");
    }

    private String protocolFailureMessage() {
        final String hint = protocolFailureHint.get();
        final String preview = protocolFailurePreview.get();
        final StringBuilder message = new StringBuilder("fx ACP output is invalid");
        if (hint != null) message.append(": ").append(hint);
        if (preview != null) message.append("; first line: ").append(preview);
        return message.toString();
    }

    private String safeSessionId(final Object value) {
        final String sessionId = string(value);
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 512
            || sessionId.indexOf('\0') >= 0) {
            return null;
        }
        final String redacted = redact(sessionId);
        if (!redacted.equals(sessionId) || sessionId.regionMatches(
            true,
            0,
            "Bearer ",
            0,
            "Bearer ".length()
        )) {
            throw new IllegalArgumentException("fx ACP sessionId contained authorization material");
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
