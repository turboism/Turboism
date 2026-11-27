package dev.turboism.adapter.cubism.optimization.save;

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
 * Patches {@code com/live2d/serialize/archiver/RandomAccess$d} to route sequential archive writes
 * through {@link BufferedRandomAccessWriter} while preserving seek-back offset patching and fsync.
 */
public final class BufferedRandomAccessPatcher {

    public static final String TARGET = "com/live2d/serialize/archiver/RandomAccess$d";

    private static final String WRITER_INTERNAL =
            "dev/turboism/adapter/cubism/optimization/save/BufferedRandomAccessWriter";
    private static final String WRITER_DESC = "L" + WRITER_INTERNAL + ";";
    private static final String BUFFERED_FIELD = "turboism$buffered";
    private static final String RAF_FIELD = "b";
    private static final String RAF_DESC = "Ljava/io/RandomAccessFile;";
    private static final String INIT_NAME = "<init>";
    private static final String INIT_FILE_DESC = "(Ljava/io/File;)V";
    private static final String WRITER_INIT_DESC = "(Ljava/io/RandomAccessFile;)V";

    private static final String METHOD_GET_RAF = "a";
    private static final String DESC_GET_RAF = "()Ljava/io/RandomAccessFile;";
    private static final String METHOD_CLOSE = "b";
    private static final String DESC_VOID = "()V";
    private static final String METHOD_SEEK = "a";
    private static final String DESC_SEEK = "(J)V";
    private static final String METHOD_FILE_POINTER = "c";
    private static final String DESC_LONG = "()J";
    private static final String METHOD_ZEROS = "a";
    private static final String DESC_ZEROS = "(I)V";
    private static final String METHOD_WRITE = "a";
    private static final String DESC_BYTE = "(BI)V";
    private static final String DESC_SHORT = "(SI)V";
    private static final String DESC_INT = "(II)V";
    private static final String DESC_LONG_XOR = "(JI)V";
    private static final String DESC_BYTES = "([BIIIZ)V";

    private static final Set<String> REQUIRED_METHODS = Set.of(
            INIT_NAME + INIT_FILE_DESC,
            METHOD_GET_RAF + DESC_GET_RAF,
            METHOD_CLOSE + DESC_VOID,
            METHOD_SEEK + DESC_SEEK,
            METHOD_FILE_POINTER + DESC_LONG,
            METHOD_ZEROS + DESC_ZEROS,
            METHOD_WRITE + DESC_BYTE,
            METHOD_WRITE + DESC_SHORT,
            METHOD_WRITE + DESC_INT,
            METHOD_WRITE + DESC_LONG_XOR,
            METHOD_WRITE + DESC_BYTES);

    private BufferedRandomAccessPatcher() {}

    /**
     * Patches {@code RandomAccess$d} if expected shape matches and buffering is not yet applied.
     *
     * @param internalName JVM internal class name
     * @param loader target host class loader, nullable
     * @param classfileBuffer input class bytes
     * @return patched bytes, or {@code null} when not applicable or already patched
     */
    public static byte[] tryPatch(final String internalName, final ClassLoader loader, final byte[] classfileBuffer) {
        if (!TARGET.equals(internalName) || classfileBuffer == null) {
            return null;
        }
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (SavePerfClassWriters.callsOwner(reader, WRITER_INTERNAL)) {
            return null;
        }
        final ShapeScan scan = new ShapeScan();
        reader.accept(scan, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (!scan.matches()) {
            return null;
        }

        final ClassWriter writer = SavePerfClassWriters.newFrameWriter(reader, loader);
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
                                    ACC_PRIVATE | ACC_FINAL | ACC_SYNTHETIC, BUFFERED_FIELD, WRITER_DESC, null, null);
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
                || (METHOD_FILE_POINTER.equals(name) && DESC_LONG.equals(desc))
                || (METHOD_ZEROS.equals(name) && DESC_ZEROS.equals(desc))
                || (METHOD_WRITE.equals(name) && DESC_BYTE.equals(desc))
                || (METHOD_WRITE.equals(name) && DESC_SHORT.equals(desc))
                || (METHOD_WRITE.equals(name) && DESC_INT.equals(desc))
                || (METHOD_WRITE.equals(name) && DESC_LONG_XOR.equals(desc))
                || (METHOD_WRITE.equals(name) && DESC_BYTES.equals(desc));
    }

    private static void emitDelegatedMethod(final MethodVisitor mv, final String name, final String desc) {
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, TARGET, BUFFERED_FIELD, WRITER_DESC);
        if (METHOD_GET_RAF.equals(name) && DESC_GET_RAF.equals(desc)) {
            mv.visitMethodInsn(INVOKEVIRTUAL, WRITER_INTERNAL, "flushAndGetFile", DESC_GET_RAF, false);
            mv.visitInsn(ARETURN);
        } else if (METHOD_CLOSE.equals(name) && DESC_VOID.equals(desc)) {
            mv.visitMethodInsn(INVOKEVIRTUAL, WRITER_INTERNAL, "close", DESC_VOID, false);
            mv.visitInsn(RETURN);
        } else if (METHOD_SEEK.equals(name) && DESC_SEEK.equals(desc)) {
            mv.visitVarInsn(LLOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, WRITER_INTERNAL, "seek", DESC_SEEK, false);
            mv.visitInsn(RETURN);
        } else if (METHOD_FILE_POINTER.equals(name) && DESC_LONG.equals(desc)) {
            mv.visitMethodInsn(INVOKEVIRTUAL, WRITER_INTERNAL, "getFilePointer", DESC_LONG, false);
            mv.visitInsn(LRETURN);
        } else if (METHOD_ZEROS.equals(name) && DESC_ZEROS.equals(desc)) {
            mv.visitVarInsn(ILOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, WRITER_INTERNAL, "writeZeros", DESC_ZEROS, false);
            mv.visitInsn(RETURN);
        } else if (DESC_BYTE.equals(desc)) {
            mv.visitVarInsn(ILOAD, 1);
            mv.visitVarInsn(ILOAD, 2);
            mv.visitMethodInsn(INVOKEVIRTUAL, WRITER_INTERNAL, "writeByte", DESC_BYTE, false);
            mv.visitInsn(RETURN);
        } else if (DESC_SHORT.equals(desc)) {
            mv.visitVarInsn(ILOAD, 1);
            mv.visitVarInsn(ILOAD, 2);
            mv.visitMethodInsn(INVOKEVIRTUAL, WRITER_INTERNAL, "writeShort", DESC_SHORT, false);
            mv.visitInsn(RETURN);
        } else if (DESC_INT.equals(desc)) {
            mv.visitVarInsn(ILOAD, 1);
            mv.visitVarInsn(ILOAD, 2);
            mv.visitMethodInsn(INVOKEVIRTUAL, WRITER_INTERNAL, "writeInt", DESC_INT, false);
            mv.visitInsn(RETURN);
        } else if (DESC_LONG_XOR.equals(desc)) {
            mv.visitVarInsn(LLOAD, 1);
            mv.visitVarInsn(ILOAD, 3);
            mv.visitMethodInsn(INVOKEVIRTUAL, WRITER_INTERNAL, "writeLong", DESC_LONG_XOR, false);
            mv.visitInsn(RETURN);
        } else if (DESC_BYTES.equals(desc)) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ILOAD, 2);
            mv.visitVarInsn(ILOAD, 3);
            mv.visitVarInsn(ILOAD, 4);
            mv.visitVarInsn(ILOAD, 5);
            mv.visitMethodInsn(INVOKEVIRTUAL, WRITER_INTERNAL, "writeBytes", DESC_BYTES, false);
            mv.visitInsn(RETURN);
        }
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static final class ShapeScan extends ClassVisitor {

        private final Set<String> foundMethods = new HashSet<>();
        private boolean hasRafField;

        private ShapeScan() {
            super(ASM9);
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
            return this.hasRafField && this.foundMethods.containsAll(REQUIRED_METHODS);
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
                super.visitTypeInsn(NEW, WRITER_INTERNAL);
                super.visitInsn(DUP);
                super.visitVarInsn(ALOAD, 0);
                super.visitFieldInsn(GETFIELD, TARGET, RAF_FIELD, RAF_DESC);
                super.visitMethodInsn(INVOKESPECIAL, WRITER_INTERNAL, INIT_NAME, WRITER_INIT_DESC, false);
                super.visitFieldInsn(PUTFIELD, TARGET, BUFFERED_FIELD, WRITER_DESC);
            }
            super.visitInsn(opcode);
        }
    }
}
