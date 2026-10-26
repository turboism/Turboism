package dev.turboism.plugin.acp;

import static dev.turboism.plugin.acp.NativeAgentTransport.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Codex's native app-server JSON-RPC protocol; no codex-acp installation. */
final class NativeCodexProvider extends NativeAgentProvider {
    static final int MAX_PENDING_START_EVENTS = 64;
    static final int MAX_PENDING_START_BYTES = 1024 * 1024;
    private String threadId;
    private Turn activeTurn;
    private boolean unusable;

    private static final class Turn {
        final CompletableFuture<String> future;
        final Set<String> streamed = new java.util.HashSet<>();
        final List<Map<String, Object>> pending = new ArrayList<>();
        String id;
        long pendingBytes;
        boolean cancelRequested;
        boolean interruptSent;

        Turn(CompletableFuture<String> future) {
            this.future = future;
        }
    }

    NativeCodexProvider(final NativeAgentTransport host) {
        super(host);
    }

    @Override
    void start(final Map<String, Object> params) throws Exception {
        connection = host.startProcess(this, List.of("app-server"), Map.of(), this::event);
        await(rpc(
                "initialize",
                Map.of("clientInfo", Map.of("name", "turboism", "title", "Turboism", "version", "0.1.0"))));
        connection.send(Map.of("method", "initialized"));
        final var threadParams = new LinkedHashMap<String, Object>();
        threadParams.put("cwd", params.get("cwd"));
        threadParams.put("config", Map.of("mcp_servers", mcpServers(params, true)));
        if (params.containsKey("sessionId")) threadParams.put("threadId", params.get("sessionId"));
        final var response =
                await(rpc(params.containsKey("sessionId") ? "thread/resume" : "thread/start", threadParams));
        threadId = text(object(response.get("thread")).get("id"));
        if (threadId.isBlank()) throw new IOException("Codex did not return a thread ID");
        host.sessionId(threadId);
        final String model = text(response.get("model"));
        final var models = await(rpc("model/list", Map.of("limit", 100L)));
        final var values = new ArrayList<Map<String, String>>();
        for (Object raw : list(models.get("data"))) {
            final var entry = object(raw);
            final String value = text(entry.get("model"));
            if (value.isBlank()) continue;
            values.add(Map.of(
                    "value",
                    value,
                    "name",
                    text(entry.get("displayName")).isBlank() ? value : text(entry.get("displayName"))));
            if (value.equals(model) || (model.isBlank() && Boolean.TRUE.equals(entry.get("isDefault"))))
                selected.put("model", value);
        }
        if (!model.isBlank() && values.stream().noneMatch(value -> model.equals(value.get("value"))))
            values.add(Map.of("value", model, "name", model));
        if (!values.isEmpty()) {
            choices.put("model", values);
            selected.putIfAbsent("model", model.isBlank() ? values.get(0).get("value") : model);
        }
        choices.put(
                "permissions",
                List.of(
                        Map.of("value", "untrusted", "name", "Ask for untrusted commands"),
                        Map.of("value", "on-request", "name", "Ask when needed")));
        final String policy = text(response.get("approvalPolicy"));
        if (choices.get("permissions").stream().anyMatch(option -> policy.equals(option.get("value"))))
            selected.put("permissions", policy);
        rebuildEffort(models);
    }

    private void rebuildEffort(final Map<String, Object> models) {
        for (Object raw : list(models.get("data"))) {
            final var entry = object(raw);
            if (!text(entry.get("model")).equals(selected.get("model"))) continue;
            final var levels = new ArrayList<Map<String, String>>();
            for (Object level : list(entry.get("supportedReasoningEfforts"))) {
                final String value = text(object(level).get("reasoningEffort"));
                if (!value.isBlank()) levels.add(Map.of("value", value, "name", value));
            }
            if (!levels.isEmpty()) {
                choices.put("effort", levels);
                final String defaultEffort = text(entry.get("defaultReasoningEffort"));
                selected.put(
                        "effort",
                        levels.stream().anyMatch(value -> value.get("value").equals(defaultEffort))
                                ? defaultEffort
                                : levels.get(0).get("value"));
            }
        }
    }

    private CompletableFuture<Map<String, Object>> rpc(final String method, final Map<String, Object> params) {
        final long id = host.nextId();
        return connection
                .request(id, Map.of("id", id, "method", method, "params", params))
                .thenApply(response -> object(checked(response).get("result")));
    }

    @Override
    CompletableFuture<String> prompt(final List<Object> blocks) throws Exception {
        final var input = new ArrayList<Map<String, Object>>();
        input.add(Map.of("type", "text", "text", promptText(blocks)));
        for (Object raw : blocks) {
            final var block = object(raw);
            if ("image".equals(block.get("type")))
                input.add(Map.of(
                        "type",
                        "image",
                        "url",
                        "data:" + text(block.get("mimeType")) + ";base64," + text(block.get("data"))));
        }
        final var params = new LinkedHashMap<String, Object>();
        params.put("threadId", threadId);
        params.put("input", input);
        if (selected.containsKey("permissions")) params.put("approvalPolicy", selected.get("permissions"));
        if (selected.containsKey("model")) params.put("model", selected.get("model"));
        if (selected.containsKey("effort")) params.put("effort", selected.get("effort"));
        final Turn turn;
        synchronized (this) {
            if (unusable)
                throw new IOException("Codex turn identity is unavailable; reconnect and reconcile host operations");
            turn = new Turn(beginPrompt());
            activeTurn = turn;
            turn.future.whenComplete((reason, failure) -> retire(turn));
        }
        rpc("turn/start", params).whenComplete((result, failure) -> {
            synchronized (this) {
                if (activeTurn != turn || turn.future.isDone()) return;
                if (failure != null) {
                    poison(
                            turn,
                            "Codex turn/start failed; reconnect and reconcile host operations: "
                                    + failure.getMessage());
                    return;
                }
                String id = identity(object(result.get("turn")).get("id"));
                if (id.isEmpty()) {
                    poison(
                            turn,
                            "Codex turn/start omitted its native turn identity; reconnect and reconcile host operations");
                    return;
                }
                turn.id = id;
                // Only the response to this exact request establishes ownership. A delayed
                // turn/started notification cannot redirect an early cancellation.
                if (turn.cancelRequested) interrupt(turn);
                var pending = List.copyOf(turn.pending);
                turn.pending.clear();
                turn.pendingBytes = 0;
                for (var message : pending) event(message);
            }
        });
        return turn.future;
    }

    @Override
    void configure(final String id, final String value) throws Exception {
        super.configure(id, value);
        if (id.equals("model")) {
            choices.remove("effort");
            selected.remove("effort");
            rebuildEffort(await(rpc("model/list", Map.of("limit", 100L))));
        }
    }

    @Override
    synchronized void cancel() {
        final var turn = activeTurn;
        if (turn == null || turn.future.isDone()) return;
        turn.cancelRequested = true;
        host.cancelPermissions(turn.future);
        if (turn.id != null) interrupt(turn);
    }

    private void interrupt(final Turn turn) {
        if (activeTurn != turn || turn.future.isDone() || turn.interruptSent) return;
        turn.interruptSent = true;
        rpc("turn/interrupt", Map.of("threadId", threadId, "turnId", turn.id)).whenComplete((result, failure) -> {
            synchronized (this) {
                if (failure != null && activeTurn == turn && !turn.future.isDone())
                    poison(
                            turn,
                            "Codex interrupt failed; reconnect and reconcile host operations: " + failure.getMessage());
            }
        });
    }

    private synchronized void retire(final Turn turn) {
        if (activeTurn != turn) return;
        activeTurn = null;
        for (var message : turn.pending) {
            if (message.containsKey("id")) reject(message.get("id"), text(message.get("method")));
        }
        turn.pending.clear();
        turn.streamed.clear();
        turn.pendingBytes = 0;
    }

    private void poison(final Turn turn, final String message) {
        unusable = true;
        turn.future.completeExceptionally(new IOException(message));
        connection.close();
    }

    private static String identity(final Object value) {
        return value instanceof String id && !id.isBlank() && id.length() <= 512 && id.indexOf('\0') < 0 ? id : "";
    }

    private synchronized void event(final Map<String, Object> message) {
        final String method = text(message.get("method"));
        final var params = object(message.get("params"));
        final var turn = activeTurn;
        if (turn == null || turn.future.isDone() || !threadId.equals(params.get("threadId"))) {
            if (message.containsKey("id")) reject(message.get("id"), method);
            return;
        }
        final String eventTurn = identity(
                method.equals("turn/started") || method.equals("turn/completed")
                        ? object(params.get("turn")).get("id")
                        : params.get("turnId"));
        if (eventTurn.isEmpty()) {
            if (message.containsKey("id")) reject(message.get("id"), method);
            return;
        }
        if (turn.id == null) {
            long bytes = dev.turboism.sdk.json.Json.stringify(message)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    .length;
            if (turn.pending.size() >= MAX_PENDING_START_EVENTS
                    || bytes > MAX_PENDING_START_BYTES - turn.pendingBytes) {
                if (message.containsKey("id")) reject(message.get("id"), method);
                poison(
                        turn,
                        "Codex events exceeded the pending turn identity budget; reconnect and reconcile host operations");
                return;
            }
            turn.pending.add(message);
            turn.pendingBytes += bytes;
            return;
        }
        if (!turn.id.equals(eventTurn)) {
            if (message.containsKey("id")) reject(message.get("id"), method);
            return;
        }
        if (message.containsKey("id")) {
            if (turn.cancelRequested) reject(message.get("id"), method);
            else approval(turn, message.get("id"), method, params);
            return;
        }
        switch (method) {
            case "item/agentMessage/delta" -> {
                turn.streamed.add(text(params.get("itemId")));
                host.text("agent_message_chunk", text(params.get("delta")));
            }
            case "item/reasoning/summaryTextDelta", "item/reasoning/textDelta" ->
                host.text("agent_thought_chunk", text(params.get("delta")));
            case "turn/started" -> {
                /* Identity was established by this request's ACK. */
            }
            case "item/started", "item/completed" ->
                item(turn, object(params.get("item")), method.equals("item/completed"));
            case "turn/completed" -> {
                final var result = object(params.get("turn"));
                final String status = text(result.get("status"));
                if ("failed".equals(status) || result.get("error") != null)
                    turn.future.completeExceptionally(
                            new IOException(text(object(result.get("error")).get("message"))));
                else if ("completed".equals(status) || "interrupted".equals(status))
                    turn.future.complete(
                            "interrupted".equals(status) || turn.cancelRequested ? "cancelled" : "end_turn");
                else poison(turn, "Codex returned an unknown terminal status; reconnect and reconcile host operations");
            }
            case "error" -> {
                if (!Boolean.TRUE.equals(params.get("willRetry")))
                    turn.future.completeExceptionally(
                            new IOException(text(object(params.get("error")).get("message"))));
            }
            default -> {
                /* Other app-server notifications do not represent conversation output. */
            }
        }
    }

    private void item(final Turn turn, final Map<String, Object> item, final boolean completed) {
        final String type = text(item.get("type"));
        final String id = text(item.get("id"));
        if (type.equals("agentMessage")) {
            if (completed && !turn.streamed.contains(id)) host.text("agent_message_chunk", text(item.get("text")));
        } else if (Set.of("commandExecution", "fileChange", "mcpToolCall", "dynamicToolCall", "webSearch")
                .contains(type)) {
            final String title = text(item.get("tool")).isBlank() ? type : text(item.get("tool"));
            final String status = "failed".equals(item.get("status")) || item.get("error") != null
                    ? "failed"
                    : completed ? "completed" : "in_progress";
            tool(
                    id,
                    title,
                    status,
                    item.getOrDefault("arguments", item.get("command")),
                    completed ? item.getOrDefault("result", item.get("aggregatedOutput")) : null);
        }
    }

    private void approval(final Turn turn, final Object id, final String method, final Map<String, Object> params) {
        if (method.equals("item/commandExecution/requestApproval")
                || method.equals("item/fileChange/requestApproval")) {
            host.permission(
                    turn.future,
                    method.contains("fileChange") ? "Edit files" : "Run command",
                    method.contains("fileChange") ? "edit" : "execute",
                    text(params.get("itemId")),
                    params,
                    allow -> {
                        synchronized (this) {
                            send(Map.of(
                                    "id",
                                    id,
                                    "result",
                                    Map.of("decision", currentApproval(turn, allow) ? "accept" : "decline")));
                        }
                    });
        } else if (method.equals("item/permissions/requestApproval")) {
            host.permission(turn.future, "Agent permissions", "other", text(params.get("itemId")), params, allow -> {
                synchronized (this) {
                    send(Map.of(
                            "id",
                            id,
                            "result",
                            Map.of(
                                    "permissions",
                                    currentApproval(turn, allow) ? object(params.get("permissions")) : Map.of(),
                                    "scope",
                                    "turn")));
                }
            });
        } else if (method.equals("mcpServer/elicitation/request")) {
            send(Map.of("id", id, "result", Map.of("action", "cancel")));
        } else if (method.equals("item/tool/requestUserInput")) {
            send(Map.of("id", id, "result", Map.of("answers", Map.of())));
        } else send(Map.of("id", id, "error", Map.of("code", -32601L, "message", "Unsupported native agent request")));
    }

    private boolean currentApproval(final Turn turn, final boolean allow) {
        return allow && activeTurn == turn && !turn.future.isDone() && !turn.cancelRequested;
    }

    private void reject(final Object id, final String method) {
        if (method.equals("item/commandExecution/requestApproval") || method.equals("item/fileChange/requestApproval"))
            send(Map.of("id", id, "result", Map.of("decision", "decline")));
        else if (method.equals("item/permissions/requestApproval"))
            send(Map.of("id", id, "result", Map.of("permissions", Map.of(), "scope", "turn")));
        else if (method.equals("mcpServer/elicitation/request"))
            send(Map.of("id", id, "result", Map.of("action", "cancel")));
        else if (method.equals("item/tool/requestUserInput"))
            send(Map.of("id", id, "result", Map.of("answers", Map.of())));
        else
            send(Map.of(
                    "id",
                    id,
                    "error",
                    Map.of("code", -32601L, "message", "Unsupported or inactive native agent request")));
    }

    private void send(final Map<String, Object> message) {
        try {
            connection.send(message);
        } catch (IOException failure) {
            failed(failure);
        }
    }
}
