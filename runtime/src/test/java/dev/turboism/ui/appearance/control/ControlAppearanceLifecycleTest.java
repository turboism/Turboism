package dev.turboism.ui.appearance.control;

import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.ui.appearance.ControlAppearanceContribution;
import dev.turboism.sdk.ui.appearance.ControlAppearanceStyle;
import dev.turboism.sdk.ui.appearance.ControlAppearanceTarget;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import java.awt.Color;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ControlAppearanceLifecycleTest {

    @Test
    void staleHostGenerationFailsClosedBeforeTouchingTheRenderer() throws Exception {
        final ControlAppearanceCoordinator coordinator = new ControlAppearanceCoordinator();
        coordinator.replaceHostGeneration(4);
        final RuntimeControlAppearanceRegistry registry = new RuntimeControlAppearanceRegistry(
            "plugin-a", 1, (permission, operation) -> { }, coordinator, TestNativeControlAppearanceAuthoring.unavailable()
        );
        registry.register(new ControlAppearanceContribution(
            "deformer.foreground",
            new ControlAppearanceTarget.DeformerLabel(new DeformerId("WarpA")),
            new ControlAppearanceStyle(Optional.of(new dev.turboism.sdk.cubism.model.Color(1.000000F, 0.000000F, 0.000000F, 1.000000F)), Optional.empty(), Optional.empty())
        ));
        final DeformerTreeControlAppearanceProvider provider =
            new DeformerTreeControlAppearanceProvider(coordinator);
        final JLabel renderer = new JLabel();
        final Color nativeColor = Color.BLACK;
        renderer.setForeground(nativeColor);

        render(provider, 3, renderer);
        assertEquals(nativeColor, renderer.getForeground());

        render(provider, 4, renderer);
        assertEquals(Color.RED, renderer.getForeground());

        coordinator.clearHostGeneration();
        renderer.setForeground(nativeColor);
        render(provider, 4, renderer);
        assertEquals(nativeColor, renderer.getForeground());
    }

    @Test
    void onePluginCannotRemoveAnotherPluginsReplacementWithTheSameContributionId() {
        final ControlAppearanceCoordinator coordinator = new ControlAppearanceCoordinator();
        final RuntimeControlAppearanceRegistry pluginA = new RuntimeControlAppearanceRegistry(
            "plugin-a", 1, (permission, operation) -> { }, coordinator, TestNativeControlAppearanceAuthoring.unavailable()
        );
        final RuntimeControlAppearanceRegistry pluginB = new RuntimeControlAppearanceRegistry(
            "plugin-b", 1, (permission, operation) -> { }, coordinator, TestNativeControlAppearanceAuthoring.unavailable()
        );
        final var contributionA = contribution(0xFFFF0000);
        final var contributionB = contribution(0xFF0000FF);
        final var registrationA = pluginA.register(contributionA);
        pluginB.register(contributionB);

        registrationA.close();

        assertEquals(new dev.turboism.sdk.cubism.model.Color(0.0F, 0.0F, 1.0F, 1.0F), coordinator.deformerLabel("WarpA")
            .orElseThrow().foreground().orElseThrow());
    }

    private static ControlAppearanceContribution contribution(final int argb) {
        return new ControlAppearanceContribution(
            "deformer.foreground",
            new ControlAppearanceTarget.DeformerLabel(new DeformerId("WarpA")),
            new ControlAppearanceStyle(
                Optional.of(new dev.turboism.sdk.cubism.model.Color(
                    ((argb >> 16) & 0xFF) / 255.0F,
                    ((argb >> 8) & 0xFF) / 255.0F,
                    (argb & 0xFF) / 255.0F,
                    ((argb >> 24) & 0xFF) / 255.0F
                )), Optional.empty(), Optional.empty())
        );
    }

    private static void render(
        final DeformerTreeControlAppearanceProvider provider,
        final long generation,
        final JLabel renderer
    ) throws Exception {
        final AtomicReference<java.awt.Component> result = new AtomicReference<>();
        javax.swing.SwingUtilities.invokeAndWait(() ->
            result.set(provider.apply(generation, "WarpA", renderer, false, false))
        );
    }
}
