package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MeshVertexProjectorTest {
    @Test
    void projectsExactEvaluatedPairsIntoComponentCoordinates() {
        AtomicInteger calls = new AtomicInteger();
        List<Point2> result = MeshVertexProjector.project(new float[] {1, 2, 3, 4}, 2, (x, y) -> {
            calls.incrementAndGet();
            return new Point2(x * 10, y * -10);
        });
        assertEquals(List.of(new Point2(10, -20), new Point2(30, -40)), result);
        assertEquals(2, calls.get());
    }

    @Test
    void malformedEvaluatedOrProjectedDataFailsBeforePartialUse() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(
                IllegalStateException.class,
                () -> MeshVertexProjector.project(new float[] {1, 2, 3}, 1, (x, y) -> {
                    calls.incrementAndGet();
                    return new Point2(x, y);
                }));
        assertEquals(0, calls.get());
        assertThrows(
                IllegalStateException.class,
                () -> MeshVertexProjector.project(new float[] {1, Float.NaN}, 1, (x, y) -> {
                    calls.incrementAndGet();
                    return new Point2(x, y);
                }));
        assertEquals(0, calls.get());
        assertThrows(
                IllegalStateException.class,
                () -> MeshVertexProjector.project(new float[] {1, 2}, 2, (x, y) -> new Point2(x, y)));
        assertThrows(
                IllegalStateException.class,
                () -> MeshVertexProjector.project(
                        new float[] {1, 2}, 1, (x, y) -> new Point2(Float.POSITIVE_INFINITY, y)));
    }
}
