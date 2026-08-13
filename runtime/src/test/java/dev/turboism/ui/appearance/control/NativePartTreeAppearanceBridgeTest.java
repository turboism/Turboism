package dev.turboism.ui.appearance.control;

import dev.turboism.sdk.ui.appearance.UiColor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import java.awt.Color;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NativePartTreeAppearanceBridgeTest {
    @AfterEach
    void clear() {
        NativePartTreeAppearanceBridge.clearForTesting();
    }

    @Test
    void partLabelsAndFoldersSharePartPaletteAndRestoreReusedLabel() throws Exception {
        final PaletteAppearanceCoordinator coordinator = new PaletteAppearanceCoordinator();
        final PaletteAppearanceCoordinator.Scope scope = scope(7);
        coordinator.reconcile(scope);
        register(coordinator, scope, "PartA", 0x112233);
        register(coordinator, scope, "FolderA", 0x445566);
        final PartTreeControlAppearanceProvider provider = new PartTreeControlAppearanceProvider(coordinator);
        NativePartTreeAppearanceBridge.install(7, selectors(), provider);
        final JLabel label = new JLabel();
        label.setForeground(Color.BLACK);

        render(() -> NativePartTreeAppearanceBridge.afterRender(label, new Node(new PartSource("PartA", List.of()))));
        assertEquals(new Color(0x11, 0x22, 0x33), label.getForeground());
        render(() -> NativePartTreeAppearanceBridge.afterRender(
            label, new Node(new PartSource("FolderA", List.of("child")))
        ));
        assertEquals(new Color(0x44, 0x55, 0x66), label.getForeground());
        render(() -> NativePartTreeAppearanceBridge.afterRender(label, new Node(new OtherSource())));
        assertEquals(Color.BLACK, label.getForeground());
    }

    @Test
    void deformerAndArtMeshRowsResolveDeformerPartPalette() throws Exception {
        final PaletteAppearanceCoordinator coordinator = new PaletteAppearanceCoordinator();
        final PaletteAppearanceCoordinator.Scope scope = scope(7);
        coordinator.reconcile(scope);
        coordinator.register("plugin", 1, scope, PaletteAppearanceCoordinator.Palette.DEFORMER_PART,
            "WarpA", PaletteAppearanceCoordinator.Property.TEXT_COLOR, color(0x336699));
        coordinator.register("plugin", 1, scope, PaletteAppearanceCoordinator.Palette.DEFORMER_PART,
            "ArtMeshA", PaletteAppearanceCoordinator.Property.BACKGROUND_COLOR, color(0x99CC22));
        final PartTreeControlAppearanceProvider provider = new PartTreeControlAppearanceProvider(coordinator);
        NativePartTreeAppearanceBridge.install(7, selectors(), provider);
        final JLabel label = new JLabel();
        label.setForeground(Color.BLACK);

        render(() -> NativePartTreeAppearanceBridge.afterRender(label, new Node(new DeformerSource("WarpA"))));
        assertEquals(new Color(0x33, 0x66, 0x99), label.getForeground());

        render(() -> NativePartTreeAppearanceBridge.afterRender(label, new Node(new ArtMeshSource("ArtMeshA"))));
        assertEquals(new Color(0x99, 0xCC, 0x22), label.getBackground());
    }

    private static void register(
        final PaletteAppearanceCoordinator coordinator,
        final PaletteAppearanceCoordinator.Scope scope,
        final String id,
        final int rgb
    ) {
        coordinator.register(
            "plugin", 1, scope, PaletteAppearanceCoordinator.Palette.PART, id,
            PaletteAppearanceCoordinator.Property.TEXT_COLOR, color(rgb)
        );
    }

    private static UiColor color(final int rgb) {
        return new UiColor(
            ((rgb >> 16) & 0xFF) / 255.0F,
            ((rgb >> 8) & 0xFF) / 255.0F,
            (rgb & 0xFF) / 255.0F,
            1.0F
        );
    }

    private static PaletteAppearanceCoordinator.Scope scope(final long hostGeneration) {
        return new PaletteAppearanceCoordinator.Scope("content", 1, "model", 1, hostGeneration, 1);
    }

    private static NativePartTreeAppearanceBridge.Selectors selectors() {
        return new NativePartTreeAppearanceBridge.Selectors(
            Node.class.getName().replace('.', '/'), "source",
            PartSource.class.getName().replace('.', '/'),
            DeformerSource.class.getName().replace('.', '/'),
            ArtMeshSource.class.getName().replace('.', '/'),
            "getId", "getIdString", "getChildren",
            Node.class.getClassLoader()
        );
    }

    private static void render(final Runnable action) throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(action);
    }

    static final class Node {
        private final Object source;
        Node(final Object source) { this.source = source; }
        public Object source() { return source; }
    }

    public static final class DeformerSource {
        private final Object id;
        DeformerSource(final String id) { this.id = new Id(id); }
        public Object getId() { return id; }
        public List<?> getChildren() { return List.of(); }
    }

    public static final class ArtMeshSource {
        private final Object id;
        ArtMeshSource(final String id) { this.id = new Id(id); }
        public Object getId() { return id; }
        public List<?> getChildren() { return List.of(); }
    }

    static final class PartSource {
        private final Object id;
        private final List<?> children;
        PartSource(final String id, final List<?> children) { this.id = new Id(id); this.children = children; }
        public Object getId() { return id; }
        public List<?> getChildren() { return children; }
    }

    static final class OtherSource { }
    record Id(String value) { public String getIdString() { return value; } }
}
