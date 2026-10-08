package dev.turboism.validation.tlindex.diagnostic;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipFile;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Offline byte-data experiment. No official class is defined or initialized. */
public final class LazyEdgeBytecodePrototype implements Opcodes {
    static final String P = "com/live2d/graphics3d/editableMesh/triangulation/";
    static final String H = P + "h", J = P + "j", T = P + "TriPoint", L = P + "l", R = P + "r";
    static final String V = "com/live2d/graphics3d/type/GVector2";
    static final String POINT = "L" + T + ";";
    static final String CTOR = "(" + POINT + POINT + ")V";
    static final String PAIR = "(L" + J + ";L" + J + ";)L" + V + ";";
    static final String ENDPOINT = "(" + ("L" + V + ";").repeat(4) + ")L" + V + ";";
    private static final Map<String, String> JARS = Map.of(
            "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd", "5203",
            "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21", "5302",
            "bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166", "5303");
    private static final List<String> HOSTS = List.of(
            "ef4a5eb2f0e1b0ac0295f76146a513a326729cfe4526884104cbb27978c52543",
            "5aa7031e3726355fde25d6d4412f0a295a3725cb8e510a3076007f3270445f0d");

    private LazyEdgeBytecodePrototype() {}

    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static void require(boolean ok, String reason) {
        if (!ok) throw new IllegalArgumentException(reason);
    }

    private static boolean load(AbstractInsnNode n, int local) {
        return n instanceof VarInsnNode v && v.getOpcode() == ALOAD && v.var == local;
    }

    private static boolean call(AbstractInsnNode n, int opcode, String owner, String name, String desc) {
        return n instanceof MethodInsnNode m && m.getOpcode() == opcode && !m.itf
                && m.owner.equals(owner) && m.name.equals(name) && m.desc.equals(desc);
    }

    private static List<AbstractInsnNode> ops(MethodNode method) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode n : method.instructions) if (n.getOpcode() >= 0) result.add(n);
        return result;
    }

    private static void a(InsnList code, int local) { code.add(new VarInsnNode(ALOAD, local)); }
    private static void i(InsnList code, int local) { code.add(new VarInsnNode(ILOAD, local)); }
    private static void constant(InsnList code, int value, int local) {
        code.add(new InsnNode(value == 0 ? ICONST_0 : ICONST_1));
        code.add(new VarInsnNode(ISTORE, local));
    }

    private static void construct(InsnList code, int first, int second, int target) {
        code.add(new TypeInsnNode(NEW, J)); code.add(new InsnNode(DUP));
        a(code, first); a(code, second);
        code.add(new MethodInsnNode(INVOKESPECIAL, J, "<init>", CTOR, false));
        code.add(new VarInsnNode(ASTORE, target));
    }

    private static void get(InsnList code, String owner, String name, String desc) {
        code.add(new MethodInsnNode(INVOKEVIRTUAL, owner, name, desc, false));
    }

    private static int lineAt(MethodNode method, AbstractInsnNode target) {
        int line = -1;
        for (AbstractInsnNode n : method.instructions) {
            if (n instanceof LineNumberNode l) line = l.line;
            if (n == target) return line;
        }
        throw new IllegalArgumentException("instruction missing");
    }

    private static void line(InsnList code, LabelNode label, int number) {
        if (number >= 0) code.add(new LineNumberNode(number, label));
    }

    private static void preflight(InsnList code, int first, int second, int target, int constructorLine) {
        LabelNode fail = new LabelNode(), good = new LabelNode();
        a(code, first); code.add(new JumpInsnNode(IFNULL, fail));
        a(code, second); code.add(new JumpInsnNode(IFNULL, fail));
        a(code, first); get(code, T, "getIndex", "()I");
        a(code, second); get(code, T, "getIndex", "()I");
        code.add(new JumpInsnNode(IF_ICMPNE, good));
        code.add(new FieldInsnNode(GETSTATIC, "kotlin/_Assertions", "ENABLED", "Z"));
        code.add(new JumpInsnNode(IFEQ, good));
        code.add(fail);
        line(code, fail, constructorLine);
        // Reuse the untouched constructor to produce its native null/assertion
        // error and stack. Exact pure getters and immutable indices are mandatory.
        construct(code, first, second, target);
        code.add(good);
    }

    private static void endpointFlag(InsnList code, int first, int second, int flag) {
        code.add(new FieldInsnNode(GETSTATIC, R, "a", "L" + R + ";"));
        a(code, 9); get(code, J, "a", "()" + POINT);
        a(code, 9); get(code, J, "b", "()" + POINT);
        a(code, first); a(code, second);
        get(code, R, "a", ENDPOINT);
        LabelNode none = new LabelNode(), done = new LabelNode();
        code.add(new JumpInsnNode(IFNULL, none)); code.add(new InsnNode(ICONST_1));
        code.add(new JumpInsnNode(GOTO, done)); code.add(none);
        code.add(new InsnNode(ICONST_0)); code.add(done);
        code.add(new VarInsnNode(ISTORE, flag));
    }

    // Generated-own-type tests only. The public CLI admits whole SHA-pinned JARs;
    // stencil checks alone are not an arbitrary-byte control-flow/dependency proof.
    static byte[] patchShape(byte[] bytes, Function<String, byte[]> definitions) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode type = new ClassNode(); reader.accept(type, ClassReader.EXPAND_FRAMES);
        require(type.name.equals(H), "host owner");
        List<MethodNode> targets = type.methods.stream()
                .filter(m -> m.name.equals("c") && m.desc.equals("()V")).toList();
        require(targets.size() == 1, "exact c method");
        MethodNode method = targets.get(0);
        require(method.access == (ACC_PUBLIC | ACC_FINAL) && method.tryCatchBlocks.isEmpty(), "c shape");
        List<AbstractInsnNode> original = ops(method);
        List<MethodInsnNode> ctors = original.stream()
                .filter(n -> call(n, INVOKESPECIAL, J, "<init>", CTOR))
                .map(n -> (MethodInsnNode) n).toList();
        List<MethodInsnNode> intersections = original.stream()
                .filter(n -> call(n, INVOKEVIRTUAL, R, "a", PAIR))
                .map(n -> (MethodInsnNode) n).toList();
        require(ctors.size() == 4 && intersections.size() == 4, "four original native sites");
        require(original.indexOf(ctors.get(2)) < original.indexOf(intersections.get(0)), "construction order");
        require(original.indexOf(intersections.get(2)) < original.indexOf(ctors.get(3)), "separate fourth path");
        AbstractInsnNode[] starts = new AbstractInsnNode[3], stores = new AbstractInsnNode[3];
        String[][] getters = {{"a", "b"}, {"b", "c"}, {"c", "a"}};
        for (int s = 0; s < 3; s++) {
            int pos = original.indexOf(ctors.get(s));
            require(pos >= 6 && pos + 1 < original.size(), "constructor range");
            starts[s] = original.get(pos - 6); stores[s] = original.get(pos + 1);
            require(starts[s] instanceof TypeInsnNode n && n.getOpcode() == NEW && n.desc.equals(J)
                    && original.get(pos - 5).getOpcode() == DUP
                    && load(original.get(pos - 4), 11)
                    && call(original.get(pos - 3), INVOKEVIRTUAL, L, getters[s][0], "()" + POINT)
                    && load(original.get(pos - 2), 11)
                    && call(original.get(pos - 1), INVOKEVIRTUAL, L, getters[s][1], "()" + POINT)
                    && stores[s] instanceof VarInsnNode v && v.getOpcode() == ASTORE && v.var == 12 + s,
                    "constructor stencil " + s);
        }
        int lastFlagStore = -1;
        for (int s = 0; s < 3; s++) {
            int pos = original.indexOf(intersections.get(s));
            require(pos >= 3 && pos + 6 < original.size(), "intersection range");
            require(original.get(pos - 3) instanceof FieldInsnNode f && f.getOpcode() == GETSTATIC
                    && f.owner.equals(R) && f.name.equals("a") && f.desc.equals("L" + R + ";")
                    && load(original.get(pos - 2), 9) && load(original.get(pos - 1), 12 + s)
                    && original.get(pos + 1).getOpcode() == IFNULL
                    && original.get(pos + 2).getOpcode() == ICONST_1
                    && original.get(pos + 3).getOpcode() == GOTO
                    && original.get(pos + 4).getOpcode() == ICONST_0
                    && original.get(pos + 5) instanceof VarInsnNode v
                    && v.getOpcode() == ISTORE && v.var == 15 + s, "intersection flag " + s);
            lastFlagStore = pos + 5;
        }
        int eagerLine = lineAt(method, starts[0]);
        int[] ctorLines = ctors.subList(0, 3).stream().mapToInt(n -> lineAt(method, n)).toArray();
        int base = method.maxLocals, ready = base, lazy = base + 1, points = base + 2;
        method.maxLocals += 8;
        InsnList entry = new InsnList(); constant(entry, 0, ready); constant(entry, 0, lazy);
        for (int s = 0; s < 6; s++) {
            entry.add(new InsnNode(ACONST_NULL)); entry.add(new VarInsnNode(ASTORE, points + s));
        }
        method.instructions.insert(entry);

        LabelNode eagerStart = new LabelNode(), endpointStart = new LabelNode(), flagsDone = new LabelNode();
        InsnList warm = new InsnList();
        i(warm, ready); warm.add(new JumpInsnNode(IFEQ, eagerStart)); constant(warm, 1, lazy);
        for (int s = 0; s < 3; s++) {
            // Preserve six distinct getter observations in the original order;
            // l's endpoint fields are mutable, so do not collapse them to three.
            a(warm, 11); get(warm, L, getters[s][0], "()" + POINT);
            warm.add(new VarInsnNode(ASTORE, points + 2 * s));
            a(warm, 11); get(warm, L, getters[s][1], "()" + POINT);
            warm.add(new VarInsnNode(ASTORE, points + 2 * s + 1));
            preflight(warm, points + 2 * s, points + 2 * s + 1, 12 + s, ctorLines[s]);
            warm.add(new InsnNode(ACONST_NULL)); warm.add(new VarInsnNode(ASTORE, 12 + s));
        }
        warm.add(new JumpInsnNode(GOTO, endpointStart)); warm.add(eagerStart);
        line(warm, eagerStart, eagerLine); constant(warm, 0, lazy);
        method.instructions.insertBefore(starts[0], warm);
        InsnList endpoint = new InsnList(); endpoint.add(new JumpInsnNode(GOTO, flagsDone));
        endpoint.add(endpointStart);
        for (int s = 0; s < 3; s++) endpointFlag(endpoint, points + 2 * s, points + 2 * s + 1, 15 + s);
        endpoint.add(flagsDone); constant(endpoint, 1, ready);
        method.instructions.insert(original.get(lastFlagStore), endpoint);

        for (int s = 0; s < 3; s++) {
            int flag = 15 + s, matches = 0;
            for (int pos = lastFlagStore + 1; pos + 1 < original.size(); pos++) {
                if (!(original.get(pos) instanceof VarInsnNode v && v.getOpcode() == ILOAD && v.var == flag)) continue;
                require(original.get(pos + 1) instanceof JumpInsnNode j && j.getOpcode() == IFEQ,
                        "hit branch opcode");
                require(original.indexOf(ctors.get(3)) > pos, "hit branch before fourth path");
                InsnList delayed = new InsnList(); LabelNode already = new LabelNode();
                i(delayed, lazy); delayed.add(new JumpInsnNode(IFEQ, already));
                construct(delayed, points + 2 * s, points + 2 * s + 1, 12 + s); delayed.add(already);
                method.instructions.insert(original.get(pos + 1), delayed); matches++;
                break;
            }
            require(matches == 1, "hit branch " + s);
        }
        // Every original executable instruction, including the whole fourth
        // path and native private/inlined predicates, remains in original order.
        List<AbstractInsnNode> after = ops(method);
        int at = 0;
        for (AbstractInsnNode n : after) if (at < original.size() && n == original.get(at)) at++;
        require(at == original.size(), "original instructions removed or reordered");
        for (AbstractInsnNode n : method.instructions.toArray()) {
            if (n instanceof FrameNode) method.instructions.remove(n);
        }
        DataWriter writer = new DataWriter(reader, definitions);
        reader.accept(new ClassVisitor(ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc,
                    String signature, String[] exceptions) {
                MethodVisitor output = super.visitMethod(access, name, desc, signature, exceptions);
                if (name.equals("c") && desc.equals("()V")) {
                    method.accept(output); return null;
                }
                return output; // ASM copies other method bodies without hierarchy loading.
            }
        }, 0);
        return writer.toByteArray();
    }

    private static final class DataWriter extends ClassWriter {
        private final Function<String, byte[]> definitions;
        private final Map<String, ClassReader> metadata = new HashMap<>();
        DataWriter(ClassReader reader, Function<String, byte[]> definitions) {
            super(reader, COMPUTE_FRAMES | COMPUTE_MAXS); this.definitions = definitions;
        }
        private ClassReader type(String name) {
            return metadata.computeIfAbsent(name, n -> {
                byte[] bytes = definitions.apply(n);
                require(bytes != null, "unresolved hierarchy bytes " + n);
                ClassReader reader = new ClassReader(bytes);
                require(reader.getClassName().equals(n), "hierarchy name mismatch " + n);
                return reader;
            });
        }
        private boolean ancestor(String ancestor, String child) {
            if (ancestor.equals(child) || ancestor.equals("java/lang/Object")) return true;
            ClassReader c = type(child);
            if (c.getSuperName() != null && ancestor(ancestor, c.getSuperName())) return true;
            for (String iface : c.getInterfaces()) if (ancestor(ancestor, iface)) return true;
            return false;
        }
        @Override protected String getCommonSuperClass(String a, String b) {
            require(!a.startsWith("[") && !b.startsWith("["), "unreviewed array merge");
            if (ancestor(a, b)) return a;
            if (ancestor(b, a)) return b;
            if ((type(a).getAccess() & ACC_INTERFACE) != 0 || (type(b).getAccess() & ACC_INTERFACE) != 0)
                return "java/lang/Object";
            do { a = type(a).getSuperName(); } while (!ancestor(a, b));
            return a;
        }
    }

    static byte[] platformBytes(String name) {
        if (!(name.startsWith("java/") || name.startsWith("javax/") || name.startsWith("jdk/")
                || name.startsWith("sun/"))) return null;
        try (InputStream input = ClassLoader.getSystemResourceAsStream(name + ".class")) {
            return input == null ? null : input.readAllBytes();
        } catch (Exception error) { throw new IllegalArgumentException("platform metadata " + name, error); }
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 2, "official jar and new output directory required");
        Path input = Path.of(args[0]); String jarSha;
        try (InputStream stream = Files.newInputStream(input)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] block = new byte[65536]; int count;
            while ((count = stream.read(block)) != -1) digest.update(block, 0, count);
            jarSha = HexFormat.of().formatHex(digest.digest());
        }
        require(JARS.containsKey(jarSha), "unreviewed official JAR");
        try (ZipFile jar = new ZipFile(input.toFile())) {
            Function<String, byte[]> definitions = name -> {
                var entry = jar.getEntry(name + ".class");
                if (entry == null) return platformBytes(name);
                try (InputStream stream = jar.getInputStream(entry)) { return stream.readAllBytes(); }
                catch (Exception error) { throw new IllegalArgumentException(name, error); }
            };
            byte[] host = definitions.apply(H);
            require(HOSTS.contains(sha(host)), "unreviewed host class");
            byte[] output = patchShape(host, definitions);
            Path directory = Path.of(args[1]); Files.createDirectory(directory);
            Files.write(directory.resolve("h.class"), output);
            StringBuilder pins = new StringBuilder("version=" + JARS.get(jarSha) + "\njarSha=" + jarSha + "\n");
            for (String name : List.of(H, J, T, L, R, V)) pins.append(name).append('=').append(sha(definitions.apply(name))).append('\n');
            pins.append("hOutput=").append(sha(output)).append("\nofficialClassesExecuted=false\nproductionIntegrated=false\n");
            Files.writeString(directory.resolve("pins.txt"), pins);
            System.out.println("LAZY_EDGE_STATIC_PROTOTYPE PASS version=" + JARS.get(jarSha)
                    + " sha=" + sha(output) + " officialClassesExecuted=false");
        }
    }
}
