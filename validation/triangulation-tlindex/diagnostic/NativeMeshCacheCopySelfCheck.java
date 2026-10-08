import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** Owned native copy seam; no action context, Editor, Unsafe or synthetic SDK. */
public final class NativeMeshCacheCopySelfCheck {
    private NativeMeshCacheCopySelfCheck() { }
    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name); result.setAccessible(true); return result;
    }
    private static String state(Class<?> type, Object mesh) throws Exception {
        List<String> rows = new ArrayList<>();
        for (Field f : type.getDeclaredFields()) {
            String name = f.getName();
            if (!name.startsWith("cached_") && !name.startsWith("cache_version_")
                    && !name.equals("_edge_edit_version") && !name.equals("_point_edit_version")) continue;
            f.setAccessible(true); Object value = f.get(mesh);
            String text;
            if (value instanceof int[] array) text = Arrays.toString(array);
            else if (value instanceof float[] array) {
                int[] bits = new int[array.length];
                for (int i = 0; i < bits.length; i++) bits[i] = Float.floatToRawIntBits(array[i]);
                text = Arrays.toString(bits);
            } else text = String.valueOf(value);
            rows.add(name + "=" + text);
        }
        Object edges = type.getMethod("getEdges").invoke(mesh);
        List<String> edgeRows = new ArrayList<>();
        for (Object edge : (List<?>) edges) {
            Class<?> e = edge.getClass();
            edgeRows.add(e.getMethod("getIndex1").invoke(edge) + ":" + e.getMethod("getIndex2").invoke(edge)
                    + ":" + e.getMethod("getType").invoke(edge));
        }
        rows.add("edges=" + edgeRows); rows.sort(String::compareTo);
        return rows.toString();
    }
    private static String points(Class<?> mesh, Object source) throws Exception {
        List<String> rows = new ArrayList<>();
        for (Object point : (List<?>) mesh.getMethod("getPoints").invoke(source)) {
            Class<?> type = point.getClass();
            rows.add(Float.floatToRawIntBits((float) type.getMethod("getX").invoke(point)) + ":"
                    + Float.floatToRawIntBits((float) type.getMethod("getY").invoke(point)));
        }
        return rows.toString();
    }
    private static Object copy(Class<?> mesh, Object source, float[] positions) throws Exception {
        Class<?> coord = Class.forName("com.live2d.doc.CoordType");
        Object companion = coord.getField("Companion").get(null);
        Object space = companion.getClass().getMethod("c").invoke(companion);
        Object guid = mesh.getMethod("getMeshGuid").invoke(source);
        Object result = mesh.getConstructor(guid.getClass()).newInstance(guid);
        mesh.getMethod("initByEditableMesh", mesh, float[].class, boolean.class, coord)
                .invoke(result, source, positions, true, space);
        return result;
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("record path and original/agent leg required");
        boolean agent = args[1].equals("agent");
        Class<?> mesh = Class.forName("com.live2d.graphics3d.editableMesh.GEditableMesh2");
        if (mesh.getClassLoader() != ClassLoader.getSystemClassLoader()) throw new AssertionError("real system SDK required");
        Method admission = null, reservation = null;
        if (agent) {
            if (Class.forName("dev.turboism.bootstrap.TurboismAgent").getClassLoader() != null)
                throw new AssertionError("canonical premain required");
            Class<?> bridge = Class.forName("dev.turboism.adapter.cubism.mesh.LazyTriangulationEdgeBridge");
            admission = bridge.getDeclaredMethod("enterMesh", Class.class); admission.setAccessible(true);
            Class<?> table = Class.forName("dev.turboism.adapter.cubism.mesh.NativeMeshEdgeTable");
            reservation = table.getDeclaredMethod("reservedBytes"); reservation.setAccessible(true);
        }
        Class<?> pt = Class.forName("com.live2d.graphics3d.editableMesh.MPoint2$PointType");
        Class<?> edge = Class.forName("com.live2d.graphics3d.editableMesh.MEdge");
        Class<?> edgeType = Class.forName("com.live2d.graphics3d.editableMesh.MEdge$EdgeType");
        Object normalEdge = edgeType.getField("NORMAL").get(null);
        Object normal = pt.getField("NORMAL").get(null);
        Method add = mesh.getMethod("addPoint", float.class, float.class, pt, long.class);
        Method connect = Arrays.stream(mesh.getMethods()).filter(m -> m.getName().equals("autoConnect")).findFirst().orElseThrow();
        Class<?> progressType = connect.getParameterTypes()[2];
        Object progress = Proxy.newProxyInstance(mesh.getClassLoader(), new Class<?>[] {progressType}, (proxy, method, values) -> {
            if (method.getDeclaringClass() == Object.class) {
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName().equals("equals")) return proxy == values[0];
                return "owned native copy progress";
            }
            Class<?> type = method.getReturnType();
            if (type == boolean.class) return false;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == float.class) return 0f;
            if (type == double.class) return 0d;
            return null;
        });
        List<String> records = new ArrayList<>(); int refreshes = 0, failures = 0;
        for (int count : new int[] {3, 8, 32, 248, 312, 376}) for (int shape = 0; shape < 2; shape++) {
            if (agent) {
                AutoCloseable lease = (AutoCloseable) admission.invoke(null, mesh);
                if (lease == null) throw new AssertionError("actual mesh admission required");
                lease.close();
            }
            Object source = mesh.getConstructor().newInstance();
            Random random = new Random(900100L + count + shape);
            for (int i = 0; i < count; i++) {
                double angle = 2 * Math.PI * i / count;
                float radius = shape == 0 ? 10f : 9f + random.nextFloat();
                add.invoke(source, (float) Math.cos(angle) * radius, (float) Math.sin(angle) * radius, normal, (long) i);
            }
            @SuppressWarnings("unchecked")
            List<Object> edges = (List<Object>) field(mesh, "_edges").get(source);
            for (int i = 0; i < count; i++) {
                int next = (i + 1) % count;
                edges.add(edge.getConstructor(int.class, int.class, edgeType)
                        .newInstance(Math.min(i, next), Math.max(i, next), normalEdge));
            }
            connect.invoke(source, true, true, progress);
            int[] generated = (int[]) field(mesh, "cached_indices").get(source);
            if (generated == null || generated.length == 0 || generated.length % 3 != 0)
                throw new AssertionError("nonempty complete native triangle fixture required");
            mesh.getMethod("setEdgeUpdated").invoke(source);
            String originalPoints = points(mesh, source), before = state(mesh, source);
            // Same getGlPositions + same-coordinate copy seam used by ac. No selection transform/context is claimed.
            float[] positions = ((float[]) mesh.getMethod("getGlPositions").invoke(source)).clone();
            String afterPositions = state(mesh, source), copyState = "", failure = "";
            try { copyState = state(mesh, copy(mesh, source, positions)); }
            catch (InvocationTargetException problem) {
                Throwable cause = problem.getCause(); failure = cause.getClass().getName() + ":" + cause.getMessage(); failures++;
            }
            String afterCopy = state(mesh, source);
            int[] refreshed = (int[]) field(mesh, "cached_indices").get(source);
            if (refreshed == null || refreshed.length == 0 || refreshed.length % 3 != 0)
                throw new AssertionError("nonempty complete copied source triangles required");
            if (!originalPoints.equals(points(mesh, source))) throw new AssertionError("source positions changed");
            if (!afterPositions.equals(afterCopy)) refreshes++;
            if (failure.isEmpty()) {
                copy(mesh, source, positions);
                if (!afterCopy.equals(state(mesh, source))) throw new AssertionError("second same-coordinate copy changed source again");
            }
            records.add(count + "|" + shape + "|before=" + before + "|afterPositions=" + afterPositions
                    + "|afterCopy=" + afterCopy + "|copy=" + copyState + "|failure=" + failure);
            if (agent && (int) reservation.invoke(null) != 0) throw new AssertionError("table reservation retained");
        }
        if (refreshes == 0 || failures != 0) throw new AssertionError("native source refresh not reproduced or copy failed");
        Files.write(Path.of(args[0]), records, StandardCharsets.UTF_8);
        if (!java.awt.GraphicsEnvironment.isHeadless() || java.awt.Frame.getFrames().length != 0)
            throw new AssertionError("no Editor allowed");
        System.out.printf("NATIVE_CACHE_COPY_PASS fixtures=12 sourceRefreshes=%d failures=%d actionContext=NOT_REPRODUCED hostGain=UNPROVEN%n", refreshes, failures);
        System.exit(0);
    }
}
