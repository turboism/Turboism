package dev.turboism.validation.triweave;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import dev.turboism.validation.kmembership.Weave;

/**
 * Bytecode mutants for shape-gate negative controls — the same eight variants the KWEAVE
 * acceptance exercised, parameterized on the shadow Weave.Config so they run against the
 * shadow target. Each produces bytes that must be rejected at a specific shape check.
 */
final class ShapeMutants implements Opcodes {
    private ShapeMutants() {}

    /** Replace first ICONST_0 in the target method with ICONST_1 (true-operand variant). */
    static byte[] zToTrue(byte[] in, Weave.Config cfg) {
        return mutateInsn(in, cfg, mv -> new MethodVisitor(ASM9, mv) {
            boolean patched;
            @Override public void visitInsn(int op) {
                if (!patched && op == ICONST_0) {
                    patched = true;
                    super.visitInsn(ICONST_1);
                    return;
                }
                super.visitInsn(op);
            }
        });
    }

    /** Replace first ICONST_0 with ILOAD 0 (non-constant operand variant). */
    static byte[] zToIload(byte[] in, Weave.Config cfg) {
        return mutateInsn(in, cfg, mv -> new MethodVisitor(ASM9, mv) {
            boolean patched;
            @Override public void visitInsn(int op) {
                if (!patched && op == ICONST_0) {
                    patched = true;
                    super.visitVarInsn(ILOAD, 0);
                    return;
                }
                super.visitInsn(op);
            }
        });
    }

    /** Retarget the first query call's descriptor to (Ljava/lang/Object;Z)Z. */
    static byte[] retargetQueryDesc(byte[] in, Weave.Config cfg) {
        return mutateInsn(in, cfg, mv -> new MethodVisitor(ASM9, mv) {
            boolean patched;
            @Override public void visitMethodInsn(int op, String o, String n,
                    String d, boolean itf) {
                if (!patched && op == INVOKEVIRTUAL && o.equals(cfg.kType)
                        && d.equals(cfg.queryDesc)) {
                    patched = true;
                    super.visitMethodInsn(op, o, n, "(Ljava/lang/Object;Z)Z", itf);
                    return;
                }
                super.visitMethodInsn(op, o, n, d, itf);
            }
        });
    }

    /** Remove the whole first query statement: the 4-insn query sequence plus its
     *  conditional jump, the append pair, and the result POP — site count 2. */
    static byte[] dropCompleteSite(byte[] in, Weave.Config cfg) {
        return mutateInsn(in, cfg, mv -> new MethodVisitor(ASM9, mv) {
            int idx = -1;
            int dropUntil = -1;
            void tick() { idx++; }
            boolean drop() { return idx <= dropUntil; }
            @Override public void visitMethodInsn(int op, String o, String n,
                    String d, boolean itf) {
                tick();
                if (dropUntil < 0 && op == INVOKEVIRTUAL && o.equals(cfg.kType)
                        && d.equals(cfg.queryDesc))
                    dropUntil = idx + 5;   // invoke+jump+aload+aload+append+pop
                if (!drop()) super.visitMethodInsn(op, o, n, d, itf);
            }
            @Override public void visitJumpInsn(int op, org.objectweb.asm.Label l) {
                tick(); if (!drop()) super.visitJumpInsn(op, l);
            }
            @Override public void visitInsn(int op) {
                tick(); if (!drop()) super.visitInsn(op);
            }
            @Override public void visitVarInsn(int op, int v) {
                tick(); if (!drop()) super.visitVarInsn(op, v);
            }
        });
    }

    /** Patch the k-init anchor ASTORE to a different local slot. */
    static byte[] anchorSlot(byte[] in, Weave.Config cfg) {
        return mutateInsn(in, cfg, mv -> new MethodVisitor(ASM9, mv) {
            boolean patched;
            final java.util.List<int[]> trail = new java.util.ArrayList<>();
            void tick(int op, int flags) {
                trail.add(new int[]{op, flags});
                if (trail.size() > 4) trail.remove(0);
            }
            @Override public void visitTypeInsn(int op, String t) {
                tick(op, 0); super.visitTypeInsn(op, t);
            }
            @Override public void visitInsn(int op) {
                tick(op, 0); super.visitInsn(op);
            }
            @Override public void visitJumpInsn(int op, org.objectweb.asm.Label l) {
                tick(op, 0); super.visitJumpInsn(op, l);
            }
            @Override public void visitIntInsn(int op, int v) {
                tick(op, 0); super.visitIntInsn(op, v);
            }
            @Override public void visitMethodInsn(int op, String o, String n,
                    String d, boolean itf) {
                tick(op, o.equals(cfg.kType) && "<init>".equals(n) ? 1 : 0);
                super.visitMethodInsn(op, o, n, d, itf);
            }
            @Override public void visitVarInsn(int op, int var) {
                tick(op, 0);
                int sz = trail.size();
                if (!patched && op == ASTORE && sz >= 4
                        && trail.get(sz - 2)[0] == INVOKESPECIAL
                        && trail.get(sz - 2)[1] == 1
                        && trail.get(sz - 3)[0] == DUP
                        && trail.get(sz - 4)[0] == NEW) {
                    patched = true;
                    super.visitVarInsn(op, var + 16);
                    return;
                }
                super.visitVarInsn(op, var);
            }
        });
    }

    /** Inject a full 4-insn query sequence BEFORE the k-init NEW. */
    static byte[] siteBeforeAnchor(byte[] in, Weave.Config cfg) {
        return mutateInsn(in, cfg, mv -> new MethodVisitor(ASM9, mv) {
            boolean injected;
            @Override public void visitTypeInsn(int op, String t) {
                if (!injected && op == NEW && t.equals(cfg.kType)) {
                    injected = true;
                    super.visitVarInsn(ALOAD, 40);
                    super.visitVarInsn(ALOAD, 41);
                    super.visitInsn(ICONST_0);
                    super.visitMethodInsn(INVOKEVIRTUAL, cfg.kType,
                        cfg.queryName, cfg.queryDesc, false);
                }
                super.visitTypeInsn(op, t);
            }
        });
    }

    /** Patch the first append's j load to a different slot: the append no longer
     *  references the same j as its own query site. */
    static byte[] appendJSlot(byte[] in, Weave.Config cfg) {
        return mutateInsn(in, cfg, mv -> new MethodVisitor(ASM9, mv) {
            // 0 wait query, 1 saw query invoke, 2 saw jump,
            // 3 saw append aload k -> next aload j is patched
            int phase;
            @Override public void visitMethodInsn(int op, String o, String n,
                    String d, boolean itf) {
                if (phase == 0 && op == INVOKEVIRTUAL && o.equals(cfg.kType)
                        && d.equals(cfg.queryDesc))
                    phase = 1;
                super.visitMethodInsn(op, o, n, d, itf);
            }
            @Override public void visitJumpInsn(int op, org.objectweb.asm.Label l) {
                if (phase == 1) phase = 2;
                super.visitJumpInsn(op, l);
            }
            @Override public void visitVarInsn(int op, int var) {
                if (phase == 2) phase = 3;
                else if (phase == 3) {
                    phase = 4;
                    super.visitVarInsn(op, var + 16);
                    return;
                }
                super.visitVarInsn(op, var);
            }
        });
    }

    /** Drop the DUP inside the NEW/DUP/<init>/ASTORE k-init sequence — the
     *  anchor pattern no longer matches anywhere. */
    static byte[] noAnchor(byte[] in, Weave.Config cfg) {
        return mutateInsn(in, cfg, mv -> new MethodVisitor(ASM9, mv) {
            boolean afterNewK;
            @Override public void visitTypeInsn(int op, String t) {
                afterNewK = op == NEW && t.equals(cfg.kType);
                super.visitTypeInsn(op, t);
            }
            @Override public void visitInsn(int op) {
                if (afterNewK && op == DUP) {
                    afterNewK = false;
                    super.visitInsn(POP);
                    return;
                }
                afterNewK = false;
                super.visitInsn(op);
            }
            @Override public void visitMethodInsn(int op, String o, String n,
                    String d, boolean itf) {
                afterNewK = false;
                super.visitMethodInsn(op, o, n, d, itf);
            }
            @Override public void visitVarInsn(int op, int var) {
                afterNewK = false;
                super.visitVarInsn(op, var);
            }
        });
    }

    private interface MvFactory { MethodVisitor wrap(MethodVisitor mv); }

    private static byte[] mutateInsn(byte[] in, Weave.Config cfg, MvFactory f) {
        ClassWriter cw = new ClassWriter(0);
        new ClassReader(in).accept(new ClassVisitor(ASM9, cw) {
            @Override public MethodVisitor visitMethod(int acc, String name, String desc,
                    String sig, String[] exc) {
                MethodVisitor mv = super.visitMethod(acc, name, desc, sig, exc);
                if (!(name.equals(cfg.methodName) && desc.equals(cfg.methodDesc)) || mv == null)
                    return mv;
                return f.wrap(mv);
            }
        }, 0);
        return cw.toByteArray();
    }
}
