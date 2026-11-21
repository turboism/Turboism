package dev.turboism.adapter.cubism.exportperf;

import static org.objectweb.asm.Opcodes.*;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

/**
 * Patches export-cloner scope boundaries, {@code CImageResource}/{@code CWritableImage} token
 * transfer, fast {@code forceCopy}, fast PNG encode, and clean atlas state propagation.
 */
final class ExportCloneBypassPatcher {

    static final String IMAGE_RESOURCE_TARGET = "com/live2d/graphics/CImageResource";
    static final String WRITABLE_IMAGE_TARGET = "com/live2d/graphics/CWritableImage";
    static final String TEXTURE_ATLAS_TARGET = "com/live2d/cubism/doc/model/texture/textureAtlas/CTextureAtlas";

    private static final String EXPORTER_PKG_PREFIX = "com/live2d/cubism/doc/model/exporter/";
    private static final String CLONER_METHOD = "a";
    private static final String CLONER_DESC = "(Lcom/live2d/cubism/doc/model/CModelSource;Lcom/live2d/util/a/a;)"
            + "Lcom/live2d/cubism/doc/model/CModelSource;";
    private static final String MODEL_SOURCE_INTERNAL = "com/live2d/cubism/doc/model/CModelSource";
    private static final String PROGRESS_INTERNAL = "com/live2d/util/a/a";
    private static final String SERIALIZE_DESC = "(Lorg/jdom/Element;Lcom/live2d/serialize/XmlWriter;)V";
    private static final String DESERIALIZE_DESC = "(Lorg/jdom/Element;Lcom/live2d/serialize/XmlReader;)V";
    private static final String FORCE_COPY_DESC = "(Ljava/awt/image/BufferedImage;I)Ljava/awt/image/BufferedImage;";
    private static final String CREATE_REDUCED_DESC = "(II)Lcom/live2d/graphics/CWritableImage;";
    private static final String WRITE_PNG_DESC = "(Ljava/io/OutputStream;)V";

    private static final String SCOPE_HELPER = Type.getInternalName(RuntimeExportScope.class);
    private static final String PNG_EXPORTER_HELPER = Type.getInternalName(FastPngExporter.class);
    private static final String PNG_ENCODER_HELPER = Type.getInternalName(FastPngEncoder.class);
    private static final String EXT_INTERP_OWNER = "com/live2d/cubism/doc/model/interpolator/extendedInterpolation/l";
    private static final String ROOT_DEFORMER_ANCHOR = "__root_deformer_for_model_export__";
    private static final String THROWABLE_INTERNAL = "java/lang/Throwable";
    private static final int CLONER_PARAM_SLOTS = 3;

    private ExportCloneBypassPatcher() {}

    static boolean isSupportedTarget(final String internalName) {
        return IMAGE_RESOURCE_TARGET.equals(internalName)
                || WRITABLE_IMAGE_TARGET.equals(internalName)
                || TEXTURE_ATLAS_TARGET.equals(internalName)
                || isClonerCandidate(internalName);
    }

    static byte[] patch(final String internalName, final byte[] classBytes) {
        if (IMAGE_RESOURCE_TARGET.equals(internalName)) {
            return patchImageResource(classBytes);
        }
        if (WRITABLE_IMAGE_TARGET.equals(internalName)) {
            return patchWritableImage(classBytes);
        }
        if (TEXTURE_ATLAS_TARGET.equals(internalName)) {
            return patchTextureAtlas(classBytes);
        }
        if (isClonerCandidate(internalName)) {
            return patchExporterCloner(internalName, classBytes);
        }
        return null;
    }

    private static boolean isClonerCandidate(final String internalName) {
        return internalName != null
                && internalName.startsWith(EXPORTER_PKG_PREFIX)
                && internalName.endsWith("$a")
                && internalName.length() == EXPORTER_PKG_PREFIX.length() + 3;
    }

    private static byte[] patchImageResource(final byte[] classBytes) {
        final ClassReader reader = new ClassReader(classBytes);
        if (!IMAGE_RESOURCE_TARGET.equals(reader.getClassName())) {
            return null;
        }
        final ResourceScan scan = new ResourceScan();
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
                        if ("serialize".equals(name) && SERIALIZE_DESC.equals(desc)) {
                            return wrapTokenShortCircuit(
                                    mv,
                                    IMAGE_RESOURCE_TARGET,
                                    "com/live2d/serialize/XmlWriter",
                                    "trySerializeImageResource");
                        }
                        if ("deserialize".equals(name) && DESERIALIZE_DESC.equals(desc)) {
                            return wrapTokenShortCircuit(
                                    mv,
                                    IMAGE_RESOURCE_TARGET,
                                    "com/live2d/serialize/XmlReader",
                                    "tryDeserializeImageResource");
                        }
                        return mv;
                    }
                },
                ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static byte[] patchWritableImage(final byte[] classBytes) {
        final ClassReader reader = new ClassReader(classBytes);
        if (!WRITABLE_IMAGE_TARGET.equals(reader.getClassName())) {
            return null;
        }
        final WritableScan scan = new WritableScan();
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
                        if ("serialize".equals(name) && SERIALIZE_DESC.equals(desc)) {
                            return wrapTokenShortCircuit(
                                    mv,
                                    WRITABLE_IMAGE_TARGET,
                                    "com/live2d/serialize/XmlWriter",
                                    "trySerializeWritableImage");
                        }
                        if ("deserialize".equals(name) && DESERIALIZE_DESC.equals(desc)) {
                            return wrapTokenShortCircuit(
                                    mv,
                                    WRITABLE_IMAGE_TARGET,
                                    "com/live2d/serialize/XmlReader",
                                    "tryDeserializeWritableImage");
                        }
                        if ("forceCopy".equals(name) && FORCE_COPY_DESC.equals(desc)) {
                            return wrapFastForceCopy(mv);
                        }
                        if ("createReduced".equals(name) && CREATE_REDUCED_DESC.equals(desc)) {
                            return wrapFastCreateReducedSkip(mv);
                        }
                        if ("writeImageAsPng".equals(name) && WRITE_PNG_DESC.equals(desc)) {
                            return wrapWritePng(mv);
                        }
                        return mv;
                    }
                },
                ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static byte[] patchTextureAtlas(final byte[] classBytes) {
        final ClassReader reader = new ClassReader(classBytes);
        if (!TEXTURE_ATLAS_TARGET.equals(reader.getClassName())) {
            return null;
        }
        final AtlasScan scan = new AtlasScan();
        reader.accept(scan, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        if (scan.alreadyPatched || !scan.hasOnDeserialized) {
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
                        if (!"onDeserialized".equals(name) || !DESERIALIZE_DESC.equals(desc)) {
                            return mv;
                        }
                        return new MethodVisitor(ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                super.visitVarInsn(ALOAD, 0);
                                super.visitMethodInsn(
                                        INVOKESTATIC,
                                        SCOPE_HELPER,
                                        "beginAtlasDeserialized",
                                        "(Ljava/lang/Object;)V",
                                        false);
                            }

                            @Override
                            public void visitInsn(final int opcode) {
                                if (opcode == RETURN) {
                                    super.visitMethodInsn(
                                            INVOKESTATIC, SCOPE_HELPER, "endAtlasDeserialized", "()V", false);
                                }
                                super.visitInsn(opcode);
                            }
                        };
                    }
                },
                ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static byte[] patchExporterCloner(final String internalName, final byte[] classBytes) {
        final ClassReader reader = new ClassReader(classBytes);
        final ClonerScan scan = new ClonerScan();
        reader.accept(scan, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        if (!scan.matches()) {
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
                        if (!CLONER_METHOD.equals(name) || !CLONER_DESC.equals(desc)) {
                            return mv;
                        }
                        return new ClonerMethodVisitor(
                                mv, internalName, scan.copyLocalVar, scan.confirmedExtInterpCallOrdinal);
                    }
                },
                ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static MethodVisitor wrapTokenShortCircuit(
            final MethodVisitor delegate,
            final String ownerInternal,
            final String secondArgInternal,
            final String helperMethod) {
        return new MethodVisitor(ASM9, delegate) {
            @Override
            public void visitCode() {
                super.visitCode();
                final Label fallback = new Label();
                super.visitVarInsn(ALOAD, 0);
                super.visitVarInsn(ALOAD, 1);
                super.visitMethodInsn(
                        INVOKESTATIC, SCOPE_HELPER, helperMethod, "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
                super.visitJumpInsn(IFEQ, fallback);
                super.visitInsn(RETURN);
                super.visitLabel(fallback);
                super.visitFrame(
                        F_NEW,
                        3,
                        new Object[] {ownerInternal, "org/jdom/Element", secondArgInternal},
                        0,
                        new Object[0]);
            }
        };
    }

    private static MethodVisitor wrapFastForceCopy(final MethodVisitor delegate) {
        return new MethodVisitor(ASM9, delegate) {
            @Override
            public void visitCode() {
                super.visitCode();
                final Label fallback = new Label();
                super.visitVarInsn(ALOAD, 1);
                super.visitVarInsn(ILOAD, 2);
                super.visitMethodInsn(INVOKESTATIC, SCOPE_HELPER, "fastForceCopy", FORCE_COPY_DESC, false);
                super.visitInsn(DUP);
                super.visitJumpInsn(IFNULL, fallback);
                super.visitInsn(ARETURN);
                super.visitLabel(fallback);
                super.visitFrame(
                        F_NEW,
                        3,
                        new Object[] {WRITABLE_IMAGE_TARGET, "java/awt/image/BufferedImage", INTEGER},
                        1,
                        new Object[] {"java/awt/image/BufferedImage"});
                super.visitInsn(POP);
            }
        };
    }

    private static MethodVisitor wrapFastCreateReducedSkip(final MethodVisitor delegate) {
        return new MethodVisitor(ASM9, delegate) {
            @Override
            public void visitCode() {
                super.visitCode();
                final Label proceed = new Label();
                super.visitMethodInsn(INVOKESTATIC, PNG_EXPORTER_HELPER, "isCurrentPagePrepared", "()Z", false);
                super.visitJumpInsn(IFEQ, proceed);
                super.visitVarInsn(ALOAD, 0);
                super.visitInsn(ARETURN);
                super.visitLabel(proceed);
                super.visitFrame(F_NEW, 3, new Object[] {WRITABLE_IMAGE_TARGET, INTEGER, INTEGER}, 0, new Object[0]);
            }
        };
    }

    private static MethodVisitor wrapWritePng(final MethodVisitor delegate) {
        return new MethodVisitor(ASM9, delegate) {
            @Override
            public void visitCode() {
                super.visitCode();
                final Label checkDirect = new Label();
                final Label fallback = new Label();
                super.visitVarInsn(ALOAD, 1);
                super.visitMethodInsn(
                        INVOKESTATIC, PNG_EXPORTER_HELPER, "tryConsumePreparedPng", "(Ljava/io/OutputStream;)Z", false);
                super.visitJumpInsn(IFEQ, checkDirect);
                super.visitInsn(RETURN);
                super.visitLabel(checkDirect);
                super.visitFrame(
                        F_NEW, 2, new Object[] {WRITABLE_IMAGE_TARGET, "java/io/OutputStream"}, 0, new Object[0]);
                super.visitVarInsn(ALOAD, 0);
                super.visitMethodInsn(
                        INVOKEVIRTUAL,
                        WRITABLE_IMAGE_TARGET,
                        "getJBufferedImage",
                        "()Ljava/awt/image/BufferedImage;",
                        false);
                super.visitVarInsn(ALOAD, 1);
                super.visitMethodInsn(
                        INVOKESTATIC,
                        PNG_ENCODER_HELPER,
                        "writeIfSupported",
                        "(Ljava/awt/image/BufferedImage;Ljava/io/OutputStream;)Z",
                        false);
                super.visitJumpInsn(IFEQ, fallback);
                super.visitInsn(RETURN);
                super.visitLabel(fallback);
                super.visitFrame(
                        F_NEW, 2, new Object[] {WRITABLE_IMAGE_TARGET, "java/io/OutputStream"}, 0, new Object[0]);
            }
        };
    }

    private static final class ClonerScan extends ClassVisitor {
        private boolean hasAnchorString;
        private boolean hasCloneMethod;
        private boolean alreadyScoped;
        private boolean overwritesParams;
        private int copyLocalVar = -1;
        private int confirmedExtInterpCallOrdinal = -1;

        ClonerScan() {
            super(ASM9);
        }

        boolean matches() {
            return hasAnchorString && hasCloneMethod && !alreadyScoped && !overwritesParams;
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String sig, final String[] ex) {
            final boolean isCloneTarget = CLONER_METHOD.equals(name) && CLONER_DESC.equals(desc);
            if (isCloneTarget) {
                hasCloneMethod = true;
            }
            return new MethodVisitor(ASM9) {
                private boolean prevCheckcastModelSource;
                private int methodCallOrdinal;
                private int pendingExtInterpCall = -1;

                @Override
                public void visitLdcInsn(final Object value) {
                    if (ROOT_DEFORMER_ANCHOR.equals(value)) {
                        hasAnchorString = true;
                    }
                    prevCheckcastModelSource = false;
                    pendingExtInterpCall = -1;
                }

                @Override
                public void visitTypeInsn(final int opcode, final String type) {
                    prevCheckcastModelSource = opcode == CHECKCAST && MODEL_SOURCE_INTERNAL.equals(type);
                    pendingExtInterpCall = -1;
                }

                @Override
                public void visitVarInsn(final int opcode, final int var) {
                    if (isCloneTarget) {
                        if (var < CLONER_PARAM_SLOTS && opcode >= ISTORE && opcode <= ASTORE) {
                            overwritesParams = true;
                        }
                        if (prevCheckcastModelSource && opcode == ASTORE) {
                            copyLocalVar = var;
                        }
                    }
                    prevCheckcastModelSource = false;
                    pendingExtInterpCall = -1;
                }

                @Override
                public void visitMethodInsn(
                        final int opcode,
                        final String owner,
                        final String mName,
                        final String mDesc,
                        final boolean itf) {
                    if (isCloneTarget) {
                        if (SCOPE_HELPER.equals(owner)) {
                            alreadyScoped = true;
                        }
                        final int currentOrdinal = methodCallOrdinal++;
                        if (EXT_INTERP_OWNER.equals(owner)
                                && "a".equals(mName)
                                && "()Z".equals(mDesc)
                                && copyLocalVar >= 0) {
                            pendingExtInterpCall = currentOrdinal;
                        } else {
                            pendingExtInterpCall = -1;
                        }
                    }
                    prevCheckcastModelSource = false;
                }

                @Override
                public void visitJumpInsn(final int opcode, final Label label) {
                    if (isCloneTarget
                            && pendingExtInterpCall >= 0
                            && opcode == IFEQ
                            && confirmedExtInterpCallOrdinal < 0) {
                        confirmedExtInterpCallOrdinal = pendingExtInterpCall;
                    }
                    prevCheckcastModelSource = false;
                    pendingExtInterpCall = -1;
                }

                @Override
                public void visitInsn(final int opcode) {
                    prevCheckcastModelSource = false;
                    pendingExtInterpCall = -1;
                }

                @Override
                public void visitIntInsn(final int opcode, final int operand) {
                    prevCheckcastModelSource = false;
                    pendingExtInterpCall = -1;
                }

                @Override
                public void visitFieldInsn(
                        final int opcode, final String owner, final String fName, final String fDesc) {
                    prevCheckcastModelSource = false;
                    pendingExtInterpCall = -1;
                }
            };
        }
    }

    private static final class ClonerMethodVisitor extends MethodVisitor {
        private final String ownerInternal;
        private final int copyLocalVar;
        private final int targetExtInterpOrdinal;
        private final Label tryStart = new Label();
        private final Label tryEnd = new Label();
        private final Label catchHandler = new Label();
        private int methodCallOrdinal;
        private boolean waitingForTargetIfeq;
        private boolean codeVisited;

        ClonerMethodVisitor(
                final MethodVisitor delegate,
                final String ownerInternal,
                final int copyLocalVar,
                final int targetExtInterpOrdinal) {
            super(ASM9, delegate);
            this.ownerInternal = ownerInternal;
            this.copyLocalVar = copyLocalVar;
            this.targetExtInterpOrdinal = targetExtInterpOrdinal;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            codeVisited = true;
            super.visitVarInsn(ALOAD, 1);
            super.visitMethodInsn(INVOKESTATIC, SCOPE_HELPER, "enter", "(Ljava/lang/Object;)V", false);
            super.visitLabel(tryStart);
        }

        @Override
        public void visitMethodInsn(
                final int opcode, final String owner, final String name, final String desc, final boolean itf) {
            super.visitMethodInsn(opcode, owner, name, desc, itf);
            final int current = methodCallOrdinal++;
            waitingForTargetIfeq = targetExtInterpOrdinal >= 0 && current == targetExtInterpOrdinal;
        }

        @Override
        public void visitJumpInsn(final int opcode, final Label label) {
            super.visitJumpInsn(opcode, label);
            if (waitingForTargetIfeq && opcode == IFEQ && copyLocalVar >= 0) {
                super.visitVarInsn(ALOAD, copyLocalVar);
                super.visitMethodInsn(
                        INVOKESTATIC, SCOPE_HELPER, "needsExtendedInterpolationPass", "(Ljava/lang/Object;)Z", false);
                super.visitJumpInsn(IFEQ, label);
            }
            waitingForTargetIfeq = false;
        }

        @Override
        public void visitInsn(final int opcode) {
            waitingForTargetIfeq = false;
            if (opcode == ARETURN) {
                super.visitMethodInsn(INVOKESTATIC, SCOPE_HELPER, "exit", "()V", false);
            }
            super.visitInsn(opcode);
        }

        @Override
        public void visitVarInsn(final int opcode, final int var) {
            waitingForTargetIfeq = false;
            super.visitVarInsn(opcode, var);
        }

        @Override
        public void visitTypeInsn(final int opcode, final String type) {
            waitingForTargetIfeq = false;
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitFieldInsn(final int opcode, final String owner, final String name, final String desc) {
            waitingForTargetIfeq = false;
            super.visitFieldInsn(opcode, owner, name, desc);
        }

        @Override
        public void visitMaxs(final int maxStack, final int maxLocals) {
            if (codeVisited) {
                super.visitLabel(tryEnd);
                super.visitLabel(catchHandler);
                super.visitFrame(F_NEW, 0, new Object[0], 1, new Object[] {THROWABLE_INTERNAL});
                super.visitMethodInsn(INVOKESTATIC, SCOPE_HELPER, "exit", "()V", false);
                super.visitInsn(ATHROW);
                super.visitTryCatchBlock(tryStart, tryEnd, catchHandler, null);
            }
            super.visitMaxs(maxStack, maxLocals);
        }
    }

    private static final class ResourceScan extends ClassVisitor {
        private boolean hasSerialize;
        private boolean hasDeserialize;
        private boolean alreadyPatched;

        ResourceScan() {
            super(ASM9);
        }

        boolean matches() {
            return hasSerialize && hasDeserialize;
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String sig, final String[] ex) {
            if ("serialize".equals(name) && SERIALIZE_DESC.equals(desc)) {
                hasSerialize = true;
            } else if ("deserialize".equals(name) && DESERIALIZE_DESC.equals(desc)) {
                hasDeserialize = true;
            } else {
                return null;
            }
            return new MethodVisitor(ASM9) {
                @Override
                public void visitMethodInsn(
                        final int opcode,
                        final String owner,
                        final String mName,
                        final String mDesc,
                        final boolean itf) {
                    if (opcode == INVOKESTATIC && SCOPE_HELPER.equals(owner)) {
                        alreadyPatched = true;
                    }
                }
            };
        }
    }

    private static final class WritableScan extends ClassVisitor {
        private boolean hasSerialize;
        private boolean hasDeserialize;
        private boolean hasForceCopy;
        private boolean hasCreateReduced;
        private boolean hasWritePng;
        private boolean alreadyPatched;

        WritableScan() {
            super(ASM9);
        }

        boolean matches() {
            return hasSerialize && hasDeserialize && hasForceCopy && hasCreateReduced && hasWritePng;
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String sig, final String[] ex) {
            if ("serialize".equals(name) && SERIALIZE_DESC.equals(desc)) {
                hasSerialize = true;
            } else if ("deserialize".equals(name) && DESERIALIZE_DESC.equals(desc)) {
                hasDeserialize = true;
            } else if ("forceCopy".equals(name) && FORCE_COPY_DESC.equals(desc)) {
                hasForceCopy = true;
            } else if ("createReduced".equals(name) && CREATE_REDUCED_DESC.equals(desc)) {
                hasCreateReduced = true;
            } else if ("writeImageAsPng".equals(name) && WRITE_PNG_DESC.equals(desc)) {
                hasWritePng = true;
            } else {
                return null;
            }
            return new MethodVisitor(ASM9) {
                @Override
                public void visitMethodInsn(
                        final int opcode,
                        final String owner,
                        final String mName,
                        final String mDesc,
                        final boolean itf) {
                    if (opcode == INVOKESTATIC
                            && (SCOPE_HELPER.equals(owner)
                                    || PNG_EXPORTER_HELPER.equals(owner)
                                    || PNG_ENCODER_HELPER.equals(owner))) {
                        alreadyPatched = true;
                    }
                }
            };
        }
    }

    private static final class AtlasScan extends ClassVisitor {
        private boolean hasOnDeserialized;
        private boolean alreadyPatched;

        AtlasScan() {
            super(ASM9);
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String sig, final String[] ex) {
            if (!"onDeserialized".equals(name) || !DESERIALIZE_DESC.equals(desc)) {
                return null;
            }
            hasOnDeserialized = true;
            return new MethodVisitor(ASM9) {
                @Override
                public void visitMethodInsn(
                        final int opcode,
                        final String owner,
                        final String mName,
                        final String mDesc,
                        final boolean itf) {
                    if (opcode == INVOKESTATIC && SCOPE_HELPER.equals(owner)) {
                        alreadyPatched = true;
                    }
                }
            };
        }
    }
}
