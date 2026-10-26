package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.transaction.*;
import dev.turboism.sdk.json.Json;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Explicitly mutating pose inspection with mandatory restoration and independently readable results. */
final class McpPoseGridDomain {
    static final String SCAN = "turboism.render.pose_grid";
    static final String STATUS = "turboism.render.pose_grid.status";
    static final String CANCEL = "turboism.render.pose_grid.cancel";
    static final String RESULT = "turboism.render.pose_grid.result";
    static final int MAX_POSES = 9;
    static final int MAX_AXES = 6;
    static final String RELEASE = "turboism.render.pose_grid.release";
    static final int MAX_JOBS = 64;
    static final int MAX_IMAGE_JOBS = 2;
    static final int MAX_TOTAL_PIXELS = 1_048_576;
    static final int MAX_METADATA_BYTES = 240 * 1024;
    private final CubismFacade cubism;
    private final McpExecutionBridge execution;
    private final McpRenderDomain render;
    private final LongSupplier clock;
    // Bounded, no eviction of results which could allow an old request to be replayed.
    private final Map<Key, Job> jobs = new HashMap<>();

    McpPoseGridDomain(CubismFacade cubism, McpExecutionBridge execution, McpRenderDomain render) {
        this(cubism, execution, render, System::nanoTime);
    }

    McpPoseGridDomain(CubismFacade cubism, McpExecutionBridge execution, McpRenderDomain render, LongSupplier clock) {
        this.cubism = Objects.requireNonNull(cubism);
        this.execution = Objects.requireNonNull(execution);
        this.render = Objects.requireNonNull(render);
        this.clock = Objects.requireNonNull(clock);
    }

    McpToolCatalog tools() {
        var versions = ReviewedVersions.since("5.0.00").stream().sorted().toList();
        var scan = McpRegisteredTool.typed(
                McpPoseGridSchemas.definition(SCAN),
                McpOperationEffect.TRANSACTION_CONTROL,
                McpExecutionAffinity.UI_THREAD,
                false,
                McpVersionSupport.exact(
                        "cubism.editor-model.render.read",
                        versions,
                        List.of(
                                McpVersionSupport.OperationSupport.available(
                                        "pose_grid",
                                        McpOperationEffect.TRANSACTION_CONTROL,
                                        false,
                                        McpVersionSupport.UndoVerification.RUNTIME_VERIFIED,
                                        versions,
                                        "Parameter writes/restoration in own SDK transaction, native render at each verified pose; exact host admission is Runtime-owned. May record one Undo unit even after values are restored."))),
                execution,
                args -> scan(args, () -> false));
        // Capture the request cancellation token before crossing the UI queue. It is used only at
        // cooperative scan boundaries, never to interrupt a setter, GPU call or restoration.
        scan = scan.withPublicHandler(args -> {
            var cancellation = McpRequestRegistry.currentCancellation();
            return execution.ui(() -> scan(args, cancellation));
        });
        var registrations = new ArrayList<McpRegisteredTool>();
        registrations.add(scan);
        for (String name : List.of(STATUS, CANCEL, RESULT, RELEASE))
            registrations.add(McpRegisteredTool.typed(
                    McpPoseGridSchemas.definition(name),
                    (name.equals(CANCEL) || name.equals(RELEASE))
                            ? McpOperationEffect.TRANSACTION_CONTROL
                            : McpOperationEffect.READ,
                    McpExecutionAffinity.DIRECT,
                    false,
                    execution,
                    args -> query(name, args)));
        registrations.add(McpRegisteredTool.typed(
                McpRasterValidationSchemas.definition(),
                McpOperationEffect.READ,
                McpExecutionAffinity.DIRECT,
                false,
                McpVersionSupport.exact(
                        "cubism.editor-model.render.read",
                        versions,
                        List.of(
                                McpVersionSupport.OperationSupport.available(
                                        "retained_raster_checks",
                                        McpOperationEffect.READ,
                                        false,
                                        McpVersionSupport.UndoVerification.NOT_APPLICABLE,
                                        versions,
                                        "Pure checks on retained session-authorized PNGs; no host access or current-model freshness claim."))),
                execution,
                this::validateRaster));
        return McpToolCatalog.of(registrations);
    }

    private Map<String, Object> scan(Map<String, Object> args, McpRequestRegistry.Cancellation cancellation) {
        String id = "";
        Job job = null;
        boolean admitted = false;
        try {
            var required =
                    Set.of("operationId", "expectedState", "width", "height", "poses", "timeBudgetMs", "restorePolicy");
            var allowed = new HashSet<>(required);
            allowed.add("crop");
            if (!args.keySet().containsAll(required) || !allowed.containsAll(args.keySet()))
                throw new IllegalArgumentException();
            id = text(args.get("operationId"), 128);
            String signature = canonicalJson(args);
            var key = new Key(McpRequestRegistry.currentSessionId(), id);
            synchronized (jobs) {
                var previous = jobs.get(key);
                if (previous != null)
                    return previous.signature.equals(signature)
                            ? previous.result()
                            : envelope(
                                    error(id, "OPERATION_ID_CONFLICT", "Identity belongs to a different scan request"),
                                    List.of());
            }
            var initial = render.context();
            McpRenderDomain.requireExpected(args.get("expectedState"), initial.state());
            if (!"always".equals(args.get("restorePolicy")))
                throw new IllegalArgumentException("Restoration is mandatory");
            int width = McpRenderDomain.dimension(args.get("width")),
                    height = McpRenderDomain.dimension(args.get("height"));
            var crop = args.containsKey("crop")
                    ? McpRenderDomain.crop(args.get("crop"), width, height)
                    : new McpRenderDomain.Crop(0, 0, width, height);
            int timeMs = integer(args.get("timeBudgetMs"), 1, 30_000);
            var poses = parsePoses(args.get("poses"));
            if ((long) width * height * poses.size() > MAX_TOTAL_PIXELS)
                throw new IllegalArgumentException("Total pixel budget");
            if (Json.stringify(initial.parameters()).getBytes(StandardCharsets.UTF_8).length > 128 * 1024)
                throw new IllegalArgumentException("Baseline parameter metadata budget");
            var parameters = initial.model().parameters().all();
            var byId = new LinkedHashMap<String, Parameter>();
            var baseline = new LinkedHashMap<String, Float>();
            for (var parameter : parameters) {
                String parameterId = text(parameter.id().value(), 4096);
                if (byId.putIfAbsent(parameterId, parameter) != null)
                    throw new UnsupportedOperationException("Ambiguous parameter IDs");
                float value = parameter.getValue();
                if (!Float.isFinite(value)) throw new IllegalArgumentException("Invalid baseline");
                baseline.put(parameterId, value);
            }
            var targets = new LinkedHashSet<String>();
            poses.forEach(p -> targets.addAll(p.values().keySet()));
            if (targets.size() > MAX_AXES) throw new IllegalArgumentException("Target axis budget");
            for (String target : targets) {
                var parameter = byId.get(target);
                if (parameter == null) throw new NoSuchElementException("Unknown parameter");
                float min = parameter.getMinimumValue(), max = parameter.getMaximumValue();
                if (!Float.isFinite(min)
                        || !Float.isFinite(max)
                        || min > max
                        || baseline.get(target) < min
                        || baseline.get(target) > max)
                    throw new UnsupportedOperationException(
                            "Baseline cannot be safely restored within native parameter range");
            }
            for (var pose : poses)
                for (var value : pose.values().entrySet()) {
                    var parameter = byId.get(value.getKey());
                    if (parameter == null) throw new NoSuchElementException("Unknown parameter");
                    if (value.getValue() < parameter.getMinimumValue()
                            || value.getValue() > parameter.getMaximumValue())
                        throw new IllegalArgumentException("Parameter range exceeded");
                }
            var history = cubism.history().snapshot();
            requireBinding(history);
            var transactions = cubism.authoringTransactions();
            job = new Job(id, signature, initial.state(), initial.parameters(), poses);
            if (Json.stringify(job.metadata("RUNNING", false, null, null, null)).getBytes(StandardCharsets.UTF_8).length
                    > 128 * 1024) throw new IllegalArgumentException("Planned metadata budget");
            synchronized (jobs) {
                if (jobs.size() >= MAX_JOBS
                        || jobs.values().stream().filter(j -> !j.imagesReleased).count() >= MAX_IMAGE_JOBS)
                    return envelope(
                            error(
                                    id,
                                    "RESULT_STORE_FULL",
                                    "Scan retention is full; release completed images before another scan. Metadata identities are retained up to 64 operations per server"),
                            List.of());
                jobs.put(key, job);
                admitted = true;
            }
            Job running = job;
            long start = clock.getAsLong();
            var result =
                    transactions.execute(AuthoringTransactionOptions.of("MCP pose grid (restore parameters)"), () -> {
                        // Preflight again before any mutation. Reads cannot silently introduce new parameter targets.
                        McpRenderDomain.requireExpected(
                                initial.state(), render.context().state());
                        requireHistoryScope(history, cubism.history().snapshot());
                        var expected = new LinkedHashMap<>(baseline);
                        try {
                            for (int i = 0; i < poses.size(); i++) {
                                running.boundary(cancellation, clock.getAsLong() - start, timeMs);
                                running.currentPose = i;
                                var pose = poses.get(i);
                                var desired = new LinkedHashMap<>(baseline);
                                desired.putAll(pose.values());
                                requirePose(initial, history, expected, byId);
                                running.phase = "APPLYING_POSE";
                                for (String target : targets) {
                                    var parameter = byId.get(target);
                                    if (Float.compare(parameter.getValue(), desired.get(target)) != 0)
                                        parameter.setValue(desired.get(target));
                                }
                                expected = desired;
                                requirePose(initial, history, expected, byId);
                                running.boundary(cancellation, clock.getAsLong() - start, timeMs);
                                running.phase = "RENDERING";
                                var state = render.context().state();
                                var capture = render.capture(Map.of(
                                        "expectedState",
                                        state,
                                        "width",
                                        width,
                                        "height",
                                        height,
                                        "crop",
                                        crop.metadata()));
                                var metadata = structured(capture);
                                if (!Boolean.TRUE.equals(metadata.get("ok"))) throw new ScanFailed("RENDER_FAILED");
                                requirePose(initial, history, expected, byId);
                                running.addFrame(i, pose, metadata, image(capture), expected, targets);
                                running.boundary(cancellation, clock.getAsLong() - start, timeMs);
                            }
                        } catch (RuntimeException failure) {
                            running.failureCode =
                                    failure instanceof ScanFailed scan ? scan.code : "POSE_OPERATION_FAILED";
                            throw failure;
                        } finally {
                            running.phase = "RESTORING";
                            // Restore only to the original scope and captured generation-bound handles. Never
                            // write a newly-active document or same-ID replacement. Cancellation cannot skip this.
                            requireScope(initial, history);
                            RuntimeException failed = null;
                            for (String target : targets) {
                                try {
                                    var parameter = byId.get(target);
                                    if (Float.compare(parameter.getValue(), baseline.get(target)) != 0)
                                        parameter.setValue(baseline.get(target));
                                } catch (RuntimeException failure) {
                                    failed = failure;
                                }
                            }
                            if (failed != null) {
                                running.failureCode = "RESTORE_FAILED";
                                throw new ScanFailed("RESTORE_FAILED");
                            }
                            requirePose(initial, history, baseline, byId);
                        }
                        return Boolean.TRUE;
                    });
            var receipt = result.receipt().map(McpPoseGridDomain::receipt).orElse(null);
            boolean restored = false;
            try {
                requirePose(initial, history, baseline, byId);
                restored = true;
            } catch (RuntimeException ignored) {
                /* Report unproven restoration. */
            }
            String outcome = result.successful()
                    ? "COMPLETED"
                    : switch (result.outcome()) {
                        case ROLLED_BACK -> running.stopReason == null ? "FAILED" : running.stopReason;
                        case UNAVAILABLE, REJECTED_STALE, REJECTED_SCOPE -> "NOT_APPLIED";
                        default -> "OUTCOME_UNKNOWN";
                    };
            if (!restored && !Set.of("NOT_APPLIED", "OUTCOME_UNKNOWN").contains(outcome))
                outcome = "RESTORE_UNVERIFIED";
            running.complete(
                    outcome,
                    restored,
                    result.outcome().name(),
                    receipt,
                    result.diagnosticId().orElse(null));
            return running.result();
        } catch (RuntimeException failure) {
            if (admitted) {
                job.complete("OUTCOME_UNKNOWN", false, null, null, null);
                return job.result();
            }
            String code = failure instanceof SecurityException
                            || failure instanceof dev.turboism.sdk.permission.CubismPermissionException
                    ? "PERMISSION_DENIED"
                    : failure instanceof UnsupportedOperationException
                            ? "UNAVAILABLE"
                            : failure instanceof NoSuchElementException
                                    ? "NOT_FOUND"
                                    : failure instanceof IllegalArgumentException
                                            ? "INVALID_ARGUMENT"
                                            : "STALE_OR_UNAVAILABLE";
            return envelope(error(id, code, "Scan preflight rejected; no parameter write was started"), List.of());
        }
    }

    private void requireScope(McpRenderDomain.Context initial, HistorySnapshot history) {
        var now = render.context();
        for (String key : List.of("documentId", "modelId", "historyGeneration"))
            if (!Objects.equals(initial.state().get(key), now.state().get(key))) throw new ScanFailed("SCOPE_CHANGED");
        requireHistoryScope(history, cubism.history().snapshot());
    }

    private void requirePose(
            McpRenderDomain.Context initial,
            HistorySnapshot history,
            Map<String, Float> expected,
            Map<String, Parameter> handles) {
        requireScope(initial, history);
        var now = render.context();
        if (now.parameters().size() != handles.size()) throw new ScanFailed("PARAMETERS_CHANGED");
        var seen = new HashSet<String>();
        for (var row : now.parameters()) {
            String id = (String) row.get("id");
            if (!seen.add(id)
                    || !expected.containsKey(id)
                    || Float.compare(((Number) row.get("value")).floatValue(), expected.get(id)) != 0
                    || Float.compare(handles.get(id).getValue(), expected.get(id)) != 0)
                throw new ScanFailed("POSE_MISMATCH");
        }
        requireScope(initial, history);
    }

    private static void requireBinding(HistorySnapshot value) {
        if (value.availability() != HistorySnapshot.Availability.AVAILABLE
                || value.documentBindingId().isBlank()
                || value.managerBindingId().isBlank())
            throw new UnsupportedOperationException("No exact history binding");
    }

    private static void requireHistoryScope(HistorySnapshot a, HistorySnapshot b) {
        requireBinding(b);
        if (a.generation() != b.generation()
                || !a.documentBindingId().equals(b.documentBindingId())
                || !a.managerBindingId().equals(b.managerBindingId())) throw new ScanFailed("SCOPE_CHANGED");
    }

    private Map<String, Object> validateRaster(Map<String, Object> args) {
        try {
            String id = text(args.get("operationId"), 128);
            Job job;
            synchronized (jobs) {
                job = jobs.get(new Key(McpRequestRegistry.currentSessionId(), id));
            }
            if (job == null) throw McpRasterValidation.fail("NOT_FOUND");
            Map<String, Object> metadata;
            List<Map<String, Object>> images;
            synchronized (job) {
                if (!job.terminal) throw McpRasterValidation.fail("STILL_RUNNING");
                if (job.imagesReleased) throw McpRasterValidation.fail("IMAGES_RELEASED");
                metadata = job.completed;
                images = List.copyOf(job.images);
            }
            return envelope(McpRasterValidation.analyze(args, metadata, images), List.of());
        } catch (McpRasterValidation.Failure failure) {
            return envelope(Map.of("ok", false, "error", failure.code), List.of());
        } catch (RuntimeException failure) {
            return envelope(Map.of("ok", false, "error", "RASTER_CHECK_FAILED"), List.of());
        }
    }

    private Map<String, Object> query(String name, Map<String, Object> args) {
        try {
            fields(args, "operationId");
            String id = text(args.get("operationId"), 128);
            Job job;
            synchronized (jobs) {
                job = jobs.get(new Key(McpRequestRegistry.currentSessionId(), id));
            }
            if (job == null) {
                var unknown = new LinkedHashMap<>(error(
                        id,
                        "NOT_FOUND",
                        "No result in this session/server; absence does not prove unchanged host state"));
                unknown.put("outcome", "OUTCOME_UNKNOWN");
                return envelope(unknown, List.of());
            }
            if (name.equals(CANCEL) && !job.terminal) job.cancel.set(true);
            if (name.equals(RELEASE) && !job.release())
                return envelope(
                        error(
                                id,
                                "STILL_RUNNING",
                                "Images cannot be released before the scan and restoration terminate"),
                        List.of());
            return name.equals(RESULT) ? job.result() : envelope(job.status(), List.of());
        } catch (RuntimeException failure) {
            return envelope(error("", "INVALID_ARGUMENT", "Invalid scan query"), List.of());
        }
    }

    private static List<Pose> parsePoses(Object raw) {
        if (!(raw instanceof List<?> values) || values.isEmpty() || values.size() > MAX_POSES)
            throw new IllegalArgumentException("Pose count");
        var result = new ArrayList<Pose>();
        var labels = new HashSet<String>();
        for (Object value : values) {
            var pose = fields(value, "label", "values");
            String label = text(pose.get("label"), 64);
            if (!labels.add(label)) throw new IllegalArgumentException("Duplicate pose label");
            if (!(pose.get("values") instanceof List<?> parameters)
                    || parameters.isEmpty()
                    || parameters.size() > MAX_AXES) throw new IllegalArgumentException("Pose values");
            var requested = new LinkedHashMap<String, Float>();
            for (Object parameter : parameters) {
                var edit = fields(parameter, "parameterId", "value");
                String id = text(edit.get("parameterId"), 4096);
                if (!(edit.get("value") instanceof Number number) || !Float.isFinite(number.floatValue()))
                    throw new IllegalArgumentException("Nonfinite pose value");
                if (requested.putIfAbsent(id, number.floatValue()) != null)
                    throw new IllegalArgumentException("Duplicate pose target");
            }
            result.add(new Pose(label, Collections.unmodifiableMap(requested)));
        }
        return List.copyOf(result);
    }

    private static final class Job {
        final String id, signature;
        final Map<String, Object> initialState;
        final List<Map<String, Object>> initialParameters;
        final int requested;
        final List<Pose> poses;
        final AtomicBoolean cancel = new AtomicBoolean();
        final List<Map<String, Object>> frames = new ArrayList<>(), images = new ArrayList<>();
        volatile String phase = "STARTING", stopReason, failureCode;
        volatile int currentPose = -1;
        volatile boolean terminal, imagesReleased;
        Map<String, Object> completed;
        int bytes;

        Job(
                String id,
                String signature,
                Map<String, Object> state,
                List<Map<String, Object>> parameters,
                List<Pose> poses) {
            this.id = id;
            this.signature = signature;
            initialState = state;
            initialParameters = parameters;
            this.poses = poses;
            this.requested = poses.size();
        }

        void boundary(McpRequestRegistry.Cancellation cancellation, long elapsed, int timeMs) {
            if (cancel.get() || cancellation.cancelled()) {
                cancel.set(true);
                stopReason = "CANCELLED";
                throw new ScanFailed("CANCELLED");
            }
            if (elapsed >= timeMs * 1_000_000L) {
                stopReason = "TIME_BUDGET_EXCEEDED";
                throw new ScanFailed("TIME_BUDGET_EXCEEDED");
            }
        }

        synchronized void addFrame(
                int index,
                Pose pose,
                Map<String, Object> capture,
                Map<String, Object> image,
                Map<String, Float> expected,
                Set<String> targets) {
            int size = ((Number) capture.get("byteLength")).intValue();
            if (bytes + size > McpRenderDomain.MAX_PNG_BYTES) throw new ScanFailed("IMAGE_BUDGET_EXCEEDED");
            var frame = new LinkedHashMap<String, Object>();
            frame.put("index", index);
            frame.put("label", pose.label());
            frame.put("imageContentIndex", images.size() + 1);
            frame.put("captureId", capture.get("captureId"));
            frame.put("capturedAt", capture.get("capturedAt"));
            frame.put("state", capture.get("state"));
            frame.put(
                    "parameters",
                    targets.stream()
                            .map(id -> Map.<String, Object>of("id", id, "value", expected.get(id)))
                            .toList());
            frame.put("width", capture.get("width"));
            frame.put("height", capture.get("height"));
            for (String field :
                    List.of("renderWidth", "renderHeight", "crop", "pixelCoordinates", "modelToPixelTransform"))
                frame.put(field, capture.get(field));
            frame.put("sha256", capture.get("sha256"));
            frame.put("byteLength", size);
            frames.add(Collections.unmodifiableMap(frame));
            images.add(image);
            bytes += size;
            if (Json.stringify(metadata("RUNNING", false, null, null, null)).getBytes(StandardCharsets.UTF_8).length
                    > MAX_METADATA_BYTES - 4096) {
                frames.remove(frames.size() - 1);
                images.remove(images.size() - 1);
                bytes -= size;
                throw new ScanFailed("METADATA_BUDGET_EXCEEDED");
            }
        }

        synchronized void complete(
                String outcome, boolean restored, String tx, Map<String, Object> receipt, String diagnostic) {
            completed = metadata(outcome, restored, tx, receipt, diagnostic);
            terminal = true;
            phase = "TERMINAL";
        }

        synchronized Map<String, Object> result() {
            return envelope(terminal ? completed : metadata("RUNNING", false, null, null, null), List.copyOf(images));
        }

        synchronized boolean release() {
            if (!terminal) return false;
            if (!imagesReleased) {
                imagesReleased = true;
                images.clear();
                bytes = 0;
                var result = new LinkedHashMap<String, Object>(completed);
                result.put("imagesReleased", true);
                result.put(
                        "frames",
                        frames.stream()
                                .map(frame -> {
                                    var copy = new LinkedHashMap<String, Object>(frame);
                                    copy.put("imageContentIndex", null);
                                    return Collections.unmodifiableMap(copy);
                                })
                                .toList());
                completed = Collections.unmodifiableMap(result);
            }
            return true;
        }

        synchronized Map<String, Object> status() {
            var result = new LinkedHashMap<String, Object>();
            result.put("ok", true);
            result.put("operationId", id);
            result.put("phase", phase);
            result.put("terminal", terminal);
            result.put("completedPoses", frames.size());
            result.put("requestedPoses", requested);
            result.put("cancelRequested", cancel.get());
            result.put("outcome", terminal ? completed.get("outcome") : "RUNNING");
            result.put("imagesReleased", imagesReleased);
            return Collections.unmodifiableMap(result);
        }

        private Map<String, Object> metadata(
                String outcome, boolean restored, String tx, Map<String, Object> receipt, String diagnostic) {
            var result = new LinkedHashMap<String, Object>();
            result.put("ok", outcome.equals("COMPLETED") && restored);
            result.put("operationId", id);
            result.put("outcome", outcome);
            result.put("initialState", initialState);
            result.put("initialParameters", initialParameters);
            result.put("requestedPoses", requested);
            result.put(
                    "poses",
                    poses.stream()
                            .map(p -> Map.<String, Object>of(
                                    "label",
                                    p.label(),
                                    "values",
                                    p.values().entrySet().stream()
                                            .map(e -> Map.<String, Object>of(
                                                    "parameterId", e.getKey(), "value", e.getValue()))
                                            .toList()))
                            .toList());
            result.put("failureCode", failureCode);
            result.put("failedPoseIndex", failureCode == null || currentPose < 0 ? null : currentPose);
            result.put("frames", List.copyOf(frames));
            result.put("parametersRestored", restored);
            result.put("imagesReleased", imagesReleased);
            result.put("transactionOutcome", tx);
            result.put("receipt", receipt);
            result.put("diagnosticId", diagnostic);
            return Collections.unmodifiableMap(result);
        }
    }

    private record Key(String session, String id) {}

    private record Pose(String label, Map<String, Float> values) {}

    private static final class ScanFailed extends IllegalStateException {
        final String code;

        ScanFailed(String code) {
            super(code);
            this.code = code;
        }
    }

    private static Map<String, Object> error(String id, String code, String message) {
        return Map.of(
                "ok",
                false,
                "operationId",
                id,
                "outcome",
                "NOT_APPLIED",
                "error",
                Map.of("code", code, "message", message));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("structuredContent");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> image(Map<String, Object> envelope) {
        var content = (List<?>) envelope.get("content");
        if (content.size() != 2) throw new ScanFailed("INVALID_CAPTURE_RESULT");
        return (Map<String, Object>) content.get(1);
    }

    private static Map<String, Object> envelope(Map<String, Object> output, List<Map<String, Object>> images) {
        var safe = McpJsonSupport.encodable(output);
        var content = new ArrayList<Map<String, Object>>();
        content.add(Map.of("type", "text", "text", Json.stringify(safe)));
        content.addAll(images);
        return Map.of(
                "content",
                List.copyOf(content),
                "structuredContent",
                safe,
                "isError",
                !Boolean.TRUE.equals(output.get("ok")));
    }

    private static Map<String, Object> receipt(AuthoringTransactionReceipt value) {
        var result = new LinkedHashMap<String, Object>();
        result.put("transactionId", value.transactionId());
        result.put("historyEntryId", value.historyEntryId().orElse(null));
        result.put("generationBefore", value.historyBefore().generation());
        result.put("revisionBefore", value.historyBefore().revision());
        result.put("revisionAfter", value.historyAfter().revision());
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Object> fields(Object raw, String... keys) {
        if (!(raw instanceof Map<?, ?> map) || !map.keySet().equals(Set.of(keys)))
            throw new IllegalArgumentException("Closed schema");
        var result = new LinkedHashMap<String, Object>();
        map.forEach((k, v) -> result.put((String) k, v));
        return result;
    }

    private static String text(Object raw, int max) {
        if (!(raw instanceof String s)
                || s.isBlank()
                || s.length() > max
                || s.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid identity");
        return s;
    }

    private static int integer(Object raw, int min, int max) {
        if (!(raw instanceof Number n)) throw new IllegalArgumentException("Integer required");
        int value;
        try {
            value = new java.math.BigDecimal(n.toString()).intValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(e);
        }
        if (value < min || value > max) throw new IllegalArgumentException("Integer budget");
        return value;
    }

    private static String canonicalJson(Map<String, Object> value) {
        return Json.stringify(Map.of("value", canonical(value)));
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new TreeMap<String, Object>();
            map.forEach((k, v) -> result.put((String) k, canonical(v)));
            return result;
        }
        if (value instanceof List<?> list)
            return list.stream().map(McpPoseGridDomain::canonical).toList();
        return value;
    }
}
