package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.transaction.*;
import dev.turboism.sdk.json.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Supplier;

/** Bounded ordinary stored-form transaction; results survive a lost response in this server session. */
final class McpKeyformWriteDomain {
    static final String WRITE = "turboism.authoring.keyforms.write";
    static final String PREVIEW = "turboism.authoring.keyforms.preview";
    static final String RESULT = "turboism.authoring.keyforms.result";
    static final int MAX_OPERATIONS = 256;
    static final int MAX_TARGETS = 8;
    private final CubismFacade cubism;
    private final McpExecutionBridge execution;
    private final McpAuthoringSnapshotStore snapshots;
    private final Supplier<McpStoredAuthoringDomain.Context> context;
    private final Map<OperationKey, Operation> operations = new HashMap<>();

    McpKeyformWriteDomain(
            CubismFacade cubism,
            McpExecutionBridge execution,
            McpAuthoringSnapshotStore snapshots,
            Supplier<McpStoredAuthoringDomain.Context> context) {
        this.cubism = Objects.requireNonNull(cubism);
        this.execution = Objects.requireNonNull(execution);
        this.snapshots = Objects.requireNonNull(snapshots);
        this.context = Objects.requireNonNull(context);
    }

    McpToolCatalog tools() {
        var versions = ReviewedVersions.since("5.0.00").stream().sorted().toList();
        return McpToolCatalog.of(List.of(
                McpRegisteredTool.typed(
                        McpKeyformWriteSchemas.definition(WRITE, false),
                        McpOperationEffect.TRANSACTION_CONTROL,
                        McpExecutionAffinity.UI_THREAD,
                        false,
                        McpVersionSupport.exact(
                                "cubism.editor-model.objects.write",
                                versions,
                                List.of(
                                        McpVersionSupport.OperationSupport.available(
                                                "ordinary_stored_batch",
                                                McpOperationEffect.TRANSACTION_CONTROL,
                                                false,
                                                McpVersionSupport.UndoVerification.RUNTIME_VERIFIED,
                                                versions,
                                                "Own synchronous SDK authoring transaction, complete snapshot CAS and independent non-default ordinary forms only. ArtMesh positions, draw order or combined edits use their respective typed SDK batch writers. Runtime exact-host write/Undo admission remains authoritative; no real-host acceptance claim."))),
                        execution,
                        this::write),
                McpRegisteredTool.typed(
                        McpKeyformPreviewSchemas.definition(),
                        McpOperationEffect.READ,
                        McpExecutionAffinity.UI_THREAD,
                        false,
                        McpVersionSupport.exact(
                                "cubism.editor-model.objects.read",
                                versions,
                                List.of(
                                        McpVersionSupport.OperationSupport.available(
                                                "ordinary_stored_preview",
                                                McpOperationEffect.READ,
                                                false,
                                                McpVersionSupport.UndoVerification.NOT_APPLICABLE,
                                                versions,
                                                "Read-only shared request/CAS preflight and bounded expected-state diff; no write/Undo admission or quality acceptance."))),
                        execution,
                        this::preview),
                McpRegisteredTool.typed(
                        McpKeyformWriteSchemas.definition(RESULT, true),
                        McpOperationEffect.READ,
                        McpExecutionAffinity.DIRECT,
                        false,
                        execution,
                        this::result)));
    }

    private Map<String, Object> write(Map<String, Object> args) {
        String id = "";
        OperationKey key = null;
        boolean reserved = false;
        try {
            fields(args, "documentId", "modelId", "operationId", "targets");
            id = identity(args.get("operationId"), 128);
            String session = McpRequestRegistry.currentSessionId();
            key = new OperationKey(session, id);
            String fingerprint = digest(args);
            synchronized (operations) {
                var previous = operations.get(key);
                if (previous != null)
                    return envelope(
                            previous.fingerprint().equals(fingerprint)
                                    ? previous.output()
                                    : output(
                                            id,
                                            "NOT_APPLIED",
                                            "OPERATION_ID_CONFLICT",
                                            "Operation identity already belongs to another request",
                                            null,
                                            null,
                                            List.of()));
            }
            var prepared = prepare(args, session);
            var current = prepared.context();
            var plans = prepared.targets();
            var plannedSummaries = prepared.summaries();
            // Resolve mandatory transaction admission before reserving the operation. No fallback setters.
            var transactions = cubism.authoringTransactions();
            synchronized (operations) {
                if (operations.size() >= MAX_OPERATIONS)
                    return envelope(output(
                            id,
                            "NOT_APPLIED",
                            "RESULT_STORE_FULL",
                            "Result retention is full; no operation was started. Results are retained until this server stops",
                            null,
                            null,
                            List.of()));
                operations.put(
                        key,
                        new Operation(
                                fingerprint,
                                output(
                                        id,
                                        "RUNNING",
                                        "RUNNING",
                                        "Host transaction has not returned",
                                        null,
                                        null,
                                        List.of())));
                reserved = true;
            }
            var result = transactions.execute(AuthoringTransactionOptions.of("MCP ordinary keyforms"), () -> {
                verify(current, plans, false, true); // All targets checked again inside the scope, before any setter.
                for (var target : plans) apply(current.model(), target.plan());
                verify(current, plans, true, false); // Whole snapshots, including unedited fields and forms.
                return Boolean.TRUE;
            });
            String outcome =
                    switch (result.outcome()) {
                        case COMMITTED -> "APPLIED";
                        case NO_CHANGE -> "NO_CHANGE";
                        case ROLLED_BACK -> "ROLLED_BACK";
                        case RECOVERY_FAILED -> "OUTCOME_UNKNOWN";
                        default -> "NOT_APPLIED";
                    };
            boolean verified = false;
            if (result.successful()) {
                try {
                    verify(current, plans, true, false);
                    verified = true;
                } catch (RuntimeException failure) {
                    outcome = "APPLIED_WITH_READBACK_WARNING";
                }
            }
            final boolean readbackVerified = verified;
            var summaries = plannedSummaries.stream()
                    .map(summary -> {
                        var copy = new LinkedHashMap<String, Object>(summary);
                        copy.put("readbackVerified", readbackVerified);
                        return Collections.unmodifiableMap(copy);
                    })
                    .toList();
            var terminal = new LinkedHashMap<>(output(
                    id,
                    outcome,
                    result.diagnosticId().isPresent() ? "TRANSACTION_DIAGNOSTIC" : "NONE",
                    result.diagnosticId().isPresent()
                            ? "See the SDK transaction diagnostic identity"
                            : "Use this receipt as historical operation evidence; rediscover before another write",
                    result.outcome().name(),
                    result.receipt().map(McpKeyformWriteDomain::receipt).orElse(null),
                    summaries));
            terminal.put("diagnosticId", result.diagnosticId().orElse(null));
            // Retain a stable terminal response before returning to HTTP. Never evict and silently replay.
            finish(key, terminal);
            if (result.successful() || result.outcome() == AuthoringTransactionOutcome.RECOVERY_FAILED)
                plans.forEach(t -> snapshots.invalidate(t.snapshot().snapshotId()));
            return envelope(terminal);
        } catch (RuntimeException failure) {
            String outcome = reserved ? "OUTCOME_UNKNOWN" : "NOT_APPLIED";
            String code = reserved ? "RESULT_UNKNOWN" : classify(failure);
            var response = output(
                    id,
                    outcome,
                    code,
                    reserved
                            ? "Transaction did not return authoritative outcome; inspect host history and geometry before any retry"
                            : "Preflight rejected; no transaction was started. Rediscover identity, capability and stored snapshots",
                    null,
                    null,
                    List.of());
            if (reserved) finish(key, response);
            return envelope(response);
        }
    }

    private Map<String, Object> preview(Map<String, Object> args) {
        try {
            fields(args, "documentId", "modelId", "operationId", "targets");
            String id = identity(args.get("operationId"), 128), session = McpRequestRegistry.currentSessionId();
            requireUnusedOperation(session, id);
            var prepared = prepare(args, session);
            var targets = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < prepared.targets().size(); i++) {
                var target = prepared.targets().get(i);
                var row = new LinkedHashMap<>(prepared.summaries().get(i));
                row.put("snapshotId", target.snapshot().snapshotId());
                row.putAll(McpKeyformPreview.summary(target.plan()));
                targets.add(Collections.unmodifiableMap(row));
            }
            var output = Map.<String, Object>of(
                    "ok",
                    true,
                    "applied",
                    false,
                    "operationId",
                    id,
                    "requestHash",
                    digest(args),
                    "state",
                    prepared.context().binding(),
                    "requestedForms",
                    prepared.forms(),
                    "budgetedPoints",
                    prepared.points(),
                    "targets",
                    List.copyOf(targets),
                    "writeAdmission",
                    "NOT_CHECKED",
                    "reservedByPreview",
                    false);
            if (Json.stringify(output).getBytes(StandardCharsets.UTF_8).length > 240 * 1024)
                throw new IllegalArgumentException("Preview budget");
            verify(prepared.context(), prepared.targets(), false, true);
            requireUnusedOperation(session, id);
            return envelope(output);
        } catch (RuntimeException failure) {
            return envelope(Map.of(
                    "ok",
                    false,
                    "error",
                    failure instanceof AlreadyAdmitted ? "OPERATION_ID_ALREADY_ADMITTED" : classify(failure)));
        }
    }

    private void requireUnusedOperation(String session, String id) {
        synchronized (operations) {
            if (operations.containsKey(new OperationKey(session, id))) throw new AlreadyAdmitted();
        }
    }

    private Prepared prepare(Map<String, Object> args, String session) {
        var current = context.get();
        identity(current.binding().get("historyDocumentBinding"), 4096);
        identity(current.binding().get("historyManagerBinding"), 4096);
        if (!identity(args.get("documentId"), 4096).equals(current.binding().get("documentId"))
                || !identity(args.get("modelId"), 4096).equals(current.binding().get("modelId"))) throw new Stale();
        if (!(args.get("targets") instanceof List<?> rawTargets)
                || rawTargets.isEmpty()
                || rawTargets.size() > MAX_TARGETS) throw new IllegalArgumentException("Target budget");
        if (Json.stringify(args).getBytes(StandardCharsets.UTF_8).length > 768 * 1024)
            throw new IllegalArgumentException("Request byte budget");
        var plans = new ArrayList<Target>();
        var seen = new HashSet<String>();
        int edits = 0, points = 0;
        for (Object value : rawTargets) {
            var target = fields(value, "kind", "objectId", "snapshotId", "edits");
            String kind = identity(target.get("kind"), 32), objectId = identity(target.get("objectId"), 4096);
            // Warp and Rotation share the deformer ID namespace.
            if (!seen.add((kind.equals("art_mesh") ? "mesh:" : "deformer:") + objectId))
                throw new IllegalArgumentException("Duplicate object target");
            var snapshot = snapshots.get(identity(target.get("snapshotId"), 128), session);
            if (!snapshot.kind().equals(kind)
                    || !snapshot.objectId().equals(objectId)
                    || !snapshot.binding().equals(current.binding())) throw new Stale();
            identity(snapshot.sourceGuid(), 4096);
            var plan = McpKeyformWritePlan.prepare(snapshot.nativeSnapshot(), target.get("edits"));
            if (plan instanceof McpKeyformWritePlan.Mesh m) {
                edits += Math.max(
                        m.values().size(),
                        Math.max(m.edits().size(), m.drawOrders().size()));
                points += m.edits().stream().mapToInt(e -> e.positions().size()).sum();
            } else if (plan instanceof McpKeyformWritePlan.Warp w) {
                edits += w.edits().size();
                points += w.edits().stream()
                        .mapToInt(e -> e.controlPoints().size())
                        .sum();
            } else if (plan instanceof McpKeyformWritePlan.Rotation r)
                edits += r.edits().size();
            if (edits > McpKeyformWritePlan.MAX_EDITS || points > McpKeyformWritePlan.MAX_POINTS)
                throw new IllegalArgumentException("Aggregate write budget");
            plans.add(new Target(snapshot, plan));
        }
        verify(current, plans, false, true);
        var plannedSummaries = summaries(plans, false);
        if (Json.stringify(plannedSummaries).getBytes(StandardCharsets.UTF_8).length > 96 * 1024)
            throw new IllegalArgumentException("Result summary byte budget");
        return new Prepared(current, List.copyOf(plans), plannedSummaries, edits, points);
    }

    private Map<String, Object> result(Map<String, Object> args) {
        try {
            fields(args, "operationId");
            String id = identity(args.get("operationId"), 128);
            synchronized (operations) {
                var operation = operations.get(new OperationKey(McpRequestRegistry.currentSessionId(), id));
                return envelope(
                        operation == null
                                ? output(
                                        id,
                                        "OUTCOME_UNKNOWN",
                                        "NOT_FOUND",
                                        "No retained result in this session/server; absence does not prove the host was unchanged",
                                        null,
                                        null,
                                        List.of())
                                : operation.output());
            }
        } catch (RuntimeException failure) {
            return envelope(
                    output("", "NOT_APPLIED", "INVALID_ARGUMENT", "Invalid result query", null, null, List.of()));
        }
    }

    private void finish(OperationKey key, Map<String, Object> output) {
        synchronized (operations) {
            var before = operations.get(key);
            operations.put(
                    key, new Operation(before.fingerprint(), Collections.unmodifiableMap(new LinkedHashMap<>(output))));
        }
    }

    private void verify(McpStoredAuthoringDomain.Context initial, List<Target> plans, boolean after, boolean history) {
        var now = context.get();
        if (history ? !initial.binding().equals(now.binding()) : !sameScope(initial.binding(), now.binding()))
            throw new Stale();
        for (var target : plans) {
            var snapshot = target.snapshot();
            Object actual;
            Optional<String> parentPart, parentDeformer;
            if (target.plan() instanceof McpKeyformWritePlan.Mesh m) {
                var object = now.model().drawables().find(m.before().artMeshId());
                actual = object.authoringSnapshot();
                parentPart = object.parentPartId().map(v -> v.value());
                parentDeformer = object.parentDeformerId().map(v -> v.value());
            } else {
                var object = now.model().deformers().find(new DeformerId(snapshot.objectId()));
                if (target.plan() instanceof McpKeyformWritePlan.Warp && object instanceof WarpDeformer warp)
                    actual = warp.authoringSnapshot();
                else if (target.plan() instanceof McpKeyformWritePlan.Rotation
                        && object instanceof RotationDeformer rotation) actual = rotation.authoringSnapshot();
                else throw new Stale();
                parentPart = object.parentPartId().map(v -> v.value());
                parentDeformer = object.parentDeformerId().map(v -> v.value());
            }
            if (!Objects.equals(after ? target.plan().after() : target.plan().before(), actual)) throw new Stale();
            var parent = snapshot.rows().stream()
                    .filter(r -> "parent_space".equals(r.get("kind")))
                    .findFirst()
                    .orElseThrow(Stale::new);
            if (!Objects.equals(parent.get("parentPartId"), parentPart.orElse(null))
                    || !Objects.equals(parent.get("parentDeformerId"), parentDeformer.orElse(null))) throw new Stale();
        }
        var end = context.get();
        if (!now.binding().equals(end.binding())) throw new Stale();
        if (history ? !initial.binding().equals(end.binding()) : !sameScope(initial.binding(), end.binding()))
            throw new Stale();
    }

    private static boolean sameScope(Map<String, Object> a, Map<String, Object> b) {
        return List.of("documentId", "modelId", "historyGeneration", "historyDocumentBinding", "historyManagerBinding")
                .stream()
                .allMatch(k -> Objects.equals(a.get(k), b.get(k)));
    }

    private static void apply(CubismModel model, McpKeyformWritePlan.Plan plan) {
        if (plan.before().equals(plan.after())) return;
        if (plan instanceof McpKeyformWritePlan.Mesh m) {
            var mesh = model.drawables().find(m.before().artMeshId());
            if (!m.values().isEmpty()) mesh.setOrdinaryKeyformValues(m.before(), m.values());
            else if (m.edits().isEmpty()) mesh.setOrdinaryKeyformDrawOrders(m.before(), m.drawOrders());
            else if (m.drawOrders().isEmpty()) mesh.setOrdinaryKeyformPositions(m.before(), m.edits());
            else mesh.setOrdinaryKeyforms(m.before(), m.edits(), m.drawOrders());
        } else if (plan instanceof McpKeyformWritePlan.Warp w)
            ((WarpDeformer) model.deformers().find(w.before().deformerId()))
                    .setOrdinaryKeyformPositions(w.before(), w.edits());
        else if (plan instanceof McpKeyformWritePlan.Rotation r)
            ((RotationDeformer) model.deformers().find(r.before().deformerId()))
                    .setOrdinaryKeyformForms(r.before(), r.edits());
    }

    private static List<Map<String, Object>> summaries(List<Target> targets, boolean verified) {
        return targets.stream()
                .map(t -> Map.<String, Object>of(
                        "kind",
                        t.snapshot().kind(),
                        "objectId",
                        t.snapshot().objectId(),
                        "sourceGuid",
                        t.snapshot().sourceGuid(),
                        "beforeHash",
                        snapshotHash(t.plan().before()),
                        "expectedAfterHash",
                        snapshotHash(t.plan().after()),
                        "readbackVerified",
                        verified))
                .toList();
    }

    private static String snapshotHash(Object value) {
        if (value instanceof ArtMeshAuthoringSnapshot m)
            return digest(Map.of(
                    "kind",
                    "art_mesh",
                    "objectId",
                    m.artMeshId().value(),
                    "sourceGuid",
                    m.sourceGuid(),
                    "rows",
                    McpAuthoringProjection.mesh(m)));
        if (value instanceof WarpAuthoringSnapshot w)
            return digest(Map.of(
                    "kind",
                    "warp_deformer",
                    "objectId",
                    w.deformerId().value(),
                    "sourceGuid",
                    w.sourceGuid(),
                    "rows",
                    McpAuthoringProjection.warp(w)));
        if (value instanceof RotationAuthoringSnapshot r)
            return digest(Map.of(
                    "kind",
                    "rotation_deformer",
                    "objectId",
                    r.deformerId().value(),
                    "sourceGuid",
                    r.sourceGuid(),
                    "rows",
                    McpAuthoringProjection.rotation(r)));
        throw new IllegalArgumentException("Unsupported snapshot");
    }

    private static Map<String, Object> receipt(AuthoringTransactionReceipt value) {
        var result = new LinkedHashMap<String, Object>();
        result.put("transactionId", value.transactionId());
        result.put("label", value.label());
        result.put("historyEntryId", value.historyEntryId().orElse(null));
        result.put("historyBefore", history(value.historyBefore()));
        result.put("historyAfter", history(value.historyAfter()));
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Object> history(HistorySnapshot value) {
        return Map.of(
                "availability",
                value.availability().name(),
                "generation",
                value.generation(),
                "revision",
                value.revision(),
                "position",
                value.position(),
                "documentBindingId",
                value.documentBindingId(),
                "managerBindingId",
                value.managerBindingId());
    }

    private static Map<String, Object> output(
            String id,
            String outcome,
            String code,
            String message,
            String transactionOutcome,
            Map<String, Object> receipt,
            List<Map<String, Object>> targets) {
        var result = new LinkedHashMap<String, Object>();
        result.put(
                "ok",
                Set.of("APPLIED", "NO_CHANGE", "APPLIED_WITH_READBACK_WARNING").contains(outcome));
        result.put("operationId", id);
        result.put("outcome", outcome);
        result.put("code", code);
        result.put("message", message);
        result.put("diagnosticId", null);
        result.put("transactionOutcome", transactionOutcome);
        result.put("receipt", receipt);
        result.put("targets", targets);
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Object> envelope(Map<String, Object> output) {
        var safe = McpJsonSupport.encodable(output);
        return Map.of(
                "content",
                List.of(Map.of("type", "text", "text", Json.stringify(safe))),
                "structuredContent",
                safe,
                "isError",
                !Boolean.TRUE.equals(output.get("ok")));
    }

    private static String classify(RuntimeException e) {
        if (e instanceof Stale || e instanceof McpStoredAuthoringDomain.Stale) return "STALE_STATE";
        if (e instanceof McpStoredAuthoringDomain.NoModel) return "NO_ACTIVE_MODEL";
        if (e instanceof NoSuchElementException) return "NOT_FOUND";
        if (e instanceof SecurityException || e instanceof dev.turboism.sdk.permission.CubismPermissionException)
            return "PERMISSION_DENIED";
        if (e instanceof UnsupportedOperationException) return "UNAVAILABLE";
        if (e instanceof IllegalArgumentException) return "INVALID_ARGUMENT";
        return "PREFLIGHT_FAILED";
    }

    private static Map<String, Object> fields(Object raw, String... fields) {
        if (!(raw instanceof Map<?, ?> map) || !map.keySet().equals(Set.of(fields)))
            throw new IllegalArgumentException("Closed request required");
        var result = new LinkedHashMap<String, Object>();
        map.forEach((k, v) -> result.put((String) k, v));
        return result;
    }

    private static String identity(Object raw, int max) {
        if (!(raw instanceof String value)
                || value.isBlank()
                || value.length() > max
                || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid identity");
        return value;
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            map.forEach((k, v) -> sorted.put((String) k, canonical(v)));
            return sorted;
        }
        if (value instanceof List<?> list)
            return list.stream().map(McpKeyformWriteDomain::canonical).toList();
        return value;
    }

    private static String digest(Object value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(Json.stringify(Map.of("value", canonical(value)))
                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record Prepared(
            McpStoredAuthoringDomain.Context context,
            List<Target> targets,
            List<Map<String, Object>> summaries,
            int forms,
            int points) {}

    private static final class AlreadyAdmitted extends IllegalStateException {}

    private record Target(McpAuthoringSnapshotStore.Entry snapshot, McpKeyformWritePlan.Plan plan) {}

    private record OperationKey(String session, String id) {}

    private record Operation(String fingerprint, Map<String, Object> output) {}

    private static final class Stale extends IllegalStateException {}
}
