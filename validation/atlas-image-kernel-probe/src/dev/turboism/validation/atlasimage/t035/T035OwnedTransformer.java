package dev.turboism.validation.atlasimage.t035;

import java.util.Arrays;
import dev.turboism.validation.atlasimage.shaded.asm97.ClassReader;
import dev.turboism.validation.atlasimage.shaded.asm97.ClassVisitor;
import dev.turboism.validation.atlasimage.shaded.asm97.MethodVisitor;
import dev.turboism.validation.atlasimage.shaded.asm97.Opcodes;

/** Strict-gated transformer used only to execute the hand-generated owned fixture. */
final class T035OwnedTransformer implements Opcodes {
    private static final int ORIGINAL_HEIGHT_LOCAL = 13;
    private static final int ORIGINAL_WIDTH_LOCAL = 18;
    private static final int LOOP_HEIGHT_LOCAL = 35;
    private static final int LOOP_WIDTH_LOCAL = 36;
    private static final int PACKED_BOUNDS_LOCAL = 37;
    private static final byte[] EXACT_BYTES = T035OwnedFixtureGenerator.generate();
    private static final String EXACT_SHA256 = T035Shape.sha256(EXACT_BYTES);
    private static final String EXACT_SHAPE_SHA256 = T035Shape.inspect(EXACT_BYTES).sha256();
    private static int parseCount;

    private T035OwnedTransformer() {
    }

    static byte[] apply(final byte[] input, final boolean enabled) {
        return applyForHelper(
            input,
            enabled,
            T035OwnedFixtureGenerator.ADMISSION_OWNER,
            T035OwnedFixtureGenerator.BOUNDS_DESCRIPTOR
        );
    }

    /** Reuses the exact T035 gate while binding the inserted call to one helper identity. */
    static byte[] applyForHelper(
        final byte[] input,
        final boolean enabled,
        final String helperOwner,
        final String helperDescriptor
    ) {
        if (!enabled || input == null || helperOwner == null || helperDescriptor == null
                || !T035Shape.sha256(input).equals(EXACT_SHA256)) {
            return input;
        }
        parseCount++;
        final T035Shape.Shape shape = T035Shape.inspect(input);
        if (!shape.sha256().equals(EXACT_SHAPE_SHA256)
                || shape.methodCount() != 3
                || shape.handlerCount() != 0
                || shape.maxLocals() != 35
                || shape.countContains(": J 167 ") != 4) {
            return input;
        }
        final T035Shape.OffsetReader reader = new T035Shape.OffsetReader(input);
        final T035StrictNoResolutionWriter writer = new T035StrictNoResolutionWriter(reader);
        final Counts counts = new Counts();
        try {
            reader.accept(new ClassVisitor(ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(
                    final int access,
                    final String name,
                    final String descriptor,
                    final String signature,
                    final String[] exceptions
                ) {
                    writer.setCurrentMethod(name + descriptor);
                    final MethodVisitor downstream = super.visitMethod(
                        access, name, descriptor, signature, exceptions);
                    if (!name.equals("render") || !descriptor.equals(T035Shape.RENDER_DESCRIPTOR)) {
                        return downstream;
                    }
                    return new BoundaryRewriter(
                        downstream, counts, helperOwner, helperDescriptor);
                }
            }, ClassReader.EXPAND_FRAMES);
            if (counts.heightRewritten != 1 || counts.widthRewritten != 1 || !counts.boundsInserted) {
                return input;
            }
            return writer.toByteArray();
        } catch (final RuntimeException exception) {
            return input;
        }
    }

    static int parseCount() {
        return parseCount;
    }

    static void resetParseCount() {
        parseCount = 0;
    }

    private static void emitBounds(
        final MethodVisitor method,
        final String helperOwner,
        final String helperDescriptor
    ) {
        for (int slot = 1; slot <= 10; slot++) {
            method.visitVarInsn(slot == 3 || slot == 6 ? ALOAD : ILOAD, slot);
        }
        method.visitInsn(ICONST_1);
        method.visitMethodInsn(
            INVOKESTATIC,
            helperOwner,
            "bounds",
            helperDescriptor,
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

    private static final class BoundaryRewriter extends MethodVisitor {
        private final Counts counts;
        private final String helperOwner;
        private final String helperDescriptor;
        private int pendingInputLocal = -1;
        private int pendingOriginalLocal = -1;
        private int pendingReplacementLocal = -1;
        private int pendingStage;

        private BoundaryRewriter(
            final MethodVisitor downstream,
            final Counts counts,
            final String helperOwner,
            final String helperDescriptor
        ) {
            super(ASM9, downstream);
            this.counts = counts;
            this.helperOwner = helperOwner;
            this.helperDescriptor = helperDescriptor;
        }

        @Override
        public void visitVarInsn(final int opcode, final int variable) {
            if (pendingInputLocal != -1) {
                if (pendingStage == 3 && opcode == ISTORE && variable == pendingOriginalLocal) {
                    mv.visitVarInsn(ILOAD, pendingReplacementLocal);
                    mv.visitInsn(ICONST_1);
                    mv.visitInsn(ISUB);
                    mv.visitVarInsn(ISTORE, pendingOriginalLocal);
                    if (pendingOriginalLocal == ORIGINAL_HEIGHT_LOCAL) {
                        counts.heightRewritten++;
                    } else {
                        counts.widthRewritten++;
                    }
                    clearPending();
                    return;
                }
                flushPending();
            }
            if (opcode == ILOAD && variable == 9 && counts.heightRewritten == 0) {
                beginPending(9, ORIGINAL_HEIGHT_LOCAL, LOOP_HEIGHT_LOCAL);
            } else if (opcode == ILOAD && variable == 8 && counts.widthRewritten == 0) {
                beginPending(8, ORIGINAL_WIDTH_LOCAL, LOOP_WIDTH_LOCAL);
            } else {
                mv.visitVarInsn(opcode, variable);
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
            if (!counts.boundsInserted && opcode == INVOKESTATIC
                    && owner.equals("java/util/Arrays")
                    && name.equals("fill")
                    && descriptor.equals("([IIII)V")
                    && !isInterface) {
                emitBounds(mv, helperOwner, helperDescriptor);
                counts.boundsInserted = true;
            }
            mv.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public void visitLabel(final dev.turboism.validation.atlasimage.shaded.asm97.Label label) {
            if (pendingInputLocal != -1) {
                flushPending();
            }
            mv.visitLabel(label);
        }

        @Override
        public void visitMaxs(final int maxStack, final int maxLocals) {
            if (pendingInputLocal != -1 || counts.heightRewritten != 1
                    || counts.widthRewritten != 1 || !counts.boundsInserted) {
                throw new IllegalArgumentException("owned fixture patch shape mismatch");
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

    private static final class Counts {
        private int heightRewritten;
        private int widthRewritten;
        private boolean boundsInserted;
    }
}
