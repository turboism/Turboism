package dev.turboism.plugin.acp;

import static dev.turboism.plugin.acp.NativeAgentTransport.*;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Pi's native RPC mode; the bundled extension supplies Turboism MCP without npm dependencies. */
final class NativePiProvider extends NativeAgentProvider {
    private boolean textStreamed;
    private boolean thoughtStreamed;
    private String lastError;
    private final Map<String, Map<String, Object>> models = new LinkedHashMap<>();

    NativePiProvider(final NativeAgentTransport host) {
        super(host);
    }

    @Override
    void start(final Map<String, Object> params) throws Exception {
        final var extension = host.directory().resolve("pi-mcp-extension.mjs");
        try (var resource = NativePiProvider.class.getResourceAsStream("/native/pi-mcp-extension.mjs")) {
            if (resource == null) throw new IOException("Bundled Pi MCP extension is missing");
            Files.copy(resource, extension);
        }
        connection = host.startProcess(
                this,
                List.of(
                        "--mode",
                        "rpc",
                        "--session",
                        host.sessionDirectory()
                                .resolve(host.sessionId() + ".jsonl")
                                .toString(),
                        "--extension",
                        extension.toString()),
                Map.of("TURBOISM_PI_MCP_CONFIG", writeMcpConfig(params, false).toString()),
                this::event);
        final var state = await(command("get_state", Map.of()));
        final var commands = await(command("get_commands", Map.of()));
        if (list(commands.get("commands")).stream()
                .noneMatch(raw -> "turboism-mcp-ready".equals(object(raw).get("name"))))
            throw new IOException("Pi could not load the bundled Turboism MCP extension; check CLI diagnostics");
        final var available = await(command("get_available_models", Map.of()));
        final var options = new ArrayList<Map<String, String>>();
        for (Object raw : list(available.get("models"))) {
            final var model = object(raw);
            final String id = text(model.get("provider")) + "/" + text(model.get("id"));
            models.put(id, model);
            options.add(Map.of("value", id, "name", text(model.getOrDefault("name", model.get("id")))));
        }
        if (!options.isEmpty()) {
            choices.put("model", options);
            final var model = object(state.get("model"));
            final String current = text(model.get("provider")) + "/" + text(model.get("id"));
            if (models.containsKey(current)) selected.put("model", current);
        }
        // Query the runtime rather than inventing thinking levels that a provider may not support.
    }

    private CompletableFuture<Map<String, Object>> command(final String type, final Map<String, Object> params) {
        final String id = "pi-" + host.nextId();
        final var message = new LinkedHashMap<String, Object>(params);
        message.put("id", id);
        message.put("type", type);
        return connection
                .request(id, message)
                .thenApply(response -> object(checked(response).get("data")));
    }

    @Override
    CompletableFuture<String> prompt(final List<Object> blocks) throws Exception {
        final var params = new LinkedHashMap<String, Object>();
        params.put("message", promptText(blocks));
        final var images = new ArrayList<Map<String, Object>>();
        for (Object raw : blocks) {
            final var block = object(raw);
            if ("image".equals(block.get("type")))
                images.add(Map.of(
                        "type", "image", "data", text(block.get("data")), "mimeType", text(block.get("mimeType"))));
        }
        if (!images.isEmpty()) params.put("images", images);
        textStreamed = false;
        thoughtStreamed = false;
        lastError = null;
        final var future = beginPrompt();
        command("prompt", params).whenComplete((result, failure) -> {
            if (failure != null) future.completeExceptionally(failure);
            else if ("handled".equals(result.get("disposition")) || Boolean.FALSE.equals(result.get("agentInvoked")))
                complete("end_turn");
        });
        return future;
    }

    @Override
    void configure(final String id, final String value) throws Exception {
        if (!id.equals("model") || !models.containsKey(value)) throw new IOException("Unknown Pi model");
        final var model = models.get(value);
        await(command("set_model", Map.of("provider", model.get("provider"), "modelId", model.get("id"))));
        super.configure(id, value);
    }

    @Override
    void cancel() {
        cancelled = true;
        command("abort", Map.of()).whenComplete((result, failure) -> {
            if (failure != null) failed(failure);
        });
    }

    private void settled() {
        if (cancelled) complete("cancelled");
        else if (lastError != null) failed(new IOException(lastError));
        else complete("end_turn");
    }

    private void event(final Map<String, Object> message) {
        switch (text(message.get("type"))) {
            case "message_start" -> {
                textStreamed = false;
                thoughtStreamed = false;
            }
            case "message_update" -> {
                final var event = object(message.get("assistantMessageEvent"));
                if ("text_delta".equals(event.get("type"))) {
                    textStreamed = true;
                    host.text("agent_message_chunk", text(event.get("delta")));
                }
                if ("thinking_delta".equals(event.get("type"))) {
                    thoughtStreamed = true;
                    host.text("agent_thought_chunk", text(event.get("delta")));
                }
            }
            case "message_end" -> {
                final var value = object(message.get("message"));
                if (!"assistant".equals(value.get("role"))) break;
                for (Object raw : list(value.get("content"))) {
                    final var block = object(raw);
                    if ("text".equals(block.get("type")) && !textStreamed)
                        host.text("agent_message_chunk", text(block.get("text")));
                    if ("thinking".equals(block.get("type")) && !thoughtStreamed)
                        host.text("agent_thought_chunk", text(block.get("thinking")));
                }
                if ("error".equals(value.get("stopReason"))) lastError = text(value.get("errorMessage"));
            }
            case "tool_execution_start" ->
                tool(
                        text(message.get("toolCallId")),
                        text(message.get("toolName")),
                        "in_progress",
                        message.get("args"),
                        null);
            case "tool_execution_end" ->
                tool(
                        text(message.get("toolCallId")),
                        text(message.get("toolName")),
                        Boolean.TRUE.equals(message.get("isError")) ? "failed" : "completed",
                        null,
                        message.get("result"));
            case "agent_end" -> {
                if (!message.containsKey("willRetry")) settled();
            }
            case "agent_settled" -> settled();
            case "auto_retry_end" -> {
                if (Boolean.TRUE.equals(message.get("success"))) lastError = null;
            }
            case "extension_ui_request" -> {
                if ("confirm".equals(message.get("method")))
                    host.permission(
                            text(message.get("title")),
                            "other",
                            text(message.get("id")),
                            message.getOrDefault("message", ""),
                            allow -> uiResponse(message, allow));
                else if (List.of("select", "input", "editor").contains(text(message.get("method"))))
                    uiResponse(message, false);
            }
            default -> {}
        }
    }

    private void uiResponse(final Map<String, Object> request, final boolean allow) {
        try {
            connection.send(Map.of(
                    "type", "extension_ui_response", "id", request.get("id"), "confirmed", allow, "cancelled", !allow));
        } catch (IOException failure) {
            failed(failure);
        }
    }
}
