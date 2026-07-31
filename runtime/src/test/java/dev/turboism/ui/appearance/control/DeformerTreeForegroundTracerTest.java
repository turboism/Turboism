package dev.turboism.ui.appearance.control;

import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.appearance.ControlAppearanceContribution;
import dev.turboism.sdk.ui.appearance.ControlAppearanceStyle;
import dev.turboism.sdk.ui.appearance.ControlAppearanceTarget;
import dev.turboism.sdk.ui.appearance.UiColor;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import java.awt.Color;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class DeformerTreeForegroundTracerTest {

    @Test
    void stylesOnlyTheRegisteredDeformerAndStopsAfterRegistrationCloses() throws Exception {
        final ControlAppearanceCoordinator coordinator = new ControlAppearanceCoordinator();
        coordinator.replaceHostGeneration(11);
        final RuntimeControlAppearanceRegistry registry = new RuntimeControlAppearanceRegistry(
            "dev.turboism.test.control-appearance",
            7,
            (permissionId, operation) -> assertEquals(
                PermissionIds.TURBOISM_UI_APPEARANCE_MODIFY,
                permissionId
            ),
            coordinator
        );
        final DeformerTreeControlAppearanceProvider provider =
            new DeformerTreeControlAppearanceProvider(coordinator);
        final Registration registration = registry.register(new ControlAppearanceContribution(
            "deformer.foreground",
            new ControlAppearanceTarget.DeformerLabel(new DeformerId("WarpA")),
            new ControlAppearanceStyle(
                Optional.of(new UiColor(0xFF336699)),
                Optional.empty(),
                Optional.empty()
            )
        ));
        final JLabel reusedRenderer = new JLabel();
        final Color nativeForeground = new Color(0x22, 0x22, 0x22);

        reusedRenderer.setForeground(nativeForeground);
        assertSame(
            reusedRenderer,
            render(provider, "WarpA", reusedRenderer)
        );
        assertEquals(new Color(0x33, 0x66, 0x99), reusedRenderer.getForeground());

        reusedRenderer.setForeground(nativeForeground); // native delegate renders another row
        assertSame(
            reusedRenderer,
            render(provider, "WarpB", reusedRenderer)
        );
        assertEquals(nativeForeground, reusedRenderer.getForeground());

        registration.close();
        reusedRenderer.setForeground(nativeForeground); // native delegate renders the former target again
        assertSame(
            reusedRenderer,
            render(provider, "WarpA", reusedRenderer)
        );
        assertEquals(nativeForeground, reusedRenderer.getForeground());
    }

    private static JLabel render(
        final DeformerTreeControlAppearanceProvider provider,
        final String deformerId,
        final JLabel renderer
    ) throws Exception {
        final java.util.concurrent.atomic.AtomicReference<java.awt.Component> result =
            new java.util.concurrent.atomic.AtomicReference<>();
        javax.swing.SwingUtilities.invokeAndWait(() ->
            result.set(provider.apply(11, deformerId, renderer, false, false))
        );
        return (JLabel) result.get();
    }
}
