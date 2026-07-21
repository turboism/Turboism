package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.SelectionSnapshot;
import dev.turboism.sdk.cubism.callback.ModelHooks;
import dev.turboism.sdk.cubism.callback.ParameterHooks;
import dev.turboism.sdk.cubism.callback.PartHooks;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.transaction.TransactionManager;
import dev.turboism.sdk.plugin.TurboismPlugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedCubismModelApiContractTest {

    @Test
    void facadeExposesModelAccessWithoutCallbackRegistrationService() throws Exception {
        final Method model = CubismFacade.class.getMethod("model");

        assertEquals(CubismModelAccess.class, model.getReturnType());
        assertTrue(model.isDefault());
        assertFalseAbstract(model);
        assertThrows(NoSuchMethodException.class, () ->
            CubismFacade.class.getMethod("callbacks")
        );
    }

    @Test
    void cubismPluginAggregatesOverrideBasedHookDomains() {
        assertTrue(TurboismPlugin.class.isAssignableFrom(CubismPlugin.class));
        assertTrue(ParameterHooks.class.isAssignableFrom(CubismPlugin.class));
        assertTrue(PartHooks.class.isAssignableFrom(CubismPlugin.class));
        assertTrue(ModelHooks.class.isAssignableFrom(CubismPlugin.class));

        final CubismPlugin plugin = new CubismPlugin() { };
        assertEquals(12.0f, plugin.beforeSetParameterValue(null, 12.0f));
        assertEquals(0.5f, plugin.beforeSetPartOpacity(null, 0.5f));
        assertDoesNotThrow(() -> plugin.onParameterValueChanged(null, 0.0f, 1.0f));
        assertDoesNotThrow(() -> plugin.afterSetParameterValue(null, 1.0f));
        assertDoesNotThrow(() -> plugin.onPartOpacityChanged(null, 0.0f, 1.0f));
        assertDoesNotThrow(() -> plugin.afterSetPartOpacity(null, 1.0f));
        assertDoesNotThrow(() -> plugin.beforeUpdateModel(null));
        assertDoesNotThrow(() -> plugin.onModelUpdated(null));
        assertDoesNotThrow(() -> plugin.afterUpdateModel(null));
    }

    @Test
    void sdkOnlyConsumerUsesOverrideHooksInPluginLoadOrder() {
        final List<String> events = new ArrayList<>();
        final List<ParameterHooks> hooks = List.of(
            new HalvingPlugin(),
            new RecordingPlugin(events)
        );
        final FakeParameter parameter = new FakeParameter(
            new ParameterId("ParamAngleX"),
            hooks
        );
        final CubismFacade facade = new FakeFacade(new FakeModel(parameter));

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
        assertEquals(20.0f, selected.getValue());
        assertEquals(List.of("after:20.0"), events);
    }

    @Test
    void modelCollectionsUseDirectFindAndImmutableSequences() {
        final FakeParameter parameter = new FakeParameter(
            new ParameterId("ParamAngleX"),
            List.of()
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

    private static final class HalvingPlugin implements CubismPlugin {
        @Override
        public float beforeSetParameterValue(
            final Parameter parameter,
            final float value
        ) {
            return value * 0.5f;
        }
    }

    private static final class RecordingPlugin implements CubismPlugin {
        private final List<String> events;

        private RecordingPlugin(final List<String> events) {
            this.events = events;
        }

        @Override
        public float beforeSetParameterValue(
            final Parameter parameter,
            final float value
        ) {
            return Math.min(value, 20.0f);
        }

        @Override
        public void onParameterValueChanged(
            final Parameter parameter,
            final float oldValue,
            final float newValue
        ) {
            events.add("on:" + oldValue + "->" + newValue);
        }

        @Override
        public void afterSetParameterValue(
            final Parameter parameter,
            final float value
        ) {
            events.add("after:" + value);
        }
    }

    private static final class FakeParameter implements Parameter {
        private final ParameterId id;
        private final List<ParameterHooks> hooks;
        private float value;

        private FakeParameter(
            final ParameterId id,
            final List<ParameterHooks> hooks
        ) {
            this.id = id;
            this.hooks = List.copyOf(hooks);
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
            for (ParameterHooks hook : hooks) {
                finalValue = hook.beforeSetParameterValue(this, finalValue);
            }

            final float oldValue = value;
            value = finalValue;
            if (Float.compare(oldValue, value) != 0) {
                for (ParameterHooks hook : hooks) {
                    hook.onParameterValueChanged(this, oldValue, value);
                }
            }
            for (ParameterHooks hook : hooks) {
                hook.afterSetParameterValue(this, value);
            }
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

    private static final class FakeFacade implements CubismFacade {
        private final CubismModel model;

        private FakeFacade(final CubismModel model) {
            this.model = model;
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

        @Override public TransactionManager transactionManager() {
            return (context, documentId) -> {
                throw new UnsupportedOperationException();
            };
        }
    }
}
