package dev.turboism.adapter.cubism.optimization.geometry;

import dev.turboism.adapter.cubism.optimization.ClassPinTable;
import dev.turboism.adapter.cubism.optimization.HostClassWriters;
import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Invocation-local matrix-product scratch space and cached local-to-parent bridge. No output
 * matrix is shared across invocations; original traversal and arithmetic order remain intact.
 */
public final class MatrixScratchTransformer implements ClassFileTransformer {
    /** Candidate opt-in property; absent means the original allocation path. */
    public static final String ENABLE_PROPERTY = "turboism.optimization.matrixScratch";
    /** Installer-owned AtomicBoolean; a user request alone never admits reuse. */
    public static final String ADMISSION_PROPERTY = "turboism.matrix-scratch.admission";
    /** Property key for the bridge callback slot. */
    public static final String CALLBACK_PROPERTY = MatrixScratchBridge.CALLBACK_PROPERTY;
    /** Exact transform class containing the attested parent traversal. */
    public static final String OWNER = "com/live2d/graphics3d/component/GTransform";
    /** Exact native method under investigation. */
    public static final String METHOD = "getLocalToWorldMatrix";
    /** Matrix class whose native general multiplication is retained. */
    public static final String MATRIX = "com/live2d/graphics3d/type/GMatrix44";
    /** Exact parent-chain method descriptor. */
    public static final String DESCRIPTOR = "()L" + MATRIX + ";";

    private static final String PRODUCT_OWNER = "com/live2d/graphics3d/type/a";
    private static final String PRODUCT_METHOD = "a";
    private static final String PRODUCT_DESCRIPTOR = "(L" + MATRIX + ";L" + MATRIX + ";)L" + MATRIX + ";";
    private static final String THROWABLE = "java/lang/Throwable";
    private static final String BOOLEAN = "java/lang/Boolean";
    private static final String GET_BOOLEAN = "getBoolean";
    private static final String GET_BOOLEAN_DESC = "(Ljava/lang/String;)Z";
    private static final String SYSTEM = "java/lang/System";
    private static final String GET_PROPERTIES = "getProperties";
    private static final String GET_PROPERTIES_DESC = "()Ljava/util/Properties;";
    private static final String PROPERTIES = "java/util/Properties";
    private static final String PROPERTIES_GET = "get";
    private static final String PROPERTIES_GET_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String ATOMIC_BOOLEAN = "java/util/concurrent/atomic/AtomicBoolean";
    private static final String ATOMIC_GET = "get";
    private static final String ATOMIC_GET_DESC = "()Z";
    private static final String FUNCTION = "java/util/function/Function";
    private static final String FUNCTION_APPLY = "apply";
    private static final String FUNCTION_APPLY_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String INIT_METHOD = "<init>";
    private static final String VOID_DESC = "()V";
    private static final String MATRIX_ARRAY_METHOD = "a";
    private static final String MATRIX_ARRAY_DESC = "()[F";
    private static final String MATRIX_MUL_METHOD = "b";
    private static final String MATRIX_MUL_DESC = "([F[F[FZ)V";
    private static final String REJECT_ABSENT_MESSAGE = "native matrix method absent";
    private static final String REJECT_PRODUCT_MESSAGE = "expected one native product site";
    private static final String REJECT_SOURCE_MESSAGE = "matrix source mismatch";
    private static final String REJECT_CHANGED_MESSAGE = "matrix parent method changed";
    private static final int EXPECTED_PRODUCT_SITES = 1;
    private static final int MIN_LOCALS = 1;
    private final ClassLoader loader;
    private final Path artifact;
    private final List<String> shape;
    private final int localBase;
    private volatile String failure;
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
        if (shape == null) throw new IllegalArgumentException(REJECT_ABSENT_MESSAGE);
        int[] inspection = {-1, 0};
        new ClassReader(reference)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String descriptor, String signature, String[] exceptions) {
                                if (!METHOD.equals(name) || !DESCRIPTOR.equals(descriptor)) return null;
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode, String owner, String name, String desc, boolean itf) {
                                        if (product(opcode, owner, name, desc, itf)) inspection[1]++;
                                    }

                                    @Override
                                    public void visitMaxs(int stack, int locals) {
                                        inspection[0] = locals;
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG);
        if (inspection[0] < MIN_LOCALS || inspection[1] != EXPECTED_PRODUCT_SITES)
            throw new IllegalArgumentException(REJECT_PRODUCT_MESSAGE);
        localBase = inspection[0];
    }
    /** Returns the latest rejection, or null. */
    public String failure() {
        return failure;
    }
    /** Returns the successful class transform count. */
    public int matches() {
        return matches;
    }

    @Override
    public byte[] transform(
            Module module,
            ClassLoader actualLoader,
            String name,
            Class<?> type,
            ProtectionDomain domain,
            byte[] bytes) {
        if (actualLoader != loader || !OWNER.equals(name) || bytes == null) return null;
        try {
            if (domain == null
                    || domain.getCodeSource() == null
                    || !artifact.equals(
                            Path.of(domain.getCodeSource().getLocation().toURI())
                                    .toAbsolutePath()
                                    .normalize())) {
                failure = REJECT_SOURCE_MESSAGE;
                return null;
            }
            if (!shape.equals(ReviewedMethodShape.read(bytes, OWNER, METHOD, DESCRIPTOR))) {
                failure = REJECT_CHANGED_MESSAGE;
                return null;
            }
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override
                protected ClassLoader getClassLoader() {
                    return loader == null ? super.getClassLoader() : loader;
                }

                @Override
                protected String getCommonSuperClass(final String left, final String right) {
                    return HostClassWriters.commonSuperClass(loader, left, right);
                }
            };
            reader.accept(
                    new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override
                        public MethodVisitor visitMethod(
                                int access, String method, String descriptor, String signature, String[] exceptions) {
                            MethodVisitor original =
                                    super.visitMethod(access, method, descriptor, signature, exceptions);
                            return METHOD.equals(method) && DESCRIPTOR.equals(descriptor)
                                    ? new ScratchVisitor(original, localBase)
                                    : original;
                        }
                    },
                    ClassReader.EXPAND_FRAMES);
            byte[] result = writer.toByteArray();

            matches++;
            return result;
        } catch (Exception | LinkageError rejected) {
            failure = rejected.toString();
            return null;
        }
    }

    private static final class ScratchVisitor extends MethodVisitor {
        private final int enabled, first, second, left, right, destination, bridge;
        private final java.util.List<Handler> nativeHandlers = new java.util.ArrayList<>();

        ScratchVisitor(MethodVisitor original, int base) {
            super(Opcodes.ASM9, original);
            enabled = base;
            first = base + 1;
            second = base + 2;
            left = base + 3;
            right = base + 4;
            destination = base + 5;
            bridge = base + 6;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            super.visitInsn(Opcodes.ACONST_NULL);
            super.visitVarInsn(Opcodes.ASTORE, first);
            super.visitInsn(Opcodes.ACONST_NULL);
            super.visitVarInsn(Opcodes.ASTORE, second);
            super.visitInsn(Opcodes.ACONST_NULL);
            super.visitVarInsn(Opcodes.ASTORE, bridge);
            super.visitInsn(Opcodes.ICONST_0);
            super.visitVarInsn(Opcodes.ISTORE, enabled);
            Label start = new Label(),
                    popTwoAndEnd = new Label(),
                    popOneAndEnd = new Label(),
                    end = new Label(),
                    failed = new Label(),
                    done = new Label(),
                    popAndFallback = new Label(),
                    nativeTraversal = new Label();
            super.visitTryCatchBlock(start, end, failed, THROWABLE);
            super.visitLabel(start);
            super.visitLdcInsn(ENABLE_PROPERTY);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, BOOLEAN, GET_BOOLEAN, GET_BOOLEAN_DESC, false);
            super.visitJumpInsn(Opcodes.IFEQ, end);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, SYSTEM, GET_PROPERTIES, GET_PROPERTIES_DESC, false);
            super.visitInsn(Opcodes.DUP);
            super.visitLdcInsn(ADMISSION_PROPERTY);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PROPERTIES, PROPERTIES_GET, PROPERTIES_GET_DESC, false);
            super.visitInsn(Opcodes.DUP);
            super.visitTypeInsn(Opcodes.INSTANCEOF, ATOMIC_BOOLEAN);
            super.visitJumpInsn(Opcodes.IFEQ, popTwoAndEnd);
            super.visitTypeInsn(Opcodes.CHECKCAST, ATOMIC_BOOLEAN);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ATOMIC_BOOLEAN, ATOMIC_GET, ATOMIC_GET_DESC, false);
            super.visitInsn(Opcodes.DUP);
            super.visitVarInsn(Opcodes.ISTORE, enabled);
            super.visitJumpInsn(Opcodes.IFEQ, popOneAndEnd);
            // Lookup callback slot inside the same Properties handle when admitted.
            super.visitLdcInsn(CALLBACK_PROPERTY);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PROPERTIES, PROPERTIES_GET, PROPERTIES_GET_DESC, false);
            super.visitVarInsn(Opcodes.ASTORE, bridge);
            super.visitJumpInsn(Opcodes.GOTO, end);
            super.visitLabel(popTwoAndEnd);
            super.visitInsn(Opcodes.POP);
            super.visitLabel(popOneAndEnd);
            super.visitInsn(Opcodes.POP);
            super.visitLabel(end);
            super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(failed);
            super.visitInsn(Opcodes.POP);
            super.visitInsn(Opcodes.ICONST_0);
            super.visitVarInsn(Opcodes.ISTORE, enabled);
            super.visitInsn(Opcodes.ACONST_NULL);
            super.visitVarInsn(Opcodes.ASTORE, bridge);
            super.visitLabel(done);

            super.visitVarInsn(Opcodes.ALOAD, bridge);
            super.visitTypeInsn(Opcodes.INSTANCEOF, FUNCTION);
            super.visitJumpInsn(Opcodes.IFEQ, nativeTraversal);
            super.visitVarInsn(Opcodes.ALOAD, bridge);
            super.visitTypeInsn(Opcodes.CHECKCAST, FUNCTION);
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, FUNCTION, FUNCTION_APPLY, FUNCTION_APPLY_DESC, true);
            super.visitInsn(Opcodes.DUP);
            super.visitTypeInsn(Opcodes.INSTANCEOF, MATRIX);
            super.visitJumpInsn(Opcodes.IFEQ, popAndFallback);
            super.visitTypeInsn(Opcodes.CHECKCAST, MATRIX);
            super.visitInsn(Opcodes.ARETURN);
            super.visitLabel(popAndFallback);
            super.visitInsn(Opcodes.POP);
            super.visitLabel(nativeTraversal);
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            nativeHandlers.add(new Handler(start, end, handler, type));
        }

        @Override
        public void visitMaxs(int stack, int locals) {
            for (Handler handler : nativeHandlers)
                super.visitTryCatchBlock(handler.start(), handler.end(), handler.target(), handler.type());
            super.visitMaxs(stack, Math.max(locals, bridge + 1));
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
            if (!product(opcode, owner, name, descriptor, itf)) {
                super.visitMethodInsn(opcode, owner, name, descriptor, itf);
                return;
            }
            super.visitVarInsn(Opcodes.ASTORE, right);
            super.visitVarInsn(Opcodes.ASTORE, left);
            Label nativeProduct = new Label(), useFirst = new Label(), multiply = new Label(), done = new Label();
            super.visitVarInsn(Opcodes.ILOAD, enabled);
            super.visitJumpInsn(Opcodes.IFEQ, nativeProduct);
            super.visitVarInsn(Opcodes.ALOAD, left);
            super.visitJumpInsn(Opcodes.IFNULL, nativeProduct);
            super.visitVarInsn(Opcodes.ALOAD, right);
            super.visitJumpInsn(Opcodes.IFNULL, nativeProduct);
            super.visitVarInsn(Opcodes.ALOAD, right);
            super.visitVarInsn(Opcodes.ALOAD, first);
            super.visitJumpInsn(Opcodes.IF_ACMPNE, useFirst);
            select(second);
            super.visitJumpInsn(Opcodes.GOTO, multiply);
            super.visitLabel(useFirst);
            select(first);
            super.visitLabel(multiply);
            super.visitVarInsn(Opcodes.ALOAD, left);
            array(left);
            array(right);
            array(destination);
            // false chooses the same four-term multiplication as the allocating helper.
            // Destination is invocation-local and never aliases either original operand.
            super.visitInsn(Opcodes.ICONST_0);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MATRIX, MATRIX_MUL_METHOD, MATRIX_MUL_DESC, false);
            super.visitVarInsn(Opcodes.ALOAD, destination);
            super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(nativeProduct);
            super.visitVarInsn(Opcodes.ALOAD, left);
            super.visitVarInsn(Opcodes.ALOAD, right);
            super.visitMethodInsn(opcode, owner, name, descriptor, itf);
            super.visitLabel(done);
        }

        private void select(int slot) {
            Label ready = new Label();
            super.visitVarInsn(Opcodes.ALOAD, slot);
            super.visitJumpInsn(Opcodes.IFNONNULL, ready);
            super.visitTypeInsn(Opcodes.NEW, MATRIX);
            super.visitInsn(Opcodes.DUP);
            super.visitMethodInsn(Opcodes.INVOKESPECIAL, MATRIX, INIT_METHOD, VOID_DESC, false);
            super.visitVarInsn(Opcodes.ASTORE, slot);
            super.visitLabel(ready);
            super.visitVarInsn(Opcodes.ALOAD, slot);
            super.visitVarInsn(Opcodes.ASTORE, destination);
        }

        private void array(int slot) {
            super.visitVarInsn(Opcodes.ALOAD, slot);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MATRIX, MATRIX_ARRAY_METHOD, MATRIX_ARRAY_DESC, false);
        }
    }

    private record Handler(Label start, Label end, Label target, String type) {}

    private static boolean product(int opcode, String owner, String name, String descriptor, boolean itf) {
        return opcode == Opcodes.INVOKESTATIC
                && !itf
                && owner.equals(PRODUCT_OWNER)
                && name.equals(PRODUCT_METHOD)
                && descriptor.equals(PRODUCT_DESCRIPTOR);
    }

    /**
     * SHA-256 of the transform, matrix, product and entity classes this hook rewrites and verifies, keyed by internal name, per reviewed generation. A
     * repackaged or differently-declared artifact binds this hook only when
     * one distinct contract's pins match every class entry; identical contracts merge.
     */
    public static Map<String, List<String>> reviewedClassSha256() {
        return REVIEWED_CLASSES;
    }

    private static final Map<String, List<String>> REVIEWED_CLASSES = ClassPinTable.load("matrix-scratch");
}
