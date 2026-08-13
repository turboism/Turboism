package dev.turboism.adapter.jdk;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Objects;

/**
 * In-memory JDK compatibility transform for {@code sun.nio.ch.PipeImpl}:
 * forces the {@code noUnixDomainSockets} static flag to {@code true} inside
 * {@code <clinit>} so {@code createListener()} deterministically uses the
 * pre-JEP-380 AF_INET loopback connector instead of the AF_UNIX path that
 * fails under wine-managed Cubism hosts (wine returns an empty
 * {@code sun_path} from {@code getsockname}, which NPEs in
 * {@code UnixDomainSockets.localAddress}).
 *
 * <p>Fail-open only: any unexpected class shape or parse failure rejects the
 * whole transform (original bytes are kept) and never throws into the class
 * loading path.
 */
final class PipeImplLoopbackTransformer {

    static final String TARGET_OWNER = "sun/nio/ch/PipeImpl";
    static final String FIELD_NAME = "noUnixDomainSockets";
    static final String FIELD_DESCRIPTOR = "Z";
    static final String CLINIT = "<clinit>";

    byte[] transformClass(final byte[] original) {
        Objects.requireNonNull(original, "original");
        requireExactShape(inspect(original));

        final ClassReader reader = new ClassReader(original);
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                final int access,
                final String name,
                final String descriptor,
                final String signature,
                final String[] exceptions
            ) {
                final MethodVisitor delegate = super.visitMethod(
                    access,
                    name,
                    descriptor,
                    signature,
                    exceptions
                );
                if (!name.equals(CLINIT) || !descriptor.equals("()V")) {
                    return delegate;
                }
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    private boolean flagInjected = false;

                    @Override
                    public void visitCode() {
                        super.visitCode();
                        if (!flagInjected) {
                            flagInjected = true;
                            // noUnixDomainSockets = true; before any other
                            // <clinit> work, so createListener() always takes
                            // the AF_INET loopback branch.
                            super.visitInsn(Opcodes.ICONST_1);
                            super.visitFieldInsn(
                                Opcodes.PUTSTATIC,
                                TARGET_OWNER,
                                FIELD_NAME,
                                FIELD_DESCRIPTOR
                            );
                        }
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    private static Shape inspect(final byte[] original) {
        final Shape shape = new Shape();
        new ClassReader(original).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(
                final int version,
                final int access,
                final String name,
                final String signature,
                final String superName,
                final String[] interfaces
            ) {
                if (TARGET_OWNER.equals(name)) {
                    shape.ownerMatches++;
                }
            }

            @Override
            public FieldVisitor visitField(
                final int access,
                final String name,
                final String descriptor,
                final String signature,
                final Object value
            ) {
                if (FIELD_NAME.equals(name) && FIELD_DESCRIPTOR.equals(descriptor)) {
                    shape.flagFields++;
                }
                return null;
            }

            @Override
            public MethodVisitor visitMethod(
                final int access,
                final String name,
                final String descriptor,
                final String signature,
                final String[] exceptions
            ) {
                if (CLINIT.equals(name) && "()V".equals(descriptor)) {
                    shape.clinits++;
                }
                return null;
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return shape;
    }

    private static void requireExactShape(final Shape shape) {
        if (shape.ownerMatches != 1 || shape.flagFields != 1 || shape.clinits != 1) {
            throw new TransformationRejectedException(
                "PipeImpl shape mismatch: owner=" + shape.ownerMatches
                    + ", noUnixDomainSockets(Z)=" + shape.flagFields
                    + ", <clinit>=" + shape.clinits
            );
        }
    }

    static final class TransformationRejectedException extends RuntimeException {
        TransformationRejectedException(final String message) {
            super(message);
        }
    }

    private static final class Shape {
        private int ownerMatches;
        private int flagFields;
        private int clinits;
    }
}
