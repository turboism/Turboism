package acpagent;

import dev.turboism.sdk.json.Json;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * One bridge subprocess speaking line-delimited JSON-RPC over stdio, as published by the MCP
 * plugin's {@code McpStdioLaunch} descriptor.
 *
 * <p>Requests are written one per line; responses are read back one per line. Notifications
 * produce no response line, mirroring the bridge's relay behavior for bodiless HTTP responses.</p>
 */
final class BridgeClient implements AutoCloseable {

    private final Process process;
    private final Writer stdin;
    private final BufferedReader stdout;
    private final StringBuilder stderrBuffer = new StringBuilder();
    private final Thread stderrPump;
    private long nextId = 1;

    private BridgeClient(final Process process) {
        this.process = process;
        this.stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        this.stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        this.stderrPump = new Thread(this::pumpStderr, "turboism-acp-fake-bridge-stderr");
        this.stderrPump.setDaemon(true);
        this.stderrPump.start();
    }

    static BridgeClient start(final List<String> command, final List<String> args) throws IOException {
        final ArrayList<String> argv = new ArrayList<>(command);
        argv.addAll(args);
        final ProcessBuilder builder = new ProcessBuilder(argv);
        builder.redirectErrorStream(false);
        return new BridgeClient(builder.start());
    }

    /** Sends one JSON-RPC request and returns the decoded {@code result} member. */
    Map<String, Object> request(final String method, final Map<String, Object> params, final long timeoutSeconds)
            throws IOException {
        final long id = nextId++;
        final LinkedHashMap<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.put("params", params);
        send(Json.stringify(message));
        final long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        while (System.currentTimeMillis() < deadline) {
            final String line = stdout.readLine();
            if (line == null) throw new IOException("bridge closed stdout before responding to " + method);
            final Map<String, ?> response = parseLine(line);
            if (response == null) continue;
            final Object responseId = response.get("id");
            if (responseId instanceof Number number
                    ? number.longValue() != id
                    : !String.valueOf(responseId).equals(String.valueOf(id))) {
                continue;
            }
            if (response.containsKey("error")) {
                throw new IOException("bridge request " + method + " failed: " + Json.stringify(response));
            }
            final Object result = response.get("result");
            if (result instanceof Map<?, ?> map) {
                final LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
                map.forEach((key, value) -> copy.put(String.valueOf(key), value));
                return copy;
            }
            return new LinkedHashMap<>();
        }
        throw new IOException("bridge request " + method + " timed out after " + timeoutSeconds + "s");
    }

    /** Sends one JSON-RPC notification; the bridge produces no response line for it. */
    void notify(final String method, final Map<String, Object> params) throws IOException {
        final LinkedHashMap<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        message.put("params", params);
        send(Json.stringify(message));
    }

    String stderrText() {
        synchronized (stderrBuffer) {
            return stderrBuffer.toString();
        }
    }

    /** Closes stdin so the bridge relays EOF and deletes its MCP session, then waits for exit. */
    Integer closeAndWait(final long timeoutSeconds) {
        try {
            stdin.close();
        } catch (IOException ignored) {
            // The bridge may already be gone.
        }
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return process.exitValue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    @Override
    public void close() {
        closeAndWait(30L);
    }

    private void send(final String line) throws IOException {
        synchronized (stdin) {
            stdin.write(line);
            stdin.write('\n');
            stdin.flush();
        }
    }

    private Map<String, ?> parseLine(final String line) {
        final String text = line.strip();
        if (text.isEmpty()) return null;
        try {
            return Json.parseObject(text);
        } catch (IllegalArgumentException incomplete) {
            return null;
        }
    }

    private void pumpStderr() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                synchronized (stderrBuffer) {
                    if (stderrBuffer.length() < 8_000) stderrBuffer.append(line).append('\n');
                }
            }
        } catch (IOException ignored) {
            // Streams close with the process.
        }
    }
}
