package dev.turboism.validation.meshhash;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Appends a bounded digest call immediately before every return of one exact host method.
 *
 * <p>The insertion is straight-line and stack-neutral: {@code ALOAD 0} pushes the receiver and the
 * {@code static} call pops it, so the frame at the return site is unchanged and existing stack map
 * frames stay valid. The transformer therefore recomputes only max stack/locals and never needs to
 * resolve host types.</p>
 *
 * <p>It fails closed: a missing class or method, an unexpected descriptor, or a method with no
 * return at all is reported as not applicable instead of being modified.</p>
 */
final class MeshUpdateDigestTransformer {
    private static final String PROBE_OWNER =
        "dev/turboism/validation/meshhash/MeshHashDigestProbe";
    private static final String PROBE_NAME = "capture";
    private static final String PROBE_DESCRIPTOR = "(Ljava/lang/Object;)V";

    private MeshUpdateDigestTransformer() {
    }

    /** Thrown when the target method is not present or not shaped as reviewed. */
    static final class NotApplicable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NotApplicable(final String message) {
            super(message);
        }
    }

    static byte[] hook(final byte[] original, final String methodDescriptor) {
        final boolean[] found = new boolean[1];
        final ClassWriter writer = new ClassWriter(new ClassReader(original),
            ClassWriter.COMPUTE_MAXS);
        new ClassReader(original).accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name,
                                             final String descriptor, final String signature,
                                             final String[] exceptions) {
                final MethodVisitor delegate =
                    super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"updateMesh".equals(name) || !methodDescriptor.equals(descriptor)) {
                    return delegate;
                }
                found[0] = true;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    private boolean sawReturn;

                    @Override
                    public void visitInsn(final int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            sawReturn = true;
                            mv.visitVarInsn(Opcodes.ALOAD, 0);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, PROBE_OWNER, PROBE_NAME,
                                PROBE_DESCRIPTOR, false);
                        }
                        super.visitInsn(opcode);
                    }

                    @Override
                    public void visitEnd() {
                        if (!sawReturn) {
                            throw new NotApplicable("updateMesh has no RETURN to instrument");
                        }
                        super.visitEnd();
                    }
                };
            }
        }, 0);
        if (!found[0]) {
            throw new NotApplicable("updateMesh" + methodDescriptor + " is absent");
        }
        return writer.toByteArray();
    }
}
