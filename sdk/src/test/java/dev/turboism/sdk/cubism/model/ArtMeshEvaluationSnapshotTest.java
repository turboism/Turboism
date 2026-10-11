package dev.turboism.sdk.cubism.model;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ParameterId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ArtMeshEvaluationSnapshotTest {
    @Test
    void recursivelyCopiesInputsAndRetainsIndependentCounterDomains() {
        final var points = new ArrayList<>(List.of(new Point2(3, 4)));
        final var parameters = new ArrayList<>(List.of(value("x", .5F), value("y", -.5F)));
        final var form = new ArtMeshEvaluationSnapshot.Form(points, 1234, "DeformerLocal", false);
        final var live = new ArtMeshEvaluationSnapshot.ParameterState(Integer.MIN_VALUE, parameters);
        final var consumed = new ArtMeshEvaluationSnapshot.ParameterState(13, List.of(value("x", 0)));
        final var snapshot =
                new ArtMeshEvaluationSnapshot(new ArtMeshId("mesh"), "guid", Optional.of(form), live, consumed, 4L);
        points.clear();
        parameters.clear();
        assertEquals(
                List.of(new Point2(3, 4)),
                snapshot.calculatedForm().orElseThrow().positions());
        assertEquals(2, snapshot.liveParameters().parameters().size());
        assertEquals(0F, snapshot.consumedParameters().parameters().get(0).value());
        assertNotEquals(live.updateVersion(), consumed.updateVersion());
        assertFalse(form.interpolated());
        assertThrows(UnsupportedOperationException.class, () -> form.positions().clear());
        assertThrows(
                UnsupportedOperationException.class, () -> live.parameters().clear());
        assertThrows(
                UnsupportedOperationException.class, () -> consumed.parameters().clear());
    }

    @Test
    void representsNoCalculationWithoutInventingCoordinates() {
        final var state = new ArtMeshEvaluationSnapshot.ParameterState(0, List.of());
        final var snapshot =
                new ArtMeshEvaluationSnapshot(new ArtMeshId("mesh"), "guid", Optional.empty(), state, state, -1);
        assertTrue(snapshot.calculatedForm().isEmpty());
        assertEquals(-1, snapshot.passEntryTimeMillis());
        final Drawable unavailable = (Drawable) java.lang.reflect.Proxy.newProxyInstance(
                Drawable.class.getClassLoader(), new Class<?>[] {Drawable.class}, (proxy, method, args) -> {
                    if (method.isDefault())
                        return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
                    throw new AssertionError("unexpected call: " + method);
                });
        assertThrows(UnsupportedOperationException.class, unavailable::evaluationSnapshot);
    }

    @Test
    void rejectsMalformedInputs() {
        final var state = new ArtMeshEvaluationSnapshot.ParameterState(0, List.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ArtMeshEvaluationSnapshot.ParameterState(0, List.of(value("x", 0), value("x", 1))));
        assertThrows(IllegalArgumentException.class, () -> value("", 0));
        assertThrows(IllegalArgumentException.class, () -> value("x", Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> value("x", Float.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new ArtMeshEvaluationSnapshot.Form(List.of(), 1, " ", true));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ArtMeshEvaluationSnapshot(new ArtMeshId("mesh"), "", Optional.empty(), state, state, -1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ArtMeshEvaluationSnapshot(new ArtMeshId("mesh"), "guid", Optional.empty(), state, state, -2));
        assertThrows(
                NullPointerException.class,
                () -> new ArtMeshEvaluationSnapshot(new ArtMeshId("mesh"), "guid", null, state, state, -1));
    }

    private static ArtMeshEvaluationSnapshot.ParameterValue value(final String guid, final float value) {
        return new ArtMeshEvaluationSnapshot.ParameterValue(guid, new ParameterId("same-id"), value);
    }
}
