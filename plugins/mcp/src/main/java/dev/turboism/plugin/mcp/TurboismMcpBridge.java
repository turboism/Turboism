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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final Pattern ENDPOINT_PATTERN =
            Pattern.compile("\"endpoint\"\\s*:\\s*\"(http://127\\.0\\.0\\.1:\\d+/mcp)\"");
    private static final Pattern METHOD_PATTERN = Pattern.compile("\"method\"\\s*:\\s*\"([^\"\\\\]+)\"");
    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|-?\\d+)");
    private static final Pattern PROTOCOL_PATTERN = Pattern.compile("\"protocolVersion\"\\s*:\\s*\"([^\"\\\\]+)\"");

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
        final String id = batch ? null : extract(ID_PATTERN, line);
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
                return id == null ? null : error(id, "bridge relay failure: " + failure);
            }
        }
        return id == null ? null : error(id, "Turboism MCP server is unreachable");
    }

    private void captureSession(final HttpResponse<byte[]> response, final String request) {
        if (!"initialize".equals(extract(METHOD_PATTERN, request))) return;
        if (response.statusCode() != 200) return;
        response.headers().firstValue("MCP-Session-Id").ifPresent(value -> sessionId = value);
        final String negotiated = extract(PROTOCOL_PATTERN, new String(response.body(), StandardCharsets.UTF_8));
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
            return extract(
                    ENDPOINT_PATTERN,
                    Files.readString(stateDir.resolve("mcp-connection.json"), StandardCharsets.UTF_8));
        } catch (IOException absent) {
            return null;
        }
    }

    private static String extract(final Pattern pattern, final String text) {
        final Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
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
                    out.append(c);
            }
        }
        return out.toString();
    }
}
