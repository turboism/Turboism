package dev.turboism.plugin.acp;

import static dev.turboism.plugin.acp.NativeAgentTransport.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Claude Code's stream-json protocol, including its stdio permission callback. */
final class NativeClaudeProvider extends NativeAgentProvider {
    private boolean textStreamed;
    private boolean thoughtStreamed;

    NativeClaudeProvider(final NativeAgentTransport host) {
        super(host);
    }

    @Override
    void start(final Map<String, Object> params) throws Exception {
        final var arguments = new ArrayList<String>(List.of(
                "--print",
                "--output-format",
                "stream-json",
                "--verbose",
                "--input-format",
                "stream-json",
                "--include-partial-messages",
                "--permission-prompt-tool",
                "stdio",
                "--mcp-config",
                writeMcpConfig(params, false).toString()));
        arguments.addAll(List.of(params.containsKey("sessionId") ? "--resume" : "--session-id", host.sessionId()));
        connection = host.startProcess(this, arguments, Map.of(), this::event);
        final var initialized = await(control(Map.of("subtype", "initialize")));
        choices.put(
                "permissions",
                List.of(
                        Map.of("value", "default", "name", "Default"),
                        Map.of("value", "acceptEdits", "name", "Accept edits"),
                        Map.of("value", "plan", "name", "Plan")));
        final String permissionMode = text(initialized.get("current_permission_mode"));
        selected.put("permissions", permissionMode.isBlank() ? "default" : permissionMode);
        final var models = new ArrayList<Map<String, String>>();
        for (Object raw : list(initialized.get("models"))) {
            final var model = object(raw);
            final String value = text(model.get("value"));
            if (!value.isBlank())
                models.add(Map.of("value", value, "name", text(model.getOrDefault("displayName", value))));
        }
        if (!models.isEmpty()) {
            choices.put("model", models);
            selected.put("model", models.get(0).get("value"));
        }
    }

    private CompletableFuture<Map<String, Object>> control(final Map<String, Object> request) {
        final String id = "claude-" + host.nextId();
        return connection
                .request(id, Map.of("type", "control_request", "request_id", id, "request", request))
                .thenApply(message -> {
                    final var response = object(message.get("response"));
                    if ("error".equals(response.get("subtype")))
                        throw new java.util.concurrent.CompletionException(
                                new IOException(text(response.get("error"))));
                    return object(response.get("response"));
                });
    }

    @Override
    CompletableFuture<String> prompt(final List<Object> blocks) throws Exception {
        final var content = new ArrayList<Map<String, Object>>();
        content.add(Map.of("type", "text", "text", promptText(blocks)));
        for (Object raw : blocks) {
            final var block = object(raw);
            if ("image".equals(block.get("type")))
                content.add(Map.of(
                        "type",
                        "image",
                        "source",
                        Map.of(
                                "type",
                                "base64",
                                "media_type",
                                text(block.get("mimeType")),
                                "data",
                                text(block.get("data")))));
        }
        textStreamed = false;
        thoughtStreamed = false;
        final var future = beginPrompt();
        try {
            connection.send(Map.of("type", "user", "message", Map.of("role", "user", "content", content)));
        } catch (IOException failure) {
            failed(failure);
        }
        return future;
    }

    @Override
    void configure(final String id, final String value) throws Exception {
        if (!List.of("model", "permissions").contains(id)) throw new IOException("Unsupported Claude Code option");
        if (choices.getOrDefault(id, List.of()).stream().noneMatch(option -> value.equals(option.get("value"))))
            throw new IOException("Unknown option");
        await(control(
                id.equals("model")
                        ? Map.of("subtype", "set_model", "model", value)
                        : Map.of("subtype", "set_permission_mode", "mode", value)));
        super.configure(id, value);
    }

    @Override
    void cancel() {
        cancelled = true;
        control(Map.of("subtype", "interrupt")).whenComplete((result, failure) -> {
            if (failure != null) failed(failure);
        });
    }

    private void event(final Map<String, Object> message) {
        switch (text(message.get("type"))) {
            case "control_request" -> {
                final var request = object(message.get("request"));
                final Object id = message.get("request_id");
                if ("can_use_tool".equals(request.get("subtype"))) {
                    host.permission(
                            text(request.get("tool_name")),
                            "other",
                            text(request.get("tool_use_id")),
                            request.getOrDefault("input", Map.of()),
                            allow -> sendControl(
                                    id,
                                    allow
                                            ? Map.of(
                                                    "behavior",
                                                    "allow",
                                                    "updatedInput",
                                                    request.getOrDefault("input", Map.of()))
                                            : Map.of("behavior", "deny", "message", "Rejected by user")));
                } else {
                    try {
                        connection.send(Map.of(
                                "type",
                                "control_response",
                                "response",
                                Map.of("subtype", "error", "request_id", id, "error", "Unsupported control request")));
                    } catch (IOException failure) {
                        failed(failure);
                    }
                }
            }
            case "stream_event" -> {
                final var event = object(message.get("event"));
                if ("message_start".equals(event.get("type"))) {
                    textStreamed = false;
                    thoughtStreamed = false;
                }
                final var delta = object(event.get("delta"));
                if ("text_delta".equals(delta.get("type"))) {
                    textStreamed = true;
                    host.text("agent_message_chunk", text(delta.get("text")));
                }
                if ("thinking_delta".equals(delta.get("type"))) {
                    thoughtStreamed = true;
                    host.text("agent_thought_chunk", text(delta.get("thinking")));
                }
            }
            case "assistant", "user" -> {
                final var content = object(message.get("message")).get("content");
                if (!(content instanceof List<?>)) break;
                for (Object raw : list(content)) {
                    final var block = object(raw);
                    switch (text(block.get("type"))) {
                        case "text" -> {
                            if ("assistant".equals(message.get("type")) && !textStreamed)
                                host.text("agent_message_chunk", text(block.get("text")));
                        }
                        case "thinking" -> {
                            if (!thoughtStreamed) host.text("agent_thought_chunk", text(block.get("thinking")));
                        }
                        case "tool_use" ->
                            tool(
                                    text(block.get("id")),
                                    text(block.get("name")),
                                    "in_progress",
                                    block.get("input"),
                                    null);
                        case "tool_result" ->
                            tool(
                                    text(block.get("tool_use_id")),
                                    "Tool",
                                    Boolean.TRUE.equals(block.get("is_error")) ? "failed" : "completed",
                                    null,
                                    block.get("content"));
                        default -> {}
                    }
                }
            }
            case "result" -> {
                if (cancelled) complete("cancelled");
                else if (Boolean.TRUE.equals(message.get("is_error"))
                        || text(message.get("subtype")).startsWith("error"))
                    failed(new IOException(
                            message.containsKey("errors")
                                    ? jsonValue(message.get("errors"))
                                    : text(message.get("result"))));
                else complete("end_turn");
            }
            default -> {}
        }
    }

    private void sendControl(final Object id, final Map<String, Object> response) {
        try {
            connection.send(Map.of(
                    "type",
                    "control_response",
                    "response",
                    Map.of("subtype", "success", "request_id", id, "response", response)));
        } catch (IOException failure) {
            failed(failure);
        }
    }
}
