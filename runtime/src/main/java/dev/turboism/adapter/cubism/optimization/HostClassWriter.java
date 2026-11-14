package dev.turboism.adapter.cubism.optimization;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

/**
 * {@link ClassWriter} that resolves frame hierarchies through the host's own loader.
 *
 * <p>{@code COMPUTE_FRAMES} needs the common superclass of merged reference types, and the
 * default {@link ClassWriter} answers that question by loading classes through this agent's
 * loader. Host types are invisible there, so the merge fails or resolves to a foreign type.
 * Every writer here therefore answers with the loader that defines the class being transformed.
 * </p>
 *
 * <p>A {@code null} loader — the bootstrap loader — falls back to the ASM default, which is the
 * only answer available for JDK types.</p>
 */
public final class HostClassWriter extends ClassWriter {

    private final ClassLoader loader;

    /**
     * Creates a writer that copies the constant pool of {@code reader}.
     *
     * @param reader reader over the definition being transformed
     * @param flags ASM {@code COMPUTE_*} flags
     * @param loader the defining loader of the transformed class, or {@code null} for bootstrap
     */
    public HostClassWriter(final ClassReader reader, final int flags, final ClassLoader loader) {
        super(reader, flags);
        this.loader = loader;
    }

    /**
     * Creates a writer that builds a definition without a source reader.
     *
     * @param flags ASM {@code COMPUTE_*} flags
     * @param loader the defining loader of the generated class, or {@code null} for bootstrap
     */
    public HostClassWriter(final int flags, final ClassLoader loader) {
        super(flags);
        this.loader = loader;
    }

    /**
     * Answers frame merges from the host loader, so host types stay resolvable.
     *
     * @return the defining loader of the transformed class, or {@code null} for bootstrap
     */
    @Override
    protected ClassLoader getClassLoader() {
        return loader == null ? super.getClassLoader() : loader;
    }
}
