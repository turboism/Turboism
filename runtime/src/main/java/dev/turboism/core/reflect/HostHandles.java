package dev.turboism.core.reflect;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.WrongMethodTypeException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;

/**
 * The one host-member lookup policy for optimized host bridges.
 *
 * <p>Policy, and only this policy: the member is found by <em>exact JVM descriptor</em> on the
 * owner, its superclasses and then its public surface; unreflected through
 * {@link MethodHandles#privateLookupIn(Class, MethodHandles.Lookup)}, falling back to
 * {@link MethodHandles#publicLookup()} only when the declaring class and the member are both
 * public; adapted to the requested {@link MethodType}; and otherwise refused with a named
 * {@link Refusal}. There is no third path and no silent {@code null}.</p>
 *
 * <p>The host artifact lives in the unnamed module and is therefore open, so the private lookup is
 * the normal case rather than the exotic one: a bridge on an {@code --add-opens}-free launch
 * resolves exactly like one on an opened launch. A member that is genuinely not open is refused
 * with {@link Refusal#MODULE_CLOSED} instead of degrading into a no-op.</p>
 *
 * <p>Finding goes through {@link MethodHandleCache}, so a {@code (class, name, arity)} hierarchy
 * walk is paid once per class loader. Opening is deliberately not {@link ReflectAccess}: that helper
 * keeps its documented best-effort, null-tolerant contract for UI adapters, while a bridge that
 * cannot open a reviewed member must fail with a reason.</p>
 *
 * <p>Every entry point either returns a handle of exactly the requested shape or throws
 * {@link HostMemberRefused}, so callers keep one {@code ReflectiveOperationException} path.</p>
 */
public final class HostHandles {

    /** Why a host member could not be turned into a handle; one value per failure mode. */
    public enum Refusal {
        /** No member with the requested name and descriptor exists on the owner or its hierarchy. */
        MEMBER_ABSENT,
        /** The member exists but its module does not open it to the private lookup. */
        MODULE_CLOSED,
        /** The member exists but cannot be adapted to the requested {@link MethodType}. */
        SHAPE_MISMATCH,
        /** The owning class itself could not be loaded by the host loader. */
        OWNER_UNLOADABLE
    }

    /** A refused host member, carrying the failure reason and the declaration that was refused. */
    public static final class HostMemberRefused extends ReflectiveOperationException {

        private final Refusal reason;
        private final String declaration;

        HostMemberRefused(final Refusal reason, final String declaration, final Throwable cause) {
            super(reason + " " + declaration, cause);
            this.reason = Objects.requireNonNull(reason, "reason");
            this.declaration = Objects.requireNonNull(declaration, "declaration");
        }

        /** Which of the four failure modes applies. */
        public Refusal reason() {
            return reason;
        }

        /**
         * What was refused: {@code owner.name+descriptor} for a member, {@code owner.<init>+descriptor}
         * for a constructor, and the internal name for {@link HostHandles#load(ClassLoader, String)}.
         */
        public String declaration() {
            return declaration;
        }
    }

    /**
     * Opens one declaring class for member unreflection.
     *
     * @see #PRIVATE
     */
    @FunctionalInterface
    interface LookupFactory {

        /** The production policy: {@code privateLookupIn} through this class's own lookup. */
        LookupFactory PRIVATE = declaring -> MethodHandles.privateLookupIn(declaring, MethodHandles.lookup());

        /**
         * Returns a lookup that can unreflect members declared by {@code declaring}.
         *
         * @param declaring the class declaring the already-found member
         * @return a lookup with private access to that class
         * @throws IllegalAccessException when the declaring class's module is not open
         */
        MethodHandles.Lookup lookup(Class<?> declaring) throws IllegalAccessException;
    }

    /** Unreflection of one already-found {@link Member} through a lookup. */
    @FunctionalInterface
    private interface Unreflector {
        MethodHandle open(MethodHandles.Lookup lookup) throws IllegalAccessException;
    }

    private HostHandles() {}

    /**
     * Loads a host class by internal name without initialising it.
     *
     * @param hostLoader the admitted host loader; {@code null} means the bootstrap loader
     * @param internalName the internal name, e.g. {@code com/live2d/cubism/doc/model/CModelSource}
     * @return the loaded, not initialised class
     * @throws HostMemberRefused with {@link Refusal#OWNER_UNLOADABLE} when the loader cannot define it
     */
    public static Class<?> load(final ClassLoader hostLoader, final String internalName) throws HostMemberRefused {
        Objects.requireNonNull(internalName, "internalName");
        try {
            return Class.forName(internalName.replace('/', '.'), false, hostLoader);
        } catch (ClassNotFoundException | LinkageError unloadable) {
            throw new HostMemberRefused(Refusal.OWNER_UNLOADABLE, internalName, unloadable);
        }
    }

    /**
     * Resolves an instance method that {@code owner} answers as a receiver of {@code shape}.
     *
     * <p>{@code shape} is the caller's invocation type: its first parameter type is the receiver, so
     * {@code (Object)Object} adapts any reference-returning instance method.</p>
     *
     * @param owner the class the receiver is typed as
     * @param name the exact method name
     * @param descriptor the exact JVM method descriptor, e.g. {@code ()Lcom/live2d/x/A;}
     * @param shape the invocation type to adapt the handle to
     * @return a handle of exactly {@code shape}
     * @throws HostMemberRefused when the member is absent, closed or of an incompatible shape
     */
    public static MethodHandle virtual(
            final Class<?> owner, final String name, final String descriptor, final MethodType shape)
            throws HostMemberRefused {
        return virtual(LookupFactory.PRIVATE, owner, name, descriptor, shape);
    }

    static MethodHandle virtual(
            final LookupFactory lookups,
            final Class<?> owner,
            final String name,
            final String descriptor,
            final MethodType shape)
            throws HostMemberRefused {
        final Method member = findMethod(owner, name, descriptor);
        final String declaration = declaration(owner, name, descriptor);
        return adapt(open(member, lookups, lookup -> lookup.unreflect(member), declaration), shape, declaration);
    }

    /**
     * Resolves a static method of {@code owner}.
     *
     * @param owner the class declaring the static method
     * @param name the exact method name
     * @param descriptor the exact JVM method descriptor
     * @param shape the invocation type to adapt the handle to; it carries no receiver
     * @return a handle of exactly {@code shape}
     * @throws HostMemberRefused when the member is absent, closed or of an incompatible shape
     */
    public static MethodHandle staticCall(
            final Class<?> owner, final String name, final String descriptor, final MethodType shape)
            throws HostMemberRefused {
        return staticCall(LookupFactory.PRIVATE, owner, name, descriptor, shape);
    }

    static MethodHandle staticCall(
            final LookupFactory lookups,
            final Class<?> owner,
            final String name,
            final String descriptor,
            final MethodType shape)
            throws HostMemberRefused {
        final Method member = findMethod(owner, name, descriptor);
        final String declaration = declaration(owner, name, descriptor);
        return adapt(open(member, lookups, lookup -> lookup.unreflect(member), declaration), shape, declaration);
    }

    /**
     * Resolves a field getter.
     *
     * @param owner the class the receiver is typed as
     * @param name the exact field name
     * @param descriptor the field type descriptor, e.g. {@code Ljava/util/Map;}
     * @param shape the invocation type to adapt the handle to, receiver first, e.g. {@code (Object)Object}
     * @return a handle of exactly {@code shape}
     * @throws HostMemberRefused when the field is absent, closed or of an incompatible shape
     */
    public static MethodHandle getter(
            final Class<?> owner, final String name, final String descriptor, final MethodType shape)
            throws HostMemberRefused {
        return getter(LookupFactory.PRIVATE, owner, name, descriptor, shape);
    }

    static MethodHandle getter(
            final LookupFactory lookups,
            final Class<?> owner,
            final String name,
            final String descriptor,
            final MethodType shape)
            throws HostMemberRefused {
        final Field member = findField(owner, name, descriptor);
        final String declaration = declaration(owner, name, descriptor);
        return adapt(open(member, lookups, lookup -> lookup.unreflectGetter(member), declaration), shape, declaration);
    }

    /**
     * Resolves a field setter.
     *
     * @param owner the class the receiver is typed as
     * @param name the exact field name
     * @param descriptor the field type descriptor, e.g. {@code Ljava/util/Map;}
     * @param shape the invocation type to adapt the handle to, receiver first, e.g. {@code (Object,Object)void}
     * @return a handle of exactly {@code shape}
     * @throws HostMemberRefused when the field is absent, closed, final or of an incompatible shape
     */
    public static MethodHandle setter(
            final Class<?> owner, final String name, final String descriptor, final MethodType shape)
            throws HostMemberRefused {
        return setter(LookupFactory.PRIVATE, owner, name, descriptor, shape);
    }

    static MethodHandle setter(
            final LookupFactory lookups,
            final Class<?> owner,
            final String name,
            final String descriptor,
            final MethodType shape)
            throws HostMemberRefused {
        final Field member = findField(owner, name, descriptor);
        final String declaration = declaration(owner, name, descriptor);
        return adapt(open(member, lookups, lookup -> lookup.unreflectSetter(member), declaration), shape, declaration);
    }

    /**
     * Resolves a constructor.
     *
     * @param owner the class to construct
     * @param descriptor the exact constructor descriptor, e.g. {@code (I)V}
     * @param shape the invocation type to adapt the handle to; it carries no receiver
     * @return a handle of exactly {@code shape}
     * @throws HostMemberRefused when the constructor is absent, closed or of an incompatible shape
     */
    public static MethodHandle construct(final Class<?> owner, final String descriptor, final MethodType shape)
            throws HostMemberRefused {
        return construct(LookupFactory.PRIVATE, owner, descriptor, shape);
    }

    static MethodHandle construct(
            final LookupFactory lookups, final Class<?> owner, final String descriptor, final MethodType shape)
            throws HostMemberRefused {
        final String declaration = owner.getName() + ".<init>" + descriptor;
        for (final Constructor<?> candidate : owner.getDeclaredConstructors()) {
            if (MethodType.methodType(void.class, candidate.getParameterTypes())
                    .descriptorString()
                    .equals(descriptor)) {
                return adapt(
                        open(candidate, lookups, lookup -> lookup.unreflectConstructor(candidate), declaration),
                        shape,
                        declaration);
            }
        }
        throw new HostMemberRefused(Refusal.MEMBER_ABSENT, declaration, null);
    }

    /** Exact-descriptor method lookup: hierarchy first (closest class wins), then the public surface. */
    private static Method findMethod(final Class<?> owner, final String name, final String descriptor)
            throws HostMemberRefused {
        final int arity = arity(descriptor);
        for (final Method candidate : MethodHandleCache.declaredOverloads(owner, name, arity)) {
            if (descriptor(candidate).equals(descriptor)) {
                return candidate;
            }
        }
        for (final Method candidate : MethodHandleCache.overloads(owner, name, arity)) {
            if (descriptor(candidate).equals(descriptor)) {
                return candidate;
            }
        }
        throw new HostMemberRefused(Refusal.MEMBER_ABSENT, declaration(owner, name, descriptor), null);
    }

    /** Exact-descriptor field lookup: hierarchy first, then the public surface. */
    private static Field findField(final Class<?> owner, final String name, final String descriptor)
            throws HostMemberRefused {
        Class<?> current = owner;
        while (current != null) {
            final Field candidate;
            try {
                candidate = MethodHandleCache.declaredFieldUp(current, name);
            } catch (NoSuchFieldException absent) {
                break;
            }
            if (candidate.getType().descriptorString().equals(descriptor)) {
                return candidate;
            }
            // A same-named field of another type is not the reviewed member: keep walking above it.
            current = candidate.getDeclaringClass().getSuperclass();
        }
        try {
            final Field candidate = owner.getField(name);
            if (candidate.getType().descriptorString().equals(descriptor)) {
                return candidate;
            }
        } catch (NoSuchFieldException absent) {
            // fall through to the refusal below
        }
        throw new HostMemberRefused(Refusal.MEMBER_ABSENT, declaration(owner, name, descriptor), null);
    }

    /**
     * Unreflects one found member through the private lookup, falling back to the public lookup only
     * for a member whose declaring class and modifiers are both public.
     */
    private static MethodHandle open(
            final Member member, final LookupFactory lookups, final Unreflector unreflector, final String declaration)
            throws HostMemberRefused {
        Objects.requireNonNull(lookups, "lookups");
        try {
            return unreflector.open(lookups.lookup(member.getDeclaringClass()));
        } catch (IllegalAccessException refused) {
            if (publiclyOpen(member)) {
                try {
                    return unreflector.open(MethodHandles.publicLookup());
                } catch (IllegalAccessException alsoRefused) {
                    refused.addSuppressed(alsoRefused);
                }
            }
            throw new HostMemberRefused(Refusal.MODULE_CLOSED, declaration, refused);
        }
    }

    private static boolean publiclyOpen(final Member member) {
        return Modifier.isPublic(member.getModifiers())
                && Modifier.isPublic(member.getDeclaringClass().getModifiers());
    }

    /** Adapts to the caller's invocation type; only an unadaptable shape is a refusal here. */
    private static MethodHandle adapt(final MethodHandle handle, final MethodType shape, final String declaration)
            throws HostMemberRefused {
        Objects.requireNonNull(shape, "shape");
        try {
            return handle.asType(shape);
        } catch (WrongMethodTypeException mismatch) {
            throw new HostMemberRefused(Refusal.SHAPE_MISMATCH, declaration, mismatch);
        }
    }

    private static String descriptor(final Method method) {
        return MethodType.methodType(method.getReturnType(), method.getParameterTypes())
                .descriptorString();
    }

    private static String declaration(final Class<?> owner, final String name, final String descriptor) {
        return owner.getName() + "." + name + descriptor;
    }

    /**
     * Parameter count encoded in a method descriptor, computed textually so a lookup never loads a
     * parameter type. A malformed descriptor yields a count no real member has, so the exact
     * descriptor comparison in the finders stays the only acceptance rule.
     */
    private static int arity(final String descriptor) {
        int parameters = 0;
        int index = 1;
        while (index < descriptor.length() && descriptor.charAt(index) != ')') {
            while (index < descriptor.length() && descriptor.charAt(index) == '[') {
                index++;
            }
            if (index >= descriptor.length()) {
                break;
            }
            if (descriptor.charAt(index) == 'L') {
                final int end = descriptor.indexOf(';', index);
                if (end < 0) {
                    break;
                }
                index = end + 1;
            } else {
                index++;
            }
            parameters++;
        }
        return parameters;
    }
}
