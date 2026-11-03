package dev.turboism.core.reflect;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Single entry point for locating and opening host members that are not public.
 *
 * <p>{@link #open(AccessibleObject)} is the replacement for {@code member.setAccessible(true)}:
 * access that is refused fails with the same {@link InaccessibleObjectException} (and
 * {@link SecurityException} still propagates), but through {@link AccessibleObject#trySetAccessible()}
 * so the refusal carries a message naming the member and call sites stay greppable.</p>
 *
 * <p>The lookup helpers below ({@link #findField(Class, String)}, {@link #readField(Object, String)},
 * {@link #findMethod(Class, String, Class[])}, {@link #tryInvokeZeroArg(Object, String)}) replace the
 * per-class hierarchy walks that used to be copy-pasted across the host adapter and appearance
 * bridges. They resolve through {@link MethodHandleCache}, so a given {@code (kind, class, name,
 * signature)} is walked and opened once per class loader and every later call is a map hit.</p>
 *
 * <p>Two failure policies exist on purpose and are not interchangeable:</p>
 * <ul>
 *   <li>the {@code find*} helpers are <em>fail-closed</em> on access — an unopenable member
 *       surfaces as {@link InaccessibleObjectException}, exactly as {@code setAccessible(true)} did,
 *       so a host contract that silently loses its access cannot degrade into a no-op;</li>
 *   <li>{@link #readField(Object, String)} and {@link #tryInvokeZeroArg(Object, String)} are the
 *       explicitly <em>null-tolerant</em> variants used by best-effort bridges: they report {@code
 *       null} for a missing member, a missing access grant, {@code null} input, or a throwing
 *       target. Call sites that must not swallow a broken contract use {@link #findField(Class,
 *       String)}/{@link #findMethod(Class, String, Class[])} and apply their own policy.</li>
 * </ul>
 */
public final class ReflectAccess {

    private static final Class<?>[] NO_PARAMETER_TYPES = new Class<?>[0];

    private ReflectAccess() {}

    /**
     * Makes {@code member} accessible and returns it.
     *
     * @throws InaccessibleObjectException when the member's module does not open it to the runtime
     */
    public static <T extends AccessibleObject> T open(final T member) {
        if (!member.trySetAccessible()) {
            throw new InaccessibleObjectException("Turboism cannot open host member: " + member);
        }
        return member;
    }

    /**
     * Finds and opens a field declared by {@code type} or by any of its superclasses.
     *
     * <p>The resolved {@link Field} is cached per class loader, and {@link #open(AccessibleObject)} is
     * applied once, so the returned handle is directly readable/writable by every caller.</p>
     *
     * @param type the class to start the walk from; {@code null} yields {@code null}
     * @param name the field name; {@code null} yields {@code null}
     * @return the opened field, or {@code null} when no class in the hierarchy declares it
     * @throws InaccessibleObjectException when the field is found but its module does not open it
     */
    public static Field findField(final Class<?> type, final String name) {
        if (type == null || name == null) {
            return null;
        }
        try {
            return open(MethodHandleCache.declaredFieldUp(type, name));
        } catch (NoSuchFieldException missing) {
            return null;
        }
    }

    /**
     * Reads the value of the field {@code name} on {@code target}, tolerating every ordinary failure.
     *
     * <p>This is the null-tolerant variant of the duplicated {@code field(Object, String)} helpers
     * that the host bridges carried: it returns {@code null} (rather than throwing) when
     * {@code target} or {@code name} is {@code null}, when no class in the target's hierarchy
     * declares the field, when the field cannot be opened, or when the read itself fails.</p>
     *
     * @param target the instance to read from; {@code null} yields {@code null}
     * @param name the field name; {@code null} yields {@code null}
     * @return the field value (which may legitimately be {@code null}), or {@code null} on failure
     */
    public static Object readField(final Object target, final String name) {
        if (target == null || name == null) {
            return null;
        }
        try {
            final Field field = findField(target.getClass(), name);
            return field == null ? null : field.get(target);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            // Best-effort contract: a missing/unopenable/failing member is "no value", never an error.
            return null;
        }
    }

    /**
     * Finds and opens a method declared by {@code type}, any superclass, or any implemented interface.
     *
     * <p>The walk is exact-signature: a declared method on the closest class wins, then the remaining
     * superclasses, then the public surface (which is what a legacy {@link Class#getMethod} call
     * resolved, covariantly-bridged overrides included), then non-public interface declarations
     * (default and static methods of package-private contracts). Candidates come from
     * {@link MethodHandleCache}, so the hierarchy scan is paid once per class loader; the returned
     * {@link Method} is already opened.</p>
     *
     * @param type the class to start the walk from; {@code null} yields {@code null}
     * @param name the method name; {@code null} yields {@code null}
     * @param parameterTypes the exact parameter types; {@code null} is treated as no parameters
     * @return the opened method, or {@code null} when nothing in the hierarchy matches
     * @throws InaccessibleObjectException when the method is found but its module does not open it
     */
    public static Method findMethod(final Class<?> type, final String name, final Class<?>... parameterTypes) {
        if (type == null || name == null) {
            return null;
        }
        final Class<?>[] parameters = parameterTypes == null ? NO_PARAMETER_TYPES : parameterTypes;
        for (Method candidate : MethodHandleCache.declaredOverloads(type, name, parameters.length)) {
            if (Arrays.equals(candidate.getParameterTypes(), parameters)) {
                return open(candidate);
            }
        }
        try {
            return open(MethodHandleCache.method(type, name, parameters));
        } catch (NoSuchMethodException notPublic) {
            return findInterfaceMethod(type, name, parameters);
        }
    }

    /**
     * Invokes a zero-argument method named {@code methodName} on {@code target}, tolerating failures.
     *
     * <p>Resolution goes through {@link #findMethod(Class, String, Class[])}, so inherited and
     * interface methods are reachable. Returns {@code null} when {@code target} or {@code methodName}
     * is {@code null}, when the method is missing, over-parameterised, inaccessible, or when the
     * invocation throws.</p>
     *
     * @param target the receiver; {@code null} yields {@code null}
     * @param methodName the zero-argument method name; {@code null} yields {@code null}
     * @return the invocation result (which may legitimately be {@code null}), or {@code null} on failure
     */
    public static Object tryInvokeZeroArg(final Object target, final String methodName) {
        if (target == null || methodName == null) {
            return null;
        }
        try {
            final Method method = findMethod(target.getClass(), methodName);
            if (method == null || method.getParameterCount() != 0) {
                return null;
            }
            return method.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            // Best-effort contract: an absent/refused/throwing member is "no value", never an error.
            return null;
        }
    }

    private static Method findInterfaceMethod(final Class<?> type, final String name, final Class<?>[] parameterTypes) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Class<?> contract : current.getInterfaces()) {
                final Method found = declaredInterfaceMethod(contract, name, parameterTypes);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Method declaredInterfaceMethod(
            final Class<?> contract, final String name, final Class<?>[] parameterTypes) {
        try {
            return open(contract.getDeclaredMethod(name, parameterTypes));
        } catch (NoSuchMethodException notDeclared) {
            for (Class<?> parent : contract.getInterfaces()) {
                final Method found = declaredInterfaceMethod(parent, name, parameterTypes);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
    }
}
