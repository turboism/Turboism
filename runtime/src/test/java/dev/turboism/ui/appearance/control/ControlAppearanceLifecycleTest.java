package dev.turboism.ui.appearance.control;

import dev.turboism.sdk.ui.appearance.UiColor;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ControlAppearanceLifecycleTest {
    @Test
    void staleHostGenerationFailsClosedBeforeTouchingTheRenderer() throws Exception {
        final PaletteAppearanceCoordinator coordinator = new PaletteAppearanceCoordinator();
        final PaletteAppearanceCoordinator.Scope scope = scope(4);
        coordinator.reconcile(scope);
        coordinator.register(
            "plugin", 1, scope, PaletteAppearanceCoordinator.Palette.DEFORMER_PART, "WarpA",
            PaletteAppearanceCoordinator.Property.TEXT_COLOR, new UiColor(1.0F, 0.0F, 0.0F, 1.0F)
        );
        final DeformerTreeControlAppearanceProvider provider =
            new DeformerTreeControlAppearanceProvider(coordinator);
        final JLabel renderer = new JLabel();
        final Color nativeColor = Color.BLACK;
        renderer.setForeground(nativeColor);

        render(provider, 3, renderer);
        assertEquals(nativeColor, renderer.getForeground());

        render(provider, 4, renderer);
        assertEquals(Color.RED, renderer.getForeground());

        coordinator.invalidate();
        renderer.setForeground(nativeColor);
        render(provider, 4, renderer);
        assertEquals(nativeColor, renderer.getForeground());
        provider.close();
    }

    private static PaletteAppearanceCoordinator.Scope scope(final long hostGeneration) {
        return new PaletteAppearanceCoordinator.Scope("content", 1, "model", 1, hostGeneration, 1);
    }

    private static void render(
        final DeformerTreeControlAppearanceProvider provider,
        final long generation,
        final JLabel renderer
    ) throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() ->
            provider.apply(generation, "WarpA", renderer, false, false)
        );
    }
}
