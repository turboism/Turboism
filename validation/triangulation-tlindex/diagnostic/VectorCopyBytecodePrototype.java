import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Owned-only fresh-vector copy elision on the existing nullable angle lease. */
final class VectorCopyBytecodePrototype implements Opcodes {
    static final String P = AngleGuardBytecodePrototype.P;
    static final String H = AngleGuardBytecodePrototype.H, R = AngleGuardBytecodePrototype.R;
    static final String V = AngleGuardBytecodePrototype.V;
    private static final String CONTROL = "VectorCopyNativeSelfCheck$Control";
    private VectorCopyBytecodePrototype() { }
    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    static byte[] patch(byte[] original, boolean candidate) throws Exception {
        // Original complete h SHA/access/stencil admission remains owned and unchanged.
        return apply(AngleGuardBytecodePrototype.patch(original), candidate);
    }
    static byte[] patch(byte[] original) throws Exception { return patch(original, true); }
    static byte[] patchShape(byte[] original) { return apply(AngleGuardBytecodePrototype.patchShape(original), true); }
    private static byte[] apply(byte[] common, boolean candidate) {
        ClassNode type = new ClassNode();
        new ClassReader(common).accept(type, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        MethodNode method = type.methods.stream().filter(m -> m.name.equals("d") && m.desc.equals("()V"))
                .findFirst().orElseThrow();
        int lease = -1;
        List<MethodInsnNode> copies = new ArrayList<>();
        for (AbstractInsnNode node : method.instructions) {
            if (!(node instanceof MethodInsnNode call)) continue;
            if (call.owner.equals("AngleGuardNativeSelfCheck$Control")) {
                call.owner = CONTROL;
                if (call.name.equals("enter")) {
                    require(call.getNext() instanceof VarInsnNode store && store.getOpcode() == ASTORE,
                            "owned common lease store");
                    lease = ((VarInsnNode) call.getNext()).var;
                }
            }
            if (call.getOpcode() == INVOKESPECIAL && call.owner.equals(V) && call.name.equals("<init>")
                    && call.desc.equals("(L" + V + ";)V")) copies.add(call);
        }
        require(lease >= 0 && copies.size() == 2, "one shared lease and two fresh-vector copies");
        for (int site = 0; site < copies.size(); site++) {
            MethodInsnNode copy = copies.get(site);
            require(copy.getPrevious() instanceof MethodInsnNode minus && minus.getOpcode() == INVOKEVIRTUAL
                    && minus.owner.equals(P + "TriPoint") && minus.name.equals("minus")
                    && minus.desc.equals("(L" + V + ";)L" + V + ";"), "immediate reviewed final minus");
            require(copy.getNext() instanceof VarInsnNode store && store.getOpcode() == ASTORE
                    && store.var == 11 + site, "vector copy result local");
            AbstractInsnNode allocation = copy.getPrevious();
            while (allocation != null && allocation.getOpcode() != NEW) {
                require(!(allocation instanceof JumpInsnNode) && !(allocation instanceof LabelNode),
                        "straight-line vector expression");
                allocation = allocation.getPrevious();
            }
            require(allocation instanceof TypeInsnNode created && created.desc.equals(V)
                    && allocation.getNext().getOpcode() == DUP, "exact outer allocation");
            InsnList observer = new InsnList();
            MethodInsnNode observedCopy = new MethodInsnNode(INVOKESTATIC, CONTROL, "nativeCopy", "()V", false);
            observer.add(observedCopy);
            method.instructions.insertBefore(copy, observer);
            if (!candidate) continue;
            LabelNode nativeCopy = new LabelNode(), done = new LabelNode();
            InsnList fast = new InsnList();
            fast.add(new VarInsnNode(ALOAD, lease)); fast.add(new JumpInsnNode(IFNULL, nativeCopy));
            for (AbstractInsnNode expression = allocation.getNext().getNext(); expression != observedCopy;
                    expression = expression.getNext()) {
                require(expression != copy && expression != null, "bounded operand expression");
                fast.add(expression.clone(new java.util.HashMap<>()));
            }
            fast.add(new MethodInsnNode(INVOKESTATIC, CONTROL, "elidedCopy", "()V", false));
            fast.add(new VarInsnNode(ASTORE, 11 + site)); fast.add(new JumpInsnNode(GOTO, done));
            fast.add(nativeCopy);
            method.instructions.insertBefore(allocation, fast);
            method.instructions.insert(copy.getNext(), done);
        }
        ClassWriter output = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String b) { return "java/lang/Object"; }
        };
        type.accept(output); return output.toByteArray();
    }
    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
}
