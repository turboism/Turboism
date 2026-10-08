package dev.turboism.validation.triweave;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Return-point capture weave — the OBSERVATION half of the A/B agent, identical in both
 * modes. At the single ARETURN of the target method it stores the result into a fresh
 * local, calls {@code captureInternal.onReturn(Ljava/lang/Object;)V} inside a
 * catch(Throwable) whose handler pops the error, reloads the result local, and re-joins the
 * original ARETURN. A capture failure can never propagate into the host.
 *
 * Shape gate (both modes): the target method exists and has exactly one ARETURN.
 * Reject → original bytes returned; the reason is observable via {@link Result}.
 */
final class CaptureWeave implements Opcodes {
    private CaptureWeave() {}

    static final class Result {
        final byte[] bytes;
        final String rejectReason;          // null when accepted
        Result(byte[] b, String r) { bytes = b; rejectReason = r; }
    }

    static Result weaveChecked(String methodName, String methodDesc, String captureInternal,
            byte[] in) {
        Plan p = analyze(methodName, methodDesc, in);
        if (p == null) return new Result(in, "target-method-missing");
        if (p.areturns != 1) return new Result(in, "capture-areturns=" + p.areturns);
        return new Result(emit(methodName, methodDesc, captureInternal, in, p), null);
    }

    private static final class Plan {
        int areturns;
        int maxLocals;
        boolean found;
    }

    private static Plan analyze(String methodName, String methodDesc, byte[] in) {
        Plan p = new Plan();
        new ClassReader(in).accept(new ClassVisitor(ASM9) {
            @Override public MethodVisitor visitMethod(int acc, String name, String desc,
                    String sig, String[] exc) {
                if (!(name.equals(methodName) && desc.equals(methodDesc))) return null;
                p.found = true;
                return new MethodVisitor(ASM9) {
                    @Override public void visitInsn(int op) {
                        if (op == ARETURN) p.areturns++;
                    }
                    @Override public void visitMaxs(int ms, int ml) { p.maxLocals = ml; }
                };
            }
        }, 0);
        return p.found ? p : null;
    }

    private static byte[] emit(String methodName, String methodDesc, String captureInternal,
            byte[] in, Plan p) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String c) {
                return "java/lang/Object";
            }
        };
        new ClassReader(in).accept(new ClassVisitor(ASM9, cw) {
            @Override public MethodVisitor visitMethod(int acc, String name, String desc,
                    String sig, String[] exc) {
                MethodVisitor mv = super.visitMethod(acc, name, desc, sig, exc);
                if (!(name.equals(methodName) && desc.equals(methodDesc)) || mv == null)
                    return mv;
                return new MethodVisitor(ASM9, mv) {
                    final int tmpSlot = p.maxLocals;

                    @Override public void visitInsn(int op) {
                        if (op == ARETURN) {
                            Label t0 = new Label(), t1 = new Label(),
                                  handler = new Label(), post = new Label();
                            super.visitVarInsn(ASTORE, tmpSlot);
                            super.visitTryCatchBlock(t0, t1, handler,
                                "java/lang/Throwable");
                            super.visitLabel(t0);
                            super.visitVarInsn(ALOAD, tmpSlot);
                            super.visitMethodInsn(INVOKESTATIC, captureInternal,
                                "onReturn", "(Ljava/lang/Object;)V", false);
                            super.visitLabel(t1);
                            super.visitJumpInsn(GOTO, post);
                            super.visitLabel(handler);
                            super.visitInsn(POP);
                            super.visitLabel(post);
                            super.visitVarInsn(ALOAD, tmpSlot);
                        }
                        super.visitInsn(op);
                    }
                };
            }
        }, 0);
        return cw.toByteArray();
    }
}
