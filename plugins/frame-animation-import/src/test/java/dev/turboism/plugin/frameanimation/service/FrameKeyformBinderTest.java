package dev.turboism.plugin.frameanimation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.turboism.plugin.frameanimation.domain.DisplayStrategy;
import dev.turboism.plugin.frameanimation.domain.KeyformBindingMode;
import dev.turboism.plugin.frameanimation.domain.ParameterConfig;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.FloatSequence;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterBindingOperations;
import dev.turboism.sdk.cubism.model.ParameterBindingPoint;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.cubism.model.ParameterDefinition;
import dev.turboism.sdk.cubism.model.ParameterType;
import dev.turboism.sdk.cubism.model.Parameters;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FrameKeyformBinderTest {

    @Test
    void bindsLocalNeighborhoodCrossfade() {
        final TestBinderModel model = new TestBinderModel();
        final Parameter parameter = FrameKeyformBinder.getOrCreateParameter(
                model, ParameterConfig.ofFrames("PARAM_CF", "Crossfade", 4, false));

        final List<Drawable> meshes = List.of(
                new TestDrawable(new ArtMeshId("m0")),
                new TestDrawable(new ArtMeshId("m1")),
                new TestDrawable(new ArtMeshId("m2")),
                new TestDrawable(new ArtMeshId("m3")));

        FrameKeyformBinder.bindFrameOpacities(
                model, parameter, meshes, DisplayStrategy.CROSSFADE, KeyformBindingMode.FULL_RANGE);

        // In FULL_RANGE mode, all meshes bind to the 4 integer keys {0, 1, 2, 3}
        assertEquals(4, model.boundPoints.get(0).size());
        assertEquals(4, model.boundPoints.get(1).size());
        assertEquals(4, model.boundPoints.get(2).size());
        assertEquals(4, model.boundPoints.get(3).size());

        // Parameter reset to default (0.0)
        assertEquals(0.0f, parameter.getValue());
    }

    @Test
    void bindsStepFlipbookWithGuardKeys() {
        final TestBinderModel model = new TestBinderModel();
        final Parameter parameter =
                FrameKeyformBinder.getOrCreateParameter(model, ParameterConfig.ofFrames("PARAM_STEP", "Step", 3, true));

        final List<Drawable> meshes = List.of(
                new TestDrawable(new ArtMeshId("s0")),
                new TestDrawable(new ArtMeshId("s1")),
                new TestDrawable(new ArtMeshId("s2")));

        FrameKeyformBinder.bindFrameOpacities(
                model, parameter, meshes, DisplayStrategy.STEP_FLIPBOOK, KeyformBindingMode.LOCAL_NEIGHBORHOOD);

        // Check bindings exist for all meshes
        assertEquals(3, model.boundPoints.size());
        // Mesh 0 has guard keys: cur (0.0), guardEnd (0.99), next (1.0) -> 3 points
        assertEquals(3, model.boundPoints.get(0).size());
        // Mesh 1 has guard keys: defaultValue (0.0), guardStart (0.99), cur (1.0), guardEnd (1.99), next (2.0) -> 5
        // points
        assertEquals(5, model.boundPoints.get(1).size());

        assertEquals(0.0f, parameter.getValue());
    }

    // --- Test harness ---

    private static final class TestBinderModel implements CubismModel {
        final TestParameters params = new TestParameters();
        final List<List<ParameterBindingPoint>> boundPoints = new ArrayList<>();

        @Override
        public dev.turboism.sdk.cubism.id.ModelId id() {
            return null;
        }

        @Override
        public dev.turboism.sdk.cubism.model.Parts parts() {
            return null;
        }

        @Override
        public dev.turboism.sdk.cubism.model.Drawables drawables() {
            return null;
        }

        @Override
        public dev.turboism.sdk.cubism.model.Deformers deformers() {
            return null;
        }

        @Override
        public dev.turboism.sdk.cubism.model.Glues glues() {
            return null;
        }

        @Override
        public Parameters parameters() {
            return params;
        }

        @Override
        public ParameterBindingOperations parameterBindings(final ParameterId parameterId) {
            return new ParameterBindingOperations() {
                @Override
                public void bind(final ParameterBindingTarget target, final List<ParameterBindingPoint> points) {
                    boundPoints.add(points);
                }

                @Override
                public void createPoint(final ParameterBindingTarget target, final ParameterBindingPoint point) {}

                @Override
                public void movePoint(
                        final ParameterBindingTarget target,
                        final dev.turboism.sdk.cubism.id.ParameterBindingPointId pointId,
                        final float value) {}

                @Override
                public void deletePoint(
                        final ParameterBindingTarget target,
                        final dev.turboism.sdk.cubism.id.ParameterBindingPointId pointId) {}

                @Override
                public void unbind(final ParameterBindingTarget target) {}
            };
        }

        @Override
        public void update() {}
    }

    private static final class TestParameters implements Parameters {
        final List<Parameter> list = new ArrayList<>();

        @Override
        public List<Parameter> all() {
            return list;
        }

        @Override
        public Parameter find(final ParameterId id) {
            return list.stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
        }

        @Override
        public Parameter create(
                final ParameterDefinition definition,
                final Optional<dev.turboism.sdk.cubism.id.ParameterGroupId> folderId) {
            final TestParameter p = new TestParameter(definition);
            list.add(p);
            return p;
        }
    }

    private static final class TestParameter implements Parameter {
        private final ParameterDefinition def;
        private float value;

        TestParameter(final ParameterDefinition def) {
            this.def = def;
            this.value = def.defaultValue();
        }

        @Override
        public ParameterId id() {
            return def.id();
        }

        @Override
        public ParameterType type() {
            return def.type();
        }

        @Override
        public float getValue() {
            return value;
        }

        @Override
        public void setValue(final float value) {
            this.value = value;
        }

        @Override
        public float getMinimumValue() {
            return def.minimumValue();
        }

        @Override
        public float getMaximumValue() {
            return def.maximumValue();
        }

        @Override
        public float getDefaultValue() {
            return def.defaultValue();
        }
    }

    private static final class TestDrawable implements Drawable {
        private final ArtMeshId id;
        private float opacity = 1.0f;

        TestDrawable(final ArtMeshId id) {
            this.id = id;
        }

        @Override
        public ArtMeshId id() {
            return id;
        }

        @Override
        public float getOpacity() {
            return opacity;
        }

        @Override
        public void setOpacity(final float opacity) {
            this.opacity = opacity;
        }

        @Override
        public byte constantFlag() {
            return 0;
        }

        @Override
        public byte dynamicFlag() {
            return 0;
        }

        @Override
        public BlendMode blendMode() {
            return BlendMode.NORMAL;
        }

        @Override
        public int textureIndex() {
            return 0;
        }

        @Override
        public int drawOrder() {
            return 500;
        }

        @Override
        public int renderOrder() {
            return 0;
        }

        @Override
        public IntSequence masks() {
            return null;
        }

        @Override
        public FloatSequence vertexPositions() {
            return null;
        }

        @Override
        public FloatSequence vertexUvs() {
            return null;
        }

        @Override
        public IntSequence indices() {
            return null;
        }

        @Override
        public Color multiplyColor() {
            return null;
        }

        @Override
        public Color screenColor() {
            return null;
        }

        @Override
        public int parentPartIndex() {
            return 0;
        }

        @Override
        public int parentDeformerIndex() {
            return 0;
        }

        @Override
        public IntSequence parameters() {
            return null;
        }
    }
}
