import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.jar.JarFile;
import dev.turboism.adapter.cubism.mesh.NativeMeshEdgeTableOwnedAccess;

/** Complete official autoConnect invocation in isolated owned loaders; no Editor. */
public final class NativeMeshMemoryLargeAutoConnectSelfCheck {
    private static int checks;
    private static int preEntryEdits, immutableFailures, callbackFailures, suffixFailures;
    private static final IllegalStateException CALLBACK_FAILURE = new IllegalStateException("owned progress failure");
    private NativeMeshMemoryLargeAutoConnectSelfCheck() { }
    private record Result(List<String> edges, List<String> points, List<String> caches,
                          List<String> callbacks, int version, String failure) { }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static List<String> caches(Class<?> type, Object mesh) throws Exception {
        List<String> rows = new ArrayList<>();
        for (Field f : type.getDeclaredFields()) {
            if (!f.getName().startsWith("cached_")) continue;
            f.setAccessible(true); Object value = f.get(mesh);
            String text;
            if (value instanceof int[] values) text = Arrays.toString(values);
            else if (value instanceof float[] values) {
                int[] bits = new int[values.length];
                for (int i = 0; i < bits.length; i++) bits[i] = Float.floatToRawIntBits(values[i]);
                text = Arrays.toString(bits);
            } else text = String.valueOf(value);
            rows.add(f.getName() + "=" + text);
        }
        rows.sort(String::compareTo); return rows;
    }

    @SuppressWarnings("unchecked")
    private static Result run(NativeMeshEdgeLoopSelfCheck.Loader loader, int fixture, boolean enabled) throws Exception {
        Class<?> meshClass = loader.loadClass(NativeMeshEdgeLoopPrototype.MESH.replace('/', '.'));
        Class<?> edge = loader.loadClass("com.live2d.graphics3d.editableMesh.MEdge");
        Class<?> point = loader.loadClass("com.live2d.graphics3d.editableMesh.MPoint2");
        Class<?> edgeType = loader.loadClass("com.live2d.graphics3d.editableMesh.MEdge$EdgeType");
        Class<?> pointType = loader.loadClass("com.live2d.graphics3d.editableMesh.MPoint2$PointType");
        Method autoConnect = Arrays.stream(meshClass.getMethods()).filter(m -> m.getName().equals("autoConnect")).findFirst().orElseThrow();
        Class<?> progressType = autoConnect.getParameterTypes()[2];
        Object mesh = meshClass.getConstructor().newInstance();
        Object normal = edgeType.getField("NORMAL").get(null);
        Object normalPoint = pointType.getField("NORMAL").get(null);
        Method addPoint = meshClass.getMethod("addPoint", float.class, float.class, pointType, long.class);
        Random random = new Random(810000L + fixture);
        int count = fixture == 0 ? 0 : fixture == 1 ? 1 : fixture == 2 ? 2
                : fixture >= 30 ? 248 + (fixture % 3) * 64 : 4 + fixture % 17;
        for (int i = 0; i < count; i++) {
            double angle = 2 * Math.PI * i / Math.max(count, 1);
            float x = (float) (Math.cos(angle) * 10), y = (float) (Math.sin(angle) * 10);
            if (fixture % 8 == 3) { x += random.nextFloat(); y += random.nextFloat(); }
            if (fixture % 8 == 4) { x = i; y = 0; }
            if (fixture % 8 == 5) { x = i; y = i * 1.0e-6f; }
            addPoint.invoke(mesh, x, y, normalPoint, (long) i);
        }
        List<Object> edges = (List<Object>) field(meshClass, "_edges").get(mesh);
        // A boundary ring supports both preserveBorder and rebuild modes without UI.
        if (count >= 3) for (int i = 0; i < count; i++) {
            int a = i, b = (i + 1) % count;
            edges.add(edge.getConstructor(int.class, int.class, edgeType).newInstance(Math.min(a, b), Math.max(a, b), normal));
        }
        boolean rebuild = (fixture & 1) == 0;
        boolean preserve = count >= 3 && fixture % 8 != 4 && fixture % 8 != 5 && (fixture & 2) != 0;
        List<String> callbacks = new ArrayList<>();
        int[] callbackCount = {0}; boolean[] changed = {false};
        Object progress = Proxy.newProxyInstance(loader, new Class<?>[] {progressType}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                if (method.getName().equals("toString")) return "owned progress";
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName().equals("equals")) return proxy == args[0];
            }
            require(!NativeMeshEdgeLoopSelfCheck.Control.active(), "progress callbacks execute outside index lease");
            callbacks.add(method.getName() + ":" + method.getReturnType().getName());
            callbackCount[0]++;
            if (fixture % 13 == 7 && callbackCount[0] == 1) throw CALLBACK_FAILURE;
            // Modify the published list after generated indices appear, before suffix entry.
            if (fixture % 13 == 8 && !changed[0] && !edges.isEmpty()
                    && field(meshClass, "cached_indices").get(mesh) != null) {
                edges.set(0, edge.getConstructor(int.class, int.class, edgeType).newInstance(0, count - 1, normal));
                changed[0] = true; callbacks.add("same-size-pre-entry-edit");
            }
            if (fixture % 13 == 9 && field(meshClass, "cached_indices").get(mesh) != null)
                field(edges.getClass(), "immutable").setBoolean(edges, true);
            Class<?> result = method.getReturnType();
            if (result == boolean.class) return false;
            if (result == int.class) return 0;
            if (result == long.class) return 0L;
            if (result == float.class) return 0f;
            if (result == double.class) return 0d;
            return null;
        });
        NativeMeshEdgeLoopSelfCheck.Control.reset(enabled);
        String failure = "";
        try { autoConnect.invoke(mesh, rebuild, preserve, fixture % 13 == 10 ? null : progress); }
        catch (InvocationTargetException problem) {
            Throwable cause = problem.getCause();
            if (fixture % 13 == 7) require(cause == CALLBACK_FAILURE, "callback Throwable identity preserved");
            failure = cause.getClass().getName() + ":" + cause.getMessage();
        }
        require(!NativeMeshEdgeLoopSelfCheck.Control.active(), "complete operation cleans normal/exception lease");
        if (fixture % 13 == 7) require(failure.equals(CALLBACK_FAILURE.getClass().getName() + ":" + CALLBACK_FAILURE.getMessage()),
                "progress failure actually exercised");
        if (fixture % 13 == 10) require(!failure.isEmpty() && NativeMeshEdgeLoopSelfCheck.Control.entries == 0,
                "null progress fails before suffix entry");
        if (loader.candidate) {
            require(NativeMeshEdgeLoopSelfCheck.Control.entries <= 1, "at most one complete suffix entry");
            if (fixture % 13 == 7) require(NativeMeshEdgeLoopSelfCheck.Control.entries == 0,
                    "progress failure precedes index scope");
            if (enabled) {
                if (changed[0]) preEntryEdits++;
                if (fixture % 13 == 7) callbackFailures++;
                if (fixture % 13 == 9 && !failure.isEmpty()) immutableFailures++;
                if (!failure.isEmpty() && NativeMeshEdgeLoopSelfCheck.Control.closes == 1) suffixFailures++;
            }
        }
        List<String> rows = new ArrayList<>();
        for (Object value : edges) rows.add(edge.getMethod("getIndex1").invoke(value) + ":"
                + edge.getMethod("getIndex2").invoke(value) + ":" + edge.getMethod("getType").invoke(value));
        List<String> points = new ArrayList<>();
        for (Object value : (List<?>) meshClass.getMethod("getPoints").invoke(mesh))
            points.add(Float.floatToRawIntBits((float) point.getMethod("getX").invoke(value)) + ":"
                    + Float.floatToRawIntBits((float) point.getMethod("getY").invoke(value)));
        return new Result(rows, points, caches(meshClass, mesh), callbacks,
                field(meshClass, "_edge_edit_version").getInt(mesh), failure);
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 3, "three exact official archive paths");
        int indexed = 0, nativeCalls = 0, appended = 0;
        for (String path : args) for (boolean assertions : new boolean[] {false, true}) {
            int start = checks;
            try (JarFile archive = new JarFile(path); var in = archive.getInputStream(
                    archive.getJarEntry(NativeMeshEdgeLoopPrototype.MESH + ".class"))) {
                byte[] raw = in.readAllBytes();
                byte[] transformed = NativeMeshEdgeLoopPrototype.patch(raw, true, true);
                require(NativeMeshEdgeLoopSelfCheck.untouched(raw, true).equals(
                        NativeMeshEdgeLoopSelfCheck.untouched(transformed, true)), "untargeted full-operation methods/metadata preserved");
                boolean rejected = false;
                try { NativeMeshEdgeLoopPrototype.patch(transformed, true, true); }
                catch (IllegalArgumentException expected) { rejected = true; }
                require(rejected, "complete double weave refused");
            }
            try (NativeMeshEdgeLoopSelfCheck.Loader baseline = new NativeMeshEdgeLoopSelfCheck.Loader(Path.of(path), false, assertions, true);
                    NativeMeshEdgeLoopSelfCheck.Loader candidate = new NativeMeshEdgeLoopSelfCheck.Loader(Path.of(path), true, assertions, true)) {
                for (int fixture = 0; fixture < 48; fixture++) {
                    Result expected = run(baseline, fixture, false);
                    Result fallback = run(candidate, fixture, false);
                    require(expected.equals(fallback), "complete native fallback fixture " + fixture + " expected=" + expected + " actual=" + fallback);
                    Result actual = run(candidate, fixture, true);
                    require(expected.equals(actual), "complete indexed operation fixture " + fixture + " expected=" + expected + " actual=" + actual);
                    indexed += NativeMeshEdgeLoopSelfCheck.Control.indexedCalls;
                    nativeCalls += NativeMeshEdgeLoopSelfCheck.Control.nativeCalls;
                    appended += NativeMeshEdgeLoopSelfCheck.Control.registrations;
                }
            }
            System.out.printf("NATIVE_MESH_AUTOCONNECT_PASS jar=%s assertions=%s fixtures=48 checks=%d%n", path, assertions, checks - start);
        }
        require(indexed > 0 && nativeCalls > 0 && appended > 0, "complete indexed/native/append paths exercised");
        require(preEntryEdits > 0 && immutableFailures > 0 && callbackFailures > 0 && suffixFailures > 0,
                "pre-entry mutation, immutable failure, progress failure and protected suffix exception exercised");
        System.out.printf("NATIVE_MESH_AUTOCONNECT_FINISHED checks=%d indexed=%d native=%d appends=%d scope=OWNED_COMPLETE_OPERATION hostGain=UNPROVEN%n",
                checks, indexed, nativeCalls, appended);
        System.out.printf("NATIVE_MESH_AUTOCONNECT_CONTROLS preEntryEdits=%d immutableFailures=%d callbackFailures=%d suffixFailures=%d%n",
                preEntryEdits, immutableFailures, callbackFailures, suffixFailures);
        if (Boolean.getBoolean("turboism.validation.meshPrimitiveTable")) {
            require(NativeMeshEdgeTableOwnedAccess.reservedBytes() == 0, "all primitive reservations released");
            System.out.println("NATIVE_MESH_AUTOCONNECT_PRIMITIVE reservedBytes=0 productionStorage=ACTUAL hostGain=UNPROVEN");
        }
    }
}
