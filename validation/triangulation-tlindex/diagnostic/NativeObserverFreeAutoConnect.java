import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import javax.swing.SwingUtilities;

/** Separate 5303 command boundary, without producer recording or transformers.
 * Package/native admission remains a caller gate; this source alone is not host admission.
 */
final class NativeObserverFreeAutoConnect {
    private static final String EDIT = "com.live2d.cubism.doc.modeling.CModelingEditMode_MeshEditor";
    private NativeObserverFreeAutoConnect() {}

    record Input(String sourceId, Object source, Object mesh, int edgeVersion, int positionVersion, int pointCount) {}
    record Result(String sourceId, MeshResultSnapshot.Result arrays, int beforeEdgeVersion,
                  int indexCacheVersion, int positionVersion, int vertexCacheVersion) {}
    record Observation(CommandCpuBoundary.Sample before, CommandCpuBoundary.Sample after,
                       List<Result> results) {}
    record Interval(CommandCpuBoundary.Sample before, CommandCpuBoundary.Sample after) {}

    static List<String> enter(Object controller, NativeAutoConnect.Binding binding) throws Exception {
        List<String> selected = NativeAutoConnect.enter(controller, binding);
        Object mode = NativeAutoConnect.invokeNamed(NativeAutoConnect.boundDocument(controller, binding),
                "getCurrentEditMode");
        List<Input> inputs = bind(mode, null);
        List<String> ids = inputs.stream().map(Input::sourceId).toList();
        require(ids.size() == selected.size() && new HashSet<>(ids).equals(new HashSet<>(selected)),
                "native edit set differs from complete selection");
        return ids;
    }

    static Observation connect(Object controller, NativeAutoConnect.Binding binding,
            List<String> expectedIds, CommandCpuBoundary cpu) throws Exception {
        require(SwingUtilities.isEventDispatchThread(), "command boundary requires EDT");
        Object doc = NativeAutoConnect.boundDocument(controller, binding);
        Object mode = NativeAutoConnect.invokeNamed(doc, "getCurrentEditMode");
        require(mode.getClass().getName().equals(EDIT), "native mesh mode required");
        // Resolve command and configure both options before measuring. No reflective
        // method discovery, source hashing or filesystem I/O inside the interval.
        Method command = resolveCommand(controller, doc);
        Class<?> manual = Class.forName(
                "com.live2d.cubism.view.palette.tool.toolMode.meshEditor.ToolMode_MeshEdit_Manual",
                false, mode.getClass().getClassLoader());
        Object panel = NativeAutoConnect.callReturning(manual.getField("INSTANCE").get(null),
                "getToolPanel", "com.live2d.cubism.view.palette.tool.toolMode.meshEditor.ToolPanel_MeshEdit");
        for (String getter : List.of("getCheckboxRebuildMesh", "getCheckboxSaveBorderOfEdge")) {
            Object box = NativeAutoConnect.invokeNamed(panel, getter);
            NativeAutoConnect.invokeNamed(box, "setSelected", true);
            require(Boolean.TRUE.equals(NativeAutoConnect.invokeNamed(box, "isSelected")),
                    "native command option mismatch");
        }
        List<Input> inputs = bind(mode, expectedIds);
        // Exact 5303 autoConnect returns early for empty native boundary loops with
        // preserveBorder=true. Evaluate the native read-only loop builder before
        // measurement; never skip a source or substitute a success marker.
        Class<?> loops = Class.forName("com.live2d.graphics3d.editableMesh.b", false,
                mode.getClass().getClassLoader());
        Object builder = loops.getField("a").get(null);
        for (Input input : inputs) {
            Object value = NativeAutoConnect.invokeNamed(builder, "b", input.mesh());
            require(value instanceof List<?> && !((List<?>) value).isEmpty(),
                    "native boundary loops empty; generation cannot be established");
        }
        requireContext(controller, binding, doc, mode, inputs, expectedIds);
        for (Input input : inputs) require(input.pointCount() >= 3
                && input.edgeVersion() == integer(input.mesh(), "get_edge_edit_version")
                && input.positionVersion() == integer(input.mesh(), "get_postion_edit_version")
                && input.pointCount() == integer(input.mesh(), "getPointCount"),
                "native input changed during preparation");
        // Everything between these two counter reads is the one pre-resolved
        // native command invocation. Post-command context and array checks follow.
        Interval interval = invokeMeasured(command, controller, doc, cpu);
        requireContext(controller, binding, doc, mode, inputs, expectedIds);
        List<Result> results = capture(inputs);
        requireContext(controller, binding, doc, mode, inputs, expectedIds);
        return new Observation(interval.before(), interval.after(), results);
    }

    static Interval invokeMeasured(Method command, Object controller, Object doc,
            CommandCpuBoundary cpu) throws Exception {
        require(SwingUtilities.isEventDispatchThread(), "measured invocation requires EDT");
        CommandCpuBoundary.Sample before = cpu.read();
        CommandCpuBoundary.Sample after;
        Throwable invocationFailure = null;
        try {
            command.invoke(controller, doc);
        } catch (InvocationTargetException failure) {
            invocationFailure = failure.getCause();
        } finally {
            // Counter must be read even when the native command throws. Refusal
            // propagates; a failed invocation cannot create a complete observation.
            after = cpu.read();
        }
        if (invocationFailure instanceof Error error) throw error;
        if (invocationFailure instanceof Exception exception) throw exception;
        require(invocationFailure == null, "native command failed");
        return new Interval(before, after);
    }

    static List<Result> capture(List<Input> inputs) throws Exception {
        require(SwingUtilities.isEventDispatchThread(), "command return snapshot requires EDT");
        require(inputs != null && !inputs.isEmpty(), "empty snapshot scope");
        IdentityHashMap<Object, Boolean> meshes = new IdentityHashMap<>();
        HashSet<String> ids = new HashSet<>();
        List<Result> results = new ArrayList<>();
        try {
            for (Input input : inputs) {
                require(input != null && input.sourceId() != null && input.source() != null && input.mesh() != null
                        && ids.add(input.sourceId()) && meshes.put(input.mesh(), true) == null,
                        "duplicate/missing native source or mesh");
                Object mesh = input.mesh();
                int edge = integer(mesh, "get_edge_edit_version");
                int cache = integer(mesh, "getCache_version_gl_indices$core");
                int position = integer(mesh, "get_postion_edit_version");
                int vertex = integer(mesh, "getCache_version_gl_vertex$core");
                int count = integer(mesh, "getPointCount");
                require(edge != input.edgeVersion() && position == input.positionVersion()
                        && position == vertex && count == input.pointCount(),
                        "command version/position/count scope refused");
                Object xy = NativeAutoConnect.invokeNamed(mesh, "getCached_positions$core");
                Object triangles = NativeAutoConnect.invokeNamed(mesh, "getCached_indices$core");
                require(xy instanceof float[] && triangles instanceof int[], "missing return arrays");
                MeshResultSnapshot.Result arrays = MeshResultSnapshot.snapshot(count, edge,
                        (float[]) xy, (int[]) triangles);
                require(edge == integer(mesh, "get_edge_edit_version")
                        && cache == integer(mesh, "getCache_version_gl_indices$core")
                        && position == integer(mesh, "get_postion_edit_version")
                        && vertex == integer(mesh, "getCache_version_gl_vertex$core")
                        && count == integer(mesh, "getPointCount")
                        && xy == NativeAutoConnect.invokeNamed(mesh, "getCached_positions$core")
                        && triangles == NativeAutoConnect.invokeNamed(mesh, "getCached_indices$core"),
                        "native state changed during snapshot");
                results.add(new Result(input.sourceId(), arrays, input.edgeVersion(), cache, position, vertex));
            }
            return List.copyOf(results);
        } finally {
            meshes.clear();
        }
    }

    private static List<Input> bind(Object mode, List<String> expectedIds) throws Exception {
        Object value = NativeAutoConnect.invokeNamed(mode, "getEditDataList");
        require(value instanceof List<?> && !((List<?>) value).isEmpty(), "missing native edit list");
        List<Input> inputs = new ArrayList<>();
        IdentityHashMap<Object, Boolean> meshes = new IdentityHashMap<>();
        HashSet<String> ids = new HashSet<>();
        try {
            for (Object data : (List<?>) value) {
                Object source = NativeAutoConnect.invokeNamed(data, "a");
                Object mesh = NativeAutoConnect.invokeNamed(data, "b");
                String id = NativeAutoConnect.invokeNamed(source, "getId").toString();
                require(mesh != null && ids.add(id) && meshes.put(mesh, true) == null,
                        "duplicate native source/mesh");
                inputs.add(new Input(id, source, mesh, integer(mesh, "get_edge_edit_version"),
                        integer(mesh, "get_postion_edit_version"), integer(mesh, "getPointCount")));
            }
            if (expectedIds != null) require(inputs.stream().map(Input::sourceId).toList().equals(expectedIds),
                    "native edit source order changed");
            return List.copyOf(inputs);
        } finally {
            meshes.clear();
        }
    }

    private static void requireContext(Object controller, NativeAutoConnect.Binding binding,
            Object doc, Object mode, List<Input> inputs, List<String> ids) throws Exception {
        require(NativeAutoConnect.boundDocument(controller, binding) == doc
                && NativeAutoConnect.invokeNamed(doc, "getCurrentEditMode") == mode,
                "native document/mode changed");
        List<Input> now = bind(mode, ids);
        require(now.size() == inputs.size(), "native mesh count changed");
        for (int i = 0; i < now.size(); i++) require(now.get(i).mesh() == inputs.get(i).mesh()
                && now.get(i).source() == inputs.get(i).source(), "native source/mesh identity changed");
    }

    private static Method resolveCommand(Object controller, Object doc) {
        Method selected = null;
        for (Method method : controller.getClass().getMethods()) {
            if (!method.getName().equals("command_meshEditConnectAuto") || method.isBridge()
                    || method.getParameterCount() != 1 || method.getReturnType() != void.class
                    || !method.getParameterTypes()[0].isInstance(doc)) continue;
            require(selected == null, "ambiguous native command");
            selected = method;
        }
        require(selected != null, "missing native command");
        return selected;
    }

    private static int integer(Object mesh, String name) throws Exception {
        Object value = NativeAutoConnect.invokeNamed(mesh, name);
        require(value instanceof Integer, "unexpected native integer");
        return (Integer) value;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
