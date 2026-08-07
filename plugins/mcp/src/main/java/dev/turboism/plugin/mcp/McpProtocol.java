package dev.turboism.plugin.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Stateless MCP 2025-11-25 JSON-RPC dispatcher. */
final class McpProtocol {

    static final String VERSION = "2025-11-25";

    private final McpTools tools;

    McpProtocol(final McpTools tools) {
        this.tools = Objects.requireNonNull(tools, "tools");
    }

    Outcome handle(final Object message) {
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
            return Outcome.accepted();
        }

        try {
            final Map<String, Object> params = params(request.get("params"));
            final Object result = switch (method) {
                case "initialize" -> initialize(params);
                case "ping" -> Map.of();
                case "tools/list" -> listTools(params);
                case "tools/call" -> callTool(params);
                default -> throw new MethodNotFound(method);
            };
            return Outcome.response(200, success(id, result));
        } catch (MethodNotFound failure) {
            return Outcome.response(200, error(id, -32601, "Method not found", failure.getMessage()));
        } catch (IllegalArgumentException failure) {
            return Outcome.response(200, error(id, -32602, "Invalid params", failure.getMessage()));
        } catch (RuntimeException failure) {
            return Outcome.response(200, error(id, -32603, "Internal error", "request failed"));
        }
    }

    static Map<String, Object> parseError(final String detail) {
        return error(null, -32700, "Parse error", detail);
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
        return linked(
            entry("protocolVersion", VERSION),
            entry("capabilities", linked(entry("tools", linked(entry("listChanged", false))))),
            entry("serverInfo", linked(
                entry("name", "turboism-mcp"),
                entry("title", "Turboism MCP Server"),
                entry("version", "0.1.0"),
                entry("description", "Typed access to Turboism Cubism model-object operations.")
            )),
            entry("instructions",
                "Use stable Cubism IDs returned by turboism_model_objects_list. "
                    + "Structural operations fail closed when the active host provider is not verified.")
        );
    }

    private Map<String, Object> listTools(final Map<String, Object> params) {
        only(params, "cursor", "_meta");
        if (params.containsKey("cursor") && params.get("cursor") != null
            && !(params.get("cursor") instanceof String)) {
            throw new IllegalArgumentException("cursor must be a string");
        }
        return linked(entry("tools", new ArrayList<>(tools.definitions())));
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
        return linked(
            entry("jsonrpc", "2.0"),
            entry("id", id),
            entry("result", result)
        );
    }

    private static Map<String, Object> error(
        final Object id,
        final int code,
        final String message,
        final Object data
    ) {
        final LinkedHashMap<String, Object> failure = linked(
            entry("code", code),
            entry("message", message)
        );
        if (data != null) failure.put("data", data);
        return linked(
            entry("jsonrpc", "2.0"),
            entry("id", id),
            entry("error", failure)
        );
    }

    @SafeVarargs
    private static LinkedHashMap<String, Object> linked(
        final Map.Entry<String, Object>... entries
    ) {
        final LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : entries) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static Map.Entry<String, Object> entry(final String key, final Object value) {
        return new java.util.AbstractMap.SimpleImmutableEntry<>(key, value);
    }

    record Outcome(int status, Object body) {
        static Outcome accepted() {
            return new Outcome(202, null);
        }

        static Outcome response(final int status, final Object body) {
            return new Outcome(status, Objects.requireNonNull(body, "body"));
        }
    }

    private static final class MethodNotFound extends RuntimeException {
        private MethodNotFound(final String method) {
            super("Unknown MCP method: " + method);
        }
    }
}
