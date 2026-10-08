package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.mesh.TriangulationDefinitionLifecycle;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;

/** Validation-only first premain contributor; never defines the official target here. */
public final class TriangulationSingleAgentShadowPreHook implements HookContributor {
    static final String SHADOW = "dev.turboism.validation.atlasimage.t039.T039ShadowAgent";
    static final String SHADOW_CLASS = "com.live2d.util.f.g";
    static final String SHADOW_SHA256 = "12d5fea3e9cbfaf5e16f7148731c3e499232f4679aefee127561e9cb52cb8680";
    private static volatile String status = "NOT_INSTALLED";
    private static volatile String failureReason = "";
    private static Class<?> entry;
    private static TriangulationSingleAgentValidationHook.Sidecar sidecar;

    public TriangulationSingleAgentShadowPreHook() {}
    public static String status() { return status; }
    public static String failureReason() { return failureReason; }
    @Override public String id() { return "T050_SINGLE_AGENT_SHADOW_PRE"; }
    @Override public Phase phase() { return Phase.PREMAIN; }
    @Override public boolean closesOnProcessExit() { return true; }
    @Override public boolean admitted(HookEnvironment environment) {
        return TriangulationSingleAgentValidationHook.TOKEN.equals(System.getProperty(
                TriangulationSingleAgentValidationHook.PREFIX + "optIn"))
                && "scene".equals(System.getProperty(TriangulationSingleAgentValidationHook.PREFIX + "mode"))
                && "5303".equals(System.getProperty("turboism.validation.atlasImageShadow.version"));
    }

    static Object call(String method, Class<?>[] parameters, Object... arguments) throws Exception {
        if (entry == null) throw new IllegalStateException("shadow entry absent");
        try { return entry.getMethod(method, parameters).invoke(null, arguments); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            if (failure.getCause() instanceof Error error) throw error;
            throw failure;
        }
    }
    private static Object snapshot() throws Exception { return call("snapshot", new Class<?>[0]); }
    private static Object value(Object snapshot, String field) throws Exception {
        return snapshot.getClass().getMethod(field).invoke(snapshot);
    }
    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
    private static void abortRegistered() throws Exception {
        if (entry == null) return;
        Object before = snapshot();
        if (Boolean.TRUE.equals(value(before, "transformerRegistered"))) {
            call("abortOwnerColdRemoval", new Class<?>[] {String.class}, value(before, "runId"));
            require(!Boolean.TRUE.equals(value(snapshot(), "transformerRegistered")), "shadow cleanup removal unconfirmed");
        }
    }

    @Override public AutoCloseable install(HookEnvironment environment) throws Exception {
        Instrumentation instrumentation = environment.instrumentation();
        var owner = TriangulationDefinitionLifecycle.ownedBy(instrumentation);
        require(owner != null && owner.startupReason().equals("SUPPORTED_OWNED_PREMAIN"), "shadow needs owned premain");
        require(entry == null && sidecar == null, "shadow pre-contributor already installed");
        require(SHADOW_SHA256.equals(System.getProperty(
                TriangulationSingleAgentValidationHook.PREFIX + "shadowSha256")), "unreviewed owner-cold shadow sidecar");
        require("T039_OWNED_COLD_REMOVAL_V1".equals(System.getProperty(
                "turboism.validation.t039.ownerColdRemovalOptIn")), "shadow owner-cold opt-in missing");
        try {
            sidecar = TriangulationSingleAgentValidationHook.sidecar("shadow", "t039-shadow-agent.jar", SHADOW);
            entry = TriangulationSingleAgentValidationHook.append(instrumentation, sidecar);
            call("premainForOwnerColdRemoval", new Class<?>[] {String.class, Instrumentation.class}, null, instrumentation);
            Object armed = snapshot();
            require("ARMED".equals(value(armed, "state")) && "5303".equals(value(armed, "profile"))
                    && "shadow-ready".equals(value(armed, "shadowMode"))
                    && Boolean.TRUE.equals(value(armed, "transformerRegistered"))
                    && "NOT_ATTEMPTED".equals(value(armed, "removalStatus"))
                    && Integer.valueOf(0).equals(value(armed, "targetEvents")), "shadow did not arm before definition");
            status = "ARMED";
            System.out.println("TRI_SINGLE_AGENT_SHADOW_PRE status=ARMED targetDefined=false");
            return () -> {
                try {
                    abortRegistered();
                    System.out.println("TRI_SINGLE_AGENT_SHADOW_CLEANUP registered=false");
                } finally { sidecar.close(); }
            };
        } catch (Exception | Error failure) {
            status = "REJECTED";
            failureReason = failure.getClass().getName() + ":" + Objects.toString(failure.getMessage(), "");
            try { abortRegistered(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            if (sidecar != null) sidecar.close();
            throw failure;
        }
    }

    static void completeAfterProductionRegistration() throws Exception {
        require(status.equals("ARMED"), "shadow pre-contributor unavailable");
        Class<?> target = Class.forName(SHADOW_CLASS, false, ClassLoader.getSystemClassLoader());
        require(target.getClassLoader() == ClassLoader.getSystemClassLoader(), "shadow target loader rejected");
        String runId = System.getProperty("turboism.validation.t039.runId");
        call("completeOwnerColdRemoval", new Class<?>[] {String.class}, runId);
        Object complete = snapshot();
        require("SHADOW_READY".equals(value(complete, "state"))
                && "REMOVED".equals(value(complete, "removalStatus"))
                && Boolean.FALSE.equals(value(complete, "transformerRegistered"))
                && Integer.valueOf(1).equals(value(complete, "targetEvents"))
                && Integer.valueOf(1).equals(value(complete, "candidateCount"))
                && Integer.valueOf(1).equals(value(complete, "candidateReturnedCount"))
                && Integer.valueOf(0).equals(value(complete, "rejectionCount"))
                && Integer.valueOf(0).equals(value(complete, "lateCallbacks")), "shadow cold completion rejected");
        status = "REMOVED_AFTER_PRODUCTION_REGISTRATION";
        System.out.println("TRI_SINGLE_AGENT_SHADOW_POST status=" + status + " lazyCapturePerformed=false");
    }
}
