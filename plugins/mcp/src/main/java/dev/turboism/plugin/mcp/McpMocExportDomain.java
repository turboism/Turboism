package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.core.*;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.ui.*;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Official export, Core inspection and single-file authorized atomic package jobs. */
final class McpMocExportDomain implements AutoCloseable {
    static final String PLAN = "turboism.export.moc.plan",
            EXECUTE = "turboism.export.moc.execute",
            RESULT = "turboism.export.moc.result",
            CANCEL = "turboism.export.moc.cancel";
    private final CubismFacade cubism;
    private final UserFileAccessService files;
    private final McpFileGrantDomain grants;
    private final McpExecutionBridge execution;
    private final Supplier<McpStoredAuthoringDomain.Context> context;
    private final ExecutorService worker;
    private final LongSupplier clock;
    private final Map<String, Plan> plans = new LinkedHashMap<>();
    private final Map<Key, Job> jobs = new HashMap<>();
    private Job active;
    private boolean closed;

    McpMocExportDomain(
            CubismFacade cubism,
            UserFileAccessService files,
            McpFileGrantDomain grants,
            McpExecutionBridge execution,
            Supplier<McpStoredAuthoringDomain.Context> context) {
        this(cubism, files, grants, execution, context, System::nanoTime, Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "turboism-mcp-moc-package");
            thread.setDaemon(true);
            return thread;
        }));
    }

    McpMocExportDomain(
            CubismFacade cubism,
            UserFileAccessService files,
            McpFileGrantDomain grants,
            McpExecutionBridge execution,
            Supplier<McpStoredAuthoringDomain.Context> context,
            LongSupplier clock,
            ExecutorService worker) {
        this.cubism = Objects.requireNonNull(cubism);
        this.files = Objects.requireNonNull(files);
        this.grants = Objects.requireNonNull(grants);
        this.execution = Objects.requireNonNull(execution);
        this.context = Objects.requireNonNull(context);
        this.clock = Objects.requireNonNull(clock);
        this.worker = Objects.requireNonNull(worker);
    }

    McpToolCatalog tools() {
        var versions = ReviewedVersions.since("5.3.00").stream().sorted().toList();
        return McpToolCatalog.of(List.of(
                McpRegisteredTool.typed(
                        McpMocExportSchemas.definition(PLAN),
                        McpOperationEffect.READ,
                        McpExecutionAffinity.UI_THREAD,
                        false,
                        McpVersionSupport.exact(
                                "cubism.editor-model.moc-export.read",
                                versions,
                                List.of(
                                        McpVersionSupport.OperationSupport.available(
                                                "moc_package_plan",
                                                McpOperationEffect.READ,
                                                false,
                                                McpVersionSupport.UndoVerification.NOT_APPLICABLE,
                                                versions,
                                                "Read bounded source atlas metadata and history; no native export, Core inspection or grant consumption. Export admission remains unchecked."))),
                        execution,
                        this::plan),
                McpRegisteredTool.typed(
                        McpMocExportSchemas.definition(EXECUTE),
                        McpOperationEffect.EXTERNAL_SIDE_EFFECT,
                        McpExecutionAffinity.DIRECT,
                        false,
                        McpVersionSupport.exact(
                                "cubism.editor-model.moc-export.read",
                                versions,
                                List.of(
                                        McpVersionSupport.OperationSupport.available(
                                                "authorized_moc_package_job",
                                                McpOperationEffect.EXTERNAL_SIDE_EFFECT,
                                                false,
                                                McpVersionSupport.UndoVerification.NOT_APPLICABLE,
                                                versions,
                                                "Native MOC5.3 export and Core consistency inspection, bounded PNG/static model3/manifest ZIP to a same-session authorized WRITE target. Not model authoring/Undo; actual Runtime admission is authoritative."))),
                        execution,
                        this::start),
                McpRegisteredTool.typed(
                        McpMocExportSchemas.definition(RESULT),
                        McpOperationEffect.READ,
                        McpExecutionAffinity.DIRECT,
                        false,
                        execution,
                        this::result),
                McpRegisteredTool.typed(
                        McpMocExportSchemas.definition(CANCEL),
                        McpOperationEffect.EXTERNAL_SIDE_EFFECT,
                        McpExecutionAffinity.DIRECT,
                        false,
                        execution,
                        this::cancel)));
    }

    private Map<String, Object> plan(Map<String, Object> args) {
        try {
            var current = context.get();
            if (!Objects.equals(args.get("documentId"), current.binding().get("documentId"))
                    || !Objects.equals(args.get("modelId"), current.binding().get("modelId"))) throw new Stale();
            String session = McpRequestRegistry.currentSessionId();
            if (session.isBlank()) return error("SESSION_REQUIRED");
            String operation = McpAtlasLayout.text(args.get("operationId"), 128),
                    grant = McpAtlasLayout.text(args.get("grantId"), 128);
            var settings = McpMocPackage.settings(args.get("settings"));
            var raw = (Map<?, ?>) args.get("budgets");
            var budget = new McpMocPackage.Budgets(
                    ((Number) raw.get("maxMocBytes")).intValue(),
                    ((Number) raw.get("maxArchiveBytes")).intValue(),
                    ((Number) raw.get("maxTotalPixels")).longValue());
            var source = source(current, budget);
            var core = cubism.coreRuntime();
            if (!core.isAvailable() || !core.capabilities().mocInspection()) throw new UnsupportedOperationException();
            verify(current.binding(), source, budget);
            String id = UUID.randomUUID().toString();
            var request = Map.<String, Object>of(
                    "operationId",
                    operation,
                    "snapshotId",
                    id,
                    "grantId",
                    grant,
                    "writePolicy",
                    "REPLACE_AUTHORIZED_FILE");
            var value = new Plan(session, current.binding(), source, settings, budget, request, clock.getAsLong());
            var output = Map.<String, Object>of(
                    "ok",
                    true,
                    "snapshotId",
                    id,
                    "sourceState",
                    current.binding(),
                    "source",
                    source,
                    "sourceHash",
                    McpWriteResults.digest(source),
                    "settings",
                    McpMocPackage.project(settings),
                    "executeRequest",
                    request,
                    "writeAdmission",
                    "NOT_CHECKED",
                    "fullCharacterDelivery",
                    false);
            McpAtlasLayout.budget(output);
            synchronized (this) {
                if (closed) return error("UNAVAILABLE");
                prune();
                while (plans.size() >= 16)
                    plans.remove(plans.keySet().iterator().next());
                plans.put(id, value);
            }
            return McpWriteResults.envelope(output);
        } catch (RuntimeException failure) {
            return error(code(failure));
        }
    }

    private synchronized Map<String, Object> start(Map<String, Object> args) {
        if (closed) return error("UNAVAILABLE");
        String session = McpRequestRegistry.currentSessionId();
        if (session.isBlank()) return error("SESSION_REQUIRED");
        var key = new Key(session, (String) args.get("operationId"));
        String fingerprint = McpWriteResults.digest(args);
        var prior = jobs.get(key);
        if (prior != null)
            return fingerprint.equals(prior.fingerprint) ? output(prior) : error("OPERATION_ID_CONFLICT");
        if (active != null) return error("EXPORT_BUSY");
        if (jobs.size() >= 128) return error("RESULT_STORE_FULL");
        var job = new Job(key, fingerprint, Map.copyOf(args));
        jobs.put(key, job);
        active = job;
        try {
            worker.execute(() -> perform(job));
        } catch (RejectedExecutionException unavailable) {
            finish(job, "NOT_WRITTEN", "WORKER_UNAVAILABLE");
        }
        return output(job);
    }

    private void perform(Job job) {
        try {
            var exported = McpRequestRegistry.inSession(
                    job.key.session,
                    () -> execution.ui(() -> {
                        check(job);
                        var plan = get(job);
                        verify(plan.binding, plan.source, plan.budget);
                        var core = cubism.coreRuntime();
                        if (!core.isAvailable() || !core.capabilities().mocInspection())
                            throw new UnsupportedOperationException();
                        synchronized (this) {
                            check(job);
                            job.exportAdmitted = true;
                            job.phase = "EXPORTING";
                        }
                        var export = context.get().model().exportMoc(plan.settings);
                        if (!export.settings().equals(plan.settings)) throw new IllegalArgumentException();
                        var moc = export.moc3();
                        if (moc.length > plan.budget.mocBytes()) throw new Limit();
                        validateTextures(export, plan.source, plan.budget);
                        var info = core.mocInspector().inspect(MocData.copyOf(moc));
                        if (info.version() != MocVersion.V5_3 || info.consistency() != MocConsistency.CONSISTENT)
                            throw new InvalidMoc();
                        verify(plan.binding, plan.source, plan.budget);
                        synchronized (this) {
                            job.sourceVerified = true;
                            job.phase = "ENCODING";
                        }
                        return new Exported(plan, export);
                    }));
            check(job);
            var archive = McpMocPackage.build(
                    exported.export,
                    exported.plan.budget,
                    exported.plan.binding,
                    McpWriteResults.digest(exported.plan.source));
            check(job);
            McpRequestRegistry.inSession(
                    job.key.session,
                    () -> execution.ui(() -> {
                        var plan = get(job);
                        verify(plan.binding, plan.source, plan.budget);
                        synchronized (this) {
                            check(job);
                            job.phase = "WRITE_PENDING";
                            job.lease = grants.consume(
                                    job.key.session, (String) job.request.get("grantId"), UserFileMode.WRITE, "zip");
                            // Grant consumption cannot stop an admitted file operation. Serialize final cancellation
                            // with actual submission and retain the lease/global slot until real completion.
                            check(job);
                            job.manifest = archive.manifest();
                            job.byteLength = archive.bytes().length;
                            job.sha256 = archive.sha256();
                            job.phase = "WRITING";
                            job.writeAdmitted = true;
                            CompletionStage<UserFileWriteResult> stage;
                            try {
                                stage = Objects.requireNonNull(files.writeBytesAtomicBounded(
                                        job.lease.handle(), archive.bytes(), plan.budget.archiveBytes()));
                            } catch (NoSuchMethodError unavailable) {
                                finish(job, "NOT_WRITTEN", "BOUNDED_WRITE_API_UNAVAILABLE");
                                return null;
                            } catch (RuntimeException failure) {
                                finish(job, "OUTCOME_UNKNOWN", "WRITE_SUBMISSION_UNKNOWN");
                                return null;
                            }
                            stage.whenComplete((result, failure) -> complete(job, result, failure));
                        }
                        return null;
                    }));
        } catch (CancellationException failure) {
            finish(job, "CANCELLED", "CANCELLED_BEFORE_WRITE");
        } catch (McpMocPackage.Budget | Limit failure) {
            finish(job, "NOT_WRITTEN", "BUDGET_EXCEEDED");
        } catch (InvalidMoc failure) {
            finish(job, "NOT_WRITTEN", "MOC_VALIDATION_FAILED");
        } catch (IOException failure) {
            finish(job, "NOT_WRITTEN", "ENCODING_FAILED");
        } catch (RuntimeException failure) {
            finish(
                    job,
                    job.writeAdmitted ? "OUTCOME_UNKNOWN" : "NOT_WRITTEN",
                    job.writeAdmitted ? "FILE_OUTCOME_UNKNOWN" : code(failure));
        }
    }

    private static void validateTextures(
            CubismMocExport export, Map<String, Object> source, McpMocPackage.Budgets budget) {
        var rows = (List<?>) source.get("atlases");
        if (export.textures().size() != rows.size()) throw new IllegalArgumentException();
        long pixels = 0;
        for (int i = 0; i < rows.size(); i++) {
            var row = (Map<?, ?>) rows.get(i);
            var texture = export.textures().get(i);
            if (!Objects.equals(row.get("width"), texture.width())
                    || !Objects.equals(row.get("height"), texture.height())) throw new IllegalArgumentException();
            pixels += (long) texture.width() * texture.height();
        }
        if (pixels > budget.pixels()) throw new Limit();
    }

    private Map<String, Object> source(McpStoredAuthoringDomain.Context current, McpMocPackage.Budgets budget) {
        for (String key : List.of("documentId", "modelId", "historyDocumentBinding", "historyManagerBinding"))
            McpAtlasLayout.text(current.binding().get(key), 4096);
        var atlases = current.model().textures().textureAtlases();
        if (atlases.isEmpty()) throw new EmptyAtlas();
        if (atlases.size() > 32) throw new Limit();
        var rows = new ArrayList<Map<String, Object>>();
        var ids = new HashSet<String>();
        long pixels = 0;
        for (var atlas : atlases) {
            String id = McpAtlasLayout.text(atlas.id().value(), 4096);
            if (!ids.add(id)) throw new IllegalArgumentException();
            int width = atlas.width(), height = atlas.height();
            if (width < 1
                    || height < 1
                    || width > 8192
                    || height > 8192
                    || (long) width * height > CubismExportTexture.MAX_PIXELS) throw new Limit();
            pixels += (long) width * height;
            if (pixels > budget.pixels() || pixels > McpMocPackage.MAX_PIXELS) throw new Limit();
            rows.add(Map.of(
                    "id",
                    id,
                    "name",
                    McpAtlasLayout.text(atlas.name(), 128),
                    "width",
                    width,
                    "height",
                    height,
                    "atlasVersion",
                    atlas.atlasVersion(),
                    "modelImageCount",
                    atlas.modelImageCount()));
        }
        var source = Map.<String, Object>of("atlases", List.copyOf(rows), "totalPixels", pixels);
        McpAtlasLayout.budget(source);
        return source;
    }

    private void verify(Map<String, Object> binding, Map<String, Object> expected, McpMocPackage.Budgets budget) {
        var current = context.get();
        if (!binding.equals(current.binding())
                || !expected.equals(source(current, budget))
                || !binding.equals(context.get().binding())) throw new Stale();
    }

    private synchronized Plan get(Job job) {
        check(job);
        prune();
        var plan = plans.get(job.request.get("snapshotId"));
        if (plan == null || !plan.session.equals(job.key.session)) throw new NoSuchElementException();
        if (!plan.request.equals(job.request)) throw new IllegalArgumentException();
        return plan;
    }

    private void prune() {
        plans.values().removeIf(plan -> clock.getAsLong() - plan.created >= McpAtlasDomain.TTL);
    }

    private synchronized void check(Job job) {
        if (closed || job.cancelRequested || jobs.get(job.key) != job) throw new CancellationException();
    }

    private synchronized void complete(Job job, UserFileWriteResult result, Throwable failure) {
        if (failure != null || result == null) finish(job, "OUTCOME_UNKNOWN", "WRITE_COMPLETION_UNKNOWN");
        else if (result.written()) {
            job.fileWriteConfirmed = true;
            finish(job, "WRITTEN", "ATOMIC_WRITE_REPORTED");
        } else finish(job, "NOT_WRITTEN", result.error().orElseThrow().code().name());
    }

    private synchronized void finish(Job job, String outcome, String code) {
        if (job.completedAt != null) return;
        job.outcome = outcome;
        job.code = code;
        job.phase = "TERMINAL";
        job.completedAt = Instant.now().toString();
        job.request = null;
        if (job.lease != null) {
            job.lease.close();
            job.lease = null;
        }
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
        plans.values().removeIf(plan -> plan.session.equals(session));
        jobs.entrySet().removeIf(entry -> {
            if (!entry.getKey().session.equals(session)) return false;
            entry.getValue().cancelRequested = true;
            return true;
        });
    }

    public synchronized void close() {
        if (closed) return;
        closed = true;
        plans.clear();
        jobs.values().forEach(job -> job.cancelRequested = true);
        worker.shutdown();
    }

    private synchronized Map<String, Object> output(Job job) {
        var out = new LinkedHashMap<String, Object>();
        out.put("ok", Set.of("RUNNING", "WRITTEN").contains(job.outcome));
        out.put("operationId", job.key.id);
        out.put("phase", job.phase);
        out.put("outcome", job.outcome);
        out.put("code", job.code);
        out.put("startedAt", job.startedAt);
        out.put("completedAt", job.completedAt);
        out.put("exportAdmitted", job.exportAdmitted);
        out.put("writeAdmitted", job.writeAdmitted);
        out.put("cancelRequested", job.cancelRequested);
        out.put("hostCancellationConfirmed", false);
        out.put("sourceStateVerified", job.sourceVerified);
        out.put("sourceVerificationScope", "HISTORY_AND_ATLAS_METADATA");
        out.put("fileWriteConfirmed", job.fileWriteConfirmed);
        out.put("archiveBytes", job.byteLength);
        out.put("archiveSha256", job.sha256);
        out.put("manifest", job.manifest);
        out.put("fileReadbackVerified", false);
        out.put("playerLoadVerified", false);
        out.put("fullCharacterDelivery", false);
        return McpWriteResults.envelope(out);
    }

    private static Map<String, Object> error(String code) {
        return McpWriteResults.envelope(Map.of("ok", false, "error", code));
    }

    private static String code(RuntimeException failure) {
        if (failure instanceof Stale || failure instanceof McpStoredAuthoringDomain.Stale) return "STALE_STATE";
        if (failure instanceof Limit || failure instanceof McpAtlasLayout.Budget) return "BUDGET_EXCEEDED";
        if (failure instanceof EmptyAtlas) return "NO_TEXTURE_ATLAS";
        if (failure instanceof NoSuchElementException) return "NOT_FOUND";
        if (failure instanceof McpStoredAuthoringDomain.NoModel) return "NO_ACTIVE_MODEL";
        if (failure instanceof SecurityException
                || failure instanceof dev.turboism.sdk.permission.CubismPermissionException) return "PERMISSION_DENIED";
        if (failure instanceof UnsupportedOperationException
                || failure instanceof dev.turboism.sdk.cubism.CubismEditorApiUnavailableException) return "UNAVAILABLE";
        if (failure instanceof IllegalArgumentException) return "INVALID_ARGUMENT";
        return "EXPORT_ADMISSION_OR_EXECUTION_FAILED";
    }

    private record Plan(
            String session,
            Map<String, Object> binding,
            Map<String, Object> source,
            CubismMocExportSettings settings,
            McpMocPackage.Budgets budget,
            Map<String, Object> request,
            long created) {}

    private record Exported(Plan plan, CubismMocExport export) {}

    private record Key(String session, String id) {}

    private static class Stale extends RuntimeException {}

    private static class Limit extends RuntimeException {}

    private static class EmptyAtlas extends RuntimeException {}

    private static class InvalidMoc extends RuntimeException {}

    private static final class Job {
        final Key key;
        final String fingerprint, startedAt = Instant.now().toString();
        Map<String, Object> request, manifest;
        String phase = "QUEUED", outcome = "RUNNING", code = "QUEUED", completedAt, sha256;
        Integer byteLength;
        boolean exportAdmitted, writeAdmitted, cancelRequested, sourceVerified, fileWriteConfirmed;
        McpFileGrantDomain.Lease lease;

        Job(Key key, String fingerprint, Map<String, Object> request) {
            this.key = key;
            this.fingerprint = fingerprint;
            this.request = request;
        }
    }
}
