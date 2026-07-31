package dev.turboism.sdk.ui.appearance;

import dev.turboism.sdk.cubism.id.DeformerId;
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
            new ControlAppearanceStyle(Optional.of(new UiColor(0xFF112233)), Optional.empty(), Optional.empty())
        );
        assertEquals("PartA", new ControlAppearanceTarget.PartLabel(new PartId("PartA")).id().value());
        assertEquals("PartFolderA", new ControlAppearanceTarget.PartFolder(new PartId("PartFolderA")).id().value());
        assertEquals(0xFF112233, new UiColor(0xFF112233).argb());
        assertEquals("Noto Sans", new UiFont(
            Optional.of("  Noto Sans  "), Optional.of(12.0F), UiFont.Weight.BOLD, UiFont.Posture.NORMAL
        ).family().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> new ControlAppearanceContribution(
            "UPPERCASE",
            new ControlAppearanceTarget.DeformerLabel(new DeformerId("WarpA")),
            new ControlAppearanceStyle(Optional.of(new UiColor(0xFF112233)), Optional.empty(), Optional.empty())
        ));
        for (Class<?> type : new Class<?>[] {
            ControlAppearanceRegistry.class,
            ControlAppearanceContribution.class,
            ControlAppearanceTarget.class,
            ControlAppearanceStyle.class,
            UiColor.class,
            UiFont.class
        }) {
            for (Method method : type.getDeclaredMethods()) {
                assertAllowed(method.getReturnType());
                for (Class<?> parameter : method.getParameterTypes()) assertAllowed(parameter);
            }
        }
    }

    private static void assertAllowed(final Class<?> type) {
        final String name = type.getName();
        assertTrue(!name.startsWith("java.awt.")
            && !name.startsWith("javax.swing.")
            && !name.startsWith("com.live2d."), () -> "raw host UI type leaked: " + name);
    }
}
