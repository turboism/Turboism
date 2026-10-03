import java.util.IdentityHashMap;
import java.util.List;
import javax.swing.SwingUtilities;

/** Own executable fixtures only; neither loads nor executes official host classes. */
public final class NativeCommandReturnSnapshotSelfCheck {
    private static int checks;

    public static final class Mesh {
        int edge = 8, cache = 2, position = 3, vertex = 3;
        float[] xy = {0, 0, 1, 0, 0, 1};
        int[] indices = {0, 1, 2};
        boolean changeVersionOnArrayRead;
        public int get_edge_edit_version() { return edge; }
        public int getCache_version_gl_indices$core() { return cache; }
        public int get_postion_edit_version() { return position; }
        public int getCache_version_gl_vertex$core() { return vertex; }
        public int getPointCount() { return 3; }
        public float[] getCached_positions$core() { return xy; }
        public int[] getCached_indices$core() {
            if (changeVersionOnArrayRead) edge++;
            return indices;
        }
        @Override public int hashCode() { throw new AssertionError("mesh hashCode called"); }
        @Override public boolean equals(Object other) { throw new AssertionError("mesh equals called"); }
    }

    interface Checked { void run() throws Exception; }
    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    static void refuse(Checked action) throws Exception {
        try { action.run(); }
        catch (IllegalStateException expected) { checks++; return; }
        throw new AssertionError("invalid observation accepted");
    }
    static MeshProducerRecorder.Event event(String id, int invocation, int cycle, Mesh mesh, long thread) {
        return new MeshProducerRecorder.Event(invocation, cycle, id, thread, 1, 2,
                MeshResultSnapshot.snapshot(3, 7, mesh.xy, mesh.indices), null);
    }
    static NativeProducerAutoConnect.Observation proof(List<MeshProducerRecorder.Event> events) {
        return new NativeProducerAutoConnect.Observation(true, true, events, "");
    }
    static IdentityHashMap<Object, String> scope(Mesh a, Mesh b) {
        var scope = new IdentityHashMap<Object, String>();
        scope.put(a, "a"); scope.put(b, "b"); return scope;
    }

    static void onEdt() throws Exception {
        Mesh a = new Mesh(), b = new Mesh();
        long thread = Thread.currentThread().getId();
        var ids = List.of("a", "b"); var meshes = scope(a, b);
        var events = List.of(event("a", 1, 1, a, thread), event("b", 2, 1, b, thread));
        var result = NativeCommandReturnSnapshot.captureAndCompare(ids, meshes, proof(events), 1);
        require(result.size() == 2 && result.get(0).sourceId().equals("a")
                && result.get(1).sourceId().equals("b"), "complete ordered results");
        require(result.get(0).arrays().edgeVersion() == 8 && result.get(0).producerEdgeVersion() == 7
                && result.get(0).indexCacheVersion() == 2, "separate boundary/cache versions");
        refuse(() -> MeshResultSnapshot.capture(a));
        String pinned = result.get(0).arrays().positionsSha256();
        a.xy[0] = 5;
        require(pinned.equals(result.get(0).arrays().positionsSha256()), "detached result");
        refuse(() -> NativeCommandReturnSnapshot.captureAndCompare(ids, meshes, proof(events), 1));
        a.xy[0] = 0;
        refuse(() -> NativeCommandReturnSnapshot.captureAndCompare(ids, meshes, null, 1));
        refuse(() -> NativeCommandReturnSnapshot.captureAndCompare(ids, meshes,
                new NativeProducerAutoConnect.Observation(false, true, events, ""), 1));
        refuse(() -> NativeCommandReturnSnapshot.captureAndCompare(ids, meshes,
                new NativeProducerAutoConnect.Observation(true, false, events, ""), 1));
        refuse(() -> NativeCommandReturnSnapshot.captureAndCompare(ids, meshes,
                new NativeProducerAutoConnect.Observation(true, true, events, "FAILED"), 1));
        refuse(() -> NativeCommandReturnSnapshot.captureAndCompare(ids, meshes, proof(events.subList(0, 1)), 1));
        refuse(() -> NativeCommandReturnSnapshot.captureAndCompare(ids, meshes,
                proof(List.of(events.get(1), events.get(0))), 1));
        refuse(() -> NativeCommandReturnSnapshot.captureAndCompare(ids, meshes,
                proof(List.of(events.get(0), event("b", 1, 1, b, thread))), 1));
        refuse(() -> NativeCommandReturnSnapshot.captureAndCompare(ids, meshes, proof(events), 2));
        refuse(() -> NativeCommandReturnSnapshot.captureAndCompare(ids, meshes,
                proof(List.of(events.get(0), event("b", 2, 1, b, thread + 1))), 1));
        refuse(() -> NativeCommandReturnSnapshot.captureAndCompare(List.of("a", "a"), meshes, proof(events), 1));
        a.vertex = 0;
        refuse(() -> NativeCommandReturnSnapshot.captureAndCompare(ids, meshes, proof(events), 1));
        a.vertex = a.position;
        a.changeVersionOnArrayRead = true;
        refuse(() -> NativeCommandReturnSnapshot.captureAndCompare(ids, meshes, proof(events), 1));
        a.changeVersionOnArrayRead = false;
        a.indices[2] = 3;
        refuse(() -> NativeCommandReturnSnapshot.captureAndCompare(ids, meshes, proof(events), 1));
        a.indices[2] = 2;
        meshes.clear();
        require(result.size() == 2 && result.get(0).sourceId().equals("a"), "scope released independently");
    }

    public static void main(String[] args) throws Exception {
        final Exception[] failure = {null};
        SwingUtilities.invokeAndWait(() -> {
            try { onEdt(); } catch (Exception exception) { failure[0] = exception; }
        });
        if (failure[0] != null) throw failure[0];
        Mesh a = new Mesh(), b = new Mesh();
        refuse(() -> NativeCommandReturnSnapshot.captureAndCompare(List.of("a", "b"), scope(a, b),
                proof(List.of()), 1));
        System.out.println("NativeCommandReturnSnapshotSelfCheck PASS checks=" + checks);
    }
}
