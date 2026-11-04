package dev.turboism.sdk.ui.resource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.turboism.sdk.ui.viewcontext.ViewContextMenuRegistry;
import dev.turboism.sdk.ui.viewcontext.ViewContextMenuRegistry.StateButtonContribution;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class UiRasterImageTest {
    @Test
    void pixelOwnershipIsIndependentOfBothCallerAndRenderer() {
        final int[] pixels = {0x80ff0000, 0xff00ff00};
        final UiRasterImage image = new UiRasterImage(2, 1, pixels);
        pixels[0] = 0;
        final int[] rendered = image.argb();
        rendered[1] = 0;
        assertArrayEquals(new int[] {0x80ff0000, 0xff00ff00}, image.argb());
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
        final UiRasterImage image = new UiRasterImage(1, 1, new int[] {0xff123456});
        final LinkedHashMap<Integer, UiRasterImage> icons = new LinkedHashMap<>();
        icons.put(1, image);
        icons.put(0, image);
        final StateButtonContribution contribution = new StateButtonContribution("mirror", icons, 1, ignored -> {});
        icons.clear();
        assertEquals(List.of(1, 0), List.copyOf(contribution.stateIcons().keySet()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> contribution.stateIcons().clear());
    }

    @Test
    void orderedRegistrationFailsClosedWhenTheHostDoesNotSupportOrdering() {
        final AtomicInteger registrations = new AtomicInteger();
        final ViewContextMenuRegistry olderHost = new ViewContextMenuRegistry() {
            @Override
            public dev.turboism.sdk.plugin.Registration contributeStateButtons(
                    final StateButtonContribution contribution) {
                registrations.incrementAndGet();
                return () -> {};
            }

            @Override
            public void updateButtonState(final String contributionId, final int axis) {}
        };
        final StateButtonContribution contribution = new StateButtonContribution(
                "mirror", java.util.Map.of(0, new UiRasterImage(1, 1, new int[] {0xff123456})), 0, ignored -> {});

        olderHost.contributeStateButtons(contribution, 0).close();
        assertEquals(1, registrations.get());
        assertThrows(UnsupportedOperationException.class, () -> olderHost.contributeStateButtons(contribution, 7));
        assertEquals(1, registrations.get());
    }
}
