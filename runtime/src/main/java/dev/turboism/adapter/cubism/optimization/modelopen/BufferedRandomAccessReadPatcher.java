package dev.turboism.adapter.cubism.optimization.modelopen;

import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_SYNTHETIC;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.GETFIELD;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.LLOAD;
import static org.objectweb.asm.Opcodes.LRETURN;
import static org.objectweb.asm.Opcodes.NEW;
import static org.objectweb.asm.Opcodes.PUTFIELD;
import static org.objectweb.asm.Opcodes.RETURN;

import java.util.HashSet;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;

/**
 * Patches {@code com/live2d/serialize/archiver/RandomAccess$c} to route archive reads through
 * {@link BufferedRandomAccessReader} across Cubism {@code 5.0.00} through {@code 5.3.05}.
 */
final class BufferedRandomAccessReadPatcher {

    static final String TARGET = "com/live2d/serialize/archiver/RandomAccess$c";

    private static final String SUPER_NAME = "com/live2d/serialize/archiver/RandomAccess$e";
    private static final String READER_INTERNAL =
            "dev/turboism/adapter/cubism/optimization/modelopen/BufferedRandomAccessReader";
    private static final String READER_DESC = "L" + READER_INTERNAL + ";";
    private static final String BUFFERED_FIELD = "turboism$buffered";
    private static final String RAF_FIELD = "b";
    private static final String RAF_DESC = "Ljava/io/RandomAccessFile;";
    private static final String INIT_NAME = "<init>";
    private static final String INIT_FILE_DESC = "(Ljava/io/File;)V";
    private static final String READER_INIT_DESC = "(Ljava/io/RandomAccessFile;)V";

    private static final String METHOD_GET_RAF = "b";
    private static final String DESC_GET_RAF = "()Ljava/io/RandomAccessFile;";
    private static final String METHOD_CLOSE = "a";
    private static final String DESC_VOID = "()V";
    private static final String METHOD_SEEK = "a";
    private static final String DESC_SEEK = "(J)V";
    private static final String METHOD_SKIP = "a";
    private static final String DESC_SKIP = "(I)V";
    private static final String METHOD_READ_BYTE = "b";
    private static final String DESC_READ_BYTE = "(I)B";
    private static final String METHOD_READ_SHORT = "c";
    private static final String DESC_READ_SHORT = "(I)S";
    private static final String METHOD_READ_INT = "d";
    private static final String DESC_READ_INT = "(I)I";
    private static final String METHOD_READ_LONG = "e";
    private static final String DESC_READ_LONG = "(I)J";
    private static final String METHOD_READ_BYTES = "a";
    private static final String DESC_READ_BYTES = "(I[BI)[B";

    private static final String DELEGATE_SYNC_GET_FILE = "syncAndGetFile";
    private static final String DELEGATE_CLOSE = "close";
    private static final String DELEGATE_SEEK = "seek";
    private static final String DELEGATE_SKIP = "skip";
    private static final String DELEGATE_READ_BYTE = "readByte";
    private static final String DELEGATE_READ_SHORT = "readShort";
    private static final String DELEGATE_READ_INT = "readInt";
    private static final String DELEGATE_READ_LONG = "readLong";
    private static final String DELEGATE_READ_BYTES = "readBytes";

    private static final Set<String> REQUIRED_METHODS = Set.of(
            INIT_NAME + INIT_FILE_DESC,
            METHOD_GET_RAF + DESC_GET_RAF,
            METHOD_CLOSE + DESC_VOID,
            METHOD_SEEK + DESC_SEEK,
            METHOD_SKIP + DESC_SKIP,
            METHOD_READ_BYTE + DESC_READ_BYTE,
            METHOD_READ_SHORT + DESC_READ_SHORT,
            METHOD_READ_INT + DESC_READ_INT,
            METHOD_READ_LONG + DESC_READ_LONG,
            METHOD_READ_BYTES + DESC_READ_BYTES);

    private BufferedRandomAccessReadPatcher() {}

    /**
     * Patches {@code RandomAccess$c} when its verified class shape matches and buffering is absent.
     *
     * @param internalName JVM internal class name
     * @param loader target host class loader, nullable
     * @param classfileBuffer input class bytes
     * @return patched bytes, or {@code null} when not applicable or already patched
     */
    static byte[] tryPatch(final String internalName, final ClassLoader loader, final byte[] classfileBuffer) {
        if (!TARGET.equals(internalName) || classfileBuffer == null) {
            return null;
        }
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (ModelOpenClassWriters.callsOwner(reader, READER_INTERNAL)) {
            return null;
        }
        final ShapeScan scan = new ShapeScan();
        reader.accept(scan, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (!scan.matches()) {
            return null;
        }

        final ClassWriter writer = ModelOpenClassWriters.newFrameWriter(reader, loader);
        reader.accept(
                new ClassVisitor(ASM9, writer) {
                    private boolean emittedField;

                    @Override
                    public FieldVisitor visitField(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final Object value) {
                        if (BUFFERED_FIELD.equals(name)) {
                            this.emittedField = true;
                        }
                        return super.visitField(access, name, desc, signature, value);
                    }

                    @Override
                    public void visitEnd() {
                        if (!this.emittedField) {
                            final FieldVisitor fv = super.visitField(
                                    ACC_PRIVATE | ACC_FINAL | ACC_SYNTHETIC, BUFFERED_FIELD, READER_DESC, null, null);
                            if (fv != null) {
                                fv.visitEnd();
                            }
                            this.emittedField = true;
                        }
                        super.visitEnd();
                    }

                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final String[] exceptions) {
                        if (INIT_NAME.equals(name) && INIT_FILE_DESC.equals(desc)) {
                            final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                            return new ConstructorVisitor(mv);
                        }
                        if (isDelegatedMethod(name, desc)) {
                            emitDelegatedMethod(
                                    super.visitMethod(access, name, desc, signature, exceptions), name, desc);
                            return null;
                        }
                        return super.visitMethod(access, name, desc, signature, exceptions);
                    }
                },
                ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static boolean isDelegatedMethod(final String name, final String desc) {
        return (METHOD_GET_RAF.equals(name) && DESC_GET_RAF.equals(desc))
                || (METHOD_CLOSE.equals(name) && DESC_VOID.equals(desc))
                || (METHOD_SEEK.equals(name) && DESC_SEEK.equals(desc))
                || (METHOD_SKIP.equals(name) && DESC_SKIP.equals(desc))
                || (METHOD_READ_BYTE.equals(name) && DESC_READ_BYTE.equals(desc))
                || (METHOD_READ_SHORT.equals(name) && DESC_READ_SHORT.equals(desc))
                || (METHOD_READ_INT.equals(name) && DESC_READ_INT.equals(desc))
                || (METHOD_READ_LONG.equals(name) && DESC_READ_LONG.equals(desc))
                || (METHOD_READ_BYTES.equals(name) && DESC_READ_BYTES.equals(desc));
    }

    private static void emitDelegatedMethod(final MethodVisitor mv, final String name, final String desc) {
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, TARGET, BUFFERED_FIELD, READER_DESC);
        if (METHOD_GET_RAF.equals(name) && DESC_GET_RAF.equals(desc)) {
            mv.visitMethodInsn(INVOKEVIRTUAL, READER_INTERNAL, DELEGATE_SYNC_GET_FILE, DESC_GET_RAF, false);
            mv.visitInsn(ARETURN);
        } else if (METHOD_CLOSE.equals(name) && DESC_VOID.equals(desc)) {
            mv.visitMethodInsn(INVOKEVIRTUAL, READER_INTERNAL, DELEGATE_CLOSE, DESC_VOID, false);
            mv.visitInsn(RETURN);
        } else if (METHOD_SEEK.equals(name) && DESC_SEEK.equals(desc)) {
            mv.visitVarInsn(LLOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, READER_INTERNAL, DELEGATE_SEEK, DESC_SEEK, false);
            mv.visitInsn(RETURN);
        } else if (METHOD_SKIP.equals(name) && DESC_SKIP.equals(desc)) {
            mv.visitVarInsn(ILOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, READER_INTERNAL, DELEGATE_SKIP, DESC_SKIP, false);
            mv.visitInsn(RETURN);
        } else if (METHOD_READ_BYTE.equals(name) && DESC_READ_BYTE.equals(desc)) {
            mv.visitVarInsn(ILOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, READER_INTERNAL, DELEGATE_READ_BYTE, DESC_READ_BYTE, false);
            mv.visitInsn(IRETURN);
        } else if (METHOD_READ_SHORT.equals(name) && DESC_READ_SHORT.equals(desc)) {
            mv.visitVarInsn(ILOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, READER_INTERNAL, DELEGATE_READ_SHORT, DESC_READ_SHORT, false);
            mv.visitInsn(IRETURN);
        } else if (METHOD_READ_INT.equals(name) && DESC_READ_INT.equals(desc)) {
            mv.visitVarInsn(ILOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, READER_INTERNAL, DELEGATE_READ_INT, DESC_READ_INT, false);
            mv.visitInsn(IRETURN);
        } else if (METHOD_READ_LONG.equals(name) && DESC_READ_LONG.equals(desc)) {
            mv.visitVarInsn(ILOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, READER_INTERNAL, DELEGATE_READ_LONG, DESC_READ_LONG, false);
            mv.visitInsn(LRETURN);
        } else if (METHOD_READ_BYTES.equals(name) && DESC_READ_BYTES.equals(desc)) {
            mv.visitVarInsn(ILOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ILOAD, 3);
            mv.visitMethodInsn(INVOKEVIRTUAL, READER_INTERNAL, DELEGATE_READ_BYTES, DESC_READ_BYTES, false);
            mv.visitInsn(ARETURN);
        }
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static final class ShapeScan extends ClassVisitor {

        private final Set<String> foundMethods = new HashSet<>();
        private boolean hasExpectedSuper;
        private boolean hasRafField;

        private ShapeScan() {
            super(ASM9);
        }

        @Override
        public void visit(
                final int version,
                final int access,
                final String name,
                final String signature,
                final String superName,
                final String[] interfaces) {
            this.hasExpectedSuper = SUPER_NAME.equals(superName);
        }

        @Override
        public FieldVisitor visitField(
                final int access, final String name, final String desc, final String signature, final Object value) {
            if (RAF_FIELD.equals(name) && RAF_DESC.equals(desc)) {
                this.hasRafField = true;
            }
            return null;
        }

        @Override
        public MethodVisitor visitMethod(
                final int access,
                final String name,
                final String desc,
                final String signature,
                final String[] exceptions) {
            this.foundMethods.add(name + desc);
            return null;
        }

        private boolean matches() {
            return this.hasExpectedSuper && this.hasRafField && this.foundMethods.containsAll(REQUIRED_METHODS);
        }
    }

    private static final class ConstructorVisitor extends MethodVisitor {

        private ConstructorVisitor(final MethodVisitor delegate) {
            super(ASM9, delegate);
        }

        @Override
        public void visitInsn(final int opcode) {
            if (opcode == RETURN) {
                super.visitVarInsn(ALOAD, 0);
                super.visitTypeInsn(NEW, READER_INTERNAL);
                super.visitInsn(DUP);
                super.visitVarInsn(ALOAD, 0);
                super.visitFieldInsn(GETFIELD, TARGET, RAF_FIELD, RAF_DESC);
                super.visitMethodInsn(INVOKESPECIAL, READER_INTERNAL, INIT_NAME, READER_INIT_DESC, false);
                super.visitFieldInsn(PUTFIELD, TARGET, BUFFERED_FIELD, READER_DESC);
            }
            super.visitInsn(opcode);
        }
    }
}
