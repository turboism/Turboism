package dev.turboism.validation.tlindex.singleagent;

import dev.turboism.adapter.cubism.mesh.LazyTriangulationEdgeBridge;
import dev.turboism.bootstrap.TriangulationSingleAgentValidationHook;
import dev.turboism.bootstrap.TurboismAgent;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

/** Runs after the real Turboism premain; official classes are metadata only. */
public final class SingleAgentPremainSelfCheck {
    private static int checks;
    private SingleAgentPremainSelfCheck() {}
    private static void require(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message + " check=" + checks);
    }
    public static void main(String[] args) throws Exception {
        dev.turboism.runtime.log.RuntimeDiagnostics.install((level, component, message, failure) ->
                System.out.println("DIAGNOSTIC " + level + " " + component + " " + message
                        + (failure == null ? "" : " failure=" + failure.getClass().getName())));
        require(args.length == 2, "official JAR and installed|badsha|unsupported|unreviewed-origin required");
        require(ManagementFactory.getRuntimeMXBean().getInputArguments().stream().filter(a -> a.startsWith("-javaagent:")).count() == 1,
                "test is not using a single Agent");
        require(TurboismAgent.class.getClassLoader() == null, "real premain was not canonical bootstrap loaded");
        if (args[1].equals("badsha") || args[1].equals("unsupported")) {
            require(!TriangulationSingleAgentValidationHook.status().equals("INSTALLED"), "rejected companion was installed");
            require(TriangulationSingleAgentValidationHook.status().equals(args[1].equals("badsha") ? "REJECTED" : "OWNERSHIP_REJECTED"),
                    "unexpected rejection status: " + TriangulationSingleAgentValidationHook.status());
            boolean absent = false;
            try { Class.forName("dev.turboism.validation.triweave.WeaveAbAgent", false, ClassLoader.getSystemClassLoader()); }
            catch (ClassNotFoundException expected) { absent = true; }
            require(absent, "rejected sidecar was appended to the system class path");
            System.out.println("SINGLE_AGENT_REAL_PREMAIN PASS mode=" + args[1] + " checks=" + checks + " officialCodeInvoked=false");
            return;
        }
        require(args[1].equals("installed") || args[1].equals("unreviewed-origin"), "unknown test mode");
        require(TriangulationSingleAgentValidationHook.owned() && TriangulationSingleAgentValidationHook.status().equals("INSTALLED"),
                "real premain did not hand the companion an owned handle");
        ClassLoader app = ClassLoader.getSystemClassLoader();
        Class<?> capture = Class.forName("dev.turboism.validation.triweave.Capture", false, app);
        require(capture.getClassLoader() == app, "official-typed capture was incorrectly bootstrap loaded");
        capture.getDeclaredMethods();
        Class<?> list = Class.forName("com.live2d.graphics3d.editableMesh.triangulation.TriangleList", false, app);
        list.getDeclaredMethods(); list.getDeclaredFields(); list.getDeclaredConstructors();
        Class<?> host = Class.forName("com.live2d.graphics3d.editableMesh.triangulation.h", false, app);
        host.getDeclaredMethods(); host.getDeclaredFields(); host.getDeclaredConstructors();
        require(host.getClassLoader() == app && list.getClassLoader() == app, "official definitions changed loader");
        boolean sameOrigin = host.getProtectionDomain().getCodeSource().getLocation().toURI().normalize()
                .equals(Path.of(args[0]).toRealPath().toUri());
        require(sameOrigin == args[1].equals("installed"), "incorrect test origin");
        require(Class.forName(LazyTriangulationEdgeBridge.class.getName(), false, app) == LazyTriangulationEdgeBridge.class,
                "host resolves a different production bridge");
        for (String name : new String[] {"com.live2d.graphics3d.editableMesh.triangulation.j",
                "com.live2d.graphics3d.editableMesh.triangulation.TriPoint", "com.live2d.graphics3d.editableMesh.triangulation.l",
                "com.live2d.graphics3d.editableMesh.triangulation.r", "com.live2d.graphics3d.type.GVector2",
                "kotlin._Assertions", "kotlin.jvm.internal.Intrinsics"}) {
            Class<?> type = Class.forName(name, false, app);
            System.out.println("DEPENDENCY_ORIGIN " + name + " " + type.getProtectionDomain().getCodeSource().getLocation());
        }
        Path definition = Path.of(System.getProperty("turboism.validation.tlWeave.outputDir"),
                "tri-weave-def-" + System.getProperty("turboism.validation.tlWeave.runId") + ".log");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        String wanted = sameOrigin ? "capture=applied" : "gate=reject reason=codeSource";
        String log = "";
        while (System.nanoTime() < deadline) {
            if (Files.exists(definition)) log = Files.readString(definition);
            if (log.contains(wanted)) break;
            Thread.sleep(10);
        }
        require(log.contains(wanted), "actual capture definition event not confirmed: " + log);
        try (AutoCloseable lease = LazyTriangulationEdgeBridge.enter(host)) {
            var fingerprinter = Class.forName("dev.turboism.adapter.cubism.mesh.TriangulationDefinitionFingerprint")
                    .getDeclaredMethod("runtimeOf", byte[].class); fingerprinter.setAccessible(true);
            var initial = TriangulationSingleAgentValidationHook.initialDefinitions();
            for (var entry : TriangulationSingleAgentValidationHook.capturedDefinitions().entrySet()) {
                String observed = (String) fingerprinter.invoke(null, (Object) entry.getValue());
                byte[] before = initial.get(entry.getKey());
                String reviewed = before == null ? "not-observed" : (String) fingerprinter.invoke(null, (Object) before);
                System.out.println("ACTUAL_RUNTIME_FINGERPRINT " + entry.getKey() + " initial=" + reviewed + " captured=" + observed);
            }
            require((lease != null) == sameOrigin, "real premain/probe combination admitted the wrong origin");
        }
        try (AutoCloseable lease = LazyTriangulationEdgeBridge.enter(host)) {
            require((lease != null) == sameOrigin, "real premain/probe combination changed its admission");
        }
        System.out.println("SINGLE_AGENT_REAL_PREMAIN PASS mode=" + args[1] + " checks=" + checks
                + " actualTurboismPremain=true captureLoader=system lazyAdmission=" + sameOrigin
                + " officialDefinitions=true officialCodeInvoked=false fullHostRuntimeStarted=false");
    }
}
