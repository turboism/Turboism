package dev.turboism.sdk.cubism.model;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ArtMeshKeyformValuesTest {
    @Test
    void sparseValuesOwnListsAndRejectEmptyOrInvalidOpacity() {
        var coordinates = new ArrayList<>(List.of(new ArtMeshAuthoringSnapshot.Coordinate("x", 1)));
        var positions = new ArrayList<>(List.of(new Point2(3, 4)));
        var edit =
                new ArtMeshKeyformValues(coordinates, "f", Optional.of(positions), Optional.of(500), Optional.of(.25F));
        coordinates.clear();
        positions.clear();
        assertEquals(1, edit.coordinates().size());
        assertEquals(List.of(new Point2(3, 4)), edit.positions().orElseThrow());
        assertThrows(
                UnsupportedOperationException.class,
                () -> edit.positions().orElseThrow().clear());
        for (float value : new float[] {-1, 1.01F, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> opacity(value));
        assertEquals(Optional.of(0F), opacity(0).opacity());
        assertEquals(Optional.of(1F), opacity(1).opacity());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ArtMeshKeyformValues(List.of(), "f", Optional.empty(), Optional.empty(), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ArtMeshKeyformValues(List.of(), "f", Optional.empty(), Optional.of(1001), Optional.empty()));
        assertThrows(
                NullPointerException.class,
                () -> new ArtMeshKeyformValues(List.of(), "f", Optional.empty(), Optional.empty(), null));
    }

    @Test
    void oldProviderFailsWithoutInvokingAnyDelegateAndApiHasVersionBoundary() throws Exception {
        var drawable = (Drawable) Proxy.newProxyInstance(
                Drawable.class.getClassLoader(), new Class<?>[] {Drawable.class}, (o, method, args) -> {
                    if (!method.isDefault()) throw new AssertionError("unexpected access");
                    return InvocationHandler.invokeDefault(o, method, args);
                });
        assertThrows(UnsupportedOperationException.class, () -> drawable.setOrdinaryKeyformValues(null, List.of()));
        var method = Drawable.class.getMethod("setOrdinaryKeyformValues", ArtMeshAuthoringSnapshot.class, List.class);
        assertTrue(method.isDefault());
        assertEquals(
                "5.0.00",
                method.getAnnotation(dev.turboism.sdk.CubismEditor.class).from());
    }

    private static ArtMeshKeyformValues opacity(float value) {
        return new ArtMeshKeyformValues(List.of(), "f", Optional.empty(), Optional.empty(), Optional.of(value));
    }
}
