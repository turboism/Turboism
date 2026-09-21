package dev.turboism.adapter.cubism.optimization.geometry;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Bypasses only the first temporary-reference-to-position map; native writes stay untouched. */
public final class WarpPositionProjectionTransformer implements ClassFileTransformer {
    /** Exact reviewed native consumer. */
    public static final String TARGET = "com/live2d/cubism/doc/model/interpolator/extendedInterpolation/CExtendedInterpolationExtension";
    /** The reviewed operation, not all point-reference callers. */
    public static final String METHOD = "updateInterpolatedForms_common";
    /** Full native consumer descriptor. */
    public static final String DESCRIPTOR = "(Ljava/util/List;Ljava/util/List;Ljava/util/List;Lcom/live2d/cubism/doc/model/interpolator/extendedInterpolation/ExtendedInterpolationType;F)V";
    private static final String FORM = "com/live2d/cubism/doc/model/ACForm";
    private final ClassLoader loader;
    private final Path artifact;
    private final List<String> shape;
    private volatile String beforeSha256, failure;
    private volatile int matches;

    /** Reference bytes come from the digest-pinned official artifact. */
    public WarpPositionProjectionTransformer(ClassLoader loader, Path artifact, byte[] reference) {
        this.loader = loader;
        this.artifact = artifact == null ? null : artifact.toAbsolutePath().normalize();
        shape = ReviewedMethodShape.read(reference, TARGET, METHOD, DESCRIPTOR);
        if (shape == null) throw new IllegalArgumentException("reviewed position consumer absent");
    }

    @Override public byte[] transform(Module module, ClassLoader actualLoader, String name, Class<?> type,
                                      ProtectionDomain domain, byte[] bytes) {
        if (actualLoader != loader || !TARGET.equals(name) || bytes == null) return null;
        try {
            if (artifact != null && !artifact.equals(Path.of(domain.getCodeSource().getLocation().toURI()).toAbsolutePath().normalize())) return null;
            if (!shape.equals(ReviewedMethodShape.read(bytes, TARGET, METHOD, DESCRIPTOR))) {
                failure = "native position consumer differs from reviewed method"; return null;
            }
            ClassReader reader = new ClassReader(bytes);
            int[] locals = {0}, calls = {0};
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                    if (!METHOD.equals(name) || !DESCRIPTOR.equals(desc)) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                            if (pointRefs(op, owner, name, desc)) calls[0]++;
                        }
                        @Override public void visitMaxs(int stack, int maxLocals) { locals[0] = maxLocals; }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
            if (calls[0] != 2) { failure = "expected two native point-ref call sites"; return null; }
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override protected ClassLoader getClassLoader() { return loader; }
            };
            Projection[] visitor = {null};
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                    MethodVisitor original = super.visitMethod(access, name, desc, sig, exceptions);
                    if (!METHOD.equals(name) || !DESCRIPTOR.equals(desc)) return original;
                    visitor[0] = new Projection(original, locals[0]); return visitor[0];
                }
            }, ClassReader.EXPAND_FRAMES);
            if (visitor[0] == null || !visitor[0].resumed) { failure = "position map join absent"; return null; }
            byte[] output = writer.toByteArray();
            if (beforeSha256 == null) beforeSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            matches++; return output;
        } catch (Exception | LinkageError rejected) { failure = rejected.toString(); return null; }
    }

    /** Successful transformations. */
    public int matches() { return matches; }
    /** First native bytes for restoration evidence. */
    public String beforeSha256() { return beforeSha256; }
    /** Most recent rejection. */
    public String failure() { return failure; }

    private static boolean pointRefs(int op, String owner, String name, String descriptor) {
        return op == Opcodes.INVOKEVIRTUAL && FORM.equals(owner) && "getAllPointRef".equals(name)
            && "()Ljava/util/List;".equals(descriptor);
    }

    private static final class Projection extends MethodVisitor {
        final int base;
        final Label resume = new Label();
        final List<Handler> handlers = new ArrayList<>();
        boolean injected, resumed;
        Projection(MethodVisitor original, int base) { super(Opcodes.ASM9, original); this.base = base; }
        @Override public void visitTryCatchBlock(Label start, Label end, Label target, String type) {
            handlers.add(new Handler(start, end, target, type));
        }
        @Override public void visitMethodInsn(int op, String owner, String name, String descriptor, boolean itf) {
            if (!injected && pointRefs(op, owner, name, descriptor)) {
                injected = true;
                // Original stack contains only the ACForm input; the enclosing output collection is in a local.
                super.visitVarInsn(Opcodes.ASTORE, base);
                Label start = new Label(), end = new Label(), error = new Label(), discard = new Label(), nativePath = new Label();
                super.visitTryCatchBlock(start, end, error, "java/lang/Throwable");
                super.visitLabel(start);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false);
                super.visitLdcInsn(WarpPositionProjectionBridge.CALLBACK_PROPERTY);
                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                super.visitInsn(Opcodes.DUP); super.visitTypeInsn(Opcodes.INSTANCEOF, "java/util/function/Function");
                super.visitJumpInsn(Opcodes.IFEQ, discard);
                super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Function");
                super.visitVarInsn(Opcodes.ALOAD, base);
                super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
                super.visitInsn(Opcodes.DUP); super.visitTypeInsn(Opcodes.INSTANCEOF, "java/util/List");
                super.visitJumpInsn(Opcodes.IFEQ, discard);
                super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/List");
                super.visitLabel(end); super.visitJumpInsn(Opcodes.GOTO, resume);
                super.visitLabel(discard); super.visitInsn(Opcodes.POP); super.visitJumpInsn(Opcodes.GOTO, nativePath);
                super.visitLabel(error); super.visitInsn(Opcodes.POP);
                super.visitLabel(nativePath); super.visitVarInsn(Opcodes.ALOAD, base);
            }
            super.visitMethodInsn(op, owner, name, descriptor, itf);
        }
        @Override public void visitTypeInsn(int op, String type) {
            super.visitTypeInsn(op, type);
            if (injected && !resumed && op == Opcodes.CHECKCAST && "java/util/List".equals(type)) {
                super.visitLabel(resume); resumed = true;
            }
        }
        @Override public void visitMaxs(int stack, int locals) {
            for (Handler h : handlers) super.visitTryCatchBlock(h.start, h.end, h.target, h.type);
            super.visitMaxs(stack, Math.max(locals, base + 1));
        }
    }
    private record Handler(Label start, Label end, Label target, String type) { }
}
