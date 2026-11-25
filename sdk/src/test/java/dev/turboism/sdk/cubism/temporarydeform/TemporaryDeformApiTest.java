package dev.turboism.sdk.cubism.temporarydeform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.ui.appearance.UiColor;
import java.util.List;
import org.junit.jupiter.api.Test;

class TemporaryDeformApiTest {

    @Test
    void gridLayerEnforcesPositiveDimensionsAndExactPointCount() {
        final var valid = new TemporaryDeformContext.GridLayer(
                2, 2, List.of(new Point2(0, 0), new Point2(10, 0), new Point2(0, 10), new Point2(10, 10)));
        assertEquals(4, valid.points().size());
        assertThrows(IllegalArgumentException.class, () -> new TemporaryDeformContext.GridLayer(0, 2, List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TemporaryDeformContext.GridLayer(2, 2, List.of(new Point2(0, 0))));
    }

    @Test
    void choiceOptionRejectsUnknownSelectedValueAndEmptyItems() {
        final var auto = new TemporaryDeformOption.ChoiceItem("auto", "Auto");
        final var row = new TemporaryDeformOption.ChoiceItem("row", "Row");
        final var valid = new TemporaryDeformOption.Choice("axis", "Axis", List.of(auto, row), "auto");
        assertEquals("auto", valid.selectedValue());

        assertThrows(
                IllegalArgumentException.class,
                () -> new TemporaryDeformOption.Choice("axis", "Axis", List.of(), "auto"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TemporaryDeformOption.Choice("axis", "Axis", List.of(auto, row), "column"));
    }

    @Test
    void renderSceneValidatesWidthAndPointSize() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TemporaryDeformRenderScene.LineLayer(
                        List.of(List.of(new Point2(0, 0))), new UiColor(1.0F, 1.0F, 1.0F, 1.0F), 0.0F));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TemporaryDeformRenderScene.PointLayer(
                        List.of(new Point2(0, 0)),
                        0,
                        new UiColor(1.0F, 1.0F, 1.0F, 1.0F),
                        new UiColor(0.0F, 0.0F, 0.0F, 1.0F)));
    }

    @Test
    void unavailableSentinelFailsClosedOnRegisterAndActivate() {
        final TemporaryDeformToolRegistry sentinel = TemporaryDeformToolRegistry.unavailable();
        assertFalse(sentinel.isAvailable());
        assertTrue(sentinel.activeToolId().isEmpty());
        final TemporaryDeformTool dummyTool = new TemporaryDeformTool() {
            @Override
            public String id() {
                return "dummy";
            }

            @Override
            public String label() {
                return "Dummy";
            }

            @Override
            public TemporaryDeformSession createSession(final TemporaryDeformContext context) {
                throw new UnsupportedOperationException();
            }
        };
        assertThrows(UnsupportedOperationException.class, () -> sentinel.register(dummyTool));
        assertThrows(UnsupportedOperationException.class, () -> sentinel.activate("dummy"));
    }
}
