package dev.turboism.validation.parameterbatchtransfer;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterBinding;
import dev.turboism.sdk.cubism.model.ParameterBindingFamily;
import dev.turboism.sdk.cubism.model.ParameterBindingPoint;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.cubism.model.ParameterBindingTransferPlan;
import dev.turboism.sdk.cubism.model.ParameterDefinition;
import dev.turboism.sdk.cubism.model.ParameterType;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.TurboismPlugin;

import javax.swing.SwingUtilities;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Task-local exerciser for the parameter-batch-transfer plugin's exact-host path.
 *
 * <p>Re-executes the same public SDK read path the plugin uses for its dialog
 * session (bound parameter snapshots with M/C markers, model-wide candidates,
 * per-row same-category target filtering) and the same write call the plugin's
 * apply step uses {@code model.parameterBindingBatch().transferMorphClamped(...)} for morph rows and {@code transferClamped(...)} for keyform rows, then
 * verifies native Undo/Redo restore through the editor's Ctrl+Z/Ctrl+Y path.
 * The probe is validation tooling only and is never part of the production
 * preview bundle or product build.</p>
 */
public final class ParameterBatchTransferHostValidationPlugin implements TurboismPlugin {

    private static final String FLAG = "exerciser.flag";
    private static final long FLAG_TIMEOUT_MILLIS = 240_000L;
    // Post-flag model-await budget: the fixture document takes ~2.5 min to
    // become a modeling document on the exact host.
    private static final long MODEL_AWAIT_MAX_MILLIS = 240_000L;
    private static final long VALUE_AWAIT_MAX_MILLIS = 30_000L;
    private static final long SHORTCUT_AWAIT_MAX_MILLIS = 2_000L;
    private static final float DUPLICATE_EPSILON = 0.000001F;
    private static final List<Float> SCAFFOLD_POINTS = List.of(-30.0F, 0.0F, 30.0F);
    private static final long EDT_TIMEOUT_MILLIS = 30_000L;
    private static final String SESSION_DRAWABLE_CLASS =
        "dev.turboism.adapter.host.DynamicCubismModelAccess$SessionDrawable";
    private static final String PERMISSION_CHECKED_DRAWABLE_CLASS =
        "dev.turboism.adapter.cubism.CubismFacadeImpl$PermissionCheckedDrawable";
    private static final String EDITOR_DRAWABLE_CLASS =
        "dev.turboism.adapter.cubism.editor.EditorObjectReadAccess$EditorDrawable";
    private static final int MAX_DRAWABLE_UNWRAP_DEPTH = 3;
    private static final String RAW_ART_MESH_SOURCE =
        "com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshSource";
    private static final String RAW_ART_MESH_FORM =
        "com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshForm";
    private static final String RAW_MODEL_SOURCE = "com.live2d.cubism.doc.model.CModelSource";
    private static final String RAW_PARAMETER_SOURCE = "com.live2d.cubism.doc.model.param.CParameterSource";
    private static final String RAW_PARAMETER_ID = "com.live2d.cubism.doc.model.id.CParameterId";
    private static final String RAW_PARAMETER_GUID = "com.live2d.type.CParameterGuid";
    private static final String RAW_FORM_GUID = "com.live2d.type.CFormGuid";
    private static final String RAW_MORPH_TARGET_SET =
        "com.live2d.cubism.doc.model.morphTarget.KeyFormMorphTargetSet";
    private static final String RAW_MORPH_TARGET =
        "com.live2d.cubism.doc.model.morphTarget.KeyFormMorphTarget";
    private static final String RAW_AC_FORM = "com.live2d.cubism.doc.model.ACForm";
    private static final String RAW_GRID_SOURCE =
        "com.live2d.cubism.doc.model.interpolator.KeyformGridSource";
    private static final String RAW_GRID_ENTRY =
        "com.live2d.cubism.doc.model.interpolator.KeyformOnGrid";
    private static final String RAW_GRID_KEY =
        "com.live2d.cubism.doc.model.interpolator.KeyformGridAccessKey";
    private static final String RAW_KEY_ON_PARAMETER =
        "com.live2d.cubism.doc.model.interpolator.KeyOnParameter";

    private PluginLogger logger;
    private PluginContext context;
    private Path stateDir;
    private final List<Assertion> assertions = new ArrayList<>();

    private String modelId = "unknown";
    private String ownerType = "none";
    private String ownerId = "none";
    private int boundCount = 0;
    private String sourceParameter = "none";
    private String targetParameter = "none";

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        this.stateDir = context.paths().stateDir();
        final Thread exerciser = new Thread(this::runWhenFlagged, "parameter-batch-transfer-host-exerciser");
        exerciser.setDaemon(true);
        exerciser.start();
        logger.info("PBT_PROBE_READY stateDir=" + stateDir);
    }

    @Override
    public void enable() {
        logger.info("PBT_PROBE_ENABLED");
    }

    @Override
    public void disable() {
        logger.info("PBT_PROBE_DISABLED");
    }

    @Override
    public void shutdown() {
        logger.info("PBT_PROBE_SHUTDOWN");
    }

    private void runWhenFlagged() {
        final Path flag = stateDir.resolve(FLAG);
        final long deadline = System.currentTimeMillis() + FLAG_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(flag)) {
                runMatrix();
                return;
            }
            try {
                Thread.sleep(2_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        logger.warn("PBT_PROBE_FLAG_TIMEOUT flag=" + flag);
        Runtime.getRuntime().halt(2);
    }

    private void runMatrix() {
        final long startedNanos = System.nanoTime();
        try {
            final CubismModel model = awaitActiveModel();
            modelId = onHostThread(() -> model.id() != null ? model.id().value() : "null");
            logger.info("PBT_MODEL_READY modelId=" + modelId);
            runSessionMatrix(model);
            runApplyMatrix(model);
        } catch (Exception failure) {
            recordAssertion("matrix.unexpectedFailure", "no exception", singleLine(failure), "FAIL");
            logger.error("PBT_MATRIX_FAILED " + singleLine(failure), failure);
        }
        final String terminal = computeTerminal();
        writeResultFile(startedNanos);
        logger.info("PBT_MATRIX_RESULT status=" + terminal
            + " ownerType=" + ownerType
            + " ownerId=" + ownerId
            + " bound=" + boundCount
            + " source=" + sourceParameter
            + " target=" + targetParameter
            + " assertions=" + assertions.size()
            + " durationMillis=" + ((System.nanoTime() - startedNanos) / 1_000_000L));
        try {
            Thread.sleep(3_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        Runtime.getRuntime().exit(0);
    }

    // ------------------------------------------------------------------
    // Session matrix (mirrors ParameterBatchTransferService.sessionFor)
    // ------------------------------------------------------------------

    private void runSessionMatrix(final CubismModel model) throws Exception {
        final Owner owner = discoverOwner(model);
        if (owner == null) {
            recordAssertion(
                "owner.discovery",
                "an object with at least one parameter binding",
                "none found (deformers and ArtMeshes scanned)",
                "BLOCKED"
            );
            return;
        }
        ownerType = owner.target().type().name();
        ownerId = owner.target().id();
        boundCount = owner.bound().size();
        recordAssertion("owner.discovery", "object with bound parameters", ownerType + ":" + ownerId, "PASS");
        recordAssertion("bound.count", "> 0", String.valueOf(boundCount), boundCount > 0 ? "PASS" : "FAIL");

        final List<BoundSnapshot> bound = owner.bound();
        final List<BoundSnapshot> candidates = model.parameters().all().stream()
            .map(parameter -> BoundSnapshot.of(parameter, null))
            .toList();
        recordAssertion("candidates.count", "> 0", String.valueOf(candidates.size()),
            candidates.isEmpty() ? "FAIL" : "PASS");

        boolean markersPass = true;
        boolean labelsPass = true;
        for (final BoundSnapshot snapshot : bound) {
            final StringBuilder expectedMarkers = new StringBuilder();
            if (snapshot.parameter().type() == ParameterType.BLEND_SHAPE) {
                expectedMarkers.append('M');
            }
            if (snapshot.parameter().combined().orElse(false)) {
                expectedMarkers.append('C');
            }
            if (!expectedMarkers.toString().equals(snapshot.markers())) {
                markersPass = false;
                recordAssertion("markers." + snapshot.parameterId().value(),
                    expectedMarkers.toString(), snapshot.markers(), "FAIL");
            }
            final String expectedLabel = snapshot.parameter().name()
                .filter(value -> !value.isBlank())
                .orElse(snapshot.parameterId().value());
            if (!expectedLabel.equals(snapshot.label())) {
                labelsPass = false;
                recordAssertion("label." + snapshot.parameterId().value(),
                    expectedLabel, snapshot.label(), "FAIL");
            }
        }
        recordAssertion("markers.match", "all bound M/C markers match SDK types",
            markersPass ? "match" : "mismatch", markersPass ? "PASS" : "FAIL");
        recordAssertion("label.match", "all bound labels are name or id",
            labelsPass ? "match" : "mismatch", labelsPass ? "PASS" : "FAIL");

        final BoundSnapshot source = bound.get(0);
        final List<BoundSnapshot> targets = targetCandidates(candidates, bound, source);
        recordAssertion("targetCandidates.containsSource",
            "source itself is offered as default target",
            targets.stream().anyMatch(value -> value.parameterId().equals(source.parameterId())) ? "yes" : "no",
            targets.stream().anyMatch(value -> value.parameterId().equals(source.parameterId())) ? "PASS" : "FAIL");
        final boolean categoryFilterPass = targets.stream().allMatch(value ->
            value.morph() == source.morph() && value.combined() == source.combined());
        recordAssertion("targetCandidates.categoryFilter",
            "all candidates share source morph/combined category",
            categoryFilterPass ? "match" : "mismatch", categoryFilterPass ? "PASS" : "FAIL");
        final Set<ParameterId> boundElsewhere = bound.stream()
            .map(BoundSnapshot::parameterId)
            .filter(id -> !id.equals(source.parameterId()))
            .collect(Collectors.toSet());
        final boolean exclusionPass = targets.stream()
            .noneMatch(value -> boundElsewhere.contains(value.parameterId()));
        recordAssertion("targetCandidates.excludesAlreadyBound",
            "no already-bound-other parameter offered", exclusionPass ? "clean" : "contaminated",
            exclusionPass ? "PASS" : "FAIL");
        final boolean sortedPass = isSortedByLowerLabel(targets);
        recordAssertion("targetCandidates.sorted", "sorted by lowercase label",
            sortedPass ? "sorted" : "unsorted", sortedPass ? "PASS" : "FAIL");

        runClassificationMatrix(candidates, bound);
    }

    /**
     * Morph/combined classification and filter matrix over the model-wide
     * candidate list (mirrors legacy isMorphTarget/isCombined semantics).
     *
     * <p>Records the SDK-reported type/combined/markers for every parameter and
     * asserts that a morph (or combined) source row is offered only same-category
     * targets. When the model has no morph/combined parameter the related
     * assertions are recorded as SKIPPED with the scan evidence.</p>
     */
    private void runClassificationMatrix(
        final List<BoundSnapshot> candidates,
        final List<BoundSnapshot> bound
    ) {
        final StringBuilder scan = new StringBuilder();
        int morphCount = 0;
        int combinedCount = 0;
        for (final BoundSnapshot candidate : candidates) {
            scan.append(candidate.parameterId().value())
                .append(":type=").append(candidate.parameter().type())
                .append(",combined=").append(candidate.combined())
                .append(",markers=").append(candidate.markers().isEmpty() ? "-" : candidate.markers())
                .append('\n');
            if (candidate.morph()) {
                morphCount++;
            }
            if (candidate.combined()) {
                combinedCount++;
            }
        }
        recordAssertion("classify.scan", "per-parameter type/combined/markers evidence",
            scan.toString(), "PASS");

        boolean markerConsistency = true;
        for (final BoundSnapshot candidate : candidates) {
            final boolean expectsM = candidate.morph();
            final boolean expectsC = candidate.combined();
            final boolean hasM = candidate.markers().contains("M");
            final boolean hasC = candidate.markers().contains("C");
            if (expectsM != hasM || expectsC != hasC) {
                markerConsistency = false;
                recordAssertion("classify.markers." + candidate.parameterId().value(),
                    "M=" + expectsM + " C=" + expectsC, candidate.markers(), "FAIL");
            }
        }
        recordAssertion("classify.markers.consistent",
            "markers match type()/combined() for every parameter",
            markerConsistency ? "consistent" : "inconsistent",
            markerConsistency ? "PASS" : "FAIL");

        final BoundSnapshot morphSource = candidates.stream()
            .filter(BoundSnapshot::morph).findFirst().orElse(null);
        if (morphSource == null) {
            recordAssertion("classify.morph.filter",
                "morph source row offers only morph targets",
                "skipped: no morph parameter in model (morphCount=" + morphCount + ")",
                "SKIPPED");
        } else {
            final List<BoundSnapshot> morphTargets = targetCandidates(candidates, bound, morphSource);
            final boolean onlyMorph = morphTargets.stream().allMatch(BoundSnapshot::morph);
            recordAssertion("classify.morph.filter",
                "morph source row offers only morph targets",
                "morphCount=" + morphCount + " offered=" + morphTargets.size()
                    + " nonMorphOffered=" + morphTargets.stream().filter(value -> !value.morph()).count(),
                onlyMorph ? "PASS" : "FAIL");
        }

        final BoundSnapshot combinedSource = candidates.stream()
            .filter(BoundSnapshot::combined).findFirst().orElse(null);
        if (combinedSource == null) {
            recordAssertion("classify.combined.filter",
                "combined source row offers no morph targets",
                "skipped: no combined parameter in model (combinedCount=" + combinedCount + ")",
                "SKIPPED");
        } else {
            final List<BoundSnapshot> combinedTargets = targetCandidates(candidates, bound, combinedSource);
            final boolean noMorph = combinedTargets.stream().noneMatch(BoundSnapshot::morph);
            recordAssertion("classify.combined.filter",
                "combined source row offers no morph targets (normal and combined allowed)",
                "combinedCount=" + combinedCount + " offered=" + combinedTargets.size()
                    + " morphOffered=" + combinedTargets.stream().filter(BoundSnapshot::morph).count(),
                noMorph ? "PASS" : "FAIL");
        }

        final BoundSnapshot normalSource = candidates.stream()
            .filter(value -> !value.morph() && !value.combined()).findFirst().orElse(null);
        if (normalSource == null) {
            recordAssertion("classify.normal.filter",
                "normal source row offers no morph targets",
                "skipped: no normal parameter in model",
                "SKIPPED");
        } else {
            final List<BoundSnapshot> normalTargets = targetCandidates(candidates, bound, normalSource);
            final boolean noMorph = normalTargets.stream().noneMatch(BoundSnapshot::morph);
            recordAssertion("classify.normal.filter",
                "normal source row offers no morph targets (normal and combined allowed)",
                "offered=" + normalTargets.size()
                    + " morphOffered=" + normalTargets.stream().filter(BoundSnapshot::morph).count()
                    + " combinedOffered=" + normalTargets.stream().filter(BoundSnapshot::combined).count(),
                noMorph ? "PASS" : "FAIL");
        }
    }

    // ------------------------------------------------------------------
    // Apply matrix (mirrors ParameterBatchTransferService.apply)
    // ------------------------------------------------------------------

    private void runApplyMatrix(final CubismModel model) throws Exception {
        MorphFixtureSetup morphFixture = MorphFixtureSetup.none();
        try {
            morphFixture = ensureMorphFixture(model);
            if (morphFixture.owner() == null) {
                recordAssertion(
                    "apply.morph.transfer",
                    "fallback or natural drawable BLEND_SHAPE binding with at least 3 points",
                    "no usable multi-point morph fixture was prepared",
                    "BLOCKED"
                );
            } else {
                runMorphApplyMatrix(model, morphFixture.owner());
            }
        } catch (Exception failure) {
            recordAssertion("apply.morph.transfer", "multi-point morph fixture and rows complete", singleLine(failure), "BLOCKED");
            logger.warn("PBT_MORPH_FIXTURE_FAILED " + singleLine(failure));
        } finally {
            cleanupMorphFixture(model, morphFixture);
        }

        // transferClamped is the keyform path; keep it separate from the morph path.
        final Owner owner = discoverOwner(model);
        if (owner == null) {
            recordAssertion("apply.skipped", "owner discovered by session matrix", "no owner", "BLOCKED");
            return;
        }

        final List<BoundSnapshot> candidates = onHostThread(() -> model.parameters().all().stream()
            .map(parameter -> BoundSnapshot.of(parameter, null))
            .toList());

        // transferClamped is the keyform path; do not fake a morph-to-morph write.
        final BoundSnapshot source = owner.bound().stream()
            .filter(value -> !value.morph())
            .findFirst()
            .orElse(null);
        if (source == null) {
            recordAssertion(
                "apply.source",
                "a non-morph bound source for transferClamped",
                "SKIPPED: fixture has no non-morph source",
                "SKIPPED"
            );
            return;
        }

        final ParameterBindingTarget ownerTarget = owner.target();
        sourceParameter = source.parameterId().value();
        final List<BoundSnapshot> targets = targetCandidates(candidates, owner.bound(), source);
        final List<Float> originalPoints = bindingValues(model, source.parameterId(), ownerTarget);
        if (originalPoints.isEmpty()) {
            recordAssertion(
                "apply.source",
                "non-empty source binding points",
                "no source points",
                "BLOCKED"
            );
            return;
        }

        final List<RangeCandidate> rangeCandidates = rangeCandidates(
            model, ownerTarget, targets, source.parameterId()
        );
        final RangeCandidate rangeContained = rangeCandidates.stream()
            .filter(candidate -> fitsInRange(originalPoints, false, candidate.range()))
            .filter(candidate -> fitsInRange(originalPoints, true, candidate.range()))
            .filter(candidate -> isUnique(mapPoints(originalPoints, false, candidate.range())))
            .filter(candidate -> isUnique(mapPoints(originalPoints, true, candidate.range())))
            .findFirst()
            .orElse(null);
        final Set<ParameterId> usedTargets = new java.util.HashSet<>();
        Robot robot = null;

        if (rangeContained != null) {
            targetParameter = rangeContained.snapshot().parameterId().value();
            usedTargets.add(rangeContained.snapshot().parameterId());
            robot = new Robot();
            transferRow(
                model, ownerTarget, source.parameterId(), rangeContained.snapshot().parameterId(),
                false, "row.plain", robot
            );
            transferRow(
                model, ownerTarget, source.parameterId(), rangeContained.snapshot().parameterId(),
                true, "row.invert", robot
            );
        } else {
            recordAssertion(
                "apply.destination",
                "a same-category range-contained target besides source",
                rangeCandidates.isEmpty()
                    ? "none available"
                    : "none contains source and inverted points without collision",
                rangeCandidates.isEmpty() ? "BLOCKED" : "SKIPPED"
            );
        }

        final MappingCandidate narrow = findNarrowCandidate(
            originalPoints, rangeCandidates, usedTargets, false
        );
        if (narrow == null) {
            recordAssertion(
                "row.clamped.plain",
                "same-category unbound candidate with unique changed clamp",
                "SKIPPED: no suitable narrow candidate",
                "SKIPPED"
            );
        } else {
            if ("none".equals(targetParameter)) {
                targetParameter = narrow.candidate().snapshot().parameterId().value();
            }
            if (robot == null) robot = new Robot();
            transferRow(
                model, ownerTarget, source.parameterId(), narrow.candidate().snapshot().parameterId(),
                false, "row.clamped.plain", robot
            );
            usedTargets.add(narrow.candidate().snapshot().parameterId());
        }

        if (rangeContained == null) {
            final MappingCandidate inverted = findNarrowCandidate(
                originalPoints, rangeCandidates, usedTargets, true
            );
            if (inverted == null) {
                recordAssertion(
                    "row.invert",
                    "same-category unbound candidate with unique changed negate-then-clamp mapping",
                    "SKIPPED: no suitable inverted candidate",
                    "SKIPPED"
                );
            } else {
                if ("none".equals(targetParameter)) {
                    targetParameter = inverted.candidate().snapshot().parameterId().value();
                }
                if (robot == null) robot = new Robot();
                transferRow(
                    model, ownerTarget, source.parameterId(), inverted.candidate().snapshot().parameterId(),
                    true, "row.invert", robot
                );
                usedTargets.add(inverted.candidate().snapshot().parameterId());
            }
        }

        final MappingCandidate collision = findCollisionCandidate(
            originalPoints, rangeCandidates, usedTargets
        );
        if (collision == null) {
            recordAssertion(
                "row.clamped.duplicate",
                "same-category unbound candidate with near-duplicate mapped points",
                "SKIPPED: no collision candidate",
                "SKIPPED"
            );
        } else {
            assertDuplicateFailsBeforeMutation(
                model, ownerTarget, source.parameterId(), collision
            );
        }
    }

    /** Exercises the public whole-binding morph transfer with a real multi-point binding. */
    private void runMorphApplyMatrix(final CubismModel model, final MorphOwner owner) throws Exception {
        final ParameterBindingTarget target = owner.target();
        final ParameterId source = owner.source();
        sourceParameter = source.value();
        ownerType = target.type().name();
        ownerId = target.id();

        final ParameterBinding sourceBinding = morphBinding(model, source, target);
        final List<Float> sourcePoints = sourceBinding == null
            ? List.of()
            : bindingValues(sourceBinding);
        final String sourceActual = sourceBinding == null
            ? "binding=null"
            : bindingEvidence(sourceBinding);
        final boolean sourceShape = onHostThread(() -> findParameter(model, source).type() == ParameterType.BLEND_SHAPE);
        final boolean sourceFinite = allFinite(sourcePoints);
        final boolean sourceHasThreePoints = sourcePoints.size() >= 3;
        final boolean sourceUnique = isUnique(sourcePoints);
        final boolean sourceReady = sourceBinding != null
            && sourceBinding.family() == ParameterBindingFamily.BLEND_SHAPE
            && sourceBinding.target().equals(target)
            && sourceShape
            && sourceFinite
            && sourceHasThreePoints
            && sourceUnique;
        recordAssertion(
            "apply.morph.source",
            "family=BLEND_SHAPE target=" + target + " points>=3 finite unique",
            sourceActual + " typeIsBlendShape=" + sourceShape,
            sourceReady ? "PASS" : sourceBinding == null || !sourceHasThreePoints ? "BLOCKED" : "FAIL"
        );
        if (!sourceReady) return;

        final List<BoundSnapshot> candidates = onHostThread(() -> model.parameters().all().stream()
            .map(parameter -> BoundSnapshot.of(parameter, null))
            .toList());
        final List<RangeCandidate> morphCandidates = morphRangeCandidates(model, target, candidates, source);
        final Set<ParameterId> usedTargets = new java.util.HashSet<>();
        final MappingCandidate wide = findWideMorphCandidate(sourcePoints, morphCandidates, usedTargets);
        if (wide == null) {
            recordAssertion(
                "apply.morph.wide.preflight",
                "free morph destination whose range preserves every source point",
                "none; candidates=" + rangeEvidence(morphCandidates),
                "BLOCKED"
            );
            recordAssertion(
                "apply.morph.transfer",
                "wide-preserve and narrow-clamp rows preflight before any edit",
                "wide destination unavailable",
                "BLOCKED"
            );
            return;
        }
        usedTargets.add(wide.candidate().snapshot().parameterId());
        recordAssertion(
            "apply.morph.wide.preflight",
            "free morph destination range preserves all source points",
            mappingEvidence(wide),
            "PASS"
        );

        final MappingCandidate narrow = findNarrowMorphCandidate(sourcePoints, morphCandidates, usedTargets);
        if (narrow == null) {
            recordAssertion(
                "apply.morph.narrow.preflight",
                "different free morph destination with unique changed per-point clamp",
                "none; candidates=" + rangeEvidence(morphCandidates),
                "BLOCKED"
            );
            recordAssertion(
                "apply.morph.transfer",
                "wide-preserve and narrow-clamp rows preflight before any edit",
                "narrow destination unavailable after wide destination reservation",
                "BLOCKED"
            );
            return;
        }
        recordAssertion(
            "apply.morph.narrow.preflight",
            "different free morph destination has unique changed mapping",
            mappingEvidence(narrow),
            "PASS"
        );

        final MappingCandidate inverted = findInvertMorphCandidate(sourcePoints, morphCandidates);
        if (inverted == null) {
            recordAssertion(
                "apply.morph.invert.preflight",
                "free morph destination with unique negate-then-clamp mapping for every point",
                "none; candidates=" + rangeEvidence(morphCandidates),
                "BLOCKED"
            );
            recordAssertion(
                "apply.morph.transfer",
                "wide-preserve, narrow-clamp, and inverted morph rows preflight before any edit",
                "inversion destination unavailable",
                "BLOCKED"
            );
            return;
        }
        recordAssertion(
            "apply.morph.invert.preflight",
            "free morph destination has unique negate-then-clamp mapping",
            mappingEvidence(inverted),
            "PASS"
        );
        recordAssertion(
            "apply.morph.preflight",
            "source, destinations, finite values, and mapped coordinates verified before edit",
            "sourcePoints=" + sourcePoints + " wide=" + mappingEvidence(wide)
                + " narrow=" + mappingEvidence(narrow) + " inverted=" + mappingEvidence(inverted),
            "PASS"
        );
        targetParameter = wide.candidate().snapshot().parameterId().value();

        final Robot robot;
        try {
            robot = new Robot();
        } catch (Exception failure) {
            recordAssertion("apply.morph.robot", "host input available", singleLine(failure), "FAIL");
            return;
        }
        final MorphRowPlan widePlan = new MorphRowPlan(
            "apply.morph.wide", target, source,
            wide.candidate().snapshot().parameterId(), false,
            sourcePoints, wide.mapped(), wide.candidate().range()
        );
        final MorphRowPlan narrowPlan = new MorphRowPlan(
            "apply.morph.narrow", target, source,
            narrow.candidate().snapshot().parameterId(), false,
            sourcePoints, narrow.mapped(), narrow.candidate().range()
        );
        final MorphRowPlan invertedPlan = new MorphRowPlan(
            "apply.morph.invert", target, source,
            inverted.candidate().snapshot().parameterId(), true,
            sourcePoints, inverted.mapped(), inverted.candidate().range()
        );
        try {
            transferMorphRow(model, widePlan, robot);
            transferMorphRow(model, narrowPlan, robot);
            transferMorphRow(model, invertedPlan, robot);
        } catch (Exception failure) {
            recordAssertion("apply.morph.exception", "three atomic morph rows complete", singleLine(failure), "FAIL");
            logger.error("PBT_MORPH_TRANSFER_FAILED " + singleLine(failure), failure);
        }
    }

    private void transferMorphRow(
        final CubismModel model,
        final MorphRowPlan plan,
        final Robot robot
    ) throws Exception {
        final ParameterBinding sourceBefore = morphBinding(model, plan.source(), plan.target());
        final List<ParameterBinding> destinationBefore = bindingsForTarget(model, plan.destination(), plan.target());
        final boolean precondition = sourceBefore != null
            && sourceBefore.points().size() == plan.sourcePoints().size()
            && bindingValues(sourceBefore).equals(plan.sourcePoints())
            && destinationBefore.isEmpty();
        recordAssertion(
            plan.prefix() + ".precondition",
            "source binding has all planned points and destination has no morph/keyform binding",
            "source=" + bindingEvidence(sourceBefore) + " destination=" + destinationEvidence(destinationBefore),
            precondition ? "PASS" : "FAIL"
        );
        if (!precondition) return;

        final float sourceValue = onHostThread(() -> findParameter(model, plan.source()).getValue());
        final float destinationValue = onHostThread(() -> findParameter(model, plan.destination()).getValue());
        final List<Object> sourceStates = captureStates(model, plan.target(), plan.source(), plan.sourcePoints());
        setParameterValue(model, plan.source(), sourceValue);
        onHostThread(() -> {
            model.parameterBindingBatch().transferMorphClamped(new ParameterBindingTransferPlan(
                plan.source(), plan.destination(), List.of(plan.target()), plan.invert()
            ));
            return null;
        });

        final ParameterBinding destinationAfter = awaitMorphBinding(
            model, plan.destination(), plan.target(), plan.expectedDestination()
        );
        final ParameterBinding sourceAfter = morphBinding(model, plan.source(), plan.target());
        final List<Float> actualDestination = bindingValues(destinationAfter);
        final boolean guidPass = destinationAfter != null
            && destinationAfter.parameterId().equals(plan.destination());
        final boolean familyPass = destinationAfter != null
            && destinationAfter.family() == ParameterBindingFamily.BLEND_SHAPE;
        final boolean countPass = destinationAfter != null
            && destinationAfter.points().size() == plan.sourcePoints().size();
        final boolean valuesPass = actualDestination.equals(plan.expectedDestination());
        final boolean transferred = sourceAfter == null && guidPass && familyPass && countPass && valuesPass;
        recordAssertion(
            plan.prefix() + ".destination.guid",
            plan.destination().value(),
            destinationAfter == null ? "binding=null" : destinationAfter.parameterId().value(),
            guidPass ? "PASS" : "FAIL"
        );
        recordAssertion(
            plan.prefix() + ".destination.points",
            "count=" + plan.sourcePoints().size(),
            "count=" + (destinationAfter == null ? 0 : destinationAfter.points().size())
                + " ids=" + (destinationAfter == null ? List.of() : destinationAfter.points().stream()
                    .map(point -> point.id().value()).toList()),
            countPass ? "PASS" : "FAIL"
        );
        recordAssertion(
            plan.prefix() + ".destination.values",
            "mappedBySource=" + plan.expectedDestination() + " expectedSourceOrder=" + plan.expectedDestination(),
            "actual=" + actualDestination + " binding=" + bindingEvidence(destinationAfter),
            valuesPass ? "PASS" : "FAIL"
        );
        recordAssertion(
            plan.prefix() + ".transferred",
            "one whole-binding morph edit moves every point and removes source binding",
            "source=" + bindingEvidence(sourceAfter) + " destination=" + bindingEvidence(destinationAfter),
            transferred ? "PASS" : "FAIL"
        );
        if (!transferred) return;

        pressShortcut(robot, KeyEvent.VK_Z);
        final boolean undone = awaitMorphState(
            model, plan.target(), plan.source(), plan.destination(),
            plan.sourcePoints(), List.of()
        );
        recordAssertion(
            plan.prefix() + ".undo",
            "one user Ctrl+Z restores every source point and removes destination",
            "shortcutCount=1 source=" + bindingEvidence(morphBinding(model, plan.source(), plan.target()))
                + " destination=" + bindingEvidence(morphBinding(model, plan.destination(), plan.target())),
            undone ? "PASS" : "FAIL"
        );
        if (!undone) return;

        pressShortcut(robot, KeyEvent.VK_Y);
        final boolean redone = awaitMorphState(
            model, plan.target(), plan.source(), plan.destination(),
            List.of(), plan.expectedDestination()
        );
        recordAssertion(
            plan.prefix() + ".redo",
            "one user Ctrl+Y restores the complete destination binding",
            "source=" + bindingEvidence(morphBinding(model, plan.source(), plan.target()))
                + " destination=" + bindingEvidence(morphBinding(model, plan.destination(), plan.target())),
            redone ? "PASS" : "FAIL"
        );
        if (!redone) return;

        final List<Object> destinationStates = captureStates(
            model, plan.target(), plan.destination(), plan.expectedDestination()
        );
        boolean associated = sourceStates.size() == destinationStates.size();
        for (int index = 0; index < Math.min(sourceStates.size(), destinationStates.size()); index++) {
            final boolean matches = Objects.equals(sourceStates.get(index), destinationStates.get(index));
            associated &= matches;
            recordAssertion(
                plan.prefix() + ".association." + index,
                "source=" + plan.sourcePoints().get(index) + " maps to destination="
                    + plan.expectedDestination().get(index),
                "before=" + singleLine(sourceStates.get(index))
                    + " after=" + singleLine(destinationStates.get(index)),
                matches ? "PASS" : "FAIL"
            );
        }
        recordAssertion(
            plan.prefix() + ".association",
            "every source morph point keeps its target association",
            "sourcePoints=" + plan.sourcePoints() + " mappedBySource=" + plan.expectedDestination(),
            associated ? "PASS" : "FAIL"
        );

        final int cleanupUndoSteps = pressUntil(
            model, plan.target(), plan.source(), plan.destination(),
            plan.sourcePoints(), plan.expectedDestination(), PressMode.UNDO, robot
        );
        final boolean restoredBindings = awaitMorphState(
            model, plan.target(), plan.source(), plan.destination(),
            plan.sourcePoints(), List.of()
        );
        setParameterValue(model, plan.source(), sourceValue);
        setParameterValue(model, plan.destination(), destinationValue);
        final boolean restoredValues = onHostThread(() ->
            Float.compare(findParameter(model, plan.source()).getValue(), sourceValue) == 0
                && Float.compare(findParameter(model, plan.destination()).getValue(), destinationValue) == 0
        );
        recordAssertion(
            plan.prefix() + ".restored",
            "final source/destination bindings and parameter values restored",
            "cleanupUndoSteps=" + cleanupUndoSteps
                + " source=" + bindingEvidence(morphBinding(model, plan.source(), plan.target()))
                + " destination=" + bindingEvidence(morphBinding(model, plan.destination(), plan.target()))
                + " valuesRestored=" + restoredValues,
            restoredBindings && restoredValues ? "PASS" : "FAIL"
        );
    }

    private List<RangeCandidate> morphRangeCandidates(
        final CubismModel model,
        final ParameterBindingTarget target,
        final List<BoundSnapshot> candidates,
        final ParameterId source
    ) throws Exception {
        final ArrayList<RangeCandidate> result = new ArrayList<>();
        for (final BoundSnapshot candidate : candidates) {
            if (candidate.parameterId() == null || candidate.parameterId().equals(source) || !candidate.morph()) continue;
            if (!bindingsForTarget(model, candidate.parameterId(), target).isEmpty()) continue;
            result.add(new RangeCandidate(candidate, parameterRange(model, candidate.parameterId())));
        }
        result.sort(Comparator.comparing(candidate -> candidate.snapshot().label().toLowerCase(Locale.ROOT)));
        return List.copyOf(result);
    }

    private ParameterBinding morphBinding(
        final CubismModel model,
        final ParameterId parameterId,
        final ParameterBindingTarget target
    ) throws Exception {
        ParameterBinding found = null;
        for (final ParameterBinding binding : bindingsForTarget(model, parameterId, target)) {
            if (binding.family() != ParameterBindingFamily.BLEND_SHAPE) continue;
            if (found != null) {
                throw new IllegalStateException("multiple morph bindings for " + parameterId.value()
                    + " on " + target);
            }
            found = binding;
        }
        return found;
    }

    private List<ParameterBinding> bindingsForTarget(
        final CubismModel model,
        final ParameterId parameterId,
        final ParameterBindingTarget target
    ) throws Exception {
        return onHostThread(() -> findParameter(model, parameterId).getParameterBindings().stream()
            .filter(binding -> binding.target().equals(target))
            .toList());
    }

    private ParameterBinding awaitMorphBinding(
        final CubismModel model,
        final ParameterId parameterId,
        final ParameterBindingTarget target,
        final List<Float> expected
    ) throws Exception {
        final long deadline = System.currentTimeMillis() + VALUE_AWAIT_MAX_MILLIS;
        ParameterBinding actual = morphBinding(model, parameterId, target);
        while ((actual == null || !bindingValues(actual).equals(expected))
            && System.currentTimeMillis() < deadline) {
            Thread.sleep(100L);
            actual = morphBinding(model, parameterId, target);
        }
        return actual;
    }

    private boolean awaitMorphState(
        final CubismModel model,
        final ParameterBindingTarget target,
        final ParameterId source,
        final ParameterId destination,
        final List<Float> sourceExpected,
        final List<Float> destinationExpected
    ) throws Exception {
        final long deadline = System.currentTimeMillis() + SHORTCUT_AWAIT_MAX_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            final List<Float> sourceActual = bindingValues(morphBinding(model, source, target));
            final List<Float> destinationActual = bindingValues(morphBinding(model, destination, target));
            if (sourceActual.equals(sourceExpected)
                && destinationActual.equals(destinationExpected)) {
                return true;
            }
            Thread.sleep(100L);
        }
        return false;
    }

    private static List<Float> bindingValues(final ParameterBinding binding) {
        return binding == null ? List.of() : binding.points().stream()
            .map(ParameterBindingPoint::value)
            .toList();
    }

    private static boolean allFinite(final List<Float> values) {
        return values.stream().allMatch(Float::isFinite);
    }

    private static boolean allFinite(final float[] values) {
        for (final float value : values) if (!Float.isFinite(value)) return false;
        return true;
    }

    private static MappingCandidate findWideMorphCandidate(
        final List<Float> sourcePoints,
        final List<RangeCandidate> candidates,
        final Set<ParameterId> usedTargets
    ) {
        final List<Float> normalizedSource = normalizedPoints(sourcePoints);
        for (final RangeCandidate candidate : candidates) {
            if (usedTargets.contains(candidate.snapshot().parameterId())) continue;
            final List<Float> mapped = mapPoints(sourcePoints, false, candidate.range());
            if (fitsInRange(sourcePoints, false, candidate.range())
                && isUnique(mapped) && mapped.equals(normalizedSource)) {
                return new MappingCandidate(candidate, false, mapped);
            }
        }
        return null;
    }

    private static MappingCandidate findNarrowMorphCandidate(
        final List<Float> sourcePoints,
        final List<RangeCandidate> candidates,
        final Set<ParameterId> usedTargets
    ) {
        for (final RangeCandidate candidate : candidates) {
            if (usedTargets.contains(candidate.snapshot().parameterId())) continue;
            final List<Float> mapped = mapPoints(sourcePoints, false, candidate.range());
            if (isUnique(mapped) && hasChanged(sourcePoints, mapped)) {
                return new MappingCandidate(candidate, false, mapped);
            }
        }
        return null;
    }

    private static MappingCandidate findInvertMorphCandidate(
        final List<Float> sourcePoints,
        final List<RangeCandidate> candidates
    ) {
        for (final RangeCandidate candidate : candidates) {
            final List<Float> mapped = mapPoints(sourcePoints, true, candidate.range());
            if (isUnique(mapped)) {
                return new MappingCandidate(candidate, true, mapped);
            }
        }
        return null;
    }

    private static List<Float> normalizedPoints(final List<Float> values) {
        return values.stream().map(value -> value == 0.0F ? 0.0F : value).toList();
    }

    private static String mappingEvidence(final MappingCandidate candidate) {
        return "destination=" + candidate.candidate().snapshot().parameterId().value()
            + " range=" + candidate.candidate().range()
            + " invert=" + candidate.invert()
            + " mappedBySource=" + candidate.mapped();
    }

    private static String rangeEvidence(final List<RangeCandidate> candidates) {
        return candidates.stream()
            .map(candidate -> candidate.snapshot().parameterId().value() + "=" + candidate.range())
            .toList().toString();
    }

    private static String bindingEvidence(final ParameterBinding binding) {
        return binding == null ? "binding=null" : "parameter=" + binding.parameterId().value()
            + " target=" + binding.target() + " family=" + binding.family()
            + " points=" + binding.points().stream()
                .map(point -> point.id().value() + ":" + point.value()).toList();
    }

    private static String destinationEvidence(final List<ParameterBinding> bindings) {
        return bindings.stream().map(ParameterBatchTransferHostValidationPlugin::bindingEvidence).toList().toString();
    }

    private void transferRow(
        final CubismModel model,
        final ParameterBindingTarget owner,
        final ParameterId source,
        final ParameterId destination,
        final boolean invert,
        final String assertionPrefix,
        final Robot robot
    ) throws Exception {
        final List<Float> originalPoints = bindingValues(model, source, owner);
        final List<Float> destinationBefore = bindingValues(model, destination, owner);
        if (originalPoints.isEmpty() || !destinationBefore.isEmpty()) {
            recordAssertion(
                assertionPrefix + ".precondition",
                "source bound and destination free before row",
                "source=" + originalPoints + " destination=" + destinationBefore,
                "BLOCKED"
            );
            return;
        }

        final ParameterRange destinationRange = parameterRange(model, destination);
        final List<Float> mappedBySource = mapPoints(originalPoints, invert, destinationRange);
        final List<Float> expectedDestination = sorted(mappedBySource);
        recordAssertion(
            assertionPrefix + ".mapped.unique",
            "mappedBySource has no equal or near-duplicate values",
            mappedBySource,
            isUnique(mappedBySource) ? "PASS" : "FAIL"
        );

        final float sourceValue = onHostThread(() -> findParameter(model, source).getValue());
        final float destinationValue = onHostThread(() -> findParameter(model, destination).getValue());
        final List<Object> sourceStates = captureStates(model, owner, source, originalPoints);
        setParameterValue(model, source, sourceValue);

        onHostThread(() -> {
            model.parameterBindingBatch().transferClamped(new ParameterBindingTransferPlan(
                source, destination, List.of(owner), invert
            ));
            return null;
        });

        final List<Float> moved = awaitBindingValues(model, destination, owner, expectedDestination);
        final List<Float> sourceAfterTransfer = bindingValues(model, source, owner);
        final boolean readbackSorted = !moved.isEmpty() && moved.equals(sorted(moved));
        recordAssertion(
            assertionPrefix + ".readback.sorted",
            "destination binding readback is sorted ascending",
            moved,
            readbackSorted ? "PASS" : "FAIL"
        );
        final boolean transferred = sourceAfterTransfer.isEmpty()
            && moved.equals(expectedDestination);
        recordAssertion(
            assertionPrefix + ".transferred",
            "destination bound with sorted clamp(-value/value) points",
            "mappedBySource=" + mappedBySource + " expectedSorted=" + expectedDestination
                + " destination=" + moved
                + " source=" + sourceAfterTransfer,
            transferred ? "PASS" : "FAIL"
        );


        final int undoSteps = pressUntil(
            model, owner, source, destination, originalPoints, expectedDestination,
            PressMode.UNDO, robot
        );
        final boolean undone = bindingValues(model, source, owner).equals(originalPoints)
            && bindingValues(model, destination, owner).isEmpty();
        recordAssertion(
            assertionPrefix + ".undo",
            "original binding restored on source (undoSteps=" + undoSteps + ")",
            "source=" + bindingValues(model, source, owner)
                + " destination=" + bindingValues(model, destination, owner),
            undone ? "PASS" : "FAIL"
        );

        final int redoSteps = pressUntil(
            model, owner, source, destination, originalPoints, expectedDestination,
            PressMode.REDO, robot
        );
        final boolean redone = bindingValues(model, destination, owner).equals(expectedDestination)
            && bindingValues(model, source, owner).isEmpty();
        recordAssertion(
            assertionPrefix + ".redo",
            "mapped destination restored after redo (redoSteps=" + redoSteps + ")",
            "destination=" + bindingValues(model, destination, owner)
                + " source=" + bindingValues(model, source, owner),
            redone ? "PASS" : "FAIL"
        );

        if (transferred && redone) {
            final List<Object> destinationStates = captureStates(
                model, owner, destination, mappedBySource
            );
            setParameterValue(model, destination, destinationValue);
            boolean associated = true;
            for (int index = 0; index < sourceStates.size(); index++) {
                final boolean matches = Objects.equals(sourceStates.get(index), destinationStates.get(index));
                associated &= matches;
                recordAssertion(
                    assertionPrefix + ".association." + index,
                    "state at source " + originalPoints.get(index)
                        + " equals destination state at " + mappedBySource.get(index),
                    "before=" + singleLine(sourceStates.get(index))
                        + " after=" + singleLine(destinationStates.get(index)),
                    matches ? "PASS" : "FAIL"
                );
            }
            recordAssertion(
                assertionPrefix + ".association",
                "each keyform state follows its source-order mapped coordinate",
                "mappedBySource=" + mappedBySource,
                associated ? "PASS" : "FAIL"
            );
        } else {
            recordAssertion(
                assertionPrefix + ".association",
                "keyform association checked after successful transfer and redo",
                "not evaluated because transfer or redo readback failed",
                "FAIL"
            );
        }

        pressUntil(
            model, owner, source, destination, originalPoints, expectedDestination,
            PressMode.UNDO, robot
        );
        final boolean restored = bindingValues(model, source, owner).equals(originalPoints)
            && bindingValues(model, destination, owner).isEmpty();
        recordAssertion(
            assertionPrefix + ".restored",
            "cleanup undo restored original state",
            "source=" + bindingValues(model, source, owner)
                + " destination=" + bindingValues(model, destination, owner),
            restored ? "PASS" : "FAIL"
        );
    }

    /** Presses Undo/Redo until the expected transfer state is reached. */
    private int pressUntil(
        final CubismModel model,
        final ParameterBindingTarget owner,
        final ParameterId source,
        final ParameterId destination,
        final List<Float> originalPoints,
        final List<Float> expectedDestination,
        final PressMode mode,
        final Robot robot
    ) throws Exception {
        // State sampling writes the parameter cursor and can add edits after a
        // transfer. Walk those edits quickly instead of spending the full value
        // readback budget on each intermediate state.
        final int maxSteps = originalPoints.size() + 2;
        for (int step = 1; step <= maxSteps; step++) {
            pressShortcut(robot, mode == PressMode.UNDO ? KeyEvent.VK_Z : KeyEvent.VK_Y);
            if (stateReached(
                model, owner, source, destination, originalPoints, expectedDestination, mode
            )) {
                return step;
            }
        }
        return maxSteps + 1;
    }

    private boolean stateReached(
        final CubismModel model,
        final ParameterBindingTarget owner,
        final ParameterId source,
        final ParameterId destination,
        final List<Float> originalPoints,
        final List<Float> expectedDestination,
        final PressMode mode
    ) throws Exception {
        final long deadline = System.currentTimeMillis() + SHORTCUT_AWAIT_MAX_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            final List<Float> sourceState = bindingValues(model, source, owner);
            final List<Float> destinationState = bindingValues(model, destination, owner);
            final boolean reached = mode == PressMode.UNDO
                ? sourceState.equals(originalPoints) && destinationState.isEmpty()
                : destinationState.equals(expectedDestination) && sourceState.isEmpty();
            if (reached) {
                return true;
            }
            Thread.sleep(200L);
        }
        return false;
    }

    private enum PressMode {
        UNDO, REDO
    }

    /** Evaluates the owner's rendered state at one parameter value. */
    private Object stateAt(
        final CubismModel model,
        final ParameterBindingTarget owner,
        final ParameterId parameterId,
        final float value
    ) throws Exception {
        return onHostThread(() -> {
            findParameter(model, parameterId).setValue(value);
            return switch (owner.type()) {
                case ART_MESH -> model.drawables().all().stream()
                    .filter(drawable -> drawable.id().value().equals(owner.id()))
                    .findFirst().orElseThrow().geometry();
                case WARP_DEFORMER -> model.warpDeformers().all().stream()
                    .filter(deformer -> deformer.id().value().equals(owner.id()))
                    .findFirst().orElseThrow().grid();
                case ROTATION_DEFORMER -> model.rotationDeformers().all().stream()
                    .filter(deformer -> deformer.id().value().equals(owner.id()))
                    .findFirst().orElseThrow().form();
            };
        });
    }

    private static Parameter findParameter(final CubismModel model, final ParameterId parameterId) {
        return model.parameters().all().stream()
            .filter(parameter -> parameter.id().equals(parameterId))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("parameter vanished: " + parameterId.value()));
    }

    // ------------------------------------------------------------------
    // Discovery
    // ------------------------------------------------------------------


    private MorphFixtureSetup ensureMorphFixture(final CubismModel model) throws Exception {
        final MorphOwner natural = discoverMorphOwner(model);
        if (natural != null) {
            recordAssertion(
                "setup.natural",
                "existing BLEND_SHAPE binding with at least three points",
                bindingEvidence(natural.binding()),
                "PASS"
            );
            final MorphFixtureSetup setup = MorphFixtureSetup.natural(natural);
            return setup;
        }
        recordAssertion(
            "setup.natural",
            "existing BLEND_SHAPE binding with at least three points",
            "none; task-scoped fallback setup required",
            "PASS"
        );
        final MorphFixtureSetup setup = prepareFallbackMorphFixture(model);
        return setup;
    }

    /**
     * Builds only the missing fixture precondition. The transfer itself remains the
     * public SDK whole-binding operation exercised by runMorphApplyMatrix.
     */
    private MorphFixtureSetup prepareFallbackMorphFixture(final CubismModel model) throws Exception {
        Object rawOwner = null;
        Object rawSet = null;
        Object rawModelSource = null;
        MorphOwner owner = null;
        List<Object> addedTargets = new ArrayList<>();
        List<ParameterRestore> restores = new ArrayList<>();
        List<Float> originalPoints = List.of();
        List<Object> originalTargets = List.of();
        boolean originalBindingPresent = false;
        try {
            final FallbackSource fallback = discoverFallbackSource(model);
            if (fallback == null) return prepareScaffoldMorphFixture(model);
            rawOwner = fallback.rawOwner();
            rawSet = fallback.rawSet();
            rawModelSource = fallback.rawModelSource();
            owner = fallback.owner();
            originalPoints = fallback.originalPoints();
            originalTargets = fallback.originalTargets();
            originalBindingPresent = fallback.originalBindingPresent();
            final Object setupRawSet = rawSet;
            final MorphOwner setupOwner = owner;
            final List<Float> setupPoints = fallback.selectedKeys().stream()
                .map(RawKeyform::value)
                .toList();
            recordAssertion(
                "setup.fallback.source",
                "existing global BLEND_SHAPE parameter + Drawable with >=3 distinct existing ACForms",
                "sourceId=" + owner.source().value()
                    + " ownerId=" + owner.target().id()
                    + " originalTargetCount=" + originalTargets.size()
                    + " originalTargets=" + rawTargetEvidence(originalTargets)
                    + " selectedForms=" + rawKeyEvidence(fallback.selectedKeys())
                    + " originalBindingPresent=" + originalBindingPresent,
                "PASS"
            );

            restores = selectFallbackTargets(model, owner.source());
            final DefinitionPlans definitions = fallbackDefinitions(setupPoints, restores);
            recordAssertion(
                "setup.fallback.targets.preflight",
                "two globally unbound NORMAL parameters with legal planned definitions",
                definitionPlansEvidence(definitions),
                "PASS"
            );

            final List<RawKeyform> existingKeys = fallback.selectedKeys();
            final List<Object> setupOriginalTargets = originalTargets;
            onHostThread(() -> {
                for (final RawKeyform key : existingKeys) {
                    boolean alreadyBound = false;
                    for (final Object existing : setupOriginalTargets) {
                        if (Objects.equals(rawTargetFormGuid(existing), key.formGuid())) {
                            alreadyBound = true;
                            break;
                        }
                    }
                    if (alreadyBound) continue;
                    final Object target = newRawMorphTarget(setupRawSet, key.form(), fallback.sourceGuid(), key.value());
                    invokeExact(setupRawSet, "add", target);
                    addedTargets.add(target);
                }
                return null;
            });
            refreshRawModel(model, rawModelSource);

            final ParameterBinding preparedBinding = morphBinding(model, owner.source(), owner.target());
            final List<Float> preparedPoints = bindingValues(preparedBinding);
            final boolean morphReady = preparedBinding != null
                && preparedBinding.family() == ParameterBindingFamily.BLEND_SHAPE
                && preparedPoints.size() >= 3
                && sorted(preparedPoints).equals(sorted(setupPoints))
                && allFinite(preparedPoints)
                && isUnique(preparedPoints)
                && onHostThread(() -> findParameter(model, setupOwner.source()).type() == ParameterType.BLEND_SHAPE);
            recordAssertion(
                "setup.fallback.morph",
                "public binding family=BLEND_SHAPE selected points>=3 finite unique",
                bindingEvidence(preparedBinding) + " selectedPoints=" + setupPoints,
                morphReady ? "PASS" : "BLOCKED"
            );
            if (!morphReady) {
                throw new IllegalStateException("Fallback MorphTarget setup did not project as the selected public three-point binding.");
            }

            final List<Object> associationStates = captureStates(
                model,
                setupOwner.target(),
                setupOwner.source(),
                setupPoints
            );
            final boolean distinctStates = setupPoints.size() >= 3
                && associationStates.size() == setupPoints.size()
                && arePairwiseDistinct(associationStates);
            recordAssertion(
                "setup.fallback.association",
                "at least three selected ACForm source points have pairwise-distinct owner states",
                "points=" + setupPoints
                    + " states=" + associationStates.stream()
                        .map(ParameterBatchTransferHostValidationPlugin::singleLine)
                        .toList(),
                distinctStates ? "PASS" : "BLOCKED"
            );
            if (!distinctStates) {
                throw new IllegalStateException("Fallback source ACForm association states are not pairwise distinct.");
            }

            for (final DefinitionPlan plan : definitions.plans()) {
                onHostThread(() -> {
                    plan.restore().parameter().updateDefinition(plan.definition());
                    return null;
                });
            }
            final boolean definitionsReady = onHostThread(() -> definitions.plans().stream().allMatch(plan ->
                definitionMatches(findParameter(model, plan.restore().definition().id()), plan.definition())
            ));
            recordAssertion(
                "setup.fallback.targets",
                "two BLEND_SHAPE definitions with planned wide/narrow ranges",
                definitionPlansEvidence(definitions),
                definitionsReady ? "PASS" : "BLOCKED"
            );
            if (!definitionsReady) {
                throw new IllegalStateException("Fallback target definition readback did not match the planned setup.");
            }
            recordAssertion(
                "setup.fallback",
                "task-scoped existing-form three-point source plus wide/narrow morph destinations prepared",
                "sourceId=" + owner.source().value()
                    + " ownerId=" + owner.target().id()
                    + " originalTargetCount=" + originalTargets.size()
                    + " selectedForms=" + rawKeyEvidence(fallback.selectedKeys())
                    + " points=" + preparedPoints
                    + " targets=" + definitionPlansEvidence(definitions),
                "PASS"
            );
            return new MorphFixtureSetup(
                owner,
                rawOwner,
                rawSet,
                rawModelSource,
                addedTargets,
                restores,
                originalPoints,
                originalTargets,
                originalBindingPresent,
                true,
                null
            );
        } catch (Exception failure) {
            recordAssertion(
                "setup.fallback",
                "fallback setup completes without leaving partial fixture state",
                singleLine(failure),
                "BLOCKED"
            );
            cleanupMorphFixture(
                model,
                new MorphFixtureSetup(
                    owner,
                    rawOwner,
                    rawSet,
                    rawModelSource,
                    addedTargets,
                    restores,
                    originalPoints,
                    originalTargets,
                    originalBindingPresent,
                    true,
                    null
                )
            );
            return MorphFixtureSetup.none();
        }
    }

    private MorphFixtureSetup prepareScaffoldMorphFixture(final CubismModel model) {
        Object rawOwner = null;
        Object rawSet = null;
        Object rawModelSource = null;
        Object rawGrid = null;
        Object sourceGuid = null;
        Object scaffoldGuid = null;
        ParameterId scaffoldParameter = null;
        ParameterDefinition scaffoldDefinition = null;
        float scaffoldValue = 0.0F;
        float sourceValue = 0.0F;
        MorphOwner owner = null;
        List<Object> addedTargets = new ArrayList<>();
        List<ParameterRestore> restores = new ArrayList<>();
        List<Object> originalTargets = List.of();
        List<Float> originalPoints = List.of();
        boolean originalBindingPresent = false;
        List<Float> addedValues = new ArrayList<>();
        List<Object> addedFormGuids = new ArrayList<>();
        ScaffoldBaseline baseline = null;
        try {
            final ScaffoldSource scaffold = discoverScaffoldSource(model);
            if (scaffold == null) {
                recordAssertion(
                    "setup.scaffold.source",
                    "existing BLEND_SHAPE source plus separate legal NORMAL scaffold parameter",
                    "no compatible source/owner/scaffold group",
                    "BLOCKED"
                );
                return MorphFixtureSetup.none();
            }
            rawOwner = scaffold.rawOwner();
            rawSet = scaffold.rawSet();
            rawModelSource = scaffold.rawModelSource();
            rawGrid = scaffold.rawGrid();
            sourceGuid = scaffold.sourceGuid();
            scaffoldGuid = scaffold.scaffoldGuid();
            scaffoldParameter = scaffold.scaffoldParameter();
            scaffoldDefinition = scaffold.scaffoldDefinition();
            scaffoldValue = scaffold.scaffoldValue();
            sourceValue = scaffold.sourceValue();
            owner = scaffold.owner();
            originalTargets = scaffold.originalTargets();
            originalPoints = scaffold.originalPoints();
            originalBindingPresent = scaffold.originalBindingPresent();
            baseline = scaffold.baseline();

            final Object setupRawOwner = rawOwner;
            final Object setupRawSet = rawSet;
            final Object setupRawGrid = rawGrid;
            final Object setupSourceGuid = sourceGuid;
            final Object setupScaffoldGuid = scaffoldGuid;
            final MorphOwner setupOwner = owner;
            final ScaffoldBaseline setupBaseline = baseline;
            final List<Float> setupPoints = SCAFFOLD_POINTS;
            restores = selectFallbackTargets(model, owner.source(), scaffoldParameter);
            final DefinitionPlans definitions = fallbackDefinitions(setupPoints, restores);
            recordAssertion(
                "setup.scaffold.preflight",
                "source/scaffold definitions admit [-30,0,30], scaffold grid is unbound, and two distinct destinations are reserved",
                "sourceId=" + owner.source().value()
                    + " scaffoldId=" + scaffoldParameter.value()
                    + " sourceRange=" + definitionEvidence(scaffold.sourceDefinition())
                    + " scaffoldRange=" + definitionEvidence(scaffoldDefinition)
                    + " baselineGridKeys=" + setupBaseline.gridKeys().size()
                    + " baselineForms=" + setupBaseline.formPositions().size()
                    + " targets=" + definitionPlansEvidence(definitions),
                "PASS"
            );

            final List<RawKeyform> selected = new ArrayList<>();
            for (final float point : setupPoints) {
                addedValues.add(point);
                onHostThread(() -> {
                    invokeExact(setupRawGrid, "addKey", point, setupScaffoldGuid);
                    return null;
                });
                final RawKeyform key = onHostThread(() -> scaffoldKeyForValue(
                    setupRawOwner,
                    setupScaffoldGuid,
                    point
                ));
                selected.add(key);
                if (!setupBaseline.formPositions().containsKey(key.formGuid())) {
                    addedFormGuids.add(key.formGuid());
                }
            }
            final boolean distinctForms = arePairwiseDistinct(
                selected.stream().map(RawKeyform::formGuid).toList()
            );
            final long newFormCount = selected.stream()
                .map(RawKeyform::formGuid)
                .filter(formGuid -> !setupBaseline.formPositions().containsKey(formGuid))
                .distinct()
                .count();
            if (!distinctForms || newFormCount < 2) {
                throw new IllegalStateException(
                    "Scaffold keys did not produce three distinct forms with at least two new forms: "
                        + rawKeyEvidence(selected) + " newFormCount=" + newFormCount
                );
            }

            final Map<Object, float[]> nudgedPositions = new LinkedHashMap<>();
            onHostThread(() -> {
                nudgeScaffoldForms(selected, setupBaseline, nudgedPositions);
                return null;
            });
            refreshRawModel(model, rawModelSource);
            final List<RawGridEntry> afterGridEntries = onHostThread(() -> rawGridEntries(setupRawOwner));
            final List<RawKeyform> afterGrid = flattenGridEntries(afterGridEntries);
            final List<RawKeyform> selectedAfter = scaffoldKeyforms(afterGrid, setupScaffoldGuid, setupPoints);
            onHostThread(() -> {
                requireScaffoldGridReadback(
                    setupBaseline,
                    afterGridEntries,
                    afterGrid,
                    selectedAfter,
                    nudgedPositions
                );
                return null;
            });
            recordAssertion(
                "setup.scaffold.grid",
                "exact scaffold points [-30,0,30] map to three distinct forms and at least two new forms; existing forms unchanged",
                "keys=" + rawKeyEvidence(selectedAfter)
                    + " newFormCount=" + newFormCount
                    + " nudgedForms=" + nudgedPositions.keySet(),
                "PASS"
            );

            onHostThread(() -> {
                for (final RawKeyform key : selectedAfter) {
                    final Object target = newRawMorphTarget(setupRawSet, key.form(), setupSourceGuid, key.value());
                    invokeExact(setupRawSet, "add", target);
                    addedTargets.add(target);
                }
                return null;
            });
            refreshRawModel(model, rawModelSource);

            final ParameterBinding preparedBinding = morphBinding(model, owner.source(), owner.target());
            final List<Float> preparedPoints = bindingValues(preparedBinding);
            final boolean morphReady = preparedBinding != null
                && preparedBinding.family() == ParameterBindingFamily.BLEND_SHAPE
                && preparedBinding.target().equals(owner.target())
                && preparedPoints.equals(setupPoints)
                && preparedPoints.size() == 3
                && allFinite(preparedPoints)
                && isUnique(preparedPoints)
                && onHostThread(() -> findParameter(model, setupOwner.source()).type() == ParameterType.BLEND_SHAPE);
            recordAssertion(
                "setup.scaffold.morph",
                "public source binding is exactly BLEND_SHAPE [-30,0,30], finite and unique",
                bindingEvidence(preparedBinding) + " selectedPoints=" + setupPoints,
                morphReady ? "PASS" : "BLOCKED"
            );
            if (!morphReady) {
                throw new IllegalStateException("Scaffold MorphTarget setup did not project as the exact public three-point binding.");
            }

            final List<Object> associationStates = captureStates(
                model,
                setupOwner.target(),
                setupOwner.source(),
                setupPoints
            );
            final boolean distinctStates = associationStates.size() == setupPoints.size()
                && arePairwiseDistinct(associationStates);
            recordAssertion(
                "setup.scaffold.association",
                "three scaffold source points have pairwise-distinct owner states",
                "points=" + setupPoints
                    + " states=" + associationStates.stream()
                        .map(ParameterBatchTransferHostValidationPlugin::singleLine)
                        .toList(),
                distinctStates ? "PASS" : "BLOCKED"
            );
            if (!distinctStates) {
                throw new IllegalStateException("Scaffold source form states are not pairwise distinct.");
            }

            for (final DefinitionPlan plan : definitions.plans()) {
                onHostThread(() -> {
                    plan.restore().parameter().updateDefinition(plan.definition());
                    return null;
                });
            }
            final boolean definitionsReady = onHostThread(() -> definitions.plans().stream().allMatch(plan ->
                definitionMatches(findParameter(model, plan.restore().definition().id()), plan.definition())
            ));
            recordAssertion(
                "setup.scaffold.targets",
                "two BLEND_SHAPE definitions with planned wide/narrow ranges",
                definitionPlansEvidence(definitions),
                definitionsReady ? "PASS" : "BLOCKED"
            );
            if (!definitionsReady) {
                throw new IllegalStateException("Scaffold target definition readback did not match the planned setup.");
            }
            recordAssertion(
                "setup.scaffold",
                "task-scoped keyform scaffold plus exact three-point morph source and destinations prepared",
                "sourceId=" + owner.source().value()
                    + " scaffoldId=" + scaffoldParameter.value()
                    + " points=" + preparedPoints
                    + " forms=" + rawKeyEvidence(selectedAfter)
                    + " newForms=" + addedFormGuids
                    + " targets=" + definitionPlansEvidence(definitions),
                "PASS"
            );
            return new MorphFixtureSetup(
                owner,
                rawOwner,
                rawSet,
                rawModelSource,
                addedTargets,
                restores,
                originalPoints,
                originalTargets,
                originalBindingPresent,
                true,
                new ScaffoldState(
                    rawGrid,
                    scaffoldGuid,
                    scaffoldParameter,
                    sourceValue,
                    addedValues,
                    addedFormGuids,
                    scaffoldDefinition,
                    scaffoldValue,
                    baseline
                )
            );
        } catch (Exception failure) {
            recordAssertion(
                "setup.scaffold",
                "scaffold setup completes without leaving partial grid/form/target state",
                singleLine(failure),
                "BLOCKED"
            );
            final ScaffoldState partialScaffold = rawGrid == null
                    || scaffoldGuid == null
                    || scaffoldParameter == null
                    || scaffoldDefinition == null
                    || baseline == null
                ? null
                : new ScaffoldState(
                    rawGrid,
                    scaffoldGuid,
                    scaffoldParameter,
                    sourceValue,
                    addedValues,
                    addedFormGuids,
                    scaffoldDefinition,
                    scaffoldValue,
                    baseline
                );
            cleanupMorphFixture(
                model,
                new MorphFixtureSetup(
                    owner,
                    rawOwner,
                    rawSet,
                    rawModelSource,
                    addedTargets,
                    restores,
                    originalPoints,
                    originalTargets,
                    originalBindingPresent,
                    true,
                    partialScaffold
                )
            );
            return MorphFixtureSetup.none();
        }
    }

    private FallbackSource discoverFallbackSource(final CubismModel model) throws Exception {
        final List<ParameterId> sourceParameters = onHostThread(() -> model.parameters().all().stream()
            .filter(parameter -> parameter.type() == ParameterType.BLEND_SHAPE)
            .map(Parameter::id)
            .sorted(Comparator.comparing(ParameterId::value))
            .toList());
        if (sourceParameters.isEmpty()) {
            recordAssertion(
                "setup.fallback.source.parameter",
                "optional existing-form fast path: existing global BLEND_SHAPE parameter",
                "none",
                "SKIPPED"
            );
            return null;
        }

        final List<Drawable> drawables = onHostThread(() -> model.drawables().all().stream()
            .sorted(Comparator.comparing(drawable -> drawable.id().value()))
            .toList());
        final ParameterId probeSource = sourceParameters.get(0);
        for (final Drawable drawable : drawables) {
            final ParameterBindingTarget target =
                ParameterBindingTarget.artMesh(new ArtMeshId(drawable.id().value()));
            final List<ParameterBinding> publicBindings =
                readBindings(drawable::getMorphParameterBindings);
            if (publicBindings == null) continue;

            final RawOwner raw;
            final List<RawKeyform> keyforms;
            try {
                raw = onHostThread(() -> inspectRawOwner(drawable, probeSource));
                keyforms = onHostThread(() -> rawKeyforms(raw.rawOwner()));
            } catch (Exception incompatibleDrawable) {
                continue;
            }

            for (final ParameterId source : sourceParameters) {
                final Object sourceGuid;
                final List<Object> originalTargets;
                try {
                    sourceGuid = source.equals(probeSource)
                        ? raw.sourceGuid()
                        : onHostThread(() -> rawParameter(raw.rawModelSource(), source).guid());
                    originalTargets = onHostThread(() -> rawTargetsForParameter(raw.rawSet(), sourceGuid));
                } catch (Exception incompatibleSource) {
                    continue;
                }

                final List<RawKeyform> selected = selectFallbackKeyforms(originalTargets, keyforms);
                if (selected == null || selected.size() < 3
                    || !isUnique(selected.stream().map(RawKeyform::value).toList())) {
                    continue;
                }

                final List<ParameterBinding> existingBindings = publicBindings.stream()
                    .filter(binding -> binding.target().equals(target))
                    .filter(binding -> binding.parameterId().equals(source))
                    .filter(binding -> binding.family() == ParameterBindingFamily.BLEND_SHAPE)
                    .toList();
                if (existingBindings.size() > 1) continue;
                final ParameterBinding existingBinding = existingBindings.isEmpty()
                    ? null
                    : existingBindings.get(0);
                final List<Float> originalPoints = existingBinding == null
                    ? List.of()
                    : bindingValues(existingBinding);
                if (!originalTargets.isEmpty() && existingBinding == null) continue;
                if (originalTargets.isEmpty() && existingBinding != null && !originalPoints.isEmpty()) continue;
                if (!originalTargets.isEmpty()
                    && !sorted(originalPoints).equals(rawTargetValues(originalTargets))) {
                    continue;
                }
                final ParameterBinding descriptor = existingBinding == null
                    ? new ParameterBinding(target, source, ParameterBindingFamily.BLEND_SHAPE, List.of())
                    : existingBinding;
                return new FallbackSource(
                    new MorphOwner(target, source, descriptor),
                    raw.rawOwner(),
                    raw.rawSet(),
                    raw.rawModelSource(),
                    sourceGuid,
                    originalTargets,
                    selected,
                    originalPoints,
                    existingBinding != null
                );
            }
        }
        recordAssertion(
            "setup.fallback.source",
            "optional existing-form fast path: existing global BLEND_SHAPE parameter + Drawable with >=3 distinct existing ACForms",
            "none; blendShapeParameters=" + sourceParameters.stream().map(ParameterId::value).toList(),
            "SKIPPED"
        );
        return null;
    }

    private ScaffoldSource discoverScaffoldSource(final CubismModel model) throws Exception {
        final List<ParameterCandidate> parameters = onHostThread(() -> model.parameters().all().stream()
            .map(ParameterBatchTransferHostValidationPlugin::parameterCandidate)
            .sorted(Comparator.comparing(candidate -> candidate.id().value()))
            .toList());
        final List<ParameterCandidate> sources = parameters.stream()
            .filter(candidate -> candidate.type() == ParameterType.BLEND_SHAPE)
            .filter(candidate -> Float.isFinite(candidate.value()))
            .filter(candidate -> admitsScaffoldPoints(candidate.definition()))
            .toList();
        final List<ParameterCandidate> scaffolds = parameters.stream()
            .filter(candidate -> candidate.type() == ParameterType.NORMAL)
            .filter(candidate -> Float.isFinite(candidate.value()))
            .filter(candidate -> !candidate.combined())
            .filter(candidate -> admitsScaffoldPoints(candidate.definition()))
            .toList();
        if (sources.isEmpty() || scaffolds.isEmpty()) return null;

        final List<Drawable> drawables = onHostThread(() -> model.drawables().all().stream()
            .sorted(Comparator.comparing(drawable -> drawable.id().value()))
            .toList());
        for (final Drawable drawable : drawables) {
            final ParameterBindingTarget target =
                ParameterBindingTarget.artMesh(new ArtMeshId(drawable.id().value()));
            final List<ParameterBinding> publicBindings =
                readBindings(drawable::getMorphParameterBindings);
            if (publicBindings == null) continue;
            for (final ParameterCandidate source : sources) {
                final RawOwner raw;
                final Object grid;
                final List<RawGridEntry> gridEntries;
                final List<RawKeyform> keyforms;
                try {
                    raw = onHostThread(() -> inspectRawOwner(drawable, source.id()));
                    grid = onHostThread(() -> rawGrid(raw.rawOwner()));
                    gridEntries = onHostThread(() -> rawGridEntries(raw.rawOwner()));
                    keyforms = flattenGridEntries(gridEntries);
                } catch (Exception incompatibleDrawable) {
                    continue;
                }
                final boolean sourceBound = publicBindings.stream()
                    .anyMatch(binding -> binding.target().equals(target)
                        && binding.parameterId().equals(source.id()));
                if (sourceBound) continue;
                final List<Object> originalTargets = onHostThread(() ->
                    rawTargetsForParameter(raw.rawSet(), raw.sourceGuid())
                );
                if (!originalTargets.isEmpty()) continue;

                for (final ParameterCandidate scaffold : scaffolds) {
                    if (scaffold.id().equals(source.id())) continue;
                    try {
                        final Object scaffoldGuid = onHostThread(() ->
                            rawParameter(raw.rawModelSource(), scaffold.id()).guid()
                        );
                        if (rawGridContains(grid, scaffoldGuid)
                            || keyforms.stream().anyMatch(key -> Objects.equals(key.parameterGuid(), scaffoldGuid))) {
                            continue;
                        }
                        if (!bindingsForTarget(model, scaffold.id(), target).isEmpty()) continue;
                        final ScaffoldBaseline baseline = onHostThread(() -> scaffoldBaseline(gridEntries));
                        final ParameterBinding descriptor = new ParameterBinding(
                            target,
                            source.id(),
                            ParameterBindingFamily.BLEND_SHAPE,
                            List.of()
                        );
                        return new ScaffoldSource(
                            new MorphOwner(target, source.id(), descriptor),
                            raw.rawOwner(),
                            raw.rawSet(),
                            raw.rawModelSource(),
                            grid,
                            raw.sourceGuid(),
                            scaffoldGuid,
                            scaffold.id(),
                            source.definition(),
                            source.value(),
                            scaffold.definition(),
                            scaffold.value(),
                            originalTargets,
                            List.of(),
                            false,
                            baseline
                        );
                    } catch (Exception incompatibleScaffold) {
                        // Try the next exact, legal NORMAL parameter on this owner.
                    }
                }
            }
        }
        return null;
    }

    private static ParameterCandidate parameterCandidate(final Parameter parameter) {
        return new ParameterCandidate(
            parameter.id(),
            parameter.type(),
            parameter.combined().orElse(false),
            !parameter.getParameterBindings().isEmpty(),
            parameterDefinition(parameter),
            parameter.getValue()
        );
    }

    private static boolean admitsScaffoldPoints(final ParameterDefinition definition) {
        return Float.isFinite(definition.minimumValue())
            && Float.isFinite(definition.maximumValue())
            && definition.minimumValue() <= SCAFFOLD_POINTS.get(0)
            && definition.maximumValue() >= SCAFFOLD_POINTS.get(SCAFFOLD_POINTS.size() - 1)
            && Float.isFinite(definition.defaultValue())
            && definition.defaultValue() >= definition.minimumValue()
            && definition.defaultValue() <= definition.maximumValue();
    }

    private RawOwner inspectRawOwner(final Drawable drawable, final ParameterId source) throws Exception {
        final Object rawOwner;
        try {
            rawOwner = nativeDrawableSource(drawable);
        } catch (Exception failure) {
            throw new IllegalStateException(
                "Validation raw owner lookup failed: wrapper=" + drawable.getClass().getName(),
                failure
            );
        }
        requireRawType(rawOwner, RAW_ART_MESH_SOURCE);
        final Object rawSet = invokeExact(rawOwner, "getKeyformMorphTargetSet");
        requireRawType(rawSet, RAW_MORPH_TARGET_SET);
        final Object rawModelSource = invokeExact(rawOwner, "getModelSource");
        requireRawType(rawModelSource, RAW_MODEL_SOURCE);
        final RawParameter rawParameter = rawParameter(rawModelSource, source);
        return new RawOwner(rawOwner, rawSet, rawModelSource, rawParameter.guid());
    }

    private RawParameter rawParameter(final Object rawModelSource, final ParameterId id) throws Exception {
        final Object rawParameters = invokeExact(rawModelSource, "getAllParameters");
        if (!(rawParameters instanceof List<?> parameters)) {
            throw new IllegalStateException("Exact CModelSource.getAllParameters did not return a List.");
        }
        for (final Object parameter : parameters) {
            requireRawType(parameter, RAW_PARAMETER_SOURCE);
            final Object rawId = invokeExact(parameter, "getId");
            requireRawType(rawId, RAW_PARAMETER_ID);
            final Object idString = invokeExact(rawId, "getIdString");
            if (id.value().equals(idString)) {
                final Object guid = invokeExact(parameter, "getGuid");
                requireRawType(guid, RAW_PARAMETER_GUID);
                return new RawParameter(parameter, guid);
            }
        }
        throw new IllegalStateException("Raw source parameter is absent: " + id.value());
    }

    private List<Object> rawTargetsForParameter(final Object rawSet, final Object parameterGuid) throws Exception {
        final ArrayList<Object> result = new ArrayList<>();
        for (final Object target : rawMorphTargets(rawSet)) {
            if (Objects.equals(rawTargetParameterGuid(target), parameterGuid)) result.add(target);
        }
        return List.copyOf(result);
    }

    private Object rawGrid(final Object rawOwner) throws Exception {
        requireRawType(rawOwner, RAW_ART_MESH_SOURCE);
        final Object grid = invokeExact(rawOwner, "getKeyformGridSource");
        requireRawType(grid, RAW_GRID_SOURCE);
        return grid;
    }

    private static boolean rawGridContains(final Object rawGrid, final Object parameterGuid) throws Exception {
        requireRawType(rawGrid, RAW_GRID_SOURCE);
        final Object result = invokeExact(rawGrid, "contains", parameterGuid);
        if (!(result instanceof Boolean contains)) {
            throw new IllegalStateException("Exact KeyformGridSource.contains did not return boolean.");
        }
        return contains;
    }

    private List<Object> rawMorphTargets(final Object rawSet) throws Exception {
        requireRawType(rawSet, RAW_MORPH_TARGET_SET);
        final Object rawTargets = invokeExact(rawSet, "getMorphTargets");
        if (!(rawTargets instanceof List<?> targets)) {
            throw new IllegalStateException("Exact KeyFormMorphTargetSet.getMorphTargets did not return a List.");
        }
        final ArrayList<Object> result = new ArrayList<>(targets.size());
        for (final Object target : targets) {
            requireRawType(target, RAW_MORPH_TARGET);
            result.add(target);
        }
        return List.copyOf(result);
    }

    private List<RawKeyform> rawKeyforms(final Object rawOwner) throws Exception {
        return flattenGridEntries(rawGridEntries(rawOwner));
    }

    private List<RawGridEntry> rawGridEntries(final Object rawOwner) throws Exception {
        requireRawType(rawOwner, RAW_ART_MESH_SOURCE);
        final Object grid = invokeExact(rawOwner, "getKeyformGridSource");
        requireRawType(grid, RAW_GRID_SOURCE);
        final Object rawEntries = invokeExact(grid, "getKeyformsOnGrid");
        if (!(rawEntries instanceof List<?> entries)) {
            throw new IllegalStateException("Exact KeyformGridSource.getKeyformsOnGrid did not return a List.");
        }
        final ArrayList<RawGridEntry> result = new ArrayList<>(entries.size());
        for (final Object entry : entries) {
            requireRawType(entry, RAW_GRID_ENTRY);
            final Object formGuid = invokeExact(entry, "b");
            requireRawType(formGuid, RAW_FORM_GUID);
            final Object accessKey = invokeExact(entry, "a");
            requireRawType(accessKey, RAW_GRID_KEY);
            final Object rawKeys = invokeExact(accessKey, "a");
            if (!(rawKeys instanceof List<?> keys)) {
                throw new IllegalStateException("Exact KeyformGridAccessKey.a did not return a List.");
            }
            final Object form = invokeExact(rawOwner, "getKeyForm", formGuid);
            requireRawType(form, RAW_ART_MESH_FORM);
            final ArrayList<RawKeyform> keyforms = new ArrayList<>(keys.size());
            for (final Object key : keys) {
                requireRawType(key, RAW_KEY_ON_PARAMETER);
                final Object parameterGuid = invokeExact(key, "getParameterGuid");
                requireRawType(parameterGuid, RAW_PARAMETER_GUID);
                final Object rawValue = invokeExact(key, "getValue");
                if (!(rawValue instanceof Float value) || !Float.isFinite(value)) {
                    throw new IllegalStateException("Existing keyform has a non-finite parameter value.");
                }
                keyforms.add(new RawKeyform(formGuid, form, parameterGuid, value));
            }
            result.add(new RawGridEntry(formGuid, form, rawFormPositions(form), keyforms));
        }
        return List.copyOf(result);
    }

    private static List<RawKeyform> flattenGridEntries(final List<RawGridEntry> entries) {
        return entries.stream()
            .flatMap(entry -> entry.keyforms().stream())
            .toList();
    }

    private static ScaffoldBaseline scaffoldBaseline(final List<RawGridEntry> entries) throws Exception {
        final LinkedHashMap<Object, float[]> formPositions = new LinkedHashMap<>();
        for (final RawGridEntry entry : entries) {
            final float[] positions = entry.positions();
            if (positions.length == 0 || !allFinite(positions)) {
                throw new IllegalStateException("Baseline grid form positions are invalid.");
            }
            final float[] previous = formPositions.putIfAbsent(entry.formGuid(), positions.clone());
            if (previous != null) {
                throw new IllegalStateException("Baseline grid contains a duplicate form entry.");
            }
        }
        return new ScaffoldBaseline(entries, formPositions);
    }

    private static float[] rawFormPositions(final Object form) throws Exception {
        requireRawType(form, RAW_ART_MESH_FORM);
        final Object rawPositions = invokeExact(form, "getPositions");
        if (!(rawPositions instanceof float[] positions) || positions.length == 0) {
            throw new IllegalStateException("Exact CArtMeshForm.getPositions did not return non-empty float[].");
        }
        final float[] copy = positions.clone();
        if (!allFinite(copy)) throw new IllegalStateException("CArtMeshForm positions are not finite.");
        return copy;
    }

    private static void setRawFormPositions(final Object form, final float[] positions) throws Exception {
        requireRawType(form, RAW_ART_MESH_FORM);
        if (positions.length == 0 || !allFinite(positions)) {
            throw new IllegalStateException("CArtMeshForm replacement positions are invalid.");
        }
        invokeExact(form, "setPositions", positions.clone());
    }

    private RawKeyform scaffoldKeyForValue(
        final Object rawOwner,
        final Object parameterGuid,
        final float value
    ) throws Exception {
        return scaffoldKeyForValue(rawKeyforms(rawOwner), parameterGuid, value);
    }

    private boolean scaffoldKeyPresent(
        final Object rawOwner,
        final Object parameterGuid,
        final float value
    ) throws Exception {
        return rawKeyforms(rawOwner).stream()
            .anyMatch(keyform -> Objects.equals(keyform.parameterGuid(), parameterGuid)
                && Float.compare(keyform.value(), value) == 0);
    }

    private static RawKeyform scaffoldKeyForValue(
        final List<RawKeyform> keyforms,
        final Object parameterGuid,
        final float value
    ) throws Exception {
        final List<RawKeyform> matches = keyforms.stream()
            .filter(keyform -> Objects.equals(keyform.parameterGuid(), parameterGuid))
            .filter(keyform -> Float.compare(keyform.value(), value) == 0)
            .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException(
                "Scaffold key value did not resolve to exactly one form: value=" + value
                    + " matches=" + matches.size()
            );
        }
        return matches.get(0);
    }

    private static List<RawKeyform> scaffoldKeyforms(
        final List<RawKeyform> keyforms,
        final Object parameterGuid,
        final List<Float> values
    ) throws Exception {
        final ArrayList<RawKeyform> result = new ArrayList<>(values.size());
        for (final float value : values) result.add(scaffoldKeyForValue(keyforms, parameterGuid, value));
        return List.copyOf(result);
    }

    private static void nudgeScaffoldForms(
        final List<RawKeyform> selected,
        final ScaffoldBaseline baseline,
        final Map<Object, float[]> nudgedPositions
    ) throws Exception {
        int ordinal = 0;
        for (final RawKeyform keyform : selected) {
            if (baseline.formPositions().containsKey(keyform.formGuid())) continue;
            final float[] changed = rawFormPositions(keyform.form());
            final float delta = 0.001F * ++ordinal;
            changed[0] += delta;
            if (changed.length > 1) changed[1] -= delta / 2.0F;
            if (!allFinite(changed)) throw new IllegalStateException("Scaffold nudge became non-finite.");
            setRawFormPositions(keyform.form(), changed);
            invokeExact(keyform.form(), "setUpdated");
            nudgedPositions.put(keyform.formGuid(), changed.clone());
        }
    }

    private static void requireScaffoldGridReadback(
        final ScaffoldBaseline baseline,
        final List<RawGridEntry> currentEntries,
        final List<RawKeyform> current,
        final List<RawKeyform> selected,
        final Map<Object, float[]> nudgedPositions
    ) throws Exception {
        final ArrayList<GridKeySnapshot> expectedKeys = new ArrayList<>(baseline.gridKeys());
        for (final RawKeyform keyform : selected) {
            expectedKeys.add(new GridKeySnapshot(keyform.formGuid(), keyform.parameterGuid(), keyform.value()));
        }
        if (!containsGridKeys(current, expectedKeys)) {
            throw new IllegalStateException("Scaffold grid key multiset changed during setup.");
        }
        if (!gridFormSetMatches(baseline, currentEntries, selected)) {
            throw new IllegalStateException("Scaffold grid form set changed unexpectedly during setup.");
        }
        if (!baselineFormPositionsMatch(baseline, currentEntries)) {
            throw new IllegalStateException("An existing form changed during scaffold setup.");
        }
        if (selected.size() != SCAFFOLD_POINTS.size()
            || !SCAFFOLD_POINTS.equals(selected.stream().map(RawKeyform::value).toList())
            || !arePairwiseDistinct(selected.stream().map(RawKeyform::formGuid).toList())) {
            throw new IllegalStateException("Scaffold readback did not preserve exact points and distinct forms.");
        }
        final long newFormCount = selected.stream()
            .map(RawKeyform::formGuid)
            .filter(formGuid -> !baseline.formPositions().containsKey(formGuid))
            .distinct()
            .count();
        if (newFormCount < 2) throw new IllegalStateException("Scaffold readback has fewer than two new forms.");
        for (final RawKeyform keyform : selected) {
            final float[] positions = rawFormPositions(keyform.form());
            if (baseline.formPositions().containsKey(keyform.formGuid())) {
                if (!Arrays.equals(baseline.formPositions().get(keyform.formGuid()), positions)) {
                    throw new IllegalStateException("An existing selected form changed during scaffold setup.");
                }
            } else if (!Arrays.equals(nudgedPositions.get(keyform.formGuid()), positions)) {
                throw new IllegalStateException("A new scaffold form position did not read back exactly.");
            }
        }
    }

    private static boolean containsGridKeys(
        final List<RawKeyform> current,
        final List<GridKeySnapshot> expected
    ) {
        final List<GridKeySnapshot> actual = current.stream()
            .map(keyform -> new GridKeySnapshot(keyform.formGuid(), keyform.parameterGuid(), keyform.value()))
            .toList();
        if (actual.size() != expected.size()) return false;
        final boolean[] matched = new boolean[actual.size()];
        for (final GridKeySnapshot expectedKey : expected) {
            boolean found = false;
            for (int index = 0; index < actual.size(); index++) {
                if (matched[index]) continue;
                final GridKeySnapshot actualKey = actual.get(index);
                if (Objects.equals(expectedKey.formGuid(), actualKey.formGuid())
                    && Objects.equals(expectedKey.parameterGuid(), actualKey.parameterGuid())
                    && Float.compare(expectedKey.value(), actualKey.value()) == 0) {
                    matched[index] = true;
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private static boolean gridFormSetMatches(
        final ScaffoldBaseline baseline,
        final List<RawGridEntry> current,
        final List<RawKeyform> selected
    ) {
        final Set<Object> expected = new java.util.LinkedHashSet<>(baseline.formPositions().keySet());
        selected.stream().map(RawKeyform::formGuid).forEach(expected::add);
        final Set<Object> actual = current.stream().map(RawGridEntry::formGuid).collect(Collectors.toSet());
        return current.size() == actual.size() && actual.equals(expected);
    }

    private static boolean baselineFormPositionsMatch(
        final ScaffoldBaseline baseline,
        final List<RawGridEntry> current
    ) {
        for (final Map.Entry<Object, float[]> entry : baseline.formPositions().entrySet()) {
            final RawGridEntry found = current.stream()
                .filter(candidate -> Objects.equals(entry.getKey(), candidate.formGuid()))
                .findFirst()
                .orElse(null);
            if (found == null || !Arrays.equals(entry.getValue(), found.positions())) return false;
        }
        return true;
    }

    private static boolean exactFormSet(
        final ScaffoldBaseline baseline,
        final List<RawGridEntry> current
    ) {
        final Set<Object> currentGuids = current.stream()
            .map(RawGridEntry::formGuid)
            .collect(Collectors.toSet());
        return current.size() == baseline.entries().size()
            && current.size() == currentGuids.size()
            && currentGuids.equals(baseline.formPositions().keySet());
    }

    private static List<RawKeyform> selectFallbackKeyforms(
        final List<Object> sourceTargets,
        final List<RawKeyform> keyforms
    ) throws Exception {
        final Map<Object, List<RawKeyform>> byParameter = new LinkedHashMap<>();
        for (final RawKeyform keyform : keyforms) {
            byParameter.computeIfAbsent(keyform.parameterGuid(), ignored -> new ArrayList<>()).add(keyform);
        }
        for (final List<RawKeyform> group : byParameter.values()) {
            final Map<Object, RawKeyform> byForm = new LinkedHashMap<>();
            for (final RawKeyform keyform : group) {
                final RawKeyform previous = byForm.putIfAbsent(keyform.formGuid(), keyform);
                if (previous != null && Float.compare(previous.value(), keyform.value()) != 0) {
                    throw new IllegalStateException("One ACForm has conflicting values for a parameter.");
                }
            }
            if (byForm.size() < 3) continue;
            final ArrayList<RawKeyform> selected = new ArrayList<>();
            for (final Object target : sourceTargets) {
                final RawKeyform keyform = byForm.get(rawTargetFormGuid(target));
                if (keyform == null || Float.compare(rawTargetKeyValue(target), keyform.value()) != 0) {
                    selected.clear();
                    break;
                }
                if (selected.stream().noneMatch(value -> Objects.equals(value.formGuid(), keyform.formGuid()))) {
                    selected.add(keyform);
                }
            }
            if (selected.isEmpty() && !sourceTargets.isEmpty()) continue;
            final List<RawKeyform> ordered = group.stream()
                .distinct()
                .sorted(Comparator.comparing(RawKeyform::value))
                .toList();
            while (selected.size() < 3) {
                RawKeyform best = null;
                float distance = -1.0F;
                for (final RawKeyform candidate : ordered) {
                    if (selected.stream().anyMatch(value -> Objects.equals(value.formGuid(), candidate.formGuid()))) continue;
                    final float candidateDistance = (float) selected.stream()
                        .mapToDouble(value -> Math.abs(value.value() - candidate.value()))
                        .min()
                        .orElse(Double.POSITIVE_INFINITY);
                    if (best == null || candidateDistance > distance) {
                        best = candidate;
                        distance = candidateDistance;
                    }
                }
                if (best == null) break;
                selected.add(best);
            }
            if (selected.size() >= 3 && isUnique(selected.stream().map(RawKeyform::value).toList())) {
                return selected.stream().sorted(Comparator.comparing(RawKeyform::value)).toList();
            }
        }
        return null;
    }

    private List<ParameterRestore> selectFallbackTargets(
        final CubismModel model,
        final ParameterId source
    ) throws Exception {
        return selectFallbackTargets(model, source, null);
    }

    private List<ParameterRestore> selectFallbackTargets(
        final CubismModel model,
        final ParameterId source,
        final ParameterId excluded
    ) throws Exception {
        return onHostThread(() -> {
            final ArrayList<ParameterRestore> candidates = new ArrayList<>();
            for (final Parameter parameter : model.parameters().all()) {
                if (parameter.id().equals(source)
                    || parameter.id().equals(excluded)
                    || parameter.type() != ParameterType.NORMAL
                    || parameter.combined().orElse(false)
                    || !parameter.getParameterBindings().isEmpty()) {
                    continue;
                }
                candidates.add(new ParameterRestore(
                    parameter,
                    parameterDefinition(parameter),
                    parameter.getValue()
                ));
            }
            candidates.sort(Comparator.comparing(value -> value.definition().id().value()));
            if (candidates.size() < 2) {
                throw new IllegalStateException(
                    "Fallback requires two globally unbound NORMAL parameters; found " + candidates.size()
                );
            }
            return List.copyOf(candidates.subList(0, 2));
        });
    }

    private static ParameterDefinition parameterDefinition(final Parameter parameter) {
        return new ParameterDefinition(
            parameter.id(),
            parameter.name().filter(value -> !value.isBlank()).orElse(parameter.id().value()),
            parameter.getMinimumValue(),
            parameter.getDefaultValue(),
            parameter.getMaximumValue(),
            parameter.type(),
            parameter.repeat().orElse(false)
        );
    }

    private static DefinitionPlans fallbackDefinitions(
        final List<Float> sourcePoints,
        final List<ParameterRestore> restores
    ) {
        final float sourceMinimum = (float) sourcePoints.stream().mapToDouble(Float::doubleValue).min().orElseThrow();
        final float sourceMaximum = (float) sourcePoints.stream().mapToDouble(Float::doubleValue).max().orElseThrow();
        float wideMinimum = Math.min(-70.0F, sourceMinimum);
        float wideMaximum = Math.max(50.0F, sourceMaximum);
        wideMinimum = Math.min(wideMinimum, -sourceMaximum);
        wideMaximum = Math.max(wideMaximum, -sourceMinimum);
        for (final ParameterRestore restore : restores) {
            wideMinimum = Math.min(wideMinimum, restore.definition().defaultValue());
            wideMaximum = Math.max(wideMaximum, restore.definition().defaultValue());
        }
        if (Float.compare(wideMinimum, wideMaximum) == 0) {
            wideMinimum -= 1.0F;
            wideMaximum += 1.0F;
        }
        final ParameterRestore wide = restores.get(0);
        final ParameterRestore narrow = restores.get(1);
        final ParameterRange wideRange = new ParameterRange(wideMinimum, wideMaximum);
        final ParameterRange narrowRange = narrowRange(sourcePoints, narrow.definition().defaultValue());
        return new DefinitionPlans(
            List.of(
                new DefinitionPlan(wide, withType(wide.definition(), wideRange, ParameterType.BLEND_SHAPE)),
                new DefinitionPlan(narrow, withType(narrow.definition(), narrowRange, ParameterType.BLEND_SHAPE))
            ),
            wideRange,
            narrowRange
        );
    }

    private static ParameterDefinition withType(
        final ParameterDefinition current,
        final ParameterRange range,
        final ParameterType type
    ) {
        return new ParameterDefinition(
            current.id(),
            current.name(),
            range.minimum(),
            current.defaultValue(),
            range.maximum(),
            type,
            current.repeat()
        );
    }

    private static ParameterRange narrowRange(final List<Float> sourcePoints, final float defaultValue) {
        final ParameterRange preferred = new ParameterRange(-10.0F, 50.0F);
        final ArrayList<ParameterRange> candidates = new ArrayList<>();
        if (defaultValue >= preferred.minimum() && defaultValue <= preferred.maximum()) {
            candidates.add(preferred);
        }
        final ArrayList<Float> boundaries = new ArrayList<>();
        boundaries.add(defaultValue);
        for (final float value : sourcePoints) {
            boundaries.add(value);
            boundaries.add(-value);
        }
        boundaries.sort(Float::compare);
        final ArrayList<Float> uniqueBoundaries = new ArrayList<>();
        for (final float boundary : boundaries) {
            if (uniqueBoundaries.isEmpty() || !near(uniqueBoundaries.get(uniqueBoundaries.size() - 1), boundary)) {
                uniqueBoundaries.add(boundary);
            }
        }
        final int originalBoundaryCount = uniqueBoundaries.size();
        for (int index = 1; index < originalBoundaryCount; index++) {
            final float left = uniqueBoundaries.get(index - 1);
            final float right = uniqueBoundaries.get(index);
            final float midpoint = (left / 2.0F) + (right / 2.0F);
            if (Float.isFinite(midpoint)) uniqueBoundaries.add(midpoint);
        }
        uniqueBoundaries.sort(Float::compare);
        for (final float minimum : uniqueBoundaries) {
            for (final float maximum : uniqueBoundaries) {
                if (!(minimum < maximum) || defaultValue < minimum || defaultValue > maximum) continue;
                candidates.add(new ParameterRange(minimum, maximum));
            }
        }
        for (final ParameterRange candidate : candidates) {
            final List<Float> plain = mapPoints(sourcePoints, false, candidate);
            final List<Float> inverted = mapPoints(sourcePoints, true, candidate);
            if (hasChanged(sourcePoints, plain) && isUnique(plain) && isUnique(inverted)) return candidate;
        }
        throw new IllegalStateException("No narrow range preserves unique plain and inverted three-point mappings.");
    }

    private static boolean definitionMatches(final Parameter parameter, final ParameterDefinition expected) {
        return parameter.id().equals(expected.id())
            && parameter.name().filter(value -> !value.isBlank()).orElse(parameter.id().value()).equals(expected.name())
            && Float.compare(parameter.getMinimumValue(), expected.minimumValue()) == 0
            && Float.compare(parameter.getDefaultValue(), expected.defaultValue()) == 0
            && Float.compare(parameter.getMaximumValue(), expected.maximumValue()) == 0
            && parameter.type() == expected.type()
            && parameter.repeat().orElse(false) == expected.repeat();
    }

    private static String definitionEvidence(final ParameterDefinition definition) {
        return definition.id().value()
            + "[" + definition.minimumValue() + "," + definition.defaultValue() + ","
            + definition.maximumValue() + ",type=" + definition.type() + ",repeat=" + definition.repeat() + "]";
    }

    private static String definitionPlansEvidence(final DefinitionPlans definitions) {
        if (definitions.plans().size() != 2) {
            throw new IllegalStateException("Fallback definition plan must contain exactly wide and narrow entries.");
        }
        return "wide=" + definitionEvidence(definitions.plans().get(0).definition())
            + " narrow=" + definitionEvidence(definitions.plans().get(1).definition());
    }

    private void cleanupMorphFixture(final CubismModel model, final MorphFixtureSetup fixture) {
        if (!fixture.fallback()) return;
        boolean rawRemoved = true;
        if (!fixture.addedTargets().isEmpty()) {
            for (int index = fixture.addedTargets().size() - 1; index >= 0; index--) {
                final Object target = fixture.addedTargets().get(index);
                try {
                    onHostThread(() -> {
                        invokeExact(fixture.rawSet(), "remove", target);
                        return null;
                    });
                } catch (Exception failure) {
                    rawRemoved = false;
                    logger.warn("PBT_FALLBACK_REMOVE_FAILED " + singleLine(failure));
                }
            }
            try {
                refreshRawModel(model, fixture.rawModelSource());
            } catch (Exception failure) {
                rawRemoved = false;
                logger.warn("PBT_FALLBACK_REFRESH_FAILED " + singleLine(failure));
            }
        }
        if (fixture.rawSet() != null && fixture.rawModelSource() != null && fixture.owner() != null) {
            try {
                final List<Object> currentTargets = onHostThread(() -> {
                    final Object sourceGuid = rawParameter(
                        fixture.rawModelSource(),
                        fixture.owner().source()
                    ).guid();
                    return rawTargetsForParameter(fixture.rawSet(), sourceGuid);
                });
                final boolean addedAbsent = onHostThread(() -> rawMorphTargets(fixture.rawSet()).stream()
                    .noneMatch(candidate -> fixture.addedTargets().stream().anyMatch(added -> added == candidate)));
                final boolean originalPreserved = rawTargetsMatch(fixture.originalTargets(), currentTargets);
                rawRemoved &= addedAbsent && originalPreserved;
                recordAssertion(
                    "setup.cleanup.morphTargets",
                    "all fallback MorphTargets removed and original raw targets preserved",
                    "added=" + fixture.addedTargets().size()
                        + " absent=" + addedAbsent
                        + " originalTargetCount=" + fixture.originalTargets().size()
                        + " original=" + rawTargetEvidence(fixture.originalTargets())
                        + " current=" + rawTargetEvidence(currentTargets),
                    addedAbsent && originalPreserved ? "PASS" : "FAIL"
                );
            } catch (Exception failure) {
                rawRemoved = false;
                recordAssertion(
                    "setup.cleanup.morphTargets",
                    "all fallback MorphTargets removed and original raw targets preserved",
                    singleLine(failure),
                    "FAIL"
                );
            }
        }

        boolean scaffoldRestored = true;
        if (fixture.scaffold() != null) {
            final ScaffoldState scaffold = fixture.scaffold();
            for (int index = scaffold.addedValues().size() - 1; index >= 0; index--) {
                final float value = scaffold.addedValues().get(index);
                try {
                    final boolean present = onHostThread(() -> scaffoldKeyPresent(
                        fixture.rawOwner(),
                        scaffold.scaffoldGuid(),
                        value
                    ));
                    if (!present) continue;
                    onHostThread(() -> {
                        invokeExact(scaffold.rawGrid(), "removeKey", value, scaffold.scaffoldGuid());
                        return null;
                    });
                } catch (Exception failure) {
                    scaffoldRestored = false;
                    logger.warn("PBT_FALLBACK_SCAFFOLD_REMOVE_FAILED " + singleLine(failure));
                }
            }
            try {
                refreshRawModel(model, fixture.rawModelSource());
            } catch (Exception failure) {
                scaffoldRestored = false;
                logger.warn("PBT_FALLBACK_SCAFFOLD_REFRESH_FAILED " + singleLine(failure));
            }
            try {
                final List<RawGridEntry> currentEntries = onHostThread(() -> rawGridEntries(fixture.rawOwner()));
                final List<RawKeyform> current = flattenGridEntries(currentEntries);
                final boolean gridRestored = currentEntries.size() == scaffold.baseline().entries().size()
                    && containsGridKeys(current, scaffold.baseline().gridKeys());
                final boolean positionsRestored = baselineFormPositionsMatch(scaffold.baseline(), currentEntries);
                final boolean formsRestored = exactFormSet(scaffold.baseline(), currentEntries);
                final boolean addedFormsAbsent = scaffold.addedFormGuids().stream()
                    .noneMatch(added -> currentEntries.stream().anyMatch(entry -> Objects.equals(added, entry.formGuid())));
                final boolean scaffoldRawUnbound = !onHostThread(() -> rawGridContains(
                    scaffold.rawGrid(),
                    scaffold.scaffoldGuid()
                )) && current.stream()
                    .noneMatch(key -> Objects.equals(key.parameterGuid(), scaffold.scaffoldGuid()));
                final boolean scaffoldPublicUnbound = bindingsForTarget(
                    model,
                    scaffold.scaffoldParameter(),
                    fixture.owner().target()
                ).isEmpty();
                final boolean definitionRestored = onHostThread(() -> {
                    final Parameter parameter = findParameter(model, scaffold.scaffoldParameter());
                    parameter.setValue(scaffold.originalValue());
                    return definitionMatches(parameter, scaffold.originalDefinition())
                        && Float.compare(parameter.getValue(), scaffold.originalValue()) == 0
                        && parameter.type() == ParameterType.NORMAL;
                });
                scaffoldRestored &= gridRestored
                    && positionsRestored
                    && formsRestored
                    && addedFormsAbsent
                    && scaffoldRawUnbound
                    && scaffoldPublicUnbound
                    && definitionRestored;
                recordAssertion(
                    "setup.cleanup.scaffold",
                    "added scaffold keys/forms removed and baseline grid/forms/positions plus NORMAL scaffold state restored exactly",
                    "entries=" + currentEntries.size()
                        + " baselineEntries=" + scaffold.baseline().entries().size()
                        + " grid=" + gridRestored
                        + " positions=" + positionsRestored
                        + " forms=" + formsRestored
                        + " addedFormsAbsent=" + addedFormsAbsent
                        + " scaffoldRawUnbound=" + scaffoldRawUnbound
                        + " scaffoldPublicUnbound=" + scaffoldPublicUnbound
                        + " definition=" + definitionRestored
                        + " addedFormGuids=" + scaffold.addedFormGuids(),
                    scaffoldRestored ? "PASS" : "FAIL"
                );
            } catch (Exception failure) {
                scaffoldRestored = false;
                recordAssertion(
                    "setup.cleanup.scaffold",
                    "added scaffold keys/forms removed and baseline grid/forms/positions restored exactly",
                    singleLine(failure),
                    "FAIL"
                );
            }
        }

        boolean definitionsRestored = true;
        for (int index = fixture.restores().size() - 1; index >= 0; index--) {
            final ParameterRestore restore = fixture.restores().get(index);
            try {
                onHostThread(() -> {
                    restore.parameter().updateDefinition(restore.definition());
                    restore.parameter().setValue(restore.value());
                    return null;
                });
            } catch (Exception failure) {
                definitionsRestored = false;
                logger.warn("PBT_FALLBACK_DEFINITION_RESTORE_FAILED " + singleLine(failure));
            }
        }
        try {
            final boolean definitionReadback = onHostThread(() -> fixture.restores().stream().allMatch(restore -> {
                final Parameter parameter = findParameter(model, restore.definition().id());
                return definitionMatches(parameter, restore.definition())
                    && Float.compare(parameter.getValue(), restore.value()) == 0;
            }));
            definitionsRestored &= definitionReadback;
            recordAssertion(
                "setup.cleanup.definitions",
                "fallback target definitions and values restored exactly",
                fixture.restores().stream().map(restore -> definitionEvidence(restore.definition())).toList()
                    + " readback=" + definitionReadback,
                definitionReadback ? "PASS" : "FAIL"
            );
        } catch (Exception failure) {
            definitionsRestored = false;
            recordAssertion("setup.cleanup.definitions", "fallback target definitions restored exactly", singleLine(failure), "FAIL");
        }

        boolean sourceRestored = true;
        if (fixture.owner() != null) {
            try {
                boolean sourceValueRestored = true;
                if (fixture.scaffold() != null) {
                    final ScaffoldState scaffold = fixture.scaffold();
                    onHostThread(() -> {
                        findParameter(model, fixture.owner().source()).setValue(scaffold.sourceValue());
                        return null;
                    });
                    sourceValueRestored = onHostThread(() -> Float.compare(
                        findParameter(model, fixture.owner().source()).getValue(),
                        scaffold.sourceValue()
                    ) == 0);
                }
                final ParameterBinding restored = morphBinding(model, fixture.owner().source(), fixture.owner().target());
                final boolean bindingRestored = fixture.originalBindingPresent()
                    ? restored != null && bindingValues(restored).equals(fixture.originalPoints())
                    : restored == null;
                sourceRestored = bindingRestored && sourceValueRestored;
                recordAssertion(
                    "setup.cleanup.source",
                    fixture.originalBindingPresent()
                        ? "original source morph binding and value restored"
                        : "source remains unbound and value restored when it was originally unbound",
                    "originalBindingPresent=" + fixture.originalBindingPresent()
                        + " restored=" + bindingEvidence(restored)
                        + " expected=" + fixture.originalPoints()
                        + " valueRestored=" + sourceValueRestored,
                    sourceRestored ? "PASS" : "FAIL"
                );
            } catch (Exception failure) {
                sourceRestored = false;
                recordAssertion("setup.cleanup.source", "original source morph binding and value restored", singleLine(failure), "FAIL");
            }
        }
        if (!(rawRemoved && scaffoldRestored && definitionsRestored && sourceRestored)) {
            logger.warn("PBT_FALLBACK_CLEANUP_FAILED");
        }
    }

    private void refreshRawModel(final CubismModel model, final Object rawModelSource) throws Exception {
        if (rawModelSource == null) return;
        onHostThread(() -> {
            invokeExact(rawModelSource, "updateParamInstanceKeepValue");
            model.update();
            return null;
        });
    }

    private static Object nativeDrawableSource(final Drawable drawable) throws Exception {
        Objects.requireNonNull(drawable, "drawable");
        final String wrapperClass = drawable.getClass().getName();
        Object candidate = drawable;
        int unwrapDepth = 0;
        while (isAllowedDrawableWrapper(candidate.getClass().getName())) {
            if (unwrapDepth++ >= MAX_DRAWABLE_UNWRAP_DEPTH) {
                throw new IllegalStateException(
                    "Validation Drawable unwrap depth exceeded: wrapper=" + wrapperClass
                        + " underlying=" + candidate.getClass().getName()
                );
            }
            candidate = unwrapDrawableDelegate(candidate, wrapperClass);
        }
        final String underlyingClass = candidate.getClass().getName();
        if (!EDITOR_DRAWABLE_CLASS.equals(underlyingClass)) {
            throw new IllegalStateException(
                "Validation Drawable base is not whitelisted: wrapper=" + wrapperClass
                    + " underlying=" + underlyingClass
            );
        }
        Method nativeSource = null;
        for (Class<?> type = candidate.getClass(); type != null && nativeSource == null; type = type.getSuperclass()) {
            try {
                nativeSource = type.getDeclaredMethod("nativeSource");
            } catch (NoSuchMethodException ignored) {
                // Continue through the verified SDK wrapper hierarchy only.
            } catch (SecurityException failure) {
                throw new IllegalStateException(
                    "Validation Drawable nativeSource reflection lookup failed: wrapper=" + wrapperClass
                        + " underlying=" + underlyingClass,
                    failure
                );
            }
        }
        if (nativeSource == null || nativeSource.getParameterCount() != 0
            || nativeSource.getReturnType() != Object.class) {
            throw new IllegalStateException(
                "The validation-only Drawable nativeSource seam is unavailable: wrapper=" + wrapperClass
                    + " underlying=" + underlyingClass
            );
        }
        try {
            nativeSource.setAccessible(true);
            final Object raw = invokeMethod(nativeSource, candidate);
            requireRawType(raw, RAW_ART_MESH_SOURCE);
            return raw;
        } catch (Exception failure) {
            throw new IllegalStateException(
                "Validation Drawable nativeSource failed: wrapper=" + wrapperClass
                    + " underlying=" + underlyingClass,
                failure
            );
        }
    }

    private static Object unwrapDrawableDelegate(final Object wrapper, final String wrapperClass) throws Exception {
        final String actualClass = wrapper.getClass().getName();
        if (!isAllowedDrawableWrapper(actualClass)) {
            throw new IllegalStateException(
                "Validation Drawable wrapper is not whitelisted: wrapper=" + wrapperClass
                    + " underlying=" + actualClass
            );
        }
        final Field delegate;
        try {
            delegate = wrapper.getClass().getDeclaredField("delegate");
        } catch (Exception failure) {
            throw new IllegalStateException(
                "Validation Drawable delegate field is unavailable: wrapper=" + wrapperClass
                    + " underlying=" + actualClass,
                failure
            );
        }
        if (!Drawable.class.isAssignableFrom(delegate.getType())) {
            throw new IllegalStateException(
                "Validation Drawable delegate field type is not Drawable: wrapper=" + wrapperClass
                    + " underlying=" + actualClass + " fieldType=" + delegate.getType().getName()
            );
        }
        try {
            delegate.setAccessible(true);
            final Object value = delegate.get(wrapper);
            if (!(value instanceof Drawable)) {
                throw new IllegalStateException(
                    "Validation Drawable delegate value is not Drawable: wrapper=" + wrapperClass
                        + " underlying=" + actualClass
                        + " delegate=" + (value == null ? "null" : value.getClass().getName())
                );
            }
            return value;
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(
                "Validation Drawable delegate read failed: wrapper=" + wrapperClass
                    + " underlying=" + actualClass,
                failure
            );
        }
    }

    private static boolean isAllowedDrawableWrapper(final String className) {
        return SESSION_DRAWABLE_CLASS.equals(className)
            || PERMISSION_CHECKED_DRAWABLE_CLASS.equals(className);
    }

    private static Object newRawMorphTarget(
        final Object loaderSource,
        final Object form,
        final Object parameterGuid,
        final float value
    ) throws Exception {
        requireRawType(loaderSource, RAW_MORPH_TARGET_SET);
        requireRawType(form, RAW_ART_MESH_FORM);
        requireRawType(parameterGuid, RAW_PARAMETER_GUID);
        final ClassLoader loader = loaderSource.getClass().getClassLoader();
        final Constructor<?> constructor = Class.forName(RAW_MORPH_TARGET, true, loader).getConstructor(
            Class.forName(RAW_AC_FORM, true, loader),
            Class.forName(RAW_PARAMETER_GUID, true, loader),
            float.class
        );
        final Object target = constructor.newInstance(form, parameterGuid, value);
        requireRawType(target, RAW_MORPH_TARGET);
        return target;
    }

    private static Object invokeExact(final Object receiver, final String name, final Object... args) throws Exception {
        if (receiver == null) throw new IllegalStateException("Cannot invoke " + name + " on null.");
        Method match = null;
        for (final Method method : receiver.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            final Class<?>[] parameterTypes = method.getParameterTypes();
            boolean compatible = true;
            for (int index = 0; index < args.length; index++) {
                if (!parameterCompatible(parameterTypes[index], args[index])) {
                    compatible = false;
                    break;
                }
            }
            if (compatible) {
                if (match == null) {
                    match = method;
                } else {
                    final Class<?> currentReturn = match.getReturnType();
                    final Class<?> candidateReturn = method.getReturnType();
                    if (currentReturn.isAssignableFrom(candidateReturn)) {
                        match = method;
                    } else if (!candidateReturn.isAssignableFrom(currentReturn)) {
                        throw new IllegalStateException("Ambiguous exact host method: " + name);
                    }
                }
            }
        }
        if (match == null) {
            throw new IllegalStateException(
                "Exact host method is unavailable: " + receiver.getClass().getName() + "." + name
            );
        }
        match.setAccessible(true);
        return invokeMethod(match, receiver, args);
    }

    private static boolean parameterCompatible(final Class<?> parameterType, final Object argument) {
        if (argument == null) return !parameterType.isPrimitive();
        if (!parameterType.isPrimitive()) return parameterType.isAssignableFrom(argument.getClass());
        return (parameterType == boolean.class && argument instanceof Boolean)
            || (parameterType == float.class && argument instanceof Float)
            || (parameterType == double.class && argument instanceof Double)
            || (parameterType == int.class && argument instanceof Integer)
            || (parameterType == long.class && argument instanceof Long);
    }

    private static Object invokeMethod(final Method method, final Object receiver, final Object... args) throws Exception {
        try {
            return method.invoke(receiver, args);
        } catch (InvocationTargetException failure) {
            final Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw failure;
        }
    }

    private static void requireRawType(final Object value, final String expectedClass) {
        if (value == null || !expectedClass.equals(value.getClass().getName())) {
            throw new IllegalStateException(
                "Exact host class mismatch: expected " + expectedClass
                    + " actual=" + (value == null ? "null" : value.getClass().getName())
            );
        }
    }

    private static Object rawTargetParameterGuid(final Object target) throws Exception {
        final Object guid = invokeExact(target, "getParameterGuid");
        requireRawType(guid, RAW_PARAMETER_GUID);
        return guid;
    }

    private static Object rawTargetFormGuid(final Object target) throws Exception {
        final Object guid = invokeExact(target, "getKeyformGuid");
        requireRawType(guid, RAW_FORM_GUID);
        return guid;
    }

    private static float rawTargetKeyValue(final Object target) throws Exception {
        final Object value = invokeExact(target, "getKeyValue");
        if (!(value instanceof Float keyValue) || !Float.isFinite(keyValue)) {
            throw new IllegalStateException("Exact MorphTarget key value is not finite.");
        }
        return keyValue;
    }

    private static String rawTargetEvidence(final List<?> targets) throws Exception {
        return targets.stream().map(target -> {
            try {
                return rawTargetFormGuid(target) + ":" + rawTargetKeyValue(target);
            } catch (Exception failure) {
                return "invalid(" + singleLine(failure) + ")";
            }
        }).toList().toString();
    }

    private static List<Float> rawTargetValues(final List<?> targets) throws Exception {
        final ArrayList<Float> values = new ArrayList<>(targets.size());
        for (final Object target : targets) values.add(rawTargetKeyValue(target));
        values.sort(Float::compare);
        return List.copyOf(values);
    }

    private static boolean rawTargetsMatch(final List<?> expected, final List<?> actual) throws Exception {
        if (expected.size() != actual.size()) return false;
        final boolean[] matched = new boolean[actual.size()];
        for (final Object expectedTarget : expected) {
            boolean found = false;
            for (int index = 0; index < actual.size(); index++) {
                if (matched[index]) continue;
                final Object actualTarget = actual.get(index);
                if (Objects.equals(rawTargetFormGuid(expectedTarget), rawTargetFormGuid(actualTarget))
                    && Float.compare(rawTargetKeyValue(expectedTarget), rawTargetKeyValue(actualTarget)) == 0) {
                    matched[index] = true;
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private static String rawKeyEvidence(final List<RawKeyform> keyforms) {
        return keyforms.stream()
            .map(keyform -> keyform.formGuid() + ":" + keyform.value())
            .toList()
            .toString();
    }

    private MorphOwner discoverMorphOwner(final CubismModel model) throws Exception {
        final List<Drawable> drawables = onHostThread(model.drawables()::all);
        for (final Drawable drawable : drawables) {
            final List<ParameterBinding> bindings = readBindings(drawable::getMorphParameterBindings);
            if (bindings == null) continue;
            for (final ParameterBinding binding : bindings) {
                if (binding.family() != ParameterBindingFamily.BLEND_SHAPE || binding.points().size() < 3) continue;
                final ParameterBindingTarget target =
                    ParameterBindingTarget.artMesh(new ArtMeshId(drawable.id().value()));
                return new MorphOwner(target, binding.parameterId(), binding);
            }
        }
        return null;
    }
    private Owner discoverOwner(final CubismModel model) throws Exception {
        final List<Deformer> deformers = onHostThread(model.deformers()::all);
        final Set<String> warpIds = onHostThread(() -> model.warpDeformers().all().stream()
            .map(value -> value.id().value()).collect(Collectors.toSet()));
        final Set<String> rotationIds = onHostThread(() -> model.rotationDeformers().all().stream()
            .map(value -> value.id().value()).collect(Collectors.toSet()));
        for (final Deformer deformer : deformers) {
            final List<ParameterBinding> bindings = readBindings(() -> deformer.getParameterBindings());
            if (bindings == null || bindings.isEmpty()) {
                continue;
            }
            final String id = deformer.id().value();
            if (rotationIds.contains(id)) {
                return owner(ParameterBindingTarget.rotationDeformer(new DeformerId(id)), model, bindings);
            }
            if (warpIds.contains(id)) {
                return owner(ParameterBindingTarget.warpDeformer(new DeformerId(id)), model, bindings);
            }
        }
        final List<Drawable> drawables = onHostThread(model.drawables()::all);
        for (final Drawable drawable : drawables) {
            final List<ParameterBinding> bindings = readBindings(() -> drawable.getParameterBindings());
            if (bindings == null || bindings.isEmpty()) {
                continue;
            }
            return owner(
                ParameterBindingTarget.artMesh(new ArtMeshId(drawable.id().value())),
                model,
                bindings
            );
        }
        return null;
    }

    private Owner owner(
        final ParameterBindingTarget target,
        final CubismModel model,
        final List<ParameterBinding> bindings
    ) throws Exception {
        final List<BoundSnapshot> bound = new ArrayList<>();
        for (final ParameterBinding binding : bindings) {
            final Parameter parameter = onHostThread(() -> model.parameters().all().stream()
                .filter(value -> value.id().equals(binding.parameterId()))
                .findFirst().orElse(null));
            bound.add(BoundSnapshot.of(parameter, binding));
        }
        return new Owner(target, bound);
    }

    private List<ParameterBinding> readBindings(final Callable<List<ParameterBinding>> reader) {
        try {
            return onHostThread(reader);
        } catch (RuntimeException unsupported) {
            return null;
        } catch (Exception failure) {
            logger.warn("PBT_BINDING_READ_FAILED " + singleLine(failure));
            return null;
        }
    }

    private List<BoundSnapshot> targetCandidates(
        final List<BoundSnapshot> candidates,
        final List<BoundSnapshot> bound,
        final BoundSnapshot source
    ) {
        final Set<ParameterId> boundElsewhere = bound.stream()
            .map(BoundSnapshot::parameterId)
            .filter(id -> !id.equals(source.parameterId()))
            .collect(Collectors.toSet());
        // A morph source is offered only morph targets; a non-morph source
        // (normal or combined) is offered every non-morph target.
        return candidates.stream()
            .filter(candidate -> candidate.morph() == source.morph())
            .filter(candidate -> !boundElsewhere.contains(candidate.parameterId()))
            .sorted(Comparator.comparing(candidate -> candidate.label().toLowerCase(Locale.ROOT)))
            .toList();
    }

    private static boolean isSortedByLowerLabel(final List<BoundSnapshot> values) {
        for (int index = 1; index < values.size(); index++) {
            final String previous = values.get(index - 1).label().toLowerCase(Locale.ROOT);
            final String current = values.get(index).label().toLowerCase(Locale.ROOT);
            if (previous.compareTo(current) > 0) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Binding reads
    // ------------------------------------------------------------------

    private List<Float> bindingValues(
        final CubismModel model,
        final ParameterId parameterId,
        final ParameterBindingTarget target
    ) throws Exception {
        return onHostThread(() -> model.parameters().all().stream()
            .filter(parameter -> parameter.id().equals(parameterId))
            .findFirst()
            .map(parameter -> parameter.getParameterBindings().stream()
                .filter(binding -> binding.target().equals(target))
                .findFirst()
                .map(binding -> binding.points().stream()
                    .map(ParameterBindingPoint::value)
                    .toList())
                .orElseGet(List::of))
            .orElseGet(List::of));
    }

    private List<Float> awaitBindingValues(
        final CubismModel model,
        final ParameterId parameterId,
        final ParameterBindingTarget target,
        final List<Float> expected
    ) throws Exception {
        final long deadline = System.currentTimeMillis() + VALUE_AWAIT_MAX_MILLIS;
        List<Float> actual = bindingValues(model, parameterId, target);
        while (!actual.equals(expected) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100L);
            actual = bindingValues(model, parameterId, target);
        }
        return actual;
    }

    private ParameterRange parameterRange(
        final CubismModel model,
        final ParameterId parameterId
    ) throws Exception {
        return onHostThread(() -> {
            final Parameter parameter = findParameter(model, parameterId);
            return new ParameterRange(parameter.getMinimumValue(), parameter.getMaximumValue());
        });
    }

    private List<Object> captureStates(
        final CubismModel model,
        final ParameterBindingTarget owner,
        final ParameterId parameterId,
        final List<Float> values
    ) throws Exception {
        final ArrayList<Object> states = new ArrayList<>(values.size());
        for (final float value : values) {
            states.add(stateAt(model, owner, parameterId, value));
        }
        return List.copyOf(states);
    }

    private void setParameterValue(
        final CubismModel model,
        final ParameterId parameterId,
        final float value
    ) throws Exception {
        onHostThread(() -> {
            findParameter(model, parameterId).setValue(value);
            return null;
        });
    }

    private List<RangeCandidate> rangeCandidates(
        final CubismModel model,
        final ParameterBindingTarget owner,
        final List<BoundSnapshot> candidates,
        final ParameterId source
    ) throws Exception {
        final ArrayList<RangeCandidate> result = new ArrayList<>();
        for (final BoundSnapshot candidate : candidates) {
            if (candidate.parameterId().equals(source)) continue;
            if (!bindingValues(model, candidate.parameterId(), owner).isEmpty()) continue;
            result.add(new RangeCandidate(candidate, parameterRange(model, candidate.parameterId())));
        }
        result.sort(
            Comparator.comparingInt((RangeCandidate candidate) -> isZeroOne(candidate.range()) ? 0 : 1)
                .thenComparing(candidate -> candidate.snapshot().label().toLowerCase(Locale.ROOT))
        );
        return List.copyOf(result);
    }

    private void assertDuplicateFailsBeforeMutation(
        final CubismModel model,
        final ParameterBindingTarget owner,
        final ParameterId source,
        final MappingCandidate candidate
    ) throws Exception {
        final ParameterId destination = candidate.candidate().snapshot().parameterId();
        final List<Float> sourceBefore = bindingValues(model, source, owner);
        final List<Float> destinationBefore = bindingValues(model, destination, owner);
        RuntimeException failure = null;
        try {
            onHostThread(() -> {
                model.parameterBindingBatch().transferClamped(new ParameterBindingTransferPlan(
                    source, destination, List.of(owner), candidate.invert()
                ));
                return null;
            });
        } catch (RuntimeException expected) {
            failure = expected;
        }
        final List<Float> sourceAfter = bindingValues(model, source, owner);
        final List<Float> destinationAfter = bindingValues(model, destination, owner);
        final boolean passed = failure instanceof IllegalStateException
            && destinationBefore.isEmpty()
            && sourceAfter.equals(sourceBefore)
            && destinationAfter.equals(destinationBefore);
        recordAssertion(
            "row.clamped.duplicate",
            "transferClamped throws before mutation and leaves source/destination bindings unchanged",
            "invert=" + candidate.invert()
                + " mappedBySource=" + candidate.mapped()
                + " exception=" + (failure == null ? "none" : singleLine(failure))
                + " sourceBefore=" + sourceBefore + " sourceAfter=" + sourceAfter
                + " destinationBefore=" + destinationBefore + " destinationAfter=" + destinationAfter,
            passed ? "PASS" : "FAIL"
        );
    }

    private static MappingCandidate findNarrowCandidate(
        final List<Float> originalPoints,
        final List<RangeCandidate> candidates,
        final Set<ParameterId> usedTargets,
        final boolean invert
    ) {
        for (int pass = 0; pass < 2; pass++) {
            final boolean skipUsed = pass == 0;
            for (final RangeCandidate candidate : candidates) {
                if (skipUsed && usedTargets.contains(candidate.snapshot().parameterId())) continue;
                final List<Float> mapped = mapPoints(originalPoints, invert, candidate.range());
                if (isUnique(mapped) && hasChanged(originalPoints, mapped)) {
                    return new MappingCandidate(candidate, invert, mapped);
                }
            }
        }
        return null;
    }

    private static MappingCandidate findCollisionCandidate(
        final List<Float> originalPoints,
        final List<RangeCandidate> candidates,
        final Set<ParameterId> usedTargets
    ) {
        for (int pass = 0; pass < 2; pass++) {
            final boolean skipUsed = pass == 0;
            for (final RangeCandidate candidate : candidates) {
                if (skipUsed && usedTargets.contains(candidate.snapshot().parameterId())) continue;
                for (final boolean invert : List.of(false, true)) {
                    final List<Float> mapped = mapPoints(originalPoints, invert, candidate.range());
                    if (!isUnique(mapped)) {
                        return new MappingCandidate(candidate, invert, mapped);
                    }
                }
            }
        }
        return null;
    }

    private static List<Float> mapPoints(
        final List<Float> sourcePoints,
        final boolean invert,
        final ParameterRange range
    ) {
        final ArrayList<Float> mapped = new ArrayList<>(sourcePoints.size());
        for (final float value : sourcePoints) {
            final float normalized = value == 0.0F ? 0.0F : value;
            final float requested = invert ? -normalized + 0.0F : normalized;
            final float result = clamp(requested, range.minimum(), range.maximum());
            mapped.add(result == 0.0F ? 0.0F : result);
        }
        return List.copyOf(mapped);
    }

    private static List<Float> sorted(final List<Float> values) {
        final ArrayList<Float> result = new ArrayList<>(values);
        result.sort(Float::compare);
        return List.copyOf(result);
    }

    private static boolean fitsInRange(
        final List<Float> sourcePoints,
        final boolean invert,
        final ParameterRange range
    ) {
        for (final float value : sourcePoints) {
            final float normalized = value == 0.0F ? 0.0F : value;
            final float requested = invert ? -normalized + 0.0F : normalized;
            if (requested < range.minimum() || requested > range.maximum()) return false;
        }
        return true;
    }

    private static boolean isUnique(final List<Float> values) {
        for (int index = 0; index < values.size(); index++) {
            for (int other = 0; other < index; other++) {
                if (near(values.get(index), values.get(other))) return false;
            }
        }
        return true;
    }

    private static boolean arePairwiseDistinct(final List<?> values) {
        for (int index = 0; index < values.size(); index++) {
            for (int other = 0; other < index; other++) {
                if (Objects.equals(values.get(index), values.get(other))) return false;
            }
        }
        return true;
    }

    private static boolean hasChanged(final List<Float> before, final List<Float> after) {
        for (int index = 0; index < before.size(); index++) {
            if (Float.compare(before.get(index), after.get(index)) != 0) return true;
        }
        return false;
    }

    private static boolean near(final float left, final float right) {
        return Math.abs(left - right) <= DUPLICATE_EPSILON;
    }

    private static boolean isZeroOne(final ParameterRange range) {
        return Float.compare(range.minimum(), 0.0F) == 0
            && Float.compare(range.maximum(), 1.0F) == 0;
    }

    private static float clamp(final float value, final float minimum, final float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private record ParameterRange(float minimum, float maximum) {
        private ParameterRange {
            if (!Float.isFinite(minimum) || !Float.isFinite(maximum) || minimum > maximum) {
                throw new IllegalStateException("invalid destination parameter range");
            }
        }
    }

    private record RangeCandidate(BoundSnapshot snapshot, ParameterRange range) {
    }

    private record MappingCandidate(
        RangeCandidate candidate,
        boolean invert,
        List<Float> mapped
    ) {
    }

    // ------------------------------------------------------------------
    // Undo/Redo shortcut (house pattern: Swing accelerator first, Robot fallback)
    // ------------------------------------------------------------------

    private static void pressShortcut(final Robot robot, final int key) throws Exception {
        if (invokeMenuShortcut(key)) {
            Thread.sleep(250L);
            return;
        }
        final AtomicReference<java.awt.Frame> hostFrame = new AtomicReference<>();
        invokeOnEdt(() -> {
            java.awt.Frame fallback = null;
            for (java.awt.Frame frame : java.awt.Frame.getFrames()) {
                if (!frame.isVisible()) continue;
                if (fallback == null) fallback = frame;
                final String title = frame.getTitle();
                if (title != null && title.contains(".cmo3")) {
                    hostFrame.set(frame);
                    break;
                }
                if (hostFrame.get() == null && title != null && title.contains("Cubism")) {
                    hostFrame.set(frame);
                }
            }
            if (hostFrame.get() == null) hostFrame.set(fallback);
            final java.awt.Frame frame = hostFrame.get();
            if (frame != null) {
                frame.setState(java.awt.Frame.NORMAL);
                frame.toFront();
                frame.requestFocus();
            }
        });
        final java.awt.Frame frame = hostFrame.get();
        if (frame != null) {
            final java.awt.Rectangle bounds = frame.getBounds();
            robot.mouseMove(bounds.x + Math.max(20, bounds.width / 2), bounds.y + 12);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        }
        Thread.sleep(400L);
        robot.keyPress(KeyEvent.VK_CONTROL);
        robot.keyPress(key);
        robot.keyRelease(key);
        robot.keyRelease(KeyEvent.VK_CONTROL);
        Thread.sleep(250L);
    }

    private static boolean invokeMenuShortcut(final int key) throws Exception {
        final AtomicReference<javax.swing.JMenuItem> match = new AtomicReference<>();
        final AtomicBoolean enabled = new AtomicBoolean();
        invokeOnEdt(() -> {
            for (java.awt.Frame frame : java.awt.Frame.getFrames()) {
                if (!(frame instanceof javax.swing.JFrame swingFrame) || !frame.isVisible()) continue;
                final javax.swing.JMenuBar bar = swingFrame.getJMenuBar();
                if (bar == null) continue;
                for (int index = 0; index < bar.getMenuCount() && match.get() == null; index++) {
                    findMenuShortcut(bar.getMenu(index), key, match);
                }
            }
            final javax.swing.JMenuItem item = match.get();
            enabled.set(item != null && item.isEnabled());
            if (enabled.get()) item.doClick(0);
        });
        return enabled.get();
    }

    private static void findMenuShortcut(
        final javax.swing.JMenuItem item,
        final int key,
        final AtomicReference<javax.swing.JMenuItem> match
    ) {
        if (item == null || match.get() != null) return;
        final javax.swing.KeyStroke accelerator = item.getAccelerator();
        if (accelerator != null && accelerator.getKeyCode() == key
            && (accelerator.getModifiers() & InputEvent.CTRL_DOWN_MASK) != 0) {
            match.set(item);
            return;
        }
        if (item instanceof javax.swing.JMenu menu) {
            for (int index = 0; index < menu.getMenuComponentCount() && match.get() == null; index++) {
                final java.awt.Component component = menu.getMenuComponent(index);
                if (component instanceof javax.swing.JMenuItem child) {
                    findMenuShortcut(child, key, match);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Host-thread bridge
    // ------------------------------------------------------------------

    private static void invokeOnEdt(final Runnable operation) throws Exception {
        Objects.requireNonNull(operation, "operation");
        if (SwingUtilities.isEventDispatchThread()) {
            operation.run();
            return;
        }
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeLater(() -> {
            try {
                operation.run();
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                completed.countDown();
            }
        });
        try {
            if (!completed.await(EDT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(
                    "EDT operation timed out after " + EDT_TIMEOUT_MILLIS + " ms"
                );
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        final Throwable throwable = failure.get();
        if (throwable != null) {
            throw new InvocationTargetException(throwable);
        }
    }

    private <T> T onHostThread(final Callable<T> operation) throws Exception {
        final AtomicReference<T> result = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        if (SwingUtilities.isEventDispatchThread()) {
            return operation.call();
        }
        invokeOnEdt(() -> {
            try {
                result.set(operation.call());
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        if (failure.get() != null) {
            if (failure.get() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure.get() instanceof Exception checked) {
                throw checked;
            }
            throw new IllegalStateException(failure.get());
        }
        return result.get();
    }

    private CubismModel awaitActiveModel() throws Exception {
        final long deadline = System.currentTimeMillis() + MODEL_AWAIT_MAX_MILLIS;
        Exception lastFailure = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                final CubismModel model = onHostThread(() -> context.cubism().model().active());
                final boolean hasDrawables = onHostThread(() -> !model.drawables().all().isEmpty());
                if (model != null && hasDrawables) {
                    return model;
                }
            } catch (Exception exception) {
                lastFailure = exception;
            }
            Thread.sleep(1_000L);
        }
        throw new IllegalStateException(
            "No active model with drawables was observed within " + MODEL_AWAIT_MAX_MILLIS + " ms",
            lastFailure
        );
    }

    // ------------------------------------------------------------------
    // Evidence
    // ------------------------------------------------------------------

    private void recordAssertion(final String name, final Object expected, final Object actual, final String status) {
        assertions.add(new Assertion(name, singleLine(expected), singleLine(actual), status));
    }

    private static String singleLine(final Object value) {
        if (value == null) {
            return "null";
        }
        final String text = value.toString().replace('\n', ' ').replace('\r', ' ');
        return text.length() > 4000 ? text.substring(0, 4000) : text;
    }

    private String computeTerminal() {
        boolean anyFail = false;
        boolean anyBlocked = false;
        for (final Assertion assertion : assertions) {
            if ("FAIL".equals(assertion.status())) {
                anyFail = true;
            } else if ("BLOCKED".equals(assertion.status())) {
                anyBlocked = true;
            }
        }
        return anyFail ? "FAIL" : anyBlocked ? "BLOCKED" : "PASS";
    }

    private void writeResultFile(final long startedNanos) {
        final Path result = stateDir.getParent().resolve("parameter-batch-transfer-validation-result.properties");
        try {
            final StringBuilder report = new StringBuilder()
                .append("schemaVersion=1\n")
                .append("runId=").append(System.getProperty("turboism.validation.runId", "unknown")).append('\n')
                .append("pluginId=dev.turboism.validation.parameter-batch-transfer\n")
                .append("hostVersion=").append(System.getProperty("turboism.validation.hostVersion", "unknown")).append('\n')
                .append("fixtureName=").append(System.getProperty("turboism.validation.fixtureName", "unknown")).append('\n')
                .append("modelId=").append(modelId).append('\n')
                .append("ownerType=").append(ownerType).append('\n')
                .append("ownerId=").append(ownerId).append('\n')
                .append("boundCount=").append(boundCount).append('\n')
                .append("sourceParameter=").append(sourceParameter).append('\n')
                .append("targetParameter=").append(targetParameter).append('\n')
                .append("durationMillis=").append((System.nanoTime() - startedNanos) / 1_000_000L).append('\n');
            for (final Assertion assertion : assertions) {
                report.append("assertion.").append(assertion.name()).append(".expected=")
                    .append(assertion.expected()).append('\n')
                    .append("assertion.").append(assertion.name()).append(".actual=")
                    .append(assertion.actual()).append('\n')
                    .append("assertion.").append(assertion.name()).append(".status=")
                    .append(assertion.status()).append('\n');
            }
            report.append("status=").append(computeTerminal()).append('\n');
            Files.createDirectories(result.getParent());
            Files.writeString(result, report.toString(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            logger.info("PBT_RESULT_WRITTEN result=" + result
                + " status=" + computeTerminal() + " assertions=" + assertions.size());
        } catch (Exception failure) {
            logger.error("PBT_RESULT_WRITE_FAILED result=" + result + " " + singleLine(failure));
        }
    }

    private record Assertion(String name, String expected, String actual, String status) {
    }

    /** One parameter source snapshot (mirrors BoundParameterSnapshot semantics). */
    private record BoundSnapshot(
        Parameter parameter,
        ParameterId parameterId,
        String label,
        String markers,
        boolean morph,
        boolean combined
    ) {
        static BoundSnapshot of(final Parameter parameter, final ParameterBinding binding) {
            if (parameter == null) {
                final ParameterId id = binding == null ? null : binding.parameterId();
                return new BoundSnapshot(null, id, id == null ? "" : id.value(), "", false, false);
            }
            final String label = parameter.name().filter(value -> !value.isBlank())
                .orElse(parameter.id().value());
            final StringBuilder markers = new StringBuilder();
            if (parameter.type() == ParameterType.BLEND_SHAPE) {
                markers.append('M');
            }
            if (parameter.combined().orElse(false)) {
                markers.append('C');
            }
            return new BoundSnapshot(
                parameter,
                parameter.id(),
                label,
                markers.toString(),
                parameter.type() == ParameterType.BLEND_SHAPE,
                parameter.combined().orElse(false)
            );
        }
    }

    private record MorphOwner(
        ParameterBindingTarget target,
        ParameterId source,
        ParameterBinding binding
    ) {
        MorphOwner {
            target = Objects.requireNonNull(target, "target");
            source = Objects.requireNonNull(source, "source");
            binding = Objects.requireNonNull(binding, "binding");
        }
    }

    private record MorphRowPlan(
        String prefix,
        ParameterBindingTarget target,
        ParameterId source,
        ParameterId destination,
        boolean invert,
        List<Float> sourcePoints,
        List<Float> expectedDestination,
        ParameterRange range
    ) {
        MorphRowPlan {
            prefix = Objects.requireNonNull(prefix, "prefix");
            target = Objects.requireNonNull(target, "target");
            source = Objects.requireNonNull(source, "source");
            destination = Objects.requireNonNull(destination, "destination");
            sourcePoints = List.copyOf(Objects.requireNonNull(sourcePoints, "sourcePoints"));
            expectedDestination = List.copyOf(Objects.requireNonNull(expectedDestination, "expectedDestination"));
            range = Objects.requireNonNull(range, "range");
        }
    }

    /** Discovered owner object with its bound-parameter snapshots. */
    private record Owner(ParameterBindingTarget target, List<BoundSnapshot> bound) {
        Owner {
            target = Objects.requireNonNull(target, "target");
            bound = List.copyOf(Objects.requireNonNull(bound, "bound"));
        }
    }

    private record MorphFixtureSetup(
        MorphOwner owner,
        Object rawOwner,
        Object rawSet,
        Object rawModelSource,
        List<Object> addedTargets,
        List<ParameterRestore> restores,
        List<Float> originalPoints,
        List<Object> originalTargets,
        boolean originalBindingPresent,
        boolean fallback,
        ScaffoldState scaffold
    ) {
        MorphFixtureSetup {
            addedTargets = List.copyOf(Objects.requireNonNull(addedTargets, "addedTargets"));
            restores = List.copyOf(Objects.requireNonNull(restores, "restores"));
            originalPoints = List.copyOf(Objects.requireNonNull(originalPoints, "originalPoints"));
            originalTargets = List.copyOf(Objects.requireNonNull(originalTargets, "originalTargets"));
        }

        static MorphFixtureSetup none() {
            return new MorphFixtureSetup(null, null, null, null, List.of(), List.of(), List.of(), List.of(), false, false, null);
        }

        static MorphFixtureSetup natural(final MorphOwner owner) {
            return new MorphFixtureSetup(owner, null, null, null, List.of(), List.of(), List.of(), List.of(), false, false, null);
        }
    }

    private record ParameterRestore(
        Parameter parameter,
        ParameterDefinition definition,
        float value
    ) {
    }

    private record ParameterCandidate(
        ParameterId id,
        ParameterType type,
        boolean combined,
        boolean hasBindings,
        ParameterDefinition definition,
        float value
    ) {
    }

    private record GridKeySnapshot(Object formGuid, Object parameterGuid, float value) {
    }

    private record ScaffoldBaseline(
        List<RawGridEntry> entries,
        Map<Object, float[]> formPositions
    ) {
        ScaffoldBaseline {
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
            final Map<Object, float[]> copiedPositions = new LinkedHashMap<>();
            for (final Map.Entry<Object, float[]> entry : Objects.requireNonNull(formPositions, "formPositions").entrySet()) {
                copiedPositions.put(
                    Objects.requireNonNull(entry.getKey(), "formGuid"),
                    Objects.requireNonNull(entry.getValue(), "positions").clone()
                );
            }
            formPositions = Map.copyOf(copiedPositions);
        }

        List<GridKeySnapshot> gridKeys() {
            return flattenGridEntries(entries).stream()
                .map(keyform -> new GridKeySnapshot(keyform.formGuid(), keyform.parameterGuid(), keyform.value()))
                .toList();
        }
    }

    private record ScaffoldSource(
        MorphOwner owner,
        Object rawOwner,
        Object rawSet,
        Object rawModelSource,
        Object rawGrid,
        Object sourceGuid,
        Object scaffoldGuid,
        ParameterId scaffoldParameter,
        ParameterDefinition sourceDefinition,
        float sourceValue,
        ParameterDefinition scaffoldDefinition,
        float scaffoldValue,
        List<Object> originalTargets,
        List<Float> originalPoints,
        boolean originalBindingPresent,
        ScaffoldBaseline baseline
    ) {
        ScaffoldSource {
            owner = Objects.requireNonNull(owner, "owner");
            rawOwner = Objects.requireNonNull(rawOwner, "rawOwner");
            rawSet = Objects.requireNonNull(rawSet, "rawSet");
            rawModelSource = Objects.requireNonNull(rawModelSource, "rawModelSource");
            rawGrid = Objects.requireNonNull(rawGrid, "rawGrid");
            sourceGuid = Objects.requireNonNull(sourceGuid, "sourceGuid");
            scaffoldGuid = Objects.requireNonNull(scaffoldGuid, "scaffoldGuid");
            scaffoldParameter = Objects.requireNonNull(scaffoldParameter, "scaffoldParameter");
            sourceDefinition = Objects.requireNonNull(sourceDefinition, "sourceDefinition");
            scaffoldDefinition = Objects.requireNonNull(scaffoldDefinition, "scaffoldDefinition");
            originalTargets = List.copyOf(Objects.requireNonNull(originalTargets, "originalTargets"));
            originalPoints = List.copyOf(Objects.requireNonNull(originalPoints, "originalPoints"));
            baseline = Objects.requireNonNull(baseline, "baseline");
        }
    }

    private record ScaffoldState(
        Object rawGrid,
        Object scaffoldGuid,
        ParameterId scaffoldParameter,
        float sourceValue,
        List<Float> addedValues,
        List<Object> addedFormGuids,
        ParameterDefinition originalDefinition,
        float originalValue,
        ScaffoldBaseline baseline
    ) {
        ScaffoldState {
            rawGrid = Objects.requireNonNull(rawGrid, "rawGrid");
            scaffoldGuid = Objects.requireNonNull(scaffoldGuid, "scaffoldGuid");
            scaffoldParameter = Objects.requireNonNull(scaffoldParameter, "scaffoldParameter");
            addedValues = List.copyOf(Objects.requireNonNull(addedValues, "addedValues"));
            addedFormGuids = List.copyOf(Objects.requireNonNull(addedFormGuids, "addedFormGuids"));
            originalDefinition = Objects.requireNonNull(originalDefinition, "originalDefinition");
            baseline = Objects.requireNonNull(baseline, "baseline");
        }
    }
    private record DefinitionPlan(ParameterRestore restore, ParameterDefinition definition) {
    }

    private record DefinitionPlans(
        List<DefinitionPlan> plans,
        ParameterRange wideRange,
        ParameterRange narrowRange
    ) {
        DefinitionPlans {
            plans = List.copyOf(Objects.requireNonNull(plans, "plans"));
        }
    }

    private record RawOwner(
        Object rawOwner,
        Object rawSet,
        Object rawModelSource,
        Object sourceGuid
    ) {
    }

    private record RawParameter(Object source, Object guid) {
    }

    private record RawGridEntry(
        Object formGuid,
        Object form,
        float[] positions,
        List<RawKeyform> keyforms
    ) {
        RawGridEntry {
            formGuid = Objects.requireNonNull(formGuid, "formGuid");
            form = Objects.requireNonNull(form, "form");
            positions = Objects.requireNonNull(positions, "positions").clone();
            keyforms = List.copyOf(Objects.requireNonNull(keyforms, "keyforms"));
        }
    }

    private record RawKeyform(
        Object formGuid,
        Object form,
        Object parameterGuid,
        float value
    ) {
    }

    private record FallbackSource(
        MorphOwner owner,
        Object rawOwner,
        Object rawSet,
        Object rawModelSource,
        Object sourceGuid,
        List<Object> originalTargets,
        List<RawKeyform> selectedKeys,
        List<Float> originalPoints,
        boolean originalBindingPresent
    ) {
        FallbackSource {
            originalTargets = List.copyOf(Objects.requireNonNull(originalTargets, "originalTargets"));
            selectedKeys = List.copyOf(Objects.requireNonNull(selectedKeys, "selectedKeys"));
            originalPoints = List.copyOf(Objects.requireNonNull(originalPoints, "originalPoints"));
        }
    }
}
