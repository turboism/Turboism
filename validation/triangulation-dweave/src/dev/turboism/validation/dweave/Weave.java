package dev.turboism.validation.dweave;

import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Core-ASM (no asm-tree dependency) weaver for the T029-DWEAVE candidate:
 * single-allocation-site substitution.
 *
 * Inside the target method ({@code h.c()V} on the official class, {@code
 * OwnWindow.c()V} on the own fixture) exactly one sequence
 *
 *   NEW java/util/ArrayList ; DUP ; INVOKESPECIAL ArrayList.<init>()V ; ASTORE s
 *
 * must exist with {@code s == Config.astoreSlot} (the pinned Phase-3 matchList
 * slot — 7 on the reviewed 5.3.03 bytes). The transform rewrites exactly two
 * instruction operands: NEW's type and the INVOKESPECIAL owner become
 * {@code Config.matchListInternal}. DUP, ASTORE and every other instruction
 * pass through untouched, so the instruction stream length, branch targets,
 * stack shapes and frames are unchanged; {@code MatchList IS-A ArrayList}
 * keeps the original frame entries and every downstream use (invokevirtual
 * ArrayList.contains/add, checkcast Collection, ArrayList-typed call args)
 * verifiably valid.
 *
 * The official c()V contains a SECOND identical-pattern site (bci 523,
 * astore 8 — the Phase-4 list). Uniqueness of the weave target therefore
 * pins BOTH dimensions: exactly one pattern site on the configured slot AND
 * the configured total site count (Config.expectedTotalSites). Anything else
 * rejects.
 *
 * Passes (REAL-insn index = excludes label/line/frame events):
 *  pass 1  {@link #collect}: find every 4-insn pattern site (real-insn index
 *          of the NEW, ASTORE slot) and apply the shape gate.
 *  pass 2  {@link #emit}: swap the two operands at the recorded indices.
 *
 * Shape reject → weave() returns the ORIGINAL byte array unchanged;
 * weaveChecked() additionally exposes WHICH check rejected plus the
 * collected site inventory.
 *
 * Official classes are never read, loaded, or executed by this codebase.
 */
public final class Weave {
    private Weave() {}

    /**
     * Target shape + helper binding for one weaving variant. The own-fixture
     * configuration is what the offline acceptance exercises; a host leg would
     * reuse the same transform with the official constants — there is no
     * second implementation of the weaving logic.
     */
    public static final class Config {
        /** Target method name, e.g. {@code c}. */
        public final String methodName;
        /** Target method descriptor, {@code ()V}. */
        public final String methodDesc;
        /** Internal name of the list type allocated at the site. */
        public final String listType;
        /** Descriptor of the list's no-arg constructor, {@code ()V}. */
        public final String ctorDesc;
        /** Pinned ASTORE slot of the Phase-3 site (official: 7). -1 = pin
         *  uniqueness of the pattern itself (exactly one site overall). */
        public final int astoreSlot;
        /** Expected total count of full 4-insn pattern sites in the method
         *  (any slot). -1 disables the pin. Official c()V: 2 (slots 7 and 8). */
        public final int expectedTotalSites;
        /** Internal name of the MatchList helper substituted at the site. */
        public final String matchListInternal;

        public Config(String methodName, String methodDesc, String listType,
                String ctorDesc, int astoreSlot, int expectedTotalSites,
                String matchListInternal) {
            this.methodName = methodName;
            this.methodDesc = methodDesc;
            this.listType = listType;
            this.ctorDesc = ctorDesc;
            this.astoreSlot = astoreSlot;
            this.expectedTotalSites = expectedTotalSites;
            this.matchListInternal = matchListInternal;
        }
    }

    public static final class ShapeReject extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public ShapeReject(String m) { super(m); }
    }

    /** One collected pattern site: real-insn indices of NEW and the
     *  INVOKESPECIAL <init>, plus the ASTORE slot. */
    public static final class Site {
        public final int newIndex;
        public final int initIndex;
        public final int astoreIndex;
        public final int astoreSlot;
        Site(int n, int i, int a, int s) {
            newIndex = n; initIndex = i; astoreIndex = a; astoreSlot = s;
        }
        @Override public String toString() {
            return "new@" + newIndex + "/init@" + initIndex
                + "/astore@" + astoreIndex + ":slot" + astoreSlot;
        }
    }

    /** Shape analysis record for the target method. */
    public static final class Plan {
        public boolean methodFound;
        /** Every full-pattern site, in code order. */
        public final List<Site> sites = new ArrayList<>();
        /** Count of NEW listType instructions regardless of what follows. */
        public int listTypeNews;
        /** The accepted site (null while rejected / before gating). */
        public Site target;
    }

    /** Weave outcome: bytes (original array on reject) + observable state. */
    public static final class Result {
        public final byte[] bytes;
        public final String rejectReason;   // null when the shape was accepted
        public final Plan plan;
        Result(byte[] b, String r, Plan p) { bytes = b; rejectReason = r; plan = p; }
    }

    /** Same contract as weave(cfg, in) plus WHICH shape check fired and the
     *  collected site inventory (null rejectReason = woven). */
    public static Result weaveChecked(Config cfg, byte[] in) {
        Plan p = new Plan();
        try { p = collect(cfg, in); gate(cfg, p); }
        catch (ShapeReject re) { return new Result(in, re.getMessage(), p); }
        return new Result(emit(cfg, in, p), null, p);
    }

    /** Returns woven bytes, or the ORIGINAL array unchanged on shape reject. */
    public static byte[] weave(Config cfg, byte[] in) {
        return weaveChecked(cfg, in).bytes;
    }

    // ------------------------------------------------------------------ pass 1
    /** Collect every pattern site in the target method; no gating here. */
    public static Plan collect(Config cfg, byte[] in) {
        Plan p = new Plan();
        new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int acc, String name,
                    String desc, String sig, String[] exc) {
                if (!(name.equals(cfg.methodName) && desc.equals(cfg.methodDesc)))
                    return null;
                p.methodFound = true;
                return new MethodVisitor(Opcodes.ASM9) {
                    int idx = -1;                 // real-insn index
                    final List<int[]> trail = new ArrayList<>();  // recent insns

                    // trail entry: {idx, opcode, flag} where flag!=0 marks
                    // a NEW or INVOKESPECIAL matching the pinned list/ctor
                    void trail(int opcode, int flag) {
                        idx++;
                        trail.add(new int[] { idx, opcode, flag });
                        if (trail.size() > 4) trail.remove(0);
                    }
                    @Override public void visitTypeInsn(int op, String t) {
                        trail(op, op == Opcodes.NEW && t.equals(cfg.listType) ? 1 : 0);
                        if (op == Opcodes.NEW && t.equals(cfg.listType)) p.listTypeNews++;
                    }
                    @Override public void visitMethodInsn(int op, String o, String n,
                            String d, boolean itf) {
                        trail(op, op == Opcodes.INVOKESPECIAL && o.equals(cfg.listType)
                                && "<init>".equals(n) && d.equals(cfg.ctorDesc) ? 1 : 0);
                    }
                    @Override public void visitVarInsn(int op, int var) {
                        trail(op, 0);
                        // pattern tail: NEW t; DUP; INVOKESPECIAL t.<init>(); ASTORE
                        if (op == Opcodes.ASTORE) {
                            int sz = trail.size();
                            int[] init = sz >= 2 ? trail.get(sz - 2) : null;
                            int[] dup  = sz >= 3 ? trail.get(sz - 3) : null;
                            int[] nw   = sz >= 4 ? trail.get(sz - 4) : null;
                            if (init != null && dup != null && nw != null
                                    && nw[1] == Opcodes.NEW && nw[2] == 1
                                    && dup[1] == Opcodes.DUP
                                    && init[1] == Opcodes.INVOKESPECIAL && init[2] == 1) {
                                p.sites.add(new Site(nw[0], init[0], idx, var));
                            }
                        }
                    }
                    @Override public void visitInsn(int op) { trail(op, 0); }
                    @Override public void visitJumpInsn(int op, Label l) { trail(op, 0); }
                    @Override public void visitFieldInsn(int op, String o, String n, String d) { trail(op, 0); }
                    @Override public void visitIntInsn(int op, int v) { trail(op, 0); }
                    @Override public void visitLdcInsn(Object v) { trail(Opcodes.LDC, 0); }
                    @Override public void visitIincInsn(int v, int i) { trail(Opcodes.IINC, 0); }
                    @Override public void visitTableSwitchInsn(int a, int b2, Label d, Label... l) { trail(Opcodes.TABLESWITCH, 0); }
                    @Override public void visitLookupSwitchInsn(Label d, int[] k, Label[] l) { trail(Opcodes.LOOKUPSWITCH, 0); }
                    @Override public void visitMultiANewArrayInsn(String d2, int n) { trail(Opcodes.MULTIANEWARRAY, 0); }
                    @Override public void visitInvokeDynamicInsn(String n, String d, org.objectweb.asm.Handle h, Object... a) { trail(Opcodes.INVOKEDYNAMIC, 0); }
                };
            }
        }, 0);
        return p;
    }

    /** The shape gate. On success {@code p.target} is the accepted site. */
    static void gate(Config cfg, Plan p) {
        if (!p.methodFound)
            throw new ShapeReject("method-not-found " + cfg.methodName + cfg.methodDesc);
        int matches = 0;
        Site last = null;
        for (Site s : p.sites) {
            if (cfg.astoreSlot < 0 || s.astoreSlot == cfg.astoreSlot) { matches++; last = s; }
        }
        if (matches == 0)
            throw new ShapeReject("no pinned init site slot=" + cfg.astoreSlot
                + " sites=" + p.sites);
        if (matches > 1)
            throw new ShapeReject("ambiguous pinned init site slot=" + cfg.astoreSlot
                + " count=" + matches + " sites=" + p.sites);
        if (cfg.expectedTotalSites >= 0 && p.sites.size() != cfg.expectedTotalSites)
            throw new ShapeReject("total-sites=" + p.sites.size()
                + " expected=" + cfg.expectedTotalSites + " sites=" + p.sites);
        p.target = last;
    }

    // ------------------------------------------------------------------ pass 2
    static byte[] emit(Config cfg, byte[] in, Plan p) {
        // ClassWriter(0): re-emit visited events verbatim — instruction stream
        // length, max stack/locals and frames pass through. Only two operand
        // fields change (NEW type, INVOKESPECIAL owner) at the pinned site,
        // and MatchList IS-A ArrayList keeps original frames valid.
        ClassWriter cw = new ClassWriter(0);
        new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override public MethodVisitor visitMethod(int acc, String name,
                    String desc, String sig, String[] exc) {
                MethodVisitor mv = super.visitMethod(acc, name, desc, sig, exc);
                if (!(name.equals(cfg.methodName) && desc.equals(cfg.methodDesc))
                        || mv == null) return mv;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    int idx = -1;
                    @Override public void visitTypeInsn(int op, String t) {
                        idx++;
                        if (idx == p.target.newIndex) {
                            super.visitTypeInsn(op, cfg.matchListInternal);
                            return;
                        }
                        super.visitTypeInsn(op, t);
                    }
                    @Override public void visitMethodInsn(int op, String o, String n,
                            String d, boolean itf) {
                        idx++;
                        if (idx == p.target.initIndex) {
                            super.visitMethodInsn(op, cfg.matchListInternal, n, d, itf);
                            return;
                        }
                        super.visitMethodInsn(op, o, n, d, itf);
                    }
                    @Override public void visitInsn(int op) { idx++; super.visitInsn(op); }
                    @Override public void visitVarInsn(int op, int var) { idx++; super.visitVarInsn(op, var); }
                    @Override public void visitJumpInsn(int op, Label l) { idx++; super.visitJumpInsn(op, l); }
                    @Override public void visitFieldInsn(int op, String o, String n, String d) { idx++; super.visitFieldInsn(op, o, n, d); }
                    @Override public void visitIntInsn(int op, int v) { idx++; super.visitIntInsn(op, v); }
                    @Override public void visitLdcInsn(Object v) { idx++; super.visitLdcInsn(v); }
                    @Override public void visitIincInsn(int v, int i) { idx++; super.visitIincInsn(v, i); }
                    @Override public void visitTableSwitchInsn(int a, int b2, Label d, Label... l) { idx++; super.visitTableSwitchInsn(a, b2, d, l); }
                    @Override public void visitLookupSwitchInsn(Label d, int[] k, Label[] l) { idx++; super.visitLookupSwitchInsn(d, k, l); }
                    @Override public void visitMultiANewArrayInsn(String d2, int n) { idx++; super.visitMultiANewArrayInsn(d2, n); }
                    @Override public void visitInvokeDynamicInsn(String n, String d, org.objectweb.asm.Handle h, Object... a) { idx++; super.visitInvokeDynamicInsn(n, d, h, a); }
                };
            }
        }, 0);
        return cw.toByteArray();
    }
}
