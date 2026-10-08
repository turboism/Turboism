package dev.turboism.validation.modelimport3d.nativeprobe;

/** Exercise the same identity accessor as the native camera guard. */
public final class CatNativeDragIdentityTest {
    public static final class Guid {
        public String getUuidString() { return "a8f4cb25-ef63-4e6c-b89f-ae9e04102b5d"; }
        public String toString() { return "[CDrawableGuid #42100 ("+getUuidString()+")]"; }
    }
    public static final class Mesh {
        public Guid getGuid() { return new Guid(); }
    }
    public static void main(String[] args) throws Exception {
        String value = CatNativeDragAgent.sourceGuid(new Mesh());
        if (!value.equals(new Guid().getUuidString()) || value.equals(new Guid().toString()))
            throw new AssertionError("native diagnostic identity leaked into SDK UUID comparison");
        if (args.length>0) {
            Class<?> bridge=CatNativeDragAgent.pinned(CatNativeDragAgent.BRIDGE,CatNativeDragAgent.RUNTIME_SHA);
            if(bridge.getClassLoader()!=null || bridge.getProtectionDomain().getCodeSource()!=null)
                throw new AssertionError("test did not exercise actual bootstrap null CodeSource");
            try {
                CatNativeDragAgent.pinned("java.lang.String",CatNativeDragAgent.RUNTIME_SHA);
                throw new AssertionError("arbitrary bootstrap class admitted");
            } catch(IllegalStateException expected) { }
            System.out.println("PASS exact bootstrap class/JAR hashes; unknown bootstrap class refused");
        }
        System.out.println("PASS canonical source UUID for native camera identity guard");
    }
}
