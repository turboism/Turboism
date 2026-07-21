package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.SelectionSnapshot;
import dev.turboism.sdk.cubism.callback.AfterSetParameterValue;
import dev.turboism.sdk.cubism.callback.BeforeSetParameterValue;
import dev.turboism.sdk.cubism.callback.CubismCallbacks;
import dev.turboism.sdk.cubism.callback.OnParameterValueChanged;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.transaction.TransactionManager;
import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedCubismModelApiContractTest {

    @Test
    void facadeExposesSourceCompatibleUnifiedEntryPoints() throws Exception {
        final Method model = CubismFacade.class.getMethod("model");
        final Method callbacks = CubismFacade.class.getMethod("callbacks");

        assertEquals(CubismModelAccess.class, model.getReturnType());
        assertEquals(CubismCallbacks.class, callbacks.getReturnType());
        assertTrue(model.isDefault());
        assertTrue(callbacks.isDefault());
        assertFalseAbstract(model);
        assertFalseAbstract(callbacks);
    }

    @Test
    void sdkOnlyConsumerUsesNaturalModelAndLifecycleMethods() {
        final RecordingCallbacks callbacks = new RecordingCallbacks();
        final FakeParameter parameter = new FakeParameter(
            new ParameterId("ParamAngleX"),
            callbacks
        );
        final CubismFacade facade = new FakeFacade(
            new FakeModel(parameter),
            callbacks
        );
        final List<String> events = new ArrayList<>();

        callbacks.beforeSetParameterValue((target, value) -> value * 0.5f);
        callbacks.beforeSetParameterValue((target, value) -> Math.min(value, 20.0f));
        callbacks.onParameterValueChanged((target, oldValue, newValue) ->
            events.add("on:" + oldValue + "->" + newValue)
        );
        callbacks.afterSetParameterValue((target, value) ->
            events.add("after:" + value)
        );

        final Parameter selected = facade
            .model()
            .active()
            .parameters()
            .find(new ParameterId("ParamAngleX"));

        selected.setValue(100.0f);
        assertEquals(20.0f, selected.getValue());
        assertEquals(List.of("on:0.0->20.0", "after:20.0"), events);

        events.clear();
        selected.setValue(40.0f);
        assertEquals(List.of("after:20.0"), events);
    }

    @Test
    void modelCollectionsUseDirectFindAndImmutableSequences() {
        final FakeParameter parameter = new FakeParameter(
            new ParameterId("ParamAngleX"),
            new RecordingCallbacks()
        );
        final CubismModel model = new FakeModel(parameter);

        assertEquals(1, model.parameters().all().size());
        assertThrows(
            UnsupportedOperationException.class,
            () -> model.parameters().all().add(parameter)
        );
        assertThrows(
            NoSuchElementException.class,
            () -> model.parameters().find(new ParameterId("Missing"))
        );

        final FloatSequence floats = sequence(1.0f, 2.0f);
        final IntSequence ints = sequence(1, 2, 3);
        assertEquals(2, floats.size());
        assertEquals(2.0f, floats.get(1));
        assertEquals(3, ints.size());
        assertEquals(3, ints.get(2));
        assertThrows(IndexOutOfBoundsException.class, () -> floats.get(2));
    }

    private static void assertFalseAbstract(final Method method) {
        assertTrue(!Modifier.isAbstract(method.getModifiers()));
    }

    private static FloatSequence sequence(final float... values) {
        final float[] copy = values.clone();
        return new FloatSequence() {
            @Override
            public int size() {
                return copy.length;
            }

            @Override
            public float get(final int index) {
                return copy[index];
            }
        };
    }

    private static IntSequence sequence(final int... values) {
        final int[] copy = values.clone();
        return new IntSequence() {
            @Override
            public int size() {
                return copy.length;
            }

            @Override
            public int get(final int index) {
                return copy[index];
            }
        };
    }

    private static final class FakeParameter implements Parameter {
        private final ParameterId id;
        private final RecordingCallbacks callbacks;
        private float value;

        private FakeParameter(
            final ParameterId id,
            final RecordingCallbacks callbacks
        ) {
            this.id = id;
            this.callbacks = callbacks;
        }

        @Override
        public ParameterId id() {
            return id;
        }

        @Override
        public float getValue() {
            return value;
        }

        @Override
        public float getMinimumValue() {
            return -30.0f;
        }

        @Override
        public float getMaximumValue() {
            return 30.0f;
        }

        @Override
        public float getDefaultValue() {
            return 0.0f;
        }

        @Override
        public void setValue(final float requestedValue) {
            float finalValue = requestedValue;
            for (BeforeSetParameterValue callback : callbacks.before) {
                finalValue = callback.beforeSetParameterValue(this, finalValue);
            }
            final float oldValue = value;
            value = finalValue;
            if (Float.compare(oldValue, value) != 0) {
                callbacks.on.forEach(callback ->
                    callback.onParameterValueChanged(this, oldValue, value)
                );
            }
            callbacks.after.forEach(callback ->
                callback.afterSetParameterValue(this, value)
            );
        }
    }

    private static final class FakeModel implements CubismModel {
        private final Parameters parameters;

        private FakeModel(final Parameter parameter) {
            this.parameters = new Parameters() {
                private final List<Parameter> all = List.of(parameter);

                @Override
                public List<Parameter> all() {
                    return all;
                }

                @Override
                public Parameter find(final ParameterId id) {
                    return all.stream()
                        .filter(candidate -> candidate.id().equals(id))
                        .findFirst()
                        .orElseThrow(NoSuchElementException::new);
                }
            };
        }

        @Override
        public ModelId id() {
            return new ModelId("model");
        }

        @Override
        public Parameters parameters() {
            return parameters;
        }

        @Override
        public Parts parts() {
            return new Parts() {
                @Override public List<Part> all() { return List.of(); }
                @Override public Part find(final PartId id) {
                    throw new NoSuchElementException();
                }
            };
        }

        @Override
        public Drawables drawables() {
            return new Drawables() {
                @Override public List<Drawable> all() { return List.of(); }
                @Override public Drawable find(final ArtMeshId id) {
                    throw new NoSuchElementException();
                }
            };
        }

        @Override
        public Deformers deformers() {
            return new Deformers() {
                @Override public List<Deformer> all() { return List.of(); }
                @Override public Deformer find(final DeformerId id) {
                    throw new NoSuchElementException();
                }
            };
        }

        @Override
        public Glues glues() {
            return new Glues() {
                @Override public List<Glue> all() { return List.of(); }
                @Override public Glue find(final GlueId id) {
                    throw new NoSuchElementException();
                }
            };
        }

        @Override
        public void update() {
        }
    }

    private static final class RecordingCallbacks implements CubismCallbacks {
        private final List<BeforeSetParameterValue> before = new ArrayList<>();
        private final List<OnParameterValueChanged> on = new ArrayList<>();
        private final List<AfterSetParameterValue> after = new ArrayList<>();

        @Override
        public Registration beforeSetParameterValue(
            final BeforeSetParameterValue callback
        ) {
            before.add(callback);
            return remove(before, callback);
        }

        @Override
        public Registration onParameterValueChanged(
            final OnParameterValueChanged callback
        ) {
            on.add(callback);
            return remove(on, callback);
        }

        @Override
        public Registration afterSetParameterValue(
            final AfterSetParameterValue callback
        ) {
            after.add(callback);
            return remove(after, callback);
        }

        private static <T> Registration remove(
            final List<T> values,
            final T value
        ) {
            return () -> values.remove(value);
        }
    }

    private static final class FakeFacade implements CubismFacade {
        private final CubismModel model;
        private final CubismCallbacks callbacks;

        private FakeFacade(
            final CubismModel model,
            final CubismCallbacks callbacks
        ) {
            this.model = model;
            this.callbacks = callbacks;
        }

        @Override
        public CubismRuntimeSnapshot runtime() {
            return new CubismRuntimeSnapshot(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                new SelectionSnapshot(
                    List.of(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of()
            );
        }

        @Override public Optional<ProjectSnapshot> activeProject() {
            return Optional.empty();
        }

        @Override public Optional<DocumentSnapshot> activeDocument() {
            return Optional.empty();
        }

        @Override public Optional<ModelSnapshot> activeModel() {
            return Optional.empty();
        }

        @Override public boolean isHostPresent() {
            return true;
        }

        @Override public CubismModelAccess model() {
            return () -> model;
        }

        @Override public CubismCallbacks callbacks() {
            return callbacks;
        }

        @Override public TransactionManager transactionManager() {
            return (context, documentId) -> {
                throw new UnsupportedOperationException();
            };
        }
    }
}
