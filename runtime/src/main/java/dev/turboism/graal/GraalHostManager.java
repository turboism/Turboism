package dev.turboism.graal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.turboism.sdk.script.ScriptExecutionId;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Lazy, persistent process supervisor for the dedicated Graal script host.
 *
 * <p>It never replaces the Cubism JVM. The external process is started only when a script is
 * actually executed and all communication uses bounded JSON messages.</p>
 */
public final class GraalHostManager implements AutoCloseable {

    private static final int PROTOCOL_VERSION = 1;
    private static final int MAX_MESSAGE_CHARS = 4 * 1024 * 1024;

    private final ObjectMapper mapper = new ObjectMapper();
    private final GraalHostConfiguration configuration;
    private final Consumer<String> diagnostics;
    private final Object lifecycleLock = new Object();
    private final Object writeLock = new Object();
    private final Map<String, PendingExecution> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile Process process;
    private volatile BufferedWriter writer;
    private volatile CompletableFuture<ReadyState> ready = new CompletableFuture<>();

    public GraalHostManager(
        final GraalHostConfiguration configuration,
        final Consumer<String> diagnostics
    ) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    public boolean configured() {
        return configuration.enabled() && !closed.get();
    }

    public Execution submit(
        final String scriptId,
        final String source,
        final Map<String, String> arguments,
        final HostCallHandler handler
    ) {
        Objects.requireNonNull(scriptId, "scriptId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(handler, "handler");
        final ScriptExecutionId id = new ScriptExecutionId(UUID.randomUUID().toString());
        final PendingExecution execution = new PendingExecution(id, handler);
        if (!configured()) {
            execution.complete(TransportResult.rejected(
                "GRAAL_HOST_NOT_CONFIGURED",
                "Graal host is not configured. Set TURBOISM_GRAALVM_HOME or turboism.graal.java."
            ));
            return new Execution(execution);
        }
        pending.put(id.value(), execution);
        if (!ensureStarted()) {
            pending.remove(id.value(), execution);
            execution.complete(TransportResult.rejected(
                "GRAAL_HOST_UNAVAILABLE",
                "Configured Graal host could not become ready."
            ));
            return new Execution(execution);
        }
        final ObjectNode run = mapper.createObjectNode();
        run.put("type", "RUN");
        run.put("protocolVersion", PROTOCOL_VERSION);
        run.put("executionId", id.value());
        run.put("scriptId", scriptId);
        run.put("source", source);
        run.set("arguments", mapper.valueToTree(arguments));
        try {
            send(run);
        } catch (IOException failure) {
            pending.remove(id.value(), execution);
            execution.complete(TransportResult.failed(
                "GRAAL_HOST_WRITE_FAILED", safeMessage(failure, "Failed to send script to Graal host."), ""
            ));
            invalidateProcess(failure);
        }
        return new Execution(execution);
    }

    private boolean ensureStarted() {
        CompletableFuture<ReadyState> startup;
        synchronized (lifecycleLock) {
            if (closed.get()) {
                return false;
            }
            final Process current = process;
            if (current != null && current.isAlive() && ready.isDone()) {
                try {
                    return ready.getNow(ReadyState.unavailable("not ready")).available();
                } catch (RuntimeException ignored) {
                    invalidateProcessLocked(current);
                }
            }
            if (current == null || !current.isAlive()) {
                try {
                    startProcessLocked();
                } catch (IOException failure) {
                    diagnostics.accept("GRAAL_HOST_START_FAILED: " + safeMessage(failure, "process launch failed"));
                    return false;
                }
            }
            startup = ready;
        }
        try {
            final ReadyState state = startup.get(
                configuration.startupTimeoutMillis(), TimeUnit.MILLISECONDS
            );
            if (!state.available()) {
                diagnostics.accept("GRAAL_HOST_UNAVAILABLE: " + state.detail());
            }
            return state.available();
        } catch (TimeoutException timeout) {
            diagnostics.accept("GRAAL_HOST_START_TIMEOUT: host did not report READY");
            invalidateProcess(timeout);
            return false;
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            diagnostics.accept("GRAAL_HOST_START_FAILED: " + safeMessage(failure, "startup failed"));
            invalidateProcess(failure);
            return false;
        }
    }

    private void startProcessLocked() throws IOException {
        final Process launched = new ProcessBuilder(
            configuration.javaBinary(),
            "-cp",
            configuration.classpath(),
            configuration.mainClass()
        ).start();
        final BufferedWriter launchedWriter = new BufferedWriter(
            new OutputStreamWriter(launched.getOutputStream(), StandardCharsets.UTF_8)
        );
        final BufferedReader launchedReader = new BufferedReader(
            new InputStreamReader(launched.getInputStream(), StandardCharsets.UTF_8)
        );
        process = launched;
        writer = launchedWriter;
        ready = new CompletableFuture<>();
        final Thread readerThread = new Thread(
            () -> readLoop(launched, launchedReader),
            "turboism-graal-host-reader"
        );
        readerThread.setDaemon(true);
        readerThread.start();
        final Thread errorThread = new Thread(
            () -> drainErrors(launched),
            "turboism-graal-host-stderr"
        );
        errorThread.setDaemon(true);
        errorThread.start();
    }

    private void readLoop(final Process owner, final BufferedReader reader) {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() > MAX_MESSAGE_CHARS) {
                    throw new IOException("Graal host emitted an oversized protocol message");
                }
                if (line.isBlank()) {
                    continue;
                }
                handleMessage(owner, mapper.readTree(line));
            }
            processExited(owner, null);
        } catch (Throwable failure) {
            processExited(owner, failure);
        }
    }

    private void drainErrors(final Process owner) {
        try (BufferedReader error = new BufferedReader(
            new InputStreamReader(owner.getErrorStream(), StandardCharsets.UTF_8)
        )) {
            String line;
            int emitted = 0;
            while ((line = error.readLine()) != null && emitted < 32) {
                if (!line.isBlank()) {
                    diagnostics.accept("GRAAL_HOST_STDERR: " + truncate(line, 1024));
                    emitted++;
                }
            }
        } catch (IOException ignored) {
            // Process stdout/protocol failure is the authoritative transport signal.
        }
    }

    private void handleMessage(final Process owner, final JsonNode message) throws IOException {
        if (owner != process) {
            return;
        }
        final String type = text(message, "type");
        switch (type) {
            case "READY" -> handleReady(message);
            case "HOST_CALL" -> handleHostCall(message);
            case "COMPLETE" -> handleComplete(message);
            case "FAILED" -> handleFailure(message);
            case "PONG" -> { }
            case "PROTOCOL_ERROR" -> diagnostics.accept(
                "GRAAL_HOST_PROTOCOL_ERROR: " + text(message, "code") + ": " + text(message, "message")
            );
            default -> throw new IOException("Unknown Graal host message: " + type);
        }
    }

    private void handleReady(final JsonNode message) {
        final int protocol = message.path("protocolVersion").asInt(-1);
        if (protocol != PROTOCOL_VERSION) {
            ready.complete(ReadyState.unavailable(
                "protocol mismatch: expected " + PROTOCOL_VERSION + " but got " + protocol
            ));
            return;
        }
        final boolean graalAvailable = message.path("graalAvailable").asBoolean(false);
        final String detail = text(message, "detail");
        ready.complete(graalAvailable
            ? new ReadyState(true, detail)
            : ReadyState.unavailable(detail.isBlank() ? "Polyglot runtime unavailable" : detail));
    }

    private void handleHostCall(final JsonNode message) throws IOException {
        final String executionId = text(message, "executionId");
        final String callId = text(message, "callId");
        final String operation = text(message, "operation");
        final String payload = text(message, "payload");
        final PendingExecution execution = pending.get(executionId);
        if (execution == null) {
            sendHostError(callId, "SCRIPT_EXECUTION_NOT_FOUND", "Script execution is no longer active.");
            return;
        }
        try {
            final String result = execution.handler().call(operation, payload);
            final ObjectNode response = mapper.createObjectNode();
            response.put("type", "HOST_RESULT");
            response.put("callId", callId);
            response.put("result", Objects.requireNonNullElse(result, "null"));
            send(response);
        } catch (HostCallException failure) {
            sendHostError(callId, failure.code(), safeMessage(failure, "Host call rejected."));
        } catch (Throwable failure) {
            sendHostError(callId, "SCRIPT_HOST_CALL_FAILED", safeMessage(failure, "Host call failed."));
        }
    }

    private void sendHostError(final String callId, final String code, final String message) throws IOException {
        final ObjectNode response = mapper.createObjectNode();
        response.put("type", "HOST_ERROR");
        response.put("callId", callId);
        response.put("code", code);
        response.put("message", message);
        send(response);
    }

    private void handleComplete(final JsonNode message) {
        final String executionId = text(message, "executionId");
        final PendingExecution execution = pending.remove(executionId);
        if (execution != null) {
            execution.complete(TransportResult.success(text(message, "output")));
        }
    }

    private void handleFailure(final JsonNode message) {
        final String executionId = text(message, "executionId");
        final PendingExecution execution = pending.remove(executionId);
        if (execution == null) {
            return;
        }
        final Status status = switch (text(message, "status")) {
            case "CANCELLED" -> Status.CANCELLED;
            case "TIMED_OUT" -> Status.TIMED_OUT;
            default -> Status.FAILED;
        };
        execution.complete(new TransportResult(
            status,
            defaultCode(text(message, "code"), status),
            text(message, "message"),
            text(message, "output")
        ));
    }

    private void cancel(final PendingExecution execution) {
        if (execution.completion().isDone()) {
            return;
        }
        final ObjectNode cancel = mapper.createObjectNode();
        cancel.put("type", "CANCEL");
        cancel.put("executionId", execution.id().value());
        try {
            send(cancel);
        } catch (IOException failure) {
            if (pending.remove(execution.id().value(), execution)) {
                execution.complete(new TransportResult(
                    Status.CANCELLED,
                    "SCRIPT_CANCELLED",
                    "Script execution was cancelled while the Graal host was unavailable.",
                    ""
                ));
            }
            invalidateProcess(failure);
        }
    }

    private void send(final JsonNode message) throws IOException {
        final String encoded = mapper.writeValueAsString(message);
        if (encoded.length() > MAX_MESSAGE_CHARS) {
            throw new IOException("Outgoing Graal host message exceeded the size limit");
        }
        synchronized (writeLock) {
            final BufferedWriter current = writer;
            final Process owner = process;
            if (current == null || owner == null || !owner.isAlive()) {
                throw new IOException("Graal host process is not running");
            }
            current.write(encoded);
            current.newLine();
            current.flush();
        }
    }

    private void processExited(final Process owner, final Throwable failure) {
        synchronized (lifecycleLock) {
            if (owner != process) {
                return;
            }
            process = null;
            writer = null;
            if (!ready.isDone()) {
                ready.complete(ReadyState.unavailable("process exited before READY"));
            }
        }
        if (!closed.get()) {
            diagnostics.accept("GRAAL_HOST_EXITED: " + (failure == null
                ? "process ended"
                : safeMessage(failure, "protocol reader failed")));
        }
        pending.forEach((id, execution) -> {
            if (pending.remove(id, execution)) {
                execution.complete(TransportResult.failed(
                    "GRAAL_HOST_CRASHED",
                    "Graal host process exited while the script was running.",
                    ""
                ));
            }
        });
    }

    private void invalidateProcess(final Throwable reason) {
        synchronized (lifecycleLock) {
            final Process current = process;
            if (current != null) {
                invalidateProcessLocked(current);
            }
        }
        diagnostics.accept("GRAAL_HOST_INVALIDATED: " + safeMessage(reason, "transport failure"));
    }

    private void invalidateProcessLocked(final Process current) {
        if (process == current) {
            process = null;
            writer = null;
            current.destroyForcibly();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            final ObjectNode shutdown = mapper.createObjectNode();
            shutdown.put("type", "SHUTDOWN");
            send(shutdown);
        } catch (IOException ignored) {
        }
        synchronized (lifecycleLock) {
            final Process current = process;
            process = null;
            writer = null;
            if (current != null && current.isAlive()) {
                try {
                    if (!current.waitFor(1, TimeUnit.SECONDS)) {
                        current.destroyForcibly();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    current.destroyForcibly();
                }
            }
        }
        pending.forEach((id, execution) -> {
            if (pending.remove(id, execution)) {
                execution.complete(new TransportResult(
                    Status.CANCELLED,
                    "SCRIPT_RUNTIME_CLOSED",
                    "Script runtime was closed.",
                    ""
                ));
            }
        });
    }

    private static String text(final JsonNode node, final String field) {
        final JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.textValue() : "";
    }

    private static String defaultCode(final String code, final Status status) {
        if (code != null && !code.isBlank()) {
            return code;
        }
        return switch (status) {
            case CANCELLED -> "SCRIPT_CANCELLED";
            case TIMED_OUT -> "SCRIPT_TIMED_OUT";
            case REJECTED -> "SCRIPT_REJECTED";
            default -> "SCRIPT_FAILED";
        };
    }

    private static String safeMessage(final Throwable failure, final String fallback) {
        if (failure == null || failure.getMessage() == null || failure.getMessage().isBlank()) {
            return fallback;
        }
        return truncate(failure.getMessage().replace('\r', ' ').replace('\n', ' ').trim(), 1024);
    }

    private static String truncate(final String value, final int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    @FunctionalInterface
    public interface HostCallHandler {
        String call(String operation, String payloadJson) throws Exception;
    }

    public static final class HostCallException extends Exception {
        private final String code;

        public HostCallException(final String code, final String message) {
            super(message);
            this.code = code == null || code.isBlank() ? "SCRIPT_HOST_CALL_REJECTED" : code;
        }

        public String code() {
            return code;
        }
    }

    public final class Execution implements AutoCloseable {
        private final PendingExecution delegate;

        private Execution(final PendingExecution delegate) {
            this.delegate = delegate;
        }

        public ScriptExecutionId id() {
            return delegate.id();
        }

        public CompletionStage<TransportResult> completion() {
            return delegate.completion();
        }

        public boolean cancel() {
            if (delegate.completion().isDone()) {
                return false;
            }
            GraalHostManager.this.cancel(delegate);
            return true;
        }

        @Override
        public void close() {
            cancel();
        }
    }

    public record TransportResult(Status status, String code, String message, String output) {
        public TransportResult {
            status = Objects.requireNonNull(status, "status");
            code = Objects.requireNonNullElse(code, "");
            message = Objects.requireNonNullElse(message, "");
            output = Objects.requireNonNullElse(output, "");
        }

        static TransportResult success(final String output) {
            return new TransportResult(Status.SUCCEEDED, "", "", output);
        }

        static TransportResult failed(final String code, final String message, final String output) {
            return new TransportResult(Status.FAILED, code, message, output);
        }

        static TransportResult rejected(final String code, final String message) {
            return new TransportResult(Status.REJECTED, code, message, "");
        }
    }

    public enum Status {
        SUCCEEDED,
        FAILED,
        CANCELLED,
        REJECTED,
        TIMED_OUT
    }

    private record ReadyState(boolean available, String detail) {
        static ReadyState unavailable(final String detail) {
            return new ReadyState(false, detail);
        }
    }

    private static final class PendingExecution {
        private final ScriptExecutionId id;
        private final HostCallHandler handler;
        private final CompletableFuture<TransportResult> completion = new CompletableFuture<>();

        private PendingExecution(final ScriptExecutionId id, final HostCallHandler handler) {
            this.id = id;
            this.handler = handler;
        }

        ScriptExecutionId id() {
            return id;
        }

        HostCallHandler handler() {
            return handler;
        }

        CompletableFuture<TransportResult> completion() {
            return completion;
        }

        void complete(final TransportResult result) {
            completion.complete(result);
        }
    }
}
