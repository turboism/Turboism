package dev.turboism.validation.atlasimage.t035;

import dev.turboism.validation.atlasimage.shaded.asm97.ClassReader;
import dev.turboism.validation.atlasimage.shaded.asm97.ClassVisitor;
import dev.turboism.validation.atlasimage.shaded.asm97.MethodVisitor;
import dev.turboism.validation.atlasimage.shaded.asm97.Opcodes;

/** Data-only official adapter: the candidate remains an in-memory byte array. */
final class T035OfficialAdapter implements Opcodes {
    private static final int LOOP_HEIGHT_LOCAL = 35;
    private static final int LOOP_WIDTH_LOCAL = 36;
    private static final int PACKED_BOUNDS_LOCAL = 37;

    private T035OfficialAdapter() {
    }

    static Result apply(final T035OfficialProfile.Loaded loaded) {
        return apply(loaded, T035InstructionMapping.HELPER_OWNER,
            T035InstructionMapping.HELPER_DESCRIPTOR);
    }

    static Result apply(
        final T035OfficialProfile.Loaded loaded,
        final String helperOwner,
        final String helperDescriptor
    ) {
        final byte[] original = loaded.classBytes();
        final T035Shape.Shape shape = loaded.shape();
        if (!shape.sha256().equals(loaded.profile().shapeSha256())) {
            return Result.rejected(original, 0, "complete shape gate");
        }
        final T035Shape.OffsetReader reader = new T035Shape.OffsetReader(original);
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
                    if (!name.equals("a") || !descriptor.equals(T035OfficialProfile.DESCRIPTOR)) {
                        return downstream;
                    }
                    return new BoundaryPatch(
                        downstream, reader, counts, helperOwner, helperDescriptor);
                }
            }, ClassReader.EXPAND_FRAMES);
            if (counts.heightReads != 1 || counts.widthReads != 1 || counts.boundsCalls != 1) {
                return Result.rejected(original, writer.commonSuperQueries(), "patch count gate");
            }
            final byte[] candidate = writer.toByteArray();
            return new Result(true, candidate, writer.commonSuperQueries(), "accepted",
                counts.heightReads, counts.widthReads, counts.boundsCalls);
        } catch (final RuntimeException exception) {
            return Result.rejected(original, writer.commonSuperQueries(), exception.getClass().getSimpleName());
        }
    }

    private static void emitBounds(
        final MethodVisitor method,
        final String helperOwner,
        final String helperDescriptor
    ) {
        final int[] slots = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        for (final int slot : slots) {
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

    private static final class BoundaryPatch extends MethodVisitor {
        private final T035Shape.OffsetReader reader;
        private final Counts counts;
        private final String helperOwner;
        private final String helperDescriptor;

        private BoundaryPatch(
            final MethodVisitor downstream,
            final T035Shape.OffsetReader reader,
            final Counts counts,
            final String helperOwner,
            final String helperDescriptor
        ) {
            super(ASM9, downstream);
            this.reader = reader;
            this.counts = counts;
            this.helperOwner = helperOwner;
            this.helperDescriptor = helperDescriptor;
        }

        @Override
        public void visitVarInsn(final int opcode, final int variable) {
            if (reader.offset() == T035OfficialProfile.HEIGHT_BOUNDARY_OFFSET) {
                require(opcode == ILOAD && variable == 9, "height boundary predecessor");
                mv.visitVarInsn(ILOAD, LOOP_HEIGHT_LOCAL);
                counts.heightReads++;
                return;
            }
            if (reader.offset() == T035OfficialProfile.WIDTH_BOUNDARY_OFFSET) {
                require(opcode == ILOAD && variable == 8, "width boundary predecessor");
                mv.visitVarInsn(ILOAD, LOOP_WIDTH_LOCAL);
                counts.widthReads++;
                return;
            }
            mv.visitVarInsn(opcode, variable);
        }

        @Override
        public void visitMethodInsn(
            final int opcode,
            final String owner,
            final String name,
            final String descriptor,
            final boolean isInterface
        ) {
            if (reader.offset() == T035OfficialProfile.FILL_OFFSET) {
                require(opcode == INVOKESTATIC && owner.equals("java/util/Arrays")
                    && name.equals("fill") && descriptor.equals("([IIII)V")
                    && !isInterface, "fill predecessor");
                emitBounds(mv, helperOwner, helperDescriptor);
                counts.boundsCalls++;
            }
            mv.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        private static void require(final boolean condition, final String label) {
            if (!condition) {
                throw new IllegalArgumentException("official shape mismatch: " + label);
            }
        }
    }

    private static final class Counts {
        private int heightReads;
        private int widthReads;
        private int boundsCalls;
    }

    record Result(
        boolean accepted,
        byte[] candidate,
        int commonSuperQueries,
        String reason,
        int heightReads,
        int widthReads,
        int boundsCalls
    ) {
        private static Result rejected(final byte[] original, final int queries, final String reason) {
            return new Result(false, original, queries, reason, 0, 0, 0);
        }
    }
}
