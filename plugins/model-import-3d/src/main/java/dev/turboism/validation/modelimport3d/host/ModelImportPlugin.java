package dev.turboism.validation.modelimport3d.host;

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
    interface Ui {
        int appearance();

        boolean confirm(SurfaceImport.Prepared prepared);

        boolean confirmLayers(SurfaceWarpLayerAuthoring.Prepared prepared);

        default boolean confirmVertices(SurfaceVertexAuthoring.Prepared prepared) {
            return false;
        }

        default boolean confirmVertices(SurfaceVertexAuthoring.Prepared prepared, OptimizationSummary optimization) {
            return confirmVertices(prepared);
        }

        void message(String text, boolean error);
    }

    record OptimizationSummary(
            int sourceTriangles, int outputTriangles, double geometryErrorPixels, double combinedErrorBoundPixels) {}

    record PreparedImport(
            SurfaceImport.Prepared simple,
            SurfaceWarpLayerAuthoring.Prepared layered,
            SurfaceVertexAuthoring.Prepared vertices,
            OptimizationSummary optimization) {
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
            if ((simple == null ? 0 : 1) + (layered == null ? 0 : 1) + (vertices == null ? 0 : 1) != 1)
                throw new IllegalArgumentException("one import plan required");
        }
    }

    static PreparedImport prepare(
            byte[] bytes, SurfaceImport.Request request, SurfaceBaseColorPlan.Appearance appearance)
            throws java.io.IOException {
        bytes = ModelInputNormalizer.normalize(bytes);
        if (request.roll().isPresent()) {
            if (appearance != SurfaceBaseColorPlan.Appearance.SOURCE_MATERIAL)
                throw new IllegalArgumentException("XYZ 360° 导入使用源模型材质与贴图。");
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
                            request.roll());
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
                    .orElseThrow(() -> new IllegalStateException(text("error.no-active-document", "请先打开目标模型。")));
            var target = context.cubism().model().active();
            if (target.canvas().widthPixels() != 1024 || target.canvas().heightPixels() != 1024)
                throw new IllegalArgumentException(text("error.invalid-canvas-size", "当前导入需要 1024×1024 画布。"));
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
                    choice == 0 ? Optional.of(new ParameterId("Param3DRoll" + suffix)) : Optional.empty());
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
                            throw new CompletionException(new IllegalStateException(
                                    format("error.file-select", "无法选择文件：{0}", selected.status())));
                        if (!active(token)) {
                            selected.handle().orElseThrow().close();
                            return CompletableFuture.completedFuture(Optional.<PreparedImport>empty());
                        }
                        return files.readBytes(selected.handle().orElseThrow(), GlbSurfaceReader.MAX_BYTES)
                                .thenCompose(read -> {
                                    if (!active(token))
                                        return CompletableFuture.completedFuture(Optional.<PreparedImport>empty());
                                    if (read.truncated())
                                        throw new CompletionException(new IllegalArgumentException(
                                                text("error.file-too-large", "模型文件超过 16 MiB 限制。")));
                                    byte[] bytes = read.value()
                                            .orElseThrow(() ->
                                                    new IllegalStateException(text("error.file-read", "无法读取所选模型。")));
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
                                                            prepare(normalized.orElseThrow(), request, appearance));
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
        if (!(prepared.simple() != null
                ? ui.confirm(prepared.simple())
                : prepared.vertices() != null
                        ? ui.confirmVertices(prepared.vertices(), prepared.optimization())
                        : ui.confirmLayers(prepared.layered()))) return;
        requireTarget(token, documentId, target);
        java.util.function.Supplier<CubismModel> current = () -> {
            requireTarget(token, documentId, target);
            return target;
        };
        var transactions = context.cubism().authoringTransactions();
        var result = prepared.simple() != null
                ? SurfaceImport.apply(prepared.simple(), current, transactions)
                : prepared.vertices() != null
                        ? SurfaceVertexAuthoring.apply(prepared.vertices(), current, transactions)
                        : SurfaceWarpLayerAuthoring.apply(prepared.layered(), current, transactions, stage -> {});
        if (result.outcome() != AuthoringTransactionOutcome.COMMITTED) {
            String diag = result.diagnosticId().map(id -> "（" + id + "）").orElse("");
            throw new IllegalStateException(
                    format("error.transaction-uncommitted", "导入未完成：{0}{1}", result.outcome(), diag));
        }
        ui.message(
                prepared.vertices() != null
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
            throw new IllegalStateException(text("error.target-switched", "目标文档已切换，请重新发起导入。"));
        target.canvas(); // Exercise the retained SDK handle's native generation guard.
    }

    private void show(Throwable failure) {
        while (failure.getCause() != null && failure instanceof CompletionException) failure = failure.getCause();
        context.logger().warn("3D import failed: " + failure);
        String msg = failure.getMessage();
        if ("XYZ 360° 导入使用源模型材质与贴图。".equals(msg)) {
            msg = text("error.source-material-required", msg);
        }
        ui.message(msg, true);
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
            int faces = p.geometry.patches().size(), keys = p.geometry.keys().size();
            return JOptionPane.showConfirmDialog(
                            null,
                            format(
                                    "dialog.confirm.simple",
                                    "将创建 {0} 个网格，共 {1} 个形态。\n水平 / 垂直参数绑定点：{2} / {3}。\n可用一次撤销撤回本次导入。继续？",
                                    faces,
                                    faces * keys,
                                    p.geometry.grid().yawKeys().size(),
                                    p.geometry.grid().pitchKeys().size()),
                            text("dialog.confirm.title", "确认导入"),
                            JOptionPane.OK_CANCEL_OPTION)
                    == JOptionPane.OK_OPTION;
        }

        @Override
        public boolean confirmLayers(SurfaceWarpLayerAuthoring.Prepared layered) {
            var p = layered.surface();
            int meshes = p.geometry.meshes().size();
            String rollNote =
                    p.request.roll().isPresent() ? text("dialog.confirm.layers.roll-note", " Z 旋转：5 点，三轴各 ±180°。") : "";
            return JOptionPane.showConfirmDialog(
                            null,
                            format(
                                    "dialog.confirm.layers",
                                    "将创建 {0} 个曲面网格和 {1} 个变形器，保留源模型几何与贴图。\n水平 / 垂直参数绑定点：{2} / {3}。{4}\n遮挡层序采样点：{5} / {6}。\n复杂交叠在旋转时仍可能有偏差。可用一次撤销撤回本次导入。继续？",
                                    meshes,
                                    (p.request.roll().isPresent() ? 3 : 2),
                                    p.yawKeys.size(),
                                    p.pitchKeys.size(),
                                    rollNote,
                                    layered.layerYawKeys().size(),
                                    layered.layerPitchKeys().size()),
                            text("dialog.confirm.title", "确认导入"),
                            JOptionPane.OK_CANCEL_OPTION)
                    == JOptionPane.OK_OPTION;
        }

        @Override
        public boolean confirmVertices(SurfaceVertexAuthoring.Prepared p) {
            int meshes = p.geometry().meshes().size();
            long triangles = p.geometry().meshes().stream()
                    .mapToLong(m -> m.triangles().size())
                    .sum();
            return JOptionPane.showConfirmDialog(
                            null,
                            format(
                                    "dialog.confirm.vertices",
                                    "将创建 {0} 个曲面网格，保留原始 {1} 个三角形、UV 和贴图。\nX / Y 各 9 个绑定点，使用椭圆插值；Z 旋转：5 点，三轴各 ±180°。\n遮挡采用分组近似，复杂交叠仍可能有少量偏差。\n可用一次撤销撤回本次导入。整体移动或缩放时选择“整体定位”变形器。继续？",
                                    meshes,
                                    triangles),
                            text("dialog.confirm.title", "确认导入"),
                            JOptionPane.OK_CANCEL_OPTION)
                    == JOptionPane.OK_OPTION;
        }

        @Override
        public boolean confirmVertices(SurfaceVertexAuthoring.Prepared p, OptimizationSummary optimization) {
            if (optimization == null) return confirmVertices(p);
            String bound = String.format(Locale.ROOT, "%.2f", optimization.combinedErrorBoundPixels());
            return JOptionPane.showConfirmDialog(
                            null,
                            format(
                                    "dialog.confirm.vertices-optimized",
                                    "将创建 {0} 个曲面网格。\n源模型 {1} 面 → 导入 {2} 面，保留材质边界和 UV 接缝。\n曲面几何与参数插值的合计偏差上界：{3} 像素（不含遮挡分组近似）。\nX / Y 各 9 点椭圆插值；Z 5 点；三轴各 ±180°。\n整体移动或缩放时选择“整体定位”变形器。可用一次撤销撤回本次导入。继续？",
                                    p.geometry().meshes().size(),
                                    optimization.sourceTriangles(),
                                    optimization.outputTriangles(),
                                    bound),
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
