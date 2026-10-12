package dev.turboism.sdk.cubism.model;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.CubismEditor;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ArtMeshKeyformContractTest {
    @Test
    void drawOrderAdditionIsDefaultUnsupportedWithoutCallingOlderImplementations() throws Exception {
        final ArtMeshKeyform previousImplementation = new ArtMeshKeyform() {
            @Override
            public List<ArtMeshAuthoringSnapshot.Coordinate> coordinates() {
                throw new AssertionError("unexpected read");
            }

            @Override
            public ArtMeshAuthoringSnapshot.Form snapshot() {
                throw new AssertionError("unexpected read");
            }

            @Override
            public boolean defaultKeyform() {
                throw new AssertionError("unexpected read");
            }

            @Override
            public void setPositions(final List<Point2> positions) {
                throw new AssertionError("unexpected write");
            }
        };
        for (final int order : List.of(0, 500, 1000, -1, 1001)) {
            assertThrows(UnsupportedOperationException.class, () -> previousImplementation.setDrawOrder(order));
        }
        final var setter = ArtMeshKeyform.class.getMethod("setDrawOrder", int.class);
        assertTrue(setter.isDefault());
        assertEquals(void.class, setter.getReturnType());
    }

    @Test
    void storedTargetSurfaceRemainsTypedAndRestrictedToExactEditorVersion() {
        assertArrayEquals(
                new String[0],
                ArtMeshKeyform.class.getAnnotation(CubismEditor.class).value());
        assertEquals(
                "5.0.00", ArtMeshKeyform.class.getAnnotation(CubismEditor.class).from());
        assertEquals(
                Set.of("coordinates", "snapshot", "defaultKeyform", "setPositions", "setDrawOrder"),
                Arrays.stream(ArtMeshKeyform.class.getDeclaredMethods())
                        .filter(method -> !method.isSynthetic())
                        .map(java.lang.reflect.Method::getName)
                        .collect(Collectors.toSet()));
    }
}
