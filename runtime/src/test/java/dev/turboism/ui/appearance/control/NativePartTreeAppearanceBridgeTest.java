package dev.turboism.ui.appearance.control;

import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.ui.appearance.ControlAppearanceContribution;
import dev.turboism.sdk.ui.appearance.ControlAppearanceStyle;
import dev.turboism.sdk.ui.appearance.ControlAppearanceTarget;
import dev.turboism.sdk.ui.appearance.UiColor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import java.awt.Color;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NativePartTreeAppearanceBridgeTest {
    @AfterEach void clear() { NativePartTreeAppearanceBridge.clearForTesting(); }

    @Test
    void separatesLeafAndFolderTargetsAndRestoresReusedLabel() throws Exception {
        final ControlAppearanceCoordinator coordinator = new ControlAppearanceCoordinator();
        coordinator.replaceHostGeneration(7);
        coordinator.put("plugin", 1, contribution("leaf", new ControlAppearanceTarget.PartLabel(new PartId("PartA")), 0xFF112233));
        coordinator.put("plugin", 1, contribution("folder", new ControlAppearanceTarget.PartFolder(new PartId("FolderA")), 0xFF445566));
        final PartTreeControlAppearanceProvider provider = new PartTreeControlAppearanceProvider(coordinator);
        NativePartTreeAppearanceBridge.install(7, selectors(), provider);
        final JLabel label = new JLabel();
        label.setForeground(Color.BLACK);

        render(() -> NativePartTreeAppearanceBridge.afterRender(label, new Node(new PartSource("PartA", List.of()))));
        assertEquals(new Color(0x11, 0x22, 0x33), label.getForeground());
        render(() -> NativePartTreeAppearanceBridge.afterRender(label, new Node(new PartSource("FolderA", List.of("child")))));
        assertEquals(new Color(0x44, 0x55, 0x66), label.getForeground());
        render(() -> NativePartTreeAppearanceBridge.afterRender(label, new Node(new OtherSource())));
        assertEquals(Color.BLACK, label.getForeground());
    }

    private static ControlAppearanceContribution contribution(String id, ControlAppearanceTarget target, int color) {
        return new ControlAppearanceContribution(id, target,
            new ControlAppearanceStyle(Optional.of(new UiColor(color)), Optional.empty(), Optional.empty()));
    }

    private static NativePartTreeAppearanceBridge.Selectors selectors() {
        return new NativePartTreeAppearanceBridge.Selectors(
            Node.class.getName().replace('.', '/'), "source",
            PartSource.class.getName().replace('.', '/'), "getId", "getIdString", "getChildren",
            Node.class.getClassLoader()
        );
    }

    private static void render(Runnable action) throws Exception { javax.swing.SwingUtilities.invokeAndWait(action); }

    static final class Node {
        private final Object source;
        Node(Object source) { this.source = source; }
        public Object source() { return source; }
    }
    static final class PartSource {
        private final Id id;
        private final List<?> children;
        PartSource(String id, List<?> children) { this.id = new Id(id); this.children = children; }
        public Id getId() { return id; }
        public List<?> getChildren() { return children; }
    }
    static final class OtherSource { }
    record Id(String value) { public String getIdString() { return value; } }
}
