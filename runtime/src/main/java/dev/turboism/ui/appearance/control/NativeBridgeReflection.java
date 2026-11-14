package dev.turboism.ui.appearance.control;

import dev.turboism.core.reflect.MethodHandleCache;
import java.lang.reflect.Method;

/** Reflection primitives shared by the native appearance bridges. */
final class NativeBridgeReflection {

    /**
     * Invokes a no-argument method and returns {@code null} when the method stays inaccessible.
     *
     * @param target the receiver
     * @param name the method name
     * @return the invocation result, or {@code null} when the method cannot be opened
     * @throws ReflectiveOperationException when the invocation itself fails
     */
    static Object invoke(final Object target, final String name) throws ReflectiveOperationException {
        final Method method = MethodHandleCache.method(target.getClass(), name);
        if (!method.canAccess(target) && !method.trySetAccessible()) {
            return null;
        }
        return method.invoke(target);
    }

    /**
     * Reports whether {@code type} or one of its superclasses carries the expected binary name.
     *
     * @param type the class to inspect
     * @param expected the fully qualified class name to match
     * @return {@code true} when the type or a superclass matches
     */
    static boolean isTypeOrSuper(final Class<?> type, final String expected) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (current.getName().equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private NativeBridgeReflection() {}
}
