package dev.turboism.bootstrap;

/** Child-process check for the distributed agent's bootstrap-visible hook ingress. */
public final class BootstrapBridgeVisibilityMain {
    private BootstrapBridgeVisibilityMain() { }

    public static void main(final String[] args) throws Exception {
        final Class<?> bridge = Class.forName(
            "dev.turboism.adapter.cubism.mesh.NativeMeshMirrorBridge",
            false,
            null
        );
        if (bridge.getClassLoader() != null) {
            throw new IllegalStateException("Mesh mirror bridge is not bootstrap-visible");
        }
    }
}
