package dev.turboism.adapter.cubism.optimization;

import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
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
    private static final String PROPERTIES_DESCRIPTOR = "Ljava/util/Properties;";
    private static final String GET_METHOD = "get";
    private static final String GET_DESCRIPTOR = "(Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String OBJECT_DESCRIPTOR = "Ljava/lang/Object;";
    private static final String CONSTANT_BOOTSTRAPS_OWNER = "java/lang/invoke/ConstantBootstraps";
    private static final String INVOKE_METHOD = "invoke";
    private static final String INVOKE_BOOTSTRAP_DESCRIPTOR =
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;"
                    + "Ljava/lang/invoke/MethodHandle;[Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String PROPERTIES_CONDY_NAME = "turboism_properties";
    private static final String SLOT_CONDY_NAME_PREFIX = "turboism_slot_";
    private static final int CONSTANT_DYNAMIC_MIN_MAJOR_VERSION = Opcodes.V11;
    private static final String METHOD_WRITER_SIMPLE_NAME = "MethodWriter";
    private static final String SYMBOL_TABLE_SIMPLE_NAME = "SymbolTable";
    private static final String SYMBOL_TABLE_FIELD = "symbolTable";
    private static final String MAJOR_VERSION_FIELD = "majorVersion";

    private static final Handle BOOTSTRAP_INVOKE_HANDLE = new Handle(
            Opcodes.H_INVOKESTATIC, CONSTANT_BOOTSTRAPS_OWNER, INVOKE_METHOD, INVOKE_BOOTSTRAP_DESCRIPTOR, false);
    private static final Handle SYSTEM_PROPERTIES_HANDLE =
            new Handle(Opcodes.H_INVOKESTATIC, SYSTEM_OWNER, GET_PROPERTIES_METHOD, GET_PROPERTIES_DESCRIPTOR, false);
    private static final Handle PROPERTIES_GET_HANDLE =
            new Handle(Opcodes.H_INVOKEVIRTUAL, PROPERTIES_OWNER, GET_METHOD, GET_DESCRIPTOR, false);
    private static final ConstantDynamic SYSTEM_PROPERTIES_CONDY = new ConstantDynamic(
            PROPERTIES_CONDY_NAME, PROPERTIES_DESCRIPTOR, BOOTSTRAP_INVOKE_HANDLE, SYSTEM_PROPERTIES_HANDLE);

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
        if (supportsConstantDynamic(visitor)) {
            visitor.visitLdcInsn(new ConstantDynamic(
                    sanitizeCondyName(key),
                    OBJECT_DESCRIPTOR,
                    BOOTSTRAP_INVOKE_HANDLE,
                    PROPERTIES_GET_HANDLE,
                    SYSTEM_PROPERTIES_CONDY,
                    key));
            return;
        }
        visitor.visitMethodInsn(
                Opcodes.INVOKESTATIC, SYSTEM_OWNER, GET_PROPERTIES_METHOD, GET_PROPERTIES_DESCRIPTOR, false);
        visitor.visitLdcInsn(key);
        visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PROPERTIES_OWNER, GET_METHOD, GET_DESCRIPTOR, false);
    }

    private static boolean supportsConstantDynamic(final MethodVisitor visitor) {
        if (!ClassMajorVersionAccess.AVAILABLE) {
            return false;
        }
        MethodVisitor current = visitor;
        while (current != null) {
            if (ClassMajorVersionAccess.METHOD_WRITER_TYPE.isInstance(current)) {
                return ClassMajorVersionAccess.majorVersion(current) >= CONSTANT_DYNAMIC_MIN_MAJOR_VERSION;
            }
            current = current.getDelegate();
        }
        return false;
    }

    private static final class ClassMajorVersionAccess {

        private static final Class<?> METHOD_WRITER_TYPE;
        private static final MethodHandle SYMBOL_TABLE_GETTER;
        private static final MethodHandle MAJOR_VERSION_GETTER;
        private static final boolean AVAILABLE;

        static {
            Class<?> writerType = null;
            MethodHandle tableGetter = null;
            MethodHandle versionGetter = null;
            boolean ok = false;
            try {
                final String asmPackage = MethodVisitor.class.getPackageName();
                final ClassLoader asmLoader = MethodVisitor.class.getClassLoader();
                writerType = Class.forName(asmPackage + "." + METHOD_WRITER_SIMPLE_NAME, false, asmLoader);
                final Class<?> symbolTableType =
                        Class.forName(asmPackage + "." + SYMBOL_TABLE_SIMPLE_NAME, false, asmLoader);
                final MethodHandles.Lookup callerLookup = MethodHandles.lookup();
                final MethodHandles.Lookup writerLookup = MethodHandles.privateLookupIn(writerType, callerLookup);
                tableGetter = writerLookup
                        .findGetter(writerType, SYMBOL_TABLE_FIELD, symbolTableType)
                        .asType(MethodType.methodType(Object.class, Object.class));
                final MethodHandles.Lookup tableLookup = MethodHandles.privateLookupIn(symbolTableType, callerLookup);
                versionGetter = tableLookup
                        .findGetter(symbolTableType, MAJOR_VERSION_FIELD, int.class)
                        .asType(MethodType.methodType(int.class, Object.class));
                ok = true;
            } catch (final Throwable unavailable) {
                FatalErrors.rethrowIfFatal(unavailable);
            }
            METHOD_WRITER_TYPE = writerType;
            SYMBOL_TABLE_GETTER = tableGetter;
            MAJOR_VERSION_GETTER = versionGetter;
            AVAILABLE = ok;
        }

        private static int majorVersion(final MethodVisitor writer) {
            try {
                final Object table = (Object) SYMBOL_TABLE_GETTER.invokeExact((Object) writer);
                return table == null ? 0 : (int) MAJOR_VERSION_GETTER.invokeExact(table);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                return 0;
            }
        }
    }

    private static String sanitizeCondyName(final String key) {
        final StringBuilder sanitized = new StringBuilder(SLOT_CONDY_NAME_PREFIX.length() + key.length());
        sanitized.append(SLOT_CONDY_NAME_PREFIX);
        for (int i = 0; i < key.length(); i++) {
            final char ch = key.charAt(i);
            if (ch == '.' || ch == ';' || ch == '[' || ch == '/') {
                sanitized.append('_');
            } else {
                sanitized.append(ch);
            }
        }
        return sanitized.toString();
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
