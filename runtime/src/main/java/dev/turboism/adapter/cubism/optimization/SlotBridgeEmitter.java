package dev.turboism.adapter.cubism.optimization;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Emits the {@code System.getProperties()} slot protocol that the bridge consults share.
 *
 * <p>A bridge publishes its callback into a JDK {@link java.util.Properties} slot, so woven host
 * code never references a Turboism type. The lookup and the guarded type consult are therefore
 * identical across hooks; only the callback argument list and the fallback differ.</p>
 *
 * <p>Every consult leaves the resolved slot value on the stack when the slot holds the expected
 * type and jumps to the caller's fallback label with the value still on the stack otherwise. The
 * caller owns the fallback path, so it must pop the value before running host code. Callers keep
 * their own labels, local variable indices and try/catch placement.</p>
 */
public final class SlotBridgeEmitter {

    private static final String SYSTEM_OWNER = "java/lang/System";
    private static final String GET_PROPERTIES_METHOD = "getProperties";
    private static final String GET_PROPERTIES_DESCRIPTOR = "()Ljava/util/Properties;";
    private static final String PROPERTIES_OWNER = "java/util/Properties";
    private static final String GET_METHOD = "get";
    private static final String GET_DESCRIPTOR = "(Ljava/lang/Object;)Ljava/lang/Object;";

    private static final String PREDICATE_OWNER = "java/util/function/Predicate";
    private static final String PREDICATE_TEST_METHOD = "test";
    private static final String PREDICATE_TEST_DESCRIPTOR = "(Ljava/lang/Object;)Z";

    private static final String FUNCTION_OWNER = "java/util/function/Function";
    private static final String FUNCTION_APPLY_METHOD = "apply";
    private static final String FUNCTION_APPLY_DESCRIPTOR = "(Ljava/lang/Object;)Ljava/lang/Object;";

    private static final String METHOD_HANDLE_OWNER = "java/lang/invoke/MethodHandle";

    private SlotBridgeEmitter() {}

    /**
     * Pushes {@code System.getProperties().get(key)}: the raw slot value, or {@code null}.
     *
     * @param visitor method visitor receiving the emitted instructions
     * @param key exact property key the bridge published its callback under
     */
    public static void emitGetSlot(final MethodVisitor visitor, final String key) {
        visitor.visitMethodInsn(
                Opcodes.INVOKESTATIC, SYSTEM_OWNER, GET_PROPERTIES_METHOD, GET_PROPERTIES_DESCRIPTOR, false);
        visitor.visitLdcInsn(key);
        visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PROPERTIES_OWNER, GET_METHOD, GET_DESCRIPTOR, false);
    }

    /**
     * Emits a guarded {@link java.util.function.Predicate} consult.
     *
     * <p>Stack after the emitted prefix while the slot holds a predicate: {@code [predicate]}. The
     * caller supplies the single argument and calls {@link #emitTest(MethodVisitor)}. When the slot
     * is absent or of another type the emitted code jumps to {@code fallback} with the raw value
     * still on the stack.</p>
     *
     * @param visitor method visitor receiving the emitted instructions
     * @param key exact property key holding the callback
     * @param fallback label reached when the slot cannot serve the consult
     */
    public static void emitPredicateConsult(final MethodVisitor visitor, final String key, final Label fallback) {
        emitTypedConsult(visitor, key, PREDICATE_OWNER, fallback);
    }

    /**
     * Emits a guarded {@link java.util.function.Function} consult.
     *
     * <p>Stack after the emitted prefix while the slot holds a function: {@code [function]}. The
     * caller supplies the single argument and calls {@link #emitApply(MethodVisitor)}. When the
     * slot is absent or of another type the emitted code jumps to {@code fallback} with the raw
     * value still on the stack.</p>
     *
     * @param visitor method visitor receiving the emitted instructions
     * @param key exact property key holding the callback
     * @param fallback label reached when the slot cannot serve the consult
     */
    public static void emitFunctionConsult(final MethodVisitor visitor, final String key, final Label fallback) {
        emitTypedConsult(visitor, key, FUNCTION_OWNER, fallback);
    }

    /**
     * Emits a guarded {@link java.lang.invoke.MethodHandle} consult.
     *
     * <p>Stack after the emitted prefix while the slot holds a handle: {@code [handle]}. The caller
     * supplies the exact call-site arguments and issues {@code invokeExact} itself, because the
     * descriptor is per hook. When the slot is absent or of another type the emitted code jumps to
     * {@code fallback} with the raw value still on the stack.</p>
     *
     * @param visitor method visitor receiving the emitted instructions
     * @param key exact property key holding the callback
     * @param fallback label reached when the slot cannot serve the consult
     */
    public static void emitMethodHandleConsult(final MethodVisitor visitor, final String key, final Label fallback) {
        emitTypedConsult(visitor, key, METHOD_HANDLE_OWNER, fallback);
    }

    /**
     * Emits the {@link java.util.function.Predicate#test} call on the consulted predicate.
     *
     * @param visitor method visitor receiving the emitted instructions
     */
    public static void emitTest(final MethodVisitor visitor) {
        visitor.visitMethodInsn(
                Opcodes.INVOKEINTERFACE, PREDICATE_OWNER, PREDICATE_TEST_METHOD, PREDICATE_TEST_DESCRIPTOR, true);
    }

    /**
     * Emits the {@link java.util.function.Function#apply} call on the consulted function.
     *
     * @param visitor method visitor receiving the emitted instructions
     */
    public static void emitApply(final MethodVisitor visitor) {
        visitor.visitMethodInsn(
                Opcodes.INVOKEINTERFACE, FUNCTION_OWNER, FUNCTION_APPLY_METHOD, FUNCTION_APPLY_DESCRIPTOR, true);
    }

    /**
     * Emits the slot lookup followed by {@code instanceof}/{@code checkcast} to {@code type}.
     *
     * @param visitor method visitor receiving the emitted instructions
     * @param key exact property key holding the callback
     * @param type internal name of the expected callback type
     * @param fallback label reached when the slot cannot serve the consult
     */
    private static void emitTypedConsult(
            final MethodVisitor visitor, final String key, final String type, final Label fallback) {
        emitGetSlot(visitor, key);
        visitor.visitInsn(Opcodes.DUP);
        visitor.visitTypeInsn(Opcodes.INSTANCEOF, type);
        visitor.visitJumpInsn(Opcodes.IFEQ, fallback);
        visitor.visitTypeInsn(Opcodes.CHECKCAST, type);
    }
}
