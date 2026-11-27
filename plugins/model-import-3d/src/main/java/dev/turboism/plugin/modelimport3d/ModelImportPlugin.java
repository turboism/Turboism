package dev.turboism.plugin.modelimport3d;

import dev.turboism.plugin.modelimport3d.viewer.GlbViewerLoader;
import dev.turboism.plugin.modelimport3d.viewer.ModelPreviewPanel;
import dev.turboism.plugin.modelimport3d.viewer.ViewerScene;
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
import java.util.function.Function;
import javax.swing.*;

/** User-mediated static model import using the same bounded engine as the native validation. */
public final class ModelImportPlugin implements TurboismPlugin {
    private static final int MAX_PARAMETER_STEM_LENGTH = 20;
    private static final int REQUEST_SUFFIX_HEX_LENGTH = 12;
    private static final int STANDARD_CANVAS_PIXELS = 1024;
    private static final int CANVAS_CENTER_PIXELS = 512;
    private static final int DEFAULT_CAMERA_SCALE = 200;
    private static final int FULL_CIRCLE_ANGLE_LIMIT = 180;
    private static final int DEFAULT_PREVIEW_FIT_PIXELS = 400;
    private static final int EDITABLE_COST_MAX_IMAGE_SIZE = 512;
    private static final int EDITABLE_COST_MAX_VERTICES = 1001;
    private static final int MAX_COMBINED_ERROR_PIXELS = 5;
    private static final int MAX_EDITABLE_PIXELS = 8192;
    private static final int MAX_EDITABLE_TRIANGLES = 100_000;
    private static final int DIALOG_DESCRIPTION_EM_WIDTH = 32;
    private static final int DIALOG_CONTENT_GAP = 12;
    private static final int PRESET_ACTION_ORDER = 110;
    private static final int IMPORT_ACTION_ORDER = 100;
    private static final int APPEARANCE_CHOICE_FACE_COLORS = 1;
    private static final int APPEARANCE_CHOICE_CANCEL = 2;
    private static final int DEFORMER_COUNT_XYZ = 3;
    private static final int DEFORMER_COUNT_XY = 2;
    private static final int IMPORT_CORE_WORKERS = 1;
    private static final int IMPORT_MAX_WORKERS = 2;
    private static final long IMPORT_KEEP_ALIVE_SECONDS = 30L;
    private static final int IMPORT_QUEUE_CAPACITY = 8;
    private static final String IMPORT_THREAD_PREFIX = "turboism-model-import-3d-";

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

        default Optional<ModelPreset> preset() {
            return Optional.empty();
        }

        /**
         * Summarizes one prepared import for the combined preset window.
         *
         * @param prepared the prepared import
         * @param atlas the atlas the confirmed import would create
         * @return the confirmation text
         */
        default String describePrepared(PreparedImport prepared, AtlasSummary atlas) {
            return "";
        }

        /**
         * @return {@code true} when this host shows the preset choice and its preview in one
         *     window, so the caller must not run the separate choice and confirm steps
         */
        default boolean composesPresetPreview() {
            return false;
        }

        /**
         * Chooses one preset and confirms it in a single window whose preview follows the
         * selection.
         *
         * <p>{@code build} prepares one preset off the event thread; {@code describe} turns a
         * prepared import into the summary shown under the preview on the event thread.</p>
         *
         * @param build prepares one preset for preview
         * @param describe summarizes one prepared import
         * @return the confirmed import, or empty when the window closed without one
         */
        default Optional<PreparedImport> composePresetImport(
                Function<ModelPreset, PreparedImport> build, Function<PreparedImport, String> describe) {
            return Optional.empty();
        }

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

        default boolean confirmImport(PreparedImport prepared, AtlasSummary atlas) {
            return prepared.character() != null
                    ? confirmCharacter(prepared.character(), atlas)
                    : prepared.simple() != null
                            ? confirm(prepared.simple(), atlas)
                            : prepared.vertices() != null
                                    ? confirmVertices(prepared.vertices(), prepared.optimization(), atlas)
                                    : confirmLayers(prepared.layered(), atlas);
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
            NativeModelImport.Prepared character,
            ViewerScene previewScene) {
        PreparedImport(
                SurfaceImport.Prepared simple,
                SurfaceWarpLayerAuthoring.Prepared layered,
                SurfaceVertexAuthoring.Prepared vertices,
                OptimizationSummary optimization,
                NativeModelImport.Prepared character) {
            this(
                    simple,
                    layered,
                    vertices,
                    optimization,
                    character,
                    character == null ? null : character.materials().scene());
        }

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
        if (stem.length() > MAX_PARAMETER_STEM_LENGTH) stem = stem.substring(0, MAX_PARAMETER_STEM_LENGTH);
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
        if (appearance == SurfaceBaseColorPlan.Appearance.FACE_COLORS
                && request.roll().isPresent()) {
            if (NativeModelImport.isGlb(bytes) && needsNativeSourceImport(bytes))
                return new PreparedImport(
                        null, null, null, null, NativeModelImport.prepare(bytes, request, appearance, () -> false));
            bytes = ModelInputNormalizer.normalize(bytes);
            if (needsNativeSourceImport(bytes))
                return new PreparedImport(
                        null, null, null, null, NativeModelImport.prepare(bytes, request, appearance, () -> false));
            var prepared = prepare(bytes, request, SurfaceBaseColorPlan.Appearance.SOURCE_MATERIAL);
            if (prepared.layered() != null) {
                var layer = prepared.layered();
                return new PreparedImport(
                        null,
                        new SurfaceWarpLayerAuthoring.Prepared(
                                SurfaceWarpAuthoring.faceColors(layer.surface()), layer.orders(),
                                layer.layerYawKeys(), layer.layerPitchKeys()));
            }
            var vertex = prepared.vertices();
            return new PreparedImport(
                    null,
                    null,
                    new SurfaceVertexAuthoring.Prepared(
                            vertex.request(),
                            vertex.geometry(),
                            SurfaceWarpImagePlan.faceColors(vertex.images()),
                            vertex.orders(),
                            vertex.interpolationErrorBoundPixels(),
                            vertex.sampling()),
                    prepared.optimization());
        }
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
                throw new LocalizedIllegalArgumentException(
                        "error.source-material-required",
                        "XYZ 360° import requires source model materials and textures.");
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
                            DEFAULT_PREVIEW_FIT_PIXELS);
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
                    var choice = SurfaceImportCostPlan.chooseEditable(
                            sources, framed, EDITABLE_COST_MAX_IMAGE_SIZE, EDITABLE_COST_MAX_VERTICES);
                    var optimized = choice.simplified();
                    var editable = SurfaceWarpImagePlan.compileEditableVertex(
                            choice.geometry(), camera, EDITABLE_COST_MAX_VERTICES);
                    var vertices = SurfaceVertexAuthoring.prepareGeometry(
                            framed, editable.geometry(), editable.images(), choice.interpolationAllowance());
                    double combined = vertices.interpolationErrorBoundPixels() + optimized.maximumGeometryErrorPixels();
                    if (combined > MAX_COMBINED_ERROR_PIXELS)
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
    private final Object importExecutorLock = new Object();
    private ExecutorService importExecutor;
    private long busyEpoch; // EDT only; an old enable cycle cannot unlock a new request.
    private static final String ACTION = "model-import-3d.import";
    private static final String PRESET_ACTION = "model-import-3d.import-preset";

    /** Isolates background model import work from the host ForkJoinPool.commonPool(). */
    private ExecutorService importExecutor() {
        synchronized (importExecutorLock) {
            if (importExecutor == null || importExecutor.isShutdown()) {
                final var threadNumber = new java.util.concurrent.atomic.AtomicInteger(1);
                final var pool = new ThreadPoolExecutor(
                        IMPORT_CORE_WORKERS,
                        IMPORT_MAX_WORKERS,
                        IMPORT_KEEP_ALIVE_SECONDS,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(IMPORT_QUEUE_CAPACITY),
                        runnable -> {
                            final Thread thread =
                                    new Thread(runnable, IMPORT_THREAD_PREFIX + threadNumber.getAndIncrement());
                            thread.setDaemon(true);
                            return thread;
                        });
                pool.allowCoreThreadTimeOut(true);
                importExecutor = pool;
            }
            return importExecutor;
        }
    }

    /** Terminates plugin worker threads so disabling releases classloader references cleanly. */
    private void shutdownImportExecutor() {
        final ExecutorService pool;
        synchronized (importExecutorLock) {
            pool = importExecutor;
            importExecutor = null;
        }
        if (pool != null) pool.shutdownNow();
    }

    @Override
    public void init(PluginContext value) {
        context = Objects.requireNonNull(value);
        if (ui == null) {
            ui = new SwingUi(
                    () -> context != null ? context.localization() : null,
                    failure -> context.logger().warn("3D import preview failed: " + failure));
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
        context.disposableScope().register(context.actions().register(PRESET_ACTION, new ActionRegistry.Action() {
            @Override
            public String id() {
                return PRESET_ACTION;
            }

            @Override
            public String label() {
                return text("action.preset.label", "Import preset…");
            }

            @Override
            public Consumer<ActionRegistry.ActionContext> handler() {
                return ignored -> SwingUtilities.invokeLater(() -> start(token, true));
            }
        }));
        context.disposableScope().register(context.menus().contribute(new MenuRegistry.MenuContribution() {
            @Override
            public String menuPath() {
                return "Turboism/" + text("menu.group", "3D Model") + "/"
                        + text("action.preset.label", "Import preset…");
            }

            @Override
            public String actionId() {
                return PRESET_ACTION;
            }

            @Override
            public int order() {
                return PRESET_ACTION_ORDER;
            }
        }));
        context.disposableScope().register(context.actions().register(ACTION, new ActionRegistry.Action() {
            @Override
            public String id() {
                return ACTION;
            }

            @Override
            public String label() {
                return text("action.import.label", "Import 3D Model…");
            }

            @Override
            public Consumer<ActionRegistry.ActionContext> handler() {
                return ignored -> SwingUtilities.invokeLater(() -> start(token));
            }
        }));
        context.disposableScope().register(context.menus().contribute(new MenuRegistry.MenuContribution() {
            @Override
            public String menuPath() {
                return "Turboism/" + text("menu.group", "3D Model") + "/"
                        + text("action.import.label", "Import 3D Model…");
            }

            @Override
            public String actionId() {
                return ACTION;
            }

            @Override
            public int order() {
                return IMPORT_ACTION_ORDER;
            }
        }));
    }

    @Override
    public void disable() {
        enabled = false;
        ++epoch;
        shutdownImportExecutor();
    }

    @Override
    public void shutdown() {
        disable();
    }

    private boolean active(long token) {
        return enabled && epoch == token;
    }

    private void start(long token) {
        start(token, false);
    }

    private void start(long token, boolean fromPreset) {
        if (!active(token) || busyEpoch == token) return;
        busyEpoch = token; // Set before opening a modal dialog (which pumps nested events).
        try {
            var document = context.cubism()
                    .activeDocument()
                    .orElseThrow(() -> new LocalizedIllegalStateException(
                            "error.no-active-document", "Please open a model project first."));
            var target = context.cubism().model().active();
            SurfacePlacementAuthoring.canvasForm(
                    target.canvas().widthPixels(), target.canvas().heightPixels());
            if (!target.textures().supportsAppendTextureAtlasPages())
                throw new LocalizedIllegalStateException(
                        "error.texture-atlas-unavailable",
                        "Texture atlas feature is unavailable in the current environment.");
            if (fromPreset && ui.composesPresetPreview()) {
                // One window chooses the preset and confirms its preview, so the prepared import is
                // the one the operator saw and the commit step must not ask again.
                final Optional<PreparedImport> confirmed = preparePresetInteractively(token, target);
                if (confirmed.isEmpty() || !active(token)) {
                    release(token);
                    return;
                }
                commit(token, document.documentId(), target, confirmed.orElseThrow(), true);
                release(token);
                return;
            }
            Optional<ModelPreset> preset = fromPreset ? ui.preset() : Optional.empty();
            if (fromPreset && (preset.isEmpty() || !active(token))) {
                release(token);
                return;
            }
            int choice = fromPreset ? 0 : ui.appearance();
            if (choice < 0 || choice == APPEARANCE_CHOICE_CANCEL || !active(token)) {
                release(token);
                return;
            }
            var appearance = choice == APPEARANCE_CHOICE_FACE_COLORS
                    ? SurfaceBaseColorPlan.Appearance.FACE_COLORS
                    : SurfaceBaseColorPlan.Appearance.SOURCE_MATERIAL;
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, REQUEST_SUFFIX_HEX_LENGTH);
            var request = importRequest(suffix);
            final ExecutorService workerPool = importExecutor();
            CompletionStage<Optional<PreparedImport>> pendingImport;
            if (fromPreset) {
                var selectedPreset = preset.orElseThrow();
                String presetName = text(selectedPreset.labelKey(), selectedPreset.fallbackLabel());
                var presetRequest = requestForFile(request, presetName + ".glb", suffix);
                pendingImport = CompletableFuture.supplyAsync(
                        () -> {
                            if (!active(token)) return Optional.empty();
                            try {
                                // Preserve the shared source image and UVs so it can be replaced after import.
                                return Optional.of(new PreparedImport(
                                        null,
                                        null,
                                        null,
                                        null,
                                        NativeModelImport.preparePreset(
                                                selectedPreset, presetRequest, () -> !active(token))));
                            } catch (Exception failure) {
                                throw new CompletionException(failure);
                            }
                        },
                        workerPool);
            } else {
                var files = context.userFiles();
                pendingImport = files.request(new UserFileRequest(
                                "import-model-" + suffix,
                                text("file.chooser.title", "Select GLB, glTF, OBJ, or model ZIP"),
                                List.of("glb", "gltf", "obj", "zip"),
                                UserFileMode.READ,
                                UserFileLifetime.ONE_OPERATION))
                        .thenCompose(selected -> {
                            if (selected.status() == UserFileRequestStatus.CANCELED)
                                return CompletableFuture.completedFuture(Optional.<PreparedImport>empty());
                            if (selected.status() != UserFileRequestStatus.GRANTED)
                                throw new CompletionException(new LocalizedIllegalStateException(
                                        "error.file-select", "Could not select file: {0}", selected.status()));
                            if (!active(token)) {
                                selected.handle().orElseThrow().close();
                                return CompletableFuture.completedFuture(Optional.<PreparedImport>empty());
                            }
                            var handle = selected.handle().orElseThrow();
                            var fileRequest = requestForFile(request, handle.displayName(), suffix);
                            CompletionStage<UserFileReadResult<byte[]>> pending;
                            try {
                                pending = files.readBytesBounded(handle, NativeModelImport.MAX_BYTES);
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
                                                    "error.character-file-too-large",
                                                    "GLB model exceeds the 128 MiB limit."));
                                        byte[] bytes = read.value()
                                                .orElseThrow(() -> new LocalizedIllegalStateException(
                                                        "error.file-read", "Could not read the selected model."));
                                        if (needsNativeSourceImport(bytes)) {
                                            return CompletableFuture.supplyAsync(
                                                    () -> {
                                                        if (!active(token)) return Optional.<PreparedImport>empty();
                                                        try {
                                                            return Optional.of(new PreparedImport(
                                                                    null,
                                                                    null,
                                                                    null,
                                                                    null,
                                                                    NativeModelImport.prepare(
                                                                            bytes,
                                                                            fileRequest,
                                                                            appearance,
                                                                            () -> !active(token))));
                                                        } catch (Exception error) {
                                                            throw new CompletionException(error);
                                                        }
                                                    },
                                                    workerPool);
                                        }
                                        return ModelInputFiles.normalize(
                                                        bytes,
                                                        files,
                                                        "import-model-" + suffix,
                                                        () -> active(token),
                                                        context.localization(),
                                                        workerPool)
                                                .thenApplyAsync(
                                                        normalized -> {
                                                            if (normalized.isEmpty() || !active(token))
                                                                return Optional.<PreparedImport>empty();
                                                            try {
                                                                byte[] source = normalized.orElseThrow();
                                                                var plan = prepare(source, fileRequest, appearance);
                                                                var scene = plan.character() != null
                                                                        ? plan.character()
                                                                                .materials()
                                                                                .scene()
                                                                        : GlbViewerLoader.load(
                                                                                source, () -> !active(token));
                                                                return Optional.of(new PreparedImport(
                                                                        plan.simple(),
                                                                        plan.layered(),
                                                                        plan.vertices(),
                                                                        plan.optimization(),
                                                                        plan.character(),
                                                                        scene));
                                                            } catch (Exception failure) {
                                                                throw new CompletionException(failure);
                                                            }
                                                        },
                                                        workerPool);
                                    });
                        });
            }
            pendingImport.whenComplete((prepared, failure) -> SwingUtilities.invokeLater(() -> {
                if (!active(token)) return;
                try {
                    if (failure != null) throw new CompletionException(failure);
                    if (prepared.isPresent())
                        commit(token, document.documentId(), target, prepared.orElseThrow(), false);
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

    /** Builds the shared import request; only the unique name suffix differs per run. */
    private SurfaceImport.Request importRequest(String suffix) {
        return new SurfaceImport.Request(
                text("model.default-name", "3D Model"),
                new ParameterId("Param3DYaw" + suffix),
                new ParameterId("Param3DPitch" + suffix),
                new SurfaceProjection.Camera(
                        CANVAS_CENTER_PIXELS,
                        CANVAS_CENTER_PIXELS,
                        DEFAULT_CAMERA_SCALE,
                        new SurfaceProjection.Vertex(0, 0, 0)),
                FULL_CIRCLE_ANGLE_LIMIT,
                FULL_CIRCLE_ANGLE_LIMIT,
                SurfaceImportPlan.MAX_PROJECTED_VERTICES,
                Optional.of(new ParameterId("Param3DRoll" + suffix)),
                ModelObjectNames.labels(this::text));
    }

    /**
     * Runs the combined preset window and returns the import the operator confirmed.
     *
     * <p>Each selection rebuilds the import on the window's worker; the description is rebuilt on
     * the event thread because it reports the atlas plan, which reads the open document.</p>
     *
     * @param token the enable-cycle token that bounds this request
     * @param target the document the import would extend
     * @return the confirmed prepared import, or empty when the window closed
     */
    private Optional<PreparedImport> preparePresetInteractively(long token, CubismModel target) {
        final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, REQUEST_SUFFIX_HEX_LENGTH);
        final var request = importRequest(suffix);
        final int existingPages = target.textures().textureAtlases().size();
        return ui.composePresetImport(
                preset -> {
                    if (!active(token)) throw new IllegalStateException("import cancelled");
                    final String presetName = text(preset.labelKey(), preset.fallbackLabel());
                    final var presetRequest = requestForFile(request, presetName + ".glb", suffix);
                    try {
                        // Preserve the shared source image and UVs so it can be replaced after import.
                        return new PreparedImport(
                                null,
                                null,
                                null,
                                null,
                                NativeModelImport.preparePreset(preset, presetRequest, () -> !active(token)));
                    } catch (java.io.IOException failure) {
                        throw new IllegalStateException(failure.getMessage(), failure);
                    }
                },
                prepared -> ui.describePrepared(
                        prepared, atlasSummary(target, prepared, preparedRequestName(prepared), existingPages)));
    }

    /** Plans the atlas a confirmed import would create, then reports its size only. */
    private AtlasSummary atlasSummary(
            CubismModel target, PreparedImport prepared, String requestName, int existingPages) {
        java.util.function.IntFunction<String> pageNames =
                pageIndex -> format("atlas.page-name", "{0} Atlas {1}", requestName, existingPages + pageIndex + 1);
        final var plan = planAtlas(target, prepared, pageNames);
        return new AtlasSummary(plan.pageCount(), plan.pageWidth(), plan.pageHeight());
    }

    /** @return the model name one prepared import would create */
    private static String preparedRequestName(PreparedImport prepared) {
        return prepared.character() != null
                ? prepared.character().geometry().request().name()
                : prepared.simple() != null
                        ? prepared.simple().request.name()
                        : prepared.vertices() != null
                                ? prepared.vertices().request().name()
                                : prepared.layered().surface().request.name();
    }

    /** @return the number of steps the confirmed import will report, or zero when it reports none */
    private static int stageCount(PreparedImport prepared) {
        if (prepared.character() != null) return ImportProgress.characterStages();
        if (prepared.simple() != null) return SurfaceImport.Stage.values().length;
        if (prepared.layered() != null) return SurfaceWarpAuthoring.Stage.values().length;
        return 0;
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

    private void commit(
            long token, String documentId, CubismModel target, PreparedImport prepared, boolean alreadyConfirmed) {
        requireTarget(token, documentId, target);
        if (prepared.vertices() != null) {
            prepared = finalizeEditableVertices(prepared, geometry -> {
                requireTarget(token, documentId, target);
                var result = target.drawables().previewSourceTriangulation(geometry);
                requireTarget(token, documentId, target);
                return result;
            });
        }
        String requestName = preparedRequestName(prepared);

        int existingCount = target.textures().textureAtlases().size();
        java.util.function.IntFunction<String> pageNames =
                pageIdx -> format("atlas.page-name", "{0} Atlas {1}", requestName, existingCount + pageIdx + 1);

        var atlasPlan = planAtlas(target, prepared, pageNames);
        var atlasSummary = new AtlasSummary(atlasPlan.pageCount(), atlasPlan.pageWidth(), atlasPlan.pageHeight());

        boolean confirmed = alreadyConfirmed || ui.confirmImport(prepared, atlasSummary);
        if (!confirmed) return;
        requireTarget(token, documentId, target);
        java.util.function.Supplier<CubismModel> current = () -> {
            requireTarget(token, documentId, target);
            return target;
        };

        var transactions = context.cubism().authoringTransactions();
        final PreparedImport finalPrepared = prepared;

        // The import writes the whole model inside one transaction on the event thread; without a window
        // the editor looks frozen for the whole write. The pipeline already reports its stages, so
        // surface them, and let the window paint itself because no queued repaint can run.
        final ImportProgress progress = new ImportProgress(
                text("progress.title", "Importing 3D model"),
                text("progress.preparing", "Preparing…"),
                step -> format("progress.step", "Writing the model ({0}/{1})", step[0], step[1]),
                mesh -> format("progress.mesh", "Creating meshes ({0}/{1})", mesh[0], mesh[1]),
                text("progress.writing", "Writing the model…"),
                stageCount(prepared));
        progress.show();
        final dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult<Void> result;
        try {
            result = transactions.execute(
                    dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions.of("Import 3D " + requestName),
                    () -> {
                        requireTarget(token, documentId, target);
                        boolean fitCanvas = target.canvas().widthPixels() != STANDARD_CANVAS_PIXELS
                                || target.canvas().heightPixels() != STANDARD_CANVAS_PIXELS;
                        var existingDeformers = fitCanvas
                                ? SurfacePlacementAuthoring.deformerIds(target)
                                : java.util.Set.<dev.turboism.sdk.cubism.id.DeformerId>of();
                        if (finalPrepared.character() != null) {
                            var created = NativeModelImport.assemble(
                                    target,
                                    finalPrepared.character(),
                                    atlasPlan,
                                    stage -> {
                                        context.logger().info("Native import stage: " + stage);
                                        progress.stage(stage);
                                    },
                                    transactions::batchStoredSourceWrites,
                                    progress::meshCreated);
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
                                dev.turboism.sdk.cubism.id.ArtMeshId,
                                dev.turboism.sdk.cubism.model.ModelImageBinding>();

                        if (finalPrepared.simple() != null) {
                            var imported = SurfaceImport.assemble(
                                    target,
                                    finalPrepared.simple(),
                                    stage -> progress.step(stage.ordinal(), SurfaceImport.Stage.values().length));
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
                            progress.writing();
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
                            var created = SurfaceWarpLayerAuthoring.assemble(
                                    target,
                                    finalPrepared.layered(),
                                    stage ->
                                            progress.step(stage.ordinal(), SurfaceWarpAuthoring.Stage.values().length));
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
                        ModelTextureAtlasAuthoring.apply(
                                target, meshIds, bindingsBySourceId, meshImageBindings, atlasPlan);
                        var group = target.parameterGroups().addGroup(requestName);
                        for (var parameter : target.parameters().all())
                            if (!existingParameters.contains(parameter.id()))
                                target.parameterGroups().moveParameter(parameter.id(), group.id());
                        return null;
                    });
        } finally {
            progress.close();
        }
        if (result.outcome() != AuthoringTransactionOutcome.COMMITTED) {
            String diag = result.diagnosticId().map(id -> " (" + id + ")").orElse("");
            throw new LocalizedIllegalStateException(
                    "error.transaction-uncommitted", "Import incomplete: {0}{1}", result.outcome(), diag);
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
                                "Import complete. Drag the added X / Y / Z rotation parameters (±180° each) to rotate a full circle.\nSelect the \"Overall Positioning\" deformer to move or scale.")
                        : text(
                                "success.yaw-pitch",
                                "Import complete. Drag the added Yaw / Pitch parameters to rotate."),
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
                p.geometry(),
                p.images(),
                new SurfaceEditableTopologyPlan.Limits(
                        EDITABLE_COST_MAX_VERTICES, MAX_EDITABLE_PIXELS, MAX_EDITABLE_TRIANGLES),
                preview);
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
        return new PreparedImport(null, null, vertices, optimization, null, prepared.previewScene());
    }

    private void requireTarget(long token, String documentId, CubismModel target) {
        if (!active(token)
                || !context.cubism()
                        .activeDocument()
                        .map(d -> d.documentId().equals(documentId))
                        .orElse(false)
                || !context.cubism().model().active().id().equals(target.id()))
            throw new LocalizedIllegalStateException(
                    "error.target-switched", "The target document was changed. Please initiate the import again.");
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
                userMessage =
                        text("error.generic-failure", "3D model import failed. Please check the file or try again.");
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
        private final Consumer<Throwable> log;
        private ViewerScene previewScene;

        SwingUi(
                java.util.function.Supplier<dev.turboism.sdk.i18n.PluginLocalization> i18nSupplier,
                Consumer<Throwable> log) {
            this.i18nSupplier = Objects.requireNonNull(i18nSupplier);
            this.log = Objects.requireNonNull(log);
        }

        @Override
        public boolean confirmImport(PreparedImport prepared, AtlasSummary atlas) {
            previewScene = prepared.previewScene();
            try {
                return Ui.super.confirmImport(prepared, atlas);
            } finally {
                previewScene = null;
            }
        }

        private boolean confirmDialog(String message) {
            if (previewScene == null) {
                return JOptionPane.showConfirmDialog(
                                null,
                                message,
                                text("dialog.confirm.title", "Confirm Import"),
                                JOptionPane.OK_CANCEL_OPTION)
                        == JOptionPane.OK_OPTION;
            }
            try (var preview = new ModelPreviewPanel(previewScene, key -> text(key, key), log)) {
                var content = new JPanel(new java.awt.BorderLayout(0, DIALOG_CONTENT_GAP));
                var description = new JTextPane();
                description.setEditable(false);
                description.setFocusable(false);
                description.setOpaque(false);
                description.setBorder(null);
                description.setFont(UIManager.getFont("Label.font"));
                description.setText(message);
                var alignment = new javax.swing.text.SimpleAttributeSet();
                javax.swing.text.StyleConstants.setAlignment(alignment, javax.swing.text.StyleConstants.ALIGN_CENTER);
                description.getStyledDocument().setParagraphAttributes(0, message.length(), alignment, false);
                int width =
                        description.getFontMetrics(description.getFont()).charWidth('m') * DIALOG_DESCRIPTION_EM_WIDTH;
                description.setSize(width, Short.MAX_VALUE);
                description.setPreferredSize(new java.awt.Dimension(width, description.getPreferredSize().height));
                content.add(preview, java.awt.BorderLayout.CENTER);
                content.add(description, java.awt.BorderLayout.SOUTH);
                return JOptionPane.showConfirmDialog(
                                null,
                                content,
                                text("dialog.confirm.title", "Confirm Import"),
                                JOptionPane.OK_CANCEL_OPTION,
                                JOptionPane.PLAIN_MESSAGE)
                        == JOptionPane.OK_OPTION;
            }
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
        public String describePrepared(PreparedImport prepared, AtlasSummary atlas) {
            return prepared.character() == null
                    ? text("dialog.preset.prepared", "Ready to import.")
                    : describeCharacter(prepared.character(), atlas);
        }

        @Override
        public boolean confirmCharacter(NativeModelImport.Prepared p, AtlasSummary atlas) {
            return confirmDialog(describeCharacter(p, atlas));
        }

        private String atlasInfo(AtlasSummary atlas) {
            return format(
                    "dialog.confirm.atlas-info",
                    "Texture atlas: Will create {0} atlas page(s) ({1}×{2}).",
                    atlas.pageCount(),
                    atlas.width(),
                    atlas.height());
        }

        private String describeCharacter(NativeModelImport.Prepared p, AtlasSummary atlas) {
            var geometry = p.geometry();
            String atlasInfo = atlasInfo(atlas);
            long partCount = p.materials().scene().meshes().stream()
                    .filter(m -> m.visible())
                    .map(m -> m.bodyPart().isBlank() ? m.appearanceGroup() : m.bodyPart())
                    .distinct()
                    .count();
            String sourceControls = format(
                    "dialog.confirm.character-controls",
                    "Source shapes and visibility: {0} Blend Shape parameters; {1} source parts.",
                    p.controls().controls().size(),
                    partCount);
            return format(
                    "dialog.confirm.character",
                    "Will create {0} native meshes, preserving {1} triangles.\nX / Y have {2} binding points each; Z supports full-circle rotation.\nOcclusion uses grouped sorting; complex intersections can show deviations.\n{3}\nThe import can be undone in one step. Continue?",
                    geometry.partitions().size(),
                    geometry.totalTriangles(),
                    geometry.keys().size(),
                    atlasInfo + "\n" + sourceControls);
        }

        @Override
        public boolean composesPresetPreview() {
            return true;
        }

        @Override
        public Optional<PreparedImport> composePresetImport(
                final Function<ModelPreset, PreparedImport> build, final Function<PreparedImport, String> describe) {
            final ModelPreset[] presets = ModelPreset.values();
            try (var dialog = new PresetImportDialog(
                    owner(), List.of(presets), presets[0], build::apply, describe::apply, key -> text(key, key), log)) {
                return dialog.show();
            }
        }

        private static java.awt.Window owner() {
            for (java.awt.Frame frame : java.awt.Frame.getFrames()) {
                if (frame.isShowing() && frame.getTitle().contains("Live2D Cubism Editor")) return frame;
            }
            return java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .getActiveWindow();
        }

        @Override
        public Optional<ModelPreset> preset() {
            var presets = ModelPreset.values();
            String[] labels = Arrays.stream(presets)
                    .map(preset -> text(preset.labelKey(), preset.fallbackLabel()))
                    .toArray(String[]::new);
            Object selected = JOptionPane.showInputDialog(
                    null,
                    text(
                            "dialog.preset.message",
                            "Choose a shape. Replace the model image after importing to change its texture; keep the image dimensions and UV layout."),
                    text("action.preset.label", "Import preset…"),
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    labels,
                    labels[0]);
            if (selected == null) return Optional.empty();
            for (int i = 0; i < labels.length; i++) {
                if (labels[i].equals(selected)) return Optional.of(presets[i]);
            }
            return Optional.empty();
        }

        @Override
        public int appearance() {
            Object[] choices = {
                text("dialog.appearance.option.source-material", "Keep Original Materials"),
                text("dialog.appearance.option.face-colors", "Distinct Face Colors"),
                text("dialog.appearance.option.cancel", "Cancel")
            };
            return JOptionPane.showOptionDialog(
                    null,
                    text(
                            "dialog.appearance.message",
                            "Select source materials to import large GLB characters in their default skin and morph pose.\nNative meshes use base color, emissive and alpha textures; normal maps and source lighting are not reproduced.\nStatic glTF, OBJ and ZIP packages retain their existing size and material limits.\nBoth color modes support full-circle X / Y / Z rotation (±180° each).\nLarge models can take several minutes and can still fail; keep the document open until the import finishes.\nColor mode:"),
                    text("dialog.appearance.title", "Import 3D Model"),
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
            String atlasInfo = atlas != null ? atlasInfo(atlas) : "";
            return confirmDialog(format(
                    "dialog.confirm.simple",
                    "Will create {0} meshes with {1} total keys.\nHorizontal / vertical binding points: {2} / {3}.\n{4}\nCan be undone in a single undo step. Continue?",
                    faces,
                    faces * keys,
                    p.geometry.grid().yawKeys().size(),
                    p.geometry.grid().pitchKeys().size(),
                    atlasInfo));
        }

        @Override
        public boolean confirmLayers(SurfaceWarpLayerAuthoring.Prepared layered) {
            return confirmLayers(layered, null);
        }

        @Override
        public boolean confirmLayers(SurfaceWarpLayerAuthoring.Prepared layered, AtlasSummary atlas) {
            var p = layered.surface();
            int meshes = p.geometry.meshes().size();
            String rollNote = p.request.roll().isPresent()
                    ? text("dialog.confirm.layers.roll-note", " Z rotation: 5 points, ±180° on all three axes.")
                    : "";
            String atlasInfo = atlas != null ? atlasInfo(atlas) : "";
            return confirmDialog(format(
                    "dialog.confirm.layers",
                    "Will create {0} curved meshes and {1} deformers, preserving source model geometry and textures.\nHorizontal / vertical binding points: {2} / {3}.{4}\nOcclusion layer sampling points: {5} / {6}.\n{7}\nComplex overlapping might still show deviations during rotation. Can be undone in a single undo step. Continue?",
                    meshes,
                    (p.request.roll().isPresent() ? DEFORMER_COUNT_XYZ : DEFORMER_COUNT_XY),
                    p.yawKeys.size(),
                    p.pitchKeys.size(),
                    rollNote,
                    layered.layerYawKeys().size(),
                    layered.layerPitchKeys().size(),
                    atlasInfo));
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
            String atlasInfo = atlas != null ? atlasInfo(atlas) : "";
            if (optimization == null) {
                int meshes = p.geometry().meshes().size();
                long triangles = p.geometry().meshes().stream()
                        .mapToLong(m -> m.triangles().size())
                        .sum();
                return confirmDialog(format(
                        "dialog.confirm.vertices",
                        "Will create {0} curved meshes, preserving original {1} triangles, UVs, and textures.\n{2}\n9 binding points each for X / Y using elliptical interpolation; Z rotation: 5 points, ±180° on all three axes.\nOcclusion uses grouped approximation; complex overlapping might still show minor deviations.\nCan be undone in a single undo step. Select the \"Overall Positioning\" deformer to move or scale. Continue?",
                        meshes,
                        triangles,
                        atlasInfo));
            }
            String bound = String.format(Locale.ROOT, "%.2f", optimization.combinedErrorBoundPixels());
            return confirmDialog(format(
                    "dialog.confirm.vertices-optimized",
                    "Will create {0} curved meshes.\nSource model: {1} faces -> imported {2} faces, preserving material boundaries and UV seams.\n{4}\nCombined error bound for surface geometry and parameter interpolation: {3} pixels (excluding occlusion grouping approximation).\n9-point elliptical interpolation for X / Y; 5 points for Z; ±180° on all three axes.\nSelect the \"Overall Positioning\" deformer to move or scale. Can be undone in a single undo step. Continue?",
                    p.geometry().meshes().size(),
                    optimization.sourceTriangles(),
                    optimization.outputTriangles(),
                    bound,
                    atlasInfo));
        }

        @Override
        public void message(String text, boolean error) {
            JOptionPane.showMessageDialog(
                    null,
                    text,
                    error
                            ? text("dialog.message.error.title", "Cannot Import 3D Model")
                            : text("dialog.message.info.title", "3D Model"),
                    error ? JOptionPane.ERROR_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
        }
    }
}
