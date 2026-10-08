import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import javax.swing.SwingUtilities;

/**
 * Feasibility observation only: requires complete same-command producer evidence.
 * Does not claim GL index-cache freshness or admit observer-free host measurement.
 * Caller owns task/document/mode binding and must invoke before yielding the EDT.
 */
final class NativeCommandReturnSnapshot {
    private NativeCommandReturnSnapshot() {}

    record Result(String sourceId, MeshResultSnapshot.Result arrays,
                  int indexCacheVersion, int positionVersion, int vertexCacheVersion,
                  int producerEdgeVersion) {}

    static List<Result> captureAndCompare(List<String> expectedIds,
            IdentityHashMap<Object, String> expectedMeshes,
            NativeProducerAutoConnect.Observation producer, int cycle) throws Exception {
        require(SwingUtilities.isEventDispatchThread(), "command-return observation requires EDT");
        require(cycle > 0 && expectedIds != null && !expectedIds.isEmpty()
                && new HashSet<>(expectedIds).size() == expectedIds.size(), "invalid source scope");
        require(expectedMeshes != null && expectedMeshes.size() == expectedIds.size()
                && !expectedMeshes.containsKey(null) && !expectedMeshes.containsValue(null)
                && new HashSet<>(expectedMeshes.values()).equals(new HashSet<>(expectedIds)),
                "invalid mesh/source scope");
        require(producer != null && producer.nativeCommandReturned() && producer.complete()
                && "".equals(producer.failure()),
                "complete producer proof required");
        HashMap<String, Object> meshes = new HashMap<>();
        HashMap<String, MeshProducerRecorder.Event> events = new HashMap<>();
        try {
            for (var entry : expectedMeshes.entrySet()) {
                require(meshes.put(entry.getValue(), entry.getKey()) == null, "duplicate source mesh");
            }
            int ordinal = 0;
            for (var event : producer.events()) {
                require(event.cycle() == cycle && event.threadId() == Thread.currentThread().getId()
                        && event.result() != null && event.failure() == null
                        && ordinal < expectedIds.size() && event.invocation() == ordinal + 1L
                        && event.sourceId().equals(expectedIds.get(ordinal))
                        && events.put(event.sourceId(), event) == null, "invalid/duplicate producer event");
                ordinal++;
            }
            require(events.size() == expectedIds.size()
                    && events.keySet().equals(new HashSet<>(expectedIds)), "incomplete producer coverage");
            List<Result> result = new ArrayList<>();
            for (String id : expectedIds) {
                Object mesh = meshes.get(id);
                int edge = integer(mesh, "get_edge_edit_version");
                int cache = integer(mesh, "getCache_version_gl_indices$core");
                int position = integer(mesh, "get_postion_edit_version");
                int vertex = integer(mesh, "getCache_version_gl_vertex$core");
                int count = integer(mesh, "getPointCount");
                require(position == vertex, "position cache stale at command return");
                Object xy = call(mesh, "getCached_positions$core");
                Object triangles = call(mesh, "getCached_indices$core");
                require(xy instanceof float[] && triangles instanceof int[], "missing command-return arrays");
                MeshResultSnapshot.Result arrays = MeshResultSnapshot.snapshot(count, edge,
                        (float[]) xy, (int[]) triangles);
                require(edge == integer(mesh, "get_edge_edit_version")
                        && cache == integer(mesh, "getCache_version_gl_indices$core")
                        && position == integer(mesh, "get_postion_edit_version")
                        && vertex == integer(mesh, "getCache_version_gl_vertex$core")
                        && count == integer(mesh, "getPointCount")
                        && xy == call(mesh, "getCached_positions$core")
                        && triangles == call(mesh, "getCached_indices$core"),
                        "mesh changed during command-return observation");
                var earlier = events.get(id).result();
                require(arrays.pointCount() == earlier.pointCount()
                        && arrays.positionValues() == earlier.positionValues()
                        && arrays.indexValues() == earlier.indexValues()
                        && arrays.positionsSha256().equals(earlier.positionsSha256())
                        && arrays.indicesSha256().equals(earlier.indicesSha256()),
                        "command-return arrays differ from bound producer result");
                // Native command marks edges after producer return. Preserve both
                // versions, with no inferred freshness/monotonicity/overflow rule.
                result.add(new Result(id, arrays, cache, position, vertex, earlier.edgeVersion()));
            }
            return List.copyOf(result);
        } finally {
            meshes.clear();
            events.clear();
        }
    }

    private static int integer(Object mesh, String getter) throws Exception {
        Object value = call(mesh, getter);
        require(value instanceof Integer, "unexpected native integer");
        return (Integer) value;
    }

    private static Object call(Object mesh, String getter) throws Exception {
        try {
            return mesh.getClass().getMethod(getter).invoke(mesh);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Error error) throw error;
            if (failure.getCause() instanceof Exception exception) throw exception;
            throw failure;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
