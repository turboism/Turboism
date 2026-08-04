package dev.turboism.ui.appearance.control;

import dev.turboism.sdk.ui.appearance.UiColor;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class DeformerTreeForegroundTracerTest {
    @Test
    void stylesOnlyTheRegisteredDeformerAndStopsAfterRegistrationCloses() throws Exception {
        final PaletteAppearanceCoordinator coordinator = new PaletteAppearanceCoordinator();
        final PaletteAppearanceCoordinator.Scope scope = scope(11);
        coordinator.reconcile(scope);
        final var registration = coordinator.register(
            "dev.turboism.test.control-appearance", 7, scope,
            PaletteAppearanceCoordinator.Palette.DEFORMER_PART, "WarpA",
            PaletteAppearanceCoordinator.Property.TEXT_COLOR,
            new UiColor(0.2F, 0.4F, 0.6F, 1.0F)
        );
        final DeformerTreeControlAppearanceProvider provider =
            new DeformerTreeControlAppearanceProvider(coordinator);
        final JLabel reusedRenderer = new JLabel();
        final Color nativeForeground = new Color(0x22, 0x22, 0x22);

        reusedRenderer.setForeground(nativeForeground);
        assertSame(reusedRenderer, render(provider, "WarpA", reusedRenderer));
        assertEquals(new Color(0x33, 0x66, 0x99), reusedRenderer.getForeground());

        reusedRenderer.setForeground(nativeForeground);
        assertSame(reusedRenderer, render(provider, "WarpB", reusedRenderer));
        assertEquals(nativeForeground, reusedRenderer.getForeground());

        registration.close();
        reusedRenderer.setForeground(nativeForeground);
        assertSame(reusedRenderer, render(provider, "WarpA", reusedRenderer));
        assertEquals(nativeForeground, reusedRenderer.getForeground());
        provider.close();
    }

    private static PaletteAppearanceCoordinator.Scope scope(final long hostGeneration) {
        return new PaletteAppearanceCoordinator.Scope("content", 1, "model", 1, hostGeneration, 1);
    }

    private static JLabel render(
        final DeformerTreeControlAppearanceProvider provider,
        final String deformerId,
        final JLabel renderer
    ) throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() ->
            provider.apply(11, deformerId, renderer, false, false)
        );
        return renderer;
    }
}
