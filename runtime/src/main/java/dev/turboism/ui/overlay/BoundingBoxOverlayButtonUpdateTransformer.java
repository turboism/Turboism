package dev.turboism.ui.overlay;

import dev.turboism.mapping.verification.StaticSelector;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Objects;

/**
 * Exact-selector transformer augmenting the native bounding-box button setup sequence.
 *
 * <p>The native {@code update} method makes exactly three {@code update$setupButton} calls.
 * This transformer preserves all three calls and appends one bounded custom-button loop
 * immediately after the third call. Helper arguments are captured from the operand stack
 * into ASM-allocated locals (never guessed slots); the second native offset is captured as
 * the step and the third as the anchor. Custom offsets are derived as
 * {@code anchor + step * (i + 1)} only.</p>
 *
 * <p>The appended bytecode is loader-neutral: it reads a {@link java.util.function.BiFunction}
 * and a {@link java.util.function.Consumer} from {@link System#getProperties()} and contains
 * no {@code dev/turboism/**} class reference. Missing, replaced, malformed or throwing
 * callbacks fail open, leaving the three original native calls and the normal return
 * unaffected, with bounded structured diagnostics through the failure callback.</p>
 */
public class BoundingBoxOverlayButtonUpdateTransformer implements ClassFileTransformer {

    static final String SETUP_PROPERTY = NativeBoundingBoxOverlayButtonBridge.SETUP_PROPERTY;
    static final String FAILURE_PROPERTY = NativeBoundingBoxOverlayButtonBridge.FAILURE_PROPERTY;
    static final int MAX_CUSTOM_BUTTONS = 8;

    private static final String BIFUNCTION = "java/util/function/BiFunction";
    private static final String CONSUMER = "java/util/function/Consumer";
    private static final String OBJECT_ARRAY = "[Ljava/lang/Object;";
    private static final String THROWABLE = "java/lang/Throwable";
    private static final String PROPERTIES_GET_DESCRIPTOR =
        "(Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String BIFUNCTION_APPLY_DESCRIPTOR =
        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String CONSUMER_ACCEPT_DESCRIPTOR = "(Ljava/lang/Object;)V";

    private final ClassLoader expectedLoader;
    private final StaticSelector updateSelector;
    private final StaticSelector setupButtonSelector;
    private final StaticSelector vectorTimesSelector;
    private final StaticSelector vectorPlusSelector;

    public BoundingBoxOverlayButtonUpdateTransformer(
        final ClassLoader expectedLoader,
        final StaticSelector updateSelector,
        final StaticSelector setupButtonSelector,
        final StaticSelector vectorTimesSelector,
        final StaticSelector vectorPlusSelector
    ) {
        this.expectedLoader = Objects.requireNonNull(expectedLoader, "expectedLoader");
        this.updateSelector = Objects.requireNonNull(updateSelector, "updateSelector");
        this.setupButtonSelector = Objects.requireNonNull(setupButtonSelector, "setupButtonSelector");
        this.vectorTimesSelector = Objects.requireNonNull(vectorTimesSelector, "vectorTimesSelector");
        this.vectorPlusSelector = Objects.requireNonNull(vectorPlusSelector, "vectorPlusSelector");
    }

    @Override
    public byte[] transform(
        final Module module,
        final ClassLoader loader,
        final String className,
        final Class<?> classBeingRedefined,
        final ProtectionDomain protectionDomain,
        final byte[] classfileBuffer
    ) {
        if (loader != expectedLoader
            || className == null
            || !className.equals(updateSelector.ownerInternalName())) {
            return null;
        }
        try {
            final Analysis analysis = analyze(classfileBuffer);
            if (analysis == null) {
                return null;
            }
            return augment(classfileBuffer, analysis);
        } catch (RuntimeException | Error failure) {
            // Fail closed: an unexpected class shape never yields partial instrumentation.
            return null;
        }
    }

    /**
    /**
     * First pass: exact owner/descriptor/access admission, the native helper call count,
     * and the original selected {@code GVector2.times}/{@code GVector2.plus} call counts of
     * the {@code update} method. Returns {@code null} when the class does not match the
     * verified shape exactly.
     */
    private Analysis analyze(final byte[] classfileBuffer) {
        final String owner = updateSelector.ownerInternalName();
        final String setupOwner = setupButtonSelector.ownerInternalName();
        final String helperName = setupButtonSelector.memberName();
        final String helperDescriptor = setupButtonSelector.descriptor();
        final ClassReader classReader = new ClassReader(classfileBuffer);
        if (!owner.equals(classReader.getClassName())) {
            return null;
        }
        if (!setupOwner.equals(owner)) {
            // The setup helper is an exact private static method of the same exact owner.
            return null;
        }
        final int[] helperCallCount = {0};
        final int[] timesCallCount = {0};
        final int[] plusCallCount = {0};
        final int[] updateMaxLocals = {-1};
        final boolean[] updateMatched = {false};
        final boolean[] updateAccessOk = {false};
        final boolean[] helperMatched = {false};
        final boolean[] helperAccessOk = {false};
        classReader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(
                final int access,
                final String name,
                final String descriptor,
                final String signature,
                final String[] exceptions
            ) {
                if (helperName.equals(name) && helperDescriptor.equals(descriptor)) {
                    helperMatched[0] = true;
                    helperAccessOk[0] = matchesAccess(access, setupButtonSelector);
                    return null;
                }
                if (!updateSelector.memberName().equals(name)
                    || !updateSelector.descriptor().equals(descriptor)) {
                    return null;
                }
                updateMatched[0] = true;
                updateAccessOk[0] = matchesAccess(access, updateSelector);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(
                        final int opcode,
                        final String methodOwner,
                        final String methodName,
                        final String methodDescriptor,
                        final boolean isInterface
                    ) {
                        if (opcode == Opcodes.INVOKESTATIC
                            && setupOwner.equals(methodOwner)
                            && helperName.equals(methodName)
                            && helperDescriptor.equals(methodDescriptor)) {
                            helperCallCount[0]++;
                        }
                        if (opcode == Opcodes.INVOKEVIRTUAL
                            && vectorTimesSelector.ownerInternalName().equals(methodOwner)
                            && vectorTimesSelector.memberName().equals(methodName)
                            && vectorTimesSelector.descriptor().equals(methodDescriptor)) {
                            timesCallCount[0]++;
                        }
                        if (opcode == Opcodes.INVOKEVIRTUAL
                            && vectorPlusSelector.ownerInternalName().equals(methodOwner)
                            && vectorPlusSelector.memberName().equals(methodName)
                            && vectorPlusSelector.descriptor().equals(methodDescriptor)) {
                            plusCallCount[0]++;
                        }
                    }

                    @Override
                    public void visitMaxs(final int maxStack, final int maxLocals) {
                        updateMaxLocals[0] = maxLocals;
                    }
                };
            }
        }, 0);
        if (!updateMatched[0]
            || !updateAccessOk[0]
            || !helperMatched[0]
            || !helperAccessOk[0]
            || helperCallCount[0] != 3
            || updateMaxLocals[0] < 0) {
            return null;
        }
        return new Analysis(
            updateMaxLocals[0],
            helperCallCount[0],
            timesCallCount[0],
            plusCallCount[0]
        );
    }

    private byte[] augment(final byte[] classfileBuffer, final Analysis analysis) {
        final ClassReader reader = new ClassReader(classfileBuffer);
        final ClassWriter writer = new ClassWriter(
            reader,
            ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS
        ) {
            @Override
            protected String getCommonSuperClass(final String left, final String right) {
                // Host-only types are private to the exact verified host loader; resolve
                // frames through it (the established host-loader-aware transformer pattern).
                try {
                    final ClassLoader loader = expectedLoader;
                    final Class<?> leftType = Class.forName(
                        left.replace('/', '.'), false, loader
                    );
                    final Class<?> rightType = Class.forName(
                        right.replace('/', '.'), false, loader
                    );
                    if (leftType.isAssignableFrom(rightType)) {
                        return left;
                    }
                    if (rightType.isAssignableFrom(leftType)) {
                        return right;
                    }
                    if (leftType.isInterface() || rightType.isInterface()) {
                        return "java/lang/Object";
                    }
                    Class<?> current = leftType;
                    do {
                        current = current.getSuperclass();
                    } while (!current.isAssignableFrom(rightType));
                    return current.getName().replace('.', '/');
                } catch (Throwable ignored) {
                    return "java/lang/Object";
                }
            }
        };
        final int[] slots = analysis.slots();
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                final int access,
                final String name,
                final String descriptor,
                final String signature,
                final String[] exceptions
            ) {
                final MethodVisitor delegate = super.visitMethod(
                    access,
                    name,
                    descriptor,
                    signature,
                    exceptions
                );
                if (!updateSelector.memberName().equals(name)
                    || !updateSelector.descriptor().equals(descriptor)) {
                    return delegate;
                }
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    private int helperCalls;

                    @Override
                    public void visitMethodInsn(
                        final int opcode,
                        final String methodOwner,
                        final String methodName,
                        final String methodDescriptor,
                        final boolean isInterface
                    ) {
                        final boolean setupCall = opcode == Opcodes.INVOKESTATIC
                            && setupButtonSelector.ownerInternalName().equals(methodOwner)
                            && setupButtonSelector.memberName().equals(methodName)
                            && setupButtonSelector.descriptor().equals(methodDescriptor);
                        if (setupCall) {
                            helperCalls++;
                            if (helperCalls == 2) {
                                captureStep();
                            } else if (helperCalls == 3) {
                                captureThirdCall();
                            }
                        }
                        super.visitMethodInsn(opcode, methodOwner, methodName, methodDescriptor, isInterface);
                        if (setupCall && helperCalls == 3) {
                            emitAugmentation(delegate, slots);
                        }
                    }

                    private void captureStep() {
                        // Top of the operand stack at the second call is the native offset.
                        super.visitInsn(Opcodes.DUP);
                        super.visitVarInsn(Opcodes.ASTORE, slots[STEP]);
                    }

                    private void captureThirdCall() {
                        // Operand stack at the third call: action, box, points, scene,
                        // button, offset (top). Capture all six into ASM-allocated locals,
                        // then re-push them so the original call is preserved unchanged.
                        super.visitVarInsn(Opcodes.ASTORE, slots[ANCHOR]);
                        super.visitVarInsn(Opcodes.ASTORE, slots[BUTTON]);
                        super.visitVarInsn(Opcodes.ASTORE, slots[SCENE]);
                        super.visitVarInsn(Opcodes.ASTORE, slots[POINTS]);
                        super.visitVarInsn(Opcodes.ASTORE, slots[BOX]);
                        super.visitVarInsn(Opcodes.ASTORE, slots[ACTION]);
                        super.visitVarInsn(Opcodes.ALOAD, slots[ACTION]);
                        super.visitVarInsn(Opcodes.ALOAD, slots[BOX]);
                        super.visitVarInsn(Opcodes.ALOAD, slots[POINTS]);
                        super.visitVarInsn(Opcodes.ALOAD, slots[SCENE]);
                        super.visitVarInsn(Opcodes.ALOAD, slots[BUTTON]);
                        super.visitVarInsn(Opcodes.ALOAD, slots[ANCHOR]);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        final byte[] candidate = writer.toByteArray();
        return verifyCandidate(candidate, analysis) ? candidate : null;
    }

    /**
     * Strongest structural re-read/invariant gate available with the current ASM core:
     * the generated candidate must re-read cleanly and its selected call-site counts must
     * be the exact deltas from the analyzed original {@code update} body: one additional
     * setup-helper call, one additional {@code GVector2.times} call and one additional
     * {@code GVector2.plus} call. The exact hosts already perform native vector arithmetic
     * (one {@code times} and two {@code plus} calls), so global absolute counts would
     * reject every exact-host candidate; delta verification keeps the augmentation
     * admissible on the host shape while still failing closed on any structural drift.
     * Expected counts are computed with overflow rejection so an impossible count never
     * wraps into a plausible one. Any mismatch fails closed with no transformed candidate.
     */
    private boolean verifyCandidate(final byte[] candidate, final Analysis analysis) {
        final int expectedHelpers = guardedIncrement(
            analysis.originalHelperCalls(),
            "setup-helper"
        );
        final int expectedTimes = guardedIncrement(
            analysis.originalTimesCalls(),
            "vector times"
        );
        final int expectedPlus = guardedIncrement(
            analysis.originalPlusCalls(),
            "vector plus"
        );
        final String owner = updateSelector.ownerInternalName();
        final String setupOwner = setupButtonSelector.ownerInternalName();
        final String helperName = setupButtonSelector.memberName();
        final String helperDescriptor = setupButtonSelector.descriptor();
        final ClassReader classReader = new ClassReader(candidate);
        if (!owner.equals(classReader.getClassName())) {
            return false;
        }
        final int[] helperCalls = {0};
        final int[] timesCalls = {0};
        final int[] plusCalls = {0};
        final boolean[] updateMatched = {false};
        classReader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(
                final int access,
                final String name,
                final String descriptor,
                final String signature,
                final String[] exceptions
            ) {
                if (!updateSelector.memberName().equals(name)
                    || !updateSelector.descriptor().equals(descriptor)) {
                    return null;
                }
                updateMatched[0] = true;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(
                        final int opcode,
                        final String methodOwner,
                        final String methodName,
                        final String methodDescriptor,
                        final boolean isInterface
                    ) {
                        if (opcode == Opcodes.INVOKESTATIC
                            && setupOwner.equals(methodOwner)
                            && helperName.equals(methodName)
                            && helperDescriptor.equals(methodDescriptor)) {
                            helperCalls[0]++;
                        }
                        if (opcode == Opcodes.INVOKEVIRTUAL
                            && vectorTimesSelector.ownerInternalName().equals(methodOwner)
                            && vectorTimesSelector.memberName().equals(methodName)
                            && vectorTimesSelector.descriptor().equals(methodDescriptor)) {
                            timesCalls[0]++;
                        }
                        if (opcode == Opcodes.INVOKEVIRTUAL
                            && vectorPlusSelector.ownerInternalName().equals(methodOwner)
                            && vectorPlusSelector.memberName().equals(methodName)
                            && vectorPlusSelector.descriptor().equals(methodDescriptor)) {
                            plusCalls[0]++;
                        }
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return updateMatched[0]
            && helperCalls[0] == expectedHelpers
            && timesCalls[0] == expectedTimes
            && plusCalls[0] == expectedPlus;
    }

    /**
     * Exact delta arithmetic: the augmented candidate carries exactly one additional
     * selected call site of each kind. An impossible overflow is rejected instead of
     * wrapping into a plausible expected count (fails closed through the caller).
     */
    private static int guardedIncrement(final int original, final String kind) {
        if (original == Integer.MAX_VALUE) {
            throw new ArithmeticException(
                "bounding-box overlay " + kind + " call count overflow is impossible"
            );
        }
        return original + 1;
    }

    /** Appended immediately after the third native setup call; all frames are computed. */
    private void emitAugmentation(final MethodVisitor out, final int[] slots) {
        final int actionSlot = slots[ACTION];
        final int boxSlot = slots[BOX];
        final int pointsSlot = slots[POINTS];
        final int sceneSlot = slots[SCENE];
        final int stepSlot = slots[STEP];
        final int anchorSlot = slots[ANCHOR];
        final int buttonsSlot = slots[BUTTONS];
        final int indexSlot = slots[INDEX];
        final int callbackSlot = slots[CALLBACK];

        final Label tryStart = new Label();
        final Label tryEnd = new Label();
        final Label handler = new Label();
        final Label complete = new Label();
        final Label validationHead = new Label();
        final Label validationNext = new Label();
        final Label validationComplete = new Label();
        final Label invalidNull = new Label();
        final Label invalidOutput = new Label();
        final Label loopHead = new Label();
        final Label next = new Label();
        final Label cbMissing = new Label();
        final Label arrayMissing = new Label();
        final Label diagStart = new Label();
        final Label diagEnd = new Label();
        final Label diagMissing = new Label();
        final Label diagHandler = new Label();

        final Type[] helperArguments = Type.getArgumentTypes(setupButtonSelector.descriptor());
        final String buttonType = helperArguments[4].getInternalName();

        out.visitTryCatchBlock(tryStart, tryEnd, handler, THROWABLE);
        out.visitTryCatchBlock(diagStart, diagEnd, diagHandler, THROWABLE);
        out.visitLabel(tryStart);

        // Callback acquisition: BiFunction(overlay, sceneGraph) -> Object[].
        out.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "java/lang/System",
            "getProperties",
            "()Ljava/util/Properties;",
            false
        );
        out.visitLdcInsn(SETUP_PROPERTY);
        out.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "java/util/Properties",
            "get",
            PROPERTIES_GET_DESCRIPTOR,
            false
        );
        out.visitTypeInsn(Opcodes.CHECKCAST, BIFUNCTION);
        out.visitInsn(Opcodes.DUP);
        out.visitJumpInsn(Opcodes.IFNULL, cbMissing);
        out.visitVarInsn(Opcodes.ASTORE, callbackSlot);
        out.visitVarInsn(Opcodes.ALOAD, callbackSlot);
        out.visitVarInsn(Opcodes.ALOAD, 0);
        out.visitVarInsn(Opcodes.ALOAD, sceneSlot);
        out.visitMethodInsn(
            Opcodes.INVOKEINTERFACE,
            BIFUNCTION,
            "apply",
            BIFUNCTION_APPLY_DESCRIPTOR,
            true
        );
        out.visitTypeInsn(Opcodes.CHECKCAST, OBJECT_ARRAY);
        out.visitInsn(Opcodes.DUP);
        out.visitJumpInsn(Opcodes.IFNULL, arrayMissing);
        out.visitVarInsn(Opcodes.ASTORE, buttonsSlot);
        out.visitInsn(Opcodes.ICONST_0);
        out.visitVarInsn(Opcodes.ISTORE, indexSlot);
        // Validate the whole callback array before the first custom setup call. The contract
        // is all-or-none: 0..8 non-null exact host button instances are accepted; malformed
        // or oversized output reaches the fail-open handler with zero custom setup calls.
        out.visitVarInsn(Opcodes.ALOAD, buttonsSlot);
        out.visitInsn(Opcodes.ARRAYLENGTH);
        out.visitIntInsn(Opcodes.BIPUSH, MAX_CUSTOM_BUTTONS);
        out.visitJumpInsn(Opcodes.IF_ICMPGT, invalidOutput);
        out.visitVarInsn(Opcodes.ALOAD, buttonsSlot);
        out.visitInsn(Opcodes.ARRAYLENGTH);
        out.visitJumpInsn(Opcodes.IFEQ, complete);

        out.visitLabel(validationHead);
        out.visitVarInsn(Opcodes.ALOAD, buttonsSlot);
        out.visitVarInsn(Opcodes.ILOAD, indexSlot);
        out.visitInsn(Opcodes.AALOAD);
        out.visitInsn(Opcodes.DUP);
        out.visitJumpInsn(Opcodes.IFNULL, invalidNull);
        out.visitTypeInsn(Opcodes.INSTANCEOF, buttonType);
        out.visitJumpInsn(Opcodes.IFNE, validationNext);
        out.visitJumpInsn(Opcodes.GOTO, invalidOutput);
        out.visitLabel(invalidNull);
        out.visitInsn(Opcodes.POP);
        out.visitJumpInsn(Opcodes.GOTO, invalidOutput);
        out.visitLabel(validationNext);
        out.visitIincInsn(indexSlot, 1);
        out.visitVarInsn(Opcodes.ILOAD, indexSlot);
        out.visitVarInsn(Opcodes.ALOAD, buttonsSlot);
        out.visitInsn(Opcodes.ARRAYLENGTH);
        out.visitJumpInsn(Opcodes.IF_ICMPLT, validationHead);
        out.visitLabel(validationComplete);
        out.visitInsn(Opcodes.ICONST_0);
        out.visitVarInsn(Opcodes.ISTORE, indexSlot);

        // Bounded custom setup loop:
        // update$setupButton(action, box, points, scene, button[i], anchor + step * (i + 1)).
        out.visitLabel(loopHead);
        out.visitVarInsn(Opcodes.ALOAD, actionSlot);
        out.visitVarInsn(Opcodes.ALOAD, boxSlot);
        out.visitVarInsn(Opcodes.ALOAD, pointsSlot);
        out.visitVarInsn(Opcodes.ALOAD, sceneSlot);
        out.visitVarInsn(Opcodes.ALOAD, buttonsSlot);
        out.visitVarInsn(Opcodes.ILOAD, indexSlot);
        out.visitInsn(Opcodes.AALOAD);
        out.visitTypeInsn(Opcodes.CHECKCAST, buttonType);
        out.visitVarInsn(Opcodes.ALOAD, anchorSlot);
        out.visitVarInsn(Opcodes.ALOAD, stepSlot);
        out.visitVarInsn(Opcodes.ILOAD, indexSlot);
        out.visitInsn(Opcodes.ICONST_1);
        out.visitInsn(Opcodes.IADD);
        out.visitInsn(Opcodes.I2F);
        out.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            vectorTimesSelector.ownerInternalName(),
            vectorTimesSelector.memberName(),
            vectorTimesSelector.descriptor(),
            false
        );
        out.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            vectorPlusSelector.ownerInternalName(),
            vectorPlusSelector.memberName(),
            vectorPlusSelector.descriptor(),
            false
        );
        out.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            setupButtonSelector.ownerInternalName(),
            setupButtonSelector.memberName(),
            setupButtonSelector.descriptor(),
            false
        );
        out.visitLabel(next);
        out.visitIincInsn(indexSlot, 1);
        out.visitVarInsn(Opcodes.ILOAD, indexSlot);
        out.visitVarInsn(Opcodes.ALOAD, buttonsSlot);
        out.visitInsn(Opcodes.ARRAYLENGTH);
        out.visitJumpInsn(Opcodes.IF_ICMPLT, loopHead);
        out.visitJumpInsn(Opcodes.GOTO, complete);

        out.visitLabel(invalidOutput);
        out.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalArgumentException");
        out.visitInsn(Opcodes.DUP);
        out.visitLdcInsn("invalid bounding-box overlay callback output");
        out.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            "java/lang/IllegalArgumentException",
            "<init>",
            "(Ljava/lang/String;)V",
            false
        );
        out.visitInsn(Opcodes.ATHROW);

        // Missing setup callback: drop the null callback.
        out.visitLabel(cbMissing);
        out.visitInsn(Opcodes.POP);
        out.visitJumpInsn(Opcodes.GOTO, complete);

        // Null callback result: drop the array.
        out.visitLabel(arrayMissing);
        out.visitInsn(Opcodes.POP);
        out.visitJumpInsn(Opcodes.GOTO, complete);

        out.visitLabel(tryEnd);
        out.visitJumpInsn(Opcodes.GOTO, complete);

        // Fail-open handler with bounded structured diagnostics.
        out.visitLabel(handler);
        out.visitVarInsn(Opcodes.ASTORE, callbackSlot);
        out.visitLabel(diagStart);
        out.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "java/lang/System",
            "getProperties",
            "()Ljava/util/Properties;",
            false
        );
        out.visitLdcInsn(FAILURE_PROPERTY);
        out.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "java/util/Properties",
            "get",
            PROPERTIES_GET_DESCRIPTOR,
            false
        );
        out.visitTypeInsn(Opcodes.CHECKCAST, CONSUMER);
        out.visitInsn(Opcodes.DUP);
        out.visitJumpInsn(Opcodes.IFNULL, diagMissing);
        out.visitVarInsn(Opcodes.ALOAD, callbackSlot);
        out.visitMethodInsn(
            Opcodes.INVOKEINTERFACE,
            CONSUMER,
            "accept",
            CONSUMER_ACCEPT_DESCRIPTOR,
            true
        );
        out.visitJumpInsn(Opcodes.GOTO, diagEnd);
        out.visitLabel(diagMissing);
        out.visitInsn(Opcodes.POP);
        out.visitLabel(diagEnd);
        out.visitJumpInsn(Opcodes.GOTO, complete);
        out.visitLabel(diagHandler);
        out.visitInsn(Opcodes.POP);
        out.visitLabel(complete);
    }

    private static boolean matchesAccess(final int access, final StaticSelector selector) {
        return (access & selector.requiredAccessFlags()) == selector.requiredAccessFlags()
            && (access & selector.forbiddenAccessFlags()) == 0;
    }

    private static final int ACTION = 0;
    private static final int BOX = 1;
    private static final int POINTS = 2;
    private static final int SCENE = 3;
    private static final int BUTTON = 4;
    private static final int STEP = 5;
    private static final int ANCHOR = 6;
    private static final int BUTTONS = 7;
    private static final int INDEX = 8;
    private static final int CALLBACK = 9;

    /**
     * ASM-allocated local slots (always beyond the original method's maxLocals) plus the
     * analyzed original {@code update} call-site counts used for delta verification.
     */
    private record Analysis(
        int updateMaxLocals,
        int originalHelperCalls,
        int originalTimesCalls,
        int originalPlusCalls
    ) {

        private int[] slots() {
            final int base = updateMaxLocals;
            return new int[] {
                base + ACTION,
                base + BOX,
                base + POINTS,
                base + SCENE,
                base + BUTTON,
                base + STEP,
                base + ANCHOR,
                base + BUTTONS,
                base + INDEX,
                base + CALLBACK
            };
        }
    }
}
