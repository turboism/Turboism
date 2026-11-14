package dev.turboism.adapter.cubism.optimization;

import dev.turboism.core.runtime.work.FatalErrors;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

/**
 * Provides frame-computing ASM {@link ClassWriter} instances resilient to unbundled optional
 * hierarchy classes during offline or early classfile inspection.
 */
public final class HostClassWriters {

    private static final String JAVA_OBJECT_INTERNAL_NAME = "java/lang/Object";

    private HostClassWriters() {}

    /**
     * Creates a {@link ClassWriter} with {@code COMPUTE_FRAMES | COMPUTE_MAXS} bound to
     * {@code loader}, degrading unresolved hierarchy merges to {@code java/lang/Object}.
     */
    public static ClassWriter computeFrames(final ClassReader reader, final ClassLoader loader) {
        return new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected ClassLoader getClassLoader() {
                return loader == null ? super.getClassLoader() : loader;
            }

            @Override
            protected String getCommonSuperClass(final String left, final String right) {
                try {
                    return super.getCommonSuperClass(left, right);
                } catch (final Throwable failure) {
                    FatalErrors.rethrowIfFatal(failure);
                    return JAVA_OBJECT_INTERNAL_NAME;
                }
            }
        };
    }
}
