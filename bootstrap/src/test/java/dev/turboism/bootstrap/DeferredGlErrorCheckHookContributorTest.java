package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.adapter.cubism.optimization.deferred.DeferredGlErrorCheckTransformer;
import dev.turboism.adapter.cubism.optimization.uniform.UniformLocationHookBridge;
import dev.turboism.runtime.log.RuntimeDiagnostics;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeferredGlErrorCheckHookContributorTest {
    private static final Map<String, MethodType> SEAM = Map.of(
            UniformLocationHookBridge.BEGIN_PROPERTY, MethodType.methodType(long.class, Object.class),
            UniformLocationHookBridge.END_PROPERTY, MethodType.methodType(void.class, long.class),
            UniformLocationHookBridge.ERROR_PROPERTY, MethodType.methodType(void.class, Object.class, int.class),
            UniformLocationHookBridge.DEFER_QUERY_PROPERTY,
                    MethodType.methodType(int.class, Object.class, String.class, boolean.class),
            UniformLocationHookBridge.DEFER_REPORT_PROPERTY, MethodType.methodType(Object.class));
    private final Map<String, Object> previous = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private final List<String> messages = new ArrayList<>();
    private final AtomicInteger instrumentationCalls = new AtomicInteger();

    @BeforeEach
    void configure() {
        var keys = new ArrayList<>(SEAM.keySet());
        keys.add(UniformLocationHookBridge.STATS_PROPERTY);
        keys.add(DeferredGlErrorCheckTransformer.ENABLE_PROPERTY);
        for (String key : keys) {
            previous.put(key, System.getProperties().remove(key));
        }
        System.setProperty(DeferredGlErrorCheckTransformer.ENABLE_PROPERTY, "true");
        RuntimeDiagnostics.clear();
        RuntimeDiagnostics.install((level, component, message, failure) -> {
            messages.add(message);
            if (level == RuntimeDiagnostics.Level.WARN) warnings.add(message);
        });
    }

    @AfterEach
    void restore() {
        previous.forEach((key, value) -> {
            if (value == null) System.getProperties().remove(key);
            else System.getProperties().put(key, value);
        });
        RuntimeDiagnostics.clear();
    }

    @Test
    void missingOrWrongTypedLifecycleSlotWarnsBeforeAnyHostAccess() throws Exception {
        for (String key : SEAM.keySet()) {
            readySeam();
            System.getProperties().remove(key);
            assertInactive("uniform-seam-unavailable");
            readySeam();
            // A MethodHandle with the wrong signature is as unusable as a missing slot.
            System.getProperties().put(key, MethodHandles.empty(MethodType.methodType(void.class)));
            assertInactive("uniform-seam-unavailable");
        }
    }

    @Test
    void unreadyOrFailingStatisticsWarnBeforeAnyHostAccess() throws Exception {
        for (String key : List.of("active", "deferredReady", "mutationCoverage")) {
            readySeam();
            var values = new LinkedHashMap<>(Map.of("active", 1L, "deferredReady", 1L, "mutationCoverage", 1L));
            values.put(key, 0L);
            System.getProperties()
                    .put(UniformLocationHookBridge.STATS_PROPERTY, (Supplier<Map<String, Long>>) () -> values);
            assertInactive("uniform-seam-not-ready");
        }
        readySeam();
        System.getProperties().put(UniformLocationHookBridge.STATS_PROPERTY, (Supplier<Object>) () -> {
            throw new IllegalStateException("observer failed");
        });
        assertInactive("uniform-seam-not-ready");
        readySeam();
        System.getProperties().put(UniformLocationHookBridge.STATS_PROPERTY, "not a supplier");
        assertInactive("uniform-seam-not-ready");
    }

    @Test
    void explicitOptOutIsVisibleWithoutAWarning() throws Exception {
        System.setProperty(DeferredGlErrorCheckTransformer.ENABLE_PROPERTY, "false");
        new DeferredGlErrorCheckHookContributor().installAdmitted(environment()).close();
        assertTrue(messages.stream()
                .anyMatch(line -> line.contains("deferred=INACTIVE") && line.contains("reason=preference-disabled")));
        assertTrue(warnings.isEmpty());
        assertEquals(0, instrumentationCalls.get());
    }

    @Test
    void installationFailureWarnsAndNeverClaimsActive() throws Exception {
        readySeam();
        // No host: the outer bootstrap boundary must still tolerate a failed install.
        new DeferredGlErrorCheckHookContributor().install(environment()).close();
        assertTrue(warnings.stream()
                .anyMatch(line -> line.contains("deferred=INACTIVE") && line.contains("reason=installation-failed")));
        assertFalse(messages.stream().anyMatch(line -> line.contains("deferred=ACTIVE")));
        assertEquals(0, instrumentationCalls.get());
    }

    private void readySeam() {
        SEAM.forEach((key, type) -> System.getProperties().put(key, MethodHandles.empty(type)));
        System.getProperties().put(UniformLocationHookBridge.STATS_PROPERTY, (Supplier<Map<String, Long>>)
                () -> Map.of("active", 1L, "deferredReady", 1L, "mutationCoverage", 1L));
    }

    private void assertInactive(String reason) throws Exception {
        warnings.clear();
        new DeferredGlErrorCheckHookContributor().installAdmitted(environment()).close();
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("deferred=INACTIVE"));
        assertTrue(warnings.get(0).contains("reason=" + reason), warnings.toString());
        assertEquals(0, instrumentationCalls.get());
    }

    private HookEnvironment environment() {
        var instrumentation = (Instrumentation) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Instrumentation.class}, (proxy, method, args) -> {
                    instrumentationCalls.incrementAndGet();
                    throw new AssertionError("must not access host instrumentation");
                });
        return HookEnvironment.builder()
                .fullRuntimeAdmission(true)
                .instrumentation(instrumentation)
                .build();
    }
}
