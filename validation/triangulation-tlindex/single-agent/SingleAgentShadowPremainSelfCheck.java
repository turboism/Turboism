package dev.turboism.validation.tlindex.singleagent;

import dev.turboism.adapter.cubism.mesh.LazyTriangulationEdgeBridge;
import dev.turboism.adapter.cubism.textureatlas.image.AtlasTileBboxTransformer;
import dev.turboism.bootstrap.TriangulationSingleAgentShadowPreHook;
import dev.turboism.bootstrap.TriangulationSingleAgentValidationHook;
import dev.turboism.bootstrap.TurboismAgent;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.ZipFile;

/** Actual Turboism premain, exact official definitions as metadata only, no Editor. */
public final class SingleAgentShadowPremainSelfCheck {
    private static int checks;
    private SingleAgentShadowPremainSelfCheck() {}
    private static void require(boolean condition, String reason) {
        checks++;
        if (!condition) throw new AssertionError(reason + " check=" + checks);
    }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    public static void main(String[] args) throws Exception {
        require(args.length == 3, "official Jar / lazy-enabled / good|reject required");
        require(TurboismAgent.class.getClassLoader() == null, "Turboism premain is not canonical bootstrap loaded");
        require(ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(option -> option.startsWith("-javaagent:")).count() == 1, "not sole Agent");
        require(java.awt.GraphicsEnvironment.isHeadless() && java.awt.Frame.getFrames().length == 0, "Editor must be absent");
        if (args[2].equals("reject")) {
            require(!TriangulationSingleAgentValidationHook.status().equals("INSTALLED"), "negative companion installed");
            require(Thread.getAllStackTraces().keySet().stream().noneMatch(
                    thread -> thread.getName().equals("atlas-image-shadow-fixed-driver")), "negative driver started");
            System.out.println("SHADOW_REAL_PREMAIN_REFUSAL_PASS pre=" + TriangulationSingleAgentShadowPreHook.status()
                    + " post=" + TriangulationSingleAgentValidationHook.status()
                    + " failure=" + TriangulationSingleAgentValidationHook.failureReason());
            System.exit(0);
        }
        require(args[2].equals("good"), "unknown case");
        require(TriangulationSingleAgentValidationHook.owned()
                && TriangulationSingleAgentValidationHook.status().equals("INSTALLED"),
                "post contributor rejected: " + TriangulationSingleAgentValidationHook.failureReason());
        require(TriangulationSingleAgentShadowPreHook.status().equals("REMOVED_AFTER_PRODUCTION_REGISTRATION"),
                "shadow did not complete before scene");
        ClassLoader loader = ClassLoader.getSystemClassLoader();
        Class<?> scene = Class.forName("dev.turboism.validation.atlasimage.shadow.T040ShadowSceneDriverAgent", false, loader);
        var producer = scene.getDeclaredField("producerWeave"); producer.setAccessible(true);
        Object weave = producer.get(null);
        require(weave != null, "producer weave absent");
        var status = weave.getClass().getDeclaredMethod("status"); status.setAccessible(true);
        require(status.invoke(weave).equals("REGISTERED_NOT_OBSERVED"), "producer observed before metadata check");
        Class<?> mesh = Class.forName("com.live2d.graphics3d.editableMesh.b", false, loader);
        mesh.getDeclaredMethods(); mesh.getDeclaredFields(); mesh.getDeclaredConstructors();
        var installed = weave.getClass().getDeclaredMethod("requireInstalled"); installed.setAccessible(true); installed.invoke(weave);
        require(status.invoke(weave).equals("INITIAL_DEFINITION_WOVEN"), "producer initial definition not woven");
        Class<?> shadow = Class.forName("dev.turboism.validation.atlasimage.t039.T039ShadowAgent", false, loader);
        Class<?> target = Class.forName("com.live2d.util.f.g", false, loader);
        require(target.getClassLoader() == loader && target.getProtectionDomain().getCodeSource().getLocation()
                .toURI().normalize().equals(Path.of(args[0]).toRealPath().toUri()), "official target identity");
        byte[] original;
        try (ZipFile jar = new ZipFile(args[0])) {
            original = jar.getInputStream(jar.getEntry("com/live2d/util/f/g.class")).readAllBytes();
        }
        require(sha(original).equals("ff1d1ce9b4291212d255c6e84c8b57242234c1fa174af09e440c1162a4a1f8d6"), "official byte pin");
        Class<?> bridge = Class.forName("dev.turboism.validation.atlasimage.t035.T039ShadowPatchBridge", false, loader);
        Object result = bridge.getMethod("patchOfficial5303", byte[].class, String.class, String.class).invoke(
                null, original, "dev/turboism/validation/atlasimage/t039/T039ShadowHelper", "(II[III[IIIIIZ)J");
        require(Boolean.TRUE.equals(result.getClass().getMethod("accepted").invoke(result)), "independent shadow data patch");
        byte[] shadowBytes = (byte[]) result.getClass().getMethod("candidate").invoke(result);
        require(sha(shadowBytes).equals("800e3f6758e47bb1d8d974e72dcfed220f8773e15a5f05cac101ae2b56c1d160"), "shadow byte pin");
        byte[] composed = new AtlasTileBboxTransformer().transform(target.getModule(), loader, "com/live2d/util/f/g", null,
                target.getProtectionDomain(), shadowBytes);
        require(composed != null, "production Atlas did not independently compose");
        byte[] observed = TriangulationSingleAgentValidationHook.initialDefinitions().get("com.live2d.util.f.g");
        require(observed != null && sha(observed).equals(sha(composed)), "actual initial weave differs from independent composition");
        System.out.println("SHADOW_COMPOSED_INITIAL_DEFINITION_SHA256=" + sha(observed));
        Class<?> host = Class.forName("com.live2d.graphics3d.editableMesh.triangulation.h", false, loader);
        host.getDeclaredMethods(); host.getDeclaredFields(); host.getDeclaredConstructors();
        try (AutoCloseable lease = LazyTriangulationEdgeBridge.enter(host)) {
            require((lease != null) == Boolean.parseBoolean(args[1]), "fresh lazy admission after shadow removal");
        }
        @SuppressWarnings("unchecked")
        Map<String, String> frozen = (Map<String, String>) shadow.getMethod("freezeCapture", String.class)
                .invoke(null, System.getProperty("turboism.validation.t039.runId"));
        require(frozen.size() == 43 && frozen.get("state").equals("SHADOW_READY")
                && frozen.get("removalStatus").equals("REMOVED")
                && frozen.get("transformerRegistered").equals("false")
                && frozen.get("sampleOutcome").equals("NO_CALLS"), "true freeze contract changed");
        try (AutoCloseable lease = LazyTriangulationEdgeBridge.enter(host)) {
            require((lease != null) == Boolean.parseBoolean(args[1]), "freeze changed lazy gate");
        }
        System.out.println("SHADOW_REAL_PREMAIN_METADATA_PASS lazyLease=" + args[1] + " checks=" + checks
                + " officialInitialized=false geometryExecuted=false EditorStarted=false");
        System.exit(0);
    }
}
