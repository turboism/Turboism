package dev.turboism.validation.tlindex.diagnostic;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Array;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.objectweb.asm.*;

/** Core-ASM definition fingerprint, independent of pool indices, debug and frames. */
final class DefinitionFingerprint implements Opcodes {
    private DefinitionFingerprint() {}
    private static List<Object> row(Object... items) { return Arrays.asList(items); }

    static String of(byte[] bytes) {
        List<Object> header = new ArrayList<>();
        TreeMap<String, List<Object>> fields = new TreeMap<>(), methods = new TreeMap<>();
        new ClassReader(bytes).accept(new ClassVisitor(ASM9) {
            @Override public void visit(int version, int access, String name, String signature,
                    String superName, String[] interfaces) {
                header.add(row("class", version, access, name, signature, superName, interfaces));
            }
            @Override public void visitNestHost(String name) { header.add(row("nestHost", name)); }
            @Override public void visitNestMember(String name) { header.add(row("nestMember", name)); }
            @Override public void visitOuterClass(String owner, String name, String desc) {
                header.add(row("outer", owner, name, desc));
            }
            @Override public void visitInnerClass(String name, String outer, String inner, int access) {
                header.add(row("inner", name, outer, inner, access));
            }
            @Override public void visitPermittedSubclass(String name) { header.add(row("permitted", name)); }
            @Override public void visitAttribute(Attribute attribute) { reject(attribute); }
            @Override public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                return annotation(header, row("annotation", desc, visible));
            }
            @Override public AnnotationVisitor visitTypeAnnotation(int ref, TypePath path, String desc, boolean visible) {
                return annotation(header, row("typeAnnotation", ref, path == null ? null : path.toString(), desc, visible));
            }
            @Override public RecordComponentVisitor visitRecordComponent(String name, String desc, String signature) {
                throw new IllegalArgumentException("record definitions are outside this admission stencil");
            }
            @Override public ModuleVisitor visitModule(String name, int access, String version) {
                throw new IllegalArgumentException("module definitions are outside this admission stencil");
            }
            @Override public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                List<Object> data = new ArrayList<>(); data.add(row("field", access, name, desc, signature, value));
                if (fields.put(name + ':' + desc, data) != null) throw new IllegalArgumentException("duplicate field");
                return new FieldVisitor(ASM9) {
                    @Override public AnnotationVisitor visitAnnotation(String d, boolean v) {
                        return annotation(data, row("annotation", d, v));
                    }
                    @Override public AnnotationVisitor visitTypeAnnotation(int r, TypePath p, String d, boolean v) {
                        return annotation(data, row("typeAnnotation", r, p == null ? null : p.toString(), d, v));
                    }
                    @Override public void visitAttribute(Attribute a) { reject(a); }
                };
            }
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                List<Object> data = new ArrayList<>(); data.add(row("method", access, name, desc, signature, exceptions));
                if (methods.put(name + desc, data) != null) throw new IllegalArgumentException("duplicate method");
                return new MethodVisitor(ASM9) {
                    private final IdentityHashMap<Label, Integer> positions = new IdentityHashMap<>();
                    private final List<Object> code = new ArrayList<>(), handlers = new ArrayList<>();
                    private int instruction;
                    private void op(Object... values) { code.add(row(values)); instruction++; }
                    @Override public void visitCode() { data.add(row("code")); }
                    @Override public void visitLabel(Label label) { positions.put(label, instruction); }
                    @Override public void visitInsn(int opcode) { op("insn", opcode); }
                    @Override public void visitIntInsn(int opcode, int operand) { op("int", opcode, operand); }
                    @Override public void visitVarInsn(int opcode, int local) { op("var", opcode, local); }
                    @Override public void visitTypeInsn(int opcode, String type) { op("type", opcode, type); }
                    @Override public void visitFieldInsn(int opcode, String owner, String n, String d) { op("field", opcode, owner, n, d); }
                    @Override public void visitMethodInsn(int opcode, String owner, String n, String d, boolean itf) { op("call", opcode, owner, n, d, itf); }
                    @Override public void visitInvokeDynamicInsn(String n, String d, Handle bootstrap, Object... args) { op("dynamic", n, d, bootstrap, args); }
                    @Override public void visitJumpInsn(int opcode, Label target) { op("jump", opcode, target); }
                    @Override public void visitLdcInsn(Object constant) { op("constant", constant); }
                    @Override public void visitIincInsn(int local, int increment) { op("increment", local, increment); }
                    @Override public void visitTableSwitchInsn(int min, int max, Label dflt, Label... targets) { op("table", min, max, dflt, targets); }
                    @Override public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] targets) { op("lookup", dflt, keys, targets); }
                    @Override public void visitMultiANewArrayInsn(String d, int dimensions) { op("multiArray", d, dimensions); }
                    @Override public void visitTryCatchBlock(Label start, Label end, Label handler, String type) { handlers.add(row(start, end, handler, type)); }
                    @Override public AnnotationVisitor visitAnnotation(String d, boolean v) { return annotation(data, row("annotation", d, v)); }
                    @Override public AnnotationVisitor visitAnnotationDefault() { return annotation(data, row("annotationDefault")); }
                    @Override public AnnotationVisitor visitParameterAnnotation(int parameter, String d, boolean v) {
                        return annotation(data, row("parameterAnnotation", parameter, d, v));
                    }
                    @Override public void visitAnnotableParameterCount(int count, boolean visible) { data.add(row("annotableParameters", count, visible)); }
                    @Override public AnnotationVisitor visitTypeAnnotation(int ref, TypePath path, String d, boolean v) {
                        return annotation(data, row("typeAnnotation", ref, path == null ? null : path.toString(), d, v));
                    }
                    @Override public AnnotationVisitor visitInsnAnnotation(int ref, TypePath path, String d, boolean v) {
                        return annotation(code, row("insnAnnotation", instruction - 1, ref, path == null ? null : path.toString(), d, v));
                    }
                    @Override public AnnotationVisitor visitTryCatchAnnotation(int ref, TypePath path, String d, boolean v) {
                        return annotation(handlers, row("handlerAnnotation", ref, path == null ? null : path.toString(), d, v));
                    }
                    @Override public AnnotationVisitor visitLocalVariableAnnotation(int ref, TypePath path, Label[] start, Label[] end, int[] index, String d, boolean v) {
                        return annotation(code, row("localAnnotation", ref, path == null ? null : path.toString(), start, end, index, d, v));
                    }
                    @Override public void visitAttribute(Attribute a) { reject(a); }
                    @Override public void visitEnd() {
                        data.add(row("instructions", resolve(code, positions)));
                        data.add(row("handlers", resolve(handlers, positions)));
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream stream = new DataOutputStream(buffer)) {
                encode(stream, row("definition-v1", header, new ArrayList<>(fields.values()), new ArrayList<>(methods.values())));
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray()));
        } catch (Exception error) { throw new IllegalArgumentException("definition encoding failed", error); }
    }

    private static void reject(Attribute attribute) {
        throw new IllegalArgumentException("unreviewed attribute " + attribute.type);
    }
    private static AnnotationVisitor annotation(List<Object> destination, List<Object> prefix) {
        TreeMap<String, Object> values = new TreeMap<>(); List<Object> entry = new ArrayList<>(prefix);
        entry.add(values); destination.add(entry); return values(values);
    }
    private static AnnotationVisitor values(Map<String, Object> destination) {
        return new AnnotationVisitor(ASM9) {
            @Override public void visit(String name, Object value) { destination.put(name == null ? "" : name, value); }
            @Override public void visitEnum(String name, String desc, String value) { destination.put(name == null ? "" : name, row("enum", desc, value)); }
            @Override public AnnotationVisitor visitAnnotation(String name, String desc) {
                TreeMap<String, Object> nested = new TreeMap<>(); destination.put(name == null ? "" : name, row("nested", desc, nested)); return values(nested);
            }
            @Override public AnnotationVisitor visitArray(String name) {
                List<Object> array = new ArrayList<>(); destination.put(name == null ? "" : name, array); return array(array);
            }
        };
    }
    private static AnnotationVisitor array(List<Object> destination) {
        return new AnnotationVisitor(ASM9) {
            @Override public void visit(String name, Object value) { destination.add(value); }
            @Override public void visitEnum(String name, String desc, String value) { destination.add(row("enum", desc, value)); }
            @Override public AnnotationVisitor visitAnnotation(String name, String desc) {
                TreeMap<String, Object> nested = new TreeMap<>(); destination.add(row("nested", desc, nested)); return values(nested);
            }
            @Override public AnnotationVisitor visitArray(String name) { List<Object> nested = new ArrayList<>(); destination.add(nested); return array(nested); }
        };
    }
    private static Object resolve(Object value, IdentityHashMap<Label, Integer> positions) {
        if (value instanceof Label label) {
            Integer position = positions.get(label);
            if (position == null) throw new IllegalArgumentException("unresolved instruction label");
            return row("position", position);
        }
        if (value instanceof List<?> list) return list.stream().map(v -> resolve(v, positions)).toList();
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> result = new TreeMap<>();
            map.forEach((k, v) -> result.put((String) k, resolve(v, positions))); return result;
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> result = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) result.add(resolve(Array.get(value, i), positions));
            return result;
        }
        return value;
    }
    private static void string(DataOutputStream out, String value) throws Exception {
        // JVM strings may contain unpaired surrogates; a replacing UTF-8 encoder
        // would collapse distinct constants. Preserve the exact UTF-16 units.
        out.writeInt(value.length());
        for (int i = 0; i < value.length(); i++) out.writeChar(value.charAt(i));
    }
    private static void encode(DataOutputStream out, Object value) throws Exception {
        if (value == null) { out.writeByte(0); return; }
        if (value instanceof String s) { out.writeByte(1); string(out, s); }
        else if (value instanceof Integer i) { out.writeByte(2); out.writeInt(i); }
        else if (value instanceof Long l) { out.writeByte(3); out.writeLong(l); }
        else if (value instanceof Float f) { out.writeByte(4); out.writeInt(Float.floatToRawIntBits(f)); }
        else if (value instanceof Double d) { out.writeByte(5); out.writeLong(Double.doubleToRawLongBits(d)); }
        else if (value instanceof Boolean b) { out.writeByte(6); out.writeBoolean(b); }
        else if (value instanceof Type t) { out.writeByte(7); string(out, t.getDescriptor()); }
        else if (value instanceof Handle h) { out.writeByte(8); encode(out, row(h.getTag(), h.getOwner(), h.getName(), h.getDesc(), h.isInterface())); }
        else if (value instanceof ConstantDynamic d) {
            out.writeByte(9); List<Object> args = new ArrayList<>();
            for (int i = 0; i < d.getBootstrapMethodArgumentCount(); i++) args.add(d.getBootstrapMethodArgument(i));
            encode(out, row(d.getName(), d.getDescriptor(), d.getBootstrapMethod(), args));
        } else if (value instanceof List<?> list) { out.writeByte(10); out.writeInt(list.size()); for (Object item : list) encode(out, item); }
        else if (value instanceof Map<?, ?> map) { out.writeByte(11); out.writeInt(map.size()); for (var item : map.entrySet()) { encode(out, item.getKey()); encode(out, item.getValue()); } }
        else if (value.getClass().isArray()) { out.writeByte(12); out.writeInt(Array.getLength(value)); for (int i = 0; i < Array.getLength(value); i++) encode(out, Array.get(value, i)); }
        else if (value instanceof Byte b) { out.writeByte(13); out.writeByte(b); }
        else if (value instanceof Short s) { out.writeByte(14); out.writeShort(s); }
        else if (value instanceof Character c) { out.writeByte(15); out.writeChar(c); }
        else throw new IllegalArgumentException("unreviewed constant " + value.getClass().getName());
    }
}
