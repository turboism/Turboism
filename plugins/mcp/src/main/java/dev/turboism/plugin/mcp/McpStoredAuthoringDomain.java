package dev.turboism.plugin.mcp;

import static dev.turboism.plugin.mcp.McpAuthoringProjection.*;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.json.Json;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/** Immutable, session-bound stored authoring pages. Never moves sliders or selection. */
final class McpStoredAuthoringDomain {
    static final String MESHES_READ = "turboism.authoring.meshes.read";
    static final String WARPS_READ = "turboism.authoring.warps.read";
    static final String ROTATIONS_READ = "turboism.authoring.rotations.read";
    private static final int MAX_PAGE_ROWS = 512;
    private static final int MAX_PAGE_BYTES = 240 * 1024;
    private final CubismFacade cubism;
    private final McpExecutionBridge execution;
    private final McpAuthoringSnapshotStore snapshots;

    McpStoredAuthoringDomain(CubismFacade cubism, McpExecutionBridge execution) {
        this(cubism, execution, new McpAuthoringSnapshotStore());
    }

    McpStoredAuthoringDomain(CubismFacade cubism, McpExecutionBridge execution, McpAuthoringSnapshotStore snapshots) {
        this.cubism = Objects.requireNonNull(cubism);
        this.execution = Objects.requireNonNull(execution);
        this.snapshots = Objects.requireNonNull(snapshots);
    }

    McpToolCatalog tools() {
        var registrations = new ArrayList<McpRegisteredTool>();
        var versions = ReviewedVersions.since("5.0.00").stream().sorted().toList();
        for (String kind : List.of("art_mesh", "warp_deformer", "rotation_deformer")) {
            String name =
                    switch (kind) {
                        case "art_mesh" -> MESHES_READ;
                        case "warp_deformer" -> WARPS_READ;
                        default -> ROTATIONS_READ;
                    };
            registrations.add(McpRegisteredTool.typed(
                    definition(name, kind),
                    McpOperationEffect.READ,
                    McpExecutionAffinity.UI_THREAD,
                    false,
                    McpVersionSupport.exact(
                            "cubism.editor-model.objects.read",
                            versions,
                            List.of(
                                    McpVersionSupport.OperationSupport.available(
                                            "stored_snapshot",
                                            McpOperationEffect.READ,
                                            false,
                                            McpVersionSupport.UndoVerification.NOT_APPLICABLE,
                                            versions,
                                            "SDK stored authoring snapshot and per-form ordinary writer grid eligibility. Read admission is observed; host write admission is UNPROVEN. Runtime checks exact selector authorization. No evaluated/source substitution."))),
                    execution,
                    args -> read(kind, args)));
        }
        return McpToolCatalog.of(registrations);
    }

    private Map<String, Object> read(String kind, Map<String, Object> args) {
        try {
            if (!Set.of("documentId", "modelId", "objectId", "snapshotId", "offset", "limit")
                    .containsAll(args.keySet())) throw new IllegalArgumentException("Unknown field");
            String documentId = text(args, "documentId"),
                    modelId = text(args, "modelId"),
                    objectId = text(args, "objectId");
            int offset = index(args, "offset", 0, Integer.MAX_VALUE), limit = index(args, "limit", 128, MAX_PAGE_ROWS);
            if (limit == 0) throw new IllegalArgumentException("Limit must be positive");
            var current = context();
            if (!documentId.equals(current.binding().get("documentId"))
                    || !modelId.equals(current.binding().get("modelId")))
                throw new Stale("Requested document/model is not active");
            final McpAuthoringSnapshotStore.Entry snapshot;
            if (args.containsKey("snapshotId")) {
                snapshot = snapshots.get(text(args, "snapshotId"), McpRequestRegistry.currentSessionId());
                if (!snapshot.kind().equals(kind)
                        || !snapshot.objectId().equals(objectId)
                        || !snapshot.binding().equals(current.binding()))
                    throw new Stale("Snapshot target or history changed");
            } else {
                if (offset != 0) throw new IllegalArgumentException("First page must start at zero");
                snapshot = capture(kind, objectId, current);
            }
            if (offset > snapshot.rows().size())
                throw new IllegalArgumentException("Offset exceeds snapshot row count");
            int end = Math.min(
                    snapshot.rows().size(),
                    offset + Math.min(limit, snapshot.rows().size() - offset));
            Map<String, Object> output;
            while (true) {
                var result = new LinkedHashMap<String, Object>();
                result.put("ok", true);
                result.put("kind", kind);
                result.put("objectId", objectId);
                result.put("sourceGuid", snapshot.sourceGuid());
                result.put("snapshotId", snapshot.snapshotId());
                result.put("state", snapshot.binding());
                result.put(
                        "coordinateSpace", "native SDK stored coordinates; source/form/evaluated/pixels are distinct");
                result.put("rows", snapshot.rows().subList(offset, end));
                result.put("offset", offset);
                result.put("totalRows", snapshot.rows().size());
                result.put("nextOffset", end < snapshot.rows().size() ? end : null);
                output = Collections.unmodifiableMap(result);
                if (Json.stringify(output).getBytes(StandardCharsets.UTF_8).length <= MAX_PAGE_BYTES) break;
                if (end - offset <= 1) throw new Budget("Single authoring row exceeds page budget");
                end = offset + (end - offset) / 2;
            }
            return envelope(output);
        } catch (Stale failure) {
            return error("STALE_STATE", "Document/model/history or snapshot target changed; rediscover");
        } catch (NoModel failure) {
            return error("NO_ACTIVE_MODEL", "No active model document");
        } catch (NoSuchElementException failure) {
            return error("NOT_FOUND", "Object or session snapshot is absent, expired or evicted");
        } catch (Budget | McpAuthoringProjection.BudgetExceeded failure) {
            return error("BUDGET_EXCEEDED", "Authoring data exceeds the configured snapshot/page budget");
        } catch (SecurityException | dev.turboism.sdk.permission.CubismPermissionException failure) {
            return error("PERMISSION_DENIED", "Model read permission is required");
        } catch (UnsupportedOperationException failure) {
            return error("UNAVAILABLE", "Stored authoring read is not admitted for this object or exact host");
        } catch (IllegalArgumentException failure) {
            return error("INVALID_ARGUMENT", "Invalid authoring read request or unsupported snapshot size");
        } catch (RuntimeException failure) {
            return error(
                    "READ_FAILED", "Stored authoring snapshot could not be read; no substituted geometry returned");
        }
    }

    private McpAuthoringSnapshotStore.Entry capture(String kind, String id, Context context) {
        Object nativeSnapshot;
        OrdinaryKeyformWriteSupport writeSupport;
        String sourceGuid;
        List<Map<String, Object>> rows;
        var header = new LinkedHashMap<String, Object>();
        header.put("kind", "parent_space");
        if (kind.equals("art_mesh")) {
            Drawable drawable = context.model().drawables().find(new ArtMeshId(id));
            var snapshot = drawable.authoringSnapshot();
            writeSupport = readWriteSupport(drawable::ordinaryKeyformWriteSupport);
            if (!snapshot.artMeshId().value().equals(id)) throw new Stale("ArtMesh identity changed");
            nativeSnapshot = snapshot;
            sourceGuid = snapshot.sourceGuid();
            rows = mesh(snapshot);
            header.put(
                    "parentPartId",
                    drawable.parentPartId().map(value -> value.value()).orElse(null));
            header.put(
                    "parentDeformerId",
                    drawable.parentDeformerId().map(value -> value.value()).orElse(null));
        } else {
            Deformer deformer = context.model().deformers().find(new DeformerId(id));
            writeSupport = readWriteSupport(deformer::ordinaryKeyformWriteSupport);
            if (kind.equals("warp_deformer") && deformer instanceof WarpDeformer warp) {
                var snapshot = warp.authoringSnapshot();
                if (!snapshot.deformerId().value().equals(id)) throw new Stale("Warp identity changed");
                nativeSnapshot = snapshot;
                sourceGuid = snapshot.sourceGuid();
                rows = warp(snapshot);
            } else if (kind.equals("rotation_deformer") && deformer instanceof RotationDeformer rotation) {
                var snapshot = rotation.authoringSnapshot();
                if (!snapshot.deformerId().value().equals(id)) throw new Stale("Rotation identity changed");
                nativeSnapshot = snapshot;
                sourceGuid = snapshot.sourceGuid();
                rows = rotation(snapshot);
            } else throw new IllegalArgumentException("Object type mismatch");
            header.put(
                    "parentPartId",
                    deformer.parentPartId().map(value -> value.value()).orElse(null));
            header.put(
                    "parentDeformerId",
                    deformer.parentDeformerId().map(value -> value.value()).orElse(null));
        }
        if (rows.size() > MAX_ROWS - 2) throw new Budget("Snapshot row budget exceeded including parent metadata");
        var completeRows = new ArrayList<Map<String, Object>>(rows.size() + 2);
        completeRows.add(Collections.unmodifiableMap(header));
        completeRows.add(Map.of(
                "kind",
                "ordinary_write_support",
                "hostVersion",
                writeSupport.hostVersion(),
                "reviewedHost",
                writeSupport.reviewedHost().name(),
                "writerSelectors",
                writeSupport.writerSelectors().name(),
                "historySelectors",
                writeSupport.historySelectors().name(),
                "transactionCoordinator",
                writeSupport.transactionCoordinator().name(),
                "writePermission",
                writeSupport.writePermission().name(),
                "operationSelectors",
                operationSelectors(writeSupport)));
        completeRows.addAll(rows);
        int bytes = Json.stringify(completeRows).getBytes(StandardCharsets.UTF_8).length;
        if (bytes > McpAuthoringSnapshotStore.MAX_ENTRY_BYTES) throw new Budget("Snapshot byte budget exceeded");
        if (!context.binding().equals(context().binding())) throw new Stale("Document changed during snapshot");
        return snapshots.put(
                McpRequestRegistry.currentSessionId(),
                context.binding(),
                kind,
                id,
                sourceGuid,
                nativeSnapshot,
                completeRows,
                bytes);
    }

    private static Map<String, Object> operationSelectors(OrdinaryKeyformWriteSupport support) {
        var result = new java.util.LinkedHashMap<String, Object>();
        support.operationSelectors().forEach((operation, check) -> result.put(operation.name(), check.name()));
        return java.util.Collections.unmodifiableMap(result);
    }

    private static OrdinaryKeyformWriteSupport readWriteSupport(
            java.util.function.Supplier<OrdinaryKeyformWriteSupport> read) {
        try {
            return Objects.requireNonNull(read.get());
        } catch (UnsupportedOperationException unavailable) {
            return OrdinaryKeyformWriteSupport.unknown();
        }
    }

    Context context() {
        var document = cubism.activeModelDocument().orElseThrow(NoModel::new);
        var modelSnapshot = cubism.activeModel().orElseThrow(NoModel::new);
        var model = cubism.model().active();
        // Snapshot IDs belong to the workspace session; typed model IDs are native GUIDs.
        // id() validates that the typed handle still belongs to the active native binding.
        if (model.id().value().isBlank()
                || document.model().isEmpty()
                || !document.model().get().modelId().equals(modelSnapshot.modelId()))
            throw new Stale("Document/model identity mismatch");
        var history = cubism.history().snapshot();
        if (history.availability() != HistorySnapshot.Availability.AVAILABLE)
            throw new UnsupportedOperationException("No history identity");
        return new Context(
                model,
                Map.of(
                        "documentId",
                        document.documentId(),
                        "modelId",
                        modelSnapshot.modelId(),
                        "historyGeneration",
                        history.generation(),
                        "historyRevision",
                        history.revision(),
                        "historyDocumentBinding",
                        history.documentBindingId(),
                        "historyManagerBinding",
                        history.managerBindingId()));
    }

    private static Map<String, Object> definition(String name, String kind) {
        var inputFields = new LinkedHashMap<String, Object>();
        inputFields.put("documentId", string());
        inputFields.put("modelId", string());
        inputFields.put("objectId", string());
        inputFields.put("snapshotId", string());
        inputFields.put("offset", integer());
        inputFields.put("limit", Map.of("type", "integer", "minimum", 1, "maximum", MAX_PAGE_ROWS));
        var input = Map.of(
                "type",
                "object",
                "properties",
                inputFields,
                "required",
                List.of("documentId", "modelId", "objectId"),
                "additionalProperties",
                false);
        var rowAlternatives = new ArrayList<Object>((List<?>) rowSchema().get("oneOf"));
        rowAlternatives.add(object(Map.of(
                "kind",
                Map.of("const", "parent_space"),
                "parentPartId",
                Map.of("type", List.of("string", "null")),
                "parentDeformerId",
                Map.of("type", List.of("string", "null")))));
        var output = new LinkedHashMap<String, Object>();
        output.put("ok", Map.of("const", true));
        output.put("kind", Map.of("const", kind));
        output.put("objectId", string());
        output.put("sourceGuid", string());
        output.put("snapshotId", string());
        output.put(
                "state",
                object(Map.of(
                        "documentId",
                        string(),
                        "modelId",
                        string(),
                        "historyGeneration",
                        integer(),
                        "historyRevision",
                        integer(),
                        "historyDocumentBinding",
                        Map.of("type", "string"),
                        "historyManagerBinding",
                        Map.of("type", "string"))));
        output.put("coordinateSpace", string());
        output.put(
                "rows", Map.of("type", "array", "maxItems", MAX_PAGE_ROWS, "items", Map.of("oneOf", rowAlternatives)));
        output.put("offset", integer());
        output.put("totalRows", integer());
        output.put("nextOffset", Map.of("type", List.of("integer", "null")));
        return Map.of(
                "name",
                name,
                "title",
                "Read stored " + kind + " authoring",
                "description",
                "Read immutable stored authoring rows, complete GUID axes/cells/forms and parent-space identities. Start without snapshotId, then reuse it and nextOffset. Snapshot TTL 5 minutes; 4MiB/snapshot, 16MiB cache, 16 snapshots, 512 rows/240KiB per page. No evaluation, parameter or selection changes. History changes require rediscovery. Source data is not current/evaluated geometry. ordinary_write_eligibility rows classify each stored form using the ordinary writer grid rules only. snapshotEligible does not prove host write admission, permissions, freshness, payload budgets, edit mode or transaction readiness; hostWriteAdmission remains UNPROVEN. Default/shared/Morph/unreferenced and malformed-grid targets report explicit reasons. ordinary_write_support separately observes reviewed host, writer/history selectors, per-operation selector metadata (including ArtMesh opacity independently), coordinator presence and model.write permission as UNKNOWN/PRESENT/ABSENT; these snapshot-time observations do not authorize writes. Legacy unsupported queries return UNKNOWN and empty operationSelectors. Missing operation entries mean UNKNOWN; selector PRESENT does not prove form eligibility, host admission or ready transaction. Snapshot contents and eligibility remain historical until writer live CAS.",
                "inputSchema",
                input,
                "outputSchema",
                Map.of(
                        "oneOf",
                        List.of(
                                object(output),
                                object(Map.of(
                                        "ok",
                                        Map.of("const", false),
                                        "error",
                                        object(Map.of("code", string(), "message", string())))))),
                "annotations",
                Map.of("readOnlyHint", true, "destructiveHint", false, "idempotentHint", true, "openWorldHint", false));
    }

    private static String text(Map<String, Object> args, String key) {
        if (!(args.get(key) instanceof String text) || text.isBlank() || text.length() > 4096)
            throw new IllegalArgumentException("Invalid identity");
        return text;
    }

    private static int index(Map<String, Object> args, String key, int fallback, int maximum) {
        if (!args.containsKey(key)) return fallback;
        if (!(args.get(key) instanceof Number number)) throw new IllegalArgumentException("Invalid page index");
        int result;
        try {
            result = new java.math.BigDecimal(number.toString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid page index", failure);
        }
        if (result < 0 || result > maximum) throw new IllegalArgumentException("Page index out of bounds");
        return result;
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

    private static Map<String, Object> error(String code, String message) {
        return envelope(Map.of("ok", false, "error", Map.of("code", code, "message", message)));
    }

    record Context(CubismModel model, Map<String, Object> binding) {}

    static final class Stale extends IllegalStateException {
        Stale(String message) {
            super(message);
        }
    }

    static final class NoModel extends IllegalStateException {}

    private static final class Budget extends IllegalStateException {
        Budget(String message) {
            super(message);
        }
    }
}
