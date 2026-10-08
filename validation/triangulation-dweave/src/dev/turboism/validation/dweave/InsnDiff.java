package dev.turboism.validation.dweave;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Structural instruction-stream comparison for two class byte arrays.
 * Records every REAL instruction (opcode + rendered operands; labels become
 * first-seen ordinals) per method, then diffs pairwise by position. Used to
 * prove the DWEAVE transform changes exactly two instruction operands and
 * nothing else — on the own fixture and, read-only, on official bytes.
 */
final class InsnDiff implements Opcodes {
    private InsnDiff() {}

    /** methodKey (name+desc, visit order) -> ordered rendered insn keys. */
    static Map<String, List<String>> methods(byte[] classBytes) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        new ClassReader(classBytes).accept(new ClassVisitor(ASM9) {
            @Override public MethodVisitor visitMethod(int acc, String name,
                    String desc, String sig, String[] exc) {
                List<String> insns = new ArrayList<>();
                // name+desc is unique within a class; suffix visit order anyway
                // so a duplicate key would be observable rather than merged.
                String key = name + desc;
                for (int i = 0; out.containsKey(key); i++) key = name + desc + "#dup" + i;
                out.put(key, insns);
                Map<Label, Integer> labels = new HashMap<>();
                return new MethodVisitor(ASM9) {
                    int lbl(Label l) {
                        Integer v = labels.get(l);
                        if (v == null) { v = labels.size(); labels.put(l, v); }
                        return v;
                    }
                    void add(String k) { insns.add(k); }
                    @Override public void visitInsn(int op) { add("insn:" + op); }
                    @Override public void visitVarInsn(int op, int var) { add("var:" + op + ":" + var); }
                    @Override public void visitTypeInsn(int op, String t) { add("type:" + op + ":" + t); }
                    @Override public void visitMethodInsn(int op, String o, String n, String d, boolean itf) {
                        add("method:" + op + ":" + o + "." + n + d + ":" + itf);
                    }
                    @Override public void visitFieldInsn(int op, String o, String n, String d) {
                        add("field:" + op + ":" + o + "." + n + ":" + d);
                    }
                    @Override public void visitJumpInsn(int op, Label l) { add("jump:" + op + ":L" + lbl(l)); }
                    @Override public void visitIntInsn(int op, int v) { add("int:" + op + ":" + v); }
                    @Override public void visitLdcInsn(Object v) {
                        add("ldc:" + (v == null ? "null" : v.getClass().getName() + "=" + v));
                    }
                    @Override public void visitIincInsn(int v, int i) { add("iinc:" + v + ":" + i); }
                    @Override public void visitTableSwitchInsn(int a, int b, Label d, Label... l) {
                        StringBuilder sb = new StringBuilder("tswitch:" + a + ":" + b + ":L" + lbl(d));
                        for (Label x : l) sb.append(":L").append(lbl(x));
                        add(sb.toString());
                    }
                    @Override public void visitLookupSwitchInsn(Label d, int[] k, Label[] l) {
                        StringBuilder sb = new StringBuilder("lswitch:L" + lbl(d));
                        for (int i = 0; i < k.length; i++) sb.append(":").append(k[i]).append("->L").append(lbl(l[i]));
                        add(sb.toString());
                    }
                    @Override public void visitMultiANewArrayInsn(String d2, int n) { add("marea:" + d2 + ":" + n); }
                    @Override public void visitInvokeDynamicInsn(String n, String d, Handle h, Object... a) {
                        StringBuilder sb = new StringBuilder("indy:" + n + d + ":" + h);
                        for (Object x : a) sb.append(":").append(x);
                        add(sb.toString());
                    }
                };
            }
        }, 0);
        return out;
    }

    /**
     * Positional diff of the two classes' instruction streams. Returns one
     * string per differing instruction: "method#idx: before -> after".
     * Method-set or length mismatches appear as whole-entry records.
     */
    static List<String> diff(byte[] before, byte[] after) {
        Map<String, List<String>> a = methods(before);
        Map<String, List<String>> b = methods(after);
        List<String> out = new ArrayList<>();
        List<String> ak = new ArrayList<>(a.keySet());
        List<String> bk = new ArrayList<>(b.keySet());
        if (!ak.equals(bk)) {
            out.add("method-list mismatch: " + ak + " vs " + bk);
            return out;
        }
        for (String m : ak) {
            List<String> la = a.get(m), lb = b.get(m);
            if (la.size() != lb.size()) {
                out.add(m + " insn-count " + la.size() + " -> " + lb.size());
                continue;
            }
            for (int i = 0; i < la.size(); i++)
                if (!la.get(i).equals(lb.get(i)))
                    out.add(m + "#" + i + ": " + la.get(i) + " -> " + lb.get(i));
        }
        return out;
    }
}
