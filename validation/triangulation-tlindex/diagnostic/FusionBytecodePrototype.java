package dev.turboism.validation.tlindex.diagnostic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Offline-only guarded fusion experiment. Does not install a transformer or load host classes. */
public final class FusionBytecodePrototype implements Opcodes {
    private static final String PACKAGE = "com/live2d/graphics3d/editableMesh/triangulation/";
    private static final String OWNER = PACKAGE + "h";
    private static final String LIST = PACKAGE + "TriangleList";
    private static final String ARG = "(L" + PACKAGE + "l;)Z";
    private static final String METHOD = "(L" + PACKAGE + "l;L" + PACKAGE + "l;L"
            + PACKAGE + "j;)Ljava/util/List;";
    private static final Set<String> PINS = Set.of(
            "ef4a5eb2f0e1b0ac0295f76146a513a326729cfe4526884104cbb27978c52543",
            "5aa7031e3726355fde25d6d4412f0a295a3725cb8e510a3076007f3270445f0d");

    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static byte[] patch(byte[] bytes) throws Exception {
        require(PINS.contains(sha(bytes)), "unreviewed class hash");
        return patchShape(bytes);
    }

    // Package access permits negative fixture tests without weakening production pins.
    static byte[] patchShape(byte[] bytes) {
        ClassNode type = new ClassNode();
        new ClassReader(bytes).accept(type, ClassReader.EXPAND_FRAMES);
        require(OWNER.equals(type.name), "owner");
        List<MethodNode> methods = type.methods.stream()
                .filter(m -> m.name.equals("a") && m.desc.equals(METHOD)).toList();
        require(methods.size() == 1, "method count");
        MethodNode method = methods.get(0);
        require(method.access == (ACC_PUBLIC | ACC_FINAL), "method access");
        require(method.tryCatchBlocks.isEmpty(), "unexpected handlers");
        List<AbstractInsnNode> ops = new ArrayList<>();
        for (AbstractInsnNode node : method.instructions) if (node.getOpcode() >= 0) ops.add(node);
        List<AbstractInsnNode[]> sites = new ArrayList<>();
        for (int i = 2; i + 6 < ops.size(); i++) {
            if (!call(ops.get(i), "c")) continue;
            if (ops.get(i + 1).getOpcode() != IFNE) continue; // existing debug logs remain
            AbstractInsnNode[] block = ops.subList(i - 2, i + 6).toArray(AbstractInsnNode[]::new);
            require(load(block[0], 4) && load(block[4], 4), "receiver local");
            int argument = sites.isEmpty() ? 7 : 8;
            require(load(block[1], argument) && load(block[5], argument), "argument local");
            require(call(block[6], "a") && block[7].getOpcode() == POP, "conditional add");
            JumpInsnNode jump = (JumpInsnNode) block[3];
            require(nextOp(jump.label) == ops.get(i + 6), "branch skips more than add");
            // The original join frame is already complete and has empty operand stack.
            FrameNode frame = precedingFrame(block[0]);
            require(frame.type == F_NEW && frame.stack.isEmpty(), "join frame");
            require(frame.local.stream().noneMatch(v -> v instanceof LabelNode), "uninitialized locals");
            sites.add(block);
        }
        require(sites.size() == 2, "exactly two sites required");
        for (AbstractInsnNode[] block : sites) {
            FrameNode frame = precedingFrame(block[0]);
            LabelNode original = new LabelNode();
            InsnList guard = new InsnList();
            guard.add(new FieldInsnNode(GETSTATIC, PACKAGE + "c", "a", "L" + PACKAGE + "c$a;"));
            guard.add(new MethodInsnNode(INVOKEVIRTUAL, PACKAGE + "c$a", "b", "()Z", false));
            guard.add(new JumpInsnNode(IFNE, original));
            guard.add(new VarInsnNode(ALOAD, 4));
            guard.add(new VarInsnNode(ALOAD, ((VarInsnNode) block[1]).var));
            guard.add(new MethodInsnNode(INVOKEVIRTUAL, LIST, "a", ARG, false));
            guard.add(new InsnNode(POP));
            guard.add(new JumpInsnNode(GOTO, ((JumpInsnNode) block[3]).label));
            guard.add(original);
            guard.add(new FrameNode(F_NEW, frame.local.size(), frame.local.toArray(), 0, new Object[0]));
            method.instructions.insertBefore(block[0], guard);
        }
        // Preserve existing expanded frames; inserted frames have identical locals/empty stack.
        // COMPUTE_MAXS needs no host hierarchy loading, unlike COMPUTE_FRAMES.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        type.accept(writer);
        return writer.toByteArray();
    }

    private static FrameNode precedingFrame(AbstractInsnNode node) {
        for (AbstractInsnNode prior = node.getPrevious(); prior != null; prior = prior.getPrevious()) {
            if (prior instanceof FrameNode frame) return frame;
            require(prior.getOpcode() < 0, "no frame at site boundary");
        }
        throw new IllegalArgumentException("missing join frame");
    }

    private static AbstractInsnNode nextOp(AbstractInsnNode node) {
        while (node != null && node.getOpcode() < 0) node = node.getNext();
        return node;
    }

    private static boolean load(AbstractInsnNode node, int local) {
        return node instanceof VarInsnNode v && v.getOpcode() == ALOAD && v.var == local;
    }

    private static boolean call(AbstractInsnNode node, String name) {
        return node instanceof MethodInsnNode m && m.getOpcode() == INVOKEVIRTUAL
                && !m.itf && m.owner.equals(LIST) && m.name.equals(name) && m.desc.equals(ARG);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    public static void main(String[] args) throws Exception {
        byte[] original;
        try (ZipFile zip = new ZipFile(args[0])) {
            original = zip.getInputStream(zip.getEntry(OWNER + ".class")).readAllBytes();
        }
        byte[] patched = patch(original);
        Path output = Path.of(args[1]);
        Files.createDirectory(output); // never overwrite prior evidence
        Files.write(output.resolve("h.class"), patched);
        Files.writeString(output.resolve("pins.txt"), "original=" + sha(original)
                + "\npatched=" + sha(patched) + "\nstatus=OFFLINE_PROTOTYPE_NOT_INSTALLED\n");
        System.out.println("PROTOTYPE_EMITTED " + output + " bytes=" + patched.length);
    }
}
