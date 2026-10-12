package dev.turboism.adapter.cubism.editor;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorSourceTriangulationSelectorContract;
import dev.turboism.sdk.cubism.model.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@ResourceLock("source-triangulation-fixture")
class EditorSourceTriangulationAccessTest {
    private static final String M = "cubism.editor-model.editable-mesh.";
    private static final ArtMeshGeometry INPUT = new ArtMeshGeometry(
            List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, 1)),
            List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, 1)),
            List.of(0, 1, 2));

    @BeforeEach
    void reset() {
        Engine.constructed = 0;
        Engine.last = null;
        Engine.positions = null;
        Engine.indices = null;
    }

    @Test
    void observesNativeTopologyWithoutPretendingItIsTheInputAndOwnsReturnedArrays() {
        Engine.indices = new int[] {0, 2, 1};
        var result = preview(resolver("5.3.02", false, null));
        assertEquals(INPUT.positions(), result.positions());
        assertEquals(List.of(0, 2, 1), result.triangleIndices());
        assertEquals(List.of(0, 1, 2), INPUT.triangleIndices());
        assertEquals(1, Engine.constructed);
        Engine.last.xy[0] = 99;
        Engine.indices[0] = 2;
        assertEquals(0, result.positions().get(0).x());
        assertEquals(0, result.triangleIndices().get(0));
        assertThrows(
                UnsupportedOperationException.class, () -> result.positions().clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.triangleIndices().clear());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.0.00", "5.0.06", "5.1.03", "5.2.00", "5.2.03", "5.3.00", "5.3.04"})
    void previewsNativeTopologyOnEveryReviewedGeneration(final String version) {
        Engine.indices = new int[] {0, 2, 1};
        var result = preview(resolver(version, false, null));
        assertEquals(INPUT.positions(), result.positions());
        assertEquals(List.of(0, 2, 1), result.triangleIndices());
        assertEquals(1, Engine.constructed);
    }

    @Test
    void faithfullyExposesNativeEmptyFacesAndCollapsedPoints() {
        Engine.positions = new float[] {0, 0};
        Engine.indices = new int[0];
        var result = preview(resolver("5.3.02", false, null));
        assertEquals(List.of(new Point2(0, 0)), result.positions());
        assertTrue(result.triangleIndices().isEmpty());
    }

    @Test
    void missingVersionCapabilityOrAnySelectorFailsBeforeConstruction() {
        for (var version : List.of("5.4.00"))
            assertThrows(UnsupportedOperationException.class, () -> preview(resolver(version, false, null)));
        assertThrows(UnsupportedOperationException.class, () -> preview(resolver("5.3.02", true, null)));
        for (var alias : EditorSourceTriangulationSelectorContract.REQUIRED_ALIASES)
            assertThrows(UnsupportedOperationException.class, () -> preview(resolver("5.3.02", false, alias)), alias);
        assertEquals(0, Engine.constructed);
    }

    @Test
    void rejectsMalformedNativeArraysAndOutOfDomainFaces() {
        for (var coordinates : List.of(new float[] {0}, new float[] {Float.NaN, 0})) {
            Engine.positions = coordinates;
            assertThrows(RuntimeException.class, () -> preview(resolver("5.3.02", false, null)));
        }
        Engine.positions = null;
        for (var indices : List.of(new int[] {0}, new int[] {0, 1, 99}, new int[] {-1, 1, 2})) {
            Engine.indices = indices;
            assertThrows(RuntimeException.class, () -> preview(resolver("5.3.02", false, null)));
        }
    }

    private static ArtMeshSourceTriangulation preview(VerifiedMemberResolver resolver) {
        return EditorHostThread.dispatch(
                "source preview test", () -> new EditorSourceTriangulationAccess(resolver).preview(INPUT));
    }

    private static VerifiedMemberResolver resolver(String version, boolean missingCapability, String missingAlias) {
        String engine = Engine.class.getName().replace('.', '/'),
                coord = Coord.class.getName().replace('.', '/');
        var aliases = List.of(
                StaticSelector.classSelector(M + "class", engine),
                StaticSelector.constructor(M + "create", engine, "()V", 1),
                StaticSelector.method(M + "init-by-indices", engine, "init", "([F[IL" + coord + ";)V", 1),
                StaticSelector.method(M + "gl-positions", engine, "points", "()[F", 1),
                StaticSelector.staticMethod(
                        M + "gl-indices-default",
                        engine,
                        "getGlIndices$default",
                        "(L" + engine + ";Ljava/lang/Object;ZILjava/lang/Object;)[I",
                        9),
                StaticSelector.staticMethod(
                        "cubism.editor-model.coord-type.canvas", coord, "canvas", "()L" + coord + ";", 9));
        return TestVerifiedResolvers.create(
                version,
                EditorSourceTriangulationSelectorContract.ADAPTER_SLICE_ID,
                missingCapability
                        ? Set.of("cubism.editor-model.read")
                        : Set.of(EditorSourceTriangulationSelectorContract.CAPABILITY_ID),
                aliases.stream().filter(a -> !a.alias().equals(missingAlias)).toList(),
                Engine.class.getClassLoader());
    }

    public static final class Coord {
        public static Coord canvas() {
            return new Coord();
        }
    }

    public static final class Engine {
        static int constructed;
        static Engine last;
        static float[] positions;
        static int[] indices;
        float[] xy;
        int[] ix;

        public Engine() {
            constructed++;
            last = this;
        }

        public void init(float[] p, int[] i, Coord c) {
            xy = p;
            ix = i;
            assertNotNull(c);
        }

        public float[] points() {
            return positions == null ? xy : positions;
        }

        public static int[] getGlIndices$default(Engine e, Object unused, boolean flag, int mask, Object marker) {
            assertNull(unused);
            assertNull(marker);
            assertFalse(flag);
            assertEquals(3, mask);
            return indices == null ? e.ix : indices;
        }
    }
}
