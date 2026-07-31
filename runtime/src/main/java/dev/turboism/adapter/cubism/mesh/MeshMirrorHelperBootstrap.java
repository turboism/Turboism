package dev.turboism.adapter.cubism.mesh;

import java.lang.instrument.Instrumentation;
import java.util.Objects;

/** Verifies the helper linkage contract before host methods are transformed. */
public final class MeshMirrorHelperBootstrap {

    private MeshMirrorHelperBootstrap() { }

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
