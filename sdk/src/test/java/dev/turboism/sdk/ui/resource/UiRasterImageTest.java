package dev.turboism.sdk.ui.resource;

import dev.turboism.sdk.ui.viewcontext.ViewContextMenuRegistry.StateButtonContribution;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UiRasterImageTest {
    @Test
    void pixelOwnershipIsIndependentOfBothCallerAndRenderer() {
        final int[] pixels = {0x80ff0000, 0xff00ff00};
        final UiRasterImage image = new UiRasterImage(2, 1, pixels);
        pixels[0] = 0;
        final int[] rendered = image.argb();
        rendered[1] = 0;
        assertArrayEquals(new int[]{0x80ff0000, 0xff00ff00}, image.argb());
    }

    @Test
    void dimensionsMustMatchThePixelCountWithoutOverflow() {
        assertThrows(IllegalArgumentException.class, () -> new UiRasterImage(0, 1, new int[0]));
        assertThrows(IllegalArgumentException.class, () -> new UiRasterImage(-1, -1, new int[1]));
        assertThrows(IllegalArgumentException.class, () -> new UiRasterImage(2, 2, new int[3]));
        assertThrows(IllegalArgumentException.class, () -> new UiRasterImage(65536, 65536, new int[0]));
    }

    @Test
    void deferredContributionKeepsItsIconsAndFallbackOrder() {
        final UiRasterImage image = new UiRasterImage(1, 1, new int[]{0xff123456});
        final LinkedHashMap<Integer, UiRasterImage> icons = new LinkedHashMap<>();
        icons.put(1, image);
        icons.put(0, image);
        final StateButtonContribution contribution =
            new StateButtonContribution("mirror", icons, 1, ignored -> { });
        icons.clear();
        assertEquals(List.of(1, 0), List.copyOf(contribution.stateIcons().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> contribution.stateIcons().clear());
    }
}
