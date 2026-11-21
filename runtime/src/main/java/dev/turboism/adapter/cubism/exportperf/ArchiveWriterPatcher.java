package dev.turboism.adapter.cubism.exportperf;

import static org.objectweb.asm.Opcodes.*;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

/** Rewrites {@code ArchiveWriter.getFreeFilePath(String)} to O(1) indexed lookup. */
final class ArchiveWriterPatcher {

    static final String TARGET = "com/live2d/serialize/archiver/ArchiveWriter";
    private static final String FILE_NAME_SET_FIELD = "fileNameSet";
    private static final String FILE_NAME_SET_DESC = "Ljava/util/HashSet;";
    private static final String GET_FREE_FILE_PATH = "getFreeFilePath";
    private static final String GET_FREE_FILE_PATH_DESC = "(Ljava/lang/String;)Ljava/lang/String;";
    private static final String HELPER_INTERNAL = Type.getInternalName(ArchiveWriterIndexCache.class);
    private static final String HELPER_METHOD = "getFreeFilePath";
    private static final String HELPER_DESC =
            "(Ljava/lang/Object;Ljava/util/HashSet;Ljava/lang/String;)Ljava/lang/String;";

    private ArchiveWriterPatcher() {}

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
        reader.accept(new PatchVisitor(writer), ClassReader.SKIP_FRAMES);
        return writer.toByteArray();
    }

    private static final class ShapeScan extends ClassVisitor {
        private boolean hasFileNameSet;
        private boolean hasTargetMethod;
        private boolean alreadyPatched;

        ShapeScan() {
            super(ASM9);
        }

        boolean matches() {
            return hasFileNameSet && hasTargetMethod;
        }

        @Override
        public FieldVisitor visitField(
                final int access, final String name, final String desc, final String sig, final Object value) {
            if (FILE_NAME_SET_FIELD.equals(name) && FILE_NAME_SET_DESC.equals(desc)) {
                hasFileNameSet = true;
            }
            return null;
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String sig, final String[] ex) {
            if (!GET_FREE_FILE_PATH.equals(name) || !GET_FREE_FILE_PATH_DESC.equals(desc)) {
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
            final MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
            if (!GET_FREE_FILE_PATH.equals(name) || !GET_FREE_FILE_PATH_DESC.equals(desc)) {
                return mv;
            }
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
    }
}
