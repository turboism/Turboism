package dev.turboism.ui.appearance.control;

import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.ui.appearance.ControlAppearanceContribution;
import dev.turboism.sdk.ui.appearance.ControlAppearanceStyle;
import dev.turboism.sdk.ui.appearance.ControlAppearanceTarget;
import dev.turboism.sdk.ui.appearance.UiFont;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlAppearanceBatchContractTest {

    @Test
    void resolvesAllSixTargetsIndependently() {
        final ControlAppearanceCoordinator coordinator = new ControlAppearanceCoordinator();
        coordinator.put("plugin", 1, contribution("parameter", new ControlAppearanceTarget.ParameterLabel(new ParameterId("ParamA"))));
        coordinator.put("plugin", 1, contribution("parameter-folder", new ControlAppearanceTarget.ParameterFolder(new ParameterGroupId("GroupA"))));
        coordinator.put("plugin", 1, contribution("deformer", new ControlAppearanceTarget.DeformerLabel(new DeformerId("WarpA"))));
        coordinator.put("plugin", 1, contribution("deformer-row", new ControlAppearanceTarget.DeformerControlRow(new DeformerId("WarpA"))));
        coordinator.put("plugin", 1, contribution("part", new ControlAppearanceTarget.PartLabel(new PartId("PartA"))));
        coordinator.put("plugin", 1, contribution("part-folder", new ControlAppearanceTarget.PartFolder(new PartId("PartFolderA"))));

        assertTrue(coordinator.parameterLabel("ParamA").isPresent());
        assertTrue(coordinator.parameterFolder("GroupA").isPresent());
        assertTrue(coordinator.deformerLabel("WarpA").isPresent());
        assertTrue(coordinator.deformerControlRow("WarpA").isPresent());
        assertTrue(coordinator.partLabel("PartA").isPresent());
        assertTrue(coordinator.partFolder("PartFolderA").isPresent());
    }

    @Test
    void appliesForegroundBackgroundAndDerivedFontWithoutDroppingNativeFields() {
        final Font nativeFont = new Font("Dialog", Font.ITALIC, 11);
        final ControlAppearanceStyle style = new ControlAppearanceStyle(
            Optional.of(new dev.turboism.sdk.cubism.model.Color(0.070588F, 0.203922F, 0.337255F, 1.000000F)),
            Optional.of(new dev.turboism.sdk.cubism.model.Color(0.396078F, 0.262745F, 0.129412F, 1.000000F)),
            Optional.of(new UiFont(
                Optional.of("Monospaced"),
                Optional.of(18.0F),
                UiFont.Weight.BOLD,
                UiFont.Posture.NORMAL
            ))
        );

        final NativeControlStyle result = NativeControlStyle.apply(nativeFont, Color.BLACK, Color.WHITE, false, style);

        assertEquals(new Color(0x12, 0x34, 0x56), result.foreground());
        assertEquals(new Color(0x65, 0x43, 0x21), result.background());
        assertEquals("Monospaced", result.font().getFamily());
        assertEquals(18.0F, result.font().getSize2D());
        assertEquals(Font.BOLD, result.font().getStyle());
        assertTrue(result.opaque());
    }

    private static ControlAppearanceContribution contribution(
        final String id,
        final ControlAppearanceTarget target
    ) {
        return new ControlAppearanceContribution(
            id,
            target,
            new ControlAppearanceStyle(Optional.of(new dev.turboism.sdk.cubism.model.Color(0.066667F, 0.133333F, 0.200000F, 1.000000F)), Optional.empty(), Optional.empty())
        );
    }
}
