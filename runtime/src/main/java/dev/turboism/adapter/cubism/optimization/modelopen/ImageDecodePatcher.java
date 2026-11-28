package dev.turboism.adapter.cubism.optimization.modelopen;

import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SYNCHRONIZED;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.GETFIELD;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.IADD;
import static org.objectweb.asm.Opcodes.ICONST_0;
import static org.objectweb.asm.Opcodes.ICONST_1;
import static org.objectweb.asm.Opcodes.ICONST_4;
import static org.objectweb.asm.Opcodes.ICONST_M1;
import static org.objectweb.asm.Opcodes.IFNE;
import static org.objectweb.asm.Opcodes.IFNONNULL;
import static org.objectweb.asm.Opcodes.IFNULL;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.IMUL;
import static org.objectweb.asm.Opcodes.INEG;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.ISTORE;
import static org.objectweb.asm.Opcodes.ISUB;
import static org.objectweb.asm.Opcodes.PUTSTATIC;
import static org.objectweb.asm.Opcodes.RETURN;

import java.util.ArrayDeque;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Patches {@code jp/noids/graphics/UtImage} for memory-cached PNG decode and fast ABGR-to-ARGB
 * conversion, and hardens static loaded counters on {@code com/live2d/graphics/CImageResource}.
 */
final class ImageDecodePatcher {

    static final String UT_IMAGE = "jp/noids/graphics/UtImage";
    static final String IMAGE_RESOURCE = "com/live2d/graphics/CImageResource";

    private static final String BRIDGE_OWNER = "dev/turboism/adapter/cubism/optimization/modelopen/ImageDecodeBridge";
    private static final String READ_BUFFERED_IMAGE_NAME = "readBufferedImage";
    private static final String READ_BUFFERED_IMAGE_DESC =
            "(Ljava/io/InputStream;Ljava/io/InputStream;I)Ljava/awt/image/BufferedImage;";
    private static final String TRY_CONVERT_NAME = "tryConvertToIntArgb";
    private static final String TRY_CONVERT_DESC = "(Ljava/awt/image/BufferedImage;I)Ljava/awt/image/BufferedImage;";

    private static final String GET_BUFFERED_IMAGE_NAME = "getBufferedImage";
    private static final String GET_BUFFERED_IMAGE_DESC =
            "(Ljava/io/InputStream;ILjp/noids/framework/e/c;)Ljava/awt/image/BufferedImage;";
    private static final String CREATE_SAME_TYPE_NAME = "createSameTypeImage";
    private static final String CREATE_SAME_TYPE_DESC =
            "(Ljava/awt/image/BufferedImage;I)Ljava/awt/image/BufferedImage;";
    private static final String NATIVE_CREATE_SAME_TYPE_NAME = "turboism$nativeCreateSameTypeImage";

    private static final String IMAGEIO_OWNER = "javax/imageio/ImageIO";
    private static final String IMAGEIO_READ_NAME = "read";
    private static final String IMAGEIO_READ_STREAM_DESC = "(Ljava/io/InputStream;)Ljava/awt/image/BufferedImage;";

    private static final String FIELD_LOADED_COUNT = "loadedCount";
    private static final String FIELD_LOADED_BYTES = "loadedBytes";
    private static final String FIELD_WIDTH = "width";
    private static final String FIELD_HEIGHT = "height";
    private static final String FIELD_IS_DISPOSED = "isDisposed";
    private static final String FIELD_IMAGE = "image";
    private static final String FIELD_IMAGE_FILE_BUF = "imageFileBuf";
    private static final String ADD_LOADED_COUNT_NAME = "turboism$addLoadedCount";
    private static final String ADD_LOADED_BYTES_NAME = "turboism$addLoadedBytes";
    private static final String NEEDS_PREDECODE_NAME = "turboism$needsPredecode";
    private static final String INT_VOID_DESC = "(I)V";
    private static final String NEEDS_PREDECODE_DESC = "()Z";
    private static final int REQUIRED_STATS_REWRITE_SITES = 10;

    private ImageDecodePatcher() {}

    static byte[] tryPatch(final String internalName, final ClassLoader loader, final byte[] classfileBuffer) {
        if (UT_IMAGE.equals(internalName)) {
            return patchUtImage(loader, classfileBuffer);
        }
        if (IMAGE_RESOURCE.equals(internalName)) {
            return patchImageResource(loader, classfileBuffer);
        }
        return null;
    }

    private static byte[] patchUtImage(final ClassLoader loader, final byte[] classfileBuffer) {
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (ModelOpenClassWriters.hasDeclaredMethod(reader, NATIVE_CREATE_SAME_TYPE_NAME, CREATE_SAME_TYPE_DESC)
                || ModelOpenClassWriters.callsOwner(reader, BRIDGE_OWNER)) {
            return null;
        }
        final ClassWriter writer = ModelOpenClassWriters.newFrameWriter(reader, loader);
        final boolean[] patchedStreamRead = new boolean[1];
        final boolean[] patchedSameType = new boolean[1];
        final ClassVisitor visitor = new ClassVisitor(ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                    final int access,
                    final String name,
                    final String desc,
                    final String signature,
                    final String[] exceptions) {
                if (GET_BUFFERED_IMAGE_NAME.equals(name) && GET_BUFFERED_IMAGE_DESC.equals(desc)) {
                    final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                    return new MethodVisitor(ASM9, mv) {
                        @Override
                        public void visitMethodInsn(
                                final int opcode,
                                final String owner,
                                final String mName,
                                final String mDesc,
                                final boolean isInterface) {
                            if (opcode == INVOKESTATIC
                                    && IMAGEIO_OWNER.equals(owner)
                                    && IMAGEIO_READ_NAME.equals(mName)
                                    && IMAGEIO_READ_STREAM_DESC.equals(mDesc)) {
                                patchedStreamRead[0] = true;
                                super.visitVarInsn(ALOAD, 0);
                                super.visitVarInsn(ILOAD, 1);
                                super.visitMethodInsn(
                                        INVOKESTATIC,
                                        BRIDGE_OWNER,
                                        READ_BUFFERED_IMAGE_NAME,
                                        READ_BUFFERED_IMAGE_DESC,
                                        false);
                                return;
                            }
                            super.visitMethodInsn(opcode, owner, mName, mDesc, isInterface);
                        }
                    };
                }
                if (CREATE_SAME_TYPE_NAME.equals(name) && CREATE_SAME_TYPE_DESC.equals(desc)) {
                    patchedSameType[0] = true;
                    emitFastCreateSameTypeWrapper(super.visitMethod(access, name, desc, signature, exceptions));
                    return super.visitMethod(
                            ACC_PRIVATE | ACC_STATIC, NATIVE_CREATE_SAME_TYPE_NAME, desc, signature, exceptions);
                }
                return super.visitMethod(access, name, desc, signature, exceptions);
            }
        };
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        if (!patchedStreamRead[0] || !patchedSameType[0]) {
            return null;
        }
        return writer.toByteArray();
    }

    private static void emitFastCreateSameTypeWrapper(final MethodVisitor mv) {
        final Label fallback = new Label();
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ILOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, BRIDGE_OWNER, TRY_CONVERT_NAME, TRY_CONVERT_DESC, false);
        mv.visitInsn(DUP);
        mv.visitJumpInsn(IFNULL, fallback);
        mv.visitInsn(ARETURN);

        mv.visitLabel(fallback);
        mv.visitInsn(org.objectweb.asm.Opcodes.POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ILOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, UT_IMAGE, NATIVE_CREATE_SAME_TYPE_NAME, CREATE_SAME_TYPE_DESC, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static byte[] patchImageResource(final ClassLoader loader, final byte[] classfileBuffer) {
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (ModelOpenClassWriters.hasDeclaredMethod(reader, ADD_LOADED_COUNT_NAME, INT_VOID_DESC)) {
            return null;
        }
        final ClassWriter writer = ModelOpenClassWriters.newFrameWriter(reader, loader);
        final int[] statsRewrites = new int[1];
        final ClassVisitor visitor = new ClassVisitor(ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                    final int access,
                    final String name,
                    final String desc,
                    final String signature,
                    final String[] exceptions) {
                final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                return new ImageResourceStatsMethodVisitor(mv, statsRewrites);
            }

            @Override
            public void visitEnd() {
                emitAtomicAddHelper(
                        super.visitMethod(
                                ACC_PRIVATE | ACC_STATIC | ACC_SYNCHRONIZED,
                                ADD_LOADED_COUNT_NAME,
                                INT_VOID_DESC,
                                null,
                                null),
                        FIELD_LOADED_COUNT);
                emitAtomicAddHelper(
                        super.visitMethod(
                                ACC_PRIVATE | ACC_STATIC | ACC_SYNCHRONIZED,
                                ADD_LOADED_BYTES_NAME,
                                INT_VOID_DESC,
                                null,
                                null),
                        FIELD_LOADED_BYTES);
                emitNeedsPredecodeHelper(super.visitMethod(
                        ACC_PUBLIC | ACC_FINAL | ACC_SYNCHRONIZED,
                        NEEDS_PREDECODE_NAME,
                        NEEDS_PREDECODE_DESC,
                        null,
                        null));
                super.visitEnd();
            }
        };
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        if (statsRewrites[0] != REQUIRED_STATS_REWRITE_SITES) {
            return null;
        }
        return writer.toByteArray();
    }

    private static void emitAtomicAddHelper(final MethodVisitor mv, final String fieldName) {
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, IMAGE_RESOURCE, fieldName, "I");
        mv.visitVarInsn(ILOAD, 0);
        mv.visitInsn(IADD);
        mv.visitFieldInsn(PUTSTATIC, IMAGE_RESOURCE, fieldName, "I");
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitNeedsPredecodeHelper(final MethodVisitor mv) {
        final Label no = new Label();
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, IMAGE_RESOURCE, FIELD_IS_DISPOSED, "Z");
        mv.visitJumpInsn(IFNE, no);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, IMAGE_RESOURCE, FIELD_IMAGE, "Lcom/live2d/graphics/CWritableImage;");
        mv.visitJumpInsn(IFNONNULL, no);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, IMAGE_RESOURCE, FIELD_IMAGE_FILE_BUF, "[B");
        mv.visitJumpInsn(IFNULL, no);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IRETURN);

        mv.visitLabel(no);
        mv.visitInsn(ICONST_0);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static final class ImageResourceStatsMethodVisitor extends MethodVisitor {

        private final int[] rewriteCount;
        private final ArrayDeque<Runnable> buffer = new ArrayDeque<>();
        private boolean capturingCount;
        private boolean capturingBytes;
        private int countStage;
        private int countTempSlot;
        private int countDeltaOpcode;
        private int bytesStage;
        private int bytesOpOpcode;

        private ImageResourceStatsMethodVisitor(final MethodVisitor delegate, final int[] rewriteCount) {
            super(ASM9, delegate);
            this.rewriteCount = rewriteCount;
        }

        @Override
        public void visitFieldInsn(final int opcode, final String owner, final String name, final String descriptor) {
            if (!this.capturingCount
                    && !this.capturingBytes
                    && opcode == GETSTATIC
                    && IMAGE_RESOURCE.equals(owner)
                    && FIELD_LOADED_COUNT.equals(name)
                    && "I".equals(descriptor)) {
                this.capturingCount = true;
                this.countStage = 1;
                this.buffer.addLast(() -> super.visitFieldInsn(opcode, owner, name, descriptor));
                return;
            }
            if (this.capturingCount
                    && this.countStage == 5
                    && opcode == PUTSTATIC
                    && IMAGE_RESOURCE.equals(owner)
                    && FIELD_LOADED_COUNT.equals(name)
                    && "I".equals(descriptor)) {
                super.visitInsn(this.countDeltaOpcode);
                super.visitMethodInsn(INVOKESTATIC, IMAGE_RESOURCE, ADD_LOADED_COUNT_NAME, INT_VOID_DESC, false);
                this.buffer.clear();
                this.capturingCount = false;
                this.rewriteCount[0]++;
                return;
            }
            if (!this.capturingCount
                    && !this.capturingBytes
                    && opcode == GETSTATIC
                    && IMAGE_RESOURCE.equals(owner)
                    && FIELD_LOADED_BYTES.equals(name)
                    && "I".equals(descriptor)) {
                this.capturingBytes = true;
                this.bytesStage = 1;
                this.buffer.addLast(() -> super.visitFieldInsn(opcode, owner, name, descriptor));
                return;
            }
            if (this.capturingBytes && opcode == GETFIELD && IMAGE_RESOURCE.equals(owner)) {
                if (this.bytesStage == 2 && FIELD_WIDTH.equals(name)) {
                    this.bytesStage = 3;
                    this.buffer.addLast(() -> super.visitFieldInsn(opcode, owner, name, descriptor));
                    return;
                }
                if (this.bytesStage == 4 && FIELD_HEIGHT.equals(name)) {
                    this.bytesStage = 5;
                    this.buffer.addLast(() -> super.visitFieldInsn(opcode, owner, name, descriptor));
                    return;
                }
            }
            if (this.capturingBytes
                    && this.bytesStage == 9
                    && opcode == PUTSTATIC
                    && IMAGE_RESOURCE.equals(owner)
                    && FIELD_LOADED_BYTES.equals(name)
                    && "I".equals(descriptor)) {
                super.visitVarInsn(ALOAD, 0);
                super.visitFieldInsn(org.objectweb.asm.Opcodes.GETFIELD, IMAGE_RESOURCE, FIELD_WIDTH, "I");
                super.visitVarInsn(ALOAD, 0);
                super.visitFieldInsn(org.objectweb.asm.Opcodes.GETFIELD, IMAGE_RESOURCE, FIELD_HEIGHT, "I");
                super.visitInsn(IMUL);
                super.visitInsn(ICONST_4);
                super.visitInsn(IMUL);
                if (this.bytesOpOpcode == ISUB) {
                    super.visitInsn(INEG);
                }
                super.visitMethodInsn(INVOKESTATIC, IMAGE_RESOURCE, ADD_LOADED_BYTES_NAME, INT_VOID_DESC, false);
                this.buffer.clear();
                this.capturingBytes = false;
                this.rewriteCount[0]++;
                return;
            }
            flushBuffer();
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public void visitVarInsn(final int opcode, final int varIndex) {
            if (this.capturingCount) {
                if (this.countStage == 1 && opcode == ISTORE) {
                    this.countStage = 2;
                    this.countTempSlot = varIndex;
                    this.buffer.addLast(() -> super.visitVarInsn(opcode, varIndex));
                    return;
                }
                if (this.countStage == 2 && opcode == ILOAD && varIndex == this.countTempSlot) {
                    this.countStage = 3;
                    this.buffer.addLast(() -> super.visitVarInsn(opcode, varIndex));
                    return;
                }
            } else if (this.capturingBytes && opcode == ALOAD && varIndex == 0) {
                if (this.bytesStage == 1) {
                    this.bytesStage = 2;
                    this.buffer.addLast(() -> super.visitVarInsn(opcode, varIndex));
                    return;
                }
                if (this.bytesStage == 3) {
                    this.bytesStage = 4;
                    this.buffer.addLast(() -> super.visitVarInsn(opcode, varIndex));
                    return;
                }
            }
            flushBuffer();
            super.visitVarInsn(opcode, varIndex);
        }

        @Override
        public void visitInsn(final int opcode) {
            if (this.capturingCount) {
                if (this.countStage == 3 && (opcode == ICONST_1 || opcode == ICONST_M1)) {
                    this.countStage = 4;
                    this.countDeltaOpcode = opcode;
                    this.buffer.addLast(() -> super.visitInsn(opcode));
                    return;
                }
                if (this.countStage == 4 && opcode == IADD) {
                    this.countStage = 5;
                    this.buffer.addLast(() -> super.visitInsn(opcode));
                    return;
                }
            } else if (this.capturingBytes) {
                if (this.bytesStage == 5 && opcode == IMUL) {
                    this.bytesStage = 6;
                    this.buffer.addLast(() -> super.visitInsn(opcode));
                    return;
                }
                if (this.bytesStage == 6 && opcode == ICONST_4) {
                    this.bytesStage = 7;
                    this.buffer.addLast(() -> super.visitInsn(opcode));
                    return;
                }
                if (this.bytesStage == 7 && opcode == IMUL) {
                    this.bytesStage = 8;
                    this.buffer.addLast(() -> super.visitInsn(opcode));
                    return;
                }
                if (this.bytesStage == 8 && (opcode == IADD || opcode == ISUB)) {
                    this.bytesStage = 9;
                    this.bytesOpOpcode = opcode;
                    this.buffer.addLast(() -> super.visitInsn(opcode));
                    return;
                }
            }
            flushBuffer();
            super.visitInsn(opcode);
        }

        @Override
        public void visitIntInsn(final int opcode, final int operand) {
            flushBuffer();
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitTypeInsn(final int opcode, final String type) {
            flushBuffer();
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitMethodInsn(
                final int opcode,
                final String owner,
                final String name,
                final String descriptor,
                final boolean isInterface) {
            flushBuffer();
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public void visitJumpInsn(final int opcode, final Label label) {
            flushBuffer();
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitLabel(final Label label) {
            flushBuffer();
            super.visitLabel(label);
        }

        @Override
        public void visitLdcInsn(final Object value) {
            flushBuffer();
            super.visitLdcInsn(value);
        }

        @Override
        public void visitIincInsn(final int varIndex, final int increment) {
            flushBuffer();
            super.visitIincInsn(varIndex, increment);
        }

        @Override
        public void visitEnd() {
            flushBuffer();
            super.visitEnd();
        }

        private void flushBuffer() {
            this.capturingCount = false;
            this.capturingBytes = false;
            while (!this.buffer.isEmpty()) {
                this.buffer.removeFirst().run();
            }
        }
    }
}
