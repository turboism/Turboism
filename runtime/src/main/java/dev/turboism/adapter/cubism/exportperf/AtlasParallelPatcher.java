package dev.turboism.adapter.cubism.exportperf;

import static org.objectweb.asm.Opcodes.*;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

/** Rewrites multi-page texture atlas batch loop {@code texture/G.a} to run in parallel. */
final class AtlasParallelPatcher {

    static final String TARGET = "com/live2d/cubism/doc/model/texture/G";
    private static final String ATLAS_LIST_FIELD = "a";
    private static final String ATLAS_LIST_DESC = "Ljava/util/List;";
    private static final String RUN_METHOD = "a";
    private static final String RUN_DESC = "(Lcom/live2d/util/a/a;)V";
    private static final String PAGE_LAMBDA = "com/live2d/cubism/doc/model/texture/H";
    private static final String HELPER_INTERNAL = Type.getInternalName(AtlasRebuildParallelizer.class);
    private static final String HELPER_METHOD = "runParallelUpdate";
    private static final String HELPER_DESC = "(Ljava/util/List;Ljava/lang/Object;)Z";

    private AtlasParallelPatcher() {}

    static byte[] patch(final byte[] classBytes) {
        final ClassReader reader = new ClassReader(classBytes);
        if (!TARGET.equals(reader.getClassName())) {
            return null;
        }
        final ShapeScan scan = new ShapeScan();
        reader.accept(scan, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        if (scan.alreadyPatched || !scan.matches()) {
            return null;
        }
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new PatchVisitor(writer), ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static final class ShapeScan extends ClassVisitor {
        private boolean hasAtlasListField;
        private boolean hasRunMethod;
        private boolean callsPageLambda;
        private boolean alreadyPatched;

        ShapeScan() {
            super(ASM9);
        }

        boolean matches() {
            return hasAtlasListField && hasRunMethod && callsPageLambda;
        }

        @Override
        public FieldVisitor visitField(
                final int access, final String name, final String desc, final String sig, final Object value) {
            if (ATLAS_LIST_FIELD.equals(name) && ATLAS_LIST_DESC.equals(desc)) {
                hasAtlasListField = true;
            }
            return null;
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String sig, final String[] ex) {
            if (!RUN_METHOD.equals(name) || !RUN_DESC.equals(desc)) {
                return null;
            }
            hasRunMethod = true;
            return new MethodVisitor(ASM9) {
                @Override
                public void visitTypeInsn(final int opcode, final String type) {
                    if (opcode == NEW && PAGE_LAMBDA.equals(type)) {
                        callsPageLambda = true;
                    }
                }

                @Override
                public void visitMethodInsn(
                        final int opcode,
                        final String owner,
                        final String mName,
                        final String mDesc,
                        final boolean itf) {
                    if (opcode == INVOKESTATIC && HELPER_INTERNAL.equals(owner) && HELPER_METHOD.equals(mName)) {
                        alreadyPatched = true;
                    }
                }
            };
        }
    }

    private static final class PatchVisitor extends ClassVisitor {
        PatchVisitor(final ClassWriter writer) {
            super(ASM9, writer);
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String sig, final String[] ex) {
            final MethodVisitor output = super.visitMethod(access, name, desc, sig, ex);
            if (!RUN_METHOD.equals(name) || !RUN_DESC.equals(desc)) {
                return output;
            }
            return new MethodVisitor(ASM9, output) {
                @Override
                public void visitCode() {
                    super.visitCode();
                    final Label fallback = new Label();
                    super.visitVarInsn(ALOAD, 0);
                    super.visitFieldInsn(GETFIELD, TARGET, ATLAS_LIST_FIELD, ATLAS_LIST_DESC);
                    super.visitVarInsn(ALOAD, 1);
                    super.visitMethodInsn(INVOKESTATIC, HELPER_INTERNAL, HELPER_METHOD, HELPER_DESC, false);
                    super.visitJumpInsn(IFEQ, fallback);
                    super.visitInsn(RETURN);
                    super.visitLabel(fallback);
                    super.visitFrame(F_NEW, 2, new Object[] {TARGET, "com/live2d/util/a/a"}, 0, new Object[0]);
                }
            };
        }
    }
}
