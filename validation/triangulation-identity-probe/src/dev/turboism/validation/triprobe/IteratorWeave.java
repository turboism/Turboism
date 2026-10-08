package dev.turboism.validation.triprobe;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Prepends {@code Probe.record(this, this.b)} to {@code iterator()Ljava/util/Iterator;} inside a
 * {@code Throwable} catch whose handler pops the error and re-enters the original body. The
 * try-block wraps the {@code getfield}/{@code invokestatic} instructions themselves, so linkage and
 * initialization failures of the helper are caught at the callsite rather than relying on any
 * helper-internal guard. COMPUTE_MAXS only; original frames are left attached to their original
 * instructions and the handler gets one explicit frame — no class is loaded to resolve supertypes.
 */
final class IteratorWeave implements Opcodes {
    private IteratorWeave() {}

    static final String HELPER = "dev/turboism/validation/triprobe/Probe";

    static byte[] weave(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        cr.accept(new ClassVisitor(ASM9, cw) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature,
                        exceptions);
                if (!"iterator".equals(name) || !"()Ljava/util/Iterator;".equals(descriptor)
                        || mv == null) {
                    return mv;
                }
                return new MethodVisitor(ASM9, mv) {
                    @Override public void visitCode() {
                        super.visitCode();
                        Label tryStart = new Label();
                        Label tryEnd = new Label();
                        Label handler = new Label();
                        visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/Throwable");
                        visitLabel(tryStart);
                        visitVarInsn(ALOAD, 0);
                        visitVarInsn(ALOAD, 0);
                        visitFieldInsn(GETFIELD, ProbeConfig.TARGET_INTERNAL, "b",
                                "Ljava/util/LinkedHashSet;");
                        visitMethodInsn(INVOKESTATIC, HELPER, "record",
                                "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                        visitLabel(tryEnd);
                        // The handler's GOTO re-enters here; the verifier requires a frame at
                        // every branch target — locals=[this], stack empty on both edges.
                        visitFrame(F_SAME, 0, null, 0, null);
                        // Original method body follows. Handler is appended at method end by
                        // visitMaxs-time jump target; emit it via a deferred label.
                        this.handler = handler;
                        this.tryEnd = tryEnd;
                    }

                    private Label handler;
                    private Label tryEnd;

                    @Override public void visitMaxs(int maxStack, int maxLocals) {
                        // Emit the handler right before the real code ends: pop the throwable and
                        // resume at the first original instruction.
                        mv.visitLabel(handler);
                        mv.visitFrame(F_FULL, 1,
                                new Object[] {ProbeConfig.TARGET_INTERNAL}, 1,
                                new Object[] {"java/lang/Throwable"});
                        mv.visitInsn(POP);
                        mv.visitJumpInsn(GOTO, tryEnd);
                        super.visitMaxs(maxStack, maxLocals);
                    }
                };
            }
        }, 0);
        return cw.toByteArray();
    }
}
