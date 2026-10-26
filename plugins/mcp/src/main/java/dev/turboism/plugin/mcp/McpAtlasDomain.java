package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.textureatlas.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Server-retained, scope-bound complete atlas targets and standalone native-Undo apply jobs. */
final class McpAtlasDomain implements AutoCloseable {
    static final String READ = "turboism.atlas.read",
            PLAN = "turboism.atlas.plan",
            APPLY = "turboism.atlas.apply",
            RESULT = "turboism.atlas.result",
            CANCEL = "turboism.atlas.cancel";
    static final long TTL = java.time.Duration.ofMinutes(5).toNanos();
    private final CubismFacade cubism;
    private final McpExecutionBridge execution;
    private final Supplier<McpStoredAuthoringDomain.Context> context;
    private final LongSupplier clock;
    private final ExecutorService worker;
    private final Map<String, Snapshot> snapshots = new LinkedHashMap<>();
    private final Map<Key, Job> jobs = new HashMap<>();
    private Job active;
    private boolean closed;

    McpAtlasDomain(
            CubismFacade cubism, McpExecutionBridge execution, Supplier<McpStoredAuthoringDomain.Context> context) {
        this(cubism, execution, context, System::nanoTime, Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "turboism-mcp-atlas-apply");
            thread.setDaemon(true);
            return thread;
        }));
    }

    McpAtlasDomain(
            CubismFacade cubism,
            McpExecutionBridge execution,
            Supplier<McpStoredAuthoringDomain.Context> context,
            LongSupplier clock,
            ExecutorService worker) {
        this.cubism = Objects.requireNonNull(cubism);
        this.execution = Objects.requireNonNull(execution);
        this.context = Objects.requireNonNull(context);
        this.clock = Objects.requireNonNull(clock);
        this.worker = Objects.requireNonNull(worker);
    }

    McpToolCatalog tools() {
        var versions = ReviewedVersions.since("5.0.00").stream().sorted().toList();
        return McpToolCatalog.of(List.of(
                McpRegisteredTool.typed(
                        McpAtlasSchemas.definition(READ),
                        McpOperationEffect.READ,
                        McpExecutionAffinity.UI_THREAD,
                        false,
                        McpVersionSupport.exact(
                                "cubism.texture-atlas.layout",
                                versions,
                                List.of(
                                        McpVersionSupport.OperationSupport.available(
                                                "complete_rectangle_snapshot",
                                                McpOperationEffect.READ,
                                                false,
                                                McpVersionSupport.UndoVerification.NOT_APPLICABLE,
                                                versions,
                                                "Server-retained complete-atlas target; no native invocation target, render or UI change."))),
                        execution,
                        this::read),
                McpRegisteredTool.typed(
                        McpAtlasSchemas.definition(PLAN),
                        McpOperationEffect.READ,
                        McpExecutionAffinity.UI_THREAD,
                        false,
                        McpVersionSupport.exact(
                                "cubism.texture-atlas.layout",
                                versions,
                                List.of(
                                        McpVersionSupport.OperationSupport.available(
                                                "complete_rectangle_plan",
                                                McpOperationEffect.READ,
                                                false,
                                                McpVersionSupport.UndoVerification.NOT_APPLICABLE,
                                                versions,
                                                "Deterministic height-descending shelf plan on issued complete constraints; no optimality or write admission claim."))),
                        execution,
                        this::plan),
                McpRegisteredTool.typed(
                        McpAtlasSchemas.definition(APPLY),
                        McpOperationEffect.UNDOABLE_WRITE,
                        McpExecutionAffinity.DIRECT,
                        false,
                        McpVersionSupport.exact(
                                "cubism.texture-atlas.layout",
                                versions,
                                List.of(
                                        McpVersionSupport.OperationSupport.available(
                                                "complete_rectangle_apply_job",
                                                McpOperationEffect.UNDOABLE_WRITE,
                                                false,
                                                McpVersionSupport.UndoVerification.RUNTIME_VERIFIED,
                                                versions,
                                                "Retained standalone native atlas Undo job; not an SDK authoring transaction participant. Exact-host Runtime admission and unknown-result semantics remain authoritative."))),
                        execution,
                        this::apply),
                McpRegisteredTool.typed(
                        McpAtlasSchemas.definition(RESULT),
                        McpOperationEffect.READ,
                        McpExecutionAffinity.DIRECT,
                        false,
                        execution,
                        this::result),
                McpRegisteredTool.typed(
                        McpAtlasSchemas.definition(CANCEL),
                        McpOperationEffect.EXTERNAL_SIDE_EFFECT,
                        McpExecutionAffinity.DIRECT,
                        false,
                        execution,
                        this::cancel)));
    }

    private Map<String, Object> read(Map<String, Object> args) {
        try {
            var current = context.get();
            scope(args, current.binding());
            long pixels = ((Number) args.get("maxTotalPixels")).longValue();
            var nativeState = capture(current.binding());
            var wire = McpAtlasLayout.project(nativeState);
            verify(current.binding(), nativeState);
            var hash = McpWriteResults.digest(wire);
            String id = UUID.randomUUID().toString();
            var output = Map.<String, Object>of(
                    "ok",
                    true,
                    "snapshotId",
                    id,
                    "snapshotHash",
                    hash,
                    "state",
                    current.binding(),
                    "atlas",
                    wire,
                    "maxTotalPixels",
                    pixels,
                    "mode",
                    "COMPLETE_RECTANGLES",
                    "outlinesUsedForPacking",
                    false);
            McpAtlasLayout.budget(output);
            synchronized (this) {
                if (closed) throw new UnsupportedOperationException();
                prune();
                while (snapshots.size() >= 16)
                    snapshots.remove(snapshots.keySet().iterator().next());
                snapshots.put(
                        id,
                        new Snapshot(
                                McpRequestRegistry.currentSessionId(),
                                current.binding(),
                                nativeState,
                                hash,
                                pixels,
                                clock.getAsLong()));
            }
            return McpWriteResults.envelope(output);
        } catch (RuntimeException failure) {
            return error(code(failure));
        }
    }

    private Map<String, Object> plan(Map<String, Object> args) {
        try {
            var snapshot = get(args);
            verify(snapshot.binding, snapshot.nativeState);
            String operation = McpAtlasLayout.text(args.get("operationId"), 128);
            var layout = McpAtlasLayout.shelf(snapshot.nativeState, snapshot.pixels);
            var request = Map.<String, Object>of(
                    "documentId",
                    snapshot.binding.get("documentId"),
                    "modelId",
                    snapshot.binding.get("modelId"),
                    "operationId",
                    operation,
                    "snapshotId",
                    args.get("snapshotId"),
                    "layout",
                    McpAtlasLayout.project(layout));
            var output = Map.<String, Object>of(
                    "ok",
                    true,
                    "snapshotHash",
                    snapshot.hash,
                    "applyRequest",
                    request,
                    "pageCount",
                    layout.pageCount(),
                    "placementCount",
                    layout.placements().size(),
                    "totalPixels",
                    McpAtlasLayout.pixels(layout),
                    "writeAdmission",
                    "NOT_CHECKED",
                    "optimality",
                    "NOT_CLAIMED");
            McpAtlasLayout.budget(output);
            verify(snapshot.binding, snapshot.nativeState);
            return McpWriteResults.envelope(output);
        } catch (RuntimeException failure) {
            return error(code(failure));
        }
    }

    private synchronized Map<String, Object> apply(Map<String, Object> args) {
        if (closed) return error("UNAVAILABLE");
        String session = McpRequestRegistry.currentSessionId();
        if (session.isBlank()) return error("SESSION_REQUIRED");
        String id;
        try {
            id = McpAtlasLayout.text(args.get("operationId"), 128);
        } catch (RuntimeException failure) {
            return error(code(failure));
        }
        var key = new Key(session, id);
        String fingerprint = McpWriteResults.digest(args);
        var old = jobs.get(key);
        if (old != null) return old.fingerprint.equals(fingerprint) ? output(old) : error("OPERATION_ID_CONFLICT");
        if (active != null) return error("ATLAS_BUSY");
        if (jobs.size() >= 256) return error("RESULT_STORE_FULL");
        try {
            McpAtlasLayout.budget(args);
        } catch (RuntimeException failure) {
            return error(code(failure));
        }
        var job = new Job(key, fingerprint, Map.copyOf(args));
        jobs.put(key, job);
        active = job;
        try {
            worker.execute(() -> perform(job));
        } catch (RejectedExecutionException unavailable) {
            finish(job, "NOT_APPLIED", "WORKER_UNAVAILABLE");
        }
        return output(job);
    }

    private void perform(Job job) {
        try {
            McpRequestRegistry.inSession(
                    job.key.session,
                    () -> execution.ui(() -> {
                        synchronized (this) {
                            if (stopped(job)) {
                                finish(job, "CANCELLED", "CANCELLED_BEFORE_HOST");
                                return null;
                            }
                        }
                        var snapshot = get(job.request);
                        var layout = McpAtlasLayout.parse(job.request.get("layout"));
                        McpAtlasLayout.validate(snapshot.nativeState, layout, snapshot.pixels);
                        verify(snapshot.binding, snapshot.nativeState);
                        var service = cubism.textureAtlasLayouts();
                        if (!service.isAvailable()) throw new UnsupportedOperationException();
                        synchronized (this) {
                            if (stopped(job)) {
                                finish(job, "CANCELLED", "CANCELLED_BEFORE_HOST");
                                return null;
                            }
                            job.hostAdmitted = true;
                            job.phase = "HOST_RUNNING";
                            job.beforeHash = snapshot.hash;
                            job.expectedHash = McpWriteResults.digest(McpAtlasLayout.project(layout));
                            snapshots.remove((String) job.request.get("snapshotId"));
                        }
                        var result = Objects.requireNonNull(service.apply(snapshot.nativeState.target(), layout));
                        synchronized (this) {
                            job.nativeStatus = result.status().map(Enum::name).orElse(null);
                            job.nativeFailure =
                                    result.failureCode().map(Enum::name).orElse(null);
                        }
                        if (result.failureCode().isPresent()) {
                            boolean unknown = Set.of(
                                            TextureAtlasLayoutFailureCode.PROVIDER_FAILED,
                                            TextureAtlasLayoutFailureCode.PROVIDER_REJECTED)
                                    .contains(result.failureCode().get());
                            finish(
                                    job,
                                    unknown ? "OUTCOME_UNKNOWN" : "NOT_APPLIED",
                                    result.failureCode().get().name());
                            return null;
                        }
                        String outcome = result.status().get() == TextureAtlasLayoutApplyStatus.NO_CHANGE
                                ? "NO_CHANGE"
                                : "APPLIED";
                        try {
                            var afterContext = context.get();
                            if (!sameScope(snapshot.binding, afterContext.binding())) throw new Stale();
                            var after = capture(afterContext.binding());
                            String actual = McpWriteResults.digest(McpAtlasLayout.project(after.currentPlan()));
                            synchronized (this) {
                                job.actualHash = actual;
                            }
                            if (!job.expectedHash.equals(actual)
                                    || !snapshot.nativeState.items().equals(after.items())
                                    || snapshot.nativeState.constraints().edgeMargin()
                                            != after.constraints().edgeMargin()
                                    || snapshot.nativeState.constraints().itemPadding()
                                            != after.constraints().itemPadding()
                                    || snapshot.nativeState.constraints().maxPages()
                                            != after.constraints().maxPages()) throw new Stale();
                            if (!afterContext.binding().equals(context.get().binding())) throw new Stale();
                            synchronized (this) {
                                job.readbackVerified = true;
                                job.historyAfter = afterContext.binding();
                            }
                        } catch (RuntimeException failure) {
                            outcome = "APPLIED_WITH_READBACK_WARNING";
                        }
                        finish(job, outcome, result.status().get().name());
                        return null;
                    }));
        } catch (RuntimeException failure) {
            finish(
                    job,
                    job.hostAdmitted ? "OUTCOME_UNKNOWN" : "NOT_APPLIED",
                    job.hostAdmitted ? "HOST_OUTCOME_UNKNOWN" : code(failure));
        }
    }

    private TextureAtlasLayoutSnapshot capture(Map<String, Object> binding) {
        var service = cubism.textureAtlasLayouts();
        if (!service.isAvailable()) throw new UnsupportedOperationException();
        var snapshot = service.current().orElseThrow(() -> new UnsupportedOperationException());
        if (!snapshot.documentId().equals(binding.get("documentId"))
                || !snapshot.modelId().equals(binding.get("modelId"))) throw new Stale();
        McpAtlasLayout.project(snapshot);
        return snapshot;
    }

    private void verify(Map<String, Object> binding, TextureAtlasLayoutSnapshot expected) {
        var before = context.get();
        if (!binding.equals(before.binding())) throw new Stale();
        var actual = capture(binding);
        if (!expected.documentId().equals(actual.documentId())
                || !expected.modelId().equals(actual.modelId())
                || !expected.atlasId().equals(actual.atlasId())
                || !expected.constraints().equals(actual.constraints())
                || !expected.items().equals(actual.items())
                || !McpAtlasLayout.project(expected.currentPlan()).equals(McpAtlasLayout.project(actual.currentPlan())))
            throw new Stale();
        if (!binding.equals(context.get().binding())) throw new Stale();
    }

    private synchronized Snapshot get(Map<String, Object> args) {
        if (closed) throw new UnsupportedOperationException();
        prune();
        var value = snapshots.get(McpAtlasLayout.text(args.get("snapshotId"), 128));
        if (value == null || !value.session.equals(McpRequestRegistry.currentSessionId()))
            throw new NoSuchElementException();
        scope(args, value.binding);
        return value;
    }

    private void prune() {
        long now = clock.getAsLong();
        snapshots.values().removeIf(s -> now - s.created >= TTL);
    }

    private static void scope(Map<String, Object> args, Map<String, Object> binding) {
        if (!McpAtlasLayout.text(args.get("documentId"), 4096).equals(binding.get("documentId"))
                || !McpAtlasLayout.text(args.get("modelId"), 4096).equals(binding.get("modelId"))) throw new Stale();
        McpAtlasLayout.text(binding.get("historyDocumentBinding"), 4096);
        McpAtlasLayout.text(binding.get("historyManagerBinding"), 4096);
    }

    private static boolean sameScope(Map<String, Object> a, Map<String, Object> b) {
        return List.of("documentId", "modelId", "historyGeneration", "historyDocumentBinding", "historyManagerBinding")
                .stream()
                .allMatch(k -> Objects.equals(a.get(k), b.get(k)));
    }

    private synchronized boolean stopped(Job job) {
        return closed || job.cancelRequested || jobs.get(job.key) != job;
    }

    private synchronized void finish(Job job, String outcome, String code) {
        if (job.completedAt != null) return;
        if (!job.hostAdmitted && stopped(job)) {
            outcome = "CANCELLED";
            code = "CANCELLED_BEFORE_HOST";
        }
        job.outcome = outcome;
        job.code = code;
        job.phase = "TERMINAL";
        job.completedAt = Instant.now().toString();
        job.request = null;
        if (active == job) active = null;
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
        return output(job);
    }

    synchronized void dropSession(String session) {
        snapshots.values().removeIf(s -> s.session.equals(session));
        jobs.entrySet().removeIf(e -> {
            if (!e.getKey().session.equals(session)) return false;
            e.getValue().cancelRequested = true;
            return true;
        });
    }

    public synchronized void close() {
        if (closed) return;
        closed = true;
        snapshots.clear();
        jobs.values().forEach(j -> j.cancelRequested = true);
        worker.shutdown();
    }

    private static Map<String, Object> error(String code) {
        return McpWriteResults.envelope(Map.of("ok", false, "error", code));
    }

    private synchronized Map<String, Object> output(Job job) {
        var out = new LinkedHashMap<String, Object>();
        out.put(
                "ok",
                Set.of("RUNNING", "APPLIED", "NO_CHANGE", "APPLIED_WITH_READBACK_WARNING")
                        .contains(job.outcome));
        out.put("operationId", job.key.id);
        out.put("phase", job.phase);
        out.put("outcome", job.outcome);
        out.put("code", job.code);
        out.put("startedAt", job.startedAt);
        out.put("completedAt", job.completedAt);
        out.put("hostAdmitted", job.hostAdmitted);
        out.put("cancelRequested", job.cancelRequested);
        out.put("hostCancellationConfirmed", false);
        out.put("nativeStatus", job.nativeStatus);
        out.put("nativeFailureCode", job.nativeFailure);
        out.put("beforeSnapshotHash", job.beforeHash);
        out.put("expectedLayoutHash", job.expectedHash);
        out.put("actualLayoutHash", job.actualHash);
        out.put("readbackVerified", job.readbackVerified);
        out.put("historyAfter", job.historyAfter);
        out.put("nativeUndoReceiptAvailable", false);
        out.put("textureBindingsVerified", false);
        out.put("pixelSamplingVerified", false);
        return McpWriteResults.envelope(out);
    }

    private static String code(RuntimeException e) {
        if (e instanceof Stale || e instanceof McpStoredAuthoringDomain.Stale) return "STALE_STATE";
        if (e instanceof McpAtlasLayout.Budget) return "BUDGET_EXCEEDED";
        if (e instanceof McpAtlasLayout.DoesNotFit) return "SHELF_DID_NOT_FIT";
        if (e instanceof NoSuchElementException) return "NOT_FOUND";
        if (e instanceof McpStoredAuthoringDomain.NoModel) return "NO_ACTIVE_MODEL";
        if (e instanceof SecurityException || e instanceof dev.turboism.sdk.permission.CubismPermissionException)
            return "PERMISSION_DENIED";
        if (e instanceof UnsupportedOperationException
                || e instanceof dev.turboism.sdk.cubism.CubismEditorApiUnavailableException) return "UNAVAILABLE";
        if (e instanceof IllegalArgumentException || e instanceof ArithmeticException) return "INVALID_ARGUMENT";
        return "READ_FAILED";
    }

    private record Snapshot(
            String session,
            Map<String, Object> binding,
            TextureAtlasLayoutSnapshot nativeState,
            String hash,
            long pixels,
            long created) {}

    private record Key(String session, String id) {}

    private static final class Stale extends RuntimeException {}

    private static final class Job {
        final Key key;
        final String fingerprint;
        final String startedAt = Instant.now().toString();
        Map<String, Object> request, historyAfter;
        String phase = "QUEUED",
                outcome = "RUNNING",
                code = "QUEUED",
                completedAt,
                nativeStatus,
                nativeFailure,
                beforeHash,
                expectedHash,
                actualHash;
        boolean hostAdmitted, cancelRequested, readbackVerified;

        Job(Key key, String fingerprint, Map<String, Object> request) {
            this.key = key;
            this.fingerprint = fingerprint;
            this.request = request;
        }
    }
}
