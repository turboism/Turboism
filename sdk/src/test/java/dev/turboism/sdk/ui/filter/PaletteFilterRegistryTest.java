package dev.turboism.sdk.ui.filter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Contract test for the palette filter registry and its descriptor validation. */
class PaletteFilterRegistryTest {

    @Test
    void exposesTheFourPortablePaletteTabIdentifiers() {
        assertEquals("PARAMETER", PaletteFilterRegistry.PALETTE_PARAMETER);
        assertEquals("DEFORMER", PaletteFilterRegistry.PALETTE_DEFORMER);
        assertEquals("SCENE", PaletteFilterRegistry.PALETTE_SCENE);
        assertEquals("LOG", PaletteFilterRegistry.PALETTE_LOG);
    }

    @Test
    void contributionRetainsAllDescriptorFields() {
        final PaletteFilterRegistry.PaletteFilterContribution contribution =
            new PaletteFilterRegistry.PaletteFilterContribution(
                "tab-filter.parameter",
                PaletteFilterRegistry.PALETTE_PARAMETER,
                "tab-filter.placeholder.parameter",
                10
            );

        assertEquals("tab-filter.parameter", contribution.contributionId());
        assertEquals("PARAMETER", contribution.paletteId());
        assertEquals("tab-filter.placeholder.parameter", contribution.placeholderKey());
        assertEquals(10, contribution.order());
    }

}
