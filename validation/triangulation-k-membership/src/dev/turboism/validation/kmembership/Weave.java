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
 * Core-ASM (no asm-tree dependency) weaver for the membership-query candidate.
 * Official classes are never read, loaded, or executed by this codebase.
 *
 * The transform is parameterized by {@link Config}: the own-fixture
 * configuration ({@link #FIXTURE}) is what the KWEAVE acceptance exercised;
 * T029-TRIAB reuses the same transform with a different Config — there is no
 * second implementation of the weaving logic.
 *
 * Two passes over the target method (REAL-insn index = excludes label/line/
 * frame events):
 *  pass 1  shape pin + record: k-init ASTORE anchor (index + slot), exactly
 *          three 4-insn query sequences
 *          [ALOAD k, ALOAD jn, ICONST_0, INVOKEVIRTUAL k.query(Lj;Z)Z]
 *          all after the anchor and all loading the SAME k slot as the anchor,
 *          three appends each pinned as
 *          [conditional jump at site+4, ALOAD k, ALOAD j, INVOKEVIRTUAL k.append(Lj;)Z]
 *          against their own site, single ARETURN, iterator init, and no
 *          branch target inside a replaced range.
 *  pass 2  emit entry-init right after the anchor; replace each 4-insn sequence
 *          wholesale with the gated block (empty-stack start, per frozen CFG).
 *
 * Shape reject → weave() returns the ORIGINAL byte array unchanged;
 * weaveChecked() additionally exposes WHICH shape check rejected.
 */
public final class Weave {
    private Weave() {}

    /**
     * Target shape + helper binding for one weaving variant. All owner /
     * descriptor / anchor-shape constants that the KWEAVE fixture hardcoded
     * live here, so the same transform code serves any verified target.
     */
    public static final class Config {
        /** Target method name, e.g. {@code b}. */
        public final String methodName;
        /** Target method descriptor, {@code ()Lk;}. */
        public final String methodDesc;
        /** Internal name of the edge-list type built inside the method (k). */
        public final String kType;
        /** Internal name of the edge type (j). */
        public final String jType;
        /** Descriptor of the no-arg k constructor used as the entry anchor. */
        public final String ctorDesc;
        /** Name of the membership query on k, e.g. {@code a}. */
        public final String queryName;
        /** Query descriptor, {@code (Lj;Z)Z}. */
        public final String queryDesc;
        /** Name of the raw append on k, e.g. {@code a}. */
        public final String appendName;
        /** Append descriptor, {@code (Lj;)Z}. */
        public final String appendDesc;
        /** Internal name of the iterated set type, {@code java/util/LinkedHashSet}. */
        public final String iteratorOwner;
        /** Iterator method name. */
        public final String iteratorName;
        /** Iterator method descriptor. */
        public final String iteratorDesc;
        /** Internal name of the woven-path helper class. */
        public final String helperInternal;
        /** Helper entry-init name/descriptor. */
        public final String helperNewBoxName;
        public final String helperNewBoxDesc;
        /** Helper query name; descriptor is {@code (Lk;Lj;ZLjava/lang/Object;)Z}. */
        public final String helperQueryName;
        public final String helperQueryDesc;

        /**
         * Full config; descriptors that are structurally fixed by the shape are
         * derived from the two type names rather than accepted independently.
         */
        public Config(String methodName, String kType, String jType,
                String queryName, String appendName, String helperInternal) {
            this.methodName = methodName;
            this.kType = kType;
            this.jType = jType;
            this.ctorDesc = "()V";
            this.queryName = queryName;
            this.queryDesc = "(L" + jType + ";Z)Z";
            this.appendName = appendName;
            this.appendDesc = "(L" + jType + ";)Z";
            this.iteratorOwner = "java/util/LinkedHashSet";
            this.iteratorName = "iterator";
            this.iteratorDesc = "()Ljava/util/Iterator;";
            this.helperInternal = helperInternal;
            this.helperNewBoxName = "newBox";
            this.helperNewBoxDesc = "()Ljava/lang/Object;";
            this.helperQueryName = "query";
            this.helperQueryDesc =
                "(L" + kType + ";L" + jType + ";ZLjava/lang/Object;)Z";
            this.methodDesc = "()L" + kType + ";";
        }
    }

    /** The KWEAVE own-fixture configuration — unchanged accepted semantics. */
    public static final Config FIXTURE = new Config(
        "b",
        "dev/turboism/validation/kmembership/Fixture$EdgeK",
        "dev/turboism/validation/kmembership/Fixture$EdgeJ",
        "a", "a",
        "dev/turboism/validation/kmembership/Helper");

    // Legacy fixture-shaped constants retained for the existing test code.
    static final String HELPER = FIXTURE.helperInternal;
    static final String KTYPE  = FIXTURE.kType;
    static final String JTYPE  = FIXTURE.jType;
    static final String QUERY_DESC  = FIXTURE.queryDesc;
    static final String APPEND_DESC = FIXTURE.appendDesc;
    static final String HELPER_QUERY_SIG = FIXTURE.helperQueryDesc;

    public static final class ShapeReject extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public ShapeReject(String m) { super(m); }
    }

    /** Shape analysis record for the target method. */
    static final class Plan {
        int anchorIndex = -1;               // real-insn index of ASTORE k (entry anchor)
        int anchorSlot = -1;                // local slot the anchor ASTORE wrote (the k slot)
        int maxLocals;
        final List<int[]> sites = new ArrayList<>();  // [startIndex, kSlot, jSlot]
        int areturns, queries, appends;
        boolean iteratorInitSeen;
    }

    /** Weave outcome: bytes (original array on reject) + observable reject reason. */
    public static final class Result {
        public final byte[] bytes;
        public final String rejectReason;   // null when the shape was accepted
        Result(byte[] b, String r) { bytes = b; rejectReason = r; }
    }

    /** Same contract as weave(cfg, in) plus WHICH shape check fired (null = woven). */
    public static Result weaveChecked(Config cfg, byte[] in) {
        Plan p;
        try { p = analyze(cfg, in); }
        catch (ShapeReject re) { return new Result(in, re.getMessage()); }
        return new Result(emit(cfg, in, p), null);
    }

    /** Returns woven bytes, or the ORIGINAL array unchanged on shape reject. */
    public static byte[] weave(Config cfg, byte[] in) {
        return weaveChecked(cfg, in).bytes;
    }

    /** Own-fixture overloads: identical semantics, Config defaults to FIXTURE. */
    public static Result weaveChecked(byte[] in) {
        return weaveChecked(FIXTURE, in);
    }

    public static byte[] weave(byte[] in) {
        return weaveChecked(FIXTURE, in).bytes;
    }

    // ------------------------------------------------------------------ pass 1
    static Plan analyze(Config cfg, byte[] in) {
        Plan p = new Plan();
        new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int acc, String name,
                    String desc, String sig, String[] exc) {
                if (!(name.equals(cfg.methodName) && desc.equals(cfg.methodDesc))) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    int idx = -1;                 // real-insn index
                    final List<int[]> trail = new ArrayList<>();  // recent insns
                    final java.util.Map<Label, Integer> labelPos = new java.util.HashMap<>();
                    final java.util.List<Label> jumpTargets = new ArrayList<>();

                    void trail(int opcode, int var, String owner, String name, String desc) {
                        idx++;
                        int flags = (owner != null && owner.equals(cfg.kType)
                                && "<init>".equals(name) && cfg.ctorDesc.equals(desc)) ? 1 : 0;
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
                        // entry anchor: NEW k; DUP; INVOKESPECIAL <init>; ASTORE
                        if (op == Opcodes.ASTORE && p.anchorIndex < 0) {
                            int sz = trail.size();
                            int[] init = sz >= 2 ? trail.get(sz - 2) : null;
                            int[] dup  = sz >= 3 ? trail.get(sz - 3) : null;
                            int[] nw   = sz >= 4 ? trail.get(sz - 4) : null;
                            if (init != null && dup != null && nw != null
                                    && nw[1] == Opcodes.NEW && dup[1] == Opcodes.DUP
                                    && init[1] == Opcodes.INVOKESPECIAL
                                    && init[3] == 1 /* owner=k, name=<init>, desc=ctorDesc */) {
                                p.anchorIndex = idx;
                                p.anchorSlot = var;
                            }
                        }
                    }
                    @Override public void visitInsn(int op) { trail(op, -1, null, null, null); }
                    @Override public void visitTypeInsn(int op, String t) {
                        trail(op, -1, null, null, null);
                    }
                    @Override public void visitFieldInsn(int op, String o, String n, String d) {
                        trail(op, -1, o, n, d);
                    }
                    @Override public void visitMethodInsn(int op, String o, String n,
                            String d, boolean itf) {
                        trail(op, -1, o, n, d);
                        if (op == Opcodes.INVOKEVIRTUAL && o.equals(cfg.kType)) {
                            if (d.equals(cfg.queryDesc) && n.equals(cfg.queryName)) {
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
                            if (d.equals(cfg.appendDesc) && n.equals(cfg.appendName)) {
                                p.appends++;
                                // append shape pin: at idx-3 a conditional jump that sits
                                // IMMEDIATELY after a recorded query site (site+4), then
                                // ALOAD k (that site's k slot), ALOAD j (that site's j slot).
                                int sz = trail.size();
                                int[] aj = sz >= 2 ? trail.get(sz - 2) : null;
                                int[] ak = sz >= 3 ? trail.get(sz - 3) : null;
                                int[] br = sz >= 4 ? trail.get(sz - 4) : null;
                                if (br == null || (br[1] != Opcodes.IFNE
                                        && br[1] != Opcodes.IFEQ))
                                    throw new ShapeReject("append#" + p.appends
                                        + " not gated by conditional jump");
                                int[] site = null;
                                for (int[] s : p.sites)
                                    if (s[0] + 4 == br[0]) { site = s; break; }
                                if (site == null)
                                    throw new ShapeReject("append#" + p.appends
                                        + " jump not adjacent to pinned query site");
                                if (ak == null || ak[1] != Opcodes.ALOAD
                                        || ak[2] != site[1])
                                    throw new ShapeReject("append#" + p.appends
                                        + " k slot does not match its query site");
                                if (aj == null || aj[1] != Opcodes.ALOAD
                                        || aj[2] != site[2])
                                    throw new ShapeReject("append#" + p.appends
                                        + " j slot does not match its query site");
                            }
                        }
                        if (o.equals(cfg.iteratorOwner) && n.equals(cfg.iteratorName)
                                && d.equals(cfg.iteratorDesc))
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

        if (p.anchorIndex < 0) throw new ShapeReject("no k-init anchor");
        for (int[] s : p.sites) {
            if (s[0] < p.anchorIndex)
                throw new ShapeReject("site@" + s[0] + " before anchor@" + p.anchorIndex);
            if (s[1] != p.anchorSlot)
                throw new ShapeReject("site@" + s[0] + " k slot " + s[1]
                    + " != anchor slot " + p.anchorSlot);
        }
        if (p.queries != 3) throw new ShapeReject("queries=" + p.queries);
        if (p.appends != 3) throw new ShapeReject("appends=" + p.appends);
        if (p.areturns != 1) throw new ShapeReject("areturns=" + p.areturns);
        if (!p.iteratorInitSeen) throw new ShapeReject("no iterator init");
        if (p.sites.size() != 3) throw new ShapeReject("sites=" + p.sites.size());
        return p;
    }

    // ------------------------------------------------------------------ pass 2
    static byte[] emit(Config cfg, byte[] in, Plan p) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String c) {
                return "java/lang/Object";
            }
        };
        new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override public MethodVisitor visitMethod(int acc, String name,
                    String desc, String sig, String[] exc) {
                MethodVisitor mv = super.visitMethod(acc, name, desc, sig, exc);
                if (!(name.equals(cfg.methodName) && desc.equals(cfg.methodDesc))
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

                    /** Emit entry-init block right after the anchor ASTORE. */
                    void emitEntry() {
                        Label eTry = new Label(), eEnd = new Label(),
                              eCatch = new Label(), ePost = new Label();
                        super.visitInsn(Opcodes.ACONST_NULL);
                        super.visitVarInsn(Opcodes.ASTORE, boxSlot);
                        super.visitTryCatchBlock(eTry, eEnd, eCatch, "java/lang/LinkageError");
                        super.visitLabel(eTry);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, cfg.helperInternal,
                            cfg.helperNewBoxName, cfg.helperNewBoxDesc, false);
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
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, cfg.helperInternal,
                            cfg.helperQueryName, cfg.helperQueryDesc, false);
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
                        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, cfg.kType,
                            cfg.queryName, cfg.queryDesc, false);
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
