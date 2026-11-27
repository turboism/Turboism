package dev.turboism.adapter.cubism.optimization.export;

import static org.objectweb.asm.Opcodes.*;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

/**
 * Patches pixel-level edge extension, edge cleaning, and box downscaling across Cubism versions.
 */
final class EdgeAndDownscalePatcher {

    static final String EDGE_EXTEND_TARGET = "com/live2d/util/d/a";
    static final String EDGE_CLEAN_TARGET = "jp/noids/graphics/h";
    private static final String EDGE_CLEAN_UTIL_C = "com/live2d/util/C";
    private static final String EDGE_CLEAN_UTIL_D = "com/live2d/util/D";

    private static final String EXTEND_PUBLIC_METHOD = "a";
    private static final String EXTEND_PUBLIC_DESC =
            "(Ljava/awt/image/BufferedImage;ILkotlin/jvm/functions/Function2;)V";
    private static final String EXTEND_IMPL_METHOD = "b";
    private static final String EXTEND_IMPL_DESC = "(Ljava/awt/image/BufferedImage;ILkotlin/jvm/functions/Function2;)V";

    private static final String CLEAN_METHOD = "a";
    private static final String CLEAN_DESC = "(Ljava/awt/image/BufferedImage;IZ)Ljava/awt/image/BufferedImage;";
    private static final String UTIL_CLEAN_DESC = "(Ljava/awt/image/BufferedImage;)Ljava/awt/image/BufferedImage;";

    private static final String DOWNSCALE_METHOD = "a";
    private static final String DOWNSCALE_DESC = "(I[III[IIII)V";

    private static final String EXTEND_HELPER = Type.getInternalName(EdgeExtendFastOp.class);
    private static final String EXTEND_HELPER_METHOD = "tryExtendEdgeColor";
    private static final String EXTEND_HELPER_DESC = "(Ljava/awt/image/BufferedImage;ILjava/lang/Object;)Z";

    private static final String CLEAN_HELPER = Type.getInternalName(EdgeCleanFastOp.class);
    private static final String CLEAN_HELPER_METHOD = "fastCleanEdges";
    private static final String CLEAN_HELPER_DESC = "(Ljava/awt/image/BufferedImage;IZ)Ljava/awt/image/BufferedImage;";

    private static final String DOWNSCALE_HELPER = Type.getInternalName(DownscaleFastOp.class);
    private static final String DOWNSCALE_HELPER_METHOD = "downscaleBox";

    private static final String EXPORTER_HELPER = Type.getInternalName(FastPngExporter.class);
    private static final String SKIP_CHECK_METHOD = "isCurrentPagePrepared";
    private static final String SKIP_CHECK_DESC = "()Z";

    private EdgeAndDownscalePatcher() {}

    static boolean isSupportedTarget(final String internalName) {
        return EDGE_EXTEND_TARGET.equals(internalName)
                || EDGE_CLEAN_TARGET.equals(internalName)
                || EDGE_CLEAN_UTIL_C.equals(internalName)
                || EDGE_CLEAN_UTIL_D.equals(internalName)
                || isDownscaleCandidate(internalName);
    }

    static byte[] patch(final String internalName, final byte[] classBytes) {
        if (EDGE_EXTEND_TARGET.equals(internalName)) {
            return patchEdgeExtend(classBytes);
        }
        if (EDGE_CLEAN_TARGET.equals(internalName)) {
            return patchEdgeClean(classBytes);
        }
        if (EDGE_CLEAN_UTIL_C.equals(internalName) || EDGE_CLEAN_UTIL_D.equals(internalName)) {
            return patchEdgeCleanUtilWrapper(internalName, classBytes);
        }
        if (isDownscaleCandidate(internalName)) {
            return patchDownscale(internalName, classBytes);
        }
        return null;
    }

    private static boolean isDownscaleCandidate(final String internalName) {
        return internalName != null
                && internalName.length() == "com/live2d/util/d/g".length()
                && internalName.startsWith("com/live2d/util/")
                && internalName.endsWith("/g");
    }

    private static byte[] patchEdgeExtend(final byte[] classBytes) {
        final ClassReader reader = new ClassReader(classBytes);
        if (!EDGE_EXTEND_TARGET.equals(reader.getClassName())) {
            return null;
        }
        final ExtendScan scan = new ExtendScan();
        reader.accept(scan, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        if (scan.alreadyPatched || !scan.matches()) {
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
                        final MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                        if (EXTEND_PUBLIC_METHOD.equals(name) && EXTEND_PUBLIC_DESC.equals(desc)) {
                            return new MethodVisitor(ASM9, mv) {
                                @Override
                                public void visitCode() {
                                    super.visitCode();
                                    final Label proceed = new Label();
                                    super.visitMethodInsn(
                                            INVOKESTATIC, EXPORTER_HELPER, SKIP_CHECK_METHOD, SKIP_CHECK_DESC, false);
                                    super.visitJumpInsn(IFEQ, proceed);
                                    super.visitInsn(RETURN);
                                    super.visitLabel(proceed);
                                    super.visitFrame(
                                            F_NEW,
                                            4,
                                            new Object[] {
                                                EDGE_EXTEND_TARGET,
                                                "java/awt/image/BufferedImage",
                                                INTEGER,
                                                "kotlin/jvm/functions/Function2"
                                            },
                                            0,
                                            new Object[0]);
                                }
                            };
                        }
                        if (EXTEND_IMPL_METHOD.equals(name) && EXTEND_IMPL_DESC.equals(desc)) {
                            return new MethodVisitor(ASM9, mv) {
                                @Override
                                public void visitCode() {
                                    super.visitCode();
                                    final Label fallback = new Label();
                                    super.visitVarInsn(ALOAD, 1);
                                    super.visitVarInsn(ILOAD, 2);
                                    super.visitVarInsn(ALOAD, 3);
                                    super.visitMethodInsn(
                                            INVOKESTATIC,
                                            EXTEND_HELPER,
                                            EXTEND_HELPER_METHOD,
                                            EXTEND_HELPER_DESC,
                                            false);
                                    super.visitJumpInsn(IFEQ, fallback);
                                    super.visitInsn(RETURN);
                                    super.visitLabel(fallback);
                                    super.visitFrame(
                                            F_NEW,
                                            4,
                                            new Object[] {
                                                EDGE_EXTEND_TARGET,
                                                "java/awt/image/BufferedImage",
                                                INTEGER,
                                                "kotlin/jvm/functions/Function2"
                                            },
                                            0,
                                            new Object[0]);
                                }
                            };
                        }
                        return mv;
                    }
                },
                ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static byte[] patchEdgeClean(final byte[] classBytes) {
        final ClassReader reader = new ClassReader(classBytes);
        if (!EDGE_CLEAN_TARGET.equals(reader.getClassName())) {
            return null;
        }
        final CleanScan scan = new CleanScan();
        reader.accept(scan, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        if (scan.alreadyPatched || !scan.matches()) {
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
                        final MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                        if ((access & ACC_STATIC) == 0 || !CLEAN_METHOD.equals(name) || !CLEAN_DESC.equals(desc)) {
                            return mv;
                        }
                        return new MethodVisitor(ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                final Label fallback = new Label();
                                super.visitVarInsn(ALOAD, 0);
                                super.visitVarInsn(ILOAD, 1);
                                super.visitVarInsn(ILOAD, 2);
                                super.visitMethodInsn(
                                        INVOKESTATIC, CLEAN_HELPER, CLEAN_HELPER_METHOD, CLEAN_HELPER_DESC, false);
                                super.visitInsn(DUP);
                                super.visitJumpInsn(IFNULL, fallback);
                                super.visitInsn(ARETURN);
                                super.visitLabel(fallback);
                                super.visitFrame(
                                        F_NEW,
                                        3,
                                        new Object[] {"java/awt/image/BufferedImage", INTEGER, INTEGER},
                                        1,
                                        new Object[] {"java/awt/image/BufferedImage"});
                                super.visitInsn(POP);
                            }
                        };
                    }
                },
                ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static byte[] patchEdgeCleanUtilWrapper(final String internalName, final byte[] classBytes) {
        final ClassReader reader = new ClassReader(classBytes);
        if (!internalName.equals(reader.getClassName())) {
            return null;
        }
        final WrapperScan scan = new WrapperScan();
        reader.accept(scan, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        if (scan.alreadyPatched || !scan.matches()) {
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
                        final MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                        if (!CLEAN_METHOD.equals(name) || !UTIL_CLEAN_DESC.equals(desc)) {
                            return mv;
                        }
                        return new MethodVisitor(ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                final Label proceed = new Label();
                                super.visitMethodInsn(
                                        INVOKESTATIC, EXPORTER_HELPER, SKIP_CHECK_METHOD, SKIP_CHECK_DESC, false);
                                super.visitJumpInsn(IFEQ, proceed);
                                super.visitVarInsn(ALOAD, 1);
                                super.visitInsn(ARETURN);
                                super.visitLabel(proceed);
                                super.visitFrame(
                                        F_NEW,
                                        2,
                                        new Object[] {internalName, "java/awt/image/BufferedImage"},
                                        0,
                                        new Object[0]);
                            }
                        };
                    }
                },
                ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static byte[] patchDownscale(final String internalName, final byte[] classBytes) {
        final ClassReader reader = new ClassReader(classBytes);
        if (!internalName.equals(reader.getClassName())) {
            return null;
        }
        final DownscaleScan scan = new DownscaleScan();
        reader.accept(scan, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        if (scan.alreadyPatched || !scan.matches()) {
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
                        final MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                        if (!DOWNSCALE_METHOD.equals(name) || !DOWNSCALE_DESC.equals(desc)) {
                            return mv;
                        }
                        mv.visitCode();
                        mv.visitVarInsn(ILOAD, 1);
                        mv.visitVarInsn(ALOAD, 2);
                        mv.visitVarInsn(ILOAD, 3);
                        mv.visitVarInsn(ILOAD, 4);
                        mv.visitVarInsn(ALOAD, 5);
                        mv.visitVarInsn(ILOAD, 6);
                        mv.visitVarInsn(ILOAD, 7);
                        mv.visitVarInsn(ILOAD, 8);
                        mv.visitMethodInsn(
                                INVOKESTATIC, DOWNSCALE_HELPER, DOWNSCALE_HELPER_METHOD, DOWNSCALE_DESC, false);
                        mv.visitInsn(RETURN);
                        mv.visitMaxs(8, 9);
                        mv.visitEnd();
                        return null;
                    }
                },
                ClassReader.SKIP_FRAMES);
        return writer.toByteArray();
    }

    private static final class ExtendScan extends ClassVisitor {
        private boolean hasPublicMethod;
        private boolean hasImplMethod;
        private boolean usesRasterGetPixels;
        private boolean alreadyPatched;

        ExtendScan() {
            super(ASM9);
        }

        boolean matches() {
            return hasPublicMethod && hasImplMethod && usesRasterGetPixels;
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String sig, final String[] ex) {
            if (EXTEND_PUBLIC_METHOD.equals(name) && EXTEND_PUBLIC_DESC.equals(desc)) {
                hasPublicMethod = true;
            }
            if (!EXTEND_IMPL_METHOD.equals(name) || !EXTEND_IMPL_DESC.equals(desc)) {
                return null;
            }
            hasImplMethod = true;
            return new MethodVisitor(ASM9) {
                @Override
                public void visitMethodInsn(
                        final int opcode,
                        final String owner,
                        final String mName,
                        final String mDesc,
                        final boolean itf) {
                    if ("java/awt/image/WritableRaster".equals(owner) && "getPixels".equals(mName)) {
                        usesRasterGetPixels = true;
                    }
                    if (opcode == INVOKESTATIC && EXTEND_HELPER.equals(owner) && EXTEND_HELPER_METHOD.equals(mName)) {
                        alreadyPatched = true;
                    }
                }
            };
        }
    }

    private static final class CleanScan extends ClassVisitor {
        private boolean hasTargetMethod;
        private boolean callsSpiralHelper;
        private boolean alreadyPatched;

        CleanScan() {
            super(ASM9);
        }

        boolean matches() {
            return hasTargetMethod && callsSpiralHelper;
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String sig, final String[] ex) {
            if ((access & ACC_STATIC) == 0 || !CLEAN_METHOD.equals(name) || !CLEAN_DESC.equals(desc)) {
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
                    if (opcode == INVOKESTATIC
                            && EDGE_CLEAN_TARGET.equals(owner)
                            && "b".equals(mName)
                            && "(Ljava/awt/image/BufferedImage;IZ)Ljava/awt/image/BufferedImage;".equals(mDesc)) {
                        callsSpiralHelper = true;
                    }
                    if (opcode == INVOKESTATIC && CLEAN_HELPER.equals(owner) && CLEAN_HELPER_METHOD.equals(mName)) {
                        alreadyPatched = true;
                    }
                }
            };
        }
    }

    private static final class WrapperScan extends ClassVisitor {
        private boolean hasMethod;
        private boolean callsEdgeCleanDelegate;
        private boolean alreadyPatched;

        WrapperScan() {
            super(ASM9);
        }

        boolean matches() {
            return hasMethod && callsEdgeCleanDelegate;
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String sig, final String[] ex) {
            if (!CLEAN_METHOD.equals(name) || !UTIL_CLEAN_DESC.equals(desc)) {
                return null;
            }
            hasMethod = true;
            return new MethodVisitor(ASM9) {
                @Override
                public void visitMethodInsn(
                        final int opcode,
                        final String owner,
                        final String mName,
                        final String mDesc,
                        final boolean itf) {
                    if (opcode == INVOKESTATIC
                            && "jp/live2d/l/a/a".equals(owner)
                            && CLEAN_METHOD.equals(mName)
                            && CLEAN_DESC.equals(mDesc)) {
                        callsEdgeCleanDelegate = true;
                    }
                    if (opcode == INVOKESTATIC && EXPORTER_HELPER.equals(owner) && SKIP_CHECK_METHOD.equals(mName)) {
                        alreadyPatched = true;
                    }
                }
            };
        }
    }

    private static final class DownscaleScan extends ClassVisitor {
        private boolean hasTargetMethod;
        private boolean referencesCWritableImage;
        private boolean alreadyPatched;

        DownscaleScan() {
            super(ASM9);
        }

        boolean matches() {
            return hasTargetMethod && referencesCWritableImage;
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String sig, final String[] ex) {
            if (DOWNSCALE_DESC.equals(desc) && DOWNSCALE_METHOD.equals(name)) {
                hasTargetMethod = true;
                return new MethodVisitor(ASM9) {
                    @Override
                    public void visitMethodInsn(
                            final int opcode,
                            final String owner,
                            final String mName,
                            final String mDesc,
                            final boolean itf) {
                        if (opcode == INVOKESTATIC
                                && DOWNSCALE_HELPER.equals(owner)
                                && DOWNSCALE_HELPER_METHOD.equals(mName)) {
                            alreadyPatched = true;
                        }
                    }
                };
            }
            if (desc.contains("Lcom/live2d/graphics/CWritableImage;")) {
                referencesCWritableImage = true;
            }
            return null;
        }
    }
}
