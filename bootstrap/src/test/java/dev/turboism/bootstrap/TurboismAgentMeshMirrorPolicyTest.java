package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.mesh.NativeMeshMirrorBridge;
import dev.turboism.adapter.cubism.mesh.RuntimeMeshEditUiService;
import dev.turboism.adapter.cubism.mesh.RuntimeMeshMirrorAxisService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class TurboismAgentMeshMirrorPolicyTest {
    @AfterEach
    void clearBridge() {
        NativeMeshMirrorBridge.uninstall();
    }

    @Test
    void disabledDecisionHasZeroInstrumentationOrBridgeSideEffects() throws Exception {
        final AtomicInteger instrumentationCalls = new AtomicInteger();
        final Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {Instrumentation.class},
            (proxy, method, args) -> {
                instrumentationCalls.incrementAndGet();
                return defaultValue(method.getReturnType());
            }
        );
        final Method install = TurboismAgent.class.getDeclaredMethod(
            "installMeshMirrorHook",
            dev.turboism.preview.PreviewRuntime.class,
            Instrumentation.class,
            HostClassLocator.LocatedHost.class,
            boolean.class
        );
        install.setAccessible(true);

        install.invoke(null, null, instrumentation, null, false);

        assertEquals(0, instrumentationCalls.get());
        NativeMeshMirrorBridge.install(new RuntimeMeshMirrorAxisService(), new RuntimeMeshEditUiService());
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0d;
        if (type == char.class) return '\0';
        return null;
    }
}
