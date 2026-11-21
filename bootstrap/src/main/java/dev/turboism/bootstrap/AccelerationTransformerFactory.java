package dev.turboism.bootstrap;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Objects;

/** Loads and constructs optional acceleration transformers by class name. */
final class AccelerationTransformerFactory {

    private static final ClassFileTransformer NO_OP = new NoOpClassFileTransformer();

    private AccelerationTransformerFactory() {}

    /**
     * Instantiates {@code transformerClassName} with a no-arg constructor, or returns a no-op
     * transformer when the implementation class is absent from the classpath.
     *
     * @param transformerClassName binary class name of a {@link ClassFileTransformer}
     * @return live transformer instance or shared no-op placeholder
     */
    static ClassFileTransformer instantiateOrNoOp(final String transformerClassName) {
        Objects.requireNonNull(transformerClassName, "transformerClassName");
        try {
            final Class<?> type =
                    Class.forName(transformerClassName, false, AccelerationTransformerFactory.class.getClassLoader());
            return (ClassFileTransformer) type.getDeclaredConstructor().newInstance();
        } catch (final ClassNotFoundException absent) {
            return NO_OP;
        } catch (final ReflectiveOperationException failure) {
            throw new IllegalStateException("failed to instantiate transformer: " + transformerClassName, failure);
        }
    }

    private static final class NoOpClassFileTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(
                final Module module,
                final ClassLoader loader,
                final String className,
                final Class<?> classBeingRedefined,
                final ProtectionDomain protectionDomain,
                final byte[] classfileBuffer) {
            return null;
        }
    }
}
