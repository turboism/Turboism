package dev.turboism.plugin.frameanimation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.turboism.plugin.frameanimation.domain.AnchorPoint;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Point2;
import org.junit.jupiter.api.Test;

class FrameMeshAssemblerTest {

    @Test
    void createsCenteredQuadGeometry() {
        final ArtMeshGeometry geometry = FrameMeshAssembler.createGeometry(200f, 100f, AnchorPoint.CENTER, 0f, 0f);

        assertEquals(4, geometry.positions().size());
        assertEquals(4, geometry.uvs().size());
        assertEquals(6, geometry.triangleIndices().size());

        // Bounds: x in [-100, 100], y in [-50, 50]
        assertEquals(new Point2(-100f, -50f), geometry.positions().get(0));
        assertEquals(new Point2(100f, -50f), geometry.positions().get(1));
        assertEquals(new Point2(100f, 50f), geometry.positions().get(2));
        assertEquals(new Point2(-100f, 50f), geometry.positions().get(3));

        // Upright UVs: bottom corresponds to (0, 0), top to (0, 1) in Cubism screen space
        assertEquals(new Point2(0.0f, 0.0f), geometry.uvs().get(0));
        assertEquals(new Point2(1.0f, 0.0f), geometry.uvs().get(1));
        assertEquals(new Point2(1.0f, 1.0f), geometry.uvs().get(2));
        assertEquals(new Point2(0.0f, 1.0f), geometry.uvs().get(3));
    }

    @Test
    void createsBottomCenterQuadGeometry() {
        final ArtMeshGeometry geometry =
                FrameMeshAssembler.createGeometry(100f, 100f, AnchorPoint.BOTTOM_CENTER, 50f, 10f);

        // x offset is 50, y offset is 0
        // Left = 50 - 50 = 0, Right = 100
        // Bottom = 10 - 0 = 10, Top = 110
        assertEquals(new Point2(0f, 10f), geometry.positions().get(0));
        assertEquals(new Point2(100f, 10f), geometry.positions().get(1));
        assertEquals(new Point2(100f, 110f), geometry.positions().get(2));
        assertEquals(new Point2(0f, 110f), geometry.positions().get(3));
    }

    @Test
    void rejectsInvalidDimensions() {
        assertThrows(
                IllegalArgumentException.class,
                () -> FrameMeshAssembler.createGeometry(0f, 100f, AnchorPoint.CENTER, 0f, 0f));
        assertThrows(
                IllegalArgumentException.class,
                () -> FrameMeshAssembler.createGeometry(100f, -10f, AnchorPoint.CENTER, 0f, 0f));
    }
}
