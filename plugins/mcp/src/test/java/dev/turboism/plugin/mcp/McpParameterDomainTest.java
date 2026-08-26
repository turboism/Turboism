package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterBindingPointId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterBinding;
import dev.turboism.sdk.cubism.model.ParameterBindingBatchOperations;
import dev.turboism.sdk.cubism.model.ParameterBindingFamily;
import dev.turboism.sdk.cubism.model.ParameterBindingOperations;
import dev.turboism.sdk.cubism.model.ParameterBindingPoint;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.cubism.model.ParameterBindingTargetType;
import dev.turboism.sdk.cubism.model.ParameterBindingTransferPlan;
import dev.turboism.sdk.cubism.model.ParameterDefinition;
import dev.turboism.sdk.cubism.model.ParameterDefinitions;
import dev.turboism.sdk.cubism.model.ParameterType;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Parts;
import dev.turboism.sdk.cubism.transaction.TransactionManager;
import dev.turboism.sdk.permission.CubismPermissionException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class McpParameterDomainTest {

    @Test
    void parameterOperationsRunSequentiallyReturnActualStateAndKeepNativeBatchAtomic() {
        final FakeModel model = new FakeModel();
        model.add("ParamA", "A", 1, 0, 10, ParameterType.NORMAL);
        final McpParameterDomain domain = domain(model);

        final Map<String, Object> output = domain.call(McpParameterDomain.PARAMETERS_APPLY, Map.of(
            "operations", List.of(
                Map.of("operation", "set_value", "parameterId", "ParamA", "value", 4.5),
                Map.of("operation", "reset_default", "parameterId", "ParamA"),
                Map.of("operation", "create_many", "definitions", List.of(
                    definition("ParamB", "B", "normal"), definition("ParamC", "C", "normal")
                )),
                Map.of("operation", "remove_many", "parameterIds", List.of("ParamB", "ParamC"))
            )
        ));

        assertEquals(Boolean.TRUE, output.get("ok"));
        assertEquals(4, list(output.get("results")).size());
        assertEquals(1, model.createManyCalls);
        assertEquals(1, model.removeManyCalls);
        assertEquals(List.of("ParamA"), model.order);
        assertEquals(0f, model.values.get("ParamA").value);
        assertEquals(3, domain.resources().size() + domain.resourceTemplates().size());
        assertEquals(2, domain.tools().size());

        final Map<String, Object> resource = domain.read(McpParameterDomain.PARAMETERS_URI);
        assertEquals("ParamA", object(list(resource.get("parameters")).get(0)).get("id"));
    }

    @Test
    void updateDefinitionCanReplaceTheStableParameterIdAndReturnsTheReplacement() {
        final FakeModel model = new FakeModel();
        model.add("ParamOld", "Old", 1, 0, 10, ParameterType.NORMAL);
        final McpParameterDomain domain = domain(model);

        final Map<String, Object> output = domain.call(McpParameterDomain.PARAMETERS_APPLY, Map.of(
            "operations", List.of(Map.of(
                "operation", "update_definition",
                "parameterId", "ParamOld",
                "definition", definition("ParamNew", "New", "normal")
            ))
        ));

        assertEquals(Boolean.TRUE, output.get("ok"));
        final Map<String, Object> result = object(object(list(output.get("results")).get(0)).get("result"));
        assertEquals("ParamNew", result.get("id"));
        assertTrue(model.values.containsKey("ParamNew"));
        assertFalse(model.values.containsKey("ParamOld"));
        assertEquals(List.of("ParamNew"), model.order);
    }

    @Test
    void parameterResourceTemplatesReadDetailAndBindingsThroughCatalog() {
        final FakeModel model = new FakeModel();
        model.add("Param A+B", "Encoded", 2, 0, 10, ParameterType.NORMAL);
        final McpParameterDomain domain = domain(model);
        final McpResourceCatalog resources = domain.resourceCatalog();

        assertEquals(2, resources.templates().size());
        final List<Map<String, Object>> detail = resources.read(
            "turboism://active/model/parameters/Param%20A%2BB"
        );
        final Map<String, Object> content = object(detail.get(0));
        final Map<String, Object> payload = object(Json.parse(
            ((String) content.get("text")).getBytes(java.nio.charset.StandardCharsets.UTF_8)
        ));
        assertEquals("Param A+B", payload.get("id"));

        final List<Map<String, Object>> bindings = resources.read(
            "turboism://active/model/parameters/Param%20A%2BB/bindings"
        );
        assertEquals(0, list(object(Json.parse(
            ((String) object(bindings.get(0)).get("text"))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)
        )).get("bindings")).size());
    }

    @Test
    void parameterFailuresPreservePermissionUnavailableAndCancellationContracts() {
        final McpParameterDomain denied = domain(() -> {
            throw new CubismPermissionException("model read denied");
        });
        final Map<String, Object> deniedOutput = denied.call(
            McpParameterDomain.PARAMETERS_APPLY,
            Map.of("operations", List.of(Map.of(
                "operation", "set_value", "parameterId", "ParamA", "value", 1
            )))
        );
        assertFalse((Boolean) deniedOutput.get("ok"));
        assertEquals("PERMISSION_DENIED", object(deniedOutput.get("error")).get("code"));

        final McpParameterDomain unavailable = domain(() -> {
            throw new UnsupportedOperationException("active model unavailable");
        });
        final Map<String, Object> unavailableOutput = unavailable.call(
            McpParameterDomain.BINDINGS_APPLY,
            Map.of("operations", List.of(Map.of(
                "operation", "unbind",
                "parameterId", "ParamA",
                "target", Map.of("type", "art_mesh", "id", "ArtMesh1")
            )))
        );
        assertFalse((Boolean) unavailableOutput.get("ok"));
        assertEquals("UNAVAILABLE", object(unavailableOutput.get("error")).get("code"));

        final FakeModel model = new FakeModel();
        model.add("ParamA", "A", 1, 0, 10, ParameterType.NORMAL);
        model.values.get("ParamA").setFailure = new java.util.concurrent.CancellationException();
        final McpParameterDomain cancelled = domain(model);
        try {
            cancelled.call(McpParameterDomain.PARAMETERS_APPLY, Map.of(
                "operations", List.of(Map.of(
                    "operation", "set_value", "parameterId", "ParamA", "value", 2
                ))
            ));
            throw new AssertionError("CancellationException expected");
        } catch (java.util.concurrent.CancellationException expected) {
            // Cancellation is handled by the protocol, not collapsed into a batch failure.
        }
    }

    @Test
    void bindingOperationsRereadActualStateRejectBlendCrudAndStopOnError() {
        final FakeModel model = new FakeModel();
        model.add("ParamA", "A", 1, 0, 10, ParameterType.NORMAL);
        model.add("Morph", "Morph", 1, 0, 10, ParameterType.BLEND_SHAPE);
        final McpParameterDomain domain = domain(model);
        final Map<String, Object> target = Map.of("type", "art_mesh", "id", "ArtMesh1");

        final Map<String, Object> output = domain.call(McpParameterDomain.BINDINGS_APPLY, Map.of(
            "stopOnError", true,
            "operations", List.of(
                Map.of("operation", "bind", "parameterId", "ParamA", "target", target,
                    "points", List.of(Map.of("id", "point-1", "value", 2))),
                Map.of("operation", "create_point", "parameterId", "Morph", "target", target,
                    "point", Map.of("id", "point-2", "value", 3)),
                Map.of("operation", "unbind", "parameterId", "ParamA", "target", target)
            )
        ));

        assertFalse((Boolean) output.get("ok"));
        assertEquals(2, list(output.get("results")).size());
        final Map<String, Object> first = object(list(output.get("results")).get(0));
        assertTrue((Boolean) first.get("ok"));
        final Map<String, Object> binding = object(object(first.get("result")).get("binding"));
        assertEquals("ArtMesh1", object(binding.get("target")).get("id"));
        assertEquals("INVALID_ARGUMENT", object(object(list(output.get("results")).get(1)).get("error")).get("code"));
        assertEquals(1, model.bindCalls);
    }

    private static McpParameterDomain domain(final FakeModel model) {
        return domain(() -> model);
    }

    private static McpParameterDomain domain(final Supplier<CubismModel> model) {
        return new McpParameterDomain(new FakeFacade(model), new McpExecutionBridge(
            new dev.turboism.sdk.ui.UiScheduler() {
                @Override public dev.turboism.sdk.plugin.Registration runOnUiThread(
                    final Runnable work
                ) {
                    work.run();
                    return () -> { };
                }

                @Override public dev.turboism.sdk.plugin.Registration runOnUiThreadLater(
                    final Runnable work,
                    final java.time.Duration delay
                ) {
                    work.run();
                    return () -> { };
                }
            }
        ));
    }

    private static Map<String, Object> definition(final String id, final String name, final String type) {
        return Map.of("id", id, "name", name, "minimumValue", 0, "defaultValue", 0,
            "maximumValue", 10, "type", type, "repeat", false);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(final Object value) { return (Map<String, Object>) value; }

    @SuppressWarnings("unchecked")
    private static List<Object> list(final Object value) { return (List<Object>) value; }

    private static final class FakeFacade implements CubismFacade {
        private final Supplier<CubismModel> model;
        private FakeFacade(final Supplier<CubismModel> model) { this.model = model; }
        @Override public CubismRuntimeSnapshot runtime() { return null; }
        @Override public Optional<ProjectSnapshot> activeProject() { return Optional.empty(); }
        @Override public Optional<DocumentSnapshot> activeDocument() { return Optional.empty(); }
        @Override public Optional<ModelSnapshot> activeModel() { return Optional.empty(); }
        @Override public boolean isHostPresent() { return true; }
        @Override public TransactionManager transactionManager() { return null; }
        @Override public CubismModelAccess model() { return model::get; }
    }

    private static final class FakeModel implements CubismModel {
        private final LinkedHashMap<String, FakeParameter> values = new LinkedHashMap<>();
        private final List<String> order = new ArrayList<>();
        private int createManyCalls;
        private int removeManyCalls;
        private int bindCalls;

        void add(final String id, final String name, final float value, final float min, final float max, final ParameterType type) {
            values.put(id, new FakeParameter(this, new ParameterDefinition(new ParameterId(id), name, min, 0, max, type, false), value));
            order.add(id);
        }

        @Override public ModelId id() { return new ModelId("model"); }
        @Override public Parameters parameters() {
            return new Parameters() {
                @Override public List<Parameter> all() { return order.stream().map(values::get).map(value -> (Parameter) value).toList(); }
                @Override public Parameter find(final ParameterId id) {
                    final FakeParameter result = values.get(id.value());
                    if (result == null) throw new NoSuchElementException(id.value());
                    return result;
                }
                @Override public Parameter create(final ParameterDefinition definition) { return createOne(definition); }
                @Override public List<Parameter> createMany(final List<ParameterDefinition> definitions) {
                    createManyCalls++;
                    definitions.forEach(FakeModel.this::createOne);
                    return definitions.stream().map(definition -> find(definition.id())).toList();
                }
                @Override public Parameter copy(final ParameterId id) {
                    final FakeParameter source = (FakeParameter) find(id);
                    final String copyId = id.value() + "Copy";
                    return createOne(new ParameterDefinition(new ParameterId(copyId), source.definition.name(), source.definition.minimumValue(), source.definition.defaultValue(), source.definition.maximumValue(), source.definition.type(), source.definition.repeat()));
                }
                @Override public void remove(final ParameterId id) { values.remove(id.value()); order.remove(id.value()); }
                @Override public void removeMany(final List<ParameterId> ids) { removeManyCalls++; ids.forEach(this::remove); }
            };
        }
        private FakeParameter createOne(final ParameterDefinition definition) {
            if (values.containsKey(definition.id().value())) throw new IllegalArgumentException("duplicate");
            final FakeParameter created = new FakeParameter(this, definition, definition.defaultValue());
            values.put(definition.id().value(), created);
            order.add(definition.id().value());
            return created;
        }
        @Override public ParameterDefinitions parameterDefinitions() {
            return new ParameterDefinitions() {
                @Override public List<ParameterDefinition> all() { return order.stream().map(id -> values.get(id).definition).toList(); }
                @Override public ParameterDefinition find(final ParameterId id) { return ((FakeParameter) parameters().find(id)).definition; }
            };
        }
        @Override public ParameterBindingOperations parameterBindings(final ParameterId parameterId) {
            final FakeParameter parameter = (FakeParameter) parameters().find(parameterId);
            return new ParameterBindingOperations() {
                @Override public void bind(final ParameterBindingTarget target, final List<ParameterBindingPoint> points) {
                    bindCalls++;
                    parameter.bindings.put(target, new ParameterBinding(target, parameterId, ParameterBindingFamily.KEYFORM_GRID, points));
                }
                @Override public void createPoint(final ParameterBindingTarget target, final ParameterBindingPoint point) {
                    final ParameterBinding existing = parameter.bindings.get(target);
                    final List<ParameterBindingPoint> points = new ArrayList<>(existing == null ? List.of() : existing.points());
                    points.add(point);
                    bind(target, points);
                }
                @Override public void movePoint(final ParameterBindingTarget target, final ParameterBindingPointId pointId, final float value) { throw new UnsupportedOperationException(); }
                @Override public void deletePoint(final ParameterBindingTarget target, final ParameterBindingPointId pointId) { throw new UnsupportedOperationException(); }
                @Override public void unbind(final ParameterBindingTarget target) { parameter.bindings.remove(target); }
            };
        }
        @Override public ParameterBindingBatchOperations parameterBindingBatch() {
            return new ParameterBindingBatchOperations() {
                @Override public void invert(final List<ParameterBindingTarget> targets) { }
                @Override public void transfer(final ParameterBindingTransferPlan plan) { }
            };
        }
        @Override public Parts parts() { return null; }
        @Override public Drawables drawables() { return null; }
        @Override public Deformers deformers() { return null; }
        @Override public Glues glues() { return null; }
        @Override public void update() { }
    }

    private static final class FakeParameter implements Parameter {
        private final FakeModel owner;
        private ParameterDefinition definition;
        private float value;
        private RuntimeException setFailure;
        private final LinkedHashMap<ParameterBindingTarget, ParameterBinding> bindings = new LinkedHashMap<>();
        private FakeParameter(
            final FakeModel owner,
            final ParameterDefinition definition,
            final float value
        ) {
            this.owner = owner;
            this.definition = definition;
            this.value = value;
        }
        @Override public ParameterId id() { return definition.id(); }
        @Override public float getValue() { return value; }
        @Override public float getMinimumValue() { return definition.minimumValue(); }
        @Override public float getMaximumValue() { return definition.maximumValue(); }
        @Override public float getDefaultValue() { return definition.defaultValue(); }
        @Override public void setValue(final float value) {
            if (setFailure != null) throw setFailure;
            this.value = value;
        }
        @Override public ParameterType type() { return definition.type(); }
        @Override public List<ParameterBinding> getParameterBindings() { return List.copyOf(bindings.values()); }
        @Override public void updateDefinition(final ParameterDefinition definition) {
            final String oldId = this.definition.id().value();
            final String newId = definition.id().value();
            this.definition = definition;
            if (!oldId.equals(newId)) {
                final FakeParameter value = owner.values.remove(oldId);
                final int index = owner.order.indexOf(oldId);
                owner.values.put(newId, value);
                owner.order.set(index, newId);
            }
        }
    }
}
