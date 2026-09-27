package dev.turboism.validation.atlastiming;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Stack-neutral enter/exit timing weave for the exact 5.3.03 atlas-path methods.
 *
 * <p>At method entry it emits {@code enter(metricId)}; immediately before every {@code RETURN}
 * it emits {@code exit(metricId)}. Both calls take an int constant and return void, so the operand
 * stack is unchanged at every insertion point and all original stack-map frames stay valid — the
 * writer recomputes only max stack. No new local slots are allocated and the exception table is
 * untouched: a method that exits by throwing simply leaves an unmatched entry on the probe's
 * per-thread stack, which the probe counts as unpaired instead of misattributing.</p>
 *
 * <p>Fails closed per class: targets that are absent, abstract/native, or multiply matched are
 * recorded and the class is left unmodified.</p>
 */
final class AtlasTimingTransformer {
    private static final String PROBE_OWNER =
        "dev/turboism/validation/atlastiming/AtlasTimingProbe";

    private AtlasTimingTransformer() {
    }

    /** Per-target match results for one transformed class. */
    record Outcome(byte[] bytes, Map<String, Integer> matches) {
    }

    /**
     * Instruments every listed target found in {@code original}. Returns {@code null} bytes when
     * no target matched — callers keep the original class in that case.
     */
    static Outcome instrument(final byte[] original, final String ownerInternalName,
                              final List<AtlasTimingTargets.Target> targets) {
        final List<AtlasTimingTargets.Target> scoped = targets.stream()
            .filter(target -> target.ownerInternalName().equals(ownerInternalName))
            .toList();
        if (scoped.isEmpty()) return new Outcome(null, Map.of());

        final Map<String, Integer> matches = new LinkedHashMap<>();
        final ClassWriter writer = new ClassWriter(new ClassReader(original),
            ClassWriter.COMPUTE_MAXS);
        new ClassReader(original).accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name,
                                             final String descriptor, final String signature,
                                             final String[] exceptions) {
                final MethodVisitor delegate =
                    super.visitMethod(access, name, descriptor, signature, exceptions);
                AtlasTimingTargets.Target matched = null;
                for (final AtlasTimingTargets.Target target : scoped) {
                    if (target.methodName().equals(name) && target.descriptor().equals(descriptor)) {
                        matched = target;
                        break;
                    }
                }
                if (matched == null) return delegate;
                final AtlasTimingTargets.Target target = matched;
                final String key = name + descriptor;
                if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                    matches.put(key, -1);
                    return delegate;
                }
                matches.merge(key, 1, Integer::sum);
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        pushMetric(target.metricId());
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, PROBE_OWNER, "enter", "(I)V",
                            false);
                    }

                    @Override
                    public void visitInsn(final int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            pushMetric(target.metricId());
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, PROBE_OWNER, "exit", "(I)V",
                                false);
                        }
                        super.visitInsn(opcode);
                    }

                    private void pushMetric(final int metricId) {
                        if (metricId >= -1 && metricId <= 5) {
                            mv.visitInsn(Opcodes.ICONST_0 + metricId);
                        } else {
                            mv.visitIntInsn(Opcodes.BIPUSH, metricId);
                        }
                    }
                };
            }
        }, 0);
        final boolean any = matches.values().stream().anyMatch(count -> count > 0);
        return new Outcome(any ? writer.toByteArray() : null, matches);
    }
}
