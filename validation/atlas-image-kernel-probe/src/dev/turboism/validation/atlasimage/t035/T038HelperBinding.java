package dev.turboism.validation.atlasimage.t035;

import dev.turboism.validation.atlasimage.t038.T038ArrayHelper;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Finite helper identity/visibility contract for the owned fixture only. */
final record T038HelperBinding(
    Class<?> helperClass,
    ClassLoader expectedLoader,
    String owner,
    String descriptor
) {
    static T038HelperBinding expected(final Class<?> helperClass, final ClassLoader loader) {
        return new T038HelperBinding(
            helperClass,
            loader,
            T038ArrayHelper.INTERNAL_NAME,
            T038ArrayHelper.BOUNDS_DESCRIPTOR
        );
    }

    static Check validate(
        final Class<?> visibleHelper,
        final T038HelperBinding binding
    ) {
        if (binding == null) {
            return Check.fail("helper binding missing");
        }
        if (visibleHelper == null) {
            return Check.fail("helper missing");
        }
        if (visibleHelper != binding.helperClass()) {
            return Check.fail("helper identity mismatch");
        }
        if (visibleHelper.getClassLoader() != binding.expectedLoader()) {
            return Check.fail("helper loader mismatch");
        }
        if (!T038ArrayHelper.INTERNAL_NAME.equals(binding.owner())) {
            return Check.fail("helper owner mismatch");
        }
        if (!T038ArrayHelper.BOUNDS_DESCRIPTOR.equals(binding.descriptor())) {
            return Check.fail("helper descriptor mismatch");
        }
        if (!T038ArrayHelper.BINARY_NAME.equals(visibleHelper.getName())
                || !Modifier.isPublic(visibleHelper.getModifiers())
                || !Modifier.isFinal(visibleHelper.getModifiers())) {
            return Check.fail("helper class shape mismatch");
        }
        try {
            final Method method = visibleHelper.getDeclaredMethod(
                "bounds",
                int.class,
                int.class,
                int[].class,
                int.class,
                int.class,
                int[].class,
                int.class,
                int.class,
                int.class,
                int.class,
                boolean.class
            );
            if (!Modifier.isPublic(method.getModifiers())
                    || !Modifier.isStatic(method.getModifiers())
                    || method.getReturnType() != long.class) {
                return Check.fail("helper method shape mismatch");
            }
        } catch (final NoSuchMethodException exception) {
            return Check.fail("helper method missing");
        }
        return Check.pass();
    }

    record Check(boolean accepted, String reason) {
        static Check pass() {
            return new Check(true, "accepted");
        }

        static Check fail(final String reason) {
            return new Check(false, reason);
        }
    }
}
