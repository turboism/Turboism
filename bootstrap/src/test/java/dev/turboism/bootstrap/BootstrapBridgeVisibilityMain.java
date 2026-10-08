package dev.turboism.bootstrap;

/** Child-process check for the distributed agent's bootstrap-visible hook ingress. */
public final class BootstrapBridgeVisibilityMain {
    private BootstrapBridgeVisibilityMain() {}

    public static void main(final String[] args) throws Exception {
        for (String name : new String[] {"NativeMeshMirrorBridge", "NativeMeshToolSessionBridge"}) {
            final Class<?> bridge = Class.forName("dev.turboism.adapter.cubism.mesh." + name, false, null);
            if (bridge.getClassLoader() != null) {
                throw new IllegalStateException(name + " is not bootstrap-visible");
            }
        }
    }
}
