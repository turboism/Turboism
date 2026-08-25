package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.core.CoreCapabilities;
import dev.turboism.sdk.cubism.core.CoreRuntimeInfo;
import dev.turboism.sdk.cubism.core.CoreVersion;
import dev.turboism.sdk.cubism.model.AtlasTexture;
import dev.turboism.sdk.cubism.model.ModelImageEntry;
import dev.turboism.sdk.cubism.model.ModelImageGroup;
import dev.turboism.sdk.cubism.model.ModelStatistics;
import dev.turboism.sdk.cubism.model.ModelTextures;
import dev.turboism.sdk.cubism.model.RawTexture;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.ui.workspace.WorkspaceInfo;
import dev.turboism.sdk.ui.workspace.WorkspaceService;
import dev.turboism.sdk.ui.workspace.WorkspaceStatus;
import dev.turboism.sdk.ui.workspace.layout.DockComponent;
import dev.turboism.sdk.ui.workspace.layout.PaletteDock;
import dev.turboism.sdk.ui.workspace.layout.PaletteTab;
import dev.turboism.sdk.ui.workspace.layout.SplitDock;
import dev.turboism.sdk.ui.workspace.layout.WorkspaceLayoutService;
import dev.turboism.sdk.ui.workspace.layout.WorkspaceLayoutSnapshot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Read-only MCP resources for Cubism, workspace, model, and runtime diagnostics. */
final class McpDiagnosticsDomain {

    static final String CUBISM_CORE = "turboism://environment/cubism-core";
    static final String WORKSPACE = "turboism://environment/workspace";
    static final String WORKSPACE_LAYOUT = "turboism://environment/workspace/layout";
    static final String MODEL_STATISTICS = "turboism://active/model/statistics";
    static final String MODEL_TEXTURES = "turboism://active/model/textures";
    static final String DIAGNOSTICS = "turboism://environment/diagnostics";

    private static final int MAX_DIAGNOSTIC_PROBLEMS = 100;
    private static final int MAX_DIAGNOSTIC_MESSAGE_CHARS = 512;
    private static final Pattern FILE_URI = Pattern.compile(
        "(?i)file:(?://)?[^\\s]+"
    );
    private static final Pattern WINDOWS_PATH = Pattern.compile(
        "(?i)(?:[a-z]:\\\\|\\\\\\\\)[^\\s]+"
    );
    private static final Pattern UNIX_PATH = Pattern.compile(
        "(?<![A-Za-z0-9_.-])/(?:[^\\s/]+/)*[^\\s]+"
    );

    private final CubismFacade cubism;
    private final WorkspaceService workspace;
    private final WorkspaceLayoutService workspaceLayout;
    private final DiagnosticReport diagnostics;
    private final McpExecutionBridge execution;

    McpDiagnosticsDomain(
        final CubismFacade cubism,
        final WorkspaceService workspace,
        final WorkspaceLayoutService workspaceLayout,
        final DiagnosticReport diagnostics,
        final McpExecutionBridge execution
    ) {
        this.cubism = Objects.requireNonNull(cubism, "cubism");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.workspaceLayout = Objects.requireNonNull(workspaceLayout, "workspaceLayout");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.execution = Objects.requireNonNull(execution, "execution");
    }

    McpResourceCatalog resourceCatalog() {
        return new McpResourceCatalog(resources(), List.of(), this::read);
    }

    private List<Map<String, Object>> resources() {
        return List.of(
            resource(CUBISM_CORE, "Cubism Core", "The admitted Cubism Core version and public capabilities."),
            resource(WORKSPACE, "Workspace status", "The current Cubism workspace and available workspace choices."),
            resource(WORKSPACE_LAYOUT, "Workspace layout", "The current read-only Cubism workspace dock tree."),
            resource(MODEL_STATISTICS, "Active model statistics", "Structural and rendering statistics for the active model."),
            resource(MODEL_TEXTURES, "Active model textures", "The active model texture library without file paths or image bytes."),
            resource(DIAGNOSTICS, "Runtime diagnostics", "Sanitized Turboism diagnostics without source paths.")
        );
    }

    private List<Map<String, Object>> read(final String uri) {
        final Map<String, Object> payload = switch (uri) {
            case CUBISM_CORE -> core();
            case WORKSPACE -> workspace();
            case WORKSPACE_LAYOUT -> workspaceLayout();
            case MODEL_STATISTICS -> modelStatistics();
            case MODEL_TEXTURES -> modelTextures();
            case DIAGNOSTICS -> diagnostics();
            default -> throw new McpResourceCatalog.ResourceNotFound(uri);
        };
        return List.of(linked(
            entry("uri", uri),
            entry("mimeType", "application/json"),
            entry("text", Json.stringify(payload))
        ));
    }

    private Map<String, Object> core() {
        return execution.ui(() -> {
            final CoreRuntimeInfo runtime = cubism.coreRuntime();
            final CoreVersion version = runtime.version();
            final CoreCapabilities capabilities = runtime.capabilities();
            return linked(
                entry("version", linked(
                    entry("major", version.major()),
                    entry("minor", version.minor()),
                    entry("patch", version.patch())
                )),
                entry("capabilities", linked(
                    entry("parameterRepeat", capabilities.parameterRepeat()),
                    entry("drawableTypedFlags", capabilities.drawableTypedFlags()),
                    entry("mocInspection", capabilities.mocInspection())
                ))
            );
        });
    }

    private Map<String, Object> diagnostics() {
        return execution.direct(() -> {
            final List<DiagnosticReport.Problem> problems = List.copyOf(diagnostics.problems());
            return linked(
                entry("createdAt", diagnostics.createdAt().toString()),
                entry("problems", problems.stream()
                    .limit(MAX_DIAGNOSTIC_PROBLEMS)
                    .map(McpDiagnosticsDomain::problem).toList()),
                entry("truncated", problems.size() > MAX_DIAGNOSTIC_PROBLEMS)
            );
        });
    }

    private static Map<String, Object> problem(final DiagnosticReport.Problem value) {
        return linked(
            entry("code", value.code()),
            entry("severity", value.severity().name()),
            entry("message", sanitizedMessage(value.message()))
        );
    }

    private static String sanitizedMessage(final String value) {
        String text = Objects.requireNonNull(value, "message")
            .replaceAll("[\\p{Cc}\\p{Cf}]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
        text = FILE_URI.matcher(text).replaceAll("[redacted-path]");
        text = WINDOWS_PATH.matcher(text).replaceAll("[redacted-path]");
        text = UNIX_PATH.matcher(text).replaceAll("[redacted-path]");
        if (text.length() > MAX_DIAGNOSTIC_MESSAGE_CHARS) {
            text = text.substring(0, MAX_DIAGNOSTIC_MESSAGE_CHARS - 1) + "…";
        }
        return text;
    }

    private Map<String, Object> modelStatistics() {
        return execution.ui(() -> {
            final ModelStatistics value = cubism.model().active().statistics();
            return linked(
                entry("parameterCount", value.parameterCount()),
                entry("partCount", value.partCount()),
                entry("drawableCount", value.drawableCount()),
                entry("artMeshCount", value.artMeshCount()),
                entry("deformerCount", value.deformerCount()),
                entry("vertexCount", value.vertexCount()),
                entry("triangleCount", value.triangleCount()),
                entry("textureCount", value.textureCount()),
                entry("maskedDrawableCount", value.maskedDrawableCount()),
                entry("maskGroupCount", value.maskGroupCount()),
                entry("offscreenRenderingCount", optionalInt(value.offscreenRenderingCount())),
                entry("maxOffscreenDepth", optionalInt(value.maxOffscreenDepth()))
            );
        });
    }

    private Map<String, Object> modelTextures() {
        return execution.ui(() -> {
            final ModelTextures value = cubism.model().active().textures();
            return linked(
                entry("rawImages", value.rawImages().stream()
                    .map(McpDiagnosticsDomain::rawTexture).toList()),
                entry("modelImageGroups", value.modelImageGroups().stream()
                    .map(McpDiagnosticsDomain::modelImageGroup).toList()),
                entry("textureAtlases", value.textureAtlases().stream()
                    .map(McpDiagnosticsDomain::atlasTexture).toList())
            );
        });
    }

    private static Map<String, Object> rawTexture(final RawTexture value) {
        return linked(
            entry("id", value.id().value()),
            entry("name", value.name()),
            entry("width", value.width()),
            entry("height", value.height())
        );
    }

    private static Map<String, Object> modelImageGroup(final ModelImageGroup value) {
        return linked(
            entry("groupName", value.groupName()),
            entry("memo", value.memo()),
            entry("modelImages", value.modelImages().stream()
                .map(McpDiagnosticsDomain::modelImage).toList())
        );
    }

    private static Map<String, Object> modelImage(final ModelImageEntry value) {
        return linked(
            entry("id", value.id().value()),
            entry("name", value.name()),
            entry("width", value.width()),
            entry("height", value.height())
        );
    }

    private static Map<String, Object> atlasTexture(final AtlasTexture value) {
        return linked(
            entry("id", value.id().value()),
            entry("name", value.name()),
            entry("width", value.width()),
            entry("height", value.height()),
            entry("atlasVersion", value.atlasVersion()),
            entry("modelImageCount", value.modelImageCount())
        );
    }

    private static Integer optionalInt(final java.util.OptionalInt value) {
        return value.isPresent() ? value.getAsInt() : null;
    }

    private Map<String, Object> workspace() {
        final WorkspaceStatus status = execution.stage(workspace::current);
        return linked(
            entry("availability", status.availability().name()),
            entry("current", status.current().map(McpDiagnosticsDomain::workspaceInfo).orElse(null)),
            entry("available", status.available().stream()
                .map(McpDiagnosticsDomain::workspaceInfo).toList()),
            entry("diagnosticCode", status.diagnosticCode().orElse(null))
        );
    }

    private Map<String, Object> workspaceLayout() {
        final WorkspaceLayoutSnapshot snapshot = execution.stage(workspaceLayout::current);
        return linked(
            entry("availability", snapshot.availability().name()),
            entry("root", snapshot.root().map(McpDiagnosticsDomain::dock).orElse(null)),
            entry("diagnosticCode", snapshot.diagnosticCode().orElse(null))
        );
    }

    private static Map<String, Object> workspaceInfo(final WorkspaceInfo value) {
        return linked(
            entry("id", value.id().value()),
            entry("displayName", value.displayName())
        );
    }

    private static Map<String, Object> dock(final DockComponent value) {
        if (value instanceof SplitDock split) {
            return linked(
                entry("type", "split"),
                entry("children", split.children().stream()
                    .map(McpDiagnosticsDomain::dock).toList())
            );
        }
        if (value instanceof PaletteDock palette) {
            return linked(
                entry("type", "palette"),
                entry("tabs", palette.tabs().stream()
                    .map(McpDiagnosticsDomain::tab).toList())
            );
        }
        throw new IllegalStateException("Unsupported workspace dock component");
    }

    private static Map<String, Object> tab(final PaletteTab value) {
        return linked(entry("paletteId", value.paletteId()));
    }

    private static Map<String, Object> resource(
        final String uri,
        final String title,
        final String description
    ) {
        return linked(
            entry("uri", uri),
            entry("name", title.toLowerCase(java.util.Locale.ROOT).replace(' ', '-')),
            entry("title", title),
            entry("description", description),
            entry("mimeType", "application/json")
        );
    }

    @SafeVarargs
    private static LinkedHashMap<String, Object> linked(final Map.Entry<String, Object>... entries) {
        final LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : entries) result.put(entry.getKey(), entry.getValue());
        return result;
    }

    private static Map.Entry<String, Object> entry(final String key, final Object value) {
        return new java.util.AbstractMap.SimpleImmutableEntry<>(key, value);
    }
}
