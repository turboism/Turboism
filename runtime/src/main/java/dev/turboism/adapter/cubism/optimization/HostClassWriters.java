package dev.turboism.adapter.cubism.optimization;

import dev.turboism.core.runtime.work.FatalErrors;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

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

    private static final int CLASS_HEADER_MIN_BYTES = 8;
    private static final int CLASS_MAGIC = 0xCAFEBABE;
    private static final int CLASS_MINOR_OFFSET = 4;
    private static final int CLASS_MAJOR_OFFSET = 6;
    private static final int BYTE_MASK = 0xFF;
    private static final int SHIFT_8 = 8;
    private static final int SHIFT_16 = 16;
    private static final int SHIFT_24 = 24;
    private static final int JDK_25_MAJOR_VERSION = 69;
    private static final int ASM_READER_MAX_MAJOR_VERSION = 68;

    private HostClassWriters() {}

    /**
     * Opens an ASM {@link ClassReader}, normalizing non-preview Java 25 class headers for ASM 9.7.1.
     *
     * @param bytes raw classfile bytes
     * @return reader inspecting the supplied bytecode
     */
    public static ClassReader newClassReader(final byte[] bytes) {
        if (!isJava25NonPreview(bytes)) {
            return new ClassReader(bytes);
        }
        final byte[] normalized = bytes.clone();
        normalized[CLASS_MAJOR_OFFSET] = (byte) (ASM_READER_MAX_MAJOR_VERSION >>> SHIFT_8);
        normalized[CLASS_MAJOR_OFFSET + 1] = (byte) ASM_READER_MAX_MAJOR_VERSION;
        return new ClassReader(normalized);
    }

    /**
     * Wraps a {@link ClassVisitor} so normalized Java 25 inputs retain major version 69 on output.
     *
     * @param originalBytes raw classfile bytes before reader normalization
     * @param delegate downstream class visitor receiving restored version metadata
     * @return wrapper preserving original Java 25 major version, or {@code delegate} unchanged
     */
    public static ClassVisitor preservingClassVersion(final byte[] originalBytes, final ClassVisitor delegate) {
        if (!isJava25NonPreview(originalBytes)) {
            return delegate;
        }
        return new ClassVisitor(Opcodes.ASM9, delegate) {
            @Override
            public void visit(
                    final int version,
                    final int access,
                    final String name,
                    final String signature,
                    final String superName,
                    final String[] interfaces) {
                super.visit(JDK_25_MAJOR_VERSION, access, name, signature, superName, interfaces);
            }
        };
    }

    private static boolean isJava25NonPreview(final byte[] bytes) {
        if (bytes == null || bytes.length < CLASS_HEADER_MIN_BYTES) {
            return false;
        }
        final int magic = ((bytes[0] & BYTE_MASK) << SHIFT_24)
                | ((bytes[1] & BYTE_MASK) << SHIFT_16)
                | ((bytes[2] & BYTE_MASK) << SHIFT_8)
                | (bytes[3] & BYTE_MASK);
        if (magic != CLASS_MAGIC) {
            return false;
        }
        final int minor =
                ((bytes[CLASS_MINOR_OFFSET] & BYTE_MASK) << SHIFT_8) | (bytes[CLASS_MINOR_OFFSET + 1] & BYTE_MASK);
        final int major =
                ((bytes[CLASS_MAJOR_OFFSET] & BYTE_MASK) << SHIFT_8) | (bytes[CLASS_MAJOR_OFFSET + 1] & BYTE_MASK);
        return minor == 0 && major == JDK_25_MAJOR_VERSION;
    }

    /**
     * Returns a writer that recomputes frames and maximums, resolving supertypes on {@code loader}.
     *
     * <p>Supertype resolution uses the conservative {@code java/lang/Object} fallback for missing
     * optional metadata instead of throwing; see {@link #commonSuperClass(ClassLoader, String,
     * String)} for why, and for why that fallback does not guarantee verifier acceptance.</p>
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
     * the host unpatched, so an unresolvable pair uses the conservative {@code java/lang/Object}
     * fallback instead.</p>
     *
     * <p>That fallback is not a validity guarantee. Merging to {@code java/lang/Object} discards
     * the type precision a later invocation or field instruction may require, which surfaces as a
     * {@link VerifyError} at class definition. A method-shape gate proves the reviewed body, not
     * verifier acceptance. Downstream JVM verification remains the authoritative check.</p>
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
