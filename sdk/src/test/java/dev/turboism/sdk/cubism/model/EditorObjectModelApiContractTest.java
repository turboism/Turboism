package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorObjectModelApiContractTest {

    @Test
    void modelExposesStronglyTypedEditorObjectFamilies() throws Exception {
        assertEquals(Drawables.class, CubismModel.class.getMethod("drawables").getReturnType());
        assertEquals(WarpDeformers.class, CubismModel.class.getMethod("warpDeformers").getReturnType());
        assertEquals(
            RotationDeformers.class,
            CubismModel.class.getMethod("rotationDeformers").getReturnType()
        );

        assertEquals(Drawable.class, Drawables.class.getMethod("find", ArtMeshId.class).getReturnType());
        assertEquals(
            WarpDeformer.class,
            WarpDeformers.class.getMethod("find", DeformerId.class).getReturnType()
        );
        assertEquals(
            RotationDeformer.class,
            RotationDeformers.class.getMethod("find", DeformerId.class).getReturnType()
        );
    }

    @Test
    void objectFamiliesExposeStableEditorPropertiesAndAtomicWrites() throws Exception {
        assertMethod(Drawable.class, "name");
        assertMethod(Drawable.class, "visible");
        assertMethod(Drawable.class, "setVisible", boolean.class);
        assertMethod(Drawable.class, "locked");
        assertMethod(Drawable.class, "setLocked", boolean.class);
        assertMethod(Drawable.class, "visibleInHierarchy");
        assertMethod(Drawable.class, "lockedInHierarchy");
        assertMethod(Drawable.class, "setOpacity", float.class);
        assertMethod(Drawable.class, "geometry");
        assertMethod(Drawable.class, "replaceGeometry", ArtMeshGeometry.class);
        assertMethod(Drawable.class, "invertedMask");
        assertMethod(Drawable.class, "culling");
        assertMethod(Drawable.class, "userData");

        assertMethod(Deformer.class, "name");
        assertMethod(Deformer.class, "visible");
        assertMethod(Deformer.class, "setVisible", boolean.class);
        assertMethod(Deformer.class, "locked");
        assertMethod(Deformer.class, "setLocked", boolean.class);
        assertMethod(Deformer.class, "visibleInHierarchy");
        assertMethod(Deformer.class, "lockedInHierarchy");
        assertMethod(Deformer.class, "getOpacity");
        assertMethod(Deformer.class, "setOpacity", float.class);
        assertMethod(Deformer.class, "multiplyColor");
        assertMethod(Deformer.class, "screenColor");
        assertMethod(Deformer.class, "parentPartIndex");

        assertMethod(WarpDeformer.class, "grid");
        assertMethod(WarpDeformer.class, "replaceGrid", WarpGrid.class);
        assertMethod(RotationDeformer.class, "baseAngle");
        assertMethod(RotationDeformer.class, "setBaseAngle", float.class);
        assertMethod(RotationDeformer.class, "form");
        assertMethod(
            RotationDeformer.class,
            "replaceForm",
            RotationDeformerForm.class
        );
    }

    @Test
    void artMeshGeometryIsDeeplyImmutableAndValidatesTopology() {
        final ArrayList<Point2> positions = new ArrayList<>(List.of(
            new Point2(0.0f, 0.0f),
            new Point2(1.0f, 0.0f),
            new Point2(0.0f, 1.0f)
        ));
        final ArrayList<Point2> uvs = new ArrayList<>(List.of(
            new Point2(0.0f, 0.0f),
            new Point2(1.0f, 0.0f),
            new Point2(0.0f, 1.0f)
        ));
        final ArrayList<Integer> indices = new ArrayList<>(List.of(0, 1, 2));

        final ArtMeshGeometry geometry = new ArtMeshGeometry(positions, uvs, indices);
        positions.set(0, new Point2(9.0f, 9.0f));
        uvs.clear();
        indices.clear();

        assertEquals(new Point2(0.0f, 0.0f), geometry.positions().get(0));
        assertEquals(3, geometry.uvs().size());
        assertEquals(List.of(0, 1, 2), geometry.triangleIndices());
        assertThrows(
            UnsupportedOperationException.class,
            () -> geometry.positions().add(new Point2(2.0f, 2.0f))
        );

        final ArtMeshGeometry moved = geometry.withVertexPosition(1, 2.0f, 3.0f);
        assertEquals(new Point2(2.0f, 3.0f), moved.positions().get(1));
        assertEquals(new Point2(1.0f, 0.0f), geometry.positions().get(1));

        assertThrows(IllegalArgumentException.class, () -> new ArtMeshGeometry(
            List.of(new Point2(0.0f, 0.0f)),
            List.of(),
            List.of()
        ));
        assertThrows(IllegalArgumentException.class, () -> new ArtMeshGeometry(
            geometry.positions(),
            geometry.uvs(),
            List.of(0, 1)
        ));
        assertThrows(IllegalArgumentException.class, () -> new ArtMeshGeometry(
            geometry.positions(),
            geometry.uvs(),
            List.of(0, 1, 3)
        ));
    }

    @Test
    void warpGridIsDeeplyImmutableAndValidatesDimensions() {
        final ArrayList<Point2> points = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            points.add(new Point2(index, index + 0.5f));
        }
        final WarpGrid grid = new WarpGrid(2, 3, false, points);
        points.clear();

        assertEquals(12, grid.controlPoints().size());
        assertThrows(
            UnsupportedOperationException.class,
            () -> grid.controlPoints().add(new Point2(0.0f, 0.0f))
        );
        final WarpGrid moved = grid.withControlPoint(4, -2.0f, 8.0f);
        assertEquals(new Point2(-2.0f, 8.0f), moved.controlPoints().get(4));
        assertEquals(new Point2(4.0f, 4.5f), grid.controlPoints().get(4));

        assertThrows(IllegalArgumentException.class, () -> new WarpGrid(0, 3, false, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new WarpGrid(
            2,
            3,
            false,
            List.of(new Point2(0.0f, 0.0f))
        ));
    }

    @Test
    void rotationFormRejectsNonFiniteOrNonPositiveScale() {
        final RotationDeformerForm form = new RotationDeformerForm(
            15.0f,
            2.0f,
            3.0f,
            1.25f,
            true,
            false
        );
        assertEquals(15.0f, form.angle());
        assertEquals(new Point2(2.0f, 3.0f), form.origin());

        assertThrows(IllegalArgumentException.class, () -> new RotationDeformerForm(
            Float.NaN, 0.0f, 0.0f, 1.0f, false, false
        ));
        assertThrows(IllegalArgumentException.class, () -> new RotationDeformerForm(
            0.0f, 0.0f, 0.0f, 0.0f, false, false
        ));
    }

    private static Method assertMethod(
        final Class<?> owner,
        final String name,
        final Class<?>... parameterTypes
    ) throws Exception {
        final Method method = owner.getMethod(name, parameterTypes);
        assertTrue(method.getDeclaringClass().isAssignableFrom(owner));
        return method;
    }
}
