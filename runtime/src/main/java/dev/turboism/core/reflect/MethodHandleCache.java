package dev.turboism.core.reflect;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe per-signature cache of resolved {@link Method} handles for hot reflective host paths.
 *
 * <p>Replaces repeated per-call {@code getMethod}/{@code getMethods}/{@code getDeclaredMethod}
 * scans in the host adapter layer (palette filtering, tree appearance bridges, physics/mesh UI
 * injection, screenshot capture). Keys use identity semantics for {@link Class} (a host class
 * loaded by a different class loader is a distinct cache entry), so the cache stays correct
 * across plugin-isolated class loaders while being hit on the steady-state host paths.</p>
 *
 * <p>Only successful resolutions are cached; a missing method is rescanned on each call, matching
 * the previous per-call behavior. Lookups that require access to non-public members attempt
 * {@link Method#trySetAccessible()} once at resolution time (the granted access persists on the
 * cached {@link Method}); when that fails and the member is not already accessible, the lookup
 * fails closed with {@link NoSuchMethodException} so callers keep their existing
 * {@code ReflectiveOperationException} handling.</p>
 */
public final class MethodHandleCache {

    private enum Kind {
        /** Public lookup: {@link Class#getMethod}, inherited methods included. */
        PUBLIC,
        /** Declared on the exact class only (no superclass walk). */
        DECLARED,
        /** Declared walk: exact class, then superclasses, matched by parameter types. */
        DECLARED_UP,
        /** Declared walk: exact class, then superclasses, first match by name + arity. */
        DECLARED_UP_ARITY,
        /** Public overload list by name + arity ({@link Class#getMethods}). */
        PUBLIC_ARITY
    }

    private record MethodKey(Kind kind, Class<?> type, String name, List<Class<?>> parameterTypes, int arity) {
        MethodKey(final Kind kind, final Class<?> type, final String name, final Class<?>[] parameterTypes) {
            this(kind, type, name, parameterTypes.length == 0 ? List.of() : List.of(parameterTypes), -1);
        }

        MethodKey(final Kind kind, final Class<?> type, final String name, final int arity) {
            this(kind, type, name, List.of(), arity);
        }
    }

    private static final ConcurrentHashMap<MethodKey, Method> METHODS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<MethodKey, List<Method>> OVERLOADS = new ConcurrentHashMap<>();

    private MethodHandleCache() { }

    /**
     * Public method lookup (inherited methods included), equivalent to {@link Class#getMethod}.
     *
     * @throws NoSuchMethodException when no public method matches
     */
    public static Method method(final Class<?> type, final String name, final Class<?>... parameterTypes)
        throws NoSuchMethodException {
        final MethodKey key = new MethodKey(Kind.PUBLIC, type, name, parameterTypes);
        final Method cached = METHODS.get(key);
        if (cached != null) {
            return cached;
        }
        final Method resolved = type.getMethod(name, parameterTypes);
        final Method existing = METHODS.putIfAbsent(key, resolved);
        // Return the canonical cached instance so concurrent resolvers observe the same handle.
        return existing == null ? resolved : existing;
    }

    /**
     * Declared method lookup on the exact class only, equivalent to {@link Class#getDeclaredMethod},
     * with the non-public access policy applied (see class javadoc).
     *
     * @throws NoSuchMethodException when the class has no such declared method or it cannot be accessed
     */
    public static Method declared(final Class<?> type, final String name, final Class<?>... parameterTypes)
        throws NoSuchMethodException {
        final MethodKey key = new MethodKey(Kind.DECLARED, type, name, parameterTypes);
        final Method cached = METHODS.get(key);
        if (cached != null) {
            return cached;
        }
        return resolve(key, type.getDeclaredMethod(name, parameterTypes));
    }

    /**
     * Declared lookup walking the class hierarchy (exact class first, then superclasses),
     * equivalent to the legacy {@code getDeclaredMethod} loop, with the access policy applied.
     *
     * @throws NoSuchMethodException when no class in the hierarchy declares the method or it cannot be accessed
     */
    public static Method declaredUp(final Class<?> type, final String name, final Class<?>... parameterTypes)
        throws NoSuchMethodException {
        final MethodKey key = new MethodKey(Kind.DECLARED_UP, type, name, parameterTypes);
        final Method cached = METHODS.get(key);
        if (cached != null) {
            return cached;
        }
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            final Method candidate;
            try {
                candidate = current.getDeclaredMethod(name, parameterTypes);
            } catch (NoSuchMethodException ignored) {
                // try the next superclass
                continue;
            }
            return resolve(key, candidate);
        }
        throw new NoSuchMethodException(type.getName() + "#" + name);
    }

    /**
     * Declared lookup walking the class hierarchy and returning the first method matching
     * {@code name} and {@code arity} (parameter types are not known at the call site), with the
     * access policy applied.
     *
     * @throws NoSuchMethodException when no class in the hierarchy declares a matching method
     */
    public static Method declaredByArity(final Class<?> type, final String name, final int arity)
        throws NoSuchMethodException {
        final MethodKey key = new MethodKey(Kind.DECLARED_UP_ARITY, type, name, arity);
        final Method cached = METHODS.get(key);
        if (cached != null) {
            return cached;
        }
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method candidate : current.getDeclaredMethods()) {
                if (candidate.getName().equals(name) && candidate.getParameterCount() == arity) {
                    return resolve(key, candidate);
                }
            }
        }
        throw new NoSuchMethodException(type.getName() + "#" + name);
    }

    /**
     * Cached list of all public methods (inherited included) matching {@code name} and {@code arity},
     * equivalent to the legacy {@code getMethods()} scan. The returned list is immutable and the
     * iteration order is the one observed at first resolution (callers that previously relied on the
     * unspecified {@code getMethods()} order keep working; the try-next-overload pattern is preserved).
     */
    public static List<Method> overloads(final Class<?> type, final String name, final int arity) {
        final MethodKey key = new MethodKey(Kind.PUBLIC_ARITY, type, name, arity);
        final List<Method> cached = OVERLOADS.get(key);
        if (cached != null) {
            return cached;
        }
        final List<Method> resolved = new ArrayList<>();
        for (Method candidate : type.getMethods()) {
            if (candidate.getName().equals(name) && candidate.getParameterCount() == arity) {
                resolved.add(candidate);
            }
        }
        final List<Method> stored = List.copyOf(resolved);
        final List<Method> existing = OVERLOADS.putIfAbsent(key, stored);
        return existing == null ? stored : existing;
    }

    private static Method resolve(final MethodKey key, final Method method) throws NoSuchMethodException {
        if (!method.trySetAccessible() && !method.canAccess(null)) {
            throw new NoSuchMethodException("inaccessible " + method);
        }
        final Method existing = METHODS.putIfAbsent(key, method);
        // Return the canonical cached instance so concurrent resolvers observe the same handle.
        return existing == null ? method : existing;
    }
}
