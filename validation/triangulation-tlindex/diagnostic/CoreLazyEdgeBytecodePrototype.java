package dev.turboism.validation.tlindex.diagnostic;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.zip.ZipFile;
import org.objectweb.asm.*;

/** ASM-core port for offline composition research. Not registered in production. */
public final class CoreLazyEdgeBytecodePrototype implements Opcodes {
    private static final String P = "com/live2d/graphics3d/editableMesh/triangulation/";
    private static final String H = P + "h", J = P + "j", T = P + "TriPoint", L = P + "l", R = P + "r";
    private static final String V = "com/live2d/graphics3d/type/GVector2";
    private static final String POINT = "L" + T + ";", CTOR = "(" + POINT + POINT + ")V";
    private static final String PAIR = "(L" + J + ";L" + J + ";)L" + V + ";";
    private static final String ENDPOINT = "(" + ("L" + V + ";").repeat(4) + ")L" + V + ";";
    private static final Map<String, String> JARS = Map.of(
            "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd", "5203",
            "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21", "5302",
            "bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166", "5303");
    private static final List<String> HOSTS = List.of(
            "ef4a5eb2f0e1b0ac0295f76146a513a326729cfe4526884104cbb27978c52543",
            "5aa7031e3726355fde25d6d4412f0a295a3725cb8e510a3076007f3270445f0d");
    private CoreLazyEdgeBytecodePrototype() {}
    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private record Event(int opcode, int local, String owner, String name, String descriptor,
                         int line, Consumer<MethodVisitor> replay) {}
    private static boolean load(Event event, int local) { return event.opcode == ALOAD && event.local == local; }
    private static boolean call(Event event, int opcode, String owner, String name, String descriptor) {
        return event.opcode == opcode && owner.equals(event.owner)
                && name.equals(event.name) && descriptor.equals(event.descriptor);
    }
    private static void a(MethodVisitor output, int local) { output.visitVarInsn(ALOAD, local); }
    private static void i(MethodVisitor output, int local) { output.visitVarInsn(ILOAD, local); }
    private static void constant(MethodVisitor output, int value, int local) {
        output.visitInsn(value == 0 ? ICONST_0 : ICONST_1); output.visitVarInsn(ISTORE, local);
    }
    private static void get(MethodVisitor output, String owner, String name, String descriptor) {
        output.visitMethodInsn(INVOKEVIRTUAL, owner, name, descriptor, false);
    }
    private static void construct(MethodVisitor output, int first, int second, int target) {
        output.visitTypeInsn(NEW, J); output.visitInsn(DUP); a(output, first); a(output, second);
        output.visitMethodInsn(INVOKESPECIAL, J, "<init>", CTOR, false); output.visitVarInsn(ASTORE, target);
    }
    private static void line(MethodVisitor output, Label label, int number) {
        if (number >= 0) output.visitLineNumber(number, label);
    }
    private static void preflight(MethodVisitor output, int first, int second, int target, int sourceLine) {
        Label fail = new Label(), good = new Label();
        a(output, first); output.visitJumpInsn(IFNULL, fail);
        a(output, second); output.visitJumpInsn(IFNULL, fail);
        a(output, first); get(output, T, "getIndex", "()I");
        a(output, second); get(output, T, "getIndex", "()I");
        output.visitJumpInsn(IF_ICMPNE, good);
        output.visitFieldInsn(GETSTATIC, "kotlin/_Assertions", "ENABLED", "Z");
        output.visitJumpInsn(IFEQ, good); output.visitLabel(fail); line(output, fail, sourceLine);
        construct(output, first, second, target); output.visitLabel(good);
    }
    private static void endpointFlag(MethodVisitor output, int first, int second, int flag) {
        output.visitFieldInsn(GETSTATIC, R, "a", "L" + R + ";");
        a(output, 9); get(output, J, "a", "()" + POINT);
        a(output, 9); get(output, J, "b", "()" + POINT);
        a(output, first); a(output, second); get(output, R, "a", ENDPOINT);
        Label none = new Label(), done = new Label();
        output.visitJumpInsn(IFNULL, none); output.visitInsn(ICONST_1); output.visitJumpInsn(GOTO, done);
        output.visitLabel(none); output.visitInsn(ICONST_0); output.visitLabel(done);
        output.visitVarInsn(ISTORE, flag);
    }

    // Owned tests and SHA-bound offline CLI only. Does not establish actual
    // dependency purity, final JVM definitions or a production admission gate.
    static byte[] patchShape(byte[] bytes, Function<String, byte[]> definitions) {
        ClassReader reader = new ClassReader(bytes); require(reader.getClassName().equals(H), "host owner");
        DataWriter writer = new DataWriter(reader, definitions); int[] methods = {0};
        reader.accept(new ClassVisitor(ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                MethodVisitor output = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals("c") || !descriptor.equals("()V")) return output;
                require(access == (ACC_PUBLIC | ACC_FINAL), "c access"); methods[0]++;
                return new RecordingMethod(output);
            }
        }, ClassReader.SKIP_FRAMES);
        require(methods[0] == 1, "exact c method"); return writer.toByteArray();
    }

    private static final class RecordingMethod extends MethodVisitor {
        private final List<Event> events = new ArrayList<>();
        private int sourceLine = -1, maxLocals;
        RecordingMethod(MethodVisitor output) { super(ASM9, output); }
        private void event(int opcode, int local, String owner, String name, String descriptor,
                Consumer<MethodVisitor> replay) {
            events.add(new Event(opcode, local, owner, name, descriptor, sourceLine, replay));
        }
        private void plain(int opcode, Consumer<MethodVisitor> replay) { event(opcode, -1, null, null, null, replay); }
        @Override public void visitCode() { /* Replayed once before the added locals. */ }
        @Override public void visitInsn(int opcode) { plain(opcode, v -> v.visitInsn(opcode)); }
        @Override public void visitIntInsn(int opcode, int operand) { plain(opcode, v -> v.visitIntInsn(opcode, operand)); }
        @Override public void visitVarInsn(int opcode, int local) {
            event(opcode, local, null, null, null, v -> v.visitVarInsn(opcode, local));
        }
        @Override public void visitTypeInsn(int opcode, String type) {
            event(opcode, -1, type, null, null, v -> v.visitTypeInsn(opcode, type));
        }
        @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            event(opcode, -1, owner, name, descriptor, v -> v.visitFieldInsn(opcode, owner, name, descriptor));
        }
        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
            event(opcode, -1, itf ? null : owner, name, descriptor,
                    v -> v.visitMethodInsn(opcode, owner, name, descriptor, itf));
        }
        @Override public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap, Object... arguments) {
            Object[] copied = arguments.clone();
            plain(INVOKEDYNAMIC, v -> v.visitInvokeDynamicInsn(name, descriptor, bootstrap, copied));
        }
        @Override public void visitJumpInsn(int opcode, Label label) { plain(opcode, v -> v.visitJumpInsn(opcode, label)); }
        @Override public void visitLabel(Label label) { plain(-1, v -> v.visitLabel(label)); }
        @Override public void visitFrame(int type, int nl, Object[] locals, int ns, Object[] stack) {
            throw new IllegalArgumentException("frames must be skipped and recomputed");
        }
        @Override public void visitLdcInsn(Object value) { plain(LDC, v -> v.visitLdcInsn(value)); }
        @Override public void visitIincInsn(int local, int increment) { plain(IINC, v -> v.visitIincInsn(local, increment)); }
        @Override public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            Label[] copied = labels.clone(); plain(TABLESWITCH, v -> v.visitTableSwitchInsn(min, max, dflt, copied));
        }
        @Override public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            int[] copiedKeys = keys.clone(); Label[] copiedLabels = labels.clone();
            plain(LOOKUPSWITCH, v -> v.visitLookupSwitchInsn(dflt, copiedKeys, copiedLabels));
        }
        @Override public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
            plain(MULTIANEWARRAY, v -> v.visitMultiANewArrayInsn(descriptor, dimensions));
        }
        @Override public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            throw new IllegalArgumentException("unexpected c handler");
        }
        @Override public void visitLineNumber(int number, Label start) {
            sourceLine = number; plain(-1, v -> v.visitLineNumber(number, start));
        }
        @Override public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end, int index) {
            plain(-1, v -> v.visitLocalVariable(name, descriptor, signature, start, end, index));
        }
        @Override public AnnotationVisitor visitInsnAnnotation(int ref, TypePath path, String descriptor, boolean visible) {
            throw new IllegalArgumentException("unexpected instruction annotation");
        }
        @Override public AnnotationVisitor visitTryCatchAnnotation(int ref, TypePath path, String descriptor, boolean visible) {
            throw new IllegalArgumentException("unexpected handler annotation");
        }
        @Override public AnnotationVisitor visitLocalVariableAnnotation(int ref, TypePath path, Label[] start,
                Label[] end, int[] index, String descriptor, boolean visible) {
            throw new IllegalArgumentException("unexpected local annotation");
        }
        @Override public void visitAttribute(Attribute attribute) {
            throw new IllegalArgumentException("unreviewed c attribute " + attribute.type);
        }
        @Override public void visitMaxs(int stack, int locals) { maxLocals = locals; }
        @Override public void visitEnd() {
            List<Event> original = events.stream().filter(e -> e.opcode >= 0).toList();
            List<Event> constructors = original.stream().filter(e -> call(e, INVOKESPECIAL, J, "<init>", CTOR)).toList();
            List<Event> intersections = original.stream().filter(e -> call(e, INVOKEVIRTUAL, R, "a", PAIR)).toList();
            require(constructors.size() == 4 && intersections.size() == 4, "four original native sites");
            require(original.indexOf(constructors.get(2)) < original.indexOf(intersections.get(0)), "construction order");
            require(original.indexOf(intersections.get(2)) < original.indexOf(constructors.get(3)), "separate fourth path");
            Event[] starts = new Event[3]; String[][] getters = {{"a", "b"}, {"b", "c"}, {"c", "a"}};
            for (int s = 0; s < 3; s++) {
                int pos = original.indexOf(constructors.get(s)); require(pos >= 6 && pos + 1 < original.size(), "constructor range");
                Event start = original.get(pos - 6), store = original.get(pos + 1); starts[s] = start;
                require(start.opcode == NEW && J.equals(start.owner) && original.get(pos - 5).opcode == DUP
                        && load(original.get(pos - 4), 11)
                        && call(original.get(pos - 3), INVOKEVIRTUAL, L, getters[s][0], "()" + POINT)
                        && load(original.get(pos - 2), 11)
                        && call(original.get(pos - 1), INVOKEVIRTUAL, L, getters[s][1], "()" + POINT)
                        && store.opcode == ASTORE && store.local == 12 + s, "constructor stencil " + s);
            }
            int lastFlagStore = -1;
            for (int s = 0; s < 3; s++) {
                int pos = original.indexOf(intersections.get(s)); require(pos >= 3 && pos + 6 < original.size(), "intersection range");
                require(call(original.get(pos - 3), GETSTATIC, R, "a", "L" + R + ";")
                        && load(original.get(pos - 2), 9) && load(original.get(pos - 1), 12 + s)
                        && original.get(pos + 1).opcode == IFNULL && original.get(pos + 2).opcode == ICONST_1
                        && original.get(pos + 3).opcode == GOTO && original.get(pos + 4).opcode == ICONST_0
                        && original.get(pos + 5).opcode == ISTORE && original.get(pos + 5).local == 15 + s,
                        "intersection flag " + s);
                lastFlagStore = pos + 5;
            }
            Map<Event, Integer> delayedAt = new IdentityHashMap<>();
            for (int s = 0; s < 3; s++) {
                boolean found = false;
                for (int pos = lastFlagStore + 1; pos + 1 < original.size(); pos++) {
                    Event flag = original.get(pos);
                    if (flag.opcode != ILOAD || flag.local != 15 + s) continue;
                    Event branch = original.get(pos + 1);
                    require(branch.opcode == IFEQ && original.indexOf(constructors.get(3)) > pos, "hit branch " + s);
                    delayedAt.put(branch, s); found = true; break;
                }
                require(found, "missing hit branch " + s);
            }
            int ready = maxLocals, lazy = maxLocals + 1, points = maxLocals + 2;
            Label eagerStart = new Label(), endpointStart = new Label(), flagsDone = new Label();
            mv.visitCode(); constant(mv, 0, ready); constant(mv, 0, lazy);
            for (int s = 0; s < 6; s++) { mv.visitInsn(ACONST_NULL); mv.visitVarInsn(ASTORE, points + s); }
            Event lastFlag = original.get(lastFlagStore);
            for (Event event : events) {
                if (event == starts[0]) {
                    i(mv, ready); mv.visitJumpInsn(IFEQ, eagerStart); constant(mv, 1, lazy);
                    for (int s = 0; s < 3; s++) {
                        a(mv, 11); get(mv, L, getters[s][0], "()" + POINT); mv.visitVarInsn(ASTORE, points + 2 * s);
                        a(mv, 11); get(mv, L, getters[s][1], "()" + POINT); mv.visitVarInsn(ASTORE, points + 2 * s + 1);
                        preflight(mv, points + 2 * s, points + 2 * s + 1, 12 + s, constructors.get(s).line);
                        mv.visitInsn(ACONST_NULL); mv.visitVarInsn(ASTORE, 12 + s);
                    }
                    mv.visitJumpInsn(GOTO, endpointStart); mv.visitLabel(eagerStart);
                    line(mv, eagerStart, starts[0].line); constant(mv, 0, lazy);
                }
                // Replay every original event once, in its original order.
                event.replay.accept(mv);
                if (event == lastFlag) {
                    mv.visitJumpInsn(GOTO, flagsDone); mv.visitLabel(endpointStart);
                    for (int s = 0; s < 3; s++) endpointFlag(mv, points + 2 * s, points + 2 * s + 1, 15 + s);
                    mv.visitLabel(flagsDone); constant(mv, 1, ready);
                }
                Integer site = delayedAt.get(event);
                if (site != null) {
                    Label already = new Label(); i(mv, lazy); mv.visitJumpInsn(IFEQ, already);
                    construct(mv, points + 2 * site, points + 2 * site + 1, 12 + site); mv.visitLabel(already);
                }
            }
            mv.visitMaxs(0, maxLocals + 8); mv.visitEnd();
        }
    }
    private static final class DataWriter extends ClassWriter {
        private final Function<String, byte[]> definitions;
        private final Map<String, ClassReader> metadata = new HashMap<>();
        DataWriter(ClassReader reader, Function<String, byte[]> definitions) {
            super(reader, COMPUTE_FRAMES | COMPUTE_MAXS); this.definitions = definitions;
        }
        private ClassReader type(String name) {
            return metadata.computeIfAbsent(name, n -> {
                byte[] bytes = definitions.apply(n); require(bytes != null, "unresolved hierarchy bytes " + n);
                ClassReader reader = new ClassReader(bytes); require(reader.getClassName().equals(n), "hierarchy name mismatch " + n);
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
        @Override protected String getCommonSuperClass(String first, String second) {
            require(!first.startsWith("[") && !second.startsWith("["), "unreviewed array merge");
            if (ancestor(first, second)) return first; if (ancestor(second, first)) return second;
            if ((type(first).getAccess() & ACC_INTERFACE) != 0 || (type(second).getAccess() & ACC_INTERFACE) != 0) return "java/lang/Object";
            do { first = type(first).getSuperName(); } while (!ancestor(first, second));
            return first;
        }
    }
    private static byte[] platformBytes(String name) {
        if (!(name.startsWith("java/") || name.startsWith("javax/") || name.startsWith("jdk/") || name.startsWith("sun/"))) return null;
        try (InputStream input = ClassLoader.getSystemResourceAsStream(name + ".class")) {
            return input == null ? null : input.readAllBytes();
        } catch (Exception failure) { throw new IllegalArgumentException("platform metadata " + name, failure); }
    }
    public static void main(String[] args) throws Exception {
        require(args.length == 2 || args.length == 4 && args[2].equals("--compose"),
                "official jar, new output directory, optional --compose frozen-agent required");
        Path input = Path.of(args[0]); String jarSha;
        try (InputStream stream = Files.newInputStream(input)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] block = new byte[65536]; int count;
            while ((count = stream.read(block)) != -1) digest.update(block, 0, count);
            jarSha = HexFormat.of().formatHex(digest.digest());
        }
        require(JARS.containsKey(jarSha), "unreviewed official JAR");
        try (ZipFile jar = new ZipFile(input.toFile())) {
            Function<String, byte[]> definitions = name -> {
                var entry = jar.getEntry(name + ".class"); if (entry == null) return platformBytes(name);
                try (InputStream stream = jar.getInputStream(entry)) { return stream.readAllBytes(); }
                catch (Exception failure) { throw new IllegalArgumentException(name, failure); }
            };
            byte[] original = definitions.apply(H); require(HOSTS.contains(sha(original)), "unreviewed host class");
            byte[] baseline = original;
            if (args.length == 4) try (FrozenEdgeTransforms frozen = new FrozenEdgeTransforms(Path.of(args[3]))) {
                baseline = frozen.apply(original, false);
            }
            byte[] output = patchShape(baseline, definitions);
            Path directory = Path.of(args[1]); Files.createDirectory(directory);
            Files.write(directory.resolve("h.class"), output);
            Files.write(directory.resolve("h.before-lazy.class"), baseline);
            StringBuilder pins = new StringBuilder("version=" + JARS.get(jarSha) + "\njarSha=" + jarSha + "\n");
            for (String name : List.of(H, J, T, L, R, V)) pins.append(name).append('=').append(sha(definitions.apply(name))).append('\n');
            pins.append("hBeforeLazy=").append(sha(baseline)).append("\nhOutput=").append(sha(output))
                    .append("\ncomposed=").append(args.length == 4).append("\nofficialClassesExecuted=false\nproductionIntegrated=false\n");
            if (args.length == 4) pins.append("frozenAgentSha=").append(FrozenEdgeTransforms.reviewedSha()).append('\n');
            Files.writeString(directory.resolve("pins.txt"), pins);
            System.out.println("CORE_LAZY_EDGE_STATIC_PROTOTYPE PASS version=" + JARS.get(jarSha)
                    + " composed=" + (args.length == 4) + " sha=" + sha(output) + " officialClassesExecuted=false");
        }
    }
}
