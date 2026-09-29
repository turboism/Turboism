package dev.turboism.validation.dweave;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Bytecode mutants for the DWEAVE shape gate — each produces bytes that must
 * be rejected with a specific reason. All operate on the fixture's target
 * method ({@code c()V}); mutants are never defined or executed, only fed to
 * {@link Weave#weaveChecked}.
 */
final class ShapeMutants implements Opcodes {
    private ShapeMutants() {}

    private static final String LIST = "java/util/ArrayList";

    private interface MvFactory { MethodVisitor wrap(MethodVisitor mv); }

    private static byte[] mutate(byte[] in, MvFactory f) {
        ClassWriter cw = new ClassWriter(0);
        new ClassReader(in).accept(new ClassVisitor(ASM9, cw) {
            @Override public MethodVisitor visitMethod(int acc, String name,
                    String desc, String sig, String[] exc) {
                MethodVisitor mv = super.visitMethod(acc, name, desc, sig, exc);
                if (!(name.equals("c") && desc.equals("()V")) || mv == null) return mv;
                return f.wrap(mv);
            }
        }, 0);
        return cw.toByteArray();
    }

    /** Drop the DUP in the FIRST pattern site: NEW;POP;INVOKESPECIAL;ASTORE
     *  no longer matches -> "no pinned init site". */
    static byte[] dropPinnedDup(byte[] in) {
        return mutate(in, mv -> new MethodVisitor(ASM9, mv) {
            boolean afterNew;
            boolean patched;
            @Override public void visitTypeInsn(int op, String t) {
                afterNew = !patched && op == NEW && t.equals(LIST);
                super.visitTypeInsn(op, t);
            }
            @Override public void visitInsn(int op) {
                if (afterNew && op == DUP) {
                    patched = true; afterNew = false;
                    super.visitInsn(POP);
                    return;
                }
                afterNew = false;
                super.visitInsn(op);
            }
            @Override public void visitMethodInsn(int op, String o, String n, String d, boolean itf) {
                afterNew = false;
                super.visitMethodInsn(op, o, n, d, itf);
            }
        });
    }

    /** Insert a NOP between the first site's NEW and DUP — the 4-insn
     *  contiguity breaks -> "no pinned init site". */
    static byte[] gapBeforeDup(byte[] in) {
        return mutate(in, mv -> new MethodVisitor(ASM9, mv) {
            boolean afterNew;
            boolean patched;
            @Override public void visitTypeInsn(int op, String t) {
                afterNew = !patched && op == NEW && t.equals(LIST);
                super.visitTypeInsn(op, t);
            }
            @Override public void visitInsn(int op) {
                if (afterNew && op == DUP) {
                    patched = true; afterNew = false;
                    super.visitInsn(NOP);
                    super.visitInsn(op);
                    return;
                }
                afterNew = false;
                super.visitInsn(op);
            }
            @Override public void visitMethodInsn(int op, String o, String n, String d, boolean itf) {
                afterNew = false;
                super.visitMethodInsn(op, o, n, d, itf);
            }
        });
    }

    /** Repoint the FIRST matching site's ASTORE to a different local slot —
     *  the pinned slot no longer has a site -> "no pinned init site". */
    static byte[] retargetPinnedSlot(byte[] in) {
        return mutate(in, mv -> new MethodVisitor(ASM9, mv) {
            final java.util.List<int[]> trail = new java.util.ArrayList<>();
            boolean patched;
            void tick(int op, int flag) {
                trail.add(new int[] { op, flag });
                if (trail.size() > 4) trail.remove(0);
            }
            @Override public void visitTypeInsn(int op, String t) {
                tick(op, op == NEW && t.equals(LIST) ? 1 : 0);
                super.visitTypeInsn(op, t);
            }
            @Override public void visitInsn(int op) { tick(op, 0); super.visitInsn(op); }
            @Override public void visitMethodInsn(int op, String o, String n, String d, boolean itf) {
                tick(op, op == INVOKESPECIAL && o.equals(LIST)
                        && "<init>".equals(n) && "()V".equals(d) ? 1 : 0);
                super.visitMethodInsn(op, o, n, d, itf);
            }
            @Override public void visitVarInsn(int op, int var) {
                int sz = trail.size();
                if (!patched && op == ASTORE && sz >= 3
                        && trail.get(sz - 1)[0] == INVOKESPECIAL && trail.get(sz - 1)[1] == 1
                        && trail.get(sz - 2)[0] == DUP
                        && trail.get(sz - 3)[0] == NEW && trail.get(sz - 3)[1] == 1) {
                    patched = true;
                    tick(op, 0);
                    super.visitVarInsn(op, var + 16);
                    return;
                }
                tick(op, 0);
                super.visitVarInsn(op, var);
            }
        });
    }

    /** Emit a SECOND identical pattern sequence to the SAME pinned slot right
     *  after the first site -> "ambiguous pinned init site". */
    static byte[] duplicatePinnedSite(byte[] in) {
        return mutate(in, mv -> new MethodVisitor(ASM9, mv) {
            final java.util.List<int[]> trail = new java.util.ArrayList<>();
            boolean patched;
            void tick(int op, int flag) {
                trail.add(new int[] { op, flag });
                if (trail.size() > 4) trail.remove(0);
            }
            @Override public void visitTypeInsn(int op, String t) {
                tick(op, op == NEW && t.equals(LIST) ? 1 : 0);
                super.visitTypeInsn(op, t);
            }
            @Override public void visitInsn(int op) { tick(op, 0); super.visitInsn(op); }
            @Override public void visitMethodInsn(int op, String o, String n, String d, boolean itf) {
                tick(op, op == INVOKESPECIAL && o.equals(LIST)
                        && "<init>".equals(n) && "()V".equals(d) ? 1 : 0);
                super.visitMethodInsn(op, o, n, d, itf);
            }
            @Override public void visitVarInsn(int op, int var) {
                int sz = trail.size();
                boolean match = !patched && op == ASTORE && sz >= 3
                        && trail.get(sz - 1)[0] == INVOKESPECIAL && trail.get(sz - 1)[1] == 1
                        && trail.get(sz - 2)[0] == DUP
                        && trail.get(sz - 3)[0] == NEW && trail.get(sz - 3)[1] == 1;
                tick(op, 0);
                super.visitVarInsn(op, var);
                if (match) {
                    patched = true;
                    super.visitTypeInsn(NEW, LIST);
                    super.visitInsn(DUP);
                    super.visitMethodInsn(INVOKESPECIAL, LIST, "<init>", "()V", false);
                    super.visitVarInsn(ASTORE, var);
                }
            }
        });
    }

    /** Retarget the first site's ctor call to ArrayList.<init>(I)V — the
     *  pinned ()V pattern no longer matches -> "no pinned init site". */
    static byte[] wrongCtorDesc(byte[] in) {
        return mutate(in, mv -> new MethodVisitor(ASM9, mv) {
            boolean patched;
            @Override public void visitMethodInsn(int op, String o, String n, String d, boolean itf) {
                if (!patched && op == INVOKESPECIAL && o.equals(LIST)
                        && "<init>".equals(n) && "()V".equals(d)) {
                    patched = true;
                    super.visitMethodInsn(op, o, n, "(I)V", itf);
                    return;
                }
                super.visitMethodInsn(op, o, n, d, itf);
            }
        });
    }

    /** Repoint the first NEW ArrayList to LinkedList — the pinned listType
     *  operand no longer matches -> "no pinned init site". */
    static byte[] otherNewType(byte[] in) {
        return mutate(in, mv -> new MethodVisitor(ASM9, mv) {
            boolean patched;
            @Override public void visitTypeInsn(int op, String t) {
                if (!patched && op == NEW && t.equals(LIST)) {
                    patched = true;
                    super.visitTypeInsn(op, "java/util/LinkedList");
                    return;
                }
                super.visitTypeInsn(op, t);
            }
        });
    }
}
