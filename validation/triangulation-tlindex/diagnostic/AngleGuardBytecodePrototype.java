import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Owned-only h.d weave; no Agent, production bridge, installation or admission. */
final class AngleGuardBytecodePrototype implements Opcodes {
    static final String P = "com/live2d/graphics3d/editableMesh/triangulation/";
    static final String H = P + "h", R = P + "r", V = "com/live2d/graphics3d/type/GVector2";
    private static final String CONTROL = "AngleGuardNativeSelfCheck$Control";
    private static final Set<String> PINS = Set.of(
            "ef4a5eb2f0e1b0ac0295f76146a513a326729cfe4526884104cbb27978c52543",
            "5aa7031e3726355fde25d6d4412f0a295a3725cb8e510a3076007f3270445f0d");
    private AngleGuardBytecodePrototype() { }
    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    static byte[] patch(byte[] bytes) throws Exception {
        require(PINS.contains(sha(bytes)), "unreviewed h bytes");
        return patchShape(bytes);
    }
    static byte[] patchShape(byte[] bytes) {
        ClassNode type = new ClassNode(); new ClassReader(bytes).accept(type, ClassReader.SKIP_FRAMES);
        require(type.name.equals(H), "owner");
        List<MethodNode> methods = type.methods.stream().filter(m -> m.name.equals("d") && m.desc.equals("()V")).toList();
        require(methods.size() == 1, "one d method");
        MethodNode method = methods.get(0);
        require(method.access == (ACC_PUBLIC | ACC_FINAL) && method.tryCatchBlocks.isEmpty(), "method shape");
        List<AbstractInsnNode> ops = new ArrayList<>();
        for (AbstractInsnNode node : method.instructions) if (node.getOpcode() >= 0) ops.add(node);
        List<Integer> sites = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            if (ops.get(i) instanceof MethodInsnNode m && m.owner.equals(R) && m.name.equals("a")
                    && m.desc.equals("(L" + V + ";L" + V + ";)F")) sites.add(i);
        }
        require(sites.size() == 1, "one angle site");
        int site = sites.get(0);
        require(site >= 3 && site + 6 < ops.size(), "site range");
        require(ops.get(site - 3) instanceof FieldInsnNode field && field.getOpcode() == GETSTATIC
                && field.owner.equals(R) && field.name.equals("a"), "original singleton read");
        require(load(ops.get(site - 2), ALOAD, 11) && load(ops.get(site - 1), ALOAD, 12)
                && load(ops.get(site + 1), FSTORE, 13) && load(ops.get(site + 2), FLOAD, 13), "angle locals");
        int branch = site + 3;
        if (ops.get(branch).getOpcode() == FCONST_0) branch++;
        else {
            require(ops.get(branch) instanceof FieldInsnNode field && field.getOpcode() == GETSTATIC
                    && field.owner.equals("com/live2d/util/L") && field.name.equals("a"), "threshold singleton");
            branch++;
            require(ops.get(branch) instanceof MethodInsnNode m && m.owner.equals("com/live2d/util/L")
                    && m.name.equals("f") && m.desc.equals("()F"), "threshold method");
            branch++;
        }
        require(ops.get(branch).getOpcode() == FCMPL && ops.get(branch + 1).getOpcode() == IFGT, "native reject branch");
        LabelNode rejected = ((JumpInsnNode) ops.get(branch + 1)).label;
        int ready = method.maxLocals, misses = ready + 1, lease = ready + 2, failure = ready + 3;
        LabelNode start = new LabelNode(), end = new LabelNode(), done = new LabelNode(), failed = new LabelNode();
        InsnList entry = new InsnList();
        entry.add(new MethodInsnNode(INVOKESTATIC, CONTROL, "enter", "()Ljava/lang/AutoCloseable;", false));
        entry.add(new VarInsnNode(ASTORE, lease)); entry.add(start);
        entry.add(new InsnNode(ICONST_0)); entry.add(new VarInsnNode(ISTORE, ready));
        entry.add(new InsnNode(ICONST_0)); entry.add(new VarInsnNode(ISTORE, misses));
        method.instructions.insert(entry);
        // Insert after GETSTATIC r.a so original singleton initialization is never skipped.
        LabelNode nativeCall = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new VarInsnNode(ALOAD, lease)); guard.add(new JumpInsnNode(IFNULL, nativeCall));
        guard.add(new VarInsnNode(ILOAD, ready)); guard.add(new JumpInsnNode(IFLE, nativeCall));
        getter(guard, 11, "getX"); getter(guard, 12, "getY"); guard.add(new InsnNode(FMUL));
        getter(guard, 11, "getY"); getter(guard, 12, "getX"); guard.add(new InsnNode(FMUL)); guard.add(new InsnNode(FSUB));
        getter(guard, 11, "getX"); getter(guard, 12, "getX"); guard.add(new InsnNode(FMUL));
        getter(guard, 11, "getY"); getter(guard, 12, "getY"); guard.add(new InsnNode(FMUL)); guard.add(new InsnNode(FADD));
        guard.add(new MethodInsnNode(INVOKESTATIC, CONTROL, "reject", "(FF)Z", false));
        LabelNode fastReject = new LabelNode();
        guard.add(new JumpInsnNode(IFNE, fastReject));
        guard.add(new IincInsnNode(misses, 1)); guard.add(new VarInsnNode(ILOAD, misses));
        guard.add(new IntInsnNode(BIPUSH, 8)); guard.add(new JumpInsnNode(IF_ICMPLT, nativeCall));
        guard.add(new InsnNode(ICONST_M1)); guard.add(new VarInsnNode(ISTORE, ready));
        guard.add(new JumpInsnNode(GOTO, nativeCall));
        guard.add(fastReject); guard.add(new InsnNode(ICONST_0)); guard.add(new VarInsnNode(ISTORE, misses));
        guard.add(new InsnNode(POP)); // the original singleton receiver remains underneath predicate operands
        guard.add(new JumpInsnNode(GOTO, rejected)); guard.add(nativeCall);
        method.instructions.insert(ops.get(site - 3), guard);
        LabelNode alreadyWarm = new LabelNode();
        InsnList warmed = new InsnList();
        warmed.add(new VarInsnNode(ILOAD, ready)); warmed.add(new JumpInsnNode(IFNE, alreadyWarm));
        warmed.add(new InsnNode(ICONST_1)); warmed.add(new VarInsnNode(ISTORE, ready)); warmed.add(alreadyWarm);
        method.instructions.insert(ops.get(site + 1), warmed);
        for (AbstractInsnNode node : ops) if (node.getOpcode() == RETURN)
            method.instructions.set(node, new JumpInsnNode(GOTO, done));
        method.instructions.add(end); method.instructions.add(done);
        leave(method.instructions, lease); method.instructions.add(new InsnNode(RETURN));
        method.instructions.add(failed); method.instructions.add(new VarInsnNode(ASTORE, failure));
        leave(method.instructions, lease); method.instructions.add(new VarInsnNode(ALOAD, failure));
        method.instructions.add(new InsnNode(ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, failed, "java/lang/Throwable"));
        method.maxLocals = failure + 1;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String b) { return "java/lang/Object"; }
        };
        type.accept(writer); return writer.toByteArray();
    }
    private static void getter(InsnList out, int local, String name) {
        out.add(new VarInsnNode(ALOAD, local)); out.add(new MethodInsnNode(INVOKEVIRTUAL, V, name, "()F", false));
    }
    private static void leave(InsnList out, int lease) {
        out.add(new VarInsnNode(ALOAD, lease));
        out.add(new MethodInsnNode(INVOKESTATIC, CONTROL, "leave", "(Ljava/lang/AutoCloseable;)V", false));
    }
    private static boolean load(AbstractInsnNode node, int opcode, int local) {
        return node instanceof VarInsnNode v && v.getOpcode() == opcode && v.var == local;
    }
    private static void require(boolean value, String reason) {
        if (!value) throw new IllegalArgumentException(reason);
    }
}
