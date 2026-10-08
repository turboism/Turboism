import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.jar.JarFile;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Complete native 53 h.a(TriangleList,k) with owned fixtures and nullable lease. */
public final class BorrowedEdgeNativeSelfCheck {
    private static int checks;
    private static final IllegalStateException LIST_FAILURE = new IllegalStateException("owned native lookup iterator failure");
    private BorrowedEdgeNativeSelfCheck() { }

    public static final class Control {
        static boolean enabled;
        static int entries, closes, nativeCalls, bypasses;
        private Control() { }
        public static AutoCloseable enter() { entries++; return enabled ? () -> closes++ : null; }
        public static void leave(AutoCloseable lease) throws Exception { if (lease != null) lease.close(); }
        public static void nativeAttempt() { nativeCalls++; }
        public static void bypass() { bypasses++; }
        static void reset(boolean fast) {
            enabled = fast; entries = closes = nativeCalls = bypasses = 0;
        }
    }

    private static void require(boolean value, String reason) {
        checks++;
        if (!value) throw new AssertionError(reason);
    }

    private static final class Loader extends URLClassLoader {
        final Path jar;
        final int mode; // 0 pristine; 1 common native-call observer; 2 observer plus candidate.
        Loader(Path jar, int mode, boolean assertions) throws Exception {
            super(new URL[] {jar.toUri().toURL(), jar.getParent().resolve("kotlin-stdlib-1.7.21.jar").toUri().toURL()},
                    BorrowedEdgeNativeSelfCheck.class.getClassLoader());
            this.jar = jar; this.mode = mode;
            setDefaultAssertionStatus(assertions);
        }
        @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!name.equals(BorrowedEdgeBytecodePrototype.H.replace('/', '.')) || mode == 0)
                return super.findClass(name);
            try (JarFile file = new JarFile(jar.toFile())) {
                var entry = file.getJarEntry(BorrowedEdgeBytecodePrototype.H + ".class");
                byte[] bytes;
                try (InputStream in = file.getInputStream(entry)) { bytes = in.readAllBytes(); }
                bytes = BorrowedEdgeBytecodePrototype.patch(bytes, mode == 2);
                String pkg = name.substring(0, name.lastIndexOf('.'));
                if (getDefinedPackage(pkg) == null) definePackage(pkg, file.getManifest(), jar.toUri().toURL());
                return defineClass(name, bytes, 0, bytes.length,
                        new CodeSource(jar.toUri().toURL(), entry.getCodeSigners()));
            } catch (Exception failure) {
                throw new ClassNotFoundException(name, failure);
            }
        }
    }

    private record Result(List<String> input, List<String> returned, String failure) { }

    private static List<String> snapshot(Object triangles, Class<?> face, Class<?> point,
            IdentityHashMap<Object, Integer> pointIds, IdentityHashMap<Object, Integer> faceIds) throws Exception {
        List<String> rows = new ArrayList<>();
        if (triangles == null) return rows;
        for (Object triangle : (Iterable<?>) triangles) {
            require(faceIds.containsKey(triangle), "no substituted triangle identities");
            StringBuilder row = new StringBuilder().append(faceIds.get(triangle)).append('|');
            for (String getter : new String[] {"a", "b", "c"}) {
                Object vertex = face.getMethod(getter).invoke(triangle);
                require(pointIds.containsKey(vertex), "no substituted endpoint identities");
                row.append(pointIds.get(vertex)).append(':').append(point.getMethod("getIndex").invoke(vertex)).append(':')
                        .append(Float.floatToRawIntBits((float) point.getMethod("getX").invoke(vertex))).append(':')
                        .append(Float.floatToRawIntBits((float) point.getMethod("getY").invoke(vertex))).append(';');
            }
            rows.add(row.toString());
        }
        return rows;
    }

    private static Result run(Loader loader, int fixture, boolean fast) throws Exception {
        String p = BorrowedEdgeBytecodePrototype.P.replace('/', '.');
        Class<?> host = loader.loadClass(p + "h"), point = loader.loadClass(p + "TriPoint");
        Class<?> face = loader.loadClass(p + "l"), list = loader.loadClass(p + "TriangleList");
        Class<?> edge = loader.loadClass(p + "j"), collection = loader.loadClass(p + "k");
        Object operation = host.getConstructor().newInstance();
        Object triangles = list.getConstructor().newInstance();
        Object edges = collection.getConstructor().newInstance();
        IdentityHashMap<Object, Integer> pointIds = new IdentityHashMap<>(), faceIds = new IdentityHashMap<>();
        ArrayList<Object> points = new ArrayList<>();
        Random random = new Random(590000L + fixture);
        int size = fixture == 0 ? 0 : fixture < 8 ? 4 : 4 + fixture % 9;
        for (int i = 0; i < size; i++) {
            double angle = 2 * Math.PI * i / size;
            float x = (float) Math.cos(angle) * 10, y = (float) Math.sin(angle) * 10;
            if (fixture >= 8 && fixture % 9 == 1) { x += random.nextFloat(); y += random.nextFloat(); }
            if (fixture >= 8 && fixture % 9 == 2) { x = i; y = 0; }
            if (fixture >= 8 && fixture % 9 == 3) { x = i; y = i * 1.0e-6f; }
            if (fixture >= 8 && fixture % 9 == 4) { x = i; y = (i & 1) == 0 ? -0.0f : Float.MIN_VALUE; }
            if (fixture >= 8 && fixture % 9 == 5 && i == 2) x = Float.NaN;
            if (fixture >= 8 && fixture % 9 == 6 && i == 2) y = Float.POSITIVE_INFINITY;
            Object vertex = point.getConstructor(float.class, float.class, int.class).newInstance(x, y, i);
            pointIds.put(vertex, pointIds.size()); points.add(vertex);
        }
        for (int i = 1; i + 1 < size; i++) {
            Object triangle = face.getConstructor(point, point, point).newInstance(points.get(0), points.get(i), points.get(i + 1));
            faceIds.put(triangle, faceIds.size());
            list.getMethod("a", face).invoke(triangles, triangle);
        }
        if (fixture >= 8 && fixture % 9 == 7 && size > 2) {
            point.getMethod("setX", float.class).invoke(points.get(1), 0.0f);
            point.getMethod("setY", float.class).invoke(points.get(2), -0.0f);
        }
        @SuppressWarnings("unchecked")
        ArrayList<Object> nativeEdges = (ArrayList<Object>) collection.getMethod("a").invoke(edges);
        if (size > 0) {
            int count = fixture < 8 ? 1 : 1 + fixture % 7;
            for (int i = 0; i < count; i++) {
                float y = fixture < 8 ? 0.5f : -8 + i * 2;
                Object a = point.getConstructor(float.class, float.class, int.class).newInstance(-20.0f, y, 1000 + i * 2);
                Object b = point.getConstructor(float.class, float.class, int.class).newInstance(20.0f, y, 1001 + i * 2);
                nativeEdges.add(edge.getConstructor(point, point).newInstance(a, b));
            }
            if (fixture >= 8 && fixture % 9 == 8) {
                nativeEdges.add(edge.getConstructor(point, point).newInstance(points.get(0), points.get(1)));
            }
        }
        boolean subclass = fixture == 2 || fixture == 3;
        if (subclass) {
            final boolean inject = fixture == 3;
            ArrayList<Object> replacement = new ArrayList<>(nativeEdges) {
                private static final long serialVersionUID = 1L;
                @Override public Iterator<Object> iterator() {
                    if (inject) {
                        for (StackTraceElement frame : Thread.currentThread().getStackTrace())
                            if (frame.getClassName().equals(p + "k") && frame.getMethodName().equals("a"))
                                throw LIST_FAILURE;
                    }
                    return super.iterator();
                }
            };
            var field = collection.getDeclaredField("a"); field.setAccessible(true); field.set(edges, replacement);
        }
        if (fixture == 4) nativeEdges.add(0, null);
        var method = host.getDeclaredMethod("a", list, collection); method.setAccessible(true);
        Control.reset(fast);
        Object returned = null;
        String failure = "";
        try {
            returned = method.invoke(operation, fixture == 5 ? null : triangles, fixture == 6 ? null : edges);
        } catch (InvocationTargetException problem) {
            Throwable cause = problem.getCause();
            failure = cause.getClass().getName() + ":" + cause.getMessage();
            if (fixture == 3) require(cause == LIST_FAILURE, "native injected Throwable identity");
        }
        if (fixture == 3) require(!failure.isEmpty(), "native lookup failure exercised");
        if (loader.mode == 2) {
            require(Control.entries == 1 && Control.closes == (fast ? 1 : 0), "normal/error lease cleanup");
            if (!fast || subclass) require(Control.bypasses == 0, "null/subclass native fallback");
            if (subclass) require(Control.nativeCalls > 0, "subclass fallback actually reaches duplicate native lookup");
        }
        return new Result(snapshot(triangles, face, point, pointIds, faceIds),
                snapshot(returned, face, point, pointIds, faceIds), failure);
    }

    private static String nonTarget(byte[] bytes) throws Exception {
        ClassNode type = new ClassNode();
        new ClassReader(bytes).accept(type, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        type.methods.removeIf(m -> m.name.equals("a") && m.desc.equals(BorrowedEdgeBytecodePrototype.DESC));
        ClassWriter writer = new ClassWriter(0); type.accept(writer);
        return BorrowedEdgeBytecodePrototype.sha(writer.toByteArray());
    }

    private static void refusal(Path jar) throws Exception {
        byte[] bytes;
        try (JarFile file = new JarFile(jar.toFile());
                InputStream in = file.getInputStream(file.getJarEntry(BorrowedEdgeBytecodePrototype.H + ".class"))) {
            bytes = in.readAllBytes();
        }
        byte[] patched = BorrowedEdgeBytecodePrototype.patch(bytes, true);
        require(nonTarget(bytes).equals(nonTarget(patched)), "all non-target code/class metadata preserved");
        boolean declined = false;
        try { BorrowedEdgeBytecodePrototype.patch(patched, true); }
        catch (IllegalArgumentException expected) { declined = true; }
        require(declined, "double weave refuses");
        ClassNode type = new ClassNode(); new ClassReader(bytes).accept(type, 0);
        MethodNode method = type.methods.stream().filter(m -> m.name.equals("a") && m.desc.equals(BorrowedEdgeBytecodePrototype.DESC))
                .findFirst().orElseThrow();
        method.access &= ~Opcodes.ACC_FINAL;
        ClassWriter writer = new ClassWriter(0); type.accept(writer);
        declined = false;
        try { BorrowedEdgeBytecodePrototype.patchShape(writer.toByteArray(), true); }
        catch (IllegalArgumentException expected) { declined = true; }
        require(declined, "changed access refuses");
        type = new ClassNode(); new ClassReader(bytes).accept(type, 0);
        method = type.methods.stream().filter(m -> m.name.equals("a") && m.desc.equals(BorrowedEdgeBytecodePrototype.DESC))
                .findFirst().orElseThrow();
        for (AbstractInsnNode node : method.instructions) {
            if (node instanceof MethodInsnNode call && call.owner.equals(BorrowedEdgeBytecodePrototype.K)
                    && call.name.equals("a") && call.desc.equals("(L" + BorrowedEdgeBytecodePrototype.J + ";Z)Z")) {
                AbstractInsnNode mode = node.getPrevious();
                while (mode.getOpcode() < 0) mode = mode.getPrevious();
                method.instructions.set(mode, new InsnNode(Opcodes.ICONST_1));
                break;
            }
        }
        writer = new ClassWriter(0); type.accept(writer);
        declined = false;
        try { BorrowedEdgeBytecodePrototype.patchShape(writer.toByteArray(), true); }
        catch (IllegalArgumentException expected) { declined = true; }
        require(declined, "changed membership mode refuses");
        declined = false;
        try { BorrowedEdgeBytecodePrototype.patch(writer.toByteArray(), true); }
        catch (IllegalArgumentException expected) { declined = true; }
        require(declined, "unknown complete raw bytes refuse");
    }

    private static void one(Path jar, boolean assertions) throws Exception {
        int start = checks, bypasses = 0, nativeCalls = 0;
        refusal(jar);
        try (Loader pristine = new Loader(jar, 0, assertions); Loader baseline = new Loader(jar, 1, assertions);
                Loader candidate = new Loader(jar, 2, assertions)) {
            for (int fixture = 0; fixture < 128; fixture++) {
                Result expected = run(pristine, fixture, false);
                Result observed = run(baseline, fixture, false);
                int expectedCalls = Control.nativeCalls;
                require(expected.equals(observed), "common observer preserves pristine complete results/errors");
                Result fallback = run(candidate, fixture, false);
                require(expected.equals(fallback) && Control.nativeCalls == expectedCalls, "complete null-lease path");
                Result actual = run(candidate, fixture, true);
                require(expected.equals(actual), "complete leased result/order/identity/error signature");
                require(Control.nativeCalls + Control.bypasses == expectedCalls, "only duplicate lookup is skipped");
                bypasses += Control.bypasses; nativeCalls += Control.nativeCalls;
            }
        }
        require(bypasses > 0 && nativeCalls > 0, "bypass and fallback both exercised");
        System.out.printf("FULL_BORROWED_NATIVE_PASS jar=%s assertions=%s fixtures=128 checks=%d nativeCalls=%d bypasses=%d%n",
                jar, assertions, checks - start, nativeCalls, bypasses);
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 3, "5203 refusal and two 53 exact jars required");
        boolean declined = false;
        try (JarFile file = new JarFile(args[0]);
                InputStream in = file.getInputStream(file.getJarEntry(BorrowedEdgeBytecodePrototype.H + ".class"))) {
            try { BorrowedEdgeBytecodePrototype.patch(in.readAllBytes(), true); }
            catch (IllegalArgumentException expected) { declined = true; }
        }
        require(declined, "5203 must refuse unsupported input");
        for (int index = 1; index < args.length; index++)
            for (boolean assertions : new boolean[] {false, true}) one(Path.of(args[index]), assertions);
        System.out.println("FULL_BORROWED_NATIVE_FINISHED checks=" + checks + " scope=OWNED_ONLY hostGain=UNPROVEN");
    }
}
