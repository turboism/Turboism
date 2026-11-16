package dev.turboism.adapter.cubism.optimization;

import dev.turboism.core.reflect.HostHandles;
import dev.turboism.core.reflect.MethodHandleCache;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * Proves that a reviewed host member still exists with its exact JVM descriptor before a weave
 * rewrites the class that declares it.
 *
 * <p>Presence, not invocation. The reviewed contract already pins the member's descriptor, so a
 * weave that cannot find it must fail closed instead of installing against a different host. The
 * lookup policy is the one {@link HostHandles} documents: exact descriptor over the declaring
 * class hierarchy first, then the public surface. Finding goes through {@link MethodHandleCache},
 * and opening goes through {@link HostHandles#unreflect(Method)}, so a hook and its bridge never
 * disagree about what "the reviewed member" means.</p>
 *
 * <p>A refusal names the declaration that went missing, because an install that fails here is
 * reported to the operator as a host-contract mismatch rather than as a silent no-op.</p>
 */
public final class HostMemberVerifier {

    /** Separator between an owner and its member in a refusal message. */
    private static final String MEMBER_SEPARATOR = ".";

    /** Separator between a field name and its type descriptor in a refusal message. */
    private static final String FIELD_SEPARATOR = ":";

    private HostMemberVerifier() {}

    /**
     * Requires {@code owner.name+descriptor} to exist as a method and to be openable under the
     * shared host lookup policy.
     *
     * @param owner the class the reviewed contract names as the declaring owner
     * @param name the exact method name
     * @param descriptor the exact JVM method descriptor
     * @throws HostHandles.HostMemberRefused when the member is absent or its module is closed
     * @throws NoSuchMethodException when no member of the owner carries that exact descriptor
     */
    public static void requireMethod(final Class<?> owner, final String name, final String descriptor)
            throws ReflectiveOperationException {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        HostHandles.unreflect(find(owner, name, descriptor));
    }

    /**
     * Requires {@code owner.name:descriptor} to exist as a field declared by {@code owner}.
     *
     * @param owner the class the reviewed contract names as the declaring owner
     * @param name the exact field name
     * @param descriptor the exact JVM field type descriptor
     * @throws HostHandles.HostMemberRefused when {@code owner} declares no such field
     * @throws NoSuchFieldException when the field exists with another type
     */
    public static void requireField(final Class<?> owner, final String name, final String descriptor)
            throws ReflectiveOperationException {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        final HostHandles.HostField field = HostHandles.field(owner, name);
        if (!field.type().descriptorString().equals(descriptor)) {
            throw new NoSuchFieldException(owner.getName() + MEMBER_SEPARATOR + name + FIELD_SEPARATOR + descriptor);
        }
    }

    /**
     * Finds the one method of {@code owner} whose descriptor is exactly {@code descriptor}.
     *
     * <p>Declared members win over inherited ones, which is the order {@link HostHandles} resolves
     * in; a same-named overload of another descriptor is never accepted.</p>
     *
     * @param owner the class the reviewed contract names as the declaring owner
     * @param name the exact method name
     * @param descriptor the exact JVM method descriptor
     * @return the reviewed member
     * @throws NoSuchMethodException when no member of the owner carries that exact descriptor
     */
    private static Method find(final Class<?> owner, final String name, final String descriptor)
            throws NoSuchMethodException {
        final int arity = arity(descriptor);
        for (final Method candidate : MethodHandleCache.declaredOverloads(owner, name, arity)) {
            if (descriptor(candidate).equals(descriptor)) return candidate;
        }
        for (final Method candidate : MethodHandleCache.overloads(owner, name, arity)) {
            if (descriptor(candidate).equals(descriptor)) return candidate;
        }
        throw new NoSuchMethodException(owner.getName() + MEMBER_SEPARATOR + name + descriptor);
    }

    private static String descriptor(final Method method) {
        return MethodType.methodType(method.getReturnType(), method.getParameterTypes())
                .descriptorString();
    }

    /**
     * Counts the parameters a descriptor declares, so a lookup never loads a parameter type. A
     * malformed descriptor yields a count no real member has, which keeps the exact descriptor
     * comparison the only acceptance rule.
     */
    private static int arity(final String descriptor) {
        int parameters = 0;
        int index = 1;
        while (index < descriptor.length() && descriptor.charAt(index) != ')') {
            while (index < descriptor.length() && descriptor.charAt(index) == '[') {
                index++;
            }
            if (index >= descriptor.length()) break;
            if (descriptor.charAt(index) == 'L') {
                final int end = descriptor.indexOf(';', index);
                if (end < 0) break;
                index = end + 1;
            } else {
                index++;
            }
            parameters++;
        }
        return parameters;
    }
}
