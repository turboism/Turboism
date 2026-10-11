package dev.turboism.validation.modelimport3d.host;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SurfaceWarpSpatialFitTest {
    private static List<SurfaceWarpSpatialFit.Sample> samples(int divisions, double translation) {
        var result = new ArrayList<SurfaceWarpSpatialFit.Sample>();
        for (int y = 0; y <= divisions; y++)
            for (int x = 0; x <= divisions; x++) {
                double u = (double) x / divisions, v = (double) y / divisions;
                result.add(new SurfaceWarpSpatialFit.Sample(
                        u,
                        v,
                        new SurfaceProjection.Vertex(translation + u, translation + v, u * (1 - u) * v * (1 - v))));
            }
        return result;
    }

    @Test
    void coarseFitPreservesSourceBoundaryAndReportsActualResidual() {
        var source = samples(4, 0);
        var before = List.copyOf(source);
        var fitted = SurfaceWarpSpatialFit.fit(source, 1);
        assertEquals(.0625, fitted.maximumSourceResidual(), 1e-14);
        assertEquals(.625, fitted.projectedResidualBound(10), 1e-14);
        assertEquals(before, source);
        assertEquals(4, fitted.controls().size());
        for (var s : source)
            if (s.u() == 0 || s.u() == 1 || s.v() == 0 || s.v() == 1)
                assertEquals(s.position(), fitted.evaluate(s.u(), s.v()));
    }

    @Test
    void alignedGridIsExactAndPromotionRetainsItsWholeBilinearFunction() {
        var source = samples(2, 0);
        var coarse = SurfaceWarpSpatialFit.fit(source, 2);
        var dense = SurfaceWarpSpatialFit.fitPromoted(source, 16);
        assertEquals(0, coarse.maximumSourceResidual());
        assertEquals(289, dense.controls().size());
        assertEquals(0, dense.maximumSourceResidual());
        for (int y = 0; y <= 32; y++)
            for (int x = 0; x <= 32; x++)
                assertEquals(coarse.evaluate(x / 32d, y / 32d), dense.evaluate(x / 32d, y / 32d));
        assertThrows(IllegalArgumentException.class, () -> SurfaceWarpSpatialFit.fit(source, 16));
    }

    @Test
    void leastSquaresInteriorRemainsStableWithLargeSourceOffsetsAndDuplicateCorners() {
        var source = samples(4, 0);
        var expected = SurfaceWarpSpatialFit.fit(source, 2);
        var shifted = SurfaceWarpSpatialFit.fit(samples(4, 1e9), 2);
        assertEquals(49d / 576, expected.controls().get(4).z(), 1e-14);
        assertEquals(13d / 576, expected.maximumSourceResidual(), 1e-14);
        for (int i = 0; i < 9; i++) {
            assertEquals(
                    expected.controls().get(i).x(), shifted.controls().get(i).x() - 1e9, 1e-6);
            assertEquals(
                    expected.controls().get(i).y(), shifted.controls().get(i).y() - 1e9, 1e-6);
            assertEquals(
                    expected.controls().get(i).z(), shifted.controls().get(i).z(), 1e-14);
        }
        var duplicate = new ArrayList<>(source);
        duplicate.addAll(source);
        assertEquals(expected, SurfaceWarpSpatialFit.fit(duplicate, 2));
    }

    @Test
    void ambiguousUnderdeterminedAndInvalidChartsAreRejected() {
        var source = new ArrayList<>(samples(2, 0));
        source.add(new SurfaceWarpSpatialFit.Sample(0, 0, new SurfaceProjection.Vertex(0, 0, 1)));
        assertThrows(IllegalArgumentException.class, () -> SurfaceWarpSpatialFit.fit(source, 2));
        var boundary = samples(2, 0).stream()
                .filter(s -> s.u() == 0 || s.u() == 1 || s.v() == 0 || s.v() == 1)
                .toList();
        assertThrows(IllegalArgumentException.class, () -> SurfaceWarpSpatialFit.fit(boundary, 2));
        assertThrows(IllegalArgumentException.class, () -> SurfaceWarpSpatialFit.fit(samples(2, 0), 33));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SurfaceWarpSpatialFit.Sample(.5, .5, new SurfaceProjection.Vertex(Double.NaN, 0, 0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> SurfaceWarpSpatialFit.fit(samples(2, 0), 1).evaluate(1.01, 0));
    }
}
