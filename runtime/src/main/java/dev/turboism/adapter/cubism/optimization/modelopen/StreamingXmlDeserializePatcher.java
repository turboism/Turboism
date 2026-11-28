package dev.turboism.adapter.cubism.optimization.modelopen;

import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.IF_ACMPEQ;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.POP;
import static org.objectweb.asm.Opcodes.SWAP;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Patches {@code com.live2d.serialize.UUSerialize#deserialize_exe} to route {@code main.xml} byte
 * arrays through {@link StreamingXmlDeserializeBridge} before JDOM {@code Document} construction.
 *
 * <p>Modifies only {@code deserialize_exe} so it composes cleanly and idempotently in either order
 * with {@link DeserializeGcElisionPatcher}.</p>
 */
final class StreamingXmlDeserializePatcher {

    static final String TARGET_INTERNAL_NAME = "com/live2d/serialize/UUSerialize";

    private static final String DESERIALIZE_EXE_NAME = "deserialize_exe";
    private static final String DESERIALIZE_EXE_DESC =
            "(Lcom/live2d/serialize/archiver/ArchiveReader;Ljava/util/HashMap;IZ)Ljava/lang/Object;";
    private static final String UTIL_OWNER_PREFIX = "com/live2d/util/";
    private static final String PARSE_BYTES_TO_DOC_DESC = "([B)Lorg/jdom/Document;";
    private static final String BRIDGE_INTERNAL_NAME =
            "dev/turboism/adapter/cubism/optimization/modelopen/StreamingXmlDeserializeBridge";
    private static final String TRY_DESERIALIZE_NAME = "tryDeserialize";
    private static final String TRY_DESERIALIZE_DESC = "([BLjava/lang/Object;Ljava/util/HashMap;IZ)Ljava/lang/Object;";
    private static final String UNHANDLED_FIELD_NAME = "UNHANDLED";
    private static final String OBJECT_DESC = "Ljava/lang/Object;";

    private static final int ARG_ARCHIVE_READER = 1;
    private static final int ARG_CLIENT_PROPERTY = 2;
    private static final int ARG_APP_VERSION = 3;
    private static final int ARG_SHOW_DIALOG = 4;

    private StreamingXmlDeserializePatcher() {}

    /**
     * Patches {@code UUSerialize#deserialize_exe} to invoke {@link StreamingXmlDeserializeBridge}
     * before parsing {@code main.xml} bytes into a JDOM {@code Document}.
     *
     * @param internalName JVM internal name of the class under transformation
     * @param loader class loader defining the target class
     * @param classfileBuffer original class bytecode
     * @return patched bytecode, or {@code null} when the class does not match or is already patched
     */
    static byte[] tryPatch(final String internalName, final ClassLoader loader, final byte[] classfileBuffer) {
        if (!TARGET_INTERNAL_NAME.equals(internalName) || classfileBuffer == null) {
            return null;
        }
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (ModelOpenClassWriters.callsOwner(reader, BRIDGE_INTERNAL_NAME)) {
            return null;
        }
        final TargetScan scan = new TargetScan();
        reader.accept(scan, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        if (!scan.hasTargetCall) {
            return null;
        }
        final ClassWriter writer = ModelOpenClassWriters.newFrameWriter(reader, loader);
        final boolean[] patched = new boolean[1];
        reader.accept(
                new ClassVisitor(ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                        if (!isTargetMethod(name, desc)) {
                            return mv;
                        }
                        return new MethodVisitor(ASM9, mv) {
                            @Override
                            public void visitMethodInsn(
                                    final int opcode,
                                    final String owner,
                                    final String methodName,
                                    final String methodDesc,
                                    final boolean isInterface) {
                                if (!patched[0] && isParseBytesToDocCall(opcode, owner, methodDesc)) {
                                    patched[0] = true;
                                    emitStreamingFastPath(mv);
                                }
                                super.visitMethodInsn(opcode, owner, methodName, methodDesc, isInterface);
                            }
                        };
                    }
                },
                ClassReader.EXPAND_FRAMES);
        return patched[0] ? writer.toByteArray() : null;
    }

    private static void emitStreamingFastPath(final MethodVisitor mv) {
        final Label fallbackLabel = new Label();
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, ARG_ARCHIVE_READER);
        mv.visitVarInsn(ALOAD, ARG_CLIENT_PROPERTY);
        mv.visitVarInsn(ILOAD, ARG_APP_VERSION);
        mv.visitVarInsn(ILOAD, ARG_SHOW_DIALOG);
        mv.visitMethodInsn(INVOKESTATIC, BRIDGE_INTERNAL_NAME, TRY_DESERIALIZE_NAME, TRY_DESERIALIZE_DESC, false);
        mv.visitInsn(DUP);
        mv.visitFieldInsn(GETSTATIC, BRIDGE_INTERNAL_NAME, UNHANDLED_FIELD_NAME, OBJECT_DESC);
        mv.visitJumpInsn(IF_ACMPEQ, fallbackLabel);
        // Clean the remaining [utilInstance, xmlBytes] entries underneath the return value.
        mv.visitInsn(SWAP);
        mv.visitInsn(POP);
        mv.visitInsn(SWAP);
        mv.visitInsn(POP);
        mv.visitInsn(ARETURN);
        mv.visitLabel(fallbackLabel);
        mv.visitInsn(POP);
    }

    private static boolean isTargetMethod(final String name, final String desc) {
        return DESERIALIZE_EXE_NAME.equals(name) && DESERIALIZE_EXE_DESC.equals(desc);
    }

    private static boolean isParseBytesToDocCall(final int opcode, final String owner, final String desc) {
        return opcode == INVOKEVIRTUAL
                && owner != null
                && owner.startsWith(UTIL_OWNER_PREFIX)
                && PARSE_BYTES_TO_DOC_DESC.equals(desc);
    }

    private static final class TargetScan extends ClassVisitor {

        private boolean hasTargetCall;

        TargetScan() {
            super(ASM9);
        }

        @Override
        public MethodVisitor visitMethod(
                final int access,
                final String name,
                final String desc,
                final String signature,
                final String[] exceptions) {
            if (!isTargetMethod(name, desc)) {
                return null;
            }
            return new MethodVisitor(ASM9) {
                @Override
                public void visitMethodInsn(
                        final int opcode,
                        final String owner,
                        final String methodName,
                        final String methodDesc,
                        final boolean isInterface) {
                    if (isParseBytesToDocCall(opcode, owner, methodDesc)) {
                        hasTargetCall = true;
                    }
                }
            };
        }
    }
}
