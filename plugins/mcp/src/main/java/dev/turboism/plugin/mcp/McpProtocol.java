package dev.turboism.plugin.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Stateless MCP 2025-11-25 JSON-RPC dispatcher. */
final class McpProtocol {

    static final String VERSION = "2025-11-25";
    static final Set<String> SUPPORTED_VERSIONS = Set.of(
        VERSION,
        "2025-06-18",
        "2025-03-26"
    );

    private final McpToolCatalog tools;
    private final McpResourceCatalog resources;
    private final McpPromptCatalog prompts;
    private final McpRequestRegistry requests;

    McpProtocol(final McpTools legacyTools) {
        this(
            new McpToolCatalog(legacyTools.definitions(), legacyTools::call),
            McpResourceCatalog.empty(),
            McpPromptCatalog.defaults(),
            new McpRequestRegistry()
        );
    }

    McpProtocol(
        final McpToolCatalog tools,
        final McpResourceCatalog resources,
        final McpPromptCatalog prompts
    ) {
        this(tools, resources, prompts, new McpRequestRegistry());
    }

    McpProtocol(
        final McpToolCatalog tools,
        final McpResourceCatalog resources,
        final McpPromptCatalog prompts,
        final McpRequestRegistry requests
    ) {
        this.tools = Objects.requireNonNull(tools, "tools");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.prompts = Objects.requireNonNull(prompts, "prompts");
        this.requests = Objects.requireNonNull(requests, "requests");
    }

    static McpProtocol forCatalogs(
        final McpToolCatalog tools,
        final McpResourceCatalog resources,
        final McpPromptCatalog prompts
    ) {
        return new McpProtocol(tools, resources, prompts);
    }

    static McpProtocol forCatalogs(
        final McpToolCatalog tools,
        final McpResourceCatalog resources,
        final McpPromptCatalog prompts,
        final McpRequestRegistry requests
    ) {
        return new McpProtocol(tools, resources, prompts, requests);
    }

    Outcome handle(final Object message) {
        return handle(message, "local");
    }

    Outcome handle(final Object message, final String sessionId) {
        final String cursorScope = Objects.requireNonNull(sessionId, "sessionId");
        if (!(message instanceof Map<?, ?> raw)) {
            return Outcome.response(200, error(null, -32600, "Invalid Request", null));
        }
        final Map<String, Object> request;
        try {
            request = stringMap(raw, "request");
        } catch (IllegalArgumentException failure) {
            return Outcome.response(200, error(null, -32600, "Invalid Request", failure.getMessage()));
        }
        final Object id = request.get("id");
        final boolean notification = !request.containsKey("id");
        if (!"2.0".equals(request.get("jsonrpc"))) {
            return notification
                ? Outcome.accepted()
                : Outcome.response(200, error(id, -32600, "Invalid Request", "jsonrpc must be 2.0"));
        }
        final Object methodValue = request.get("method");
        if (!(methodValue instanceof String method) || method.isBlank()) {
            return notification
                ? Outcome.accepted()
                : Outcome.response(200, error(id, -32600, "Invalid Request", "method is required"));
        }
        if (notification) {
            if ("notifications/cancelled".equals(method)) {
                try {
                    final Map<String, Object> params = params(request.get("params"));
                    only(params, "requestId", "reason", "_meta");
                    final Object requestId = params.get("requestId");
                    if (requestId == null) {
                        throw new IllegalArgumentException("requestId is required");
                    }
                    requests.cancel(cursorScope, requestId);
                } catch (IllegalArgumentException ignored) {
                    // JSON-RPC notifications never receive an error response.
                }
            }
            return Outcome.accepted();
        }

        try (McpRequestRegistry.Scope ignored = requests.enter(cursorScope, id)) {
            final Map<String, Object> params = params(request.get("params"));
            final Object result = switch (method) {
                case "initialize" -> initialize(params);
                case "ping" -> Map.of();
                case "tools/list" -> listTools(params, cursorScope);
                case "tools/call" -> callTool(params);
                case "resources/list" -> listResources(params, cursorScope);
                case "resources/templates/list" -> listResourceTemplates(params, cursorScope);
                case "resources/read" -> readResource(params);
                case "prompts/list" -> listPrompts(params, cursorScope);
                case "prompts/get" -> getPrompt(params);
                default -> throw new MethodNotFound(method);
            };
            return Outcome.response(200, success(id, result));
        } catch (McpResourceCatalog.ResourceNotFound failure) {
            return Outcome.response(200, error(id, -32002, "Resource not found", failure.getMessage()));
        } catch (McpResourceCatalog.ResourceFailure failure) {
            return Outcome.response(200, resourceFailure(id, failure));
        } catch (MethodNotFound failure) {
            return Outcome.response(200, error(id, -32601, "Method not found", failure.getMessage()));
        } catch (IllegalArgumentException failure) {
            return Outcome.response(200, error(id, -32602, "Invalid params", failure.getMessage()));
        } catch (java.util.concurrent.CancellationException failure) {
            return "tools/call".equals(method)
                ? Outcome.response(200, success(id, cancelledToolResult()))
                : Outcome.response(200, error(id, -32800, "Request cancelled", null));
        } catch (RuntimeException failure) {
            return Outcome.response(200, error(id, -32603, "Internal error", "request failed"));
        }
    }

    static Map<String, Object> parseError(final String detail) {
        return error(null, -32700, "Parse error", detail);
    }

    private static Map<String, Object> resourceFailure(
        final Object id,
        final McpResourceCatalog.ResourceFailure failure
    ) {
        return switch (failure.kind()) {
            case PERMISSION_DENIED -> error(
                id,
                -32001,
                "Resource permission denied",
                failure.getMessage()
            );
            case UNAVAILABLE -> error(
                id,
                -32003,
                "Resource unavailable",
                failure.getMessage()
            );
            case TIMEOUT -> error(id, -32004, "Resource read timed out", failure.getMessage());
            case FAILED -> error(id, -32603, "Internal error", "resource read failed");
        };
    }

    static java.util.Optional<String> negotiatedVersion(final Outcome outcome) {
        if (!(outcome.body() instanceof Map<?, ?> envelope)) return java.util.Optional.empty();
        if (!(envelope.get("result") instanceof Map<?, ?> result)) return java.util.Optional.empty();
        final Object value = result.get("protocolVersion");
        return value instanceof String version && SUPPORTED_VERSIONS.contains(version)
            ? java.util.Optional.of(version) : java.util.Optional.empty();
    }

    private Map<String, Object> initialize(final Map<String, Object> params) {
        only(params, "protocolVersion", "capabilities", "clientInfo", "_meta");
        final Object requested = params.get("protocolVersion");
        if (!(requested instanceof String version) || version.isBlank()) {
            throw new IllegalArgumentException("protocolVersion is required");
        }
        if (params.containsKey("capabilities") && !(params.get("capabilities") instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("capabilities must be an object");
        }
        if (params.containsKey("clientInfo") && !(params.get("clientInfo") instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("clientInfo must be an object");
        }
        final String negotiated = SUPPORTED_VERSIONS.contains(version) ? version : VERSION;
        return linked(
            entry("protocolVersion", negotiated),
            entry("capabilities", linked(
                entry("tools", linked(entry("listChanged", false))),
                entry("resources", linked(entry("listChanged", false))),
                entry("prompts", linked(entry("listChanged", false)))
            )),
            entry("serverInfo", linked(
                entry("name", "turboism-mcp"),
                entry("title", "Turboism MCP Server"),
                entry("version", "0.2.0"),
                entry("description", "Typed Turboism Cubism resources, workflows, and authoring operations.")
            )),
            entry("instructions",
                "Read turboism:// resources before mutations. Use stable Cubism IDs, respect returned "
                    + "generation/revision preconditions, and re-read resources after writes.")
        );
    }

    private Map<String, Object> listTools(
        final Map<String, Object> params,
        final String sessionId
    ) {
        return page("tools", tools.definitions(), params, sessionId);
    }

    private Map<String, Object> callTool(final Map<String, Object> params) {
        only(params, "name", "arguments", "_meta", "task");
        final Object nameValue = params.get("name");
        if (!(nameValue instanceof String name) || name.isBlank()) {
            throw new IllegalArgumentException("tool name is required");
        }
        final Map<String, Object> arguments = params.containsKey("arguments")
            ? stringMap(requiredMap(params.get("arguments"), "arguments"), "arguments")
            : Map.of();
        return tools.call(name, arguments);
    }

    private Map<String, Object> listResources(
        final Map<String, Object> params,
        final String sessionId
    ) {
        return page("resources", resources.resources(), params, sessionId);
    }

    private Map<String, Object> listResourceTemplates(
        final Map<String, Object> params,
        final String sessionId
    ) {
        return page("resourceTemplates", resources.templates(), params, sessionId);
    }

    private Map<String, Object> readResource(final Map<String, Object> params) {
        only(params, "uri", "_meta");
        final String uri = requiredString(params, "uri");
        return linked(entry("contents", resources.read(uri)));
    }

    private Map<String, Object> listPrompts(
        final Map<String, Object> params,
        final String sessionId
    ) {
        return page("prompts", prompts.definitions(), params, sessionId);
    }

    private Map<String, Object> getPrompt(final Map<String, Object> params) {
        only(params, "name", "arguments", "_meta");
        final String name = requiredString(params, "name");
        final Map<String, Object> arguments = params.containsKey("arguments")
            ? stringMap(requiredMap(params.get("arguments"), "arguments"), "arguments")
            : Map.of();
        return prompts.get(name, arguments);
    }

    private static Map<String, Object> page(
        final String key,
        final java.util.List<Map<String, Object>> values,
        final Map<String, Object> params,
        final String sessionId
    ) {
        only(params, "cursor", "_meta");
        final int offset = cursorOffset(params.get("cursor"), key, sessionId, values.size());
        final int end = Math.min(offset + 50, values.size());
        final LinkedHashMap<String, Object> result = linked(
            entry(key, new ArrayList<>(values.subList(offset, end)))
        );
        if (end < values.size()) {
            result.put("nextCursor", encodeCursor(sessionId, key, end));
        }
        return result;
    }

    private static int cursorOffset(
        final Object value,
        final String key,
        final String sessionId,
        final int size
    ) {
        if (value == null) return 0;
        if (!(value instanceof String cursor) || cursor.isBlank()) {
            throw new IllegalArgumentException("cursor must be a non-blank string");
        }
        final String decoded;
        try {
            decoded = new String(
                java.util.Base64.getUrlDecoder().decode(cursor),
                java.nio.charset.StandardCharsets.UTF_8
            );
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("cursor is invalid", failure);
        }
        final String[] components = decoded.split("\\n", -1);
        if (components.length != 3
            || !sessionId.equals(components[0])
            || !key.equals(components[1])) {
            throw new IllegalArgumentException("cursor is not valid for this session and method");
        }
        try {
            final int offset = Integer.parseInt(components[2]);
            if (offset <= 0 || offset >= size) {
                throw new IllegalArgumentException("cursor offset is out of range");
            }
            return offset;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("cursor offset is invalid", failure);
        }
    }

    private static String encodeCursor(
        final String sessionId,
        final String key,
        final int offset
    ) {
        final byte[] payload = (sessionId + "\n" + key + "\n" + offset)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
    }

    private static String requiredString(final Map<String, Object> params, final String key) {
        final Object value = params.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return text;
    }

    private static Map<String, Object> params(final Object value) {
        if (value == null) return Map.of();
        return stringMap(requiredMap(value, "params"), "params");
    }

    private static Map<?, ?> requiredMap(final Object value, final String label) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(label + " must be an object");
        }
        return map;
    }

    private static Map<String, Object> stringMap(final Map<?, ?> raw, final String label) {
        final LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(label + " contains a non-string key");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static void only(final Map<String, Object> values, final String... allowed) {
        final java.util.Set<String> names = java.util.Set.of(allowed);
        for (String key : values.keySet()) {
            if (!names.contains(key)) throw new IllegalArgumentException("unknown parameter: " + key);
        }
    }

    private static Map<String, Object> success(final Object id, final Object result) {
        return linked(entry("jsonrpc", "2.0"), entry("id", id), entry("result", result));
    }

    private static Map<String, Object> cancelledToolResult() {
        final Map<String, Object> output = linked(
            entry("ok", false),
            entry("error", linked(
                entry("code", "CANCELLED"),
                entry("message", "MCP request was cancelled before host submission")
            ))
        );
        return linked(
            entry("content", java.util.List.of(linked(
                entry("type", "text"),
                entry("text", Json.stringify(output))
            ))),
            entry("structuredContent", output),
            entry("isError", true)
        );
    }

    private static Map<String, Object> error(
        final Object id,
        final int code,
        final String message,
        final Object data
    ) {
        final LinkedHashMap<String, Object> failure = linked(
            entry("code", code), entry("message", message)
        );
        if (data != null) failure.put("data", data);
        return linked(entry("jsonrpc", "2.0"), entry("id", id), entry("error", failure));
    }

    @SafeVarargs
    private static LinkedHashMap<String, Object> linked(
        final Map.Entry<String, Object>... entries
    ) {
        final LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : entries) result.put(entry.getKey(), entry.getValue());
        return result;
    }

    private static Map.Entry<String, Object> entry(final String key, final Object value) {
        return new java.util.AbstractMap.SimpleImmutableEntry<>(key, value);
    }

    record Outcome(int status, Object body) {
        static Outcome accepted() { return new Outcome(202, null); }
        static Outcome response(final int status, final Object body) {
            return new Outcome(status, Objects.requireNonNull(body, "body"));
        }
    }

    private static final class MethodNotFound extends RuntimeException {
        private MethodNotFound(final String method) { super("Unknown MCP method: " + method); }
    }
}
