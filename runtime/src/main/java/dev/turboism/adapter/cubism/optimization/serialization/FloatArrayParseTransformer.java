package dev.turboism.adapter.cubism.optimization.serialization;

import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Exact native float-array method substitution; unsupported callbacks fall through unchanged. */
public final class FloatArrayParseTransformer implements ClassFileTransformer {
    /** Reviewed 5.3.02 float-array serializer owner. */
    public static final String TARGET = "com/live2d/serialize/impl/G";
    private static final String DESCRIPTOR = "(ILjava/util/List;)Ljava/lang/Object;";
    private final ClassLoader expectedLoader;
    private final Path artifact;
    private final List<String> reviewedShape;
    private volatile String beforeSha256;
    private volatile String failure;
    private volatile int matches;

    /** The installer supplies reference bytes from the digest-pinned official artifact. */
    public FloatArrayParseTransformer(ClassLoader loader, Path artifact, byte[] reviewedClass) {
        expectedLoader = loader;
        this.artifact = artifact == null ? null : artifact.toAbsolutePath().normalize();
        reviewedShape = shape(reviewedClass);
        if (reviewedShape == null) throw new IllegalArgumentException("reviewed float-array method is absent");
    }

    @Override public byte[] transform(Module module, ClassLoader loader, String name, Class<?> redefining,
                                      ProtectionDomain domain, byte[] bytes) {
        if (loader != expectedLoader || !TARGET.equals(name) || bytes == null) return null;
        try {
            if (artifact != null && !artifact.equals(Path.of(domain.getCodeSource().getLocation().toURI()).toAbsolutePath().normalize())) return null;
            if (!reviewedShape.equals(shape(bytes))) {
                failure = "native float-array method body differs from reviewed artifact";
                return null;
            }
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override protected ClassLoader getClassLoader() { return expectedLoader; }
            };
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override public MethodVisitor visitMethod(int access, String method, String desc, String signature, String[] exceptions) {
                    MethodVisitor delegate = super.visitMethod(access, method, desc, signature, exceptions);
                    if (!method.equals("a") || !desc.equals(DESCRIPTOR)) return delegate;
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override public void visitCode() {
                            super.visitCode();
                            Label start = new Label(), end = new Label(), handler = new Label();
                            Label discard = new Label(), nativePath = new Label();
                            super.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
                            super.visitLabel(start);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false);
                            super.visitLdcInsn(FloatArrayParseBridge.CALLBACK_PROPERTY);
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                            super.visitInsn(Opcodes.DUP);
                            super.visitTypeInsn(Opcodes.INSTANCEOF, "java/util/function/BiFunction");
                            super.visitJumpInsn(Opcodes.IFEQ, discard);
                            super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/BiFunction");
                            super.visitVarInsn(Opcodes.ILOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
                            super.visitVarInsn(Opcodes.ALOAD, 2);
                            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiFunction", "apply", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
                            super.visitInsn(Opcodes.DUP);
                            super.visitTypeInsn(Opcodes.INSTANCEOF, "[F");
                            super.visitJumpInsn(Opcodes.IFEQ, discard);
                            super.visitTypeInsn(Opcodes.CHECKCAST, "[F");
                            super.visitInsn(Opcodes.DUP);
                            super.visitInsn(Opcodes.ARRAYLENGTH);
                            super.visitVarInsn(Opcodes.ILOAD, 1);
                            super.visitJumpInsn(Opcodes.IF_ICMPNE, discard);
                            super.visitLabel(end);
                            super.visitInsn(Opcodes.ARETURN);
                            super.visitLabel(discard);
                            super.visitInsn(Opcodes.POP);
                            super.visitJumpInsn(Opcodes.GOTO, nativePath);
                            super.visitLabel(handler);
                            super.visitInsn(Opcodes.POP);
                            super.visitLabel(nativePath);
                        }
                    };
                }
            }, ClassReader.EXPAND_FRAMES);
            byte[] output = writer.toByteArray();
            if (beforeSha256 == null) beforeSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            matches++;
            return output;
        } catch (Exception | LinkageError rejected) {
            failure = rejected.getClass().getName();
            return null;
        }
    }

    /** Successful transformations in the attested loader. */
    public int matches() { return matches; }
    /** First retransformation input hash for restoration evidence. */
    public String beforeSha256() { return beforeSha256; }
    /** Admission/transformation failure, if any. */
    public String failure() { return failure; }

    // Compare semantic instructions and branch targets, not constant-pool indexes,
    // debug labels or stack-map encoding (which the JVM may rewrite on retransform).
    private static List<String> shape(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        if (!TARGET.equals(reader.getClassName())) return null;
        List<List<String>> result = new ArrayList<>(); int[] count = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                if (!name.equals("a") || !desc.equals(DESCRIPTOR)) return null;
                count[0]++;
                if ((access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    final List<String> ops = new ArrayList<>();
                    final IdentityHashMap<Label, Integer> labels = new IdentityHashMap<>();
                    final List<Jump> jumps = new ArrayList<>();
                    boolean unsupported;
                    @Override public void visitLabel(Label label) { labels.put(label, ops.size()); }
                    @Override public void visitInsn(int opcode) { ops.add("op:" + opcode); }
                    @Override public void visitIntInsn(int opcode, int operand) { ops.add("int:" + opcode + ":" + operand); }
                    @Override public void visitVarInsn(int opcode, int variable) { ops.add("var:" + opcode + ":" + variable); }
                    @Override public void visitTypeInsn(int opcode, String type) { ops.add("type:" + opcode + ":" + type); }
                    @Override public void visitFieldInsn(int opcode, String owner, String field, String descriptor) { ops.add("field:" + opcode + ":" + owner + ":" + field + ":" + descriptor); }
                    @Override public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean itf) { ops.add("call:" + opcode + ":" + owner + ":" + method + ":" + descriptor + ":" + itf); }
                    @Override public void visitLdcInsn(Object value) { ops.add("ldc:" + value.getClass().getName() + ":" + value); }
                    @Override public void visitIincInsn(int variable, int increment) { ops.add("inc:" + variable + ":" + increment); }
                    @Override public void visitJumpInsn(int opcode, Label target) { jumps.add(new Jump(ops.size(), opcode, target)); ops.add("jump"); }
                    @Override public void visitTryCatchBlock(Label a, Label b, Label c, String type) { unsupported = true; }
                    @Override public void visitTableSwitchInsn(int min, int max, Label d, Label... labels) { unsupported = true; }
                    @Override public void visitLookupSwitchInsn(Label d, int[] keys, Label[] labels) { unsupported = true; }
                    @Override public void visitInvokeDynamicInsn(String name, String desc, org.objectweb.asm.Handle handle, Object... args) { unsupported = true; }
                    @Override public void visitMultiANewArrayInsn(String desc, int dimensions) { unsupported = true; }
                    @Override public void visitEnd() {
                        for (Jump jump : jumps) {
                            Integer target = labels.get(jump.target());
                            if (target == null) unsupported = true;
                            ops.set(jump.index(), "jump:" + jump.opcode() + ":" + target);
                        }
                        if (!unsupported) {ops.add(0, "access:" + access); result.add(List.copyOf(ops));}
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0] == 1 && result.size() == 1 ? result.get(0) : null;
    }

    private record Jump(int index, int opcode, Label target) { }
}
