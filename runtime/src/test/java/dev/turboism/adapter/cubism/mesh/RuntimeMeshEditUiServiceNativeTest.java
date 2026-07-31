package dev.turboism.adapter.cubism.mesh;

import dev.turboism.sdk.cubism.mesh.MeshEditUiService;
import org.junit.jupiter.api.Test;

import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class RuntimeMeshEditUiServiceNativeTest {

    @Test
    void attachesAtTheLegacyTopPositionAndRoutesChangesThenRemovesOnClose() throws Exception {
        final RuntimeMeshEditUiService service = new RuntimeMeshEditUiService();
        final RuntimeMeshMirrorAxisService axis = new RuntimeMeshMirrorAxisService();
        final AtomicReference<Float> changed = new AtomicReference<>();
        final JPanel nativeWidget = new JPanel();
        nativeWidget.add(new JPanel());

        final var registration = service.contributeMirrorAxisAngleControl(
            new MeshEditUiService.MirrorAxisAngleControl(
                "mesh.mirror-axis.angle", "Mirror Axis Rotation",
                -180.0f, 180.0f, 0.1f, changed::set
            )
        );
        service.attachNative(new Object(), nativeWidget, axis);
        SwingUtilities.invokeAndWait(() -> { });

        final JComponent root = (JComponent) nativeWidget.getComponent(0);
        assertEquals("mesh.mirror-axis.angle", root.getName());
        final JSlider slider = find(root, JSlider.class);
        assertNotNull(slider);
        slider.setValue(123);
        assertEquals(12.3f, changed.get(), 0.0001f);

        registration.close();
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(1, nativeWidget.getComponentCount());
    }

    @Test
    void offEdtAttachmentAndCloseAreNonBlockingAndStaleSafe() throws Exception {
        final RuntimeMeshEditUiService service = new RuntimeMeshEditUiService();
        final RuntimeMeshMirrorAxisService axis = new RuntimeMeshMirrorAxisService();
        final JPanel widget = new JPanel();
        final var registration = service.contributeMirrorAxisAngleControl(
            new MeshEditUiService.MirrorAxisAngleControl(
                "mesh.mirror-axis.angle", "Mirror Axis Rotation",
                -180.0f, 180.0f, 0.1f, ignored -> { }
            )
        );

        service.attachNative(new Object(), widget, axis);
        registration.close();
        SwingUtilities.invokeAndWait(() -> { });

        assertEquals(0, widget.getComponentCount());
    }

    @Test
    void queuedAttachmentCannotSurviveSessionReset() throws Exception {
        final RuntimeMeshEditUiService service = new RuntimeMeshEditUiService();
        final RuntimeMeshMirrorAxisService axis = new RuntimeMeshMirrorAxisService();
        final JPanel widget = new JPanel();
        service.contributeMirrorAxisAngleControl(
            new MeshEditUiService.MirrorAxisAngleControl(
                "mesh.mirror-axis.angle", "Mirror Axis Rotation",
                -180.0f, 180.0f, 0.1f, ignored -> { }
            )
        );

        service.attachNative(new Object(), widget, axis);
        service.resetSession();
        SwingUtilities.invokeAndWait(() -> { });

        assertEquals(0, widget.getComponentCount());
    }

    @Test
    void reportsTheLiveConsumerContributionLifecycle() {
        final RuntimeMeshEditUiService service = new RuntimeMeshEditUiService();
        final java.util.List<Boolean> changes = new java.util.ArrayList<>();
        final var observer = service.observeContribution(changes::add);
        final var registration = service.contributeMirrorAxisAngleControl(
            new MeshEditUiService.MirrorAxisAngleControl(
                "mesh.mirror-axis.angle", "Mirror Axis Rotation",
                -180.0f, 180.0f, 0.1f, ignored -> { }
            )
        );

        registration.close();
        observer.close();

        assertEquals(java.util.List.of(false, true, false), changes);
    }

    private static <T extends Component> T find(final Component root, final Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                final T found = find(child, type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
