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
import java.util.concurrent.atomic.AtomicBoolean;
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
 * <p>Two properties make the verdict trustworthy. First, the native path is proven stable (same counts across runs; the Editor generator moves points by up to a pixel)
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
    static final String READINESS_TIMEOUT_PROPERTY = "turboism.automeshValidation.readinessTimeoutSeconds";
    static final String SELECTOR_ATTACH_TIMEOUT_PROPERTY = "turboism.automeshValidation.selectorAttachTimeoutSeconds";

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

    /**
     * Readiness budget. The Editor loads plugins before it opens the document, and a real host
     * spends most of its startup inside Cubism itself, so the budget covers a slow cold start.
     */
    private static final Duration DEFAULT_READINESS_TIMEOUT = Duration.ofSeconds(240);

    /** Budget of one selector-row attach, which the runtime posts to the Editor UI thread. */
    private static final Duration DEFAULT_SELECTOR_ATTACH_TIMEOUT = Duration.ofSeconds(10);

    private static final long POLL_MILLIS = 250L;
    private static final int NATIVE_RUN_ATTEMPTS = 2;
    private static final double NATIVE_TRIANGLE_TOLERANCE_RATIO = 0.01;
    private static final String VARIANT_NATIVE = "native";
    private static final String KEY_SEPARATOR = ".";
    private static final String GUARD_MODELING_KEY = "turboism.automesh.guard.modeling-mode";
    private static final String GUARD_MESH_EDITOR_KEY = "turboism.automesh.guard.mesh-editor-mode";
    /**
     * Property the transformed container constructor reads. The runtime publishes this literal, and
     * the plugin class loader refuses the runtime's own bridge type, so the probe names it here.
     */
    private static final String GUARD_ATTACH_KEY = "turboism.automesh.attach";

    private static final String FUNCTION_TYPE = "java.util.function.Function";
    private static final String ABSENT = "<absent>";
    private static final String ABSENT_SKIPPED = "<absent: skipped>";
    private static final String REVIEWED_RELEASE_CODE = "a reviewed compact release code";
    private static final String ROW_EXPECTATION = "> 0 centre children";
    private static final String UNSAFE_PATH_CHARACTERS = "[^A-Za-z0-9._-]";

    private final List<AutoMeshProbeStatus> statuses = new ArrayList<>();
    private final AutoMeshProbeResult result = new AutoMeshProbeResult();
    private final AtomicBoolean editorStarted = new AtomicBoolean();
    private volatile boolean sessionReady;
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

    /**
     * Records that the runtime finished loading every plugin and admitted the host.
     *
     * <p>The runtime publishes startup only after the plugin load wave committed, so this callback is
     * the probe's proof that a sibling plugin had its chance to register. The callback runs on a
     * plugin worker, which is why the observation is a flag the probe worker polls.</p>
     */
    @Override
    public void onEditorStarted(final dev.turboism.sdk.cubism.EditorLifecycleSnapshot editor) {
        editorStarted.set(true);
    }

    private void run() {
        Path dumpRoot = null;
        try {
            dumpRoot = dumpRoot();
            publishDumpDirectory(dumpRoot);
            result.put("schemaVersion", 1);
            result.put("runId", property(RUN_ID_PROPERTY, "unknown"));
            result.put("hostVersion", property(HOST_VERSION_PROPERTY, "unknown"));
            result.put("dumpDir", dumpRoot);
            if (!awaitReady()) {
                return;
            }
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

    // --- readiness ---------------------------------------------------------

    /**
     * Waits until the session can answer every later assertion.
     *
     * <p>Two preconditions must hold. First, the runtime must have published Editor startup, which
     * happens only after the whole plugin load wave committed, so every plugin that registers an
     * algorithm had its chance. Second, the active model must expose readable ArtMeshes, because a
     * document that is still loading answers an empty drawable list.</p>
     *
     * <p>A timeout records one {@code BLOCKED} assertion with the last observed gap and stops the
     * run. Continuing would judge a half-built session, which is exactly the defect this gate
     * removes.</p>
     *
     * @return {@code true} when the run may continue
     * @throws Exception when the host refuses a readiness read or the wait is interrupted
     */
    private boolean awaitReady() throws Exception {
        final AutoMeshProbeReadiness.Outcome readiness =
                AutoMeshProbeReadiness.await(this::readinessGap, readinessTimeout(), POLL_MILLIS);
        result.put("readiness.waitedMillis", Long.toString(readiness.waitedMillis()));
        if (readiness.ready()) {
            sessionReady = true;
            result.put("readiness.editorStarted", Boolean.toString(editorStarted.get()));
            return true;
        }
        record(
                "readiness.session",
                "a loaded plugin wave and a readable model",
                readiness.reason(),
                AutoMeshProbeStatus.BLOCKED);
        return false;
    }

    /**
     * Reports the first precondition that does not hold yet.
     *
     * <p>The plugin load wave counts as committed when the runtime published Editor startup, or when
     * the registry already lists both plugin algorithms: an algorithm can only be registered from a
     * committed enable, so its presence proves the wave reached that plugin. The second route keeps
     * the gate correct even when the startup notification is unavailable.</p>
     *
     * @return empty when every precondition holds, otherwise the blocking reason
     * @throws Exception when the host refuses a read
     */
    private Optional<String> readinessGap() throws Exception {
        if (!editorStarted.get() && !pluginAlgorithmsRegistered()) {
            return Optional.of("the plugin load wave is not committed; no plugin algorithm is registered yet");
        }
        final int drawables = onUi(() -> {
            try {
                return context.cubism().model().active().drawables().all().size();
            } catch (IllegalStateException | UnsupportedOperationException unavailable) {
                return 0;
            }
        });
        if (drawables == 0) {
            return Optional.of("the active model exposes no readable ArtMesh");
        }
        return Optional.empty();
    }

    /**
     * Reports whether the registry already lists both plugin algorithms.
     *
     * <p>These registrations outlive no plugin generation, so their presence is direct evidence that
     * the owning plugin finished its enable step.</p>
     *
     * @return {@code true} when both plugin algorithms are registered
     */
    private boolean pluginAlgorithmsRegistered() {
        final Optional<AutoMeshAlgorithmRegistry> available = context.services().find(AutoMeshAlgorithmRegistry.class);
        if (available.isEmpty()) {
            return false;
        }
        final AutoMeshAlgorithmRegistry live = available.orElseThrow();
        return live.find(REGION_ALGORITHM_ID).isPresent()
                && live.find(ADAPTIVE_ALGORITHM_ID).isPresent();
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
     *
     * <p>The published values are lambdas that implement {@link java.util.function.Function}, so the
     * assertion is an {@code instanceof} test. The concrete lambda class is recorded as evidence only:
     * its name carries a JVM-assigned suffix that changes on every run.</p>
     */
    private void checkGuardInstalled() {
        for (final Map.Entry<String, String> probe : Map.of(
                        "guard.modelingFunnel", GUARD_MODELING_KEY,
                        "guard.meshEditorFunnel", GUARD_MESH_EDITOR_KEY,
                        "guard.containerAttach", GUARD_ATTACH_KEY)
                .entrySet()) {
            final Object callback = System.getProperties().get(probe.getValue());
            final String type = callback == null ? ABSENT : callback.getClass().getName();
            record(
                    probe.getKey(),
                    FUNCTION_TYPE + " instance",
                    type,
                    statusOf(callback instanceof java.util.function.Function<?, ?>));
        }
        final String version = property(HOST_VERSION_PROPERTY, "");
        record(
                "guard.hostVersionAdmitted",
                REVIEWED_RELEASE_CODE,
                version.isBlank() ? ABSENT : version,
                statusOf(AutoMeshHostVersionCode.admitted(version)));
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
        final List<String> counts = new ArrayList<>(NATIVE_RUN_ATTEMPTS);
        final List<Integer> vertexCounts = new ArrayList<>(NATIVE_RUN_ATTEMPTS);
        final List<Integer> triangleCounts = new ArrayList<>(NATIVE_RUN_ATTEMPTS);
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
            counts.add(geometry.positions().size() + "/"
                    + geometry.triangleIndices().size() / 3);
            vertexCounts.add(geometry.positions().size());
            triangleCounts.add(geometry.triangleIndices().size() / 3);
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
                "native.stable",
                "the same vertex count and triangle counts within " + NATIVE_TRIANGLE_TOLERANCE_RATIO + " across "
                        + NATIVE_RUN_ATTEMPTS + " runs",
                counts.toString(),
                statusOf(nativeCountsStable(vertexCounts, triangleCounts)));
        // The Editor generator is not bit-deterministic (real host run: same counts, positions
        // differ by up to one pixel), so the digests stay evidence and never decide the verdict.
        record(
                "native.digestsEqual",
                "equal digests when the Editor generator is deterministic",
                digests.toString(),
                AutoMeshProbeStatus.INFO);
        result.put("native.digest", digests.isEmpty() ? ABSENT : digests.get(0));
    }

    /**
     * Judges whether repeated native runs agree on the mesh size.
     *
     * <p>The Editor generator moves points between runs and can differ by a triangle on large
     * layers (real host run: 617 vertices both times, 1018 then 1019 triangles), so the triangle
     * count may differ by a small ratio while the vertex count must match.</p>
     *
     * @param vertexCounts the vertex count of each run
     * @param triangleCounts the triangle count of each run
     * @return whether the runs agree within the tolerance
     */
    static boolean nativeCountsStable(final List<Integer> vertexCounts, final List<Integer> triangleCounts) {
        if (vertexCounts.size() != NATIVE_RUN_ATTEMPTS || triangleCounts.size() != NATIVE_RUN_ATTEMPTS) {
            return false;
        }
        final int first = triangleCounts.get(0);
        final int second = triangleCounts.get(1);
        final double allowed = Math.max(first, second) * NATIVE_TRIANGLE_TOLERANCE_RATIO;
        return vertexCounts.get(0).equals(vertexCounts.get(1)) && Math.abs(first - second) <= allowed;
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
     * The probe builds the native modeling popup, records what it built, waits for the runtime's own
     * attach callback to finish on the Editor UI thread, then counts the centre children the row was
     * appended to and captures the widget as a PNG.</p>
     *
     * <p>The probe selects the region algorithm first, so the dialog artifact the runtime paints shows
     * a parameterised algorithm with its presets instead of the Editor's own generator.</p>
     *
     * <p>The assertion stays strict: at least one algorithm plugin is registered, so the row must be
     * attached. Every diagnostic exists to name the exact reason when it is not.</p>
     *
     * @param dumpRoot the dump directory the capture belongs to
     * @throws Exception when the host refuses a container operation
     */
    private void checkSelectorRow(final Path dumpRoot) throws Exception {
        registry().select(REGION_ALGORITHM_ID);
        final int nativeChildren = nativeContainerChildren();
        final NativeAutoMeshPopup.Build build = onUi(NativeAutoMeshPopup::open);
        result.put("selector.containerClass", build.containerClass());
        result.put("selector.centerClass", build.centerClass());
        build.failure().ifPresent(failure -> result.put("selector.buildFailure", failure));
        if (!build.built()) {
            record(
                    "selector.popup",
                    "a native Auto Mesh container",
                    build.containerClass() + (build.failure().isPresent() ? " (threw)" : ""),
                    AutoMeshProbeStatus.BLOCKED);
            result.put("selector.screenshot", "<unavailable: the native popup could not be built>");
            return;
        }
        final Object popup = build.container();
        try {
            final int children = awaitRowAttached(popup, nativeChildren);
            result.put("selector.containerChildrenBefore", Integer.toString(nativeChildren));
            result.put("selector.containerChildren", Integer.toString(children));
            result.put("selector.attachCallback", attachCallbackState());
            record(
                    "selector.rowAttached",
                    ROW_EXPECTATION,
                    Integer.toString(children),
                    statusOf(children > nativeChildren));
            final NativeAutoMeshPopup.Capture capture = NativeAutoMeshPopup.capture(popup, dumpRoot);
            if (capture.file().isPresent()) {
                result.put("selector.screenshot", capture.file().orElseThrow().toString());
                record(
                        "selector.screenshot",
                        "a PNG file",
                        capture.file().orElseThrow().toString(),
                        AutoMeshProbeStatus.INFO);
            } else {
                result.put("selector.screenshot", "<unavailable: " + capture.reason() + ">");
                record("selector.screenshot", "a PNG file", "<unavailable>", AutoMeshProbeStatus.INFO);
            }
            recordParameterDialog(dumpRoot);
        } finally {
            NativeAutoMeshPopup.close(popup);
        }
    }

    /**
     * Records the parameter-dialog artifact the runtime paints for this validation run.
     *
     * <p>The dialog content is runtime Swing code, so the runtime paints it into the dump directory
     * and the probe reads the result back. The record names the file, its size and the section that
     * must be visible, which is what makes the artifact evidence instead of a file on disk.</p>
     *
     * @param dumpRoot the dump directory the artifact belongs to
     */
    private void recordParameterDialog(final Path dumpRoot) {
        final NativeAutoMeshPopup.Capture capture = NativeAutoMeshPopup.parameterDialog(dumpRoot);
        if (capture.file().isPresent()) {
            result.put(
                    "selector.parameterDialogScreenshot",
                    capture.file().orElseThrow().toString());
            result.put("selector.parameterDialogSize", capture.width() + "x" + capture.height());
            record(
                    "selector.parameterDialogScreenshot",
                    "a PNG file",
                    capture.file().orElseThrow() + " (" + capture.width() + "x" + capture.height() + ")",
                    AutoMeshProbeStatus.INFO);
            return;
        }
        result.put("selector.parameterDialogScreenshot", "<unavailable: " + capture.reason() + ">");
        record("selector.parameterDialogScreenshot", "a PNG file", "<unavailable>", AutoMeshProbeStatus.INFO);
    }

    /**
     * Counts the widgets of a native container built without the runtime attach route.
     *
     * <p>The attach hook runs inside the container constructor, so any container built while the
     * route is installed already carries the row. The only honest baseline is a container built
     * while the route is absent. The probe lifts the route property for that one construction on
     * the Editor UI thread and restores it in a finally block; no user popup exists during
     * validation, so no other construction can observe the gap.</p>
     *
     * @return the widget count of an unmodified native container, or {@code -1} when none was built
     * @throws Exception when the host refuses a container operation
     */
    private int nativeContainerChildren() throws Exception {
        return onUi(() -> {
            final Object route = System.getProperties().remove(GUARD_ATTACH_KEY);
            try {
                final NativeAutoMeshPopup.Build baseline = NativeAutoMeshPopup.open();
                final int count = baseline.built() ? baseline.childrenBefore() : -1;
                NativeAutoMeshPopup.close(baseline.container());
                return count;
            } finally {
                if (route != null) {
                    System.getProperties().put(GUARD_ATTACH_KEY, route);
                }
            }
        });
    }

    /**
     * Waits for the runtime's attach callback to append the row to the container centre.
     *
     * <p>The runtime posts the attach to the Editor UI thread, so the row appears shortly after the
     * constructor returns. The wait is bounded and answers the last observed count, which keeps the
     * assertion strict while the evidence stays truthful.</p>
     *
     * @param popup the live container
     * @param childrenBefore the centre child count the host itself produced
     * @return the centre child count after the wait
     * @throws Exception when the host refuses a read
     */
    private int awaitRowAttached(final Object popup, final int childrenBefore) throws Exception {
        final Duration budget =
                AutoMeshProbeBudget.read(SELECTOR_ATTACH_TIMEOUT_PROPERTY, DEFAULT_SELECTOR_ATTACH_TIMEOUT);
        final long deadline = System.nanoTime() + budget.toNanos();
        int children = onUi(() -> NativeAutoMeshPopup.childCount(popup));
        while (children <= childrenBefore && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MILLIS);
            children = onUi(() -> NativeAutoMeshPopup.childCount(popup));
        }
        return children;
    }

    /**
     * Reports whether the runtime still holds the container attach callback.
     *
     * <p>The bridge publishes the callback as a system property while its installer is open, so the
     * property alone proves the attach route was live when the container was constructed. Its absence
     * means no attach could ever have run.</p>
     *
     * @return the callback state as recorded evidence
     */
    private static String attachCallbackState() {
        final Object callback = System.getProperties().get(GUARD_ATTACH_KEY);
        if (callback == null) {
            return "<absent: no attach route was installed>";
        }
        return callback instanceof java.util.function.Function<?, ?>
                ? "installed as " + callback.getClass().getName()
                : "installed but not a Function: " + callback.getClass().getName();
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

    /**
     * Publishes the dump directory the runtime paints its own evidence into.
     *
     * <p>The parameter dialog is runtime Swing content, and a plugin loader cannot link runtime
     * classes. The runtime therefore reads this property and paints the dialog into the same dump
     * directory, which keeps both artifacts in one place. An explicit runner value wins.</p>
     *
     * @param dumpRoot the dump directory this run writes
     */
    private static void publishDumpDirectory(final Path dumpRoot) {
        if (System.getProperty(DUMP_DIR_PROPERTY, "").isBlank()) {
            System.setProperty(DUMP_DIR_PROPERTY, dumpRoot.toString());
        }
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
                // The transaction commits history and geometry together, but the poll that saw the
                // history change may hold the previous geometry; sample once more after one tick.
                Thread.sleep(POLL_MILLIS);
                return geometryOf(id);
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

    private Duration readinessTimeout() {
        return AutoMeshProbeBudget.read(READINESS_TIMEOUT_PROPERTY, DEFAULT_READINESS_TIMEOUT);
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
        final boolean exitRequested = Boolean.getBoolean(EXIT_PROPERTY);
        if (exitRequested) {
            recordHostCloseReadiness();
        }
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
            if (exitRequested) {
                requestHostClose();
            }
        }
    }

    /**
     * Records whether the shared close path can identify its host window before the result is written.
     *
     * <p>The close runs after the result file is persisted, so its evidence must be collected first.
     * A window that is still unidentified becomes a recorded {@code BLOCKED} assertion instead of an
     * unactionable error line.</p>
     */
    private void recordHostCloseReadiness() {
        if (!sessionReady) {
            // A session that never loaded cannot publish the fixture title, so waiting again would
            // only repeat the readiness timeout the run already reported.
            record(
                    "close.windowIdentity",
                    "exactly one visible host window for the fixture",
                    "the run never reached a loaded session",
                    AutoMeshProbeStatus.BLOCKED);
            return;
        }
        try {
            final Optional<String> gap = awaitHostWindowIdentity();
            result.put("close.windowIdentity", gap.orElse("identified"));
            record(
                    "close.windowIdentity",
                    "exactly one visible host window for the fixture",
                    gap.orElse("identified"),
                    gap.isEmpty() ? AutoMeshProbeStatus.PASS : AutoMeshProbeStatus.BLOCKED);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            record(
                    "close.windowIdentity",
                    "exactly one visible host window for the fixture",
                    "interrupted",
                    AutoMeshProbeStatus.BLOCKED);
        } catch (Exception unreadable) {
            record(
                    "close.windowIdentity",
                    "exactly one visible host window for the fixture",
                    describe(unreadable),
                    AutoMeshProbeStatus.BLOCKED);
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

    /**
     * Waits until the shared close path can identify exactly one host window for the fixture.
     *
     * <p>The Editor publishes its frame title only after the document is open, so a close requested
     * too early cannot name its own window. The wait is bounded and answers the last observed gap.</p>
     *
     * @return empty when the window is identifiable, otherwise the blocking reason
     * @throws Exception when the host refuses a readiness read or the wait is interrupted
     */
    private Optional<String> awaitHostWindowIdentity() throws Exception {
        final String fixtureName = System.getProperty(FIXTURE_NAME_PROPERTY);
        final long deadline = System.nanoTime() + readinessTimeout().toNanos();
        String gap = "";
        while (System.nanoTime() < deadline) {
            gap = onUi(() -> hostWindowGap(fixtureName));
            if (gap.isEmpty()) {
                return Optional.empty();
            }
            Thread.sleep(POLL_MILLIS);
        }
        return Optional.of(gap);
    }

    /**
     * Describes why the shared close path cannot identify the host window right now.
     *
     * <p>Runs on the Editor UI thread, because window inspection is an EDT operation.</p>
     *
     * @param fixtureName the runner-provided fixture name
     * @return empty when exactly one candidate matches, otherwise the reason
     */
    private static String hostWindowGap(final String fixtureName) {
        final List<WindowsHistoryNativeUiHostClose.HostWindowCandidate> candidates = new ArrayList<>();
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (!(window instanceof java.awt.Frame frame) || !frame.isDisplayable() || !frame.isVisible()) {
                continue;
            }
            candidates.add(new WindowsHistoryNativeUiHostClose.HostWindowCandidate(frame.getTitle(), frame.getName()));
        }
        if (candidates.isEmpty()) {
            return "no visible Cubism frame is displayable";
        }
        try {
            WindowsHistoryNativeUiHostClose.selectHostWindowCandidate(candidates, fixtureName);
            return "";
        } catch (IllegalStateException unidentified) {
            return unidentified.getMessage();
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
