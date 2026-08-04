package dev.turboism.ui.appearance.control;

import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.ui.appearance.ControlAppearanceContribution;
import dev.turboism.sdk.ui.appearance.ControlAppearanceStyle;
import dev.turboism.sdk.ui.appearance.ControlAppearanceTarget;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import java.awt.Color;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParameterControlAppearanceProviderTest {
    @Test
    void contributionCloseImmediatelyRestoresBoundLongLivedLabel() throws Exception {
        final ControlAppearanceCoordinator coordinator = new ControlAppearanceCoordinator();
        coordinator.replaceHostGeneration(5);
        final RuntimeControlAppearanceRegistry registry = new RuntimeControlAppearanceRegistry(
            "plugin", 1, (permission, operation) -> { }, coordinator, TestNativeControlAppearanceAuthoring.unavailable()
        );
        final var registration = registry.register(new ControlAppearanceContribution(
            "parameter", new ControlAppearanceTarget.ParameterLabel(new ParameterId("ParamA")),
            new ControlAppearanceStyle(Optional.of(new dev.turboism.sdk.cubism.model.Color(0.070588F, 0.203922F, 0.337255F, 1.000000F)), Optional.empty(), Optional.empty())
        ));
        final ParameterControlAppearanceProvider provider = new ParameterControlAppearanceProvider(5, coordinator);
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
    void publishesExactParameterIdAndLabelToTheSharedRowCatalog() throws Exception {
        final ControlAppearanceCoordinator coordinator = new ControlAppearanceCoordinator();
        coordinator.replaceHostGeneration(5);
        final ParameterControlAppearanceProvider provider = new ParameterControlAppearanceProvider(5, coordinator);
        final JLabel label = new JLabel("Angle X");

        javax.swing.SwingUtilities.invokeAndWait(() ->
            provider.bind(ParameterControlAppearanceProvider.Kind.PARAMETER, "ParamAngleX", label));

        final ControlAppearanceCoordinator.ParameterControlBinding binding =
            coordinator.parameterControlBindings().get(0);
        assertEquals(false, binding.folder());
        assertEquals("ParamAngleX", binding.id());
        assertEquals(label, binding.label());

        provider.close();
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        assertEquals(0, coordinator.parameterControlBindings().size());
    }
}
