import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

/** Scoped producer observations only. Synthetic arrays do not establish native correctness. */
public final class MeshProducerRecorderSelfCheck {
    private static int checks;
    private MeshProducerRecorderSelfCheck() {}
    public static void main(String[] args) throws Exception {
        Mesh mesh = new Mesh();
        IdentityHashMap<Object, String> bindings = binding(mesh, "source");
        Object outside = MeshProducerRecorder.started(mesh);
        require(outside == null);
        try (var scope = MeshProducerRecorder.begin(1, List.of("source"), bindings)) {
            MeshProducerRecorder.returned(outside, mesh);
            reject(scope::finish);
            Object first = MeshProducerRecorder.started(mesh);
            MeshProducerRecorder.returned(first, mesh);
            List<MeshProducerRecorder.Event> before = scope.finish();
            require(before.size() == 1 && before.get(0).cycle() == 1);
            Object second = MeshProducerRecorder.started(mesh);
            mesh.indices[1] = 2; mesh.indices[2] = 1;
            MeshProducerRecorder.returned(second, mesh);
            List<MeshProducerRecorder.Event> after = scope.finish();
            require(after.size() == 2 && after.get(0).invocation() < after.get(1).invocation());
            require(!before.get(0).result().indicesSha256().equals(after.get(1).result().indicesSha256()));
            reject(() -> MeshProducerRecorder.begin(2, List.of("source"), bindings));
            reject(MeshProducerRecorder::requireIdle);
            require(scope.retainedMeshCount() == 1);
        }
        // Old delayed reader still refuses stale indices; only the invocation-bound producer path admits them.
        SwingUtilities.invokeAndWait(() -> rejectChecked(() -> MeshResultSnapshot.capture(mesh)));
        var old = MeshProducerRecorder.begin(1, List.of("source"), bindings);
        Object oldTicket = MeshProducerRecorder.started(mesh);
        MeshProducerRecorder.returned(oldTicket, mesh);
        old.close();
        require(old.retainedMeshCount() == 0);
        try (var next = MeshProducerRecorder.begin(2, List.of("source"), bindings)) {
            MeshProducerRecorder.returned(oldTicket, mesh);
            reject(next::finish);
            MeshProducerRecorder.returned(MeshProducerRecorder.started(mesh), mesh);
            require(next.finish().size() == 1 && next.finish().get(0).cycle() == 2);
        }
        Mesh other = new Mesh();
        IdentityHashMap<Object, String> two = binding(mesh, "one");
        two.put(other, "two");
        try (var scope = MeshProducerRecorder.begin(3, List.of("one", "two"), two)) {
            MeshProducerRecorder.returned(MeshProducerRecorder.started(mesh), mesh);
            reject(scope::finish);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { MeshProducerRecorder.returned(MeshProducerRecorder.started(other), other); }
                catch (Throwable problem) { failure.set(problem); }
            });
            worker.start(); worker.join();
            require(failure.get() == null && scope.finish().size() == 2);
        }
        try (var scope = MeshProducerRecorder.begin(4, List.of("source"), bindings)) {
            MeshProducerRecorder.returned(MeshProducerRecorder.started(new Mesh()), new Mesh());
            MeshProducerRecorder.returned(MeshProducerRecorder.started(mesh), mesh);
            reject(scope::finish); // no silent filtering of unknown producers
        }
        try (var scope = MeshProducerRecorder.begin(5, List.of("source"), bindings)) {
            Object ticket = MeshProducerRecorder.started(mesh);
            reject(scope::finish); // worker still in flight
            MeshProducerRecorder.failed(ticket, new UnsupportedOperationException("native"));
            reject(scope::finish);
            require(scope.events().size() == 1 && scope.events().get(0).result() == null
                && scope.events().get(0).failure().equals("NATIVE_PRODUCER_FAILED"));
        }
        try (var scope = MeshProducerRecorder.begin(5, List.of("source"), bindings)) {
            Object ticket = MeshProducerRecorder.started(mesh);
            MeshProducerRecorder.returned(ticket, mesh);
            require(scope.finish().size() == 1);
            MeshProducerRecorder.returned(ticket, mesh);
            reject(scope::finish);
        }
        for (int mutation = 0; mutation < 5; mutation++) {
            Mesh invalid = new Mesh();
            if (mutation == 0) invalid.vertexCache = 0;
            if (mutation == 1) invalid.positions[0] = Float.NaN;
            if (mutation == 2) invalid.indices[0] = 3;
            if (mutation == 3) invalid.positions = new float[2];
            if (mutation == 4) invalid.changeDuringRead = true;
            var scope = MeshProducerRecorder.begin(6, List.of("source"), binding(invalid, "source"));
            try {
                MeshProducerRecorder.returned(MeshProducerRecorder.started(invalid), invalid);
                reject(scope::finish);
                require(scope.events().size() == 1 && scope.events().get(0).result() == null
                    && scope.events().get(0).sourceId().equals("source") && scope.events().get(0).failure() != null);
            } finally { scope.close(); }
            require(scope.retainedMeshCount() == 0);
        }
        // Callback failures cannot retain native arrays: returned events hold only scalar values/digests.
        require(MeshProducerRecorder.started(mesh) == null);
        reject(() -> MeshProducerRecorder.begin(0, List.of("source"), bindings));
        reject(() -> MeshProducerRecorder.begin(1, List.of("source", "source"), bindings));
        reject(() -> MeshProducerRecorder.begin(1, List.of("wrong"), bindings));
        var abandoned = MeshProducerRecorder.begin(7, List.of("source"), bindings);
        Object unfinished = MeshProducerRecorder.started(mesh);
        abandoned.close();
        require(abandoned.retainedMeshCount() == 0);
        MeshProducerRecorder.returned(unfinished, mesh);
        reject(() -> MeshProducerRecorder.begin(8, List.of("source"), bindings));
        reject(MeshProducerRecorder::requireIdle);
        System.out.println("Mesh producer recorder checks PASS: " + checks);
    }
    public static final class Mesh {
        float[] positions = {0, 0, 1, 0, 0, 1};
        int[] indices = {0, 1, 2};
        int vertexCache = 1;
        int edge = 370;
        boolean changeDuringRead;
        int reads;
        public int getPointCount() { return 3; }
        public int get_edge_edit_version() { return changeDuringRead && ++reads > 1 ? ++edge : edge; }
        public int get_postion_edit_version() { return 1; }
        public int getCache_version_gl_vertex$core() { return vertexCache; }
        public int getCache_version_gl_indices$core() { return -1; }
        public float[] getCached_positions$core() { return positions; }
        public int[] getCached_indices$core() { return indices; }
        @Override public int hashCode() { throw new AssertionError("mesh hashCode queried"); }
        @Override public boolean equals(Object other) { throw new AssertionError("mesh equals queried"); }
    }
    private static IdentityHashMap<Object, String> binding(Object mesh, String id) {
        IdentityHashMap<Object, String> result = new IdentityHashMap<>(); result.put(mesh, id); return result;
    }
    private static void reject(Runnable action) {
        try { action.run(); }
        catch (IllegalStateException expected) { checks++; return; }
        throw new AssertionError("expected refusal");
    }
    private interface Checked { void run() throws Exception; }
    private static void rejectChecked(Checked action) {
        try { action.run(); }
        catch (IllegalStateException expected) { checks++; return; }
        catch (Exception wrong) { throw new AssertionError(wrong); }
        throw new AssertionError("expected refusal");
    }
    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("check failed");
        checks++;
    }
}
