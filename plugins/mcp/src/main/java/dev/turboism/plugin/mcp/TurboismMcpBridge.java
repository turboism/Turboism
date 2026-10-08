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

/**
 * Stdio-to-loopback relay for the Turboism MCP server. The class intentionally uses only
 * {@code java.base} and {@code java.net.http} so it can run on the bundled Cubism JRE, which
 * ships no {@code jdk.compiler} module. The plugin publishes this same source file for
 * single-file clients ({@code java TurboismMcpBridge.java}); the ACP path instead launches
 * the compiled class from the plugin JAR and passes the state directory as an argument.
 * Each stdin JSON-RPC line is posted to the published loopback endpoint with the persisted
 * bearer token, and each non-empty response line is written back to stdout. Mutating tools
 * stay token-gated while users never have to copy a credential themselves.
 */
public final class TurboismMcpBridge {

    private static final Path EMBEDDED_STATE_DIR = Path.of("__TURBOISM_STATE_DIR__");
    private static final Duration ENDPOINT_WAIT = Duration.ofSeconds(30);

    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Path stateDir;
    private String endpoint;
    private String token;
    private String sessionId;
    private String protocolVersion;

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
            System.err.println("turboism-mcp-bridge: " + failure);
            System.exit(1);
        }
    }

    private static Path stateDirOf(final String[] arguments) {
        if (arguments != null && arguments.length > 0 && !arguments[0].isBlank()) {
            return Path.of(arguments[0]);
        }
        return EMBEDDED_STATE_DIR;
    }

    private TurboismMcpBridge(final Path stateDir) throws IOException, InterruptedException {
        this.stateDir = stateDir;
        this.token = readToken();
        this.endpoint = awaitEndpoint();
    }

    private void run() throws IOException, InterruptedException {
        final PrintWriter stdout = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
        final BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line = stdin.readLine(); line != null; line = stdin.readLine()) {
            if (line.isBlank()) continue;
            final String response = relay(line);
            if (response != null) stdout.println(response);
        }
        closeSession();
    }

    private String relay(final String line) {
        final boolean batch = line.strip().startsWith("[");
        final String id = batch ? null : jsonMember(line, "id");
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                        .timeout(Duration.ofSeconds(120))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.ofString(line, StandardCharsets.UTF_8));
                if (sessionId != null) builder.header("MCP-Session-Id", sessionId);
                if (protocolVersion != null) {
                    builder.header("MCP-Protocol-Version", protocolVersion);
                }
                final HttpResponse<byte[]> response =
                        client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
                captureSession(response, line);
                if (response.statusCode() == 404) {
                    sessionId = null;
                    protocolVersion = null;
                }
                final String body = new String(response.body(), StandardCharsets.UTF_8);
                if (!body.isEmpty()) return body;
                return id == null ? null : error(id, "MCP server returned HTTP " + response.statusCode());
            } catch (ConnectException | HttpConnectTimeoutException unreachable) {
                refreshEndpoint();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return id == null ? null : error(id, "bridge relay was interrupted");
            } catch (IOException | IllegalArgumentException failure) {
                // The wire error is fixed text; the exception detail (paths, class
                // names, endpoint internals) stays on stderr of the bridge process.
                System.err.println("turboism-mcp-bridge: relay failure: " + failure);
                return id == null ? null : error(id, "bridge relay failure");
            }
        }
        return id == null ? null : error(id, "Turboism MCP server is unreachable");
    }

    private void captureSession(final HttpResponse<byte[]> response, final String request) {
        if (!"initialize".equals(jsonString(request, "method"))) return;
        if (response.statusCode() != 200) return;
        response.headers().firstValue("MCP-Session-Id").ifPresent(value -> sessionId = value);
        final String result = jsonMember(new String(response.body(), StandardCharsets.UTF_8), "result");
        final String negotiated = result == null ? null : jsonString(result, "protocolVersion");
        if (negotiated != null) protocolVersion = negotiated;
    }

    private void closeSession() {
        if (sessionId == null) return;
        try {
            final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + token)
                    .header("MCP-Session-Id", sessionId)
                    .method("DELETE", HttpRequest.BodyPublishers.noBody());
            if (protocolVersion != null) {
                builder.header("MCP-Protocol-Version", protocolVersion);
            }
            client.send(builder.build(), HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // Best effort: the editor may already be shutting down.
        }
    }

    private String readToken() throws IOException {
        return Files.readString(stateDir.resolve("mcp.token"), StandardCharsets.UTF_8)
                .strip();
    }

    private String awaitEndpoint() throws InterruptedException {
        final long deadline = System.nanoTime() + ENDPOINT_WAIT.toNanos();
        for (; ; ) {
            final String found = readEndpoint();
            if (found != null) return found;
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("Turboism MCP connection file was not published under " + stateDir);
            }
            Thread.sleep(250);
        }
    }

    private void refreshEndpoint() {
        final String found = readEndpoint();
        if (found != null) endpoint = found;
    }

    private String readEndpoint() {
        try {
            final String endpoint = jsonString(
                    Files.readString(stateDir.resolve("mcp-connection.json"), StandardCharsets.UTF_8), "endpoint");
            // Only a published loopback endpoint is ever followed.
            if (endpoint != null && endpoint.matches("http://127\\.0\\.0\\.1:\\d+/mcp")) {
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
     * the member is absent. The scan walks the document structurally — strings
     * honour backslash escapes and nested containers are skipped by depth — so a
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
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id
                + ",\"error\":{\"code\":-32000,\"message\":\""
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
