package dev.turboism.adapter.jdk;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * In-memory synthetic stand-in for the real JDK {@code sun.nio.ch.PipeImpl}
 * class file. The real class was previously checked in as a test fixture
 * copied from the Cubism-bundled JRE; this helper regenerates only the
 * minimal structural shape the transformer requires, so tests exercise the
 * same transform regression surface without shipping copied JDK bytes.
 *
 * <p>The transformer requires exactly one owner match, exactly one
 * {@code noUnixDomainSockets:Z} field and exactly one {@code <clinit>}; a
 * valid fixture additionally carries a {@code createListener()} whose
 * flag gate branches straight to the AF_INET {@code ServerSocketChannel}
 * path, mirroring the guarded shape the transformation makes unconditional.
 */
final class PipeImplSyntheticFixture {

    private PipeImplSyntheticFixture() {
    }

    /** Generates bytecode with the full required pipeline shape. */
    static byte[] valid() {
        return synthetic(true, true, true);
    }

    /** Generates bytecode that misses the field or the clinit as requested. */
    static byte[] invalid(final boolean withClinit, final boolean withFlag) {
        return synthetic(withClinit, withFlag, false);
    }

    private static byte[] synthetic(
        final boolean withClinit,
        final boolean withFlag,
        final boolean withCreateListener
    ) {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(
            Opcodes.V17,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
            PipeImplLoopbackTransformer.TARGET_OWNER,
            null,
            "java/lang/Object",
            null
        );
        if (withFlag) {
            writer.visitField(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
                PipeImplLoopbackTransformer.FIELD_NAME,
                PipeImplLoopbackTransformer.FIELD_DESCRIPTOR,
                null,
                null
            ).visitEnd();
        }
        if (withClinit) {
            final MethodVisitor clinit = writer.visitMethod(
                Opcodes.ACC_STATIC,
                "<clinit>",
                "()V",
                null,
                null
            );
            clinit.visitCode();
            clinit.visitInsn(Opcodes.RETURN);
            clinit.visitMaxs(0, 0);
            clinit.visitEnd();
        }
        if (withCreateListener) {
            final MethodVisitor listener = writer.visitMethod(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "createListener",
                "()Ljava/nio/channels/ServerSocketChannel;",
                null,
                null
            );
            listener.visitCode();
            listener.visitFieldInsn(
                Opcodes.GETSTATIC,
                PipeImplLoopbackTransformer.TARGET_OWNER,
                PipeImplLoopbackTransformer.FIELD_NAME,
                PipeImplLoopbackTransformer.FIELD_DESCRIPTOR
            );
            final org.objectweb.asm.Label inet = new org.objectweb.asm.Label();
            listener.visitJumpInsn(Opcodes.IFNE, inet);
            // Fallback kept minimal: the transform only needs the guarded
            // AF_INET branch to be reachable once the flag is forced true.
            listener.visitTypeInsn(Opcodes.NEW, "java/io/IOException");
            listener.visitInsn(Opcodes.DUP);
            listener.visitLdcInsn("synthetic fallback");
            listener.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "java/io/IOException",
                "<init>",
                "(Ljava/lang/String;)V",
                false
            );
            listener.visitInsn(Opcodes.ATHROW);
            listener.visitLabel(inet);
            listener.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/nio/channels/ServerSocketChannel",
                "open",
                "()Ljava/nio/channels/ServerSocketChannel;",
                false
            );
            listener.visitInsn(Opcodes.ARETURN);
            listener.visitMaxs(0, 0);
            listener.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }
}