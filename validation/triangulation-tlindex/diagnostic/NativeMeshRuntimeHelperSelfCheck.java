import dev.turboism.adapter.cubism.mesh.NativeMeshEdgeLookup;
import dev.turboism.adapter.cubism.mesh.NativeMeshEdgeTableOwnedAccess;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual helper refusal/disposal and exposed-view counterexamples. No production admission. */
public final class NativeMeshRuntimeHelperSelfCheck {
    private static int checks;
    private NativeMeshRuntimeHelperSelfCheck() { }
    private static void require(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        for (String path : args) for (boolean assertions : new boolean[] {false, true}) {
            int start = checks;
            try (var loader = new NativeMeshEdgeLoopSelfCheck.Loader(Path.of(path), false, assertions, true)) {
                Class<?> meshType = loader.loadClass("com.live2d.graphics3d.editableMesh.GEditableMesh2");
                Class<?> edgeType = loader.loadClass("com.live2d.graphics3d.editableMesh.MEdge");
                Class<?> rank = loader.loadClass("com.live2d.graphics3d.editableMesh.MEdge$EdgeType");
                Object normal = rank.getField("NORMAL").get(null);
                var constructor = edgeType.getConstructor(int.class, int.class, rank);
                Object mesh = meshType.getConstructor().newInstance();
                Field cached = field(meshType, "cached_indices");
                List<Object> edges = (List<Object>) field(meshType, "_edges").get(mesh);
                Method nativeFind = meshType.getDeclaredMethod("chechExistingEdge_exe", int.class, int.class, boolean.class);
                nativeFind.setAccessible(true);
                edges.add(constructor.newInstance(0, 1, normal));
                edges.add(constructor.newInstance(0, 1, normal));
                cached.set(mesh, new int[] {0, 1, 2});
                require(NativeMeshEdgeLookup.enter(mesh) == null, "missing real extended admission declines");
                require(NativeMeshEdgeTableOwnedAccess.reservedBytes() == 0, "public refusal allocates no table");
                AtomicInteger closed = new AtomicInteger();
                try (AutoCloseable scope = NativeMeshEdgeTableOwnedAccess.begin(mesh, closed::incrementAndGet)) {
                    require(scope != null && closed.get() == 0, "owned scope consumes live definition");
                    require(NativeMeshEdgeLookup.find(mesh, 0, 1, false) == 0, "first physical duplicate slot");
                    require(NativeMeshEdgeLookup.find(mesh, 1, 0, false) == -1, "stored orientation remains literal");
                    require(NativeMeshEdgeLookup.find(mesh, 0, 1, true) == -2, "filtered path remains native");
                    require(NativeMeshEdgeLookup.find(new Object(), 0, 1, false) == -2, "other receiver remains native");
                    edges.add(constructor.newInstance(1, 2, normal));
                    NativeMeshEdgeLookup.appended(mesh);
                    require(NativeMeshEdgeLookup.find(mesh, 1, 2, false) == 2, "actual successful append registered");
                    edges.add(constructor.newInstance(2, 3, normal));
                    require(NativeMeshEdgeLookup.find(mesh, 0, 1, false) == -2, "unregistered size change discards");
                    require(NativeMeshEdgeTableOwnedAccess.reservedBytes() == 0, "discard releases before operation ends");
                }
                require(closed.get() == 1, "definition released exactly once");
                require(NativeMeshEdgeLookup.find(mesh, 0, 1, false) == -2, "no scope survives close");
                for (Object malformed : new Object[] {null, new int[0], new int[] {0, 1}, new int[] {0, 0, 1},
                        new int[] {0, 1, 1}, new int[] {0, 1, 0}}) {
                    cached.set(mesh, malformed); closed.set(0);
                    require(NativeMeshEdgeTableOwnedAccess.begin(mesh, closed::incrementAndGet) == null, "unsupported indices refuse");
                    require(closed.get() == 1 && NativeMeshEdgeTableOwnedAccess.reservedBytes() == 0, "refusal releases definition/buffers");
                }
                cached.set(mesh, new int[] {0, 1, 2}); edges.clear(); edges.add(null); closed.set(0);
                require(NativeMeshEdgeTableOwnedAccess.begin(mesh, closed::incrementAndGet) == null, "unknown edge refuses partial initialization");
                require(closed.get() == 1 && NativeMeshEdgeTableOwnedAccess.reservedBytes() == 0, "partial initialization fully released");
                edges.clear();
                for (int i = 0; i < 16385; i++) edges.add(constructor.newInstance(0, 1, normal));
                closed.set(0);
                require(NativeMeshEdgeTableOwnedAccess.begin(mesh, closed::incrementAndGet) == null, "bounded list admission refuses");
                require(closed.get() == 1 && NativeMeshEdgeTableOwnedAccess.reservedBytes() == 0, "bound refusal releases");
                // Preserve failures: even native views can bypass list size and mesh version.
                // These controls explain why production mutation admission remains mandatory.
                for (int mode = 0; mode < 3; mode++) {
                    edges.clear(); edges.add(constructor.newInstance(0, 1, normal));
                    try (AutoCloseable scope = NativeMeshEdgeTableOwnedAccess.begin(mesh)) {
                        require(scope != null, "owned counterexample scope");
                        Object replacement = constructor.newInstance(2, 3, normal);
                        if (mode == 0) edges.set(0, replacement);
                        else if (mode == 1) edges.subList(0, 1).set(0, replacement);
                        else { var iterator = edges.listIterator(); iterator.next(); iterator.set(replacement); }
                        require(NativeMeshEdgeLookup.find(mesh, 0, 1, false) == 0
                                && nativeFind.invoke(mesh, 0, 1, false) == null, "same-size exposed mutation counterexample retained");
                    }
                }
                require(NativeMeshEdgeTableOwnedAccess.reservedBytes() == 0, "all helper reservations released");
            }
            System.out.printf("NATIVE_MESH_RUNTIME_HELPER_PASS assertions=%s checks=%d counterexamples=set,subList,listIterator%n", assertions, checks - start);
        }
        require(args.length == 3, "three official archives");
        System.out.printf("NATIVE_MESH_RUNTIME_HELPER_FINISHED checks=%d reservedBytes=0 admission=OPEN%n", checks);
    }
}
