package dev.turboism.plugin.turboismwithfx;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.turboism.protocol.json.StrictJson;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Loopback Gateway-protocol adapter for one OpenAI-compatible Chat Completions endpoint. */
final class FxOpenAiAdapter implements AutoCloseable {

    private static final int MAX_REQUEST_BYTES = 4 * 1024 * 1024;
    private static final String CHAT_PATH = "/v1/chat/completions";
    private static final String MODELS_PATH = "/v1/models";

    private final HttpServer server;
    private final ExecutorService executor;
    private final HttpClient client;
    private final FxCustomEndpointSettings settings;
    private final URI baseEndpoint;
    private final URI endpoint;
    private final List<String> configuredModels;
    private volatile java.util.Set<String> availableModels;
    private final AtomicBoolean closed = new AtomicBoolean();

    private FxOpenAiAdapter(
        final HttpServer server,
        final ExecutorService executor,
        final HttpClient client,
        final FxCustomEndpointSettings settings,
        final URI baseEndpoint,
        final URI endpoint,
        final List<String> configuredModels
    ) {
        this.server = server;
        this.executor = executor;
        this.client = client;
        this.settings = settings;
        this.baseEndpoint = baseEndpoint;
        this.endpoint = endpoint;
        this.configuredModels = List.copyOf(configuredModels);
        this.availableModels = java.util.Set.copyOf(configuredModels);
    }

    static FxOpenAiAdapter start(final FxCustomEndpointSettings settings) throws IOException {
        return start(settings, List.of());
    }

    static FxOpenAiAdapter start(
        final FxCustomEndpointSettings settings,
        final List<String> configuredModels
    ) throws IOException {
        final FxCustomEndpointSettings checked = Objects.requireNonNull(settings, "settings");
        final List<String> models = List.copyOf(Objects.requireNonNull(
            configuredModels, "configuredModels"
        ));
        if (!checked.enabled()) throw new IllegalArgumentException("custom endpoint is disabled");
        final URI base = normalizedBase(checked.endpoint());
        final HttpServer server = HttpServer.create(new InetSocketAddress(
            InetAddress.getByName("127.0.0.1"), 0
        ), 0);
        final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
            final Thread thread = new Thread(runnable, "turboism-fx-openai-adapter");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        final URI local = URI.create(
            "http://127.0.0.1:" + server.getAddress().getPort()
        );
        final FxOpenAiAdapter adapter = new FxOpenAiAdapter(
            server,
            executor,
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(),
            checked,
            base,
            local,
            models
        );
        server.createContext("/v3/ai/language-model", adapter::generate);
        server.createContext("/coding-agent/v1/models", adapter::models);
        server.start();
        return adapter;
    }

    URI endpoint() {
        return endpoint;
    }

    static List<String> discoverModels(
        final FxCustomEndpointSettings settings
    ) throws IOException {
        final FxCustomEndpointSettings checked = Objects.requireNonNull(settings, "settings");
        if (!checked.enabled()) throw new IllegalArgumentException("custom endpoint is disabled");
        final URI base = normalizedBase(checked.endpoint());
        final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        final HttpRequest.Builder request = HttpRequest.newBuilder(resolve(base, MODELS_PATH))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json");
        final String key = checked.resolveApiKey();
        if (!key.isBlank()) request.header("Authorization", "Bearer " + key);
        final HttpResponse<byte[]> response;
        try {
            response = client.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("custom provider model discovery was interrupted", failure);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("custom provider model discovery was rejected");
        }
        final Map<String, Object> catalog = object(StrictJson.parse(response.body()));
        final java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        for (Object value : array(catalog.get("data"))) {
            final Map<String, Object> model = object(value);
            result.add(text(model.get("id"), "model id"));
        }
        return List.copyOf(result);
    }

    Map<String, String> fxEnvironment() {
        return Map.of(
            "AI_GATEWAY_API_KEY", "turboism-loopback-adapter",
            "FX_GATEWAY_CHAT_URL", endpoint + "/v3/ai/language-model",
            "FX_GATEWAY_BASE_URL", endpoint.toString()
        );
    }

    boolean hasModel(final String model) {
        return model != null && availableModels.contains(model);
    }

    private void generate(final HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                sendEmpty(exchange, 405);
                return;
            }
            final Object parsed;
            try {
                parsed = StrictJson.parse(readBounded(exchange));
            } catch (IllegalArgumentException failure) {
                sendError(exchange, 400, "invalid_request", "Invalid Gateway request");
                return;
            }
            final Map<String, Object> request;
            try {
                request = openAiRequest(
                    parsed,
                    exchange.getRequestHeaders().getFirst("ai-language-model-id")
                );
            } catch (IllegalArgumentException failure) {
                sendError(exchange, 400, "unsupported_request", failure.getMessage());
                return;
            }
            final HttpRequest upstream;
            try {
                upstream = upstreamRequest(CHAT_PATH, "POST", StrictJson.bytes(request));
            } catch (IllegalStateException failure) {
                sendError(exchange, 400, "missing_api_key", "Custom endpoint API key is unavailable");
                return;
            }
            final HttpResponse<java.io.InputStream> response;
            try {
                response = client.send(upstream, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                sendError(exchange, 503, "upstream_interrupted", "Custom endpoint request was interrupted");
                return;
            } catch (IOException failure) {
                sendError(exchange, 502, "upstream_unavailable", "Custom endpoint is unavailable");
                return;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.headers().firstValue("Retry-After").ifPresent(value ->
                    exchange.getResponseHeaders().set("Retry-After", value)
                );
                response.body().close();
                sendError(
                    exchange,
                    response.statusCode(),
                    "upstream_error",
                    "Custom endpoint rejected the request"
                );
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, 0);
            stream(response.body(), exchange);
        }
    }

    private void models(final HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET");
                sendEmpty(exchange, 405);
                return;
            }
            final ArrayList<Object> data = new ArrayList<>();
            try {
                final HttpResponse<byte[]> response = client.send(
                    upstreamRequest(MODELS_PATH, "GET", null),
                    HttpResponse.BodyHandlers.ofByteArray()
                );
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    final Map<String, Object> catalog = object(StrictJson.parse(response.body()));
                    for (Object value : array(catalog.get("data"))) {
                        final Map<String, Object> model = object(value);
                        final String id = text(model.get("id"), "model id");
                        data.add(gatewayModel(id));
                    }
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            } catch (IOException | IllegalArgumentException | IllegalStateException ignored) {
                // The configured model remains the authoritative fallback.
            }
            for (String model : configuredModels) {
                if (data.stream().noneMatch(value -> model.equals(object(value).get("id")))) {
                    data.add(gatewayModel(model));
                }
            }
            if (!settings.model().isBlank()
                && data.stream().noneMatch(value -> settings.model().equals(object(value).get("id")))) {
                data.add(gatewayModel(settings.model()));
            }
            final java.util.LinkedHashSet<String> published = new java.util.LinkedHashSet<>();
            data.forEach(value -> published.add((String) object(value).get("id")));
            availableModels = java.util.Set.copyOf(published);
            sendJson(exchange, 200, Map.of("object", "list", "data", data));
        }
    }

    private Map<String, Object> openAiRequest(
        final Object parsed,
        final String requestedModel
    ) {
        final Map<String, Object> gateway = object(parsed);
        rejectNonEmpty(gateway, "responseFormat");
        rejectNonEmpty(gateway, "providerOptions");
        final LinkedHashMap<String, Object> request = new LinkedHashMap<>();
        request.put("model", selectedModel(requestedModel));
        request.put("stream", true);
        request.put("messages", messages(array(gateway.get("prompt"))));
        final List<Object> tools = arrayOrEmpty(gateway.get("tools"));
        if (!tools.isEmpty()) request.put("tools", tools(tools));
        final Object toolChoice = gateway.get("toolChoice");
        if (toolChoice != null) request.put("tool_choice", toolChoice(toolChoice));
        final Object max = gateway.get("maxOutputTokens");
        if (max instanceof Number number && number.longValue() > 0L) {
            request.put("max_tokens", number.longValue());
        }
        return request;
    }

    private static List<Object> messages(final List<Object> prompt) {
        final ArrayList<Object> messages = new ArrayList<>();
        for (Object raw : prompt) {
            final Map<String, Object> message = object(raw);
            final String role = text(message.get("role"), "message role");
            switch (role) {
                case "system" -> messages.add(Map.of(
                    "role", "system",
                    "content", contentText(message.get("content"))
                ));
                case "user" -> messages.add(Map.of(
                    "role", "user",
                    "content", contentText(message.get("content"))
                ));
                case "assistant" -> messages.add(assistant(message));
                case "tool" -> messages.addAll(toolResults(message));
                default -> throw new IllegalArgumentException("Unsupported Gateway message role");
            }
        }
        return messages;
    }

    private static Map<String, Object> assistant(final Map<String, Object> message) {
        final LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("role", "assistant");
        final StringBuilder text = new StringBuilder();
        final ArrayList<Object> calls = new ArrayList<>();
        for (Object raw : parts(message.get("content"))) {
            final Map<String, Object> part = object(raw);
            final String type = text(part.get("type"), "assistant content type");
            if ("text".equals(type)) {
                text.append(text(part.get("text"), "assistant text"));
            } else if ("tool-call".equals(type)) {
                calls.add(Map.of(
                    "id", text(part.get("toolCallId"), "tool call id"),
                    "type", "function",
                    "function", Map.of(
                        "name", text(part.get("toolName"), "tool name"),
                        "arguments", StrictJson.stringify(part.get("input"))
                    )
                ));
            } else {
                throw new IllegalArgumentException("Unsupported assistant content");
            }
        }
        if (!text.isEmpty()) result.put("content", text.toString());
        if (!calls.isEmpty()) result.put("tool_calls", calls);
        if (text.isEmpty() && calls.isEmpty()) result.put("content", "");
        return result;
    }

    private static List<Object> toolResults(final Map<String, Object> message) {
        final ArrayList<Object> results = new ArrayList<>();
        for (Object raw : parts(message.get("content"))) {
            final Map<String, Object> part = object(raw);
            if (!"tool-result".equals(part.get("type"))) {
                throw new IllegalArgumentException("Unsupported tool content");
            }
            final Map<String, Object> output = object(part.get("output"));
            if (!"text".equals(output.get("type"))) {
                throw new IllegalArgumentException("Unsupported tool result output");
            }
            results.add(Map.of(
                "role", "tool",
                "tool_call_id", text(part.get("toolCallId"), "tool call id"),
                "content", text(output.get("value"), "tool output")
            ));
        }
        return results;
    }

    private static List<Object> tools(final List<Object> gatewayTools) {
        final ArrayList<Object> result = new ArrayList<>();
        for (Object raw : gatewayTools) {
            final Map<String, Object> tool = object(raw);
            if (!"function".equals(tool.get("type"))) {
                throw new IllegalArgumentException("Unsupported Gateway tool type");
            }
            final LinkedHashMap<String, Object> function = new LinkedHashMap<>();
            function.put("name", text(tool.get("name"), "tool name"));
            final Object description = tool.get("description");
            if (description instanceof String value && !value.isBlank()) {
                function.put("description", value);
            }
            function.put("parameters", object(tool.get("inputSchema")));
            result.add(Map.of("type", "function", "function", function));
        }
        return result;
    }

    private static Object toolChoice(final Object raw) {
        final Map<String, Object> choice = object(raw);
        return switch (text(choice.get("type"), "tool choice")) {
            case "auto" -> "auto";
            case "none" -> "none";
            case "required" -> "required";
            default -> throw new IllegalArgumentException("Unsupported tool choice");
        };
    }

    private void stream(final java.io.InputStream input, final HttpExchange exchange)
        throws IOException {
        final java.io.OutputStream output = exchange.getResponseBody();
        final LinkedHashMap<Integer, ToolBuffer> calls = new LinkedHashMap<>();
        String finishReason = null;
        Map<String, Object> usage = null;
        try (input; BufferedReader reader = new BufferedReader(new InputStreamReader(
            input, StandardCharsets.UTF_8
        ))) {
            for (String line; (line = reader.readLine()) != null;) {
                if (!line.startsWith("data:")) continue;
                final String data = line.substring("data:".length()).strip();
                if (data.isEmpty() || "[DONE]".equals(data)) continue;
                final Map<String, Object> event = object(StrictJson.parse(
                    data.getBytes(StandardCharsets.UTF_8)
                ));
                final Object usageValue = event.get("usage");
                if (usageValue instanceof Map<?, ?>) usage = usage(object(usageValue));
                for (Object rawChoice : arrayOrEmpty(event.get("choices"))) {
                    final Map<String, Object> choice = object(rawChoice);
                    final Object finish = choice.get("finish_reason");
                    if (finish instanceof String value) finishReason = value;
                    final Object deltaValue = choice.get("delta");
                    if (!(deltaValue instanceof Map<?, ?>)) continue;
                    final Map<String, Object> delta = object(deltaValue);
                    final Object content = delta.get("content");
                    if (content instanceof String text && !text.isEmpty()) {
                        sendEvent(output, ordered(
                            "type", "text-delta",
                            "id", "text-1",
                            "delta", text
                        ));
                    }
                    for (Object rawCall : arrayOrEmpty(delta.get("tool_calls"))) {
                        final Map<String, Object> call = object(rawCall);
                        final int index = ((Number) Objects.requireNonNullElse(
                            call.get("index"), Integer.valueOf(calls.size())
                        )).intValue();
                        final ToolBuffer buffer = calls.computeIfAbsent(index, ignored ->
                            new ToolBuffer()
                        );
                        if (call.get("id") instanceof String id) buffer.id = id;
                        if (call.get("function") instanceof Map<?, ?>) {
                            final Map<String, Object> function = object(call.get("function"));
                            if (function.get("name") instanceof String name) buffer.name = name;
                            if (function.get("arguments") instanceof String arguments) {
                                buffer.arguments.append(arguments);
                            }
                        }
                    }
                }
            }
            for (ToolBuffer call : calls.values()) {
                sendEvent(output, ordered(
                    "type", "tool-call",
                    "toolCallId", requireStreamText(call.id, "tool call id"),
                    "toolName", requireStreamText(call.name, "tool name"),
                    "input", StrictJson.parse(call.arguments.toString().getBytes(StandardCharsets.UTF_8))
                ));
            }
            final LinkedHashMap<String, Object> finish = new LinkedHashMap<>();
            finish.put("type", "finish");
            finish.put("finishReason", Map.of("unified", finishReason(finishReason)));
            if (usage != null && !usage.isEmpty()) finish.put("usage", usage);
            sendEvent(output, finish);
            done(output);
        } catch (RuntimeException | IOException failure) {
            sendEvent(output, ordered("type", "error", "error", "Custom endpoint stream failed"));
            sendEvent(output, ordered(
                "type", "finish",
                "finishReason", Map.of("unified", "error")
            ));
            done(output);
        }
    }

    private HttpRequest upstreamRequest(
        final String path,
        final String method,
        final byte[] body
    ) {
        final String key = settings.resolveApiKey();
        final HttpRequest.Builder request = HttpRequest.newBuilder(resolve(baseEndpoint, path))
            .timeout(Duration.ofMinutes(5))
            .header("Accept", "application/json, text/event-stream");
        if (!key.isBlank()) request.header("Authorization", "Bearer " + key);
        if (body == null) return request.GET().build();
        return request.header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
    }

    private String selectedModel(final String requestedModel) {
        if (requestedModel == null || requestedModel.isBlank()) {
            if (settings.model().isBlank()) {
                throw new IllegalArgumentException("Select a model before sending a prompt");
            }
            return settings.model();
        }
        final String model = requestedModel.strip();
        if (model.length() > 512 || model.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Requested model id is invalid");
        }
        return model;
    }

    private static URI resolve(final URI baseEndpoint, final String path) {
        final String base = baseEndpoint.toString();
        return URI.create(base + (base.endsWith("/") ? path.substring(1) : "/" + path));
    }

    private static URI normalizedBase(final String value) {
        final URI raw = URI.create(value);
        String path = Objects.requireNonNullElse(raw.getPath(), "");
        for (String suffix : List.of(CHAT_PATH, "/v1", MODELS_PATH)) {
            if (path.endsWith(suffix)) {
                path = path.substring(0, path.length() - suffix.length());
                break;
            }
        }
        if (!path.endsWith("/")) path += "/";
        try {
            return new URI(raw.getScheme(), null, raw.getHost(), raw.getPort(), path, null, null);
        } catch (java.net.URISyntaxException failure) {
            throw new IllegalArgumentException("custom endpoint URL is invalid", failure);
        }
    }

    private static Map<String, Object> gatewayModel(final String id) {
        return ordered("id", id, "type", "language", "tags", List.of("tool-use"));
    }

    /** Emitted Gateway events keep a deterministic field order for stable diagnostics. */
    private static Map<String, Object> ordered(final Object... pairs) {
        final LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put((String) pairs[index], pairs[index + 1]);
        }
        return result;
    }

    private static Map<String, Object> usage(final Map<String, Object> upstream) {
        final LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        putUsage(result, "inputTokens", upstream.get("prompt_tokens"));
        putUsage(result, "outputTokens", upstream.get("completion_tokens"));
        return result;
    }

    private static void putUsage(
        final Map<String, Object> target,
        final String name,
        final Object value
    ) {
        if (value instanceof Number number && number.longValue() >= 0L) {
            target.put(name, Map.of("total", number.longValue()));
        }
    }

    private static String finishReason(final String reason) {
        if (reason == null) return "other";
        return switch (reason) {
            case "stop" -> "stop";
            case "length" -> "length";
            case "content_filter" -> "content-filter";
            case "tool_calls" -> "tool-calls";
            default -> "other";
        };
    }

    private static String contentText(final Object value) {
        if (value instanceof String text) return text;
        final StringBuilder result = new StringBuilder();
        for (Object raw : parts(value)) {
            final Map<String, Object> part = object(raw);
            if (!"text".equals(part.get("type"))) {
                throw new IllegalArgumentException("Unsupported message content");
            }
            result.append(text(part.get("text"), "message text"));
        }
        return result.toString();
    }

    private static List<Object> parts(final Object value) {
        if (value instanceof List<?> list) return new ArrayList<>(list);
        throw new IllegalArgumentException("Message content must be text parts");
    }

    /**
     * fx sends a Gateway reasoning level that has no portable OpenAI Chat Completions equivalent.
     * Forwarding a guessed {@code reasoning_effort} would break non-reasoning models, so the adapter
     * deliberately drops it and documents the omission rather than translating it silently.
     */
    private static void rejectNonEmpty(final Map<String, Object> request, final String key) {
        final Object value = request.get(key);
        if (value == null) return;
        if (value instanceof String text && text.isBlank()) return;
        if (value instanceof Map<?, ?> map && map.isEmpty()) return;
        throw new IllegalArgumentException("Unsupported Gateway field: " + key);
    }

    private byte[] readBounded(final HttpExchange exchange) throws IOException {
        final byte[] bytes = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (bytes.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("request is too large");
        }
        return bytes;
    }

    private static void sendEvent(final java.io.OutputStream output, final Object value)
        throws IOException {
        output.write(("data: " + StrictJson.stringify(value) + "\n\n")
            .getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static void done(final java.io.OutputStream output) throws IOException {
        output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static void sendError(
        final HttpExchange exchange,
        final int status,
        final String code,
        final String message
    ) throws IOException {
        sendJson(exchange, status, Map.of(
            "error", Map.of("message", message, "type", "turboism_adapter", "code", code)
        ));
    }

    private static void sendJson(
        final HttpExchange exchange,
        final int status,
        final Object value
    ) throws IOException {
        final byte[] bytes = StrictJson.bytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void sendEmpty(final HttpExchange exchange, final int status) throws IOException {
        exchange.sendResponseHeaders(status, -1L);
    }

    private static String requireStreamText(final String value, final String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " missing");
        return value;
    }

    private static String text(final Object value, final String name) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return text;
    }

    private static Map<String, Object> object(final Object value) {
        if (!(value instanceof Map<?, ?> raw)) throw new IllegalArgumentException("JSON object required");
        final LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (!(key instanceof String text)) throw new IllegalArgumentException("JSON key invalid");
            result.put(text, item);
        });
        return result;
    }

    private static List<Object> array(final Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("JSON array required");
        return new ArrayList<>(list);
    }

    private static List<Object> arrayOrEmpty(final Object value) {
        return value == null ? List.of() : array(value);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        server.stop(0);
        executor.shutdownNow();
    }

    private static final class ToolBuffer {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }
}
