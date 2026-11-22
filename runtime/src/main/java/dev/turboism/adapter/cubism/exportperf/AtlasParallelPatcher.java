package dev.turboism.adapter.cubism.exportperf;

import static org.objectweb.asm.Opcodes.*;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

/**
 * Rewrites multi-page texture atlas batch loops ({@code texture/G.a}) and atlas input switches
 * ({@code CTextureManager.changeModelTextureInput_toAtlas}) to rebuild dirty pages in parallel.
 */
final class AtlasParallelPatcher {

    static final String TARGET = "com/live2d/cubism/doc/model/texture/G";
    static final String TEXTURE_MANAGER_TARGET = "com/live2d/cubism/doc/model/texture/CTextureManager";

    private static final String ATLAS_LIST_FIELD = "a";
    private static final String ATLAS_LIST_DESC = "Ljava/util/List;";
    private static final String RUN_METHOD = "a";
    private static final String RUN_DESC = "(Lcom/live2d/util/a/a;)V";
    private static final String PAGE_LAMBDA = "com/live2d/cubism/doc/model/texture/H";
    private static final String SWITCH_TO_ATLAS_METHOD = "changeModelTextureInput_toAtlas";
    private static final String VOID_DESC = "()V";
    private static final String HELPER_INTERNAL = Type.getInternalName(AtlasRebuildParallelizer.class);
    private static final String HELPER_METHOD = "runParallelUpdate";
    private static final String HELPER_DESC = "(Ljava/util/List;Ljava/lang/Object;)Z";
    private static final String PREWARM_METHOD = "prewarmDirtyAtlases";
    private static final String PREWARM_DESC = "(Ljava/lang/Object;)V";

    private AtlasParallelPatcher() {}

    static boolean isSupportedTarget(final String internalName) {
        return TARGET.equals(internalName) || TEXTURE_MANAGER_TARGET.equals(internalName);
    }

    static byte[] patch(final String internalName, final byte[] classBytes) {
        if (TARGET.equals(internalName)) {
            return patchBatchLambda(classBytes);
        }
        if (TEXTURE_MANAGER_TARGET.equals(internalName)) {
            return patchTextureManager(classBytes);
        }
        return null;
    }

    private static byte[] patchBatchLambda(final byte[] classBytes) {
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

    private static byte[] patchTextureManager(final byte[] classBytes) {
        final ClassReader reader = new ClassReader(classBytes);
        if (!TEXTURE_MANAGER_TARGET.equals(reader.getClassName())) {
            return null;
        }
        final TextureManagerScan scan = new TextureManagerScan();
        reader.accept(scan, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        if (scan.alreadyPatched || !scan.hasTargetMethod) {
            return null;
        }
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(
                new ClassVisitor(ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String desc,
                            final String sig,
                            final String[] ex) {
                        final MethodVisitor output = super.visitMethod(access, name, desc, sig, ex);
                        if (!SWITCH_TO_ATLAS_METHOD.equals(name) || !VOID_DESC.equals(desc)) {
                            return output;
                        }
                        return new MethodVisitor(ASM9, output) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                super.visitVarInsn(ALOAD, 0);
                                super.visitMethodInsn(
                                        INVOKESTATIC, HELPER_INTERNAL, PREWARM_METHOD, PREWARM_DESC, false);
                            }
                        };
                    }
                },
                ClassReader.EXPAND_FRAMES);
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

    private static final class TextureManagerScan extends ClassVisitor {
        private boolean hasTargetMethod;
        private boolean alreadyPatched;

        TextureManagerScan() {
            super(ASM9);
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String sig, final String[] ex) {
            if (!SWITCH_TO_ATLAS_METHOD.equals(name) || !VOID_DESC.equals(desc)) {
                return null;
            }
            hasTargetMethod = true;
            return new MethodVisitor(ASM9) {
                @Override
                public void visitMethodInsn(
                        final int opcode,
                        final String owner,
                        final String mName,
                        final String mDesc,
                        final boolean itf) {
                    if (opcode == INVOKESTATIC && HELPER_INTERNAL.equals(owner) && PREWARM_METHOD.equals(mName)) {
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
