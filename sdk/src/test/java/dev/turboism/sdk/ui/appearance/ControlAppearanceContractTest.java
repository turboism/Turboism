package dev.turboism.sdk.ui.appearance;

import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.plugin.PluginContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlAppearanceContractTest {

    @Test
    void valuesAreBoundedAndExposeNoRawHostTypes() throws Exception {
        assertEquals(ControlAppearanceRegistry.class,
            PluginContext.class.getMethod("controlAppearance").getReturnType());
        assertThrows(IllegalArgumentException.class, () -> new ControlAppearanceStyle(
            Optional.empty(), Optional.empty(), Optional.empty()
        ));
        assertThrows(IllegalArgumentException.class, () -> new UiFont(
            Optional.of("\u0000"), Optional.empty(), UiFont.Weight.INHERIT, UiFont.Posture.INHERIT
        ));
        assertThrows(IllegalArgumentException.class, () -> new UiFont(
            Optional.empty(), Optional.of(5.0F), UiFont.Weight.INHERIT, UiFont.Posture.INHERIT
        ));

        new ControlAppearanceContribution(
            "deformer.foreground",
            new ControlAppearanceTarget.DeformerLabel(new DeformerId("WarpA")),
            new ControlAppearanceStyle(
                Optional.of(new Color(1.0F, 0.25F, 0.5F, 0.75F)), Optional.empty(), Optional.empty()
            )
        );
        assertEquals("PartA", new ControlAppearanceTarget.PartLabel(new PartId("PartA")).id().value());
        assertEquals("PartFolderA", new ControlAppearanceTarget.PartFolder(new PartId("PartFolderA")).id().value());
        assertEquals("Noto Sans", new UiFont(
            Optional.of("  Noto Sans  "), Optional.of(12.0F), UiFont.Weight.BOLD, UiFont.Posture.NORMAL
        ).family().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> new ControlAppearanceContribution(
            "UPPERCASE",
            new ControlAppearanceTarget.DeformerLabel(new DeformerId("WarpA")),
            new ControlAppearanceStyle(
                Optional.of(new Color(1.0F, 0.0F, 0.0F, 1.0F)), Optional.empty(), Optional.empty()
            )
        ));
        for (Class<?> type : new Class<?>[] {
            ControlAppearanceRegistry.class,
            ControlAppearanceContribution.class,
            ControlAppearanceTarget.class,
            ControlAppearanceStyle.class,
            ControlAppearanceSnapshot.class,
            NativeControlAppearance.class,
            NativeControlBackground.class,
            PresetColor.class,
            UiFont.class
        }) {
            for (Method method : type.getDeclaredMethods()) {
                assertAllowed(method.getReturnType());
                for (Class<?> parameter : method.getParameterTypes()) assertAllowed(parameter);
            }
        }
    }

    @Test
    void styleColorsMustBeFiniteAndInUnitRange() {
        assertThrows(IllegalArgumentException.class, () -> new ControlAppearanceStyle(
            Optional.of(new Color(Float.NaN, 0.0F, 0.0F, 1.0F)), Optional.empty(), Optional.empty()
        ));
        assertThrows(IllegalArgumentException.class, () -> new ControlAppearanceStyle(
            Optional.empty(), Optional.of(new Color(1.5F, 0.0F, 0.0F, 1.0F)), Optional.empty()
        ));
        assertThrows(NullPointerException.class, () -> new ControlAppearanceStyle(
            null, Optional.empty(), Optional.empty()
        ));
    }

    @Test
    void nativeControlBackgroundValuesAreBounded() {
        assertEquals(new NativeControlBackground.Default(), new NativeControlBackground.Default());
        assertEquals(
            new NativeControlBackground.Preset(PresetColor.GRAY),
            new NativeControlBackground.Preset(PresetColor.GRAY)
        );
        assertEquals(
            new NativeControlBackground.Custom(new Color(0.1F, 0.2F, 0.3F, 0.4F)),
            new NativeControlBackground.Custom(new Color(0.1F, 0.2F, 0.3F, 0.4F))
        );
        assertEquals(
            java.util.List.of(
                PresetColor.RED, PresetColor.ORANGE, PresetColor.YELLOW, PresetColor.GREEN,
                PresetColor.BLUE, PresetColor.PURPLE, PresetColor.GRAY
            ),
            java.util.List.of(PresetColor.values())
        );
        assertThrows(NullPointerException.class, () -> new NativeControlBackground.Preset(null));
        assertThrows(NullPointerException.class, () -> new NativeControlBackground.Custom(null));
        assertThrows(IllegalArgumentException.class, () -> new NativeControlBackground.Custom(
            new Color(2.0F, 0.0F, 0.0F, 1.0F)
        ));
        assertThrows(IllegalArgumentException.class, () -> new NativeControlBackground.Custom(
            new Color(0.0F, Float.NEGATIVE_INFINITY, 0.0F, 1.0F)
        ));
        assertThrows(NullPointerException.class, () -> new NativeControlAppearance(
            new NativeControlBackground.Default(), null
        ));
        assertThrows(NullPointerException.class, () -> new ControlAppearanceSnapshot(
            null, Optional.empty()
        ));
    }

    @Test
    void unavailableRegistryFailsClosedForEveryOperation() {
        final ControlAppearanceRegistry registry = ControlAppearanceRegistry.unavailable();
        final ControlAppearanceTarget target =
            new ControlAppearanceTarget.PartLabel(new PartId("PartA"));
        assertThrows(UnsupportedOperationException.class, () -> registry.register(
            new ControlAppearanceContribution(
                "part.foreground", target,
                new ControlAppearanceStyle(
                    Optional.of(new Color(1.0F, 0.0F, 0.0F, 1.0F)), Optional.empty(), Optional.empty()
                )
            )
        ));
        assertThrows(UnsupportedOperationException.class, () -> registry.snapshot(target));
        assertThrows(UnsupportedOperationException.class, () -> registry.setNativeBackground(
            target, new NativeControlBackground.Default()
        ));
    }

    private static void assertAllowed(final Class<?> type) {
        final String name = type.getName();
        assertTrue(!name.startsWith("java.awt.")
            && !name.startsWith("javax.swing.")
            && !name.startsWith("com.live2d."), () -> "raw host UI type leaked: " + name);
    }
}
