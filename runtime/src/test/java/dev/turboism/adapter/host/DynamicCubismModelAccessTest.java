package dev.turboism.adapter.host;

import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterDefinition;
import dev.turboism.sdk.cubism.model.ParameterType;
import dev.turboism.sdk.cubism.model.Parameters;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicCubismModelAccessTest {

    @Test
    void replacementInvalidatesModelCollectionsAndChildren() {
        DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        access.connect(modelAccess("model-a", 1.0F));

        CubismModel staleModel = access.active();
        Parameters staleParameters = staleModel.parameters();
        Parameter staleParameter = staleParameters.find(new ParameterId("ParamA"));
        assertEquals(1.0F, staleParameter.getValue());

        access.connect(modelAccess("model-b", 2.0F));

        assertThrows(IllegalStateException.class, staleModel::id);
        assertThrows(IllegalStateException.class, staleParameters::all);
        assertThrows(IllegalStateException.class, staleParameter::getValue);
        assertEquals(new ModelId("model-b"), access.active().id());
    }

    @Test
    void sessionWrappersPreserveParameterMetadataAndGuardItByGeneration() {
        final DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        final Parameter delegate = new Parameter() {
            @Override public ParameterId id() { return new ParameterId("ParamCheek"); }
            @Override public Optional<String> name() { return Optional.of("Cheek"); }
            @Override public ParameterType type() { return ParameterType.BLEND_SHAPE; }
            @Override public Optional<Boolean> repeat() { return Optional.of(false); }
            @Override public Optional<Boolean> combined() { return Optional.of(true); }
            @Override public Optional<ParameterId> combinedWith() {
                return Optional.of(new ParameterId("ParamSmile"));
            }
            @Override public float getValue() { return 0.5F; }
            @Override public float getMinimumValue() { return 0.0F; }
            @Override public float getMaximumValue() { return 1.0F; }
            @Override public float getDefaultValue() { return 0.0F; }
            @Override public void setValue(final float value) { throw unsupported(); }
        };
        access.connect(() -> model("model-a", delegate));

        final Parameter parameter = access.active().parameters().find(new ParameterId("ParamCheek"));
        assertEquals(Optional.of("Cheek"), parameter.name());
        assertEquals(ParameterType.BLEND_SHAPE, parameter.type());
        assertEquals(Optional.of(false), parameter.repeat());
        assertEquals(Optional.of(true), parameter.combined());
        assertEquals(Optional.of(new ParameterId("ParamSmile")), parameter.combinedWith());

        access.deactivate();
        assertThrows(IllegalStateException.class, parameter::name);
        assertThrows(IllegalStateException.class, parameter::type);
        assertThrows(IllegalStateException.class, parameter::repeat);
        assertThrows(IllegalStateException.class, parameter::combined);
        assertThrows(IllegalStateException.class, parameter::combinedWith);
        assertThrows(
            IllegalStateException.class,
            () -> parameter.combineWith(new ParameterId("ParamSmile"))
        );
        assertThrows(IllegalStateException.class, parameter::uncombine);
    }

    @Test
    void resetIsForwardedOnlyWhileTheSessionGenerationIsCurrent() {
        final DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        final float[] value = {0.75F};
        final Parameter delegate = new Parameter() {
            @Override public ParameterId id() { return new ParameterId("ParamA"); }
            @Override public float getValue() { return value[0]; }
            @Override public float getMinimumValue() { return -1.0F; }
            @Override public float getMaximumValue() { return 1.0F; }
            @Override public float getDefaultValue() { return -0.25F; }
            @Override public void setValue(final float nextValue) { value[0] = nextValue; }
        };
        access.connect(() -> model("model-a", delegate));
        final Parameter parameter = access.active().parameters().find(new ParameterId("ParamA"));

        parameter.resetToDefault();
        assertEquals(-0.25F, value[0]);

        value[0] = 0.5F;
        access.connect(modelAccess("model-b", 2.0F));
        assertThrows(IllegalStateException.class, parameter::resetToDefault);
        assertEquals(0.5F, value[0]);
    }

    @Test
    void definitionUpdateIsForwardedOnlyWhileTheSessionGenerationIsCurrent() {
        final DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        final AtomicReference<ParameterDefinition> updated = new AtomicReference<>();
        final Parameter delegate = new Parameter() {
            @Override public ParameterId id() { return new ParameterId("ParamA"); }
            @Override public float getValue() { return 0.0F; }
            @Override public float getMinimumValue() { return -1.0F; }
            @Override public float getMaximumValue() { return 1.0F; }
            @Override public float getDefaultValue() { return 0.0F; }
            @Override public void setValue(final float value) { throw unsupported(); }
            @Override public void updateDefinition(final ParameterDefinition definition) {
                updated.set(definition);
            }
        };
        access.connect(() -> model("model-a", delegate));
        final Parameter parameter = access.active().parameters().find(new ParameterId("ParamA"));
        final ParameterDefinition definition = new ParameterDefinition(
            new ParameterId("ParamRenamed"),
            "Renamed",
            -2.0F,
            0.0F,
            2.0F,
            ParameterType.NORMAL,
            false
        );

        parameter.updateDefinition(definition);
        assertEquals(definition, updated.get());

        access.connect(modelAccess("model-b", 2.0F));
        assertThrows(IllegalStateException.class, () -> parameter.updateDefinition(definition));
        assertEquals(definition, updated.get());
    }

    @Test
    void rapidReplacementAndDeactivationInvalidateEveryPriorGeneration() {
        DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        java.util.List<CubismModel> staleModels = new java.util.ArrayList<>();
        java.util.List<Parameter> staleParameters = new java.util.ArrayList<>();

        for (int generation = 0; generation < 200; generation++) {
            access.connect(modelAccess("model-" + generation, generation));
            CubismModel model = access.active();
            staleModels.add(model);
            staleParameters.add(model.parameters().find(new ParameterId("ParamA")));
            assertEquals(new ModelId("model-" + generation), model.id());
        }
        access.deactivate();

        for (CubismModel model : staleModels) {
            assertThrows(IllegalStateException.class, model::id);
        }
        for (Parameter parameter : staleParameters) {
            assertThrows(IllegalStateException.class, parameter::getValue);
        }
        assertThrows(IllegalStateException.class, access::active);
    }

    @Test
    void replacementDuringDelegateReadCannotReturnAnApparentlyCurrentValue() throws Exception {
        DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        access.connect(() -> model("model-a", new Parameter() {
            @Override public ParameterId id() { return new ParameterId("ParamA"); }
            @Override public float getValue() {
                entered.countDown();
                await(release);
                return 1.0F;
            }
            @Override public float getMinimumValue() { return 0.0F; }
            @Override public float getMaximumValue() { return 2.0F; }
            @Override public float getDefaultValue() { return 1.0F; }
            @Override public void setValue(final float value) { throw unsupported(); }
        }));
        Parameter stale = access.active().parameters().find(new ParameterId("ParamA"));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try {
                stale.getValue();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });

        reader.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        CountDownLatch replacementFinished = new CountDownLatch(1);
        Thread replacement = new Thread(() -> {
            access.connect(modelAccess("model-b", 2.0F));
            replacementFinished.countDown();
        });
        replacement.start();
        assertTrue(
            !replacementFinished.await(100, TimeUnit.MILLISECONDS),
            "replacement must wait for the in-flight delegate read"
        );
        release.countDown();
        reader.join(5_000);
        replacement.join(5_000);

        assertEquals(null, failure.get());
        assertEquals(new ModelId("model-b"), access.active().id());
    }

    private static CubismModelAccess modelAccess(final String id, final float value) {
        return () -> model(id, parameter(value));
    }

    private static CubismModel model(final String id, final Parameter parameter) {
        return new CubismModel() {
            @Override public ModelId id() { return new ModelId(id); }
            @Override public Parameters parameters() {
                return new Parameters() {
                    @Override public List<Parameter> all() { return List.of(parameter); }
                    @Override public Parameter find(final ParameterId parameterId) {
                        if (!parameter.id().equals(parameterId)) {
                            throw new java.util.NoSuchElementException();
                        }
                        return parameter;
                    }
                };
            }
            @Override public dev.turboism.sdk.cubism.model.Parts parts() { throw unsupported(); }
            @Override public dev.turboism.sdk.cubism.model.Drawables drawables() { throw unsupported(); }
            @Override public dev.turboism.sdk.cubism.model.Deformers deformers() { throw unsupported(); }
            @Override public dev.turboism.sdk.cubism.model.Glues glues() { throw unsupported(); }
            @Override public void update() { throw unsupported(); }
        };
    }

    private static Parameter parameter(final float value) {
        return new Parameter() {
            @Override public ParameterId id() { return new ParameterId("ParamA"); }
            @Override public float getValue() { return value; }
            @Override public float getMinimumValue() { return 0.0F; }
            @Override public float getMaximumValue() { return 2.0F; }
            @Override public float getDefaultValue() { return 1.0F; }
            @Override public void setValue(final float ignored) { throw unsupported(); }
        };
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException();
    }

    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
