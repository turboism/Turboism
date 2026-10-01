package dev.turboism.adapter.cubism.mesh;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.*;

/** Three reviewed fresh-edge sites; runtime equality guard preserves unknown edge semantics. */
final class FreshTriangulationEdgePatcher implements Opcodes {
    private static final String P = "com/live2d/graphics3d/editableMesh/triangulation/";
    private static final String INIT = P + "j.<init>(L" + P + "TriPoint;L" + P + "TriPoint;)V";
    private static final String LIST = "java/util/ArrayList";
    private static final String CONTAINS = LIST + ".contains(Ljava/lang/Object;)Z";
    private static final Set<String> PINS = Set.of(
            "ef4a5eb2f0e1b0ac0295f76146a513a326729cfe4526884104cbb27978c52543",
            "5aa7031e3726355fde25d6d4412f0a295a3725cb8e510a3076007f3270445f0d");

    private FreshTriangulationEdgePatcher() {}

    static byte[] patch(final byte[] bytes) {
        require(PINS.contains(TriangulationEdgeIndexTransformer.sha256(bytes)), "unreviewed fresh-edge caller");
        return patchShape(bytes);
    }

    private record Op(int code, int local, String member) {}

    // Test-only shape entry. Only patch() admits production bytes; these local checks are
    // intentionally not advertised as a general escape/control-flow proof.
    static byte[] patchShape(final byte[] bytes) {
        final ClassReader reader = new ClassReader(bytes);
        require((P + "h").equals(reader.getClassName()), "caller owner");
        final List<Op> ops = new ArrayList<>();
        final int[] methods = {0};
        reader.accept(new ClassVisitor(ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                if (!name.equals("c") || !descriptor.equals("()V")) return null;
                require(access == (ACC_PUBLIC | ACC_FINAL), "c access"); methods[0]++;
                return new MethodVisitor(ASM9) {
                    private void op(int code) { ops.add(new Op(code, -1, null)); }
                    @Override public void visitInsn(int code) { op(code); }
                    @Override public void visitVarInsn(int code, int local) { ops.add(new Op(code, local, null)); }
                    @Override public void visitMethodInsn(int code, String owner, String name, String desc, boolean itf) {
                        ops.add(new Op(code, -1, itf ? null : owner + "." + name + desc));
                    }
                    @Override public void visitTypeInsn(int code, String type) { op(code); }
                    @Override public void visitFieldInsn(int code, String owner, String name, String desc) { op(code); }
                    @Override public void visitIntInsn(int code, int value) { op(code); }
                    @Override public void visitJumpInsn(int code, Label label) { op(code); }
                    @Override public void visitLdcInsn(Object value) { op(LDC); }
                    @Override public void visitIincInsn(int local, int increment) { op(IINC); }
                    @Override public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... args) { op(INVOKEDYNAMIC); }
                    @Override public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) { op(TABLESWITCH); }
                    @Override public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) { op(LOOKUPSWITCH); }
                    @Override public void visitMultiANewArrayInsn(String desc, int dimensions) { op(MULTIANEWARRAY); }
                    @Override public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                        throw new IllegalArgumentException("unexpected c handler");
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        require(methods[0] == 1, "exact c method");
        int sites = 0;
        for (int i = 0; i < ops.size(); i++) {
            if (!CONTAINS.equals(ops.get(i).member())) continue;
            require(i >= 2 && i + 5 < ops.size() && sites < 3, "query position");
            int argument = 12 + sites;
            require(ops.get(i).code() == INVOKEVIRTUAL && load(ops.get(i - 2), 7)
                    && load(ops.get(i - 1), argument), "query receiver/argument");
            require(ops.get(i + 1).code() == IFNE && load(ops.get(i + 2), 7)
                    && load(ops.get(i + 3), argument)
                    && (LIST + ".add(Ljava/lang/Object;)Z").equals(ops.get(i + 4).member())
                    && ops.get(i + 5).code() == POP, "conditional append");
            int edgeStore = lastStore(ops, i, argument), listStore = lastStore(ops, i, 7);
            require(edgeStore > 0 && INIT.equals(ops.get(edgeStore - 1).member())
                    && ops.get(edgeStore - 1).code() == INVOKESPECIAL, "fresh edge initialization");
            require(listStore > 0 && (LIST + ".<init>()V").equals(ops.get(listStore - 1).member())
                    && ops.get(listStore - 1).code() == INVOKESPECIAL, "local list initialization");
            sites++;
        }
        require(sites == 3, "exactly three queries");
        final ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                MethodVisitor output = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals("c") || !descriptor.equals("()V")) return output;
                return new MethodVisitor(ASM9, output) {
                    @Override public void visitMethodInsn(int code, String owner, String name, String desc, boolean itf) {
                        if (code == INVOKEVIRTUAL && !itf && CONTAINS.equals(owner + "." + name + desc)) {
                            super.visitMethodInsn(INVOKESTATIC,
                                    "dev/turboism/adapter/cubism/mesh/FreshTriangulationEdgeSearch", "containsFresh",
                                    "(Ljava/util/ArrayList;Ljava/lang/Object;)Z", false);
                        } else super.visitMethodInsn(code, owner, name, desc, itf);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    private static boolean load(Op op, int local) { return op.code() == ALOAD && op.local() == local; }
    private static int lastStore(List<Op> ops, int before, int local) {
        for (int i = before - 1; i >= 0; i--) if (ops.get(i).code() == ASTORE && ops.get(i).local() == local) return i;
        return -1;
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
