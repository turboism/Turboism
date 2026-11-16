package dev.turboism.adapter.cubism.optimization;

import dev.turboism.core.runtime.work.FatalErrors;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

/**
 * Resolves common bytecode supertypes across host class loaders while degrading missing optional
 * hierarchy classes to {@code java/lang/Object}.
 *
 * <p>The frame-computing {@link ClassWriter} factories live here as well, so a weave hook never
 * re-declares the loader-aware subclass that ASM's default writer lacks.</p>
 */
public final class HostClassWriters {

    private static final String JAVA_OBJECT_INTERNAL_NAME = "java/lang/Object";

    /** Frame and stack recomputation, required after any instruction insertion. */
    private static final int FRAME_COMPUTING_FLAGS = ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS;

    private HostClassWriters() {}

    /**
     * Returns a writer that recomputes frames and maximums, resolving supertypes on {@code loader}.
     *
     * <p>Supertype resolution degrades to {@code java/lang/Object} instead of throwing; see
     * {@link #commonSuperClass(ClassLoader, String, String)} for why.</p>
     *
     * @param reader reader whose constant pool the writer reuses
     * @param loader loader that supplies hierarchy metadata, or {@code null} for the runtime loader
     * @return a frame-computing writer bound to {@code loader}
     */
    public static ClassWriter newFrameWriter(final ClassReader reader, final ClassLoader loader) {
        return newFrameWriter(loader, reader);
    }

    /**
     * Returns a writer that recomputes frames and maximums without a reader to copy from.
     *
     * @param loader loader that supplies hierarchy metadata, or {@code null} for the runtime loader
     * @return a frame-computing writer bound to {@code loader}
     */
    public static ClassWriter newFrameWriter(final ClassLoader loader) {
        return newFrameWriter(loader, null);
    }

    /**
     * Resolves the internal binary name of the lowest common supertype of {@code left} and
     * {@code right} on {@code loader}, falling back to {@code java/lang/Object} when optional host
     * types are absent.
     *
     * <p>This deliberately diverges from ASM's default {@code getCommonSuperClass}, which throws
     * {@link TypeNotPresentException} for a type the loader cannot resolve. A weave hook runs
     * against a partially admitted host: the class being transformed is reachable, but optional
     * siblings it references may not be. Throwing there would abort the whole transform and leave
     * the host unpatched, so an unresolvable pair degrades to {@code java/lang/Object} instead. The
     * widened frame is always verifiable, and the hook's own method-shape gate has already refused
     * any body it does not recognise before this runs.</p>
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

    /**
     * Builds the frame-computing writer that defers supertype resolution to
     * {@link #commonSuperClass(ClassLoader, String, String)}.
     *
     * @param loader loader that supplies hierarchy metadata, or {@code null} for the runtime loader
     * @param reader reader whose constant pool the writer reuses, or {@code null}
     * @return a frame-computing writer bound to {@code loader}
     */
    private static ClassWriter newFrameWriter(final ClassLoader loader, final ClassReader reader) {
        return new ClassWriter(reader, FRAME_COMPUTING_FLAGS) {
            @Override
            protected ClassLoader getClassLoader() {
                return loader != null ? loader : super.getClassLoader();
            }

            @Override
            protected String getCommonSuperClass(final String left, final String right) {
                return commonSuperClass(loader, left, right);
            }
        };
    }
}
