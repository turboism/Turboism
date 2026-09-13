package dev.turboism.adapter.cubism.optimization;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.stream.Collectors;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Constant-pool-independent instruction, branch and handler identity for a reviewed native method. */
public final class ReviewedMethodShape {
    private ReviewedMethodShape() { }

    /** Returns immutable semantic instructions, or null for absent/duplicate/abstract/native methods. */
    public static List<String> read(byte[] bytes, String owner, String methodName, String descriptor) {
        ClassReader reader = new ClassReader(bytes);
        if (!owner.equals(reader.getClassName())) return null;
        List<List<String>> results = new ArrayList<>(); int[] count = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                if (!methodName.equals(name) || !descriptor.equals(desc)) return null;
                count[0]++;
                if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    final List<String> ops = new ArrayList<>();
                    final IdentityHashMap<Label, Integer> positions = new IdentityHashMap<>();
                    final List<Jump> jumps = new ArrayList<>();
                    final List<Switch> switches = new ArrayList<>();
                    final List<Handler> handlers = new ArrayList<>();
                    @Override public void visitLabel(Label label) { positions.put(label, ops.size()); }
                    @Override public void visitInsn(int op) { ops.add("op:" + op); }
                    @Override public void visitIntInsn(int op, int operand) { ops.add("int:" + op + ":" + operand); }
                    @Override public void visitVarInsn(int op, int variable) { ops.add("var:" + op + ":" + variable); }
                    @Override public void visitTypeInsn(int op, String type) { ops.add("type:" + op + ":" + encode(type)); }
                    @Override public void visitFieldInsn(int op, String type, String field, String desc) { ops.add("field:" + op + ":" + encode(type) + ":" + encode(field) + ":" + encode(desc)); }
                    @Override public void visitMethodInsn(int op, String type, String method, String desc, boolean itf) { ops.add("call:" + op + ":" + encode(type) + ":" + encode(method) + ":" + encode(desc) + ":" + itf); }
                    @Override public void visitLdcInsn(Object value) { ops.add("ldc:" + encode(constant(value))); }
                    @Override public void visitIincInsn(int variable, int increment) { ops.add("inc:" + variable + ":" + increment); }
                    @Override public void visitJumpInsn(int op, Label label) { jumps.add(new Jump(ops.size(), op, label)); ops.add("jump"); }
                    @Override public void visitTableSwitchInsn(int min, int max, Label fallback, Label... targets) {
                        int[] keys = new int[targets.length]; for (int i = 0; i < keys.length; i++) keys[i] = min + i;
                        switches.add(new Switch(ops.size(), "table:" + min + ":" + max, keys, fallback, targets.clone())); ops.add("switch");
                    }
                    @Override public void visitLookupSwitchInsn(Label fallback, int[] keys, Label[] targets) { switches.add(new Switch(ops.size(), "lookup", keys.clone(), fallback, targets.clone())); ops.add("switch"); }
                    @Override public void visitInvokeDynamicInsn(String name, String desc, Handle bootstrap, Object... args) {
                        ops.add("dynamic:" + encode(name) + ":" + encode(desc) + ":" + encode(constant(bootstrap)) + ":" + constants(args));
                    }
                    @Override public void visitMultiANewArrayInsn(String desc, int dimensions) { ops.add("multiarray:" + encode(desc) + ":" + dimensions); }
                    @Override public void visitTryCatchBlock(Label start, Label end, Label target, String type) { handlers.add(new Handler(start, end, target, type)); }
                    int at(Label label) {
                        Integer value = positions.get(label);
                        if (value == null) throw new IllegalArgumentException("unresolved native method label");
                        return value;
                    }
                    @Override public void visitEnd() {
                        for (Jump jump : jumps) ops.set(jump.index(), "jump:" + jump.opcode() + ":" + at(jump.target()));
                        for (Switch branch : switches) {
                            String targets = Arrays.stream(branch.targets()).map(label -> Integer.toString(at(label))).collect(Collectors.joining(","));
                            ops.set(branch.index(), "switch:" + branch.kind() + ":" + Arrays.toString(branch.keys()) + ":" + at(branch.fallback()) + ":" + targets);
                        }
                        List<String> result = new ArrayList<>(); result.add("access:" + access); result.addAll(ops);
                        for (Handler handler : handlers) result.add("handler:" + at(handler.start()) + ":" + at(handler.end()) + ":" + at(handler.target()) + ":" + encode(handler.type()));
                        results.add(List.copyOf(result));
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0] == 1 && results.size() == 1 ? results.get(0) : null;
    }

    private static String encode(String text) {
        return text == null ? "~" : Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
    private static String constants(Object[] values) {
        return Arrays.stream(values).map(value -> encode(constant(value))).collect(Collectors.joining(","));
    }
    private static String constant(Object value) {
        if (value instanceof String text) return "string:" + encode(text);
        if (value instanceof Float number) return "float:" + Integer.toHexString(Float.floatToRawIntBits(number));
        if (value instanceof Double number) return "double:" + Long.toHexString(Double.doubleToRawLongBits(number));
        if (value instanceof Integer || value instanceof Long) return value.getClass().getName() + ":" + value;
        if (value instanceof Type type) return "type:" + encode(type.getDescriptor());
        if (value instanceof Handle handle) return "handle:" + handle.getTag() + ":" + encode(handle.getOwner()) + ":" + encode(handle.getName()) + ":" + encode(handle.getDesc()) + ":" + handle.isInterface();
        if (value instanceof ConstantDynamic dynamic) {
            Object[] args = new Object[dynamic.getBootstrapMethodArgumentCount()];
            for (int i = 0; i < args.length; i++) args[i] = dynamic.getBootstrapMethodArgument(i);
            return "constantDynamic:" + encode(dynamic.getName()) + ":" + encode(dynamic.getDescriptor()) + ":" + encode(constant(dynamic.getBootstrapMethod())) + ":" + constants(args);
        }
        throw new IllegalArgumentException("unsupported native classfile constant");
    }
    private record Jump(int index, int opcode, Label target) { }
    private record Switch(int index, String kind, int[] keys, Label fallback, Label[] targets) { }
    private record Handler(Label start, Label end, Label target, String type) { }
}
