package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.cubism.command.EditorCommandService;
import dev.turboism.sdk.cubism.history.HistoryEntry;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.transaction.*;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Actual Java producer -> independent Python oracle, using an explicitly synthetic SDK backend. */
public final class JointPoseProducerIntegrationTest {
    public static void main(String[] args) throws Exception { run(); }

    static void run() throws Exception {
        var world = new World(false);
        var data = new LinkedHashMap<String, Object>(); data.put("schemaVersion", "mi3d-native-joint-v1");
        JointPoseProbe.run(world::model, world::history, world::restore, world.commands(), world.transactions(), data);
        NativeAssertions.require(data.get("status").equals("PASS") && world.meshes.isEmpty() && world.defs.isEmpty(),
            "joint producer did not restore synthetic world");
        Path root = Path.of("build/model-import-3d-host"); Files.createDirectories(root);
        Path evidence = Files.createTempDirectory(root, "joint-producer-e2e-");
        Path report = evidence.resolve("synthetic-report.json");
        Files.writeString(report, NativeReport.json(Map.of("evidenceKind", "OFFLINE_FIXTURE", "jointPose", data)));
        var process = new ProcessBuilder("python3", "-B", "-c",
            "import json,runpy,sys; runpy.run_path(sys.argv[1])['verify_joint_pose'](json.load(open(sys.argv[2])))",
            "validation/model-import-3d-host-probe/verify-joint-pose.py", report.toString())
            .redirectErrorStream(true).redirectOutput(evidence.resolve("oracle.log").toFile()).start();
        int exit = process.waitFor();
        NativeAssertions.require(exit == 0, "joint producer/oracle mismatch: " + Files.readString(evidence.resolve("oracle.log")));
        var wrong = new World(true);
        var failed = new LinkedHashMap<String, Object>();
        try {
            JointPoseProbe.run(wrong::model, wrong::history, wrong::restore, wrong.commands(), wrong.transactions(), failed);
            throw new AssertionError("wrong calculated geometry passed joint gate");
        } catch (Exception expected) {
            NativeAssertions.require("FAIL".equals(failed.get("status")) && Boolean.TRUE.equals(failed.get("finallyRestored"))
                && wrong.meshes.isEmpty() && wrong.defs.isEmpty(), "joint failure did not restore synthetic state");
        }
        System.out.println("JOINT PRODUCER_E2E PASS; synthetic bilinear evaluator; hostExecuted=false artifacts=" + evidence);
    }

    static final class World {
        record State(Map<ParameterId, ParameterDefinition> defs, Map<ArtMeshId, ArtMeshAuthoringSnapshot> meshes,
                Map<ParameterId, Float> live) { }
        Map<ParameterId, ParameterDefinition> defs = new LinkedHashMap<>();
        Map<ArtMeshId, ArtMeshAuthoringSnapshot> meshes = new LinkedHashMap<>();
        final Map<ParameterId, Float> live = new LinkedHashMap<>();
        final List<State> states = new ArrayList<>();
        int position, revision, consumed, liveVersion;
        boolean inTransaction;
        final boolean corruptEvaluation;
        World(boolean corruptEvaluation) { this.corruptEvaluation = corruptEvaluation; states.add(state()); }
        State state() { return new State(new LinkedHashMap<>(defs), new LinkedHashMap<>(meshes), new LinkedHashMap<>(live)); }
        void commit() {
            if (inTransaction) return;
            while (states.size() > position + 1) states.remove(states.size() - 1);
            states.add(state()); position++; revision++;
        }
        void restore(int at) {
            var state = states.get(at); defs = new LinkedHashMap<>(state.defs()); meshes = new LinkedHashMap<>(state.meshes());
            live.clear(); live.putAll(state.live());
            position = at; revision++;
        }
        HistorySnapshot history() {
            var entries = new ArrayList<HistoryEntry>();
            for (int i = 0; i < states.size()-1; i++) entries.add(new HistoryEntry(i, "synthetic joint", true));
            return new HistorySnapshot(HistorySnapshot.Availability.AVAILABLE, 1, revision, position, entries,
                position > 0, position < states.size()-1, "doc", "manager");
        }
        CubismModel model() {
            return proxy(CubismModel.class, (p, m, a) -> switch (m.getName()) {
                case "parameters" -> proxy(Parameters.class, (pp, pm, pa) -> switch (pm.getName()) {
                    case "all" -> defs.keySet().stream().map(this::parameter).toList();
                    case "find" -> parameter((ParameterId) pa[0]);
                    case "createMany" -> {
                        for (Object d : (List<?>) pa[0]) { var def = (ParameterDefinition) d; defs.put(def.id(), def); live.put(def.id(), 0f); }
                        commit(); yield null;
                    }
                    default -> throw new AssertionError(pm);
                });
                case "drawables" -> proxy(Drawables.class, (dp, dm, da) -> switch (dm.getName()) {
                    case "all" -> meshes.keySet().stream().map(this::mesh).toList();
                    case "find" -> mesh((ArtMeshId) da[0]);
                    case "create", "createWithSourceGeometry" -> {
                        var id = new ArtMeshId("minted" + da[0]); var geometry = (ArtMeshGeometry) da[3];
                        var defaultPositions = dm.getName().equals("create") ? geometry.positions()
                            : ((List<?>) da[4]).stream().map(Point2.class::cast).toList();
                        meshes.put(id, new ArtMeshAuthoringSnapshot(id, "source" + id.value(), geometry, List.of(), List.of(),
                            List.of(new ArtMeshAuthoringSnapshot.Form(id.value()+"base", defaultPositions, 500)), List.of()));
                        commit(); yield mesh(id);
                    }
                    default -> throw new AssertionError(dm);
                });
                case "parameterBindings" -> proxy(ParameterBindingOperations.class, (bp, bm, ba) -> {
                    if (!bm.getName().equals("bind")) throw new AssertionError(bm);
                    bind((ParameterId) a[0], new ArtMeshId(((ParameterBindingTarget) ba[0]).id()), (List<?>) ba[1]); return null;
                });
                case "canvas" -> proxy(Canvas.class, (cp, cm, ca) -> switch (cm.getName()) {
                    case "widthPixels", "heightPixels" -> 1024f;
                    case "originXPixels", "originYPixels" -> 512f;
                    case "pixelsPerUnit" -> 100f;
                    default -> throw new AssertionError(cm);
                });
                case "deformers", "parts" -> Proxy.newProxyInstance(m.getReturnType().getClassLoader(), new Class<?>[]{m.getReturnType()},
                    (op, om, oa) -> { if (!om.getName().equals("all")) throw new AssertionError(om); return List.of(); });
                default -> throw new AssertionError(m);
            });
        }
        Parameter parameter(ParameterId id) {
            return proxy(Parameter.class, (p, m, a) -> {
                var definition = defs.get(id);
                switch (m.getName()) {
                    case "id": return id;
                    case "type": return definition.type();
                    case "repeat": return Optional.of(definition.repeat());
                    case "getValue": return live.get(id);
                    case "getDefaultValue": return definition.defaultValue();
                    case "getMinimumValue": return definition.minimumValue();
                    case "getMaximumValue": return definition.maximumValue();
                    case "setValue": break;
                    default: throw new AssertionError(m);
                }
                float value = (Float) a[0];
                if (Float.compare(live.get(id), value) != 0) {
                    live.put(id, value); liveVersion++; commit();
                }
                return null;
            });
        }
        void bind(ParameterId parameter, ArtMeshId id, List<?> points) {
            var s = meshes.get(id); var axes = new ArrayList<>(s.axes());
            axes.add(new ArtMeshAuthoringSnapshot.Axis(parameter.value(), parameter, -30, 0, 30,
                points.stream().map(ParameterBindingPoint.class::cast).map(ParameterBindingPoint::value).toList()));
            List<List<ArtMeshAuthoringSnapshot.Coordinate>> tuples = List.of(List.of());
            for (var axis : axes) {
                var next = new ArrayList<List<ArtMeshAuthoringSnapshot.Coordinate>>();
                for (var tuple : tuples) for (float key : axis.keys()) {
                    var coords = new ArrayList<>(tuple); coords.add(new ArtMeshAuthoringSnapshot.Coordinate(axis.parameterGuid(), key)); next.add(coords);
                }
                tuples = next;
            }
            var cells = new ArrayList<ArtMeshAuthoringSnapshot.Cell>(); var forms = new ArrayList<ArtMeshAuthoringSnapshot.Form>();
            for (var tuple : tuples) {
                String guid = id.value()+"f"+cells.size(); cells.add(new ArtMeshAuthoringSnapshot.Cell(guid, tuple));
                forms.add(new ArtMeshAuthoringSnapshot.Form(guid, s.forms().get(0).positions(), 500));
            }
            meshes.put(id, new ArtMeshAuthoringSnapshot(id, s.sourceGuid(), s.sourceGeometry(), axes, cells, forms, List.of())); commit();
        }
        Drawable mesh(ArtMeshId id) {
            return proxy(Drawable.class, (p, m, a) -> switch (m.getName()) {
                case "id" -> id;
                case "authoringSnapshot" -> meshes.get(id);
                case "evaluationSnapshot" -> evaluate(id);
                case "setOrdinaryKeyformPositions" -> {
                    if (!meshes.get(id).equals(a[0])) throw new IllegalStateException("stale batch");
                    var snapshot = meshes.get(id);
                    var cells = new HashMap<String, ArtMeshAuthoringSnapshot.Cell>();
                    for (var cell : snapshot.cells()) cells.put(cell.formGuid(), cell);
                    var edits = new HashMap<String, ArtMeshKeyformPositions>();
                    for (var value : (List<?>) a[1]) {
                        var edit = (ArtMeshKeyformPositions) value;
                        var cell = cells.get(edit.formGuid());
                        if (cell == null || cell.coordinates().size() != edit.coordinates().size()
                                || !cell.coordinates().containsAll(edit.coordinates()) || edits.put(edit.formGuid(), edit) != null)
                            throw new IllegalArgumentException("wrong form/tuple or duplicate");
                    }
                    meshes.put(id, new ArtMeshAuthoringSnapshot(id, snapshot.sourceGuid(), snapshot.sourceGeometry(),
                        snapshot.axes(), snapshot.cells(), snapshot.forms().stream().map(f -> edits.containsKey(f.formGuid())
                            ? new ArtMeshAuthoringSnapshot.Form(f.formGuid(), edits.get(f.formGuid()).positions(), f.drawOrder()) : f).toList(),
                        snapshot.morphKeys()));
                    commit();
                    yield null;
                }
                case "ordinaryKeyform" -> {
                    var coords = (List<?>) a[0]; var s = meshes.get(id);
                    var cell = s.cells().stream().filter(c -> c.coordinates().size() == coords.size() && c.coordinates().containsAll(coords))
                        .findFirst().orElseThrow();
                    yield proxy(ArtMeshKeyform.class, (kp, km, ka) -> switch (km.getName()) {
                        case "snapshot" -> meshes.get(id).forms().stream().filter(f -> f.formGuid().equals(cell.formGuid())).findFirst().orElseThrow();
                        case "defaultKeyform" -> cell.coordinates().stream().allMatch(c -> c.value() == 0);
                        case "setDrawOrder" -> {
                            if(cell.coordinates().stream().allMatch(c->c.value()==0))throw new IllegalStateException("default order write");
                            int order=(Integer)ka[0];if(order<0 || order>1000)throw new IllegalArgumentException("order range");
                            var current=meshes.get(id);
                            meshes.put(id,new ArtMeshAuthoringSnapshot(id,current.sourceGuid(),current.sourceGeometry(),current.axes(),current.cells(),
                                current.forms().stream().map(f->f.formGuid().equals(cell.formGuid())
                                    ? new ArtMeshAuthoringSnapshot.Form(f.formGuid(),f.positions(),order):f).toList(),current.morphKeys()));
                            commit();yield null;
                        }
                        case "setPositions" -> {
                            var current = meshes.get(id);
                            meshes.put(id, new ArtMeshAuthoringSnapshot(id, current.sourceGuid(), current.sourceGeometry(), current.axes(), current.cells(),
                                current.forms().stream().map(f -> f.formGuid().equals(cell.formGuid()) ? new ArtMeshAuthoringSnapshot.Form(f.formGuid(),
                                    ((List<?>) ka[0]).stream().map(Point2.class::cast).toList(), f.drawOrder()) : f).toList(), current.morphKeys()));
                            commit(); yield null;
                        }
                        default -> throw new AssertionError(km);
                    });
                }
                default -> throw new AssertionError(m);
            });
        }
        ArtMeshEvaluationSnapshot evaluate(ArtMeshId id) {
            var s = meshes.get(id); var params = defs.keySet().stream().map(p -> new ArtMeshEvaluationSnapshot.ParameterValue(p.value(), p, live.get(p))).toList();
            var points = new ArrayList<Point2>();
            var keys = s.axes().get(0).keys(); float yaw = live.get(s.axes().get(0).parameterId()), pitch = live.get(s.axes().get(1).parameterId());
            int x = interval(keys, yaw), y = interval(keys, pitch), n = keys.size();
            double u = (yaw-keys.get(x))/(double)(keys.get(x+1)-keys.get(x));
            double v = (pitch-keys.get(y))/(double)(keys.get(y+1)-keys.get(y));
            int[] corners = {x*n+y, (x+1)*n+y, x*n+y+1, (x+1)*n+y+1};
            double[] weights = {(1-u)*(1-v), u*(1-v), (1-u)*v, u*v};
            for (int vertex=0; vertex<s.sourceGeometry().positions().size(); vertex++) {
                double px=0, py=0;
                for (int k=0;k<4;k++) { var point=s.forms().get(corners[k]).positions().get(vertex); px+=weights[k]*point.x(); py+=weights[k]*point.y(); }
                points.add(new Point2((float)px+(corruptEvaluation?1:0), (float)py));
            }
            return new ArtMeshEvaluationSnapshot(id, s.sourceGuid(), Optional.of(new ArtMeshEvaluationSnapshot.Form(points,500,"Canvas",true)),
                new ArtMeshEvaluationSnapshot.ParameterState(liveVersion,params), new ArtMeshEvaluationSnapshot.ParameterState(++consumed,params), consumed);
        }
        int interval(List<Float> keys, float value) { int i=0; while(i<keys.size()-2 && value>keys.get(i+1)) i++; return i; }
        EditorCommandService commands() {
            return proxy(EditorCommandService.class, (p,m,a) -> { if (!m.getName().equals("execute")) throw new AssertionError(m);
                return new EditorCommandResult(EditorCommandResult.Status.EXECUTED,"synthetic-canvas"); });
        }
        AuthoringTransactionService transactions() {
            return proxy(AuthoringTransactionService.class, (p,m,a) -> {
                if (!m.getName().equals("execute")) throw new AssertionError(m);
                var before=history(); inTransaction=true;
                try { ((AuthoringTransactionWork<?>)a[1]).run(); } finally { inTransaction=false; }
                commit(); return AuthoringTransactionResult.committed(null,new AuthoringTransactionReceipt("synthetic-tx","joint",
                    before,history(),Optional.of("synthetic-entry")));
            });
        }
    }
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler));
    }
}
