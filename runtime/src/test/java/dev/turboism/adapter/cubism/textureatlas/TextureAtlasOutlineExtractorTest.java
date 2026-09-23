package dev.turboism.adapter.cubism.textureatlas;

import java.awt.geom.Path2D;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureAtlasOutlineExtractorTest {

    @Test
    void nullAndEmptyInputYieldNull() {
        assertNull(TextureAtlasOutlineExtractor.extract(null));
        assertNull(TextureAtlasOutlineExtractor.extract(List.of()));
    }

    @Test
    void concaveContourIsPreserved() {
        final Path2D.Double l = new Path2D.Double();
        l.moveTo(0, 0); l.lineTo(40, 0); l.lineTo(40, 20);
        l.lineTo(20, 20); l.lineTo(20, 40); l.lineTo(0, 40);
        l.closePath();
        final TextureAtlasOutlineExtractor.Extraction e =
            TextureAtlasOutlineExtractor.extract(List.of(l));
        assertNotNull(e);
        assertFalse(e.holesFilled());
        assertEquals(1, e.outline().rings().size());
        // convex hull would have 4 vertices; the notch must survive
        assertEquals(6, e.outline().rings().get(0).length);
    }

    @Test
    void multipleShapesStaySeparateRings() {
        final Path2D.Double a = rect(0, 0, 10, 10);
        final Path2D.Double b = rect(50, 50, 10, 10);
        final TextureAtlasOutlineExtractor.Extraction e =
            TextureAtlasOutlineExtractor.extract(List.of(a, b));
        assertNotNull(e);
        assertEquals(2, e.outline().rings().size());
        assertFalse(e.holesFilled());
    }

    @Test
    void holeRingIsDroppedAndFlagged() {
        final Path2D.Double donut =
            new Path2D.Double(Path2D.WIND_EVEN_ODD);
        append(donut, new double[][] {{0, 0}, {40, 0}, {40, 40}, {0, 40}});
        append(donut, new double[][] {{10, 10}, {30, 10}, {30, 30}, {10, 30}});
        final TextureAtlasOutlineExtractor.Extraction e =
            TextureAtlasOutlineExtractor.extract(List.of(donut));
        assertNotNull(e);
        assertTrue(e.holesFilled(), "hole must be flagged as conservatively filled");
        assertEquals(1, e.outline().rings().size());
        // only the outer ring survives; no silent extra rings
        assertEquals(4, e.outline().rings().get(0).length);
    }

    private static Path2D.Double rect(final double x, final double y,
        final double w, final double h) {
        final Path2D.Double p = new Path2D.Double();
        append(p, new double[][] {{x, y}, {x + w, y}, {x + w, y + h}, {x, y + h}});
        return p;
    }

    private static void append(final Path2D.Double path, final double[][] ring) {
        path.moveTo(ring[0][0], ring[0][1]);
        for (int i = 1; i < ring.length; i++) {
            path.lineTo(ring[i][0], ring[i][1]);
        }
        path.closePath();
    }
}
