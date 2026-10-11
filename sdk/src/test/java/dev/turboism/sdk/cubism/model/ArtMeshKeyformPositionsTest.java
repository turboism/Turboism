package dev.turboism.sdk.cubism.model;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArtMeshKeyformPositionsTest {
    @Test
    void editOwnsItsListsAndRejectsInvalidIdentity() {
        var coordinates = new ArrayList<>(List.of(new ArtMeshAuthoringSnapshot.Coordinate("yaw", 1)));
        var positions = new ArrayList<>(List.of(new Point2(3, 4)));
        var edit = new ArtMeshKeyformPositions(coordinates, "form", positions);
        coordinates.clear();
        positions.clear();
        assertEquals(1, edit.coordinates().size());
        assertEquals(List.of(new Point2(3, 4)), edit.positions());
        assertThrows(UnsupportedOperationException.class, () -> edit.positions().clear());
        assertThrows(IllegalArgumentException.class, () -> new ArtMeshKeyformPositions(List.of(), " ", List.of()));
        assertThrows(NullPointerException.class, () -> new ArtMeshKeyformPositions(List.of(), null, List.of()));
    }

    @Test
    void olderProvidersFailWithoutReadsOrWrites() {
        var drawable = (Drawable) Proxy.newProxyInstance(
                Drawable.class.getClassLoader(), new Class<?>[] {Drawable.class}, (o, method, args) -> {
                    if (!method.isDefault()) throw new AssertionError("unexpected delegate access");
                    return InvocationHandler.invokeDefault(o, method, args);
                });
        assertThrows(UnsupportedOperationException.class, () -> drawable.setOrdinaryKeyformPositions(null, List.of()));
    }
}
