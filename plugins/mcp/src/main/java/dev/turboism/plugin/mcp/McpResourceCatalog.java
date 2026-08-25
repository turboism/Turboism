package dev.turboism.plugin.mcp;

import dev.turboism.sdk.permission.CubismPermissionException;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Discoverable MCP resources and resource templates. */
final class McpResourceCatalog {

    @FunctionalInterface
    interface Reader {
        List<Map<String, Object>> read(String uri);
    }

    private final List<Map<String, Object>> resources;
    private final List<Map<String, Object>> templates;
    private final Reader reader;

    McpResourceCatalog(
        final List<Map<String, Object>> resources,
        final List<Map<String, Object>> templates,
        final Reader reader
    ) {
        this.resources = List.copyOf(Objects.requireNonNull(resources, "resources"));
        this.templates = List.copyOf(Objects.requireNonNull(templates, "templates"));
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    static McpResourceCatalog empty() {
        return new McpResourceCatalog(List.of(), List.of(), uri -> {
            throw new ResourceNotFound(uri);
        });
    }

    List<Map<String, Object>> resources() {
        return resources;
    }

    List<Map<String, Object>> templates() {
        return templates;
    }

    static McpResourceCatalog combine(final McpResourceCatalog... catalogs) {
        Objects.requireNonNull(catalogs, "catalogs");
        final java.util.ArrayList<Map<String, Object>> resources = new java.util.ArrayList<>();
        final java.util.ArrayList<Map<String, Object>> templates = new java.util.ArrayList<>();
        final java.util.ArrayList<McpResourceCatalog> readers = new java.util.ArrayList<>();
        for (McpResourceCatalog catalog : catalogs) {
            final McpResourceCatalog checked = Objects.requireNonNull(catalog, "catalog");
            resources.addAll(checked.resources());
            templates.addAll(checked.templates());
            readers.add(checked);
        }
        return new McpResourceCatalog(resources, templates, uri -> {
            for (McpResourceCatalog reader : readers) {
                try {
                    return reader.read(uri);
                } catch (ResourceNotFound ignored) {
                    // Try the next resource domain.
                }
            }
            throw new ResourceNotFound(uri);
        });
    }

    List<Map<String, Object>> read(final String uri) {
        final List<Map<String, Object>> contents;
        try {
            contents = reader.read(uri);
        } catch (RuntimeException failure) {
            throw classify(failure);
        }
        classifyStructuredFailure(contents);
        return contents;
    }

    private static void classifyStructuredFailure(final List<Map<String, Object>> contents) {
        if (contents.size() != 1) return;
        final Object textValue = contents.get(0).get("text");
        if (!(textValue instanceof String text)) return;
        final Object parsed;
        try {
            parsed = Json.parse(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException ignored) {
            return;
        }
        if (!(parsed instanceof Map<?, ?> output) || !Boolean.FALSE.equals(output.get("ok"))) return;
        if (!(output.get("error") instanceof Map<?, ?> error)) return;
        final Object codeValue = error.get("code");
        final Object messageValue = error.get("message");
        final String message = messageValue instanceof String value && !value.isBlank()
            ? value : "resource read failed";
        if ("PERMISSION_DENIED".equals(codeValue)) {
            throw new ResourceFailure(ResourceFailure.Kind.PERMISSION_DENIED, message, null);
        }
        if ("UNAVAILABLE".equals(codeValue) || "UNSUPPORTED".equals(codeValue)) {
            throw new ResourceFailure(ResourceFailure.Kind.UNAVAILABLE, message, null);
        }
        throw new ResourceFailure(ResourceFailure.Kind.FAILED, "resource read failed", null);
    }

    static RuntimeException classify(final RuntimeException failure) {
        Objects.requireNonNull(failure, "failure");
        if (failure instanceof ResourceFailure
            || failure instanceof ResourceNotFound
            || failure instanceof java.util.concurrent.CancellationException) {
            return failure;
        }
        if (failure instanceof CubismPermissionException || failure instanceof SecurityException) {
            return new ResourceFailure(
                ResourceFailure.Kind.PERMISSION_DENIED,
                safeMessage(failure),
                failure
            );
        }
        if (failure instanceof UnsupportedOperationException) {
            return new ResourceFailure(
                ResourceFailure.Kind.UNAVAILABLE,
                safeMessage(failure),
                failure
            );
        }
        if (failure instanceof McpExecutionBridge.ExecutionFailure) {
            final String message = safeMessage(failure);
            if (message.toLowerCase(java.util.Locale.ROOT).contains("timed out")) {
                return new ResourceFailure(ResourceFailure.Kind.TIMEOUT, message, failure);
            }
        }
        return new ResourceFailure(
            ResourceFailure.Kind.FAILED,
            "resource read failed",
            failure
        );
    }

    private static String safeMessage(final RuntimeException failure) {
        final String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    static final class ResourceNotFound extends RuntimeException {
        ResourceNotFound(final String uri) {
            super("Resource not found: " + uri);
        }
    }

    static final class ResourceFailure extends RuntimeException {
        enum Kind {
            PERMISSION_DENIED,
            UNAVAILABLE,
            TIMEOUT,
            FAILED
        }

        private final Kind kind;

        ResourceFailure(final Kind kind, final String message, final Throwable cause) {
            super(message, cause);
            this.kind = Objects.requireNonNull(kind, "kind");
        }

        Kind kind() {
            return kind;
        }
    }
}
