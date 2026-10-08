import dev.turboism.adapter.cubism.mesh.LazyTriangulationEdgeBridge;
import dev.turboism.runtime.log.RuntimeDiagnostics;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;

/** Compare complete owned fixtures using the real sole-premain composed weave and shared gate. */
public final class BorrowedEdgePremainExecutionSelfCheck {
    private BorrowedEdgePremainExecutionSelfCheck() { }
    public static void main(String[] args) throws Exception {
        RuntimeDiagnostics.install((level, component, message, failure) -> System.out.println(message));
        Class<?> host = Class.forName("com.live2d.graphics3d.editableMesh.triangulation.h");
        if (host.getClassLoader() != ClassLoader.getSystemClassLoader())
            throw new AssertionError("host must use actual premain system loader");
        try (AutoCloseable lease = LazyTriangulationEdgeBridge.enter(host)) {
            if (lease == null) throw new AssertionError("actual shared gate unavailable");
        }
        Class<?> fixtureLoader = Class.forName("BorrowedEdgeNativeSelfCheck$Loader");
        Constructor<?> constructor = fixtureLoader.getDeclaredConstructor(Path.class, int.class, boolean.class);
        constructor.setAccessible(true);
        Method run = BorrowedEdgeNativeSelfCheck.class.getDeclaredMethod("run", fixtureLoader, int.class, boolean.class);
        run.setAccessible(true);
        boolean assertions = host.desiredAssertionStatus();
        ArrayList<String> rows = new ArrayList<>();
        try (AutoCloseable loader = (AutoCloseable) constructor.newInstance(Path.of(args[0]), 0, assertions)) {
            for (int fixture = 0; fixture < 128; fixture++) {
                Object result = run.invoke(null, loader, fixture, false);
                rows.add(fixture + "|" + Base64.getEncoder().encodeToString(result.toString().getBytes(StandardCharsets.UTF_8)));
                try (AutoCloseable lease = LazyTriangulationEdgeBridge.enter(host)) {
                    if (lease == null) throw new AssertionError("actual lease lost after fixture " + fixture);
                }
            }
        }
        Files.write(Path.of(args[1]), rows, StandardCharsets.UTF_8);
        if (!java.awt.GraphicsEnvironment.isHeadless() || java.awt.Frame.getFrames().length != 0)
            throw new AssertionError("Editor must be absent");
        System.out.println("BORROWED_EDGE_PREMAIN_EXECUTION_PASS fixtures=128 assertions=" + assertions);
        System.exit(0);
    }
}
