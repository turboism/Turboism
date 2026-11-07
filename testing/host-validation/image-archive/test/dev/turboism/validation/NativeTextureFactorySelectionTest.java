package dev.turboism.validation;

/** Offline owner selection; no class is loaded and no GL context is created. */
public final class NativeTextureFactorySelectionTest {
    private NativeTextureFactorySelectionTest() {}
    public static void main(String[] args) {
        String previous = System.getProperty(NativeTextureUploadHostAgent.FACTORY_PROPERTY);
        try {
            System.clearProperty(NativeTextureUploadHostAgent.FACTORY_PROPERTY);
            if (!NativeTextureUploadHostAgent.factoryOwner().equals("com.live2d.graphics3d.shader.A"))
                throw new AssertionError("legacy probe default changed");
            for (String suffix : new String[] {"w", "y", "A"}) {
                String owner = "com.live2d.graphics3d.shader." + suffix;
                System.setProperty(NativeTextureUploadHostAgent.FACTORY_PROPERTY, owner);
                if (!NativeTextureUploadHostAgent.factoryOwner().equals(owner)) throw new AssertionError("owner ignored");
            }
            for (String invalid : new String[] {"java.lang.System", "com/live2d/graphics3d/shader/A", "com.live2d.graphics3d.shader.A.extra"}) {
                System.setProperty(NativeTextureUploadHostAgent.FACTORY_PROPERTY, invalid);
                try { NativeTextureUploadHostAgent.factoryOwner(); throw new AssertionError("foreign owner accepted"); }
                catch (IllegalArgumentException expected) { }
            }
        } finally {
            if (previous == null) System.clearProperty(NativeTextureUploadHostAgent.FACTORY_PROPERTY);
            else System.setProperty(NativeTextureUploadHostAgent.FACTORY_PROPERTY, previous);
        }
        System.out.println("NativeTextureFactorySelectionTest PASS");
    }
}
