package dev.turboism.adapter.cubism.core;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.GlueId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Canvas;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.FloatSequence;
import dev.turboism.sdk.cubism.model.Glue;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterType;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.Parts;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoreEditorCubismModelAccessTest {

    @Test
    void combinesMatchingModelSourcesWithCoreEvaluatedReadsAndEditorWrites() {
        final Fixture editor = Fixture.editor("model-a", 11.0F, 0.25F);
        final Fixture core = Fixture.core("model-a", 22.0F, 0.75F);

        final CubismModel model = new CoreEditorCubismModelAccess(
            new FixedAccess(editor.model),
            new FixedAccess(core.model)
        ).active();

        assertEquals(new ModelId("model-a"), model.id());
        assertEquals(1000.0F, model.canvas().widthPixels());
        assertEquals(22.0F, model.parameters().find(new ParameterId("ParamAngleX")).getValue());
        assertEquals(0.75F, model.parts().find(new PartId("PartRoot")).getOpacity());
        assertEquals(BlendMode.ADDITIVE, model.drawables().find(new ArtMeshId("Mesh")).blendMode());
        assertEquals(-1, model.deformers().find(new DeformerId("Warp")).parentDeformerIndex());
        assertEquals(1, model.glues().find(new GlueId("Glue")).drawableB());

        final Parameter parameter = model.parameters().find(new ParameterId("ParamAngleX"));
        parameter.setValue(19.0F);
        assertEquals(19.0F, editor.parameter.value);
        assertEquals(22.0F, core.parameter.value);
        assertSame(editor.parameter, editor.lastParameterWrite);
    }

    @Test
    void rejectsDifferentModelIdsBeforeExposingJoinedModel() {
        final Fixture editor = Fixture.editor("model-a", 11.0F, 0.25F);
        final Fixture core = Fixture.core("model-b", 22.0F, 0.75F);

        assertThrows(IllegalStateException.class, () -> new CoreEditorCubismModelAccess(
            new FixedAccess(editor.model),
            new FixedAccess(core.model)
        ).active());
    }

    @Test
    void rejectsMissingStableIdentifierBeforeExposingJoinedModel() {
        final Fixture editor = Fixture.editor("model-a", 11.0F, 0.25F);
        final Fixture core = Fixture.core("model-a", 22.0F, 0.75F);
        final CubismModel missingDrawable = new TestModel(
            "model-a",
            List.of(core.parameter),
            List.of(new TestPart(0.75F, value -> { })),
            List.of(),
            List.of(new TestDeformer()),
            List.of(new TestGlue())
        );

        assertThrows(IllegalStateException.class, () -> new CoreEditorCubismModelAccess(
            new FixedAccess(editor.model),
            new FixedAccess(missingDrawable)
        ).active());
    }

    @Test
    void rejectsStaleJoinedObjectsAndTheirCanvas() {
        final Fixture editor = Fixture.editor("model-a", 11.0F, 0.25F);
        final Fixture core = Fixture.core("model-a", 22.0F, 0.75F);
        final CubismModel model = new CoreEditorCubismModelAccess(
            new FixedAccess(editor.model),
            new FixedAccess(core.model)
        ).active();
        final Canvas canvas = model.canvas();

        ((TestModel) core.model).stale = true;

        assertThrows(IllegalStateException.class, model::id);
        assertThrows(IllegalStateException.class, canvas::widthPixels);
    }

    @Test
    void failsClosedWhenADelegateCannotActivate() {
        final Fixture editor = Fixture.editor("model-a", 11.0F, 0.25F);
        final CubismModelAccess unavailableCore = new CubismModelAccess() {
            @Override public CubismModel active() {
                throw new IllegalStateException("core unavailable");
            }
        };

        assertThrows(IllegalStateException.class, () -> new CoreEditorCubismModelAccess(
            new FixedAccess(editor.model),
            unavailableCore
        ).active());
    }

    private static final class FixedAccess implements CubismModelAccess {
        private final CubismModel model;

        private FixedAccess(final CubismModel model) {
            this.model = model;
        }

        @Override
        public CubismModel active() {
            return model;
        }
    }

    private static final class Fixture {
        private final TestParameter parameter;
        private final CubismModel model;
        private Parameter lastParameterWrite;

        private Fixture(final TestParameter parameter, final CubismModel model) {
            this.parameter = parameter;
            this.model = model;
        }

        private static Fixture editor(final String id, final float parameterValue, final float partOpacity) {
            final TestParameter parameter = new TestParameter(parameterValue, value -> { });
            final TestPart part = new TestPart(partOpacity, value -> { });
            final TestDrawable drawable = new TestDrawable();
            final TestDeformer deformer = new TestDeformer();
            final TestModel model = new TestModel(id, parameter, part, drawable, deformer);
            final Fixture fixture = new Fixture(parameter, model);
            parameter.onWrite = value -> {
                parameter.value = value;
                fixture.lastParameterWrite = parameter;
            };
            return fixture;
        }

        private static Fixture core(final String id, final float parameterValue, final float partOpacity) {
            final TestParameter parameter = new TestParameter(parameterValue, value -> { });
            return new Fixture(
                parameter,
                new TestModel(id, parameter, new TestPart(partOpacity, value -> { }), new TestDrawable(), new TestDeformer())
            );
        }
    }

    private static final class TestModel implements CubismModel {
        private final ModelId id;
        private final Parameters parameters;
        private final Parts parts;
        private final Drawables drawables;
        private final Deformers deformers;
        private final Glues glues;
        private boolean stale;

        private TestModel(
            final String id,
            final TestParameter parameter,
            final TestPart part,
            final TestDrawable drawable,
            final TestDeformer deformer
        ) {
            this(
                id,
                List.of(parameter),
                List.of(part),
                List.of(drawable),
                List.of(deformer),
                List.of(new TestGlue())
            );
        }

        private TestModel(
            final String id,
            final List<? extends Parameter> parameters,
            final List<? extends Part> parts,
            final List<? extends Drawable> drawables,
            final List<? extends Deformer> deformers,
            final List<? extends Glue> glues
        ) {
            this.id = new ModelId(id);
            final List<Parameter> parameterValues = List.copyOf(parameters);
            final List<Part> partValues = List.copyOf(parts);
            final List<Drawable> drawableValues = List.copyOf(drawables);
            final List<Deformer> deformerValues = List.copyOf(deformers);
            final List<Glue> glueValues = List.copyOf(glues);
            this.parameters = new Parameters() {
                @Override public List<Parameter> all() { return parameterValues; }
                @Override public Parameter find(final ParameterId id) { return parameterValues.get(0); }
            };
            this.parts = new Parts() {
                @Override public List<Part> all() { return partValues; }
                @Override public Part find(final PartId id) { return partValues.get(0); }
            };
            this.drawables = new Drawables() {
                @Override public List<Drawable> all() { return drawableValues; }
                @Override public Drawable find(final ArtMeshId id) { return drawableValues.get(0); }
            };
            this.deformers = new Deformers() {
                @Override public List<Deformer> all() { return deformerValues; }
                @Override public Deformer find(final DeformerId id) { return deformerValues.get(0); }
            };
            this.glues = new Glues() {
                @Override public List<Glue> all() { return glueValues; }
                @Override public Glue find(final GlueId id) { return glueValues.get(0); }
            };
        }

        @Override public ModelId id() {
            if (stale) throw new IllegalStateException("stale model");
            return id;
        }
        @Override public Canvas canvas() { return new Canvas() {
            @Override public float widthPixels() { return 1000.0F; }
            @Override public float heightPixels() { return 500.0F; }
            @Override public float originXPixels() { return 500.0F; }
            @Override public float originYPixels() { return 250.0F; }
            @Override public float pixelsPerUnit() { return 100.0F; }
        }; }
        @Override public Parameters parameters() { return parameters; }
        @Override public Parts parts() { return parts; }
        @Override public Drawables drawables() { return drawables; }
        @Override public Deformers deformers() { return deformers; }
        @Override public Glues glues() { return glues; }
        @Override public void update() { }
    }

    private static final class TestParameter implements Parameter {
        private float value;
        private java.util.function.Consumer<Float> onWrite;

        private TestParameter(final float value, final java.util.function.Consumer<Float> onWrite) {
            this.value = value;
            this.onWrite = onWrite;
        }

        @Override public ParameterId id() { return new ParameterId("ParamAngleX"); }
        @Override public Optional<String> name() { return Optional.of("Angle X"); }
        @Override public ParameterType type() { return ParameterType.NORMAL; }
        @Override public float getValue() { return value; }
        @Override public float getMinimumValue() { return -30.0F; }
        @Override public float getMaximumValue() { return 30.0F; }
        @Override public float getDefaultValue() { return 0.0F; }
        @Override public void setValue(final float value) { onWrite.accept(value); }
    }

    private static final class TestPart implements Part {
        private float opacity;
        private final java.util.function.Consumer<Float> onWrite;

        private TestPart(final float opacity, final java.util.function.Consumer<Float> onWrite) {
            this.opacity = opacity;
            this.onWrite = onWrite;
        }

        @Override public PartId id() { return new PartId("PartRoot"); }
        @Override public void setName(final String name) { }
        @Override public float getOpacity() { return opacity; }
        @Override public int parentIndex() { return -1; }
        @Override public void setOpacity(final float opacity) { this.opacity = opacity; onWrite.accept(opacity); }
    }

    private static final class TestDrawable implements Drawable {
        @Override public ArtMeshId id() { return new ArtMeshId("Mesh"); }
        @Override public byte constantFlag() { return 1; }
        @Override public byte dynamicFlag() { return 2; }
        @Override public BlendMode blendMode() { return BlendMode.ADDITIVE; }
        @Override public int textureIndex() { return 0; }
        @Override public int drawOrder() { return 3; }
        @Override public int renderOrder() { return 4; }
        @Override public float getOpacity() { return 1.0F; }
        @Override public IntSequence masks() { return ints(); }
        @Override public FloatSequence vertexPositions() { return floats(); }
        @Override public FloatSequence vertexUvs() { return floats(); }
        @Override public IntSequence indices() { return ints(); }
        @Override public Color multiplyColor() { return new Color(1, 1, 1, 1); }
        @Override public Color screenColor() { return new Color(0, 0, 0, 1); }
        @Override public int parentPartIndex() { return 0; }
        @Override public int parentDeformerIndex() { return -1; }
        @Override public IntSequence parameters() { return ints(); }
    }

    private static final class TestDeformer implements Deformer {
        @Override public DeformerId id() { return new DeformerId("Warp"); }
        @Override public int parentDeformerIndex() { return -1; }
        @Override public IntSequence parameters() { return ints(); }
    }

    private static final class TestGlue implements Glue {
        @Override public GlueId id() { return new GlueId("Glue"); }
        @Override public int drawableA() { return 0; }
        @Override public int drawableB() { return 1; }
        @Override public IntSequence parameters() { return ints(); }
    }

    private static IntSequence ints() {
        return new IntSequence() {
            @Override public int size() { return 0; }
            @Override public int get(final int index) { throw new IndexOutOfBoundsException(index); }
        };
    }

    private static FloatSequence floats() {
        return new FloatSequence() {
            @Override public int size() { return 0; }
            @Override public float get(final int index) { throw new IndexOutOfBoundsException(index); }
        };
    }
}
