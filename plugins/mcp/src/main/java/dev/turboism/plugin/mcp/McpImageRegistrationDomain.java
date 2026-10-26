package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.model.AuthorizedImageContent;
import dev.turboism.sdk.ui.*;
import java.nio.ByteBuffer;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** One bounded PNG read/registration job, with cancellation only before native transaction admission. */
final class McpImageRegistrationDomain implements AutoCloseable {
    static final String START = "turboism.assets.images.register",
            RESULT = "turboism.assets.images.result",
            CANCEL = "turboism.assets.images.cancel";
    static final int MAX_PNG_BYTES = 16 * 1024 * 1024, MAX_PIXELS = 16 * 1024 * 1024, MAX_JOBS = 128;
    private final UserFileAccessService files;
    private final McpFileGrantDomain grants;
    private final McpImageBindingDomain bindings;
    private final McpExecutionBridge execution;
    private final ExecutorService worker;
    private final Map<Key, Job> jobs = new HashMap<>();
    private Job active;
    private boolean closed;

    McpImageRegistrationDomain(
            UserFileAccessService files,
            McpFileGrantDomain grants,
            McpImageBindingDomain bindings,
            McpExecutionBridge execution) {
        this(files, grants, bindings, execution, Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "turboism-mcp-image-registration");
            thread.setDaemon(true);
            return thread;
        }));
    }

    McpImageRegistrationDomain(
            UserFileAccessService files,
            McpFileGrantDomain grants,
            McpImageBindingDomain bindings,
            McpExecutionBridge execution,
            ExecutorService worker) {
        this.files = Objects.requireNonNull(files);
        this.grants = Objects.requireNonNull(grants);
        this.bindings = Objects.requireNonNull(bindings);
        this.execution = Objects.requireNonNull(execution);
        this.worker = Objects.requireNonNull(worker);
    }

    McpToolCatalog tools() {
        var versions = ReviewedVersions.since("5.0.00").stream().sorted().toList();
        return McpToolCatalog.of(List.of(
                McpRegisteredTool.typed(
                        McpImageRegistrationSchemas.definition(START),
                        McpOperationEffect.EXTERNAL_SIDE_EFFECT,
                        McpExecutionAffinity.DIRECT,
                        false,
                        McpVersionSupport.exact(
                                "cubism.editor-model.texture.write",
                                versions,
                                List.of(
                                        McpVersionSupport.OperationSupport.available(
                                                "authorized_png_registration",
                                                McpOperationEffect.EXTERNAL_SIDE_EFFECT,
                                                false,
                                                McpVersionSupport.UndoVerification.RUNTIME_VERIFIED,
                                                versions,
                                                "Session READ grant consumption plus bounded asynchronous PNG validation and owned model authoring transaction. File read is not Undoable; Runtime exact-host registration/Undo/permission checks remain authoritative."))),
                        execution,
                        this::start),
                McpRegisteredTool.typed(
                        McpImageRegistrationSchemas.definition(RESULT),
                        McpOperationEffect.READ,
                        McpExecutionAffinity.DIRECT,
                        false,
                        execution,
                        this::result),
                McpRegisteredTool.typed(
                        McpImageRegistrationSchemas.definition(CANCEL),
                        McpOperationEffect.EXTERNAL_SIDE_EFFECT,
                        McpExecutionAffinity.DIRECT,
                        false,
                        execution,
                        this::cancel)));
    }

    private synchronized Map<String, Object> start(Map<String, Object> args) {
        String session = McpRequestRegistry.currentSessionId();
        if (session.isBlank()) return error("SESSION_REQUIRED");
        if (closed) return error("UNAVAILABLE");
        if (args.get("name") instanceof String label
                && (label.isBlank() || label.chars().anyMatch(Character::isISOControl)))
            return error("INVALID_ARGUMENT");
        var key = new Key(session, (String) args.get("operationId"));
        String fingerprint = McpWriteResults.digest(args);
        var previous = jobs.get(key);
        if (previous != null)
            return previous.fingerprint.equals(fingerprint) ? output(previous) : error("OPERATION_ID_CONFLICT");
        if (active != null) return error("REGISTRATION_BUSY");
        if (jobs.size() >= MAX_JOBS) return error("RESULT_STORE_FULL");
        var job = new Job(key, fingerprint, Map.copyOf(args));
        jobs.put(key, job);
        active = job;
        try {
            worker.execute(() -> begin(job));
        } catch (RejectedExecutionException failure) {
            finish(job, "NOT_APPLIED", "WORKER_UNAVAILABLE", null);
        }
        return output(job);
    }

    private void begin(Job job) {
        try {
            if (stop(job)) return;
            phase(job, "PREFLIGHT");
            var target = ui(job, () -> bindings.prepareRegistration(job.args));
            synchronized (this) {
                if (stop(job)) return;
                job.lease = grants.consume(job.key.session, (String) job.args.get("grantId"), UserFileMode.READ, "png");
                job.phase = "READING";
                try {
                    Objects.requireNonNull(files.readBytesBounded(
                                    job.lease.handle(), ((Number) job.args.get("maxPngBytes")).intValue()))
                            .whenComplete((read, failure) -> receive(job, target, read, failure));
                } catch (RuntimeException failure) {
                    release(job);
                    finish(job, "NOT_APPLIED", "READ_ADMISSION_FAILED", null);
                }
            }
        } catch (RuntimeException failure) {
            finish(job, "NOT_APPLIED", McpImageBindingDomain.registrationError(failure), null);
        }
    }

    private synchronized void receive(
            Job job,
            McpImageBindingDomain.RegistrationTarget target,
            UserFileReadResult<byte[]> read,
            Throwable failure) {
        // Completion, not cancellation/disconnect, owns handle release. Never cancel a native read stage.
        release(job);
        if (stop(job)) return;
        try {
            worker.execute(() -> process(job, target, read, failure));
        } catch (RejectedExecutionException stopped) {
            finish(job, "NOT_APPLIED", "WORKER_UNAVAILABLE", null);
        }
    }

    private void process(
            Job job,
            McpImageBindingDomain.RegistrationTarget target,
            UserFileReadResult<byte[]> read,
            Throwable readFailure) {
        try {
            if (stop(job)) return;
            phase(job, "VALIDATING");
            if (readFailure != null || read == null) {
                finish(job, "NOT_APPLIED", "READ_FAILED", null);
                return;
            }
            if (read.error().isPresent()) {
                finish(job, "NOT_APPLIED", read.error().get().code().name(), null);
                return;
            }
            byte[] png = read.value().orElseThrow();
            if (png.length > ((Number) job.args.get("maxPngBytes")).intValue()) {
                finish(job, "NOT_APPLIED", "SDK_BUDGET_VIOLATION", null);
                return;
            }
            if (read.truncated()) {
                finish(job, "NOT_APPLIED", "TRUNCATED_PNG", null);
                return;
            }
            if (png.length < 24) {
                finish(job, "NOT_APPLIED", "INVALID_PNG", null);
                return;
            }
            int width = ByteBuffer.wrap(png, 16, 4).getInt(),
                    height = ByteBuffer.wrap(png, 20, 4).getInt();
            if (width <= 0 || height <= 0 || (long) width * height > ((Number) job.args.get("maxPixels")).longValue()) {
                finish(job, "NOT_APPLIED", "PIXEL_BUDGET_EXCEEDED", null);
                return;
            }
            AuthorizedImageContent content;
            try {
                content = AuthorizedImageContent.ofDimensionsBounded(png, (String) job.args.get("name"), width, height);
            } catch (IllegalArgumentException invalid) {
                finish(job, "NOT_APPLIED", "INVALID_PNG", null);
                return;
            }
            String sha = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(content.copyBytes()));
            synchronized (this) {
                job.fileEvidence = Map.of(
                        "sha256",
                        sha,
                        "byteLength",
                        png.length,
                        "width",
                        width,
                        "height",
                        height,
                        "fileStability",
                        "UNPROVEN");
            }
            if (job.args.containsKey("expectedSha256") && !sha.equals(job.args.get("expectedSha256"))) {
                finish(job, "NOT_APPLIED", "CONTENT_HASH_MISMATCH", null);
                return;
            }
            if (stop(job)) return;
            phase(job, "HOST_PENDING");
            var write = ui(
                    job,
                    () -> bindings.registerPrepared(target, content, job.key.id, () -> {
                        synchronized (this) {
                            if (closed || job.cancelRequested || jobs.get(job.key) != job)
                                throw new CancellationException();
                            job.hostAdmitted = true;
                            job.phase = "HOST_RUNNING";
                        }
                    }));
            finish(job, (String) write.get("outcome"), (String) write.get("code"), write);
        } catch (NoSuchAlgorithmException failure) {
            finish(job, "NOT_APPLIED", "HASH_UNAVAILABLE", null);
        } catch (RuntimeException failure) {
            finish(
                    job,
                    job.hostAdmitted ? "OUTCOME_UNKNOWN" : "NOT_APPLIED",
                    job.hostAdmitted ? "HOST_OUTCOME_UNKNOWN" : McpImageBindingDomain.registrationError(failure),
                    null);
        }
    }

    private <T> T ui(Job job, Supplier<T> action) {
        return McpRequestRegistry.inSession(
                job.key.session,
                () -> execution.ui(() -> {
                    synchronized (this) {
                        if (closed || job.cancelRequested || jobs.get(job.key) != job)
                            throw new CancellationException();
                    }
                    return action.get();
                }));
    }

    private synchronized boolean stop(Job job) {
        if (closed || job.cancelRequested || jobs.get(job.key) != job) {
            finish(job, "CANCELLED", "CANCELLED_BEFORE_HOST", null);
            return true;
        }
        return false;
    }

    private synchronized void phase(Job job, String phase) {
        job.phase = phase;
    }

    private synchronized void finish(Job job, String outcome, String code, Map<String, Object> write) {
        if (job.completedAt != null) return;
        if (!job.hostAdmitted && (closed || job.cancelRequested || jobs.get(job.key) != job)) {
            outcome = "CANCELLED";
            code = "CANCELLED_BEFORE_HOST";
        }
        job.outcome = outcome;
        job.code = code;
        job.writeResult = write;
        job.phase = "TERMINAL";
        job.completedAt = Instant.now().toString();
        if (active == job) active = null;
    }

    private static void release(Job job) {
        if (job.lease != null) {
            job.lease.close();
            job.lease = null;
        }
    }

    private synchronized Map<String, Object> result(Map<String, Object> args) {
        if (closed) return error("UNAVAILABLE");
        var job = jobs.get(new Key(McpRequestRegistry.currentSessionId(), (String) args.get("operationId")));
        return job == null ? error("NOT_FOUND") : output(job);
    }

    private synchronized Map<String, Object> cancel(Map<String, Object> args) {
        if (closed) return error("UNAVAILABLE");
        var job = jobs.get(new Key(McpRequestRegistry.currentSessionId(), (String) args.get("operationId")));
        if (job == null) return error("NOT_FOUND");
        if (job.completedAt == null) job.cancelRequested = true;
        // A pending read/worker/native call continues holding the sole slot until actual completion.
        return output(job);
    }

    synchronized void dropSession(String session) {
        jobs.entrySet().removeIf(e -> {
            if (!e.getKey().session.equals(session)) return false;
            e.getValue().cancelRequested = true;
            return true;
        });
    }

    public synchronized void close() {
        if (closed) return;
        closed = true;
        jobs.values().forEach(j -> j.cancelRequested = true);
        worker.shutdown();
    }

    private static Map<String, Object> error(String code) {
        return McpWriteResults.envelope(Map.of("ok", false, "error", code));
    }

    private synchronized Map<String, Object> output(Job job) {
        var value = new LinkedHashMap<String, Object>();
        value.put(
                "ok",
                Set.of("RUNNING", "APPLIED", "NO_CHANGE", "APPLIED_WITH_READBACK_WARNING")
                        .contains(job.outcome));
        value.put("operationId", job.key.id);
        value.put("phase", job.phase);
        value.put("outcome", job.outcome);
        value.put("code", job.code);
        value.put("startedAt", job.startedAt);
        value.put("completedAt", job.completedAt);
        value.put("cancelRequested", job.cancelRequested);
        value.put("hostAdmitted", job.hostAdmitted);
        value.put("hostCancellationConfirmed", false);
        value.put("fileEvidence", job.fileEvidence);
        value.put("writeResult", job.writeResult);
        return McpWriteResults.envelope(value);
    }

    private record Key(String session, String id) {}

    private static final class Job {
        final Key key;
        final String fingerprint;
        final Map<String, Object> args;
        final String startedAt = Instant.now().toString();
        String phase = "QUEUED", outcome = "RUNNING", code = "QUEUED", completedAt;
        boolean cancelRequested, hostAdmitted;
        Map<String, Object> fileEvidence, writeResult;
        McpFileGrantDomain.Lease lease;

        Job(Key key, String fingerprint, Map<String, Object> args) {
            this.key = key;
            this.fingerprint = fingerprint;
            this.args = args;
        }
    }
}
