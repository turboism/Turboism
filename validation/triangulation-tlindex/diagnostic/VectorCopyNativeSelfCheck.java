import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Random;
import java.util.jar.JarFile;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Complete native d: common angle lease/guard, original copies vs leased copy elision. */
public final class VectorCopyNativeSelfCheck {
    private static int checks;
    private static final IllegalStateException LIST_FAILURE = new IllegalStateException("owned list failure");
    private VectorCopyNativeSelfCheck() { }
    public static final class Control {
        static boolean enabled;
        static int entries, closes, nativeCalls, guardCalls, bypasses, copies, elisions;
        private Control() { }
        public static AutoCloseable enter() { entries++; return enabled ? () -> closes++ : null; }
        public static void leave(AutoCloseable lease) throws Exception { if (lease != null) lease.close(); }
        public static boolean reject(float cross, float dot) {
            require(nativeCalls > 0, "first angle invocation remains native");
            guardCalls++; boolean rejected = AngleGuardMath.reject(cross, dot);
            if (rejected) bypasses++; return rejected;
        }
        public static void nativeCopy() { copies++; }
        public static void elidedCopy() { elisions++; }
        public static void nativeAngle() { nativeCalls++; }
        static void reset(boolean fast) {
            enabled = fast; entries = closes = nativeCalls = guardCalls = bypasses = copies = elisions = 0;
        }
    }
    private static void require(boolean value, String reason) {
        checks++; if (!value) throw new AssertionError(reason);
    }
    private static final class Loader extends URLClassLoader {
        final Path jar;
        final boolean patched;
        Loader(Path jar, boolean patched, boolean assertions) throws Exception {
            super(new URL[] {jar.toUri().toURL(), jar.getParent().resolve("kotlin-stdlib-1.7.21.jar").toUri().toURL()},
                    VectorCopyNativeSelfCheck.class.getClassLoader());
            this.jar = jar; this.patched = patched; setDefaultAssertionStatus(assertions);
        }
        @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
            String internal = name.replace('.', '/');
            if (!internal.equals(VectorCopyBytecodePrototype.H) && !internal.equals(VectorCopyBytecodePrototype.R))
                return super.findClass(name);
            try (JarFile file = new JarFile(jar.toFile())) {
                var entry = file.getJarEntry(internal + ".class"); byte[] bytes;
                try (InputStream in = file.getInputStream(entry)) { bytes = in.readAllBytes(); }
                CodeSigner[] signers = entry.getCodeSigners();
                String pkg = name.substring(0, name.lastIndexOf('.'));
                if (getDefinedPackage(pkg) == null) definePackage(pkg, file.getManifest(), jar.toUri().toURL());
                if (internal.equals(VectorCopyBytecodePrototype.H)) bytes = VectorCopyBytecodePrototype.patch(bytes, patched);
                if (internal.equals(VectorCopyBytecodePrototype.R)) {
                    ClassWriter out = new ClassWriter(0);
                    new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, out) {
                        @Override public MethodVisitor visitMethod(int access, String called, String descriptor,
                                String signature, String[] exceptions) {
                            MethodVisitor original = super.visitMethod(access, called, descriptor, signature, exceptions);
                            if (!called.equals("a") || !descriptor.equals("(L" + VectorCopyBytecodePrototype.V + ";L"
                                    + VectorCopyBytecodePrototype.V + ";)F")) return original;
                            return new MethodVisitor(Opcodes.ASM9, original) {
                                @Override public void visitCode() {
                                    super.visitCode(); super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                            "VectorCopyNativeSelfCheck$Control", "nativeAngle", "()V", false);
                                }
                            };
                        }
                    }, 0);
                    bytes = out.toByteArray();
                }
                return defineClass(name, bytes, 0, bytes.length, new CodeSource(jar.toUri().toURL(), signers));
            } catch (Exception failure) { throw new ClassNotFoundException(name, failure); }
        }
    }
    private record Result(List<String> triangles, String failure) { }
    private static Result run(Loader loader, float[][] coordinates, boolean fast, boolean failList, boolean mutate) throws Exception {
        String prefix = VectorCopyBytecodePrototype.P.replace('/', '.');
        Class<?> host = loader.loadClass(prefix + "h"), point = loader.loadClass(prefix + "TriPoint");
        Class<?> face = loader.loadClass(prefix + "l");
        Object operation = host.getConstructor().newInstance();
        Object context = host.getMethod("a").invoke(operation);
        Object triangles = context.getClass().getMethod("d").invoke(context);
        ArrayList<Object> points = new ArrayList<>();
        IdentityHashMap<Object, Integer> faceIds = new IdentityHashMap<>();
        for (int i = 0; i < coordinates.length; i++)
            points.add(point.getConstructor(float.class, float.class, int.class).newInstance(coordinates[i][0], coordinates[i][1], i));
        for (int i = 1; i + 1 < points.size(); i++) {
            Object triangle = face.getConstructor(point, point, point).newInstance(points.get(0), points.get(i), points.get(i + 1));
            faceIds.put(triangle, faceIds.size());
            triangles.getClass().getMethod("a", face).invoke(triangles, triangle);
        }
        if (mutate && points.size() >= 3) {
            float x = (float) point.getMethod("getX").invoke(points.get(1));
            point.getMethod("setX", float.class).invoke(points.get(1), x + 0.25f);
            float y = (float) point.getMethod("getY").invoke(points.get(2));
            point.getMethod("setY", float.class).invoke(points.get(2), y - 0.5f);
        }
        List<Object> source = points;
        if (coordinates.length > 0 && coordinates[0][0] == 12345.0f) source = new ArrayList<>(points);
        if (coordinates.length > 0 && coordinates[0][0] == 12345.0f) source.set(1, null);
        if (failList) source = new ArrayList<>(points) {
            private static final long serialVersionUID = 1L;
            @Override public Object get(int index) {
                if (index == 1) throw LIST_FAILURE;
                return super.get(index);
            }
        };
        var field = context.getClass().getDeclaredField("b"); field.setAccessible(true); field.set(context, source);
        Control.reset(fast); String failure = "";
        try { host.getMethod("d").invoke(operation); }
        catch (InvocationTargetException problem) {
            Throwable cause = problem.getCause(); failure = cause.getClass().getName() + ":" + cause.getMessage();
            if (failList) require(cause == LIST_FAILURE, "original fixture Throwable identity");
        }
        require(Control.entries == 1 && Control.closes == (fast ? 1 : 0), "lease released on normal/exception exit");
        if (!fast) require(Control.guardCalls == 0 && Control.bypasses == 0, "null lease native path");
        List<String> output = new ArrayList<>();
        for (Object triangle : (Iterable<?>) triangles) {
            faceIds.computeIfAbsent(triangle, ignored -> faceIds.size());
            StringBuilder row = new StringBuilder().append(faceIds.get(triangle)).append('|');
            for (String getter : new String[] {"a", "b", "c"}) {
                Object vertex = face.getMethod(getter).invoke(triangle);
                row.append(point.getMethod("getIndex").invoke(vertex)).append(':')
                        .append(Float.floatToRawIntBits((float) point.getMethod("getX").invoke(vertex))).append(':')
                        .append(Float.floatToRawIntBits((float) point.getMethod("getY").invoke(vertex))).append(';');
                require(points.stream().anyMatch(candidatePoint -> candidatePoint == vertex), "output endpoints refer to fixture identities");
            }
            output.add(row.toString());
        }
        return new Result(output, failure);
    }
    private static void refusal(Path jar) throws Exception {
        byte[] original;
        try (JarFile file = new JarFile(jar.toFile()); InputStream in = file.getInputStream(file.getJarEntry(VectorCopyBytecodePrototype.H + ".class"))) {
            original = in.readAllBytes();
        }
        byte[] patched = VectorCopyBytecodePrototype.patch(original);
        require(nonTargetFingerprint(original).equals(nonTargetFingerprint(patched)),
                "all non-d algorithms and class/field metadata unchanged");
        ClassNode type = new ClassNode(); new ClassReader(original).accept(type, 0);
        MethodNode method = type.methods.stream().filter(m -> m.name.equals("d") && m.desc.equals("()V")).findFirst().orElseThrow();
        method.access &= ~Opcodes.ACC_FINAL;
        ClassWriter writer = new ClassWriter(0); type.accept(writer);
        boolean refused = false;
        try { VectorCopyBytecodePrototype.patchShape(writer.toByteArray()); }
        catch (IllegalArgumentException expected) { refused = true; }
        require(refused, "wrong d access refused");
        refused = false;
        try { VectorCopyBytecodePrototype.patch(writer.toByteArray()); }
        catch (IllegalArgumentException expected) { refused = true; }
        require(refused, "unknown bytes refused");
        refused = false;
        try { VectorCopyBytecodePrototype.patch(patched); }
        catch (IllegalArgumentException expected) { refused = true; }
        require(refused, "double weave refused");
        type = new ClassNode(); new ClassReader(original).accept(type, 0);
        method = type.methods.stream().filter(m -> m.name.equals("d") && m.desc.equals("()V")).findFirst().orElseThrow();
        MethodInsnNode copy = null;
        for (AbstractInsnNode node : method.instructions) {
            if (node instanceof MethodInsnNode call && call.owner.equals(VectorCopyBytecodePrototype.V)
                    && call.name.equals("<init>") && call.desc.equals("(L" + VectorCopyBytecodePrototype.V + ";)V")) {
                copy = call; break;
            }
        }
        require(copy != null, "copy site present");
        ((MethodInsnNode) copy.getPrevious()).name = "unreviewedMinus";
        writer = new ClassWriter(0); type.accept(writer);
        refused = false;
        try { VectorCopyBytecodePrototype.patchShape(writer.toByteArray()); }
        catch (IllegalArgumentException expected) { refused = true; }
        require(refused, "changed fresh-result call refused");
    }
    private static String nonTargetFingerprint(byte[] bytes) throws Exception {
        ClassNode type = new ClassNode();
        new ClassReader(bytes).accept(type, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        type.methods.removeIf(method -> method.name.equals("d") && method.desc.equals("()V"));
        ClassWriter writer = new ClassWriter(0); type.accept(writer);
        return VectorCopyBytecodePrototype.sha(writer.toByteArray());
    }
    private static void one(Path jar, boolean assertions) throws Exception {
        int start = checks, bypasses = 0, guardCalls = 0, copiesElided = 0;
        refusal(jar);
        try (Loader baseline = new Loader(jar, false, assertions); Loader candidate = new Loader(jar, true, assertions)) {
            require(baseline.loadClass("kotlin._Assertions").getField("ENABLED").getBoolean(null) == assertions
                    && candidate.loadClass("kotlin._Assertions").getField("ENABLED").getBoolean(null) == assertions,
                    "actual native Kotlin assertion mode");
            Random random = new Random(0x560a11L);
            for (int fixture = 0; fixture < 128; fixture++) {
                int size = fixture == 0 ? 0 : 4 + fixture % 8;
                float[][] xy = new float[size][2];
                for (int i = 0; i < size; i++) {
                    xy[i][0] = random.nextFloat() * 20.0f - 10.0f;
                    xy[i][1] = random.nextFloat() * 20.0f - 10.0f;
                    if (fixture % 8 == 1) { xy[i][0] = i; xy[i][1] = 0.0f; }
                    if (fixture % 8 == 2) { xy[i][0] = i; xy[i][1] = i * 1.0e-6f; }
                    if (fixture % 8 == 3) { xy[i][0] = i; xy[i][1] = (i & 1) == 0 ? -0.0f : Float.MIN_VALUE; }
                }
                if (fixture == 5) xy[2][0] = Float.NaN;
                if (fixture == 8) xy[0][0] = 12345.0f;
                if (fixture == 6) xy[2][1] = Float.POSITIVE_INFINITY;
                boolean fail = fixture == 7;
                boolean mutate = fixture % 8 == 4;
                Result expected = run(baseline, xy, false, fail, mutate);
                int expectedCalls = Control.nativeCalls, expectedCopies = Control.copies;
                require(Control.elisions == 0 && (!expected.failure().isEmpty() || expectedCopies == 2 * expectedCalls), "baseline native copies observed");
                Result fallback = run(candidate, xy, false, fail, mutate);
                require(expected.equals(fallback) && expectedCalls == Control.nativeCalls, "complete null-lease native output/count");
                require(Control.elisions == 0 && Control.copies == expectedCopies, "null lease original copy path");
                Result leasedBaseline = run(baseline, xy, true, fail, mutate);
                int baselineCalls = Control.nativeCalls, baselineGuards = Control.guardCalls,
                        baselineBypasses = Control.bypasses, baselineCopies = Control.copies;
                Result actual = run(candidate, xy, true, fail, mutate);
                require(leasedBaseline.equals(actual) && Control.nativeCalls == baselineCalls
                        && Control.guardCalls == baselineGuards && Control.bypasses == baselineBypasses,
                        "vector elision alone preserves leased angle behavior");
                require(Control.copies == 0 && Control.elisions == baselineCopies,
                        "exact two outer copies removed while existing lease held");
                require(expected.equals(actual), "complete leased native output and failure signature");
                require(Control.nativeCalls + Control.bypasses == expectedCalls, "only proven rejections bypass native angle");
                if (expectedCalls > 0) require(Control.nativeCalls > 0, "first angle stays native");
                if (fixture % 8 == 1) require(Control.guardCalls <= 8 && Control.bypasses == 0,
                        "all-collinear misses stop at bounded eight attempts");
                copiesElided += Control.elisions;
                bypasses += Control.bypasses; guardCalls += Control.guardCalls;
            }
        }
        require(bypasses > 0 && guardCalls > bypasses, "actual bypass and native warm fallback exercised");
        System.out.printf("FULL_VECTOR_COPY_D_PASS jar=%s assertions=%s fixtures=128 checks=%d guardCalls=%d bypasses=%d copiesElided=%d%n",
                jar, assertions, checks - start, guardCalls, bypasses, copiesElided);
    }
    public static void main(String[] arguments) throws Exception {
        require(arguments.length > 0, "official jars required");
        for (String path : arguments) for (boolean assertions : new boolean[] {false, true}) one(Path.of(path), assertions);
        System.out.println("FULL_VECTOR_COPY_D_FINISHED checks=" + checks + " scope=OWNED_LEASE_ONLY hostGain=UNPROVEN");
    }
}
