package acpagent;

import dev.turboism.sdk.json.Json;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scripted ACP agent for the {@code acp} exact-host validation capability.
 *
 * <p>On the ACP side it answers the minimal v1 handshake, asserts that {@code session/new}
 * carries exactly one credential-free stdio MCP server, then drives that bridge end to end:
 * initialize, tool discovery, one {@code rename} mutation through
 * {@code turboism.model_objects.apply}, resource readback, guarded history undo, restored
 * readback, and a balanced history position. Every step lands in the assertion ledger that the
 * probe and the runner judge.</p>
 */
public final class FakeAgent {

    private final Scenario scenario;
    private final Ledger ledger = new Ledger();
    private Map<String, Object> stdioServer;
    private boolean scenarioStarted;

    private FakeAgent(final Scenario scenario) {
        this.scenario = scenario;
    }

    /** Runs the agent against stdio; returns only after stdin EOF. */
    public static void main(final String[] args) throws Exception {
        final FakeAgent agent = new FakeAgent(Scenario.load());
        agent.serve(System.in, System.out);
    }

    private void serve(final InputStream input, final java.io.OutputStream output) {
        final Writer stdout = new OutputStreamWriter(output, StandardCharsets.UTF_8);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.isBlank()) continue;
                try {
                    handle(line, stdout);
                } catch (RuntimeException failure) {
                    System.err.println("acp-fake-agent: " + failure);
                }
            }
        } catch (IOException failure) {
            System.err.println("acp-fake-agent: stdin failed: " + failure);
        }
    }

    private synchronized void handle(final String line, final Writer stdout) throws IOException {
        final Map<String, ?> message;
        try {
            message = Json.parseObject(line);
        } catch (IllegalArgumentException malformed) {
            return;
        }
        final Object id = message.get("id");
        final Object method = message.get("method");
        if (!(method instanceof String name)) return;
        if (id == null) return;
        final Map<String, Object> params = objectValue(message.get("params"));
        final Map<String, Object> result = switch (name) {
            case "initialize" -> initializeResult();
            case "session/new" -> newSessionResult(params);
            default -> new LinkedHashMap<>();
        };
        final LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        sendLine(stdout, Json.stringify(response));
    }

    private Map<String, Object> initializeResult() {
        final LinkedHashMap<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("loadSession", false);
        final LinkedHashMap<String, Object> agentInfo = new LinkedHashMap<>();
        agentInfo.put("name", "acp-fake-agent");
        agentInfo.put("title", "ACP Fake Validation Agent");
        agentInfo.put("version", "0.0.0");
        final LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", 1L);
        result.put("agentCapabilities", capabilities);
        result.put("agentInfo", agentInfo);
        return result;
    }

    private Map<String, Object> newSessionResult(final Map<String, Object> params) {
        assertStdioAttachment(params);
        final LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", "fake-session-1");
        if (scenarioStarted) return result;
        scenarioStarted = true;
        final Thread scenarioThread = new Thread(this::runScenario, "turboism-acp-fake-scenario");
        scenarioThread.setDaemon(true);
        scenarioThread.start();
        return result;
    }

    private void assertStdioAttachment(final Map<String, Object> params) {
        final List<Object> servers = listValue(params.get("mcpServers"));
        if (servers.size() != 1) {
            ledger.fail("mcpServersStdio", "expected_exactly_one_mcp_server_got_" + servers.size());
            return;
        }
        final Map<String, Object> server = objectValue(servers.get(0));
        if (!"turboism".equals(server.get("name"))) {
            ledger.fail("mcpServersStdio", "server_name_is_not_turboism");
            return;
        }
        if (!(server.get("command") instanceof String command) || command.isBlank()) {
            ledger.fail("mcpServersStdio", "missing_stdio_command");
            return;
        }
        final List<Object> args = listValue(server.get("args"));
        if (!(server.get("env") instanceof List<?> env) || !env.isEmpty()) {
            ledger.fail("mcpServersStdio", "stdio_env_is_not_empty");
            return;
        }
        final LinkedHashMap<String, Object> captured = new LinkedHashMap<>();
        captured.put("command", command);
        captured.put("args", new ArrayList<>(args));
        stdioServer = captured;
        ledger.pass("mcpServersStdio");
        assertPayloadCredentialFree(captured);
    }

    private void assertPayloadCredentialFree(final Map<String, Object> server) {
        final String tokenText = readTokenText();
        if (tokenText == null || tokenText.isBlank()) {
            ledger.fail("payloadCredentialFree", "token_file_unreadable_for_negative_check");
            return;
        }
        final String payload = Json.stringify(server);
        if (payload.contains(tokenText) || payload.contains("Authorization")) {
            ledger.fail("payloadCredentialFree", "payload_contains_token_material");
            return;
        }
        ledger.pass("payloadCredentialFree");
    }

    private String readTokenText() {
        try {
            return Files.readString(scenario.tokenFile, StandardCharsets.UTF_8).strip();
        } catch (IOException unreadable) {
            return null;
        }
    }

    private void runScenario() {
        try {
            driveBridge();
        } catch (RuntimeException | IOException failure) {
            ledger.fail("bridgeMcpInitialize", "scenario_error_" + failure.getClass().getSimpleName());
        }
        try {
            ledger.write(scenario.resultFile, scenario.runId);
        } catch (IOException failure) {
            System.err.println("acp-fake-agent: could not write result file " + scenario.resultFile + ": " + failure);
        }
    }

    private void driveBridge() throws IOException {
        if (stdioServer == null) {
            ledger.fail("bridgeMcpInitialize", "no_stdio_server_was_attached");
            return;
        }
        final List<String> command = List.of(String.valueOf(stdioServer.get("command")));
        final List<String> args = stringList(stdioServer.get("args"));
        final long timeout = scenario.stepTimeoutSeconds;
        try (BridgeClient bridge = BridgeClient.start(command, args)) {
            final LinkedHashMap<String, Object> initializeParams = new LinkedHashMap<>();
            initializeParams.put("protocolVersion", "2025-06-18");
            initializeParams.put("capabilities", new LinkedHashMap<String, Object>());
            initializeParams.put("clientInfo", Map.of("name", "acp-fake-agent", "version", "0.0.0"));
            final Map<String, Object> initialized;
            try {
                initialized = bridge.request("initialize", initializeParams, timeout);
            } catch (IOException failure) {
                ledger.fail("bridgeMcpInitialize", describe("initialize", failure, bridge));
                return;
            }
            if (String.valueOf(initialized.get("protocolVersion")).isBlank()) {
                ledger.fail("bridgeMcpInitialize", "initialize_missing_protocol_version");
                return;
            }
            ledger.pass("bridgeMcpInitialize");
            bridge.notify("notifications/initialized", new LinkedHashMap<>());

            final Map<String, Object> tools;
            try {
                tools = bridge.request("tools/list", new LinkedHashMap<>(), timeout);
            } catch (IOException failure) {
                ledger.fail("toolsListHasApply", "tools_list_failed");
                return;
            }
            if (!containsTool(tools, "turboism.model_objects.apply")) {
                ledger.fail("toolsListHasApply", "apply_tool_missing");
                return;
            }
            ledger.pass("toolsListHasApply");

            final Map<String, Object> historyBefore;
            try {
                historyBefore = structured(bridge.request("tools/call", toolCall("turboism.history.read", Map.of()), timeout));
            } catch (IOException failure) {
                failRenameChain("history_read_before_failed");
                return;
            }
            final long positionBefore = number(historyBefore.get("position"), -1L);
            if (positionBefore < 0) {
                failRenameChain("history_position_unavailable");
                return;
            }
            ledger.data("historyPositionBefore", Long.toString(positionBefore));

            final Map<String, Object> overview = readOverview(bridge, timeout);
            if (overview == null) {
                failRenameChain("overview_read_failed");
                return;
            }
            final Map<String, Object> target = selectTarget(overview);
            if (target == null) {
                failRenameChain("no_model_object_found");
                return;
            }
            final String id = String.valueOf(target.get("id"));
            final String kind = String.valueOf(target.get("kind"));
            final String originalName = String.valueOf(target.get("name"));
            final String newName = scenario.newName();

            final Map<String, Object> rename;
            try {
                rename = structured(bridge.request(
                        "tools/call",
                        toolCall(
                                "turboism.model_objects.apply",
                                Map.of("operations", List.of(Map.of(
                                        "operation", "rename",
                                        "kind", kind,
                                        "id", id,
                                        "name", newName)))),
                        timeout));
            } catch (IOException failure) {
                failRenameChain("rename_call_failed");
                return;
            }
            if (!Boolean.TRUE.equals(rename.get("ok")) || !"APPLIED".equals(rename.get("outcome"))) {
                failRenameChain("rename_not_applied");
                return;
            }
            ledger.pass("renameApplied");

            final Map<String, Object> renamedOverview = readOverview(bridge, timeout);
            if (renamedOverview == null || !objectNameEquals(renamedOverview, id, newName)) {
                ledger.fail("renameReadback", "renamed_name_not_visible");
                return;
            }
            ledger.pass("renameReadback");

            final Map<String, Object> historyAfterRename;
            try {
                historyAfterRename = structured(
                        bridge.request("tools/call", toolCall("turboism.history.read", Map.of()), timeout));
            } catch (IOException failure) {
                ledger.fail("undoRestored", "history_read_after_rename_failed");
                return;
            }
            final long positionAfterRename = number(historyAfterRename.get("position"), -1L);
            if (positionAfterRename != positionBefore + 1) {
                ledger.fail("undoRestored", "unexpected_history_position_after_rename");
                return;
            }
            final LinkedHashMap<String, Object> undoParams = new LinkedHashMap<>();
            undoParams.put("expectedGeneration", number(historyAfterRename.get("generation"), 0L));
            undoParams.put("expectedRevision", number(historyAfterRename.get("revision"), 0L));
            undoParams.put("steps", 1L);
            final Map<String, Object> undo;
            try {
                undo = structured(bridge.request("tools/call", toolCall("turboism.history.undo", undoParams), timeout));
            } catch (IOException failure) {
                ledger.fail("undoRestored", "undo_call_failed");
                return;
            }
            if (!Boolean.TRUE.equals(undo.get("ok"))) {
                ledger.fail("undoRestored", "undo_rejected");
                return;
            }

            final Map<String, Object> restoredOverview = readOverview(bridge, timeout);
            if (restoredOverview == null || !objectNameEquals(restoredOverview, id, originalName)) {
                ledger.fail("undoRestored", "original_name_not_restored");
                return;
            }
            ledger.pass("undoRestored");

            final Map<String, Object> historyAfterUndo;
            try {
                historyAfterUndo = structured(
                        bridge.request("tools/call", toolCall("turboism.history.read", Map.of()), timeout));
            } catch (IOException failure) {
                ledger.fail("historyPositionBalanced", "history_read_after_undo_failed");
                return;
            }
            if (number(historyAfterUndo.get("position"), -1L) != positionBefore) {
                ledger.fail("historyPositionBalanced", "history_position_not_balanced");
                return;
            }
            ledger.pass("historyPositionBalanced");

            final Integer exit = bridge.closeAndWait(timeout);
            if (exit == null || exit != 0) {
                ledger.fail("bridgeClosed", "bridge_exit_" + (exit == null ? "timeout" : exit));
                return;
            }
            ledger.pass("bridgeClosed");
        }
    }

    private void failRenameChain(final String reason) {
        ledger.fail("renameApplied", reason);
    }

    private String describe(final String step, final Exception failure, final BridgeClient bridge) {
        final String stderr = bridge == null ? "" : bridge.stderrText();
        final String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return sanitize(step + "_" + message + (stderr.isBlank() ? "" : "_stderr_" + firstLine(stderr)));
    }

    private static String firstLine(final String text) {
        final int newline = text.indexOf('\n');
        final String line = newline < 0 ? text : text.substring(0, newline);
        return line.length() > 200 ? line.substring(0, 200) : line;
    }

    private static String sanitize(final String text) {
        final String truncated = text.substring(0, Math.min(400, text.length()));
        return truncated.replaceAll("[\\s\"]+", "_");
    }

    private Map<String, Object> readOverview(final BridgeClient bridge, final long timeout) throws IOException {
        final Map<String, Object> response = bridge.request(
                "resources/read", Map.of("uri", "turboism://active/model/overview"), timeout);
        final List<Object> contents = listValue(response.get("contents"));
        if (contents.size() != 1) return null;
        final Map<String, Object> content = objectValue(contents.get(0));
        if (!"application/json".equals(content.get("mimeType"))) return null;
        try {
            final Map<String, ?> parsed = Json.parseObject(String.valueOf(content.get("text")));
            final LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            parsed.forEach((key, value) -> copy.put(key, value));
            return copy;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private Map<String, Object> selectTarget(final Map<String, Object> overview) {
        for (Object item : listValue(overview.get("objects"))) {
            final Map<String, Object> object = objectValue(item);
            if (String.valueOf(object.get("id")).isBlank()) continue;
            if (scenario.objectKind != null && !scenario.objectKind.equals(object.get("kind"))) continue;
            return object;
        }
        return null;
    }

    private static boolean objectNameEquals(final Map<String, Object> overview, final String id, final String name) {
        for (Object item : listValue(overview.get("objects"))) {
            final Map<String, Object> object = objectValue(item);
            if (id.equals(String.valueOf(object.get("id")))) {
                return name.equals(String.valueOf(object.get("name")));
            }
        }
        return false;
    }

    private static boolean containsTool(final Map<String, Object> tools, final String name) {
        for (Object item : listValue(tools.get("tools"))) {
            if (name.equals(objectValue(item).get("name"))) return true;
        }
        return false;
    }

    private static Map<String, Object> toolCall(final String name, final Map<String, Object> arguments) {
        final LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        params.put("name", name);
        params.put("arguments", arguments);
        return params;
    }

    private static Map<String, Object> structured(final Map<String, Object> toolResponse) {
        final Object structured = toolResponse.get("structuredContent");
        if (structured instanceof Map<?, ?> map) {
            final LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, value) -> copy.put(String.valueOf(key), value));
            return copy;
        }
        final List<Object> contents = listValue(toolResponse.get("content"));
        if (contents.size() == 1) {
            final Map<String, Object> block = objectValue(contents.get(0));
            try {
                final Map<String, ?> parsed = Json.parseObject(String.valueOf(block.get("text")));
                final LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
                parsed.forEach((key, value) -> copy.put(key, value));
                return copy;
            } catch (IllegalArgumentException malformed) {
                return new LinkedHashMap<>();
            }
        }
        return new LinkedHashMap<>();
    }

    private static long number(final Object value, final long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private static List<Object> listValue(final Object value) {
        return value instanceof List<?> list ? new ArrayList<>(list) : List.of();
    }

    private static Map<String, Object> objectValue(final Object value) {
        if (value instanceof Map<?, ?> map) {
            final LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(String.valueOf(key), nested));
            return copy;
        }
        return new LinkedHashMap<>();
    }

    private static List<String> stringList(final Object value) {
        final ArrayList<String> list = new ArrayList<>();
        for (Object item : listValue(value)) list.add(String.valueOf(item));
        return list;
    }

    private void sendLine(final Writer stdout, final String line) throws IOException {
        synchronized (stdout) {
            stdout.write(line);
            stdout.write('\n');
            stdout.flush();
        }
    }
}
