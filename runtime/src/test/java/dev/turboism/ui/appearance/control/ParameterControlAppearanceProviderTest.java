package dev.turboism.ui.appearance.control;

import dev.turboism.sdk.ui.appearance.UiColor;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParameterControlAppearanceProviderTest {
    @Test
    void registrationCloseImmediatelyRestoresBoundLongLivedLabel() throws Exception {
        final PaletteAppearanceCoordinator coordinator = new PaletteAppearanceCoordinator();
        final PaletteAppearanceCoordinator.Scope scope = scope(5);
        coordinator.reconcile(scope);
        final var registration = coordinator.register(
            "plugin", 1, scope, PaletteAppearanceCoordinator.Palette.PARAMETER, "ParamA",
            PaletteAppearanceCoordinator.Property.TEXT_COLOR,
            new UiColor(0x12 / 255.0F, 0x34 / 255.0F, 0x56 / 255.0F, 1.0F)
        );
        final ParameterControlAppearanceProvider provider =
            new ParameterControlAppearanceProvider(5, coordinator);
        final JLabel label = new JLabel();
        label.setForeground(Color.BLACK);
        javax.swing.SwingUtilities.invokeAndWait(() ->
            provider.bind(ParameterControlAppearanceProvider.Kind.PARAMETER, "ParamA", label));
        assertEquals(new Color(0x12, 0x34, 0x56), label.getForeground());

        registration.close();
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        assertEquals(Color.BLACK, label.getForeground());
        provider.close();
    }

    @Test
    void parameterAndFolderKindsResolveSeparatePalettesForTheSameId() throws Exception {
        final PaletteAppearanceCoordinator coordinator = new PaletteAppearanceCoordinator();
        final PaletteAppearanceCoordinator.Scope scope = scope(5);
        coordinator.reconcile(scope);
        coordinator.register(
            "plugin", 1, scope, PaletteAppearanceCoordinator.Palette.PARAMETER, "Shared",
            PaletteAppearanceCoordinator.Property.TEXT_COLOR, new UiColor(1.0F, 0.0F, 0.0F, 1.0F)
        );
        coordinator.register(
            "plugin", 1, scope, PaletteAppearanceCoordinator.Palette.PARAMETER_GROUP, "Shared",
            PaletteAppearanceCoordinator.Property.TEXT_COLOR, new UiColor(0.0F, 0.0F, 1.0F, 1.0F)
        );
        final ParameterControlAppearanceProvider provider =
            new ParameterControlAppearanceProvider(5, coordinator);
        final JLabel parameter = new JLabel();
        final JLabel folder = new JLabel();
        parameter.setForeground(Color.BLACK);
        folder.setForeground(Color.BLACK);

        javax.swing.SwingUtilities.invokeAndWait(() -> {
            provider.bind(ParameterControlAppearanceProvider.Kind.PARAMETER, "Shared", parameter);
            provider.bind(ParameterControlAppearanceProvider.Kind.FOLDER, "Shared", folder);
        });

        assertEquals(Color.RED, parameter.getForeground());
        assertEquals(Color.BLUE, folder.getForeground());
        javax.swing.SwingUtilities.invokeAndWait(provider::close);
    }

    @Test
    void publishesExactParameterIdAndLabelToTheSharedRowCatalog() throws Exception {
        final PaletteAppearanceCoordinator coordinator = new PaletteAppearanceCoordinator();
        coordinator.replaceHostGeneration(5);
        final ParameterControlAppearanceProvider provider =
            new ParameterControlAppearanceProvider(5, coordinator);
        final JLabel label = new JLabel("Angle X");

        javax.swing.SwingUtilities.invokeAndWait(() ->
            provider.bind(ParameterControlAppearanceProvider.Kind.PARAMETER, "ParamAngleX", label));

        final PaletteAppearanceCoordinator.ParameterControlBinding binding =
            coordinator.parameterControlBindings().get(0);
        assertEquals(false, binding.folder());
        assertEquals("ParamAngleX", binding.id());
        assertEquals(label, binding.label());

        provider.close();
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        assertEquals(0, coordinator.parameterControlBindings().size());
    }

    private static PaletteAppearanceCoordinator.Scope scope(final long hostGeneration) {
        return new PaletteAppearanceCoordinator.Scope("content", 1, "model", 1, hostGeneration, 1);
    }
}
