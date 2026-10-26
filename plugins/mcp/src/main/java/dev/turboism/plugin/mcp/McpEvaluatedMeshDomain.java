package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.json.Json;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** Immutable, pose-checked observations; intentionally makes no successful-evaluation claim. */
final class McpEvaluatedMeshDomain {
    static final String READ = "turboism.authoring.meshes.evaluated.read";
    static final int MAX_POINTS = 65536, MAX_PARAMETERS = 2048;
    private final McpAuthoringSnapshotStore snapshots;
    private final McpExecutionBridge execution;
    private final Supplier<McpStoredAuthoringDomain.Context> context;

    McpEvaluatedMeshDomain(
            McpAuthoringSnapshotStore snapshots,
            McpExecutionBridge execution,
            Supplier<McpStoredAuthoringDomain.Context> context) {
        this.snapshots = Objects.requireNonNull(snapshots);
        this.execution = Objects.requireNonNull(execution);
        this.context = Objects.requireNonNull(context);
    }

    McpToolCatalog tools() {
        var versions = ReviewedVersions.since("5.0.00").stream().sorted().toList();
        return McpToolCatalog.of(List.of(McpRegisteredTool.typed(
                McpEvaluatedMeshSchemas.definition(),
                McpOperationEffect.READ,
                McpExecutionAffinity.UI_THREAD,
                false,
                McpVersionSupport.exact(
                        "cubism.editor-model.objects.read",
                        versions,
                        List.of(
                                McpVersionSupport.OperationSupport.available(
                                        "calculated_observation",
                                        McpOperationEffect.READ,
                                        false,
                                        McpVersionSupport.UndoVerification.NOT_APPLICABLE,
                                        versions,
                                        "Native calculated-form observation only; exact-host and rendering-configuration admission remains authoritative. No forced update, Core substitution or freshness/success proof."))),
                execution,
                this::read)));
    }

    private Map<String, Object> read(Map<String, Object> args) {
        try {
            if (!Set.of("documentId", "modelId", "objectId", "authoringSnapshotId", "snapshotId", "offset", "limit")
                    .containsAll(args.keySet())) throw new IllegalArgumentException();
            String id = text(args.get("objectId")),
                    session = McpRequestRegistry.currentSessionId(),
                    authoringId = text(args.get("authoringSnapshotId"));
            var initial = context.get();
            if (!text(args.get("documentId")).equals(initial.binding().get("documentId"))
                    || !text(args.get("modelId")).equals(initial.binding().get("modelId"))) throw new Stale();
            var authoring = snapshots.get(authoringId, session);
            if (!authoring.kind().equals("art_mesh")
                    || !authoring.objectId().equals(id)
                    || !authoring.binding().equals(initial.binding())
                    || !(authoring.nativeSnapshot() instanceof ArtMeshAuthoringSnapshot stored)) throw new Stale();
            int offset = index(args.getOrDefault("offset", 0), Integer.MAX_VALUE),
                    limit = index(args.getOrDefault("limit", 128), 512);
            if (limit < 1) throw new IllegalArgumentException();
            verifyAuthoring(initial, authoring);
            McpAuthoringSnapshotStore.Entry sampled;
            if (args.containsKey("snapshotId")) {
                sampled = snapshots.get(text(args.get("snapshotId")), session);
                if (!sampled.kind().equals("art_mesh_evaluated")
                        || !sampled.objectId().equals(id)
                        || !sampled.sourceGuid().equals(authoring.sourceGuid())
                        || !sampled.binding().equals(initial.binding())
                        || !authoringId.equals(sampled.rows().get(0).get("authoringSnapshotId"))) throw new Stale();
            } else {
                if (offset != 0) throw new IllegalArgumentException();
                var nativeSample =
                        initial.model().drawables().find(new ArtMeshId(id)).evaluationSnapshot();
                validate(nativeSample, id, stored);
                var rows = project(nativeSample, authoringId);
                if (!nativeSample.equals(
                        initial.model().drawables().find(new ArtMeshId(id)).evaluationSnapshot())) throw new Stale();
                verifyAuthoring(initial, authoring);
                int bytes = Json.stringify(rows).getBytes(StandardCharsets.UTF_8).length;
                if (bytes > McpAuthoringSnapshotStore.MAX_ENTRY_BYTES) throw new Budget();
                sampled = snapshots.put(
                        session,
                        initial.binding(),
                        "art_mesh_evaluated",
                        id,
                        nativeSample.sourceGuid(),
                        nativeSample,
                        rows,
                        bytes,
                        authoringId);
            }
            if (!(sampled.nativeSnapshot() instanceof ArtMeshEvaluationSnapshot nativeSample)) throw new Stale();
            validate(nativeSample, id, stored);
            // Each page is from one immutable sample; a changed native sample/pose rejects further pages.
            if (!nativeSample.equals(
                    initial.model().drawables().find(new ArtMeshId(id)).evaluationSnapshot())) throw new Stale();
            verifyAuthoring(initial, authoring);
            if (offset > sampled.rows().size()) throw new IllegalArgumentException();
            int end = offset + Math.min(limit, sampled.rows().size() - offset);
            Map<String, Object> output;
            while (true) {
                var value = new LinkedHashMap<String, Object>();
                value.put("ok", true);
                value.put("objectId", id);
                value.put("sourceGuid", sampled.sourceGuid());
                value.put("snapshotId", sampled.snapshotId());
                value.put("authoringSnapshotId", authoringId);
                value.put("state", sampled.binding());
                value.put("freshness", "UNPROVEN");
                value.put(
                        "coordinateSpace",
                        "Native calculated-form coordinateType label only; no Core/source/stored/pixel equivalence or conversion is asserted.");
                value.put("rows", sampled.rows().subList(offset, end));
                value.put("offset", offset);
                value.put("totalRows", sampled.rows().size());
                value.put("nextOffset", end < sampled.rows().size() ? end : null);
                output = value;
                if (Json.stringify(output).getBytes(StandardCharsets.UTF_8).length <= 240 * 1024) break;
                if (end - offset <= 1) throw new Budget();
                end = offset + (end - offset) / 2;
            }
            return envelope(output);
        } catch (RuntimeException e) {
            return envelope(
                    Map.of(
                            "ok",
                            false,
                            "error",
                            Map.of(
                                    "code",
                                    code(e),
                                    "message",
                                    "Calculated observation unavailable or changed; no geometry substitution or freshness verdict returned")));
        }
    }

    private void verifyAuthoring(McpStoredAuthoringDomain.Context initial, McpAuthoringSnapshotStore.Entry expected) {
        var now = context.get();
        if (!initial.binding().equals(now.binding())) throw new Stale();
        var drawable = now.model().drawables().find(new ArtMeshId(expected.objectId()));
        if (!expected.nativeSnapshot().equals(drawable.authoringSnapshot())) throw new Stale();
        var parent = expected.rows().stream()
                .filter(r -> "parent_space".equals(r.get("kind")))
                .findFirst()
                .orElseThrow(Stale::new);
        if (!Objects.equals(
                        parent.get("parentPartId"),
                        drawable.parentPartId().map(v -> v.value()).orElse(null))
                || !Objects.equals(
                        parent.get("parentDeformerId"),
                        drawable.parentDeformerId().map(v -> v.value()).orElse(null))) throw new Stale();
        if (!initial.binding().equals(context.get().binding())) throw new Stale();
    }

    private static void validate(ArtMeshEvaluationSnapshot sample, String id, ArtMeshAuthoringSnapshot authoring) {
        if (!sample.artMeshId().value().equals(id) || !sample.sourceGuid().equals(authoring.sourceGuid()))
            throw new Stale();
        if (sample.liveParameters().parameters().size() > MAX_PARAMETERS
                || sample.consumedParameters().parameters().size() > MAX_PARAMETERS) throw new Budget();
        if (sample.calculatedForm().isPresent()) {
            var form = sample.calculatedForm().get();
            if (form.positions().size() > MAX_POINTS) throw new Budget();
            if (form.positions().size()
                    != authoring.sourceGeometry().positions().size()) throw new Stale();
            text(form.coordinateType());
        }
        for (var state : List.of(sample.liveParameters(), sample.consumedParameters()))
            for (var parameter : state.parameters()) {
                text(parameter.parameterGuid());
                text(parameter.parameterId().value());
            }
    }

    private static List<Map<String, Object>> project(ArtMeshEvaluationSnapshot sample, String authoringId) {
        var rows = new ArrayList<Map<String, Object>>();
        var header = new LinkedHashMap<String, Object>();
        header.put("kind", "observation");
        header.put("authoringSnapshotId", authoringId);
        header.put("sampledAt", Instant.now().toString());
        header.put("passEntryTimeMillis", sample.passEntryTimeMillis());
        header.put("calculatedFormPresent", sample.calculatedForm().isPresent());
        header.put(
                "liveConsumedValuesMatch", values(sample.liveParameters()).equals(values(sample.consumedParameters())));
        rows.add(header);
        if (sample.calculatedForm().isPresent()) {
            var form = sample.calculatedForm().get();
            rows.add(Map.of(
                    "kind",
                    "calculated_form",
                    "drawOrder",
                    form.drawOrder(),
                    "coordinateType",
                    form.coordinateType(),
                    "interpolatedIdentity",
                    form.interpolated(),
                    "pointCount",
                    form.positions().size()));
            for (int i = 0; i < form.positions().size(); i++) {
                var p = form.positions().get(i);
                rows.add(Map.of("kind", "calculated_position", "index", i, "x", p.x(), "y", p.y()));
            }
        }
        for (boolean live : List.of(true, false)) {
            String kind = live ? "live" : "consumed";
            var state = live ? sample.liveParameters() : sample.consumedParameters();
            rows.add(Map.of(
                    "kind",
                    kind + "_state",
                    "updateVersion",
                    state.updateVersion(),
                    "parameterCount",
                    state.parameters().size()));
            for (var parameter : state.parameters())
                rows.add(Map.of(
                        "kind",
                        kind + "_parameter",
                        "parameterGuid",
                        parameter.parameterGuid(),
                        "parameterId",
                        parameter.parameterId().value(),
                        "value",
                        parameter.value()));
        }
        return List.copyOf(rows);
    }

    private static Map<String, Float> values(ArtMeshEvaluationSnapshot.ParameterState state) {
        var values = new HashMap<String, Float>();
        state.parameters().forEach(p -> values.put(p.parameterGuid(), p.value()));
        return values;
    }

    private static int index(Object raw, int max) {
        if (!(raw instanceof Number n)) throw new IllegalArgumentException();
        try {
            int value = new java.math.BigDecimal(n.toString()).intValueExact();
            if (value < 0 || value > max) throw new IllegalArgumentException();
            return value;
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static String text(Object raw) {
        if (!(raw instanceof String s)
                || s.isBlank()
                || s.length() > 4096
                || s.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
        return s;
    }

    private static Map<String, Object> envelope(Map<String, Object> value) {
        var safe = McpJsonSupport.encodable(value);
        return Map.of(
                "content",
                List.of(Map.of("type", "text", "text", Json.stringify(safe))),
                "structuredContent",
                safe,
                "isError",
                !Boolean.TRUE.equals(value.get("ok")));
    }

    private static String code(RuntimeException e) {
        if (e instanceof Stale || e instanceof McpStoredAuthoringDomain.Stale) return "STALE_STATE";
        if (e instanceof McpStoredAuthoringDomain.NoModel) return "NO_ACTIVE_MODEL";
        if (e instanceof NoSuchElementException) return "NOT_FOUND";
        if (e instanceof Budget) return "BUDGET_EXCEEDED";
        if (e instanceof SecurityException || e instanceof dev.turboism.sdk.permission.CubismPermissionException)
            return "PERMISSION_DENIED";
        if (e instanceof UnsupportedOperationException) return "UNAVAILABLE";
        if (e instanceof IllegalArgumentException) return "INVALID_ARGUMENT";
        return "READ_FAILED";
    }

    private static final class Stale extends IllegalStateException {}

    private static final class Budget extends IllegalArgumentException {}
}
