package dev.turboism.validation.tlindex.diagnostic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Offline-only experiment. Never loads official classes or installs an agent. */
public final class FreshEdgeBytecodePrototype implements Opcodes {
    private static final String P = "com/live2d/graphics3d/editableMesh/triangulation/";
    private static final String EDGE = P + "j";
    private static final String CTOR = "(L" + P + "TriPoint;L" + P + "TriPoint;)V";
    private static final Map<String, String> PINS = Map.of(
            "ef4a5eb2f0e1b0ac0295f76146a513a326729cfe4526884104cbb27978c52543",
            "2adcb397fbc52a1e2463f21e893ee305c53259e44693bcdf394b882535801e5a",
            "5aa7031e3726355fde25d6d4412f0a295a3725cb8e510a3076007f3270445f0d",
            "1037aa92fd095da9eba18409ee75f01b1eec7ff40c3b698432d1f2d5ca95f961");

    private FreshEdgeBytecodePrototype() {}

    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static byte[] patch(byte[] host, byte[] edge) throws Exception {
        require(sha(edge).equals(PINS.get(sha(host))), "unreviewed paired h/j hashes");
        return patchShape(host, edge);
    }

    static byte[] patchShape(byte[] bytes, byte[] edgeBytes) {
        ClassNode edge = new ClassNode();
        new ClassReader(edgeBytes).accept(edge, ClassReader.SKIP_CODE);
        require(EDGE.equals(edge.name) && "java/lang/Object".equals(edge.superName)
                && (edge.access & ACC_FINAL) != 0, "identity edge type");
        require(edge.methods.stream().noneMatch(m -> m.name.equals("equals") || m.name.equals("hashCode")),
                "edge equality override");
        ClassReader reader = new ClassReader(bytes);
        ClassNode type = new ClassNode();
        reader.accept(type, 0);
        require((P + "h").equals(type.name), "host owner");
        List<MethodNode> selected = type.methods.stream()
                .filter(m -> m.name.equals("c") && m.desc.equals("()V")).toList();
        require(selected.size() == 1, "exact c method");
        MethodNode method = selected.get(0);
        require(method.access == (ACC_PUBLIC | ACC_FINAL) && method.tryCatchBlocks.isEmpty(), "method shape");
        List<AbstractInsnNode> ops = new ArrayList<>();
        for (AbstractInsnNode n : method.instructions) if (n.getOpcode() >= 0) ops.add(n);
        int sites = 0;
        for (int i = 2; i + 5 < ops.size(); i++) {
            if (!call(ops.get(i), "java/util/ArrayList", "contains", "(Ljava/lang/Object;)Z")) continue;
            int argument = 12 + sites;
            require(sites < 3 && load(ops.get(i - 2), 7) && load(ops.get(i - 1), argument), "query locals");
            require(ops.get(i + 1).getOpcode() == IFNE && load(ops.get(i + 2), 7)
                    && load(ops.get(i + 3), argument)
                    && call(ops.get(i + 4), "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z")
                    && ops.get(i + 5).getOpcode() == POP, "conditional append");
            int store = lastStore(ops, i, argument);
            require(store > 0 && call(ops.get(store - 1), EDGE, "<init>", CTOR), "fresh edge assignment");
            int listStore = lastStore(ops, i, 7);
            require(listStore > 1 && call(ops.get(listStore - 1), "java/util/ArrayList", "<init>", "()V"),
                    "local list initialization");
            // Reviewed paired class pins are essential: this shape check is not a general
            // bytecode escape/dataflow proof and must never admit arbitrary host bytes.
            sites++;
        }
        require(sites == 3, "exactly three fresh-edge queries");
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                MethodVisitor output = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals("c") || !descriptor.equals("()V")) return output;
                return new MethodVisitor(ASM9, output) {
                    @Override public void visitMethodInsn(int opcode, String owner, String name,
                            String descriptor, boolean itf) {
                        if (opcode == INVOKEVIRTUAL && !itf && owner.equals("java/util/ArrayList")
                                && name.equals("contains") && descriptor.equals("(Ljava/lang/Object;)Z")) {
                            // Same stack effect (two references -> int), no new branch or frame.
                            super.visitInsn(POP2);
                            super.visitInsn(ICONST_0);
                        } else super.visitMethodInsn(opcode, owner, name, descriptor, itf);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    private static int lastStore(List<AbstractInsnNode> ops, int before, int local) {
        for (int i = before - 1; i >= 0; i--) {
            if (ops.get(i) instanceof VarInsnNode v && v.var == local && v.getOpcode() == ASTORE) return i;
        }
        return -1;
    }
    private static boolean load(AbstractInsnNode n, int local) {
        return n instanceof VarInsnNode v && v.getOpcode() == ALOAD && v.var == local;
    }
    private static boolean call(AbstractInsnNode n, String owner, String name, String descriptor) {
        return n instanceof MethodInsnNode m && !m.itf && m.owner.equals(owner)
                && m.name.equals(name) && m.desc.equals(descriptor);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("official jar, new output directory required");
        byte[] host;
        byte[] edge;
        try (ZipFile jar = new ZipFile(args[0])) {
            host = jar.getInputStream(jar.getEntry(P + "h.class")).readAllBytes();
            edge = jar.getInputStream(jar.getEntry(EDGE + ".class")).readAllBytes();
        }
        byte[] output = patch(host, edge);
        Path directory = Path.of(args[1]);
        Files.createDirectory(directory);
        Files.write(directory.resolve("h.class"), output);
        Files.writeString(directory.resolve("pins.txt"), "hInput=" + sha(host) + "\njInput=" + sha(edge)
                + "\nhOutput=" + sha(output) + "\nhostExecuted=false\n");
        System.out.println("Fresh-edge static prototype produced: " + sha(output));
    }
}
