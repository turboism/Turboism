package dev.turboism.plugin.acp;

import static dev.turboism.plugin.acp.NativeAgentTransport.*;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Pi's native RPC mode; the bundled extension supplies Turboism MCP without npm dependencies. */
final class NativePiProvider extends NativeAgentProvider {
    private boolean textStreamed;
    private boolean thoughtStreamed;
    private String lastError;
    private ActiveAskUser activeAskUser;
    private final Map<String, Map<String, Object>> models = new LinkedHashMap<>();

    private static final class ActiveAskUser {
        final String callId;
        final List<AcpQuestion.Item> items;
        AcpQuestion.Response response;
        Map<String, Object> deferredDialog;
        int index;
        boolean expectingCustomInput;

        ActiveAskUser(final String callId, final List<AcpQuestion.Item> items) {
            this.callId = callId;
            this.items = items;
        }
    }

    NativePiProvider(final NativeAgentTransport host) {
        super(host);
    }

    @Override
    void start(final Map<String, Object> params) throws Exception {
        final var extension = host.directory().resolve("pi-mcp-extension.mjs");
        try (var resource = NativePiProvider.class.getResourceAsStream("/extensions/pi-mcp-extension.mjs")) {
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
            final String provider = text(model.get("provider"));
            final String rawModelId = text(model.get("id"));
            final String id = provider + "/" + rawModelId;
            models.put(id, model);
            final String rawName = text(model.get("name"));
            options.add(Map.of(
                    "value", id,
                    "name", rawName.isBlank() ? rawModelId : rawName,
                    "description", id));
        }
        if (!options.isEmpty()) {
            choices.put("model", options);
            final var model = object(state.get("model"));
            final String current = text(model.get("provider")) + "/" + text(model.get("id"));
            if (models.containsKey(current)) selected.put("model", current);
        }
        final var model = object(state.get("model"));
        final String current = text(model.get("provider")) + "/" + text(model.get("id"));
        if (!current.equals("/")) {
            models.putIfAbsent(current, model);
            selected.put("model", current);
        }
        if (params.containsKey("sessionId")) {
            final var history = await(command("get_messages", Map.of()));
            NativeSessionReplay.replayPiMessages(this, host, list(history.get("messages")));
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
    synchronized CompletableFuture<String> prompt(final List<Object> blocks) throws Exception {
        final var params = new LinkedHashMap<String, Object>();
        params.put("message", promptText(blocks));
        final var images = new ArrayList<Map<String, Object>>();
        for (Object raw : blocks) {
            final var block = object(raw);
            if ("image".equals(block.get("type")))
                images.add(Map.of(
                        "type", "image", "data", text(block.get("data")), "mimeType", text(block.get("mimeType"))));
        }
        if (!images.isEmpty()) {
            final var model = models.getOrDefault(selected.get("model"), Map.of());
            if (!list(model.get("input")).contains("image"))
                throw new IOException(
                        "The selected Pi model has no verified image input support; select a vision model");
            params.put("images", images);
        }
        final var future = beginPrompt();
        textStreamed = false;
        thoughtStreamed = false;
        lastError = null;
        activeAskUser = null;
        command("prompt", params).whenComplete((result, failure) -> {
            synchronized (this) {
                if (!ownsPrompt(future)) return;
                if (failure != null) uncertainPrompt(future, "Pi prompt", failure);
                else if ("handled".equals(result.get("disposition"))
                        || Boolean.FALSE.equals(result.get("agentInvoked")))
                    future.complete(cancelled ? "cancelled" : "end_turn");
            }
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
    synchronized void cancel() {
        final var future = runningPrompt();
        if (future == null || cancelled) return;
        cancelled = true;
        activeAskUser = null;
        host.cancelPermissions(future);
        command("abort", Map.of()).whenComplete((result, failure) -> {
            if (failure != null) uncertainPrompt(future, "Pi abort", failure);
        });
    }

    private void settled() {
        activeAskUser = null;
        if (cancelled) complete("cancelled");
        else if (lastError != null) failed(new IOException(lastError));
        else complete("end_turn");
    }

    private synchronized void event(final Map<String, Object> message) {
        final var future = runningPrompt();
        if (future == null) {
            if ("extension_ui_request".equals(message.get("type"))
                    && List.of("confirm", "select", "input", "editor").contains(text(message.get("method"))))
                uiResponse(message, false);
            return;
        }
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
                final Object outTokens = object(value.get("usage")).get("output");
                if (outTokens instanceof Number n && n.longValue() > 0L) {
                    host.usage(n.longValue());
                }
                for (Object raw : list(value.get("content"))) {
                    final var block = object(raw);
                    if ("text".equals(block.get("type")) && !textStreamed)
                        host.text("agent_message_chunk", text(block.get("text")));
                    if ("thinking".equals(block.get("type")) && !thoughtStreamed)
                        host.text("agent_thought_chunk", text(block.get("thinking")));
                }
                if ("error".equals(value.get("stopReason"))) lastError = text(value.get("errorMessage"));
            }
            case "tool_execution_start" -> {
                final String callId = text(message.get("toolCallId"));
                final String toolName = text(message.get("toolName"));
                tool(callId, toolName, "in_progress", message.get("args"), null);
                if ("ask_user_question".equals(toolName) && !cancelled) {
                    final var args = object(message.get("args"));
                    final List<AcpQuestion.Item> items = AcpQuestion.parseRawQuestions(args.get("questions"), true);
                    if (!items.isEmpty()) {
                        final ActiveAskUser tracker = new ActiveAskUser(callId, items);
                        activeAskUser = tracker;
                        host.question(future, "AskUserQuestion", items, response -> {
                            synchronized (this) {
                                if (activeAskUser != tracker) return;
                                tracker.response =
                                        ownsPrompt(future) && !cancelled ? response : AcpQuestion.Response.CANCELLED;
                                final var deferred = tracker.deferredDialog;
                                tracker.deferredDialog = null;
                                if (deferred != null) {
                                    answerAskUserDialog(tracker, deferred);
                                }
                            }
                        });
                    }
                }
            }
            case "tool_execution_end" -> {
                final String callId = text(message.get("toolCallId"));
                if (activeAskUser != null && activeAskUser.callId.equals(callId)) {
                    activeAskUser = null;
                }
                tool(
                        callId,
                        text(message.get("toolName")),
                        Boolean.TRUE.equals(message.get("isError")) ? "failed" : "completed",
                        null,
                        message.get("result"));
            }
            case "agent_end" -> {
                if (!message.containsKey("willRetry")) settled();
            }
            case "agent_settled" -> settled();
            case "auto_retry_end" -> {
                if (Boolean.TRUE.equals(message.get("success"))) lastError = null;
            }
            case "extension_ui_request" -> {
                final String method = text(message.get("method"));
                if (activeAskUser != null && ("select".equals(method) || "input".equals(method))) {
                    if (cancelled) {
                        uiResponse(message, false);
                    } else if (activeAskUser.response == null) {
                        activeAskUser.deferredDialog = message;
                    } else {
                        answerAskUserDialog(activeAskUser, message);
                    }
                    break;
                }
                if ("confirm".equals(method) && !cancelled) {
                    host.permission(
                            future,
                            text(message.get("title")),
                            "other",
                            text(message.get("id")),
                            unwrappedConfirmDetails(text(message.get("message"))),
                            allow -> {
                                synchronized (this) {
                                    uiResponse(message, allow && ownsPrompt(future) && !cancelled);
                                }
                            });
                } else if ("select".equals(method) && !cancelled) {
                    final List<AcpQuestion.Option> options = new ArrayList<>();
                    for (Object rawOpt : list(message.get("options"))) {
                        final String label = text(rawOpt);
                        if (!label.isBlank()) options.add(new AcpQuestion.Option(label, "", ""));
                    }
                    final String title = text(message.get("title"));
                    final String questionText = title.isBlank() ? "Select an option" : title;
                    final var item = new AcpQuestion.Item(
                            "pi-select", "", questionText, options, false, options.isEmpty(), false, false, "");
                    host.question(future, questionText, List.of(item), response -> {
                        synchronized (this) {
                            if (response.cancelled() || !ownsPrompt(future) || cancelled) {
                                uiResponse(message, false);
                                return;
                            }
                            final AcpQuestion.Answer ans = response.answerFor(item, 0);
                            final String chosen = ans == null ? "" : ans.formattedText();
                            if (chosen.isEmpty()) uiResponse(message, false);
                            else uiValueResponse(message, chosen);
                        }
                    });
                } else if (("input".equals(method) || "editor".equals(method)) && !cancelled) {
                    final String title = text(message.get("title"));
                    final String placeholder = text(message.get("placeholder"));
                    final String questionText =
                            !title.isBlank() ? title : ("editor".equals(method) ? "Edit text" : "Enter a value");
                    final var item = new AcpQuestion.Item(
                            "pi-input", "", questionText, List.of(), false, true, false, false, placeholder);
                    host.question(future, questionText, List.of(item), response -> {
                        synchronized (this) {
                            if (response.cancelled() || !ownsPrompt(future) || cancelled) {
                                uiResponse(message, false);
                                return;
                            }
                            final AcpQuestion.Answer ans = response.answerFor(item, 0);
                            if (ans == null || !ans.hasContent()) uiResponse(message, false);
                            else uiValueResponse(message, ans.customText());
                        }
                    });
                } else if (List.of("confirm", "select", "input", "editor").contains(method)) {
                    uiResponse(message, false);
                }
            }
            default -> {}
        }
    }

    private static Object unwrappedConfirmDetails(final String rawMessage) {
        final String trimmed = rawMessage == null ? "" : rawMessage.strip();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return dev.turboism.sdk.json.Json.parseObject(
                        trimmed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (RuntimeException keepString) {
                // Fall back to plain message wrapper when confirmation text is not JSON.
            }
        }
        return Map.of("message", rawMessage == null ? "" : rawMessage);
    }

    private void answerAskUserDialog(final ActiveAskUser tracker, final Map<String, Object> dialog) {
        final AcpQuestion.Response response = tracker.response;
        if (response == null || response.cancelled() || tracker.index >= tracker.items.size()) {
            uiResponse(dialog, false);
            return;
        }
        final AcpQuestion.Item item = tracker.items.get(tracker.index);
        final AcpQuestion.Answer ans = response.answerFor(item, tracker.index);
        if (ans == null || !ans.hasContent()) {
            uiResponse(dialog, false);
            return;
        }
        final String method = text(dialog.get("method"));
        if (tracker.expectingCustomInput && "input".equals(method)) {
            tracker.expectingCustomInput = false;
            tracker.index++;
            uiValueResponse(dialog, ans.customText());
            return;
        }
        if (item.multiSelect()) {
            final List<String> indices = new ArrayList<>();
            for (String selected : ans.selectedOptions()) {
                final int idx = optionIndex(item, selected);
                if (idx >= 0) indices.add(String.valueOf(idx + 1));
            }
            final String value;
            if (!indices.isEmpty()) {
                final String joined = String.join(",", indices);
                value = ans.customText().isEmpty() ? joined : joined + "; " + ans.customText();
            } else {
                value = ans.formattedText();
            }
            tracker.index++;
            uiValueResponse(dialog, value);
            return;
        }
        final List<Object> dialogOptions = list(dialog.get("options"));
        if (!ans.selectedOptions().isEmpty()) {
            final String selected = ans.selectedOptions().get(0);
            final int optIdx = optionIndex(item, selected);
            if (optIdx >= 0) {
                tracker.index++;
                final String reply = optIdx < dialogOptions.size() ? text(dialogOptions.get(optIdx)) : selected;
                uiValueResponse(dialog, reply);
                return;
            }
        }
        if (!ans.customText().isEmpty()) {
            tracker.expectingCustomInput = true;
            final int otherIdx = item.options().size();
            final String otherReply = otherIdx < dialogOptions.size()
                    ? text(dialogOptions.get(otherIdx))
                    : (otherIdx + 1) + ". Type something.";
            uiValueResponse(dialog, otherReply);
            return;
        }
        uiResponse(dialog, false);
    }

    private static int optionIndex(final AcpQuestion.Item item, final String label) {
        for (int i = 0; i < item.options().size(); i++) {
            if (item.options().get(i).label().equals(label)) return i;
        }
        return -1;
    }

    private void uiValueResponse(final Map<String, Object> request, final String value) {
        try {
            connection.send(Map.of(
                    "type",
                    "extension_ui_response",
                    "id",
                    request.get("id"),
                    "value",
                    Objects.requireNonNullElse(value, "")));
        } catch (IOException failure) {
            failed(failure);
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
