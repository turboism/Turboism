package dev.turboism.plugin.mcp;

import dev.turboism.protocol.json.StrictJson;

import dev.turboism.sdk.cubism.AnimationSnapshot;
import dev.turboism.sdk.cubism.ArtMeshSnapshot;
import dev.turboism.sdk.cubism.ClipMaskSnapshot;
import dev.turboism.sdk.cubism.CubismServiceException;
import dev.turboism.sdk.cubism.DeformerSnapshot;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelObjectSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ParameterSnapshot;
import dev.turboism.sdk.cubism.ProjectContentSnapshot;
import dev.turboism.sdk.cubism.ProjectResourceSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.PsdDocumentSnapshot;
import dev.turboism.sdk.cubism.RenderStatusSnapshot;
import dev.turboism.sdk.cubism.SelectionSnapshot;
import dev.turboism.sdk.cubism.TextureAtlasSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.DocumentId;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.id.ProjectId;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.ModelObjectCreateRequest;
import dev.turboism.sdk.cubism.model.ModelObjectDeletePolicy;
import dev.turboism.sdk.cubism.model.ModelObjectDescriptor;
import dev.turboism.sdk.cubism.model.ModelObjectKind;
import dev.turboism.sdk.cubism.model.ModelObjectOperationException;
import dev.turboism.sdk.cubism.model.ModelObjectReference;
import dev.turboism.sdk.cubism.model.ModelObjectService;
import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService;
import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService.ClipMaskRecord;
import dev.turboism.sdk.cubism.service.query.HierarchyNode;
import dev.turboism.sdk.cubism.service.query.ModelHierarchy;
import dev.turboism.sdk.cubism.service.query.ModelHierarchyQueryService;
import dev.turboism.sdk.cubism.service.query.ParameterQueryService;
import dev.turboism.sdk.cubism.service.query.ParameterSummary;
import dev.turboism.sdk.cubism.service.query.SelectionQueryService;
import dev.turboism.sdk.cubism.service.query.SelectionSummary;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.theme.ThemeStatusSnapshot;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.RotationDeformerForm;
import dev.turboism.sdk.cubism.model.WarpGrid;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import java.util.function.Supplier;

/** MCP tool catalog and strict argument-to-SDK translation. */
final class McpTools {

    static final String LIST = "turboism_model_objects_list";
    static final String RENAME = "turboism_model_object_rename";
    static final String REPARENT = "turboism_model_object_reparent";
    static final String CREATE = "turboism_model_object_create";
    static final String DELETE = "turboism_model_object_delete";
    static final String PARAMETERS_LIST = "turboism_parameters_list";
    static final String MODEL_HIERARCHY_GET = "turboism_model_hierarchy_get";
    static final String SELECTION_GET = "turboism_selection_get";
    static final String MODEL_SNAPSHOT_GET = "turboism_model_snapshot_get";
    static final String CLIP_MASKS_LIST = "turboism_clip_masks_list";

    private static final Map<String, Object> READ_ONLY_HINTS = Map.of(
        "readOnlyHint", true,
        "destructiveHint", false,
        "idempotentHint", true
    );

    private final ModelObjectService service;
    private final ParameterQueryService parameterQuery;
    private final ModelHierarchyQueryService hierarchyQuery;
    private final SelectionQueryService selectionQuery;
    private final CubismReadCapabilityService read;
    private final CubismClipMaskService clipMasks;
    private final PluginLogger logger;
    private final McpExecutionBridge execution;

    McpTools(
        final ModelObjectService service,
        final ParameterQueryService parameterQuery,
        final ModelHierarchyQueryService hierarchyQuery,
        final SelectionQueryService selectionQuery,
        final CubismReadCapabilityService read,
        final CubismClipMaskService clipMasks,
        final PluginLogger logger,
        final UiScheduler uiScheduler
    ) {
        this(
            service,
            parameterQuery,
            hierarchyQuery,
            selectionQuery,
            read,
            clipMasks,
            logger,
            new McpExecutionBridge(uiScheduler)
        );
    }

    McpTools(
        final ModelObjectService service,
        final ParameterQueryService parameterQuery,
        final ModelHierarchyQueryService hierarchyQuery,
        final SelectionQueryService selectionQuery,
        final CubismReadCapabilityService read,
        final CubismClipMaskService clipMasks,
        final PluginLogger logger,
        final McpExecutionBridge execution
    ) {
        this.service = Objects.requireNonNull(service, "service");
        this.parameterQuery = Objects.requireNonNull(parameterQuery, "parameterQuery");
        this.hierarchyQuery = Objects.requireNonNull(hierarchyQuery, "hierarchyQuery");
        this.selectionQuery = Objects.requireNonNull(selectionQuery, "selectionQuery");
        this.read = Objects.requireNonNull(read, "read");
        this.clipMasks = Objects.requireNonNull(clipMasks, "clipMasks");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.execution = Objects.requireNonNull(execution, "execution");
    }

    List<Map<String, Object>> definitions() {
        return List.of(
            tool(
                LIST,
                "List Cubism model objects",
                "Lists Parts, ArtMeshes, Warp Deformers, and Rotation Deformers in the active modeling document.",
                objectSchema(
                    properties(entry("kind", kindSchema("Optional object-family filter."))),
                    List.of()
                ),
                Map.of("readOnlyHint", true, "destructiveHint", false, "idempotentHint", true)
            ),
            tool(
                PARAMETERS_LIST,
                "List model parameters",
                "Lists parameters of the active Cubism model. id filters by stable parameter ID (exact); "
                    + "name filters by parameter name (case-insensitive substring). Both may be combined (AND).",
                objectSchema(
                    properties(
                        entry("id", stringSchema("Stable parameter ID to return (exact match); omitting lists all.", 1, 256)),
                        entry("name", stringSchema("Parameter name to match (case-insensitive substring).", 1, 256))
                    ),
                    List.of()
                ),
                READ_ONLY_HINTS
            ),
            tool(
                MODEL_HIERARCHY_GET,
                "Get the model object hierarchy",
                "Returns the active model's object hierarchy as a nested tree. id returns one node and its "
                    + "subtree; name (case-insensitive substring, combinable with id as AND) returns every "
                    + "matching node and its subtree as a matches list.",
                objectSchema(
                    properties(
                        entry("id", stringSchema("Root node ID of the subtree to return; omitting returns the full tree.", 1, 256)),
                        entry("name", stringSchema("Node name to match (case-insensitive substring).", 1, 256))
                    ),
                    List.of()
                ),
                READ_ONLY_HINTS
            ),
            tool(
                SELECTION_GET,
                "Get the current selection",
                "Returns the active project, document, model, and selected parameters, ArtMeshes, deformers, and model objects.",
                objectSchema(properties(), List.of()),
                READ_ONLY_HINTS
            ),
            tool(
                MODEL_SNAPSHOT_GET,
                "Get a model snapshot",
                "Returns a lightweight snapshot of the active project, document, model, selection, and all model/editor data families.",
                objectSchema(properties(), List.of()),
                READ_ONLY_HINTS
            ),
            tool(
                CLIP_MASKS_LIST,
                "List ArtMesh clip masks",
                "Lists ArtMesh clip-mask records of the active model. id filters by the "
                    + "Inspector-editable, user-visible ID (e.g. Warp1); guid filters by the "
                    + "generated, non-editable internal stable identifier; name filters by the "
                    + "user-visible mesh display name (case-insensitive substring). All filters may "
                    + "be combined (AND); omitting all lists every record.",
                objectSchema(
                    properties(
                        entry("id", stringSchema("User-visible, Inspector-editable ArtMesh ID to return (exact match).", 1, 256)),
                        entry("guid", stringSchema("Generated, non-editable internal ArtMesh GUID to return (exact match).", 1, 256)),
                        entry("name", stringSchema("Mesh display name to match (case-insensitive substring).", 1, 256))
                    ),
                    List.of()
                ),
                READ_ONLY_HINTS
            ),
            tool(
                RENAME,
                "Rename a Cubism model object",
                "Renames one object by typed kind and stable Cubism ID through the Turboism authoring API.",
                objectSchema(
                    properties(
                        entry("kind", kindSchema("Object family.")),
                        entry("id", stringSchema("Stable Cubism object ID.", 1, 256)),
                        entry("name", stringSchema("New display name.", 1, 256))
                    ),
                    List.of("kind", "id", "name")
                ),
                Map.of("readOnlyHint", false, "destructiveHint", false, "idempotentHint", true)
            ),
            tool(
                REPARENT,
                "Reparent a Cubism model object",
                "Moves an existing object under a new parent. Part targets accept only Part parents; ArtMesh and Deformer targets accept Part or Deformer parents. Index -1 appends to the parent.",
                objectSchema(
                    properties(
                        entry("kind", kindSchema("Object family to move.")),
                        entry("id", stringSchema("Stable Cubism object ID.", 1, 256)),
                        entry("parent", objectSchema(
                            properties(
                                entry("kind", kindSchema("New parent object family.")),
                                entry("id", stringSchema("New parent object ID.", 1, 256))
                            ),
                            List.of("kind", "id")
                        )),
                        entry("index", integerSchema(
                            "Sibling index under the new parent; -1 appends.",
                            -1,
                            Integer.MAX_VALUE
                        ))
                    ),
                    List.of("kind", "id", "parent")
                ),
                Map.of("readOnlyHint", false, "destructiveHint", false, "idempotentHint", true)
            ),
            tool(
                CREATE,
                "Create a Cubism model object",
                "Creates a Part, ArtMesh, Warp Deformer, or Rotation Deformer. ArtMesh defaults to a unit triangle; Warp defaults to a 2x2 unit grid; Rotation defaults to origin (0,0), angle 0, scale 1.",
                createSchema(),
                Map.of("readOnlyHint", false, "destructiveHint", false, "idempotentHint", false)
            ),
            tool(
                DELETE,
                "Delete a Cubism model object",
                "Deletes one object by kind and ID. The default policy rejects referenced objects; cascade must be explicit.",
                objectSchema(
                    properties(
                        entry("kind", kindSchema("Object family.")),
                        entry("id", stringSchema("Stable Cubism object ID.", 1, 256)),
                        entry("policy", enumSchema(
                            "Reference handling policy.",
                            List.of("reject_referenced", "cascade")
                        ))
                    ),
                    List.of("kind", "id")
                ),
                Map.of("readOnlyHint", false, "destructiveHint", true, "idempotentHint", true)
            )
        );
    }

    Map<String, Object> call(final String name, final Map<String, Object> arguments) {
        final String toolName = Objects.requireNonNull(name, "name");
        final Map<String, Object> checkedArguments = new LinkedHashMap<>(
            Objects.requireNonNull(arguments, "arguments")
        );
        try {
            McpRequestRegistry.throwIfCancelled();
            final Map<String, Object> output = switch (toolName) {
                case LIST -> list(checkedArguments);
                case RENAME -> rename(checkedArguments);
                case REPARENT -> reparent(checkedArguments);
                case CREATE -> create(checkedArguments);
                case DELETE -> delete(checkedArguments);
                case PARAMETERS_LIST -> parametersList(checkedArguments);
                case MODEL_HIERARCHY_GET -> modelHierarchyGet(checkedArguments);
                case SELECTION_GET -> selectionGet(checkedArguments);
                case MODEL_SNAPSHOT_GET -> modelSnapshotGet(checkedArguments);
                case CLIP_MASKS_LIST -> clipMasksList(checkedArguments);
                default -> throw new ToolInputException("Unknown MCP tool: " + toolName);
            };
            return toolResult(output, false);
        } catch (CancellationException failure) {
            throw failure;
        } catch (ToolInputException failure) {
            return toolFailure("INVALID_ARGUMENT", failure.getMessage(), failure, false);
        } catch (ModelObjectOperationException failure) {
            return toolFailure(failure.code().name(), safeMessage(failure), failure, false);
        } catch (ReadServiceException failure) {
            return toolFailure("FAILED", failure.getMessage(), failure, false);
        } catch (CubismPermissionException failure) {
            return toolFailure("PERMISSION_DENIED", safeMessage(failure), failure, false);
        } catch (SecurityException failure) {
            return toolFailure("PERMISSION_DENIED", safeMessage(failure), failure, false);
        } catch (RuntimeException failure) {
            return toolFailure("FAILED", safeMessage(failure), failure, true);
        }
    }

    private Map<String, Object> list(final Map<String, Object> arguments) {
        only(arguments, "kind");
        final Optional<ModelObjectKind> filter = optionalString(arguments, "kind")
            .map(McpTools::kind);
        final List<Map<String, Object>> objects = execution.ui(service::list).stream()
            .filter(value -> filter.isEmpty() || value.reference().kind() == filter.orElseThrow())
            .map(McpTools::descriptor)
            .toList();
        return linked(
            entry("ok", true),
            entry("count", objects.size()),
            entry("objects", objects)
        );
    }

    private Map<String, Object> parametersList(final Map<String, Object> arguments) {
        only(arguments, "id", "name");
        final Optional<String> idFilter = optionalString(arguments, "id");
        final Optional<String> nameFilter = optionalString(arguments, "name");
        final List<ParameterSummary> parameters = idFilter
            .map(value -> readService(() -> parameterQuery.findById(new ParameterId(value)))
                .map(List::of)
                .orElseGet(List::of))
            .orElseGet(() -> readService(parameterQuery::listAll))
            .stream()
            .filter(parameter -> nameFilter.isEmpty()
                || containsIgnoreCase(parameter.name(), nameFilter.orElseThrow()))
            .toList();
        return linked(
            entry("ok", true),
            entry("count", parameters.size()),
            entry("parameters", parameters.stream().map(McpTools::parameter).toList())
        );
    }

    private Map<String, Object> modelHierarchyGet(final Map<String, Object> arguments) {
        only(arguments, "id", "name");
        final Optional<String> idFilter = optionalString(arguments, "id");
        final Optional<String> nameFilter = optionalString(arguments, "name");
        final Optional<HierarchyNode> root = idFilter
            .map(value -> readService(() -> hierarchyQuery.findNode(new ModelObjectId(value))))
            .orElseGet(() -> readService(hierarchyQuery::currentHierarchy)
                .map(ModelHierarchy::rootNode));
        if (nameFilter.isEmpty()) {
            return linked(
                entry("ok", true),
                entry("root", root.map(this::hierarchyNode).orElse(null))
            );
        }
        final List<Map<String, Object>> matches = root
            .map(this::collectSubtrees)
            .orElseGet(List::of)
            .stream()
            .filter(node -> containsIgnoreCase(node.name(), nameFilter.orElseThrow()))
            .map(this::hierarchyNode)
            .toList();
        return linked(
            entry("ok", true),
            entry("count", matches.size()),
            entry("matches", matches)
        );
    }

    private Map<String, Object> selectionGet(final Map<String, Object> arguments) {
        only(arguments);
        final SelectionSummary selection = readService(selectionQuery::currentSelection);
        return linked(
            entry("ok", true),
            entry("projectId", selection.activeProjectId().map(ProjectId::value).orElse(null)),
            entry("documentId", selection.activeDocumentId().map(DocumentId::value).orElse(null)),
            entry("modelId", selection.activeModelId().map(ModelObjectId::value).orElse(null)),
            entry("parameters", selection.selectedParameterIds().stream().map(ParameterId::value).toList()),
            entry("artMeshes", selection.selectedArtMeshIds().stream().map(ArtMeshId::value).toList()),
            entry("deformers", selection.selectedDeformerIds().stream().map(DeformerId::value).toList()),
            entry("modelObjects", selection.selectedModelObjectIds().stream().map(ModelObjectId::value).toList())
        );
    }

    private Map<String, Object> modelSnapshotGet(final Map<String, Object> arguments) {
        only(arguments);
        return linked(
            entry("ok", true),
            entry("project", execution.ui(read::activeProject).map(McpTools::project).orElse(null)),
            entry("document", execution.ui(read::activeDocument).map(McpTools::document).orElse(null)),
            entry("model", execution.ui(read::activeModel).map(McpTools::model).orElse(null)),
            entry("selection", selection(execution.ui(read::selection))),
            entry("parameters", list(execution.ui(read::parameters), McpTools::parameterSnapshot)),
            entry("modelObjects", list(execution.ui(read::modelObjects), McpTools::modelObject)),
            entry("meshes", list(execution.ui(read::meshes), McpTools::artMesh)),
            entry("deformers", list(execution.ui(read::deformers), McpTools::deformer)),
            entry("psdDocuments", list(execution.ui(read::psdDocuments), McpTools::psdDocument)),
            entry("clipMasks", list(execution.ui(read::clipMasks), McpTools::clipMask)),
            entry("textureAtlases", list(execution.ui(read::textureAtlases), McpTools::textureAtlas)),
            entry("renderStatus", execution.ui(read::renderStatus).map(McpTools::renderStatus).orElse(null)),
            entry("workspace", execution.ui(read::workspace).map(McpTools::workspace).orElse(null)),
            entry("themeStatus", execution.ui(read::themeStatus).map(McpTools::themeStatus).orElse(null))
        );
    }

    private Map<String, Object> clipMasksList(final Map<String, Object> arguments) {
        only(arguments, "id", "guid", "name");
        final Optional<String> idFilter = optionalString(arguments, "id");
        final Optional<String> guidFilter = optionalString(arguments, "guid");
        final Optional<String> nameFilter = optionalString(arguments, "name");
        final List<ClipMaskRecord> records = readService(clipMasks::collectClipMaskRecords).stream()
            .filter(record -> idFilter.isEmpty() || record.id().equals(idFilter.orElseThrow()))
            .filter(record -> guidFilter.isEmpty() || record.guid().equals(guidFilter.orElseThrow()))
            .filter(record -> nameFilter.isEmpty()
                || containsIgnoreCase(record.displayName(), nameFilter.orElseThrow()))
            .toList();
        return linked(
            entry("ok", true),
            entry("count", records.size()),
            entry("clipMasks", records.stream().map(McpTools::clipMaskRecord).toList())
        );
    }

    private List<HierarchyNode> collectSubtrees(final HierarchyNode node) {
        final ArrayList<HierarchyNode> result = new ArrayList<>();
        collect(node, result);
        return result;
    }

    private void collect(final HierarchyNode node, final ArrayList<HierarchyNode> result) {
        result.add(node);
        for (HierarchyNode child : readService(() -> hierarchyQuery.childrenOf(node.id()))) {
            collect(child, result);
        }
    }
    private Map<String, Object> hierarchyNode(final HierarchyNode node) {
        final List<HierarchyNode> children = readService(() -> hierarchyQuery.childrenOf(node.id()));
        return linked(
            entry("id", node.id().value()),
            entry("name", node.name()),
            entry("kind", node.kind().name()),
            entry("parentId", node.parentId().map(ModelObjectId::value).orElse(null)),
            entry("children", children.stream().map(this::hierarchyNode).toList())
        );
    }

    private <T> T readService(final ServiceCall<T> call) {
        return execution.ui(() -> {
            try {
                return call.run();
            } catch (CubismServiceException failure) {
                throw new ReadServiceException(failure);
            }
        });
    }

    private Map<String, Object> rename(final Map<String, Object> arguments) {
        only(arguments, "kind", "id", "name");
        final ModelObjectReference target = reference(arguments);
        final String name = requiredString(arguments, "name", 256);
        return linked(
            entry("ok", true),
            entry("object", descriptor(execution.ui(() -> service.rename(target, name))))
        );
    }

    private Map<String, Object> reparent(final Map<String, Object> arguments) {
        only(arguments, "kind", "id", "parent", "index");
        final ModelObjectReference target = reference(arguments);
        final Object parentValue = arguments.get("parent");
        if (parentValue == null) {
            throw new ToolInputException("parent is required");
        }
        final ModelObjectReference parent = reference(object(parentValue, "parent"));
        final int index = optionalInteger(arguments, "index").orElse(-1);
        if (index < -1) {
            throw new ToolInputException("index must be -1 or greater");
        }
        return linked(
            entry("ok", true),
            entry("object", descriptor(execution.ui(() -> service.reparent(target, parent, index))))
        );
    }

    private Map<String, Object> create(final Map<String, Object> arguments) {
        only(
            arguments,
            "kind", "name", "parent", "positions", "uvs", "triangleIndices",
            "rows", "columns", "quadTransform", "controlPoints", "originX", "originY",
            "width", "height", "angle", "scale", "reflectedX", "reflectedY"
        );
        final ModelObjectKind kind = kind(requiredString(arguments, "kind", 64));
        final String name = requiredString(arguments, "name", 256);
        final Optional<ModelObjectReference> parent = optionalParent(arguments);
        final ModelObjectCreateRequest request = switch (kind) {
            case PART -> new ModelObjectCreateRequest.Part(name, parent);
            case ART_MESH -> new ModelObjectCreateRequest.ArtMesh(
                name,
                parent,
                artMeshGeometry(arguments)
            );
            case WARP_DEFORMER -> new ModelObjectCreateRequest.WarpDeformer(
                name,
                parent,
                warpGrid(arguments)
            );
            case ROTATION_DEFORMER -> new ModelObjectCreateRequest.RotationDeformer(
                name,
                parent,
                rotationForm(arguments)
            );
        };
        return linked(
            entry("ok", true),
            entry("object", descriptor(execution.ui(() -> service.create(request))))
        );
    }

    private Map<String, Object> delete(final Map<String, Object> arguments) {
        only(arguments, "kind", "id", "policy");
        final ModelObjectReference target = reference(arguments);
        final ModelObjectDeletePolicy policy = optionalString(arguments, "policy")
            .map(value -> switch (value) {
                case "reject_referenced" -> ModelObjectDeletePolicy.REJECT_REFERENCED;
                case "cascade" -> ModelObjectDeletePolicy.CASCADE;
                default -> throw new ToolInputException(
                    "policy must be reject_referenced or cascade"
                );
            })
            .orElse(ModelObjectDeletePolicy.REJECT_REFERENCED);
        execution.ui(() -> {
            service.delete(target, policy);
            return null;
        });
        return linked(
            entry("ok", true),
            entry("deleted", true),
            entry("target", reference(target)),
            entry("policy", policy == ModelObjectDeletePolicy.CASCADE
                ? "cascade" : "reject_referenced")
        );
    }

    private Map<String, Object> toolFailure(
        final String code,
        final String message,
        final RuntimeException failure,
        final boolean logStack
    ) {
        if (logStack) {
            logger.error("MCP tool execution failed: " + code + ": " + message, failure);
        } else {
            logger.warn("MCP tool rejected: " + code + ": " + message);
        }
        return toolResult(linked(
            entry("ok", false),
            entry("error", linked(entry("code", code), entry("message", message)))
        ), true);
    }

    private static Map<String, Object> toolResult(
        final Map<String, Object> output,
        final boolean error
    ) {
        return linked(
            entry("content", List.of(linked(
                entry("type", "text"),
                entry("text", StrictJson.stringify(output))
            ))),
            entry("structuredContent", output),
            entry("isError", error)
        );
    }

    private static ArtMeshGeometry artMeshGeometry(final Map<String, Object> arguments) {
        final Optional<Object> positionsValue = Optional.ofNullable(arguments.get("positions"));
        final Optional<Object> uvsValue = Optional.ofNullable(arguments.get("uvs"));
        final Optional<Object> indicesValue = Optional.ofNullable(arguments.get("triangleIndices"));
        if (positionsValue.isEmpty() && uvsValue.isEmpty() && indicesValue.isEmpty()) {
            return new ArtMeshGeometry(
                List.of(new Point2(0.0f, 0.0f), new Point2(1.0f, 0.0f), new Point2(0.0f, 1.0f)),
                List.of(new Point2(0.0f, 0.0f), new Point2(1.0f, 0.0f), new Point2(0.0f, 1.0f)),
                List.of(0, 1, 2)
            );
        }
        if (positionsValue.isEmpty() || uvsValue.isEmpty() || indicesValue.isEmpty()) {
            throw new ToolInputException(
                "positions, uvs, and triangleIndices must be supplied together"
            );
        }
        return new ArtMeshGeometry(
            points(positionsValue.orElseThrow(), "positions"),
            points(uvsValue.orElseThrow(), "uvs"),
            integers(indicesValue.orElseThrow(), "triangleIndices")
        );
    }

    private static WarpGrid warpGrid(final Map<String, Object> arguments) {
        final int rows = optionalInteger(arguments, "rows").orElse(2);
        final int columns = optionalInteger(arguments, "columns").orElse(2);
        if (rows < 1 || rows > 64 || columns < 1 || columns > 64) {
            throw new ToolInputException("rows and columns must be between 1 and 64");
        }
        final boolean quadTransform = optionalBoolean(arguments, "quadTransform").orElse(false);
        final List<Point2> controlPoints;
        if (arguments.containsKey("controlPoints")) {
            controlPoints = points(arguments.get("controlPoints"), "controlPoints");
        } else {
            final float originX = optionalFloat(arguments, "originX").orElse(0.0f);
            final float originY = optionalFloat(arguments, "originY").orElse(0.0f);
            final float width = optionalFloat(arguments, "width").orElse(1.0f);
            final float height = optionalFloat(arguments, "height").orElse(1.0f);
            if (!(width > 0.0f) || !(height > 0.0f)) {
                throw new ToolInputException("width and height must be positive");
            }
            final ArrayList<Point2> generated = new ArrayList<>((rows + 1) * (columns + 1));
            for (int row = 0; row <= rows; row++) {
                final float y = originY + height * row / rows;
                for (int column = 0; column <= columns; column++) {
                    final float x = originX + width * column / columns;
                    generated.add(new Point2(x, y));
                }
            }
            controlPoints = List.copyOf(generated);
        }
        return new WarpGrid(rows, columns, quadTransform, controlPoints);
    }

    private static RotationDeformerForm rotationForm(final Map<String, Object> arguments) {
        return new RotationDeformerForm(
            optionalFloat(arguments, "angle").orElse(0.0f),
            optionalFloat(arguments, "originX").orElse(0.0f),
            optionalFloat(arguments, "originY").orElse(0.0f),
            optionalFloat(arguments, "scale").orElse(1.0f),
            optionalBoolean(arguments, "reflectedX").orElse(false),
            optionalBoolean(arguments, "reflectedY").orElse(false)
        );
    }

    private static Optional<ModelObjectReference> optionalParent(
        final Map<String, Object> arguments
    ) {
        final Object value = arguments.get("parent");
        if (value == null) return Optional.empty();
        return Optional.of(reference(object(value, "parent")));
    }

    private static ModelObjectReference reference(final Map<String, Object> values) {
        return new ModelObjectReference(
            kind(requiredString(values, "kind", 64)),
            requiredString(values, "id", 256)
        );
    }

    private static ModelObjectKind kind(final String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "part" -> ModelObjectKind.PART;
            case "art_mesh", "artmesh" -> ModelObjectKind.ART_MESH;
            case "warp_deformer", "warp" -> ModelObjectKind.WARP_DEFORMER;
            case "rotation_deformer", "rotation" -> ModelObjectKind.ROTATION_DEFORMER;
            default -> throw new ToolInputException(
                "kind must be part, art_mesh, warp_deformer, or rotation_deformer"
            );
        };
    }

    private static Map<String, Object> descriptor(final ModelObjectDescriptor value) {
        return linked(
            entry("kind", wire(value.reference().kind())),
            entry("id", value.reference().id()),
            entry("name", value.name()),
            entry("parent", value.parent().map(McpTools::reference).orElse(null))
        );
    }

    private static Map<String, Object> reference(final ModelObjectReference value) {
        return linked(entry("kind", wire(value.kind())), entry("id", value.id()));
    }

    private static String wire(final ModelObjectKind kind) {
        return switch (kind) {
            case PART -> "part";
            case ART_MESH -> "art_mesh";
            case WARP_DEFORMER -> "warp_deformer";
            case ROTATION_DEFORMER -> "rotation_deformer";
        };
    }

    private static Map<String, Object> parameter(final ParameterSummary value) {
        return linked(
            entry("id", value.id().value()),
            entry("name", value.name()),
            entry("currentValue", value.currentValue()),
            entry("minValue", value.minValue()),
            entry("maxValue", value.maxValue()),
            entry("defaultValue", value.defaultValue()),
            entry("visible", value.visible()),
            entry("editable", value.editable())
        );
    }

    private static Map<String, Object> clipMaskRecord(final ClipMaskRecord value) {
        return linked(
            entry("guid", value.guid()),
            entry("id", value.id()),
            entry("displayName", value.displayName()),
            entry("inverted", value.inverted()),
            entry("orderedMaskGuids", value.orderedMaskGuids())
        );
    }

    private static Map<String, Object> project(final ProjectSnapshot value) {
        return linked(
            entry("projectId", value.projectId()),
            entry("name", value.name()),
            entry("contents", value.contents().stream().map(McpTools::projectContent).toList()),
            entry("documents", value.documents().stream().map(McpTools::document).toList())
        );
    }

    private static Map<String, Object> projectContent(final ProjectContentSnapshot value) {
        return linked(
            entry("contentId", value.contentId()),
            entry("name", value.name()),
            entry("kind", value.kind().name()),
            entry("documentIds", value.documentIds()),
            entry("resources", value.resources().stream().map(McpTools::projectResource).toList())
        );
    }

    private static Map<String, Object> projectResource(final ProjectResourceSnapshot value) {
        return linked(
            entry("resourceId", value.resourceId()),
            entry("name", value.name()),
            entry("kind", value.kind().name()),
            entry("relativePath", value.relativePath().orElse(null))
        );
    }

    private static Map<String, Object> document(final DocumentSnapshot value) {
        return linked(
            entry("documentId", value.documentId()),
            entry("name", value.name()),
            entry("kind", value.kind().name()),
            entry("relativePath", value.relativePath()),
            entry("contentId", value.contentId().orElse(null)),
            entry("model", value.model().map(McpTools::model).orElse(null)),
            entry("animation", value.animation().map(McpTools::animation).orElse(null))
        );
    }

    private static Map<String, Object> animation(final AnimationSnapshot value) {
        return linked(
            entry("animationId", value.animationId()),
            entry("name", value.name()),
            entry("sceneDocumentIds", value.sceneDocumentIds()),
            entry("activeSceneDocumentId", value.activeSceneDocumentId().orElse(null))
        );
    }

    private static Map<String, Object> model(final ModelSnapshot value) {
        return linked(
            entry("modelId", value.modelId()),
            entry("name", value.name()),
            entry("objects", value.objects().stream().map(McpTools::modelObject).toList()),
            entry("parameters", value.parameters().stream().map(McpTools::parameterSnapshot).toList()),
            entry("artMeshes", value.artMeshes().stream().map(McpTools::artMesh).toList()),
            entry("deformers", value.deformers().stream().map(McpTools::deformer).toList())
        );
    }

    private static Map<String, Object> modelObject(final ModelObjectSnapshot value) {
        if (value instanceof ParameterSnapshot parameter) return parameterSnapshot(parameter);
        if (value instanceof ArtMeshSnapshot mesh) return artMesh(mesh);
        if (value instanceof DeformerSnapshot deformer) return deformer(deformer);
        throw new IllegalArgumentException(
            "Unsupported model object snapshot: " + value.getClass().getName()
        );
    }

    private static Map<String, Object> parameterSnapshot(final ParameterSnapshot value) {
        return linked(
            entry("id", value.id()),
            entry("name", value.name()),
            entry("value", value.value()),
            entry("defaultValue", value.defaultValue()),
            entry("minValue", value.minValue()),
            entry("maxValue", value.maxValue()),
            entry("visible", value.visible()),
            entry("editable", value.editable())
        );
    }

    private static Map<String, Object> artMesh(final ArtMeshSnapshot value) {
        return linked(
            entry("id", value.id()),
            entry("name", value.name()),
            entry("textureId", value.textureId().orElse(null)),
            entry("visible", value.visible()),
            entry("renderable", value.renderable())
        );
    }

    private static Map<String, Object> deformer(final DeformerSnapshot value) {
        return linked(
            entry("id", value.id()),
            entry("name", value.name()),
            entry("type", value.type().name()),
            entry("parentId", value.parentId().orElse(null)),
            entry("childIds", value.childIds())
        );
    }

    private static Map<String, Object> selection(final SelectionSnapshot value) {
        return linked(
            entry("selectedObjectIds", value.selectedObjectIds()),
            entry("activeParameterId", value.activeParameterId().orElse(null)),
            entry("activeArtMeshId", value.activeArtMeshId().orElse(null)),
            entry("activeDeformerId", value.activeDeformerId().orElse(null))
        );
    }

    private static Map<String, Object> psdDocument(final PsdDocumentSnapshot value) {
        return linked(
            entry("documentId", value.documentId()),
            entry("relativePath", value.relativePath()),
            entry("layers", value.layers().stream().map(McpTools::psdLayer).toList())
        );
    }

    private static Map<String, Object> psdLayer(final PsdDocumentSnapshot.PsdLayerSnapshot value) {
        return linked(
            entry("layerId", value.layerId()),
            entry("name", value.name()),
            entry("visible", value.visible())
        );
    }

    private static Map<String, Object> clipMask(final ClipMaskSnapshot value) {
        return linked(
            entry("targetMeshId", value.targetMeshId()),
            entry("orderedMaskSourceIds", value.orderedMaskSourceIds()),
            entry("inverted", value.inverted())
        );
    }

    private static Map<String, Object> textureAtlas(final TextureAtlasSnapshot value) {
        return linked(
            entry("atlasId", value.atlasId()),
            entry("width", value.width()),
            entry("height", value.height()),
            entry("textureIds", value.textureIds())
        );
    }

    private static Map<String, Object> renderStatus(final RenderStatusSnapshot value) {
        return linked(
            entry("rendering", value.rendering()),
            entry("framesPerSecond", value.framesPerSecond()),
            entry("rendererName", value.rendererName())
        );
    }

    private static Map<String, Object> workspace(final WorkspaceSnapshot value) {
        return linked(
            entry("workspaceId", value.workspaceId()),
            entry("displayName", value.displayName()),
            entry("rootRelativePath", value.rootRelativePath()),
            entry("recentProjectIds", value.recentProjectIds())
        );
    }

    private static Map<String, Object> themeStatus(final ThemeStatusSnapshot value) {
        return linked(
            entry("themeId", value.themeId()),
            entry("displayName", value.displayName()),
            entry("dark", value.dark())
        );
    }

    private static <T> Object list(
        final List<T> values,
        final Function<T, Map<String, Object>> serializer
    ) {
        return values.isEmpty() ? null : values.stream().map(serializer).toList();
    }

    private static boolean containsIgnoreCase(final String text, final String fragment) {
        return text.toLowerCase(Locale.ROOT).contains(fragment.toLowerCase(Locale.ROOT));
    }

    private static List<Point2> points(final Object value, final String label) {
        final List<Object> values = array(value, label);
        final ArrayList<Point2> result = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            final Map<String, Object> point = object(values.get(index), label + "[" + index + "]");
            only(point, "x", "y");
            result.add(new Point2(
                requiredFloat(point, "x"),
                requiredFloat(point, "y")
            ));
        }
        return List.copyOf(result);
    }

    private static List<Integer> integers(final Object value, final String label) {
        final List<Object> values = array(value, label);
        final ArrayList<Integer> result = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            result.add(integer(values.get(index), label + "[" + index + "]"));
        }
        return List.copyOf(result);
    }

    private static String requiredString(
        final Map<String, Object> values,
        final String key,
        final int maxLength
    ) {
        final Object value = values.get(key);
        if (!(value instanceof String text)) {
            throw new ToolInputException(key + " must be a string");
        }
        final String normalized = text.strip();
        if (normalized.isEmpty()) throw new ToolInputException(key + " must not be blank");
        if (normalized.length() > maxLength) {
            throw new ToolInputException(key + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }

    private static Optional<String> optionalString(
        final Map<String, Object> values,
        final String key
    ) {
        if (!values.containsKey(key) || values.get(key) == null) return Optional.empty();
        return Optional.of(requiredString(values, key, 256));
    }

    private static float requiredFloat(final Map<String, Object> values, final String key) {
        if (!values.containsKey(key)) throw new ToolInputException(key + " is required");
        return floating(values.get(key), key);
    }

    private static Optional<Float> optionalFloat(
        final Map<String, Object> values,
        final String key
    ) {
        if (!values.containsKey(key) || values.get(key) == null) return Optional.empty();
        return Optional.of(floating(values.get(key), key));
    }

    private static float floating(final Object value, final String label) {
        if (!(value instanceof Number number)) {
            throw new ToolInputException(label + " must be a number");
        }
        final float result = number.floatValue();
        if (!Float.isFinite(result)) throw new ToolInputException(label + " must be finite");
        return result;
    }

    private static Optional<Integer> optionalInteger(
        final Map<String, Object> values,
        final String key
    ) {
        if (!values.containsKey(key) || values.get(key) == null) return Optional.empty();
        return Optional.of(integer(values.get(key), key));
    }

    private static int integer(final Object value, final String label) {
        try {
            if (value instanceof BigDecimal decimal) return decimal.intValueExact();
            if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
                return ((Number) value).intValue();
            }
            if (value instanceof Long number) return Math.toIntExact(number);
        } catch (ArithmeticException failure) {
            throw new ToolInputException(label + " must be a 32-bit integer");
        }
        throw new ToolInputException(label + " must be an integer");
    }

    private static Optional<Boolean> optionalBoolean(
        final Map<String, Object> values,
        final String key
    ) {
        if (!values.containsKey(key) || values.get(key) == null) return Optional.empty();
        final Object value = values.get(key);
        if (!(value instanceof Boolean flag)) {
            throw new ToolInputException(key + " must be a boolean");
        }
        return Optional.of(flag);
    }

    private static Map<String, Object> object(final Object value, final String label) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new ToolInputException(label + " must be an object");
        }
        final LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new ToolInputException(label + " contains a non-string key");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static List<Object> array(final Object value, final String label) {
        if (!(value instanceof List<?> values)) {
            throw new ToolInputException(label + " must be an array");
        }
        return new ArrayList<>(values);
    }

    private static void only(final Map<String, Object> values, final String... allowed) {
        final java.util.Set<String> names = java.util.Set.of(allowed);
        for (String key : values.keySet()) {
            if (!names.contains(key)) throw new ToolInputException("unknown argument: " + key);
        }
    }

    private static Map<String, Object> tool(
        final String name,
        final String title,
        final String description,
        final Map<String, Object> inputSchema,
        final Map<String, Object> annotations
    ) {
        return linked(
            entry("name", name),
            entry("title", title),
            entry("description", description),
            entry("inputSchema", inputSchema),
            entry("annotations", annotations)
        );
    }

    private static Map<String, Object> createSchema() {
        return objectSchema(
            properties(
                entry("kind", kindSchema("Object family to create.")),
                entry("name", stringSchema("Display name.", 1, 256)),
                entry("parent", objectSchema(
                    properties(
                        entry("kind", kindSchema("Parent object family.")),
                        entry("id", stringSchema("Parent object ID.", 1, 256))
                    ),
                    List.of("kind", "id")
                )),
                entry("positions", pointArraySchema("ArtMesh vertex positions.")),
                entry("uvs", pointArraySchema("ArtMesh UV coordinates.")),
                entry("triangleIndices", linked(
                    entry("type", "array"),
                    entry("description", "ArtMesh triangle vertex indices."),
                    entry("items", Map.of("type", "integer", "minimum", 0))
                )),
                entry("rows", integerSchema("Warp grid rows.", 1, 64)),
                entry("columns", integerSchema("Warp grid columns.", 1, 64)),
                entry("quadTransform", Map.of("type", "boolean")),
                entry("controlPoints", pointArraySchema("Explicit Warp control points.")),
                entry("originX", Map.of("type", "number")),
                entry("originY", Map.of("type", "number")),
                entry("width", linked(entry("type", "number"), entry("exclusiveMinimum", 0))),
                entry("height", linked(entry("type", "number"), entry("exclusiveMinimum", 0))),
                entry("angle", Map.of("type", "number")),
                entry("scale", linked(entry("type", "number"), entry("exclusiveMinimum", 0))),
                entry("reflectedX", Map.of("type", "boolean")),
                entry("reflectedY", Map.of("type", "boolean"))
            ),
            List.of("kind", "name")
        );
    }

    private static Map<String, Object> pointArraySchema(final String description) {
        return linked(
            entry("type", "array"),
            entry("description", description),
            entry("items", objectSchema(
                properties(
                    entry("x", Map.of("type", "number")),
                    entry("y", Map.of("type", "number"))
                ),
                List.of("x", "y")
            ))
        );
    }

    private static Map<String, Object> kindSchema(final String description) {
        return enumSchema(
            description,
            List.of("part", "art_mesh", "warp_deformer", "rotation_deformer")
        );
    }

    private static Map<String, Object> enumSchema(
        final String description,
        final List<String> values
    ) {
        return linked(
            entry("type", "string"),
            entry("description", description),
            entry("enum", values)
        );
    }

    private static Map<String, Object> stringSchema(
        final String description,
        final int minimum,
        final int maximum
    ) {
        return linked(
            entry("type", "string"),
            entry("description", description),
            entry("minLength", minimum),
            entry("maxLength", maximum)
        );
    }

    private static Map<String, Object> integerSchema(
        final String description,
        final int minimum,
        final int maximum
    ) {
        return linked(
            entry("type", "integer"),
            entry("description", description),
            entry("minimum", minimum),
            entry("maximum", maximum)
        );
    }

    private static Map<String, Object> objectSchema(
        final Map<String, Object> properties,
        final List<String> required
    ) {
        return linked(
            entry("type", "object"),
            entry("properties", properties),
            entry("required", required),
            entry("additionalProperties", false)
        );
    }

    @SafeVarargs
    private static Map<String, Object> properties(
        final Map.Entry<String, Object>... entries
    ) {
        return linked(entries);
    }

    @SafeVarargs
    private static Map<String, Object> linked(final Map.Entry<String, Object>... entries) {
        final LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : entries) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static Map.Entry<String, Object> entry(final String key, final Object value) {
        return new java.util.AbstractMap.SimpleImmutableEntry<>(key, value);
    }

    private static String safeMessage(final RuntimeException failure) {
        final String message = failure.getMessage();
        return message == null || message.isBlank()
            ? failure.getClass().getSimpleName()
            : message;
    }

    private static final class ToolInputException extends RuntimeException {
        private ToolInputException(final String message) {
            super(message);
        }
    }

    @FunctionalInterface
    private interface ServiceCall<T> {
        T run() throws CubismServiceException;
    }

    private static final class ReadServiceException extends RuntimeException {
        private ReadServiceException(final CubismServiceException failure) {
            super(failure.getMessage() == null || failure.getMessage().isBlank()
                ? "Cubism read service failed"
                : failure.getMessage());
        }
    }
}
