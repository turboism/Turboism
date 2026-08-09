package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.ArtMeshSnapshot;
import dev.turboism.sdk.cubism.ClipMaskSnapshot;
import dev.turboism.sdk.cubism.DeformerSnapshot;
import dev.turboism.sdk.cubism.DeformerType;
import dev.turboism.sdk.cubism.DocumentKind;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelObjectSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ParameterSnapshot;
import dev.turboism.sdk.cubism.ProjectContentKind;
import dev.turboism.sdk.cubism.ProjectContentSnapshot;
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
import dev.turboism.sdk.cubism.service.query.ParameterBounds;
import dev.turboism.sdk.cubism.service.query.ParameterQueryService;
import dev.turboism.sdk.cubism.service.query.ParameterSummary;
import dev.turboism.sdk.cubism.service.query.SelectionQueryService;
import dev.turboism.sdk.cubism.service.query.SelectionSummary;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.theme.ThemeStatusSnapshot;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class McpHttpServerIntegrationTest {

    private static final String TOKEN = "test-token-0123456789-abcdef";

    @TempDir
    Path temporaryDirectory;

    @Test
    void servesLifecycleAndObjectToolsOverAuthenticatedLoopbackHttp() throws Exception {
        final MutableObjects objects = new MutableObjects();
        objects.put(new ModelObjectDescriptor(
            new ModelObjectReference(ModelObjectKind.PART, "PartHead"),
            "Head",
            Optional.empty()
        ));
        final CapturingLogger logger = new CapturingLogger();
        final McpHttpServer server = McpHttpServer.start(dependencies(logger, objects, new FakeReadServices()));
        final Path connectionFile = server.connectionFile();
        try {
            assertEquals("127.0.0.1", server.endpoint().getHost());
            assertTrue(Files.isRegularFile(connectionFile));
            final Map<String, Object> connection = object(Json.parse(Files.readAllBytes(connectionFile)));
            assertEquals(server.endpoint().toString(), connection.get("endpoint"));
            assertEquals("Bearer " + TOKEN, connection.get("authorization"));
            assertEquals(McpProtocol.VERSION, connection.get("protocolVersion"));

            final HttpResponse<byte[]> initialized = request(server.endpoint(), TOKEN, null, false, Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "initialize",
                "params", Map.of(
                    "protocolVersion", McpProtocol.VERSION,
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "integration-test", "version", "1.0")
                )
            ));
            assertEquals(200, initialized.statusCode());
            final Map<String, Object> initializeResult = result(initialized);
            assertEquals(McpProtocol.VERSION, initializeResult.get("protocolVersion"));
            final Map<String, Object> serverInfo = object(initializeResult.get("serverInfo"));
            assertEquals("turboism-mcp", serverInfo.get("name"));

            final HttpResponse<byte[]> tools = request(server.endpoint(), TOKEN, null, true, Map.of(
                "jsonrpc", "2.0",
                "id", 2,
                "method", "tools/list",
                "params", Map.of()
            ));
            assertEquals(200, tools.statusCode());
            assertEquals(10, array(result(tools).get("tools")).size());

            final HttpResponse<byte[]> renamed = toolCall(
                server.endpoint(),
                3,
                McpTools.RENAME,
                Map.of("kind", "part", "id", "PartHead", "name", "Head Renamed")
            );
            final Map<String, Object> renamedOutput = structuredResult(renamed);
            assertEquals(Boolean.TRUE, renamedOutput.get("ok"));
            assertEquals(
                "Head Renamed",
                object(renamedOutput.get("object")).get("name")
            );
            assertEquals("Head Renamed", objects.find(ModelObjectKind.PART, "PartHead").name());

            final HttpResponse<byte[]> created = toolCall(
                server.endpoint(),
                4,
                McpTools.CREATE,
                Map.of(
                    "kind", "warp_deformer",
                    "name", "Face Warp",
                    "parent", Map.of("kind", "part", "id", "PartHead"),
                    "rows", 3,
                    "columns", 4,
                    "originX", -1,
                    "originY", -2,
                    "width", 2,
                    "height", 4
                )
            );
            final Map<String, Object> createdOutput = structuredResult(created);
            assertEquals(Boolean.TRUE, createdOutput.get("ok"));
            assertInstanceOf(ModelObjectCreateRequest.WarpDeformer.class, objects.lastCreate);
            final ModelObjectCreateRequest.WarpDeformer warp =
                (ModelObjectCreateRequest.WarpDeformer) objects.lastCreate;
            assertEquals(3, warp.grid().rows());
            assertEquals(4, warp.grid().columns());
            assertEquals(20, warp.grid().controlPoints().size());
            final Map<String, Object> createdObject = object(createdOutput.get("object"));
            final String createdId = (String) createdObject.get("id");
            assertNotNull(createdId);

            final HttpResponse<byte[]> reparented = toolCall(
                server.endpoint(),
                5,
                McpTools.REPARENT,
                Map.of(
                    "kind", "warp_deformer",
                    "id", createdId,
                    "parent", Map.of("kind", "part", "id", "PartHead"),
                    "index", 2
                )
            );
            final Map<String, Object> reparentedOutput = structuredResult(reparented);
            assertEquals(Boolean.TRUE, reparentedOutput.get("ok"));
            assertEquals(
                Map.of("kind", "part", "id", "PartHead"),
                object(object(reparentedOutput.get("object")).get("parent"))
            );
            assertEquals(
                new ModelObjectReference(ModelObjectKind.WARP_DEFORMER, createdId),
                objects.lastReparentTarget
            );
            assertEquals(
                new ModelObjectReference(ModelObjectKind.PART, "PartHead"),
                objects.lastReparentParent
            );
            assertEquals(2, objects.lastReparentIndex);

            final HttpResponse<byte[]> deleted = toolCall(
                server.endpoint(),
                6,
                McpTools.DELETE,
                Map.of(
                    "kind", "warp_deformer",
                    "id", createdId,
                    "policy", "cascade"
                )
            );
            final Map<String, Object> deletedOutput = structuredResult(deleted);
            assertEquals(Boolean.TRUE, deletedOutput.get("deleted"));
            assertEquals(ModelObjectDeletePolicy.CASCADE, objects.lastDeletePolicy);
            assertFalse(objects.contains(ModelObjectKind.WARP_DEFORMER, createdId));

            final HttpResponse<byte[]> notification = request(
                server.endpoint(),
                TOKEN,
                null,
                true,
                Map.of("jsonrpc", "2.0", "method", "notifications/initialized")
            );
            assertEquals(202, notification.statusCode());
            assertEquals(0, notification.body().length);
        } finally {
            server.close();
        }
        assertFalse(Files.exists(connectionFile));
        assertTrue(logger.info.stream().anyMatch(value -> value.contains("listening")));
        assertTrue(logger.info.stream().anyMatch(value -> value.contains("stopped")));
    }

    @Test
    void rejectsMissingTokenAndNonLoopbackOrigin() throws Exception {
        final McpHttpServer server = McpHttpServer.start(dependencies(
            new CapturingLogger(),
            new MutableObjects(),
            new FakeReadServices()
        ));
        try {
            final Map<String, Object> ping = Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "ping"
            );
            assertEquals(
                401,
                request(server.endpoint(), "wrong-token-0123456789-abcdef", null, true, ping)
                    .statusCode()
            );
            assertEquals(
                403,
                request(server.endpoint(), TOKEN, "https://attacker.example", true, ping)
                    .statusCode()
            );
            assertEquals(
                200,
                request(server.endpoint(), TOKEN, "http://127.0.0.1", true, ping)
                    .statusCode()
            );
        } finally {
            server.close();
        }
    }

    @Test
    void returnsStableToolErrorWhenStructuralProviderIsUnavailable() throws Exception {
        final ModelObjectService unavailable = new MutableObjects() {
            @Override public ModelObjectDescriptor create(final ModelObjectCreateRequest request) {
                throw new ModelObjectOperationException(
                    ModelObjectOperationException.Code.UNAVAILABLE,
                    "structural provider is not verified"
                );
            }
        };
        final McpHttpServer server = McpHttpServer.start(dependencies(
            new CapturingLogger(),
            unavailable,
            new FakeReadServices()
        ));
        try {
            final HttpResponse<byte[]> response = toolCall(
                server.endpoint(),
                6,
                McpTools.CREATE,
                Map.of("kind", "part", "name", "Unavailable Part")
            );
            final Map<String, Object> result = object(result(response));
            assertEquals(Boolean.TRUE, result.get("isError"));
            final Map<String, Object> structured = object(result.get("structuredContent"));
            assertEquals(Boolean.FALSE, structured.get("ok"));
            assertEquals(
                "UNAVAILABLE",
                object(structured.get("error")).get("code")
            );
        } finally {
            server.close();
        }
    }

    @Test
    void servesAllFiveReadToolsOverAuthenticatedLoopbackHttp() throws Exception {
        final FakeReadServices reads = new FakeReadServices();
        reads.parameters.put(new ParameterSummary(
            new ParameterId("ParamAngle"),
            "Angle",
            45.0,
            new ParameterBounds(-180.0, 180.0, 0.0),
            true,
            true
        ));
        reads.parameters.put(new ParameterSummary(
            new ParameterId("ParamEyeOpen"),
            "Eye Open",
            0.5,
            new ParameterBounds(0.0, 1.0, 1.0),
            false,
            false
        ));
        final HierarchyNode modelRoot = new HierarchyNode(
            new ModelObjectId("ModelRoot"), "Model Root", HierarchyNode.Kind.MODEL,
            Optional.empty(), List.of(new ModelObjectId("PartBody"))
        );
        final HierarchyNode partBody = new HierarchyNode(
            new ModelObjectId("PartBody"), "Body", HierarchyNode.Kind.PART,
            Optional.of(new ModelObjectId("ModelRoot")), List.of(new ModelObjectId("MeshFace"))
        );
        final HierarchyNode meshFace = new HierarchyNode(
            new ModelObjectId("MeshFace"), "Face", HierarchyNode.Kind.ART_MESH,
            Optional.of(new ModelObjectId("PartBody")), List.of()
        );
        reads.hierarchy.put(modelRoot);
        reads.hierarchy.put(partBody);
        reads.hierarchy.put(meshFace);
        reads.selection.set(new SelectionSummary(
            Optional.of(new ProjectId("ProjectA")),
            Optional.of(new DocumentId("DocA")),
            Optional.of(new ModelObjectId("ModelA")),
            List.of(new ParameterId("ParamAngle")),
            List.of(new ArtMeshId("MeshFace")),
            List.of(new DeformerId("DeformerWarp")),
            List.of(new ModelObjectId("PartBody"))
        ));
        final ParameterSnapshot parameter = new ParameterSnapshot(
            "ParamAngle", "Angle", 45.0, 0.0, -180.0, 180.0, true, true
        );
        final ArtMeshSnapshot mesh = new ArtMeshSnapshot(
            "MeshFace", "Face", Optional.of("TextureA"), true, true
        );
        final DeformerSnapshot deformer = new DeformerSnapshot(
            "DeformerWarp", "Face Warp", DeformerType.WARP,
            Optional.of("PartBody"), List.of("MeshFace")
        );
        final ModelSnapshot model = new ModelSnapshot(
            "ModelA",
            "Demo Model",
            List.of(parameter, mesh, deformer),
            List.of(parameter),
            List.of(mesh),
            List.of(deformer)
        );
        final DocumentSnapshot document = new DocumentSnapshot(
            "DocA", "Demo Model", DocumentKind.MODEL, "Models/Demo.model3.json",
            Optional.empty(), Optional.empty(), Optional.of(model), Optional.empty()
        );
        final ProjectContentSnapshot content = new ProjectContentSnapshot(
            "ContentA", "Demo Model", ProjectContentKind.MODEL,
            Optional.empty(), List.of("DocA")
        );
        final ProjectSnapshot project = new ProjectSnapshot(
            "ProjectA", "Demo Project", Optional.empty(),
            List.of(content), List.of(document)
        );
        reads.read.project(project);
        reads.read.document(document);
        reads.read.model(model);
        reads.read.selection(new SelectionSnapshot(
            List.of("PartBody"),
            Optional.of("ParamAngle"),
            Optional.of("MeshFace"),
            Optional.of("DeformerWarp")
        ));
        reads.read.parameters(List.of(parameter));
        reads.read.modelObjects(List.of(parameter, mesh, deformer));
        reads.read.meshes(List.of(mesh));
        reads.read.deformers(List.of(deformer));
        reads.read.psdDocuments(List.of(new PsdDocumentSnapshot(
            "PsdA", "Assets/layers.psd",
            List.of(new PsdDocumentSnapshot.PsdLayerSnapshot("Layer1", "Base", true))
        )));
        reads.read.clipMasks(List.of(new ClipMaskSnapshot("MeshA", List.of("Mask1"), false)));
        reads.read.textureAtlases(List.of(new TextureAtlasSnapshot("AtlasA", 2048, 2048, List.of("TextureA"))));
        reads.read.renderStatus(Optional.of(new RenderStatusSnapshot(true, 60.0, "OpenGL")));
        reads.read.workspace(Optional.of(new WorkspaceSnapshot("ws1", "Default", "Workspaces/Default", List.of("ProjectA"))));
        reads.read.themeStatus(Optional.of(new ThemeStatusSnapshot("theme-dark", "Dark", true)));
        reads.clipMasks.add(new ClipMaskRecord(
            "9f3e2a1b-c4d5-4e6f-8a7b-1c2d3e4f5a6b", "Warp1", "Face", false,
            List.of("7d1c9b2a-1111-4a5b-8c9d-0e1f2a3b4c5d")
        ));
        reads.clipMasks.add(new ClipMaskRecord(
            "7d1c9b2a-1111-4a5b-8c9d-0e1f2a3b4c5d", "ArtMeshFace", "Hair", true, List.of()
        ));

        final McpHttpServer server = McpHttpServer.start(dependencies(
            new CapturingLogger(),
            new MutableObjects(),
            reads
        ));
        try {
            final Map<String, Object> listed = structuredResult(toolCall(
                server.endpoint(), 10, McpTools.PARAMETERS_LIST, Map.of()
            ));
            assertEquals(Boolean.TRUE, listed.get("ok"));
            assertEquals(2L, integer(listed.get("count")));
            final Map<String, Object> angle = object(array(listed.get("parameters")).get(0));
            assertEquals("ParamAngle", angle.get("id"));
            assertEquals("Angle", angle.get("name"));
            assertEquals(45.0, number(angle.get("currentValue")), 0.0);
            assertEquals(-180.0, number(angle.get("minValue")), 0.0);
            assertEquals(180.0, number(angle.get("maxValue")), 0.0);
            assertEquals(0.0, number(angle.get("defaultValue")), 0.0);
            assertEquals(Boolean.TRUE, angle.get("visible"));
            assertEquals(Boolean.TRUE, angle.get("editable"));

            final Map<String, Object> filtered = structuredResult(toolCall(
                server.endpoint(), 11, McpTools.PARAMETERS_LIST,
                Map.of("id", "ParamEyeOpen")
            ));
            assertEquals(1L, integer(filtered.get("count")));
            assertEquals("ParamEyeOpen", object(array(filtered.get("parameters")).get(0)).get("id"));

            final Map<String, Object> missing = structuredResult(toolCall(
                server.endpoint(), 12, McpTools.PARAMETERS_LIST,
                Map.of("id", "ParamNope")
            ));
            assertEquals(Boolean.TRUE, missing.get("ok"));
            assertEquals(0L, integer(missing.get("count")));
            assertEquals(0, array(missing.get("parameters")).size());

            final Map<String, Object> byName = structuredResult(toolCall(
                server.endpoint(), 23, McpTools.PARAMETERS_LIST,
                Map.of("name", "eye")
            ));
            assertEquals(1L, integer(byName.get("count")));
            assertEquals("ParamEyeOpen", object(array(byName.get("parameters")).get(0)).get("id"));

            final Map<String, Object> byNameCaseInsensitive = structuredResult(toolCall(
                server.endpoint(), 24, McpTools.PARAMETERS_LIST,
                Map.of("name", "EYE")
            ));
            assertEquals(1L, integer(byNameCaseInsensitive.get("count")));
            assertEquals("ParamEyeOpen", object(array(byNameCaseInsensitive.get("parameters")).get(0)).get("id"));

            final Map<String, Object> byNameMiss = structuredResult(toolCall(
                server.endpoint(), 25, McpTools.PARAMETERS_LIST,
                Map.of("name", "zzz")
            ));
            assertEquals(0L, integer(byNameMiss.get("count")));
            assertEquals(0, array(byNameMiss.get("parameters")).size());

            final Map<String, Object> byIdAndName = structuredResult(toolCall(
                server.endpoint(), 26, McpTools.PARAMETERS_LIST,
                Map.of("id", "ParamAngle", "name", "angle")
            ));
            assertEquals(1L, integer(byIdAndName.get("count")));
            assertEquals("ParamAngle", object(array(byIdAndName.get("parameters")).get(0)).get("id"));

            final Map<String, Object> byIdAndNameMiss = structuredResult(toolCall(
                server.endpoint(), 27, McpTools.PARAMETERS_LIST,
                Map.of("id", "ParamAngle", "name", "eye")
            ));
            assertEquals(0L, integer(byIdAndNameMiss.get("count")));

            final Map<String, Object> tree = structuredResult(toolCall(
                server.endpoint(), 13, McpTools.MODEL_HIERARCHY_GET, Map.of()
            ));
            final Map<String, Object> root = object(tree.get("root"));
            assertEquals("ModelRoot", root.get("id"));
            assertEquals("MODEL", root.get("kind"));
            assertEquals(null, root.get("parentId"));
            final Map<String, Object> part = object(array(root.get("children")).get(0));
            assertEquals("PartBody", part.get("id"));
            assertEquals("PART", part.get("kind"));
            assertEquals("ModelRoot", part.get("parentId"));
            assertEquals("MeshFace", object(array(part.get("children")).get(0)).get("id"));

            final Map<String, Object> subtree = structuredResult(toolCall(
                server.endpoint(), 14, McpTools.MODEL_HIERARCHY_GET,
                Map.of("id", "PartBody")
            ));
            final Map<String, Object> subRoot = object(subtree.get("root"));
            assertEquals("PartBody", subRoot.get("id"));
            assertEquals("ModelRoot", subRoot.get("parentId"));
            assertEquals(1, array(subRoot.get("children")).size());
            assertEquals("MeshFace", object(array(subRoot.get("children")).get(0)).get("id"));

            final Map<String, Object> missingNode = structuredResult(toolCall(
                server.endpoint(), 15, McpTools.MODEL_HIERARCHY_GET,
                Map.of("id", "MissingNode")
            ));
            assertEquals(Boolean.TRUE, missingNode.get("ok"));
            assertEquals(null, missingNode.get("root"));

            final Map<String, Object> hierarchyByName = structuredResult(toolCall(
                server.endpoint(), 28, McpTools.MODEL_HIERARCHY_GET,
                Map.of("name", "body")
            ));
            assertEquals(Boolean.TRUE, hierarchyByName.get("ok"));
            assertEquals(1L, integer(hierarchyByName.get("count")));
            final Map<String, Object> match = object(array(hierarchyByName.get("matches")).get(0));
            assertEquals("PartBody", match.get("id"));
            assertEquals("PART", match.get("kind"));
            assertEquals("ModelRoot", match.get("parentId"));
            assertEquals("MeshFace", object(array(match.get("children")).get(0)).get("id"));

            final Map<String, Object> hierarchyByNameCaseInsensitive = structuredResult(toolCall(
                server.endpoint(), 29, McpTools.MODEL_HIERARCHY_GET,
                Map.of("name", "BODY")
            ));
            assertEquals(1L, integer(hierarchyByNameCaseInsensitive.get("count")));
            assertEquals("PartBody", object(array(hierarchyByNameCaseInsensitive.get("matches")).get(0)).get("id"));

            final Map<String, Object> hierarchyByNameMiss = structuredResult(toolCall(
                server.endpoint(), 30, McpTools.MODEL_HIERARCHY_GET,
                Map.of("name", "zzz")
            ));
            assertEquals(0L, integer(hierarchyByNameMiss.get("count")));
            assertEquals(0, array(hierarchyByNameMiss.get("matches")).size());

            final Map<String, Object> hierarchyByIdAndName = structuredResult(toolCall(
                server.endpoint(), 31, McpTools.MODEL_HIERARCHY_GET,
                Map.of("id", "PartBody", "name", "face")
            ));
            assertEquals(1L, integer(hierarchyByIdAndName.get("count")));
            assertEquals("MeshFace", object(array(hierarchyByIdAndName.get("matches")).get(0)).get("id"));

            final Map<String, Object> selection = structuredResult(toolCall(
                server.endpoint(), 16, McpTools.SELECTION_GET, Map.of()
            ));
            assertEquals("ProjectA", selection.get("projectId"));
            assertEquals("DocA", selection.get("documentId"));
            assertEquals("ModelA", selection.get("modelId"));
            assertEquals(List.of("ParamAngle"), selection.get("parameters"));
            assertEquals(List.of("MeshFace"), selection.get("artMeshes"));
            assertEquals(List.of("DeformerWarp"), selection.get("deformers"));
            assertEquals(List.of("PartBody"), selection.get("modelObjects"));

            final Map<String, Object> snapshot = structuredResult(toolCall(
                server.endpoint(), 17, McpTools.MODEL_SNAPSHOT_GET, Map.of()
            ));
            final Map<String, Object> projectOut = object(snapshot.get("project"));
            assertEquals("ProjectA", projectOut.get("projectId"));
            final Map<String, Object> documentOut = object(snapshot.get("document"));
            assertEquals("MODEL", documentOut.get("kind"));
            assertEquals("ModelA", object(documentOut.get("model")).get("modelId"));
            assertEquals("ModelA", object(snapshot.get("model")).get("modelId"));
            assertEquals(3, array(snapshot.get("modelObjects")).size());
            assertEquals(1, array(snapshot.get("parameters")).size());
            assertEquals(1, array(snapshot.get("meshes")).size());
            assertEquals(1, array(snapshot.get("deformers")).size());
            assertEquals(1, array(snapshot.get("psdDocuments")).size());
            assertEquals(1, array(snapshot.get("clipMasks")).size());
            assertEquals(1, array(snapshot.get("textureAtlases")).size());
            assertEquals(Boolean.TRUE, object(snapshot.get("renderStatus")).get("rendering"));
            assertEquals("ws1", object(snapshot.get("workspace")).get("workspaceId"));
            assertEquals(Boolean.TRUE, object(snapshot.get("themeStatus")).get("dark"));
            final Map<String, Object> selectionOut = object(snapshot.get("selection"));
            assertEquals(List.of("PartBody"), selectionOut.get("selectedObjectIds"));
            assertEquals("ParamAngle", selectionOut.get("activeParameterId"));

            final Map<String, Object> masks = structuredResult(toolCall(
                server.endpoint(), 18, McpTools.CLIP_MASKS_LIST, Map.of()
            ));
            assertEquals(2L, integer(masks.get("count")));
            final Map<String, Object> firstMask = object(array(masks.get("clipMasks")).get(0));
            assertEquals("9f3e2a1b-c4d5-4e6f-8a7b-1c2d3e4f5a6b", firstMask.get("guid"));
            assertEquals("Warp1", firstMask.get("id"));
            assertEquals("Face", firstMask.get("displayName"));
            assertEquals(Boolean.FALSE, firstMask.get("inverted"));
            assertEquals(
                List.of("7d1c9b2a-1111-4a5b-8c9d-0e1f2a3b4c5d"),
                firstMask.get("orderedMaskGuids")
            );

            final Map<String, Object> byGuid = structuredResult(toolCall(
                server.endpoint(), 19, McpTools.CLIP_MASKS_LIST,
                Map.of("guid", "7d1c9b2a-1111-4a5b-8c9d-0e1f2a3b4c5d")
            ));
            assertEquals(1L, integer(byGuid.get("count")));
            assertEquals(
                "ArtMeshFace",
                object(array(byGuid.get("clipMasks")).get(0)).get("id")
            );

            final Map<String, Object> byId = structuredResult(toolCall(
                server.endpoint(), 20, McpTools.CLIP_MASKS_LIST,
                Map.of("id", "Warp1")
            ));
            assertEquals(1L, integer(byId.get("count")));
            final Map<String, Object> byIdMask = object(array(byId.get("clipMasks")).get(0));
            assertEquals("Warp1", byIdMask.get("id"));
            assertEquals("9f3e2a1b-c4d5-4e6f-8a7b-1c2d3e4f5a6b", byIdMask.get("guid"));

            final Map<String, Object> byBoth = structuredResult(toolCall(
                server.endpoint(), 21, McpTools.CLIP_MASKS_LIST,
                Map.of("id", "Warp1", "guid", "9f3e2a1b-c4d5-4e6f-8a7b-1c2d3e4f5a6b")
            ));
            assertEquals(1L, integer(byBoth.get("count")));

            final Map<String, Object> byBothMismatched = structuredResult(toolCall(
                server.endpoint(), 22, McpTools.CLIP_MASKS_LIST,
                Map.of("id", "Warp1", "guid", "7d1c9b2a-1111-4a5b-8c9d-0e1f2a3b4c5d")
            ));
            assertEquals(0L, integer(byBothMismatched.get("count")));

            final Map<String, Object> byDisplayName = structuredResult(toolCall(
                server.endpoint(), 32, McpTools.CLIP_MASKS_LIST,
                Map.of("name", "face")
            ));
            assertEquals(1L, integer(byDisplayName.get("count")));
            final Map<String, Object> byNameMask = object(array(byDisplayName.get("clipMasks")).get(0));
            assertEquals("Warp1", byNameMask.get("id"));
            assertEquals("9f3e2a1b-c4d5-4e6f-8a7b-1c2d3e4f5a6b", byNameMask.get("guid"));

            final Map<String, Object> byDisplayNameCaseInsensitive = structuredResult(toolCall(
                server.endpoint(), 33, McpTools.CLIP_MASKS_LIST,
                Map.of("name", "FACE")
            ));
            assertEquals(1L, integer(byDisplayNameCaseInsensitive.get("count")));

            final Map<String, Object> byDisplayNameMiss = structuredResult(toolCall(
                server.endpoint(), 34, McpTools.CLIP_MASKS_LIST,
                Map.of("name", "zzz")
            ));
            assertEquals(0L, integer(byDisplayNameMiss.get("count")));
            assertEquals(0, array(byDisplayNameMiss.get("clipMasks")).size());

            final Map<String, Object> masksByIdAndName = structuredResult(toolCall(
                server.endpoint(), 35, McpTools.CLIP_MASKS_LIST,
                Map.of("id", "Warp1", "name", "face")
            ));
            assertEquals(1L, integer(masksByIdAndName.get("count")));

            final Map<String, Object> masksByIdAndNameMiss = structuredResult(toolCall(
                server.endpoint(), 36, McpTools.CLIP_MASKS_LIST,
                Map.of("id", "Warp1", "name", "hair")
            ));
            assertEquals(0L, integer(masksByIdAndNameMiss.get("count")));
        } finally {
            server.close();
        }
    }

    @Test
    void servesAllFiveReadToolsWithEmptyServices() throws Exception {
        final McpHttpServer server = McpHttpServer.start(dependencies(
            new CapturingLogger(),
            new MutableObjects(),
            new FakeReadServices()
        ));
        try {
            final Map<String, Object> listed = structuredResult(toolCall(
                server.endpoint(), 10, McpTools.PARAMETERS_LIST, Map.of()
            ));
            assertEquals(Boolean.TRUE, listed.get("ok"));
            assertEquals(0L, integer(listed.get("count")));
            assertEquals(0, array(listed.get("parameters")).size());

            final Map<String, Object> tree = structuredResult(toolCall(
                server.endpoint(), 11, McpTools.MODEL_HIERARCHY_GET, Map.of()
            ));
            assertEquals(Boolean.TRUE, tree.get("ok"));
            assertEquals(null, tree.get("root"));

            final Map<String, Object> selection = structuredResult(toolCall(
                server.endpoint(), 12, McpTools.SELECTION_GET, Map.of()
            ));
            assertEquals(null, selection.get("projectId"));
            assertEquals(null, selection.get("documentId"));
            assertEquals(null, selection.get("modelId"));
            assertEquals(0, array(selection.get("parameters")).size());
            assertEquals(0, array(selection.get("artMeshes")).size());
            assertEquals(0, array(selection.get("deformers")).size());
            assertEquals(0, array(selection.get("modelObjects")).size());

            final Map<String, Object> snapshot = structuredResult(toolCall(
                server.endpoint(), 13, McpTools.MODEL_SNAPSHOT_GET, Map.of()
            ));
            assertEquals(null, snapshot.get("project"));
            assertEquals(null, snapshot.get("document"));
            assertEquals(null, snapshot.get("model"));
            assertEquals(null, snapshot.get("parameters"));
            assertEquals(null, snapshot.get("modelObjects"));
            assertEquals(null, snapshot.get("meshes"));
            assertEquals(null, snapshot.get("deformers"));
            assertEquals(null, snapshot.get("psdDocuments"));
            assertEquals(null, snapshot.get("clipMasks"));
            assertEquals(null, snapshot.get("textureAtlases"));
            assertEquals(null, snapshot.get("renderStatus"));
            assertEquals(null, snapshot.get("workspace"));
            assertEquals(null, snapshot.get("themeStatus"));
            assertEquals(0, array(object(snapshot.get("selection")).get("selectedObjectIds")).size());

            final Map<String, Object> masks = structuredResult(toolCall(
                server.endpoint(), 14, McpTools.CLIP_MASKS_LIST, Map.of()
            ));
            assertEquals(Boolean.TRUE, masks.get("ok"));
            assertEquals(0L, integer(masks.get("count")));
            assertEquals(0, array(masks.get("clipMasks")).size());
        } finally {
            server.close();
        }
    }

    private HttpResponse<byte[]> toolCall(
        final URI endpoint,
        final int id,
        final String tool,
        final Map<String, Object> arguments
    ) throws Exception {
        return request(endpoint, TOKEN, null, true, Map.of(
            "jsonrpc", "2.0",
            "id", id,
            "method", "tools/call",
            "params", Map.of("name", tool, "arguments", arguments)
        ));
    }

    private static HttpResponse<byte[]> request(
        final URI endpoint,
        final String token,
        final String origin,
        final boolean includeProtocolVersion,
        final Map<String, Object> body
    ) throws Exception {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofByteArray(Json.bytes(body)));
        if (origin != null) builder.header("Origin", origin);
        if (includeProtocolVersion) {
            builder.header("MCP-Protocol-Version", McpProtocol.VERSION);
        }
        return HttpClient.newHttpClient().send(
            builder.build(),
            HttpResponse.BodyHandlers.ofByteArray()
        );
    }

    private static Map<String, Object> result(final HttpResponse<byte[]> response) {
        final Map<String, Object> envelope = object(Json.parse(response.body()));
        assertFalse(envelope.containsKey("error"), () -> new String(
            response.body(), StandardCharsets.UTF_8
        ));
        return object(envelope.get("result"));
    }

    private static Map<String, Object> structuredResult(
        final HttpResponse<byte[]> response
    ) {
        final Map<String, Object> toolResult = result(response);
        assertEquals(Boolean.FALSE, toolResult.get("isError"));
        return object(toolResult.get("structuredContent"));
    }

    private static UiScheduler immediateUi() {
        return new UiScheduler() {
            @Override public Registration runOnUiThread(final Runnable work) {
                work.run();
                return () -> { };
            }

            @Override public Registration runOnUiThreadLater(
                final Runnable work,
                final Duration delay
            ) {
                work.run();
                return () -> { };
            }
        };
    }

    private static Map<String, Object> object(final Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new AssertionError("Expected JSON object but got " + value);
        }
        final LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put((String) key, item));
        return result;
    }

    private static List<Object> array(final Object value) {
        if (!(value instanceof List<?> values)) {
            throw new AssertionError("Expected JSON array but got " + value);
        }
        return new ArrayList<>(values);
    }

    private static long integer(final Object value) {
        if (!(value instanceof Number number)) {
            throw new AssertionError("Expected JSON number but got " + value);
        }
        return number.longValue();
    }

    private static double number(final Object value) {
        if (!(value instanceof Number number)) {
            throw new AssertionError("Expected JSON number but got " + value);
        }
        return number.doubleValue();
    }

    private McpHttpServer.Dependencies dependencies(
        final PluginLogger logger,
        final ModelObjectService objects,
        final FakeReadServices reads
    ) {
        return new McpHttpServer.Dependencies(
            logger,
            objects,
            reads.parameters,
            reads.hierarchy,
            reads.selection,
            reads.read,
            reads.clipMasks,
            immediateUi(),
            temporaryDirectory,
            0,
            TOKEN,
            120
        );
    }

    private static final class FakeReadServices {
        final FakeParameterQuery parameters = new FakeParameterQuery();
        final FakeHierarchyQuery hierarchy = new FakeHierarchyQuery();
        final FakeSelectionQuery selection = new FakeSelectionQuery();
        final FakeRead read = new FakeRead();
        final FakeClipMasks clipMasks = new FakeClipMasks();
    }

    private static final class FakeParameterQuery implements ParameterQueryService {
        private final LinkedHashMap<String, ParameterSummary> values = new LinkedHashMap<>();

        void put(final ParameterSummary value) {
            values.put(value.id().value(), value);
        }

        @Override public Optional<ParameterSummary> findById(final ParameterId id) {
            return Optional.ofNullable(values.get(id.value()));
        }

        @Override public List<ParameterSummary> listAll() {
            return List.copyOf(values.values());
        }

        @Override public boolean exists(final ParameterId id) {
            return values.containsKey(id.value());
        }
    }

    private static final class FakeHierarchyQuery implements ModelHierarchyQueryService {
        private final LinkedHashMap<String, HierarchyNode> nodes = new LinkedHashMap<>();

        void put(final HierarchyNode node) {
            nodes.put(node.id().value(), node);
        }

        @Override public Optional<ModelHierarchy> currentHierarchy() {
            final HierarchyNode root = nodes.values().stream()
                .filter(node -> node.parentId().isEmpty())
                .findFirst()
                .orElse(null);
            if (root == null) return Optional.empty();
            return Optional.of(new ModelHierarchy(root, List.copyOf(nodes.values())));
        }

        @Override public List<HierarchyNode> childrenOf(final ModelObjectId id) {
            final HierarchyNode node = nodes.get(id.value());
            if (node == null) return List.of();
            return node.childIds().stream()
                .map(child -> nodes.get(child.value()))
                .filter(Objects::nonNull)
                .toList();
        }

        @Override public Optional<HierarchyNode> findNode(final ModelObjectId id) {
            return Optional.ofNullable(nodes.get(id.value()));
        }
    }

    private static final class FakeSelectionQuery implements SelectionQueryService {
        private SelectionSummary current = SelectionSummary.empty();

        void set(final SelectionSummary value) {
            current = value;
        }

        @Override public SelectionSummary currentSelection() {
            return current;
        }

        @Override public List<ModelObjectId> selectedIds(final HierarchyNode.Kind kind) {
            throw new UnsupportedOperationException("selectedIds is not used by MCP tools");
        }

        @Override public Registration onSelectionChanged(final SelectionChangedListener listener) {
            return () -> { };
        }
    }

    private static final class FakeRead implements CubismReadCapabilityService {
        private Optional<ProjectSnapshot> project = Optional.empty();
        private Optional<DocumentSnapshot> document = Optional.empty();
        private Optional<ModelSnapshot> model = Optional.empty();
        private SelectionSnapshot selection =
            new SelectionSnapshot(List.of(), Optional.empty(), Optional.empty(), Optional.empty());
        private List<ParameterSnapshot> parameters = List.of();
        private List<ModelObjectSnapshot> modelObjects = List.of();
        private List<ArtMeshSnapshot> meshes = List.of();
        private List<DeformerSnapshot> deformers = List.of();
        private List<PsdDocumentSnapshot> psdDocuments = List.of();
        private List<ClipMaskSnapshot> clipMasks = List.of();
        private List<TextureAtlasSnapshot> textureAtlases = List.of();
        private Optional<RenderStatusSnapshot> renderStatus = Optional.empty();
        private Optional<WorkspaceSnapshot> workspace = Optional.empty();
        private Optional<ThemeStatusSnapshot> themeStatus = Optional.empty();

        void project(final ProjectSnapshot value) { project = Optional.of(value); }
        void document(final DocumentSnapshot value) { document = Optional.of(value); }
        void model(final ModelSnapshot value) { model = Optional.of(value); }
        void selection(final SelectionSnapshot value) { selection = value; }
        void parameters(final List<ParameterSnapshot> value) { parameters = value; }
        void modelObjects(final List<ModelObjectSnapshot> value) { modelObjects = value; }
        void meshes(final List<ArtMeshSnapshot> value) { meshes = value; }
        void deformers(final List<DeformerSnapshot> value) { deformers = value; }
        void psdDocuments(final List<PsdDocumentSnapshot> value) { psdDocuments = value; }
        void clipMasks(final List<ClipMaskSnapshot> value) { clipMasks = value; }
        void textureAtlases(final List<TextureAtlasSnapshot> value) { textureAtlases = value; }
        void renderStatus(final Optional<RenderStatusSnapshot> value) { renderStatus = value; }
        void workspace(final Optional<WorkspaceSnapshot> value) { workspace = value; }
        void themeStatus(final Optional<ThemeStatusSnapshot> value) { themeStatus = value; }

        @Override public Optional<ProjectSnapshot> activeProject() { return project; }
        @Override public Optional<DocumentSnapshot> activeDocument() { return document; }
        @Override public Optional<ModelSnapshot> activeModel() { return model; }
        @Override public SelectionSnapshot selection() { return selection; }
        @Override public List<ParameterSnapshot> parameters() { return parameters; }
        @Override public List<ModelObjectSnapshot> modelObjects() { return modelObjects; }
        @Override public List<ArtMeshSnapshot> meshes() { return meshes; }
        @Override public List<DeformerSnapshot> deformers() { return deformers; }
        @Override public List<PsdDocumentSnapshot> psdDocuments() { return psdDocuments; }
        @Override public List<ClipMaskSnapshot> clipMasks() { return clipMasks; }
        @Override public List<TextureAtlasSnapshot> textureAtlases() { return textureAtlases; }
        @Override public Optional<RenderStatusSnapshot> renderStatus() { return renderStatus; }
        @Override public Optional<WorkspaceSnapshot> workspace() { return workspace; }
        @Override public Optional<ThemeStatusSnapshot> themeStatus() { return themeStatus; }
    }

    private static final class FakeClipMasks implements CubismClipMaskService {
        private final ArrayList<ClipMaskRecord> records = new ArrayList<>();

        void add(final ClipMaskRecord record) {
            records.add(record);
        }

        @Override public List<ClipMaskRecord> collectClipMaskRecords() {
            return List.copyOf(records);
        }
    }
    private static class MutableObjects implements ModelObjectService {
        private final LinkedHashMap<ModelObjectReference, ModelObjectDescriptor> values =
            new LinkedHashMap<>();
        private final AtomicInteger generated = new AtomicInteger();
        ModelObjectCreateRequest lastCreate;
        ModelObjectReference lastReparentTarget;
        ModelObjectReference lastReparentParent;
        int lastReparentIndex = Integer.MIN_VALUE;
        ModelObjectReference lastDelete;
        ModelObjectDeletePolicy lastDeletePolicy;

        void put(final ModelObjectDescriptor value) {
            values.put(value.reference(), value);
        }

        ModelObjectDescriptor find(final ModelObjectKind kind, final String id) {
            final ModelObjectDescriptor result = values.get(new ModelObjectReference(kind, id));
            if (result == null) throw new NoSuchElementException(id);
            return result;
        }

        boolean contains(final ModelObjectKind kind, final String id) {
            return values.containsKey(new ModelObjectReference(kind, id));
        }

        @Override public List<ModelObjectDescriptor> list() {
            return List.copyOf(values.values());
        }

        @Override public ModelObjectDescriptor rename(
            final ModelObjectReference target,
            final String name
        ) {
            final ModelObjectDescriptor current = values.get(target);
            if (current == null) throw new NoSuchElementException(target.id());
            final ModelObjectDescriptor renamed = new ModelObjectDescriptor(
                current.reference(), name, current.parent()
            );
            values.put(target, renamed);
            return renamed;
        }

        @Override public ModelObjectDescriptor reparent(
            final ModelObjectReference target,
            final ModelObjectReference parent,
            final int index
        ) {
            final ModelObjectDescriptor current = values.get(target);
            if (current == null) throw new NoSuchElementException(target.id());
            if (!values.containsKey(parent)) throw new NoSuchElementException(parent.id());
            lastReparentTarget = target;
            lastReparentParent = parent;
            lastReparentIndex = index;
            final ModelObjectDescriptor reparented = new ModelObjectDescriptor(
                current.reference(), current.name(), Optional.of(parent)
            );
            values.put(target, reparented);
            return reparented;
        }

        @Override public ModelObjectDescriptor create(final ModelObjectCreateRequest request) {
            lastCreate = request;
            final ModelObjectReference reference = new ModelObjectReference(
                request.kind(),
                "Generated" + generated.incrementAndGet()
            );
            final ModelObjectDescriptor created = new ModelObjectDescriptor(
                reference, request.name(), request.parent()
            );
            values.put(reference, created);
            return created;
        }

        @Override public void delete(
            final ModelObjectReference target,
            final ModelObjectDeletePolicy policy
        ) {
            lastDelete = target;
            lastDeletePolicy = policy;
            if (values.remove(target) == null) throw new NoSuchElementException(target.id());
        }
    }

    private static final class CapturingLogger implements PluginLogger {
        private final List<String> info = new ArrayList<>();

        @Override public void debug(final String message) { }
        @Override public void info(final String message) { info.add(message); }
        @Override public void warn(final String message) { }
        @Override public void error(final String message) { }
        @Override public void error(final String message, final Throwable throwable) { }
    }
}
