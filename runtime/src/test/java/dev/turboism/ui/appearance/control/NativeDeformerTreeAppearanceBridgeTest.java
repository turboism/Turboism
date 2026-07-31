package dev.turboism.ui.appearance.control;

import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.ui.appearance.ControlAppearanceContribution;
import dev.turboism.sdk.ui.appearance.ControlAppearanceStyle;
import dev.turboism.sdk.ui.appearance.ControlAppearanceTarget;
import dev.turboism.sdk.ui.appearance.UiColor;
import dev.turboism.ui.appearance.control.fixture.HiddenRowFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Component;
import java.util.Optional;
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
        final ControlAppearanceCoordinator coordinator = new ControlAppearanceCoordinator();
        coordinator.replaceHostGeneration(41);
        coordinator.put(
            "dev.turboism.test",
            3,
            new ControlAppearanceContribution(
                "warp.foreground",
                new ControlAppearanceTarget.DeformerLabel(new DeformerId("WarpA")),
                new ControlAppearanceStyle(Optional.of(new UiColor(0xFF336699)), Optional.empty(), Optional.empty())
            )
        );
        final DeformerTreeControlAppearanceProvider provider =
            new DeformerTreeControlAppearanceProvider(coordinator);
        final NativeDeformerTreeAppearanceBridge.Selectors selectors = selectors();
        NativeDeformerTreeAppearanceBridge.install(41, selectors, provider);

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
            container,
            new Row(new DeformerSource("WarpB")),
            false,
            false
        ));
        assertEquals(nativeForeground, reused.getForeground());

        render(() -> NativeDeformerTreeAppearanceBridge.afterRender(
            container,
            new Row(new DrawableSource("WarpA")),
            false,
            false
        ));
        assertEquals(nativeForeground, reused.getForeground());
    }


    @Test
    void selectedAndFocusedRowsKeepForegroundOverlayAndNativeBackground() throws Exception {
        final ControlAppearanceCoordinator coordinator = new ControlAppearanceCoordinator();
        coordinator.replaceHostGeneration(41);
        coordinator.put(
            "dev.turboism.test",
            3,
            new ControlAppearanceContribution(
                "warp.foreground",
                new ControlAppearanceTarget.DeformerLabel(new DeformerId("WarpA")),
                new ControlAppearanceStyle(Optional.of(new UiColor(0xFF336699)), Optional.empty(), Optional.empty())
            )
        );
        NativeDeformerTreeAppearanceBridge.install(
            41,
            selectors(),
            new DeformerTreeControlAppearanceProvider(coordinator)
        );
        final JLabel label = new JLabel("native");
        final Color selectedBackground = new Color(0x44, 0x55, 0x66);
        label.setBackground(selectedBackground);

        render(() -> NativeDeformerTreeAppearanceBridge.afterRender(
            label,
            new Row(new DeformerSource("WarpA")),
            true,
            true
        ));

        assertEquals(new Color(0x33, 0x66, 0x99), label.getForeground());
        assertEquals(selectedBackground, label.getBackground());
    }

    @Test
    void uninstallRestoresCurrentlyOverlaidLabels() throws Exception {
        final ControlAppearanceCoordinator coordinator = new ControlAppearanceCoordinator();
        coordinator.replaceHostGeneration(41);
        coordinator.put(
            "dev.turboism.test",
            3,
            new ControlAppearanceContribution(
                "warp.style",
                new ControlAppearanceTarget.DeformerLabel(new DeformerId("WarpA")),
                new ControlAppearanceStyle(
                    Optional.of(new UiColor(0xFF336699)),
                    Optional.of(new UiColor(0xFF997755)),
                    Optional.of(new dev.turboism.sdk.ui.appearance.UiFont(
                        Optional.of("Monospaced"),
                        Optional.of(18.0F),
                        dev.turboism.sdk.ui.appearance.UiFont.Weight.BOLD,
                        dev.turboism.sdk.ui.appearance.UiFont.Posture.ITALIC
                    ))
                )
            )
        );
        NativeDeformerTreeAppearanceBridge.install(
            41,
            selectors(),
            new DeformerTreeControlAppearanceProvider(coordinator)
        );
        final Color nativeForeground = new Color(0x22, 0x22, 0x22);
        final JLabel label = new JLabel("native");
        label.setForeground(nativeForeground);
        final Color nativeBackground = new Color(0x11, 0x33, 0x55);
        final java.awt.Font nativeFont = new java.awt.Font("Dialog", java.awt.Font.PLAIN, 11);
        label.setBackground(nativeBackground);
        label.setFont(nativeFont);
        label.setOpaque(false);
        final JPanel container = new JPanel();
        container.add(label);

        render(() -> NativeDeformerTreeAppearanceBridge.afterRender(
            container,
            new Row(new DeformerSource("WarpA")),
            false,
            false
        ));
        assertEquals(new Color(0x33, 0x66, 0x99), label.getForeground());
        assertEquals(new Color(0x99, 0x77, 0x55), label.getBackground());
        assertEquals("Monospaced", label.getFont().getFamily());
        assertEquals(18.0F, label.getFont().getSize2D());
        assertEquals(java.awt.Font.BOLD | java.awt.Font.ITALIC, label.getFont().getStyle());
        assertEquals(true, label.isOpaque());

        render(NativeDeformerTreeAppearanceBridge::uninstall);
        assertEquals(nativeForeground, label.getForeground());
        assertEquals(nativeBackground, label.getBackground());
        assertEquals(nativeFont, label.getFont());
        assertEquals(false, label.isOpaque());
    }

    @Test
    void resolvesPublicAccessorDeclaredOnNonPublicRowOwner() throws Exception {
        final ControlAppearanceCoordinator coordinator = new ControlAppearanceCoordinator();
        coordinator.replaceHostGeneration(41);
        coordinator.put(
            "dev.turboism.test",
            3,
            new ControlAppearanceContribution(
                "hidden-row.foreground",
                new ControlAppearanceTarget.DeformerLabel(new DeformerId("WarpA")),
                new ControlAppearanceStyle(Optional.of(new UiColor(0xFF336699)), Optional.empty(), Optional.empty())
            )
        );
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
