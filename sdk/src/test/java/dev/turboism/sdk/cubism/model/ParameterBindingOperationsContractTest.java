package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ParameterBindingPointId;
import dev.turboism.sdk.cubism.id.ParameterId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ParameterBindingOperationsContractTest {

    @Test
    void modelBindingOperationsRemainExplicitAndFailClosedByDefault() {
        final CubismModel model = new CubismModel() {
            @Override public dev.turboism.sdk.cubism.id.ModelId id() { return new dev.turboism.sdk.cubism.id.ModelId("model"); }
            @Override public Parameters parameters() { throw new UnsupportedOperationException(); }
            @Override public Parts parts() { throw new UnsupportedOperationException(); }
            @Override public Drawables drawables() { throw new UnsupportedOperationException(); }
            @Override public Deformers deformers() { throw new UnsupportedOperationException(); }
            @Override public Glues glues() { throw new UnsupportedOperationException(); }
            @Override public void update() { }
        };

        assertThrows(
            UnsupportedOperationException.class,
            () -> model.parameterBindings(new ParameterId("ParamAngleX"))
        );
    }

    @Test
    void pointIdentityAndTargetAreRequiredForEveryMutation() {
        final RecordingOperations operations = new RecordingOperations();
        final ParameterBindingTarget target = ParameterBindingTarget.artMesh(new ArtMeshId("ArtMeshFace"));
        final ParameterBindingPoint point = new ParameterBindingPoint(
            new ParameterBindingPointId("ParamAngleX:0"),
            0.0F
        );

        operations.bind(target, List.of(point));
        operations.createPoint(target, point);
        operations.movePoint(target, point.id(), 1.0F);
        operations.deletePoint(target, point.id());
        operations.unbind(target);
    }

    private static final class RecordingOperations implements ParameterBindingOperations {
        @Override public void bind(ParameterBindingTarget target, List<ParameterBindingPoint> points) { }
        @Override public void createPoint(ParameterBindingTarget target, ParameterBindingPoint point) { }
        @Override public void movePoint(ParameterBindingTarget target, ParameterBindingPointId pointId, float value) { }
        @Override public void deletePoint(ParameterBindingTarget target, ParameterBindingPointId pointId) { }
        @Override public void unbind(ParameterBindingTarget target) { }
    }
}
