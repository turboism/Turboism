package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.optimization.modelupdate.ModelUpdateSkipBridge;
import dev.turboism.config.optimization.OptimizationCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class TurboismAgentMeshSetupElisionAdmissionTest {

    @AfterEach
    void resetPolicy() {
        ModelUpdateSkipBridge.setConfiguredDefault(false);
        final var properties = System.getProperties();
        properties.remove(
                dev.turboism.adapter.cubism.optimization.meshsetup.SkippedFrameMeshSetupElisionBridge
                        .PREDICATE_PROPERTY);
        properties.remove(
                dev.turboism.adapter.cubism.optimization.meshsetup.SkippedFrameMeshSetupElisionBridge
                        .COMPLETE_PROPERTY);
        properties.remove(
                dev.turboism.adapter.cubism.optimization.meshsetup.SkippedFrameMeshSetupElisionBridge.FAILURE_PROPERTY);
        properties.remove(
                dev.turboism.adapter.cubism.optimization.meshsetup.SkippedFrameMeshSetupElisionBridge.STATS_PROPERTY);
    }

    @Test
    void admitsOnlyAfterFullRuntimeAdmission() {
        final var contributor = new SkippedFrameMeshSetupElisionHookContributor();
        assertTrue(contributor.admitted(environment(true)));
        assertFalse(contributor.admitted(environment(false)));
    }

    @Test
    void preferenceOffInstallsNothingAndNeverTouchesTheHost() throws Exception {
        withProperty(
                OptimizationCatalog.MESH_SETUP_ELISION.property(),
                "false",
                () -> withProperty(OptimizationCatalog.MODEL_UPDATE_SKIP.property(), "true", () -> {
                    final var instrumentationCalls = new java.util.concurrent.atomic.AtomicInteger();
                    final var instrumentation =
                            (java.lang.instrument.Instrumentation) java.lang.reflect.Proxy.newProxyInstance(
                                    getClass().getClassLoader(),
                                    new Class<?>[] {java.lang.instrument.Instrumentation.class},
                                    (proxy, method, args) -> {
                                        instrumentationCalls.incrementAndGet();
                                        throw new UnsupportedOperationException(method.getName());
                                    });
                    final var contributor = new SkippedFrameMeshSetupElisionHookContributor();
                    try {
                        assertNotNull(contributor.installAdmitted(HookEnvironment.builder()
                                .fullRuntimeAdmission(true)
                                .instrumentation(instrumentation)
                                .build()));
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                    org.junit.jupiter.api.Assertions.assertEquals(
                            0, instrumentationCalls.get(), "preference off must not touch the host's instrumentation");
                }));
    }

    @Test
    void modelUpdateSkipDisabledRefusesBeforeTouchingTheHost() {
        withProperty(
                OptimizationCatalog.MESH_SETUP_ELISION.property(),
                "true",
                () -> withProperty(OptimizationCatalog.MODEL_UPDATE_SKIP.property(), "false", () -> {
                    final var contributor = new SkippedFrameMeshSetupElisionHookContributor();
                    assertNotNull(contributor.install(environment(true)));
                    assertFalse(System.getProperties()
                            .containsKey(
                                    dev.turboism.adapter.cubism.optimization.meshsetup
                                            .SkippedFrameMeshSetupElisionBridge.PREDICATE_PROPERTY));
                }));
    }

    @Test
    void preferenceOnWithoutHostFailsSafe() {
        withProperty(
                OptimizationCatalog.MESH_SETUP_ELISION.property(),
                "true",
                () -> withProperty(OptimizationCatalog.MODEL_UPDATE_SKIP.property(), "true", () -> {
                    final var contributor = new SkippedFrameMeshSetupElisionHookContributor();
                    assertNotNull(contributor.install(environment(true)));
                }));
    }

    private static void withProperty(final String name, final String value, final Runnable body) {
        final String prior = System.getProperty(name);
        System.setProperty(name, value);
        try {
            body.run();
        } finally {
            if (prior == null) System.clearProperty(name);
            else System.setProperty(name, prior);
        }
    }

    private static HookEnvironment environment(final boolean admitted) {
        return HookEnvironment.builder()
                .profile("5.3.03")
                .fullRuntimeAdmission(admitted)
                .build();
    }
}
