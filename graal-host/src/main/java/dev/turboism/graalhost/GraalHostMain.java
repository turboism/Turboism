package dev.turboism.graalhost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Persistent Turboism Graal execution process.
 *
 * <p>Protocol transport is newline-delimited JSON. Standard output is protocol-only;
 * guest stdout/stderr is captured and returned in terminal execution messages.</p>
 */
public final class GraalHostMain {

    static final int PROTOCOL_VERSION = 1;
    private static final int MAX_MESSAGE_CHARS = 4 * 1024 * 1024;
    private static final int MAX_SOURCE_CHARS = 2 * 1024 * 1024;
    private static final Duration HOST_CALL_TIMEOUT = Duration.ofSeconds(30);

    private final ObjectMapper mapper = new ObjectMapper();
    private final BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    private final BufferedWriter output = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
    private final ReflectiveGraalJsRuntime runtime = new ReflectiveGraalJsRuntime(mapper);
    private final ExecutorService executions = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "turboism-graal-execution");
        thread.setDaemon(false);
        return thread;
    });
    private final Map<String, ReflectiveGraalJsRuntime.ExecutionControl> active = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<String>> hostCalls = new ConcurrentHashMap<>();
    private final AtomicInteger callSequence = new AtomicInteger(1);
    private volatile boolean closing;

    public static void main(final String[] args) throws Exception {
        new GraalHostMain().run();
    }

    private void run() throws Exception {
        send(ready());
        try {
            String line;
            while (!closing && (line = input.readLine()) != null) {
                if (line.length() > MAX_MESSAGE_CHARS) {
                    send(protocolFailure("MESSAGE_TOO_LARGE", "Protocol message exceeded the size limit."));
                    continue;
                }
                if (line.isBlank()) {
                    continue;
                }
                final JsonNode message;
                try {
                    message = mapper.readTree(line);
                } catch (IOException malformed) {
                    send(protocolFailure("INVALID_JSON", "Malformed protocol JSON."));
                    continue;
                }
                handle(message);
            }
        } finally {
            closing = true;
            active.values().forEach(runtime::cancel);
            executions.shutdownNow();
            executions.awaitTermination(2, TimeUnit.SECONDS);
            hostCalls.values().forEach(future -> future.completeExceptionally(
                new IllegalStateException("Graal host is shutting down")
            ));
            hostCalls.clear();
        }
    }

    private void handle(final JsonNode message) throws IOException {
        final String type = text(message, "type");
        switch (type) {
            case "RUN" -> scheduleRun(message);
            case "CANCEL" -> cancel(message);
            case "HOST_RESULT" -> hostResult(message);
            case "HOST_ERROR" -> hostError(message);
            case "PING" -> send(simple("PONG"));
            case "SHUTDOWN" -> closing = true;
            default -> send(protocolFailure("UNKNOWN_MESSAGE", "Unknown protocol message type: " + type));
        }
    }

    private void scheduleRun(final JsonNode message) throws IOException {
        final String executionId = text(message, "executionId");
        final String scriptId = text(message, "scriptId");
        final String source = text(message, "source");
        if (executionId.isBlank() || executionId.length() > 128) {
            send(executionFailure(executionId, "FAILED", "INVALID_EXECUTION_ID", "Invalid execution id.", ""));
            return;
        }
        if (source.length() > MAX_SOURCE_CHARS) {
            send(executionFailure(executionId, "FAILED", "SCRIPT_TOO_LARGE", "Script source exceeded 2 MiB.", ""));
            return;
        }
        final Map<String, String> arguments;
        try {
            arguments = arguments(message.path("arguments"));
        } catch (IllegalArgumentException invalid) {
            send(executionFailure(executionId, "FAILED", "INVALID_ARGUMENTS", invalid.getMessage(), ""));
            return;
        }
        final ReflectiveGraalJsRuntime.ExecutionControl control = new ReflectiveGraalJsRuntime.ExecutionControl();
        if (active.putIfAbsent(executionId, control) != null) {
            send(executionFailure(executionId, "FAILED", "DUPLICATE_EXECUTION", "Execution id is already active.", ""));
            return;
        }
        executions.execute(() -> execute(executionId, scriptId, source, arguments, control));
    }

    private void execute(
        final String executionId,
        final String scriptId,
        final String source,
        final Map<String, String> arguments,
        final ReflectiveGraalJsRuntime.ExecutionControl control
    ) {
        try {
            final ReflectiveGraalJsRuntime.ExecutionResult result = runtime.execute(
                source,
                arguments,
                (operation, payloadJson) -> callHost(executionId, operation, payloadJson),
                control
            );
            final ObjectNode message;
            if (result.status() == ReflectiveGraalJsRuntime.Status.SUCCEEDED) {
                message = mapper.createObjectNode();
                message.put("type", "COMPLETE");
                message.put("executionId", executionId);
                message.put("scriptId", scriptId);
                message.put("status", "SUCCEEDED");
                message.put("output", result.output());
            } else {
                message = executionFailure(
                    executionId,
                    result.status().name(),
                    result.code(),
                    result.message(),
                    result.output()
                );
                message.put("scriptId", scriptId);
            }
            send(message);
        } catch (Throwable failure) {
            try {
                send(executionFailure(
                    executionId,
                    "FAILED",
                    "GRAAL_HOST_EXECUTION_FAILED",
                    safeMessage(failure, "Graal host execution failed."),
                    ""
                ));
            } catch (IOException ignored) {
                closing = true;
            }
        } finally {
            active.remove(executionId, control);
        }
    }

    private String callHost(
        final String executionId,
        final String operation,
        final String payloadJson
    ) throws Exception {
        if (operation == null || operation.isBlank() || operation.length() > 256) {
            throw new IllegalArgumentException("Invalid host operation");
        }
        if (payloadJson == null || payloadJson.length() > 512 * 1024) {
            throw new IllegalArgumentException("Host call payload exceeded the size limit");
        }
        final String callId = executionId + ":" + callSequence.getAndIncrement();
        final CompletableFuture<String> future = new CompletableFuture<>();
        hostCalls.put(callId, future);
        final ObjectNode request = mapper.createObjectNode();
        request.put("type", "HOST_CALL");
        request.put("executionId", executionId);
        request.put("callId", callId);
        request.put("operation", operation);
        request.put("payload", payloadJson);
        send(request);
        try {
            return future.get(HOST_CALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            throw new IllegalStateException("Timed out waiting for Turboism host operation " + operation, timeout);
        } catch (ExecutionException failed) {
            final Throwable cause = failed.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException("Host call failed", cause);
        } finally {
            hostCalls.remove(callId, future);
        }
    }

    private void hostResult(final JsonNode message) {
        final String callId = text(message, "callId");
        final CompletableFuture<String> future = hostCalls.remove(callId);
        if (future != null) {
            future.complete(text(message, "result"));
        }
    }

    private void hostError(final JsonNode message) {
        final String callId = text(message, "callId");
        final CompletableFuture<String> future = hostCalls.remove(callId);
        if (future != null) {
            future.completeExceptionally(new HostCallException(
                text(message, "code"),
                text(message, "message")
            ));
        }
    }

    private void cancel(final JsonNode message) {
        final String executionId = text(message, "executionId");
        final ReflectiveGraalJsRuntime.ExecutionControl control = active.get(executionId);
        if (control != null) {
            runtime.cancel(control);
        }
    }

    private ObjectNode ready() {
        final ReflectiveGraalJsRuntime.Availability availability = runtime.availability();
        final ObjectNode node = mapper.createObjectNode();
        node.put("type", "READY");
        node.put("protocolVersion", PROTOCOL_VERSION);
        node.put("graalAvailable", availability.available());
        node.put("detail", availability.detail());
        node.put("javaVersion", System.getProperty("java.version", "unknown"));
        node.put("javaVmName", System.getProperty("java.vm.name", "unknown"));
        return node;
    }

    private ObjectNode simple(final String type) {
        final ObjectNode node = mapper.createObjectNode();
        node.put("type", type);
        node.put("protocolVersion", PROTOCOL_VERSION);
        return node;
    }

    private ObjectNode protocolFailure(final String code, final String message) {
        final ObjectNode node = simple("PROTOCOL_ERROR");
        node.put("code", code);
        node.put("message", message);
        return node;
    }

    private ObjectNode executionFailure(
        final String executionId,
        final String status,
        final String code,
        final String message,
        final String outputText
    ) {
        final ObjectNode node = mapper.createObjectNode();
        node.put("type", "FAILED");
        node.put("executionId", executionId == null ? "" : executionId);
        node.put("status", status);
        node.put("code", code);
        node.put("message", message);
        node.put("output", outputText == null ? "" : outputText);
        return node;
    }

    private synchronized void send(final JsonNode message) throws IOException {
        final String encoded = mapper.writeValueAsString(message);
        if (encoded.length() > MAX_MESSAGE_CHARS) {
            throw new IOException("Outgoing protocol message exceeded the size limit");
        }
        output.write(encoded);
        output.newLine();
        output.flush();
    }

    private static Map<String, String> arguments(final JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject() || node.size() > 64) {
            throw new IllegalArgumentException("Script arguments must be an object with at most 64 entries.");
        }
        final Map<String, String> result = new HashMap<>();
        node.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) {
                throw new IllegalArgumentException("Script argument values must be strings.");
            }
            final String key = entry.getKey();
            final String value = entry.getValue().textValue();
            if (key.isBlank() || key.length() > 128 || value.length() > 4096) {
                throw new IllegalArgumentException("Script argument exceeded the size limit.");
            }
            result.put(key, value);
        });
        return Map.copyOf(result);
    }

    private static String text(final JsonNode node, final String field) {
        if (node == null) {
            return "";
        }
        final JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.textValue() : "";
    }

    private static String safeMessage(final Throwable failure, final String fallback) {
        if (failure == null || failure.getMessage() == null || failure.getMessage().isBlank()) {
            return fallback;
        }
        final String normalized = failure.getMessage().replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.length() <= 1024 ? normalized : normalized.substring(0, 1024);
    }

    private static final class HostCallException extends Exception {
        private HostCallException(final String code, final String message) {
            super((code == null || code.isBlank() ? "HOST_CALL_FAILED" : code) + ": "
                + Objects.requireNonNullElse(message, "Host call failed."));
        }
    }
}
