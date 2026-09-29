package dev.turboism.validation.kmembership;

import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Core-ASM (no asm-tree dependency) weaver for the OWN fixture target only.
 * Official classes are never read, loaded, or executed.
 *
 * Two passes over b() (REAL-insn index = excludes label/line/frame events):
 *  pass 1  shape pin + record: k-init ASTORE anchor, exactly three 4-insn query
 *          sequences [ALOAD k, ALOAD jn, ICONST_0, INVOKEVIRTUAL EdgeK.a(LEdgeJ;Z)Z],
 *          three append sites, single ARETURN, and no branch target inside a
 *          replaced range.
 *  pass 2  emit entry-init right after the anchor; replace each 4-insn sequence
 *          wholesale with the gated block (empty-stack start, per frozen CFG).
 *
 * Shape reject → weave() returns the ORIGINAL byte array unchanged.
 */
public final class Weave {
    private Weave() {}

    static final String HELPER = "dev/turboism/validation/kmembership/Helper";
    static final String KTYPE  = "dev/turboism/validation/kmembership/Fixture$EdgeK";
    static final String JTYPE  = "dev/turboism/validation/kmembership/Fixture$EdgeJ";
    static final String QUERY_DESC  = "(L" + JTYPE + ";Z)Z";
    static final String APPEND_DESC = "(L" + JTYPE + ";)Z";
    static final String HELPER_QUERY_SIG =
        "(L" + KTYPE + ";L" + JTYPE + ";ZLjava/lang/Object;)Z";

    public static final class ShapeReject extends RuntimeException {
        public ShapeReject(String m) { super(m); }
    }

    /** Shape analysis record for method b(). */
    static final class Plan {
        int anchorIndex = -1;               // real-insn index of ASTORE k (entry anchor)
        int maxLocals;
        final List<int[]> sites = new ArrayList<>();  // [startIndex, kSlot, jSlot]
        final java.util.Set<Integer> branchTargets = new java.util.HashSet<>();
        int areturns, queries, appends;
        boolean iteratorInitSeen;
    }

    /** Returns woven bytes, or the ORIGINAL array unchanged on shape reject. */
    public static byte[] weave(byte[] in) {
        Plan p;
        try { p = analyze(in); }
        catch (ShapeReject re) { return in; }
        return emit(in, p);
    }

    // ------------------------------------------------------------------ pass 1
    static Plan analyze(byte[] in) {
        Plan p = new Plan();
        new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int acc, String name,
                    String desc, String sig, String[] exc) {
                if (!(name.equals("b") && desc.equals("()L" + KTYPE + ";"))) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    int idx = -1;                 // real-insn index
                    final List<int[]> trail = new ArrayList<>();  // recent insns
                    final java.util.Map<Label, Integer> labelPos = new java.util.HashMap<>();
                    final java.util.List<Label> jumpTargets = new ArrayList<>();

                    void trail(int opcode, int var, String owner, String name, String desc) {
                        idx++;
                        int flags = (owner != null && owner.equals(KTYPE)
                                && "<init>".equals(name)) ? 1 : 0;
                        trail.add(new int[] { idx, opcode, var, flags });
                        if (trail.size() > 8) trail.remove(0);
                        if (opcode == Opcodes.ARETURN) p.areturns++;
                    }
                    @Override public void visitLabel(Label l) { labelPos.put(l, idx + 1); }
                    @Override public void visitJumpInsn(int op, Label l) {
                        jumpTargets.add(l);
                        trail(op, -1, null, null, null);
                    }
                    @Override public void visitVarInsn(int op, int var) {
                        trail(op, var, null, null, null);
                        // entry anchor: NEW EdgeK; DUP; INVOKESPECIAL <init>; ASTORE
                        if (op == Opcodes.ASTORE && p.anchorIndex < 0) {
                            int sz = trail.size();
                            int[] init = sz >= 2 ? trail.get(sz - 2) : null;
                            int[] dup  = sz >= 3 ? trail.get(sz - 3) : null;
                            int[] nw   = sz >= 4 ? trail.get(sz - 4) : null;
                            if (init != null && dup != null && nw != null
                                    && nw[1] == Opcodes.NEW && dup[1] == Opcodes.DUP
                                    && init[1] == Opcodes.INVOKESPECIAL
                                    && init[3] == 1 /* owner=EdgeK, name=<init> */)
                                p.anchorIndex = idx;
                        }
                    }
                    @Override public void visitInsn(int op) { trail(op, -1, null, null, null); }
                    @Override public void visitTypeInsn(int op, String t) {
                        trail(op, -1, null, null, null);
                    }
                    @Override public void visitFieldInsn(int op, String o, String n, String d) {
                        trail(op, -1, o, n, d);
                        if (o.equals(KTYPE) && op == Opcodes.GETFIELD) {}
                    }
                    @Override public void visitMethodInsn(int op, String o, String n,
                            String d, boolean itf) {
                        trail(op, -1, o, n, d);
                        if (op == Opcodes.INVOKEVIRTUAL && o.equals(KTYPE)) {
                            if (d.equals(QUERY_DESC)) {
                                p.queries++;
                                // verify 3-insn prefix: ALOAD k, ALOAD jn, ICONST_0
                                int sz = trail.size();
                                int[] z  = sz >= 2 ? trail.get(sz - 2) : null;
                                int[] aj = sz >= 3 ? trail.get(sz - 3) : null;
                                int[] ak = sz >= 4 ? trail.get(sz - 4) : null;
                                if (z == null || z[1] != Opcodes.ICONST_0)
                                    throw new ShapeReject("query#" + p.queries + " Z not iconst_0");
                                if (aj == null || aj[1] != Opcodes.ALOAD)
                                    throw new ShapeReject("query#" + p.queries + " missing aload j");
                                if (ak == null || ak[1] != Opcodes.ALOAD)
                                    throw new ShapeReject("query#" + p.queries + " missing aload k");
                                p.sites.add(new int[] { ak[0], ak[2], aj[2] });
                            }
                            if (d.equals(APPEND_DESC)) p.appends++;
                        }
                        if (n.equals("iterator") && o.contains("LinkedHashSet"))
                            p.iteratorInitSeen = true;
                    }
                    @Override public void visitMaxs(int ms, int ml) { p.maxLocals = ml; }
                    @Override public void visitEnd() {
                        // no branch may target inside a replaced 4-insn range
                        for (Label t : jumpTargets) {
                            Integer pos = labelPos.get(t);
                            if (pos == null) continue;
                            for (int[] site : p.sites)
                                if (pos > site[0] && pos < site[0] + 4)
                                    throw new ShapeReject("branch target inside site @" + site[0]);
                        }
                    }
                    @Override public void visitLdcInsn(Object v) { trail(Opcodes.LDC, -1, null, null, null); }
                    @Override public void visitIincInsn(int v, int i) { trail(Opcodes.IINC, -1, null, null, null); }
                    @Override public void visitIntInsn(int op, int v) { trail(op, -1, null, null, null); }
                    @Override public void visitTableSwitchInsn(int a, int b2, Label d, Label... l) { trail(Opcodes.TABLESWITCH, -1, null, null, null); }
                    @Override public void visitLookupSwitchInsn(Label d, int[] k, Label[] l) { trail(Opcodes.LOOKUPSWITCH, -1, null, null, null); }
                    @Override public void visitMultiANewArrayInsn(String d2, int n) { trail(Opcodes.MULTIANEWARRAY, -1, null, null, null); }

                };
            }
        }, 0);

        if (p.queries != 3) throw new ShapeReject("queries=" + p.queries);
        if (p.appends != 3) throw new ShapeReject("appends=" + p.appends);
        if (p.areturns != 1) throw new ShapeReject("areturns=" + p.areturns);
        if (!p.iteratorInitSeen) throw new ShapeReject("no iterator init");
        if (p.sites.size() != 3) throw new ShapeReject("sites=" + p.sites.size());
        return p;
    }

    // ------------------------------------------------------------------ pass 2
    static byte[] emit(byte[] in, Plan p) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String c) {
                return "java/lang/Object";
            }
        };
        new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override public MethodVisitor visitMethod(int acc, String name,
                    String desc, String sig, String[] exc) {
                MethodVisitor mv = super.visitMethod(acc, name, desc, sig, exc);
                if (!(name.equals("b") && desc.equals("()L" + KTYPE + ";"))
                        || mv == null) return mv;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    int idx = -1;
                    int boxSlot = p.maxLocals;
                    int skipUntil = -1;
                    boolean entryDone = false;

                    void nextInsn() { idx++; }

                    int[] siteAt(int i) {
                        for (int[] s : p.sites) if (i == s[0]) return s;
                        return null;
                    }

                    @Override public void visitCode() {
                        super.visitCode();
                    }

                    /** Emit entry-init block right after the anchor ASTORE. */
                    void emitEntry() {
                        Label eTry = new Label(), eEnd = new Label(),
                              eCatch = new Label(), ePost = new Label();
                        super.visitInsn(Opcodes.ACONST_NULL);
                        super.visitVarInsn(Opcodes.ASTORE, boxSlot);
                        super.visitTryCatchBlock(eTry, eEnd, eCatch, "java/lang/LinkageError");
                        super.visitLabel(eTry);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER,
                            "newBox", "()Ljava/lang/Object;", false);
                        super.visitLabel(eEnd);
                        super.visitVarInsn(Opcodes.ASTORE, boxSlot);
                        super.visitJumpInsn(Opcodes.GOTO, ePost);
                        super.visitLabel(eCatch);
                        super.visitInsn(Opcodes.POP);
                        super.visitInsn(Opcodes.ACONST_NULL);
                        super.visitVarInsn(Opcodes.ASTORE, boxSlot);
                        super.visitLabel(ePost);
                    }

                    /** Emit gated query block (empty-stack entry). */
                    void emitSite(int kSlot, int jSlot) {
                        Label qOrig = new Label(), qTry = new Label(),
                              qEnd = new Label(), qCatch = new Label(), qJoin = new Label();
                        super.visitTryCatchBlock(qTry, qEnd, qCatch, "java/lang/LinkageError");
                        super.visitVarInsn(Opcodes.ALOAD, boxSlot);
                        super.visitJumpInsn(Opcodes.IFNULL, qOrig);
                        super.visitVarInsn(Opcodes.ALOAD, jSlot);
                        super.visitJumpInsn(Opcodes.IFNULL, qOrig);
                        super.visitLabel(qTry);
                        super.visitVarInsn(Opcodes.ALOAD, kSlot);
                        super.visitVarInsn(Opcodes.ALOAD, jSlot);
                        super.visitInsn(Opcodes.ICONST_0);
                        super.visitVarInsn(Opcodes.ALOAD, boxSlot);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER,
                            "query", HELPER_QUERY_SIG, false);
                        super.visitLabel(qEnd);
                        super.visitJumpInsn(Opcodes.GOTO, qJoin);
                        super.visitLabel(qCatch);
                        super.visitInsn(Opcodes.POP);
                        super.visitInsn(Opcodes.ACONST_NULL);
                        super.visitVarInsn(Opcodes.ASTORE, boxSlot);
                        super.visitLabel(qOrig);
                        super.visitVarInsn(Opcodes.ALOAD, kSlot);
                        super.visitVarInsn(Opcodes.ALOAD, jSlot);
                        super.visitInsn(Opcodes.ICONST_0);
                        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, KTYPE, "a", QUERY_DESC, false);
                        super.visitLabel(qJoin);
                    }

                    /** Gate real-insn emission; called before delegating any insn visit. */
                    boolean intercept() {
                        nextInsn();
                        int[] site = siteAt(idx);
                        if (site != null) {
                            emitSite(site[1], site[2]);
                            skipUntil = idx + 3;
                        }
                        return idx <= skipUntil;   // true = suppress original emit
                    }

                    @Override public void visitInsn(int op) {
                        if (intercept()) return;
                        super.visitInsn(op);
                    }
                    @Override public void visitVarInsn(int op, int var) {
                        if (intercept()) return;
                        super.visitVarInsn(op, var);
                        // entry anchor: the ASTORE right after new/dup/<init>
                        if (!entryDone && op == Opcodes.ASTORE) {
                            if (idx == p.anchorIndex) { emitEntry(); entryDone = true; }
                        }
                    }
                    @Override public void visitMethodInsn(int op, String o, String n,
                            String d, boolean itf) {
                        if (intercept()) return;
                        super.visitMethodInsn(op, o, n, d, itf);
                    }
                    @Override public void visitJumpInsn(int op, Label l) {
                        if (intercept()) return;
                        super.visitJumpInsn(op, l);
                    }
                    @Override public void visitTypeInsn(int op, String t) {
                        if (intercept()) return;
                        super.visitTypeInsn(op, t);
                    }
                    @Override public void visitFieldInsn(int op, String o, String n, String d) {
                        if (intercept()) return;
                        super.visitFieldInsn(op, o, n, d);
                    }
                    @Override public void visitIntInsn(int op, int v) {
                        if (intercept()) return;
                        super.visitIntInsn(op, v);
                    }
                    @Override public void visitLdcInsn(Object v) {
                        if (intercept()) return;
                        super.visitLdcInsn(v);
                    }
                    @Override public void visitIincInsn(int v, int i) {
                        if (intercept()) return;
                        super.visitIincInsn(v, i);
                    }
                    @Override public void visitTableSwitchInsn(int a, int b2, Label d, Label... l) {
                        if (intercept()) return;
                        super.visitTableSwitchInsn(a, b2, d, l);
                    }
                    @Override public void visitLookupSwitchInsn(Label d, int[] k, Label[] l) {
                        if (intercept()) return;
                        super.visitLookupSwitchInsn(d, k, l);
                    }
                    @Override public void visitMultiANewArrayInsn(String d2, int n) {
                        if (intercept()) return;
                        super.visitMultiANewArrayInsn(d2, n);
                    }

                };
            }
        }, 0);
        return cw.toByteArray();
    }
}
