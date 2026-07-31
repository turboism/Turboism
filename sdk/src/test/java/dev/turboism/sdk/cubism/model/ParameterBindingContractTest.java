package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ParameterBindingPointId;
import dev.turboism.sdk.cubism.id.ParameterId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ParameterBindingContractTest {

    @Test
    void bindingSnapshotCarriesTypedTargetParameterAndOrderedImmutablePoints() {
        final ParameterBindingPoint first = new ParameterBindingPoint(
            new ParameterBindingPointId("point:min"),
            -1.0F
        );
        final ParameterBindingPoint second = new ParameterBindingPoint(
            new ParameterBindingPointId("point:max"),
            1.0F
        );
        final java.util.ArrayList<ParameterBindingPoint> source =
            new java.util.ArrayList<>(List.of(first, second));
        final ParameterBinding binding = new ParameterBinding(
            ParameterBindingTarget.artMesh(new ArtMeshId("ArtMeshA")),
            new ParameterId("ParamAngleX"),
            ParameterBindingFamily.KEYFORM_GRID,
            source
        );
        source.clear();

        assertEquals(ParameterBindingTargetType.ART_MESH, binding.target().type());
        assertEquals("ArtMeshA", binding.target().id());
        assertEquals("ParamAngleX", binding.parameterId().value());
        assertEquals(List.of(first, second), binding.points());
        assertThrows(UnsupportedOperationException.class, () -> binding.points().add(first));
    }

    @Test
    void targetsRetainTheirObjectFamilyAndRejectBlankIdentity() {
        assertEquals(
            ParameterBindingTargetType.WARP_DEFORMER,
            ParameterBindingTarget.warpDeformer(new DeformerId("WarpA")).type()
        );
        assertEquals(
            ParameterBindingTargetType.ROTATION_DEFORMER,
            ParameterBindingTarget.rotationDeformer(new DeformerId("RotationA")).type()
        );
        assertThrows(IllegalArgumentException.class, () -> new ParameterBindingPointId(" "));
    }

    @Test
    void legacyBackendsFailExplicitlyWhenBindingProjectionIsUnavailable() {
        final Parameter parameter = new Parameter() {
            @Override public ParameterId id() { return new ParameterId("ParamA"); }
            @Override public float getValue() { return 0.0F; }
            @Override public float getMinimumValue() { return -1.0F; }
            @Override public float getMaximumValue() { return 1.0F; }
            @Override public float getDefaultValue() { return 0.0F; }
            @Override public void setValue(final float value) { }
        };
        final Drawable drawable = new UnavailableDrawable();
        final WarpDeformer warp = new UnavailableWarp();
        final RotationDeformer rotation = new UnavailableRotation();

        assertThrows(UnsupportedOperationException.class, parameter::getParameterBindings);
        assertThrows(UnsupportedOperationException.class, drawable::getParameterBindings);
        assertThrows(UnsupportedOperationException.class, warp::getParameterBindings);
        assertThrows(UnsupportedOperationException.class, rotation::getParameterBindings);
    }

    private static class UnavailableDrawable implements Drawable {
        @Override public ArtMeshId id() { return new ArtMeshId("ArtMeshA"); }
        @Override public byte constantFlag() { return 0; }
        @Override public byte dynamicFlag() { return 0; }
        @Override public BlendMode blendMode() { return BlendMode.NORMAL; }
        @Override public int textureIndex() { return 0; }
        @Override public int drawOrder() { return 0; }
        @Override public int renderOrder() { return 0; }
        @Override public float getOpacity() { return 1.0F; }
        @Override public IntSequence masks() { return emptyInts(); }
        @Override public FloatSequence vertexPositions() { return emptyFloats(); }
        @Override public FloatSequence vertexUvs() { return emptyFloats(); }
        @Override public IntSequence indices() { return emptyInts(); }
        @Override public Color multiplyColor() { return new Color(1.0F, 1.0F, 1.0F, 1.0F); }
        @Override public Color screenColor() { return new Color(0.0F, 0.0F, 0.0F, 0.0F); }
        @Override public int parentPartIndex() { return -1; }
        @Override public int parentDeformerIndex() { return -1; }
        @Override public IntSequence parameters() { return emptyInts(); }
    }

    private static final class UnavailableWarp extends UnavailableDeformer implements WarpDeformer {
        @Override public WarpGrid grid() { throw new UnsupportedOperationException(); }
        @Override public void replaceGrid(final WarpGrid grid) { throw new UnsupportedOperationException(); }
    }

    private static final class UnavailableRotation extends UnavailableDeformer implements RotationDeformer {
        @Override public float baseAngle() { return 0.0F; }
        @Override public void setBaseAngle(final float angle) { }
        @Override public RotationDeformerForm form() { throw new UnsupportedOperationException(); }
        @Override public void replaceForm(final RotationDeformerForm form) { }
    }

    private static class UnavailableDeformer implements Deformer {
        @Override public DeformerId id() { return new DeformerId("DeformerA"); }
        @Override public int parentDeformerIndex() { return -1; }
        @Override public IntSequence parameters() { return emptyInts(); }
    }

    private static IntSequence emptyInts() {
        return new IntSequence() {
            @Override public int size() { return 0; }
            @Override public int get(final int index) { throw new IndexOutOfBoundsException(index); }
        };
    }

    private static FloatSequence emptyFloats() {
        return new FloatSequence() {
            @Override public int size() { return 0; }
            @Override public float get(final int index) { throw new IndexOutOfBoundsException(index); }
        };
    }
}
