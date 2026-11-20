package dev.turboism.plugin.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stdio-to-loopback relay for the Turboism MCP server. The class intentionally uses only
 * {@code java.base} and {@code java.net.http} so it can run on the bundled Cubism JRE, which
 * ships no {@code jdk.compiler} module. The plugin publishes this same source file for
 * single-file clients ({@code java TurboismMcpBridge.java}); the ACP path instead launches
 * the compiled class from the plugin JAR and passes the state directory as an argument.
 * Each stdin JSON-RPC line is posted to the published loopback endpoint with the persisted
 * bearer token, and each non-empty response line is written back to stdout. Mutating tools
 * stay token-gated while users never have to copy a credential themselves.
 *
 * <p>One {@link HttpClient} serves every frame so the transport keeps a single keep-alive
 * connection instead of a handshake per call. Session establishment stays synchronous, and
 * later frames run on a bounded daemon pool: a slow {@code tools/call} must not delay the
 * {@code notifications/cancelled} frame that stops it, nor a direct query queued behind it.
 * This file must stay pure US-ASCII: clients compile it with {@code java TurboismMcpBridge.java}
 * under whatever platform codepage the client process runs with.</p>
 */
public final class TurboismMcpBridge {

    private static final Path EMBEDDED_STATE_DIR = Path.of("__TURBOISM_STATE_DIR__");
    private static final Duration ENDPOINT_WAIT = Duration.ofSeconds(30);
    private static final long ENDPOINT_POLL_MILLIS = 250L;
    private static final String LOOPBACK_ENDPOINT_PATTERN = "http://127\\.0\\.0\\.1:\\d+/mcp";
    private static final String TOKEN_FILE = "mcp.token";
    private static final String CONNECTION_FILE = "mcp-connection.json";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(30);
    private static final int DISPATCH_THREADS = 4;
    private static final int DISPATCH_QUEUE_CAPACITY = 64;
    private static final int MAX_ATTEMPTS = 2;
    private static final int HTTP_OK = 200;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int AUTH_FAILURE_CODE = -32001;
    private static final int RELAY_FAILURE_CODE = -32000;
    private static final String SESSION_HEADER = "MCP-Session-Id";
    private static final String PROTOCOL_HEADER = "MCP-Protocol-Version";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String METHOD_INITIALIZE = "initialize";
    private static final String METHOD_INITIALIZED = "notifications/initialized";
    private static final String AUTH_FAILURE_MESSAGE =
            "Turboism MCP authentication failed (HTTP 401). Reconnect the stdio bridge;"
                    + " this is a credential failure, not a read-only tool limitation.";
    private static final String UNREACHABLE_MESSAGE = "Turboism MCP server is unreachable";

    private final Path stateDir;
    private final HttpClient client;
    private final ExecutorService dispatch;
    private final Object stdoutLock = new Object();
    private volatile PrintWriter stdout;
    private volatile String endpoint;
    private volatile String token;
    private volatile String sessionId;
    private volatile String protocolVersion;

    /**
     * Entry point launched by the MCP client. Relays stdin JSON-RPC frames to the loopback
     * endpoint until stdin closes.
     *
     * @param arguments optional first argument: the Turboism MCP plugin state directory;
     *     when absent the embedded publication-time path is used
     */
    public static void main(final String[] arguments) {
        try {
            new TurboismMcpBridge(stateDirOf(arguments)).run();
        } catch (Throwable failure) {
            // @containment-exempt: a bridge process must report every failure to stderr and
            // exit non-zero; there is no framework logger or fatal-error policy to delegate to.
            failure.printStackTrace(System.err);
            System.err.println("turboism-mcp-bridge: " + failure);
            System.exit(1);
        }
    }

    private static Path stateDirOf(final String[] arguments) {
        if (arguments != null && arguments.length > 0 && arguments[0] != null && !arguments[0].isBlank()) {
            return Path.of(arguments[0]);
        }
        return EMBEDDED_STATE_DIR;
    }

    private TurboismMcpBridge(final Path stateDir) throws IOException, InterruptedException {
        this.stateDir = stateDir;
        this.token = readToken();
        this.endpoint = awaitEndpoint();
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.dispatch = newDispatchPool();
    }

    private void run() throws IOException, InterruptedException {
        final PrintWriter output = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
        this.stdout = output;
        final BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line = stdin.readLine(); line != null; line = stdin.readLine()) {
            if (line.isBlank()) continue;
            submit(line);
        }
        drain();
        closeSession();
    }

    /**
     * Routes one stdin frame. Session-establishing frames run on the reader thread so every
     * later frame observes its session identity; all other frames run on the pool.
     */
    private void submit(final String line) {
        if (establishesSession(line)) {
            publish(relay(line));
            return;
        }
        try {
            dispatch.execute(() -> publish(relay(line)));
        } catch (RejectedExecutionException rejected) {
            // A closed pool means stdin already ended; the frame has no reader left to answer.
            System.err.println("turboism-mcp-bridge: frame dropped after shutdown");
        }
    }

    /**
     * Reports whether this frame must finish before the next frame starts. A batch may carry the
     * {@code initialize} request, so it stays on the reader thread as well.
     */
    private static boolean establishesSession(final String line) {
        if (line.strip().startsWith("[")) return true;
        final String method = jsonString(line, "method");
        return METHOD_INITIALIZE.equals(method) || METHOD_INITIALIZED.equals(method);
    }

    private void publish(final String response) {
        if (response == null) return;
        final PrintWriter output = stdout;
        if (output == null) return;
        synchronized (stdoutLock) {
            output.println(response);
        }
    }

    private String relay(final String line) {
        final boolean batch = line.strip().startsWith("[");
        final String id = batch ? null : jsonMember(line, "id");
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                final HttpResponse<String> response =
                        client.send(request(line), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                final int status = response.statusCode();
                final String body = response.body() == null ? "" : response.body();
                if (status == HTTP_UNAUTHORIZED) {
                    // Authentication is checked before dispatch, so this rejected request
                    // cannot have mutated the model. Retry once only if the persisted
                    // credential changed; never turn a write rejection into read-only mode.
                    if (attempt == 0 && refreshToken()) continue;
                    return id == null ? null : error(id, AUTH_FAILURE_CODE, AUTH_FAILURE_MESSAGE);
                }
                captureSession(response, line, status, body);
                if (status == HTTP_NOT_FOUND) {
                    sessionId = null;
                    protocolVersion = null;
                }
                if (!body.isEmpty()) return body;
                return id == null ? null : error(id, "MCP server returned HTTP " + status);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return id == null ? null : error(id, RELAY_FAILURE_CODE, "bridge relay was interrupted");
            } catch (IOException failure) {
                if (reachesEndpoint(failure)) {
                    // The listener may have restarted on a new port; republish before retrying.
                    refreshEndpoint();
                    continue;
                }
                // The wire error is fixed text; the exception detail (paths, class
                // names, endpoint internals) stays on stderr of the bridge process.
                System.err.println("turboism-mcp-bridge: relay failure: " + failure);
                return id == null ? null : error(id, RELAY_FAILURE_CODE, "bridge relay failure");
            } catch (IllegalArgumentException failure) {
                System.err.println("turboism-mcp-bridge: relay failure: " + failure);
                return id == null ? null : error(id, RELAY_FAILURE_CODE, "bridge relay failure");
            }
        }
        return id == null ? null : error(id, RELAY_FAILURE_CODE, UNREACHABLE_MESSAGE);
    }

    private HttpRequest request(final String line) {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header(AUTHORIZATION_HEADER, BEARER_PREFIX + token);
        final String currentSession = sessionId;
        if (currentSession != null) builder.header(SESSION_HEADER, currentSession);
        final String currentProtocol = protocolVersion;
        if (currentProtocol != null) builder.header(PROTOCOL_HEADER, currentProtocol);
        return builder.POST(HttpRequest.BodyPublishers.ofString(line, StandardCharsets.UTF_8))
                .build();
    }

    /**
     * Reports whether the failure means the listener did not answer at all, as opposed to a
     * rejected request. Only an unreachable endpoint justifies re-reading the published port.
     */
    private static boolean reachesEndpoint(final IOException failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof ConnectException || current instanceof HttpConnectTimeoutException) {
                return true;
            }
            if (current.getCause() == current) return false;
        }
        return false;
    }

    private void captureSession(
            final HttpResponse<String> response, final String request, final int status, final String body) {
        if (!METHOD_INITIALIZE.equals(jsonString(request, "method"))) return;
        if (status != HTTP_OK) return;
        final String header = response.headers().firstValue(SESSION_HEADER).orElse(null);
        if (header != null && !header.isBlank()) sessionId = header;
        final String result = jsonMember(body, "result");
        final String negotiated = result == null ? null : jsonString(result, "protocolVersion");
        if (negotiated != null) protocolVersion = negotiated;
    }

    private void closeSession() {
        final String current = sessionId;
        if (current == null) return;
        try {
            final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(CLOSE_TIMEOUT)
                    .header(AUTHORIZATION_HEADER, BEARER_PREFIX + token)
                    .header(SESSION_HEADER, current);
            final String negotiated = protocolVersion;
            if (negotiated != null) builder.header(PROTOCOL_HEADER, negotiated);
            client.send(builder.DELETE().build(), HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException | IllegalArgumentException ignored) {
            // Best effort: the editor may already be shutting down.
        }
    }

    /** Waits for every dispatched frame so no response is lost when stdin ends. */
    private void drain() throws InterruptedException {
        dispatch.shutdown();
        if (!dispatch.awaitTermination(DRAIN_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            dispatch.shutdownNow();
        }
    }

    /**
     * Builds the frame pool. A full queue runs the frame on the reader thread, which applies
     * backpressure to stdin instead of dropping a response or growing without a bound.
     */
    private static ExecutorService newDispatchPool() {
        final AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(
                DISPATCH_THREADS,
                DISPATCH_THREADS,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(DISPATCH_QUEUE_CAPACITY),
                task -> {
                    final Thread thread = new Thread(task, "turboism-mcp-bridge-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    private String readToken() throws IOException {
        return Files.readString(stateDir.resolve(TOKEN_FILE), StandardCharsets.UTF_8)
                .strip();
    }

    private boolean refreshToken() {
        try {
            final String current = readToken();
            if (current.isEmpty() || current.equals(token)) return false;
            token = current;
            return true;
        } catch (IOException unavailable) {
            return false;
        }
    }

    private String awaitEndpoint() throws InterruptedException {
        final long deadline = System.nanoTime() + ENDPOINT_WAIT.toNanos();
        for (; ; ) {
            final String found = readEndpoint();
            if (found != null) return found;
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("Turboism MCP connection file was not published under " + stateDir);
            }
            Thread.sleep(ENDPOINT_POLL_MILLIS);
        }
    }

    private void refreshEndpoint() {
        final String found = readEndpoint();
        if (found != null) endpoint = found;
    }

    private String readEndpoint() {
        try {
            final String endpoint =
                    jsonString(Files.readString(stateDir.resolve(CONNECTION_FILE), StandardCharsets.UTF_8), "endpoint");
            // Only a published loopback endpoint is ever followed.
            if (endpoint != null && endpoint.matches(LOOPBACK_ENDPOINT_PATTERN)) {
                return endpoint;
            }
            return null;
        } catch (IOException absent) {
            return null;
        }
    }

    /**
     * Returns the verbatim source token of the top-level member {@code name} in a
     * JSON object document, or {@code null} when the document is not an object or
     * the member is absent. The scan walks the document structurally - strings
     * honour backslash escapes and nested containers are skipped by depth - so a
     * same-named key inside a nested value cannot shadow the top-level member.
     */
    private static String jsonMember(final String json, final String name) {
        int index = skipWhitespace(json, 0);
        if (index >= json.length() || json.charAt(index) != '{') {
            return null;
        }
        index = skipWhitespace(json, index + 1);
        if (index < json.length() && json.charAt(index) == '}') {
            return null;
        }
        while (index < json.length()) {
            if (json.charAt(index) != '"') {
                return null;
            }
            final int keyStart = index;
            index = skipString(json, index);
            if (index < 0) {
                return null;
            }
            final String key = decodeString(json, keyStart, index);
            if (key == null) {
                return null;
            }
            index = skipWhitespace(json, index);
            if (index >= json.length() || json.charAt(index) != ':') {
                return null;
            }
            index = skipWhitespace(json, index + 1);
            final int valueStart = index;
            index = skipValue(json, index);
            if (index < 0) {
                return null;
            }
            if (key.equals(name)) {
                return json.substring(valueStart, index);
            }
            index = skipWhitespace(json, index);
            if (index >= json.length()) {
                return null;
            }
            final char separator = json.charAt(index++);
            if (separator == '}') {
                return null;
            }
            if (separator != ',') {
                return null;
            }
            index = skipWhitespace(json, index);
        }
        return null;
    }

    /** Returns the decoded string of the top-level member {@code name}, or {@code null}. */
    private static String jsonString(final String json, final String name) {
        final String token = jsonMember(json, name);
        if (token == null || !token.startsWith("\"") || token.length() < 2) {
            return null;
        }
        return decodeString(token, 0, token.length());
    }

    private static int skipWhitespace(final String json, final int start) {
        int index = start;
        while (index < json.length()) {
            final char c = json.charAt(index);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                break;
            }
            index++;
        }
        return index;
    }

    /** Returns the index after a complete string token, or -1 when unterminated. */
    private static int skipString(final String json, final int start) {
        for (int index = start + 1; index < json.length(); index++) {
            final char c = json.charAt(index);
            if (c == '\\') {
                index++;
            } else if (c == '"') {
                return index + 1;
            }
        }
        return -1;
    }

    /** Returns the index after a complete JSON value, or -1 when malformed. */
    private static int skipValue(final String json, final int start) {
        if (start >= json.length()) {
            return -1;
        }
        final char first = json.charAt(start);
        switch (first) {
            case '"':
                return skipString(json, start);
            case '{':
            case '[': {
                int depth = 0;
                for (int index = start; index < json.length(); index++) {
                    final char c = json.charAt(index);
                    if (c == '"') {
                        index = skipString(json, index) - 1;
                        if (index < -1) {
                            return -1;
                        }
                    } else if (c == '{' || c == '[') {
                        depth++;
                    } else if (c == '}' || c == ']') {
                        depth--;
                        if (depth == 0) {
                            return index + 1;
                        }
                    }
                }
                return -1;
            }
            default: {
                if (json.startsWith("true", start)) return start + 4;
                if (json.startsWith("false", start)) return start + 5;
                if (json.startsWith("null", start)) return start + 4;
                return skipNumber(json, start);
            }
        }
    }

    private static int skipNumber(final String json, final int start) {
        int index = start;
        if (index < json.length() && json.charAt(index) == '-') {
            index++;
        }
        index = skipDigits(json, index);
        if (index < 0) {
            return -1;
        }
        if (index < json.length() && json.charAt(index) == '.') {
            index = skipDigits(json, index + 1);
            if (index < 0) {
                return -1;
            }
        }
        if (index < json.length() && (json.charAt(index) == 'e' || json.charAt(index) == 'E')) {
            index++;
            if (index < json.length() && (json.charAt(index) == '+' || json.charAt(index) == '-')) {
                index++;
            }
            index = skipDigits(json, index);
            if (index < 0) {
                return -1;
            }
        }
        return index;
    }

    private static int skipDigits(final String json, final int start) {
        int index = start;
        while (index < json.length() && Character.isDigit(json.charAt(index))) {
            index++;
        }
        return index > start ? index : -1;
    }

    /**
     * Decodes the quoted JSON string token spanning {@code [start, end)}, honouring
     * every escape including surrogate pairs, or returns {@code null} when the
     * token is malformed.
     */
    private static String decodeString(final String json, final int start, final int end) {
        final StringBuilder out = new StringBuilder(end - start);
        int index = start + 1;
        final int last = end - 1;
        while (index < last) {
            final char c = json.charAt(index++);
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (index >= last) {
                return null;
            }
            final char escape = json.charAt(index++);
            switch (escape) {
                case '"':
                    out.append('"');
                    break;
                case '\\':
                    out.append('\\');
                    break;
                case '/':
                    out.append('/');
                    break;
                case 'b':
                    out.append('\b');
                    break;
                case 'f':
                    out.append('\f');
                    break;
                case 'n':
                    out.append('\n');
                    break;
                case 'r':
                    out.append('\r');
                    break;
                case 't':
                    out.append('\t');
                    break;
                case 'u': {
                    try {
                        final int code = Integer.parseInt(json.substring(index, index + 4), 16);
                        index += 4;
                        if (Character.isHighSurrogate((char) code)) {
                            if (index + 6 > last || json.charAt(index) != '\\' || json.charAt(index + 1) != 'u') {
                                return null;
                            }
                            final int low = Integer.parseInt(json.substring(index + 2, index + 6), 16);
                            index += 6;
                            if (!Character.isLowSurrogate((char) low)) {
                                return null;
                            }
                            out.append((char) code).append((char) low);
                        } else {
                            out.append((char) code);
                        }
                    } catch (NumberFormatException | IndexOutOfBoundsException malformed) {
                        return null;
                    }
                    break;
                }
                default:
                    return null;
            }
        }
        return out.toString();
    }

    private static String error(final String id, final String message) {
        return error(id, RELAY_FAILURE_CODE, message);
    }

    private static String error(final String id, final int code, final String message) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id
                + ",\"error\":{\"code\":" + code + ",\"message\":\""
                + escape(message) + "\"}}";
    }

    private static String escape(final String value) {
        final StringBuilder out = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            final char c = value.charAt(index);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }
}
