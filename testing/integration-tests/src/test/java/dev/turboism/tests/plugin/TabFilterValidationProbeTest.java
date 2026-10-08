package dev.turboism.tests.plugin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.tests.plugin.TabFilterValidationProbe.PaletteProbe;
import java.util.List;
import javax.swing.JPanel;
import javax.swing.JTextField;
import org.junit.jupiter.api.Test;

/** Verdict-seam tests for {@link TabFilterValidationProbe}'s filter-readiness gate. */
final class TabFilterValidationProbeTest {

    private static PaletteProbe probe(final String kind, final boolean filterBoxFound) {
        return new PaletteProbe(kind, filterBoxFound, "", 0, 0, new JTextField(), null, null, new JPanel());
    }

    @Test
    void filtersReadyRequiresEveryRequiredFilterBox() {
        final List<PaletteProbe> palettes =
                List.of(probe("parameter", true), probe("deformer", true), probe("scene", false));

        assertTrue(TabFilterValidationProbe.filtersReady(palettes, List.of("parameter", "deformer")));
        assertTrue(TabFilterValidationProbe.filtersReady(palettes, List.of(" parameter ")));

        assertFalse(TabFilterValidationProbe.filtersReady(palettes, List.of("scene")));
        assertFalse(TabFilterValidationProbe.filtersReady(palettes, List.of("parameter", "scene")));
        assertFalse(TabFilterValidationProbe.filtersReady(palettes, List.of("log")));
        assertFalse(TabFilterValidationProbe.filtersReady(List.of(), List.of("parameter")));
    }

    @Test
    void paletteLookupMatchesKind() {
        final PaletteProbe parameter = probe("parameter", true);
        final List<PaletteProbe> palettes = List.of(parameter, probe("deformer", false));

        assertSame(parameter, TabFilterValidationProbe.palette(palettes, "parameter"));
        assertNull(TabFilterValidationProbe.palette(palettes, "log"));
        assertTrue(TabFilterValidationProbe.paletteKindFound(palettes, "deformer"));
        assertFalse(TabFilterValidationProbe.paletteKindFound(palettes, "scene"));
    }
}
