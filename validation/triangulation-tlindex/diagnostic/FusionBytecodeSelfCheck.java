package dev.turboism.validation.tlindex.diagnostic;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.*;

/** Executes only generated own fixtures under JVM verification, never official classes. */
public final class FusionBytecodeSelfCheck implements Opcodes {
    private static final String P = "com/live2d/graphics3d/editableMesh/triangulation/";
    private static final String SELF = FusionBytecodeSelfCheck.class.getName().replace('.', '/');
    private static final String DESC = "(L" + P + "l;L" + P + "l;L" + P + "j;)Ljava/util/List;";
    private static int checks;

    public static final class Tri { }
    public static final class Edge { }
    public static final class Switch {
        public boolean debug;
        public boolean b() { return debug; }
    }
    public static final class Config {
        public static final Switch a = new Switch();
    }
    public static final class TList {
        public final LinkedHashSet<Tri> contents = new LinkedHashSet<>();
        public int containsCalls;
        public int debugAdds;
        public boolean c(Tri tri) { containsCalls++; return contents.contains(tri); }
        public boolean a(Tri tri) {
            if (Config.a.b()) debugAdds++;
            return contents.add(tri);
        }
    }

    private static byte[] fixture() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, P + "h", null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC, "sink", "L" + P + "TriangleList;", null, null).visitEnd();
        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode(); ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(RETURN); ctor.visitMaxs(0, 0); ctor.visitEnd();
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_FINAL, "a", DESC, null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, P + "h", "sink", "L" + P + "TriangleList;");
        mv.visitVarInsn(ASTORE, 4);
        mv.visitInsn(ACONST_NULL); mv.visitVarInsn(ASTORE, 5);
        mv.visitInsn(ACONST_NULL); mv.visitVarInsn(ASTORE, 6);
        mv.visitVarInsn(ALOAD, 1); mv.visitVarInsn(ASTORE, 7);
        mv.visitVarInsn(ALOAD, 2); mv.visitVarInsn(ASTORE, 8);
        Label first = new Label();
        mv.visitJumpInsn(GOTO, first); mv.visitLabel(first);
        for (int local : new int[] {7, 8}) {
            Label skip = new Label();
            mv.visitVarInsn(ALOAD, 4); mv.visitVarInsn(ALOAD, local);
            mv.visitMethodInsn(INVOKEVIRTUAL, P + "TriangleList", "c", "(L" + P + "l;)Z", false);
            mv.visitJumpInsn(IFNE, skip);
            mv.visitVarInsn(ALOAD, 4); mv.visitVarInsn(ALOAD, local);
            mv.visitMethodInsn(INVOKEVIRTUAL, P + "TriangleList", "a", "(L" + P + "l;)Z", false);
            mv.visitInsn(POP); mv.visitLabel(skip);
        }
        mv.visitInsn(ACONST_NULL); mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0); mv.visitEnd(); cw.visitEnd();
        return cw.toByteArray();
    }

    private static Class<?> ownClass(byte[] bytes) {
        ClassWriter cw = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(cw, new Remapper() {
            @Override public String map(String name) {
                if (!name.startsWith(P)) return name;
                return SELF + "$" + switch (name.substring(P.length())) {
                    case "h" -> "Host";
                    case "TriangleList" -> "TList";
                    case "l" -> "Tri";
                    case "j" -> "Edge";
                    case "c" -> "Config";
                    case "c$a" -> "Switch";
                    default -> throw new AssertionError(name);
                };
            }
        }), 0);
        byte[] own = cw.toByteArray();
        return new ClassLoader(FusionBytecodeSelfCheck.class.getClassLoader()) {
            Class<?> define() { return defineClass(null, own, 0, own.length); }
        }.define();
    }

    private static void require(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("check " + checks);
    }

    private static void reject(byte[] bytes) {
        try {
            FusionBytecodePrototype.patchShape(bytes);
            throw new AssertionError("shape accepted");
        } catch (IllegalArgumentException expected) { checks++; }
    }

    public static void main(String[] args) throws Exception {
        byte[] original = fixture();
        byte[] patched = FusionBytecodePrototype.patchShape(original);
        Class<?> base = ownClass(original);
        Class<?> fused = ownClass(patched);
        for (boolean debug : new boolean[] {false, true}) {
            Config.a.debug = debug;
            TList a = new TList(); TList b = new TList();
            base.getField("sink").set(null, a); fused.getField("sink").set(null, b);
            Object hostA = base.getConstructor().newInstance();
            Object hostB = fused.getConstructor().newInstance();
            Method callA = base.getMethod("a", Tri.class, Tri.class, Edge.class);
            Method callB = fused.getMethod("a", Tri.class, Tri.class, Edge.class);
            Tri x = new Tri(); Tri y = new Tri();
            for (int round = 0; round < 3; round++) {
                require(callA.invoke(hostA, x, y, null) == null);
                require(callB.invoke(hostB, x, y, null) == null);
                require(a.contents.size() == b.contents.size());
                Object[] left = a.contents.toArray(); Object[] right = b.contents.toArray();
                for (int i = 0; i < left.length; i++) require(left[i] == right[i]);
                require(a.debugAdds == b.debugAdds);
            }
            require(a.containsCalls == 6);
            require(b.containsCalls == (debug ? 6 : 0));
            require(b.debugAdds == (debug ? 2 : 0));
        }
        try {
            FusionBytecodePrototype.patch(original);
            throw new AssertionError("fixture bypassed hash pin");
        } catch (IllegalArgumentException expected) { checks++; }
        for (int mutation = 0; mutation < 4; mutation++) {
            ClassNode type = new ClassNode();
            new ClassReader(original).accept(type, 0);
            MethodNode method = type.methods.stream().filter(m -> m.desc.equals(DESC)).findFirst().orElseThrow();
            for (AbstractInsnNode node : method.instructions) {
                if (node instanceof MethodInsnNode call && call.owner.equals(P + "TriangleList") && call.name.equals("c")) {
                    if (mutation == 0) call.name = "unknown";
                    if (mutation == 1) ((VarInsnNode) node.getPrevious()).var = 6;
                    if (mutation == 2) ((JumpInsnNode) node.getNext()).setOpcode(IFEQ);
                    if (mutation == 3) method.access = ACC_PUBLIC;
                    break;
                }
            }
            ClassWriter out = new ClassWriter(0); type.accept(out); reject(out.toByteArray());
        }
        System.out.println("FUSION_BYTECODE_SELF_CHECK PASS checks=" + checks);
    }
}
