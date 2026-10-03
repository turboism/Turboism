import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Owned-only copy of the complete native append suffix. No production weaving. */
final class NativeMeshEdgeLoopPrototype implements Opcodes {
    static final String MESH = "com/live2d/graphics3d/editableMesh/GEditableMesh2";
    static final String LIST = "com/live2d/type/CArrayList";
    static final String CONTROL = "NativeMeshEdgeLoopSelfCheck$Control";
    private NativeMeshEdgeLoopPrototype() { }

    static byte[] patch(byte[] raw, boolean candidate) throws Exception {
        return patch(raw, candidate, false);
    }

    static byte[] patch(byte[] raw, boolean candidate, boolean complete) throws Exception {
        String pin = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        require(pin.equals("734b9bde593f27816b72f63c585a371d724507f21c97ac41980cbf3f347c57ad")
                || pin.equals("d6fe4e690399d767019d113e82c62414da0d82d9a54aa799a74919d9e8693f7a"), "exact native class pin");
        ClassNode type = new ClassNode();
        new ClassReader(raw).accept(type, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        require(type.name.equals(MESH), "exact owner");
        MethodNode nativeLoop = type.methods.stream().filter(m -> m.name.equals("autoConnect")).findFirst().orElseThrow();
        require(nativeLoop.tryCatchBlocks.isEmpty(), "no native handlers");
        FieldInsnNode read = null;
        for (AbstractInsnNode n : nativeLoop.instructions)
            if (n instanceof FieldInsnNode f && f.getOpcode() == GETFIELD && f.name.equals("cached_indices")) {
                require(read == null, "one index publication read"); read = f;
            }
        require(read != null && read.getPrevious() instanceof VarInsnNode load
                && load.getOpcode() == ALOAD && load.var == 0, "suffix starts at actual mesh load");
        AbstractInsnNode first = read.getPrevious();
        Map<LabelNode, LabelNode> labels = new HashMap<>();
        for (AbstractInsnNode n : nativeLoop.instructions)
            if (n instanceof LabelNode l) labels.put(l, new LabelNode());
        MethodNode owned = new MethodNode(ACC_PUBLIC | ACC_FINAL, "ownedAppend", "()V", null, null);
        int additions = 0;
        for (AbstractInsnNode n = first; n != null; n = n.getNext()) {
            owned.instructions.add(n.clone(labels));
            if (n instanceof MethodInsnNode call && call.name.equals("addEdgeIfNotExists$default")) additions++;
        }
        require(additions == 3, "three native insertion sites");
        owned.maxLocals = nativeLoop.maxLocals;
        if (candidate) wrapLease(owned);
        type.methods.add(owned);
        if (candidate && complete) wrapSuffix(nativeLoop, first);
        if (candidate) {
            int lookups = 0, appends = 0;
            for (MethodNode method : type.methods) {
                for (AbstractInsnNode n : method.instructions.toArray()) {
                    if (!(n instanceof MethodInsnNode call)) continue;
                    if (method.name.equals("checkExitingTypedEdge") && call.owner.equals(MESH)
                            && call.name.equals("chechExistingEdge_exe") && call.desc.equals("(IIZ)Ljava/lang/Integer;")) {
                        method.instructions.set(n, new MethodInsnNode(INVOKESTATIC, CONTROL, "lookup",
                                "(Ljava/lang/Object;IIZ)Ljava/lang/Integer;", false));
                        lookups++;
                    }
                    if (method.name.equals("addEdge") && call.owner.equals(LIST) && call.name.equals("add")
                            && call.desc.equals("(Ljava/lang/Object;)Z")) {
                        AbstractInsnNode pop = n.getNext();
                        require(pop.getOpcode() == POP, "native successful append pop");
                        InsnList register = new InsnList();
                        register.add(new VarInsnNode(ALOAD, 0));
                        register.add(new MethodInsnNode(INVOKESTATIC, CONTROL, "appended", "(Ljava/lang/Object;)V", false));
                        method.instructions.insert(pop, register);
                        appends++;
                    }
                }
            }
            require(lookups == 1 && appends == 1, "one exact lookup and append");
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String b) { return "java/lang/Object"; }
        };
        type.accept(writer);
        return writer.toByteArray();
    }

    private static void wrapSuffix(MethodNode method, AbstractInsnNode first) {
        int lease = method.maxLocals, failure = lease + 1;
        LabelNode start = new LabelNode(), end = new LabelNode(), done = new LabelNode(), failed = new LabelNode();
        InsnList enter = new InsnList();
        enter.add(new VarInsnNode(ALOAD, 0));
        enter.add(new MethodInsnNode(INVOKESTATIC, CONTROL, "enter", "(Ljava/lang/Object;)Ljava/lang/AutoCloseable;", false));
        enter.add(new VarInsnNode(ASTORE, lease)); enter.add(start);
        method.instructions.insertBefore(first, enter);
        int returns = 0;
        for (AbstractInsnNode n = first; n != null; ) {
            AbstractInsnNode next = n.getNext();
            if (n.getOpcode() == RETURN) {
                method.instructions.set(n, new JumpInsnNode(GOTO, done)); returns++;
            }
            n = next;
        }
        require(returns == 1, "one suffix return; earlier native return untouched");
        method.instructions.add(end); method.instructions.add(done);
        leave(method.instructions, lease); method.instructions.add(new InsnNode(RETURN));
        method.instructions.add(failed); method.instructions.add(new VarInsnNode(ASTORE, failure));
        leave(method.instructions, lease); method.instructions.add(new VarInsnNode(ALOAD, failure));
        method.instructions.add(new InsnNode(ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, failed, "java/lang/Throwable"));
        method.maxLocals = failure + 1;
    }

    private static void wrapLease(MethodNode method) {
        int lease = method.maxLocals, failure = lease + 1;
        LabelNode start = new LabelNode(), end = new LabelNode(), done = new LabelNode(), failed = new LabelNode();
        InsnList enter = new InsnList();
        enter.add(new VarInsnNode(ALOAD, 0));
        enter.add(new MethodInsnNode(INVOKESTATIC, CONTROL, "enter", "(Ljava/lang/Object;)Ljava/lang/AutoCloseable;", false));
        enter.add(new VarInsnNode(ASTORE, lease)); enter.add(start);
        method.instructions.insert(enter);
        for (AbstractInsnNode n : method.instructions.toArray()) if (n.getOpcode() == RETURN)
            method.instructions.set(n, new JumpInsnNode(GOTO, done));
        method.instructions.add(end); method.instructions.add(done);
        leave(method.instructions, lease); method.instructions.add(new InsnNode(RETURN));
        method.instructions.add(failed); method.instructions.add(new VarInsnNode(ASTORE, failure));
        leave(method.instructions, lease); method.instructions.add(new VarInsnNode(ALOAD, failure));
        method.instructions.add(new InsnNode(ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, failed, "java/lang/Throwable"));
        method.maxLocals = failure + 1;
    }

    private static void leave(InsnList output, int lease) {
        output.add(new VarInsnNode(ALOAD, lease));
        output.add(new MethodInsnNode(INVOKESTATIC, CONTROL, "leave", "(Ljava/lang/AutoCloseable;)V", false));
    }
    private static void require(boolean value, String reason) {
        if (!value) throw new IllegalArgumentException(reason);
    }
}
