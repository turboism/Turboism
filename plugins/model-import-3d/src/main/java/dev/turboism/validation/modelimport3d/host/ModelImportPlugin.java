package dev.turboism.validation.modelimport3d.host;

import dev.turboism.plugin.modelimport3d.viewer.ModelViewerWindow;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOutcome;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.*;
import dev.turboism.sdk.ui.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.swing.*;

/** User-mediated static model import using the same bounded engine as the native validation. */
public final class ModelImportPlugin implements TurboismPlugin {
    /**
     * Summary of planned texture atlas pages and dimensions for user confirmation.
     *
     * @param pageCount planned atlas page count
     * @param width     planned atlas page width in pixels
     * @param height    planned atlas page height in pixels
     */
    public record AtlasSummary(int pageCount, int width, int height) {}

    interface Ui {
        int appearance();

        boolean confirm(SurfaceImport.Prepared prepared);

        default boolean confirm(SurfaceImport.Prepared prepared, AtlasSummary atlas) {
            return confirm(prepared);
        }

        boolean confirmLayers(SurfaceWarpLayerAuthoring.Prepared prepared);

        default boolean confirmLayers(SurfaceWarpLayerAuthoring.Prepared prepared, AtlasSummary atlas) {
            return confirmLayers(prepared);
        }

        default boolean confirmVertices(SurfaceVertexAuthoring.Prepared prepared) {
            return false;
        }

        default boolean confirmVertices(SurfaceVertexAuthoring.Prepared prepared, AtlasSummary atlas) {
            return confirmVertices(prepared);
        }

        default boolean confirmVertices(SurfaceVertexAuthoring.Prepared prepared, OptimizationSummary optimization) {
            return confirmVertices(prepared);
        }

        default boolean confirmVertices(
                SurfaceVertexAuthoring.Prepared prepared, OptimizationSummary optimization, AtlasSummary atlas) {
            return confirmVertices(prepared, optimization);
        }

        default boolean confirmCharacter(NativeModelImport.Prepared prepared, AtlasSummary atlas) {
            return false;
        }

        void message(String text, boolean error);
    }

    interface LocalizedMessageCarrier {
        String messageKey();

        Object[] messageArgs();

        String getMessage();
    }

    static final class LocalizedIllegalStateException extends IllegalStateException implements LocalizedMessageCarrier {
        private final String key;
        private final Object[] args;

        LocalizedIllegalStateException(String key, String fallback, Object... args) {
            this(key, null, fallback, args);
        }

        LocalizedIllegalStateException(String key, Throwable cause, String fallback, Object... args) {
            super(args.length == 0 ? fallback : java.text.MessageFormat.format(fallback, args), cause);
            this.key = Objects.requireNonNull(key, "key");
            this.args = args;
        }

        @Override
        public String messageKey() {
            return key;
        }

        @Override
        public Object[] messageArgs() {
            return args;
        }
    }

    static final class LocalizedIllegalArgumentException extends IllegalArgumentException
            implements LocalizedMessageCarrier {
        private final String key;
        private final Object[] args;

        LocalizedIllegalArgumentException(String key, String fallback, Object... args) {
            this(key, null, fallback, args);
        }

        LocalizedIllegalArgumentException(String key, Throwable cause, String fallback, Object... args) {
            super(args.length == 0 ? fallback : java.text.MessageFormat.format(fallback, args), cause);
            this.key = Objects.requireNonNull(key, "key");
            this.args = args;
        }

        @Override
        public String messageKey() {
            return key;
        }

        @Override
        public Object[] messageArgs() {
            return args;
        }
    }

    record OptimizationSummary(
            int sourceTriangles, int outputTriangles, double geometryErrorPixels, double combinedErrorBoundPixels) {}

    record PreparedImport(
            SurfaceImport.Prepared simple,
            SurfaceWarpLayerAuthoring.Prepared layered,
            SurfaceVertexAuthoring.Prepared vertices,
            OptimizationSummary optimization,
            NativeModelImport.Prepared character) {
        PreparedImport(
                SurfaceImport.Prepared simple,
                SurfaceWarpLayerAuthoring.Prepared layered,
                SurfaceVertexAuthoring.Prepared vertices,
                OptimizationSummary optimization) {
            this(simple, layered, vertices, optimization, null);
        }

        PreparedImport(
                SurfaceImport.Prepared simple,
                SurfaceWarpLayerAuthoring.Prepared layered,
                SurfaceVertexAuthoring.Prepared vertices) {
            this(simple, layered, vertices, null);
        }

        PreparedImport(SurfaceImport.Prepared simple, SurfaceWarpLayerAuthoring.Prepared layered) {
            this(simple, layered, null);
        }

        PreparedImport {
            if ((simple == null ? 0 : 1)
                            + (layered == null ? 0 : 1)
                            + (vertices == null ? 0 : 1)
                            + (character == null ? 0 : 1)
                    != 1) throw new IllegalArgumentException("one import plan required");
        }
    }

    static SurfaceImport.Request requestForFile(SurfaceImport.Request base, String displayName, String suffix) {
        String name = displayName == null ? "Model" : displayName.strip();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        if (name.isBlank()) name = "Model";
        String stem = name.replaceAll("[^A-Za-z0-9_]", "_").replaceAll("_+", "_");
        if (stem.isBlank() || stem.equals("_")) stem = "Model_" + Integer.toUnsignedString(name.hashCode(), 16);
        // Leave room for the import suffix, rotation axis and source-control index.
        if (stem.length() > 20) stem = stem.substring(0, 20);
        String prefix = "Param3D_" + stem + "_" + suffix;
        return new SurfaceImport.Request(
                name,
                new ParameterId(prefix + "_Yaw"),
                new ParameterId(prefix + "_Pitch"),
                base.camera(),
                base.yawLimit(),
                base.pitchLimit(),
                base.maxProjectedVertices(),
                base.roll().map(ignored -> new ParameterId(prefix + "_Roll")),
                base.labels());
    }

    /** Retain the frozen small-convex path only when its exact proof succeeds.
     * Concave, open, rigged and larger sources use the generic visibility compiler.
     */
    private static boolean needsNativeSourceImport(byte[] bytes) {
        if (!NativeModelImport.isGlb(bytes)) return false;
        if (bytes.length > GlbSurfaceReader.MAX_BYTES
                || dev.turboism.plugin.modelimport3d.viewer.GlbViewerLoader.requiresNativeRigImport(bytes)) return true;
        try {
            ClosedConvexSurfaceProof.verify(GlbSurfaceReader.read(bytes));
            return false;
        } catch (IllegalArgumentException unsupportedLegacySolid) {
            return true;
        }
    }

    static PreparedImport prepare(
            byte[] bytes, SurfaceImport.Request request, SurfaceBaseColorPlan.Appearance appearance)
            throws java.io.IOException {
        boolean originalGlb = NativeModelImport.isGlb(bytes);
        if (request.roll().isPresent()
                && appearance == SurfaceBaseColorPlan.Appearance.SOURCE_MATERIAL
                && needsNativeSourceImport(bytes)) {
            return new PreparedImport(null, null, null, null, NativeModelImport.prepare(bytes, request, () -> false));
        }
        bytes = ModelInputNormalizer.normalize(bytes);
        if (!originalGlb
                && request.roll().isPresent()
                && appearance == SurfaceBaseColorPlan.Appearance.SOURCE_MATERIAL
                && needsNativeSourceImport(bytes))
            return new PreparedImport(null, null, null, null, NativeModelImport.prepare(bytes, request, () -> false));
        if (request.roll().isPresent()) {
            if (appearance != SurfaceBaseColorPlan.Appearance.SOURCE_MATERIAL)
                throw new LocalizedIllegalArgumentException("error.source-material-required", "XYZ 360° 导入使用源模型材质与贴图。");
            try {
                var candidate = SurfaceWarpImportCandidate.prepareFramed(
                        bytes, request, SurfaceWarpImportCandidate.FULL_CIRCLE);
                return new PreparedImport(null, SurfaceWarpLayerAuthoring.prepareStorageBoundedDerived(candidate));
            } catch (IllegalArgumentException warpFailure) {
                try {
                    var sources = GlbSurfaceReader.read(bytes);
                    var camera = SurfaceAutoCamera.fit(
                            sources,
                            request.camera().centerX(),
                            request.camera().centerY(),
                            400);
                    var framed = new SurfaceImport.Request(
                            request.name(),
                            request.yaw(),
                            request.pitch(),
                            camera,
                            request.yawLimit(),
                            request.pitchLimit(),
                            request.maxProjectedVertices(),
                            request.roll(),
                            request.labels());
                    var choice = SurfaceImportCostPlan.chooseEditable(sources, framed, 512, 1001);
                    var optimized = choice.simplified();
                    var editable = SurfaceWarpImagePlan.compileEditableVertex(choice.geometry(), camera, 1001);
                    var vertices = SurfaceVertexAuthoring.prepareGeometry(
                            framed, editable.geometry(), editable.images(), choice.interpolationAllowance());
                    double combined = vertices.interpolationErrorBoundPixels() + optimized.maximumGeometryErrorPixels();
                    if (combined > 5)
                        throw new IllegalArgumentException("combined geometry/interpolation bound exceeds 5px");
                    return new PreparedImport(
                            null,
                            null,
                            vertices,
                            new OptimizationSummary(
                                    optimized.sourceTriangles(),
                                    optimized.outputTriangles(),
                                    optimized.maximumGeometryErrorPixels(),
                                    combined));
                } catch (java.io.IOException | IllegalArgumentException vertexFailure) {
                    vertexFailure.addSuppressed(warpFailure);
                    throw vertexFailure;
                }
            }
        }
        try {
            return new PreparedImport(SurfaceImport.prepareFramed(bytes, request, appearance), null);
        } catch (IllegalArgumentException simpleFailure) {
            if (appearance != SurfaceBaseColorPlan.Appearance.SOURCE_MATERIAL) throw simpleFailure;
            try {
                var candidate =
                        SurfaceWarpImportCandidate.prepareFramed(bytes, request, SurfaceWarpImportCandidate.DEFAULT);
                return new PreparedImport(null, SurfaceWarpLayerAuthoring.prepareStorageBoundedDerived(candidate));
            } catch (java.io.IOException | IllegalArgumentException layerFailure) {
                layerFailure.addSuppressed(simpleFailure);
                throw layerFailure;
            }
        }
    }

    private Ui ui;

    public ModelImportPlugin() {
        this(null);
    }

    ModelImportPlugin(Ui ui) {
        this.ui = ui;
    }

    private PluginContext context;
    private volatile long epoch;
    private volatile boolean enabled;
    private long busyEpoch; // EDT only; an old enable cycle cannot unlock a new request.
    private static final String ACTION = "model-import-3d.import";
    private static final String VIEW_ACTION = "model-import-3d.view";
    private volatile ModelViewerWindow viewer;

    @Override
    public void init(PluginContext value) {
        context = Objects.requireNonNull(value);
        if (ui == null) {
            ui = new SwingUi(() -> context != null ? context.localization() : null);
        }
    }

    private String text(String key, String fallback) {
        if (context == null) return fallback;
        try {
            var loc = context.localization();
            if (loc != null) {
                String val = loc.text(key);
                return key.equals(val) ? fallback : val;
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private String format(String key, String fallback, Object... args) {
        if (context == null) return java.text.MessageFormat.format(fallback, args);
        try {
            var loc = context.localization();
            if (loc != null) {
                String pattern = loc.text(key);
                if (key.equals(pattern)) return java.text.MessageFormat.format(fallback, args);
                return loc.format(key, args);
            }
        } catch (Exception ignored) {
        }
        return java.text.MessageFormat.format(fallback, args);
    }

    @Override
    public void enable() {
        enabled = true;
        long token = ++epoch;
        context.disposableScope().register(context.actions().register(VIEW_ACTION, new ActionRegistry.Action() {
            @Override
            public String id() {
                return VIEW_ACTION;
            }

            @Override
            public String label() {
                return text("viewer.action", "View 3D model…");
            }

            @Override
            public Consumer<ActionRegistry.ActionContext> handler() {
                return ignored -> SwingUtilities.invokeLater(() -> {
                    if (!active(token)) return;
                    ModelViewerWindow current = viewer;
                    if (current == null || current.isClosed()) {
                        current = new ModelViewerWindow(
                                context.userFiles(),
                                key -> text(key, key),
                                failure -> context.logger().error("3D viewer failed", failure));
                        viewer = current;
                    }
                    if (!active(token)) {
                        current.close();
                        return;
                    }
                    current.show();
                });
            }
        }));
        context.disposableScope().register(context.menus().contribute(new MenuRegistry.MenuContribution() {
            @Override
            public String menuPath() {
                return "Turboism/" + text("menu.group", "3D Models") + "/" + text("viewer.action", "View 3D model…");
            }

            @Override
            public String actionId() {
                return VIEW_ACTION;
            }

            @Override
            public int order() {
                return 90;
            }
        }));
        context.disposableScope().register(context.actions().register(ACTION, new ActionRegistry.Action() {
            @Override
            public String id() {
                return ACTION;
            }

            @Override
            public String label() {
                return text("action.import.label", "导入 3D 模型…");
            }

            @Override
            public Consumer<ActionRegistry.ActionContext> handler() {
                return ignored -> SwingUtilities.invokeLater(() -> start(token));
            }
        }));
        context.disposableScope().register(context.menus().contribute(new MenuRegistry.MenuContribution() {
            @Override
            public String menuPath() {
                return "Turboism/" + text("menu.group", "3D 模型") + "/" + text("action.import.label", "导入 3D 模型…");
            }

            @Override
            public String actionId() {
                return ACTION;
            }

            @Override
            public int order() {
                return 100;
            }
        }));
    }

    @Override
    public void disable() {
        enabled = false;
        ++epoch;
        ModelViewerWindow current = viewer;
        viewer = null;
        if (current != null) current.close();
    }

    @Override
    public void shutdown() {
        disable();
    }

    private boolean active(long token) {
        return enabled && epoch == token;
    }

    private void start(long token) {
        if (!active(token) || busyEpoch == token) return;
        busyEpoch = token; // Set before opening a modal dialog (which pumps nested events).
        try {
            var document = context.cubism()
                    .activeDocument()
                    .orElseThrow(() -> new LocalizedIllegalStateException("error.no-active-document", "请先打开目标模型。"));
            var target = context.cubism().model().active();
            SurfacePlacementAuthoring.canvasForm(
                    target.canvas().widthPixels(), target.canvas().heightPixels());
            if (!target.textures().supportsAppendTextureAtlasPages())
                throw new LocalizedIllegalStateException("error.texture-atlas-unavailable", "当前环境不支持纹理图集功能。");
            int choice = ui.appearance();
            if (choice < 0 || choice == 2 || !active(token)) {
                release(token);
                return;
            }
            var appearance = choice == 0
                    ? SurfaceBaseColorPlan.Appearance.SOURCE_MATERIAL
                    : SurfaceBaseColorPlan.Appearance.FACE_COLORS;
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            var request = new SurfaceImport.Request(
                    text("model.default-name", "3D 模型"),
                    new ParameterId("Param3DYaw" + suffix),
                    new ParameterId("Param3DPitch" + suffix),
                    new SurfaceProjection.Camera(512, 512, 200, new SurfaceProjection.Vertex(0, 0, 0)),
                    choice == 0 ? 180 : 30,
                    choice == 0 ? 180 : 30,
                    SurfaceImportPlan.MAX_PROJECTED_VERTICES,
                    choice == 0 ? Optional.of(new ParameterId("Param3DRoll" + suffix)) : Optional.empty(),
                    java.util.Map.of(
                            "object.rotation.x",
                            text("object.rotation.x", "X 旋转"),
                            "object.rotation.y",
                            text("object.rotation.y", "Y 旋转"),
                            "object.rotation.z",
                            text("object.rotation.z", "Z 旋转"),
                            "object.placement",
                            text("object.placement", "整体定位"),
                            "object.warp",
                            text("object.warp", "曲面变形"),
                            "object.local-action",
                            text("object.local-action", "分区 {0} 局部动作")));
            var files = context.userFiles();
            files.request(new UserFileRequest(
                            "import-model-" + suffix,
                            text("file.chooser.title", "选择 GLB、glTF、OBJ 或模型 ZIP"),
                            List.of("glb", "gltf", "obj", "zip"),
                            UserFileMode.READ,
                            UserFileLifetime.ONE_OPERATION))
                    .thenCompose(selected -> {
                        if (selected.status() == UserFileRequestStatus.CANCELED)
                            return CompletableFuture.completedFuture(Optional.<PreparedImport>empty());
                        if (selected.status() != UserFileRequestStatus.GRANTED)
                            throw new CompletionException(new LocalizedIllegalStateException(
                                    "error.file-select", "无法选择文件：{0}", selected.status()));
                        if (!active(token)) {
                            selected.handle().orElseThrow().close();
                            return CompletableFuture.completedFuture(Optional.<PreparedImport>empty());
                        }
                        var handle = selected.handle().orElseThrow();
                        var fileRequest = requestForFile(request, handle.displayName(), suffix);
                        CompletionStage<UserFileReadResult<byte[]>> pending;
                        try {
                            pending = choice == 0
                                    ? files.readBytesBounded(handle, NativeModelImport.MAX_BYTES)
                                    : files.readBytes(handle, GlbSurfaceReader.MAX_BYTES);
                        } catch (RuntimeException failure) {
                            handle.close();
                            throw failure;
                        }
                        return pending.whenComplete((value, error) -> handle.close())
                                .thenCompose(read -> {
                                    if (!active(token))
                                        return CompletableFuture.completedFuture(Optional.<PreparedImport>empty());
                                    if (read.truncated())
                                        throw new CompletionException(new LocalizedIllegalArgumentException(
                                                choice == 0 ? "error.character-file-too-large" : "error.file-too-large",
                                                choice == 0 ? "GLB 模型文件超过 128 MiB 限制。" : "模型文件超过 16 MiB 限制。"));
                                    byte[] bytes = read.value()
                                            .orElseThrow(() ->
                                                    new LocalizedIllegalStateException("error.file-read", "无法读取所选模型。"));
                                    if (choice == 0 && needsNativeSourceImport(bytes)) {
                                        return CompletableFuture.supplyAsync(() -> {
                                            if (!active(token)) return Optional.<PreparedImport>empty();
                                            try {
                                                return Optional.of(new PreparedImport(
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        NativeModelImport.prepare(
                                                                bytes, fileRequest, () -> !active(token))));
                                            } catch (Exception error) {
                                                throw new CompletionException(error);
                                            }
                                        });
                                    }
                                    return ModelInputFiles.normalize(
                                                    bytes,
                                                    files,
                                                    "import-model-" + suffix,
                                                    () -> active(token),
                                                    context.localization())
                                            .thenApplyAsync(normalized -> {
                                                if (normalized.isEmpty() || !active(token))
                                                    return Optional.<PreparedImport>empty();
                                                try {
                                                    return Optional.of(
                                                            prepare(normalized.orElseThrow(), fileRequest, appearance));
                                                } catch (Exception failure) {
                                                    throw new CompletionException(failure);
                                                }
                                            });
                                });
                    })
                    .whenComplete((prepared, failure) -> SwingUtilities.invokeLater(() -> {
                        if (!active(token)) return;
                        try {
                            if (failure != null) throw new CompletionException(failure);
                            if (prepared.isPresent())
                                commit(token, document.documentId(), target, prepared.orElseThrow());
                        } catch (Exception error) {
                            show(error);
                        } finally {
                            release(token);
                        }
                    }));
        } catch (Exception failure) {
            release(token);
            show(failure);
        }
    }

    private void release(long token) {
        if (busyEpoch == token) busyEpoch = 0;
    }

    static ModelTextureAtlasPlanner.PlannedAtlas planAtlas(
            CubismModel target, PreparedImport prepared, java.util.function.IntFunction<String> pageNameProvider) {
        if (prepared.character() != null) return NativeModelImport.planAtlas(prepared.character(), pageNameProvider);
        var items = new ArrayList<dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem>();
        if (prepared.simple() != null) {
            var materials = prepared.simple().appearance.materials();
            for (int i = 0; i < materials.size(); i++) {
                var m = materials.get(i);
                items.add(new dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem(
                        "mat-" + i, m.width(), m.height()));
            }
        } else if (prepared.layered() != null) {
            var images = prepared.layered().surface().images;
            for (int i = 0; i < images.imageCount(); i++) {
                items.add(new dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem(
                        "layer-img-" + i, images.width, images.height));
            }
        } else {
            var images = prepared.vertices().images();
            for (int i = 0; i < images.imageCount(); i++) {
                items.add(new dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem(
                        "vert-img-" + i, images.width, images.height));
            }
        }
        return ModelTextureAtlasPlanner.plan(items, ModelTextureAtlasPlanner.Options.DEFAULT, pageNameProvider);
    }

    private void commit(long token, String documentId, CubismModel target, PreparedImport prepared) {
        requireTarget(token, documentId, target);
        if (prepared.vertices() != null) {
            prepared = finalizeEditableVertices(prepared, geometry -> {
                requireTarget(token, documentId, target);
                var result = target.drawables().previewSourceTriangulation(geometry);
                requireTarget(token, documentId, target);
                return result;
            });
        }
        String requestName = prepared.character() != null
                ? prepared.character().geometry().request().name()
                : prepared.simple() != null
                        ? prepared.simple().request.name()
                        : prepared.vertices() != null
                                ? prepared.vertices().request().name()
                                : prepared.layered().surface().request.name();

        int existingCount = target.textures().textureAtlases().size();
        java.util.function.IntFunction<String> pageNames =
                pageIdx -> format("atlas.page-name", "{0} 图集 {1}", requestName, existingCount + pageIdx + 1);

        var atlasPlan = planAtlas(target, prepared, pageNames);
        var atlasSummary = new AtlasSummary(atlasPlan.pageCount(), atlasPlan.pageWidth(), atlasPlan.pageHeight());

        boolean confirmed = prepared.character() != null
                ? ui.confirmCharacter(prepared.character(), atlasSummary)
                : prepared.simple() != null
                        ? ui.confirm(prepared.simple(), atlasSummary)
                        : prepared.vertices() != null
                                ? ui.confirmVertices(prepared.vertices(), prepared.optimization(), atlasSummary)
                                : ui.confirmLayers(prepared.layered(), atlasSummary);
        if (!confirmed) return;
        requireTarget(token, documentId, target);
        java.util.function.Supplier<CubismModel> current = () -> {
            requireTarget(token, documentId, target);
            return target;
        };

        var transactions = context.cubism().authoringTransactions();
        final PreparedImport finalPrepared = prepared;

        var result = transactions.execute(
                dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions.of("Import 3D " + requestName), () -> {
                    requireTarget(token, documentId, target);
                    boolean fitCanvas = target.canvas().widthPixels() != 1024
                            || target.canvas().heightPixels() != 1024;
                    var existingDeformers = fitCanvas
                            ? SurfacePlacementAuthoring.deformerIds(target)
                            : java.util.Set.<dev.turboism.sdk.cubism.id.DeformerId>of();
                    if (finalPrepared.character() != null) {
                        var created = NativeModelImport.assemble(
                                target,
                                finalPrepared.character(),
                                atlasPlan,
                                stage -> context.logger().info("Native import stage: " + stage),
                                transactions::batchStoredSourceWrites);
                        if (fitCanvas)
                            SurfacePlacementAuthoring.fitCanvas(
                                    target,
                                    existingDeformers,
                                    created.meshIds(),
                                    finalPrepared
                                            .character()
                                            .geometry()
                                            .request()
                                            .placementName());
                        requireTarget(token, documentId, target);
                        return null;
                    }
                    var existingParameters = target.parameters().all().stream()
                            .map(p -> p.id())
                            .collect(java.util.stream.Collectors.toSet());
                    var meshIds = new ArrayList<dev.turboism.sdk.cubism.id.ArtMeshId>();
                    var bindingsBySourceId =
                            new LinkedHashMap<String, dev.turboism.sdk.cubism.model.ModelImageBinding>();
                    var meshImageBindings = new LinkedHashMap<
                            dev.turboism.sdk.cubism.id.ArtMeshId, dev.turboism.sdk.cubism.model.ModelImageBinding>();

                    if (finalPrepared.simple() != null) {
                        var imported = SurfaceImport.assemble(target, finalPrepared.simple(), stage -> {});
                        for (var patch : imported.geometry().patches()) {
                            meshIds.add(patch.meshId());
                        }
                        var patches = finalPrepared.simple().appearance.patches();
                        var materials = finalPrepared.simple().appearance.materials();
                        for (int i = 0; i < patches.size(); i++) {
                            var mat = patches.get(i).material();
                            int materialIndex = materials.indexOf(mat);
                            var bound = imported.materials().get(i);
                            bindingsBySourceId.putIfAbsent("mat-" + materialIndex, bound.binding());
                            meshImageBindings.put(
                                    imported.geometry().patches().get(i).meshId(), bound.binding());
                        }
                    } else if (finalPrepared.vertices() != null) {
                        var created = SurfaceVertexAuthoring.assemble(target, finalPrepared.vertices());
                        meshIds.addAll(created.meshes());
                        var images = finalPrepared.vertices().images();
                        for (int m = 0; m < created.meshes().size(); m++) {
                            int imageIndex = images.meshImages.get(m);
                            var binding = created.images().get(m);
                            var meshId = created.meshes().get(m);
                            bindingsBySourceId.putIfAbsent("vert-img-" + imageIndex, binding);
                            meshImageBindings.put(meshId, binding);
                        }
                    } else {
                        var created = SurfaceWarpLayerAuthoring.assemble(target, finalPrepared.layered(), stage -> {});
                        meshIds.addAll(created.meshIds());
                        var images = finalPrepared.layered().surface().images;
                        for (int m = 0; m < created.meshIds().size(); m++) {
                            int imageIndex = images.meshImages.get(m);
                            var binding = created.imageBindings().get(m);
                            var meshId = created.meshIds().get(m);
                            bindingsBySourceId.putIfAbsent("layer-img-" + imageIndex, binding);
                            meshImageBindings.put(meshId, binding);
                        }
                    }

                    requireTarget(token, documentId, target);
                    if (fitCanvas)
                        SurfacePlacementAuthoring.fitCanvas(
                                target,
                                existingDeformers,
                                meshIds,
                                finalPrepared.simple() != null
                                        ? finalPrepared.simple().request.placementName()
                                        : finalPrepared.vertices() != null
                                                ? finalPrepared
                                                        .vertices()
                                                        .request()
                                                        .placementName()
                                                : finalPrepared
                                                        .layered()
                                                        .surface()
                                                        .request
                                                        .placementName());
                    ModelTextureAtlasAuthoring.apply(target, meshIds, bindingsBySourceId, meshImageBindings, atlasPlan);
                    var group = target.parameterGroups().addGroup(requestName);
                    for (var parameter : target.parameters().all())
                        if (!existingParameters.contains(parameter.id()))
                            target.parameterGroups().moveParameter(parameter.id(), group.id());
                    return null;
                });
        if (result.outcome() != AuthoringTransactionOutcome.COMMITTED) {
            String diag = result.diagnosticId().map(id -> "（" + id + "）").orElse("");
            throw new LocalizedIllegalStateException(
                    "error.transaction-uncommitted", "导入未完成：{0}{1}", result.outcome(), diag);
        }
        ui.message(
                prepared.character() != null
                                || prepared.vertices() != null
                                || prepared.layered() != null
                                        && prepared.layered()
                                                .surface()
                                                .request
                                                .roll()
                                                .isPresent()
                        ? text(
                                "success.xyz-rotation",
                                "导入完成。拖动新增的 X / Y / Z 旋转参数（各 ±180°）即可旋转一整圈。\n整体移动或缩放时，请选择“整体定位”变形器。")
                        : text("success.yaw-pitch", "导入完成。拖动新增的 Yaw / Pitch 参数即可旋转。"),
                false);
    }

    static PreparedImport finalizeEditableVertices(
            PreparedImport prepared,
            java.util.function.Function<
                            dev.turboism.sdk.cubism.model.ArtMeshGeometry,
                            dev.turboism.sdk.cubism.model.ArtMeshSourceTriangulation>
                    preview) {
        var p = Objects.requireNonNull(prepared.vertices(), "direct vertex plan required");
        var refined = SurfaceWarpImagePlan.refineEditableVertex(
                p.geometry(), p.images(), new SurfaceEditableTopologyPlan.Limits(1001, 8192, 100000), preview);
        double geometryError =
                prepared.optimization() == null ? 0 : prepared.optimization().geometryErrorPixels();
        var vertices = SurfaceVertexAuthoring.prepareGeometry(
                p.request(),
                refined.geometry(),
                refined.images(),
                SurfaceImportCostPlan.TOTAL_ERROR_PIXELS - geometryError);
        var optimization = prepared.optimization() == null
                ? null
                : new OptimizationSummary(
                        prepared.optimization().sourceTriangles(),
                        prepared.optimization().outputTriangles(),
                        geometryError,
                        geometryError + vertices.interpolationErrorBoundPixels());
        return new PreparedImport(null, null, vertices, optimization);
    }

    private void requireTarget(long token, String documentId, CubismModel target) {
        if (!active(token)
                || !context.cubism()
                        .activeDocument()
                        .map(d -> d.documentId().equals(documentId))
                        .orElse(false)
                || !context.cubism().model().active().id().equals(target.id()))
            throw new LocalizedIllegalStateException("error.target-switched", "目标文档已切换，请重新发起导入。");
        target.canvas(); // Exercise the retained SDK handle's native generation guard.
    }

    private void show(Throwable failure) {
        while (failure.getCause() != null && failure instanceof CompletionException) failure = failure.getCause();
        context.logger().warn("3D import failed: " + failure);

        final String userMessage;
        if (failure instanceof LocalizedMessageCarrier carrier) {
            userMessage = carrier.messageArgs().length == 0
                    ? text(carrier.messageKey(), carrier.getMessage())
                    : format(carrier.messageKey(), carrier.getMessage(), carrier.messageArgs());
        } else {
            String raw = failure.getMessage();
            if (raw == null || raw.isBlank() || containsCjk(raw)) {
                userMessage = text("error.generic-failure", "3D 模型导入失败，请检查文件或重试。");
            } else {
                userMessage = raw;
            }
        }
        ui.message(userMessage, true);
    }

    private static boolean containsCjk(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }

    private static final class SwingUi implements Ui {
        private final java.util.function.Supplier<dev.turboism.sdk.i18n.PluginLocalization> i18nSupplier;

        SwingUi(java.util.function.Supplier<dev.turboism.sdk.i18n.PluginLocalization> i18nSupplier) {
            this.i18nSupplier = Objects.requireNonNull(i18nSupplier);
        }

        private String text(String key, String fallback) {
            var loc = i18nSupplier.get();
            if (loc == null) return fallback;
            try {
                String val = loc.text(key);
                return key.equals(val) ? fallback : val;
            } catch (Exception ignored) {
                return fallback;
            }
        }

        private String format(String key, String fallback, Object... args) {
            var loc = i18nSupplier.get();
            if (loc == null) return java.text.MessageFormat.format(fallback, args);
            try {
                String pattern = loc.text(key);
                if (key.equals(pattern)) return java.text.MessageFormat.format(fallback, args);
                return loc.format(key, args);
            } catch (Exception ignored) {
                return java.text.MessageFormat.format(fallback, args);
            }
        }

        @Override
        public boolean confirmCharacter(NativeModelImport.Prepared p, AtlasSummary atlas) {
            var geometry = p.geometry();
            String atlasInfo = format(
                    "dialog.confirm.atlas-info",
                    "纹理图集：将创建 {0} 张 {1}×{2} 图集。",
                    atlas.pageCount(),
                    atlas.width(),
                    atlas.height());
            long partCount = p.materials().scene().meshes().stream()
                    .filter(m -> m.visible())
                    .map(m -> m.bodyPart().isBlank() ? m.appearanceGroup() : m.bodyPart())
                    .distinct()
                    .count();
            String sourceControls = format(
                    "dialog.confirm.character-controls",
                    "源形态与显隐：{0} 个融合参数；{1} 个源部位。",
                    p.controls().controls().size(),
                    partCount);
            return JOptionPane.showConfirmDialog(
                            null,
                            format(
                                    "dialog.confirm.character",
                                    "将创建 {0} 个原生网格，保留 {1} 个三角形。\nX / Y 参数各 {2} 个绑定点，Z 参数支持整圈旋转。\n遮挡采用分组排序，复杂交叠可能有偏差。\n{3}\n可用一次撤销撤回本次导入。继续？",
                                    geometry.partitions().size(),
                                    geometry.totalTriangles(),
                                    geometry.keys().size(),
                                    atlasInfo + "\n" + sourceControls),
                            text("dialog.confirm.title", "确认导入"),
                            JOptionPane.OK_CANCEL_OPTION)
                    == JOptionPane.OK_OPTION;
        }

        @Override
        public int appearance() {
            Object[] choices = {
                text("dialog.appearance.option.source-material", "保留原材质"),
                text("dialog.appearance.option.face-colors", "按面区分颜色"),
                text("dialog.appearance.option.cancel", "取消")
            };
            return JOptionPane.showOptionDialog(
                    null,
                    text(
                            "dialog.appearance.message",
                            "支持不透明静态 GLB、glTF、OBJ 与模型 ZIP。\n选择单独模型后，可按提示补选引用的材质、buffer 和 PNG/JPEG 贴图；也可一起打包为 ZIP。\n仅还原基础颜色；凹凸、光照与透明材质暂不支持。\n原材质模式：X / Y / Z 各 ±180°，支持整圈旋转。\n按面区分颜色为 ±30° 检查模式。\n颜色方式："),
                    text("dialog.appearance.title", "导入 3D 模型"),
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    choices,
                    choices[0]);
        }

        @Override
        public boolean confirm(SurfaceImport.Prepared p) {
            return confirm(p, null);
        }

        @Override
        public boolean confirm(SurfaceImport.Prepared p, AtlasSummary atlas) {
            int faces = p.geometry.patches().size(), keys = p.geometry.keys().size();
            String atlasInfo = atlas != null
                    ? format(
                            "dialog.confirm.atlas-info",
                            "纹理图集：将创建 {0} 张 {1}×{2} 图集。",
                            atlas.pageCount(),
                            atlas.width(),
                            atlas.height())
                    : "";
            return JOptionPane.showConfirmDialog(
                            null,
                            format(
                                    "dialog.confirm.simple",
                                    "将创建 {0} 个网格，共 {1} 个形态。\n水平 / 垂直参数绑定点：{2} / {3}。\n{4}\n可用一次撤销撤回本次导入。继续？",
                                    faces,
                                    faces * keys,
                                    p.geometry.grid().yawKeys().size(),
                                    p.geometry.grid().pitchKeys().size(),
                                    atlasInfo),
                            text("dialog.confirm.title", "确认导入"),
                            JOptionPane.OK_CANCEL_OPTION)
                    == JOptionPane.OK_OPTION;
        }

        @Override
        public boolean confirmLayers(SurfaceWarpLayerAuthoring.Prepared layered) {
            return confirmLayers(layered, null);
        }

        @Override
        public boolean confirmLayers(SurfaceWarpLayerAuthoring.Prepared layered, AtlasSummary atlas) {
            var p = layered.surface();
            int meshes = p.geometry.meshes().size();
            String rollNote =
                    p.request.roll().isPresent() ? text("dialog.confirm.layers.roll-note", " Z 旋转：5 点，三轴各 ±180°。") : "";
            String atlasInfo = atlas != null
                    ? format(
                            "dialog.confirm.atlas-info",
                            "纹理图集：将创建 {0} 张 {1}×{2} 图集。",
                            atlas.pageCount(),
                            atlas.width(),
                            atlas.height())
                    : "";
            return JOptionPane.showConfirmDialog(
                            null,
                            format(
                                    "dialog.confirm.layers",
                                    "将创建 {0} 个曲面网格和 {1} 个变形器，保留源模型几何与贴图。\n水平 / 垂直参数绑定点：{2} / {3}。{4}\n遮挡层序采样点：{5} / {6}。\n{7}\n复杂交叠在旋转时仍可能有偏差。可用一次撤销撤回本次导入。继续？",
                                    meshes,
                                    (p.request.roll().isPresent() ? 3 : 2),
                                    p.yawKeys.size(),
                                    p.pitchKeys.size(),
                                    rollNote,
                                    layered.layerYawKeys().size(),
                                    layered.layerPitchKeys().size(),
                                    atlasInfo),
                            text("dialog.confirm.title", "确认导入"),
                            JOptionPane.OK_CANCEL_OPTION)
                    == JOptionPane.OK_OPTION;
        }

        @Override
        public boolean confirmVertices(SurfaceVertexAuthoring.Prepared p) {
            return confirmVertices(p, (OptimizationSummary) null, null);
        }

        @Override
        public boolean confirmVertices(SurfaceVertexAuthoring.Prepared p, AtlasSummary atlas) {
            return confirmVertices(p, (OptimizationSummary) null, atlas);
        }

        @Override
        public boolean confirmVertices(SurfaceVertexAuthoring.Prepared p, OptimizationSummary optimization) {
            return confirmVertices(p, optimization, null);
        }

        @Override
        public boolean confirmVertices(
                SurfaceVertexAuthoring.Prepared p, OptimizationSummary optimization, AtlasSummary atlas) {
            String atlasInfo = atlas != null
                    ? format(
                            "dialog.confirm.atlas-info",
                            "纹理图集：将创建 {0} 张 {1}×{2} 图集。",
                            atlas.pageCount(),
                            atlas.width(),
                            atlas.height())
                    : "";
            if (optimization == null) {
                int meshes = p.geometry().meshes().size();
                long triangles = p.geometry().meshes().stream()
                        .mapToLong(m -> m.triangles().size())
                        .sum();
                return JOptionPane.showConfirmDialog(
                                null,
                                format(
                                        "dialog.confirm.vertices",
                                        "将创建 {0} 个曲面网格，保留原始 {1} 个三角形、UV 和贴图。\n{2}\nX / Y 各 9 个绑定点，使用椭圆插值；Z 旋转：5 点，三轴各 ±180°。\n遮挡采用分组近似，复杂交叠仍可能有少量偏差。\n可用一次撤销撤回本次导入。整体移动或缩放时选择“整体定位”变形器。继续？",
                                        meshes,
                                        triangles,
                                        atlasInfo),
                                text("dialog.confirm.title", "确认导入"),
                                JOptionPane.OK_CANCEL_OPTION)
                        == JOptionPane.OK_OPTION;
            }
            String bound = String.format(Locale.ROOT, "%.2f", optimization.combinedErrorBoundPixels());
            return JOptionPane.showConfirmDialog(
                            null,
                            format(
                                    "dialog.confirm.vertices-optimized",
                                    "将创建 {0} 个曲面网格。\n源模型 {1} 面 → 导入 {2} 面，保留材质边界和 UV 接缝。\n{4}\n曲面几何与参数插值的合计偏差上界：{3} 像素（不含遮挡分组近似）。\nX / Y 各 9 点椭圆插值；Z 5 点；三轴各 ±180°。\n整体移动或缩放时选择“整体定位”变形器。可用一次撤销撤回本次导入。继续？",
                                    p.geometry().meshes().size(),
                                    optimization.sourceTriangles(),
                                    optimization.outputTriangles(),
                                    bound,
                                    atlasInfo),
                            text("dialog.confirm.title", "确认导入"),
                            JOptionPane.OK_CANCEL_OPTION)
                    == JOptionPane.OK_OPTION;
        }

        @Override
        public void message(String text, boolean error) {
            JOptionPane.showMessageDialog(
                    null,
                    text,
                    error
                            ? text("dialog.message.error.title", "无法导入 3D 模型")
                            : text("dialog.message.info.title", "3D 模型"),
                    error ? JOptionPane.ERROR_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
        }
    }
}
