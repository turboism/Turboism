package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.command.*;
import dev.turboism.sdk.ui.UserFileMode;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

/** Authorized native file dispatch, explicitly distinct from durable/reopen verification. */
final class McpDocumentFileDomain {
    static final String SAVE_AS = "turboism.documents.save_as";
    static final String OPEN = "turboism.documents.open";
    static final String RESULT = "turboism.documents.file_result";
    static final int MAX_OPERATIONS = 128;
    private final EditorCommandService commands;
    private final McpFileGrantDomain grants;
    private final McpExecutionBridge execution;
    private final Supplier<Map<String, Object>> state;
    private final McpDocumentLifecycleDomain documents;
    private final Map<Key, Job> jobs = new HashMap<>();

    McpDocumentFileDomain(
            EditorCommandService commands,
            McpFileGrantDomain grants,
            McpExecutionBridge execution,
            Supplier<Map<String, Object>> state) {
        this(commands, grants, execution, state, null);
    }

    McpDocumentFileDomain(
            EditorCommandService commands,
            McpFileGrantDomain grants,
            McpExecutionBridge execution,
            Supplier<Map<String, Object>> state,
            McpDocumentLifecycleDomain documents) {
        this.commands = Objects.requireNonNull(commands);
        this.grants = Objects.requireNonNull(grants);
        this.execution = Objects.requireNonNull(execution);
        this.state = Objects.requireNonNull(state);
        this.documents = documents;
    }

    McpToolCatalog tools() {
        var save = McpRegisteredTool.typed(
                McpDocumentFileSchemas.definition(SAVE_AS),
                McpOperationEffect.EXTERNAL_SIDE_EFFECT,
                McpExecutionAffinity.UI_THREAD,
                false,
                execution,
                this::rawSave);
        save = save.withPublicHandler(this::save);
        var result = McpRegisteredTool.typed(
                McpDocumentFileSchemas.definition(RESULT),
                McpOperationEffect.READ,
                McpExecutionAffinity.DIRECT,
                false,
                execution,
                this::result);
        var open = McpRegisteredTool.typed(
                McpDocumentFileSchemas.definition(OPEN),
                McpOperationEffect.EXTERNAL_SIDE_EFFECT,
                McpExecutionAffinity.UI_THREAD,
                false,
                McpVersionSupport.exact(
                        "cubism.editor-ui.top-menu",
                        List.of("5.3.03"),
                        List.of(
                                McpVersionSupport.OperationSupport.available(
                                        "authorized_cmo3_open",
                                        McpOperationEffect.EXTERNAL_SIDE_EFFECT,
                                        false,
                                        McpVersionSupport.UndoVerification.NOT_APPLICABLE,
                                        List.of("5.3.03"),
                                        "Typed READ grant dispatch; no close/reopen, fresh document, or file-content identity proof."))),
                execution,
                args -> rawOpen(args));
        open = open.withPublicHandler(args -> save(args, EditorFileCommand.OPEN));
        return McpToolCatalog.of(List.of(save, open, result));
    }

    private Map<String, Object> rawSave(Map<String, Object> args) {
        var job = admit(args, EditorFileCommand.SAVE_AS);
        if (job == null) return conflictOrError(args);
        run(job);
        return output(job);
    }

    private Map<String, Object> save(Map<String, Object> args) {
        return save(args, EditorFileCommand.SAVE_AS);
    }

    private Map<String, Object> rawOpen(Map<String, Object> args) {
        var job = admit(args, EditorFileCommand.OPEN);
        if (job == null) return conflictOrError(args);
        run(job);
        return output(job);
    }

    private Map<String, Object> save(Map<String, Object> args, EditorFileCommand command) {
        var job = admit(args, command);
        if (job == null) return conflictOrError(args);
        synchronized (jobs) {
            if (job.claimed) return output(job);
            job.claimed = true;
        }
        try {
            // Captures caller session/cancellation before crossing the UI queue. After native start,
            // this bridge awaits actual completion; a client timeout must query file_result.
            return execution.ui(() -> {
                run(job);
                return output(job);
            });
        } catch (RuntimeException failure) {
            synchronized (jobs) {
                if (job.outcome.equals("QUEUED")) finish(job, "NOT_APPLIED", "CANCELED_BEFORE_HOST", null, null);
                else if (job.outcome.equals("RUNNING")) finish(job, "OUTCOME_UNKNOWN", "EXECUTION_LOST", null, null);
                return output(job);
            }
        }
    }

    private Job admit(Map<String, Object> args, EditorFileCommand command) {
        String session = McpRequestRegistry.currentSessionId();
        if (session.isBlank()) return null;
        var key = new Key(session, (String) args.get("operationId"));
        String fingerprint = McpWriteResults.digest(Map.of("command", command.id(), "arguments", args));
        synchronized (jobs) {
            var old = jobs.get(key);
            if (old != null) return old.fingerprint.equals(fingerprint) ? old : null;
            if (jobs.size() >= MAX_OPERATIONS) return null;
            var snapshot = new LinkedHashMap<String, Object>(args);
            if (command == EditorFileCommand.SAVE_AS) snapshot.put("expectedState", expected(args));
            var job = new Job(key, fingerprint, Map.copyOf(snapshot), command);
            jobs.put(key, job);
            return job;
        }
    }

    private Map<String, Object> conflictOrError(Map<String, Object> args) {
        String session = McpRequestRegistry.currentSessionId();
        synchronized (jobs) {
            String code = session.isBlank()
                    ? "SESSION_REQUIRED"
                    : jobs.containsKey(new Key(session, (String) args.get("operationId")))
                            ? "OPERATION_ID_CONFLICT"
                            : "RESULT_STORE_FULL";
            return McpWriteResults.envelope(Map.of("ok", false, "error", code));
        }
    }

    private void run(Job job) {
        synchronized (jobs) {
            if (!job.outcome.equals("QUEUED")) return;
            job.outcome = "RUNNING";
        }
        boolean nativeStarted = false;
        try {
            boolean open = job.command == EditorFileCommand.OPEN;
            if (open && documents == null) {
                finish(job, "NOT_APPLIED", "UNAVAILABLE", null, null);
                return;
            }
            var expected =
                    open ? documents.expectedSnapshot((String) job.args.get("documentSnapshotId")) : expected(job.args);
            var before = open ? documents.capture() : state.get();
            // Snapshot hostObserved means an observed project/document, not command-service
            // availability. OPEN must also work when the last task document has been closed.
            if (!before.equals(expected)) {
                finish(job, "NOT_APPLIED", "STALE_STATE", null, null);
                return;
            }
            if (open) McpDocumentLifecycleDomain.checkCapacity(before);
            if (!commands.isAvailable()) {
                finish(job, "NOT_APPLIED", "UNAVAILABLE", null, null);
                return;
            }
            try (var grant = grants.consume(
                    job.key.session,
                    (String) job.args.get("grantId"),
                    open ? UserFileMode.READ : UserFileMode.WRITE,
                    "cmo3")) {
                var request = new EditorFileCommandRequest(
                        job.command,
                        grant.handle(),
                        open
                                ? EditorOverwritePolicy.REJECT_EXISTING
                                : EditorOverwritePolicy.valueOf((String) job.args.get("overwritePolicy")));
                if (open) documents.expectedSnapshot((String) job.args.get("documentSnapshotId"));
                if (!(open ? documents.capture() : state.get()).equals(expected)) {
                    finish(job, "NOT_APPLIED", "STALE_STATE", null, null);
                    return;
                }
                McpRequestRegistry.throwIfCancelled();
                nativeStarted = true;
                var result = commands.execute(request);
                if (result == null || !result.commandId().equals(job.command.id())) {
                    finish(job, "OUTCOME_UNKNOWN", "RESULT_COMMAND_MISMATCH", null, null);
                    return;
                }
                Map<String, Object> after = null;
                try {
                    after = Map.copyOf(state.get());
                } catch (RuntimeException ignored) {
                }
                if (result.executed())
                    finish(
                            job,
                            "EXECUTED",
                            open
                                    ? "NATIVE_OPEN_DISPATCHED_REDISCOVER_DOCUMENTS"
                                    : after == null || !after.equals(expected)
                                            ? "STATE_READBACK_WARNING"
                                            : "NATIVE_SAVE_REPORTED",
                            result.status().name(),
                            after);
                else if (result.status() == EditorCommandResult.Status.FAILED)
                    finish(
                            job,
                            "OUTCOME_UNKNOWN",
                            "NATIVE_FAILED",
                            result.status().name(),
                            after);
                else
                    finish(
                            job,
                            "NOT_APPLIED",
                            result.status().name(),
                            result.status().name(),
                            after);
            }
        } catch (CancellationException canceled) {
            finish(job, nativeStarted ? "OUTCOME_UNKNOWN" : "NOT_APPLIED", "CANCELED", null, null);
        } catch (RuntimeException failure) {
            finish(
                    job,
                    nativeStarted ? "OUTCOME_UNKNOWN" : "NOT_APPLIED",
                    nativeStarted ? "NATIVE_EXCEPTION" : "ADMISSION_REJECTED",
                    null,
                    null);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> expected(Map<String, Object> args) {
        var value = (Map<String, Object>) args.get("expectedState");
        var map = new LinkedHashMap<String, Object>(value);
        for (String key : List.of("historyGeneration", "historyRevision"))
            map.put(key, ((Number) map.get(key)).longValue());
        return Map.copyOf(map);
    }

    private void finish(Job job, String outcome, String code, String nativeStatus, Map<String, Object> after) {
        synchronized (jobs) {
            job.outcome = outcome;
            job.code = code;
            job.nativeStatus = nativeStatus;
            job.after = after;
        }
    }

    private Map<String, Object> result(Map<String, Object> args) {
        synchronized (jobs) {
            var job = jobs.get(new Key(McpRequestRegistry.currentSessionId(), (String) args.get("operationId")));
            return job == null
                    ? McpWriteResults.envelope(Map.of("ok", false, "error", "OUTCOME_UNKNOWN"))
                    : output(job);
        }
    }

    private Map<String, Object> output(Job job) {
        synchronized (jobs) {
            var data = new LinkedHashMap<String, Object>();
            data.put("ok", Set.of("QUEUED", "RUNNING", "EXECUTED").contains(job.outcome));
            data.put("operationId", job.key.operation);
            data.put("outcome", job.outcome);
            data.put("code", job.code);
            data.put("nativeStatus", job.nativeStatus);
            data.put("afterState", job.after);
            data.put("persistenceVerified", false);
            data.put("reopenVerified", false);
            data.put("retrySafe", false);
            return McpWriteResults.envelope(data);
        }
    }

    private record Key(String session, String operation) {}

    private static final class Job {
        final Key key;
        final String fingerprint;
        final Map<String, Object> args;
        final EditorFileCommand command;
        boolean claimed;
        String outcome = "QUEUED", code = "QUEUED", nativeStatus;
        Map<String, Object> after;

        Job(Key key, String fingerprint, Map<String, Object> args, EditorFileCommand command) {
            this.key = key;
            this.fingerprint = fingerprint;
            this.args = args;
            this.command = command;
        }
    }
}
