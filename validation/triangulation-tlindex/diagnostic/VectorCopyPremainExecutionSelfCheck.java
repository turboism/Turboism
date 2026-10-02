import dev.turboism.adapter.cubism.mesh.LazyTriangulationEdgeBridge;
import dev.turboism.bootstrap.TurboismAgent;
import dev.turboism.runtime.log.RuntimeDiagnostics;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Random;

/** System-loader fixtures using actual sole-premain transforms/shared lease; no synthetic gate. */
public final class VectorCopyPremainExecutionSelfCheck {
    private VectorCopyPremainExecutionSelfCheck() { }
    public static void main(String[] args) throws Exception {
        if (TurboismAgent.class.getClassLoader() != null) throw new AssertionError("canonical premain absent");
        RuntimeDiagnostics.install((level, component, message, failure) -> System.out.println(message));
        Class<?> host = Class.forName("com.live2d.graphics3d.editableMesh.triangulation.h");
        if (host.getClassLoader() != ClassLoader.getSystemClassLoader()) throw new AssertionError("system host required");
        boolean assertions = host.desiredAssertionStatus();
        if (Class.forName("kotlin._Assertions").getField("ENABLED").getBoolean(null) != assertions)
            throw new AssertionError("actual native assertion mode mismatch");
        Class<?> fixtureLoader = Class.forName("VectorCopyPremainFixtures$Loader");
        var constructor = fixtureLoader.getDeclaredConstructor(Path.class, boolean.class, boolean.class);
        constructor.setAccessible(true);
        var run = Class.forName("VectorCopyPremainFixtures").getDeclaredMethod("run", fixtureLoader,
                float[][].class, boolean.class, boolean.class, boolean.class);
        run.setAccessible(true);
        Random random = new Random(0x560a11L);
        ArrayList<String> rows = new ArrayList<>();
        try (AutoCloseable loader = (AutoCloseable) constructor.newInstance(Path.of(args[0]), false, assertions)) {
            for (int fixture = 0; fixture < 128; fixture++) {
                int size = fixture == 0 ? 0 : 4 + fixture % 8;
                float[][] xy = new float[size][2];
                for (int i = 0; i < size; i++) {
                    xy[i][0] = random.nextFloat() * 20.0f - 10.0f;
                    xy[i][1] = random.nextFloat() * 20.0f - 10.0f;
                    if (fixture % 8 == 1) { xy[i][0] = i; xy[i][1] = 0.0f; }
                    if (fixture % 8 == 2) { xy[i][0] = i; xy[i][1] = i * 1.0e-6f; }
                    if (fixture % 8 == 3) { xy[i][0] = i; xy[i][1] = (i & 1) == 0 ? -0.0f : Float.MIN_VALUE; }
                }
                if (fixture == 5) xy[2][0] = Float.NaN;
                if (fixture == 6) xy[2][1] = Float.POSITIVE_INFINITY;
                if (fixture == 8) xy[0][0] = 12345.0f;
                try (AutoCloseable lease = LazyTriangulationEdgeBridge.enter(host)) {
                    if (lease == null) throw new AssertionError("actual shared gate unavailable");
                }
                Object result = run.invoke(null, loader, xy, false, fixture == 7, fixture % 8 == 4);
                rows.add(fixture + "|" + Base64.getEncoder().encodeToString(result.toString().getBytes(StandardCharsets.UTF_8)));
                try (AutoCloseable lease = LazyTriangulationEdgeBridge.enter(host)) {
                    if (lease == null) throw new AssertionError("shared gate lost after fixture");
                }
            }
        }
        Files.write(Path.of(args[1]), rows, StandardCharsets.UTF_8);
        if (!java.awt.GraphicsEnvironment.isHeadless() || java.awt.Frame.getFrames().length != 0)
            throw new AssertionError("Editor must be absent");
        System.out.println("VECTOR_COPY_PREMAIN_EXECUTION_PASS fixtures=128 assertions=" + assertions);
        System.exit(0);
    }
}
