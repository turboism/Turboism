package dev.turboism.adapter.cubism.optimization.geometry;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Experimental invocation-local matrix-product scratch space. No state or output
 * is shared between calls; original traversal, input getters and arithmetic order
 * remain intact. Requires separate exact artifact and native-math admission.
 */
public final class MatrixScratchTransformer implements ClassFileTransformer {
    /** Candidate opt-in property; absent means the original allocation path. */
    public static final String ENABLE_PROPERTY = "turboism.optimization.matrixScratch";
    /** Exact transform class containing the attested parent traversal. */
    public static final String OWNER = "com/live2d/graphics3d/component/GTransform";
    /** Exact native method under investigation. */
    public static final String METHOD = "getLocalToWorldMatrix";
    /** Matrix class whose native general multiplication is retained. */
    public static final String MATRIX = "com/live2d/graphics3d/type/GMatrix44";
    /** Exact parent-chain method descriptor. */
    public static final String DESCRIPTOR = "()L" + MATRIX + ";";
    private static final String PRODUCT_DESCRIPTOR = "(L" + MATRIX + ";L" + MATRIX + ";)L" + MATRIX + ";";
    private final ClassLoader loader;
    private final Path artifact;
    private final List<String> shape;
    private final int localBase;
    private volatile String failure, beforeSha256;
    private volatile int matches;

    /**
     * Creates an inert transform for one exact loader/archive/reference method.
     * @param loader the admitted host class loader
     * @param artifact the expected defining archive
     * @param reference the original transform class from that archive
     */
    public MatrixScratchTransformer(ClassLoader loader, Path artifact, byte[] reference) {
        this.loader = Objects.requireNonNull(loader);
        this.artifact = Objects.requireNonNull(artifact).toAbsolutePath().normalize();
        shape = ReviewedMethodShape.read(reference, OWNER, METHOD, DESCRIPTOR);
        if (shape == null) throw new IllegalArgumentException("native matrix method absent");
        int[] inspection = {-1, 0};
        new ClassReader(reference).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (!METHOD.equals(name) || !DESCRIPTOR.equals(descriptor)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                        if (product(opcode, owner, name, desc, itf)) inspection[1]++;
                    }
                    @Override public void visitMaxs(int stack, int locals) { inspection[0] = locals; }
                };
            }
        }, ClassReader.SKIP_DEBUG);
        if (inspection[0] < 1 || inspection[1] != 1) throw new IllegalArgumentException("expected one native product site");
        localBase = inspection[0];
    }
    /** Returns the latest rejection, or null. */
    public String failure() { return failure; }
    /** Returns the successful class transform count. */
    public int matches() { return matches; }
    /** Returns the original full class digest for restoration verification. */
    public String beforeSha256() { return beforeSha256; }

    @Override public byte[] transform(Module module, ClassLoader actualLoader, String name, Class<?> type,
                                      ProtectionDomain domain, byte[] bytes) {
        if (actualLoader != loader || !OWNER.equals(name) || bytes == null) return null;
        try {
            if (domain == null || domain.getCodeSource() == null || !artifact.equals(
                    Path.of(domain.getCodeSource().getLocation().toURI()).toAbsolutePath().normalize())) {
                failure = "matrix source mismatch"; return null;
            }
            if (!shape.equals(ReviewedMethodShape.read(bytes, OWNER, METHOD, DESCRIPTOR))) {
                failure = "matrix parent method changed"; return null;
            }
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override protected ClassLoader getClassLoader() { return loader; }
            };
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                    MethodVisitor original = super.visitMethod(access, method, descriptor, signature, exceptions);
                    return METHOD.equals(method) && DESCRIPTOR.equals(descriptor) ? new ScratchVisitor(original, localBase) : original;
                }
            }, ClassReader.EXPAND_FRAMES);
            byte[] result = writer.toByteArray();
            if (beforeSha256 == null) beforeSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            matches++;
            return result;
        } catch (Exception | LinkageError rejected) { failure = rejected.toString(); return null; }
    }
    private static final class ScratchVisitor extends MethodVisitor {
        private final int enabled, first, second, left, right, destination;
        private final java.util.List<Handler> nativeHandlers = new java.util.ArrayList<>();
        ScratchVisitor(MethodVisitor original, int base) {
            super(Opcodes.ASM9, original);
            enabled = base; first = base + 1; second = base + 2;
            left = base + 3; right = base + 4; destination = base + 5;
        }
        @Override public void visitCode() {
            super.visitCode();
            super.visitInsn(Opcodes.ACONST_NULL); super.visitVarInsn(Opcodes.ASTORE, first);
            super.visitInsn(Opcodes.ACONST_NULL); super.visitVarInsn(Opcodes.ASTORE, second);
            super.visitInsn(Opcodes.ICONST_0); super.visitVarInsn(Opcodes.ISTORE, enabled);
            Label start = new Label(), end = new Label(), failed = new Label(), done = new Label();
            super.visitTryCatchBlock(start, end, failed, "java/lang/Throwable");
            super.visitLabel(start);
            super.visitLdcInsn(ENABLE_PROPERTY);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "getBoolean", "(Ljava/lang/String;)Z", false);
            super.visitVarInsn(Opcodes.ISTORE, enabled);
            super.visitLabel(end); super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(failed); super.visitInsn(Opcodes.POP);
            super.visitLabel(done);
        }
        @Override public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            nativeHandlers.add(new Handler(start, end, handler, type));
        }
        @Override public void visitMaxs(int stack, int locals) {
            for (Handler handler : nativeHandlers) super.visitTryCatchBlock(handler.start(), handler.end(), handler.target(), handler.type());
            super.visitMaxs(stack, Math.max(locals, destination + 1));
        }
        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
            if (!product(opcode, owner, name, descriptor, itf)) {
                super.visitMethodInsn(opcode, owner, name, descriptor, itf); return;
            }
            super.visitVarInsn(Opcodes.ASTORE, right); super.visitVarInsn(Opcodes.ASTORE, left);
            Label nativeProduct = new Label(), useFirst = new Label(), multiply = new Label(), done = new Label();
            super.visitVarInsn(Opcodes.ILOAD, enabled); super.visitJumpInsn(Opcodes.IFEQ, nativeProduct);
            super.visitVarInsn(Opcodes.ALOAD, left); super.visitJumpInsn(Opcodes.IFNULL, nativeProduct);
            super.visitVarInsn(Opcodes.ALOAD, right); super.visitJumpInsn(Opcodes.IFNULL, nativeProduct);
            super.visitVarInsn(Opcodes.ALOAD, right); super.visitVarInsn(Opcodes.ALOAD, first);
            super.visitJumpInsn(Opcodes.IF_ACMPNE, useFirst);
            select(second); super.visitJumpInsn(Opcodes.GOTO, multiply);
            super.visitLabel(useFirst); select(first);
            super.visitLabel(multiply);
            super.visitVarInsn(Opcodes.ALOAD, left);
            array(left); array(right); array(destination);
            // false chooses the same four-term multiplication as the allocating helper.
            // Destination is invocation-local and never aliases either original operand.
            super.visitInsn(Opcodes.ICONST_0);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MATRIX, "b", "([F[F[FZ)V", false);
            super.visitVarInsn(Opcodes.ALOAD, destination); super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(nativeProduct);
            super.visitVarInsn(Opcodes.ALOAD, left); super.visitVarInsn(Opcodes.ALOAD, right);
            super.visitMethodInsn(opcode, owner, name, descriptor, itf);
            super.visitLabel(done);
        }
        private void select(int slot) {
            Label ready = new Label();
            super.visitVarInsn(Opcodes.ALOAD, slot); super.visitJumpInsn(Opcodes.IFNONNULL, ready);
            super.visitTypeInsn(Opcodes.NEW, MATRIX); super.visitInsn(Opcodes.DUP);
            super.visitMethodInsn(Opcodes.INVOKESPECIAL, MATRIX, "<init>", "()V", false);
            super.visitVarInsn(Opcodes.ASTORE, slot);
            super.visitLabel(ready);
            super.visitVarInsn(Opcodes.ALOAD, slot); super.visitVarInsn(Opcodes.ASTORE, destination);
        }
        private void array(int slot) {
            super.visitVarInsn(Opcodes.ALOAD, slot);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MATRIX, "a", "()[F", false);
        }
    }
    private record Handler(Label start, Label end, Label target, String type) { }

    private static boolean product(int opcode, String owner, String name, String descriptor, boolean itf) {
        return opcode == Opcodes.INVOKESTATIC && !itf && owner.equals("com/live2d/graphics3d/type/a")
            && name.equals("a") && descriptor.equals(PRODUCT_DESCRIPTOR);
    }
}
