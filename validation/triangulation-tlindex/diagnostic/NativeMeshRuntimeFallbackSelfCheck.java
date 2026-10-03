import dev.turboism.adapter.cubism.mesh.NativeMeshEdgeTableOwnedAccess;
import java.lang.reflect.Method;
import java.nio.file.Path;

/** Unmodified runtime patcher/helper frontends with unavailable admission: complete native fallback. */
public final class NativeMeshRuntimeFallbackSelfCheck {
    private NativeMeshRuntimeFallbackSelfCheck() { }
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !Boolean.getBoolean("turboism.validation.meshRuntimeNativeAdmission"))
            throw new AssertionError("three official archives and actual admission frontend required");
        Method run = NativeMeshAutoConnectSelfCheck.class.getDeclaredMethod("run", NativeMeshEdgeLoopSelfCheck.Loader.class,
                int.class, boolean.class);
        run.setAccessible(true);
        int groups = 0;
        for (String path : args) for (boolean assertions : new boolean[] {false, true}) {
            try (var original = new NativeMeshEdgeLoopSelfCheck.Loader(Path.of(path), false, assertions, true);
                    var patched = new NativeMeshEdgeLoopSelfCheck.Loader(Path.of(path), true, assertions, true)) {
                for (int fixture = 0; fixture < 48; fixture++) {
                    Object expected = run.invoke(null, original, fixture, false);
                    Object actual = run.invoke(null, patched, fixture, false);
                    if (!expected.equals(actual)) throw new AssertionError("actual native frontend fallback differs: " + fixture);
                    if (NativeMeshEdgeTableOwnedAccess.reservedBytes() != 0)
                        throw new AssertionError("unavailable admission must allocate no primitive table");
                    groups++;
                }
            }
            System.out.printf("NATIVE_MESH_RUNTIME_FALLBACK_PASS assertions=%s fixtures=48 admission=ACTUAL_UNAVAILABLE%n", assertions);
        }
        System.out.printf("NATIVE_MESH_RUNTIME_FALLBACK_FINISHED groups=%d reservedBytes=0%n", groups);
    }
}
