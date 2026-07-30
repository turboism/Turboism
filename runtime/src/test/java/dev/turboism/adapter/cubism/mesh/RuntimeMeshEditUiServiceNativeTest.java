package dev.turboism.adapter.cubism.mesh;

import dev.turboism.sdk.cubism.mesh.MeshEditUiService;
import org.junit.jupiter.api.Test;

import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSlider;
import java.awt.Component;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class RuntimeMeshEditUiServiceNativeTest {

    @Test
    void attachesAtTheLegacyTopPositionAndRoutesChangesThenRemovesOnClose() {
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

        final JComponent root = (JComponent) nativeWidget.getComponent(0);
        assertEquals("mesh.mirror-axis.angle", root.getName());
        final JSlider slider = find(root, JSlider.class);
        assertNotNull(slider);
        slider.setValue(123);
        assertEquals(12.3f, changed.get(), 0.0001f);

        registration.close();
        assertEquals(1, nativeWidget.getComponentCount());
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
