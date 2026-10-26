package dev.turboism.plugin.mcp;

import static dev.turboism.plugin.mcp.McpWriteResults.*;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.transaction.*;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Detached triangulation and source/default mesh creation, with explicit native identity readback. */
final class McpMeshCreationDomain {
    static final String TRIANGULATE = "turboism.authoring.meshes.triangulate",
            PLAN = "turboism.authoring.meshes.create_plan",
            CREATE = "turboism.authoring.meshes.create",
            RESULT = "turboism.authoring.meshes.create_result";
    static final int MAX_POINTS = 4096, MAX_INDICES = 24576, MAX_MESHES = 8, MAX_MODEL_MESHES = 512, MAX_PARTS = 512;
    static final long TTL = java.time.Duration.ofMinutes(5).toNanos();
    private final CubismFacade cubism;
    private final McpExecutionBridge execution;
    private final Supplier<McpStoredAuthoringDomain.Context> context;
    private final LongSupplier clock;
    private final Map<String, Plan> plans = new LinkedHashMap<>();
    private final Map<Key, Operation> operations = new HashMap<>();

    McpMeshCreationDomain(
            CubismFacade cubism, McpExecutionBridge execution, Supplier<McpStoredAuthoringDomain.Context> context) {
        this(cubism, execution, context, System::nanoTime);
    }

    McpMeshCreationDomain(
            CubismFacade cubism,
            McpExecutionBridge execution,
            Supplier<McpStoredAuthoringDomain.Context> context,
            LongSupplier clock) {
        this.cubism = Objects.requireNonNull(cubism);
        this.execution = Objects.requireNonNull(execution);
        this.context = Objects.requireNonNull(context);
        this.clock = Objects.requireNonNull(clock);
    }

    McpToolCatalog tools() {
        var versions = ReviewedVersions.since("5.0.00").stream().sorted().toList();
        return McpToolCatalog.of(List.of(
                McpRegisteredTool.typed(
                        McpMeshCreationSchemas.definition(TRIANGULATE),
                        McpOperationEffect.READ,
                        McpExecutionAffinity.UI_THREAD,
                        false,
                        execution,
                        this::triangulate),
                McpRegisteredTool.typed(
                        McpMeshCreationSchemas.definition(PLAN),
                        McpOperationEffect.READ,
                        McpExecutionAffinity.UI_THREAD,
                        false,
                        execution,
                        this::plan),
                McpRegisteredTool.typed(
                        McpMeshCreationSchemas.definition(CREATE),
                        McpOperationEffect.TRANSACTION_CONTROL,
                        McpExecutionAffinity.UI_THREAD,
                        false,
                        McpVersionSupport.exact(
                                "cubism.editor-model.objects.write",
                                versions,
                                List.of(
                                        McpVersionSupport.OperationSupport.available(
                                                "source_mesh_creation_batch",
                                                McpOperationEffect.TRANSACTION_CONTROL,
                                                false,
                                                McpVersionSupport.UndoVerification.RUNTIME_VERIFIED,
                                                versions,
                                                "Source/default batch creation plus optional registered-image binding inside one SDK transaction. Exact host creation/binding/recovery admission remains authoritative; no default form protection bypass for existing objects."))),
                        execution,
                        this::create),
                McpRegisteredTool.typed(
                        McpMeshCreationSchemas.definition(RESULT),
                        McpOperationEffect.READ,
                        McpExecutionAffinity.DIRECT,
                        false,
                        execution,
                        this::result)));
    }

    private Map<String, Object> triangulate(Map<String, Object> args) {
        try {
            fields(args, "documentId", "modelId", "coordinateSpace", "geometry");
            space(args);
            var current = context.get();
            scope(args, current.binding());
            var input = geometry(args.get("geometry"), false);
            final ArtMeshSourceTriangulation output;
            try {
                output = current.model().drawables().previewSourceTriangulation(input);
            } catch (NoSuchMethodError | AbstractMethodError e) {
                throw new UnsupportedOperationException();
            }
            if (output == null) throw new IllegalStateException("No triangulation returned");
            if (output.positions().size() < 3 || output.triangleIndices().isEmpty())
                throw new UnsupportedOperationException("Empty triangulation");
            if (output.positions().size() > MAX_POINTS
                    || output.triangleIndices().size() > MAX_INDICES) throw new Budget();
            for (var p : output.positions()) finite(p.x(), p.y());
            validTriangles(output.positions(), output.triangleIndices());
            if (!current.binding().equals(context.get().binding())) throw new Stale();
            var result = Map.<String, Object>of(
                    "ok",
                    true,
                    "state",
                    current.binding(),
                    "coordinateSpace",
                    "CANVAS",
                    "positions",
                    pointsJson(output.positions()),
                    "triangleIndices",
                    output.triangleIndices(),
                    "positionsUnchanged",
                    input.positions().equals(output.positions()),
                    "uvMapping",
                    "NOT_RETURNED",
                    "modelMutation",
                    "NONE");
            if (bytes(result) > 240 * 1024) throw new Budget();
            return envelope(result);
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    private Map<String, Object> plan(Map<String, Object> args) {
        try {
            fields(args, "documentId", "modelId", "coordinateSpace", "meshes");
            space(args);
            if (bytes(args) > 768 * 1024) throw new Budget();
            var current = context.get();
            scope(args, current.binding());
            var requests = parseRequests(args.get("meshes"));
            var before = capture(current);
            if (before.meshes.size() + requests.size() > MAX_MODEL_MESHES) throw new Budget();
            var images = new HashSet<String>();
            before.images.stream()
                    .filter(i -> "image".equals(i.get("kind")))
                    .forEach(i -> images.add((String) i.get("id")));
            for (var r : requests) {
                var parent = before.parts.stream()
                        .filter(p -> p.id.equals(r.parentId))
                        .findFirst()
                        .orElseThrow();
                checkParent(before, parent.id);
                if (r.imageId != null && !images.contains(r.imageId)) throw new NoSuchElementException();
            }
            verify(before, true);
            String id = UUID.randomUUID().toString();
            var planned = new Plan(session(), before, requests, clock.getAsLong());
            synchronized (plans) {
                prune();
                while (plans.size() >= 16)
                    plans.remove(plans.keySet().iterator().next());
                plans.put(id, planned);
            }
            return envelope(Map.of(
                    "ok",
                    true,
                    "planId",
                    id,
                    "beforeHash",
                    before.hash(),
                    "requestHash",
                    requestHash(requests),
                    "state",
                    before.binding,
                    "meshes",
                    requests.stream().map(Request::summary).toList(),
                    "writeAdmission",
                    "NOT_CHECKED",
                    "coordinateSpace",
                    "CANVAS",
                    "uvSpace",
                    "NORMALIZED_TEXTURE",
                    "scope",
                    "EXISTING_ARTMESH_AUTHORING_PARENTS_BINDINGS_PARTS_IMAGE_METADATA"));
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    private Map<String, Object> create(Map<String, Object> args) {
        String id = "", beforeHash = null, requestedHash = null;
        Key key = null;
        boolean reserved = false;
        var observed = new ArrayList<Map<String, Object>>();
        try {
            fields(args, "documentId", "modelId", "planId", "operationId");
            id = text(args.get("operationId"), 128);
            key = new Key(session(), id);
            String fingerprint = digest(args);
            synchronized (operations) {
                var old = operations.get(key);
                if (old != null)
                    return envelope(
                            old.fingerprint.equals(fingerprint)
                                    ? old.output
                                    : output(
                                            id,
                                            "NOT_APPLIED",
                                            "OPERATION_ID_CONFLICT",
                                            null,
                                            null,
                                            null,
                                            null,
                                            false,
                                            List.of(),
                                            false));
            }
            Plan plan;
            synchronized (plans) {
                prune();
                plan = plans.get(text(args.get("planId"), 128));
            }
            if (plan == null || !plan.session.equals(session())) throw new NoSuchElementException();
            scope(args, plan.before.binding);
            verify(plan.before, true);
            var service = cubism.authoringTransactions();
            beforeHash = plan.before.hash();
            requestedHash = requestHash(plan.requests);
            synchronized (operations) {
                if (operations.size() >= 256)
                    return envelope(output(
                            id,
                            "NOT_APPLIED",
                            "RESULT_STORE_FULL",
                            null,
                            null,
                            beforeHash,
                            requestedHash,
                            false,
                            List.of(),
                            false));
                operations.put(
                        key,
                        new Operation(
                                fingerprint,
                                output(
                                        id,
                                        "RUNNING",
                                        "RUNNING",
                                        null,
                                        null,
                                        beforeHash,
                                        requestedHash,
                                        false,
                                        List.of(),
                                        false)));
                reserved = true;
            }
            var tx = service.execute(AuthoringTransactionOptions.of("MCP create source meshes"), () -> {
                if (clock.getAsLong() - plan.created >= TTL) throw new Stale();
                verify(plan.before, true);
                var current = context.get();
                if (!plan.before.binding.equals(current.binding())) throw new Stale();
                final List<Drawable> created;
                try {
                    created = current.model()
                            .drawables()
                            .createManyWithSourceGeometry(plan.requests.stream()
                                    .map(r -> new Drawables.SourceGeometryRequest(
                                            r.name, Optional.of(new PartId(r.parentId)), r.source, r.defaults))
                                    .toList());
                } catch (NoSuchMethodError | AbstractMethodError e) {
                    throw new UnsupportedOperationException("Source geometry batch API unavailable", e);
                }
                if (created == null || created.size() != plan.requests.size()) throw new Stale();
                var ids = new HashSet<String>();
                plan.before.meshes.forEach(m -> ids.add(m.id));
                for (int i = 0; i < created.size(); i++) {
                    var mesh = created.get(i);
                    String meshId = text(mesh.id().value(), 4096);
                    if (!ids.add(meshId)) throw new Stale();
                    var r = plan.requests.get(i);
                    observed.add(Map.of("objectId", meshId, "requestIndex", i, "verified", false));
                    if (r.imageId != null) {
                        var handle = current.model()
                                .textures()
                                .bindModelImage(new ModelImageId(r.imageId), new ArtMeshId(meshId));
                        if (handle == null
                                || !handle.modelId().value().equals(plan.before.binding.get("modelId"))
                                || !handle.modelImageId().value().equals(r.imageId)
                                || !handle.artMeshId().equals(Optional.of(new ArtMeshId(meshId)))) throw new Stale();
                    }
                }
                var after = verifyCreated(plan, observed);
                observed.clear();
                observed.addAll(after);
                return Boolean.TRUE;
            });
            String outcome =
                    switch (tx.outcome()) {
                        case COMMITTED -> "APPLIED";
                        case NO_CHANGE -> "NO_CHANGE";
                        case ROLLED_BACK -> "ROLLED_BACK";
                        case RECOVERY_FAILED -> "OUTCOME_UNKNOWN";
                        default -> "NOT_APPLIED";
                    };
            boolean verified = false, restored = false;
            if (tx.successful())
                try {
                    var actual = verifyCreated(plan, observed);
                    observed.clear();
                    observed.addAll(actual);
                    verified = true;
                } catch (RuntimeException e) {
                    outcome = "APPLIED_WITH_READBACK_WARNING";
                }
            else if (tx.outcome() == AuthoringTransactionOutcome.ROLLED_BACK)
                try {
                    verify(plan.before, false);
                    restored = true;
                } catch (RuntimeException e) {
                    outcome = "OUTCOME_UNKNOWN";
                }
            var terminal = output(
                    id,
                    outcome,
                    tx.outcome().name(),
                    tx.receipt().map(McpWriteResults::receipt).orElse(null),
                    tx.diagnosticId().orElse(null),
                    beforeHash,
                    requestedHash,
                    verified,
                    observed,
                    restored);
            finish(key, terminal);
            return envelope(terminal);
        } catch (RuntimeException e) {
            var terminal = output(
                    id,
                    reserved ? "OUTCOME_UNKNOWN" : "NOT_APPLIED",
                    reserved ? "HOST_OUTCOME_UNKNOWN" : code(e),
                    null,
                    null,
                    beforeHash,
                    requestedHash,
                    false,
                    observed,
                    false);
            if (reserved) finish(key, terminal);
            return envelope(terminal);
        }
    }

    private List<Map<String, Object>> verifyCreated(Plan plan, List<Map<String, Object>> observed) {
        if (observed.size() != plan.requests.size()) throw new Stale();
        var after = capture(context.get());
        if (!sameScope(plan.before.binding, after.binding)) throw new Stale();
        if (!after.parts.equals(plan.before.parts)
                || !after.images.equals(plan.before.images)
                || after.meshes.size() != plan.before.meshes.size() + plan.requests.size()) throw new Stale();
        var ids = observed.stream().map(m -> text(m.get("objectId"), 4096)).toList();
        if (new HashSet<>(ids).size() != ids.size()) throw new Stale();
        if (!after.meshes.stream().filter(m -> !ids.contains(m.id)).toList().equals(plan.before.meshes))
            throw new Stale();
        var result = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < ids.size(); i++) {
            String meshId = ids.get(i);
            var m = after.meshes.stream()
                    .filter(v -> v.id.equals(meshId))
                    .findFirst()
                    .orElseThrow();
            var r = plan.requests.get(i);
            var s = m.snapshot;
            if (!m.name.equals(r.name)
                    || !Objects.equals(m.parentPart, r.parentId)
                    || m.parentDeformer != null
                    || m.locked
                    || !s.sourceGuid().equals(m.guid)
                    || !s.sourceGeometry().equals(r.source)
                    || !s.axes().isEmpty()
                    || !s.morphKeys().isEmpty()
                    || s.cells().size() != 1
                    || !s.cells().get(0).coordinates().isEmpty()
                    || s.forms().size() != 1
                    || !s.cells().get(0).formGuid().equals(s.forms().get(0).formGuid())
                    || !s.forms().get(0).positions().equals(r.defaults)) throw new Stale();
            if (r.imageId == null) {
                if (m.input != null) throw new Stale();
            } else if (m.input == null
                    || !"MODEL_IMAGE".equals(m.input.get("kind"))
                    || !r.imageId.equals(m.input.get("modelImageId"))
                    || !meshId.equals(m.input.get("artMeshId"))
                    || !plan.before.binding.get("modelId").equals(m.input.get("modelId"))) throw new Stale();
            var row = new LinkedHashMap<String, Object>();
            row.put("objectId", meshId);
            row.put("verified", true);
            row.put("sourceGuid", m.guid);
            row.put("requestIndex", i);
            row.put("parentPartId", m.parentPart);
            row.put("modelImageId", r.imageId);
            row.put("formGuid", s.forms().get(0).formGuid());
            row.put("sourceHash", digest(geometryJson(s.sourceGeometry())));
            row.put(
                    "defaultHash",
                    digest(Map.of("positions", pointsJson(s.forms().get(0).positions()))));
            row.put("imageBound", m.input != null);
            row.put("defaultDrawOrder", s.forms().get(0).drawOrder());
            row.put("defaultOpacity", s.forms().get(0).opacity().orElse(null));
            result.add(Collections.unmodifiableMap(row));
        }
        return List.copyOf(result);
    }

    private State capture(McpStoredAuthoringDomain.Context current) {
        try {
            return captureNative(current);
        } catch (NoSuchMethodError | AbstractMethodError e) {
            throw new UnsupportedOperationException("Required native authoring reader unavailable", e);
        }
    }

    private State captureNative(McpStoredAuthoringDomain.Context current) {
        var model = current.model();
        var parts = new ArrayList<Parent>();
        var seen = new HashSet<String>();
        var rawParts = model.parts().all();
        if (rawParts.size() > MAX_PARTS) throw new Budget();
        for (var p : rawParts) {
            String id = text(p.id().value(), 4096);
            if (!seen.add(id)) throw new IllegalArgumentException();
            parts.add(new Parent(
                    id,
                    text(p.name(), 4096),
                    p.parentId().map(PartId::value).orElse(null),
                    p.index(),
                    p.visible(),
                    p.locked()));
        }
        var images = new ArrayList<Map<String, Object>>();
        var imageIds = new HashSet<String>();
        var groups = model.textures().modelImageGroups();
        if (groups.size() > 128) throw new Budget();
        // Include empty groups in metadata identity without inventing a group GUID.
        for (int g = 0; g < groups.size(); g++) {
            var entries = groups.get(g).modelImages();
            if (imageIds.size() + entries.size() > 1024) throw new Budget();
            images.add(Map.of(
                    "kind",
                    "group",
                    "index",
                    g,
                    "name",
                    groups.get(g).groupName(),
                    "memo",
                    groups.get(g).memo(),
                    "count",
                    entries.size()));
            for (var image : entries) {
                String id = text(image.id().value(), 4096);
                if (!imageIds.add(id) || image.width() < 1 || image.height() < 1) throw new IllegalArgumentException();
                images.add(Map.of(
                        "kind",
                        "image",
                        "id",
                        id,
                        "name",
                        image.name(),
                        "width",
                        image.width(),
                        "height",
                        image.height(),
                        "groupIndex",
                        g));
            }
        }
        var meshes = new ArrayList<Mesh>();
        var guids = new HashSet<String>();
        seen.clear();
        var rawMeshes = model.drawables().all();
        if (rawMeshes.size() > MAX_MODEL_MESHES) throw new Budget();
        long pointCount = 0;
        int encoded = bytes(Map.of("parts", parts.stream().map(Parent::json).toList(), "images", images));
        if (encoded > 4 * 1024 * 1024) throw new Budget();
        for (var d : rawMeshes) {
            String id = text(d.id().value(), 4096);
            if (!seen.add(id)) throw new IllegalArgumentException();
            var s = d.authoringSnapshot();
            pointCount += s.sourceGeometry().positions().size();
            for (var f : s.forms()) pointCount += f.positions().size();
            if (pointCount > 262144) throw new Budget();
            var input = model.textures()
                    .textureInput(d.id())
                    .map(McpImageBindingDomain::project)
                    .orElse(null);
            var m = new Mesh(
                    id,
                    text(d.guid(), 4096),
                    text(d.name(), 4096),
                    d.parentPartId().map(PartId::value).orElse(null),
                    d.parentDeformerId().map(DeformerId::value).orElse(null),
                    d.locked(),
                    s,
                    input);
            if (!guids.add(m.guid)
                    || !s.artMeshId().equals(d.id())
                    || !s.sourceGuid().equals(m.guid)) throw new Stale();
            encoded += bytes(m.json());
            if (encoded > 4 * 1024 * 1024) throw new Budget();
            meshes.add(m);
        }
        if (!current.binding().equals(context.get().binding())) throw new Stale();
        return new State(Map.copyOf(current.binding()), List.copyOf(parts), List.copyOf(images), List.copyOf(meshes));
    }

    private void verify(State state, boolean exact) {
        var current = context.get();
        if (exact ? !state.binding.equals(current.binding()) : !sameScope(state.binding, current.binding()))
            throw new Stale();
        var actual = capture(current);
        if (!state.parts.equals(actual.parts)
                || !state.images.equals(actual.images)
                || !state.meshes.equals(actual.meshes)) throw new Stale();
    }

    private static void checkParent(State state, String id) {
        var seen = new HashSet<String>();
        String next = id;
        while (next != null) {
            if (!seen.add(next)) throw new Stale();
            String wanted = next;
            var parent = state.parts.stream()
                    .filter(p -> p.id.equals(wanted))
                    .findFirst()
                    .orElseThrow();
            if (parent.locked || !parent.visible) throw new UnsupportedOperationException();
            next = parent.parent;
        }
    }

    private Map<String, Object> result(Map<String, Object> args) {
        fields(args, "operationId");
        String id = text(args.get("operationId"), 128);
        synchronized (operations) {
            var old = operations.get(new Key(session(), id));
            return envelope(
                    old == null
                            ? output(
                                    id, "OUTCOME_UNKNOWN", "NOT_FOUND", null, null, null, null, false, List.of(), false)
                            : old.output);
        }
    }

    private void finish(Key key, Map<String, Object> output) {
        synchronized (operations) {
            var old = operations.get(key);
            operations.put(key, new Operation(old.fingerprint, output));
        }
    }

    private void prune() {
        long now = clock.getAsLong();
        plans.values().removeIf(p -> now - p.created >= TTL);
    }

    static List<Request> parseRequests(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > MAX_MESHES)
            throw new IllegalArgumentException();
        var result = new ArrayList<Request>();
        int points = 0, indices = 0;
        for (Object value : list) {
            var row = fields(value, "name", "parentPartId", "modelImageId", "sourceGeometry", "defaultPositions");
            String name = text(text(row.get("name"), 256).strip(), 256);
            var source = geometry(row.get("sourceGeometry"), true);
            var defaults = points(row.get("defaultPositions"));
            if (defaults.size() != source.positions().size()) throw new IllegalArgumentException();
            validTriangles(defaults, source.triangleIndices());
            points += source.positions().size();
            indices += source.triangleIndices().size();
            if (points > 16384 || indices > 65536) throw new Budget();
            result.add(new Request(
                    name,
                    text(row.get("parentPartId"), 4096),
                    row.get("modelImageId") == null ? null : text(row.get("modelImageId"), 4096),
                    source,
                    defaults));
        }
        return List.copyOf(result);
    }

    static ArtMeshGeometry geometry(Object raw, boolean faces) {
        var row = fields(raw, "positions", "uvs", "triangleIndices");
        var positions = points(row.get("positions"));
        var uvs = points(row.get("uvs"));
        for (var p : uvs) if (p.x() < 0 || p.x() > 1 || p.y() < 0 || p.y() > 1) throw new IllegalArgumentException();
        if (!(row.get("triangleIndices") instanceof List<?> list) || list.size() > MAX_INDICES)
            throw new IllegalArgumentException();
        var indices = new ArrayList<Integer>();
        for (Object n : list) indices.add(McpParameterStructure.integer(n, positions.size() - 1));
        var geometry = new ArtMeshGeometry(positions, uvs, indices);
        if (faces) validTriangles(positions, indices);
        return geometry;
    }

    private static void validTriangles(List<Point2> positions, List<Integer> indices) {
        if (indices.isEmpty()) throw new IllegalArgumentException();
        var seen = new HashSet<List<Integer>>();
        for (int i = 0; i < indices.size(); i += 3) {
            int a = indices.get(i), b = indices.get(i + 1), c = indices.get(i + 2);
            var face = new ArrayList<>(List.of(a, b, c));
            face.sort(Integer::compareTo);
            if (a == b || b == c || a == c || !seen.add(face)) throw new IllegalArgumentException();
            var p = positions.get(a);
            var q = positions.get(b);
            var r = positions.get(c);
            if (((double) q.x() - p.x()) * ((double) r.y() - p.y())
                            - ((double) q.y() - p.y()) * ((double) r.x() - p.x())
                    == 0) throw new IllegalArgumentException();
        }
    }

    private static List<Point2> points(Object raw) {
        if (!(raw instanceof List<?> list) || list.size() < 3 || list.size() > MAX_POINTS)
            throw new IllegalArgumentException();
        var result = new ArrayList<Point2>();
        for (Object p : list) {
            var point = fields(p, "x", "y");
            if (!(point.get("x") instanceof Number x) || !(point.get("y") instanceof Number y))
                throw new IllegalArgumentException();
            finite(x.doubleValue(), y.doubleValue());
            result.add(new Point2(x.floatValue(), y.floatValue()));
        }
        return List.copyOf(result);
    }

    private static void finite(double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || Math.abs(x) > 1e7 || Math.abs(y) > 1e7)
            throw new IllegalArgumentException();
    }

    static List<Map<String, Object>> pointsJson(List<Point2> points) {
        return points.stream()
                .map(p -> Map.<String, Object>of("x", p.x(), "y", p.y()))
                .toList();
    }

    static Map<String, Object> geometryJson(ArtMeshGeometry g) {
        return Map.of(
                "positions",
                pointsJson(g.positions()),
                "uvs",
                pointsJson(g.uvs()),
                "triangleIndices",
                g.triangleIndices());
    }

    private static String requestHash(List<Request> requests) {
        return digest(Map.of(
                "meshes",
                requests.stream()
                        .map(r -> {
                            var row = new LinkedHashMap<>(r.summary());
                            row.put("sourceGeometry", geometryJson(r.source));
                            row.put("defaultPositions", pointsJson(r.defaults));
                            return row;
                        })
                        .toList()));
    }

    static Map<String, Object> fields(Object raw, String... keys) {
        return McpParameterStructure.fields(raw, keys);
    }

    static String text(Object raw, int max) {
        return McpParameterStructure.text(raw, max);
    }

    static int bytes(Map<String, ?> raw) {
        return McpParameterStructure.bytes(raw);
    }

    private static void space(Map<String, Object> args) {
        if (!"CANVAS".equals(args.get("coordinateSpace"))) throw new IllegalArgumentException();
    }

    private static void scope(Map<String, Object> args, Map<String, Object> b) {
        if (!text(args.get("documentId"), 4096).equals(b.get("documentId"))
                || !text(args.get("modelId"), 4096).equals(b.get("modelId"))) throw new Stale();
    }

    private static boolean sameScope(Map<String, Object> a, Map<String, Object> b) {
        return List.of("documentId", "modelId", "historyGeneration", "historyDocumentBinding", "historyManagerBinding")
                .stream()
                .allMatch(k -> Objects.equals(a.get(k), b.get(k)));
    }

    private static String session() {
        return McpRequestRegistry.currentSessionId();
    }

    private static Map<String, Object> error(RuntimeException e) {
        return envelope(Map.of("ok", false, "error", code(e)));
    }

    private static String code(RuntimeException e) {
        if (e instanceof Stale || e instanceof McpStoredAuthoringDomain.Stale) return "STALE_STATE";
        if (e instanceof McpStoredAuthoringDomain.NoModel) return "NO_ACTIVE_MODEL";
        if (e instanceof Budget) return "BUDGET_EXCEEDED";
        if (e instanceof NoSuchElementException) return "NOT_FOUND";
        if (e instanceof UnsupportedOperationException) return "UNAVAILABLE";
        if (e instanceof SecurityException || e instanceof dev.turboism.sdk.permission.CubismPermissionException)
            return "PERMISSION_DENIED";
        if (e instanceof IllegalArgumentException) return "INVALID_ARGUMENT";
        return "READ_OR_PREFLIGHT_FAILED";
    }

    private static Map<String, Object> output(
            String id,
            String outcome,
            String code,
            Map<String, Object> receipt,
            String diagnostic,
            String before,
            String request,
            boolean verified,
            List<Map<String, Object>> created,
            boolean restored) {
        var row = new LinkedHashMap<>(out(id, outcome, code, receipt, diagnostic, before, request, verified));
        row.put("createdMeshes", List.copyOf(created));
        row.put("rollbackStateVerified", restored);
        row.put("renderVerified", false);
        return Collections.unmodifiableMap(row);
    }

    record Request(String name, String parentId, String imageId, ArtMeshGeometry source, List<Point2> defaults) {
        Map<String, Object> summary() {
            var m = new LinkedHashMap<String, Object>();
            m.put("name", name);
            m.put("parentPartId", parentId);
            m.put("modelImageId", imageId);
            m.put("vertexCount", source.positions().size());
            m.put("triangleCount", source.triangleIndices().size() / 3);
            m.put("sourceHash", digest(geometryJson(source)));
            m.put("defaultHash", digest(Map.of("positions", pointsJson(defaults))));
            return Collections.unmodifiableMap(m);
        }
    }

    private record Parent(String id, String name, String parent, int index, boolean visible, boolean locked) {
        Map<String, Object> json() {
            var m = new LinkedHashMap<String, Object>();
            m.put("id", id);
            m.put("name", name);
            m.put("parentId", parent);
            m.put("index", index);
            m.put("visible", visible);
            m.put("locked", locked);
            return m;
        }
    }

    private record Mesh(
            String id,
            String guid,
            String name,
            String parentPart,
            String parentDeformer,
            boolean locked,
            ArtMeshAuthoringSnapshot snapshot,
            Map<String, Object> input) {
        Map<String, Object> json() {
            var m = new LinkedHashMap<String, Object>();
            m.put("id", id);
            m.put("guid", guid);
            m.put("name", name);
            m.put("parentPartId", parentPart);
            m.put("parentDeformerId", parentDeformer);
            m.put("locked", locked);
            m.put("authoring", McpAuthoringProjection.mesh(snapshot));
            m.put("textureInput", input);
            return m;
        }
    }

    private record State(
            Map<String, Object> binding, List<Parent> parts, List<Map<String, Object>> images, List<Mesh> meshes) {
        String hash() {
            return digest(Map.of(
                    "binding",
                    binding,
                    "parts",
                    parts.stream().map(Parent::json).toList(),
                    "images",
                    images,
                    "meshes",
                    meshes.stream().map(Mesh::json).toList()));
        }
    }

    private record Plan(String session, State before, List<Request> requests, long created) {}

    private record Key(String session, String id) {}

    private record Operation(String fingerprint, Map<String, Object> output) {}

    private static final class Stale extends IllegalStateException {}

    private static final class Budget extends IllegalArgumentException {}
}
