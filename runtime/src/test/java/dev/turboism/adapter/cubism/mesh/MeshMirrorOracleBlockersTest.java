package dev.turboism.adapter.cubism.mesh;

import dev.turboism.sdk.cubism.mesh.MeshEditUiService.MirrorAxisAngleControl;
import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

final class MeshMirrorOracleBlockersTest {

    @Test
    void bridgeIsInertWhenDisabledOrAxisCenterIsUnknown() {
        final RuntimeMeshMirrorAxisService axis = new RuntimeMeshMirrorAxisService();
        axis.setCurrentAngleDegrees(45.0f);
        final RuntimeMeshEditUiService ui = new RuntimeMeshEditUiService();
        final Point original = new Point(9.0f, 9.0f);

        NativeMeshMirrorBridge.install(axis, ui, false);
        assertSame(original, NativeMeshMirrorBridge.adjustPoint(original, new State(true), new Point(1.0f, 1.0f)));

        NativeMeshMirrorBridge.uninstall();
        NativeMeshMirrorBridge.install(axis, ui, true);
        assertSame(original, NativeMeshMirrorBridge.adjustPoint(
            original, new State(false), new Point(1.0f, 1.0f)
        ));
    }

    @org.junit.jupiter.api.AfterEach
    void clearBridge() {
        NativeMeshMirrorBridge.uninstall();
    }

    @Test
    void sessionResetClearsAngleAndNativeUiBindings() {
        final RuntimeMeshMirrorAxisService axis = new RuntimeMeshMirrorAxisService();
        final RuntimeMeshEditUiService ui = new RuntimeMeshEditUiService();
        axis.setCurrentAngleDegrees(45.0f);
        final AtomicInteger changes = new AtomicInteger();
        final Registration registration = ui.contributeMirrorAxisAngleControl(new MirrorAxisAngleControl(
            "mesh.mirror-axis.angle", "Angle", "", -180.0f, 180.0f, 0.1f, ignored -> changes.incrementAndGet()
        ));
        ui.attachNative(new JPanel(), new JPanel(), axis);

        ui.resetSession();
        axis.resetSession();

        assertEquals(0.0f, axis.currentAngleDegrees());
        assertNull(ui.nativeAttachment());
        registration.close();
        assertEquals(0, changes.get());
    }

    @Test
    void helperBootstrapUsesTheActualHostLoaderRatherThanTheSystemLoader() {
        final Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] { Instrumentation.class },
            (proxy, method, args) -> defaultValue(method.getReturnType())
        );
        final ClassLoader actualHostLoader = new ClassLoader(getClass().getClassLoader()) { };

        MeshMirrorHelperBootstrap.ensureAvailable(instrumentation, actualHostLoader);
    }

    private static Object defaultValue(final Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }

    public static final class Point {
        private final float x;
        private final float y;
        public Point(final float x, final float y) { this.x = x; this.y = y; }
        public float getX() { return x; }
        public float getY() { return y; }
    }

    public record State(boolean isVertical) { }
}
