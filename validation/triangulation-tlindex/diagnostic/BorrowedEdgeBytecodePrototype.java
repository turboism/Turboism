import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Owned complete 53 method experiment; no production owner or admission changes. */
final class BorrowedEdgeBytecodePrototype implements Opcodes {
    static final String P = "com/live2d/graphics3d/editableMesh/triangulation/";
    static final String H = P + "h", J = P + "j", K = P + "k", TL = P + "TriangleList";
    static final String DESC = "(L" + TL + ";L" + K + ";)L" + TL + ";";
    static final String CONTROL = "BorrowedEdgeNativeSelfCheck$Control";
    private static final String RAW_53 = "5aa7031e3726355fde25d6d4412f0a295a3725cb8e510a3076007f3270445f0d";
    private BorrowedEdgeBytecodePrototype() { }

    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static byte[] patch(byte[] bytes, boolean candidate) throws Exception {
        require(sha(bytes).equals(RAW_53), "exact raw reviewed 53 h bytes");
        return patchShape(bytes, candidate);
    }

    static byte[] patchShape(byte[] bytes, boolean candidate) {
        ClassReader reader = new ClassReader(bytes);
        require(reader.getClassName().equals(H), "host owner");
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String b) { return "java/lang/Object"; }
        };
        int[] methods = {0};
        reader.accept(new ClassVisitor(ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc,
                    String signature, String[] errors) {
                MethodVisitor original = super.visitMethod(access, name, desc, signature, errors);
                if (!name.equals("a") || !desc.equals(DESC)) return original;
                methods[0]++;
                require(access == (ACC_PRIVATE | ACC_FINAL), "private final target");
                return new MethodNode(ASM9, access, name, desc, signature, errors) {
                    @Override public void visitEnd() {
                        super.visitEnd();
                        rewrite(this, candidate);
                        accept(original);
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        require(methods[0] == 1, "one exact target method");
        return writer.toByteArray();
    }

    private static void rewrite(MethodNode method, boolean candidate) {
        require(method.tryCatchBlocks.isEmpty(), "native target has no handlers");
        List<AbstractInsnNode> ops = new ArrayList<>();
        for (AbstractInsnNode node : method.instructions) if (node.getOpcode() >= 0) ops.add(node);
        List<Integer> sites = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            if (ops.get(i) instanceof MethodInsnNode call && call.getOpcode() == INVOKEVIRTUAL
                    && call.owner.equals(K) && call.name.equals("a")
                    && call.desc.equals("(L" + J + ";Z)Z")) sites.add(i);
        }
        require(sites.size() == 1, "single native borrowed membership");
        int site = sites.get(0);
        require(site >= 3 && site + 1 < ops.size(), "stencil range");
        require(load(ops.get(site - 3), 2) && load(ops.get(site - 2), 15)
                && ops.get(site - 1).getOpcode() == ICONST_0
                && ops.get(site + 1).getOpcode() == IFEQ, "borrowed argument/receiver/mode/branch");
        long coordinateCalls = ops.stream().filter(n -> n instanceof MethodInsnNode call
                && call.owner.equals(J) && call.name.equals("a") && call.desc.equals("(L" + J + ";Z)Z")).count();
        require(coordinateCalls == 1, "native initial coordinate scan remains");
        // Both baseline and candidate have the same observation immediately before native call.
        InsnList observed = new InsnList();
        observed.add(new MethodInsnNode(INVOKESTATIC, CONTROL, "nativeAttempt", "()V", false));
        AbstractInsnNode observer = observed.getFirst();
        method.instructions.insertBefore(ops.get(site), observed);
        if (!candidate) return;

        int lease = method.maxLocals, result = lease + 1, failure = lease + 2;
        LabelNode start = new LabelNode(), end = new LabelNode(), done = new LabelNode(), failed = new LabelNode();
        InsnList entry = new InsnList();
        entry.add(new MethodInsnNode(INVOKESTATIC, CONTROL, "enter", "()Ljava/lang/AutoCloseable;", false));
        entry.add(new VarInsnNode(ASTORE, lease));
        entry.add(start);
        method.instructions.insert(entry);

        LabelNode nativeCall = new LabelNode(), selected = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new VarInsnNode(ALOAD, lease));
        guard.add(new JumpInsnNode(IFNULL, nativeCall));
        guard.add(new VarInsnNode(ALOAD, 11)); // Actual list supplying inner iterator/member.
        guard.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false));
        guard.add(new LdcInsnNode(Type.getType("Ljava/util/ArrayList;")));
        guard.add(new JumpInsnNode(IF_ACMPNE, nativeCall));
        guard.add(new InsnNode(POP)); // original mode
        guard.add(new InsnNode(POP)); // original borrowed argument
        guard.add(new InsnNode(POP)); // original k receiver
        guard.add(new MethodInsnNode(INVOKESTATIC, CONTROL, "bypass", "()V", false));
        guard.add(new InsnNode(ICONST_1));
        guard.add(new JumpInsnNode(GOTO, selected));
        guard.add(nativeCall);
        method.instructions.insertBefore(observer, guard);
        method.instructions.insert(ops.get(site), selected);
        for (AbstractInsnNode node : ops) if (node.getOpcode() == ARETURN) {
            InsnList returned = new InsnList();
            returned.add(new VarInsnNode(ASTORE, result));
            returned.add(new JumpInsnNode(GOTO, done));
            method.instructions.insertBefore(node, returned);
            method.instructions.remove(node);
        }
        method.instructions.add(end);
        method.instructions.add(done);
        leave(method.instructions, lease);
        method.instructions.add(new VarInsnNode(ALOAD, result));
        method.instructions.add(new InsnNode(ARETURN));
        method.instructions.add(failed);
        method.instructions.add(new VarInsnNode(ASTORE, failure));
        leave(method.instructions, lease);
        method.instructions.add(new VarInsnNode(ALOAD, failure));
        method.instructions.add(new InsnNode(ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, failed, "java/lang/Throwable"));
        method.maxLocals = failure + 1;
    }

    private static boolean load(AbstractInsnNode node, int local) {
        return node instanceof VarInsnNode value && value.getOpcode() == ALOAD && value.var == local;
    }
    private static void leave(InsnList output, int lease) {
        output.add(new VarInsnNode(ALOAD, lease));
        output.add(new MethodInsnNode(INVOKESTATIC, CONTROL, "leave", "(Ljava/lang/AutoCloseable;)V", false));
    }
    private static void require(boolean value, String reason) {
        if (!value) throw new IllegalArgumentException(reason);
    }
}
