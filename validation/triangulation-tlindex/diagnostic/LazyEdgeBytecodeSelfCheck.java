package dev.turboism.validation.tlindex.diagnostic;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.*;

/** Executes freshly generated owned class bodies only, never official Cubism bodies. */
public final class LazyEdgeBytecodeSelfCheck implements Opcodes {
    private static final String SELF = LazyEdgeBytecodeSelfCheck.class.getName().replace('.', '/');
    private static final String P = LazyEdgeBytecodePrototype.P;
    private static final String H = LazyEdgeBytecodePrototype.H, J = LazyEdgeBytecodePrototype.J;
    private static final String T = LazyEdgeBytecodePrototype.T, L = LazyEdgeBytecodePrototype.L;
    private static final String R = LazyEdgeBytecodePrototype.R, V = LazyEdgeBytecodePrototype.V;
    private static int checks;

    private LazyEdgeBytecodeSelfCheck() {}
    public static class Vec {
        public final float x, y;
        public Vec(float x, float y) { this.x = x; this.y = y; }
    }
    public static final class Point extends Vec {
        public final int index;
        public Point(int index, float x, float y) { super(x, y); this.index = index; }
        public int getIndex() { return index; }
    }
    public static final class Assertions { public static boolean ENABLED; private Assertions() {} }
    public static final class Edge {
        public final Point first, second;
        public Edge(Point first, Point second) {
            Support.constructors++;
            if (Support.constructors == Support.constructorThrowAt) throw new IllegalStateException("ctor probe");
            Objects.requireNonNull(first, "first"); Objects.requireNonNull(second, "second");
            this.first = first; this.second = second;
            boolean distinct = first.getIndex() != second.getIndex();
            if (Assertions.ENABLED && !distinct) throw new AssertionError("Assertion failed");
        }
        public Point a() { return first; }
        public Point b() { return second; }
    }
    public static final class Triangle {
        public Point first, second, third;
        public Triangle(Point first, Point second, Point third) {
            this.first = first; this.second = second; this.third = third;
        }
        public Point a() { return first; }
        public Point b() { return second; }
        public Point c() { return third; }
    }
    public static final class Geometry {
        public static final Geometry a = new Geometry();
        public Vec a(Edge first, Edge second) {
            Support.pairCalls++;
            Objects.requireNonNull(first, "constraint"); Objects.requireNonNull(second, "edge");
            return a(first.a(), first.b(), second.a(), second.b());
        }
        public Vec a(Vec first, Vec second, Vec third, Vec fourth) {
            Support.intersections++;
            Support.trace.add("I" + (1 + (Support.intersections - 1) % 3));
            if (Support.intersections == Support.intersectionThrowAt)
                throw new IllegalStateException("intersection probe");
            // A controllable owned primitive, shared by both fixture legs.
            // Numeric/native algorithm equivalence is not inferred from this fixture.
            int slot = (Support.intersections - 1) % 3;
            return (Support.hitMask & (1 << slot)) == 0 ? null : new Vec(
                    first.x + second.x + third.x + fourth.x,
                    first.y + second.y + third.y + fourth.y);
        }
    }
    public static final class Support {
        public static Edge constraint;
        public static Triangle[] inputs;
        public static Point extraFirst, extraSecond;
        public static ArrayList<Edge> output;
        public static int constructors, constructorThrowAt, intersections, intersectionThrowAt, pairCalls;
        public static int hitMask, blockMask, predicateThrowAt, predicates;
        public static List<String> trace;
        private Support() {}
        public static void notNull(Object value, String message) { Objects.requireNonNull(value, message); }
        public static boolean blocked(Edge constraint, Edge candidate) {
            Objects.requireNonNull(constraint); Objects.requireNonNull(candidate);
            predicates++;
            int slot = Math.floorMod(candidate.first.index, 3);
            trace.add("P" + (slot + 1));
            if (predicates == predicateThrowAt) throw new IllegalStateException("predicate probe");
            return (blockMask & (1 << slot)) != 0;
        }
    }

    private static void require(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message + " check=" + checks);
    }
    private static void a(MethodVisitor m, int local) { m.visitVarInsn(ALOAD, local); }
    private static void ctor(MethodVisitor m, int target, int ordinal) {
        String[] first = {"a", "b", "c"}, second = {"b", "c", "a"};
        Label line = new Label(); m.visitLabel(line); m.visitLineNumber(300 + ordinal, line);
        m.visitTypeInsn(NEW, J); m.visitInsn(DUP); a(m, 11);
        m.visitMethodInsn(INVOKEVIRTUAL, L, first[ordinal], "()L" + T + ";", false); a(m, 11);
        m.visitMethodInsn(INVOKEVIRTUAL, L, second[ordinal], "()L" + T + ";", false);
        m.visitMethodInsn(INVOKESPECIAL, J, "<init>", LazyEdgeBytecodePrototype.CTOR, false);
        m.visitVarInsn(ASTORE, target);
    }
    private static void flag(MethodVisitor m, int edge, int target) {
        m.visitFieldInsn(GETSTATIC, R, "a", "L" + R + ";"); a(m, 9); a(m, edge);
        m.visitMethodInsn(INVOKEVIRTUAL, R, "a", LazyEdgeBytecodePrototype.PAIR, false);
        Label none = new Label(), done = new Label(); m.visitJumpInsn(IFNULL, none);
        m.visitInsn(ICONST_1); m.visitJumpInsn(GOTO, done); m.visitLabel(none);
        m.visitInsn(ICONST_0); m.visitLabel(done); m.visitVarInsn(ISTORE, target);
    }
    private static void append(MethodVisitor m, int edge) {
        a(m, 7); a(m, edge); m.visitMethodInsn(INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", false);
        m.visitInsn(POP);
    }
    private static byte[] fixture() { return fixture(false); }
    private static byte[] fixture(boolean inline) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(V17, ACC_PUBLIC | ACC_FINAL, H, null, "java/lang/Object", null);
        w.visitSource("OwnedHost.java", null);
        MethodVisitor init = w.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode(); a(init, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN); init.visitMaxs(0, 0); init.visitEnd();
        MethodVisitor predicate = w.visitMethod(ACC_PRIVATE | ACC_FINAL, "a", "(L" + J + ";L" + J + ";)Z", null, null);
        predicate.visitCode(); a(predicate, 1); a(predicate, 2);
        predicate.visitMethodInsn(INVOKESTATIC, SELF + "$Support", "blocked", "(L" + J + ";L" + J + ";)Z", false);
        predicate.visitInsn(IRETURN); predicate.visitMaxs(0, 0); predicate.visitEnd();
        MethodVisitor m = w.visitMethod(ACC_PUBLIC | ACC_FINAL, "c", "()V", null, null); m.visitCode();
        m.visitTypeInsn(NEW, "java/util/ArrayList"); m.visitInsn(DUP);
        m.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false); m.visitVarInsn(ASTORE, 7);
        m.visitFieldInsn(GETSTATIC, SELF + "$Support", "constraint", "L" + J + ";");
        m.visitInsn(DUP); m.visitLdcInsn("constraint");
        m.visitMethodInsn(INVOKESTATIC, "kotlin/jvm/internal/Intrinsics", "checkNotNullExpressionValue",
                "(Ljava/lang/Object;Ljava/lang/String;)V", false); m.visitVarInsn(ASTORE, 9);
        m.visitFieldInsn(GETSTATIC, SELF + "$Support", "inputs", "[L" + L + ";"); m.visitVarInsn(ASTORE, 1);
        m.visitInsn(ICONST_0); m.visitVarInsn(ISTORE, 2);
        Label loop = new Label(), end = new Label(); m.visitLabel(loop);
        m.visitVarInsn(ILOAD, 2); a(m, 1); m.visitInsn(ARRAYLENGTH); m.visitJumpInsn(IF_ICMPGE, end);
        a(m, 1); m.visitVarInsn(ILOAD, 2); m.visitInsn(AALOAD); m.visitVarInsn(ASTORE, 11);
        for (int s = 0; s < 3; s++) ctor(m, 12 + s, s);
        for (int s = 0; s < 3; s++) flag(m, 12 + s, 15 + s);
        for (int s = 0; s < 3; s++) {
            Label skip = new Label(); m.visitVarInsn(ILOAD, 15 + s); m.visitJumpInsn(IFEQ, skip);
            if (inline) {
                // Owned 5203-style inlined stencil: reuse locals18..21 and
                // observe native-shaped edge/index getters before our stand-in.
                a(m, 0); m.visitVarInsn(ASTORE, 18); m.visitInsn(ICONST_0); m.visitVarInsn(ISTORE, 19);
                a(m, 9); m.visitVarInsn(ASTORE, 20); m.visitInsn(ICONST_0); m.visitVarInsn(ISTORE, 21);
                a(m, 20); m.visitMethodInsn(INVOKEVIRTUAL, J, "a", "()L" + T + ";", false);
                m.visitMethodInsn(INVOKEVIRTUAL, T, "getIndex", "()I", false);
                a(m, 12 + s); m.visitVarInsn(ASTORE, 20); m.visitInsn(ICONST_0); m.visitVarInsn(ISTORE, 21);
                a(m, 20); m.visitMethodInsn(INVOKEVIRTUAL, J, "a", "()L" + T + ";", false);
                m.visitMethodInsn(INVOKEVIRTUAL, T, "getIndex", "()I", false); m.visitInsn(POP2);
                a(m, 9); a(m, 12 + s);
                m.visitMethodInsn(INVOKESTATIC, SELF + "$Support", "blocked", "(L" + J + ";L" + J + ";)Z", false);
            } else {
                a(m, 0); a(m, 9); a(m, 12 + s);
                m.visitMethodInsn(INVOKESPECIAL, H, "a", "(L" + J + ";L" + J + ";)Z", false);
            }
            m.visitJumpInsn(IFNE, skip); a(m, 7); a(m, 12 + s);
            m.visitMethodInsn(INVOKEVIRTUAL, "java/util/ArrayList", "contains", "(Ljava/lang/Object;)Z", false);
            m.visitJumpInsn(IFNE, skip); append(m, 12 + s); m.visitLabel(skip);
        }
        m.visitIincInsn(2, 1); m.visitJumpInsn(GOTO, loop); m.visitLabel(end);
        Label fourthLine = new Label(); m.visitLabel(fourthLine); m.visitLineNumber(400, fourthLine);
        m.visitTypeInsn(NEW, J); m.visitInsn(DUP);
        m.visitFieldInsn(GETSTATIC, SELF + "$Support", "extraFirst", "L" + T + ";");
        m.visitFieldInsn(GETSTATIC, SELF + "$Support", "extraSecond", "L" + T + ";");
        m.visitMethodInsn(INVOKESPECIAL, J, "<init>", LazyEdgeBytecodePrototype.CTOR, false); m.visitVarInsn(ASTORE, 12);
        m.visitFieldInsn(GETSTATIC, R, "a", "L" + R + ";"); a(m, 9); a(m, 12);
        m.visitMethodInsn(INVOKEVIRTUAL, R, "a", LazyEdgeBytecodePrototype.PAIR, false); m.visitInsn(POP);
        append(m, 12); a(m, 7); m.visitFieldInsn(PUTSTATIC, SELF + "$Support", "output", "Ljava/util/ArrayList;");
        m.visitInsn(RETURN); m.visitMaxs(0, 0); m.visitEnd(); w.visitEnd(); return w.toByteArray();
    }

    private static byte[] definition(String name) {
        byte[] platform = LazyEdgeBytecodePrototype.platformBytes(name);
        if (platform != null) return platform;
        if (!List.of(H, J, T, L, R, V).contains(name)) throw new IllegalArgumentException("unknown owned metadata " + name);
        ClassWriter w = new ClassWriter(0);
        w.visit(V17, ACC_PUBLIC | ACC_FINAL, name, null, name.equals(T) ? V : "java/lang/Object", null);
        w.visitEnd(); return w.toByteArray();
    }
    private static Class<?> defineOwned(byte[] bytes) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(writer, new Remapper() {
            @Override public String map(String name) {
                if (name.equals("kotlin/_Assertions")) return SELF + "$Assertions";
                if (name.equals("kotlin/jvm/internal/Intrinsics")) return SELF + "$Support";
                if (name.equals(H)) return SELF + "$Host";
                if (name.equals(J)) return SELF + "$Edge";
                if (name.equals(T)) return SELF + "$Point";
                if (name.equals(L)) return SELF + "$Triangle";
                if (name.equals(R)) return SELF + "$Geometry";
                if (name.equals(V)) return SELF + "$Vec";
                return name;
            }
            @Override public String mapMethodName(String owner, String name, String descriptor) {
                if (owner.equals("kotlin/jvm/internal/Intrinsics")) return "notNull";
                return name;
            }
        }), 0);
        byte[] owned = writer.toByteArray();
        return new ClassLoader(LazyEdgeBytecodeSelfCheck.class.getClassLoader()) {
            Class<?> define() { return defineClass(null, owned, 0, owned.length); }
        }.define();
    }
    private record Result(Throwable failure, List<String> trace, List<Edge> output,
                          int constructors, int intersections, int pairCalls) {}
    private static Result run(Class<?> host, Triangle[] input, int hit, int block, boolean assertions,
            int constructorThrow, int intersectionThrow, int predicateThrow) throws Exception {
        Support.inputs = input; Support.hitMask = hit; Support.blockMask = block;
        Support.constructors = Support.intersections = Support.pairCalls = Support.predicates = 0;
        Support.constructorThrowAt = constructorThrow; Support.intersectionThrowAt = intersectionThrow;
        Support.predicateThrowAt = predicateThrow; Support.output = null;
        Support.trace = new ArrayList<>(); Assertions.ENABLED = assertions;
        Throwable failure = null;
        try { host.getMethod("c").invoke(host.getConstructor().newInstance()); }
        catch (InvocationTargetException expected) { failure = expected.getCause(); }
        return new Result(failure, List.copyOf(Support.trace), Support.output == null ? null : List.copyOf(Support.output),
                Support.constructors, Support.intersections, Support.pairCalls);
    }
    private static List<String> algorithmFailureFrames(Throwable error) {
        if (error == null) return List.of();
        List<String> result = new ArrayList<>();
        for (StackTraceElement frame : error.getStackTrace()) {
            result.add(frame.toString());
            if (frame.getClassName().equals(LazyEdgeBytecodeSelfCheck.class.getName() + "$Host")
                    && frame.getMethodName().equals("c")) return result;
        }
        throw new AssertionError("no owned host failure frame");
    }
    private static void equivalent(Result before, Result after) {
        require(before.trace.equals(after.trace), "calculation/predicate order");
        require(before.intersections == after.intersections, "intersection count");
        require((before.failure == null) == (after.failure == null), "exception presence");
        if (before.failure != null) {
            require(before.failure.getClass() == after.failure.getClass()
                    && Objects.equals(before.failure.getMessage(), after.failure.getMessage()), "exception type/message");
            require(algorithmFailureFrames(before.failure).equals(algorithmFailureFrames(after.failure)), "algorithm stack and source line");
            require(before.output == null && after.output == null, "published output after failure");
        } else {
            require(before.output.size() == after.output.size(), "ordered output size");
            IdentityHashMap<Edge, Boolean> identities = new IdentityHashMap<>();
            for (int s = 0; s < before.output.size(); s++) {
                Edge a = before.output.get(s), b = after.output.get(s);
                require(a.first == b.first && a.second == b.second, "ordered endpoint identities");
                require(identities.put(b, Boolean.TRUE) == null, "fresh duplicate edge identity");
            }
        }
    }
    private static void pair(Class<?> before, Class<?> after, Triangle[] faces, int hit, int block,
            boolean assertions) throws Exception {
        equivalent(run(before, faces, hit, block, assertions, 0, 0, 0),
                   run(after, faces, hit, block, assertions, 0, 0, 0));
    }
    private static byte[] rewrite(ClassNode n) {
        ClassWriter w = new ClassWriter(0); n.accept(w); return w.toByteArray();
    }
    private static ClassNode read(byte[] bytes) {
        ClassNode n = new ClassNode(); new ClassReader(bytes).accept(n, 0); return n;
    }
    private static void reject(byte[] bytes) {
        try { LazyEdgeBytecodePrototype.patchShape(bytes, LazyEdgeBytecodeSelfCheck::definition);
            throw new AssertionError("bad stencil accepted");
        } catch (IllegalArgumentException expected) { require(true, "shape refused"); }
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 1) throw new IllegalArgumentException("optional new owned-fixture directory");
        byte[] original = fixture();
        byte[] patched = LazyEdgeBytecodePrototype.patchShape(original, LazyEdgeBytecodeSelfCheck::definition);
        if (args.length == 1) {
            Path out = Path.of(args[0]); Files.createDirectory(out);
            Files.write(out.resolve("original-owned.class"), original);
            Files.write(out.resolve("patched-owned.class"), patched);
            byte[] inline = fixture(true);
            Files.write(out.resolve("original-inline-owned.class"), inline);
            Files.write(out.resolve("patched-inline-owned.class"),
                    LazyEdgeBytecodePrototype.patchShape(inline, LazyEdgeBytecodeSelfCheck::definition));
        }
        Class<?> before = defineOwned(original), after = defineOwned(patched);
        Point a = new Point(0, 0f, 0f), b = new Point(1, 4f, 0f), c = new Point(2, 0f, 4f);
        Triangle face = new Triangle(a, b, c);
        Support.constraint = new Edge(new Point(-1, 2f, -1f), new Point(-2, 2f, 5f));
        Support.extraFirst = new Point(-3, 8f, 9f); Support.extraSecond = new Point(-4, 3f, 1f);
        for (int hit = 0; hit < 8; hit++) for (int block = 0; block < 8; block++) {
            pair(before, after, new Triangle[] {face, face, face}, hit, block, true);
            Result nativeRun = run(before, new Triangle[] {face, face, face}, hit, block, true, 0, 0, 0);
            Result lazyRun = run(after, new Triangle[] {face, face, face}, hit, block, true, 0, 0, 0);
            require(nativeRun.constructors == 10, "three per scan plus fourth construction");
            require(lazyRun.constructors == 4 + 2 * Integer.bitCount(hit), "lazy constructions follow intersections");
            require(nativeRun.pairCalls == 10 && lazyRun.pairCalls == 4, "cold/fourth pair overload unchanged");
        }
        pair(before, after, new Triangle[0], 7, 0, true);
        pair(before, after, new Triangle[] {face}, 7, 0, true);
        for (int s = 0; s < 3; s++) {
            Point[] points = {a, b, c}; points[s] = null;
            Triangle invalid = new Triangle(points[0], points[1], points[2]);
            pair(before, after, new Triangle[] {invalid}, 0, 0, true);
            pair(before, after, new Triangle[] {face, invalid}, 0, 0, true);
        }
        for (int s = 0; s < 3; s++) {
            Point[] points = {a, b, c}; points[s] = new Point(points[(s + 1) % 3].index, 7f, 8f);
            Triangle duplicate = new Triangle(points[0], points[1], points[2]);
            pair(before, after, new Triangle[] {duplicate}, 0, 0, true);
            pair(before, after, new Triangle[] {face, duplicate}, 0, 0, true);
            pair(before, after, new Triangle[] {face, duplicate}, 7, 0, false);
        }
        for (int fail = 1; fail <= 3; fail++) {
            equivalent(run(before, new Triangle[] {face}, 7, 0, true, fail, 0, 0),
                       run(after, new Triangle[] {face}, 7, 0, true, fail, 0, 0));
            equivalent(run(before, new Triangle[] {face}, 7, 0, true, 0, fail, 0),
                       run(after, new Triangle[] {face}, 7, 0, true, 0, fail, 0));
        }
        for (int fail = 1; fail <= 6; fail++) {
            equivalent(run(before, new Triangle[] {face, face}, 7, 0, true, 0, 0, fail),
                       run(after, new Triangle[] {face, face}, 7, 0, true, 0, 0, fail));
        }
        Random random = new Random(0x50);
        for (int n = 0; n < 100; n++) {
            Triangle[] faces = new Triangle[1 + random.nextInt(12)];
            for (int f = 0; f < faces.length; f++) faces[f] = new Triangle(
                    new Point(3 * f, Float.intBitsToFloat(random.nextInt()), random.nextFloat()),
                    new Point(3 * f + 1, random.nextFloat(), Float.intBitsToFloat(random.nextInt())),
                    new Point(3 * f + 2, random.nextFloat(), random.nextFloat()));
            pair(before, after, faces, random.nextInt(8), random.nextInt(8), (n & 1) == 0);
        }
        face.first = new Point(6, Float.NaN, -0f); face.second = new Point(7, Float.POSITIVE_INFINITY, Float.MIN_VALUE);
        pair(before, after, new Triangle[] {face, face}, 7, 0, true);
        Point savedExtra = Support.extraFirst;
        Support.extraFirst = null;
        pair(before, after, new Triangle[] {face, face}, 7, 0, true);
        Support.extraFirst = new Point(Support.extraSecond.index, 0f, 0f);
        pair(before, after, new Triangle[] {face, face}, 7, 0, true);
        Support.extraFirst = savedExtra;
        Edge savedConstraint = Support.constraint; Support.constraint = null;
        pair(before, after, new Triangle[] {face, face}, 7, 0, true); Support.constraint = savedConstraint;
        byte[] inline = fixture(true);
        Class<?> inlineBefore = defineOwned(inline), inlineAfter = defineOwned(
                LazyEdgeBytecodePrototype.patchShape(inline, LazyEdgeBytecodeSelfCheck::definition));
        for (int hit = 0; hit < 8; hit++) for (int block = 0; block < 8; block++)
            pair(inlineBefore, inlineAfter, new Triangle[] {face, face, face}, hit, block, true);
        for (int s = 0; s < 3; s++) {
            Point[] points = {a, b, c}; points[s] = null;
            pair(inlineBefore, inlineAfter, new Triangle[] {face,
                    new Triangle(points[0], points[1], points[2])}, 0, 0, true);
            points = new Point[] {a, b, c}; points[s] = new Point(points[(s + 1) % 3].index, 7f, 8f);
            Triangle duplicate = new Triangle(points[0], points[1], points[2]);
            pair(inlineBefore, inlineAfter, new Triangle[] {face, duplicate}, 0, 0, true);
            pair(inlineBefore, inlineAfter, new Triangle[] {face, duplicate}, 7, 0, false);
        }
        reject(patched);
        ClassNode bad = read(original); bad.name += "Unknown"; reject(rewrite(bad));
        bad = read(original); MethodNode method = bad.methods.stream().filter(m -> m.name.equals("c")).findFirst().orElseThrow();
        method.access = ACC_PUBLIC; reject(rewrite(bad));
        bad = read(original); method = bad.methods.stream().filter(m -> m.name.equals("c")).findFirst().orElseThrow();
        for (AbstractInsnNode n : method.instructions) if (n instanceof MethodInsnNode m && m.owner.equals(R)) {
            m.desc = "()Ljava/lang/Object;"; break;
        }
        reject(rewrite(bad));
        Path fake = Files.createTempFile("owned-lazy-edge-", ".jar"), output = Path.of(fake + ".output");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(fake))) {
                zip.putNextEntry(new ZipEntry(H + ".class")); zip.write(original); zip.closeEntry();
            }
            try { LazyEdgeBytecodePrototype.main(new String[] {fake.toString(), output.toString()});
                throw new AssertionError("unreviewed JAR admitted");
            } catch (IllegalArgumentException expected) {
                require(!Files.exists(output), "unknown bytes created artifact");
            }
        } finally { Files.deleteIfExists(fake); }
        System.out.println("LAZY_EDGE_GENERATED_BYTECODE_SELFCHECK PASS checks=" + checks
                + " officialClassesExecuted=false nativeGeometryExecuted=false");
    }
}
