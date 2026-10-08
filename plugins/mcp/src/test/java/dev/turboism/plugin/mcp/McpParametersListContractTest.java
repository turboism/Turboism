package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.turboism.sdk.cubism.ArtMeshSnapshot;
import dev.turboism.sdk.cubism.ClipMaskSnapshot;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.DeformerSnapshot;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelObjectSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ParameterSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.PsdDocumentSnapshot;
import dev.turboism.sdk.cubism.RenderStatusSnapshot;
import dev.turboism.sdk.cubism.SelectionSnapshot;
import dev.turboism.sdk.cubism.TextureAtlasSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.ModelObjectCreateRequest;
import dev.turboism.sdk.cubism.model.ModelObjectDeletePolicy;
import dev.turboism.sdk.cubism.model.ModelObjectDescriptor;
import dev.turboism.sdk.cubism.model.ModelObjectReference;
import dev.turboism.sdk.cubism.model.ModelObjectService;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Parts;
import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService;
import dev.turboism.sdk.cubism.service.query.HierarchyNode;
import dev.turboism.sdk.cubism.service.query.ModelHierarchy;
import dev.turboism.sdk.cubism.service.query.ModelHierarchyQueryService;
import dev.turboism.sdk.cubism.service.query.SelectionQueryService;
import dev.turboism.sdk.cubism.service.query.SelectionSummary;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.theme.ThemeStatusSnapshot;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.ui.appearance.PaletteEntry;
import dev.turboism.sdk.ui.appearance.model.ParameterAppearance;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Pins the wire contract of {@code turboism_parameters_list} across read-plane migrations. */
final class McpParametersListContractTest {

    @Test
    void parameterFieldsKeepNameTypeAndOrder() {
        final Map<String, Object> output = structured(parametersList(
                parameters -> parameters.put(parameter("ParamAngleX", "Angle X", 0.5, -30.0, 30.0, 0.0, true, false)),
                Map.of()));

        assertEquals(List.of("ok", "count", "parameters"), List.copyOf(output.keySet()));
        assertEquals(true, output.get("ok"));
        assertEquals(1, output.get("count"));
        final Map<String, Object> parameter =
                object(list(output.get("parameters")).get(0));
        assertEquals(
                List.of("id", "name", "currentValue", "minValue", "maxValue", "defaultValue", "visible", "editable"),
                List.copyOf(parameter.keySet()));
        assertEquals("ParamAngleX", parameter.get("id"));
        assertEquals("Angle X", parameter.get("name"));
        assertEquals(0.5, parameter.get("currentValue"));
        assertEquals(-30.0, parameter.get("minValue"));
        assertEquals(30.0, parameter.get("maxValue"));
        assertEquals(0.0, parameter.get("defaultValue"));
        assertEquals(true, parameter.get("visible"));
        assertEquals(false, parameter.get("editable"));
    }

    @Test
    void idFilterReturnsSingleParameterAndAbsentIdReturnsEmpty() {
        final Map<String, Object> found = structured(parametersList(
                parameters -> {
                    parameters.put(parameter("ParamAngleX", "Angle X", 0.5, -30.0, 30.0, 0.0, true, true));
                    parameters.put(parameter("ParamOpacity", "Opacity", 1.0, 0.0, 1.0, 1.0, false, true));
                },
                Map.of("id", "ParamOpacity")));
        assertEquals(1, found.get("count"));
        assertEquals(
                "ParamOpacity", object(list(found.get("parameters")).get(0)).get("id"));

        final Map<String, Object> missing = structured(parametersList(
                parameters -> parameters.put(parameter("ParamAngleX", "Angle X", 0.5, -30.0, 30.0, 0.0, true, true)),
                Map.of("id", "ParamMissing")));
        assertEquals(true, missing.get("ok"));
        assertEquals(0, missing.get("count"));
        assertEquals(List.of(), missing.get("parameters"));
    }

    @Test
    void nameFilterMatchesCaseInsensitiveSubstring() {
        final Map<String, Object> output = structured(parametersList(
                parameters -> {
                    parameters.put(parameter("ParamAngleX", "Angle X", 0.5, -30.0, 30.0, 0.0, true, true));
                    parameters.put(parameter("ParamOpacity", "Opacity", 1.0, 0.0, 1.0, 1.0, true, true));
                },
                Map.of("name", "angle")));

        assertEquals(1, output.get("count"));
        assertEquals(
                "ParamAngleX", object(list(output.get("parameters")).get(0)).get("id"));
    }

    @Test
    void emptyModelReportsOkWithZeroCount() {
        final Map<String, Object> output = structured(parametersList(ignored -> {}, Map.of()));

        assertEquals(true, output.get("ok"));
        assertEquals(0, output.get("count"));
        assertEquals(List.of(), output.get("parameters"));
    }

    @Test
    void unknownArgumentAndServiceFailureKeepTheErrorContract() {
        final Map<String, Object> invalidContent = structured(parametersList(ignored -> {}, Map.of("bogus", 1)));
        assertEquals(false, invalidContent.get("ok"));
        assertEquals("INVALID_ARGUMENT", object(invalidContent.get("error")).get("code"));

        final Map<String, Object> failed =
                parametersList(parameters -> parameters.failWith(new RuntimeException("boom")), Map.of());
        final Map<String, Object> failedContent = structured(failed);
        assertEquals(false, failedContent.get("ok"));
        assertEquals("FAILED", object(failedContent.get("error")).get("code"));
        assertEquals(Boolean.TRUE, failed.get("isError"));
    }

    @Test
    void permissionFailureReportsPermissionDenied() {
        final Map<String, Object> denied = parametersList(
                parameters -> parameters.failWith(new CubismPermissionException(
                        "Missing required permission turboism.cubism.parameter.read for parametersList")),
                Map.of());

        final Map<String, Object> content = structured(denied);
        assertEquals(false, content.get("ok"));
        assertEquals("PERMISSION_DENIED", object(content.get("error")).get("code"));
    }

    @Test
    void everyHostReadRunsInsideOneUiExecution() {
        // The immediate UI scheduler cannot expose off-UI host reads; guard every fake
        // accessor on a flag that is set only while a runOnUiThread body executes, and
        // count executions so split reads would show up as more than one dispatch.
        final AtomicInteger uiExecutions = new AtomicInteger();
        final AtomicBoolean insideUi = new AtomicBoolean();
        final UiScheduler counting = new UiScheduler() {
            @Override
            public Registration runOnUiThread(final Runnable work) {
                uiExecutions.incrementAndGet();
                insideUi.set(true);
                try {
                    work.run();
                } finally {
                    insideUi.set(false);
                }
                return () -> {};
            }

            @Override
            public Registration runOnUiThreadLater(final Runnable work, final Duration delay) {
                work.run();
                return () -> {};
            }
        };
        final Runnable onUi = () -> {
            if (!insideUi.get()) throw new AssertionError("host read outside the UI execution");
        };
        final FakeModel model = new FakeModel();
        model.put(parameter("ParamAngleX", "Angle X", 0.5, -30.0, 30.0, 0.0, true, true, onUi));
        model.put(parameter("ParamOpacity", "Opacity", 1.0, 0.0, 1.0, 1.0, false, true, onUi));

        final Map<String, Object> output = new McpTools(
                        emptyObjects(),
                        cubism(model, onUi),
                        emptyHierarchy(),
                        emptySelection(),
                        emptyRead(),
                        emptyClipMasks(),
                        silentLogger(),
                        counting)
                .call(McpTools.PARAMETERS_LIST, Map.of());

        final Map<String, Object> content = structured(output);
        assertEquals(true, content.get("ok"));
        assertEquals(2, content.get("count"));
        assertEquals(1, uiExecutions.get(), "model resolution, filtering and row mapping must share one UI execution");
    }

    @Test
    void aModelThatTurnsStaleMidListKeepsTheFailedErrorCode() {
        // A parameter whose appearance read fails as stale must surface through the same
        // FAILED contract the service exception path produced before the object API.
        final Map<String, Object> output = parametersList(
                parameters -> parameters.put(new Parameter() {
                    @Override
                    public ParameterId id() {
                        return new ParameterId("ParamStale");
                    }

                    @Override
                    public Optional<String> name() {
                        return Optional.of("Stale");
                    }

                    @Override
                    public float getValue() {
                        return 0.0F;
                    }

                    @Override
                    public float getMinimumValue() {
                        return 0.0F;
                    }

                    @Override
                    public float getMaximumValue() {
                        return 1.0F;
                    }

                    @Override
                    public float getDefaultValue() {
                        return 0.0F;
                    }

                    @Override
                    public void setValue(final float next) {}

                    @Override
                    public ParameterAppearance ui() {
                        throw new IllegalStateException("Model appearance facade is stale or unavailable.");
                    }
                }),
                Map.of());

        final Map<String, Object> content = structured(output);
        assertEquals(false, content.get("ok"));
        assertEquals("FAILED", object(content.get("error")).get("code"));
        assertEquals(Boolean.TRUE, output.get("isError"));
    }

    private static Map<String, Object> parametersList(
            final Consumer<FakeModel> values, final Map<String, Object> arguments) {
        final FakeModel model = new FakeModel();
        values.accept(model);
        return new McpTools(
                        emptyObjects(),
                        cubism(model),
                        emptyHierarchy(),
                        emptySelection(),
                        emptyRead(),
                        emptyClipMasks(),
                        silentLogger(),
                        immediateUi())
                .call(McpTools.PARAMETERS_LIST, arguments);
    }

    private static Parameter parameter(
            final String id,
            final String name,
            final double value,
            final double min,
            final double max,
            final double defaultValue,
            final boolean visible,
            final boolean editable) {
        return parameter(id, name, value, min, max, defaultValue, visible, editable, () -> {});
    }

    /** A parameter fake whose every accessor first runs {@code guard}. */
    private static Parameter parameter(
            final String id,
            final String name,
            final double value,
            final double min,
            final double max,
            final double defaultValue,
            final boolean visible,
            final boolean editable,
            final Runnable guard) {
        return new Parameter() {
            @Override
            public ParameterId id() {
                // Identity is a stable handle attribute, not a host observation.
                return new ParameterId(id);
            }

            @Override
            public Optional<String> name() {
                guard.run();
                return Optional.of(name);
            }

            @Override
            public float getValue() {
                guard.run();
                return (float) value;
            }

            @Override
            public float getMinimumValue() {
                guard.run();
                return (float) min;
            }

            @Override
            public float getMaximumValue() {
                guard.run();
                return (float) max;
            }

            @Override
            public float getDefaultValue() {
                guard.run();
                return (float) defaultValue;
            }

            @Override
            public void setValue(final float next) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ParameterAppearance ui() {
                guard.run();
                return new ParameterAppearance() {
                    @Override
                    public Optional<PaletteEntry> parameterPaletteEntry() {
                        guard.run();
                        return Optional.empty();
                    }

                    @Override
                    public Optional<Boolean> visible() {
                        guard.run();
                        return Optional.of(visible);
                    }

                    @Override
                    public Optional<Boolean> editable() {
                        guard.run();
                        return Optional.of(editable);
                    }
                };
            }
        };
    }

    private static Map<String, Object> structured(final Map<String, Object> result) {
        return object(result.get("structuredContent"));
    }

    private static Map<String, Object> object(final Object value) {
        return assertInstanceOf(Map.class, value);
    }

    private static List<Object> list(final Object value) {
        return assertInstanceOf(List.class, value);
    }

    private static final class FakeModel {
        private final java.util.LinkedHashMap<String, Parameter> values = new java.util.LinkedHashMap<>();
        private Throwable failure;

        void put(final Parameter value) {
            values.put(value.id().value(), value);
        }

        void failWith(final Throwable value) {
            failure = value;
        }

        private void check() {
            if (failure == null) return;
            if (failure instanceof RuntimeException error) throw error;
            throw new IllegalStateException(failure);
        }
    }

    private static CubismFacade cubism(final FakeModel model) {
        return cubism(model, () -> {});
    }

    /** A facade whose model access first runs {@code guard} on every host touch. */
    private static CubismFacade cubism(final FakeModel model, final Runnable guard) {
        return new CubismFacade() {
            @Override
            public dev.turboism.sdk.cubism.CubismRuntimeSnapshot runtime() {
                return null;
            }

            @Override
            public Optional<ProjectSnapshot> activeProject() {
                return Optional.empty();
            }

            @Override
            public Optional<DocumentSnapshot> activeDocument() {
                return Optional.empty();
            }

            @Override
            public Optional<ModelSnapshot> activeModel() {
                return Optional.empty();
            }

            @Override
            public boolean isHostPresent() {
                return true;
            }

            @Override
            public CubismModelAccess model() {
                return new CubismModelAccess() {
                    @Override
                    public CubismModel active() {
                        guard.run();
                        model.check();
                        final List<Parameter> values = List.copyOf(model.values.values());
                        return new CubismModel() {
                            @Override
                            public ModelId id() {
                                guard.run();
                                return new ModelId("model-1");
                            }

                            @Override
                            public Parameters parameters() {
                                guard.run();
                                return new Parameters() {
                                    @Override
                                    public List<Parameter> all() {
                                        guard.run();
                                        return values;
                                    }

                                    @Override
                                    public Parameter find(final ParameterId id) {
                                        guard.run();
                                        return values.stream()
                                                .filter(value -> value.id().equals(id))
                                                .findFirst()
                                                .orElseThrow();
                                    }
                                };
                            }

                            @Override
                            public Parts parts() {
                                throw new UnsupportedOperationException();
                            }

                            @Override
                            public Drawables drawables() {
                                throw new UnsupportedOperationException();
                            }

                            @Override
                            public Deformers deformers() {
                                throw new UnsupportedOperationException();
                            }

                            @Override
                            public Glues glues() {
                                throw new UnsupportedOperationException();
                            }

                            @Override
                            public void update() {}
                        };
                    }
                };
            }
        };
    }

    private static ModelObjectService emptyObjects() {
        return new ModelObjectService() {
            @Override
            public List<ModelObjectDescriptor> list() {
                return List.of();
            }

            @Override
            public ModelObjectDescriptor rename(final ModelObjectReference target, final String name) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ModelObjectDescriptor reparent(
                    final ModelObjectReference target, final ModelObjectReference parent, final int index) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ModelObjectDescriptor create(final ModelObjectCreateRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void delete(final ModelObjectReference target, final ModelObjectDeletePolicy policy) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static ModelHierarchyQueryService emptyHierarchy() {
        return new ModelHierarchyQueryService() {
            @Override
            public Optional<ModelHierarchy> currentHierarchy() {
                return Optional.empty();
            }

            @Override
            public List<HierarchyNode> childrenOf(final ModelObjectId id) {
                return List.of();
            }

            @Override
            public Optional<HierarchyNode> findNode(final ModelObjectId id) {
                return Optional.empty();
            }
        };
    }

    private static SelectionQueryService emptySelection() {
        return new SelectionQueryService() {
            @Override
            public SelectionSummary currentSelection() {
                return SelectionSummary.empty();
            }

            @Override
            public List<ModelObjectId> selectedIds(final HierarchyNode.Kind kind) {
                return List.of();
            }
        };
    }

    private static CubismReadCapabilityService emptyRead() {
        return new CubismReadCapabilityService() {
            @Override
            public Optional<ProjectSnapshot> activeProject() {
                return Optional.empty();
            }

            @Override
            public Optional<DocumentSnapshot> activeDocument() {
                return Optional.empty();
            }

            @Override
            public Optional<ModelSnapshot> activeModel() {
                return Optional.empty();
            }

            @Override
            public SelectionSnapshot selection() {
                return new SelectionSnapshot(List.of(), Optional.empty(), Optional.empty(), Optional.empty());
            }

            @Override
            public List<ParameterSnapshot> parameters() {
                return List.of();
            }

            @Override
            public List<ModelObjectSnapshot> modelObjects() {
                return List.of();
            }

            @Override
            public List<ArtMeshSnapshot> meshes() {
                return List.of();
            }

            @Override
            public List<DeformerSnapshot> deformers() {
                return List.of();
            }

            @Override
            public List<PsdDocumentSnapshot> psdDocuments() {
                return List.of();
            }

            @Override
            public List<ClipMaskSnapshot> clipMasks() {
                return List.of();
            }

            @Override
            public List<TextureAtlasSnapshot> textureAtlases() {
                return List.of();
            }

            @Override
            public Optional<RenderStatusSnapshot> renderStatus() {
                return Optional.empty();
            }

            @Override
            public Optional<WorkspaceSnapshot> workspace() {
                return Optional.empty();
            }

            @Override
            public Optional<ThemeStatusSnapshot> themeStatus() {
                return Optional.empty();
            }
        };
    }

    private static CubismClipMaskService emptyClipMasks() {
        return List::of;
    }

    private static UiScheduler immediateUi() {
        return new UiScheduler() {
            @Override
            public Registration runOnUiThread(final Runnable work) {
                work.run();
                return () -> {};
            }

            @Override
            public Registration runOnUiThreadLater(final Runnable work, final Duration delay) {
                work.run();
                return () -> {};
            }
        };
    }

    private static PluginLogger silentLogger() {
        return new PluginLogger() {
            @Override
            public void debug(final String message) {}

            @Override
            public void info(final String message) {}

            @Override
            public void warn(final String message) {}

            @Override
            public void error(final String message) {}

            @Override
            public void error(final String message, final Throwable throwable) {}
        };
    }
}
