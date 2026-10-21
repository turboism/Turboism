package dev.turboism.plugin.acp;

import static dev.turboism.plugin.acp.NativeAgentTransport.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Provider-specific operations behind the existing normalized conversation boundary. */
abstract class NativeAgentProvider implements AutoCloseable {
    final NativeAgentTransport host;
    volatile NativeJsonlConnection connection;
    private CompletableFuture<String> activePrompt;
    volatile boolean cancelled;
    final Map<String, String> selected = new LinkedHashMap<>();
    final Map<String, List<Map<String, String>>> choices = new LinkedHashMap<>();

    NativeAgentProvider(final NativeAgentTransport host) {
        this.host = host;
    }

    abstract void start(Map<String, Object> params) throws Exception;

    abstract CompletableFuture<String> prompt(List<Object> blocks) throws Exception;

    abstract void cancel() throws Exception;

    void configure(final String id, final String value) throws Exception {
        if (choices.getOrDefault(id, List.of()).stream()
                .noneMatch(option -> option.get("value").equals(value)))
            throw new IOException("Unknown native agent option");
        selected.put(id, value);
    }

    final synchronized CompletableFuture<String> beginPrompt() throws IOException {
        if (activePrompt != null && !activePrompt.isDone()) throw new IOException("A native prompt is already running");
        cancelled = false;
        activePrompt = new CompletableFuture<>();
        return activePrompt;
    }

    final synchronized void complete(final String reason) {
        if (activePrompt != null) activePrompt.complete(reason);
    }

    final synchronized void failed(final Throwable failure) {
        if (activePrompt != null) activePrompt.completeExceptionally(failure);
    }

    final List<Map<String, Object>> configOptions() {
        final List<Map<String, Object>> options = new ArrayList<>();
        choices.forEach((id, values) -> {
            if (!values.isEmpty() && selected.containsKey(id))
                options.add(Map.of(
                        "id",
                        id,
                        "name",
                        id.equals("model") ? "Model" : id.equals("permissions") ? "Permissions" : "Thinking",
                        "type",
                        "select",
                        "category",
                        id.equals("model") ? "model" : id.equals("permissions") ? "permission" : "thought_level",
                        "currentValue",
                        selected.get(id),
                        "options",
                        values));
        });
        return options;
    }

    final Path writeMcpConfig(final Map<String, Object> params, final boolean codex) throws IOException {
        final Map<String, Object> servers = mcpServers(params, codex);
        final Path path = host.directory().resolve("mcp.json");
        Files.writeString(path, dev.turboism.sdk.json.Json.stringify(Map.of("mcpServers", servers)));
        return path;
    }

    static Map<String, Object> mcpServers(final Map<String, Object> params, final boolean codex) {
        final Map<String, Object> servers = new LinkedHashMap<>();
        for (Object raw : list(params.get("mcpServers"))) {
            final var server = object(raw);
            final var config = new LinkedHashMap<String, Object>();
            if (server.containsKey("command")) {
                config.put("command", server.get("command"));
                config.put("args", list(server.get("args")));
                final var environment = new LinkedHashMap<String, Object>();
                for (Object pair : list(server.get("env"))) {
                    final var entry = object(pair);
                    environment.put(text(entry.get("name")), text(entry.get("value")));
                }
                config.put("env", environment);
                if (!codex) config.put("type", "stdio");
            } else {
                config.put("url", server.get("url"));
                if (!codex) config.put("type", "http");
            }
            servers.put(text(server.get("name")), config);
        }
        return servers;
    }

    final void tool(final String id, final String title, final String status, final Object input, final Object result) {
        final var update = new LinkedHashMap<String, Object>();
        update.put("sessionUpdate", "tool_call");
        update.put("toolCallId", id);
        update.put("title", title);
        update.put("kind", "other");
        update.put("status", status);
        if (input != null) update.put("rawInput", input);
        if (result != null) {
            final String content = result instanceof String string ? string : jsonValue(result);
            update.put(
                    "content", List.of(Map.of("type", "content", "content", Map.of("type", "text", "text", content))));
        }
        host.update(update);
    }

    static Map<String, Object> checked(final Map<String, Object> response) {
        if (response.containsKey("error"))
            throw new java.util.concurrent.CompletionException(new IOException(
                    response.get("error") instanceof String string
                            ? string
                            : text(object(response.get("error")).get("message"))));
        if (Boolean.FALSE.equals(response.get("success")))
            throw new java.util.concurrent.CompletionException(new IOException(text(response.get("error"))));
        return response;
    }

    static String promptText(final List<Object> blocks) {
        final StringBuilder result = new StringBuilder();
        for (Object raw : blocks) {
            final var block = object(raw);
            if ("text".equals(block.get("type"))) result.append(text(block.get("text")));
            else if ("resource".equals(block.get("type"))) {
                final var resource = object(block.get("resource"));
                result.append("\n\nAttached file: ")
                        .append(text(resource.get("uri")))
                        .append('\n');
                if (resource.containsKey("text")) result.append(text(resource.get("text")));
                else if (text(resource.get("mimeType")).startsWith("text/"))
                    result.append(new String(
                            java.util.Base64.getDecoder().decode(text(resource.get("blob"))),
                            java.nio.charset.StandardCharsets.UTF_8));
                else
                    throw new IllegalArgumentException(
                            "This native provider supports text files and images; other attachment types are unavailable");
            } else if ("resource_link".equals(block.get("type")))
                result.append("\n\nAttached file: ").append(text(block.get("uri")));
        }
        return result.toString();
    }

    @Override
    public void close() {
        failed(new IOException("Native session closed"));
        if (connection != null) connection.close();
    }
}
