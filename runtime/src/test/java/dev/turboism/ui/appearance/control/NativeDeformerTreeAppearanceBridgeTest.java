package dev.turboism.ui.appearance.control;

import dev.turboism.sdk.ui.appearance.UiColor;
import dev.turboism.ui.appearance.control.fixture.HiddenRowFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Font;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class NativeDeformerTreeAppearanceBridgeTest {
    @AfterEach
    void clearBridge() {
        NativeDeformerTreeAppearanceBridge.clearForTesting();
    }

    @Test
    void resolvesOnlyDeformerRowsAndResetsReusedLabelsBeforeOptionalOverlay() throws Exception {
        final PaletteAppearanceCoordinator coordinator = coordinator();
        register(coordinator, "WarpA", PaletteAppearanceCoordinator.Property.TEXT_COLOR, color(0x336699));
        final DeformerTreeControlAppearanceProvider provider = new DeformerTreeControlAppearanceProvider(coordinator);
        NativeDeformerTreeAppearanceBridge.install(41, selectors(), provider);

        final JLabel reused = new JLabel("native");
        final Color nativeForeground = new Color(0x22, 0x22, 0x22);
        reused.setForeground(nativeForeground);
        final NativeContainer container = new NativeContainer(nativeForeground);
        container.add(reused);

        render(() -> assertSame(
            container,
            NativeDeformerTreeAppearanceBridge.afterRender(container, new Row(new DeformerSource("WarpA")), false, false)
        ));
        assertEquals(new Color(0x33, 0x66, 0x99), reused.getForeground());

        render(() -> NativeDeformerTreeAppearanceBridge.afterRender(
            container, new Row(new DeformerSource("WarpB")), false, false
        ));
        assertEquals(nativeForeground, reused.getForeground());

        render(() -> NativeDeformerTreeAppearanceBridge.afterRender(
            container, new Row(new DrawableSource("WarpA")), false, false
        ));
        assertEquals(nativeForeground, reused.getForeground());
    }

    @Test
    void selectedAndFocusedRowsKeepForegroundOverlayAndNativeBackground() throws Exception {
        final PaletteAppearanceCoordinator coordinator = coordinator();
        register(coordinator, "WarpA", PaletteAppearanceCoordinator.Property.TEXT_COLOR, color(0x336699));
        NativeDeformerTreeAppearanceBridge.install(
            41, selectors(), new DeformerTreeControlAppearanceProvider(coordinator)
        );
        final JLabel label = new JLabel("native");
        final Color selectedBackground = new Color(0x44, 0x55, 0x66);
        label.setBackground(selectedBackground);

        render(() -> NativeDeformerTreeAppearanceBridge.afterRender(
            label, new Row(new DeformerSource("WarpA")), true, true
        ));

        assertEquals(new Color(0x33, 0x66, 0x99), label.getForeground());
        assertEquals(selectedBackground, label.getBackground());
    }

    @Test
    void uninstallRestoresAllFiveNativePropertiesExactly() throws Exception {
        final PaletteAppearanceCoordinator coordinator = coordinator();
        register(coordinator, "WarpA", PaletteAppearanceCoordinator.Property.FONT_SIZE, 18.0F);
        register(coordinator, "WarpA", PaletteAppearanceCoordinator.Property.BOLD, true);
        register(coordinator, "WarpA", PaletteAppearanceCoordinator.Property.ITALIC, true);
        register(coordinator, "WarpA", PaletteAppearanceCoordinator.Property.TEXT_COLOR, color(0x336699));
        register(coordinator, "WarpA", PaletteAppearanceCoordinator.Property.BACKGROUND_COLOR, color(0x997755));
        NativeDeformerTreeAppearanceBridge.install(
            41, selectors(), new DeformerTreeControlAppearanceProvider(coordinator)
        );

        final Color nativeForeground = new Color(0x22, 0x22, 0x22);
        final JLabel label = new JLabel("native");
        final Color nativeBackground = new Color(0x11, 0x33, 0x55);
        final Font nativeFont = new Font("Dialog", Font.PLAIN, 11);
        label.setForeground(nativeForeground);
        label.setBackground(nativeBackground);
        label.setFont(nativeFont);
        label.setOpaque(false);
        final JPanel container = new JPanel();
        container.add(label);

        render(() -> NativeDeformerTreeAppearanceBridge.afterRender(
            container, new Row(new DeformerSource("WarpA")), false, false
        ));
        assertEquals(new Color(0x33, 0x66, 0x99), label.getForeground());
        assertEquals(new Color(0x99, 0x77, 0x55), label.getBackground());
        assertEquals("Dialog", label.getFont().getFamily());
        assertEquals(18.0F, label.getFont().getSize2D());
        assertEquals(Font.BOLD | Font.ITALIC, label.getFont().getStyle());
        assertEquals(true, label.isOpaque());

        render(NativeDeformerTreeAppearanceBridge::uninstall);
        assertEquals(nativeForeground, label.getForeground());
        assertEquals(nativeBackground, label.getBackground());
        assertEquals(nativeFont, label.getFont());
        assertEquals(false, label.isOpaque());
    }

    @Test
    void resolvesPublicAccessorDeclaredOnNonPublicRowOwner() throws Exception {
        final PaletteAppearanceCoordinator coordinator = coordinator();
        register(coordinator, "WarpA", PaletteAppearanceCoordinator.Property.TEXT_COLOR, color(0x336699));
        final Object row = HiddenRowFixture.row("WarpA");
        NativeDeformerTreeAppearanceBridge.install(
            41,
            new NativeDeformerTreeAppearanceBridge.Selectors(
                row.getClass().getName().replace('.', '/'),
                "source",
                HiddenRowFixture.PublicSource.class.getName().replace('.', '/'),
                "getId",
                "getIdString",
                row.getClass().getClassLoader()
            ),
            new DeformerTreeControlAppearanceProvider(coordinator)
        );
        final JLabel label = new JLabel("native");

        render(() -> NativeDeformerTreeAppearanceBridge.afterRender(label, row, false, false));

        assertEquals(new Color(0x33, 0x66, 0x99), label.getForeground());
    }

    private static PaletteAppearanceCoordinator coordinator() {
        final PaletteAppearanceCoordinator coordinator = new PaletteAppearanceCoordinator();
        coordinator.reconcile(scope(41));
        return coordinator;
    }

    private static void register(
        final PaletteAppearanceCoordinator coordinator,
        final String id,
        final PaletteAppearanceCoordinator.Property property,
        final Object value
    ) {
        coordinator.register(
            "dev.turboism.test", 3, scope(41), PaletteAppearanceCoordinator.Palette.DEFORMER_PART,
            id, property, value
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

    private static NativeDeformerTreeAppearanceBridge.Selectors selectors() {
        return new NativeDeformerTreeAppearanceBridge.Selectors(
            Row.class.getName().replace('.', '/'),
            "source",
            DeformerSource.class.getName().replace('.', '/'),
            "getId",
            "getIdString",
            Row.class.getClassLoader()
        );
    }

    private static void render(final Runnable action) throws Exception {
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            try {
                action.run();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    static final class Row {
        private final Object value;

        Row(final Object value) {
            this.value = value;
        }

        public Object source() {
            return value;
        }
    }

    public static final class DeformerSource {
        private final Id id;

        DeformerSource(final String id) {
            this.id = new Id(id);
        }

        public Id getId() {
            return id;
        }
    }

    public static final class DrawableSource {
        private final Id id;

        DrawableSource(final String id) {
            this.id = new Id(id);
        }

        public Id getId() {
            return id;
        }
    }

    public record Id(String value) {
        public String getIdString() {
            return value;
        }
    }

    private static final class NativeContainer extends JPanel {
        private final Color nativeForeground;

        private NativeContainer(final Color nativeForeground) {
            this.nativeForeground = nativeForeground;
        }

        @Override
        public void setForeground(final Color foreground) {
            super.setForeground(foreground);
            if (getComponentCount() > 0 && getComponent(0) instanceof JLabel label) {
                label.setForeground(foreground == null ? nativeForeground : foreground);
            }
        }
    }
}
