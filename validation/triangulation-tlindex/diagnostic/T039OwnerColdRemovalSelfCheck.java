package dev.turboism.validation.tlindex.diagnostic;

import dev.turboism.adapter.cubism.mesh.TriangulationDefinitionLifecycle;
import dev.turboism.validation.atlasimage.t039.T039OwnedTargetLoader;
import dev.turboism.validation.atlasimage.t039.T039ShadowAgent;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real sole-agent gateway regression; only an owned fixture is defined, never Cubism. */
public final class T039OwnerColdRemovalSelfCheck {
    private static final String PREFIX = "turboism.validation.t039.";
    private static final String TOKEN = "T039_OWNED_COLD_REMOVAL_V1";
    private static TriangulationDefinitionLifecycle lifecycle;
    private static Instrumentation raw;
    private static Path fixture;
    private static String mode;
    private static int checks;
    private static Throwable startupFailure;

    private T039OwnerColdRemovalSelfCheck() {}

    public static void premain(String argument, Instrumentation instrumentation) {
        try { prepare(argument, instrumentation); }
        catch (Throwable failure) { startupFailure = failure; }
    }

    private static void prepare(String argument, Instrumentation instrumentation) throws Exception {
        mode = System.getProperty("t039.ownerColdTest.mode");
        raw = instrumentation;
        Instrumentation supplied = instrumentation;
        if (mode.equals("false") || mode.equals("throws")) {
            // Only the removal result is fault-injected. Registration/definition still use the real VM.
            supplied = (Instrumentation) Proxy.newProxyInstance(ClassLoader.getSystemClassLoader(),
                    new Class<?>[] {Instrumentation.class}, (proxy, method, values) -> {
                        if (method.getName().equals("removeTransformer")) {
                            if (mode.equals("throws")) throw new IllegalStateException("owned-test-remove-failure");
                            return false;
                        }
                        try { return method.invoke(instrumentation, values); }
                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
        }
        lifecycle = TriangulationDefinitionLifecycle.forPremain(supplied,
                T039OwnerColdRemovalSelfCheck.class.getName());
        require(lifecycle.startupReason().equals("SUPPORTED_OWNED_PREMAIN"), "owned startup unavailable");
        Properties metadata = new Properties();
        try (InputStream input = Files.newInputStream(Path.of(argument))) { metadata.load(input); }
        fixture = Path.of(metadata.getProperty("fixtureJar")).toAbsolutePath().normalize();
        for (String key : new String[] {"classSha256", "shapeSha256", "jarSha256"}) {
            System.setProperty(PREFIX + key, metadata.getProperty(key));
        }
        System.setProperty(PREFIX + "profile", "owned");
        System.setProperty(PREFIX + "runId", "t050-owner-cold");
        System.setProperty(PREFIX + "codeSource", fixture.toString());
        System.setProperty(PREFIX + "loaderClass", T039OwnedTargetLoader.class.getName());
        System.setProperty(PREFIX + "helperSha256", resourceHash(
                "dev/turboism/validation/atlasimage/t039/T039ShadowHelper.class"));
        System.setProperty(PREFIX + "t038HelperSha256", resourceHash(
                "dev/turboism/validation/atlasimage/t038/T038ArrayHelper.class"));
        System.setProperty(PREFIX + "maxEvents", "16");
        System.setProperty(PREFIX + "shadowMode", "owned");
        if (mode.equals("wrong-hash")) System.setProperty(PREFIX + "classSha256", "0".repeat(64));
        if (mode.equals("default")) T039ShadowAgent.premain(null, lifecycle.instrumentation());
        else {
            if (!mode.equals("missing-token")) System.setProperty(PREFIX + "ownerColdRemovalOptIn", TOKEN);
            if (mode.equals("raw") || mode.equals("missing-token")) {
                rejected(() -> start(mode.equals("raw") ? raw : lifecycle.instrumentation()), "entry refusal");
                require(T039ShadowAgent.snapshot().state().equals("UNARMED"), "rejected entry armed session");
            } else start(lifecycle.instrumentation());
        }
    }

    private static void start(Instrumentation instrumentation) throws Exception {
        invoke("premainForOwnerColdRemoval", new Class<?>[] {String.class, Instrumentation.class},
                null, instrumentation);
    }

    private static void complete(String runId) throws Exception {
        invoke("completeOwnerColdRemoval", new Class<?>[] {String.class}, runId);
    }

    private static void abort(String runId) throws Exception {
        invoke("abortOwnerColdRemoval", new Class<?>[] {String.class}, runId);
    }

    private static void invoke(String name, Class<?>[] parameters, Object... arguments) throws Exception {
        try { T039ShadowAgent.class.getMethod(name, parameters).invoke(null, arguments); }
        catch (InvocationTargetException invocation) {
            if (invocation.getCause() instanceof Exception exception) throw exception;
            if (invocation.getCause() instanceof Error error) throw error;
            throw invocation;
        }
    }

    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static void rejected(Action action, String message) throws Exception {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { rejected = true; }
        require(rejected, message + " was accepted");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message + " check=" + checks + " snapshot=" + T039ShadowAgent.snapshot());
    }

    private static String resourceHash(String name) throws Exception {
        try (InputStream input = ClassLoader.getSystemResourceAsStream(name)) {
            if (input == null) throw new IllegalStateException("missing resource: " + name);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
        }
    }

    private static boolean admitted(TriangulationDefinitionLifecycle.Gate gate) {
        try (var lease = gate.acquire()) { return lease != null; }
    }

    private static TriangulationDefinitionLifecycle.Gate capture(Class<?> target) {
        // Actual owned class fingerprint, without invoking its initializer or methods.
        String fingerprint;
        try (InputStream input = target.getResourceAsStream("/" + target.getName().replace('.', '/') + ".class")) {
            if (input == null) throw new IllegalStateException("missing capture resource");
            fingerprint = DefinitionFingerprint.runtimeOf(input.readAllBytes());
        } catch (Exception failure) { throw new IllegalStateException(failure); }
        return lifecycle.capture(new Class<?>[] {target}, Map.of(target.getName(), fingerprint),
                DefinitionFingerprint::runtimeOf);
    }

    public static void main(String[] arguments) throws Exception {
        if (startupFailure != null) throw new AssertionError("owned regression startup failed", startupFailure);
        if (mode.equals("raw") || mode.equals("missing-token")) { finish(); return; }
        require(T039ShadowAgent.snapshot().state().equals("ARMED"), "entry did not arm");
        if (mode.equals("abort-before-target")) {
            rejected(() -> abort("wrong-run"), "wrong run abort");
            var before = capture(T039OwnerColdRemovalSelfCheck.class);
            require(admitted(before), "owned capture before abort refused");
            abort("t050-owner-cold");
            var aborted = T039ShadowAgent.snapshot();
            require(aborted.state().equals("BLOCKED") && aborted.removalStatus().equals("REMOVED")
                    && !aborted.transformerRegistered() && aborted.targetEvents() == 0
                    && aborted.candidateReturnedCount() == 0, "unobserved abort did not release and block");
            require(!admitted(before), "abort left old gate admitted");
            rejected(() -> T039ShadowAgent.freezeCapture("t050-owner-cold"), "abandoned freeze");
            var after = capture(T039OwnerColdRemovalSelfCheck.class);
            require(admitted(after), "new capture after abort refused");
            abort("t050-owner-cold");
            require(admitted(after), "already-removed abort performed an extra mutation");
            finish(); return;
        }
        if (!mode.equals("default")) {
            rejected(() -> complete("wrong-run"), "wrong run completion");
            rejected(() -> complete("t050-owner-cold"), "completion before target");
            rejected(() -> start(lifecycle.instrumentation()), "duplicate entry");
        }
        Class<?> target = new T039OwnedTargetLoader(fixture, ClassLoader.getSystemClassLoader()).defineTarget();
        var observed = T039ShadowAgent.snapshot();
        if (mode.equals("default")) {
            require(observed.state().equals("BLOCKED") && observed.removalStatus().equals("FAILED")
                    && observed.transformerRegistered() && observed.candidateReturnedCount() == 0,
                    "old callback removal failure not reproduced");
            rejected(() -> T039ShadowAgent.freezeCapture("t050-owner-cold"), "default failed freeze");
            finish(); return;
        }
        if (mode.equals("wrong-hash")) {
            require(observed.state().equals("BLOCKED") && observed.rejectionCount() == 1
                    && observed.candidateReturnedCount() == 0 && observed.transformerRegistered(),
                    "hash refusal did not remain pending and blocked");
            complete("t050-owner-cold");
            require(T039ShadowAgent.snapshot().state().equals("BLOCKED")
                    && T039ShadowAgent.snapshot().removalStatus().equals("REMOVED")
                    && !T039ShadowAgent.snapshot().transformerRegistered(), "blocked cleanup not truthful");
            rejected(() -> T039ShadowAgent.freezeCapture("t050-owner-cold"), "hash-refused freeze after cleanup");
            rejected(() -> complete("t050-owner-cold"), "blocked repeat completion");
            finish(); return;
        }
        require(observed.state().equals("PATCHED") && observed.removalStatus().equals("NOT_ATTEMPTED")
                && observed.transformerRegistered() && observed.targetEvents() == 1
                && observed.candidateCount() == 1 && observed.candidateReturnedCount() == 1,
                "callback did not leave truthful pending removal");
        rejected(() -> T039ShadowAgent.freezeCapture("t050-owner-cold"), "freeze before cold removal");
        if (mode.equals("false") || mode.equals("throws")) {
            complete("t050-owner-cold");
            var failed = T039ShadowAgent.snapshot();
            require(failed.state().equals("BLOCKED") && failed.transformerRegistered()
                    && failed.removalStatus().equals(mode.equals("false") ? "NOT_REMOVED" : "FAILED"),
                    "failed cold removal was accepted");
            rejected(() -> T039ShadowAgent.freezeCapture("t050-owner-cold"), "failed removal freeze");
            rejected(() -> complete("t050-owner-cold"), "failed completion retry");
            finish(); return;
        }
        if (mode.equals("late")) {
            new T039OwnedTargetLoader(fixture, ClassLoader.getSystemClassLoader()).defineTarget();
            require(T039ShadowAgent.snapshot().lateCallbacks() == 1
                    && T039ShadowAgent.snapshot().candidateReturnedCount() == 1, "late callback not inert");
            complete("t050-owner-cold");
            require(T039ShadowAgent.snapshot().removalStatus().equals("REMOVED"), "late cleanup failed");
            rejected(() -> T039ShadowAgent.freezeCapture("t050-owner-cold"), "late callback freeze");
            finish(); return;
        }
        AtomicBoolean callbackRejected = new AtomicBoolean();
        ClassFileTransformer callback = new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                    ProtectionDomain domain, byte[] bytes) {
                if (type == T039OwnerColdRemovalSelfCheck.class) {
                    try { complete("t050-owner-cold"); }
                    catch (IllegalStateException expected) { callbackRejected.set(true); }
                    catch (Exception unexpected) { throw new AssertionError(unexpected); }
                }
                return null;
            }
        };
        lifecycle.instrumentation().addTransformer(callback, true);
        try { lifecycle.instrumentation().retransformClasses(T039OwnerColdRemovalSelfCheck.class); }
        finally { lifecycle.instrumentation().removeTransformer(callback); }
        require(callbackRejected.get(), "completion inside callback was accepted");
        require(T039ShadowAgent.snapshot().removalStatus().equals("NOT_ATTEMPTED"),
                "callback refusal poisoned later cold completion");
        // Capture the harness, not the patched fixture, to isolate gateway lifecycle from shadow bytecode.
        var before = capture(T039OwnerColdRemovalSelfCheck.class);
        require(admitted(before), "pre-removal owned gate did not admit: " + before.reason());
        complete("t050-owner-cold");
        var removed = T039ShadowAgent.snapshot();
        require(removed.state().equals("PATCHED") && removed.removalStatus().equals("REMOVED")
                && !removed.transformerRegistered(), "cold removal did not complete");
        require(!admitted(before) && before.reason().equals("OWNED_DEFINITION_MUTATION"),
                "cold removal left a stale gate admitted");
        rejected(() -> complete("t050-owner-cold"), "duplicate completion");
        var after = capture(T039OwnerColdRemovalSelfCheck.class);
        require(admitted(after), "fresh post-removal gate did not admit: " + after.reason());
        var frozen = T039ShadowAgent.freezeCapture("t050-owner-cold");
        require(frozen.size() == 43 && frozen.get("removalStatus").equals("REMOVED"), "freeze schema/status changed");
        rejected(() -> T039ShadowAgent.freezeCapture("t050-owner-cold"), "repeat freeze");
        require(target.getClassLoader() instanceof T039OwnedTargetLoader, "fixture loader changed");
        finish();
    }

    private static void finish() {
        System.out.println("T039_OWNER_COLD_GATEWAY_PASS mode=" + mode + " checks=" + checks
                + " officialInitialized=false geometryExecuted=false EditorStarted=false");
    }
}
