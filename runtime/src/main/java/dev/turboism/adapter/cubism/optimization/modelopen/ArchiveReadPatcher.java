package dev.turboism.adapter.cubism.optimization.modelopen;

import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.ASTORE;
import static org.objectweb.asm.Opcodes.CHECKCAST;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.ICONST_0;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.ISTORE;
import static org.objectweb.asm.Opcodes.NEW;
import static org.objectweb.asm.Opcodes.NOP;
import static org.objectweb.asm.Opcodes.POP;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
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
    private static final String INIT_NAME = "<init>";
    private static final String BAIS_INIT_DESC = "([B)V";
    private static final String ZIS_INIT_DESC = "(Ljava/io/InputStream;)V";
    private static final String GET_NEXT_ENTRY = "getNextEntry";
    private static final String GET_NEXT_ENTRY_DESC = "()Ljava/util/zip/ZipEntry;";
    private static final String KOTLIN_BYTE_STREAMS = "kotlin/io/ByteStreamsKt";
    private static final String READ_BYTES = "readBytes";
    private static final String READ_BYTES_DESC = "(Ljava/io/InputStream;)[B";

    private static final String BRIDGE_OWNER = "dev/turboism/adapter/cubism/optimization/modelopen/ArchiveReadBridge";
    private static final String WRAP_RAW_BYTES = "wrapRawBytes";
    private static final String OPEN_ZIP_ENTRY_STREAM = "openZipEntryStream";
    private static final String READ_ALL_BYTES = "readAllBytes";
    private static final String BYTES_TO_STREAM_DESC = "([B)Ljava/io/InputStream;";
    private static final String READ_ALL_BYTES_DESC = "(Ljava/io/InputStream;)[B";

    private static final int RAW_IDLE = 0;
    private static final int RAW_SEEN_NEW = 1;
    private static final int RAW_SEEN_DUP = 2;
    private static final int RAW_SEEN_ALOAD = 3;

    private static final int ZIP_IDLE = 0;
    private static final int ZIP_SEEN_NEW_ZIS = 1;
    private static final int ZIP_SEEN_DUP_ZIS = 2;
    private static final int ZIP_SEEN_NEW_BAIS = 3;
    private static final int ZIP_SEEN_DUP_BAIS = 4;
    private static final int ZIP_SEEN_ALOAD_BYTES = 5;
    private static final int ZIP_SEEN_BAIS_INIT = 6;
    private static final int ZIP_SEEN_BAIS_CAST = 7;
    private static final int ZIP_SEEN_ZIS_INIT = 8;
    private static final int ZIP_SEEN_ASTORE_ZIP = 9;
    private static final int ZIP_SEEN_ALOAD_ZIP_FOR_APPLY = 10;
    private static final int ZIP_SEEN_ASTORE_APPLY = 11;
    private static final int ZIP_SEEN_ICONST_0 = 12;
    private static final int ZIP_SEEN_ISTORE_FLAG = 13;
    private static final int ZIP_SEEN_ALOAD_APPLY = 14;
    private static final int ZIP_SEEN_GET_NEXT_ENTRY = 15;
    private static final int ZIP_SEEN_POP = 16;
    private static final int ZIP_SEEN_ALOAD_ZIP_RESULT = 17;

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
                return new ArchiveReaderMethodVisitor(delegate, patched);
            }
        };
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        return patched[0] ? writer.toByteArray() : null;
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
                                    INVOKESTATIC, BRIDGE_OWNER, READ_ALL_BYTES, READ_ALL_BYTES_DESC, false);
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

        private final boolean[] patched;
        private final List<Runnable> zipReplay = new ArrayList<>();
        private int zipState = ZIP_IDLE;
        private int zipBytesVar = -1;
        private int zipLocalVar = -1;
        private int zipApplyVar = -1;

        private int rawState = RAW_IDLE;
        private int rawBytesVar = -1;

        private ArchiveReaderMethodVisitor(final MethodVisitor delegate, final boolean[] patched) {
            super(ASM9, delegate);
            this.patched = patched;
        }

        @Override
        public void visitTypeInsn(final int opcode, final String type) {
            if (this.zipState == ZIP_IDLE && opcode == NEW && ZIP_INPUT_STREAM.equals(type)) {
                flushRawPending();
                this.zipState = ZIP_SEEN_NEW_ZIS;
                this.zipReplay.add(() -> super.visitTypeInsn(opcode, type));
                return;
            }
            if (this.zipState == ZIP_SEEN_DUP_ZIS && opcode == NEW && BYTE_ARRAY_INPUT_STREAM.equals(type)) {
                this.zipState = ZIP_SEEN_NEW_BAIS;
                this.zipReplay.add(() -> super.visitTypeInsn(opcode, type));
                return;
            }
            if (this.zipState == ZIP_SEEN_BAIS_INIT && opcode == CHECKCAST && INPUT_STREAM.equals(type)) {
                this.zipState = ZIP_SEEN_BAIS_CAST;
                this.zipReplay.add(() -> super.visitTypeInsn(opcode, type));
                return;
            }
            if (this.zipState == ZIP_SEEN_ALOAD_ZIP_RESULT && opcode == CHECKCAST && INPUT_STREAM.equals(type)) {
                final int bytesSlot = this.zipBytesVar;
                resetZipMatch();
                this.patched[0] = true;
                super.visitVarInsn(ALOAD, bytesSlot);
                super.visitMethodInsn(INVOKESTATIC, BRIDGE_OWNER, OPEN_ZIP_ENTRY_STREAM, BYTES_TO_STREAM_DESC, false);
                return;
            }
            flushAllPending();
            if (opcode == NEW && BYTE_ARRAY_INPUT_STREAM.equals(type)) {
                this.rawState = RAW_SEEN_NEW;
                return;
            }
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitInsn(final int opcode) {
            if (this.zipState == ZIP_SEEN_NEW_ZIS && opcode == DUP) {
                this.zipState = ZIP_SEEN_DUP_ZIS;
                this.zipReplay.add(() -> super.visitInsn(opcode));
                return;
            }
            if (this.zipState == ZIP_SEEN_NEW_BAIS && opcode == DUP) {
                this.zipState = ZIP_SEEN_DUP_BAIS;
                this.zipReplay.add(() -> super.visitInsn(opcode));
                return;
            }
            if (this.zipState == ZIP_SEEN_ASTORE_APPLY && opcode == ICONST_0) {
                this.zipState = ZIP_SEEN_ICONST_0;
                this.zipReplay.add(() -> super.visitInsn(opcode));
                return;
            }
            if (this.zipState == ZIP_SEEN_GET_NEXT_ENTRY && opcode == POP) {
                this.zipState = ZIP_SEEN_POP;
                this.zipReplay.add(() -> super.visitInsn(opcode));
                return;
            }
            if (this.zipState == ZIP_SEEN_POP && opcode == NOP) {
                this.zipReplay.add(() -> super.visitInsn(opcode));
                return;
            }
            if (this.zipState == ZIP_IDLE && this.rawState == RAW_SEEN_NEW && opcode == DUP) {
                this.rawState = RAW_SEEN_DUP;
                return;
            }
            flushAllPending();
            super.visitInsn(opcode);
        }

        @Override
        public void visitVarInsn(final int opcode, final int varIndex) {
            if (this.zipState == ZIP_SEEN_DUP_BAIS && opcode == ALOAD) {
                this.zipBytesVar = varIndex;
                this.zipState = ZIP_SEEN_ALOAD_BYTES;
                this.zipReplay.add(() -> super.visitVarInsn(opcode, varIndex));
                return;
            }
            if (this.zipState == ZIP_SEEN_ZIS_INIT && opcode == ASTORE) {
                this.zipLocalVar = varIndex;
                this.zipState = ZIP_SEEN_ASTORE_ZIP;
                this.zipReplay.add(() -> super.visitVarInsn(opcode, varIndex));
                return;
            }
            if (this.zipState == ZIP_SEEN_ASTORE_ZIP && opcode == ALOAD && varIndex == this.zipLocalVar) {
                this.zipState = ZIP_SEEN_ALOAD_ZIP_FOR_APPLY;
                this.zipReplay.add(() -> super.visitVarInsn(opcode, varIndex));
                return;
            }
            if (this.zipState == ZIP_SEEN_ALOAD_ZIP_FOR_APPLY && opcode == ASTORE) {
                this.zipApplyVar = varIndex;
                this.zipState = ZIP_SEEN_ASTORE_APPLY;
                this.zipReplay.add(() -> super.visitVarInsn(opcode, varIndex));
                return;
            }
            if (this.zipState == ZIP_SEEN_ICONST_0 && opcode == ISTORE) {
                this.zipState = ZIP_SEEN_ISTORE_FLAG;
                this.zipReplay.add(() -> super.visitVarInsn(opcode, varIndex));
                return;
            }
            if (this.zipState == ZIP_SEEN_ISTORE_FLAG && opcode == ALOAD && varIndex == this.zipApplyVar) {
                this.zipState = ZIP_SEEN_ALOAD_APPLY;
                this.zipReplay.add(() -> super.visitVarInsn(opcode, varIndex));
                return;
            }
            if (this.zipState == ZIP_SEEN_POP && opcode == ALOAD && varIndex == this.zipLocalVar) {
                this.zipState = ZIP_SEEN_ALOAD_ZIP_RESULT;
                this.zipReplay.add(() -> super.visitVarInsn(opcode, varIndex));
                return;
            }
            if (this.zipState == ZIP_IDLE && this.rawState == RAW_SEEN_DUP && opcode == ALOAD) {
                this.rawBytesVar = varIndex;
                this.rawState = RAW_SEEN_ALOAD;
                return;
            }
            flushAllPending();
            super.visitVarInsn(opcode, varIndex);
        }

        @Override
        public void visitMethodInsn(
                final int opcode, final String owner, final String name, final String desc, final boolean isInterface) {
            if (this.zipState == ZIP_SEEN_ALOAD_BYTES
                    && opcode == INVOKESPECIAL
                    && BYTE_ARRAY_INPUT_STREAM.equals(owner)
                    && INIT_NAME.equals(name)
                    && BAIS_INIT_DESC.equals(desc)) {
                this.zipState = ZIP_SEEN_BAIS_INIT;
                this.zipReplay.add(() -> super.visitMethodInsn(opcode, owner, name, desc, isInterface));
                return;
            }
            if ((this.zipState == ZIP_SEEN_BAIS_CAST || this.zipState == ZIP_SEEN_BAIS_INIT)
                    && opcode == INVOKESPECIAL
                    && ZIP_INPUT_STREAM.equals(owner)
                    && INIT_NAME.equals(name)
                    && ZIS_INIT_DESC.equals(desc)) {
                this.zipState = ZIP_SEEN_ZIS_INIT;
                this.zipReplay.add(() -> super.visitMethodInsn(opcode, owner, name, desc, isInterface));
                return;
            }
            if (this.zipState == ZIP_SEEN_ALOAD_APPLY
                    && opcode == INVOKEVIRTUAL
                    && ZIP_INPUT_STREAM.equals(owner)
                    && GET_NEXT_ENTRY.equals(name)
                    && GET_NEXT_ENTRY_DESC.equals(desc)) {
                this.zipState = ZIP_SEEN_GET_NEXT_ENTRY;
                this.zipReplay.add(() -> super.visitMethodInsn(opcode, owner, name, desc, isInterface));
                return;
            }
            if (this.zipState == ZIP_IDLE
                    && this.rawState == RAW_SEEN_ALOAD
                    && opcode == INVOKESPECIAL
                    && BYTE_ARRAY_INPUT_STREAM.equals(owner)
                    && INIT_NAME.equals(name)
                    && BAIS_INIT_DESC.equals(desc)) {
                final int bytesSlot = this.rawBytesVar;
                this.rawState = RAW_IDLE;
                this.rawBytesVar = -1;
                this.patched[0] = true;
                super.visitVarInsn(ALOAD, bytesSlot);
                super.visitMethodInsn(INVOKESTATIC, BRIDGE_OWNER, WRAP_RAW_BYTES, BYTES_TO_STREAM_DESC, false);
                return;
            }
            flushAllPending();
            super.visitMethodInsn(opcode, owner, name, desc, isInterface);
        }

        @Override
        public void visitIntInsn(final int opcode, final int operand) {
            flushAllPending();
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitFieldInsn(final int opcode, final String owner, final String name, final String descriptor) {
            flushAllPending();
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public void visitInvokeDynamicInsn(
                final String name,
                final String descriptor,
                final Handle bootstrapMethodHandle,
                final Object... bootstrapMethodArguments) {
            flushAllPending();
            super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
        }

        @Override
        public void visitJumpInsn(final int opcode, final Label label) {
            flushAllPending();
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitLabel(final Label label) {
            flushAllPending();
            super.visitLabel(label);
        }

        @Override
        public void visitLdcInsn(final Object value) {
            flushAllPending();
            super.visitLdcInsn(value);
        }

        @Override
        public void visitIincInsn(final int varIndex, final int increment) {
            flushAllPending();
            super.visitIincInsn(varIndex, increment);
        }

        @Override
        public void visitTableSwitchInsn(final int min, final int max, final Label dflt, final Label... labels) {
            flushAllPending();
            super.visitTableSwitchInsn(min, max, dflt, labels);
        }

        @Override
        public void visitLookupSwitchInsn(final Label dflt, final int[] keys, final Label[] labels) {
            flushAllPending();
            super.visitLookupSwitchInsn(dflt, keys, labels);
        }

        @Override
        public void visitMultiANewArrayInsn(final String descriptor, final int numDimensions) {
            flushAllPending();
            super.visitMultiANewArrayInsn(descriptor, numDimensions);
        }

        @Override
        public void visitMaxs(final int maxStack, final int maxLocals) {
            flushAllPending();
            super.visitMaxs(maxStack, maxLocals);
        }

        @Override
        public void visitEnd() {
            flushAllPending();
            super.visitEnd();
        }

        private void flushAllPending() {
            flushZipPending();
            flushRawPending();
        }

        private void flushZipPending() {
            if (this.zipState == ZIP_IDLE) {
                return;
            }
            for (final Runnable action : this.zipReplay) {
                action.run();
            }
            resetZipMatch();
        }

        private void resetZipMatch() {
            this.zipReplay.clear();
            this.zipState = ZIP_IDLE;
            this.zipBytesVar = -1;
            this.zipLocalVar = -1;
            this.zipApplyVar = -1;
        }

        private void flushRawPending() {
            if (this.rawState >= RAW_SEEN_NEW) {
                super.visitTypeInsn(NEW, BYTE_ARRAY_INPUT_STREAM);
            }
            if (this.rawState >= RAW_SEEN_DUP) {
                super.visitInsn(DUP);
            }
            if (this.rawState >= RAW_SEEN_ALOAD) {
                super.visitVarInsn(ALOAD, this.rawBytesVar);
            }
            this.rawState = RAW_IDLE;
            this.rawBytesVar = -1;
        }
    }
}
