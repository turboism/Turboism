package dev.turboism.graal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.turboism.sdk.io.BoundedLineReader;
import dev.turboism.sdk.script.ScriptExecutionId;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    private static final int SUBMISSION_QUEUE_CAPACITY = 64;
    private static final int HOST_CALL_THREADS = 2;
    private static final int HOST_CALL_QUEUE_CAPACITY = 64;
    private static final int HOST_RESPONSE_QUEUE_CAPACITY = 64;
    private static final long STARTUP_RETRY_BACKOFF_MILLIS = 250L;

    private final ObjectMapper mapper;
    private final GraalHostConfiguration configuration;
    private final Consumer<String> diagnostics;
    private final Object lifecycleLock = new Object();
    private final Object writeLock = new Object();
    private final Map<String, PendingExecution> pending = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor submissions = new ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(SUBMISSION_QUEUE_CAPACITY),
        daemonThreadFactory("turboism-graal-host-submit"),
        new ThreadPoolExecutor.AbortPolicy()
    );
    private final ThreadPoolExecutor hostCalls = new ThreadPoolExecutor(
        HOST_CALL_THREADS, HOST_CALL_THREADS, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(HOST_CALL_QUEUE_CAPACITY),
        daemonThreadFactory("turboism-graal-host-call"),
        new ThreadPoolExecutor.AbortPolicy()
    );
    private final ThreadPoolExecutor hostResponses = new ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(HOST_RESPONSE_QUEUE_CAPACITY),
        daemonThreadFactory("turboism-graal-host-response"),
        new ThreadPoolExecutor.AbortPolicy()
    );
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile HostGeneration host;
    private long nextGeneration;
    private long nextStartupAttemptNanos;

    public GraalHostManager(
        final GraalHostConfiguration configuration,
        final Consumer<String> diagnostics
    ) {
        this(configuration, diagnostics, new ObjectMapper());
    }

    GraalHostManager(
        final GraalHostConfiguration configuration,
        final Consumer<String> diagnostics,
        final ObjectMapper mapper
    ) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
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
        final String submittedScriptId = Objects.requireNonNull(scriptId, "scriptId");
        final String submittedSource = Objects.requireNonNull(source, "source");
        final Map<String, String> submittedArguments = Map.copyOf(
            Objects.requireNonNull(arguments, "arguments")
        );
        final ScriptExecutionId id = new ScriptExecutionId(UUID.randomUUID().toString());
        final PendingExecution execution = new PendingExecution(
            id, Objects.requireNonNull(handler, "handler")
        );
        if (!configured()) {
            execution.completeUnclaimed(TransportResult.rejected(
                "GRAAL_HOST_NOT_CONFIGURED",
                "Graal host is not configured. Set TURBOISM_GRAALVM_HOME or turboism.graal.java."
            ));
            return new Execution(execution);
        }
        final Submission submission = new Submission(
            this, execution, submittedScriptId, submittedSource, submittedArguments
        );
        execution.attachSubmission(submission);
        pending.put(id.value(), execution);
        try {
            submissions.execute(submission);
        } catch (RejectedExecutionException rejected) {
            submission.discard();
            settle(execution, null, closed.get()
                ? runtimeClosedResult()
                : TransportResult.rejected(
                    "GRAAL_HOST_SUBMISSION_QUEUE_FULL",
                    "Graal host submission queue is full."
                ));
        }
        return new Execution(execution);
    }

    private void runSubmission(final Submission submission, final SubmissionPayload payload) {
        try {
            submitStarted(
                submission.execution(), payload.scriptId(), payload.source(), payload.arguments()
            );
        } catch (Throwable failure) {
            safeDiagnostic("GRAAL_HOST_SUBMISSION_FAILED: "
                + safeMessage(failure, "submission task failed"));
            settle(submission.execution(), null, TransportResult.failed(
                "GRAAL_HOST_SUBMISSION_FAILED",
                safeMessage(failure, "Failed to submit script to the Graal host."),
                ""
            ));
        }
    }

    private void submitStarted(
        final PendingExecution execution,
        final String scriptId,
        final String source,
        final Map<String, String> arguments
    ) {
        if (execution.isTerminal()) {
            pending.remove(execution.id().value(), execution);
            return;
        }
        final HostGeneration owner = ensureStarted();
        if (owner == null) {
            settle(execution, null, closed.get()
                ? runtimeClosedResult()
                : TransportResult.rejected(
                    "GRAAL_HOST_UNAVAILABLE",
                    "Configured Graal host could not become ready."
                ));
            return;
        }
        final ObjectNode run = mapper.createObjectNode();
        run.put("type", "RUN");
        run.put("protocolVersion", PROTOCOL_VERSION);
        run.put("executionId", execution.id().value());
        run.put("scriptId", scriptId);
        run.put("source", source);
        run.set("arguments", mapper.valueToTree(arguments));
        dispatchRun(owner, execution, run);
    }

    /**
     * Establishes one shared startup result for all queued submissions. A failure briefly
     * backs off retries so a queue cannot turn one timed-out launch into N serial timeouts.
     */
    private HostGeneration ensureStarted() {
        HostGeneration retired = null;
        HostGeneration immediatelyReady = null;
        CompletableFuture<ReadyState> startup = null;
        HostGeneration candidate = null;
        String launchFailure = null;
        synchronized (lifecycleLock) {
            if (closed.get()) {
                return null;
            }
            final HostGeneration current = host;
            if (current != null && !current.process().isAlive()) {
                if (!current.ready().isDone()) {
                    backOffStartupLocked();
                }
                retired = detachGenerationLocked(
                    current, "process stopped before accepting the script"
                );
            }
            final HostGeneration live = host;
            if (live != null && live.process().isAlive() && live.ready().isDone()) {
                final ReadyState state = live.ready().getNow(ReadyState.unavailable("not ready"));
                if (state.available()) {
                    immediatelyReady = live;
                } else {
                    detachGenerationLocked(live, "host reported unavailable");
                    backOffStartupLocked();
                    launchFailure = "GRAAL_HOST_UNAVAILABLE: " + state.detail();
                }
            }
            if (immediatelyReady == null && launchFailure == null) {
                final HostGeneration selected = host;
                if (selected == null || !selected.process().isAlive()) {
                    if (System.nanoTime() >= nextStartupAttemptNanos) {
                        try {
                            candidate = startProcessLocked();
                            startup = candidate.ready();
                        } catch (IOException failure) {
                            backOffStartupLocked();
                            launchFailure = "GRAAL_HOST_START_FAILED: "
                                + safeMessage(failure, "process launch failed");
                        }
                    }
                } else {
                    candidate = selected;
                    startup = selected.ready();
                }
            }
        }
        if (retired != null) {
            failPendingAfterProcessLoss(retired);
        }
        if (immediatelyReady != null) {
            return immediatelyReady;
        }
        if (launchFailure != null) {
            safeDiagnostic(launchFailure);
            return null;
        }
        if (startup == null) {
            return null;
        }
        try {
            final ReadyState state = startup.get(
                configuration.startupTimeoutMillis(), TimeUnit.MILLISECONDS
            );
            if (!state.available()) {
                safeDiagnostic("GRAAL_HOST_UNAVAILABLE: " + state.detail());
                invalidateGeneration(candidate, new IOException(state.detail()), true);
                return null;
            }
            synchronized (lifecycleLock) {
                return host == candidate && candidate.process().isAlive() ? candidate : null;
            }
        } catch (TimeoutException timeout) {
            safeDiagnostic("GRAAL_HOST_START_TIMEOUT: host did not report READY");
            invalidateGeneration(candidate, timeout, true);
            return null;
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            safeDiagnostic("GRAAL_HOST_START_FAILED: "
                + safeMessage(failure, "startup failed"));
            invalidateGeneration(candidate, failure, true);
            return null;
        }
    }

    private HostGeneration startProcessLocked() throws IOException {
        final ProcessBuilder builder = new ProcessBuilder(
            configuration.javaBinary(),
            "-cp",
            configuration.classpath(),
            configuration.mainClass()
        );
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        final Process launched = builder.start();
        final HostGeneration generation = new HostGeneration(
            ++nextGeneration,
            launched,
            new BufferedWriter(new OutputStreamWriter(
                launched.getOutputStream(), StandardCharsets.UTF_8
            )),
            new CompletableFuture<>()
        );
        host = generation;
        final BoundedLineReader launchedReader = new BoundedLineReader(
            new InputStreamReader(launched.getInputStream(), StandardCharsets.UTF_8),
            MAX_MESSAGE_CHARS
        );
        final Thread readerThread = new Thread(
            () -> readLoop(generation, launchedReader),
            "turboism-graal-host-reader-" + generation.id()
        );
        readerThread.setDaemon(true);
        readerThread.start();
        final Thread errorThread = new Thread(
            () -> drainErrors(generation),
            "turboism-graal-host-stderr-" + generation.id()
        );
        errorThread.setDaemon(true);
        errorThread.start();
        return generation;
    }

    private void readLoop(final HostGeneration owner, final BoundedLineReader reader) {
        try (reader) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    handleMessage(owner, mapper.readTree(line));
                }
            }
            processExited(owner, null);
        } catch (Throwable failure) {
            processExited(owner, failure);
        }
    }

    private void drainErrors(final HostGeneration owner) {
        try (BoundedLineReader error = new BoundedLineReader(
            new InputStreamReader(owner.process().getErrorStream(), StandardCharsets.UTF_8), 1024
        )) {
            int emitted = 0;
            BoundedLineReader.Line line;
            while ((line = error.readLineTruncated()) != null) {
                if (emitted < 32 && !line.text().isBlank()) {
                    safeDiagnostic("GRAAL_HOST_STDERR: " + line.text());
                    emitted++;
                }
            }
        } catch (IOException ignored) {
            // Process stdout/protocol failure is the authoritative transport signal.
        }
    }

    private void handleMessage(final HostGeneration owner, final JsonNode message) throws IOException {
        if (host != owner) {
            return;
        }
        final String type = text(message, "type");
        switch (type) {
            case "READY" -> handleReady(owner, message);
            case "HOST_CALL" -> handleHostCall(owner, message);
            case "COMPLETE" -> handleComplete(owner, message);
            case "FAILED" -> handleFailure(owner, message);
            case "PONG" -> { }
            case "PROTOCOL_ERROR" -> safeDiagnostic(
                "GRAAL_HOST_PROTOCOL_ERROR: " + text(message, "code")
                    + ": " + text(message, "message")
            );
            default -> throw new IOException("Unknown Graal host message: " + type);
        }
    }

    private void handleReady(final HostGeneration owner, final JsonNode message) {
        if (host != owner) {
            return;
        }
        final int protocol = message.path("protocolVersion").asInt(-1);
        if (protocol != PROTOCOL_VERSION) {
            owner.ready().complete(ReadyState.unavailable(
                "protocol mismatch: expected " + PROTOCOL_VERSION + " but got " + protocol
            ));
            return;
        }
        final boolean graalAvailable = message.path("graalAvailable").asBoolean(false);
        final String detail = text(message, "detail");
        owner.ready().complete(graalAvailable
            ? new ReadyState(true, detail)
            : ReadyState.unavailable(
                detail.isBlank() ? "Polyglot runtime unavailable" : detail
            ));
    }

    private void handleHostCall(final HostGeneration owner, final JsonNode message) {
        final String executionId = text(message, "executionId");
        final String callId = text(message, "callId");
        final String operation = text(message, "operation");
        final String payload = text(message, "payload");
        final PendingExecution execution = pending.get(executionId);
        final boolean admitted = execution != null && execution.admitHostCall(owner);
        if (!admitted) {
            return;
        }
        try {
            hostCalls.execute(() -> executeHostCall(
                owner, execution, callId, operation, payload
            ));
        } catch (RejectedExecutionException rejected) {
            safeDiagnostic("GRAAL_HOST_CALL_QUEUE_FULL: host call was rejected");
            enqueueHostError(
                owner,
                execution,
                callId,
                "SCRIPT_HOST_CALL_QUEUE_FULL",
                "Graal host call queue is full."
            );
        }
    }

    private void executeHostCall(
        final HostGeneration owner,
        final PendingExecution execution,
        final String callId,
        final String operation,
        final String payload
    ) {
        if (!execution.mayRunHostCall(owner)) {
            return;
        }
        try {
            final String result = execution.handler().call(operation, payload);
            final ObjectNode response = mapper.createObjectNode();
            response.put("type", "HOST_RESULT");
            response.put("callId", callId);
            response.put("result", Objects.requireNonNullElse(result, "null"));
            try {
                sendHostResponseIfActive(owner, execution, response);
            } catch (MessageTooLargeException oversized) {
                settle(execution, owner, TransportResult.rejected(
                    "SCRIPT_MESSAGE_TOO_LARGE",
                    "Script execution exceeded the Graal host protocol size limit."
                ));
            }
        } catch (HostCallException failure) {
            sendHostErrorIfActive(
                owner, execution, callId, failure.code(),
                safeMessage(failure, "Host call rejected.")
            );
        } catch (Throwable failure) {
            sendHostErrorIfActive(
                owner, execution, callId, "SCRIPT_HOST_CALL_FAILED",
                safeMessage(failure, "Host call failed.")
            );
        }
    }

    private void sendHostErrorIfActive(
        final HostGeneration owner,
        final PendingExecution execution,
        final String callId,
        final String code,
        final String message
    ) {
        final ObjectNode response = hostError(callId, code, message);
        try {
            sendHostResponseIfActive(owner, execution, response);
        } catch (IOException failure) {
            invalidateGeneration(owner, failure, false);
        }
    }

    private void enqueueHostError(
        final HostGeneration owner,
        final PendingExecution execution,
        final String callId,
        final String code,
        final String message
    ) {
        try {
            hostResponses.execute(() -> sendHostErrorIfActive(
                owner, execution, callId, code, message
            ));
        } catch (RejectedExecutionException rejected) {
            safeDiagnostic("GRAAL_HOST_RESPONSE_QUEUE_FULL: host response was dropped");
            invalidateGeneration(owner, rejected, false);
        }
    }

    private void sendHostResponseIfActive(
        final HostGeneration owner,
        final PendingExecution execution,
        final JsonNode response
    ) throws IOException {
        synchronized (writeLock) {
            synchronized (execution) {
                if (execution.maySendHostCallResponse(owner)) {
                    sendLocked(owner, response);
                }
            }
        }
    }

    private ObjectNode hostError(
        final String callId,
        final String code,
        final String message
    ) {
        final ObjectNode response = mapper.createObjectNode();
        response.put("type", "HOST_ERROR");
        response.put("callId", truncate(Objects.requireNonNullElse(callId, ""), 256));
        response.put("code", truncate(
            Objects.requireNonNullElse(code, "SCRIPT_HOST_CALL_FAILED"), 256
        ));
        response.put("message", truncate(
            Objects.requireNonNullElse(message, "Host call failed."), 1024
        ));
        return response;
    }

    private void handleComplete(final HostGeneration owner, final JsonNode message) {
        final String executionId = text(message, "executionId");
        final PendingExecution execution = pending.get(executionId);
        if (execution != null) {
            settle(execution, owner, TransportResult.success(text(message, "output")));
        }
    }

    private void handleFailure(final HostGeneration owner, final JsonNode message) {
        final String executionId = text(message, "executionId");
        final PendingExecution execution = pending.get(executionId);
        if (execution == null) {
            return;
        }
        final Status status = switch (text(message, "status")) {
            case "CANCELLED" -> Status.CANCELLED;
            case "TIMED_OUT" -> Status.TIMED_OUT;
            case "REJECTED" -> Status.REJECTED;
            default -> Status.FAILED;
        };
        settle(execution, owner, new TransportResult(
            status,
            defaultCode(text(message, "code"), status),
            text(message, "message"),
            text(message, "output")
        ));
    }

    private void dispatchRun(
        final HostGeneration owner,
        final PendingExecution execution,
        final ObjectNode run
    ) {
        try {
            synchronized (writeLock) {
                if (!execution.beginRun(owner)) {
                    pending.remove(execution.id().value(), execution);
                    return;
                }
                sendLocked(owner, run);
                execution.markRunSent();
            }
        } catch (MessageTooLargeException oversized) {
            settle(execution, owner, TransportResult.rejected(
                "SCRIPT_MESSAGE_TOO_LARGE",
                "Script execution exceeded the Graal host protocol size limit."
            ));
        } catch (IOException failure) {
            settle(execution, owner, TransportResult.failed(
                "GRAAL_HOST_WRITE_FAILED",
                safeMessage(failure, "Failed to send script to Graal host."),
                ""
            ));
            invalidateGeneration(owner, failure, false);
        }
    }

    private boolean cancel(final PendingExecution execution) {
        final CancellationClaim claim;
        IOException sendFailure = null;
        synchronized (writeLock) {
            claim = execution.claimCancellation();
            if (claim.claimed() && !claim.beforeRun()) {
                final ObjectNode cancel = mapper.createObjectNode();
                cancel.put("type", "CANCEL");
                cancel.put("executionId", execution.id().value());
                try {
                    sendLocked(claim.owner(), cancel);
                } catch (IOException failure) {
                    sendFailure = failure;
                }
            }
        }
        if (!claim.claimed()) {
            return false;
        }
        if (claim.beforeRun()) {
            discardSubmission(claim.submission());
            pending.remove(execution.id().value(), execution);
            execution.completeClaimed(new TransportResult(
                Status.CANCELLED,
                "SCRIPT_CANCELLED",
                "Script execution was cancelled before reaching the Graal host.",
                ""
            ));
            return true;
        }
        if (sendFailure != null) {
            settle(execution, claim.owner(), new TransportResult(
                Status.CANCELLED,
                "SCRIPT_CANCELLED",
                "Script execution was cancelled while the Graal host was unavailable.",
                ""
            ));
            invalidateGeneration(claim.owner(), sendFailure, false);
        }
        return true;
    }

    private void send(final HostGeneration owner, final JsonNode message) throws IOException {
        synchronized (writeLock) {
            sendLocked(owner, message);
        }
    }

    private void sendLocked(final HostGeneration owner, final JsonNode message) throws IOException {
        final String encoded = mapper.writeValueAsString(message);
        if (encoded.length() > MAX_MESSAGE_CHARS) {
            throw new MessageTooLargeException();
        }
        if (host != owner || !owner.process().isAlive()) {
            throw new IOException("Graal host process is not running");
        }
        owner.writer().write(encoded);
        owner.writer().newLine();
        owner.writer().flush();
    }

    private void processExited(final HostGeneration owner, final Throwable failure) {
        final HostGeneration detached;
        synchronized (lifecycleLock) {
            if (host != owner) {
                return;
            }
            if (!owner.ready().isDone()) {
                backOffStartupLocked();
            }
            detached = detachGenerationLocked(owner, "process exited before READY");
        }
        if (!closed.get()) {
            safeDiagnostic("GRAAL_HOST_EXITED: " + (failure == null
                ? "process ended"
                : safeMessage(failure, "protocol reader failed")));
        }
        failPendingAfterProcessLoss(detached);
    }

    private void invalidateGeneration(
        final HostGeneration owner,
        final Throwable reason,
        final boolean backOffStartup
    ) {
        HostGeneration detached = null;
        synchronized (lifecycleLock) {
            if (host == owner) {
                detached = detachGenerationLocked(owner, "process invalidated before READY");
                if (backOffStartup) {
                    backOffStartupLocked();
                }
            }
        }
        if (detached != null) {
            safeDiagnostic("GRAAL_HOST_INVALIDATED: "
                + safeMessage(reason, "transport failure"));
            failPendingAfterProcessLoss(detached);
        }
    }

    private HostGeneration detachGenerationLocked(
        final HostGeneration owner,
        final String unavailableDetail
    ) {
        if (host != owner) {
            return null;
        }
        host = null;
        if (!owner.ready().isDone()) {
            owner.ready().complete(ReadyState.unavailable(unavailableDetail));
        }
        destroyProcessTree(owner.process());
        return owner;
    }

    private static void destroyProcessTree(final Process process) {
        final ProcessHandle root = process.toHandle();
        root.descendants()
            .sorted((left, right) -> Long.compare(right.pid(), left.pid()))
            .forEach(GraalHostManager::destroyForcibly);
        destroyForcibly(root);
    }

    private static void destroyForcibly(final ProcessHandle process) {
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private void backOffStartupLocked() {
        nextStartupAttemptNanos = System.nanoTime()
            + TimeUnit.MILLISECONDS.toNanos(STARTUP_RETRY_BACKOFF_MILLIS);
    }

    private void failPendingAfterProcessLoss(final HostGeneration owner) {
        if (owner == null) {
            return;
        }
        pending.forEach((id, execution) -> settle(
            execution,
            owner,
            closed.get() ? runtimeClosedResult() : TransportResult.failed(
                "GRAAL_HOST_CRASHED",
                "Graal host process exited while the script was running.",
                ""
            )
        ));
    }

    private boolean settle(
        final PendingExecution execution,
        final HostGeneration expectedOwner,
        final TransportResult result
    ) {
        final TerminalClaim claim;
        claim = execution.claimTerminal(expectedOwner);
        if (!claim.claimed()) {
            return false;
        }
        discardSubmission(claim.submission());
        pending.remove(execution.id().value(), execution);
        execution.completeClaimed(result);
        return true;
    }

    private void discardSubmission(final Submission submission) {
        if (submission != null) {
            submissions.remove(submission);
            submission.discard();
        }
    }

    private void safeDiagnostic(final String message) {
        try {
            diagnostics.accept(message);
        } catch (Throwable ignored) {
            // Diagnostics are observational and cannot own transport lifecycle progress.
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        pending.forEach((id, execution) -> settle(
            execution, null, runtimeClosedResult()
        ));
        final List<Runnable> queuedSubmissions = submissions.shutdownNow();
        for (Runnable queued : queuedSubmissions) {
            if (queued instanceof Submission submission) {
                submission.discard();
                settle(submission.execution(), null, runtimeClosedResult());
            }
        }
        pending.forEach((id, execution) -> settle(
            execution, null, runtimeClosedResult()
        ));
        hostCalls.shutdownNow();
        hostResponses.shutdownNow();
        synchronized (lifecycleLock) {
            final HostGeneration live = host;
            if (live != null) {
                detachGenerationLocked(live, "manager closed");
            }
        }
    }

    private static TransportResult runtimeClosedResult() {
        return new TransportResult(
            Status.CANCELLED,
            "SCRIPT_RUNTIME_CLOSED",
            "Script runtime was closed.",
            ""
        );
    }

    private static java.util.concurrent.ThreadFactory daemonThreadFactory(final String prefix) {
        final AtomicInteger nextThread = new AtomicInteger();
        return runnable -> {
            final Thread thread = new Thread(
                runnable, prefix + "-" + nextThread.incrementAndGet()
            );
            thread.setDaemon(true);
            return thread;
        };
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
        return truncate(
            failure.getMessage().replace('\r', ' ').replace('\n', ' ').trim(), 1024
        );
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
            return GraalHostManager.this.cancel(delegate);
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

    private record HostGeneration(
        long id,
        Process process,
        BufferedWriter writer,
        CompletableFuture<ReadyState> ready
    ) {
    }

    private record SubmissionPayload(
        String scriptId,
        String source,
        Map<String, String> arguments
    ) {
    }

    private record TerminalClaim(boolean claimed, Submission submission) {
        static TerminalClaim rejected() {
            return new TerminalClaim(false, null);
        }
    }

    private record CancellationClaim(
        boolean claimed,
        boolean beforeRun,
        HostGeneration owner,
        Submission submission
    ) {
        static CancellationClaim rejected() {
            return new CancellationClaim(false, false, null, null);
        }
    }

    private static final class MessageTooLargeException extends IOException {
        private MessageTooLargeException() {
            super("Outgoing Graal host message exceeded the size limit");
        }
    }

    private static final class Submission implements Runnable {
        private final GraalHostManager manager;
        private final PendingExecution execution;
        private final String scriptId;
        private String source;
        private Map<String, String> arguments;

        private Submission(
            final GraalHostManager manager,
            final PendingExecution execution,
            final String scriptId,
            final String source,
            final Map<String, String> arguments
        ) {
            this.manager = manager;
            this.execution = execution;
            this.scriptId = scriptId;
            this.source = source;
            this.arguments = arguments;
        }

        PendingExecution execution() {
            return execution;
        }

        @Override
        public void run() {
            if (!execution.submissionStarted(this)) {
                discard();
                return;
            }
            final SubmissionPayload payload = takePayload();
            if (payload != null) {
                manager.runSubmission(this, payload);
            }
        }

        synchronized void discard() {
            source = null;
            arguments = null;
        }

        private synchronized SubmissionPayload takePayload() {
            if (source == null || arguments == null) {
                return null;
            }
            final SubmissionPayload payload = new SubmissionPayload(
                scriptId, source, arguments
            );
            source = null;
            arguments = null;
            return payload;
        }
    }

    private static final class PendingExecution {
        private final ScriptExecutionId id;
        private final HostCallHandler handler;
        private final CompletableFuture<TransportResult> completion = new CompletableFuture<>();
        private State state = State.QUEUED;
        private HostGeneration owner;
        private boolean cancelRequested;
        private Submission submission;

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

        synchronized void attachSubmission(final Submission submitted) {
            if (state != State.QUEUED || submission != null) {
                throw new IllegalStateException("Submission already attached");
            }
            submission = Objects.requireNonNull(submitted, "submitted");
        }

        synchronized boolean submissionStarted(final Submission started) {
            if (state != State.QUEUED || submission != started) {
                return false;
            }
            submission = null;
            return true;
        }

        synchronized boolean isTerminal() {
            return state == State.TERMINAL;
        }

        synchronized boolean beginRun(final HostGeneration selectedOwner) {
            if (state != State.QUEUED) {
                return false;
            }
            owner = Objects.requireNonNull(selectedOwner, "selectedOwner");
            state = State.STARTING;
            return true;
        }

        synchronized void markRunSent() {
            if (state == State.STARTING) {
                state = State.RUNNING;
            }
        }

        synchronized boolean admitHostCall(final HostGeneration expectedOwner) {
            return state == State.RUNNING
                && owner == expectedOwner
                && !cancelRequested;
        }

        synchronized boolean mayRunHostCall(final HostGeneration expectedOwner) {
            return admitHostCall(expectedOwner);
        }

        synchronized boolean maySendHostCallResponse(final HostGeneration expectedOwner) {
            return admitHostCall(expectedOwner);
        }

        synchronized CancellationClaim claimCancellation() {
            if (state == State.QUEUED || state == State.STARTING) {
                final Submission queued = submission;
                submission = null;
                state = State.TERMINAL;
                return new CancellationClaim(true, true, null, queued);
            }
            if (state != State.RUNNING || cancelRequested) {
                return CancellationClaim.rejected();
            }
            cancelRequested = true;
            return new CancellationClaim(true, false, owner, null);
        }

        synchronized TerminalClaim claimTerminal(final HostGeneration expectedOwner) {
            if (state == State.TERMINAL) {
                return TerminalClaim.rejected();
            }
            if (expectedOwner != null && owner != expectedOwner) {
                return TerminalClaim.rejected();
            }
            final Submission queued = submission;
            submission = null;
            state = State.TERMINAL;
            return new TerminalClaim(true, queued);
        }

        CompletableFuture<TransportResult> completion() {
            return completion;
        }

        void completeClaimed(final TransportResult result) {
            completion.complete(result);
        }

        synchronized void completeUnclaimed(final TransportResult result) {
            state = State.TERMINAL;
            completion.complete(result);
        }

        private enum State {
            QUEUED,
            STARTING,
            RUNNING,
            TERMINAL
        }
    }
}
