package dev.turboism.adapter.cubism.mesh;

import dev.turboism.core.reflect.MethodHandleCache;
import dev.turboism.core.reflect.ReflectAccess;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflective host access shared by the mesh-mirror bridge and its extracted helpers.
 *
 * <p>Every lookup fails closed: an unresolved member surfaces as a {@link NoSuchMethodException}
 * or {@link NoSuchFieldException} at the call site instead of a silent default. Resolved members
 * are cached per class and class loader, so the high-frequency dispatch path never re-walks a
 * hierarchy or re-runs {@code Class.forName}.
 */
final class NativeMeshMirrorReflectionOps {
    static final Class<?>[] NO_PARAMS = new Class<?>[0];

    private static final ConcurrentHashMap<Class<?>, Optional<Constructor<?>>> VECTOR_CONSTRUCTORS =
            new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<Class<?>, Object[]> ENUM_CONSTANTS = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<ConstructorKey, Optional<Constructor<?>>> HOST_CONSTRUCTORS =
            new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<FieldKey, Optional<Field>> STATIC_FIELDS = new ConcurrentHashMap<>();

    private record ConstructorKey(ClassLoader loader, String className, List<Class<?>> parameters) {}

    private record FieldKey(ClassLoader loader, String className, String fieldName) {}

    // ------------------------------------------------------------------
    // Member resolution. The host overloads single-letter names many times over
    // unrelated types, so a lookup that matched on argument count alone could
    // invoke the wrong member against user mesh data.
    // ------------------------------------------------------------------

    /**
     * Strict lookup by exact parameter types, falling back to a public assignable match.
     *
     * <p>The host mirror class overloads the name {@code a} many times over unrelated types, so
     * matching on argument count alone could invoke the wrong one against user mesh data.
     */
    static Method declaredMethod(final Class<?> owner, final String name, final Class<?>... parameters) {
        try {
            return MethodHandleCache.declaredUp(owner, name, parameters);
        } catch (NoSuchMethodException ignored) {
            // Fall through to the public assignable-match fallback.
        }
        for (Method method : MethodHandleCache.overloads(owner, name, parameters.length)) {
            final Class<?>[] actual = method.getParameterTypes();
            boolean matches = true;
            for (int index = 0; index < actual.length; index++) {
                if (!actual[index].isAssignableFrom(parameters[index])) {
                    matches = false;
                    break;
                }
            }
            if (matches) return method;
        }
        return null;
    }

    /** Invokes the strictly resolved member, or returns {@code null} when the target is absent. */
    static Object call(final Object target, final String name, final Class<?>[] parameters, final Object... arguments)
            throws ReflectiveOperationException {
        if (target == null) return null;
        final Method method = declaredMethod(target.getClass(), name, parameters);
        return method == null ? null : method.invoke(target, arguments);
    }

    // ------------------------------------------------------------------
    // Member invocation.
    // ------------------------------------------------------------------

    /** Fail-closed field read: an absent or unopenable field stays a reflective failure. */
    private static Object field(final Object target, final String name) throws ReflectiveOperationException {
        final Field field = ReflectAccess.findField(target == null ? null : target.getClass(), name);
        if (field == null) throw new NoSuchFieldException(name);
        return field.get(target);
    }

    /** getXxx() → getXxx$cubism() → field; a null target throws (fail-closed). */
    static Object property(final Object target, final String name) throws ReflectiveOperationException {
        if (target == null) throw new NoSuchFieldException(name);
        final String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        Object value = tryInvoke(target, getter);
        if (value == null) value = tryInvoke(target, getter + "$cubism");
        if (value == null) value = field(target, name);
        return value;
    }

    private static Object tryInvoke(final Object target, final String name) {
        return ReflectAccess.tryInvokeZeroArg(target, name);
    }

    static Object invokeEither(final Object target, final String first, final String second)
            throws ReflectiveOperationException {
        try {
            return invoke(target, first);
        } catch (NoSuchMethodException ignored) {
            return invoke(target, second);
        }
    }

    static Number number(final Object value) {
        if (value instanceof Number number) return number;
        throw new IllegalArgumentException("expected number");
    }

    static Object invoke(final Object target, final String name, final Object... arguments)
            throws ReflectiveOperationException {
        if (target == null) throw new NoSuchMethodException(name);
        for (Method method : MethodHandleCache.overloads(target.getClass(), name, arguments.length)) {
            try {
                return method.invoke(target, arguments);
            } catch (IllegalArgumentException ignored) {
                // Try the next overload.
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "#" + name);
    }

    static boolean invokeVoid(final Object target, final String name, final Object... arguments)
            throws ReflectiveOperationException {
        invoke(target, name, arguments);
        return true;
    }

    static float coordinate(final Object point, final String getter) throws ReflectiveOperationException {
        return number(invoke(point, getter)).floatValue();
    }

    // ------------------------------------------------------------------
    // Host value construction.
    // ------------------------------------------------------------------

    private static Constructor<?> hostConstructor(
            final ClassLoader loader, final String className, final Class<?>... parameters)
            throws ReflectiveOperationException {
        final ConstructorKey key = new ConstructorKey(loader, className, List.of(parameters));
        return HOST_CONSTRUCTORS
                .computeIfAbsent(key, unresolved -> {
                    try {
                        return Optional.of(Class.forName(unresolved.className(), false, unresolved.loader())
                                .getConstructor(unresolved.parameters().toArray(Class<?>[]::new)));
                    } catch (ReflectiveOperationException | RuntimeException failure) {
                        return Optional.empty();
                    }
                })
                .orElseThrow(() -> new NoSuchMethodException(className));
    }

    static Object staticField(final ClassLoader loader, final String className, final String fieldName)
            throws ReflectiveOperationException {
        final Field field = STATIC_FIELDS
                .computeIfAbsent(new FieldKey(loader, className, fieldName), key -> {
                    try {
                        final Field resolved = Class.forName(key.className(), false, key.loader())
                                .getDeclaredField(key.fieldName());
                        ReflectAccess.open(resolved);
                        return Optional.of(resolved);
                    } catch (ReflectiveOperationException | RuntimeException failure) {
                        return Optional.empty();
                    }
                })
                .orElseThrow(() -> new NoSuchFieldException(className + "#" + fieldName));
        return field.get(null);
    }

    static Object vector(final Object host, final MeshMirrorGeometry.Point point) throws ReflectiveOperationException {
        return hostConstructor(
                        host.getClass().getClassLoader(),
                        "com.live2d.graphics3d.type.GVector2",
                        float.class,
                        float.class)
                .newInstance(point.x(), point.y());
    }

    static Object point(final ClassLoader loader, final int x, final int y) throws ReflectiveOperationException {
        return hostConstructor(loader, "com.live2d.type.CPoint", int.class, int.class)
                .newInstance(x, y);
    }

    static Object enumConstant(final Class<?> type, final String name) {
        if (!type.isEnum()) return null;
        for (Object value : ENUM_CONSTANTS.computeIfAbsent(type, Class::getEnumConstants)) {
            if (value instanceof Enum<?> item && item.name().equals(name)) return value;
        }
        return null;
    }

    static Constructor<?> vectorConstructor(final Class<?> vectorType) {
        return VECTOR_CONSTRUCTORS
                .computeIfAbsent(vectorType, type -> {
                    try {
                        final Constructor<?> constructor = type.getDeclaredConstructor(float.class, float.class);
                        ReflectAccess.open(constructor);
                        return Optional.of(constructor);
                    } catch (ReflectiveOperationException | RuntimeException failure) {
                        return Optional.empty();
                    }
                })
                .orElse(null);
    }

    static Method movePointMethod(final Class<?> pointType, final Class<?> vectorType) {
        for (Method method : MethodHandleCache.declaredOverloads(pointType, "moveToOnLocal", 2)) {
            final Class<?>[] parameters = method.getParameterTypes();
            if (!parameters[0].isAssignableFrom(vectorType) || parameters[1] != float.class) continue;
            try {
                ReflectAccess.open(method);
                return method;
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    static Method findMethod(
            final Class<?> owner,
            final String name,
            final Class<?> first,
            final Class<?> second,
            final Class<?> third) {
        for (Method method : MethodHandleCache.overloads(owner, name, 3)) {
            final Class<?>[] parameters = method.getParameterTypes();
            if (parameters[0] != first || parameters[1] != second) continue;
            if (third != null && parameters[2] != third) continue;
            return method;
        }
        return null;
    }

    static Method findMethod(final Class<?> owner, final String name, final Class<?> first, final Class<?> second) {
        for (Method method : MethodHandleCache.overloads(owner, name, 2)) {
            final Class<?>[] parameters = method.getParameterTypes();
            if (parameters[0] == first && (second == null || parameters[1] == second)) return method;
        }
        return null;
    }

    static boolean hasUndoAttachmentMethod(final Object groupUndo) {
        return !MethodHandleCache.overloads(groupUndo.getClass(), "plusAssign", 1)
                        .isEmpty()
                || !MethodHandleCache.declaredOverloads(groupUndo.getClass(), "plusAssign", 1)
                        .isEmpty();
    }

    // ------------------------------------------------------------------
    // Identity and collection helpers. Host handles have no useful equals(), so every
    // membership question here is answered by reference.
    // ------------------------------------------------------------------

    static boolean containsIdentity(final Iterable<?> collected, final Object candidate) {
        for (Object existing : collected) if (existing == candidate) return true;
        return false;
    }

    /** Identity-keyed membership set for repeated scans within one dispatch frame. */
    static Set<Object> identitySet(final Iterable<?> items) {
        final Set<Object> set = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object item : items) set.add(item);
        return set;
    }

    static boolean containsSame(final List<?> collected, final Object candidate) {
        for (Object existing : collected) if (existing == candidate) return true;
        return false;
    }

    /** Flattens the host's list-of-lists deletion argument, and tolerates a plain list. */
    static List<Object> flatten(final Object sources) {
        final List<Object> flattened = new ArrayList<>();
        if (!(sources instanceof Collection<?> outer)) return flattened;
        for (Object element : outer) {
            if (element instanceof Collection<?> inner) flattened.addAll(inner);
            else if (element != null) flattened.add(element);
        }
        return flattened;
    }

    // ------------------------------------------------------------------
    // Live mesh accessors.
    // ------------------------------------------------------------------

    static List<?> contexts(final Object pack) throws ReflectiveOperationException {
        final Object value = call(pack, "aT", NO_PARAMS);
        return value instanceof List<?> list ? list : List.of();
    }

    static List<?> points(final Object mesh) throws ReflectiveOperationException {
        final Object value = call(mesh, "getAllPointRef", NO_PARAMS);
        return value instanceof List<?> list ? list : List.of();
    }

    static Iterable<?> edges(final Object mesh) throws ReflectiveOperationException {
        final Object value = call(mesh, "getEdges", NO_PARAMS);
        return value instanceof Iterable<?> iterable ? iterable : List.of();
    }

    static Object pointById(final Object mesh, final int id) throws ReflectiveOperationException {
        for (Object candidate : points(mesh)) {
            if (pointId(candidate) == id) return candidate;
        }
        return null;
    }

    static int pointId(final Object point) throws ReflectiveOperationException {
        final Object value = call(point, "b", NO_PARAMS);
        return value instanceof Number number ? number.intValue() : Integer.MIN_VALUE;
    }

    static Object pointMesh(final Object point) throws ReflectiveOperationException {
        final Object mesh = call(point, "a", NO_PARAMS);
        return mesh == null ? call(point, "getMesh", NO_PARAMS) : mesh;
    }
}
