package dev.turboism.adapter.cubism.mesh;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import org.objectweb.asm.*;

/** Executes only generated own fixtures under JVM verification, never official classes. */
public final class TriangulationMembershipPatcherTest implements Opcodes {
    private static final String P = "com/live2d/graphics3d/editableMesh/triangulation/";
    private static final String SELF = TriangulationMembershipPatcherTest.class.getName().replace('.', '/');
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

    private static String mapped(String text) {
        if (text == null) return null;
        String[] from = {"TriangleList", "c$a", "h", "l", "j", "c"};
        String[] to = {"TList", "Switch", "Host", "Tri", "Edge", "Config"};
        for (int i = 0; i < from.length; i++) text = text.replace(P + from[i], SELF + "$" + to[i]);
        return text;
    }

    private static Object[] mappedFrame(Object[] input, int count) {
        Object[] output = new Object[count];
        for (int i = 0; i < count; i++) output[i] = input[i] instanceof String s ? mapped(s) : input[i];
        return output;
    }

    private static Class<?> ownClass(byte[] bytes) {
        ClassWriter cw = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassVisitor(ASM9, cw) {
            @Override public void visit(int version, int access, String name, String signature,
                    String parent, String[] interfaces) {
                super.visit(version, access, mapped(name), signature, parent, interfaces);
            }
            @Override public FieldVisitor visitField(int access, String name, String descriptor,
                    String signature, Object value) {
                return super.visitField(access, name, mapped(descriptor), signature, value);
            }
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                return new MethodVisitor(ASM9, super.visitMethod(access, name, mapped(descriptor), signature, exceptions)) {
                    @Override public void visitTypeInsn(int opcode, String type) {
                        super.visitTypeInsn(opcode, mapped(type));
                    }
                    @Override public void visitFieldInsn(int opcode, String owner, String member, String desc) {
                        super.visitFieldInsn(opcode, mapped(owner), member, mapped(desc));
                    }
                    @Override public void visitMethodInsn(int opcode, String owner, String member, String desc, boolean itf) {
                        super.visitMethodInsn(opcode, mapped(owner), member, mapped(desc), itf);
                    }
                    @Override public void visitFrame(int type, int nl, Object[] local, int ns, Object[] stack) {
                        super.visitFrame(type, nl, mappedFrame(local, nl), ns, mappedFrame(stack, ns));
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        byte[] own = cw.toByteArray();
        return new ClassLoader(TriangulationMembershipPatcherTest.class.getClassLoader()) {
            Class<?> define() { return defineClass(null, own, 0, own.length); }
        }.define();
    }

    private static void require(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("check " + checks);
    }

    private static void reject(byte[] bytes) {
        try {
            TriangulationMembershipPatcher.patch(bytes);
            throw new AssertionError("shape accepted");
        } catch (IllegalArgumentException expected) { checks++; }
    }

    @org.junit.jupiter.api.Test
    void generatedFixturesPreserveBranchesAndRejectMutations() throws Exception {
        byte[] original = fixture();
        byte[] patched = TriangulationMembershipPatcher.patch(original);
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
        TriangulationEdgeIndexTransformer transformer = new TriangulationEdgeIndexTransformer();
        require(transformer.transform(null, TriangulationEdgeIndexTransformer.MEMBERSHIP_INTERNAL_NAME,
                null, null, original) == null);
        require(transformer.membershipOutcome() == TriangulationEdgeIndexTransformer.Outcome.HASH_MISMATCH);
        for (int mutation = 0; mutation < 4; mutation++) {
            final int kind = mutation;
            ClassWriter out = new ClassWriter(0);
            new ClassReader(original).accept(new ClassVisitor(ASM9, out) {
                @Override public MethodVisitor visitMethod(int access, String name, String desc,
                        String signature, String[] exceptions) {
                    if (!desc.equals(DESC)) return super.visitMethod(access, name, desc, signature, exceptions);
                    return new MethodVisitor(ASM9, super.visitMethod(kind == 3 ? ACC_PUBLIC : access,
                            name, desc, signature, exceptions)) {
                        boolean changed;
                        @Override public void visitVarInsn(int opcode, int variable) {
                            if (kind == 1 && !changed && opcode == ALOAD && variable == 7) {
                                variable = 6; changed = true;
                            }
                            super.visitVarInsn(opcode, variable);
                        }
                        @Override public void visitJumpInsn(int opcode, Label label) {
                            if (kind == 2 && !changed && opcode == IFNE) {
                                opcode = IFEQ; changed = true;
                            }
                            super.visitJumpInsn(opcode, label);
                        }
                        @Override public void visitMethodInsn(int opcode, String owner, String member,
                                String descriptor, boolean itf) {
                            if (kind == 0 && !changed && owner.equals(P + "TriangleList") && member.equals("c")) {
                                member = "unknown"; changed = true;
                            }
                            super.visitMethodInsn(opcode, owner, member, descriptor, itf);
                        }
                    };
                }
            }, 0);
            reject(out.toByteArray());
        }
        System.out.println("FUSION_BYTECODE_SELF_CHECK PASS checks=" + checks);
    }
}
