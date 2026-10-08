package dev.turboism.adapter.cubism.mesh;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Offline execution of the local weave; substituted leases never establish host admission. */
public final class NativeLocalBuilderSelfCheck implements Opcodes {
    private static final String P = "com/live2d/graphics3d/editableMesh/triangulation/";
    private static final String TL = P + "TriangleList", K = P + "k", L = P + "l";
    private static final String CONTROL = NativeLocalBuilderSelfCheck.Controls.class.getName().replace('.', '/');
    private static int checks;
    private NativeLocalBuilderSelfCheck() { }
    private static void require(final boolean value, final String reason) {
        checks++;
        if (!value) throw new AssertionError(reason);
    }

    /** Test-only observations and deterministic lease/getter negative controls. */
    public static final class Controls {
        private static boolean enabled;
        private static int nativeQueries;
        private static int closes;
        private static String failGetter;
        private static final List<String> trace = new ArrayList<>();
        private Controls() { }
        public static AutoCloseable enterBuilder(final Class<?> owner) {
            if (!enabled) return null;
            return () -> closes++;
        }
        public static void leave(final AutoCloseable lease) throws Exception {
            if (lease != null) lease.close();
        }
        public static void query() { nativeQueries++; }
        public static void getter(final String name) {
            trace.add(name);
            if (name.equals(failGetter)) throw new IllegalStateException("owned getter failure " + name);
        }
        private static void reset(final boolean fast) {
            enabled = fast; nativeQueries = 0; closes = 0; failGetter = null; trace.clear();
        }
    }

    private static byte[] instrument(final String name, final byte[] original) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(final String a, final String b) { return "java/lang/Object"; }
        };
        new ClassReader(original).accept(new ClassVisitor(ASM9, writer) {
            @Override public MethodVisitor visitMethod(final int access, final String method,
                    final String descriptor, final String signature, final String[] exceptions) {
                return new MethodVisitor(ASM9, super.visitMethod(access, method, descriptor, signature, exceptions)) {
                    @Override public void visitCode() {
                        super.visitCode();
                        if (name.equals(K) && method.equals("a") && descriptor.equals("(L" + P + "j;Z)Z")) {
                            super.visitMethodInsn(INVOKESTATIC, CONTROL, "query", "()V", false);
                        }
                        if (name.equals(L) && List.of("d", "e", "f").contains(method)
                                && descriptor.equals("()L" + P + "j;")) {
                            super.visitLdcInsn(method);
                            super.visitMethodInsn(INVOKESTATIC, CONTROL, "getter", "(Ljava/lang/String;)V", false);
                        }
                    }
                    @Override public void visitMethodInsn(final int opcode, final String owner,
                            final String called, final String desc, final boolean itf) {
                        super.visitMethodInsn(opcode, name.equals(TL)
                                && owner.equals("dev/turboism/adapter/cubism/mesh/LazyTriangulationEdgeBridge")
                                ? CONTROL : owner, called, desc, itf);
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        return writer.toByteArray();
    }

    private static final class Loader extends URLClassLoader {
        private final Path official;
        Loader(final Path jar) throws Exception {
            super(new URL[] {jar.toUri().toURL(), jar.getParent().resolve("kotlin-stdlib-1.7.21.jar").toUri().toURL()},
                    NativeLocalBuilderSelfCheck.class.getClassLoader());
            official = jar;
        }
        @Override protected Class<?> findClass(final String name) throws ClassNotFoundException {
            final String internal = name.replace('.', '/');
            if (!List.of(TL, K, L).contains(internal)) return super.findClass(name);
            try (JarFile jar = new JarFile(official.toFile(), true)) {
                final var entry = jar.getJarEntry(internal + ".class");
                final byte[] bytes;
                try (InputStream input = jar.getInputStream(entry)) { bytes = input.readAllBytes(); }
                final CodeSigner[] signers = entry.getCodeSigners();
                final String pkg = name.substring(0, name.lastIndexOf('.'));
                if (getDefinedPackage(pkg) == null) {
                    if (jar.getManifest() == null) definePackage(pkg, null, null, null, null, null, null, null);
                    else definePackage(pkg, jar.getManifest(), official.toUri().toURL());
                }
                final byte[] woven = internal.equals(TL)
                        ? TriangleListEdgeBuilderPatcher.patch(new TriangulationEdgeIndexPatcher().patch(bytes)) : bytes;
                final byte[] observed = instrument(internal, woven);
                return defineClass(name, observed, 0, observed.length, new CodeSource(official.toUri().toURL(), signers));
            } catch (Exception failure) { throw new ClassNotFoundException(name, failure); }
        }
    }

    private static List<?> edges(final Object list) throws Exception {
        final Object collection = list.getClass().getMethod("b").invoke(list);
        return List.copyOf((List<?>) collection.getClass().getMethod("a").invoke(collection));
    }

    private static void one(final Path jar) throws Exception {
        try (Loader loader = new Loader(jar)) {
            final Class<?> listType = loader.loadClass(TL.replace('/', '.'));
            final Class<?> point = loader.loadClass((P + "TriPoint").replace('/', '.'));
            final Class<?> triangle = loader.loadClass(L.replace('/', '.'));
            final Object list = listType.getConstructor().newInstance();
            final var field = listType.getDeclaredField("b"); field.setAccessible(true);
            @SuppressWarnings("unchecked")
            final LinkedHashSet<Object> backing = (LinkedHashSet<Object>) field.get(list);
            final Object[] points = new Object[102];
            for (int i = 0; i < points.length; i++) {
                points[i] = point.getConstructor(float.class, float.class, int.class).newInstance((float) i, (float) (i % 7), i);
            }
            for (int i = 0; i < 100; i++) {
                backing.add(triangle.getConstructor(point, point, point).newInstance(points[i], points[i + 1], points[i + 2]));
            }
            require(backing.size() == 100, "native fixture cardinality");
            Controls.reset(false);
            final List<?> expected = edges(list);
            require(Controls.nativeQueries == 300 && Controls.closes == 0, "null lease native path");
            require(expected.size() == 201, "native first-occurrence edge count");
            final List<String> getterTrace = List.copyOf(Controls.trace);
            Controls.reset(true);
            final List<?> actual = edges(list);
            require(Controls.nativeQueries == 0 && Controls.closes == 1, "leased fast path and normal release");
            require(actual.size() == expected.size(), "fast edge count");
            for (int i = 0; i < actual.size(); i++) require(actual.get(i) == expected.get(i), "first physical edge/order");
            require(getterTrace.equals(Controls.trace), "same original getter order");
            for (final String fail : List.of("d", "e", "f")) {
                Controls.reset(false); Controls.failGetter = fail;
                Throwable baseline = null;
                try { edges(list); } catch (InvocationTargetException failure) { baseline = failure.getCause(); }
                final List<String> failureTrace = List.copyOf(Controls.trace);
                Controls.reset(true); Controls.failGetter = fail;
                Throwable candidate = null;
                try { edges(list); } catch (InvocationTargetException failure) { candidate = failure.getCause(); }
                require(baseline instanceof IllegalStateException && candidate instanceof IllegalStateException
                        && baseline.getMessage().equals(candidate.getMessage()), "same getter failure");
                require(failureTrace.equals(Controls.trace), "same getter failure ordering");
                require(Controls.closes == 1 && Controls.nativeQueries == 0, "exceptional lease release");
            }
            backing.clear();
            Controls.reset(true);
            require(edges(list).isEmpty() && Controls.closes == 1 && Controls.nativeQueries == 0, "empty builder release");
            System.out.println("official=" + jar.getParent().getParent().getFileName()
                    + " orderedEdges=201 nativeQueries=300 fastNativeQueries=0 status=PASS");
        }
    }

    public static void main(final String[] arguments) throws Exception {
        require(arguments.length > 0, "official JAR arguments required");
        for (final String jar : arguments) one(Path.of(jar));
        System.out.println("checks=" + checks + " scope=OFFLINE_EXECUTION_WITH_OWNED_LEASE_ONLY liveAdmission=UNPROVEN");
    }
}
