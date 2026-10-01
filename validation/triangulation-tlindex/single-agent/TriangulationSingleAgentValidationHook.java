package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.mesh.TriangulationDefinitionLifecycle;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;

/** Validation-only manifest contributor; the production premain stays the sole Agent. */
public final class TriangulationSingleAgentValidationHook implements HookContributor {
    public static final String PREFIX = "turboism.validation.triSingleAgent.";
    public static final String TOKEN = "T050_SINGLE_AGENT_TLPROD_V1";
    private static final String PROBE = "dev.turboism.validation.triweave.WeaveAbAgent";
    private static final String SCENE = "dev.turboism.validation.atlasimage.shadow.T040ShadowSceneDriverAgent";
    private static volatile String status = "NOT_INSTALLED";
    private static volatile boolean owned;
    private static volatile String failureReason = "";
    private static final Map<String, byte[]> INITIAL = new ConcurrentHashMap<>();
    private static final Map<String, byte[]> CAPTURED = new ConcurrentHashMap<>();

    public TriangulationSingleAgentValidationHook() {}
    public static String status() { return status; }
    public static boolean owned() { return owned; }
    public static String failureReason() { return failureReason; }
    public static Map<String, byte[]> initialDefinitions() { return copies(INITIAL); }
    public static Map<String, byte[]> capturedDefinitions() { return copies(CAPTURED); }
    private static Map<String, byte[]> copies(Map<String, byte[]> values) {
        Map<String, byte[]> result = new java.util.LinkedHashMap<>();
        values.forEach((name, bytes) -> result.put(name, bytes.clone())); return Map.copyOf(result);
    }
    @Override public String id() { return "T050_SINGLE_AGENT_VALIDATION"; }
    @Override public Phase phase() { return Phase.PREMAIN; }
    @Override public boolean closesOnProcessExit() { return true; }
    @Override public boolean admitted(HookEnvironment environment) {
        return TOKEN.equals(System.getProperty(PREFIX + "optIn"));
    }

    private record Sidecar(Path path, String sha256, String premain, JarFile jar) implements AutoCloseable {
        @Override public void close() throws java.io.IOException { jar.close(); }
    }
    private static Sidecar sidecar(String kind, String fileName, String premain) throws Exception {
        String file = System.getProperty(PREFIX + kind + "Path", "");
        String sha = System.getProperty(PREFIX + kind + "Sha256", "");
        if (file.isEmpty() || !sha.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("missing pinned " + kind);
        Path path = Path.of(file).toAbsolutePath().normalize();
        if (!Path.of(file).isAbsolute() || !path.getFileName().toString().equals(fileName)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException("invalid " + kind + " path");
        }
        byte[] bytes = Files.readAllBytes(path);
        if (!sha.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))) {
            throw new IllegalArgumentException(kind + " hash mismatch");
        }
        JarFile jar = new JarFile(path.toFile(), true);
        if (jar.getManifest() == null || !premain.equals(jar.getManifest().getMainAttributes().getValue("Premain-Class"))) {
            jar.close(); throw new IllegalArgumentException(kind + " entry rejected");
        }
        return new Sidecar(path, sha, premain, jar);
    }
    private static Class<?> append(Instrumentation instrumentation, Sidecar sidecar) throws Exception {
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (type.getName().equals(sidecar.premain)) throw new IllegalArgumentException("sidecar already loaded");
        }
        // Keep official-typed capture classes in the system loader. Putting them
        // on Boot-Class-Path would prevent their official host types from linking.
        instrumentation.appendToSystemClassLoaderSearch(sidecar.jar);
        Class<?> entry = Class.forName(sidecar.premain, false, ClassLoader.getSystemClassLoader());
        if (entry.getClassLoader() != ClassLoader.getSystemClassLoader()
                || !sidecar.path.toUri().normalize().equals(entry.getProtectionDomain().getCodeSource().getLocation().toURI().normalize())) {
            throw new IllegalArgumentException("sidecar identity rejected");
        }
        return entry;
    }
    private static void start(Class<?> entry, Instrumentation instrumentation) throws Exception {
        try { entry.getMethod("premain", String.class, Instrumentation.class).invoke(null, null, instrumentation); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            if (failure.getCause() instanceof Error error) throw error;
            throw failure;
        }
    }
    @Override public AutoCloseable install(HookEnvironment environment) throws Exception {
        Instrumentation instrumentation = environment.instrumentation();
        var lifecycle = TriangulationDefinitionLifecycle.ownedBy(instrumentation);
        if (lifecycle == null || !lifecycle.startupReason().equals("SUPPORTED_OWNED_PREMAIN")) {
            status = "OWNERSHIP_REJECTED"; throw new IllegalArgumentException(status);
        }
        owned = true;
        String mode = System.getProperty(PREFIX + "mode", "");
        if (!mode.equals("capture-only") && !mode.equals("scene")) throw new IllegalArgumentException("unknown single-Agent mode");
        if (!"true".equals(System.getProperty("turboism.validation.tlWeave.enabled"))
                || !"tl-dump-only".equals(System.getProperty("turboism.validation.tlWeave.mode"))
                || !"tl-official".equals(System.getProperty("turboism.validation.tlWeave.profile"))
                || "true".equals(System.getProperty("turboism.validation.triWeave.enabled"))
                || "true".equals(System.getProperty("turboism.validation.dmWeave.enabled"))) {
            throw new IllegalArgumentException("only the identical TLPROD capture is admitted");
        }
        List<Sidecar> files = new ArrayList<>(); List<ClassFileTransformer> transforms = new ArrayList<>();
        try {
            files.add(sidecar("probe", "tri-weave-agent.jar", PROBE));
            if (mode.equals("scene")) {
                String version = System.getProperty("turboism.validation.atlasImageShadow.version", "");
                if (!version.equals("5203") && !version.equals("5302")) throw new IllegalArgumentException("5303 shadow lifecycle not admitted");
                files.add(sidecar("scene", "atlas-image-shadow-scene-driver.jar", SCENE));
            }
            Instrumentation tracked = (Instrumentation) Proxy.newProxyInstance(ClassLoader.getSystemClassLoader(),
                    new Class<?>[] {Instrumentation.class}, (proxy, method, args) -> {
                        try {
                            Object result = method.invoke(instrumentation, args);
                            if (method.getName().equals("addTransformer")) transforms.add((ClassFileTransformer) args[0]);
                            return result;
                        } catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
            Class<?> probe = append(instrumentation, files.get(0));
            Class<?> configType = Class.forName("dev.turboism.validation.triweave.WeaveAbConfig", true, probe.getClassLoader());
            Object config = configType.getConstructor().newInstance();
            var validate = configType.getDeclaredMethod("validate"); validate.setAccessible(true);
            Object rejection = validate.invoke(config);
            if (rejection != null) throw new IllegalArgumentException("probe config rejected: " + rejection);
            start(probe, tracked);
            if (transforms.size() != 1) throw new IllegalStateException("capture transformer was not installed exactly once");
            if (mode.equals("capture-only")) {
                ClassFileTransformer observer = new ClassFileTransformer() {
                    @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                            java.security.ProtectionDomain domain, byte[] bytes) {
                        if (name != null && (name.startsWith("com/live2d/graphics3d/editableMesh/triangulation/")
                                || name.equals("com/live2d/graphics3d/type/GVector2") || name.equals("kotlin/_Assertions")
                                || name.equals("kotlin/jvm/internal/Intrinsics"))) {
                            (type == null ? INITIAL : CAPTURED).put(name.replace('/', '.'), bytes.clone());
                        }
                        return null;
                    }
                };
                instrumentation.addTransformer(observer, true); transforms.add(observer);
            }
            if (mode.equals("scene")) {
                Class<?> scene = append(instrumentation, files.get(1));
                Class<?> configClass = Class.forName(SCENE + "$DriverConfig", true, scene.getClassLoader());
                var from = configClass.getDeclaredMethod("fromSystemProperties"); from.setAccessible(true); from.invoke(null);
                start(scene, instrumentation);
            }
            status = "INSTALLED";
            System.out.println("TRI_SINGLE_AGENT_VALIDATION status=" + status + " owned=true mode=" + mode
                    + " probeSha256=" + files.get(0).sha256 + " probeLoader=system");
            return () -> {
                for (ClassFileTransformer transformer : transforms) instrumentation.removeTransformer(transformer);
                for (Sidecar file : files) file.close();
            };
        } catch (Exception | Error failure) {
            status = "REJECTED";
            Throwable cause = failure instanceof InvocationTargetException invocation && invocation.getCause() != null
                    ? invocation.getCause() : failure;
            failureReason = cause.getClass().getName() + ":" + java.util.Objects.toString(cause.getMessage(), "");
            System.err.println("TRI_SINGLE_AGENT_VALIDATION status=REJECTED failure=" + failureReason);
            for (ClassFileTransformer transformer : transforms) instrumentation.removeTransformer(transformer);
            for (Sidecar file : files) file.close();
            throw failure;
        }
    }
}
