package dev.turboism.validation.tlindex.diagnostic;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.*;

/** Executes generated own fixtures only, never copied official classes. */
public final class FreshEdgeBytecodeSelfCheck implements Opcodes {
    private static final String P = "com/live2d/graphics3d/editableMesh/triangulation/";
    private static final String SELF = FreshEdgeBytecodeSelfCheck.class.getName().replace('.', '/');
    private static int checks;
    public static final class Point {}
    public static final class Edge {
        public static int created;
        public static int throwAt;
        public final int ordinal;
        public Edge(Point a, Point b) {
            ordinal = ++created;
            if (ordinal == throwAt) throw new IllegalStateException("constructor failure");
        }
    }
    private FreshEdgeBytecodeSelfCheck() {}
    private static void require(boolean b) { checks++; if (!b) throw new AssertionError("check " + checks); }

    private static byte[] edge() {
        ClassWriter w = new ClassWriter(0);
        w.visit(V17, ACC_PUBLIC | ACC_FINAL, P + "j", null, "java/lang/Object", null);
        w.visitEnd();
        return w.toByteArray(); // metadata input only; executable Edge is our own class above
    }
    private static byte[] fixture() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(V17, ACC_PUBLIC | ACC_FINAL, P + "h", null, "java/lang/Object", null);
        w.visitField(ACC_PUBLIC | ACC_STATIC, "mask", "I", null, null).visitEnd();
        w.visitField(ACC_PUBLIC | ACC_STATIC, "sink", "Ljava/util/ArrayList;", null, null).visitEnd();
        MethodVisitor c = w.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        c.visitCode(); c.visitVarInsn(ALOAD, 0);
        c.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        c.visitInsn(RETURN); c.visitMaxs(0, 0); c.visitEnd();
        MethodVisitor m = w.visitMethod(ACC_PUBLIC | ACC_FINAL, "c", "()V", null, null);
        m.visitCode(); m.visitTypeInsn(NEW, "java/util/ArrayList"); m.visitInsn(DUP);
        m.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        m.visitVarInsn(ASTORE, 7);
        for (int i = 0; i < 3; i++) {
            m.visitTypeInsn(NEW, P + "j"); m.visitInsn(DUP);
            m.visitInsn(ACONST_NULL); m.visitInsn(ACONST_NULL);
            m.visitMethodInsn(INVOKESPECIAL, P + "j", "<init>", "(L" + P + "TriPoint;L" + P + "TriPoint;)V", false);
            m.visitVarInsn(ASTORE, 12 + i);
            Label skip = new Label();
            m.visitFieldInsn(GETSTATIC, P + "h", "mask", "I");
            m.visitIntInsn(BIPUSH, 1 << i); m.visitInsn(IAND); m.visitJumpInsn(IFEQ, skip);
            m.visitVarInsn(ALOAD, 7); m.visitVarInsn(ALOAD, 12 + i);
            m.visitMethodInsn(INVOKEVIRTUAL, "java/util/ArrayList", "contains", "(Ljava/lang/Object;)Z", false);
            m.visitJumpInsn(IFNE, skip);
            m.visitVarInsn(ALOAD, 7); m.visitVarInsn(ALOAD, 12 + i);
            m.visitMethodInsn(INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", false);
            m.visitInsn(POP); m.visitLabel(skip);
        }
        m.visitVarInsn(ALOAD, 7); m.visitFieldInsn(PUTSTATIC, P + "h", "sink", "Ljava/util/ArrayList;");
        m.visitInsn(RETURN); m.visitMaxs(0, 0); m.visitEnd(); w.visitEnd();
        return w.toByteArray();
    }
    private static Class<?> ownClass(byte[] bytes) {
        ClassWriter w = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(w, new Remapper() {
            @Override public String map(String name) {
                if (!name.startsWith(P)) return name;
                return SELF + "$" + switch (name.substring(P.length())) {
                    case "h" -> "Host";
                    case "j" -> "Edge";
                    case "TriPoint" -> "Point";
                    default -> throw new AssertionError(name);
                };
            }
        }), 0);
        byte[] own = w.toByteArray();
        return new ClassLoader(FreshEdgeBytecodeSelfCheck.class.getClassLoader()) {
            Class<?> define() { return defineClass(null, own, 0, own.length); }
        }.define();
    }
    private static String execute(Class<?> type, int mask, int throwAt) throws Exception {
        Edge.created = 0; Edge.throwAt = throwAt;
        type.getField("mask").setInt(null, mask); type.getField("sink").set(null, null);
        String failure = "none";
        try { type.getMethod("c").invoke(type.getConstructor().newInstance()); }
        catch (InvocationTargetException e) { failure = e.getCause().getClass().getName(); }
        Object sink = type.getField("sink").get(null);
        String result = sink == null ? "null" : ((List<?>) sink).stream()
                .map(v -> Integer.toString(((Edge) v).ordinal)).toList().toString();
        return failure + ":" + Edge.created + ":" + result;
    }
    private static byte[] write(ClassNode n) { ClassWriter w = new ClassWriter(0); n.accept(w); return w.toByteArray(); }
    private static ClassNode read(byte[] b) { ClassNode n = new ClassNode(); new ClassReader(b).accept(n, 0); return n; }
    private static void reject(byte[] host, byte[] edge) {
        try { FreshEdgeBytecodePrototype.patchShape(host, edge); throw new AssertionError("accepted bad shape"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        byte[] source = fixture(); byte[] edge = edge();
        byte[] patched = FreshEdgeBytecodePrototype.patchShape(source, edge);
        Class<?> before = ownClass(source); Class<?> after = ownClass(patched);
        for (int mask = 0; mask < 8; mask++) for (int fail = 0; fail <= 3; fail++) {
            require(execute(before, mask, fail).equals(execute(after, mask, fail)));
        }
        require(execute(after, 7, 0).equals("none:3:[1, 2, 3]"));
        reject(patched, edge); // duplicate patch / missing sites
        ClassNode wrongEdge = read(edge); wrongEdge.access &= ~ACC_FINAL; reject(source, write(wrongEdge));
        wrongEdge = read(edge); wrongEdge.superName = "java/util/ArrayList"; reject(source, write(wrongEdge));
        wrongEdge = read(edge); wrongEdge.methods.add(new MethodNode(ACC_PUBLIC, "equals", "(Ljava/lang/Object;)Z", null, null));
        reject(source, write(wrongEdge));
        ClassNode wrongHost = read(source); wrongHost.name += "Unknown"; reject(write(wrongHost), edge);
        wrongHost = read(source);
        MethodNode target = wrongHost.methods.stream().filter(m -> m.name.equals("c")).findFirst().orElseThrow();
        target.access = ACC_PUBLIC; reject(write(wrongHost), edge);
        wrongHost = read(source); target = wrongHost.methods.stream().filter(m -> m.name.equals("c")).findFirst().orElseThrow();
        for (AbstractInsnNode n : target.instructions) {
            if (n instanceof MethodInsnNode m && m.name.equals("contains")) {
                ((VarInsnNode) m.getPrevious()).var = 11; break;
            }
        }
        reject(write(wrongHost), edge);
        try { FreshEdgeBytecodePrototype.patch(source, edge); throw new AssertionError("unreviewed bytes accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        System.out.println("Fresh-edge generated-bytecode checks PASS: " + checks + "; officialClassesExecuted=false");
    }
}
