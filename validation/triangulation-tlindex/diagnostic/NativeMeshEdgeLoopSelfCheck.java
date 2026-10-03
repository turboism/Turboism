import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.jar.JarFile;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import dev.turboism.adapter.cubism.mesh.NativeMeshEdgeTableOwnedAccess;

/** Functional owned-loop differential experiment. Boxed research map; no gain claim. */
public final class NativeMeshEdgeLoopSelfCheck {
    private static int checks;
    private NativeMeshEdgeLoopSelfCheck() { }

    public static final class Control {
        private static final ThreadLocal<Lease> CURRENT = new ThreadLocal<>();
        static boolean enabled;
        static int entries, closes, indexedCalls, nativeCalls, registrations;
        private static final boolean PRIMITIVE = Boolean.getBoolean("turboism.validation.meshPrimitiveTable");
        private Control() { }

        private static final class Lease implements AutoCloseable {
            final Object mesh;
            final List<?> list;
            final Method first, second;
            final Map<Long, Integer> slots;
            Object primitive;
            final Lease previous;
            int size;
            boolean active = true;
            Lease(Object mesh, List<?> list, Method first, Method second) {
                this.mesh = mesh; this.list = list; this.first = first; this.second = second;
                this.previous = CURRENT.get(); this.size = list.size();
                slots = PRIMITIVE ? null : new HashMap<>();
                primitive = PRIMITIVE ? NativeMeshEdgeTableOwnedAccess.reserve(4096) : null;
                if (PRIMITIVE && primitive == null) active = false;
            }
            void register(int index) throws ReflectiveOperationException {
                Object edge = list.get(index);
                if (edge == null || edge.getClass() != first.getDeclaringClass()) {
                    active = false; return;
                }
                int a = (int) first.invoke(edge), b = (int) second.invoke(edge);
                if (primitive != null) {
                    if (!NativeMeshEdgeTableOwnedAccess.putFirst(primitive, a, b, index)) active = false;
                } else slots.putIfAbsent(key(a, b), index);
            }
            void discard() {
                if (slots != null) slots.clear();
                if (primitive != null) NativeMeshEdgeTableOwnedAccess.close(primitive);
                primitive = null; active = false;
            }
            @Override public void close() { CURRENT.set(previous); discard(); closes++; }
        }

        private static long key(int first, int second) {
            return ((long) first << 32) | (second & 0xffffffffL);
        }
        private static Field field(Object mesh, String name) throws ReflectiveOperationException {
            Field f = mesh.getClass().getDeclaredField(name); f.setAccessible(true); return f;
        }
        public static AutoCloseable enter(Object mesh) throws ReflectiveOperationException {
            entries++;
            if (!enabled) return null;
            // Degenerate pairs invoke the native logger, whose downstream callbacks
            // are outside this append-only experiment. Preserve their native path.
            Object cached = field(mesh, "cached_indices").get(mesh);
            if (!(cached instanceof int[] indices) || indices.length % 3 != 0) return null;
            for (int i = 0; i < indices.length; i += 3)
                if (indices[i] == indices[i + 1] || indices[i + 1] == indices[i + 2]
                        || indices[i + 2] == indices[i]) return null;
            Object raw = field(mesh, "_edges").get(mesh);
            if (!(raw instanceof List<?> list) || !raw.getClass().getName().equals(NativeMeshEdgeLoopPrototype.LIST.replace('/', '.')))
                return null;
            Class<?> edge = mesh.getClass().getClassLoader().loadClass("com.live2d.graphics3d.editableMesh.MEdge");
            if (list.size() > 4096) return null;
            Lease lease = new Lease(mesh, list, edge.getMethod("getIndex1"), edge.getMethod("getIndex2"));
            try {
                for (int i = 0; i < lease.size && lease.active; i++) lease.register(i);
            } catch (ReflectiveOperationException | RuntimeException | Error failure) {
                lease.discard();
                throw failure;
            }
            if (!lease.active) { lease.discard(); return null; }
            CURRENT.set(lease);
            return lease;
        }
        public static void leave(AutoCloseable lease) throws Exception { if (lease != null) lease.close(); }
        public static Integer lookup(Object mesh, int first, int second, boolean filtered) throws Throwable {
            Lease lease = CURRENT.get();
            if (lease != null && lease.active && lease.mesh == mesh && !filtered
                    && field(mesh, "_edges").get(mesh) == lease.list && lease.list.size() == lease.size) {
                if (lease.primitive == null) {
                    indexedCalls++;
                    return lease.slots.get(key(first, second));
                }
                int found = NativeMeshEdgeTableOwnedAccess.find(lease.primitive, first, second);
                if (found >= -1) {
                    indexedCalls++;
                    return found < 0 ? null : Integer.valueOf(found);
                }
                lease.active = false;
            }
            nativeCalls++;
            Method nativeLookup = mesh.getClass().getDeclaredMethod("chechExistingEdge_exe", int.class, int.class, boolean.class);
            nativeLookup.setAccessible(true);
            try { return (Integer) nativeLookup.invoke(mesh, first, second, filtered); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
        public static void appended(Object mesh) throws ReflectiveOperationException {
            Lease lease = CURRENT.get();
            if (lease == null || !lease.active || lease.mesh != mesh) return;
            if (field(mesh, "_edges").get(mesh) != lease.list || lease.list.size() != lease.size + 1
                    || lease.size >= 4096) { lease.active = false; return; }
            lease.register(lease.size++); registrations++;
        }
        static void reset(boolean fast) {
            require(CURRENT.get() == null, "no retained prior lease");
            enabled = fast; entries = closes = indexedCalls = nativeCalls = registrations = 0;
        }
        static boolean active() { return CURRENT.get() != null; }
    }

    static final class Loader extends URLClassLoader {
        final Path jar;
        final boolean candidate;
        final boolean complete;
        Loader(Path jar, boolean candidate, boolean assertions) throws Exception {
            this(jar, candidate, assertions, false);
        }
        Loader(Path jar, boolean candidate, boolean assertions, boolean complete) throws Exception {
            super(urls(jar), NativeMeshEdgeLoopSelfCheck.class.getClassLoader());
            this.jar = jar; this.candidate = candidate; this.complete = complete; setDefaultAssertionStatus(assertions);
        }
        private static URL[] urls(Path jar) throws Exception {
            List<URL> paths = new ArrayList<>(); paths.add(jar.toUri().toURL());
            try (var files = java.nio.file.Files.list(jar.getParent())) {
                for (Path path : files.filter(p -> p.toString().endsWith(".jar") && !p.equals(jar)).sorted().toList())
                    paths.add(path.toUri().toURL());
            }
            return paths.toArray(URL[]::new);
        }
        @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!name.equals(NativeMeshEdgeLoopPrototype.MESH.replace('/', '.'))) return super.findClass(name);
            try (JarFile file = new JarFile(jar.toFile())) {
                var entry = file.getJarEntry(NativeMeshEdgeLoopPrototype.MESH + ".class");
                byte[] raw;
                try (InputStream in = file.getInputStream(entry)) { raw = in.readAllBytes(); }
                byte[] transformed = NativeMeshEdgeLoopPrototype.patch(raw, candidate, complete);
                String pkg = name.substring(0, name.lastIndexOf('.'));
                if (getDefinedPackage(pkg) == null) definePackage(pkg, file.getManifest(), jar.toUri().toURL());
                return defineClass(name, transformed, 0, transformed.length,
                        new CodeSource(jar.toUri().toURL(), entry.getCodeSigners()));
            } catch (Exception failure) { throw new ClassNotFoundException(name, failure); }
        }
    }

    private record Result(List<String> edges, int version, String failure, List<String> probes) { }
    private static void require(boolean value, String reason) {
        checks++;
        if (!value) throw new AssertionError(reason);
    }
    private static Field declared(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static String invoke(Method method, Object receiver, Object... args) throws Exception {
        try { return String.valueOf(method.invoke(receiver, args)); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            return "ERROR:" + cause.getClass().getName() + ":" + cause.getMessage();
        }
    }

    private static String untouched(byte[] bytes) throws Exception {
        return untouched(bytes, false);
    }

    static String untouched(byte[] bytes, boolean complete) throws Exception {
        ClassNode type = new ClassNode();
        new ClassReader(bytes).accept(type, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        type.methods.removeIf(m -> m.name.equals("checkExitingTypedEdge") || m.name.equals("addEdge")
                || m.name.equals("ownedAppend") || complete && m.name.equals("autoConnect"));
        ClassWriter writer = new ClassWriter(0); type.accept(writer);
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(writer.toByteArray()));
    }

    private static void refusal(Path jar) throws Exception {
        byte[] raw;
        try (JarFile file = new JarFile(jar.toFile()); InputStream in = file.getInputStream(
                file.getJarEntry(NativeMeshEdgeLoopPrototype.MESH + ".class"))) {
            raw = in.readAllBytes();
        }
        byte[] baseline = NativeMeshEdgeLoopPrototype.patch(raw, false);
        byte[] candidate = NativeMeshEdgeLoopPrototype.patch(raw, true);
        require(untouched(raw).equals(untouched(baseline)) && untouched(raw).equals(untouched(candidate)),
                "all non-target method bodies and metadata preserved after frame/debug normalization");
        boolean refused = false;
        try { NativeMeshEdgeLoopPrototype.patch(candidate, true); }
        catch (IllegalArgumentException expected) { refused = true; }
        require(refused, "double transform declined");
        ClassNode type = new ClassNode(); new ClassReader(raw).accept(type, 0);
        type.methods.stream().filter(m -> m.name.equals("autoConnect")).findFirst().orElseThrow().access &= ~Opcodes.ACC_FINAL;
        ClassWriter writer = new ClassWriter(0); type.accept(writer);
        refused = false;
        try { NativeMeshEdgeLoopPrototype.patch(writer.toByteArray(), true); }
        catch (IllegalArgumentException expected) { refused = true; }
        require(refused, "unknown native definition declined");
    }

    @SuppressWarnings("unchecked")
    private static void leaseControls(Loader loader) throws Exception {
        Class<?> meshClass = loader.loadClass(NativeMeshEdgeLoopPrototype.MESH.replace('/', '.'));
        Class<?> edgeClass = loader.loadClass("com.live2d.graphics3d.editableMesh.MEdge");
        Class<?> type = loader.loadClass("com.live2d.graphics3d.editableMesh.MEdge$EdgeType");
        Object normal = type.getField("NORMAL").get(null);
        Object auto = type.getField("AUTO_TRIANGULATION").get(null);
        Object mesh = meshClass.getConstructor().newInstance(), other = meshClass.getConstructor().newInstance();
        declared(meshClass, "cached_indices").set(mesh, new int[] {0, 1, 2});
        declared(meshClass, "cached_indices").set(other, new int[] {0, 1, 2});
        List<Object> edges = (List<Object>) declared(meshClass, "_edges").get(mesh);
        edges.add(edgeClass.getConstructor(int.class, int.class, type).newInstance(0, 1, auto));
        edges.add(edgeClass.getConstructor(int.class, int.class, type).newInstance(0, 1, normal));
        Control.reset(true);
        try (AutoCloseable lease = Control.enter(mesh)) {
            require(lease != null, "owned control lease accepted");
            require("0".equals(controlLookup(mesh, 0, 1, false)), "first duplicate index");
            require("1".equals(controlLookup(mesh, 0, 1, true)), "filtered native skips auto first duplicate");
            require("null".equals(controlLookup(other, 0, 1, false)), "other mesh falls back");
            try (AutoCloseable nested = Control.enter(other)) {
                require(nested != null && Control.CURRENT.get().mesh == other, "nested ownership");
            }
            require(Control.CURRENT.get().mesh == mesh, "nested close restores original");
            edges.remove(0);
            require("0".equals(controlLookup(mesh, 0, 1, false)), "size-changing unregistered removal falls back");
        }
        require(Control.CURRENT.get() == null && Control.closes == 2, "nested cleanup");
        require(Control.indexedCalls == 1 && Control.nativeCalls == 3, "specific indexed/fallback paths exercised");

        // A deliberately unsupported external edit demonstrates why list identity/size
        // alone cannot serve as production admission. Do not call this a passing equality case.
        edges.clear();
        edges.add(edgeClass.getConstructor(int.class, int.class, type).newInstance(0, 1, normal));
        Control.reset(true);
        try (AutoCloseable lease = Control.enter(mesh)) {
            require(lease != null, "counterexample lease accepted");
            int version = declared(meshClass, "_edge_edit_version").getInt(mesh);
            edges.set(0, edgeClass.getConstructor(int.class, int.class, type).newInstance(2, 3, normal));
            String stale = controlLookup(mesh, 0, 1, false);
            String nativeResult = invoke(meshClass.getMethod("checkExistingEdge", int.class, int.class, boolean.class), mesh, 0, 1, false);
            require("0".equals(stale) && "null".equals(nativeResult), "same-size external edit defeats prototype index");
            require(version == declared(meshClass, "_edge_edit_version").getInt(mesh), "external set also preserves mesh version");
        }
        require(Control.CURRENT.get() == null, "counterexample lease cleaned");

        for (int[] indices : new int[][] {null, {0}, {0, 1, 2, 3}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}}) {
            declared(meshClass, "cached_indices").set(mesh, indices);
            Control.reset(true);
            require(Control.enter(mesh) == null && Control.CURRENT.get() == null,
                    "null/truncated/degenerate suffix must decline before native callback/error path");
        }
    }

    private static String controlLookup(Object mesh, int first, int second, boolean filtered) throws Exception {
        try { return String.valueOf(Control.lookup(mesh, first, second, filtered)); }
        catch (Throwable failure) { throw new Exception("owned control failed", failure); }
    }

    @SuppressWarnings("unchecked")
    private static Result run(Loader loader, int fixture, boolean fast) throws Exception {
        Class<?> meshClass = loader.loadClass(NativeMeshEdgeLoopPrototype.MESH.replace('/', '.'));
        Class<?> edgeClass = loader.loadClass("com.live2d.graphics3d.editableMesh.MEdge");
        Class<?> type = loader.loadClass("com.live2d.graphics3d.editableMesh.MEdge$EdgeType");
        Object mesh = meshClass.getConstructor().newInstance();
        List<Object> edges = (List<Object>) declared(meshClass, "_edges").get(mesh);
        Object[] types = type.getEnumConstants();
        IdentityHashMap<Object, Integer> identities = new IdentityHashMap<>();
        Random random = new Random(790000L + fixture);
        int seedCount = fixture == 0 ? 0 : fixture == 10 ? 4097 : fixture < 8 ? 8 : fixture % 31;
        for (int i = 0; i < seedCount; i++) {
            int a = i % 7, b = a + 1;
            Object edge = edgeClass.getConstructor(int.class, int.class, type).newInstance(a, b, types[i % types.length]);
            edges.add(edge); identities.put(edge, i);
        }
        if (fixture == 1 && !edges.isEmpty()) {
            // Constructor assertions do not normalize storage; an owned final-field mutation exercises literal matching.
            declared(edgeClass, "index1").setInt(edges.get(0), 2);
            declared(edgeClass, "index2").setInt(edges.get(0), 0);
        }
        if (fixture == 2) edges.add(0, null);
        int[] triangles;
        if (fixture == 3) triangles = null;
        else if (fixture == 4) triangles = new int[] {0};
        else if (fixture == 5) triangles = new int[] {0, 1, 2, 3};
        else if (fixture == 6) triangles = new int[] {0, 0, 1};
        else if (fixture == 7) triangles = new int[] {-2, -1, 0};
        else {
            triangles = new int[3 * (fixture == 0 ? 0 : 6 + fixture % 17)];
            for (int i = 0; i < triangles.length; i++) triangles[i] = random.nextInt(12);
            if (fixture >= 11) for (int i = 0; i < triangles.length; i += 3) {
                while (triangles[i + 1] == triangles[i]) triangles[i + 1] = random.nextInt(12);
                while (triangles[i + 2] == triangles[i] || triangles[i + 2] == triangles[i + 1])
                    triangles[i + 2] = random.nextInt(12);
            }
        }
        if (fixture == 8 || fixture == 9) declared(edges.getClass(), "immutable").setBoolean(edges, true);
        declared(meshClass, "cached_indices").set(mesh, triangles);
        Control.reset(fast);
        String failure = invoke(meshClass.getMethod("ownedAppend"), mesh);
        require(Control.CURRENT.get() == null, "success/exception releases thread local");
        if (loader.candidate) require(Control.entries == 1 && Control.closes <= 1, "one bounded entry and at most one close");
        List<String> rows = new ArrayList<>();
        for (Object edge : edges) {
            if (edge == null) { rows.add("null"); continue; }
            rows.add(edgeClass.getMethod("getIndex1").invoke(edge) + ":"
                    + edgeClass.getMethod("getIndex2").invoke(edge) + ":"
                    + edgeClass.getMethod("getType").invoke(edge) + ":"
                    + (identities.containsKey(edge) ? "seed" + identities.get(edge) : "new"));
        }
        List<String> probes = new ArrayList<>();
        Method existing = meshClass.getMethod("checkExistingEdge", int.class, int.class, boolean.class);
        for (boolean filtered : new boolean[] {false, true})
            for (int a = 0; a < 8; a++) probes.add(invoke(existing, mesh, a + 1, a, filtered));
        return new Result(rows, declared(meshClass, "_edge_edit_version").getInt(mesh), failure, probes);
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 3, "three official archive paths");
        int bypasses = 0, fallbacks = 0, appends = 0;
        for (String path : args) for (boolean assertions : new boolean[] {false, true}) {
            int start = checks;
            refusal(Path.of(path));
            try (Loader baseline = new Loader(Path.of(path), false, assertions);
                    Loader candidate = new Loader(Path.of(path), true, assertions)) {
                leaseControls(candidate);
                for (int fixture = 0; fixture < 96; fixture++) {
                    Result expected = run(baseline, fixture, false);
                    Result fallback = run(candidate, fixture, false);
                    require(expected.equals(fallback), "native fallback fixture " + fixture);
                    Result actual = run(candidate, fixture, true);
                    require(expected.equals(actual), "indexed loop fixture " + fixture + " expected=" + expected + " actual=" + actual);
                    bypasses += Control.indexedCalls; fallbacks += Control.nativeCalls; appends += Control.registrations;
                }
            }
            System.out.printf("NATIVE_MESH_EDGE_LOOP_PASS jar=%s assertions=%s fixtures=96 checks=%d%n", path, assertions, checks - start);
        }
        require(bypasses > 0 && fallbacks > 0 && appends > 0, "indexed lookup/native fallback/successful append all exercised");
        System.out.printf("NATIVE_MESH_EDGE_LOOP_FINISHED checks=%d indexed=%d native=%d appends=%d scope=OWNED_SUFFIX_ONLY hostGain=UNPROVEN%n",
                checks, bypasses, fallbacks, appends);
        System.out.println("NATIVE_MESH_EDGE_LOOP_BOUNDARY sameSizeExternalSet=COUNTEREXAMPLE_CONFIRMED productionAdmission=OPEN");
        if (Boolean.getBoolean("turboism.validation.meshPrimitiveTable")) {
            require(NativeMeshEdgeTableOwnedAccess.reservedBytes() == 0, "all primitive reservations released");
            System.out.println("NATIVE_MESH_EDGE_LOOP_PRIMITIVE reservedBytes=0 productionStorage=ACTUAL hostGain=UNPROVEN");
        }
    }
}
