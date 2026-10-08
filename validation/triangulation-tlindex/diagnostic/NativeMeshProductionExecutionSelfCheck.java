import dev.turboism.adapter.cubism.mesh.LazyTriangulationEdgeBridge;
import dev.turboism.bootstrap.TurboismAgent;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;

/** Record complete official mesh operations using actual system-loader definitions. */
public final class NativeMeshProductionExecutionSelfCheck {
    private NativeMeshProductionExecutionSelfCheck() { }
    public static void main(String[] args) throws Exception {
        boolean agent = args[2].equals("agent");
        Class<?> mesh = Class.forName("com.live2d.graphics3d.editableMesh.GEditableMesh2");
        if (mesh.getClassLoader() != ClassLoader.getSystemClassLoader())
            throw new AssertionError("actual system-loader SDK required");
        if (agent) {
            if (TurboismAgent.class.getClassLoader() != null) throw new AssertionError("actual canonical premain required");
            try (AutoCloseable lease = LazyTriangulationEdgeBridge.enterMesh(mesh)) {
                if (lease == null) throw new AssertionError("actual SDK extended admission missing");
            }
        }
        Method run = NativeMeshAutoConnectSelfCheck.class.getDeclaredMethod("run", NativeMeshEdgeLoopSelfCheck.Loader.class,
                int.class, boolean.class);
        run.setAccessible(true);
        ArrayList<String> rows = new ArrayList<>();
        Class<?> table = Class.forName("dev.turboism.adapter.cubism.mesh.NativeMeshEdgeTable");
        Method reservation = table.getDeclaredMethod("reservedBytes"); reservation.setAccessible(true);
        // SDK Classes delegate to the actual system loader, so this fixture loader
        // does not generate or replace any native definition in either leg.
        try (var loader = new NativeMeshEdgeLoopSelfCheck.Loader(Path.of(args[0]), false, mesh.desiredAssertionStatus(), true)) {
            for (int fixture = 0; fixture < 48; fixture++) {
                Object result = run.invoke(null, loader, fixture, false);
                rows.add(fixture + "|" + Base64.getEncoder().encodeToString(result.toString().getBytes(StandardCharsets.UTF_8)));
                if ((int) reservation.invoke(null) != 0) throw new AssertionError("fixture scope reservation retained");
                if (agent) try (AutoCloseable lease = LazyTriangulationEdgeBridge.enterMesh(mesh)) {
                    if (lease == null) throw new AssertionError("mesh gate lost after fixture " + fixture);
                }
            }
        }
        Files.write(Path.of(args[1]), rows, StandardCharsets.UTF_8);
        if (!java.awt.GraphicsEnvironment.isHeadless() || java.awt.Frame.getFrames().length != 0)
            throw new AssertionError("no Editor allowed");
        System.out.printf("NATIVE_MESH_PRODUCTION_EXECUTION_PASS agent=%s assertions=%s fixtures=48 reservedBytes=0%n",
                agent, mesh.desiredAssertionStatus());
        System.exit(0);
    }
}
