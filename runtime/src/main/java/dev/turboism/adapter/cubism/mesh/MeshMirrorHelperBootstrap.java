package dev.turboism.adapter.cubism.mesh;

import java.lang.instrument.Instrumentation;
import java.util.Objects;

/** Verifies the helper linkage contract before host methods are transformed. */
public final class MeshMirrorHelperBootstrap {

    private MeshMirrorHelperBootstrap() { }

    /**
     * Fails fast unless the host class loader resolves the mesh-mirror bridge to the very same
     * class object this runtime loaded.
     *
     * <p>Transformed host methods call into {@link NativeMeshMirrorBridge} by name; if the host
     * loader would resolve that name to a different class, the transformation would link against a
     * stranger. This check is therefore a precondition for installing any transformation, not a
     * diagnostic.
     *
     * @param instrumentation  the agent's instrumentation handle, non-null; presence is required but
     *                         it is not otherwise used by this check
     * @param hostClassLoader  the loader the transformed host classes will resolve against, non-null
     * @throws NullPointerException  if either argument is null
     * @throws IllegalStateException if the bridge class is invisible to the host loader, or visible
     *                               as a different class identity
     */
    public static void ensureAvailable(
        final Instrumentation instrumentation,
        final ClassLoader hostClassLoader
    ) {
        Objects.requireNonNull(instrumentation, "instrumentation");
        Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        try {
            final Class<?> visible = Class.forName(
                NativeMeshMirrorBridge.class.getName(),
                false,
                hostClassLoader
            );
            if (visible != NativeMeshMirrorBridge.class) {
                throw new IllegalStateException("mesh mirror bridge class identity mismatch");
            }
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("mesh mirror bridge is not visible to the host class loader", failure);
        }
    }
}
