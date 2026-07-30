package dev.turboism.sdk.ui;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BoundingBoxOverlayButtonContractTest {

    @Test
    void descriptorCarriesOwnedIconsTooltipOrderAndClickCallback() {
        final AtomicInteger clicks = new AtomicInteger();
        final BoundingBoxOverlayButton button = new BoundingBoxOverlayButton(
            "fit-selection",
            "Fit selection",
            new BoundingBoxOverlayButton.IconVariants(
                "icons/fit.png",
                Optional.of("icons/fit-hover.png"),
                Optional.empty(),
                Optional.empty()
            ),
            30,
            clicks::incrementAndGet
        );

        button.onClick().run();

        assertEquals("fit-selection", button.id());
        assertEquals("Fit selection", button.tooltip());
        assertEquals("icons/fit.png", button.icons().normal());
        assertEquals(Optional.of("icons/fit-hover.png"), button.icons().hover());
        assertEquals(30, button.order());
        assertEquals(1, clicks.get());
    }

    @Test
    void descriptorRejectsBlankIdsAndUnsafeResourcePaths() {
        assertThrows(IllegalArgumentException.class, () -> new BoundingBoxOverlayButton(
            " ",
            "Tooltip",
            BoundingBoxOverlayButton.IconVariants.normal("icons/fit.png"),
            0,
            () -> { }
        ));
        assertThrows(IllegalArgumentException.class, () -> BoundingBoxOverlayButton.IconVariants.normal(
            "../outside.png"
        ));
    }
}
