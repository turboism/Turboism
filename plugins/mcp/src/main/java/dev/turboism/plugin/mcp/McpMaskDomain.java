package dev.turboism.plugin.mcp;

import static dev.turboism.plugin.mcp.McpWriteResults.*;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.clipmask.ClipMaskReplacement;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.transaction.*;
import dev.turboism.sdk.json.Json;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Session-issued, bounded mask dependency snapshots and conditional native batch replacement. */
final class McpMaskDomain {
    static final String READ = "turboism.authoring.masks.read",
            WRITE = "turboism.authoring.masks.write",
            RESULT = "turboism.authoring.masks.result";
    static final int MAX_OBJECTS = 64, MAX_TARGETS = 8, MAX_OPERATIONS = 256;
    static final long TTL = java.time.Duration.ofMinutes(5).toNanos();
    private final CubismFacade cubism;
    private final McpExecutionBridge execution;
    private final Supplier<McpStoredAuthoringDomain.Context> context;
    private final LongSupplier clock;
    private final Map<String, Snapshot> snapshots = new LinkedHashMap<>();
    private final Map<Key, Operation> operations = new HashMap<>();

    McpMaskDomain(
            CubismFacade cubism, McpExecutionBridge execution, Supplier<McpStoredAuthoringDomain.Context> context) {
        this(cubism, execution, context, System::nanoTime);
    }

    McpMaskDomain(
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
                        McpMaskSchemas.definition(READ),
                        McpOperationEffect.READ,
                        McpExecutionAffinity.UI_THREAD,
                        false,
                        execution,
                        this::read),
                McpRegisteredTool.typed(
                        McpMaskSchemas.definition(WRITE),
                        McpOperationEffect.TRANSACTION_CONTROL,
                        McpExecutionAffinity.UI_THREAD,
                        false,
                        McpVersionSupport.exact(
                                "cubism.editor-model.objects.write",
                                versions,
                                List.of(
                                        McpVersionSupport.OperationSupport.available(
                                                "clip_mask_batch",
                                                McpOperationEffect.TRANSACTION_CONTROL,
                                                false,
                                                McpVersionSupport.UndoVerification.RUNTIME_VERIFIED,
                                                versions,
                                                "Own SDK transaction and conditional clip-mask batch; exact-host clip-mask/Undo admission remains authoritative. Nonempty replacements only; no visual acceptance claim."))),
                        execution,
                        this::write),
                McpRegisteredTool.typed(
                        McpMaskSchemas.definition(RESULT),
                        McpOperationEffect.READ,
                        McpExecutionAffinity.DIRECT,
                        false,
                        execution,
                        this::result)));
    }

    private Map<String, Object> read(Map<String, Object> args) {
        try {
            fields(args, "documentId", "modelId", "objectIds");
            var initial = context.get();
            requireScope(args, initial);
            var objects = capture(initial, ids(args.get("objectIds"), MAX_OBJECTS, false), true);
            var expected = new State(initial.binding(), objects);
            verify(expected, true);
            if (bytes(expected.json()) > 64 * 1024) throw new Budget();
            String id = UUID.randomUUID().toString();
            synchronized (snapshots) {
                prune();
                while (snapshots.size() >= 16)
                    snapshots.remove(snapshots.keySet().iterator().next());
                snapshots.put(id, new Snapshot(McpRequestRegistry.currentSessionId(), expected, clock.getAsLong()));
            }
            return envelope(Map.of("ok", true, "snapshotId", id, "snapshot", expected.json()));
        } catch (RuntimeException e) {
            return envelope(Map.of("ok", false, "error", code(e)));
        }
    }

    private Map<String, Object> write(Map<String, Object> args) {
        String id = "";
        Key key = null;
        boolean reserved = false;
        try {
            fields(args, "documentId", "modelId", "snapshotId", "operationId", "replacements");
            id = text(args.get("operationId"), 128);
            key = new Key(McpRequestRegistry.currentSessionId(), id);
            String fingerprint = digest(args);
            synchronized (operations) {
                var previous = operations.get(key);
                if (previous != null)
                    return envelope(
                            previous.fingerprint.equals(fingerprint)
                                    ? previous.output
                                    : out(id, "NOT_APPLIED", "OPERATION_ID_CONFLICT", null, null, null, null, false));
            }
            var initial = context.get();
            requireScope(args, initial);
            Snapshot issued;
            synchronized (snapshots) {
                prune();
                issued = snapshots.get(text(args.get("snapshotId"), 128));
            }
            if (issued == null || !issued.session.equals(key.session)) throw new NoSuchElementException();
            var before = issued.state;
            if (!before.binding.equals(initial.binding())) throw new Stale();
            var edits = list(args.get("replacements"), MAX_TARGETS, false);
            var byId = new LinkedHashMap<String, Mask>();
            before.objects.forEach(o -> byId.put(o.objectId, o));
            var changed = new HashMap<String, Mask>();
            var batch = new ArrayList<ClipMaskReplacement>();
            for (Object raw : edits) {
                var edit = fields(raw, "objectId", "maskIds", "inverted");
                String objectId = text(edit.get("objectId"), 4096);
                var old = byId.get(objectId);
                if (old == null) throw new IllegalArgumentException();
                var maskIds = ids(edit.get("maskIds"), MAX_OBJECTS, false);
                if (!(edit.get("inverted") instanceof Boolean inverted)
                        || maskIds.contains(objectId)
                        || !byId.keySet().containsAll(maskIds)) throw new IllegalArgumentException();
                var replacement = new Mask(
                        old.objectId, old.sourceGuid, old.parentPartId, old.parentDeformerId, maskIds, inverted);
                if (changed.putIfAbsent(objectId, replacement) != null) throw new IllegalArgumentException();
                batch.add(new ClipMaskReplacement(
                        new ArtMeshId(objectId),
                        old.maskIds.stream().map(ArtMeshId::new).toList(),
                        old.inverted,
                        maskIds.stream().map(ArtMeshId::new).toList(),
                        inverted));
            }
            var after = new State(
                    before.binding,
                    before.objects.stream()
                            .map(o -> changed.getOrDefault(o.objectId, o))
                            .toList());
            // Keep an existing dependency graph readable, but never introduce a new cycle.
            if (!before.objects.equals(after.objects)) rejectNewCycles(before.objects, after.objects);
            if (bytes(args) > 128 * 1024 || bytes(after.json()) > 64 * 1024) throw new Budget();
            verify(before, true);
            var transactions = cubism.authoringTransactions();
            synchronized (operations) {
                if (operations.size() >= MAX_OPERATIONS)
                    return envelope(out(id, "NOT_APPLIED", "RESULT_STORE_FULL", null, null, null, null, false));
                operations.put(
                        key, new Operation(fingerprint, out(id, "RUNNING", "RUNNING", null, null, null, null, false)));
                reserved = true;
            }
            String beforeHash = digest(before.json()), afterHash = digest(after.json());
            var result = transactions.execute(AuthoringTransactionOptions.of("MCP ArtMesh masks"), () -> {
                verify(before, true);
                if (!before.objects.equals(after.objects)) initial.model().replaceArtMeshClipMasks(batch);
                verify(after, false);
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
            if (result.successful())
                try {
                    verify(after, false);
                    verified = true;
                } catch (RuntimeException failure) {
                    outcome = "APPLIED_WITH_READBACK_WARNING";
                }
            var terminal = out(
                    id,
                    outcome,
                    result.outcome().name(),
                    result.receipt().map(McpWriteResults::receipt).orElse(null),
                    result.diagnosticId().orElse(null),
                    beforeHash,
                    afterHash,
                    verified);
            finish(key, terminal);
            return envelope(terminal);
        } catch (RuntimeException failure) {
            var output = out(
                    id,
                    reserved ? "OUTCOME_UNKNOWN" : "NOT_APPLIED",
                    reserved ? "HOST_OUTCOME_UNKNOWN" : code(failure),
                    null,
                    null,
                    null,
                    null,
                    false);
            if (reserved) finish(key, output);
            return envelope(output);
        }
    }

    private Map<String, Object> result(Map<String, Object> args) {
        fields(args, "operationId");
        String id = text(args.get("operationId"), 128);
        synchronized (operations) {
            var retained = operations.get(new Key(McpRequestRegistry.currentSessionId(), id));
            return envelope(
                    retained == null
                            ? out(id, "OUTCOME_UNKNOWN", "NOT_FOUND", null, null, null, null, false)
                            : retained.output);
        }
    }

    private void finish(Key key, Map<String, Object> output) {
        synchronized (operations) {
            var old = operations.get(key);
            operations.put(key, new Operation(old.fingerprint, output));
        }
    }

    private List<Mask> capture(McpStoredAuthoringDomain.Context current, List<String> ids, boolean closure) {
        var scope = new LinkedHashSet<>(ids);
        var pending = new ArrayList<>(ids);
        var objects = new ArrayList<Mask>();
        for (int i = 0; i < pending.size(); i++) {
            var id = pending.get(i);
            var mesh = current.model().drawables().find(new ArtMeshId(id));
            var masks = mesh.maskIds().stream().map(v -> text(v.value(), 4096)).toList();
            if (masks.size() > MAX_OBJECTS || new HashSet<>(masks).size() != masks.size()) throw new Budget();
            objects.add(new Mask(
                    id,
                    text(mesh.guid(), 4096),
                    mesh.parentPartId().map(v -> text(v.value(), 4096)).orElse(null),
                    mesh.parentDeformerId().map(v -> text(v.value(), 4096)).orElse(null),
                    masks,
                    mesh.invertedMask()));
            if (closure)
                for (String mask : masks)
                    if (scope.add(mask)) {
                        if (scope.size() > MAX_OBJECTS) throw new Budget();
                        pending.add(mask);
                    }
        }
        if (!current.binding().equals(context.get().binding())) throw new Stale();
        return List.copyOf(objects);
    }

    private void verify(State expected, boolean history) {
        var current = context.get();
        if (history ? !expected.binding.equals(current.binding()) : !scopeEquals(expected.binding, current.binding()))
            throw new Stale();
        if (!expected.objects.equals(
                capture(current, expected.objects.stream().map(o -> o.objectId).toList(), false))) throw new Stale();
        var end = context.get();
        if (!current.binding().equals(end.binding())) throw new Stale();
    }

    private static boolean scopeEquals(Map<String, Object> a, Map<String, Object> b) {
        return List.of("documentId", "modelId", "historyGeneration", "historyDocumentBinding", "historyManagerBinding")
                .stream()
                .allMatch(k -> Objects.equals(a.get(k), b.get(k)));
    }

    private static void requireScope(Map<String, Object> args, McpStoredAuthoringDomain.Context context) {
        if (!text(args.get("documentId"), 4096).equals(context.binding().get("documentId"))
                || !text(args.get("modelId"), 4096).equals(context.binding().get("modelId"))) throw new Stale();
    }

    private void prune() {
        long now = clock.getAsLong();
        snapshots.values().removeIf(v -> now - v.created >= TTL);
    }

    private static void rejectNewCycles(List<Mask> before, List<Mask> after) {
        var original = new HashMap<String, List<String>>();
        var graph = new HashMap<String, List<String>>();
        before.forEach(o -> original.put(o.objectId, o.maskIds));
        after.forEach(o -> graph.put(o.objectId, o.maskIds));
        for (var object : after)
            for (String mask : object.maskIds)
                if (!original.get(object.objectId).contains(mask)
                        && reaches(mask, object.objectId, graph, new HashSet<>()))
                    throw new IllegalArgumentException("New cyclic masks unsupported");
    }

    private static boolean reaches(String id, String target, Map<String, List<String>> graph, Set<String> seen) {
        if (id.equals(target)) return true;
        if (!seen.add(id)) return false;
        for (String next : graph.getOrDefault(id, List.of())) if (reaches(next, target, graph, seen)) return true;
        return false;
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
        return "READ_OR_PREFLIGHT_FAILED";
    }

    private static Map<String, Object> fields(Object raw, String... keys) {
        if (!(raw instanceof Map<?, ?> m) || !m.keySet().equals(Set.of(keys))) throw new IllegalArgumentException();
        var result = new LinkedHashMap<String, Object>();
        m.forEach((k, v) -> result.put((String) k, v));
        return result;
    }

    private static String text(Object raw, int max) {
        if (!(raw instanceof String s)
                || s.isBlank()
                || s.length() > max
                || s.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
        return s;
    }

    private static List<?> list(Object raw, int max, boolean empty) {
        if (!(raw instanceof List<?> l) || l.size() > max || (!empty && l.isEmpty()))
            throw new IllegalArgumentException();
        return l;
    }

    private static List<String> ids(Object raw, int max, boolean empty) {
        var ids = list(raw, max, empty).stream().map(v -> text(v, 4096)).toList();
        if (new HashSet<>(ids).size() != ids.size()) throw new IllegalArgumentException();
        return ids;
    }

    private static int bytes(Map<String, Object> v) {
        return Json.stringify(v).getBytes(StandardCharsets.UTF_8).length;
    }

    private record Mask(
            String objectId,
            String sourceGuid,
            String parentPartId,
            String parentDeformerId,
            List<String> maskIds,
            boolean inverted) {
        Map<String, Object> json() {
            var m = new LinkedHashMap<String, Object>();
            m.put("objectId", objectId);
            m.put("sourceGuid", sourceGuid);
            m.put("parentPartId", parentPartId);
            m.put("parentDeformerId", parentDeformerId);
            m.put("maskIds", maskIds);
            m.put("inverted", inverted);
            return Collections.unmodifiableMap(m);
        }
    }

    private record State(Map<String, Object> binding, List<Mask> objects) {
        State {
            binding = Map.copyOf(binding);
            objects = List.copyOf(objects);
        }

        Map<String, Object> json() {
            return Map.of(
                    "state",
                    binding,
                    "objects",
                    objects.stream().map(Mask::json).toList());
        }
    }

    private record Snapshot(String session, State state, long created) {}

    private record Key(String session, String id) {}

    private record Operation(String fingerprint, Map<String, Object> output) {}

    private static final class Stale extends IllegalStateException {}

    private static final class Budget extends IllegalArgumentException {}
}
