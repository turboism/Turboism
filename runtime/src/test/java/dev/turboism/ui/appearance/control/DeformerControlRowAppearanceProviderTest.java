package dev.turboism.ui.appearance.control;

import dev.turboism.sdk.ui.appearance.UiColor;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeformerControlRowAppearanceProviderTest {
    @Test
    void controlRowUsesDeformerPaletteSeparateFromDeformerTreePalette() throws Exception {
        final PaletteAppearanceCoordinator coordinator = new PaletteAppearanceCoordinator();
        final PaletteAppearanceCoordinator.Scope scope =
            new PaletteAppearanceCoordinator.Scope("content", 1, "model", 1, 9, 1);
        coordinator.reconcile(scope);
        coordinator.register(
            "plugin", 1, scope, PaletteAppearanceCoordinator.Palette.DEFORMER_PART, "WarpA",
            PaletteAppearanceCoordinator.Property.TEXT_COLOR, new UiColor(0.1F, 0.2F, 0.3F, 1.0F)
        );
        coordinator.register(
            "plugin", 1, scope, PaletteAppearanceCoordinator.Palette.DEFORMER, "WarpA",
            PaletteAppearanceCoordinator.Property.TEXT_COLOR, new UiColor(0x77 / 255.0F, 0x88 / 255.0F, 0x99 / 255.0F, 1.0F)
        );
        final DeformerControlRowAppearanceProvider provider =
            new DeformerControlRowAppearanceProvider(coordinator);
        final JLabel label = new JLabel();
        label.setForeground(Color.BLACK);

        javax.swing.SwingUtilities.invokeAndWait(() -> provider.apply(9, "WarpA", label));

        assertEquals(new Color(0x77, 0x88, 0x99), label.getForeground());
        javax.swing.SwingUtilities.invokeAndWait(provider::close);
        assertEquals(Color.BLACK, label.getForeground());
    }
}
