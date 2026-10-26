package dev.turboism.plugin.mcp;

import dev.turboism.sdk.ui.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Bounded authorized file observation; no paths, file bytes or snapshot-stability claims on the wire. */
final class McpFileInspectDomain implements AutoCloseable {
    static final String INSPECT = "turboism.files.inspect";
    static final String RESULT = "turboism.files.inspect_result";
    static final int MAX_BYTES = 128 * 1024 * 1024;
    static final int MAX_OPERATIONS = 128;
    private final UserFileAccessService files;
    private final McpFileGrantDomain grants;
    private final McpExecutionBridge execution;
    private final ExecutorService hashing;
    private final Map<Key, Job> jobs = new HashMap<>();
    private Job active;
    private boolean closed;

    McpFileInspectDomain(UserFileAccessService files, McpFileGrantDomain grants, McpExecutionBridge execution) {
        this(files, grants, execution, Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "turboism-mcp-file-hash");
            thread.setDaemon(true);
            return thread;
        }));
    }

    McpFileInspectDomain(
            UserFileAccessService files,
            McpFileGrantDomain grants,
            McpExecutionBridge execution,
            ExecutorService hashing) {
        this.files = Objects.requireNonNull(files);
        this.grants = Objects.requireNonNull(grants);
        this.execution = Objects.requireNonNull(execution);
        this.hashing = Objects.requireNonNull(hashing);
    }

    McpToolCatalog tools() {
        return McpToolCatalog.of(List.of(
                McpRegisteredTool.typed(
                        McpFileInspectSchemas.definition(INSPECT),
                        McpOperationEffect.EXTERNAL_SIDE_EFFECT,
                        McpExecutionAffinity.DIRECT,
                        false,
                        execution,
                        this::inspect),
                McpRegisteredTool.typed(
                        McpFileInspectSchemas.definition(RESULT),
                        McpOperationEffect.READ,
                        McpExecutionAffinity.DIRECT,
                        false,
                        execution,
                        this::result)));
    }

    private synchronized Map<String, Object> inspect(Map<String, Object> args) {
        String session = McpRequestRegistry.currentSessionId();
        if (session.isBlank()) return error("SESSION_REQUIRED");
        if (closed) return error("UNAVAILABLE");
        var key = new Key(session, (String) args.get("operationId"));
        String fingerprint = McpWriteResults.digest(args);
        var old = jobs.get(key);
        if (old != null) return old.fingerprint.equals(fingerprint) ? output(old) : error("OPERATION_ID_CONFLICT");
        if (active != null) return error("READ_BUSY");
        if (jobs.size() >= MAX_OPERATIONS) return error("RESULT_STORE_FULL");
        int limit = ((Number) args.get("maxBytes")).intValue();
        var job = new Job(key, fingerprint, limit);
        jobs.put(key, job);
        active = job;
        try {
            // The SDK owns async I/O scheduling. Keep the lease alive until its actual completion.
            job.lease = grants.consume(session, (String) args.get("grantId"), UserFileMode.READ);
            Objects.requireNonNull(files.readBytesBounded(job.lease.handle(), limit))
                    .whenComplete((read, failure) -> receive(job, read, failure));
        } catch (RuntimeException unavailable) {
            finish(job, "FAILED", "READ_ADMISSION_FAILED", null, null);
        }
        return output(job);
    }

    private void receive(Job job, UserFileReadResult<byte[]> read, Throwable failure) {
        synchronized (this) {
            if (closed || jobs.get(job.key) != job) {
                finish(job, "FAILED", "RESULT_DISCARDED", null, null);
                return;
            }
        }
        try {
            // Hash outside the registry lock and native completion thread; results never retain bytes.
            hashing.execute(() -> process(job, read, failure));
        } catch (RejectedExecutionException stopped) {
            finish(job, "FAILED", "HASH_WORKER_UNAVAILABLE", null, null);
        }
    }

    private void process(Job job, UserFileReadResult<byte[]> read, Throwable failure) {
        synchronized (this) {
            if (closed || jobs.get(job.key) != job) {
                finish(job, "FAILED", "RESULT_DISCARDED", null, null);
                return;
            }
        }
        if (failure != null || read == null) {
            finish(job, "FAILED", "READ_FAILED", null, null);
        } else if (read.error().isPresent()) {
            finish(job, "FAILED", read.error().get().code().name(), null, null);
        } else {
            byte[] bytes = read.value().orElseThrow();
            if (bytes.length > job.limit) finish(job, "FAILED", "SDK_BUDGET_VIOLATION", null, null);
            else if (read.truncated()) finish(job, "TRUNCATED", "NO_COMPLETE_FILE_HASH", null, (long) bytes.length);
            else {
                try {
                    var hash = HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                    finish(job, "COMPLETE", "COMPLETE_READ_OBSERVED", hash, (long) bytes.length);
                } catch (NoSuchAlgorithmException unavailable) {
                    finish(job, "FAILED", "HASH_UNAVAILABLE", null, null);
                }
            }
        }
    }

    private synchronized void finish(Job job, String outcome, String code, String hash, Long byteLength) {
        job.outcome = outcome;
        job.code = code;
        job.hash = hash;
        job.byteLength = byteLength;
        job.completedAt = Instant.now().toString();
        if (active == job) active = null;
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

    synchronized void dropSession(String session) {
        jobs.entrySet().removeIf(entry -> entry.getKey().session.equals(session));
        // A pending native read still occupies the single I/O slot; only actual completion releases it.
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        jobs.clear();
        // Drain queued completion work so its lease cleanup is never dropped by shutdownNow.
        hashing.shutdown();
    }

    private Map<String, Object> output(Job job) {
        var data = new LinkedHashMap<String, Object>();
        data.put("ok", job.outcome.equals("RUNNING") || job.outcome.equals("COMPLETE"));
        data.put("operationId", job.key.operation);
        data.put("outcome", job.outcome);
        data.put("code", job.code);
        data.put("startedAt", job.startedAt);
        data.put("completedAt", job.completedAt);
        data.put("maxBytes", job.limit);
        data.put("byteLength", job.byteLength);
        data.put("sha256", job.hash);
        data.put("fileStability", "UNPROVEN");
        data.put("documentAssociation", "UNVERIFIED");
        data.put("hostCancellationConfirmed", false);
        return McpWriteResults.envelope(data);
    }

    private static Map<String, Object> error(String code) {
        return McpWriteResults.envelope(Map.of("ok", false, "error", code));
    }

    private record Key(String session, String operation) {}

    private static final class Job {
        final Key key;
        final String fingerprint;
        final int limit;
        final String startedAt = Instant.now().toString();
        String completedAt, hash;
        Long byteLength;
        String outcome = "RUNNING", code = "READ_PENDING";
        McpFileGrantDomain.Lease lease;

        Job(Key key, String fingerprint, int limit) {
            this.key = key;
            this.fingerprint = fingerprint;
            this.limit = limit;
        }
    }
}
