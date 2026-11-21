package dev.turboism.plugin.acp;

import dev.turboism.sdk.json.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bundled native provider adapters. Their events use ACP's existing internal client boundary;
 * installed CLIs are launched with their own protocols, without external ACP packages or Node SDKs.
 */
final class NativeAgentTransport implements AcpTransport {
    @FunctionalInterface
    interface ProcessStarter {
        AcpTransport start(AgentLaunchSpec launch) throws IOException;
    }

    /** Line terminator of the JSONL protocol, also the frame boundary the writer scans for. */
    private static final int NEWLINE = '\n';
    /**
     * Upper bound for one buffered request frame. UTF-8 can expand a character to four bytes, and
     * the limit must cover the full JSON-RPC envelope that wraps the largest accepted line.
     */
    private static final int MAX_BUFFERED_FRAME_BYTES = AcpClient.MAX_ACP_LINE_CHARS * 4;
    /** Frames the reader may lag behind before the producer blocks instead of dropping output. */
    private static final int MESSAGE_QUEUE_CAPACITY = 256;
    /** Bounded wait for queue space; a stalled consumer must not block the producer forever. */
    private static final long MESSAGE_QUEUE_OFFER_MILLIS = 30_000L;

    private final AgentLaunchSpec launch;
    private final ProcessStarter starter;
    private final MessageStream output = new MessageStream();
    private final MessageStream errors = new MessageStream();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong ids = new AtomicLong();
    private final java.util.concurrent.ExecutorService requests = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(64), work -> {
                final Thread thread = new Thread(work, "turboism-native-agent-requests");
                thread.setDaemon(true);
                return thread;
            });

    private record PendingPermission(
            CompletableFuture<?> scope, java.util.function.Consumer<Map<String, Object>> answer) {}

    private final Map<String, PendingPermission> permissions = new ConcurrentHashMap<>();
    private final OutputStream input = new OutputStream() {
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        @Override
        public synchronized void write(final int value) throws IOException {
            // The separator ends a frame; it is never part of the parsed payload.
            if (value == NEWLINE) dispatchLine();
            else appendByte(value);
        }

        /**
         * Buffers a caller chunk in bulk and dispatches every complete line it carries.
         *
         * <p>A prompt can embed a multi-megabyte Base64 image, so handling one byte per call would
         * pay a monitor re-entry and a bounds check for each byte. The byte scan keeps the cost
         * proportional to the chunk and still enforces the per-frame size limit.</p>
         */
        @Override
        public synchronized void write(final byte[] bytes, final int offset, final int length) throws IOException {
            java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
            if (closed.get()) throw new IOException("Native provider is closed");
            int start = offset;
            final int end = offset + length;
            while (start < end) {
                final int separator = indexOf(bytes, start, end, NEWLINE);
                if (separator < 0) {
                    appendBytes(bytes, start, end - start);
                    return;
                }
                appendBytes(bytes, start, separator - start);
                dispatchLine();
                start = separator + 1;
            }
        }

        private void appendByte(final int value) throws IOException {
            if (closed.get()) throw new IOException("Native provider is closed");
            if (line.size() >= MAX_BUFFERED_FRAME_BYTES) throw new IOException("Native request exceeds size limit");
            line.write(value);
        }

        private void appendBytes(final byte[] bytes, final int offset, final int length) throws IOException {
            if (closed.get()) throw new IOException("Native provider is closed");
            if (length > MAX_BUFFERED_FRAME_BYTES - line.size()) {
                throw new IOException("Native request exceeds size limit");
            }
            line.write(bytes, offset, length);
        }

        /** Parses one complete JSONL frame and hands it to the dispatch queue. */
        private void dispatchLine() throws IOException {
            final var message = object(Json.parseObject(line.toByteArray()));
            line.reset();
            try {
                requests.execute(() -> dispatch(message));
            } catch (java.util.concurrent.RejectedExecutionException failure) {
                throw new IOException("Native request queue is full or closed", failure);
            }
        }
    };
    private volatile NativeAgentProvider provider;
    private volatile String sessionId;
    private Path temporaryDirectory;

    NativeAgentTransport(final AgentLaunchSpec launch) {
        this(launch, AcpProcessTransport::start);
    }

    NativeAgentTransport(final AgentLaunchSpec launch, final ProcessStarter starter) {
        this.launch = launch;
        this.starter = starter;
    }

    private void dispatch(final Map<String, Object> request) {
        final Object id = request.get("id");
        try {
            if (!request.containsKey("method")) {
                final var callback = permissions.remove(String.valueOf(id));
                if (callback != null) callback.answer().accept(object(request.get("result")));
                return;
            }
            final String method = String.valueOf(request.get("method"));
            final var params = object(request.get("params"));
            switch (method) {
                case "initialize" ->
                    reply(
                            id,
                            Map.of(
                                    "protocolVersion",
                                    1L,
                                    "agentInfo",
                                    Map.of("name", displayName(), "title", displayName(), "version", "native"),
                                    "agentCapabilities",
                                    Map.of(
                                            "sessionCapabilities",
                                            Map.of("resume", Map.of()),
                                            "mcpCapabilities",
                                            Map.of("http", true),
                                            "promptCapabilities",
                                            Map.of("image", true, "embeddedContext", true))));
                case "session/new", "session/resume" -> {
                    stopProvider();
                    sessionId = "session/resume".equals(method)
                            ? text(params.get("sessionId"))
                            : UUID.randomUUID().toString();
                    if (!sessionId.matches("[a-zA-Z0-9_-]{1,128}")) throw new IOException("Invalid native session ID");
                    Files.createDirectories(launch.workingDirectory());
                    temporaryDirectory = Files.createTempDirectory(launch.workingDirectory(), "native-agent-");
                    provider = switch (launch.protocol()) {
                        case CODEX -> new NativeCodexProvider(this);
                        case CLAUDE -> new NativeClaudeProvider(this);
                        case PI -> new NativePiProvider(this);
                        default -> throw new IOException("Unsupported native provider");
                    };
                    try {
                        provider.start(params);
                        if (closed.get()) throw new IOException("Native provider closed during startup");
                    } catch (Exception failure) {
                        stopProvider();
                        throw failure;
                    }
                    reply(id, Map.of("sessionId", sessionId, "configOptions", provider.configOptions()));
                }
                case "session/prompt" -> {
                    requireSession(params);
                    provider.prompt(list(params.get("prompt"))).whenComplete((reason, failure) -> {
                        if (failure == null) reply(id, Map.of("stopReason", reason));
                        else error(id, failure);
                    });
                }
                case "session/set_config_option" -> {
                    requireSession(params);
                    provider.configure(text(params.get("configId")), text(params.get("value")));
                    reply(id, Map.of("configOptions", provider.configOptions()));
                }
                case "session/cancel" -> {
                    requireSession(params);
                    provider.cancel();
                }
                default -> error(id, new IOException("This native provider does not support " + method));
            }
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            error(id, failure);
        }
    }

    private void requireSession(final Map<String, Object> params) throws IOException {
        if (provider == null || !java.util.Objects.equals(sessionId, params.get("sessionId")))
            throw new IOException("Unknown native session");
    }

    synchronized NativeJsonlConnection startProcess(
            final NativeAgentProvider owner,
            final List<String> arguments,
            final Map<String, String> environment,
            final java.util.function.Consumer<Map<String, Object>> events)
            throws IOException {
        if (closed.get() || provider != owner) throw new IOException("Native session is closed");
        final var argv = new ArrayList<String>(launch.command());
        argv.addAll(arguments);
        final var env = new LinkedHashMap<String, String>(launch.environment());
        env.putAll(environment);
        final var process = starter.start(new AgentLaunchSpec(argv, launch.workingDirectory(), env));
        return new NativeJsonlConnection(
                process,
                event -> {
                    if (!closed.get() && provider == owner) events.accept(event);
                },
                bytes -> {
                    if (!closed.get() && provider == owner) errors.offer(bytes);
                },
                owner::failed);
    }

    AgentLaunchSpec launch() {
        return launch;
    }

    Path directory() {
        return temporaryDirectory;
    }

    Path sessionDirectory() throws IOException {
        final Path path = launch.workingDirectory()
                .resolve("native-sessions")
                .resolve(launch.protocol().name().toLowerCase(java.util.Locale.ROOT));
        Files.createDirectories(path);
        return path;
    }

    void sessionId(final String id) {
        sessionId = id;
    }

    String sessionId() {
        return sessionId;
    }

    long nextId() {
        return ids.incrementAndGet();
    }

    void update(final Map<String, Object> update) {
        emit(Map.of(
                "jsonrpc",
                "2.0",
                "method",
                "session/update",
                "params",
                Map.of("sessionId", sessionId, "update", update)));
    }

    void text(final String kind, final String text) {
        if (!text.isEmpty()) update(Map.of("sessionUpdate", kind, "content", Map.of("type", "text", "text", text)));
    }

    void image(final String kind, final String mimeType, final String data) {
        if (mimeType != null && mimeType.startsWith("image/") && data != null && !data.isEmpty()) {
            update(Map.of(
                    "sessionUpdate", kind, "content", Map.of("type", "image", "mimeType", mimeType, "data", data)));
        }
    }

    void plan(final List<?> entries) {
        update(Map.of("sessionUpdate", "plan", "entries", entries));
    }

    void usage(final long outputTokens) {
        if (outputTokens > 0L) {
            update(Map.of("sessionUpdate", "usage_update", "outputTokens", outputTokens));
        }
    }

    void permission(
            final CompletableFuture<?> scope,
            final String title,
            final String kind,
            final String callId,
            final Object details,
            final java.util.function.Consumer<Boolean> decision) {
        if (scope.isDone() || permissions.size() >= 8) {
            decision.accept(false);
            return;
        }
        final String id = "native-permission-" + nextId();
        final var permission = new PendingPermission(scope, result -> {
            final var outcome = object(result.get("outcome"));
            final Object selectedOption = outcome.get("optionId");
            decision.accept("selected".equals(outcome.get("outcome"))
                    && ("allow".equals(selectedOption) || "allow_always".equals(selectedOption)));
        });
        permissions.put(id, permission);
        scope.whenComplete((result, failure) -> retirePermission(id, permission));
        if (!permissions.containsKey(id)) return;
        emit(Map.of(
                "jsonrpc",
                "2.0",
                "id",
                id,
                "method",
                "session/request_permission",
                "params",
                Map.of(
                        "sessionId",
                        sessionId,
                        "toolCall",
                        Map.of("title", title, "kind", kind, "toolCallId", callId, "rawInput", details),
                        "options",
                        List.of(
                                Map.of("optionId", "allow", "name", "Allow once", "kind", "allow_once"),
                                Map.of(
                                        "optionId",
                                        "allow_always",
                                        "name",
                                        "Allow for this session",
                                        "kind",
                                        "allow_always"),
                                Map.of("optionId", "reject", "name", "Reject", "kind", "reject_once")))));
    }

    void question(
            final CompletableFuture<?> scope,
            final String title,
            final List<AcpQuestion.Item> items,
            final java.util.function.Consumer<AcpQuestion.Response> callback) {
        if (scope.isDone() || items == null || items.isEmpty() || permissions.size() >= 8) {
            callback.accept(AcpQuestion.Response.CANCELLED);
            return;
        }
        final String id = "native-question-" + nextId();
        final var pending =
                new PendingPermission(scope, result -> callback.accept(AcpQuestion.fromResponseResult(result)));
        permissions.put(id, pending);
        scope.whenComplete((result, failure) -> retirePermission(id, pending));
        if (!permissions.containsKey(id)) return;
        emit(Map.of(
                "jsonrpc",
                "2.0",
                "id",
                id,
                "method",
                AcpQuestion.REQUEST_METHOD,
                "params",
                AcpQuestion.toRequestParams(sessionId, title, items)));
    }

    void cancelPermissions(final CompletableFuture<?> scope) {
        permissions.forEach((id, permission) -> {
            if (permission.scope() == scope) retirePermission(id, permission);
        });
    }

    private void retirePermission(final String id, final PendingPermission permission) {
        if (permissions.remove(id, permission)) permission.answer().accept(Map.of());
    }

    void reply(final Object id, final Object result) {
        if (id != null) emit(Map.of("jsonrpc", "2.0", "id", id, "result", result));
    }

    void error(final Object id, final Throwable failure) {
        if (id == null) return;
        emit(Map.of(
                "jsonrpc",
                "2.0",
                "id",
                id,
                "error",
                Map.of("code", -32603L, "message", AcpClient.failureDetails(unwrap(failure)))));
    }

    private void emit(final Map<String, Object> message) {
        if (!closed.get()) output.offer((Json.stringify(message) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private String displayName() {
        return switch (launch.protocol()) {
            case CODEX -> "Codex";
            case CLAUDE -> "Claude Code";
            case PI -> "Pi";
            default -> "Agent";
        };
    }

    private synchronized void stopProvider() {
        permissions.forEach(this::retirePermission);
        final var previous = provider;
        provider = null;
        if (previous != null) previous.close();
        if (temporaryDirectory != null) {
            try (var paths = Files.walk(temporaryDirectory)) {
                for (Path path :
                        paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (IOException ignored) {
                /* Best-effort cleanup of task-local connection files. */
            }
            temporaryDirectory = null;
        }
    }

    @Override
    public InputStream stdout() {
        return output;
    }

    @Override
    public InputStream stderr() {
        return errors;
    }

    @Override
    public OutputStream stdin() {
        return input;
    }

    @Override
    public boolean isAlive() {
        return !closed.get();
    }

    @Override
    public void terminate(final Duration grace) {
        close();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        requests.shutdownNow();
        stopProvider();
        output.close();
        errors.close();
    }

    /** Returns the index of {@code target} in {@code bytes[start, end)}, or -1 when absent. */
    private static int indexOf(final byte[] bytes, final int start, final int end, final int target) {
        for (int index = start; index < end; index++) {
            if ((bytes[index] & 0xff) == target) return index;
        }
        return -1;
    }

    static Map<String, Object> object(final Object value) {
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?> map))
            throw new IllegalArgumentException("Native protocol value must be an object");
        @SuppressWarnings("unchecked")
        final var checked = (Map<String, Object>) map;
        return checked;
    }

    static List<Object> list(final Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> values))
            throw new IllegalArgumentException("Native protocol value must be an array");
        return new ArrayList<>(values);
    }

    static String jsonValue(final Object value) {
        if (value instanceof Map<?, ?>) return Json.stringify(object(value));
        if (value instanceof List<?>) return Json.stringify(list(value));
        return String.valueOf(value);
    }

    static String text(final Object value) {
        return value instanceof String string ? string : "";
    }

    static Throwable unwrap(final Throwable value) {
        return (value instanceof java.util.concurrent.ExecutionException
                                || value instanceof java.util.concurrent.CompletionException)
                        && value.getCause() != null
                ? unwrap(value.getCause())
                : value;
    }

    static <T> T await(final CompletableFuture<T> value) throws Exception {
        return value.get(15, TimeUnit.SECONDS);
    }

    /** Bounded pipe without PipedInputStream's last-writer-thread lifetime coupling. */
    private static final class MessageStream extends InputStream {
        private final java.util.concurrent.BlockingQueue<byte[]> queue =
                new java.util.concurrent.ArrayBlockingQueue<>(MESSAGE_QUEUE_CAPACITY);
        private volatile boolean ended;
        private byte[] current = new byte[0];
        private int position;

        /**
         * Queues one frame, blocking while the reader is behind.
         *
         * <p>A brief consumer stall is normal during paint or a large image decode, so the producer
         * waits instead of abandoning the session. The wait stays bounded and reports the loss
         * explicitly: memory remains capped, and a silent loss cannot look like a protocol error.</p>
         */
        void offer(final byte[] bytes) {
            if (ended) return;
            try {
                if (queue.offer(bytes, MESSAGE_QUEUE_OFFER_MILLIS, TimeUnit.MILLISECONDS)) return;
                System.getLogger(NativeAgentTransport.class.getName())
                        .log(
                                System.Logger.Level.WARNING,
                                "Native agent reader stalled for {0} ms; closing the message stream",
                                MESSAGE_QUEUE_OFFER_MILLIS);
                close();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                close();
            }
        }

        @Override
        public int read() throws IOException {
            while (position >= current.length) {
                if (ended && queue.isEmpty()) return -1;
                try {
                    current = queue.take();
                    position = 0;
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IOException(failure);
                }
            }
            return current[position++] & 0xff;
        }

        @Override
        public int read(final byte[] bytes, final int offset, final int length) throws IOException {
            if (length == 0) return 0;
            final int first = read();
            if (first < 0) return -1;
            bytes[offset] = (byte) first;
            final int size = Math.min(length - 1, current.length - position);
            System.arraycopy(current, position, bytes, offset + 1, size);
            position += size;
            return size + 1;
        }

        @Override
        public void close() {
            ended = true;
            queue.clear();
            queue.offer(new byte[0]);
        }
    }
}
