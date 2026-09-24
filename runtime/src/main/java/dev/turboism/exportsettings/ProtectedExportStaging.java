package dev.turboism.exportsettings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.sdk.cubism.core.MocData;
import dev.turboism.sdk.cubism.core.MocLoader;
import dev.turboism.sdk.cubism.core.OwnedCanvasInfo;
import dev.turboism.sdk.cubism.core.OwnedMoc;
import dev.turboism.sdk.cubism.core.OwnedModel;

import java.io.File;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Staged-output validation and all-or-nothing publication for protected export.
 *
 * <p>The native worker writes wholly into a task-owned staging directory; this class is
 * the publication boundary. Validation requires every reported output to be a non-empty
 * regular file under the staged pick's parent directory, every {@code .moc3} to load
 * through the verified Core runtime, and every {@code *.model3.json} FileReferences
 * entry to resolve inside staging. Only after validation does {@link #publish} copy the
 * complete set to the user's real destination.</p>
 */
public final class ProtectedExportStaging {

    /**
     * Expected per-parameter contract of the exported model. Captured from the bound
     * copy's source census before any mutation; the staged moc3 must reproduce every
     * field, so a flatten or export that altered a parameter's evaluable contract
     * (range, default, repeat flag, or the baked key positions) is rejected rather
     * than published.
     */
    public record ParameterExpectation(
        String id,
        float minimumValue,
        float maximumValue,
        float defaultValue,
        Boolean repeat,
        List<Float> keys
    ) {
        public ParameterExpectation {
            id = Objects.requireNonNull(id, "id");
            keys = keys == null ? List.of() : List.copyOf(keys);
        }
    }

    /** Move primitive — {@code Files.move} in production, replaceable in tests. */
    interface MoveOp {
        void move(Path source, Path target) throws IOException;
    }

    /**
     * Suppressed diagnostic attached to a publish failure when the rollback
     * could not prove full restoration: carries the retained scratch directory
     * verbatim so a report can surface a usable recovery location regardless of
     * generic diagnostic truncation or suppressed-entry caps.
     */
    static final class RetainedRecoveryException extends IOException {
        private static final long serialVersionUID = 1L;
        private final String retainedPath;

        RetainedRecoveryException(final Path retainedPath) {
            super("protected-export rollback incomplete; recovery material retained at "
                + retainedPath);
            this.retainedPath = retainedPath.toString();
        }

        String retainedPath() {
            return retainedPath;
        }
    }

    /**
     * Runtime-private parameter write seam on an owned Core model. Production
     * wires the adapter's owned-Moc runtime; the public SDK projection stays
     * read-only. Implementations must throw when the model is foreign, closed,
     * or the parameter is absent.
     */
    public interface CoreParameterWriter {
        void writeParameterValue(OwnedModel model, String parameterId, float value);
    }

    /** One deterministic sample step: set {@code parameterId} to {@code value}. */
    public record BehaviorSample(String parameterId, float value) {
        public BehaviorSample {
            parameterId = Objects.requireNonNull(parameterId, "parameterId");
            if (parameterId.isBlank()) {
                throw new IllegalArgumentException("parameterId must not be blank");
            }
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("value must be finite");
            }
        }
    }

    /**
     * Host-side evaluated geometry captured on the disposable copy under a
     * caller-chosen stable key: the pre-flatten snapshot keys frames by the
     * authored ArtMesh source GUID, the post-flatten/post-obfuscation snapshot
     * (the exact state the native exporter serializes) keys frames by the
     * planned drawable-ID token. {@code baseline} holds positions at parameter
     * defaults; {@code frames} holds, per sample step in {@code samples},
     * key → evaluated vertex positions. Validation replays the identical
     * sequence on the staged {@code .moc3} through the owned Core runtime and
     * compares element-wise.
     */
    public record BehaviorSnapshot(
        List<BehaviorSample> samples,
        Map<String, float[]> baseline,
        List<Map<String, float[]>> frames
    ) {
        public BehaviorSnapshot {
            samples = samples == null ? List.of() : List.copyOf(samples);
            baseline = baseline == null ? Map.of() : Map.copyOf(baseline);
            frames = frames == null ? List.of() : List.copyOf(frames);
            if (frames.size() != samples.size()) {
                throw new IllegalArgumentException(
                    "frames must align with samples");
            }
        }
    }

    /** Outcome of a staged-output validation. */
    public record Validation(
        boolean valid,
        String failureKey,
        String failureDetail,
        List<Path> stagedFiles
    ) {
        static Validation ok(final List<Path> files) {
            return new Validation(true, null, null, List.copyOf(files));
        }

        static Validation rejected(final String failureKey) {
            return rejected(failureKey, null);
        }

        static Validation rejected(final String failureKey,
            final String failureDetail
        ) {
            return new Validation(false, failureKey, failureDetail, List.of());
        }
    }

    /** Per-element absolute tolerance for evaluated-geometry comparison. */
    private static final float BEHAVIOR_TOLERANCE = 1e-3f;

    private final MocLoader mocLoader;
    private final CoreParameterWriter parameterWriter;
    private final MoveOp moveOp;
    private final ObjectMapper json = new ObjectMapper();

    public ProtectedExportStaging(final MocLoader mocLoader) {
        this(mocLoader, null);
    }

    public ProtectedExportStaging(
        final MocLoader mocLoader,
        final CoreParameterWriter parameterWriter
    ) {
        this(mocLoader, parameterWriter,
            (source, target) -> Files.move(source, target,
                StandardCopyOption.REPLACE_EXISTING));
    }

    /** Test seam: drives the atomic publish through an injected move. */
    ProtectedExportStaging(
        final MocLoader mocLoader,
        final CoreParameterWriter parameterWriter,
        final MoveOp moveOp
    ) {
        this.mocLoader = mocLoader; // may be null; validated lazily when a moc3 is staged
        this.parameterWriter = parameterWriter; // null disables the behavior oracle
        this.moveOp = Objects.requireNonNull(moveOp, "moveOp");
    }

    /**
     * Validates the native worker's staged output.
     *
     * <p>Beyond file integrity, the loaded moc3 must prove the session's identity
     * contract: every exported drawable ID is one of the planned obfuscation tokens,
     * parameter and part IDs exactly match the copy's census, and no deformer survives
     * the flatten pass into the output.</p>
     *
     * @param stagedPick the redirected staging {@code File} the worker wrote beside
     * @param reportedPaths absolute output paths reported by the native callback
     * @param expectedDrawableIds planned obfuscation tokens; staged drawable IDs must
     *     be a subset (unplaced ArtMeshes are legitimately dropped by the exporter)
     * @param expectedParameters parameter ID → expected contract (range, default,
     *     repeat, baked key positions); staged parameters must match every field
     * @param expectedPartIds the copy's part ID set; staged must equal it
     * @param expectedGlueIds the copy's Glue ID set; staged must carry them
     *     verbatim — Glue is pass-through, never re-identified
     * @param expectedPhysicsIds the copy's physics setting ID set; when the
     *     native flow emitted a {@code physics3.json} (the user's own physics
     *     output checkbox governs whether it is written at all) its setting IDs
     *     must equal the census set — no drops, no extras
     * @param behavior host-side evaluated-geometry oracle captured post-flatten;
     *     when non-null the staged model must reproduce it under the identical
     *     parameter-sample replay, or validation fails closed
     */
    public Validation validate(
        final File stagedPick,
        final List<String> reportedPaths,
        final Set<String> expectedDrawableIds,
        final Map<String, ParameterExpectation> expectedParameters,
        final Set<String> expectedPartIds,
        final Set<String> expectedGlueIds,
        final Set<String> expectedPhysicsIds,
        final BehaviorSnapshot behavior
    ) {
        if (stagedPick == null || reportedPaths == null || reportedPaths.isEmpty()) {
            return Validation.rejected("protected-export.staging-empty");
        }
        final Path stagedParent = stagedPick.toPath().toAbsolutePath().normalize()
            .getParent();
        if (stagedParent == null) {
            return Validation.rejected("protected-export.staging-root-missing");
        }

        final Set<Path> staged = new LinkedHashSet<>();
        for (String reported : reportedPaths) {
            final Path path;
            try {
                path = Path.of(reported).toAbsolutePath().normalize();
            } catch (RuntimeException failure) {
                return Validation.rejected("protected-export.staging-path-invalid");
            }
            if (!path.startsWith(stagedParent)) {
                return Validation.rejected("protected-export.staging-path-escape");
            }
            try {
                if (!Files.isRegularFile(path) || Files.size(path) <= 0L) {
                    return Validation.rejected("protected-export.staging-file-missing");
                }
            } catch (IOException failure) {
                return Validation.rejected("protected-export.staging-file-unreadable");
            }
            staged.add(path);
        }

        boolean sawMoc = false;
        for (Path path : staged) {
            final String name = path.getFileName().toString();
            if (name.endsWith(".moc3")) {
                sawMoc = true;
                final MocFailure failure = validateMoc(
                    path, expectedDrawableIds, expectedParameters, expectedPartIds,
                    expectedGlueIds, behavior);
                if (failure != null) {
                    return Validation.rejected(failure.key(), failure.detail());
                }
            } else if (name.endsWith(".model3.json")) {
                if (!validateModelJson(path, staged)) {
                    return Validation.rejected("protected-export.model3-invalid");
                }
            } else if (name.endsWith(".physics3.json")) {
                // Physics is pass-through content governed by the user's own
                // native output checkbox — when a physics3.json was staged, its
                // setting IDs must exactly reproduce the censused set.
                final Set<String> physicsIds = physicsSettingIds(path);
                if (physicsIds == null) {
                    return Validation.rejected("protected-export.physics3-invalid");
                }
                if (!physicsIds.equals(expectedPhysicsIds)) {
                    return Validation.rejected(
                        "protected-export.physics3-ids",
                        setDiffDetail(physicsIds, expectedPhysicsIds));
                }
            }
        }
        if (!sawMoc) {
            return Validation.rejected("protected-export.moc3-missing");
        }
        return Validation.ok(new ArrayList<>(staged));
    }

    /**
     * Loads the staged moc3 through the verified Core runtime and asserts the session's
     * identity contract materialized: drawable IDs ⊆ planned obfuscation tokens,
     * parameter/part IDs exactly preserved, zero deformers left after flatten, and
     * every staged parameter's evaluable contract (range, default, repeat, baked key
     * positions) identical to the pre-mutation census.
     */
    private MocFailure validateMoc(
        final Path path,
        final Set<String> expectedDrawableIds,
        final Map<String, ParameterExpectation> expectedParameters,
        final Set<String> expectedPartIds,
        final Set<String> expectedGlueIds,
        final BehaviorSnapshot behavior
    ) {
        if (mocLoader == null) {
            return new MocFailure("protected-export.moc3-loader-absent", null);
        }
        try {
            final byte[] bytes = Files.readAllBytes(path);
            try (OwnedMoc moc = mocLoader.load(MocData.copyOf(bytes))) {
                if (moc == null) {
                    return new MocFailure("protected-export.moc3-null", null);
                }
                try (var model = moc.instantiateModel()) {
                    if (model == null) {
                        return new MocFailure(
                            "protected-export.moc3-model-null", null);
                    }
                    final Set<String> drawableIds = model.drawables().stream()
                        .map(d -> d.id())
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                    if (!expectedDrawableIds.containsAll(drawableIds)) {
                        return new MocFailure(
                            "protected-export.moc3-drawable-ids",
                            "unexpected=" + bounded(diff(drawableIds,
                                expectedDrawableIds)));
                    }
                    final Set<String> parameterIds = model.parameters().stream()
                        .map(p -> p.id())
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                    if (!parameterIds.equals(expectedParameters.keySet())) {
                        return new MocFailure(
                            "protected-export.moc3-parameter-ids",
                            setDiffDetail(parameterIds, expectedParameters.keySet()));
                    }
                    final MocFailure contract = validateParameterContracts(
                        model.parameters(), expectedParameters);
                    if (contract != null) {
                        return contract;
                    }
                    final MocFailure behaviorDrift =
                        validateBehavior(model, behavior);
                    if (behaviorDrift != null) {
                        return behaviorDrift;
                    }
                    final Set<String> partIds = model.parts().stream()
                        .map(p -> p.id())
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                    if (!partIds.equals(expectedPartIds)) {
                        return new MocFailure(
                            "protected-export.moc3-part-ids",
                            setDiffDetail(partIds, expectedPartIds));
                    }
                    if (!model.deformers().isEmpty()) {
                        final Set<String> deformerIds = model.deformers().stream()
                            .map(d -> d.id())
                            .collect(Collectors.toCollection(LinkedHashSet::new));
                        return new MocFailure(
                            "protected-export.moc3-deformers-remain",
                            "remaining=" + bounded(deformerIds));
                    }
                    // Glue is pass-through: the staged artifact must carry every
                    // censused Glue under its unchanged ID — no drops, no extras.
                    final Set<String> glueIds = model.glues().stream()
                        .map(g -> g.id())
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                    if (!glueIds.equals(expectedGlueIds)) {
                        return new MocFailure(
                            "protected-export.moc3-glue-ids",
                            setDiffDetail(glueIds, expectedGlueIds));
                    }
                    return null;
                }
            }
        } catch (Throwable failure) {
            final String message = failure.getMessage();
            return new MocFailure(
                "protected-export.moc3-invalid",
                failure.getClass().getSimpleName() + ": "
                    + (message == null
                        ? ""
                        : message.substring(0, Math.min(160, message.length()))));
        }
    }

    /**
     * Per-parameter behavior check: the staged moc3 must carry the same evaluable
     * contract the copy's source census recorded — range, default and repeat flag
     * are exact; key positions are asserted one-sided (staged ⊆ the post-mutation
     * authored union) because authored positions bound on carriers that do not
     * serialize into moc3 legitimately drop from the output.
     * Parameters without recorded key positions keep the contract check only — an
     * unbound parameter may legitimately serialize a different implicit key list.
     */
    private MocFailure validateParameterContracts(
        final List<dev.turboism.sdk.cubism.core.OwnedParameter> stagedParameters,
        final Map<String, ParameterExpectation> expectedParameters
    ) {
        for (var staged : stagedParameters) {
            final ParameterExpectation expected = expectedParameters.get(staged.id());
            if (expected == null) {
                return new MocFailure(
                    "protected-export.moc3-parameter-contract",
                    "unplanned parameter id=" + staged.id());
            }
            final StringBuilder drift = new StringBuilder();
            if (Float.compare(staged.minimumValue(), expected.minimumValue()) != 0) {
                drift.append("min ").append(staged.minimumValue())
                    .append("!=").append(expected.minimumValue()).append(';');
            }
            if (Float.compare(staged.maximumValue(), expected.maximumValue()) != 0) {
                drift.append("max ").append(staged.maximumValue())
                    .append("!=").append(expected.maximumValue()).append(';');
            }
            if (Float.compare(staged.defaultValue(), expected.defaultValue()) != 0) {
                drift.append("default ").append(staged.defaultValue())
                    .append("!=").append(expected.defaultValue()).append(';');
            }
            if (expected.repeat() != null
                && staged.repeat().isPresent()
                && !staged.repeat().orElseThrow().equals(expected.repeat())) {
                drift.append("repeat ").append(staged.repeat().orElseThrow())
                    .append("!=").append(expected.repeat()).append(';');
            }
            if (!expected.keys().isEmpty()) {
                // One-sided bound: every staged key position must come from an
                // authored binding on the post-flatten copy. The reverse is not
                // asserted — authored positions bound on non-serializing
                // carriers (consumed deformers, morph content, glue/art-path
                // bindings) legitimately absent from the moc3 key list; binding
                // correctness is the behavior oracle's contract.
                final Set<Float> stagedKeys = new LinkedHashSet<>(staged.keyValues());
                final Set<Float> expectedKeys = new LinkedHashSet<>(expected.keys());
                if (!expectedKeys.containsAll(stagedKeys)) {
                    drift.append("keys unexpected=")
                        .append(bounded(floatDiff(stagedKeys, expectedKeys)))
                        .append(';');
                }
            }
            if (drift.length() > 0) {
                final String detail = "id=" + staged.id() + " " + drift;
                return new MocFailure(
                    "protected-export.moc3-parameter-contract",
                    detail.length() > 160 ? detail.substring(0, 160) : detail);
            }
        }
        return null;
    }

    /**
     * Behavior oracle: replays the exact sample sequence the host captured on
     * the disposable copy (post-flatten) against the staged model and compares
     * evaluated vertex positions element-wise within {@link #BEHAVIOR_TOLERANCE}.
     * This is the sampled-behavior proof AC08 requires beyond the structural
     * parameter contract — a flattened/baked binding that changed geometry shows
     * up as positional drift. Fail-closed: no writable parameter seam or zero
     * comparable drawables is a rejection, not a skip.
     */
    private MocFailure validateBehavior(
        final OwnedModel model,
        final BehaviorSnapshot snapshot
    ) {
        if (snapshot == null) {
            return null;
        }
        if (parameterWriter == null) {
            return new MocFailure(
                "protected-export.moc3-behavior-oracle-absent", null);
        }
        // Baseline: reset every parameter to its (contract-verified) default.
        final Map<String, Float> defaults = new java.util.LinkedHashMap<>();
        for (var parameter : model.parameters()) {
            defaults.put(parameter.id(), parameter.defaultValue());
        }
        try {
            for (Map.Entry<String, Float> entry : defaults.entrySet()) {
                parameterWriter.writeParameterValue(
                    model, entry.getKey(), entry.getValue());
            }
            model.update();
            final int[] compared = {0};
            MocFailure failure = compareFrame(
                "baseline", snapshot.baseline(), model, compared);
            if (failure != null) {
                return failure;
            }
            for (int index = 0; index < snapshot.samples().size(); index++) {
                final BehaviorSample sample = snapshot.samples().get(index);
                if (!defaults.containsKey(sample.parameterId())) {
                    return new MocFailure(
                        "protected-export.moc3-behavior-parameter-absent",
                        "id=" + sample.parameterId());
                }
                parameterWriter.writeParameterValue(
                    model, sample.parameterId(), sample.value());
                model.update();
                failure = compareFrame(
                    "sample=" + index + " param=" + sample.parameterId()
                        + " value=" + sample.value(),
                    snapshot.frames().get(index), model, compared);
                // Isolate the next sample — and never leave a sampled value
                // behind on the failure path either.
                parameterWriter.writeParameterValue(
                    model, sample.parameterId(), defaults.get(sample.parameterId()));
                if (failure != null) {
                    return failure;
                }
            }
            if (compared[0] == 0) {
                return new MocFailure(
                    "protected-export.moc3-behavior-unverifiable",
                    "no snapshot drawable present in output");
            }
            return null;
        } catch (RuntimeException failure) {
            final String message = failure.getMessage();
            return new MocFailure(
                "protected-export.moc3-behavior-eval-failed",
                failure.getClass().getSimpleName() + ": " + (message == null
                    ? "" : message.substring(0, Math.min(160, message.length()))));
        } finally {
            // Restore every touched parameter to its default so a rejected or
            // partially replayed validation cannot leave sampled values behind.
            for (Map.Entry<String, Float> entry : defaults.entrySet()) {
                try {
                    parameterWriter.writeParameterValue(
                        model, entry.getKey(), entry.getValue());
                } catch (RuntimeException ignored) {
                    // Best-effort restore: the staged model is task-owned and
                    // discarded after validation either way.
                }
            }
        }
    }

    /**
     * Compares one captured frame against the model's freshly re-projected
     * drawable positions. Drawables absent from the output were legitimately
     * dropped by the exporter and are skipped; {@code compared} accumulates the
     * number of drawables actually compared across all frames.
     */
    private MocFailure compareFrame(
        final String label,
        final Map<String, float[]> expected,
        final OwnedModel model,
        final int[] compared
    ) {
        if (expected == null || expected.isEmpty()) {
            return null;
        }
        final Map<String, List<Float>> positions = new java.util.LinkedHashMap<>();
        for (var drawable : model.drawables()) {
            positions.put(drawable.id(), drawable.vertexPositions());
        }
        // Host-evaluated frames are captured in canvas pixels; Core drawable
        // vertex positions live in origin-centered moc model space with Y
        // pointing up (canvas Y points down). Verified r35/r36:
        //   canvasX =  mocX*ppu + originX   (0.32898822*1000+500 = 828.98822)
        //   canvasY = -mocY*ppu + originY   (-0.4159348*1000+500 = 84.0652)
        final OwnedCanvasInfo canvas = model.canvasInfo();
        final float ppu = canvas.pixelsPerUnit();
        final float originX = canvas.originXPixels();
        final float originY = canvas.originYPixels();
        float worst = 0f;
        String worstDrawable = null;
        int worstIndex = -1;
        float worstExpected = 0f;
        float worstActual = 0f;
        int driftedTotal = 0;
        for (Map.Entry<String, float[]> entry : expected.entrySet()) {
            final float[] expectedPositions = entry.getValue();
            // A corrupt snapshot frame is a rejection even when the drawable
            // was legitimately dropped from the output — corrupt evidence can
            // never stand in for a comparison.
            final String invalid = invalidPositions(expectedPositions);
            if (invalid != null) {
                return new MocFailure(
                    "protected-export.moc3-behavior-drift",
                    label + " drawable=" + entry.getKey()
                        + " invalid-snapshot:" + invalid);
            }
            final List<Float> actual = positions.get(entry.getKey());
            if (actual == null) {
                continue;
            }
            if (actual.isEmpty() || (actual.size() & 1) != 0) {
                return new MocFailure(
                    "protected-export.moc3-behavior-drift",
                    label + " drawable=" + entry.getKey()
                        + " invalid-output-vertices=" + actual.size());
            }
            if (actual.size() != expectedPositions.length) {
                return new MocFailure(
                    "protected-export.moc3-behavior-drift",
                    label + " drawable=" + entry.getKey()
                        + " vertex-count " + actual.size()
                        + "!=" + expectedPositions.length);
            }
            compared[0]++;
            for (int i = 0; i < expectedPositions.length; i++) {
                final float actualValue = actual.get(i);
                if (!Float.isFinite(actualValue)) {
                    return new MocFailure(
                        "protected-export.moc3-behavior-drift",
                        label + " drawable=" + entry.getKey()
                            + " non-finite-output-index=" + i);
                }
                final float projected = (i & 1) == 0
                    ? actualValue * ppu + originX
                    : -actualValue * ppu + originY;
                final float delta = Math.abs(projected - expectedPositions[i]);
                if (delta > worst) {
                    worst = delta;
                    worstDrawable = entry.getKey();
                    worstIndex = i;
                    worstExpected = expectedPositions[i];
                    worstActual = projected;
                    driftedTotal++;
                }
            }
        }
        if (worst > BEHAVIOR_TOLERANCE) {
            return new MocFailure(
                "protected-export.moc3-behavior-drift",
                label + " drawable=" + worstDrawable + " index=" + worstIndex
                    + " expected=" + worstExpected + " actual=" + worstActual
                    + " delta=" + worst + " drifted=" + driftedTotal
                    + " canvas=" + canvas.widthPixels() + "x"
                    + canvas.heightPixels() + " origin=("
                    + canvas.originXPixels() + "," + canvas.originYPixels()
                    + ") ppu=" + canvas.pixelsPerUnit());
        }
        return null;
    }

    /**
     * Compares the pre-flatten snapshot (keyed by authored source GUID) against
     * the post-flatten/post-obfuscation snapshot (keyed by drawable token) over
     * the GUID→token mapping. Returns null when behavior is equivalent within
     * {@link #BEHAVIOR_TOLERANCE}, otherwise a bounded drift detail. Fail-closed:
     * every authored GUID must resolve and appear on the transformed side, no
     * unmapped transformed drawable is accepted, and at least one drawable must
     * actually be compared.
     */
    public static String behaviorDrift(
        final BehaviorSnapshot original,
        final BehaviorSnapshot transformed,
        final Map<String, String> guidToToken
    ) {
        if (original == null || transformed == null || guidToToken == null) {
            return "snapshot-or-mapping-absent";
        }
        if (!original.samples().equals(transformed.samples())) {
            return "sample-sequence-mismatch";
        }
        final int[] compared = {0};
        String drift = frameDrift(
            "baseline", original.baseline(), transformed.baseline(),
            guidToToken, compared);
        for (int i = 0; drift == null && i < original.frames().size(); i++) {
            drift = frameDrift(
                "sample=" + i, original.frames().get(i),
                transformed.frames().get(i), guidToToken, compared);
        }
        if (drift == null && compared[0] == 0) {
            drift = "no-comparable-drawables";
        }
        return drift;
    }

    private static String frameDrift(
        final String label,
        final Map<String, float[]> original,
        final Map<String, float[]> transformed,
        final Map<String, String> guidToToken,
        final int[] compared
    ) {
        // Coverage is driven by the plan, not by either frame's key set: a mesh
        // missing from BOTH snapshots must still reject, never be equivalent.
        for (Map.Entry<String, String> entry : guidToToken.entrySet()) {
            final String guid = entry.getKey();
            final String token = entry.getValue();
            final float[] expected = original.get(guid);
            if (expected == null) {
                return label + " guid=" + guid + " missing-original";
            }
            final float[] actual = transformed.get(token);
            if (actual == null) {
                return label + " drawable=" + token + " missing-transformed";
            }
            String invalid = invalidPositions(expected);
            if (invalid != null) {
                return label + " guid=" + guid + " invalid-original:"
                    + invalid;
            }
            invalid = invalidPositions(actual);
            if (invalid != null) {
                return label + " drawable=" + token + " invalid-transformed:"
                    + invalid;
            }
            if (actual.length != expected.length) {
                return label + " drawable=" + token + " vertex-count "
                    + actual.length + "!=" + expected.length;
            }
            compared[0]++;
            int drifted = 0;
            int firstDrift = -1;
            double sumDx = 0;
            double sumDy = 0;
            float worstDelta = 0;
            for (int i = 0; i < expected.length; i++) {
                final float delta = Math.abs(actual[i] - expected[i]);
                if (delta > BEHAVIOR_TOLERANCE) {
                    drifted++;
                    if (firstDrift < 0) {
                        firstDrift = i;
                    }
                    if (delta > worstDelta) {
                        worstDelta = delta;
                    }
                    final int pair = i & ~1;
                    sumDx += actual[pair] - expected[pair];
                    sumDy += actual[pair + 1] - expected[pair + 1];
                }
            }
            if (drifted > 0) {
                return label + " drawable=" + token + " index=" + firstDrift
                    + " delta=" + worstDelta
                    + " drifted=" + drifted + "/" + expected.length
                    + " meanDelta=(" + (sumDx / drifted) + ","
                    + (sumDy / drifted) + ")";
            }
        }
        for (String key : original.keySet()) {
            if (!guidToToken.containsKey(key)) {
                return label + " guid=" + key + " unmapped-original";
            }
        }
        final Set<String> mapped = new HashSet<>(guidToToken.values());
        for (String key : transformed.keySet()) {
            if (!mapped.contains(key)) {
                return label + " drawable=" + key + " unexpected-transformed";
            }
        }
        return null;
    }

    /**
     * Frame coordinates are xy pairs: a drawable must contribute a non-empty,
     * even-length, all-finite array. NaN or Infinity can never be equivalent —
     * {@code Math.abs(NaN - x) > tolerance} is false, so finiteness is a hard
     * precondition, not part of the delta check.
     */
    private static String invalidPositions(final float[] positions) {
        if (positions.length == 0) {
            return "empty";
        }
        if ((positions.length & 1) != 0) {
            return "odd-length=" + positions.length;
        }
        for (int i = 0; i < positions.length; i++) {
            if (!Float.isFinite(positions[i])) {
                return "non-finite-index=" + i;
            }
        }
        return null;
    }

    /** {@code actual \ expected} for float key sets. */
    private static Set<Float> floatDiff(final Set<Float> actual, final Set<Float> expected) {
        final Set<Float> out = new LinkedHashSet<>(actual);
        expected.forEach(out::remove);
        return out;
    }

    /** Bounded moc3 rejection: a stable key plus a one-line cause detail. */
    private record MocFailure(String key, String detail) {
    }

    /** {@code actual \ expected} preserving iteration order. */
    private static Set<String> diff(final Set<String> actual, final Set<String> expected) {
        final Set<String> out = new LinkedHashSet<>(actual);
        out.removeAll(expected);
        return out;
    }

    /** Both directions of a set mismatch, bounded for the evidence line. */
    private static String setDiffDetail(final Set<String> actual, final Set<String> expected) {
        return "unexpected=" + bounded(diff(actual, expected))
            + ",missing=" + bounded(diff(expected, actual));
    }

    /** Joins ids into a bounded {@code [a,b,...]} rendering. */
    private static String bounded(final Set<?> ids) {
        final StringBuilder out = new StringBuilder("[");
        boolean first = true;
        for (Object id : ids) {
            if (out.length() > 140) {
                out.append(",+").append(ids.size()).append("]");
                return out.toString();
            }
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append(id == null ? "null" : id.toString());
        }
        return out.append(']').toString();
    }

    /**
     * Every {@code FileReferences} entry in a {@code model3.json} must name a file that
     * was itself staged — a reference outside the staged set means the output is
     * incomplete or escaped the task-owned directory.
     */
    private boolean validateModelJson(final Path modelJson, final Set<Path> staged) {
        try {
            final JsonNode root = json.readTree(Files.readAllBytes(modelJson));
            final JsonNode references = root.get("FileReferences");
            if (references == null || !references.isObject()) {
                return false;
            }
            final Path parent = modelJson.getParent();
            final List<String> names = new ArrayList<>();
            references.fields().forEachRemaining(entry -> collectNames(entry.getValue(), names));
            for (String name : names) {
                final Path resolved = parent.resolve(name).toAbsolutePath().normalize();
                if (!staged.contains(resolved)) {
                    return false;
                }
            }
            return true;
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    private static void collectNames(final JsonNode node, final List<String> names) {
        if (node == null) {
            return;
        }
        if (node.isTextual()) {
            names.add(node.asText());
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectNames(child, names));
        }
    }

    /**
     * Setting IDs of a staged {@code physics3.json} — the {@code PhysicsSettings[]}
     * entries' {@code Id} fields. {@code null} when the file cannot be read as a
     * physics3 document at all (malformed JSON or a missing settings array).
     */
    private Set<String> physicsSettingIds(final Path physicsJson) {
        try {
            final JsonNode root = json.readTree(Files.readAllBytes(physicsJson));
            JsonNode settings = root.get("PhysicsSettings");
            if (settings == null) {
                settings = root.get("physicsSettings");
            }
            if (settings == null || !settings.isArray()) {
                return null;
            }
            final Set<String> ids = new LinkedHashSet<>();
            for (JsonNode setting : settings) {
                JsonNode id = setting.get("Id");
                if (id == null) {
                    id = setting.get("id");
                }
                if (id == null || !id.isTextual() || id.asText().isBlank()) {
                    return null;
                }
                ids.add(id.asText());
            }
            return ids;
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    /**
     * Publishes validated staged files to the user's real destination.
     *
     * <p>Publication is all-or-nothing. Every staged file is copied into a sibling
     * scratch directory, every target is preflighted before the first move (an
     * existing non-regular target or an escaping path rejects the whole publish
     * without touching the destination), then each move swaps the previous target
     * aside into the scratch backup before the new file lands. Any failure rolls
     * every touched target back to its prior state — an existing target keeps its
     * old bytes, a previously-absent target is removed again, and directories the
     * publish itself created are removed when they end up empty. The scratch
     * directory is removed on success and after a complete rollback. When rollback
     * itself fails the scratch directory is retained instead — its {@code backups}
     * tree may hold the only surviving copy of the user's original bytes — and the
     * publish exception carries a suppressed {@link RetainedRecoveryException}
     * naming the retained recovery path. A scratch-cleanup failure on the failure path is
     * likewise recorded as suppressed rather than silently masked.</p>
     *
     * @param stagedPick the staged pick (basename carried to the real destination)
     * @param stagedFiles validated staged files
     * @param realPick the user's originally picked destination {@code File}
     * @return the published destination files
     */
    public List<Path> publish(
        final File stagedPick,
        final List<Path> stagedFiles,
        final File realPick
    ) throws IOException {
        return publish(stagedPick, stagedFiles, realPick, moveOp);
    }

    List<Path> publish(
        final File stagedPick,
        final List<Path> stagedFiles,
        final File realPick,
        final MoveOp moveOp
    ) throws IOException {
        Objects.requireNonNull(stagedPick, "stagedPick");
        Objects.requireNonNull(stagedFiles, "stagedFiles");
        Objects.requireNonNull(realPick, "realPick");
        final Path destinationParent = realPick.toPath().toAbsolutePath().normalize()
            .getParent();
        if (destinationParent == null || !Files.isDirectory(destinationParent)
            || !Files.isWritable(destinationParent)) {
            throw new IOException("protected-export destination directory is not writable");
        }
        final Path stagedParent = stagedPick.toPath().toAbsolutePath().normalize()
            .getParent();
        final Path scratch = Files.createTempDirectory(
            destinationParent, ".turboism-publish-");
        final Path incoming = scratch.resolve("incoming");
        final Path backups = scratch.resolve("backups");
        final List<Path> placed = new ArrayList<>();
        final List<Path> restore = new ArrayList<>();
        final List<Path> createdDirs = new ArrayList<>();
        final boolean[] rollbackComplete = {true};
        Throwable failure = null;
        try {
            final List<Path> copies = new ArrayList<>();
            final List<Path> targets = new ArrayList<>();
            for (Path staged : stagedFiles) {
                final Path relative = stagedParent.relativize(
                    staged.toAbsolutePath().normalize());
                final Path copy = incoming.resolve(relative);
                Files.createDirectories(copy.getParent());
                Files.copy(staged, copy, StandardCopyOption.REPLACE_EXISTING);
                copies.add(copy);
                targets.add(targetFor(destinationParent, relative));
            }
            // Preflight every target before the first move: a single unusable
            // destination aborts the publish with the destination untouched.
            for (Path target : targets) {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException(
                        "protected-export destination is not a regular file: " + target);
                }
            }
            for (int i = 0; i < copies.size(); i++) {
                final Path copy = copies.get(i);
                final Path target = targets.get(i);
                createMissingParents(target.getParent(), createdDirs);
                Path backup = null;
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    backup = backups.resolve(incoming.relativize(copy));
                    Files.createDirectories(backup.getParent());
                    moveOp.move(target, backup);
                }
                try {
                    moveOp.move(copy, target);
                } catch (IOException | RuntimeException | Error moveFailure) {
                    if (!restoreTarget(moveOp, target, backup, moveFailure)) {
                        rollbackComplete[0] = false;
                    }
                    throw moveFailure;
                }
                placed.add(target);
                if (backup != null) {
                    restore.add(backup);
                    restore.add(target);
                }
            }
        } catch (Throwable publishFailure) {
            failure = publishFailure;
            if (!rollback(moveOp, placed, restore, createdDirs, failure)) {
                rollbackComplete[0] = false;
            }
        }
        if (!rollbackComplete[0]) {
            // Rollback could not prove every target returned to its prior bytes.
            // The scratch backups may now hold the only surviving originals, so the
            // directory is retained and its path reported instead of deleted.
            if (failure != null) {
                failure.addSuppressed(new RetainedRecoveryException(scratch));
            }
        } else {
            try {
                deleteRecursively(scratch);
            } catch (Throwable cleanup) {
                if (failure == null) {
                    throw cleanup;
                }
                failure.addSuppressed(cleanup);
            }
        }
        if (failure != null) {
            if (failure instanceof IOException io) {
                throw io;
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IOException("protected-export publish failed", failure);
        }
        return List.copyOf(placed);
    }

    /** Resolves a staged relative path to a destination target; escapes reject. */
    private static Path targetFor(final Path destinationParent, final Path relative)
        throws IOException {
        final Path target = destinationParent.resolve(relative).normalize();
        if (!target.startsWith(destinationParent)) {
            throw new IOException(
                "protected-export publish target escapes destination: " + relative);
        }
        return target;
    }

    /**
     * Creates {@code parent} and any missing ancestors, recording the directories
     * this publish created so a rollback can remove the ones it left empty.
     */
    private static void createMissingParents(
        final Path parent,
        final List<Path> createdDirs
    ) throws IOException {
        if (parent == null || Files.isDirectory(parent)) {
            return;
        }
        final List<Path> missing = new ArrayList<>();
        for (Path current = parent; current != null && !Files.exists(current);
             current = current.getParent()) {
            missing.add(0, current);
        }
        Files.createDirectories(parent);
        createdDirs.addAll(missing);
    }

    /**
     * Restores one target after its new file failed to land: when the prior entry was
     * moved aside, move it back; when the new file partially appeared, remove it.
     *
     * @return {@code true} only when every step succeeded — a {@code false} result
     *     means the destination may hold mixed state and the backup tree must be
     *     retained for recovery.
     */
    private static boolean restoreTarget(
        final MoveOp moveOp,
        final Path target,
        final Path backup,
        final Throwable failure
    ) {
        boolean complete = true;
        try {
            deleteIfExists(target);
        } catch (Throwable cleanup) {
            failure.addSuppressed(cleanup);
            complete = false;
        }
        if (backup != null) {
            try {
                moveOp.move(backup, target);
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
                complete = false;
            }
        }
        return complete;
    }

    /**
     * Unwinds a failed publish: every target that received a new file loses it again,
     * every moved-aside original comes back, and directories this publish created are
     * removed while they are empty. Rollback failures are suppressed onto the primary
     * failure so a damaged destination is never reported as cleanly untouched.
     *
     * @return {@code true} only when every unwind step succeeded; {@code false}
     *     means recovery material must be retained rather than deleted.
     */
    private static boolean rollback(
        final MoveOp moveOp,
        final List<Path> placed,
        final List<Path> restore,
        final List<Path> createdDirs,
        final Throwable failure
    ) {
        boolean complete = true;
        for (int i = placed.size() - 1; i >= 0; i--) {
            try {
                deleteIfExists(placed.get(i));
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
                complete = false;
            }
        }
        for (int i = restore.size() - 2; i >= 0; i -= 2) {
            final Path backup = restore.get(i);
            final Path target = restore.get(i + 1);
            try {
                moveOp.move(backup, target);
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
                complete = false;
            }
        }
        for (int i = createdDirs.size() - 1; i >= 0; i--) {
            try {
                Files.delete(createdDirs.get(i));
            } catch (DirectoryNotEmptyException foreign) {
                // Another writer put content there — not publish-owned residue.
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
                complete = false;
            }
        }
        return complete;
    }

    /** Best-effort recursive delete used for staging and publish scratch cleanup. */
    public static void deleteRecursively(final Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            final List<Path> paths = walk.sorted((a, b) -> b.compareTo(a)).toList();
            for (Path path : paths) {
                deleteIfExists(path);
            }
        }
    }

    /**
     * Deletes a single entry, tolerating the read-only attribute the editor puts
     * on saved copies — a Windows JVM refuses to delete read-only files with
     * {@link AccessDeniedException}. Clears the attribute up front when the
     * entry is not writable, and retries once on a denied delete.
     */
    public static boolean deleteIfExists(final Path path) throws IOException {
        if (!Files.exists(path)) {
            return false;
        }
        if (!Files.isWritable(path)) {
            path.toFile().setWritable(true);
        }
        try {
            return Files.deleteIfExists(path);
        } catch (AccessDeniedException denied) {
            if (!path.toFile().setWritable(true)) {
                throw denied;
            }
            return Files.deleteIfExists(path);
        }
    }
}
