package dev.turboism.adapter.cubism.optimization;

import dev.turboism.core.runtime.work.FatalErrors;

/**
 * Resolves common bytecode supertypes across host class loaders while degrading missing optional
 * hierarchy classes to {@code java/lang/Object}.
 */
public final class HostClassWriters {

    private static final String JAVA_OBJECT_INTERNAL_NAME = "java/lang/Object";

    private HostClassWriters() {}

    /**
     * Resolves the internal binary name of the lowest common supertype of {@code left} and
     * {@code right} on {@code loader}, falling back to {@code java/lang/Object} when optional host
     * types are absent.
     *
     * @param loader class loader inspected for hierarchy metadata, or {@code null} for the runtime loader
     * @param left internal name of the first merged type
     * @param right internal name of the second merged type
     * @return internal binary name of the common supertype
     */
    public static String commonSuperClass(final ClassLoader loader, final String left, final String right) {
        final ClassLoader effectiveLoader = loader != null ? loader : HostClassWriters.class.getClassLoader();
        try {
            Class<?> leftType = Class.forName(left.replace('/', '.'), false, effectiveLoader);
            final Class<?> rightType = Class.forName(right.replace('/', '.'), false, effectiveLoader);
            if (leftType.isAssignableFrom(rightType)) {
                return left;
            }
            if (rightType.isAssignableFrom(leftType)) {
                return right;
            }
            if (leftType.isInterface() || rightType.isInterface()) {
                return JAVA_OBJECT_INTERNAL_NAME;
            }
            do {
                leftType = leftType.getSuperclass();
            } while (leftType != null && !leftType.isAssignableFrom(rightType));
            return leftType == null
                    ? JAVA_OBJECT_INTERNAL_NAME
                    : leftType.getName().replace('.', '/');
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return JAVA_OBJECT_INTERNAL_NAME;
        }
    }
}
