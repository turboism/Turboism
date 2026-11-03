package dev.turboism.adapter.cubism.optimization.clipmask;

import dev.turboism.core.reflect.ReflectAccess;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Resolved host accessors shared by the clipping-mask bridges.
 *
 * <p>Both bridges sit on the host's per-drawable render path, so every reviewed host member is
 * resolved once at construction into a {@link MethodHandle} whose {@link MethodType} is already
 * adapted to the bridge's internal call shape (receiver first, host types widened to
 * {@link Object}). Steady-state use is then a plain {@code invokeExact} site: no
 * {@code Method.invoke} argument array, no boxing of primitive results and no reflective
 * dispatch, so the JIT can inline the host accessor and constant-fold the bridges' own
 * branches.</p>
 *
 * <p>Adaptation is deliberate: {@code asType} only inserts a reference cast on the receiver, so a
 * host member whose signature differs from the reviewed shape fails closed with a
 * {@link ClassCastException} inside the bridge's existing {@code catch (Throwable)} containment
 * instead of silently calling the wrong member.</p>
 */
final class ClipMaskHandles {

    private static final MethodHandles.Lookup PUBLIC = MethodHandles.publicLookup();

    private ClipMaskHandles() {}

    /**
     * Resolves a public host method without adapting it, for callers that must apply their own
     * shape (see the material setter adaptation in the dedupe bridge).
     *
     * @param owner      host class
     * @param name       host method name
     * @param parameters reviewed host parameter types
     * @return the resolved handle in its declared shape
     * @throws ReflectiveOperationException when the reviewed member is absent
     */
    static MethodHandle declared(final Class<?> owner, final String name, final Class<?>... parameters)
            throws ReflectiveOperationException {
        return resolve(owner.getMethod(name, parameters));
    }

    /**
     * Resolves a public host method and adapts it to the bridge's call shape.
     *
     * @param owner      host class
     * @param name       host method name
     * @param signature  internal shape, receiver first and host types widened to {@code Object}
     * @param parameters reviewed host parameter types
     * @return the adapted handle
     * @throws ReflectiveOperationException when the reviewed member is absent
     */
    static MethodHandle virtual(
            final Class<?> owner, final String name, final MethodType signature, final Class<?>... parameters)
            throws ReflectiveOperationException {
        return resolve(owner.getMethod(name, parameters)).asType(signature);
    }

    /**
     * Resolves a public host constructor and adapts it to the bridge's call shape.
     *
     * @param owner      host class
     * @param signature  internal shape, host types widened to {@code Object}
     * @param parameters reviewed host parameter types
     * @return the adapted handle
     * @throws ReflectiveOperationException when the reviewed constructor is absent
     */
    static MethodHandle construct(final Class<?> owner, final MethodType signature, final Class<?>... parameters)
            throws ReflectiveOperationException {
        return resolve(owner.getConstructor(parameters)).asType(signature);
    }

    /**
     * Unreflects through the host's own module when it is open to this one, so a reviewed member
     * declared by a non-public host class still resolves, and falls back to the public lookup.
     */
    private static MethodHandle resolve(final Method method) throws ReflectiveOperationException {
        try {
            return MethodHandles.privateLookupIn(method.getDeclaringClass(), MethodHandles.lookup())
                    .unreflect(method);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError refused) {
            return PUBLIC.unreflect(method);
        }
    }

    private static MethodHandle resolve(final Constructor<?> constructor) throws ReflectiveOperationException {
        try {
            return MethodHandles.privateLookupIn(constructor.getDeclaringClass(), MethodHandles.lookup())
                    .unreflectConstructor(constructor);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError refused) {
            return PUBLIC.unreflectConstructor(constructor);
        }
    }

    /**
     * Resolves the host field carrying the current mask lease on the render system.
     *
     * <p>The render system keeps the lease in a static field, which the woven mask pass reads and
     * writes several times per mask set. A {@code VarHandle} derived from a private lookup removes
     * the reflective field access; when the host artifact's module does not open that member the
     * accessor falls back to {@link java.lang.reflect.Field} so behaviour is unchanged.</p>
     *
     * @param owner declaring class
     * @param name  field name
     * @return the accessor
     * @throws NoSuchFieldException when the reviewed field is absent
     */
    static FieldAccess field(final Class<?> owner, final String name) throws NoSuchFieldException {
        final Field declared = owner.getDeclaredField(name);
        final boolean onInstance = !Modifier.isStatic(declared.getModifiers());
        final Class<?> type = declared.getType();
        // Getter and setter resolve independently: the mask-lease pool singleton is a
        // static final field, whose setter the JVM refuses while its getter stays usable.
        MethodHandle getter = null;
        MethodHandle setter = null;
        try {
            final MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(owner, MethodHandles.lookup());
            getter = adapt(
                    onInstance ? lookup.findGetter(owner, name, type) : lookup.findStaticGetter(owner, name, type),
                    onInstance,
                    MethodType.methodType(Object.class, Object.class));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError refused) {
            // Reflective read fallback below keeps the bridge usable.
        }
        try {
            final MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(owner, MethodHandles.lookup());
            setter = adapt(
                    onInstance ? lookup.findSetter(owner, name, type) : lookup.findStaticSetter(owner, name, type),
                    onInstance,
                    MethodType.methodType(void.class, Object.class, Object.class));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError refused) {
            // Final or unopened fields keep the reflective write path (which fails loudly).
        }
        return new FieldAccess(declared, getter, setter);
    }

    private static MethodHandle adapt(final MethodHandle raw, final boolean onInstance, final MethodType type) {
        return (onInstance ? raw : MethodHandles.dropArguments(raw, 0, Object.class)).asType(type);
    }

    /** One host field: {@code VarHandle}-backed when permitted, reflective otherwise. */
    static final class FieldAccess {

        private final Field field;
        private final MethodHandle getter;
        private final MethodHandle setter;
        private boolean opened;

        private FieldAccess(final Field field, final MethodHandle getter, final MethodHandle setter) {
            this.field = field;
            this.getter = getter;
            this.setter = setter;
        }

        /** @return the declared host type of this field */
        Class<?> type() {
            return field.getType();
        }

        Object get(final Object owner) throws Throwable {
            if (getter != null) return (Object) getter.invokeExact(owner);
            return open().get(owner);
        }

        void set(final Object owner, final Object value) throws Throwable {
            if (setter != null) {
                setter.invokeExact(owner, value);
                return;
            }
            open().set(owner, value);
        }

        private Field open() {
            if (!opened) {
                ReflectAccess.open(field);
                opened = true;
            }
            return field;
        }
    }
}
