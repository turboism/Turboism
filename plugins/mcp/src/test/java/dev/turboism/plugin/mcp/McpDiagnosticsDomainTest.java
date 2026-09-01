package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.core.CoreCapabilities;
import dev.turboism.sdk.cubism.core.CoreRuntimeInfo;
import dev.turboism.sdk.cubism.core.CoreVersion;
import dev.turboism.sdk.cubism.core.MocInspector;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.id.TextureAtlasId;
import dev.turboism.sdk.cubism.model.AtlasTexture;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.ModelImageEntry;
import dev.turboism.sdk.cubism.model.ModelImageGroup;
import dev.turboism.sdk.cubism.model.ModelStatistics;
import dev.turboism.sdk.cubism.model.ModelTextures;
import dev.turboism.sdk.cubism.model.RawTexture;
import dev.turboism.sdk.cubism.model.ParameterDefinitions;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Parts;
import dev.turboism.sdk.cubism.transaction.TransactionManager;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.ui.workspace.WorkspaceId;
import dev.turboism.sdk.ui.workspace.WorkspaceInfo;
import dev.turboism.sdk.ui.workspace.WorkspaceOperationResult;
import dev.turboism.sdk.ui.workspace.WorkspaceService;
import dev.turboism.sdk.ui.workspace.WorkspaceStatus;
import dev.turboism.sdk.ui.workspace.layout.PaletteDock;
import dev.turboism.sdk.ui.workspace.layout.PaletteTab;
import dev.turboism.sdk.ui.workspace.layout.SplitDock;
import dev.turboism.sdk.ui.workspace.layout.WorkspaceLayoutService;
import dev.turboism.sdk.ui.workspace.layout.WorkspaceLayoutSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class McpDiagnosticsDomainTest {

    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-09-01T12:00:00Z"), ZoneOffset.UTC
    );

    @Test
    void coreResourceProjectsVersionAndCapabilitiesThroughThePublicCatalog() {
        final boolean[] uiExecution = {false};
        final McpDiagnosticsDomain domain = new McpDiagnosticsDomain(
            facade(coreRuntime(uiExecution)),
            WorkspaceService.unavailable(),
            WorkspaceLayoutService.unavailable(),
            diagnostics(),
            new McpExecutionBridge(immediateUi(), Duration.ofSeconds(1))
        );

        final Map<String, Object> payload = payload(
            domain.resourceCatalog(),
            McpDiagnosticsDomain.CUBISM_CORE
        );

        assertEquals(Map.of("major", 6L, "minor", 0L, "patch", 257L), payload.get("version"));
        assertEquals(Map.of(
            "parameterRepeat", true,
            "drawableTypedFlags", true,
            "mocInspection", false
        ), payload.get("capabilities"));
        assertEquals(true, uiExecution[0]);
        assertEquals(8, domain.resourceCatalog().resources().size());
    }

    @Test
    void workspaceResourcesPreserveTypedAvailabilityAndOrderedDockStructure() {
        final WorkspaceStatus workspaceStatus = new WorkspaceStatus(
            WorkspaceStatus.Availability.AVAILABLE,
            Optional.of(new WorkspaceInfo(new WorkspaceId("modeling"), "Modeling")),
            List.of(
                new WorkspaceInfo(new WorkspaceId("modeling"), "Modeling"),
                new WorkspaceInfo(new WorkspaceId("animation"), "Animation")
            ),
            Optional.empty()
        );
        final WorkspaceLayoutSnapshot layout = new WorkspaceLayoutSnapshot(
            WorkspaceLayoutSnapshot.Availability.AVAILABLE,
            Optional.of(new SplitDock(List.of(
                new PaletteDock(List.of(new PaletteTab("parts"), new PaletteTab("parameters"))),
                new PaletteDock(List.of(new PaletteTab("inspector")))
            ))),
            Optional.empty()
        );
        final McpDiagnosticsDomain domain = domain(
            workspace(workspaceStatus),
            workspaceLayout(layout)
        );

        final Map<String, Object> statusPayload = payload(
            domain.resourceCatalog(), McpDiagnosticsDomain.WORKSPACE
        );
        assertEquals("workspace", statusPayload.get("kind"));
        assertEquals("cubism", statusPayload.get("provider"));
        assertEquals("AVAILABLE", statusPayload.get("availability"));
        assertEquals(Map.of("id", "modeling", "displayName", "Modeling"),
            statusPayload.get("current"));
        assertEquals(2, list(statusPayload.get("available")).size());
        assertEquals(null, statusPayload.get("diagnosticCode"));

        final Map<String, Object> layoutPayload = payload(
            domain.resourceCatalog(), McpDiagnosticsDomain.WORKSPACE_LAYOUT
        );
        assertEquals("workspace-layout", layoutPayload.get("kind"));
        assertEquals("cubism", layoutPayload.get("provider"));
        assertEquals("AVAILABLE", layoutPayload.get("availability"));
        final Map<String, Object> root = object(layoutPayload.get("root"));
        assertEquals("split", root.get("type"));
        final List<Object> children = list(root.get("children"));
        assertEquals(List.of("parts", "parameters"), list(object(children.get(0)).get("tabs"))
            .stream().map(McpDiagnosticsDomainTest::object).map(tab -> tab.get("paletteId")).toList());
        assertEquals("inspector", object(list(object(children.get(1)).get("tabs")).get(0))
            .get("paletteId"));

        final Map<String, Object> workspaceDefinition = resourceDefinition(
            domain.resourceCatalog(), McpDiagnosticsDomain.WORKSPACE
        );
        final Map<String, Object> layoutDefinition = resourceDefinition(
            domain.resourceCatalog(), McpDiagnosticsDomain.WORKSPACE_LAYOUT
        );
        assertEquals("Cubism workspaces", workspaceDefinition.get("title"));
        assertEquals("Cubism workspace layout", layoutDefinition.get("title"));
        assertTrue(((String) workspaceDefinition.get("description")).contains("exposed separately"));
        assertTrue(((String) layoutDefinition.get("description")).contains("exposed separately"));
    }

    @Test
    void unavailableWorkspaceSnapshotsRemainSuccessfulTypedDocuments() {
        final McpDiagnosticsDomain domain = domain(
            WorkspaceService.unavailable(),
            WorkspaceLayoutService.unavailable()
        );

        final Map<String, Object> workspacePayload = payload(
            domain.resourceCatalog(), McpDiagnosticsDomain.WORKSPACE
        );
        assertEquals("UNAVAILABLE", workspacePayload.get("availability"));
        assertEquals(null, workspacePayload.get("current"));
        assertEquals(List.of(), workspacePayload.get("available"));
        assertEquals("workspace.unavailable", workspacePayload.get("diagnosticCode"));

        final Map<String, Object> layoutPayload = payload(
            domain.resourceCatalog(), McpDiagnosticsDomain.WORKSPACE_LAYOUT
        );
        assertEquals("UNAVAILABLE", layoutPayload.get("availability"));
        assertEquals(null, layoutPayload.get("root"));
        assertEquals("workspace.layout.unavailable", layoutPayload.get("diagnosticCode"));
    }

    @Test
    void modelStatisticsPreserveCrossVersionOptionalValues() {
        final ModelStatistics statistics = new ModelStatistics(
            10, 4, 7, 6, 3, 120, 60, 2, 4, 2,
            OptionalInt.empty(), OptionalInt.of(3)
        );
        final McpDiagnosticsDomain domain = domain(model(statistics, unavailableTextures()));

        final Map<String, Object> payload = payload(
            domain.resourceCatalog(), McpDiagnosticsDomain.MODEL_STATISTICS
        );

        assertEquals(10L, payload.get("parameterCount"));
        assertEquals(6L, payload.get("artMeshCount"));
        assertEquals(120L, payload.get("vertexCount"));
        assertEquals(null, payload.get("offscreenRenderingCount"));
        assertEquals(3L, payload.get("maxOffscreenDepth"));
    }

    @Test
    void missingActiveModelMakesModelDiagnosticResourcesUnavailable() {
        final McpDiagnosticsDomain domain = domain(facadeWithoutActiveModel());

        for (String uri : List.of(
            McpDiagnosticsDomain.MODEL_STATISTICS,
            McpDiagnosticsDomain.MODEL_TEXTURES
        )) {
            final McpResourceCatalog.ResourceFailure failure = assertThrows(
                McpResourceCatalog.ResourceFailure.class,
                () -> domain.resourceCatalog().read(uri)
            );
            assertEquals(McpResourceCatalog.ResourceFailure.Kind.UNAVAILABLE, failure.kind());
        }
    }

    @Test
    void modelTexturesProjectTypedIdsAndNestedGroupsWithoutInvokingWrites() {
        final ModelTextures textures = textures();
        final McpDiagnosticsDomain domain = domain(model(
            new ModelStatistics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                OptionalInt.empty(), OptionalInt.empty()),
            textures
        ));

        final Map<String, Object> payload = payload(
            domain.resourceCatalog(), McpDiagnosticsDomain.MODEL_TEXTURES
        );

        final Map<String, Object> raw = object(list(payload.get("rawImages")).get(0));
        assertEquals("raw-1", raw.get("id"));
        assertEquals("Artwork", raw.get("name"));
        assertEquals(2048L, raw.get("width"));

        final Map<String, Object> group = object(list(payload.get("modelImageGroups")).get(0));
        assertEquals("Body", group.get("groupName"));
        assertEquals("Primary textures", group.get("memo"));
        assertEquals("image-1", object(list(group.get("modelImages")).get(0)).get("id"));

        final Map<String, Object> atlas = object(list(payload.get("textureAtlases")).get(0));
        assertEquals("atlas-1", atlas.get("id"));
        assertEquals(3L, atlas.get("atlasVersion"));
        assertEquals(1L, atlas.get("modelImageCount"));
    }

    @Test
    void diagnosticsSanitizePathsControlsAndOversizedProblemLists() {
        final List<DiagnosticReport.Problem> problems = new java.util.ArrayList<>();
        problems.add(problem(
            "MAPPING_FAILED",
            "Failed token=super-secret sessionId=session-secret at /opt/private/model.cmo3\n"
                + "then C:\\Users\\example\\secret.txt and file:///tmp/private.json\u0007",
            DiagnosticReport.Severity.ERROR
        ));
        for (int index = 0; index < 105; index++) {
            problems.add(problem("INFO_" + index, "detail " + index, DiagnosticReport.Severity.INFO));
        }
        final McpDiagnosticsDomain domain = domain(
            diagnostics(problems),
            new McpRuntimeDiagnostics(8, CLOCK),
            McpResourceCatalog.empty()
        );

        final Map<String, Object> payload = payload(
            domain.resourceCatalog(), McpDiagnosticsDomain.DIAGNOSTICS
        );

        assertEquals("startup", payload.get("kind"));
        assertEquals("turboism", payload.get("provider"));
        assertEquals("1970-01-01T00:00:00Z", payload.get("createdAt"));
        assertEquals("2026-09-01T12:00:00Z", payload.get("asOf"));
        assertEquals(true, payload.get("truncated"));
        assertEquals(100, list(payload.get("problems")).size());
        final Map<String, Object> first = object(list(payload.get("problems")).get(0));
        assertEquals("MAPPING_FAILED", first.get("code"));
        assertEquals("ERROR", first.get("severity"));
        assertFalse(first.containsKey("path"));
        final String message = (String) first.get("message");
        assertFalse(message.contains("/opt/private"));
        assertFalse(message.contains("C:\\Users"));
        assertFalse(message.contains("file:"));
        assertFalse(message.contains("super-secret"));
        assertFalse(message.contains("session-secret"));
        assertFalse(message.contains("\n"));
        assertFalse(message.contains("\u0007"));
        assertTrue(message.contains("[redacted-token]"));
        assertTrue(message.contains("[redacted-session]"));
        assertTrue(message.contains("[redacted-path]"));
    }

    @Test
    void parameterBindingAggregateReadsTheExistingParameterAndBindingTemplates() {
        final List<String> requested = new java.util.ArrayList<>();
        final McpResourceCatalog parameterResources = new McpResourceCatalog(
            List.of(Map.of("uri", McpParameterDomain.PARAMETERS_URI)),
            List.of(
                Map.of("uriTemplate", McpParameterDomain.PARAMETER_URI_TEMPLATE),
                Map.of("uriTemplate", McpParameterDomain.BINDINGS_URI_TEMPLATE)
            ),
            uri -> {
                requested.add(uri);
                if (McpParameterDomain.PARAMETERS_URI.equals(uri)) {
                    return resourceContent(uri, Map.of("parameters", List.of(
                        Map.of("id", "ParamA"),
                        Map.of("id", "Param A+B")
                    )));
                }
                if (uri.equals("turboism://active/model/parameters/ParamA/bindings")) {
                    return resourceContent(uri, Map.of(
                        "parameterId", "ParamA",
                        "bindings", List.of(Map.of("family", "keyform_grid"))
                    ));
                }
                if (uri.equals("turboism://active/model/parameters/Param%20A%2BB/bindings")) {
                    return resourceContent(uri, Map.of(
                        "parameterId", "Param A+B",
                        "bindings", List.of()
                    ));
                }
                throw new McpResourceCatalog.ResourceNotFound(uri);
            }
        );
        final McpDiagnosticsDomain domain = domain(
            diagnostics(), new McpRuntimeDiagnostics(8, CLOCK), parameterResources
        );

        final Map<String, Object> payload = payload(
            domain.resourceCatalog(), McpDiagnosticsDomain.PARAMETER_BINDINGS
        );

        assertEquals("parameter-bindings", payload.get("kind"));
        assertEquals("cubism", payload.get("provider"));
        final List<Object> aggregate = list(payload.get("parameterBindings"));
        assertEquals(2, aggregate.size());
        assertEquals("ParamA", object(aggregate.get(0)).get("parameterId"));
        assertEquals("Param A+B", object(aggregate.get(1)).get("parameterId"));
        assertEquals(List.of(
            McpParameterDomain.PARAMETERS_URI,
            "turboism://active/model/parameters/ParamA/bindings",
            "turboism://active/model/parameters/Param%20A%2BB/bindings"
        ), requested);
    }

    @Test
    void runtimeDiagnosticsHaveNoFabricatedCreatedAtAndStartupPreservesAnUnavailableOne() {
        final McpRuntimeDiagnostics runtime = new McpRuntimeDiagnostics(8, CLOCK);
        final McpToolCatalog observed = runtime.observe(new McpToolCatalog(
            List.of(Map.of("name", "turboism.test")),
            (name, arguments) -> { throw new RuntimeException("token=secret /private/model.cmo3"); }
        ));
        assertThrows(RuntimeException.class, () -> observed.call("turboism.test", Map.of()));
        final DiagnosticReport unavailableCreationTime = new DiagnosticReport() {
            @Override public Instant createdAt() { return null; }
            @Override public List<Problem> problems() { return List.of(); }
        };
        final McpDiagnosticsDomain domain = domain(
            unavailableCreationTime, runtime, McpResourceCatalog.empty()
        );

        final Map<String, Object> startup = payload(
            domain.resourceCatalog(), McpDiagnosticsDomain.DIAGNOSTICS
        );
        assertEquals(null, startup.get("createdAt"));
        assertEquals("2026-09-01T12:00:00Z", startup.get("asOf"));

        final Map<String, Object> recent = payload(
            domain.resourceCatalog(), McpDiagnosticsDomain.RUNTIME_DIAGNOSTICS
        );
        assertEquals("runtime", recent.get("kind"));
        assertEquals("turboism-mcp", recent.get("provider"));
        assertEquals("2026-09-01T12:00:00Z", recent.get("asOf"));
        assertFalse(recent.containsKey("createdAt"));
        assertEquals(false, recent.get("truncated"));
        assertEquals(0L, recent.get("dropped"));
        final Map<String, Object> event = object(list(recent.get("events")).get(0));
        assertEquals("RUNTIME_EXCEPTION", event.get("kind"));
        assertEquals("turboism.test", event.get("provider"));
        assertFalse(((String) event.get("message")).contains("secret"));
        assertFalse(((String) event.get("message")).contains("/private"));
    }

    @Test
    void resourceCatalogClassifiesPermissionUnavailableAndCancellationFailures() {
        final McpDiagnosticsDomain denied = domain(facadeFailure(
            new CubismPermissionException("model read denied")
        ));
        final McpResourceCatalog.ResourceFailure deniedFailure = assertThrows(
            McpResourceCatalog.ResourceFailure.class,
            () -> denied.resourceCatalog().read(McpDiagnosticsDomain.CUBISM_CORE)
        );
        assertEquals(McpResourceCatalog.ResourceFailure.Kind.PERMISSION_DENIED, deniedFailure.kind());

        final McpDiagnosticsDomain unavailable = domain(facadeFailure(
            new UnsupportedOperationException("core unavailable")
        ));
        final McpResourceCatalog.ResourceFailure unavailableFailure = assertThrows(
            McpResourceCatalog.ResourceFailure.class,
            () -> unavailable.resourceCatalog().read(McpDiagnosticsDomain.CUBISM_CORE)
        );
        assertEquals(McpResourceCatalog.ResourceFailure.Kind.UNAVAILABLE, unavailableFailure.kind());

        final McpDiagnosticsDomain cancelled = domain(facadeFailure(
            new java.util.concurrent.CancellationException()
        ));
        assertThrows(
            java.util.concurrent.CancellationException.class,
            () -> cancelled.resourceCatalog().read(McpDiagnosticsDomain.CUBISM_CORE)
        );
    }

    private static McpDiagnosticsDomain domain(final CubismFacade facade) {
        return new McpDiagnosticsDomain(
            facade,
            WorkspaceService.unavailable(),
            WorkspaceLayoutService.unavailable(),
            diagnostics(),
            new McpExecutionBridge(immediateUi(), Duration.ofSeconds(1))
        );
    }

    private static CubismFacade facadeWithoutActiveModel() {
        return new CubismFacade() {
            @Override public CubismRuntimeSnapshot runtime() { throw unavailable(); }
            @Override public Optional<ProjectSnapshot> activeProject() { return Optional.empty(); }
            @Override public Optional<DocumentSnapshot> activeDocument() { return Optional.empty(); }
            @Override public Optional<ModelSnapshot> activeModel() { return Optional.empty(); }
            @Override public boolean isHostPresent() { return true; }
            @Override public CoreRuntimeInfo coreRuntime() {
                return McpDiagnosticsDomainTest.coreRuntime(new boolean[1]);
            }
            @Override public CubismModelAccess model() {
                return () -> { throw new IllegalStateException("No active Cubism model"); };
            }
            @Override public TransactionManager transactionManager() { throw unavailable(); }
        };
    }

    private static CubismFacade facadeFailure(final RuntimeException failure) {
        return new CubismFacade() {
            @Override public CubismRuntimeSnapshot runtime() { throw failure; }
            @Override public Optional<ProjectSnapshot> activeProject() { throw failure; }
            @Override public Optional<DocumentSnapshot> activeDocument() { throw failure; }
            @Override public Optional<ModelSnapshot> activeModel() { throw failure; }
            @Override public boolean isHostPresent() { throw failure; }
            @Override public CoreRuntimeInfo coreRuntime() { throw failure; }
            @Override public CubismModelAccess model() { throw failure; }
            @Override public TransactionManager transactionManager() { throw failure; }
        };
    }

    private static McpDiagnosticsDomain domain(final DiagnosticReport diagnostics) {
        return new McpDiagnosticsDomain(
            facade(coreRuntime(new boolean[1])),
            WorkspaceService.unavailable(),
            WorkspaceLayoutService.unavailable(),
            diagnostics,
            new McpExecutionBridge(immediateUi(), Duration.ofSeconds(1))
        );
    }

    private static McpDiagnosticsDomain domain(
        final DiagnosticReport diagnostics,
        final McpRuntimeDiagnostics runtimeDiagnostics,
        final McpResourceCatalog parameterResources
    ) {
        return new McpDiagnosticsDomain(
            facade(coreRuntime(new boolean[1])),
            WorkspaceService.unavailable(),
            WorkspaceLayoutService.unavailable(),
            diagnostics,
            runtimeDiagnostics,
            parameterResources,
            new McpExecutionBridge(immediateUi(), Duration.ofSeconds(1)),
            CLOCK
        );
    }

    private static DiagnosticReport diagnostics(final List<DiagnosticReport.Problem> problems) {
        return new DiagnosticReport() {
            @Override public Instant createdAt() { return Instant.EPOCH; }
            @Override public List<Problem> problems() { return List.copyOf(problems); }
        };
    }

    private static DiagnosticReport.Problem problem(
        final String code,
        final String message,
        final DiagnosticReport.Severity severity
    ) {
        return new DiagnosticReport.Problem() {
            @Override public String code() { return code; }
            @Override public String message() { return message; }
            @Override public String path() { return "/must/not/be/serialized"; }
            @Override public DiagnosticReport.Severity severity() { return severity; }
        };
    }

    private static McpDiagnosticsDomain domain(final CubismModel model) {
        return new McpDiagnosticsDomain(
            facade(coreRuntime(new boolean[1]), model),
            WorkspaceService.unavailable(),
            WorkspaceLayoutService.unavailable(),
            diagnostics(),
            new McpExecutionBridge(immediateUi(), Duration.ofSeconds(1))
        );
    }

    private static McpDiagnosticsDomain domain(
        final WorkspaceService workspace,
        final WorkspaceLayoutService workspaceLayout
    ) {
        return new McpDiagnosticsDomain(
            facade(coreRuntime(new boolean[1])),
            workspace,
            workspaceLayout,
            diagnostics(),
            new McpExecutionBridge(immediateUi(), Duration.ofSeconds(1))
        );
    }

    private static WorkspaceService workspace(final WorkspaceStatus status) {
        return new WorkspaceService() {
            @Override public CompletionStage<WorkspaceStatus> current() {
                return CompletableFuture.completedFuture(status);
            }
            @Override public CompletionStage<WorkspaceOperationResult> switchTo(final WorkspaceId id) {
                throw new AssertionError("Workspace mutation must not be called");
            }
            @Override public CompletionStage<WorkspaceOperationResult> updateDefault() {
                throw new AssertionError("Workspace mutation must not be called");
            }
            @Override public CompletionStage<WorkspaceOperationResult> resetToDefault() {
                throw new AssertionError("Workspace mutation must not be called");
            }
        };
    }

    private static WorkspaceLayoutService workspaceLayout(final WorkspaceLayoutSnapshot snapshot) {
        return () -> CompletableFuture.completedFuture(snapshot);
    }

    private static CoreRuntimeInfo coreRuntime(final boolean[] uiExecution) {
        return new CoreRuntimeInfo() {
            @Override
            public CoreVersion version() {
                uiExecution[0] = true;
                return new CoreVersion(6, 0, 257);
            }

            @Override
            public CoreCapabilities capabilities() {
                return new CoreCapabilities(true, true, false);
            }

            @Override
            public MocInspector mocInspector() {
                throw new AssertionError("MOC inspection must not be exposed by this resource");
            }
        };
    }

    private static CubismFacade facade(final CoreRuntimeInfo coreRuntime) {
        return facade(coreRuntime, model());
    }

    private static CubismFacade facade(
        final CoreRuntimeInfo coreRuntime,
        final CubismModel model
    ) {
        return new CubismFacade() {
            @Override public CubismRuntimeSnapshot runtime() { throw unavailable(); }
            @Override public Optional<ProjectSnapshot> activeProject() { return Optional.empty(); }
            @Override public Optional<DocumentSnapshot> activeDocument() { return Optional.empty(); }
            @Override public Optional<ModelSnapshot> activeModel() { return Optional.empty(); }
            @Override public boolean isHostPresent() { return true; }
            @Override public CoreRuntimeInfo coreRuntime() { return coreRuntime; }
            @Override public CubismModelAccess model() { return () -> model; }
            @Override public TransactionManager transactionManager() { throw unavailable(); }
        };
    }

    private static CubismModel model() {
        return model(null, null);
    }

    private static CubismModel model(
        final ModelStatistics statistics,
        final ModelTextures textures
    ) {
        return new CubismModel() {
            @Override public ModelId id() { return new ModelId("Model1"); }
            @Override public ParameterDefinitions parameterDefinitions() { throw unavailable(); }
            @Override public ModelTextures textures() {
                if (textures == null) throw unavailable();
                return textures;
            }
            @Override public ModelStatistics statistics() {
                if (statistics == null) throw unavailable();
                return statistics;
            }
            @Override public Parameters parameters() { throw unavailable(); }
            @Override public Parts parts() { throw unavailable(); }
            @Override public Drawables drawables() { throw unavailable(); }
            @Override public Deformers deformers() { throw unavailable(); }
            @Override public Glues glues() { throw unavailable(); }
            @Override public void update() { throw unavailable(); }
        };
    }

    private static ModelTextures textures() {
        return new ModelTextures() {
            @Override public List<RawTexture> rawImages() {
                return List.of(new RawTexture() {
                    @Override public RawImageId id() { return new RawImageId("raw-1"); }
                    @Override public String name() { return "Artwork"; }
                    @Override public int width() { return 2048; }
                    @Override public int height() { return 1024; }
                });
            }

            @Override public List<ModelImageGroup> modelImageGroups() {
                return List.of(new ModelImageGroup() {
                    @Override public String groupName() { return "Body"; }
                    @Override public String memo() { return "Primary textures"; }
                    @Override public List<ModelImageEntry> modelImages() {
                        return List.of(new ModelImageEntry() {
                            @Override public ModelImageId id() { return new ModelImageId("image-1"); }
                            @Override public String name() { return "Body diffuse"; }
                            @Override public int width() { return 1024; }
                            @Override public int height() { return 1024; }
                        });
                    }
                });
            }

            @Override public List<AtlasTexture> textureAtlases() {
                return List.of(new AtlasTexture() {
                    @Override public TextureAtlasId id() { return new TextureAtlasId("atlas-1"); }
                    @Override public String name() { return "Atlas 1"; }
                    @Override public int width() { return 2048; }
                    @Override public int height() { return 2048; }
                    @Override public int atlasVersion() { return 3; }
                    @Override public int modelImageCount() { return 1; }
                });
            }

            @Override public void addModelImageGroup(final String name) {
                throw new AssertionError("Texture mutation must not be called");
            }
            @Override public void removeModelImage(final ModelImageId id) {
                throw new AssertionError("Texture mutation must not be called");
            }
            @Override public TextureAtlasId addTextureAtlas(
                final String name, final int width, final int height
            ) {
                throw new AssertionError("Texture mutation must not be called");
            }
            @Override public void removeTextureAtlas(final TextureAtlasId id) {
                throw new AssertionError("Texture mutation must not be called");
            }
            @Override public void removeRawImage(final RawImageId id) {
                throw new AssertionError("Texture mutation must not be called");
            }
        };
    }

    private static ModelTextures unavailableTextures() {
        return new ModelTextures() {
            @Override public List<RawTexture> rawImages() { throw unavailable(); }
            @Override public List<ModelImageGroup> modelImageGroups() { throw unavailable(); }
            @Override public List<AtlasTexture> textureAtlases() { throw unavailable(); }
            @Override public void addModelImageGroup(final String name) { throw unavailable(); }
            @Override public void removeModelImage(final ModelImageId id) { throw unavailable(); }
            @Override public TextureAtlasId addTextureAtlas(
                final String name, final int width, final int height
            ) { throw unavailable(); }
            @Override public void removeTextureAtlas(final TextureAtlasId id) { throw unavailable(); }
            @Override public void removeRawImage(final RawImageId id) { throw unavailable(); }
        };
    }

    private static DiagnosticReport diagnostics() {
        return new DiagnosticReport() {
            @Override public Instant createdAt() { return Instant.EPOCH; }
            @Override public List<Problem> problems() { return List.of(); }
        };
    }

    private static UiScheduler immediateUi() {
        return new UiScheduler() {
            @Override
            public dev.turboism.sdk.plugin.Registration runOnUiThread(final Runnable work) {
                work.run();
                return () -> { };
            }

            @Override
            public dev.turboism.sdk.plugin.Registration runOnUiThreadLater(
                final Runnable work,
                final Duration delay
            ) {
                throw new AssertionError("Delayed UI work is not expected");
            }
        };
    }

    private static List<Map<String, Object>> resourceContent(
        final String uri,
        final Map<String, Object> payload
    ) {
        return List.of(Map.of(
            "uri", uri,
            "mimeType", "application/json",
            "text", Json.stringify(payload)
        ));
    }

    private static Map<String, Object> resourceDefinition(
        final McpResourceCatalog catalog,
        final String uri
    ) {
        return catalog.resources().stream()
            .filter(resource -> uri.equals(resource.get("uri")))
            .findFirst()
            .orElseThrow();
    }

    private static Map<String, Object> payload(
        final McpResourceCatalog catalog,
        final String uri
    ) {
        final Map<String, Object> content = catalog.read(uri).get(0);
        return object(Json.parse(
            ((String) content.get("text")).getBytes(java.nio.charset.StandardCharsets.UTF_8)
        ));
    }

    private static UnsupportedOperationException unavailable() {
        return new UnsupportedOperationException("not used by this test");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(final Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(final Object value) {
        return (List<Object>) value;
    }
}
