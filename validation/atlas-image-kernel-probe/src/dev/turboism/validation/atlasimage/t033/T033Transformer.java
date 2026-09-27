package dev.turboism.validation.atlasimage.t033;

import dev.turboism.validation.atlasimage.shaded.asm97.ClassReader;
import dev.turboism.validation.atlasimage.shaded.asm97.ClassWriter;
import dev.turboism.validation.atlasimage.shaded.asm97.ClassVisitor;
import dev.turboism.validation.atlasimage.shaded.asm97.Label;
import dev.turboism.validation.atlasimage.shaded.asm97.MethodVisitor;
import dev.turboism.validation.atlasimage.shaded.asm97.Opcodes;

final class T033Transformer implements Opcodes {
    // Filled from the deterministic generator identity after the fixture is compiled once.
    static final String EXPECTED_CLASS_SHA256 = "855d93cf7e2f7f5d8566e30b84c466da43337840997058bd73c9a006dc8487c8";
    static final String EXPECTED_METHOD_SHAPE_SHA256 = "db1427e432c7dd43681468df595031c590b787cf1c8fbfb7794693b5aa5f5efa";

    private static final int ORIGINAL_HEIGHT_LOCAL = 13;
    private static final int ORIGINAL_WIDTH_LOCAL = 18;
    private static final int LOOP_HEIGHT_LOCAL = 35;
    private static final int LOOP_WIDTH_LOCAL = 36;
    private static final int PACKED_BOUNDS_LOCAL = 37;

    private T033Transformer() {
    }

    static byte[] apply(final byte[] input, final boolean enabled) {
        if (!enabled) {
            return input;
        }
        final T033ShapeInspector.Shape shape;
        try {
            shape = T033ShapeInspector.inspect(input);
        } catch (final RuntimeException exception) {
            return input;
        }
        if (!isExactInput(input, shape)) {
            return input;
        }
        try {
            final ClassReader reader = new ClassReader(input);
            final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            final ClassVisitor visitor = new ClassVisitor(ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(
                    final int access,
                    final String name,
                    final String descriptor,
                    final String signature,
                    final String[] exceptions
                ) {
                    final MethodVisitor downstream = super.visitMethod(
                        access,
                        name,
                        descriptor,
                        signature,
                        exceptions
                    );
                    if (name.equals("render") && descriptor.equals(T033FixtureGenerator.RENDER_DESCRIPTOR)) {
                        return new BoundaryRewriter(downstream);
                    }
                    return downstream;
                }
            };
            reader.accept(visitor, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
            return writer.toByteArray();
        } catch (final RuntimeException exception) {
            return input;
        }
    }

    private static boolean isExactInput(
        final byte[] input,
        final T033ShapeInspector.Shape shape
    ) {
        return T033ShapeInspector.sha256(input).equals(EXPECTED_CLASS_SHA256)
            && shape.sha256().equals(EXPECTED_METHOD_SHAPE_SHA256)
            && shape.className().equals(T033FixtureGenerator.INTERNAL_NAME)
            && shape.classAccess() == (ACC_PUBLIC | ACC_FINAL | ACC_SUPER)
            && shape.methodCount() == 3
            && shape.exactMethodIdentity()
            && shape.hasExpectedControlShape();
    }

    private static final class BoundaryRewriter extends MethodVisitor {
        private int pendingInputLocal = -1;
        private int pendingOriginalLocal = -1;
        private int pendingReplacementLocal = -1;
        private int pendingStage;
        private boolean heightRewritten;
        private boolean widthRewritten;
        private boolean boundsInserted;

        private BoundaryRewriter(final MethodVisitor downstream) {
            super(ASM9, downstream);
        }

        @Override
        public void visitVarInsn(final int opcode, final int var) {
            if (pendingInputLocal != -1) {
                if (pendingStage == 3 && opcode == ISTORE && var == pendingOriginalLocal) {
                    mv.visitVarInsn(ILOAD, pendingReplacementLocal);
                    mv.visitInsn(ICONST_1);
                    mv.visitInsn(ISUB);
                    mv.visitVarInsn(ISTORE, pendingOriginalLocal);
                    if (pendingOriginalLocal == ORIGINAL_HEIGHT_LOCAL) {
                        heightRewritten = true;
                    } else {
                        widthRewritten = true;
                    }
                    clearPending();
                    return;
                }
                flushPending();
            }
            if (opcode == ILOAD && var == 9 && !heightRewritten) {
                beginPending(9, ORIGINAL_HEIGHT_LOCAL, LOOP_HEIGHT_LOCAL);
            } else if (opcode == ILOAD && var == 8 && !widthRewritten) {
                beginPending(8, ORIGINAL_WIDTH_LOCAL, LOOP_WIDTH_LOCAL);
            } else {
                mv.visitVarInsn(opcode, var);
            }
        }

        @Override
        public void visitInsn(final int opcode) {
            if (pendingInputLocal != -1) {
                if (pendingStage == 1 && opcode == ICONST_1) {
                    pendingStage = 2;
                    return;
                }
                if (pendingStage == 2 && opcode == ISUB) {
                    pendingStage = 3;
                    return;
                }
                flushPending();
            }
            mv.visitInsn(opcode);
        }

        @Override
        public void visitMethodInsn(
            final int opcode,
            final String owner,
            final String name,
            final String descriptor,
            final boolean isInterface
        ) {
            if (pendingInputLocal != -1) {
                flushPending();
            }
            if (!boundsInserted
                && opcode == INVOKESTATIC
                && owner.equals("java/util/Arrays")
                && name.equals("fill")
                && descriptor.equals("([IIII)V")) {
                emitBoundsComputation(mv);
                boundsInserted = true;
            }
            mv.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public void visitLabel(final Label label) {
            if (pendingInputLocal != -1) {
                flushPending();
            }
            mv.visitLabel(label);
        }

        @Override
        public void visitMaxs(final int maxStack, final int maxLocals) {
            if (pendingInputLocal != -1 || !heightRewritten || !widthRewritten || !boundsInserted) {
                throw new IllegalStateException("fixture boundary patch did not rewrite exactly two boundaries");
            }
            mv.visitMaxs(maxStack, maxLocals);
        }

        private void beginPending(
            final int inputLocal,
            final int originalLocal,
            final int replacementLocal
        ) {
            pendingInputLocal = inputLocal;
            pendingOriginalLocal = originalLocal;
            pendingReplacementLocal = replacementLocal;
            pendingStage = 1;
        }

        private void clearPending() {
            pendingInputLocal = -1;
            pendingOriginalLocal = -1;
            pendingReplacementLocal = -1;
            pendingStage = 0;
        }

        private void flushPending() {
            mv.visitVarInsn(ILOAD, pendingInputLocal);
            if (pendingStage >= 2) {
                mv.visitInsn(ICONST_1);
            }
            if (pendingStage >= 3) {
                mv.visitInsn(ISUB);
            }
            clearPending();
        }
    }

    private static void emitBoundsComputation(final MethodVisitor method) {
        method.visitVarInsn(ILOAD, 1);
        method.visitVarInsn(ILOAD, 2);
        method.visitVarInsn(ALOAD, 3);
        method.visitVarInsn(ILOAD, 4);
        method.visitVarInsn(ILOAD, 5);
        method.visitVarInsn(ALOAD, 6);
        method.visitVarInsn(ILOAD, 7);
        method.visitVarInsn(ILOAD, 8);
        method.visitVarInsn(ILOAD, 9);
        method.visitVarInsn(ILOAD, 10);
        method.visitInsn(ICONST_1);
        method.visitMethodInsn(
            INVOKESTATIC,
            T033FixtureGenerator.ADMISSION_OWNER,
            "bounds",
            T033FixtureGenerator.BOUNDS_DESCRIPTOR,
            false
        );
        method.visitVarInsn(LSTORE, PACKED_BOUNDS_LOCAL);
        method.visitVarInsn(LLOAD, PACKED_BOUNDS_LOCAL);
        method.visitInsn(L2I);
        method.visitVarInsn(ISTORE, LOOP_WIDTH_LOCAL);
        method.visitVarInsn(LLOAD, PACKED_BOUNDS_LOCAL);
        method.visitIntInsn(BIPUSH, 32);
        method.visitInsn(LUSHR);
        method.visitInsn(L2I);
        method.visitVarInsn(ISTORE, LOOP_HEIGHT_LOCAL);
    }
}
