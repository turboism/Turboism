package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.ArtMeshKeyform;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.ParameterBindingOperations;
import dev.turboism.sdk.cubism.model.ParameterBindingPoint;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.cubism.model.ParameterBindingTargetType;
import dev.turboism.sdk.cubism.model.ParameterDefinition;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionService;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SDK-interface stub world driving the real {@link ShapeImportProbe} through the
 * B0 import flow: parameter creation, two-mesh geometry commit, rotation keyform
 * binding, native readbacks and undo/redo restore. No runtime or Cubism classes.
 */
final class ShapeImportAssertionsTest {
    private static int checks;

    static void run() throws Exception {
        var world = new World();
        var data = new LinkedHashMap<String, Object>();
        ShapeImportProbe.run(world::model, world::history, world::restore, world.transactions(), data);
        check(data.get("status").equals("PASS") && data.get("failure") == null
            && data.get("finallyRestored").equals(true), "probe reports PASS with restore");
        var rows = (List<Map<String, Object>>) data.get("checks");
        check(rows.size() == 10 && rows.stream().allMatch(r -> r.get("status").equals("PASS")
            && r.get("actual").equals(true) && r.get("evidenceKind").equals("EXACT_HOST")), "10 checks pass");
        check(((Map<String, Object>) data.get("geometryTransaction")).get("outcome").equals("COMMITTED")
            && ((Map<String, Object>) data.get("keyformTransaction")).get("outcome").equals("COMMITTED"),
            "both transactions committed");
        var ids = new java.util.HashSet<String>();
        for (var row : rows) ids.add((String) row.get("id"));
        check(ids.equals(Set.of("rotationParameterCreated", "geometryCommitted", "geometryReadback",
            "keyformsCommitted", "keyformReadback", "defaultFormIsZeroPose", "keyformUndo",
            "keyformRedo", "undoStable", "historyPositionRestored")), "frozen check id inventory");
        for (String key : List.of("initialHistory", "restoredHistory")) {
            var row = (Map<String, Object>) data.get(key);
            check(row != null && row.get("documentBindingId") instanceof String d && !d.isEmpty()
                && row.get("managerBindingId") instanceof String m && !m.isEmpty(),
                key + " carries the document/manager binding identities the oracle requires");
        }
        check(data.get("bindings") instanceof List<?> b && b.size() == 2,
            "two object-level parameter bindings observed");
        check(data.get("protectedDomain") instanceof Map<?, ?> p
            && p.containsKey("tri") && p.containsKey("quad"), "protected domain evidence observed");
        var json = NativeReport.json(data);
        check(json.contains("\"documentBindingId\"") && json.contains("\"managerBindingId\"")
            && !json.contains("textureIndex"), "producer serializes the oracle contract");
        var unbound = new World(true);
        var unboundData = new LinkedHashMap<String, Object>();
        try {
            ShapeImportProbe.run(unbound::model, unbound::history, unbound::restore,
                unbound.transactions(), unboundData);
            check(false, "probe must not pass without materialized bindings");
        } catch (Exception expected) {
            check(String.valueOf(expected).contains("axis identity missing")
                && "FAIL".equals(unboundData.get("status")),
                "unbound world fails closed at the axis check: " + expected);
        }
        System.out.println("ShapeImportAssertionsTest checks=" + checks + " PASS");
    }

    private static void check(boolean value, String message) { NativeAssertions.require(value, message); checks++; }

    /**
     * SDK stub world mirroring the verified host semantics: an ArtMesh's
     * authoring snapshot enumerates only its own keyform-grid bindings — no
     * model parameter appears as an axis until it is bound to that object via
     * {@code parameterBindings(id).bind(artMeshTarget, declaredKeys)}. Binding
     * creates one stored form per declared tuple (the all-default tuple reuses
     * the mesh's pre-existing default form). {@code ordinaryKeyform} performs
     * the host's complete-tuple selection: every bound dimension must be
     * addressed, every coordinate must be a declared key, and the default cell
     * must exist. Writes go through the transaction journal so Undo/Redo
     * restores the exact pre-write form positions.
     */
    private static final class World {
        private record Binding(String guid, List<Float> keys) { }

        private int position, revision = 5, generation = 1;
        private final Map<String, ParameterDefinition> paramDefs = new LinkedHashMap<>();
        private final Map<String, ArtMeshGeometry> geometryByMesh = new LinkedHashMap<>();
        private final List<String> meshNames = new ArrayList<>();
        /** id value -> display name, mirroring the host's separate object id. */
        private final Map<String, String> meshIds = new LinkedHashMap<>();
        /** mesh -> parameterId -> declared key binding (per-mesh guid, like the host grid). */
        private final Map<String, Map<String, Binding>> bindings = new LinkedHashMap<>();
        /** cellKey -> stored form positions; "mesh|default" is the unbound default form. */
        private final Map<String, float[]> committedForms = new LinkedHashMap<>();
        private final List<String> pendingKeys = new ArrayList<>();
        private final Map<String, float[]> pendingValues = new LinkedHashMap<>();
        /** journal position -> entries {cellKey, before, after} for exact undo/redo. */
        private final Map<Integer, List<Object[]>> journal = new LinkedHashMap<>();
        /** When true, bind() never materializes bindings — an unbound host world. */
        private final boolean noBind;

        World() { this(false); }
        World(boolean noBind) { this.noBind = noBind; }

        CubismModel model() {
            return proxy(CubismModel.class, (p, method, args) -> switch (method.getName()) {
                case "parameters" -> parametersProxy();
                case "drawables" -> drawablesProxy();
                case "parameterBindings" -> bindingOps((ParameterId) args[0]);
                default -> throw new AssertionError("unexpected CubismModel call " + method.getName());
            });
        }

        private Object parametersProxy() {
            return proxy(dev.turboism.sdk.cubism.model.Parameters.class, (p, method, args) -> switch (method.getName()) {
                case "createMany" -> {
                    for (Object def : (List<?>) args[0]) {
                        var d = (ParameterDefinition) def; paramDefs.put(d.id().value(), d);
                    }
                    yield null;
                }
                case "find" -> paramDefs.containsKey(((ParameterId) args[0]).value())
                    ? parameterProxy((ParameterId) args[0]) : null;
                default -> throw new AssertionError("unexpected Parameters call " + method.getName());
            });
        }

        private Object parameterProxy(ParameterId id) {
            return proxy(dev.turboism.sdk.cubism.model.Parameter.class, (p, method, args) -> switch (method.getName()) {
                case "id" -> id;
                case "keyValues" -> new dev.turboism.sdk.cubism.model.FloatSequence() {
                    public int size() { return 0; } public float get(int i) { throw new IndexOutOfBoundsException(); }
                };
                default -> throw new AssertionError("unexpected Parameter call " + method.getName());
            });
        }

        private Object drawablesProxy() {
            return proxy(dev.turboism.sdk.cubism.model.Drawables.class, (p, method, args) -> switch (method.getName()) {
                case "create" -> {
                    var name = (String) args[0]; meshNames.add(name);
                    // Real 5.3.02 hosts mint ids like "ArtMeshMI3D_B0_TRI": model
                    // the id separately from the display name so no code path can
                    // silently assume name == id.
                    var meshId = new ArtMeshId("ArtMesh" + name);
                    meshIds.put(meshId.value(), name);
                    geometryByMesh.put(name, args.length > 3 && args[3] instanceof ArtMeshGeometry geometry
                        ? geometry : defaultGeometry(name));
                    committedForms.put(name + "|default", toFloats(geometryByMesh.get(name).positions()));
                    yield meshProxy(name, meshId);
                }
                case "all" -> {
                    yield meshNames.stream()
                        .map(n -> meshProxy(n, new ArtMeshId("ArtMesh" + n))).toList();
                }
                default -> throw new AssertionError("unexpected Drawables call " + method.getName());
            });
        }

        private ArtMeshGeometry defaultGeometry(String name) {
            var positions = List.of(new Point2(512, 312), new Point2(312, 312), new Point2(512, 712));
            var uvs = List.of(new Point2(0.5f, 0f), new Point2(0f, 1f), new Point2(1f, 1f));
            return new ArtMeshGeometry(positions, uvs, List.of(0, 1, 2));
        }

        private Object meshProxy(String name, ArtMeshId id) {
            return proxy(Drawable.class, (p, method, args) -> switch (method.getName()) {
                case "id" -> id;
                case "replaceGeometry" -> { geometryByMesh.put(name, (ArtMeshGeometry) args[0]); yield null; }
                case "geometry" -> geometryByMesh.get(name);
                case "ordinaryKeyform" -> keyformProxy(name, (List<ArtMeshAuthoringSnapshot.Coordinate>) args[0]);
                case "authoringSnapshot" -> snapshotProxy(name, id);
                default -> throw new AssertionError("unexpected Drawable call " + method.getName());
            });
        }

        private Object bindingOps(ParameterId pid) {
            return proxy(ParameterBindingOperations.class, (p, method, args) -> switch (method.getName()) {
                case "bind" -> { bind(pid, (ParameterBindingTarget) args[0],
                    (List<ParameterBindingPoint>) args[1]); yield null; }
                case "unbind" -> {
                    var mesh = ((ParameterBindingTarget) args[0]).id();
                    var meshBindings = bindings.get(mesh);
                    if (meshBindings != null) meshBindings.remove(pid.value());
                    yield null;
                }
                default -> throw new AssertionError("unexpected binding call " + method.getName());
            });
        }

        private void bind(ParameterId pid, ParameterBindingTarget target, List<ParameterBindingPoint> points) {
            if (noBind) return;
            var def = paramDefs.get(pid.value());
            if (def == null) throw new IllegalStateException("bind to undeclared parameter");
            if (target.type() != ParameterBindingTargetType.ART_MESH || !meshIds.containsKey(target.id())) {
                throw new IllegalArgumentException("bind target is not a known art mesh");
            }
            var keys = points.stream().map(ParameterBindingPoint::value).toList();
            bindings.computeIfAbsent(target.id(), m -> new LinkedHashMap<>())
                .put(pid.value(), new Binding("guid-" + pid.value() + "@" + target.id(), keys));
        }

        private ArtMeshAuthoringSnapshot snapshotProxy(String mesh, ArtMeshId id) {
            var source = geometryByMesh.get(mesh);
            var meshBindings = bindings.getOrDefault(id.value(), Map.of());
            var axes = new ArrayList<ArtMeshAuthoringSnapshot.Axis>();
            var defaults = new LinkedHashMap<String, Float>();
            for (var e : meshBindings.entrySet()) {
                var def = paramDefs.get(e.getKey());
                axes.add(new ArtMeshAuthoringSnapshot.Axis(e.getValue().guid(), new ParameterId(e.getKey()),
                    def.minimumValue(), def.defaultValue(), def.maximumValue(), e.getValue().keys()));
                defaults.put(e.getValue().guid(), def.defaultValue());
            }
            var cells = new ArrayList<ArtMeshAuthoringSnapshot.Cell>();
            var forms = new ArrayList<ArtMeshAuthoringSnapshot.Form>();
            var seen = new LinkedHashMap<String, String>();
            forms.add(new ArtMeshAuthoringSnapshot.Form("default-" + mesh,
                fromFloats(formPositions(mesh + "|default", source)), 0));
            seen.put("default-" + mesh, "default-" + mesh);
            for (var combo : combos(meshBindings)) {
                if (combo.isEmpty()) continue;
                var cellKey = cellKey(mesh, combo, defaults);
                var formGuid = cellKey.endsWith("|default") ? "default-" + mesh
                    : "form-" + Integer.toHexString(cellKey.hashCode());
                cells.add(new ArtMeshAuthoringSnapshot.Cell(formGuid, combo.entrySet().stream()
                    .map(e -> new ArtMeshAuthoringSnapshot.Coordinate(e.getKey(), e.getValue())).toList()));
                if (seen.putIfAbsent(formGuid, cellKey) == null) {
                    forms.add(new ArtMeshAuthoringSnapshot.Form(formGuid,
                        fromFloats(formPositions(cellKey, source)), 0));
                }
            }
            return new ArtMeshAuthoringSnapshot(id, "source-" + mesh,
                source, axes, cells, forms, List.of());
        }

        /** Complete tuples: the cartesian product of every bound axis' declared keys. */
        private List<Map<String, Float>> combos(Map<String, Binding> meshBindings) {
            var result = new ArrayList<Map<String, Float>>();
            result.add(new LinkedHashMap<>());
            for (var e : meshBindings.entrySet()) {
                var next = new ArrayList<Map<String, Float>>();
                for (var partial : result) {
                    for (float key : e.getValue().keys()) {
                        var tuple = new LinkedHashMap<>(partial);
                        tuple.put(e.getValue().guid(), key); next.add(tuple);
                    }
                }
                result = next;
            }
            return result;
        }

        private String cellKey(String mesh, Map<String, Float> tuple, Map<String, Float> defaults) {
            if (tuple.equals(defaults) && !tuple.isEmpty()) return mesh + "|default";
            var key = new StringBuilder(mesh);
            tuple.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> key.append('|').append(e.getKey()).append('@').append(e.getValue()));
            return key.toString();
        }

        private float[] formPositions(String cellKey, ArtMeshGeometry source) {
            return committedForms.computeIfAbsent(cellKey, k -> toFloats(source.positions()));
        }

        /** Host-faithful ordinary keyform selection over the mesh's stored snapshot. */
        private Object keyformProxy(String mesh, List<ArtMeshAuthoringSnapshot.Coordinate> coords) {
            var snapshot = snapshotProxy(mesh, new ArtMeshId("ArtMesh" + mesh));
            var requested = new LinkedHashMap<String, Float>();
            for (var c : coords) requested.put(c.parameterGuid(), c.value());
            if (requested.size() != snapshot.axes().size()) {
                throw new IllegalArgumentException("ordinary keyform requires every bound dimension");
            }
            var defaults = new LinkedHashMap<String, Float>();
            for (var axis : snapshot.axes()) {
                var key = requested.get(axis.parameterGuid());
                if (key == null || !axis.keys().contains(key)) {
                    throw new IllegalArgumentException("ordinary coordinate is not a declared key");
                }
                if (!axis.keys().contains(axis.defaultValue())) {
                    throw new IllegalArgumentException("declared default is not an exact key");
                }
                defaults.put(axis.parameterGuid(), axis.defaultValue());
            }
            var tuples = new LinkedHashMap<Map<String, Float>, String>();
            for (var cell : snapshot.cells()) {
                var tuple = new LinkedHashMap<String, Float>();
                for (var c : cell.coordinates()) tuple.put(c.parameterGuid(), c.value());
                tuples.put(tuple, cell.formGuid());
            }
            if (!tuples.containsKey(defaults)) {
                throw new IllegalArgumentException("exact default cell is missing");
            }
            var formGuid = tuples.get(requested);
            if (formGuid == null) throw new java.util.NoSuchElementException("ordinary keyform cell is missing");
            var cellKey = cellKey(mesh, requested, defaults);
            var source = geometryByMesh.get(mesh);
            return proxy(ArtMeshKeyform.class, (p, method, args) -> switch (method.getName()) {
                case "setPositions" -> {
                    if (formGuid.equals("default-" + mesh)) {
                        throw new UnsupportedOperationException("default keyform position editing is not available");
                    }
                    pendingKeys.add(cellKey);
                    pendingValues.put(cellKey, toFloats((List<Point2>) args[0])); yield null;
                }
                case "snapshot" -> new ArtMeshAuthoringSnapshot.Form(formGuid,
                    fromFloats(formPositions(cellKey, source)), 0);
                case "coordinates" -> coords;
                case "defaultKeyform" -> formGuid.equals("default-" + mesh);
                default -> throw new AssertionError("unexpected ArtMeshKeyform call " + method.getName());
            });
        }

        private static float[] toFloats(List<Point2> points) {
            var out = new float[points.size() * 2];
            for (int i = 0; i < points.size(); i++) { out[2 * i] = points.get(i).x(); out[2 * i + 1] = points.get(i).y(); }
            return out;
        }

        private static List<Point2> fromFloats(float[] values) {
            if (values == null) return List.of();
            var out = new ArrayList<Point2>(values.length / 2);
            for (int i = 0; i < values.length / 2; i++) out.add(new Point2(values[2 * i], values[2 * i + 1]));
            return out;
        }

        private AuthoringTransactionService txService() {
            return proxy(dev.turboism.sdk.cubism.transaction.AuthoringTransactionService.class,
                (p, method, args) -> switch (method.getName()) {
                    case "execute" -> {
                        pendingKeys.clear(); pendingValues.clear();
                        ((dev.turboism.sdk.cubism.transaction.AuthoringTransactionWork<Object>) args[1]).run();
                        position++;
                        var applied = new ArrayList<Object[]>();
                        for (String key : pendingKeys) {
                            applied.add(new Object[] {key, committedForms.get(key), pendingValues.get(key)});
                            committedForms.put(key, pendingValues.get(key));
                        }
                        if (!applied.isEmpty()) journal.put(position, applied);
                        yield AuthoringTransactionResult.committed(null,
                            new dev.turboism.sdk.cubism.transaction.AuthoringTransactionReceipt(
                                "b0-tx", String.valueOf(args[0]), history(), history(),
                                java.util.Optional.of("b0-entry-" + position)));
                    }
                    default -> throw new AssertionError("unexpected transaction call " + method.getName());
                });
        }

        AuthoringTransactionService transactions() { return txService(); }

        HistorySnapshot history() {
            var entries = new ArrayList<dev.turboism.sdk.cubism.history.HistoryEntry>();
            for (int i = 0; i < position; i++) entries.add(new dev.turboism.sdk.cubism.history.HistoryEntry(i, "b0", true));
            return new HistorySnapshot(HistorySnapshot.Availability.AVAILABLE, generation, revision++,
                position, entries, position > 0, true, "document-binding", "manager-binding");
        }

        void restore(int target) {
            while (position > target) {
                var applied = journal.get(position);
                if (applied != null) for (Object[] kv : applied) {
                    if (kv[1] == null) committedForms.remove((String) kv[0]);
                    else committedForms.put((String) kv[0], (float[]) kv[1]);
                }
                position--;
            }
            while (position < target) {
                position++;
                var applied = journal.get(position);
                if (applied != null) for (Object[] kv : applied) {
                    committedForms.put((String) kv[0], (float[]) kv[2]);
                }
            }
        }
    }

    private static <T> T proxy(Class<T> iface, java.lang.reflect.InvocationHandler handler) {
        return iface.cast(Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] {iface}, handler));
    }
}
