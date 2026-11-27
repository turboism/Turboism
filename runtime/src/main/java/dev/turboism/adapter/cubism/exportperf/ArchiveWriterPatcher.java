package dev.turboism.adapter.cubism.exportperf;

import static org.objectweb.asm.Opcodes.*;

import dev.turboism.adapter.cubism.optimization.save.SavePerfClassWriters;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

/**
 * Rewrites {@code ArchiveWriter.getFreeFilePath(String)} to O(1) lookup, eliminates byte-array
 * resizing/copying in {@code addFileEntry_exe}, and adds {@code turboism$addEntryBytes}.
 */
final class ArchiveWriterPatcher {

    static final String TARGET = "com/live2d/serialize/archiver/ArchiveWriter";
    private static final String FILE_NAME_SET_FIELD = "fileNameSet";
    private static final String FILE_NAME_SET_DESC = "Ljava/util/HashSet;";
    private static final String HEADER_FIELD = "header";
    private static final String HEADER_DESC = "Lcom/live2d/serialize/archiver/ArchiveFormat$c;";
    private static final String FILE_MAP_FIELD = "filePathToFileInfoMap";
    private static final String FILE_MAP_DESC = "Ljava/util/LinkedHashMap;";

    private static final String GET_FREE_FILE_PATH = "getFreeFilePath";
    private static final String GET_FREE_FILE_PATH_DESC = "(Ljava/lang/String;)Ljava/lang/String;";
    private static final String ADD_FILE_ENTRY_EXE = "addFileEntry_exe";
    private static final String ADD_FILE_ENTRY_EXE_DESC =
            "(Ljava/lang/String;Ljava/lang/String;ZLcom/live2d/serialize/CompressOption;"
                    + "Lkotlin/jvm/functions/Function2;)Ljava/lang/String;";
    private static final String ADD_ENTRY_BYTES_METHOD = "turboism$addEntryBytes";
    private static final String ADD_ENTRY_BYTES_DESC =
            "(Ljava/lang/String;Ljava/lang/String;Lcom/live2d/serialize/CompressOption;[B)" + "Ljava/lang/String;";

    private static final String HELPER_INTERNAL = Type.getInternalName(ArchiveWriterIndexCache.class);
    private static final String HELPER_METHOD = "getFreeFilePath";
    private static final String HELPER_DESC =
            "(Ljava/lang/Object;Ljava/util/HashSet;Ljava/lang/String;)Ljava/lang/String;";
    private static final String SAVE_BRIDGE_INTERNAL =
            "dev/turboism/adapter/cubism/optimization/save/ArchiveWriterSaveBridge";
    private static final String NEW_ENTRY_STREAM_METHOD = "newEntryBufferStream";
    private static final String NEW_ENTRY_STREAM_DESC = "(Ljava/lang/Object;)Ljava/io/ByteArrayOutputStream;";

    private static final String BAOS_INTERNAL = "java/io/ByteArrayOutputStream";
    private static final String INIT_NAME = "<init>";
    private static final String VOID_DESC = "()V";
    private static final String ARCHIVE_INFO_INTERNAL = "com/live2d/serialize/archiver/ArchiveFormat$b";
    private static final String ARCHIVE_INFO_INIT_DESC =
            "(Ljava/lang/String;Ljava/lang/String;JIZLcom/live2d/serialize/CompressOption;)V";
    private static final String ARCHIVE_HEADER_INTERNAL = "com/live2d/serialize/archiver/ArchiveFormat$c";
    private static final String HEADER_OBFUSCATE_METHOD = "e";
    private static final String HEADER_OBFUSCATE_DESC = "()I";
    private static final String ARCHIVE_INFO_SET_BYTES_METHOD = "a";
    private static final String ARCHIVE_INFO_SET_BYTES_DESC = "([B)V";
    private static final String LINKED_HASH_MAP_INTERNAL = "java/util/LinkedHashMap";
    private static final String MAP_PUT_METHOD = "put";
    private static final String MAP_PUT_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String ILLEGAL_STATE_INTERNAL = "java/lang/IllegalStateException";
    private static final String ILLEGAL_STATE_INIT_DESC = "(Ljava/lang/String;)V";
    private static final String FREE_PATH_ERROR = "failed to get unused file path";
    private static final String EMPTY_TAG = "";
    private static final int COMPRESS_OPTION_SLOT = 4;

    private ArchiveWriterPatcher() {}

    static byte[] patch(final byte[] classBytes) {
        return patch(null, classBytes);
    }

    static byte[] patch(final ClassLoader loader, final byte[] classBytes) {
        if (classBytes == null) {
            return null;
        }
        final ClassReader reader = new ClassReader(classBytes);
        if (!TARGET.equals(reader.getClassName())) {
            return null;
        }
        final ShapeScan scan = new ShapeScan();
        reader.accept(scan, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        if (scan.alreadyPatched || !scan.matches()) {
            return null;
        }
        final ClassWriter writer = SavePerfClassWriters.newFrameWriter(reader, loader);
        reader.accept(new PatchVisitor(writer, scan.hasAddEntryBytes), ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static final class ShapeScan extends ClassVisitor {
        private boolean hasFileNameSet;
        private boolean hasHeader;
        private boolean hasFileMap;
        private boolean hasGetFreeFilePath;
        private boolean hasAddFileEntryBaos;
        private boolean hasFreePathHelper;
        private boolean hasSaveBridge;
        private boolean hasAddEntryBytes;
        private boolean alreadyPatched;

        ShapeScan() {
            super(ASM9);
        }

        boolean matches() {
            return hasFileNameSet && hasHeader && hasFileMap && hasGetFreeFilePath && hasAddFileEntryBaos;
        }

        @Override
        public FieldVisitor visitField(
                final int access, final String name, final String desc, final String sig, final Object value) {
            if (FILE_NAME_SET_FIELD.equals(name) && FILE_NAME_SET_DESC.equals(desc)) {
                hasFileNameSet = true;
            } else if (HEADER_FIELD.equals(name) && HEADER_DESC.equals(desc)) {
                hasHeader = true;
            } else if (FILE_MAP_FIELD.equals(name) && FILE_MAP_DESC.equals(desc)) {
                hasFileMap = true;
            }
            return null;
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String sig, final String[] ex) {
            if (ADD_ENTRY_BYTES_METHOD.equals(name) && ADD_ENTRY_BYTES_DESC.equals(desc)) {
                hasAddEntryBytes = true;
                return null;
            }
            if (GET_FREE_FILE_PATH.equals(name) && GET_FREE_FILE_PATH_DESC.equals(desc)) {
                hasGetFreeFilePath = true;
                return new MethodVisitor(ASM9) {
                    @Override
                    public void visitMethodInsn(
                            final int opcode,
                            final String owner,
                            final String mName,
                            final String mDesc,
                            final boolean itf) {
                        if (opcode == INVOKESTATIC && HELPER_INTERNAL.equals(owner) && HELPER_METHOD.equals(mName)) {
                            hasFreePathHelper = true;
                        }
                    }
                };
            }
            if (ADD_FILE_ENTRY_EXE.equals(name) && ADD_FILE_ENTRY_EXE_DESC.equals(desc)) {
                return new MethodVisitor(ASM9) {
                    @Override
                    public void visitTypeInsn(final int opcode, final String type) {
                        if (opcode == NEW && BAOS_INTERNAL.equals(type)) {
                            hasAddFileEntryBaos = true;
                        }
                    }

                    @Override
                    public void visitMethodInsn(
                            final int opcode,
                            final String owner,
                            final String mName,
                            final String mDesc,
                            final boolean itf) {
                        if (opcode == INVOKESTATIC && SAVE_BRIDGE_INTERNAL.equals(owner)) {
                            hasSaveBridge = true;
                        }
                    }
                };
            }
            return null;
        }

        @Override
        public void visitEnd() {
            this.alreadyPatched = this.hasFreePathHelper && this.hasSaveBridge && this.hasAddEntryBytes;
            super.visitEnd();
        }
    }

    private static final class PatchVisitor extends ClassVisitor {
        private final boolean skipAddEntryBytes;

        PatchVisitor(final ClassWriter writer, final boolean skipAddEntryBytes) {
            super(ASM9, writer);
            this.skipAddEntryBytes = skipAddEntryBytes;
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String sig, final String[] ex) {
            final MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
            if (GET_FREE_FILE_PATH.equals(name) && GET_FREE_FILE_PATH_DESC.equals(desc)) {
                mv.visitCode();
                mv.visitVarInsn(ALOAD, 0);
                mv.visitVarInsn(ALOAD, 0);
                mv.visitFieldInsn(GETFIELD, TARGET, FILE_NAME_SET_FIELD, FILE_NAME_SET_DESC);
                mv.visitVarInsn(ALOAD, 1);
                mv.visitMethodInsn(INVOKESTATIC, HELPER_INTERNAL, HELPER_METHOD, HELPER_DESC, false);
                mv.visitInsn(ARETURN);
                mv.visitMaxs(3, 2);
                mv.visitEnd();
                return null;
            }
            if (ADD_FILE_ENTRY_EXE.equals(name) && ADD_FILE_ENTRY_EXE_DESC.equals(desc)) {
                return new AddFileEntryMethodVisitor(mv);
            }
            return mv;
        }

        @Override
        public void visitEnd() {
            if (!this.skipAddEntryBytes) {
                emitAddEntryBytesMethod(super.visitMethod(
                        ACC_PUBLIC | ACC_FINAL | ACC_SYNTHETIC,
                        ADD_ENTRY_BYTES_METHOD,
                        ADD_ENTRY_BYTES_DESC,
                        null,
                        null));
            }
            super.visitEnd();
        }

        private static void emitAddEntryBytesMethod(final MethodVisitor mv) {
            final Label freePathOk = new Label();
            final Label nonNullTag = new Label();
            final Label obfuscateFalse = new Label();
            final Label obfuscateReady = new Label();

            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESPECIAL, TARGET, GET_FREE_FILE_PATH, GET_FREE_FILE_PATH_DESC, false);
            mv.visitInsn(DUP);
            mv.visitJumpInsn(IFNONNULL, freePathOk);
            mv.visitInsn(POP);
            mv.visitTypeInsn(NEW, ILLEGAL_STATE_INTERNAL);
            mv.visitInsn(DUP);
            mv.visitLdcInsn(FREE_PATH_ERROR);
            mv.visitMethodInsn(INVOKESPECIAL, ILLEGAL_STATE_INTERNAL, INIT_NAME, ILLEGAL_STATE_INIT_DESC, false);
            mv.visitInsn(ATHROW);

            mv.visitLabel(freePathOk);
            mv.visitVarInsn(ASTORE, 5);

            mv.visitVarInsn(ALOAD, 2);
            mv.visitJumpInsn(IFNONNULL, nonNullTag);
            mv.visitLdcInsn(EMPTY_TAG);
            mv.visitVarInsn(ASTORE, 2);
            mv.visitLabel(nonNullTag);

            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, TARGET, HEADER_FIELD, HEADER_DESC);
            mv.visitMethodInsn(
                    INVOKEVIRTUAL, ARCHIVE_HEADER_INTERNAL, HEADER_OBFUSCATE_METHOD, HEADER_OBFUSCATE_DESC, false);
            mv.visitJumpInsn(IFEQ, obfuscateFalse);
            mv.visitInsn(ICONST_1);
            mv.visitJumpInsn(GOTO, obfuscateReady);
            mv.visitLabel(obfuscateFalse);
            mv.visitInsn(ICONST_0);
            mv.visitLabel(obfuscateReady);
            mv.visitVarInsn(ISTORE, 6);

            mv.visitTypeInsn(NEW, ARCHIVE_INFO_INTERNAL);
            mv.visitInsn(DUP);
            mv.visitVarInsn(ALOAD, 5);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitLdcInsn(-1L);
            mv.visitVarInsn(ALOAD, 4);
            mv.visitInsn(ARRAYLENGTH);
            mv.visitVarInsn(ILOAD, 6);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitMethodInsn(INVOKESPECIAL, ARCHIVE_INFO_INTERNAL, INIT_NAME, ARCHIVE_INFO_INIT_DESC, false);
            mv.visitVarInsn(ASTORE, 7);

            mv.visitVarInsn(ALOAD, 7);
            mv.visitVarInsn(ALOAD, 4);
            mv.visitMethodInsn(
                    INVOKEVIRTUAL,
                    ARCHIVE_INFO_INTERNAL,
                    ARCHIVE_INFO_SET_BYTES_METHOD,
                    ARCHIVE_INFO_SET_BYTES_DESC,
                    false);

            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, TARGET, FILE_MAP_FIELD, FILE_MAP_DESC);
            mv.visitVarInsn(ALOAD, 5);
            mv.visitVarInsn(ALOAD, 7);
            mv.visitMethodInsn(INVOKEVIRTUAL, LINKED_HASH_MAP_INTERNAL, MAP_PUT_METHOD, MAP_PUT_DESC, false);
            mv.visitInsn(POP);

            mv.visitVarInsn(ALOAD, 5);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
    }

    private static final class AddFileEntryMethodVisitor extends MethodVisitor {
        private boolean pendingNewBaos;
        private boolean pendingDup;

        AddFileEntryMethodVisitor(final MethodVisitor mv) {
            super(ASM9, mv);
        }

        @Override
        public void visitTypeInsn(final int opcode, final String type) {
            flushPending();
            if (opcode == NEW && BAOS_INTERNAL.equals(type)) {
                this.pendingNewBaos = true;
                return;
            }
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitInsn(final int opcode) {
            if (this.pendingNewBaos && !this.pendingDup && opcode == DUP) {
                this.pendingDup = true;
                return;
            }
            flushPending();
            super.visitInsn(opcode);
        }

        @Override
        public void visitMethodInsn(
                final int opcode, final String owner, final String name, final String desc, final boolean itf) {
            if (this.pendingNewBaos
                    && this.pendingDup
                    && opcode == INVOKESPECIAL
                    && BAOS_INTERNAL.equals(owner)
                    && INIT_NAME.equals(name)
                    && VOID_DESC.equals(desc)) {
                this.pendingNewBaos = false;
                this.pendingDup = false;
                super.visitVarInsn(ALOAD, COMPRESS_OPTION_SLOT);
                super.visitMethodInsn(
                        INVOKESTATIC, SAVE_BRIDGE_INTERNAL, NEW_ENTRY_STREAM_METHOD, NEW_ENTRY_STREAM_DESC, false);
                return;
            }
            flushPending();
            super.visitMethodInsn(opcode, owner, name, desc, itf);
        }

        @Override
        public void visitVarInsn(final int opcode, final int varIndex) {
            flushPending();
            super.visitVarInsn(opcode, varIndex);
        }

        @Override
        public void visitFieldInsn(final int opcode, final String owner, final String name, final String desc) {
            flushPending();
            super.visitFieldInsn(opcode, owner, name, desc);
        }

        @Override
        public void visitIntInsn(final int opcode, final int operand) {
            flushPending();
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitLdcInsn(final Object value) {
            flushPending();
            super.visitLdcInsn(value);
        }

        @Override
        public void visitJumpInsn(final int opcode, final Label label) {
            flushPending();
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitLabel(final Label label) {
            flushPending();
            super.visitLabel(label);
        }

        @Override
        public void visitEnd() {
            flushPending();
            super.visitEnd();
        }

        private void flushPending() {
            if (this.pendingNewBaos) {
                super.visitTypeInsn(NEW, BAOS_INTERNAL);
                this.pendingNewBaos = false;
            }
            if (this.pendingDup) {
                super.visitInsn(DUP);
                this.pendingDup = false;
            }
        }
    }
}
