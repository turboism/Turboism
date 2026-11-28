package dev.turboism.adapter.cubism.optimization.modelopen;

import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.ASTORE;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.NEW;
import static org.objectweb.asm.Opcodes.POP;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

/**
 * Eliminates {@code ByteArrayOutputStream} buffer-doubling churn across {@code ArchiveReader},
 * {@code com/live2d/graphics/e}, and {@code com/live2d/serialize/b}.
 */
final class ArchiveReadPatcher {

    static final String ARCHIVE_READER = "com/live2d/serialize/archiver/ArchiveReader";
    static final String GRAPHICS_READ_LAMBDA = "com/live2d/graphics/e";
    static final String SERIALIZE_READ_LAMBDA = "com/live2d/serialize/b";

    private static final String READ_FILE_BY_PATH_NAME = "readFileByFilePath";
    private static final String READ_FILE_BY_PATH_DESC = "(Ljava/lang/String;Lkotlin/jvm/functions/Function1;)V";
    private static final String LAMBDA_INVOKE_NAME = "a";
    private static final String LAMBDA_INVOKE_DESC = "(Ljava/io/InputStream;)V";

    private static final String BYTE_ARRAY_INPUT_STREAM = "java/io/ByteArrayInputStream";
    private static final String INPUT_STREAM = "java/io/InputStream";
    private static final String ZIP_INPUT_STREAM = "java/util/zip/ZipInputStream";
    private static final String GET_NEXT_ENTRY = "getNextEntry";
    private static final String GET_NEXT_ENTRY_DESC = "()Ljava/util/zip/ZipEntry;";
    private static final String KOTLIN_BYTE_STREAMS = "kotlin/io/ByteStreamsKt";
    private static final String READ_BYTES = "readBytes";
    private static final String READ_BYTES_DESC = "(Ljava/io/InputStream;)[B";

    private static final String BRIDGE_OWNER = "dev/turboism/adapter/cubism/optimization/modelopen/ArchiveReadBridge";
    private static final String WRAP_RAW_DESC = "([B)Ljava/io/InputStream;";
    private static final String WRAP_ZIP_DESC =
            "(Ljava/util/zip/ZipInputStream;Ljava/util/zip/ZipEntry;)Ljava/io/InputStream;";
    private static final String READ_ALL_BYTES_DESC = "(Ljava/io/InputStream;)[B";
    private static final int MIN_PARAMS_LOCALS = 3;

    private ArchiveReadPatcher() {}

    static byte[] tryPatch(final String internalName, final ClassLoader loader, final byte[] classfileBuffer) {
        if (ARCHIVE_READER.equals(internalName)) {
            return patchArchiveReader(loader, classfileBuffer);
        }
        if (GRAPHICS_READ_LAMBDA.equals(internalName) || SERIALIZE_READ_LAMBDA.equals(internalName)) {
            return patchReadLambda(loader, classfileBuffer);
        }
        return null;
    }

    private static byte[] patchArchiveReader(final ClassLoader loader, final byte[] classfileBuffer) {
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (ModelOpenClassWriters.callsOwner(reader, BRIDGE_OWNER)) {
            return null;
        }
        final int entryLocal = computeFreeLocalSlot(reader);
        final ClassWriter writer = ModelOpenClassWriters.newFrameWriter(reader, loader);
        final boolean[] patched = new boolean[1];
        final ClassVisitor visitor = new ClassVisitor(ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                    final int access,
                    final String name,
                    final String desc,
                    final String signature,
                    final String[] exceptions) {
                final MethodVisitor delegate = super.visitMethod(access, name, desc, signature, exceptions);
                if (!READ_FILE_BY_PATH_NAME.equals(name) || !READ_FILE_BY_PATH_DESC.equals(desc)) {
                    return delegate;
                }
                return new ArchiveReaderMethodVisitor(delegate, entryLocal, patched);
            }
        };
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        return patched[0] ? writer.toByteArray() : null;
    }

    private static int computeFreeLocalSlot(final ClassReader reader) {
        final int[] maxSlot = {MIN_PARAMS_LOCALS};
        reader.accept(
                new ClassVisitor(ASM9) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final String[] exceptions) {
                        if (!READ_FILE_BY_PATH_NAME.equals(name) || !READ_FILE_BY_PATH_DESC.equals(desc)) {
                            return null;
                        }
                        return new MethodVisitor(ASM9) {
                            @Override
                            public void visitMaxs(final int maxStack, final int maxLocals) {
                                maxSlot[0] = Math.max(maxSlot[0], maxLocals);
                            }
                        };
                    }
                },
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return maxSlot[0] + 1;
    }

    private static byte[] patchReadLambda(final ClassLoader loader, final byte[] classfileBuffer) {
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (ModelOpenClassWriters.callsOwner(reader, BRIDGE_OWNER)) {
            return null;
        }
        final ClassWriter writer = ModelOpenClassWriters.newFrameWriter(reader, loader);
        final boolean[] patched = new boolean[1];
        final ClassVisitor visitor = new ClassVisitor(ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                    final int access,
                    final String name,
                    final String desc,
                    final String signature,
                    final String[] exceptions) {
                final MethodVisitor delegate = super.visitMethod(access, name, desc, signature, exceptions);
                if (!LAMBDA_INVOKE_NAME.equals(name) || !LAMBDA_INVOKE_DESC.equals(desc)) {
                    return delegate;
                }
                return new MethodVisitor(ASM9, delegate) {
                    @Override
                    public void visitMethodInsn(
                            final int opcode,
                            final String owner,
                            final String mName,
                            final String mDesc,
                            final boolean isInterface) {
                        if (opcode == INVOKESTATIC
                                && KOTLIN_BYTE_STREAMS.equals(owner)
                                && READ_BYTES.equals(mName)
                                && READ_BYTES_DESC.equals(mDesc)) {
                            patched[0] = true;
                            super.visitMethodInsn(
                                    INVOKESTATIC, BRIDGE_OWNER, "readAllBytes", READ_ALL_BYTES_DESC, false);
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, mName, mDesc, isInterface);
                    }
                };
            }
        };
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        return patched[0] ? writer.toByteArray() : null;
    }

    private static final class ArchiveReaderMethodVisitor extends MethodVisitor {

        private final int entryLocal;
        private final boolean[] patched;
        private boolean sawZipGetNextEntry;
        private boolean pendingWrapZipOnCast;
        private int pendingRawBaisState;

        private ArchiveReaderMethodVisitor(
                final MethodVisitor delegate, final int entryLocal, final boolean[] patched) {
            super(ASM9, delegate);
            this.entryLocal = entryLocal;
            this.patched = patched;
        }

        @Override
        public void visitTypeInsn(final int opcode, final String type) {
            if (this.pendingWrapZipOnCast
                    && opcode == org.objectweb.asm.Opcodes.CHECKCAST
                    && INPUT_STREAM.equals(type)) {
                this.pendingWrapZipOnCast = false;
                this.patched[0] = true;
                super.visitVarInsn(ALOAD, this.entryLocal);
                super.visitMethodInsn(INVOKESTATIC, BRIDGE_OWNER, "wrapZipEntry", WRAP_ZIP_DESC, false);
                return;
            }
            flushPending();
            if (opcode == NEW && BYTE_ARRAY_INPUT_STREAM.equals(type)) {
                this.pendingRawBaisState = 1;
                return;
            }
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitInsn(final int opcode) {
            if (this.pendingRawBaisState == 1 && opcode == DUP) {
                this.pendingRawBaisState = 2;
                return;
            }
            if (this.sawZipGetNextEntry && opcode == POP) {
                this.sawZipGetNextEntry = false;
                this.pendingWrapZipOnCast = true;
                super.visitVarInsn(ASTORE, this.entryLocal);
                return;
            }
            flushPending();
            super.visitInsn(opcode);
        }

        @Override
        public void visitVarInsn(final int opcode, final int varIndex) {
            if (this.pendingRawBaisState == 2 && opcode == ALOAD) {
                super.visitVarInsn(opcode, varIndex);
                this.pendingRawBaisState = 3;
                return;
            }
            flushPending();
            super.visitVarInsn(opcode, varIndex);
        }

        @Override
        public void visitMethodInsn(
                final int opcode, final String owner, final String name, final String desc, final boolean isInterface) {
            if (this.pendingRawBaisState == 3
                    && opcode == INVOKESPECIAL
                    && BYTE_ARRAY_INPUT_STREAM.equals(owner)
                    && "<init>".equals(name)
                    && "([B)V".equals(desc)) {
                this.pendingRawBaisState = 0;
                this.patched[0] = true;
                super.visitMethodInsn(INVOKESTATIC, BRIDGE_OWNER, "wrapRawBytes", WRAP_RAW_DESC, false);
                return;
            }
            flushPending();
            super.visitMethodInsn(opcode, owner, name, desc, isInterface);
            if (opcode == INVOKEVIRTUAL
                    && ZIP_INPUT_STREAM.equals(owner)
                    && GET_NEXT_ENTRY.equals(name)
                    && GET_NEXT_ENTRY_DESC.equals(desc)) {
                this.sawZipGetNextEntry = true;
            }
        }

        private void flushPending() {
            if (this.pendingRawBaisState >= 1) {
                super.visitTypeInsn(NEW, BYTE_ARRAY_INPUT_STREAM);
            }
            if (this.pendingRawBaisState >= 2) {
                super.visitInsn(DUP);
            }
            this.pendingRawBaisState = 0;
            this.sawZipGetNextEntry = false;
        }
    }
}
