package dev.turboism.validation.tlindex;

import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Core-ASM weaver for T029-TLINDEX. Four surgical transforms on the target
 * TriangleList shape (official 5.3.03, or the own-fixture analog under a
 * shadow profile). Per-method shape gates; ANY reject returns the original
 * byte array unchanged (weaveChecked exposes WHICH gate fired).
 *
 * Transforms (all inside methods; class shape otherwise untouched):
 *
 *  a(Ll;)Z:  before its single `invokevirtual LinkedHashSet.add(Object)Z`
 *    emit l.a/l.b/l.c + TriPoint.getIndex extraction (9 insns), then emit
 *    `invokestatic Bridge.add(LinkedHashSet,Object,int,int,int)Z` in place of
 *    the add. Official checkNotNull + log preamble stays untouched.
 *
 *  b(Ll;)Z:  swap its single `invokevirtual LinkedHashSet.remove(Object)Z`
 *    for `invokestatic Bridge.remove(LinkedHashSet,Object)Z`. Identical
 *    stack shape - pure instruction substitution.
 *
 *  c()V:     swap `invokevirtual LinkedHashSet.clear()V` for
 *    `invokestatic Bridge.clear(LinkedHashSet)V`. Same stack shape.
 *
 *  a(Lj;)Ljava/util/List;:  AFTER the checkNotNullParameter prologue
 *    (aload_1; ldc ""; invokestatic Intrinsics.checkNotNullParameter), emit
 *    `r = Bridge.tryQuery(b, e.a().getIndex(), e.b().getIndex());
 *         dup; ifnull ORIG; areturn; ORIG: pop;` — the ENTIRE original scan
 *    body remains as the fallback path. Any index anomaly -> null ->
 *    original O(T) scan answers, so correctness is structural.
 *
 * Rejects: target method missing; prologue anchor not the leading 3-insn
 * sequence (a(j)); LinkedHashSet.add/remove/clear invoke-site count != 1
 * in the respective methods.
 *
 * COMPUTE_FRAMES recomputes frames on the two methods that gain control
 * flow / shift bci (a(j) prepend, a(l) insert); getCommonSuperClass falls
 * back to java/lang/Object like CaptureWeave.
 *
 * Official classes are never read, loaded, or executed by this codebase.
 */
public final class TliWeave {
    private TliWeave() {}

    /** Owner/internal-name binding + pinned shape constants. */
    public static final class Config {
        /** Internal name of the edge type, e.g. {@code .../triangulation/j}. */
        public final String jInternal;
        /** Internal name of the triangle type ({@code l}). */
        public final String lInternal;
        /** Internal name of the vertex type ({@code TriPoint}). */
        public final String triPointInternal;
        /** Internal name of the index helper ({@code Bridge}). */
        public final String bridgeInternal;
        /** Owner of the LinkedHashSet field inside the target class —
         *  needed for the getfield in the a(j) prepend. */
        public final String ownerInternal;
        /** Field name of the LinkedHashSet (official: {@code b}). */
        public final String setFieldName;

        public Config(String owner, String j, String l, String triPoint,
                String bridge, String setField) {
            ownerInternal = owner; jInternal = j; lInternal = l;
            triPointInternal = triPoint; bridgeInternal = bridge;
            setFieldName = setField;
        }

        String lDesc() { return "L" + lInternal + ";"; }
        String jDesc() { return "L" + jInternal + ";"; }
        String tpDesc() { return "L" + triPointInternal + ";"; }
    }

    public static final class ShapeReject extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public ShapeReject(String m) { super(m); }
    }

    public static final class Plan {
        public boolean aLFound, bLFound, cFound, aJFound;
        public int aL_addSites, bL_removeSites, c_clearSites, aJ_prologues;
    }

    public static final class Result {
        public final byte[] bytes;
        public final String rejectReason;
        public final Plan plan;
        Result(byte[] b, String r, Plan p) { bytes = b; rejectReason = r; plan = p; }
    }

    static final String SET = "java/util/LinkedHashSet";
    static final String OBJ = "Ljava/lang/Object;";
    static final String INTRINSICS =
        "kotlin/jvm/internal/Intrinsics";
    static final String NNP = "checkNotNullParameter";

    public static Result weaveChecked(Config cfg, byte[] in) {
        Plan p = new Plan();
        try { p = collect(cfg, in); gate(p); }
        catch (ShapeReject re) { return new Result(in, re.getMessage(), p); }
        return new Result(emit(cfg, in), null, p);
    }

    public static byte[] weave(Config cfg, byte[] in) {
        return weaveChecked(cfg, in).bytes;
    }

    // ------------------------------------------------------------- collect
    static Plan collect(Config cfg, byte[] in) {
        Plan p = new Plan();
        String aLDesc = "(" + cfg.lDesc() + ")Z";
        String bLDesc = aLDesc;
        String aJDesc = "(" + cfg.jDesc() + ")Ljava/util/List;";
        new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int acc, String name,
                    String desc, String sig, String[] exc) {
                if (name.equals("a") && desc.equals(aLDesc)) {
                    p.aLFound = true;
                    return countInvoke(p, () -> p.aL_addSites++, SET, "add",
                            "(" + OBJ + ")Z");
                }
                if (name.equals("b") && desc.equals(bLDesc)) {
                    p.bLFound = true;
                    return countInvoke(p, () -> p.bL_removeSites++, SET, "remove",
                            "(" + OBJ + ")Z");
                }
                if (name.equals("c") && desc.equals("()V")) {
                    p.cFound = true;
                    return countInvoke(p, () -> p.c_clearSites++, SET, "clear", "()V");
                }
                if (name.equals("a") && desc.equals(aJDesc)) {
                    p.aJFound = true;
                    return new MethodVisitor(Opcodes.ASM9) {
                        int idx = -1;
                        final int[] trail = new int[3];   // kinds of last 3 REAL insns
                        void shift(int k) { idx++; trail[0]=trail[1]; trail[1]=trail[2]; trail[2]=k; }
                        void anyInsn() { shift(0); }
                        @Override public void visitInsn(int op) { anyInsn(); }
                        @Override public void visitIntInsn(int op, int v) { anyInsn(); }
                        @Override public void visitVarInsn(int op, int v) {
                            shift(op == Opcodes.ALOAD && v == 1 ? 1 : 0);
                        }
                        @Override public void visitLdcInsn(Object v) {
                            shift("".equals(v) ? 2 : 0);
                        }
                        @Override public void visitFieldInsn(int op, String o, String n, String d) { anyInsn(); }
                        @Override public void visitJumpInsn(int op, Label l) { anyInsn(); }
                        @Override public void visitIincInsn(int v, int i) { anyInsn(); }
                        @Override public void visitTypeInsn(int op, String t) { anyInsn(); }
                        @Override public void visitTableSwitchInsn(int a, int b2, Label d, Label... l) { anyInsn(); }
                        @Override public void visitLookupSwitchInsn(Label d, int[] k, Label[] l) { anyInsn(); }
                        @Override public void visitMultiANewArrayInsn(String d, int n) { anyInsn(); }
                        @Override public void visitInvokeDynamicInsn(String n, String d, org.objectweb.asm.Handle h, Object... a) { anyInsn(); }
                        @Override public void visitMethodInsn(int op, String o, String n,
                                String d, boolean itf) {
                            shift(op == Opcodes.INVOKESTATIC && o.equals(INTRINSICS)
                                    && n.equals(NNP) && d.equals("(" + OBJ + "Ljava/lang/String;)V") ? 3 : 0);
                            if (trail[0] == 1 && trail[1] == 2 && trail[2] == 3
                                    && idx <= 3) p.aJ_prologues++;
                        }
                    };
                }
                return null;
            }
            private MethodVisitor countInvoke(Plan p, Runnable hit,
                    String owner, String m, String d) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int op, String o, String n,
                            String dd, boolean itf) {
                        if (op == Opcodes.INVOKEVIRTUAL && o.equals(owner)
                                && n.equals(m) && dd.equals(d)) hit.run();
                    }
                };
            }
        }, 0);
        return p;
    }

    static void gate(Plan p) {
        if (!p.aLFound) throw new ShapeReject("method-not-found a(l)");
        if (!p.bLFound) throw new ShapeReject("method-not-found b(l)");
        if (!p.cFound)  throw new ShapeReject("method-not-found c()");
        if (!p.aJFound) throw new ShapeReject("method-not-found a(j)");
        if (p.aL_addSites != 1)
            throw new ShapeReject("a(l) LinkedHashSet.add sites=" + p.aL_addSites);
        if (p.bL_removeSites != 1)
            throw new ShapeReject("b(l) LinkedHashSet.remove sites=" + p.bL_removeSites);
        if (p.c_clearSites != 1)
            throw new ShapeReject("c() LinkedHashSet.clear sites=" + p.c_clearSites);
        if (p.aJ_prologues != 1)
            throw new ShapeReject("a(j) leading checkNotNullParameter prologues="
                + p.aJ_prologues);
    }

    // ------------------------------------------------------------- emit
    static byte[] emit(Config cfg, byte[] in) {
        String aLDesc = "(" + cfg.lDesc() + ")Z";
        String bLDesc = aLDesc;
        String aJDesc = "(" + cfg.jDesc() + ")Ljava/util/List;";
        String setGet = "Ljava/util/LinkedHashSet;";

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String c) {
                return "java/lang/Object";
            }
        };
        new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override public MethodVisitor visitMethod(int acc, String name,
                    String desc, String sig, String[] exc) {
                MethodVisitor mv = super.visitMethod(acc, name, desc, sig, exc);
                if (mv == null) return null;

                if (name.equals("a") && desc.equals(aLDesc)) {
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override public void visitMethodInsn(int op, String o, String n,
                                String d, boolean itf) {
                            if (op == Opcodes.INVOKEVIRTUAL && o.equals(SET)
                                    && n.equals("add") && d.equals("(" + OBJ + ")Z")) {
                                // stack [set, l] -> push ia,ib,ic, then static call
                                emitVertIdx(this, cfg, 1);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    cfg.bridgeInternal, "add",
                                    "(Ljava/util/LinkedHashSet;" + OBJ + "III)Z", false);
                                return;
                            }
                            super.visitMethodInsn(op, o, n, d, itf);
                        }
                    };
                }

                if (name.equals("b") && desc.equals(bLDesc)) {
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override public void visitMethodInsn(int op, String o, String n,
                                String d, boolean itf) {
                            if (op == Opcodes.INVOKEVIRTUAL && o.equals(SET)
                                    && n.equals("remove") && d.equals("(" + OBJ + ")Z")) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    cfg.bridgeInternal, "remove",
                                    "(Ljava/util/LinkedHashSet;" + OBJ + ")Z", false);
                                return;
                            }
                            super.visitMethodInsn(op, o, n, d, itf);
                        }
                    };
                }

                if (name.equals("c") && desc.equals("()V")) {
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override public void visitMethodInsn(int op, String o, String n,
                                String d, boolean itf) {
                            if (op == Opcodes.INVOKEVIRTUAL && o.equals(SET)
                                    && n.equals("clear") && d.equals("()V")) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    cfg.bridgeInternal, "clear",
                                    "(Ljava/util/LinkedHashSet;)V", false);
                                return;
                            }
                            super.visitMethodInsn(op, o, n, d, itf);
                        }
                    };
                }

                if (name.equals("a") && desc.equals(aJDesc)) {
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        int idx = -1;
                        final int[] trail = new int[3];
                        boolean injected;
                        void shift(int k) { idx++; trail[0]=trail[1]; trail[1]=trail[2]; trail[2]=k; }
                        void anyInsn() { shift(0); }
                        @Override public void visitInsn(int op) { anyInsn(); super.visitInsn(op); }
                        @Override public void visitIntInsn(int op, int v) { anyInsn(); super.visitIntInsn(op, v); }
                        @Override public void visitVarInsn(int op, int v) {
                            shift(op == Opcodes.ALOAD && v == 1 ? 1 : 0);
                            super.visitVarInsn(op, v);
                        }
                        @Override public void visitLdcInsn(Object v) {
                            shift("".equals(v) ? 2 : 0);
                            super.visitLdcInsn(v);
                        }
                        @Override public void visitFieldInsn(int op, String o, String n, String d) { anyInsn(); super.visitFieldInsn(op, o, n, d); }
                        @Override public void visitJumpInsn(int op, Label l) { anyInsn(); super.visitJumpInsn(op, l); }
                        @Override public void visitIincInsn(int v, int i) { anyInsn(); super.visitIincInsn(v, i); }
                        @Override public void visitTypeInsn(int op, String t) { anyInsn(); super.visitTypeInsn(op, t); }
                        @Override public void visitTableSwitchInsn(int a, int b2, Label d, Label... l) { anyInsn(); super.visitTableSwitchInsn(a, b2, d, l); }
                        @Override public void visitLookupSwitchInsn(Label d, int[] k, Label[] l) { anyInsn(); super.visitLookupSwitchInsn(d, k, l); }
                        @Override public void visitMultiANewArrayInsn(String d, int n) { anyInsn(); super.visitMultiANewArrayInsn(d, n); }
                        @Override public void visitInvokeDynamicInsn(String n, String d, org.objectweb.asm.Handle h, Object... a) { anyInsn(); super.visitInvokeDynamicInsn(n, d, h, a); }
                        @Override public void visitMethodInsn(int op, String o, String n,
                                String d, boolean itf) {
                            shift(op == Opcodes.INVOKESTATIC && o.equals(INTRINSICS)
                                    && n.equals(NNP) && d.equals("(" + OBJ + "Ljava/lang/String;)V") ? 3 : 0);
                            super.visitMethodInsn(op, o, n, d, itf);
                            if (!injected && trail[0] == 1 && trail[1] == 2
                                    && trail[2] == 3 && idx <= 3) {
                                injected = true;
                                injectTryQuery(mv, cfg, setGet);
                            }
                        }
                    };
                }
                return mv;
            }
        }, 0);
        return cw.toByteArray();
    }

    /** Emit `aload_v; l.<getter>(); TriPoint.getIndex()` x3 for the vertex
     *  getters a/b/c (the arg at local slot v). */
    private static void emitVertIdx(MethodVisitor mv, Config cfg, int v) {
        String[] getters = {"a", "b", "c"};
        for (String g : getters) {
            mv.visitVarInsn(Opcodes.ALOAD, v);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, cfg.lInternal, g,
                "()" + cfg.tpDesc(), false);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, cfg.triPointInternal,
                "getIndex", "()I", false);
        }
    }

    /** Emit the tryQuery dispatch: stack empty -> pushes result, dup,
     *  ifnull->orig(pop), areturn. Linear; frames recomputed by writer. */
    private static void injectTryQuery(MethodVisitor mv, Config cfg, String setGet) {
        Label orig = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, cfg.ownerInternal, cfg.setFieldName, setGet);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, cfg.jInternal, "a",
            "()" + cfg.tpDesc(), false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, cfg.triPointInternal,
            "getIndex", "()I", false);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, cfg.jInternal, "b",
            "()" + cfg.tpDesc(), false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, cfg.triPointInternal,
            "getIndex", "()I", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, cfg.bridgeInternal, "tryQuery",
            "(Ljava/util/LinkedHashSet;II)Ljava/util/List;", false);
        mv.visitInsn(Opcodes.DUP);
        mv.visitJumpInsn(Opcodes.IFNULL, orig);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(orig);
        mv.visitInsn(Opcodes.POP);
    }
}
