package dev.turboism.adapter.cubism.optimization.extendedinterpolation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Reviewed anchors for the incremental {@code CModelSource.setup()} optimization.
 *
 * <p>{@code CModelSource.setup()} ends its whole-model rebuild with two forced
 * {@code CExtendedInterpolationExtension.setupFormStructure$default(ext, false, false, 3, null)}
 * calls, one looping over every drawable source and one over every deformer source. That forced
 * call ignores the host's own per-form edit-version and binding-structure comparison and rebuilds
 * every extended-interpolation form, which dominates the whole setup on large models.</p>
 *
 * <p>Both call sites are identical on every reviewed 5.1-5.3 generation: the four default
 * arguments are pushed by {@code ICONST_0 ICONST_0 ICONST_3 ACONST_NULL} immediately before the
 * {@code INVOKESTATIC}, and no other method in {@code CModelSource} references the forced method.
 * The transformer replaces exactly those two call sites with an inlined consult and re-pushes the
 * same arguments on fallback; {@link #verify(byte[])} proves the premise on the real bytes before
 * anything is rewritten.</p>
 */
public final class ExtendedInterpolationSetupTarget {

    /** Owner of the reviewed setup entry. */
    public static final String MODEL_SOURCE = "com/live2d/cubism/doc/model/CModelSource";

    /** Reviewed setup method name. */
    public static final String SETUP_METHOD = "setup";

    /** Reviewed setup descriptor. */
    public static final String SETUP_DESCRIPTOR = "()V";

    /** Extended-interpolation extension owner. */
    public static final String EXTENSION =
            "com/live2d/cubism/doc/model/interpolator/extendedInterpolation/CExtendedInterpolationExtension";

    /** Forced whole-model form rebuild the setup entry calls. */
    public static final String FORCED_METHOD = "setupFormStructure$default";

    /** Forced whole-model form rebuild descriptor. */
    public static final String FORCED_DESCRIPTOR =
            "(Lcom/live2d/cubism/doc/model/interpolator/extendedInterpolation/CExtendedInterpolationExtension;ZZILjava/lang/Object;)V";

    /** Exact number of forced call sites the reviewed setup entry contains. */
    public static final int EXPECTED_CALL_SITES = 2;

    /** Default-argument opcodes that must directly precede each forced call. */
    private static final int[] DEFAULT_ARGUMENTS = {
        Opcodes.ICONST_0, Opcodes.ICONST_0, Opcodes.ICONST_3, Opcodes.ACONST_NULL
    };

    private ExtendedInterpolationSetupTarget() {}

    /**
     * Verifies the reviewed premise on one {@code CModelSource.class}.
     *
     * @param classFile class file bytes
     * @return every anchor violation, in discovery order; empty when the class is patchable
     */
    public static List<String> verify(final byte[] classFile) {
        Objects.requireNonNull(classFile, "classFile");
        final List<String> problems = new ArrayList<>();
        final int[] totalCalls = {0};
        final int[] anchoredCalls = {0};
        final int[] wovenSlots = {0};
        new ClassReader(classFile)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String descriptor,
                                    final String signature,
                                    final String[] exceptions) {
                                final boolean setup = SETUP_METHOD.equals(name) && SETUP_DESCRIPTOR.equals(descriptor);
                                if (setup && (access & Opcodes.ACC_STATIC) != 0) problems.add("setup is static");
                                return new MethodVisitor(Opcodes.ASM9) {
                                    private final int[] window = new int[DEFAULT_ARGUMENTS.length];
                                    private int seen;

                                    @Override
                                    public void visitLdcInsn(final Object value) {
                                        if (ExtendedInterpolationSetupBridge.CONSULT_PROPERTY.equals(value)) {
                                            wovenSlots[0]++;
                                        }
                                    }

                                    @Override
                                    public void visitInsn(final int opcode) {
                                        window[seen++ % window.length] = opcode;
                                    }

                                    @Override
                                    public void visitMethodInsn(
                                            final int opcode,
                                            final String owner,
                                            final String method,
                                            final String desc,
                                            final boolean isInterface) {
                                        if (opcode != Opcodes.INVOKESTATIC
                                                || !EXTENSION.equals(owner)
                                                || !FORCED_METHOD.equals(method)
                                                || !FORCED_DESCRIPTOR.equals(desc)) return;
                                        totalCalls[0]++;
                                        if (!setup) {
                                            problems.add("forced call outside setup");
                                            return;
                                        }
                                        if (seen < DEFAULT_ARGUMENTS.length) {
                                            problems.add("forced call without default arguments");
                                            return;
                                        }
                                        for (int i = 0; i < DEFAULT_ARGUMENTS.length; i++) {
                                            final int actual =
                                                    window[(seen - DEFAULT_ARGUMENTS.length + i) % window.length];
                                            if (actual != DEFAULT_ARGUMENTS[i]) {
                                                problems.add("forced call argument " + i + " is " + actual);
                                                return;
                                            }
                                        }
                                        anchoredCalls[0]++;
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (anchoredCalls[0] != EXPECTED_CALL_SITES) {
            problems.add("anchored call sites " + anchoredCalls[0] + "/" + EXPECTED_CALL_SITES);
        }
        if (totalCalls[0] != EXPECTED_CALL_SITES) {
            problems.add("class-wide forced calls " + totalCalls[0] + "/" + EXPECTED_CALL_SITES);
        }
        if (wovenSlots[0] != 0) {
            problems.add("class already woven (" + wovenSlots[0] + " consult slots)");
        }
        return List.copyOf(problems);
    }
}
