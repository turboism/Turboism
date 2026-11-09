package dev.turboism.tests.plugin;

import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.cubism.command.EditorCommand;
import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.cubism.command.EditorCommandService;
import dev.turboism.sdk.cubism.history.CubismHistory;
import dev.turboism.sdk.cubism.history.HistoryEntry;
import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmPreset;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithmRegistry;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.TexturePixels;
import dev.turboism.sdk.plugin.PluginContext;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

/**
 * Exact-host probe for the Auto Mesh framework: algorithm registry, native funnel guard, dispatch,
 * Undo/Redo and the selector row.
 *
 * <p>The probe runs inside a real Editor with the production runtime and the Turbo Mesh plugin
 * loaded. It never substitutes a fake host: every step reads the live registry, the live native
 * funnel and the live Undo history, because the framework's whole claim is that an unmodified Cubism
 * build runs a plugin algorithm through its own Auto Mesh Generate entry.</p>
 *
 * <p>Two properties make the verdict trustworthy. First, the native path is proven deterministic
 * before any custom run happens, so a later difference cannot be blamed on host nondeterminism.
 * Second, every geometry assertion compares content digests rather than vertex counts, because a
 * mesh can keep its count and lose its shape.</p>
 *
 * <p>System properties:
 *
 * <ul>
 *   <li>{@code turboism.automeshValidation.meshIds} — comma-separated ArtMesh ids to probe; an
 *       absent id is skipped with a recorded reason
 *   <li>{@code turboism.automeshValidation.multiKeyformMeshIds} — comma-separated ArtMesh ids that
 *       carry more than one keyform
 *   <li>{@code turboism.automeshValidation.dumpDir} — absolute dump directory the offline renderer
 *       reads
 *   <li>{@code turboism.automeshValidation.stepTimeoutSeconds} — budget of one host step
 *   <li>{@code turboism.validation.exitOnComplete} — close the host after the run
 * </ul>
 */
public final class AutoMeshFrameworkValidationProbe implements CubismPlugin {

    /** Result file the wrapper reads, relative to the plugin state directory. */
    static final String RESULT_FILE = "automesh-framework-validation.properties";

    /** Ready marker the wrapper waits for before it starts the result timeout. */
    static final String READY_MARKER = "AUTOMESH_FRAMEWORK_PROBE_READY";

    /** Result marker the wrapper treats as a failure. */
    static final String FAILURE_MARKER = "AUTOMESH_FRAMEWORK_RESULT status=FAIL";

    static final String MESH_IDS_PROPERTY = "turboism.automeshValidation.meshIds";
    static final String MULTI_KEYFORM_MESH_IDS_PROPERTY = "turboism.automeshValidation.multiKeyformMeshIds";
    static final String DUMP_DIR_PROPERTY = "turboism.automeshValidation.dumpDir";
    static final String STEP_TIMEOUT_PROPERTY = "turboism.automeshValidation.stepTimeoutSeconds";
    static final String CUSTOM_SETTLE_TIMEOUT_PROPERTY = "turboism.automeshValidation.customSettleTimeoutSeconds";
    static final String EXIT_PROPERTY = "turboism.validation.exitOnComplete";
    static final String HOST_VERSION_PROPERTY = "turboism.validation.hostVersion";
    static final String RUN_ID_PROPERTY = "turboism.validation.runId";
    static final String FIXTURE_NAME_PROPERTY = "turboism.validation.fixtureName";

    /** Default probe targets: a hair mesh with a dense silhouette, then a small ordinary mesh. */
    static final String DEFAULT_MESH_IDS = "ArtMesh342,hfa10";

    /** Smallest accepted Alpha IoU of a custom mesh against its source silhouette. */
    static final double MINIMUM_ALPHA_IOU = 0.60;

    /** Absolute triangle area at or below which a triangle counts as degenerate. */
    static final double DEGENERATE_AREA_TOLERANCE = 1.0e-9;

    /** Native Undo label the framework publishes for one custom run. */
    static final String UNDO_LABEL = "Auto Mesh";

    /** One custom run adds exactly one Undo entry. */
    static final int EXPECTED_UNDO_ENTRIES = 1;

    static final String REGION_ALGORITHM_ID = "turbo-mesh.region";
    static final String ADAPTIVE_ALGORITHM_ID = "turbo-mesh.adaptive";
    static final String NATIVE_ALGORITHM_ID = AutoMeshAlgorithm.NATIVE_ALGORITHM_ID;

    /**
     * Settle budget of one custom run. The executor stops waiting after ten seconds and the write
     * returns to the UI thread, so this budget covers a compute, a commit and a refusal.
     */
    private static final Duration DEFAULT_CUSTOM_SETTLE_TIMEOUT = Duration.ofSeconds(30);

    private static final Duration DEFAULT_STEP_TIMEOUT = Duration.ofSeconds(120);
    private static final long POLL_MILLIS = 250L;
    private static final int NATIVE_RUN_ATTEMPTS = 2;
    private static final String VARIANT_NATIVE = "native";
    private static final String KEY_SEPARATOR = ".";
    private static final String GUARD_MODELING_KEY = "turboism.automesh.guard.modeling-mode";
    private static final String GUARD_MESH_EDITOR_KEY = "turboism.automesh.guard.mesh-editor-mode";
    private static final String GUARD_ATTACH_KEY = "turboism.automesh.attach";
    private static final String FUNCTION_TYPE = "java.util.function.Function";
    private static final String ABSENT = "<absent>";
    private static final String ABSENT_SKIPPED = "<absent: skipped>";
    private static final String HOST_VERSION_PATTERN = "5\\.[0-9]\\.[0-9]{2}";
    private static final String UNSAFE_PATH_CHARACTERS = "[^A-Za-z0-9._-]";

    private final List<AutoMeshProbeStatus> statuses = new ArrayList<>();
    private final AutoMeshProbeResult result = new AutoMeshProbeResult();
    private PluginContext context;
    private Thread worker;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        context.logger().info(READY_MARKER);
    }

    @Override
    public void enable() {
        worker = new Thread(this::run, "turboism-automesh-framework-validation");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void disable() {
        if (worker != null) {
            worker.interrupt();
        }
    }

    private void run() {
        Path dumpRoot = null;
        try {
            dumpRoot = dumpRoot();
            result.put("schemaVersion", 1);
            result.put("runId", property(RUN_ID_PROPERTY, "unknown"));
            result.put("hostVersion", property(HOST_VERSION_PROPERTY, "unknown"));
            result.put("dumpDir", dumpRoot);
            checkRegistry();
            checkGuardInstalled();
            checkNativePathDeterministic(dumpRoot);
            checkCustomPath(dumpRoot);
            checkMultiKeyformRefusal();
            checkSelectorRow(dumpRoot);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            record("run.interrupted", "no interruption", "interrupted", AutoMeshProbeStatus.BLOCKED);
        } catch (Exception failure) {
            record("run", "completed", describe(failure), AutoMeshProbeStatus.FAIL);
        } finally {
            publish(dumpRoot);
        }
    }

    // --- a. Registry --------------------------------------------------------

    private void checkRegistry() {
        final AutoMeshAlgorithmRegistry registry = registry();
        final List<AutoMeshAlgorithm> algorithms = registry.algorithms();
        final List<String> ids = new ArrayList<>(algorithms.size());
        for (final AutoMeshAlgorithm algorithm : algorithms) {
            ids.add(algorithm.id());
        }
        record(
                "registry.listed",
                "[" + NATIVE_ALGORITHM_ID + ", " + REGION_ALGORITHM_ID + ", " + ADAPTIVE_ALGORITHM_ID + "]",
                ids.toString(),
                statusOf(!algorithms.isEmpty()
                        && NATIVE_ALGORITHM_ID.equals(ids.get(0))
                        && ids.contains(REGION_ALGORITHM_ID)
                        && ids.contains(ADAPTIVE_ALGORITHM_ID)));
        record(
                "registry.nativeFirst",
                NATIVE_ALGORITHM_ID,
                ids.isEmpty() ? ABSENT : ids.get(0),
                statusOf(!ids.isEmpty() && NATIVE_ALGORITHM_ID.equals(ids.get(0))));
        final Optional<String> selected = registry.selectedAlgorithmId();
        record(
                "registry.selectionDefault",
                ABSENT,
                selected.orElse(ABSENT),
                statusOf(selected.isEmpty() || NATIVE_ALGORITHM_ID.equals(selected.orElseThrow())));
        for (final String id : List.of(REGION_ALGORITHM_ID, ADAPTIVE_ALGORITHM_ID)) {
            final Optional<AutoMeshAlgorithm> algorithm = registry.find(id);
            record(
                    "registry" + KEY_SEPARATOR + id + ".found",
                    id,
                    algorithm.map(AutoMeshAlgorithm::id).orElse(ABSENT),
                    statusOf(algorithm.isPresent()));
            if (algorithm.isEmpty()) {
                continue;
            }
            final AutoMeshAlgorithm found = algorithm.orElseThrow();
            result.put(
                    "algorithm" + KEY_SEPARATOR + id + ".presets",
                    presetNames(found).toString());
            result.put(
                    "algorithm" + KEY_SEPARATOR + id + ".parameters",
                    Integer.toString(found.parameters().size()));
            result.put(
                    "algorithm" + KEY_SEPARATOR + id + ".supportsMultiKeyform",
                    Boolean.toString(found.supportsMultiKeyform()));
        }
        record(
                "registry.nativeIsNative",
                "true",
                Boolean.toString(registry.find(NATIVE_ALGORITHM_ID)
                        .map(AutoMeshAlgorithm::isNative)
                        .orElse(false)),
                statusOf(registry.find(NATIVE_ALGORITHM_ID)
                        .map(AutoMeshAlgorithm::isNative)
                        .orElse(false)));
    }

    // --- b. Guard installed -------------------------------------------------

    /**
     * Proves the guard is live by reading the installer's own published state.
     *
     * <p>The bridge publishes one callback property per funnel plus the attach callback, and removes
     * them again when the installer closes. Their presence is therefore the observable proof that the
     * transformed funnels and the three container hooks are active on this exact build.</p>
     */
    private void checkGuardInstalled() {
        for (final Map.Entry<String, String> probe : Map.of(
                        "guard.modelingFunnel", GUARD_MODELING_KEY,
                        "guard.meshEditorFunnel", GUARD_MESH_EDITOR_KEY,
                        "guard.containerAttach", GUARD_ATTACH_KEY)
                .entrySet()) {
            final String type = callbackType(probe.getValue());
            record(probe.getKey(), FUNCTION_TYPE, type, statusOf(FUNCTION_TYPE.equals(type)));
        }
        final String version = property(HOST_VERSION_PROPERTY, "");
        record(
                "guard.hostVersionAdmitted",
                "an exact reviewed release code",
                version.isBlank() ? ABSENT : version,
                statusOf(version.matches(HOST_VERSION_PATTERN)));
    }

    private static String callbackType(final String key) {
        final Object value = System.getProperties().get(key);
        return value == null ? ABSENT : value.getClass().getName();
    }

    // --- c. Native path untouched ------------------------------------------

    /**
     * Runs the native funnel twice on one ArtMesh and compares the two results.
     *
     * <p>The determinism run comes first. Without it a later difference between native and custom
     * results could be host nondeterminism rather than a framework fault, so the comparison would
     * prove nothing.</p>
     *
     * @param dumpRoot the dump directory of this run
     * @throws Exception when the host refuses a read or write
     */
    private void checkNativePathDeterministic(final Path dumpRoot) throws Exception {
        final Optional<Drawable> target = firstProbeTarget();
        if (target.isEmpty()) {
            record("native.target", "a probe ArtMesh", ABSENT_SKIPPED, AutoMeshProbeStatus.INFO);
            return;
        }
        final String id = target.orElseThrow().id().value();
        registry().select(NATIVE_ALGORITHM_ID);
        final Optional<String> selected = registry().selectedAlgorithmId();
        record(
                "native.selection",
                ABSENT,
                selected.orElse(ABSENT),
                statusOf(selected.isEmpty() || NATIVE_ALGORITHM_ID.equals(selected.orElseThrow())));

        final List<String> digests = new ArrayList<>(NATIVE_RUN_ATTEMPTS);
        for (int attempt = 0; attempt < NATIVE_RUN_ATTEMPTS; attempt++) {
            final ArtMeshGeometry before = geometryOf(id);
            final Optional<ArtMeshGeometry> after = runFunnel(id, false);
            if (after.isEmpty()) {
                record("native.run" + attempt, "a native geometry", ABSENT_SKIPPED, AutoMeshProbeStatus.INFO);
                restoreGeometry(id, before);
                return;
            }
            final ArtMeshGeometry geometry = after.orElseThrow();
            final String digest = AutoMeshGeometryDigest.of(geometry);
            digests.add(digest);
            result.put(
                    "native.run" + attempt + ".vertices",
                    Integer.toString(geometry.positions().size()));
            result.put(
                    "native.run" + attempt + ".triangles",
                    Integer.toString(geometry.triangleIndices().size() / 3));
            result.put("native.run" + attempt + ".digest", digest);
            AutoMeshDumpWriter.write(
                    dumpRoot.resolve(sanitize(id)).resolve(VARIANT_NATIVE + "-run" + attempt),
                    id,
                    VARIANT_NATIVE + "-run" + attempt,
                    geometry,
                    textureOf(id),
                    "native funnel run " + attempt);
            restoreGeometry(id, before);
        }
        record(
                "native.deterministic",
                "one digest across " + NATIVE_RUN_ATTEMPTS + " runs",
                digests.toString(),
                statusOf(digests.size() == NATIVE_RUN_ATTEMPTS && digests.get(0).equals(digests.get(1))));
        result.put("native.digest", digests.isEmpty() ? ABSENT : digests.get(0));
    }

    // --- d. Custom path ---------------------------------------------------

    private void checkCustomPath(final Path dumpRoot) throws Exception {
        for (final String algorithmId : List.of(REGION_ALGORITHM_ID, ADAPTIVE_ALGORITHM_ID)) {
            final Optional<AutoMeshAlgorithm> algorithm = registry().find(algorithmId);
            if (algorithm.isEmpty()) {
                record(
                        "custom" + KEY_SEPARATOR + algorithmId + ".available",
                        algorithmId,
                        ABSENT_SKIPPED,
                        AutoMeshProbeStatus.INFO);
                continue;
            }
            checkOneCustomRun(dumpRoot, algorithm.orElseThrow());
        }
    }

    /**
     * Runs one custom algorithm through the real funnel and verifies its whole contract.
     *
     * <p>The preset list is recorded rather than applied: the SDK exposes no preset-selection call,
     * so only the selector row can change a preset. The run therefore uses the algorithm's declared
     * default values, and the recorded preset names prove the declaration reached the SDK.</p>
     */
    private void checkOneCustomRun(final Path dumpRoot, final AutoMeshAlgorithm algorithm) throws Exception {
        final Optional<Drawable> target = firstProbeTarget();
        if (target.isEmpty()) {
            record("custom.target", "a probe ArtMesh", ABSENT_SKIPPED, AutoMeshProbeStatus.INFO);
            return;
        }
        final String id = target.orElseThrow().id().value();
        final String label = algorithm.id();
        final ArtMeshGeometry original = geometryOf(id);
        final String originalDigest = AutoMeshGeometryDigest.of(original);
        final int historyBefore = historySize();

        registry().select(algorithm.id());
        final Optional<ArtMeshGeometry> applied = runFunnel(id, true);
        if (applied.isEmpty()) {
            record(
                    "custom" + KEY_SEPARATOR + label + ".applied",
                    "a custom geometry",
                    ABSENT_SKIPPED,
                    AutoMeshProbeStatus.INFO);
            restoreGeometry(id, original);
            registry().select(NATIVE_ALGORITHM_ID);
            return;
        }
        final ArtMeshGeometry geometry = applied.orElseThrow();
        final String digest = AutoMeshGeometryDigest.of(geometry);
        if (digest.equals(originalDigest)) {
            // The algorithm answered a refusal instead of a mesh. The region algorithm reports
            // `no-texture` until the runtime exposes Drawable.texturePixels(), so the probe states
            // that reason instead of failing on a framework gap it does not own.
            record(
                    "custom" + KEY_SEPARATOR + label + ".applied",
                    "a custom geometry",
                    "refused: " + lastHistoryLabel(),
                    AutoMeshProbeStatus.INFO);
            result.put("custom" + KEY_SEPARATOR + label + ".refused", "true");
            registry().select(NATIVE_ALGORITHM_ID);
            return;
        }
        result.put("custom" + KEY_SEPARATOR + label + ".refused", "false");
        result.put(
                "custom" + KEY_SEPARATOR + label + ".vertices",
                Integer.toString(geometry.positions().size()));
        result.put(
                "custom" + KEY_SEPARATOR + label + ".triangles",
                Integer.toString(geometry.triangleIndices().size() / 3));
        result.put("custom" + KEY_SEPARATOR + label + ".digest", digest);
        record(
                "custom" + KEY_SEPARATOR + label + ".changed",
                "a digest different from the source",
                digest,
                statusOf(!digest.equals(originalDigest)));
        final int degenerate = AutoMeshGeometryDigest.degenerateTriangles(geometry, DEGENERATE_AREA_TOLERANCE);
        record(
                "custom" + KEY_SEPARATOR + label + ".degenerateTriangles",
                "0",
                Integer.toString(degenerate),
                statusOf(degenerate == 0));
        final int unreferenced = AutoMeshGeometryDigest.unreferencedVertices(geometry);
        record(
                "custom" + KEY_SEPARATOR + label + ".unreferencedVertices",
                "0",
                Integer.toString(unreferenced),
                statusOf(unreferenced == 0));
        final int addedEntries = historySize() - historyBefore;
        record(
                "custom" + KEY_SEPARATOR + label + ".undoEntries",
                Integer.toString(EXPECTED_UNDO_ENTRIES),
                Integer.toString(addedEntries),
                statusOf(addedEntries == EXPECTED_UNDO_ENTRIES));
        final String undoLabel = lastHistoryLabel();
        record(
                "custom" + KEY_SEPARATOR + label + ".undoLabel",
                UNDO_LABEL,
                undoLabel,
                statusOf(UNDO_LABEL.equals(undoLabel)));
        checkAlphaCoverage(label, geometry, textureOf(id));
        AutoMeshDumpWriter.write(
                dumpRoot.resolve(sanitize(id)).resolve(sanitize(algorithm.id())),
                id,
                algorithm.id(),
                geometry,
                textureOf(id),
                "custom funnel run with the declared default parameters");

        executeCommand("custom" + KEY_SEPARATOR + label + ".undo", EditorCommand.UNDO);
        final String afterUndo = AutoMeshGeometryDigest.of(awaitGeometry(id, originalDigest));
        record(
                "custom" + KEY_SEPARATOR + label + ".undoRestores",
                originalDigest,
                afterUndo,
                statusOf(originalDigest.equals(afterUndo)));
        executeCommand("custom" + KEY_SEPARATOR + label + ".redo", EditorCommand.REDO);
        final String afterRedo = AutoMeshGeometryDigest.of(awaitGeometry(id, digest));
        record(
                "custom" + KEY_SEPARATOR + label + ".redoReapplies",
                digest,
                afterRedo,
                statusOf(digest.equals(afterRedo)));
        restoreGeometry(id, original);
        registry().select(NATIVE_ALGORITHM_ID);
    }

    private void checkAlphaCoverage(final String label, final ArtMeshGeometry geometry, final TexturePixels texture) {
        if (texture == null) {
            record(
                    "custom" + KEY_SEPARATOR + label + ".alphaIou",
                    ">= " + MINIMUM_ALPHA_IOU,
                    "<no texture pixels>",
                    AutoMeshProbeStatus.INFO);
            return;
        }
        final double iou = AutoMeshAlphaCoverage.intersectionOverUnion(
                geometry,
                texture,
                AutoMeshAlphaCoverage.DEFAULT_ALPHA_THRESHOLD,
                AutoMeshAlphaCoverage.DEFAULT_MAX_RASTER_EDGE);
        final double recall = AutoMeshAlphaCoverage.opaqueRecall(
                geometry,
                texture,
                AutoMeshAlphaCoverage.DEFAULT_ALPHA_THRESHOLD,
                AutoMeshAlphaCoverage.DEFAULT_MAX_RASTER_EDGE);
        result.put("custom" + KEY_SEPARATOR + label + ".alphaIou", AutoMeshProbeResult.decimal(iou));
        result.put("custom" + KEY_SEPARATOR + label + ".alphaRecall", AutoMeshProbeResult.decimal(recall));
        record(
                "custom" + KEY_SEPARATOR + label + ".alphaIou",
                ">= " + MINIMUM_ALPHA_IOU,
                AutoMeshProbeResult.decimal(iou),
                statusOf(iou >= MINIMUM_ALPHA_IOU));
    }

    // --- e. Multi-keyform refusal -----------------------------------------

    /**
     * Proves the framework refuses a multi-keyform target instead of collapsing its keyforms.
     *
     * <p>The check is a refusal check, not a run check: the geometry must be identical afterwards and
     * no Undo entry may appear, because a native fallback would silently ignore the user's algorithm
     * choice.</p>
     *
     * @throws Exception when the host refuses a read
     */
    private void checkMultiKeyformRefusal() throws Exception {
        final Optional<Drawable> target = multiKeyformTarget();
        if (target.isEmpty()) {
            record(
                    "multiKeyform.target",
                    "an ArtMesh with more than one keyform",
                    ABSENT_SKIPPED,
                    AutoMeshProbeStatus.INFO);
            return;
        }
        final String id = target.orElseThrow().id().value();
        final ArtMeshGeometry original = geometryOf(id);
        final String originalDigest = AutoMeshGeometryDigest.of(original);
        final int historyBefore = historySize();
        final Optional<AutoMeshAlgorithm> algorithm = registry().find(REGION_ALGORITHM_ID);
        if (algorithm.isEmpty()) {
            record("multiKeyform.algorithm", REGION_ALGORITHM_ID, ABSENT_SKIPPED, AutoMeshProbeStatus.INFO);
            return;
        }
        registry().select(REGION_ALGORITHM_ID);
        runFunnel(id, true);
        registry().select(NATIVE_ALGORITHM_ID);
        final String afterDigest = AutoMeshGeometryDigest.of(geometryOf(id));
        record(
                "multiKeyform.geometryUnchanged",
                originalDigest,
                afterDigest,
                statusOf(originalDigest.equals(afterDigest)));
        final int addedEntries = historySize() - historyBefore;
        record("multiKeyform.undoEntries", "0", Integer.toString(addedEntries), statusOf(addedEntries == 0));
        record(
                "multiKeyform.noNativeFallback",
                "the source geometry",
                afterDigest,
                statusOf(originalDigest.equals(afterDigest)));
        restoreGeometry(id, original);
    }

    // --- g. Selector row ---------------------------------------------------

    /**
     * Proves the selector row is attached to a live native container.
     *
     * <p>The attach hook runs inside the container constructor, so only a real container proves it.
     * The probe builds the native modeling popup, counts the centre children the row was appended to,
     * captures the widget as a PNG, and disposes it again.</p>
     *
     * @param dumpRoot the dump directory the capture belongs to
     * @throws Exception when the host refuses a container operation
     */
    private void checkSelectorRow(final Path dumpRoot) throws Exception {
        final Optional<Object> container = onUi(NativeAutoMeshPopup::open);
        if (container.isEmpty()) {
            record("selector.popup", "a native Auto Mesh container", ABSENT_SKIPPED, AutoMeshProbeStatus.INFO);
            result.put("selector.screenshot", "<unavailable: the native popup could not be built>");
            return;
        }
        final Object popup = container.orElseThrow();
        try {
            final int children = NativeAutoMeshPopup.childCount(popup);
            result.put("selector.containerChildren", Integer.toString(children));
            record("selector.rowAttached", "> 0 centre children", Integer.toString(children), statusOf(children > 0));
            final Optional<Path> screenshot = NativeAutoMeshPopup.capture(popup, dumpRoot);
            if (screenshot.isPresent()) {
                result.put("selector.screenshot", screenshot.orElseThrow().toString());
                record(
                        "selector.screenshot",
                        "a PNG file",
                        screenshot.orElseThrow().toString(),
                        AutoMeshProbeStatus.INFO);
            } else {
                result.put("selector.screenshot", "<unavailable: the widget could not be painted>");
                record("selector.screenshot", "a PNG file", "<unavailable>", AutoMeshProbeStatus.INFO);
            }
        } finally {
            NativeAutoMeshPopup.close(popup);
        }
    }

    // --- host plumbing ----------------------------------------------------

    private AutoMeshAlgorithmRegistry registry() {
        return context.services()
                .find(AutoMeshAlgorithmRegistry.class)
                .orElseThrow(() -> new IllegalStateException("the runtime exposes no auto-mesh registry"));
    }

    private Path dumpRoot() {
        final String configured = System.getProperty(DUMP_DIR_PROPERTY, "");
        return configured.isBlank() ? context.paths().stateDir().resolve("automesh-dump") : Path.of(configured);
    }

    private Optional<Drawable> firstProbeTarget() throws Exception {
        for (final String id : splitProperty(MESH_IDS_PROPERTY, DEFAULT_MESH_IDS)) {
            final Optional<Drawable> drawable = findDrawable(id);
            if (drawable.isPresent()) {
                return drawable;
            }
        }
        return Optional.empty();
    }

    private Optional<Drawable> multiKeyformTarget() throws Exception {
        for (final String id : splitProperty(MULTI_KEYFORM_MESH_IDS_PROPERTY, "")) {
            final Optional<Drawable> drawable = findDrawable(id);
            if (drawable.isPresent() && keyformCount(drawable.orElseThrow()) > 1) {
                return drawable;
            }
        }
        return Optional.empty();
    }

    private static int keyformCount(final Drawable drawable) {
        try {
            return drawable.authoringSnapshot().forms().size();
        } catch (RuntimeException | LinkageError unavailable) {
            return 0;
        }
    }

    private Optional<Drawable> findDrawable(final String id) throws Exception {
        return onUi(() -> {
            try {
                for (final Drawable drawable :
                        context.cubism().model().active().drawables().all()) {
                    if (drawable.id().value().equals(id)) {
                        return Optional.of(drawable);
                    }
                }
            } catch (IllegalStateException | UnsupportedOperationException unavailable) {
                // The document is still loading; the caller treats an empty answer as absent.
            }
            return Optional.<Drawable>empty();
        });
    }

    private ArtMeshGeometry geometryOf(final String id) throws Exception {
        return onUi(() -> drawableOf(id).geometry());
    }

    private TexturePixels textureOf(final String id) throws Exception {
        return onUi(() -> drawableOf(id).texturePixels().orElse(null));
    }

    private Drawable drawableOf(final String id) {
        for (final Drawable drawable :
                context.cubism().model().active().drawables().all()) {
            if (drawable.id().value().equals(id)) {
                return drawable;
            }
        }
        throw new IllegalStateException("ArtMesh is absent from the active model: " + id);
    }

    private void restoreGeometry(final String id, final ArtMeshGeometry geometry) throws Exception {
        onUi(() -> {
            drawableOf(id).replaceGeometry(geometry);
            return null;
        });
    }

    private ArtMeshGeometry awaitGeometry(final String id, final String expectedDigest) throws Exception {
        final long deadline = System.nanoTime() + stepTimeout().toNanos();
        ArtMeshGeometry last = geometryOf(id);
        while (!AutoMeshGeometryDigest.of(last).equals(expectedDigest) && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MILLIS);
            last = geometryOf(id);
        }
        return last;
    }

    private int historySize() {
        return history().snapshot().entries().size();
    }

    private String lastHistoryLabel() {
        final List<HistoryEntry> entries = history().snapshot().entries();
        return entries.isEmpty() ? "<none>" : entries.get(entries.size() - 1).label();
    }

    private CubismHistory history() {
        return context.cubism().history();
    }

    private void executeCommand(final String phase, final EditorCommand command) {
        final EditorCommandService service =
                context.services().find(EditorCommandService.class).orElse(EditorCommandService.unavailable());
        if (!service.isAvailable()) {
            record(phase + ".command", "an available command service", "<unavailable>", AutoMeshProbeStatus.BLOCKED);
            return;
        }
        final EditorCommandResult commandResult = service.execute(command);
        record(phase + ".command", "EXECUTED", commandResult.toString(), statusOf(commandResult.executed()));
    }

    /**
     * Runs the real native funnel for one ArtMesh and returns the geometry it left behind.
     *
     * <p>The funnel is invoked through the host's own classes, exactly as the native popup drives it,
     * so the transformed guard sees the call the Editor makes. The geometry is read after the
     * funnel's completion callback fired.</p>
     *
     * @param id the ArtMesh to generate for
     * @param custom whether a plugin algorithm owns the confirmation
     * @return the geometry after the funnel, or empty when the funnel could not run
     * @throws Exception when the host refuses a read
     */
    private Optional<ArtMeshGeometry> runFunnel(final String id, final boolean custom) throws Exception {
        final CountDownLatch completed = new CountDownLatch(1);
        final String invocation = onUi(() -> NativeAutoMeshFunnel.invoke(id, completed));
        result.put("funnel.invocation", invocation);
        if (!NativeAutoMeshFunnel.INVOKED.equals(invocation)) {
            completed.countDown();
            return Optional.empty();
        }
        if (!custom) {
            if (!completed.await(stepTimeout().toSeconds(), TimeUnit.SECONDS)) {
                result.put("funnel.completion", "timeout");
                return Optional.empty();
            }
            result.put("funnel.completion", "native-callback");
            return Optional.of(geometryOf(id));
        }
        // A custom confirmation returns from the guard before the native body runs, so the
        // native completion callback never fires. The dispatch settles later on the UI thread,
        // which the probe observes through the Undo history instead of the callback.
        final int historyBefore = historySize();
        final ArtMeshGeometry before = geometryOf(id);
        final String beforeDigest = AutoMeshGeometryDigest.of(before);
        final ArtMeshGeometry settled = awaitCustomSettled(id, beforeDigest, historyBefore);
        result.put("funnel.completion", "custom-settled");
        return Optional.of(settled);
    }

    /**
     * Waits until a custom run either wrote its geometry or proved it wrote nothing.
     *
     * <p>A dispatch settles on the Editor UI thread after the compute lane returns. A write adds one
     * Undo entry, and a refusal adds none, so the wait ends on the first history change or on the
     * settle budget. The geometry is sampled after the history settles, because the transaction
     * commits the geometry and the Undo entry together.</p>
     *
     * @param id the ArtMesh the run targets
     * @param beforeDigest the geometry digest before the run
     * @param historyBefore the Undo entry count before the run
     * @return the geometry after the settle window
     * @throws Exception when the host refuses a read
     */
    private ArtMeshGeometry awaitCustomSettled(final String id, final String beforeDigest, final int historyBefore)
            throws Exception {
        final long deadline = System.nanoTime() + customSettleTimeout().toNanos();
        ArtMeshGeometry last = geometryOf(id);
        while (System.nanoTime() < deadline) {
            final boolean historyChanged = historySize() != historyBefore;
            final boolean geometryChanged = !AutoMeshGeometryDigest.of(last).equals(beforeDigest);
            if (historyChanged || geometryChanged) {
                return last;
            }
            Thread.sleep(POLL_MILLIS);
            last = geometryOf(id);
        }
        result.put("funnel.settle", "timeout");
        return last;
    }

    private Duration customSettleTimeout() {
        return AutoMeshProbeBudget.read(CUSTOM_SETTLE_TIMEOUT_PROPERTY, DEFAULT_CUSTOM_SETTLE_TIMEOUT);
    }

    private static List<String> splitProperty(final String name, final String fallback) {
        final String raw = System.getProperty(name, fallback);
        final Set<String> values = new LinkedHashSet<>();
        for (final String token : raw.split(",")) {
            final String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return List.copyOf(values);
    }

    private Duration stepTimeout() {
        return AutoMeshProbeBudget.read(STEP_TIMEOUT_PROPERTY, DEFAULT_STEP_TIMEOUT);
    }

    private static List<String> presetNames(final AutoMeshAlgorithm algorithm) {
        final List<String> names = new ArrayList<>(algorithm.presets().size());
        for (final AlgorithmPreset preset : algorithm.presets()) {
            names.add(preset.name());
        }
        return List.copyOf(names);
    }

    private static String sanitize(final String value) {
        return value.replaceAll(UNSAFE_PATH_CHARACTERS, "-");
    }

    private static String property(final String name, final String fallback) {
        final String value = System.getProperty(name, "");
        return value.isBlank() ? fallback : value;
    }

    private void record(
            final String name, final String expected, final String actual, final AutoMeshProbeStatus status) {
        statuses.add(status);
        result.put("assertion." + name + ".expected", expected);
        result.put("assertion." + name + ".actual", actual);
        result.put("assertion." + name + ".status", status.name());
    }

    private static AutoMeshProbeStatus statusOf(final boolean passed) {
        return passed ? AutoMeshProbeStatus.PASS : AutoMeshProbeStatus.FAIL;
    }

    private void publish(final Path dumpRoot) {
        final AutoMeshProbeStatus terminal = AutoMeshProbeResult.terminal(statuses);
        AutoMeshProbeResult.counters(statuses).forEach(result::put);
        result.put("status", terminal.name());
        final Path file = context.paths().stateDir().resolve(RESULT_FILE);
        try {
            result.writeTo(file);
            final String line =
                    terminal == AutoMeshProbeStatus.PASS ? "AUTOMESH_FRAMEWORK_RESULT status=PASS" : FAILURE_MARKER;
            context.logger().info(line + " result=" + file + " dump=" + dumpRoot);
        } catch (Exception failure) {
            context.logger().error("Auto mesh framework result could not be written", failure);
        } finally {
            if (Boolean.getBoolean(EXIT_PROPERTY)) {
                requestHostClose();
            }
        }
    }

    private void requestHostClose() {
        try {
            WindowsHistoryNativeUiHostClose.closeIfEligible(
                    Boolean.getBoolean(EXIT_PROPERTY),
                    worker == Thread.currentThread() && !Thread.currentThread().isInterrupted(),
                    true,
                    System.getProperty(RUN_ID_PROPERTY),
                    System.getProperty(HOST_VERSION_PROPERTY));
        } catch (Exception closeFailure) {
            context.logger().error("Auto mesh framework probe could not request the host close", closeFailure);
        }
    }

    private <T> T onUi(final Callable<T> call) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return call.call();
        }
        final AtomicReference<T> value = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch completed = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            try {
                value.set(call.call());
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                completed.countDown();
            }
        });
        final Duration budget = stepTimeout();
        if (!completed.await(budget.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException(
                    "the Editor UI thread did not accept the probe within " + budget.toSeconds() + " seconds");
        }
        final Throwable error = failure.get();
        if (error instanceof Exception exception) {
            throw exception;
        }
        if (error != null) {
            throw new IllegalStateException(error);
        }
        return value.get();
    }

    private static String describe(final Throwable failure) {
        final String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
